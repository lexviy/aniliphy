package com.aniliphy.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What was kept of the last time the app closed by itself. */
data class Crash(val time: Long, val summary: String, val report: String, val seen: Boolean)

/**
 * Keeps the last crash in a file on the phone, so it can be read and shared from Settings › About.
 * Nothing leaves the phone unless the person shares it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SEEN_FILE = "last_crash.seen"
    private const val MAX_CHARS = 32_000

    private fun file(context: Context) = File(context.filesDir, FILE)
    private fun seenFile(context: Context) = File(context.filesDir, SEEN_FILE)

    /** Called once per process, before anything else can crash. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(app).writeText(report(error, thread.name, System.currentTimeMillis(), describeApp(app)))
                seenFile(app).delete()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): Crash? {
        val text = file(context).takeIf { it.isFile }?.readTextOrNull() ?: return null
        return parse(text, seen = seenFile(context).exists())
    }

    /** The crash was told about once (on the next start), so it isn't told again. */
    fun markSeen(context: Context) {
        runCatching { seenFile(context).createNewFile() }
    }

    fun clear(context: Context) {
        file(context).delete()
        seenFile(context).delete()
    }

    private fun describeApp(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")
        return "aniliphy $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    internal fun report(error: Throwable, thread: String, time: Long, app: String): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("time: $time")
            appendLine("when: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(time))}")
            appendLine("app: $app")
            appendLine("thread: $thread")
            appendLine("summary: ${summaryOf(error)}")
            appendLine()
            append(stack)
        }
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n… (cut)" else text
    }

    /** The innermost cause is usually the one that says what went wrong. */
    internal fun summaryOf(error: Throwable): String {
        var root = error
        val visited = mutableSetOf<Throwable>()
        while (root.cause != null && visited.add(root)) root = root.cause!!
        val name = root.javaClass.simpleName.ifEmpty { root.javaClass.name }
        return root.message?.lineSequence()?.firstOrNull()?.take(160)?.let { "$name: $it" } ?: name
    }

    internal fun parse(text: String, seen: Boolean): Crash? {
        val header = text.lineSequence().takeWhile { it.isNotBlank() }
            .mapNotNull { line -> line.indexOf(": ").takeIf { it > 0 }?.let { line.take(it) to line.drop(it + 2) } }
            .toMap()
        val time = header["time"]?.toLongOrNull() ?: return null
        return Crash(time, header["summary"].orEmpty(), text, seen)
    }
}
