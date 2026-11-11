package ai.hindidub.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * Deletes what a killed job leaves behind:
 *  * `cache/dub_*` directories older than a day (audio timelines, spooled AAC);
 *  * `*.part` model downloads that never finished and are older than a week.
 *
 * It never touches installed models or finished dubs — those belong to the user.
 */
class CleanupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cache = applicationContext.cacheDir
        val models = File(applicationContext.filesDir, "models")
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        var freed = 0L

        cache.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name.startsWith("dub_") && dir.lastModified() < dayAgo) {
                freed += sizeOf(dir)
                dir.deleteRecursively()
            }
        }
        models.walkTopDown().filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < weekAgo }
            .toList()
            .forEach { part ->
                freed += part.length()
                part.delete()
            }

        Log.i(TAG, "cleanup freed ${freed / (1024 * 1024)} MB")
        return Result.success()
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val TAG = "Cleanup"
    }
}
