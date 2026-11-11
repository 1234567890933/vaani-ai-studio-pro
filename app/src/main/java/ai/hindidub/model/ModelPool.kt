package ai.hindidub.model

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Disk manager for the model pool.
 *
 * Two kinds of file live here: downloaded ones and the starter model that ships inside the
 * APK (installed from assets on first run, then treated exactly like a download). Because
 * every [ModelFile] carries its real byte size, "will this fit" is arithmetic rather than a
 * guess, and eviction never touches a group the current job needs.
 */
class ModelPool(
    private val context: Context,
    val root: File
) {

    /** User-adjustable quota; the pool can hold ~10 GB if the device has the space. */
    var quotaBytes: Long = 8L * 1024 * 1024 * 1024

    fun fileFor(f: ModelFile): File =
        if (f.isBundled && f.path.isBlank()) File(root, "bundled/${f.assetPath}")
        else File(root, f.path)

    fun installedBytes(group: ModelGroup): Long =
        group.files.sumOf { val f = fileFor(it); if (f.exists()) f.length() else 0L }

    fun totalInstalledBytes(): Long = ModelCatalog.all.sumOf { installedBytes(it) }

    fun isInstalled(group: ModelGroup): Boolean =
        group.files.all { fileFor(it).exists() && fileFor(it).length() > 0 }

    fun missingBytes(group: ModelGroup): Long =
        group.files.sumOf {
            val f = fileFor(it)
            val have = if (f.exists()) f.length() else 0L
            (it.bytes - have).coerceAtLeast(0L)
        }

    fun freeSpaceBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    fun evictToFit(needed: Long, keep: Set<String>): List<String> {
        val evicted = ArrayList<String>()
        var free = freeSpaceBytes()
        if (free >= needed) return evicted

        val candidates = ModelCatalog.all
            .filter { it.id !in keep && !it.isBundledOnly && isInstalled(it) }
            .sortedBy { lastUsed(it.id) }

        for (g in candidates) {
            if (free >= needed) break
            delete(g)
            evicted.add(g.id)
            free = freeSpaceBytes()
            Log.i(TAG, "evicted ${g.id}")
        }
        return evicted
    }

    fun delete(group: ModelGroup) {
        group.files.filter { !it.isBundled }.forEach { fileFor(it).delete() }
        group.files.mapNotNull { fileFor(it).parentFile }.distinct().forEach { d ->
            if (d.isDirectory && d.listFiles()?.isEmpty() == true) d.delete()
        }
    }

    fun touch(id: String) {
        val marker = File(root, ".used_$id")
        marker.parentFile?.mkdirs()
        marker.writeText(System.currentTimeMillis().toString())
    }

    fun lastUsed(id: String): Long {
        val marker = File(root, ".used_$id")
        return if (marker.exists()) marker.readText().toLongOrNull() ?: 0L else 0L
    }

    fun summary(): String {
        val used = totalInstalledBytes() / (1024 * 1024)
        val free = freeSpaceBytes() / (1024 * 1024)
        val quota = quotaBytes / (1024 * 1024)
        val pool = ModelCatalog.poolBytes() / (1024 * 1024)
        return "installed ${used} MB of ${pool} MB pool · quota ${quota} MB · free ${free} MB"
    }

    private companion object {
        const val TAG = "ModelPool"
    }
}
