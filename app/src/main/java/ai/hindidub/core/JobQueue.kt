package ai.hindidub.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent job queue.
 *
 * Why: a feature-length dub can run for hours. If Android kills the app, the phone reboots
 * or the user force-stops it, the work should not vanish. Each job is a JSON record; the
 * service takes the next PENDING one when it starts, so "queue five episodes, walk away"
 * works and an interrupted job is retried rather than silently lost.
 */
data class JobRecord(
    val id: String,
    val uri: String,
    val name: String,
    val status: String,          // PENDING, RUNNING, DONE, FAILED
    val outputPath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

class JobQueue(context: Context) {

    private val file = File(context.filesDir, "jobs.json")

    fun all(): List<JobRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JobRecord(
                id = o.getString("id"),
                uri = o.getString("uri"),
                name = o.optString("name", "video"),
                status = o.optString("status", "PENDING"),
                outputPath = o.optString("outputPath").takeIf { it.isNotBlank() },
                error = o.optString("error").takeIf { it.isNotBlank() },
                createdAt = o.optLong("createdAt", 0L)
            )
        }
    }.getOrElse { emptyList() }

    fun enqueue(uri: String, name: String): JobRecord {
        val job = JobRecord(UUID.randomUUID().toString(), uri, name, "PENDING")
        save(all() + job)
        return job
    }

    fun next(): JobRecord? = all().firstOrNull { it.status == "PENDING" }

    fun update(job: JobRecord) {
        val list = all().map { if (it.id == job.id) job else it }
        save(list)
    }

    fun clearFinished() {
        save(all().filter { it.status == "PENDING" || it.status == "RUNNING" })
    }

    /** Marks jobs left RUNNING by a crash/reboot back to PENDING so they retry. */
    fun recoverInterrupted() {
        val list = all().map { if (it.status == "RUNNING") it.copy(status = "PENDING") else it }
        save(list)
    }

    private fun save(list: List<JobRecord>) {
        val arr = JSONArray()
        list.forEach { j ->
            arr.put(
                JSONObject()
                    .put("id", j.id)
                    .put("uri", j.uri)
                    .put("name", j.name)
                    .put("status", j.status)
                    .put("outputPath", j.outputPath ?: "")
                    .put("error", j.error ?: "")
                    .put("createdAt", j.createdAt)
            )
        }
        runCatching { file.writeText(arr.toString(2)) }
            .onFailure { Log.w("JobQueue", "save failed: ${it.message}") }
    }
}
