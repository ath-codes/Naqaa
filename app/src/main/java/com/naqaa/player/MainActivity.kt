package com.naqaa.player

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.naqaa.player.databinding.ActivityMainBinding
import com.naqaa.player.databinding.ItemRowBinding
import java.io.File

class Row(
    val folderPath: String?,
    val track: Track?,
    val title: String,
    val sub: String,
    val state: String,
    val header: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var tracks: List<Track> = emptyList()
    private var folders: Map<String, List<Track>> = emptyMap()
    private var currentFolder: String? = null
    private val rows = ArrayList<Row>()
    private val adapter = RowAdapter()

    private var nowId: String? = null
    private var coverId: String? = null
    private var dragging = false
    private var scanning = false
    private var pending: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadLibrary()
        }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateAll()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && b.playerPage.visibility == View.VISIBLE) {
                val dur = c.duration
                if (dur > 0) {
                    b.tvDur.text = fmtTime(dur)
                    if (!dragging) {
                        b.seek.max = dur.toInt()
                        b.seek.progress = c.currentPosition.toInt()
                        b.tvPos.text = fmtTime(c.currentPosition)
                    }
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnBack.setOnClickListener { openFolder(null) }
        b.empty.setOnClickListener { ensurePermission() }
        b.miniBar.setOnClickListener {
            val c = controller
            if (c == null || c.mediaItemCount > 0) showPlayer(true) else resumeLast(true)
        }
        b.miniPlay.setOnClickListener { togglePlay() }
        b.btnCollapse.setOnClickListener { showPlayer(false) }
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnPrev.setOnClickListener { controller?.seekToPrevious() }
        b.btnNext.setOnClickListener { controller?.seekToNext() }
        b.btnBack15.setOnClickListener {
            controller?.let { it.seekTo((it.currentPosition - 15_000).coerceAtLeast(0)) }
        }
        b.btnFwd30.setOnClickListener {
            controller?.let {
                val dur = it.duration
                val target = it.currentPosition + 30_000
                it.seekTo(if (dur > 0) minOf(target, dur) else target)
            }
        }
        b.btnSpeed.setOnClickListener { chooseSpeed() }
        b.btnSilence.setOnClickListener { toggleSilence() }
        b.btnVoice.setOnClickListener { toggleVoice() }
        b.btnAddMark.setOnClickListener { addBookmark() }
        b.btnMarks.setOnClickListener { showBookmarks() }
        refreshSilenceLabel()
        refreshVoiceLabel()

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvPos.text = fmtTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                controller?.seekTo(sb.progress.toLong())
                dragging = false
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.playerPage.visibility == View.VISIBLE -> showPlayer(false)
                    currentFolder != null -> openFolder(null)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        ensurePermission()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(playerListener)
                updateAll()
                pending?.let { p -> pending = null; p() }
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        if (hasAudioPermission()) loadLibrary()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    // ------------------------------------------------------------------ library

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() =
        checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermission() {
        val need = ArrayList<String>()
        if (!hasAudioPermission()) need.add(audioPermission())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) loadLibrary() else permLauncher.launch(need.toTypedArray())
    }

    private fun loadLibrary() {
        if (!hasAudioPermission()) {
            b.empty.text = "اضغط هنا للسماح بالوصول إلى الملفات الصوتية"
            b.empty.visibility = View.VISIBLE
            return
        }
        if (scanning) return
        scanning = true
        Thread {
            val list = Library.scan(this)
            runOnUiThread {
                scanning = false
                applyLibrary(list)
            }
        }.start()
    }

    private fun applyLibrary(list: List<Track>) {
        val sorted = list.sortedWith { x, y -> natCompare(x.title, y.title) }
        tracks = sorted
        folders = sorted.groupBy { it.folder }
            .entries.sortedBy { File(it.key).name.lowercase() }
            .associate { it.key to it.value }
        if (currentFolder != null && !folders.containsKey(currentFolder)) currentFolder = null
        buildRows()
        updateAll()
    }

    private fun shortPath(p: String) = p.removePrefix("/storage/emulated/0/").ifEmpty { "/" }

    private fun stateOf(t: Track): String {
        val pos = prefs.getLong(Prefs.pos(t.id.toString()), 0L)
        return when {
            pos < 0 -> "✓"
            pos > 10_000 && t.durationMs > 0 -> "${(pos * 100 / t.durationMs).coerceIn(1, 99)}%"
            else -> ""
        }
    }

    private fun continueList(): List<Track> =
        tracks.filter {
            val p = prefs.getLong(Prefs.pos(it.id.toString()), 0L)
            p > 10_000 && it.durationMs > 0 && p < it.durationMs - 3000
        }.sortedByDescending { prefs.getLong(Prefs.seen(it.id.toString()), 0L) }.take(3)

    private fun buildRows() {
        rows.clear()
        val cf = currentFolder
        if (cf == null) {
            b.title.text = "المكتبة"
            b.btnBack.visibility = View.GONE
            val cont = continueList()
            if (cont.isNotEmpty()) {
                rows.add(Row(null, null, "تابع الاستماع", "", "", true))
                for (t in cont) rows.add(Row(null, t, t.title, File(t.folder).name, ""))
                rows.add(Row(null, null, "المجلدات", "", "", true))
            }
            val dup = folders.keys.groupingBy { File(it).name.lowercase() }.eachCount()
            for ((path, list) in folders) {
                val name = File(path).name.ifEmpty { "/" }
                val extra = if ((dup[name.lowercase()] ?: 0) > 1) " • ${shortPath(path)}" else ""
                rows.add(Row(path, null, name, "${list.size} ملف$extra", ""))
            }
        } else {
            b.title.text = File(cf).name
            b.btnBack.visibility = View.VISIBLE
            for (t in folders[cf].orEmpty()) {
                rows.add(Row(null, t, t.title, fmtTime(t.durationMs), ""))
            }
        }
        adapter.notifyDataSetChanged()
        if (rows.isEmpty()) {
            b.empty.text = "لا توجد ملفات صوتية على هذا الجهاز"
            b.empty.visibility = View.VISIBLE
        } else {
            b.empty.visibility = View.GONE
        }
    }

    private fun openFolder(path: String?) {
        currentFolder = path
        buildRows()
        b.list.scrollToPosition(0)
    }

    private fun onRow(r: Row) {
        if (r.track == null) {
            openFolder(r.folderPath)
        } else {
            val list = folders[r.track.folder].orEmpty()
            val idx = list.indexOf(r.track)
            if (idx >= 0) play(list, idx, true)
        }
    }

    // ------------------------------------------------------------------ playback

    private fun Track.toItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(File(folder).name)
                    .build()
            )
            .build()

    private fun play(list: List<Track>, index: Int, open: Boolean) {
        val c = controller ?: return
        val t = list[index]
        var start = prefs.getLong(Prefs.pos(t.id.toString()), 0L)
        if (start < 0 || (t.durationMs > 0 && start >= t.durationMs - 3000)) start = 0
        c.setMediaItems(list.map { it.toItem() }, index, start)
        c.prepare()
        c.play()
        if (open) showPlayer(true)
    }

    private fun resumeLast(open: Boolean) {
        val lid = prefs.getString(Prefs.LAST, null) ?: return
        val t = tracks.firstOrNull { it.id.toString() == lid } ?: return
        val list = folders[t.folder] ?: return
        val idx = list.indexOf(t)
        if (idx >= 0) play(list, idx, open)
    }

    private fun togglePlay() {
        val c = controller
        if (c == null) {
            pending = { togglePlay() }
            return
        }
        if (c.mediaItemCount == 0) {
            resumeLast(false)
            return
        }
        if (c.isPlaying) {
            c.pause()
            return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        else if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
        c.play()
    }

    private fun showPlayer(show: Boolean) {
        b.playerPage.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) buildRows()
    }

    // ------------------------------------------------------------------ options

    private fun speedLabel(s: Float): String {
        val t = if (s % 1f == 0f) "${s.toInt()}.0" else s.toString()
        return "$t×"
    }

    private fun chooseSpeed() {
        val c = controller ?: return
        val vals = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = vals.map { speedLabel(it) }.toTypedArray()
        val cur = vals.indexOfFirst { it == c.playbackParameters.speed }
        AlertDialog.Builder(this)
            .setTitle("سرعة التشغيل")
            .setSingleChoiceItems(labels, cur) { d, i ->
                c.setPlaybackSpeed(vals[i])
                prefs.edit().putFloat(Prefs.SPEED, vals[i]).apply()
                d.dismiss()
            }
            .show()
    }

    private fun toggleSilence() {
        val on = !prefs.getBoolean(Prefs.SILENCE, false)
        prefs.edit().putBoolean(Prefs.SILENCE, on).apply()
        val args = Bundle().apply { putBoolean("on", on) }
        controller?.sendCustomCommand(SessionCommand(Cmd.SILENCE, Bundle.EMPTY), args)
        refreshSilenceLabel()
    }

    private fun refreshSilenceLabel() {
        val on = prefs.getBoolean(Prefs.SILENCE, false)
        b.btnSilence.text = "تخطي الصمت"
        b.btnSilence.isActivated = on
    }

    private fun toggleVoice() {
        val on = !prefs.getBoolean(Prefs.VOICE, false)
        prefs.edit().putBoolean(Prefs.VOICE, on).apply()
        val args = Bundle().apply { putBoolean("on", on) }
        controller?.sendCustomCommand(SessionCommand(Cmd.VOICE, Bundle.EMPTY), args)
        refreshVoiceLabel()
    }

    private fun refreshVoiceLabel() {
        val on = prefs.getBoolean(Prefs.VOICE, false)
        b.btnVoice.text = "وضوح الصوت"
        b.btnVoice.isActivated = on
    }

    // ------------------------------------------------------------------ bookmarks

    private fun loadBookmarks(id: String): List<Pair<Long, String>> =
        (prefs.getString("bm_$id", "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull { l ->
            val p = l.split("|", limit = 2)
            val ms = p[0].toLongOrNull() ?: return@mapNotNull null
            ms to p.getOrElse(1) { "" }
        }.sortedBy { it.first }

    private fun saveBookmarks(id: String, list: List<Pair<Long, String>>) {
        prefs.edit().putString("bm_$id", list.joinToString("\n") { "${it.first}|${it.second}" }).apply()
    }

    private fun refreshMarksLabel(id: String?) {
        val n = if (id == null) 0 else loadBookmarks(id).size
        b.btnMarks.text = if (n > 0) "العلامات ($n)" else "العلامات"
    }

    private fun addBookmark() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val pos = c.currentPosition
        val et = EditText(this)
        et.hint = "ملاحظة (اختياري)"
        et.setSingleLine()
        val d = AlertDialog.Builder(this)
            .setTitle("علامة عند ${fmtTime(pos)}")
            .setPositiveButton("حفظ") { _, _ ->
                val list = loadBookmarks(id).toMutableList()
                list.add(pos to et.text.toString().replace('\n', ' ').replace("|", " ").trim())
                saveBookmarks(id, list.sortedBy { it.first })
                refreshMarksLabel(id)
                Toast.makeText(this, "تمت إضافة العلامة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .create()
        d.setView(et, 48, 16, 48, 0)
        d.show()
    }

    private fun showBookmarks() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val list = loadBookmarks(id)
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("العلامات")
                .setMessage("لا توجد علامات في هذا الملف. اضغط «إضافة علامة» أثناء التشغيل لحفظ لحظة مهمة.")
                .setPositiveButton("حسنًا", null)
                .show()
            return
        }
        val labels = list.map { "${fmtTime(it.first)}  —  ${it.second.ifEmpty { "علامة" }}" }.toTypedArray()
        val d = AlertDialog.Builder(this)
            .setTitle("العلامات (اضغط للانتقال، ومطولًا للحذف)")
            .setItems(labels) { _, i -> c.seekTo(list[i].first) }
            .create()
        d.setOnShowListener {
            d.listView.setOnItemLongClickListener { _, _, pos, _ ->
                d.dismiss()
                AlertDialog.Builder(this)
                    .setMessage("حذف هذه العلامة؟")
                    .setPositiveButton("حذف") { _, _ ->
                        saveBookmarks(id, list.filterIndexed { idx, _ -> idx != pos })
                        refreshMarksLabel(id)
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                true
            }
        }
        d.show()
    }

    // ------------------------------------------------------------------ UI sync

    private fun updateAll() {
        val c = controller
        val item = c?.currentMediaItem
        var title: String? = null
        var sub: String? = null
        var id: String? = null
        if (item != null) {
            title = item.mediaMetadata.title?.toString()
            sub = item.mediaMetadata.artist?.toString()
            id = item.mediaId
        } else {
            val lid = prefs.getString(Prefs.LAST, null)
            val last = tracks.firstOrNull { it.id.toString() == lid }
            if (last != null) {
                title = last.title
                sub = File(last.folder).name
                id = last.id.toString()
            }
        }
        b.miniBar.visibility = if (title != null) View.VISIBLE else View.GONE
        b.miniTitle.text = title ?: ""
        b.pTitle.text = title ?: ""
        b.pSub.text = sub ?: ""

        val icon = if (c?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        b.miniPlay.setImageResource(icon)
        b.btnPlay.setImageResource(icon)

        if (c != null) {
            b.btnSpeed.text = speedLabel(c.playbackParameters.speed)
            b.btnSpeed.isActivated = c.playbackParameters.speed != 1f
        }
        refreshMarksLabel(id)
        if (id != null) loadCover(id)
        if (id != nowId) {
            nowId = id
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadCover(id: String) {
        if (id == coverId) return
        coverId = id
        b.cover.setImageResource(R.drawable.cover_placeholder)
        val trackId = id.toLongOrNull() ?: return
        Thread {
            val bmp = readCover(trackId)
            runOnUiThread {
                if (coverId == id && bmp != null) {
                    val d = RoundedBitmapDrawableFactory.create(resources, bmp)
                    d.cornerRadius = minOf(bmp.width, bmp.height) * 0.05f
                    b.cover.setImageDrawable(d)
                }
            }
        }.start()
    }

    private fun readCover(trackId: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(
                this,
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
            )
            val bytes = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / s > 1000 || o.outHeight / s > 1000) s *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = s }
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ adapter

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(val v: ItemRowBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.v.title.text = r.title
            if (r.header) {
                h.v.icon.visibility = View.GONE
                h.v.sub.visibility = View.GONE
                h.v.state.text = ""
                h.v.title.textSize = 13f
                h.v.title.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                h.v.root.isClickable = false
                h.v.root.setOnClickListener(null)
                return
            }
            h.v.icon.visibility = View.VISIBLE
            h.v.sub.visibility = View.VISIBLE
            h.v.title.textSize = 16f
            h.v.root.isClickable = true
            h.v.sub.text = r.sub
            h.v.state.text = if (r.track != null) stateOf(r.track) else r.state
            h.v.icon.setImageResource(if (r.track == null) R.drawable.ic_folder else R.drawable.ic_note)
            val playingNow = r.track != null && r.track.id.toString() == nowId
            h.v.title.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (playingNow) R.color.accent else R.color.text)
            )
            h.v.root.setOnClickListener { onRow(r) }
        }
    }
}
