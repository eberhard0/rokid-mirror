package com.eberhard.rokidmirror.phone

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain running log of what the mirror did, shown on the phone's screen and written to
 * Download/RokidMirror/mirror.log at the end of each run, so a silent stop can be explained.
 */
object Log {
    private val lines = ArrayList<String>()
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    @Synchronized
    fun add(text: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + text
        lines += line
        if (lines.size > 400) lines.removeAt(0)
        _recent.value = lines.takeLast(12)
        android.util.Log.i("RokidMirror", text)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _recent.value = emptyList()
    }

    /** Writes the whole log as a new file; older files stay. */
    @Synchronized
    fun save(context: Context) {
        if (lines.isEmpty()) return
        val text = "Rokid Mirror ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            lines.joinToString("\n") + "\n"
        val name = "mirror " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date()) + ".log"
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RokidMirror")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }
}
