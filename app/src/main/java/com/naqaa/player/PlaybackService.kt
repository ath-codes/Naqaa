package com.naqaa.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var eq: Equalizer? = null

    private val saver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(saver)
            if (isPlaying) handler.postDelayed(saver, 5000) else savePosition()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            try { eq?.release() } catch (_: Exception) {}
            eq = null
            if (prefs.getBoolean(Prefs.VOICE, false)) applyVoice(true)
        }

        // رجوع تلقائي بضع ثوانٍ بعد توقف طويل (أكثر من دقيقة)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                prefs.edit().putLong(Prefs.PAUSED_AT, System.currentTimeMillis()).apply()
            } else {
                val at = prefs.getLong(Prefs.PAUSED_AT, 0L)
                if (at > 0L) {
                    prefs.edit().putLong(Prefs.PAUSED_AT, 0L).apply()
                    val pos = player.currentPosition
                    if (System.currentTimeMillis() - at > 60_000L && pos > 4000L &&
                        player.playbackState != Player.STATE_ENDED
                    ) {
                        player.seekTo((pos - 4000L).coerceAtLeast(0L))
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) savePosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val oldId = oldPosition.mediaItem?.mediaId
            if (!oldId.isNullOrEmpty() && oldId != newPosition.mediaItem?.mediaId) {
                val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                prefs.edit()
                    .putLong(Prefs.pos(oldId), if (finished) -1L else oldPosition.positionMs)
                    .apply()
            }
            savePosition()
        }
    }

    private val callback = object : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(Cmd.SILENCE, Bundle.EMPTY))
                .add(SessionCommand(Cmd.VOICE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                Cmd.SILENCE -> player.setSkipSilenceEnabled(args.getBoolean("on", false))
                Cmd.VOICE -> applyVoice(args.getBoolean("on", false))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // الواجهة ترسل mediaId فقط (رقم الملف في MediaStore) والخدمة تبني الرابط
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mediaItems.map { item ->
                val id = item.mediaId.toLongOrNull()
                if (id != null) {
                    item.buildUpon()
                        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
                        .build()
                } else item
            }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        // إعدادات نقية: بدون أي معالجة صوتية، مع إيقاف تلقائي عند فصل السماعة
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.setPlaybackSpeed(prefs.getFloat(Prefs.SPEED, 1f))
        player.setSkipSilenceEnabled(prefs.getBoolean(Prefs.SILENCE, false))
        player.addListener(listener)
        if (prefs.getBoolean(Prefs.VOICE, false)) applyVoice(true)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        try { eq?.release() } catch (_: Exception) {}
        eq = null
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    /** وضوح الصوت: خفض الدمدمة المنخفضة ورفع نطاق الكلام (1.5-6 كيلوهرتز) */
    private fun applyVoice(on: Boolean) {
        try {
            if (!on) {
                eq?.setEnabled(false)
                return
            }
            val sid = player.audioSessionId
            if (sid == C.AUDIO_SESSION_ID_UNSET) return
            val e = eq ?: Equalizer(0, sid).also { eq = it }
            val lo = e.bandLevelRange[0].toInt()
            val hi = e.bandLevelRange[1].toInt()
            for (band in 0 until e.numberOfBands.toInt()) {
                val hz = e.getCenterFreq(band.toShort()) / 1000
                val db = when {
                    hz < 120 -> -5
                    hz < 400 -> -2
                    hz < 1500 -> 0
                    hz < 6000 -> 3
                    else -> 0
                }
                e.setBandLevel(band.toShort(), (db * 100).coerceIn(lo, hi).toShort())
            }
            e.setEnabled(true)
        } catch (_: Exception) {
        }
    }

    private fun savePosition() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isEmpty()) return
        val e = prefs.edit().putString(Prefs.LAST, id).putLong(Prefs.seen(id), System.currentTimeMillis())
        val dur = player.duration
        if (dur != C.TIME_UNSET && dur > 0) {
            val pos = player.currentPosition
            e.putLong(Prefs.pos(id), if (pos >= dur - 3000) -1L else pos)
            e.putLong(Prefs.dur(id), dur)
        }
        e.apply()
    }
}
