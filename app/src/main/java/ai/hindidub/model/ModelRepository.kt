package ai.hindidub.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Installs and downloads model files.
 *
 * Three behaviours that matter on a phone:
 *  * **bundled files** (the starter Whisper model) are copied out of the APK assets once, so
 *    a fresh install can dub a first clip with no download at all;
 *  * downloads resume with HTTP Range and are written to `<name>.part`, so a dropped
 *    connection never leaves a half file that the engine would try to load;
 *  * space is checked against real byte counts, with LRU eviction of unused groups.
 */
class ModelRepository(
    private val context: Context,
    private val pool: ModelPool
) {

    suspend fun installBundled(group: ModelGroup): Boolean = withContext(Dispatchers.IO) {
        if (pool.isInstalled(group)) return@withContext true
        var ok = true
        for (f in group.files) {
            if (!f.isBundled) continue
            val dest = pool.fileFor(f)
            dest.parentFile?.mkdirs()
            try {
                context.assets.open(f.assetPath!!).use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                }
                Log.i(TAG, "installed bundled ${dest.name} (${dest.length() / (1024 * 1024)} MB)")
            } catch (t: Throwable) {
                Log.w(TAG, "bundled install failed: ${t.message}")
                ok = false
            }
        }
        ok
    }

    suspend fun downloadGroup(
        group: ModelGroup,
        onProgress: (downloaded: Long, total: Long, currentFile: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val needed = pool.missingBytes(group)
        if (needed > 0) {
            pool.evictToFit(needed, keep = setOf(group.id))
            if (pool.freeSpaceBytes() < needed) {
                error("Not enough storage: ${needed / (1024 * 1024)} MB needed")
            }
        }

        val total = group.files.sumOf { it.bytes }
        var doneBefore = 0L

        for (f in group.files) {
            coroutineContext.ensureActive()
            if (f.isBundled) {
                val dest = pool.fileFor(f)
                if (!dest.exists()) installBundled(group)
                doneBefore += f.bytes
                continue
            }
            val dest = pool.fileFor(f)
            dest.parentFile?.mkdirs()
            val have = if (dest.exists()) dest.length() else 0L
            if (have >= f.bytes) {
                doneBefore += f.bytes
                continue
            }
            downloadFile(f, dest) { written -> onProgress(doneBefore + written, total, dest.name) }
            doneBefore += f.bytes
        }

        pool.touch(group.id)
        pool.isInstalled(group)
    }

    private fun downloadFile(f: ModelFile, dest: File, onProgress: (Long) -> Unit) {
        val part = File(dest.absolutePath + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) existing = 0L
            else if (code != HttpURLConnection.HTTP_PARTIAL) error("HTTP $code for ${f.url}")

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(existing)
                    val buf = ByteArray(256 * 1024)
                    var written = existing
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        if (written - lastReport > 1_000_000L) {
                            lastReport = written
                            onProgress(written)
                        }
                    }
                    onProgress(written)
                }
            }
        } finally {
            conn.disconnect()
        }

        val size = part.length()
        if (f.bytes > 0 && size < f.bytes) {
            error("${dest.name}: $size of ${f.bytes} bytes — will resume next time")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) error("Could not finalise ${dest.name}")
    }

    suspend fun importFiles(uris: List<Uri>, onFile: (String) -> Unit = {}): List<File> =
        withContext(Dispatchers.IO) {
            val wanted = ModelCatalog.all.flatMap { g -> g.files.map { File(it.path).name to it.path } }
                .toMap()
            val out = ArrayList<File>()
            for (uri in uris) {
                val name = queryDisplayName(uri) ?: continue
                val rel = wanted[name] ?: if (name.startsWith("ggml-")) "whisper/$name" else continue
                val dest = File(pool.root, rel)
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { o -> input.copyTo(o, 256 * 1024) }
                } ?: continue
                onFile(name)
                out.add(dest)
            }
            out
        }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private companion object {
        const val TAG = "ModelRepo"
    }
}
