package io.github.androidmonitor.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.androidmonitor.BuildConfig
import io.github.androidmonitor.data.DefaultTab
import io.github.androidmonitor.data.OverlayType
import io.github.androidmonitor.data.ProcessGrouping
import io.github.androidmonitor.data.SettingsStore
import io.github.androidmonitor.privileged.AccessManager
import io.github.androidmonitor.privileged.AccessMode
import io.github.androidmonitor.privileged.AccessState
import io.github.androidmonitor.privileged.RootStatus
import io.github.androidmonitor.privileged.ShizukuStatus
import io.github.androidmonitor.service.MonitorOverlayService
import io.github.androidmonitor.ui.MonitorViewModel
import io.github.androidmonitor.ui.components.ScreenTitle
import io.github.androidmonitor.ui.components.SectionLabel
import androidx.compose.ui.unit.dp

private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
private const val SHIZUKU_GUIDE_URL = "https://shizuku.rikka.app/guide/setup/"

@Composable
fun AccessScreen(vm: MonitorViewModel) {
    val access by vm.access.state.collectAsStateWithLifecycle()
    val refreshMs by vm.settings.refreshMs.collectAsStateWithLifecycle()
    val defaultTab by vm.settings.defaultTab.collectAsStateWithLifecycle()
    val processGrouping by vm.settings.processGrouping.collectAsStateWithLifecycle()
    val overlayEnabled by vm.settings.overlayEnabled.collectAsStateWithLifecycle()
    val overlayType by vm.settings.overlayType.collectAsStateWithLifecycle()
    val overlayTopCount by vm.settings.overlayTopCount.collectAsStateWithLifecycle()
    val notificationEnabled by vm.settings.notificationEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.settings.setNotificationEnabled(true)
            MonitorOverlayService.start(context)
        } else {
            Toast.makeText(context, "Notification permission is required to post monitor status", Toast.LENGTH_SHORT).show()
            vm.settings.setNotificationEnabled(false)
        }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            vm.settings.setOverlayEnabled(true)
            MonitorOverlayService.start(context)
        } else {
            Toast.makeText(context, "'Display over other apps' permission is required for overlay", Toast.LENGTH_SHORT).show()
            vm.settings.setOverlayEnabled(false)
        }
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle("Access & settings")
            Text(
                "Android limits what regular apps can see. Choose how Android Monitor reads your system stats.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AccessOption(
                title = "Standard",
                icon = Icons.Outlined.PhoneAndroid,
                description = "No setup. Memory, network, battery, CPU clock speeds, and GPU stats " +
                    "where the device allows it. CPU usage is estimated.",
                active = access.mode == AccessMode.STANDARD,
            ) {
                if (access.mode != AccessMode.STANDARD) {
                    OutlinedButton(onClick = vm.access::useStandard) { Text("Use standard") }
                }
            }

            AccessOption(
                title = "Shizuku",
                icon = Icons.Outlined.Bolt,
                description = "Unlocks real CPU usage, per-core load, the process list with End task, " +
                    "and every thermal sensor. No root needed: Shizuku starts through wireless debugging.",
                active = access.mode == AccessMode.SHIZUKU,
                badge = "Recommended",
            ) {
                ShizukuActions(access, vm.access)
            }

            AccessOption(
                title = "Root",
                icon = Icons.Outlined.VpnKey,
                description = "For rooted devices (Magisk, KernelSU, APatch). Unlocks everything Shizuku does.",
                active = access.mode == AccessMode.ROOT,
            ) {
                RootActions(access, vm.access)
            }

            SectionLabel("Default screen on launch", Modifier.padding(top = 8.dp))
            DefaultTabPicker(defaultTab, vm.settings::setDefaultTab)

            SectionLabel("Process list grouping", Modifier.padding(top = 8.dp))
            ProcessGroupingPicker(processGrouping, vm.settings::setProcessGrouping)

            SectionLabel("Refresh rate", Modifier.padding(top = 8.dp))
            RefreshRatePicker(refreshMs, vm.settings::setRefreshMs)

            SectionLabel("Floating overlay HUD", Modifier.padding(top = 8.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable overlay", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Floating HUD over other apps. Move, resize, or minimize anytime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    if (!Settings.canDrawOverlays(context)) {
                                        Toast.makeText(context, "Please allow 'Display over other apps' to enable the floating overlay", Toast.LENGTH_LONG).show()
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                        overlayLauncher.launch(intent)
                                    } else {
                                        vm.settings.setOverlayEnabled(true)
                                        MonitorOverlayService.start(context)
                                    }
                                } else {
                                    vm.settings.setOverlayEnabled(false)
                                    MonitorOverlayService.update(context)
                                }
                            },
                        )
                    }

                    if (overlayEnabled) {
                        Text("Overlay variation", style = MaterialTheme.typography.labelMedium)
                        OverlayTypePicker(overlayType) { type ->
                            vm.settings.setOverlayType(type)
                            MonitorOverlayService.update(context)
                        }

                        if (overlayType == OverlayType.TOP_PROCESSES) {
                            Text("Top processes count", style = MaterialTheme.typography.labelMedium)
                            TopCountPicker(overlayTopCount) { count ->
                                vm.settings.setOverlayTopCount(count)
                                MonitorOverlayService.update(context)
                            }
                        }
                    }
                }
            }

            SectionLabel("Live status notification", Modifier.padding(top = 8.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ongoing monitor notification", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Shows live CPU, RAM, GPU, network and temp in the notification shade.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    vm.settings.setNotificationEnabled(true)
                                    MonitorOverlayService.start(context)
                                }
                            } else {
                                vm.settings.setNotificationEnabled(false)
                                MonitorOverlayService.update(context)
                            }
                        },
                    )
                }
            }

            SectionLabel("About", Modifier.padding(top = 8.dp))
            Text(
                "Android Monitor ${BuildConfig.VERSION_NAME}. Free and open source under the MIT License.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(64.dp))
        }
    }
}

@Composable
private fun AccessOption(
    title: String,
    icon: ImageVector,
    description: String,
    active: Boolean,
    badge: String? = null,
    actions: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (active) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (active) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@Composable
private fun ShizukuActions(state: AccessState, access: AccessManager) {
    val context = LocalContext.current
    val openShizuku = { access.shizukuLaunchIntent()?.let { context.startActivity(it) } ?: Unit }
    val active = state.mode == AccessMode.SHIZUKU
    val message = when (state.shizuku) {
        ShizukuStatus.NOT_INSTALLED -> "Shizuku isn't installed. It's free and open source."
        ShizukuStatus.NOT_RUNNING -> "Shizuku is installed but not running. Open it and start it with wireless debugging, then come back."
        ShizukuStatus.OUTDATED -> "Your Shizuku version is too old. Please update it."
        ShizukuStatus.PERMISSION_NEEDED -> "Shizuku is running. Tap Use Shizuku and allow access."
        ShizukuStatus.PERMISSION_DENIED -> "Access was denied. Allow Android Monitor in Shizuku's list of apps."
        ShizukuStatus.READY -> "Shizuku is ready."
        ShizukuStatus.CONNECTING -> "Connecting…"
        ShizukuStatus.CONNECTED -> if (active) "Connected." else "Shizuku is ready."
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.shizuku) {
                ShizukuStatus.NOT_INSTALLED -> {
                    Button(onClick = { openUrl(context, SHIZUKU_DOWNLOAD_URL) }) { Text("Get Shizuku") }
                    TextButton(onClick = { openUrl(context, SHIZUKU_GUIDE_URL) }) { Text("Setup guide") }
                }
                ShizukuStatus.NOT_RUNNING -> {
                    Button(onClick = openShizuku) { Text("Open Shizuku") }
                    TextButton(onClick = { openUrl(context, SHIZUKU_GUIDE_URL) }) { Text("Setup guide") }
                }
                ShizukuStatus.OUTDATED -> Button(onClick = { openUrl(context, SHIZUKU_DOWNLOAD_URL) }) { Text("Update Shizuku") }
                ShizukuStatus.PERMISSION_DENIED -> Button(onClick = openShizuku) { Text("Open Shizuku") }
                ShizukuStatus.CONNECTING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                ShizukuStatus.PERMISSION_NEEDED, ShizukuStatus.READY, ShizukuStatus.CONNECTED ->
                    if (!active) Button(onClick = access::useShizuku) { Text("Use Shizuku") }
            }
        }
    }
}

@Composable
private fun RootActions(state: AccessState, access: AccessManager) {
    val active = state.mode == AccessMode.ROOT
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state.root) {
            RootStatus.CHECKING -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Waiting for root permission…", style = MaterialTheme.typography.bodySmall)
            }
            RootStatus.UNAVAILABLE -> {
                Text(
                    "Root access wasn't granted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = access::useRoot) { Text("Try again") }
            }
            RootStatus.UNKNOWN, RootStatus.AVAILABLE ->
                if (!active) OutlinedButton(onClick = access::useRoot) { Text("Use root") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshRatePicker(selected: Long, onSelect: (Long) -> Unit) {
    val options = SettingsStore.REFRESH_OPTIONS
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, ms ->
            SegmentedButton(
                selected = ms == selected,
                onClick = { onSelect(ms) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(if (ms % 1000 == 0L) "${ms / 1000} s" else "${ms / 1000.0} s")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultTabPicker(selected: DefaultTab, onSelect: (DefaultTab) -> Unit) {
    val options = DefaultTab.entries
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(tab.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessGroupingPicker(selected: ProcessGrouping, onSelect: (ProcessGrouping) -> Unit) {
    val options = ProcessGrouping.entries
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, grouping ->
            SegmentedButton(
                selected = grouping == selected,
                onClick = { onSelect(grouping) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(grouping.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayTypePicker(selected: OverlayType, onSelect: (OverlayType) -> Unit) {
    val options = OverlayType.entries
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selected,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(type.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopCountPicker(selected: Int, onSelect: (Int) -> Unit) {
    val options = SettingsStore.TOP_PROCESS_OPTIONS
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, count ->
            SegmentedButton(
                selected = count == selected,
                onClick = { onSelect(count) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text("$count")
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No browser installed; nothing useful to do.
    }
}
