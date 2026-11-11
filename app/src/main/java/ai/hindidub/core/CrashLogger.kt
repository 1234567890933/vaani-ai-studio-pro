package ai.hindidub.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash handler.
 *
 * An offline dubbing app runs long native jobs; when something throws, the user sees "app has
 * stopped" and nothing else. This writes the stack trace plus device details to a file the
 * user can share, then hands control back to the platform handler so the process still dies
 * normally. It never swallows an exception.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "crash_last.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = context.filesDir
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(dir, thread, throwable) }
                .onFailure { Log.w(TAG, "could not write crash log: ${it.message}") }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun lastCrash(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(dir: File, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("time: $stamp")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine(sw.toString())
            appendLine()
            appendLine("engine trace (last events):")
            appendLine(Diag.dump(80))
        }
        File(dir, FILE_NAME).writeText(text)
        Log.e(TAG, "crash written to $FILE_NAME")
    }
}
