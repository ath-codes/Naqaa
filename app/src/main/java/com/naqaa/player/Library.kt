package com.naqaa.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as AM
import java.io.File
import java.util.Locale

object Prefs {
    const val NAME = "naqaa"
    const val SPEED = "speed"
    const val SILENCE = "silence"
    const val LAST = "last_id"
    const val VOICE = "voice"
    fun pos(id: String) = "pos_$id"
    fun dur(id: String) = "dur_$id"
}

object Cmd {
    const val SLEEP = "naqaa.SLEEP"
    const val SILENCE = "naqaa.SILENCE"
    const val VOICE = "naqaa.VOICE"
}

data class Track(
    val id: Long,
    val title: String,
    val folder: String,
    val durationMs: Long
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

object Library {

    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(AM._ID, AM.DISPLAY_NAME, AM.DURATION, AM.DATA)
        val sel = "${AM.IS_RINGTONE}=0 AND ${AM.IS_NOTIFICATION}=0 AND ${AM.IS_ALARM}=0 AND ${AM.DURATION}>=3000"
        try {
            ctx.contentResolver.query(AM.EXTERNAL_CONTENT_URI, proj, sel, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(AM._ID)
                val iName = c.getColumnIndexOrThrow(AM.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(AM.DURATION)
                val iData = c.getColumnIndexOrThrow(AM.DATA)
                while (c.moveToNext()) {
                    val path = c.getString(iData) ?: ""
                    val name = c.getString(iName) ?: File(path).name
                    out.add(
                        Track(
                            id = c.getLong(iId),
                            title = cleanTitle(name.substringBeforeLast('.')),
                            folder = File(path).parent ?: "",
                            durationMs = c.getLong(iDur)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** تنظيف اسم الملف: يزيل _ وزوائد مثل 128 kbps */
fun cleanTitle(raw: String): String {
    var s = raw.replace(Regex("[\\s_-]*\\d{2,3}\\s*kbps", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("\\s+_+\\s*|\\s*_+\\s+"), " - ").replace('_', ' ')
    s = s.replace(Regex("\\s+"), " ").trim().trim('-').trim()
    return s.ifEmpty { raw }
}

/** ترتيب طبيعي: "درس 2" قبل "درس 10" */
fun natCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var si = i
            while (si < a.length && a[si].isDigit()) si++
            var sj = j
            while (sj < b.length && b[sj].isDigit()) sj++
            val na = a.substring(i, si).trimStart('0')
            val nb = b.substring(j, sj).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = si
            j = sj
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

fun fmtTime(ms: Long): String {
    val s = if (ms < 0) 0 else ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}
