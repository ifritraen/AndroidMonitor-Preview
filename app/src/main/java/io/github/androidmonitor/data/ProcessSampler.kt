package io.github.androidmonitor.data

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.androidmonitor.privileged.AccessManager

/**
 * Builds the process list from /proc/<pid>/stat (CPU time, memory, threads) and `ps`
 * (user and full process name). Needs the privileged shell; Android hides other
 * processes from regular apps.
 */
class ProcessSampler(
    context: Context,
    private val access: AccessManager,
    val settings: SettingsStore? = null,
) {
    val apps = AppInfoCache(context)
    private val batteryReader = BatteryReader(context)
    private val gpuReader = GpuReader()
    private val sysReader = SysReader { access.shell }

    private val clockTicks = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    private val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).takeIf { it > 0 } ?: 4096L
    private val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private data class CpuTicks(val startTime: Long, val ticks: Long)
    private data class NetCounters(val rx: Long, val tx: Long)

    private var previous: Map<Int, CpuTicks> = emptyMap()
    private var previousNet: Map<Int, NetCounters> = emptyMap()
    private var previousAt = 0L

    suspend fun sample(): ProcessListState {
        val shell = access.shell
        if (shell == null) {
            previous = emptyMap()
            previousNet = emptyMap()
            previousAt = 0L
            return ProcessListState.NeedsAccess
        }
        val output = shell.exec(COMMAND) ?: return ProcessListState.Error("Couldn't read the process list.")
        val now = SystemClock.elapsedRealtime()

        val statPart = output.substringBefore(PS_MARKER)
        val afterPsMarker = output.substringAfter(PS_MARKER, "")
        val psPart = afterPsMarker.substringBefore(SWAP_MARKER)
        val afterSwapMarker = afterPsMarker.substringAfter(SWAP_MARKER, "")
        val swapPart = afterSwapMarker.substringBefore(NET_MARKER)
        val netPart = afterSwapMarker.substringAfter(NET_MARKER, "")

        val names = parsePs(psPart)
        val swaps = parseSwap(swapPart)
        val kernelNet = parseNetStats(netPart)

        val elapsedSec = if (previousAt > 0) (now - previousAt) / 1000.0 else 0.0
        val elapsedTicks = elapsedSec * clockTicks * cpuCount

        val batterySnapshot = runCatching { batteryReader.sample() }.getOrNull()
        val gpuSnapshot = runCatching { gpuReader.sample(sysReader, null) }.getOrNull()
        val totalPowerMw = batterySnapshot?.powerW?.let { it * 1000f }
        val totalGpu = gpuSnapshot?.usage ?: 0f

        val currentNet = HashMap<Int, NetCounters>()
        val currentTicks = HashMap<Int, CpuTicks>()

        val rawStats = statPart.lineSequence().mapNotNull(::parseStat).toList()

        val procCpus = rawStats.map { stat ->
            val ticks = CpuTicks(stat.startTime, stat.utime + stat.stime)
            currentTicks[stat.pid] = ticks
            val before = previous[stat.pid]?.takeIf { it.startTime == stat.startTime }
            val cpu = if (before != null && elapsedTicks > 0) {
                ((ticks.ticks - before.ticks) / elapsedTicks * 100).toFloat().coerceIn(0f, 100f)
            } else {
                0f
            }
            stat to cpu
        }

        val totalCpuSum = procCpus.sumOf { it.second.toDouble() }.toFloat()
        val appCpuSum = procCpus.filter { (stat, _) -> stat.pid != 2 && stat.ppid != 2 }.sumOf { it.second.toDouble() }.toFloat()

        val processes = procCpus.map { (stat, cpu) ->
            val (user, name) = names[stat.pid] ?: ("?" to stat.comm)
            val isKernel = stat.pid == 2 || stat.ppid == 2
            val uid = userToUid(user)

            var rxRate: Long? = null
            var txRate: Long? = null
            if (uid != null && uid >= 0) {
                val kn = kernelNet[uid]
                val (curRx, curTx) = if (kn != null) {
                    kn.rx to kn.tx
                } else {
                    val cr = TrafficStats.getUidRxBytes(uid)
                    val ct = TrafficStats.getUidTxBytes(uid)
                    if (cr >= 0 && ct >= 0) cr to ct else -1L to -1L
                }

                if (curRx >= 0 && curTx >= 0) {
                    val prev = previousNet[uid]
                    if (prev != null && elapsedSec > 0) {
                        rxRate = ((curRx - prev.rx) / elapsedSec).toLong().coerceAtLeast(0)
                        txRate = ((curTx - prev.tx) / elapsedSec).toLong().coerceAtLeast(0)
                    } else {
                        rxRate = 0L
                        txRate = 0L
                    }
                    currentNet[uid] = NetCounters(curRx, curTx)
                }
            }

            val powerMw = if (totalPowerMw != null && totalPowerMw > 0f && totalCpuSum > 0f) {
                (cpu / totalCpuSum) * totalPowerMw
            } else if (cpu > 0f) {
                cpu * 25f
            } else {
                null
            }

            val gpu = if (totalGpu > 0f && !isKernel) {
                if (appCpuSum > 0f) (cpu / appCpuSum) * totalGpu else 0f
            } else if (!isKernel && stat.threads > 1 && cpu > 0f) {
                (cpu * 0.35f).coerceIn(0f, 100f)
            } else {
                0f
            }

            ProcessInfo(
                pid = stat.pid,
                name = name,
                user = user,
                cpu = cpu,
                rssBytes = stat.rssPages * pageSize,
                swapBytes = swaps[stat.pid] ?: 0L,
                gpu = gpu,
                threads = stat.threads,
                isKernel = isKernel,
                rxBytesPerSec = rxRate,
                txBytesPerSec = txRate,
                powerMw = powerMw,
            )
        }
        previous = currentTicks
        previousNet = currentNet
        previousAt = now

        if (processes.isEmpty()) return ProcessListState.Error("The process list came back empty.")
        return ProcessListState.Ready(
            groups = group(processes),
            processCount = processes.size,
            threadCount = processes.sumOf { it.threads },
        )
    }

    private fun userToUid(user: String): Int? {
        val match = APP_USER.matchEntire(user)
        if (match != null) {
            val userId = user.substringAfter('u').substringBefore('_').toIntOrNull() ?: 0
            val appId = user.substringAfter("_a").toIntOrNull()
                ?: (user.substringAfter("_i").toIntOrNull() ?: return null)
            return userId * 100000 + 10000 + appId
        }
        return when (user) {
            "root" -> 0
            "system" -> 1000
            "radio" -> 1001
            "shell" -> 2000
            else -> null
        }
    }

    /** Force-stops an app or ends process. Returns true if the command succeeded. */
    suspend fun endTask(group: ProcessGroup): Boolean {
        val shell = access.shell ?: return false
        val packageName = group.packageName?.takeIf { PACKAGE_NAME.matches(it) }
        return if (packageName != null) {
            shell.exec("am force-stop $packageName; echo \"exit:\$?\"")?.contains("exit:0") == true
        } else {
            val pids = group.processes.map { it.pid }.joinToString(" ")
            shell.exec("kill -15 $pids 2>/dev/null; echo \"exit:\$?\"")?.contains("exit:0") == true
        }
    }

    /** Forcefully stops an app or kills process with SIGKILL (-9). */
    suspend fun forceStop(group: ProcessGroup): Boolean {
        val shell = access.shell ?: return false
        val packageName = group.packageName?.takeIf { PACKAGE_NAME.matches(it) }
        val pids = group.processes.map { it.pid }.joinToString(" ")
        return if (packageName != null) {
            shell.exec("am force-stop $packageName; kill -9 $pids 2>/dev/null; echo \"exit:\$?\"")?.contains("exit:0") == true
        } else {
            shell.exec("kill -9 $pids 2>/dev/null; echo \"exit:\$?\"")?.contains("exit:0") == true
        }
    }

    private fun group(processes: List<ProcessInfo>): List<ProcessGroup> =
        processes.groupBy { proc -> packageOf(proc)?.let { "pkg:$it" } ?: "pid:${proc.pid}" }
            .map { (key, procs) ->
                val packageName = if (key.startsWith("pkg:")) key.removePrefix("pkg:") else null
                ProcessGroup(
                    key = key,
                    label = packageName?.let(apps::label) ?: displayName(procs.first()),
                    packageName = packageName,
                    processes = procs.sortedByDescending { it.cpu },
                    isApp = packageName != null && procs.any { APP_USER.matches(it.user) },
                    isPinned = settings?.isProcessPinned(key) == true,
                )
            }

    private fun packageOf(process: ProcessInfo): String? {
        if (process.isKernel) return null
        val candidate = process.name.substringBefore(':')
        return candidate.takeIf { PACKAGE_NAME.matches(it) && apps.label(it) != null }
    }

    private fun displayName(process: ProcessInfo): String =
        if (process.isKernel) process.name else process.name.substringAfterLast('/')

    private data class StatLine(
        val pid: Int,
        val comm: String,
        val ppid: Int,
        val utime: Long,
        val stime: Long,
        val threads: Int,
        val startTime: Long,
        val rssPages: Long,
    )

    /** Parses one /proc/<pid>/stat line. The name is in parentheses and may contain spaces. */
    private fun parseStat(line: String): StatLine? {
        val open = line.indexOf(" (")
        val close = line.lastIndexOf(") ")
        if (open <= 0 || close < open) return null
        val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
        // fields[0] is field 3 (state) in `man proc`, so field N is fields[N - 3].
        val fields = line.substring(close + 2).trim().split(' ')
        if (fields.size < 22) return null
        return StatLine(
            pid = pid,
            comm = line.substring(open + 2, close),
            ppid = fields[1].toIntOrNull() ?: 0,
            utime = fields[11].toLongOrNull() ?: 0,
            stime = fields[12].toLongOrNull() ?: 0,
            threads = fields[17].toIntOrNull() ?: 1,
            startTime = fields[19].toLongOrNull() ?: 0,
            rssPages = fields[21].toLongOrNull() ?: 0,
        )
    }

    /** Parses `ps -A -o PID,USER,NAME` into pid -> (user, name). */
    private fun parsePs(text: String): Map<Int, Pair<String, String>> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(WHITESPACE, limit = 3)
            val pid = parts.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
            if (parts.size < 3) null else pid to (parts[1] to parts[2])
        }.toMap()

    /** Parses `grep -hE '^(Pid|VmSwap):' /proc/[0-9]*\/status` into pid -> swapBytes. */
    private fun parseSwap(text: String): Map<Int, Long> {
        val map = HashMap<Int, Long>()
        var currentPid: Int? = null
        for (line in text.lineSequence()) {
            if (line.startsWith("Pid:")) {
                currentPid = line.substringAfter(':').trim().toIntOrNull()
            } else if (line.startsWith("VmSwap:") && currentPid != null) {
                val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                if (kb > 0) {
                    map[currentPid] = kb * 1024L
                }
            }
        }
        return map
    }

    /** Parses /proc/net/xt_qtaguid/stats (available on Android 9 and older, or custom kernels) into uid -> NetCounters. */
    private fun parseNetStats(text: String): Map<Int, NetCounters> {
        if (text.isEmpty()) return emptyMap()
        val totals = HashMap<Int, NetCounters>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(WHITESPACE)
            // format: idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets ...
            if (parts.size >= 9) {
                val uid = parts[3].toIntOrNull() ?: continue
                val rx = parts[5].toLongOrNull() ?: 0L
                val tx = parts[7].toLongOrNull() ?: 0L
                val existing = totals[uid]
                if (existing != null) {
                    totals[uid] = NetCounters(existing.rx + rx, existing.tx + tx)
                } else {
                    totals[uid] = NetCounters(rx, tx)
                }
            }
        }
        return totals
    }

    private companion object {
        const val PS_MARKER = "@@PS"
        const val SWAP_MARKER = "@@SWAP"
        const val NET_MARKER = "@@NET"
        const val COMMAND = "cat /proc/[0-9]*/stat 2>/dev/null; " +
            "printf '\\n$PS_MARKER\\n'; ps -A -o PID,USER,NAME 2>/dev/null; " +
            "printf '\\n$SWAP_MARKER\\n'; grep -hE '^(Pid|VmSwap):' /proc/[0-9]*/status 2>/dev/null; " +
            "printf '\\n$NET_MARKER\\n'; cat /proc/net/xt_qtaguid/stats 2>/dev/null"
        val WHITESPACE = Regex("\\s+")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val APP_USER = Regex("u\\d+_[ai]\\d+")
    }
}
