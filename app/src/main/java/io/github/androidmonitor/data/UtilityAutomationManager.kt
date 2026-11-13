package io.github.androidmonitor.data

import android.content.Context
import android.os.SystemClock
import io.github.androidmonitor.privileged.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class UtilityStatusSnapshot(
    val foregroundPackages: Set<String> = emptySet(),
    val runningPackages: Set<String> = emptySet(),
    val backgroundSinceMs: Map<String, Long> = emptyMap(),
    val throttledPackages: Set<String> = emptySet(),
    val lastLogs: List<String> = emptyList(),
    val lastTickElapsedMs: Long = 0L,
)

/**
 * Tracks watched apps in background and applies automated actions:
 * - Background App Force Stop: `am force-stop <pkg>` after idle timeout
 * - Auto Thermal Reduce: standby bucket restriction + background appops + `renice 19` (no kill)
 * - Auto RAM Clean: `am send-trim-memory COMPLETE` + `/proc/<pid>/reclaim` (no kill)
 */
class UtilityAutomationManager(
    context: Context,
    private val access: AccessManager,
    private val settings: SettingsStore,
    private val apps: AppInfoCache,
) {
    private val selfPackage = context.packageName
    private val mutex = Mutex()

    private val backgroundSince = ConcurrentHashMap<String, Long>()
    private val lastForceStopRun = ConcurrentHashMap<String, Long>()
    private val lastThermalRun = ConcurrentHashMap<String, Long>()
    private val lastRamCleanRun = ConcurrentHashMap<String, Long>()
    private val throttledPackages = ConcurrentHashMap.newKeySet<String>()
    private val recentLogs = ArrayDeque<String>()

    private val _status = MutableStateFlow(UtilityStatusSnapshot())
    val status: StateFlow<UtilityStatusSnapshot> = _status.asStateFlow()

    private var lastEvaluateElapsedMs = 0L

    /** Called periodically by MonitorOverlayService or ViewModel polling. */
    suspend fun tickIfDue(groups: List<ProcessGroup>? = null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEvaluateElapsedMs < EVALUATE_INTERVAL_MS) return
        evaluate(groups)
    }

    suspend fun evaluate(groups: List<ProcessGroup>? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            lastEvaluateElapsedMs = now

            val shell = access.shell
            if (shell == null) {
                _status.value = _status.value.copy(lastTickElapsedMs = now)
                return@withLock
            }

            val watched = (settings.autoForceStopApps.value +
                settings.autoThermalApps.value +
                settings.autoRamCleanApps.value) - selfPackage

            val foregroundPkgs = detectForegroundPackages(shell)
            val procMap = resolveRunningPackages(shell, groups)
            val runningPkgs = procMap.keys

            // Update background timers & restore thermal-throttled apps if they returned to foreground
            for (pkg in watched) {
                val isRunning = runningPkgs.contains(pkg)
                val isForeground = foregroundPkgs.contains(pkg)
                if (!isRunning || isForeground) {
                    backgroundSince.remove(pkg)
                    if (isForeground && throttledPackages.remove(pkg)) {
                        restoreThermalState(shell, pkg, procMap[pkg].orEmpty())
                    }
                } else {
                    backgroundSince.putIfAbsent(pkg, now)
                }
            }
            // Clean stale entries for packages no longer watched
            backgroundSince.keys.retainAll(watched)

            // 1. Background app force stop
            if (settings.autoForceStopEnabled.value) {
                val defaultMin = settings.autoForceStopTimeoutMin.value
                val customTimeouts = settings.autoForceStopAppTimeouts.value
                for (pkg in settings.autoForceStopApps.value) {
                    if (pkg == selfPackage || !runningPkgs.contains(pkg) || foregroundPkgs.contains(pkg)) continue
                    val bgStart = backgroundSince[pkg] ?: continue
                    val timeoutMs = (customTimeouts[pkg] ?: defaultMin) * 60_000L
                    val lastRun = lastForceStopRun[pkg] ?: 0L
                    if (now - bgStart >= timeoutMs && now - lastRun >= 60_000L) {
                        val procs = procMap[pkg].orEmpty()
                        if (executeForceStop(shell, pkg, procs)) {
                            lastForceStopRun[pkg] = now
                            backgroundSince.remove(pkg)
                            appendLog("Force stopped ${apps.label(pkg) ?: pkg}")
                        }
                    }
                }
            }

            // 2. Auto thermal reduce (without force stop or killing)
            if (settings.autoThermalEnabled.value) {
                val defaultMin = settings.autoThermalTimeoutMin.value
                val customTimeouts = settings.autoThermalAppTimeouts.value
                for (pkg in settings.autoThermalApps.value) {
                    if (pkg == selfPackage || !runningPkgs.contains(pkg) || foregroundPkgs.contains(pkg)) continue
                    val bgStart = backgroundSince[pkg] ?: continue
                    val timeoutMs = (customTimeouts[pkg] ?: defaultMin) * 60_000L
                    val lastRun = lastThermalRun[pkg] ?: 0L
                    if (now - bgStart >= timeoutMs && now - lastRun >= timeoutMs) {
                        val procs = procMap[pkg].orEmpty()
                        if (executeThermalReduce(shell, pkg, procs)) {
                            lastThermalRun[pkg] = now
                            throttledPackages.add(pkg)
                            appendLog("Reduced thermal for ${apps.label(pkg) ?: pkg}")
                        }
                    }
                }
            }

            // 3. Auto RAM clean (release resources without force stop or killing)
            if (settings.autoRamCleanEnabled.value) {
                val defaultMin = settings.autoRamCleanTimeoutMin.value
                val customTimeouts = settings.autoRamCleanAppTimeouts.value
                for (pkg in settings.autoRamCleanApps.value) {
                    if (pkg == selfPackage || !runningPkgs.contains(pkg) || foregroundPkgs.contains(pkg)) continue
                    val bgStart = backgroundSince[pkg] ?: continue
                    val timeoutMs = (customTimeouts[pkg] ?: defaultMin) * 60_000L
                    val lastRun = lastRamCleanRun[pkg] ?: 0L
                    if (now - bgStart >= timeoutMs && now - lastRun >= timeoutMs) {
                        val procs = procMap[pkg].orEmpty()
                        if (executeRamClean(shell, pkg, procs)) {
                            lastRamCleanRun[pkg] = now
                            appendLog("Cleaned RAM for ${apps.label(pkg) ?: pkg}")
                        }
                    }
                }
            }

            _status.value = UtilityStatusSnapshot(
                foregroundPackages = foregroundPkgs,
                runningPackages = runningPkgs,
                backgroundSinceMs = HashMap(backgroundSince),
                throttledPackages = HashSet(throttledPackages),
                lastLogs = recentLogs.toList(),
                lastTickElapsedMs = now,
            )
        }
    }

    suspend fun runForceStopNow(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val shell = access.shell ?: return@withLock 0
            val foregroundPkgs = detectForegroundPackages(shell)
            val procMap = resolveRunningPackages(shell, null)
            var count = 0
            for (pkg in settings.autoForceStopApps.value) {
                if (pkg == selfPackage || !procMap.containsKey(pkg) || foregroundPkgs.contains(pkg)) continue
                if (executeForceStop(shell, pkg, procMap[pkg].orEmpty())) {
                    lastForceStopRun[pkg] = SystemClock.elapsedRealtime()
                    backgroundSince.remove(pkg)
                    count++
                }
            }
            if (count > 0) appendLog("Manual force stop: $count app(s)")
            refreshSnapshot(foregroundPkgs, procMap.keys)
            count
        }
    }

    suspend fun runThermalReduceNow(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val shell = access.shell ?: return@withLock 0
            val foregroundPkgs = detectForegroundPackages(shell)
            val procMap = resolveRunningPackages(shell, null)
            var count = 0
            for (pkg in settings.autoThermalApps.value) {
                if (pkg == selfPackage || !procMap.containsKey(pkg) || foregroundPkgs.contains(pkg)) continue
                if (executeThermalReduce(shell, pkg, procMap[pkg].orEmpty())) {
                    lastThermalRun[pkg] = SystemClock.elapsedRealtime()
                    throttledPackages.add(pkg)
                    count++
                }
            }
            if (count > 0) appendLog("Manual thermal reduce: $count app(s)")
            refreshSnapshot(foregroundPkgs, procMap.keys)
            count
        }
    }

    suspend fun runRamCleanNow(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val shell = access.shell ?: return@withLock 0
            val foregroundPkgs = detectForegroundPackages(shell)
            val procMap = resolveRunningPackages(shell, null)
            var count = 0
            for (pkg in settings.autoRamCleanApps.value) {
                if (pkg == selfPackage || !procMap.containsKey(pkg) || foregroundPkgs.contains(pkg)) continue
                if (executeRamClean(shell, pkg, procMap[pkg].orEmpty())) {
                    lastRamCleanRun[pkg] = SystemClock.elapsedRealtime()
                    count++
                }
            }
            if (count > 0) appendLog("Manual RAM clean: $count app(s)")
            refreshSnapshot(foregroundPkgs, procMap.keys)
            count
        }
    }

    private fun refreshSnapshot(foregroundPkgs: Set<String>, runningPkgs: Set<String>) {
        _status.value = UtilityStatusSnapshot(
            foregroundPackages = foregroundPkgs,
            runningPackages = runningPkgs,
            backgroundSinceMs = HashMap(backgroundSince),
            throttledPackages = HashSet(throttledPackages),
            lastLogs = recentLogs.toList(),
            lastTickElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    private suspend fun detectForegroundPackages(shell: io.github.androidmonitor.privileged.PrivilegedShell): Set<String> {
        val output = shell.exec(FOREGROUND_CMD) ?: return emptySet()
        return PKG_COMPONENT_REGEX.findAll(output)
            .map { it.groupValues[1] }
            .filter { PACKAGE_NAME.matches(it) }
            .toSet()
    }

    private data class RunningProc(val pid: Int, val userId: Int, val name: String)

    private suspend fun resolveRunningPackages(
        shell: io.github.androidmonitor.privileged.PrivilegedShell,
        groups: List<ProcessGroup>?,
    ): Map<String, List<RunningProc>> {
        if (groups != null) {
            val map = HashMap<String, MutableList<RunningProc>>()
            for (g in groups) {
                val pkg = g.packageName ?: continue
                val list = map.getOrPut(pkg) { mutableListOf() }
                g.processes.forEach { list.add(RunningProc(it.pid, g.userId, it.name)) }
            }
            return map
        }
        val psOut = shell.exec("ps -A -o PID,USER,NAME 2>/dev/null") ?: return emptyMap()
        val map = HashMap<String, MutableList<RunningProc>>()
        for (line in psOut.lineSequence()) {
            val parts = line.trim().split(WHITESPACE, limit = 3)
            val pid = parts.firstOrNull()?.toIntOrNull() ?: continue
            val userStr = parts.getOrNull(1)?.trim() ?: continue
            val name = parts.getOrNull(2)?.trim() ?: continue
            val pkg = name.substringBefore(':')
            val userId = if (userStr.startsWith("u") && '_' in userStr) {
                userStr.substringAfter('u').substringBefore('_').toIntOrNull() ?: 0
            } else {
                0
            }
            if (PACKAGE_NAME.matches(pkg)) {
                map.getOrPut(pkg) { mutableListOf() }.add(RunningProc(pid, userId, name))
            }
        }
        return map
    }

    private suspend fun executeForceStop(
        shell: io.github.androidmonitor.privileged.PrivilegedShell,
        pkg: String,
        procs: List<RunningProc>,
    ): Boolean {
        if (!PACKAGE_NAME.matches(pkg)) return false
        val userIds = procs.map { it.userId }.distinct().ifEmpty { listOf(0) }
        val cmds = userIds.map { uid ->
            val userFlag = if (uid > 0) "--user $uid " else ""
            "am force-stop ${userFlag}$pkg 2>/dev/null"
        }
        return shell.exec(cmds.joinToString("; ") + "; echo \"exit:\$?\"")?.contains("exit:0") == true
    }

    private suspend fun executeThermalReduce(
        shell: io.github.androidmonitor.privileged.PrivilegedShell,
        pkg: String,
        procs: List<RunningProc>,
    ): Boolean {
        if (!PACKAGE_NAME.matches(pkg)) return false
        val userIds = procs.map { it.userId }.distinct().ifEmpty { listOf(0) }
        val cmds = mutableListOf<String>()
        for (uid in userIds) {
            val userFlag = if (uid > 0) "--user $uid " else ""
            cmds.add("(am set-standby-bucket ${userFlag}$pkg restricted 2>/dev/null || am set-standby-bucket ${userFlag}$pkg rare 2>/dev/null)")
            cmds.add("am set-inactive ${userFlag}$pkg true 2>/dev/null")
            cmds.add("cmd appops set ${userFlag}$pkg RUN_ANY_IN_BACKGROUND ignore 2>/dev/null")
        }
        for (proc in procs) {
            cmds.add("renice -n 19 -p ${proc.pid} 2>/dev/null")
        }
        shell.exec(cmds.joinToString("; "))
        return true
    }

    private suspend fun restoreThermalState(
        shell: io.github.androidmonitor.privileged.PrivilegedShell,
        pkg: String,
        procs: List<RunningProc>,
    ) {
        if (!PACKAGE_NAME.matches(pkg)) return
        val userIds = procs.map { it.userId }.distinct().ifEmpty { listOf(0) }
        val cmds = mutableListOf<String>()
        for (uid in userIds) {
            val userFlag = if (uid > 0) "--user $uid " else ""
            cmds.add("am set-standby-bucket ${userFlag}$pkg active 2>/dev/null")
            cmds.add("am set-inactive ${userFlag}$pkg false 2>/dev/null")
            cmds.add("cmd appops set ${userFlag}$pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
        }
        for (proc in procs) {
            cmds.add("renice -n 0 -p ${proc.pid} 2>/dev/null")
        }
        shell.exec(cmds.joinToString("; "))
    }

    private suspend fun executeRamClean(
        shell: io.github.androidmonitor.privileged.PrivilegedShell,
        pkg: String,
        procs: List<RunningProc>,
    ): Boolean {
        if (!PACKAGE_NAME.matches(pkg)) return false
        val cmds = mutableListOf<String>()
        val procEntries = procs.distinctBy { it.userId to it.name }.ifEmpty { listOf(RunningProc(0, 0, pkg)) }
        for (entry in procEntries) {
            if (PROCESS_NAME.matches(entry.name)) {
                val userFlag = if (entry.userId > 0) "--user ${entry.userId} " else ""
                cmds.add("am send-trim-memory ${userFlag}${entry.name} COMPLETE 2>/dev/null")
            }
        }
        for (proc in procs) {
            cmds.add("[ -w /proc/${proc.pid}/reclaim ] && echo file > /proc/${proc.pid}/reclaim 2>/dev/null")
        }
        cmds.add("[ -w /sys/block/zram0/compact ] && echo 1 > /sys/block/zram0/compact 2>/dev/null")
        shell.exec(cmds.joinToString("; "))
        return true
    }

    private fun appendLog(message: String) {
        val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        recentLogs.addFirst("$stamp · $message")
        while (recentLogs.size > MAX_LOGS) {
            recentLogs.removeLast()
        }
    }

    private companion object {
        const val EVALUATE_INTERVAL_MS = 10_000L
        const val MAX_LOGS = 8
        const val FOREGROUND_CMD =
            "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 6; " +
                "dumpsys window displays 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 6"
        val WHITESPACE = Regex("\\s+")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val PROCESS_NAME = Regex("[A-Za-z][A-Za-z0-9_.:\\-]+")
        val PKG_COMPONENT_REGEX = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/")
    }
}
