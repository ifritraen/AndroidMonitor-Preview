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

    var preferredAccess: AccessMode
        get() = prefs.getString(KEY_ACCESS, null)
            ?.let { runCatching { AccessMode.valueOf(it) }.getOrNull() }
            ?: AccessMode.STANDARD
        set(value) = prefs.edit { putString(KEY_ACCESS, value.name) }

    companion object {
        val REFRESH_OPTIONS = listOf(500L, 1_000L, 2_000L, 5_000L)
        val TOP_PROCESS_OPTIONS = listOf(5, 10, 15, 20)
        private const val DEFAULT_REFRESH_MS = 1_000L
        private const val KEY_REFRESH_MS = "refresh_ms"
        private const val KEY_ACCESS = "access_mode"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_PROCESS_GROUPING = "process_grouping"
        private const val KEY_PINNED_PROCESSES = "pinned_processes"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_TYPE = "overlay_type"
        private const val KEY_OVERLAY_TOP_COUNT = "overlay_top_count"
        private const val KEY_OVERLAY_SINGLE_KEY = "overlay_single_key"
        private const val KEY_OVERLAY_SCALE = "overlay_scale"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    }
}
