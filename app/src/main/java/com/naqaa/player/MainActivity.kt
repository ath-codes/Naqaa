package com.naqaa.player

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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
import com.naqaa.player.databinding.DialogSoundBinding
import com.naqaa.player.databinding.ItemRowBinding
import java.io.File

class Row(
    val folderPath: String?,
    val track: Track?,
    val title: String,
    val sub: String,
    val state: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var allTracks: List<Track> = emptyList()
    private var hiddenFolders: List<String> = emptyList()
    private var searchOpen = false
    private var query = ""
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
    private var showRemain = false

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
                if (dur > 0 && !dragging) {
                    b.seek.max = dur.toInt()
                    b.seek.progress = c.currentPosition.toInt()
                    b.tvPos.text = fmtTime(c.currentPosition)
                    setDurText(c.currentPosition, dur)
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
        b.btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                if (searchOpen) buildRows()
            }
        })
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
        b.btnVoice.setOnClickListener { showSoundDialog() }
        b.btnRepeat.setOnClickListener { toggleRepeat() }
        showRemain = prefs.getBoolean(Prefs.REMAIN, false)
        b.tvDur.setOnClickListener {
            showRemain = !showRemain
            prefs.edit().putBoolean(Prefs.REMAIN, showRemain).apply()
            val c = controller
            val d = c?.duration ?: 0L
            if (c != null && d > 0) setDurText(if (dragging) b.seek.progress.toLong() else c.currentPosition, d)
        }
        b.btnAddMark.setOnClickListener { addBookmark() }
        b.btnMarks.setOnClickListener { showBookmarks() }
        refreshSilenceLabel()
        refreshSoundLabel()

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvPos.text = fmtTime(progress.toLong())
                    val d = controller?.duration ?: 0L
                    if (d > 0) setDurText(progress.toLong(), d)
                }
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
                    searchOpen -> closeSearch()
                    currentFolder != null -> openFolder(null)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        ensurePermission()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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

    private fun hiddenSet(): Set<String> = prefs.getStringSet(Prefs.HIDDEN, emptySet()) ?: emptySet()

    private fun applyLibrary(list: List<Track>) {
        val hidden = hiddenSet()
        val sorted = list.sortedWith { x, y -> natCompare(x.title, y.title) }
        allTracks = sorted
        tracks = sorted.filter { it.folder !in hidden }
        folders = tracks.groupBy { it.folder }
            .entries.sortedBy { File(it.key).name.lowercase() }
            .associate { it.key to it.value }
        hiddenFolders = sorted.map { it.folder }.distinct().filter { it in hidden }
            .sortedBy { File(it).name.lowercase() }
        val cf = currentFolder
        if (cf != null && cf != F_CONTINUE && !folders.containsKey(cf)) currentFolder = null
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
        }.sortedByDescending { prefs.getLong(Prefs.seen(it.id.toString()), 0L) }

    private fun matches(t: Track, toks: List<String>): Boolean {
        val hay = normAr(t.title + " " + File(t.folder).name)
        return toks.all { hay.contains(it) }
    }

    private fun buildRows() {
        rows.clear()
        val cf = currentFolder
        var emptyMsg = "لا توجد ملفات صوتية على هذا الجهاز"
        if (searchOpen && query.isNotBlank()) {
            val toks = normAr(query).split(' ').filter { it.isNotEmpty() }
            for (t in tracks) if (matches(t, toks)) rows.add(Row(null, t, t.title, File(t.folder).name, ""))
            emptyMsg = "لا توجد نتائج"
        } else if (cf == null) {
            b.title.text = "المكتبة"
            b.btnBack.visibility = View.GONE
            val cont = continueList()
            if (cont.isNotEmpty()) rows.add(Row(F_CONTINUE, null, "تابع الاستماع", "${cont.size} ملف", ""))
            val dup = folders.keys.groupingBy { File(it).name.lowercase() }.eachCount()
            for ((path, list) in folders) {
                val name = File(path).name.ifEmpty { "/" }
                val extra = if ((dup[name.lowercase()] ?: 0) > 1) " • ${shortPath(path)}" else ""
                rows.add(Row(path, null, name, "${list.size} ملف$extra", ""))
            }
            if (hiddenFolders.isNotEmpty()) {
                rows.add(Row(F_HIDDEN, null, "مجلدات مخفية", "${hiddenFolders.size} مجلد", ""))
            }
        } else if (cf == F_CONTINUE) {
            b.title.text = "تابع الاستماع"
            b.btnBack.visibility = View.VISIBLE
            for (t in continueList()) rows.add(Row(null, t, t.title, File(t.folder).name, ""))
            emptyMsg = "لا توجد ملفات غير منتهية"
        } else {
            b.title.text = File(cf).name
            b.btnBack.visibility = View.VISIBLE
            for (t in folders[cf].orEmpty()) {
                rows.add(Row(null, t, t.title, fmtTime(t.durationMs), ""))
            }
        }
        adapter.notifyDataSetChanged()
        if (rows.isEmpty()) {
            b.empty.text = emptyMsg
            b.empty.visibility = View.VISIBLE
        } else {
            b.empty.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ search / hide

    private fun openSearch() {
        searchOpen = true
        b.title.visibility = View.GONE
        b.search.visibility = View.VISIBLE
        b.btnSearch.setImageResource(R.drawable.ic_close)
        b.btnSearch.contentDescription = "إغلاق البحث"
        b.search.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.search, InputMethodManager.SHOW_IMPLICIT)
        buildRows()
    }

    private fun closeSearch() {
        searchOpen = false
        query = ""
        b.search.setText("")
        b.search.visibility = View.GONE
        b.title.visibility = View.VISIBLE
        b.btnSearch.setImageResource(R.drawable.ic_search)
        b.btnSearch.contentDescription = "بحث"
        hideKeyboard()
        buildRows()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.search.windowToken, 0)
    }

    private fun confirmHide(path: String) {
        AlertDialog.Builder(this)
            .setMessage("إخفاء المجلد «${File(path).name}» من المكتبة؟\nيمكنك إظهاره لاحقًا من «مجلدات مخفية».")
            .setPositiveButton("إخفاء") { _, _ ->
                prefs.edit().putStringSet(Prefs.HIDDEN, HashSet(hiddenSet() + path)).apply()
                applyLibrary(allTracks)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showHidden() {
        val labels = hiddenFolders.map { "${File(it).name}  •  ${shortPath(it)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اضغط على مجلد لإظهاره")
            .setItems(labels) { _, i ->
                prefs.edit().putStringSet(Prefs.HIDDEN, HashSet(hiddenSet() - hiddenFolders[i])).apply()
                applyLibrary(allTracks)
            }
            .show()
    }

    // ------------------------------------------------------------------ open external file

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val uri = i.data ?: return
        i.action = null
        if (controller != null) playExternal(uri) else pending = { playExternal(uri) }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun playExternal(uri: Uri) {
        val c = controller ?: return
        val id = "ext_" + Integer.toHexString(uri.toString().hashCode())
        val name = displayName(uri) ?: uri.lastPathSegment ?: "ملف صوتي"
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(cleanTitle(name.substringBeforeLast('.')))
                    .setArtist("ملف خارجي")
                    .build()
            )
            .build()
        val start = prefs.getLong(Prefs.pos(id), 0L).coerceAtLeast(0L)
        c.setMediaItems(listOf(item), 0, start)
        c.prepare()
        c.play()
        showPlayer(true)
    }

    private fun openFolder(path: String?) {
        currentFolder = path
        buildRows()
        b.list.scrollToPosition(0)
    }

    private fun onRow(r: Row) {
        hideKeyboard()
        if (r.track == null) {
            if (r.folderPath == F_HIDDEN) showHidden() else openFolder(r.folderPath)
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

    private fun sendVoice(on: Boolean) {
        prefs.edit().putBoolean(Prefs.VOICE, on).apply()
        val args = Bundle().apply { putBoolean("on", on) }
        controller?.sendCustomCommand(SessionCommand(Cmd.VOICE, Bundle.EMPTY), args)
        refreshSoundLabel()
    }

    private fun sendBoost(db: Int) {
        prefs.edit().putInt(Prefs.BOOST, db).apply()
        val args = Bundle().apply { putInt("db", db) }
        controller?.sendCustomCommand(SessionCommand(Cmd.BOOST, Bundle.EMPTY), args)
        refreshSoundLabel()
    }

    private fun refreshSoundLabel() {
        b.btnVoice.text = "الصوت"
        b.btnVoice.isActivated =
            prefs.getBoolean(Prefs.VOICE, false) || prefs.getInt(Prefs.BOOST, 0) > 0
    }

    private fun showSoundDialog() {
        val d = DialogSoundBinding.inflate(layoutInflater)
        d.swVoice.isChecked = prefs.getBoolean(Prefs.VOICE, false)
        when (prefs.getInt(Prefs.BOOST, 0)) {
            4 -> d.rb4.isChecked = true
            8 -> d.rb8.isChecked = true
            else -> d.rbOff.isChecked = true
        }
        d.swVoice.setOnCheckedChangeListener { _, on -> sendVoice(on) }
        d.rgBoost.setOnCheckedChangeListener { _, checkedId ->
            val db = when (checkedId) {
                R.id.rb4 -> 4
                R.id.rb8 -> 8
                else -> 0
            }
            sendBoost(db)
        }
        AlertDialog.Builder(this)
            .setTitle("الصوت")
            .setView(d.root)
            .setPositiveButton("تم", null)
            .show()
    }

    // تكرار المقطع الحالي مرة واحدة بعد انتهائه (الخدمة تعيد الوضع العادي تلقائيًا)
    private fun toggleRepeat() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val on = c.repeatMode != Player.REPEAT_MODE_ONE
        c.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        b.btnRepeat.isActivated = on
        if (on) Toast.makeText(this, "سيُعاد هذا المقطع مرة واحدة بعد انتهائه", Toast.LENGTH_SHORT).show()
    }

    private fun setDurText(pos: Long, dur: Long) {
        b.tvDur.text = if (showRemain) "−" + fmtTime((dur - pos).coerceAtLeast(0L)) else fmtTime(dur)
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
            b.btnRepeat.isActivated = c.repeatMode == Player.REPEAT_MODE_ONE
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
        val trackId = id.toLongOrNull()
        if (trackId == null) {
            showCover(null)
            return
        }
        Thread {
            val bmp = readCover(trackId)
            runOnUiThread { if (coverId == id) showCover(bmp) }
        }.start()
    }

    /** غلاف حقيقي: يظهر 240dp والعنوان بحجمه العادي. بدون غلاف: يختفي المربع والعنوان يكبر ويتوسط. */
    private fun showCover(bmp: Bitmap?) {
        if (bmp != null) {
            val d = RoundedBitmapDrawableFactory.create(resources, bmp)
            d.cornerRadius = minOf(bmp.width, bmp.height) * 0.05f
            b.cover.setImageDrawable(d)
        } else {
            b.cover.setImageDrawable(null)
        }
        b.coverBox.visibility = if (bmp != null) View.VISIBLE else View.GONE
        val lp = b.pTitle.layoutParams as LinearLayout.LayoutParams
        if (bmp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            b.pTitle.maxLines = 4
            b.pTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        } else {
            lp.height = 0
            lp.weight = 1f
            b.pTitle.maxLines = 8
            b.pTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        b.pTitle.layoutParams = lp
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
            h.v.sub.text = r.sub
            h.v.state.text = if (r.track != null) stateOf(r.track) else r.state
            h.v.icon.setImageResource(
                when {
                    r.track != null -> R.drawable.ic_note
                    r.folderPath == F_CONTINUE -> R.drawable.ic_continue
                    else -> R.drawable.ic_folder
                }
            )
            val playingNow = r.track != null && r.track.id.toString() == nowId
            h.v.title.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (playingNow) R.color.accent else R.color.text)
            )
            h.v.root.setOnClickListener { onRow(r) }
            val fp = r.folderPath
            if (fp != null && fp != F_CONTINUE && fp != F_HIDDEN) {
                h.v.root.setOnLongClickListener { confirmHide(fp); true }
            } else {
                h.v.root.setOnLongClickListener(null)
                h.v.root.isLongClickable = false
            }
        }
    }
}
