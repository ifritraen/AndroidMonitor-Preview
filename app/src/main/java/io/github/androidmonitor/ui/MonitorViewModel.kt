package io.github.androidmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.androidmonitor.MonitorApp
import io.github.androidmonitor.data.AppInfoCache
import io.github.androidmonitor.data.InstalledAppInfo
import io.github.androidmonitor.data.MonitorState
import io.github.androidmonitor.data.ProcessGroup
import io.github.androidmonitor.data.ProcessListState
import io.github.androidmonitor.data.SettingsStore
import io.github.androidmonitor.data.UtilityStatusSnapshot
import io.github.androidmonitor.privileged.AccessManager
import io.github.androidmonitor.service.MonitorOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MonitorApp
    val access: AccessManager = app.access
    val settings: SettingsStore = app.settings

    private val systemSampler = app.systemSampler
    private val processSampler = app.processSampler
    private val utilityManager = app.utilityManager
    val apps: AppInfoCache get() = processSampler.apps
    val utilityStatus: StateFlow<UtilityStatusSnapshot> = utilityManager.status

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    /** Live readings. Polling pauses while the app is in the background. */
    val monitor: StateFlow<MonitorState?> = poll { systemSampler.sample() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Only polled while the Processes screen is on screen. */
    val processes: StateFlow<ProcessListState> = poll(minIntervalMs = 1_000) { processSampler.sample() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), ProcessListState.Loading)

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = apps.installedApps()
        }
    }

    fun refreshUtilityStatus() {
        viewModelScope.launch {
            utilityManager.evaluate()
        }
    }

    fun syncUtilityService() {
        if (settings.anyAutoUtilityEnabled) {
            MonitorOverlayService.start(app)
            MonitorOverlayService.update(app)
        } else if (settings.overlayEnabled.value || settings.notificationEnabled.value) {
            MonitorOverlayService.update(app)
        } else {
            MonitorOverlayService.stop(app)
        }
    }

    fun runForceStopNow(onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(utilityManager.runForceStopNow()) }
    }

    fun runThermalReduceNow(onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(utilityManager.runThermalReduceNow()) }
    }

    fun runRamCleanNow(onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(utilityManager.runRamCleanNow()) }
    }

    fun endTask(group: ProcessGroup, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(processSampler.endTask(group)) }
    }

    fun forceStop(group: ProcessGroup, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(processSampler.forceStop(group)) }
    }

    fun togglePin(group: ProcessGroup) {
        settings.togglePinProcess(group.key)
    }

    fun toggleLock(group: ProcessGroup, onResult: ((Boolean) -> Unit)? = null) {
        val willLock = !settings.isProcessLocked(group.key)
        settings.toggleLockProcess(group.key)
        viewModelScope.launch {
            val ok = processSampler.setProcessPriority(group, willLock)
            onResult?.invoke(ok)
        }
    }

    fun reduceSwap(targetPackages: Set<String>, onResult: (Long) -> Unit) {
        val pinnedKeys = settings.pinnedProcesses.value
        val lockedKeys = settings.lockedProcesses.value
        val exempt = mutableSetOf(app.packageName)
        for (key in pinnedKeys + lockedKeys) {
            if (key.startsWith("pkg:")) exempt.add(key.removePrefix("pkg:"))
        }
        viewModelScope.launch {
            val freed = processSampler.reduceSwap(targetPackages, exempt)
            onResult(freed)
        }
    }

    private fun <T> poll(minIntervalMs: Long = 0, sample: suspend () -> T): Flow<T> = flow {
        while (true) {
            emit(sample())
            delay(maxOf(settings.refreshMs.value, minIntervalMs))
        }
    }.flowOn(Dispatchers.Default)
}
