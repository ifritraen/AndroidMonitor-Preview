package io.github.androidmonitor.data

import android.content.Context
import androidx.core.content.edit
import io.github.androidmonitor.privileged.AccessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _refreshMs = MutableStateFlow(prefs.getLong(KEY_REFRESH_MS, DEFAULT_REFRESH_MS))
    val refreshMs: StateFlow<Long> = _refreshMs.asStateFlow()

    private val _defaultTab = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_TAB, null)?.let { runCatching { DefaultTab.valueOf(it) }.getOrNull() }
            ?: DefaultTab.PERFORMANCE
    )
    val defaultTab: StateFlow<DefaultTab> = _defaultTab.asStateFlow()

    private val _processGrouping = MutableStateFlow(
        prefs.getString(KEY_PROCESS_GROUPING, null)?.let { runCatching { ProcessGrouping.valueOf(it) }.getOrNull() }
            ?: ProcessGrouping.SEPARATE
    )
    val processGrouping: StateFlow<ProcessGrouping> = _processGrouping.asStateFlow()

    private val _pinnedProcesses = MutableStateFlow(
        prefs.getStringSet(KEY_PINNED_PROCESSES, emptySet())?.toSet() ?: emptySet()
    )
    val pinnedProcesses: StateFlow<Set<String>> = _pinnedProcesses.asStateFlow()

    private val _lockedProcesses = MutableStateFlow(
        prefs.getStringSet(KEY_LOCKED_PROCESSES, emptySet())?.toSet() ?: emptySet()
    )
    val lockedProcesses: StateFlow<Set<String>> = _lockedProcesses.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY_ENABLED, false))
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _overlayType = MutableStateFlow(
        prefs.getString(KEY_OVERLAY_TYPE, null)?.let { runCatching { OverlayType.valueOf(it) }.getOrNull() }
            ?: OverlayType.RESOURCE_HUD
    )
    val overlayType: StateFlow<OverlayType> = _overlayType.asStateFlow()

    private val _overlayTopCount = MutableStateFlow(prefs.getInt(KEY_OVERLAY_TOP_COUNT, 5))
    val overlayTopCount: StateFlow<Int> = _overlayTopCount.asStateFlow()

    private val _overlaySingleProcessKey = MutableStateFlow(prefs.getString(KEY_OVERLAY_SINGLE_KEY, null))
    val overlaySingleProcessKey: StateFlow<String?> = _overlaySingleProcessKey.asStateFlow()

    private val _overlayScale = MutableStateFlow(prefs.getFloat(KEY_OVERLAY_SCALE, 1.0f))
    val overlayScale: StateFlow<Float> = _overlayScale.asStateFlow()

    private val _overlayOpacity = MutableStateFlow(prefs.getFloat(KEY_OVERLAY_OPACITY, 0.9f))
    val overlayOpacity: StateFlow<Float> = _overlayOpacity.asStateFlow()

    private val _notificationEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATION_ENABLED, false))
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled.asStateFlow()

    private val _compactProcesses = MutableStateFlow(prefs.getBoolean(KEY_COMPACT_PROCESSES, false))
    val compactProcesses: StateFlow<Boolean> = _compactProcesses.asStateFlow()

    private val _autoForceStopEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_FORCE_STOP_ENABLED, false))
    val autoForceStopEnabled: StateFlow<Boolean> = _autoForceStopEnabled.asStateFlow()

    private val _autoForceStopApps = MutableStateFlow(
        prefs.getStringSet(KEY_AUTO_FORCE_STOP_APPS, emptySet())?.toSet() ?: emptySet()
    )
    val autoForceStopApps: StateFlow<Set<String>> = _autoForceStopApps.asStateFlow()

    private val _autoForceStopTimeoutMin = MutableStateFlow(prefs.getInt(KEY_AUTO_FORCE_STOP_TIMEOUT_MIN, 15))
    val autoForceStopTimeoutMin: StateFlow<Int> = _autoForceStopTimeoutMin.asStateFlow()

    private val _autoThermalEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_THERMAL_ENABLED, false))
    val autoThermalEnabled: StateFlow<Boolean> = _autoThermalEnabled.asStateFlow()

    private val _autoThermalApps = MutableStateFlow(
        prefs.getStringSet(KEY_AUTO_THERMAL_APPS, emptySet())?.toSet() ?: emptySet()
    )
    val autoThermalApps: StateFlow<Set<String>> = _autoThermalApps.asStateFlow()

    private val _autoThermalTimeoutMin = MutableStateFlow(prefs.getInt(KEY_AUTO_THERMAL_TIMEOUT_MIN, 5))
    val autoThermalTimeoutMin: StateFlow<Int> = _autoThermalTimeoutMin.asStateFlow()

    private val _autoRamCleanEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RAM_CLEAN_ENABLED, false))
    val autoRamCleanEnabled: StateFlow<Boolean> = _autoRamCleanEnabled.asStateFlow()

    private val _autoRamCleanApps = MutableStateFlow(
        prefs.getStringSet(KEY_AUTO_RAM_CLEAN_APPS, emptySet())?.toSet() ?: emptySet()
    )
    val autoRamCleanApps: StateFlow<Set<String>> = _autoRamCleanApps.asStateFlow()

    private val _autoRamCleanTimeoutMin = MutableStateFlow(prefs.getInt(KEY_AUTO_RAM_CLEAN_TIMEOUT_MIN, 10))
    val autoRamCleanTimeoutMin: StateFlow<Int> = _autoRamCleanTimeoutMin.asStateFlow()

    val anyAutoUtilityEnabled: Boolean
        get() = _autoForceStopEnabled.value || _autoThermalEnabled.value || _autoRamCleanEnabled.value

    fun setCompactProcesses(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COMPACT_PROCESSES, enabled) }
        _compactProcesses.value = enabled
    }

    fun setRefreshMs(value: Long) {
        prefs.edit { putLong(KEY_REFRESH_MS, value) }
        _refreshMs.value = value
    }

    fun setDefaultTab(tab: DefaultTab) {
        prefs.edit { putString(KEY_DEFAULT_TAB, tab.name) }
        _defaultTab.value = tab
    }

    fun setProcessGrouping(grouping: ProcessGrouping) {
        prefs.edit { putString(KEY_PROCESS_GROUPING, grouping.name) }
        _processGrouping.value = grouping
    }

    fun togglePinProcess(key: String) {
        val current = _pinnedProcesses.value.toMutableSet()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        val immutable = current.toSet()
        prefs.edit { putStringSet(KEY_PINNED_PROCESSES, immutable) }
        _pinnedProcesses.value = immutable
    }

    fun isProcessPinned(key: String): Boolean = _pinnedProcesses.value.contains(key)

    fun toggleLockProcess(key: String) {
        val current = _lockedProcesses.value.toMutableSet()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        val immutable = current.toSet()
        prefs.edit { putStringSet(KEY_LOCKED_PROCESSES, immutable) }
        _lockedProcesses.value = immutable
    }

    fun isProcessLocked(key: String): Boolean = _lockedProcesses.value.contains(key)

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_OVERLAY_ENABLED, enabled) }
        _overlayEnabled.value = enabled
    }

    fun setOverlayType(type: OverlayType) {
        prefs.edit { putString(KEY_OVERLAY_TYPE, type.name) }
        _overlayType.value = type
    }

    fun setOverlayTopCount(count: Int) {
        prefs.edit { putInt(KEY_OVERLAY_TOP_COUNT, count) }
        _overlayTopCount.value = count
    }

    fun setOverlaySingleProcessKey(key: String?) {
        prefs.edit { putString(KEY_OVERLAY_SINGLE_KEY, key) }
        _overlaySingleProcessKey.value = key
    }

    fun setOverlayScale(scale: Float) {
        prefs.edit { putFloat(KEY_OVERLAY_SCALE, scale) }
        _overlayScale.value = scale
    }

    fun setOverlayOpacity(opacity: Float) {
        prefs.edit { putFloat(KEY_OVERLAY_OPACITY, opacity) }
        _overlayOpacity.value = opacity
    }

    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFICATION_ENABLED, enabled) }
        _notificationEnabled.value = enabled
    }

    fun setAutoForceStopEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_FORCE_STOP_ENABLED, enabled) }
        _autoForceStopEnabled.value = enabled
    }

    fun toggleAutoForceStopApp(packageName: String) {
        val current = _autoForceStopApps.value.toMutableSet()
        if (current.contains(packageName)) current.remove(packageName) else current.add(packageName)
        val immutable = current.toSet()
        prefs.edit { putStringSet(KEY_AUTO_FORCE_STOP_APPS, immutable) }
        _autoForceStopApps.value = immutable
    }

    fun setAutoForceStopTimeoutMin(minutes: Int) {
        prefs.edit { putInt(KEY_AUTO_FORCE_STOP_TIMEOUT_MIN, minutes) }
        _autoForceStopTimeoutMin.value = minutes
    }

    fun setAutoThermalEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_THERMAL_ENABLED, enabled) }
        _autoThermalEnabled.value = enabled
    }

    fun toggleAutoThermalApp(packageName: String) {
        val current = _autoThermalApps.value.toMutableSet()
        if (current.contains(packageName)) current.remove(packageName) else current.add(packageName)
        val immutable = current.toSet()
        prefs.edit { putStringSet(KEY_AUTO_THERMAL_APPS, immutable) }
        _autoThermalApps.value = immutable
    }

    fun setAutoThermalTimeoutMin(minutes: Int) {
        prefs.edit { putInt(KEY_AUTO_THERMAL_TIMEOUT_MIN, minutes) }
        _autoThermalTimeoutMin.value = minutes
    }

    fun setAutoRamCleanEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_RAM_CLEAN_ENABLED, enabled) }
        _autoRamCleanEnabled.value = enabled
    }

    fun toggleAutoRamCleanApp(packageName: String) {
        val current = _autoRamCleanApps.value.toMutableSet()
        if (current.contains(packageName)) current.remove(packageName) else current.add(packageName)
        val immutable = current.toSet()
        prefs.edit { putStringSet(KEY_AUTO_RAM_CLEAN_APPS, immutable) }
        _autoRamCleanApps.value = immutable
    }

    fun setAutoRamCleanTimeoutMin(minutes: Int) {
        prefs.edit { putInt(KEY_AUTO_RAM_CLEAN_TIMEOUT_MIN, minutes) }
        _autoRamCleanTimeoutMin.value = minutes
    }

    var preferredAccess: AccessMode
        get() = prefs.getString(KEY_ACCESS, null)
            ?.let { runCatching { AccessMode.valueOf(it) }.getOrNull() }
            ?: AccessMode.STANDARD
        set(value) = prefs.edit { putString(KEY_ACCESS, value.name) }

    companion object {
        val REFRESH_OPTIONS = listOf(500L, 1_000L, 2_000L, 5_000L)
        val TOP_PROCESS_OPTIONS = listOf(5, 10, 15, 20)
        val UTILITY_TIMEOUT_OPTIONS_MIN = listOf(1, 5, 10, 15, 30, 60, 120)
        private const val DEFAULT_REFRESH_MS = 1_000L
        private const val KEY_REFRESH_MS = "refresh_ms"
        private const val KEY_ACCESS = "access_mode"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_PROCESS_GROUPING = "process_grouping"
        private const val KEY_PINNED_PROCESSES = "pinned_processes"
        private const val KEY_LOCKED_PROCESSES = "locked_processes"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_TYPE = "overlay_type"
        private const val KEY_OVERLAY_TOP_COUNT = "overlay_top_count"
        private const val KEY_OVERLAY_SINGLE_KEY = "overlay_single_key"
        private const val KEY_OVERLAY_SCALE = "overlay_scale"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_COMPACT_PROCESSES = "compact_processes"
        private const val KEY_AUTO_FORCE_STOP_ENABLED = "auto_force_stop_enabled"
        private const val KEY_AUTO_FORCE_STOP_APPS = "auto_force_stop_apps"
        private const val KEY_AUTO_FORCE_STOP_TIMEOUT_MIN = "auto_force_stop_timeout_min"
        private const val KEY_AUTO_THERMAL_ENABLED = "auto_thermal_enabled"
        private const val KEY_AUTO_THERMAL_APPS = "auto_thermal_apps"
        private const val KEY_AUTO_THERMAL_TIMEOUT_MIN = "auto_thermal_timeout_min"
        private const val KEY_AUTO_RAM_CLEAN_ENABLED = "auto_ram_clean_enabled"
        private const val KEY_AUTO_RAM_CLEAN_APPS = "auto_ram_clean_apps"
        private const val KEY_AUTO_RAM_CLEAN_TIMEOUT_MIN = "auto_ram_clean_timeout_min"
    }
}
