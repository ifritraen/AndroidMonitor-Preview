package io.github.androidmonitor.data

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.androidmonitor.privileged.AccessManager
import io.github.androidmonitor.privileged.AccessMode
import io.github.androidmonitor.privileged.PrivilegedShell

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
    private data class UserProfileInfo(
        val id: Int,
        val name: String,
        val isPrivateSpace: Boolean,
    )

    private var previous: Map<Int, CpuTicks> = emptyMap()
    private var previousNet: Map<Int, NetCounters> = emptyMap()
    private var previousAt = 0L

    @Volatile
    private var userProfiles: Map<Int, UserProfileInfo> = emptyMap()
    private var lastUsersRefreshAt = 0L
    private val profilePackages = java.util.concurrent.ConcurrentHashMap<Int, Set<String>>()
    private val profilePackagesRefreshedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

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
        refreshProfileMetadataIfNeeded(shell, processes, now)
        val groups = group(processes)
        syncLockedPriorities(groups)
        return ProcessListState.Ready(
            groups = groups,
            processCount = processes.size,
            threadCount = processes.sumOf { it.threads },
        )
    }

    private suspend fun refreshProfileMetadataIfNeeded(
        shell: PrivilegedShell,
        processes: List<ProcessInfo>,
        now: Long,
    ) {
        val activeSecondaryUsers = processes.asSequence()
            .filter { !it.isKernel }
            .map { parseUserId(it.user) }
            .filter { it > 0 }
            .toSet()
        if (activeSecondaryUsers.isEmpty()) return

        val needsUserRefresh = activeSecondaryUsers.any { !userProfiles.containsKey(it) } ||
            (now - lastUsersRefreshAt >= 20_000L)
        if (needsUserRefresh) {
            lastUsersRefreshAt = now
            val out = shell.exec(
                "dumpsys user 2>/dev/null | grep -E 'UserInfo\\{|Type:|Flags:'; " +
                    "printf '\\n@@PMUSERS\\n'; pm list users 2>/dev/null"
            )
            if (!out.isNullOrBlank()) {
                userProfiles = parseUserProfiles(out)
            }
        }

        for (userId in activeSecondaryUsers) {
            val lastRefreshed = profilePackagesRefreshedAt[userId] ?: 0L
            if (!profilePackages.containsKey(userId) || now - lastRefreshed >= 30_000L) {
                profilePackagesRefreshedAt[userId] = now
                val out = shell.exec("pm list packages -f --user $userId 2>/dev/null")
                if (!out.isNullOrBlank()) {
                    val apkPaths = HashMap<String, String>()
                    for (line in out.lineSequence()) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("package:")) continue
                        val body = trimmed.removePrefix("package:")
                        val eqIdx = body.lastIndexOf('=')
                        if (eqIdx <= 0) continue
                        val path = body.substring(0, eqIdx)
                        val pkg = body.substring(eqIdx + 1)
                        if (PACKAGE_NAME.matches(pkg)) {
                            apkPaths[pkg] = path
                        }
                    }
                    if (apkPaths.isNotEmpty()) {
                        apps.registerApkPaths(apkPaths)
                        profilePackages[userId] = apkPaths.keys.toSet()
                    }
                }
            }
        }
    }

    private fun parseUserProfiles(text: String): Map<Int, UserProfileInfo> {
        val map = HashMap<Int, UserProfileInfo>()
        var currentId: Int? = null
        var currentName = ""
        var currentPrivate = false

        fun commitCurrent() {
            val id = currentId ?: return
            val existing = map[id]
            map[id] = UserProfileInfo(
                id = id,
                name = currentName.ifBlank { existing?.name ?: "User $id" },
                isPrivateSpace = currentPrivate || (existing?.isPrivateSpace == true),
            )
        }

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed == "@@PMUSERS") {
                commitCurrent()
                currentId = null
                continue
            }
            val match = USER_INFO_REGEX.find(trimmed)
            if (match != null) {
                commitCurrent()
                currentId = match.groupValues[1].toIntOrNull()
                currentName = match.groupValues[2].trim()
                currentPrivate = currentName.contains("private", ignoreCase = true)
            } else if (currentId != null) {
                if (trimmed.startsWith("Type:", ignoreCase = true) || trimmed.startsWith("Flags:", ignoreCase = true)) {
                    if (trimmed.contains("PRIVATE", ignoreCase = true)) {
                        currentPrivate = true
                    }
                }
            }
        }
        commitCurrent()
        return map
    }

    private fun parseUserId(user: String): Int {
        if (!APP_USER.matches(user)) return 0
        return user.substringAfter('u').substringBefore('_').toIntOrNull() ?: 0
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
        val userFlag = if (group.userId > 0) "--user ${group.userId} " else ""
        return if (packageName != null) {
            shell.exec("am force-stop ${userFlag}$packageName; echo \"exit:\$?\"")?.contains("exit:0") == true
        } else {
            val pids = group.processes.map { it.pid }.joinToString(" ")
            shell.exec("kill -15 $pids 2>/dev/null; echo \"exit:\$?\"")?.contains("exit:0") == true
        }
    }

    /** Forcefully stops an app or kills process with SIGKILL (-9). */
    suspend fun forceStop(group: ProcessGroup): Boolean {
        val shell = access.shell ?: return false
        val packageName = group.packageName?.takeIf { PACKAGE_NAME.matches(it) }
        val userFlag = if (group.userId > 0) "--user ${group.userId} " else ""
        val pids = group.processes.map { it.pid }.joinToString(" ")
        return if (packageName != null) {
            shell.exec("am force-stop ${userFlag}$packageName; kill -9 $pids 2>/dev/null; echo \"exit:\$?\"")?.contains("exit:0") == true
        } else {
            shell.exec("kill -9 $pids 2>/dev/null; echo \"exit:\$?\"")?.contains("exit:0") == true
        }
    }

    private var lastLockSyncAt = 0L

    private suspend fun syncLockedPriorities(groups: List<ProcessGroup>) {
        if (access.state.value.mode != AccessMode.ROOT) return
        val shell = access.shell ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockSyncAt < 10_000L) return
        lastLockSyncAt = now

        val lockedPids = groups.filter { it.isLocked }.flatMap { it.processes.map { p -> p.pid } }
        if (lockedPids.isEmpty()) return
        val cmd = lockedPids.joinToString("; ") { pid ->
            "echo 0 > /proc/$pid/oom_score_adj 2>/dev/null; chmod 0444 /proc/$pid/oom_score_adj 2>/dev/null"
        }
        shell.exec(cmd)
    }

    /**
     * Sets process OOM score and background permissions so it remains alive in memory.
     * Score 0 gives it foreground app priority; chmod 0444 locks out ActivityManager reversion.
     */
    suspend fun setProcessPriority(group: ProcessGroup, lock: Boolean): Boolean {
        val shell = access.shell ?: return false
        val pkg = group.packageName?.takeIf { PACKAGE_NAME.matches(it) }
        val userFlag = if (group.userId > 0) "--user ${group.userId} " else ""
        val pids = group.processes.map { it.pid }

        val commands = mutableListOf<String>()

        // 1. OOM score adj (score 0 = foreground; chmod 0444 locks out ActivityManager reversion)
        for (pid in pids) {
            if (lock) {
                commands.add("echo 0 > /proc/$pid/oom_score_adj 2>/dev/null; chmod 0444 /proc/$pid/oom_score_adj 2>/dev/null")
            } else {
                commands.add("chmod 0644 /proc/$pid/oom_score_adj 2>/dev/null; echo 900 > /proc/$pid/oom_score_adj 2>/dev/null")
            }
        }

        // 2. Device idle whitelist & AppOps (Run in background)
        if (pkg != null) {
            if (lock) {
                commands.add("dumpsys deviceidle whitelist +$pkg 2>/dev/null")
                commands.add("cmd appops set ${userFlag}$pkg RUN_IN_BACKGROUND allow 2>/dev/null")
                commands.add("cmd appops set ${userFlag}$pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            } else {
                commands.add("dumpsys deviceidle whitelist -$pkg 2>/dev/null")
                commands.add("cmd appops set ${userFlag}$pkg RUN_IN_BACKGROUND default 2>/dev/null")
                commands.add("cmd appops set ${userFlag}$pkg RUN_ANY_IN_BACKGROUND default 2>/dev/null")
            }
        }

        if (commands.isEmpty()) return false
        val fullCommand = commands.joinToString("; ") + "; echo \"exit:\$?\""
        return shell.exec(fullCommand)?.contains("exit:0") == true
    }

    /**
     * Safely reduces swap / zRAM usage by killing selected background app processes,
     * dropping OS caches (root), and triggering zRAM compaction (root).
     * Strictly exempts pinned and locked packages.
     * Returns the number of swap bytes freed (or 0L).
     */
    suspend fun reduceSwap(targetPackages: Set<String>, exemptPackages: Set<String>): Long {
        val shell = access.shell ?: return 0L
        val safeTargets = targetPackages - exemptPackages

        val beforeSwap = getSystemSwapUsed(shell)

        // 1. Terminate background processes
        for (pkg in safeTargets) {
            if (PACKAGE_NAME.matches(pkg)) {
                shell.exec("am kill $pkg 2>/dev/null")
            }
        }

        // 2. Drop caches and trigger zram compaction
        val rootCommands = mutableListOf<String>()
        rootCommands.add("sync")
        rootCommands.add("echo 3 > /proc/sys/vm/drop_caches 2>/dev/null")
        rootCommands.add("if [ -f /sys/block/zram0/compact ]; then echo 1 > /sys/block/zram0/compact 2>/dev/null; fi")
        shell.exec(rootCommands.joinToString("; "))

        kotlinx.coroutines.delay(600)
        val afterSwap = getSystemSwapUsed(shell)
        val freed = if (beforeSwap != null && afterSwap != null && beforeSwap > afterSwap) {
            beforeSwap - afterSwap
        } else {
            0L
        }
        return freed
    }

    private suspend fun getSystemSwapUsed(shell: PrivilegedShell): Long? {
        val meminfo = shell.exec("cat /proc/meminfo 2>/dev/null") ?: return null
        var total = 0L
        var free = 0L
        for (line in meminfo.lineSequence()) {
            if (line.startsWith("SwapTotal:")) {
                total = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
            } else if (line.startsWith("SwapFree:")) {
                free = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        return if (total > 0) (total - free) * 1024L else null
    }

    private fun group(processes: List<ProcessInfo>): List<ProcessGroup> {
        data class GroupKey(val key: String, val userId: Int, val pkg: String?)

        return processes.groupBy { proc ->
            val userId = parseUserId(proc.user)
            val pkg = packageOf(proc, userId)
            val key = when {
                pkg != null && userId > 0 -> "pkg:$userId:$pkg"
                pkg != null -> "pkg:$pkg"
                else -> "pid:${proc.pid}"
            }
            GroupKey(key, if (pkg != null) userId else 0, pkg)
        }.map { (gk, procs) ->
            val profile = if (gk.userId > 0) userProfiles[gk.userId] else null
            val isPrivateSpace = gk.userId > 0 && (profile?.isPrivateSpace == true)
            val profileName = if (gk.userId > 0) profile?.name ?: "User ${gk.userId}" else null
            ProcessGroup(
                key = gk.key,
                label = gk.pkg?.let(apps::label) ?: displayName(procs.first()),
                packageName = gk.pkg,
                processes = procs.sortedByDescending { it.cpu },
                isApp = gk.pkg != null && procs.any { APP_USER.matches(it.user) },
                userId = gk.userId,
                isPrivateSpace = isPrivateSpace,
                profileName = profileName,
                isPinned = settings?.isProcessPinned(gk.key) == true,
                isLocked = settings?.isProcessLocked(gk.key) == true,
            )
        }
    }

    private fun packageOf(process: ProcessInfo, userId: Int): String? {
        if (process.isKernel) return null
        val candidate = process.name.substringBefore(':')
        return candidate.takeIf {
            PACKAGE_NAME.matches(it) &&
                (apps.label(it) != null || (userId > 0 && profilePackages[userId]?.contains(it) == true))
        }
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
        val USER_INFO_REGEX = Regex("UserInfo\\{(\\d+):([^:}]+):[^}]*\\}")
    }
}
