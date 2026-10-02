package com.smatrisciano.aipantry.diagnostics.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File

/** How hot the system says the device is, as Android reports it. */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

enum class PowerSource { BATTERY, USB, AC, WIRELESS, OTHER }

/**
 * Cores that run at the same frequency. To cool the chip down the system lowers
 * [capKhz] below [maxKhz]: that is the throttling, and on-device inference slows
 * down by as much.
 */
data class CpuCluster(
    val cores: List<Int>,
    val currentKhz: Long?,
    val capKhz: Long?,
    val maxKhz: Long?
)

/** The device right now, as far as an app is allowed to see it. */
data class DeviceSnapshot(
    val thermalStatus: ThermalStatus,
    /** How close the device is to severe throttling: 1 is there. Null where it isn't available. */
    val thermalHeadroom: Float?,
    val batteryTemperatureCelsius: Float?,
    val batteryPercent: Int?,
    val powerSource: PowerSource?,
    val powerSaveMode: Boolean,
    /** Slowest cores first. */
    val clusters: List<CpuCluster>,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val lowMemory: Boolean,
    val appMemoryBytes: Long
)

data class DeviceInfo(
    val model: String,
    val chip: String?,
    val android: String,
    val cores: Int
)

/**
 * Reads what on-device inference depends on: heat and throttling, battery and
 * memory. The CPU's own temperature sensors are closed to apps: the battery's
 * temperature, the system's thermal status and its headroom stand in for them.
 */
class DeviceMonitor(private val context: Context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    val deviceInfo: DeviceInfo = DeviceInfo(
        model = "${Build.MANUFACTURER.replaceFirstChar { it.titlecase() }} ${Build.MODEL}",
        chip = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        cores = Runtime.getRuntime().availableProcessors()
    )

    /** Not on the main thread: it reads files and measures the app's memory. */
    fun snapshot(): DeviceSnapshot {
        // The battery state is a sticky broadcast: registering without a receiver just reads it
        val battery = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return DeviceSnapshot(
            thermalStatus = thermalStatus(),
            // Unsupported, or asked again too soon: NaN
            thermalHeadroom = powerManager.getThermalHeadroom(0).takeUnless { it.isNaN() },
            batteryTemperatureCelsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { it / 10f },
            batteryPercent = battery?.let { percent(it) },
            powerSource = battery?.let { powerSource(it) },
            powerSaveMode = powerManager.isPowerSaveMode,
            clusters = cpuClusters(),
            availableMemoryBytes = memory.availMem,
            totalMemoryBytes = memory.totalMem,
            lowMemory = memory.lowMemory,
            appMemoryBytes = Debug.getPss() * 1024
        )
    }

    private fun thermalStatus(): ThermalStatus = when (powerManager.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.UNKNOWN
    }

    private fun percent(battery: Intent): Int? {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    private fun powerSource(battery: Intent): PowerSource = when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        0 -> PowerSource.BATTERY
        BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
        BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
        else -> PowerSource.OTHER
    }

    /** One entry per group of cores sharing a frequency, as the kernel lists them. */
    private fun cpuClusters(): List<CpuCluster> =
        (0 until deviceInfo.cores)
            .map { cpu -> readCpu(cpu, "related_cpus") ?: "$cpu" }
            .distinct()
            .mapNotNull { related ->
                val cores = related.split(' ').mapNotNull { it.toIntOrNull() }
                val first = cores.firstOrNull() ?: return@mapNotNull null
                CpuCluster(
                    cores = cores,
                    currentKhz = readCpu(first, "scaling_cur_freq")?.toLongOrNull(),
                    capKhz = readCpu(first, "scaling_max_freq")?.toLongOrNull(),
                    maxKhz = readCpu(first, "cpuinfo_max_freq")?.toLongOrNull()
                )
            }
            .sortedBy { it.maxKhz ?: 0 }

    private fun readCpu(cpu: Int, name: String): String? =
        runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/$name").readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
}
