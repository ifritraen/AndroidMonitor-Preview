package io.github.androidmonitor.data

import io.github.androidmonitor.privileged.AccessMode

/** How many samples each graph keeps (60 samples ≈ 60 seconds at the default refresh rate). */
const val HISTORY_SIZE = 60

enum class CpuUsageSource {
    /** Real usage from /proc/stat (needs Shizuku or root on Android 8+). */
    KERNEL,

    /** Approximated from how high each core is clocked. */
    ESTIMATED,
}

data class CoreInfo(
    val id: Int,
    val online: Boolean,
    val usage: Float,
    val curMhz: Int?,
    val maxMhz: Int?,
)

data class CpuSnapshot(
    val usage: Float,
    val source: CpuUsageSource,
    /** Kernel counters need two samples before usage can be computed. */
    val warmingUp: Boolean,
    val cores: List<CoreInfo>,
    val socName: String,
    val avgFreqMhz: Int?,
    val peakFreqMhz: Int?,
    val maxFreqMhz: Int?,
)

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val cachedBytes: Long?,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
    val lowMemory: Boolean,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)
    val usedPercent: Float get() = if (totalBytes > 0) usedBytes * 100f / totalBytes else 0f
    val swapUsedBytes: Long? get() = if (swapTotalBytes != null && swapFreeBytes != null) {
        (swapTotalBytes - swapFreeBytes).coerceAtLeast(0)
    } else null
    val swapUsedPercent: Float? get() = if (swapTotalBytes != null && swapTotalBytes > 0 && swapUsedBytes != null) {
        swapUsedBytes!! * 100f / swapTotalBytes
    } else null
}

data class GpuSnapshot(
    val available: Boolean,
    val usage: Float?,
    val freqMhz: Int?,
    val maxFreqMhz: Int?,
    val model: String?,
    val tempC: Float?,
)

data class NetworkSnapshot(
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val connection: String,
)

data class BatterySnapshot(
    val level: Int,
    val status: String,
    val charging: Boolean,
    val tempC: Float?,
    val voltageV: Float?,
    val currentMa: Int?,
    val powerW: Float?,
    val health: String,
)

data class ThermalSensor(val name: String, val tempC: Float)

data class ThermalSnapshot(
    val sensors: List<ThermalSensor>,
    val cpuTempC: Float?,
    val gpuTempC: Float?,
    val skinTempC: Float?,
    val status: String?,
)

data class Snapshot(
    val cpu: CpuSnapshot,
    val memory: MemorySnapshot,
    val gpu: GpuSnapshot,
    val network: NetworkSnapshot,
    val battery: BatterySnapshot,
    val thermal: ThermalSnapshot,
    val uptimeMs: Long,
)

/** Recent values for each graph, oldest first. */
data class History(
    val cpu: List<Float>,
    val cores: List<List<Float>>,
    val memory: List<Float>,
    val gpu: List<Float>,
    val netRx: List<Float>,
    val netTx: List<Float>,
    val cpuTemp: List<Float>,
    val batteryTemp: List<Float>,
)

data class MonitorState(
    val snapshot: Snapshot,
    val history: History,
    val access: AccessMode,
)

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val user: String,
    val cpu: Float,
    val rssBytes: Long,
    val swapBytes: Long = 0L,
    val gpu: Float = 0f,
    val threads: Int,
    val isKernel: Boolean,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val powerMw: Float? = null,
)

/** One row in the process list: an app with all its processes, or a single native process. */
data class ProcessGroup(
    val key: String,
    val label: String,
    val packageName: String?,
    val processes: List<ProcessInfo>,
    val isApp: Boolean,
    val isPinned: Boolean = false,
) {
    val cpu: Float = processes.sumOf { it.cpu.toDouble() }.toFloat()
    val rssBytes: Long = processes.sumOf { it.rssBytes }
    val swapBytes: Long = processes.sumOf { it.swapBytes }
    val gpu: Float = processes.sumOf { it.gpu.toDouble() }.toFloat()
    val threads: Int = processes.sumOf { it.threads }
    val isKernel: Boolean = processes.all { it.isKernel }
    val rxBytesPerSec: Long = processes.sumOf { it.rxBytesPerSec }
    val txBytesPerSec: Long = processes.sumOf { it.txBytesPerSec }
    val powerMw: Float? = processes.mapNotNull { it.powerMw }.takeIf { it.isNotEmpty() }?.sum()

    fun matches(query: String): Boolean =
        label.contains(query, ignoreCase = true) ||
            packageName?.contains(query, ignoreCase = true) == true ||
            processes.any { it.name.contains(query, ignoreCase = true) || it.pid.toString() == query }
}

enum class DefaultTab(val label: String) {
    PERFORMANCE("Performance"),
    PROCESSES("Processes"),
}

enum class ProcessGrouping(val label: String) {
    SEPARATE("Categorized"),
    MIXED("Unified"),
}

enum class OverlayType(val label: String) {
    RESOURCE_HUD("Resource HUD"),
    TOP_PROCESSES("Top processes"),
    SINGLE_PROCESS("Single process"),
}

sealed interface ProcessListState {
    data object Loading : ProcessListState
    data object NeedsAccess : ProcessListState
    data class Error(val message: String) : ProcessListState
    data class Ready(
        val groups: List<ProcessGroup>,
        val processCount: Int,
        val threadCount: Int,
    ) : ProcessListState
}
