package ai.hindidub

import ai.hindidub.core.CrashLogger
import ai.hindidub.work.CleanupScheduler
import android.app.Application
import android.util.Log

/**
 * Application entry point.
 *
 * Two jobs, both about surviving unattended use:
 *  * install the crash handler before anything else runs, so a failure during startup is
 *    still recorded;
 *  * schedule the housekeeping worker, which clears orphaned temp files left by a killed job.
 */
class HindiDubberApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        CleanupScheduler.schedule(this)
        Log.i(TAG, "app start, abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
    }

    private companion object {
        const val TAG = "App"
    }
}
