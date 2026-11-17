package com.naqaa.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
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

    private val sleepStop = Runnable { player.pause() }

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
                .add(SessionCommand(Cmd.SLEEP, Bundle.EMPTY))
                .add(SessionCommand(Cmd.SILENCE, Bundle.EMPTY))
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
                Cmd.SLEEP -> setSleep(args.getInt("min", 0))
                Cmd.SILENCE -> player.setSkipSilenceEnabled(args.getBoolean("on", false))
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
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.setPlaybackSpeed(prefs.getFloat(Prefs.SPEED, 1f))
        player.setSkipSilenceEnabled(prefs.getBoolean(Prefs.SILENCE, false))
        player.addListener(listener)

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
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    private fun setSleep(min: Int) {
        handler.removeCallbacks(sleepStop)
        // -1 = التوقف عند نهاية الملف الحالي
        player.setPauseAtEndOfMediaItems(min == -1)
        if (min > 0) handler.postDelayed(sleepStop, min * 60_000L)
    }

    private fun savePosition() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isEmpty()) return
        val e = prefs.edit().putString(Prefs.LAST, id)
        val dur = player.duration
        if (dur != C.TIME_UNSET && dur > 0) {
            val pos = player.currentPosition
            e.putLong(Prefs.pos(id), if (pos >= dur - 3000) -1L else pos)
            e.putLong(Prefs.dur(id), dur)
        }
        e.apply()
    }
}
