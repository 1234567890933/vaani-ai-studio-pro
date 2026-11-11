package ai.hindidub.core

import ai.hindidub.core.Diag
import java.io.File

/**
 * Output library.
 *
 * Every dub lands in `Android/data/ai.hindidub/files/Movies/dubbed/`. Without a list, those
 * files pile up invisibly and fill the phone — this reads the folder, reports sizes, and
 * deletes. No database: the filesystem *is* the database, and a file the user deleted in a
 * file manager simply disappears from the list.
 */
class StorageManager(private val root: File) {

    data class Output(val file: File, val name: String, val bytes: Long, val modified: Long)

    fun outputs(): List<Output> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Output(it, it.name, it.length(), it.lastModified()) }
            ?: emptyList()
    }

    fun totalBytes(): Long = outputs().sumOf { it.bytes }

    fun delete(output: Output): Boolean {
        val ok = output.file.delete()
        if (ok) Diag.record(TAG, "deleted ${output.name}")
        return ok
    }

    fun deleteAll(): Int = outputs().count { delete(it) }

    companion object {
        private const val TAG = "Storage"

        fun human(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
