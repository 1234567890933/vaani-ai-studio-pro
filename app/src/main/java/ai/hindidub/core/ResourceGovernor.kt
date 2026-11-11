package ai.hindidub.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Keeps the phone alive and cool during multi-hour dubbing.
 *
 * Why this exists: the pipeline is a sustained CPU load. Without pacing, a Dimensity
 * 6000-class phone throttles within ~5–10 minutes, the job slows down anyway, and the
 * battery drains. This class reads real OS signals and inserts cooldowns *before* the
 * phone has to throttle by itself.
 *
 * Signals used (all free, no permissions):
 *  * PowerManager.getCurrentThermalStatus() — API 29+, the OS's own thermal verdict;
 *  * battery temperature from ACTION_BATTERY_CHANGED;
 *  * battery level and charging state.
 *
 * Policy:
 *   NONE/LIGHT       → full threads, no pause
 *   MODERATE         → threads - 1, 2 s pause every segment
 *   SEVERE           → threads - 2, 6 s pause every segment
 *   CRITICAL         → 30 s pause, and refuse to start new stages below 5 % battery
 */
class ResourceGovernor(private val context: Context) {

    data class Snapshot(
        val thermalStatus: Int,
        val batteryTempC: Float,
        val batteryPercent: Int,
        val charging: Boolean,
        val maxThreads: Int
    ) {
        val thermalLabel: String
            get() = when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "cool"
                PowerManager.THERMAL_STATUS_LIGHT -> "warm"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "hot"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                else -> "unknown"
            }

        val isHot: Boolean
            get() = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || batteryTempC >= 42f
    }

    fun snapshot(baseThreads: Int): Snapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        var tempC = 0f
        var percent = 100
        var charging = false
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (intent != null) {
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            tempC = tenths / 10f
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) percent = level * 100 / scale
            charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                .let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        }

        val threads = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> (baseThreads - 2).coerceAtLeast(1)
            thermal >= PowerManager.THERMAL_STATUS_MODERATE || tempC >= 42f ->
                (baseThreads - 1).coerceAtLeast(1)
            else -> baseThreads
        }

        return Snapshot(thermal, tempC, percent, charging, threads)
    }

    /**
     * Call between segments. Suspends if the phone needs to cool down.
     * @param onPause reported to the UI so the user sees "cooling down", not a frozen app.
     */
    suspend fun checkpoint(baseThreads: Int, onPause: (String) -> Unit): Int {
        val s = snapshot(baseThreads)
        if (s.batteryPercent in 1..4 && !s.charging) {
            onPause("Battery ${s.batteryPercent}% — pausing to protect the phone")
            delay(60_000)
        }
        when {
            s.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                onPause("Phone is hot (${s.batteryTempC}°C) — cooling for 30 s")
                delay(30_000)
            }
            s.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                onPause("Throttling: cooling 6 s")
                delay(6_000)
            }
            s.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || s.batteryTempC >= 42f -> {
                delay(2_000)
            }
            else -> Unit
        }
        if (s.isHot) Log.i(TAG, "governor: ${s.thermalLabel}, ${s.batteryTempC}°C, threads=${s.maxThreads}")
        return s.maxThreads
    }

    /** True when starting a long job is a bad idea right now. */
    fun shouldWarnBeforeStart(): String? {
        val s = snapshot(4)
        return when {
            s.batteryPercent < 15 && !s.charging ->
                "Battery is at ${s.batteryPercent}%. A long job will drain it — charge first."
            s.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Phone is already hot (${s.batteryTempC}°C). Let it cool before a long job."
            else -> null
        }
    }

    private companion object {
        const val TAG = "Governor"
    }
}
