package com.eberhard.rokidmirror.phone

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * When the app dies, the stack trace lands in Download/RokidMirror/crash <time>.txt so it can be read
 * without adb (the glasses and the phone both make logcat hard to reach). Only the error is
 * written, never what the user was watching.
 */
object CrashReport {
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = "Rokid Mirror ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "thread ${thread.name}\n\n$trace"
        val name = "crash " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RokidMirror")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } else {
            val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RokidMirror")
            dir.mkdirs()
            java.io.File(dir, name).writeText(text)
        }
    }
}
