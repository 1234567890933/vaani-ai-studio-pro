package ai.hindidub.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the housekeeping worker once a day.
 *
 * Why WorkManager here and not for the dubbing job: a 3-hour CPU job needs a foreground
 * service (WorkManager workers are bounded and can be stopped), but *cleanup* is exactly what
 * WorkManager is for — it survives reboots, respects doze, and needs no notification.
 */
object CleanupScheduler {

    private const val WORK_NAME = "hindidubber-cleanup"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
