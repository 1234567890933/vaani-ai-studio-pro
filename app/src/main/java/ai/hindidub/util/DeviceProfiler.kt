package ai.hindidub.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Cheap device facts used to pick model size and thread count.
 * Nothing here needs a permission.
 */
object DeviceProfiler {

    data class Info(
        val soc: String,
        val cores: Int,
        val bigCores: Int,
        val ramMb: Long,
        val lowRamDevice: Boolean,
        val abi: String
    ) {
        val recommendedThreads: Int
            get() = when {
                bigCores >= 4 -> 4
                bigCores >= 2 -> 4
                else -> 3
            }.coerceAtMost(cores)

        val recommendSmallWhisper: Boolean
            get() = ramMb >= 5000 && !lowRamDevice
    }

    fun profile(context: Context): Info {
        val cores = Runtime.getRuntime().availableProcessors()
        val bigCores = countFastCores(cores)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL
        else "${Build.HARDWARE}/${Build.BOARD}"

        val info = Info(
            soc = soc,
            cores = cores,
            bigCores = bigCores,
            ramMb = mi.totalMem / (1024 * 1024),
            lowRamDevice = am.isLowRamDevice,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        )
        Log.i("DeviceProfiler", "soc=$soc cores=$cores big=$bigCores ram=${info.ramMb}MB lowRam=${info.lowRamDevice}")
        return info
    }

    /** Counts cores whose max frequency is above ~1.5 GHz (the "big" cluster). */
    private fun countFastCores(cores: Int): Int {
        var fast = 0
        for (i in 0 until cores) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (!f.exists()) continue
            val khz = f.readTextOrNull()?.trim()?.toLongOrNull() ?: continue
            if (khz >= 1_500_000L) fast++
        }
        return if (fast == 0) cores else fast
    }

    private fun File.readTextOrNull(): String? = try {
        readText()
    } catch (t: Throwable) {
        null
    }
}
