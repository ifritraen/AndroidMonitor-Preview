package io.github.androidmonitor.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.androidmonitor.data.AppInfoCache
import io.github.androidmonitor.data.InstalledAppInfo
import io.github.androidmonitor.data.SettingsStore
import io.github.androidmonitor.data.UtilityStatusSnapshot
import io.github.androidmonitor.privileged.AccessMode
import io.github.androidmonitor.ui.MonitorViewModel
import io.github.androidmonitor.ui.components.NoticeCard
import io.github.androidmonitor.ui.components.ScreenTitle
import io.github.androidmonitor.ui.theme.LocalMetricPalette
import kotlinx.coroutines.delay

private enum class UtilityPickerTarget(val title: String) {
    FORCE_STOP("Background App Force Stop"),
    THERMAL_REDUCE("Auto Thermal Reduce"),
    RAM_CLEAN("Auto RAM Clean"),
}

@Composable
fun UtilitiesScreen(vm: MonitorViewModel, onOpenAccess: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalMetricPalette.current
    val access by vm.access.state.collectAsStateWithLifecycle()
    val utilityStatus by vm.utilityStatus.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()

    val forceStopEnabled by vm.settings.autoForceStopEnabled.collectAsStateWithLifecycle()
    val forceStopApps by vm.settings.autoForceStopApps.collectAsStateWithLifecycle()
    val forceStopTimeoutMin by vm.settings.autoForceStopTimeoutMin.collectAsStateWithLifecycle()

    val thermalEnabled by vm.settings.autoThermalEnabled.collectAsStateWithLifecycle()
    val thermalApps by vm.settings.autoThermalApps.collectAsStateWithLifecycle()
    val thermalTimeoutMin by vm.settings.autoThermalTimeoutMin.collectAsStateWithLifecycle()

    val ramCleanEnabled by vm.settings.autoRamCleanEnabled.collectAsStateWithLifecycle()
    val ramCleanApps by vm.settings.autoRamCleanApps.collectAsStateWithLifecycle()
    val ramCleanTimeoutMin by vm.settings.autoRamCleanTimeoutMin.collectAsStateWithLifecycle()

    var pickerTarget by remember { mutableStateOf<UtilityPickerTarget?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        vm.syncUtilityService()
    }

    fun ensureBackgroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.syncUtilityService()
    }

    LaunchedEffect(access.mode) {
        if (access.mode != AccessMode.STANDARD) {
            while (true) {
                vm.refreshUtilityStatus()
                delay(5_000L)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column {
                ScreenTitle("Utilities")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Automate background app control after idle periods without activity. Add apps here or directly from the Processes tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (access.mode == AccessMode.STANDARD) {
            item(key = "needs-access") {
                NoticeCard(
                    icon = Icons.Outlined.Lock,
                    title = "Utilities need Shizuku or root",
                    body = "Background app force-stop, thermal throttling, and memory trim commands require Shizuku or root access. You can configure your app lists now and connect access to activate automation.",
                    actionLabel = "Set up access",
                    onAction = onOpenAccess,
                )
            }
        }

        if (utilityStatus.lastLogs.isNotEmpty()) {
            item(key = "activity-log") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Recent utility actions",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        utilityStatus.lastLogs.take(4).forEach { entry ->
                            Text(
                                entry,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 1. Background app force stop
        item(key = "force-stop-card") {
            UtilitySectionCard(
                title = "Background app force stop",
                subtitle = "Force-stops selected apps after they stay in the background without activity for the configured duration.",
                icon = Icons.Outlined.PowerSettingsNew,
                accentColor = MaterialTheme.colorScheme.error,
                enabled = forceStopEnabled,
                onToggleEnabled = { enabled ->
                    vm.settings.setAutoForceStopEnabled(enabled)
                    if (enabled) ensureBackgroundService() else vm.syncUtilityService()
                },
                timeoutMin = forceStopTimeoutMin,
                onTimeoutSelected = { vm.settings.setAutoForceStopTimeoutMin(it) },
                packages = forceStopApps,
                apps = vm.apps,
                status = utilityStatus,
                privileged = access.mode != AccessMode.STANDARD,
                actionLabel = "Force stop now",
                onRunNow = {
                    vm.runForceStopNow { count ->
                        val msg = if (count > 0) "Force stopped $count background app(s)" else "No idle background apps from list running"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                onAddApp = {
                    vm.loadInstalledApps()
                    pickerTarget = UtilityPickerTarget.FORCE_STOP
                },
                onRemoveApp = { pkg -> vm.settings.toggleAutoForceStopApp(pkg) },
            )
        }

        // 2. Auto thermal reduce by specific list
        item(key = "thermal-card") {
            UtilitySectionCard(
                title = "Auto thermal reduce",
                subtitle = "Moves idle listed apps to restricted standby, blocks background wakeups, and lowers CPU priority — reduces heat without force-stopping or killing.",
                icon = Icons.Outlined.Thermostat,
                accentColor = palette.thermal,
                enabled = thermalEnabled,
                onToggleEnabled = { enabled ->
                    vm.settings.setAutoThermalEnabled(enabled)
                    if (enabled) ensureBackgroundService() else vm.syncUtilityService()
                },
                timeoutMin = thermalTimeoutMin,
                onTimeoutSelected = { vm.settings.setAutoThermalTimeoutMin(it) },
                packages = thermalApps,
                apps = vm.apps,
                status = utilityStatus,
                privileged = access.mode != AccessMode.STANDARD,
                actionLabel = "Reduce now",
                onRunNow = {
                    vm.runThermalReduceNow { count ->
                        val msg = if (count > 0) "Applied thermal reduction to $count app(s)" else "No idle background apps from list running"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                onAddApp = {
                    vm.loadInstalledApps()
                    pickerTarget = UtilityPickerTarget.THERMAL_REDUCE
                },
                onRemoveApp = { pkg -> vm.settings.toggleAutoThermalApp(pkg) },
            )
        }

        // 3. Auto ram clean by app list
        item(key = "ram-clean-card") {
            UtilitySectionCard(
                title = "Auto RAM clean",
                subtitle = "Sends memory trim signals and reclaims file caches for idle listed apps — releases memory resources without force-stopping or killing.",
                icon = Icons.Outlined.CleaningServices,
                accentColor = palette.memory,
                enabled = ramCleanEnabled,
                onToggleEnabled = { enabled ->
                    vm.settings.setAutoRamCleanEnabled(enabled)
                    if (enabled) ensureBackgroundService() else vm.syncUtilityService()
                },
                timeoutMin = ramCleanTimeoutMin,
                onTimeoutSelected = { vm.settings.setAutoRamCleanTimeoutMin(it) },
                packages = ramCleanApps,
                apps = vm.apps,
                status = utilityStatus,
                privileged = access.mode != AccessMode.STANDARD,
                actionLabel = "Clean now",
                onRunNow = {
                    vm.runRamCleanNow { count ->
                        val msg = if (count > 0) "Released memory caches for $count app(s)" else "No idle background apps from list running"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                onAddApp = {
                    vm.loadInstalledApps()
                    pickerTarget = UtilityPickerTarget.RAM_CLEAN
                },
                onRemoveApp = { pkg -> vm.settings.toggleAutoRamCleanApp(pkg) },
            )
        }
    }

    val target = pickerTarget
    if (target != null) {
        val selectedPkgs = when (target) {
            UtilityPickerTarget.FORCE_STOP -> forceStopApps
            UtilityPickerTarget.THERMAL_REDUCE -> thermalApps
            UtilityPickerTarget.RAM_CLEAN -> ramCleanApps
        }
        UtilityAppPickerDialog(
            title = "Add apps · ${target.title}",
            installedApps = installedApps,
            selectedPackages = selectedPkgs,
            apps = vm.apps,
            onTogglePackage = { pkg ->
                when (target) {
                    UtilityPickerTarget.FORCE_STOP -> vm.settings.toggleAutoForceStopApp(pkg)
                    UtilityPickerTarget.THERMAL_REDUCE -> vm.settings.toggleAutoThermalApp(pkg)
                    UtilityPickerTarget.RAM_CLEAN -> vm.settings.toggleAutoRamCleanApp(pkg)
                }
            },
            onDismiss = { pickerTarget = null },
        )
    }
}

@Composable
private fun UtilitySectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    timeoutMin: Int,
    onTimeoutSelected: (Int) -> Unit,
    packages: Set<String>,
    apps: AppInfoCache,
    status: UtilityStatusSnapshot,
    privileged: Boolean,
    actionLabel: String,
    onRunNow: () -> Unit,
    onAddApp: () -> Unit,
    onRemoveApp: (String) -> Unit,
) {
    val sortedPackages = remember(packages, apps) {
        packages.sortedBy { (apps.label(it) ?: it).lowercase() }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (enabled) accentColor.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Card header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${packages.size} app${if (packages.size == 1) "" else "s"} · ${formatTimeout(timeoutMin)} idle",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Timeout selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Idle background duration before trigger",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsStore.UTILITY_TIMEOUT_OPTIONS_MIN.forEach { mins ->
                        val selected = timeoutMin == mins
                        val bg = if (selected) accentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh
                        val fg = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .clickable { onTimeoutSelected(mins) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                formatTimeout(mins),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = fg,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // App list
            if (sortedPackages.isEmpty()) {
                Text(
                    "No apps added yet. Tap \"Add app\" below or add directly from a process dialog in the Processes tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sortedPackages.forEach { pkg ->
                        WatchedAppRow(
                            packageName = pkg,
                            timeoutMin = timeoutMin,
                            apps = apps,
                            status = status,
                            accentColor = accentColor,
                            onRemove = { onRemoveApp(pkg) },
                        )
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onAddApp,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add app", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onRunNow,
                    enabled = privileged && packages.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun WatchedAppRow(
    packageName: String,
    timeoutMin: Int,
    apps: AppInfoCache,
    status: UtilityStatusSnapshot,
    accentColor: Color,
    onRemove: () -> Unit,
) {
    val label = remember(packageName) { apps.label(packageName) ?: packageName }
    val bitmap = remember(packageName) { apps.icon(packageName) }

    val isRunning = status.runningPackages.contains(packageName)
    val isForeground = status.foregroundPackages.contains(packageName)
    val isThrottled = status.throttledPackages.contains(packageName)
    val bgSince = status.backgroundSinceMs[packageName]

    val stateText = when {
        isForeground -> "Foreground (active)"
        isRunning && bgSince != null -> {
            val idleSec = ((SystemClock.elapsedRealtime() - bgSince) / 1000L).coerceAtLeast(0L)
            val idleMin = idleSec / 60L
            val base = if (idleMin > 0) "Background ${idleMin}m / ${formatTimeout(timeoutMin)}" else "Background ${idleSec}s / ${formatTimeout(timeoutMin)}"
            if (isThrottled) "$base · Throttled" else base
        }
        isRunning -> if (isThrottled) "Running · Throttled" else "Running"
        else -> "Not running"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(28.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.take(1).uppercase(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stateText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (isRunning && !isForeground) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun UtilityAppPickerDialog(
    title: String,
    installedApps: List<InstalledAppInfo>,
    selectedPackages: Set<String>,
    apps: AppInfoCache,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val filtered = remember(installedApps, searchQuery, showSystem) {
        val q = searchQuery.trim()
        installedApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (q.isEmpty() || app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Search field
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search installed apps…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" },
                        )
                    }
                }

                // System apps toggle + selected count
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${selectedPackages.size} selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSystem = !showSystem }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = showSystem,
                            onCheckedChange = { showSystem = it },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("System apps", style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider()

                if (installedApps.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else if (filtered.isEmpty()) {
                    Text(
                        "No apps matching \"$searchQuery\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            val isSelected = selectedPackages.contains(app.packageName)
                            val bitmap = remember(app.packageName) { apps.icon(app.packageName) }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onTogglePackage(app.packageName) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onTogglePackage(app.packageName) },
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                if (bitmap != null) {
                                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

private fun formatTimeout(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"
