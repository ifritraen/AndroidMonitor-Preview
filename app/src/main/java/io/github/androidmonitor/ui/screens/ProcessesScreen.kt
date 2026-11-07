package io.github.androidmonitor.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.androidmonitor.data.AppInfoCache
import io.github.androidmonitor.data.MonitorState
import io.github.androidmonitor.data.OverlayType
import io.github.androidmonitor.data.ProcessGroup
import io.github.androidmonitor.data.ProcessGrouping
import io.github.androidmonitor.data.ProcessListState
import io.github.androidmonitor.service.MonitorOverlayService
import io.github.androidmonitor.ui.MonitorViewModel
import io.github.androidmonitor.ui.components.NoticeCard
import io.github.androidmonitor.ui.components.ScreenTitle
import io.github.androidmonitor.ui.theme.LocalMetricPalette
import io.github.androidmonitor.ui.theme.tabular
import io.github.androidmonitor.ui.util.formatBytes
import io.github.androidmonitor.ui.util.formatPercent
import io.github.androidmonitor.ui.util.formatPercentFine
import io.github.androidmonitor.ui.util.formatPower
import io.github.androidmonitor.ui.util.formatRate

private fun getNameColumnWidth(isCompact: Boolean) = if (isCompact) 150.dp else 200.dp
private fun getCpuColumnWidth(isCompact: Boolean) = if (isCompact) 62.dp else 76.dp
private fun getMemoryColumnWidth(isCompact: Boolean) = if (isCompact) 84.dp else 104.dp
private fun getSwapColumnWidth(isCompact: Boolean) = if (isCompact) 68.dp else 84.dp
private fun getGpuColumnWidth(isCompact: Boolean) = if (isCompact) 56.dp else 68.dp
private fun getNetColumnWidth(isCompact: Boolean) = if (isCompact) 72.dp else 88.dp
private fun getPowerColumnWidth(isCompact: Boolean) = if (isCompact) 70.dp else 84.dp
private fun getThreadsColumnWidth(isCompact: Boolean) = if (isCompact) 50.dp else 64.dp
private fun getPidColumnWidth(isCompact: Boolean) = if (isCompact) 50.dp else 60.dp

private enum class SortBy(val label: String) {
    NAME("Name"),
    CPU("CPU"),
    MEMORY("Memory"),
    SWAP("Swap"),
    GPU("GPU"),
    NETWORK("Network"),
    POWER("Power"),
    THREADS("Threads"),
    PID("PID");

    fun comparator(pinnedKeys: Set<String>): Comparator<ProcessGroup> {
        val base = when (this) {
            NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            CPU -> compareByDescending<ProcessGroup> { it.cpu }.thenByDescending { it.rssBytes }
            MEMORY -> compareByDescending<ProcessGroup> { it.rssBytes }.thenByDescending { it.cpu }
            SWAP -> compareByDescending<ProcessGroup> { it.swapBytes }.thenByDescending { it.rssBytes }
            GPU -> compareByDescending<ProcessGroup> { it.cpu }
            NETWORK -> compareByDescending<ProcessGroup> { it.rxBytesPerSec + it.txBytesPerSec }
            POWER -> compareByDescending<ProcessGroup> { it.powerMw ?: 0f }
            THREADS -> compareByDescending<ProcessGroup> { it.threads }.thenByDescending { it.cpu }
            PID -> compareBy<ProcessGroup> { it.processes.firstOrNull()?.pid ?: 0 }
        }
        return compareByDescending<ProcessGroup> { pinnedKeys.contains(it.key) }.then(base)
    }
}

@Composable
fun ProcessesScreen(vm: MonitorViewModel, monitor: MonitorState?, onOpenAccess: () -> Unit) {
    val state by vm.processes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        when (val s = state) {
            ProcessListState.Loading -> {
                ScreenTitle("Processes", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            ProcessListState.NeedsAccess -> {
                ScreenTitle("Processes", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    NoticeCard(
                        icon = Icons.Outlined.Lock,
                        title = "The process list needs Shizuku or root",
                        body = "Android hides other apps' processes from regular apps. Connect Shizuku " +
                            "(no root needed) or root to see what's using your CPU and memory, and to end tasks.",
                        actionLabel = "Set up access",
                        onAction = onOpenAccess,
                    )
                }
            }
            is ProcessListState.Error -> {
                ScreenTitle("Processes", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    NoticeCard(icon = Icons.Outlined.ErrorOutline, title = "Something went wrong", body = s.message)
                }
            }
            is ProcessListState.Ready -> ProcessTable(s, vm, monitor)
        }
    }
}

@Composable
private fun ProcessTable(state: ProcessListState.Ready, vm: MonitorViewModel, monitor: MonitorState?) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.CPU) }
    var showKernel by rememberSaveable { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val totalMemory = monitor?.snapshot?.memory?.totalBytes ?: 0L
    val pinnedKeys by vm.settings.pinnedProcesses.collectAsStateWithLifecycle()
    val grouping by vm.settings.processGrouping.collectAsStateWithLifecycle()
    val isCompact by vm.settings.compactProcesses.collectAsStateWithLifecycle()
    val horizontalScrollState = rememberScrollState()

    val visible = remember(state, query, sortBy, showKernel, pinnedKeys) {
        val trimmed = query.trim()
        state.groups
            .map { it.copy(isPinned = pinnedKeys.contains(it.key)) }
            .filter { (showKernel || !it.isKernel) && (trimmed.isEmpty() || it.matches(trimmed)) }
            .sortedWith(sortBy.comparator(pinnedKeys))
    }
    val (apps, system) = remember(visible) { visible.partition { it.isApp } }

    // Top Compact Bar: Title, Counts and Filter Chips
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Processes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "${state.processCount} procs · ${state.threadCount} thr" +
                    if (pinnedKeys.isNotEmpty()) " · ${pinnedKeys.size} pinned" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = isCompact,
                onClick = { vm.settings.setCompactProcesses(!isCompact) },
                label = { Text("Compact", fontSize = 11.sp) },
                modifier = Modifier.height(28.dp),
            )
            FilterChip(
                selected = showKernel,
                onClick = { showKernel = !showKernel },
                label = { Text("Kernel", fontSize = 11.sp) },
                modifier = Modifier.height(28.dp),
            )
            FilterChip(
                selected = grouping == ProcessGrouping.MIXED,
                onClick = {
                    vm.settings.setProcessGrouping(
                        if (grouping == ProcessGrouping.MIXED) ProcessGrouping.SEPARATE else ProcessGrouping.MIXED,
                    )
                },
                label = { Text(if (grouping == ProcessGrouping.MIXED) "Mixed" else "Separated", fontSize = 11.sp) },
                modifier = Modifier.height(28.dp),
            )
        }
    }

    // Compact Search Bar (34dp height)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(17.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "Search processes…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { query = "" },
                )
            }
        }
    }

    HeaderRow(sortBy, { sortBy = it }, monitor, horizontalScrollState, isCompact)
    HorizontalDivider()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 72.dp),
    ) {
        if (grouping == ProcessGrouping.MIXED) {
            items(visible, key = { it.key }) { group ->
                ProcessRow(
                    group = group,
                    totalMemory = totalMemory,
                    apps = vm.apps,
                    horizontalScrollState = horizontalScrollState,
                    isCompact = isCompact,
                    onClick = { selectedKey = it.key },
                    onTogglePin = {
                        vm.togglePin(group)
                        val isPinnedNow = !pinnedKeys.contains(group.key)
                        Toast.makeText(context, if (isPinnedNow) "Pinned ${group.label}" else "Unpinned ${group.label}", Toast.LENGTH_SHORT).show()
                    },
                    onEndTask = {
                        vm.endTask(group) { ok ->
                            Toast.makeText(context, if (ok) "Ended ${group.label}" else "Couldn't end ${group.label}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onForceStop = {
                        vm.forceStop(group) { ok ->
                            Toast.makeText(context, if (ok) "Force stopped ${group.label}" else "Couldn't force stop ${group.label}", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        } else {
            groupSection(
                title = "Apps",
                groups = apps,
                totalMemory = totalMemory,
                apps = vm.apps,
                horizontalScrollState = horizontalScrollState,
                isCompact = isCompact,
                onClick = { selectedKey = it.key },
                onTogglePin = {
                    vm.togglePin(it)
                    val isPinnedNow = !pinnedKeys.contains(it.key)
                    Toast.makeText(context, if (isPinnedNow) "Pinned ${it.label}" else "Unpinned ${it.label}", Toast.LENGTH_SHORT).show()
                },
                onEndTask = { g ->
                    vm.endTask(g) { ok ->
                        Toast.makeText(context, if (ok) "Ended ${g.label}" else "Couldn't end ${g.label}", Toast.LENGTH_SHORT).show()
                    }
                },
                onForceStop = { g ->
                    vm.forceStop(g) { ok ->
                        Toast.makeText(context, if (ok) "Force stopped ${g.label}" else "Couldn't force stop ${g.label}", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            groupSection(
                title = "System processes",
                groups = system,
                totalMemory = totalMemory,
                apps = vm.apps,
                horizontalScrollState = horizontalScrollState,
                isCompact = isCompact,
                onClick = { selectedKey = it.key },
                onTogglePin = {
                    vm.togglePin(it)
                    val isPinnedNow = !pinnedKeys.contains(it.key)
                    Toast.makeText(context, if (isPinnedNow) "Pinned ${it.label}" else "Unpinned ${it.label}", Toast.LENGTH_SHORT).show()
                },
                onEndTask = { g ->
                    vm.endTask(g) { ok ->
                        Toast.makeText(context, if (ok) "Ended ${g.label}" else "Couldn't end ${g.label}", Toast.LENGTH_SHORT).show()
                    }
                },
                onForceStop = { g ->
                    vm.forceStop(g) { ok ->
                        Toast.makeText(context, if (ok) "Force stopped ${g.label}" else "Couldn't force stop ${g.label}", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No matching processes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }

    val selected = selectedKey?.let { key -> state.groups.firstOrNull { it.key == key } }
    if (selected != null) {
        val isPinned = pinnedKeys.contains(selected.key)
        ProcessDialog(
            group = selected,
            canEnd = selected.packageName != null && selected.packageName != context.packageName,
            isPinned = isPinned,
            onDismiss = { selectedKey = null },
            onTogglePin = {
                vm.togglePin(selected)
                Toast.makeText(context, if (!isPinned) "Pinned ${selected.label}" else "Unpinned ${selected.label}", Toast.LENGTH_SHORT).show()
            },
            onPinToHud = {
                vm.settings.setOverlaySingleProcessKey(selected.key)
                vm.settings.setOverlayType(OverlayType.SINGLE_PROCESS)
                MonitorOverlayService.update(context)
                Toast.makeText(context, "Set ${selected.label} for HUD overlay", Toast.LENGTH_SHORT).show()
            },
            onEnd = {
                selectedKey = null
                vm.endTask(selected) { ok ->
                    val message = if (ok) "Ended ${selected.label}" else "Couldn't end ${selected.label}"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onForceStop = {
                selectedKey = null
                vm.forceStop(selected) { ok ->
                    val message = if (ok) "Force stopped ${selected.label}" else "Couldn't force stop ${selected.label}"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun HeaderRow(
    sortBy: SortBy,
    onSort: (SortBy) -> Unit,
    monitor: MonitorState?,
    scrollState: ScrollState,
    isCompact: Boolean,
) {
    val snapshot = monitor?.snapshot
    Row(
        Modifier.fillMaxWidth().padding(start = if (isCompact) 8.dp else 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeaderCell("Name", null, sortBy == SortBy.NAME, Modifier.width(getNameColumnWidth(isCompact)), Alignment.Start, isCompact) {
            onSort(SortBy.NAME)
        }
        Row(Modifier.horizontalScroll(scrollState), verticalAlignment = Alignment.Bottom) {
            HeaderCell(
                "CPU", snapshot?.cpu?.usage?.let(::formatPercent), sortBy == SortBy.CPU,
                Modifier.width(getCpuColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.CPU) }
            HeaderCell(
                "Memory", snapshot?.memory?.usedPercent?.let(::formatPercent), sortBy == SortBy.MEMORY,
                Modifier.width(getMemoryColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.MEMORY) }
            HeaderCell(
                "Swap", snapshot?.memory?.swapUsedBytes?.let(::formatBytes), sortBy == SortBy.SWAP,
                Modifier.width(getSwapColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.SWAP) }
            HeaderCell(
                "GPU", snapshot?.gpu?.usage?.let(::formatPercent), sortBy == SortBy.GPU,
                Modifier.width(getGpuColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.GPU) }
            HeaderCell(
                "Network", snapshot?.network?.rxBytesPerSec?.let(::formatRate), sortBy == SortBy.NETWORK,
                Modifier.width(getNetColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.NETWORK) }
            HeaderCell(
                "Power (est)", snapshot?.battery?.powerW?.let(::formatPower), sortBy == SortBy.POWER,
                Modifier.width(getPowerColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.POWER) }
            HeaderCell(
                "Threads", null, sortBy == SortBy.THREADS,
                Modifier.width(getThreadsColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.THREADS) }
            HeaderCell(
                "PID", null, sortBy == SortBy.PID,
                Modifier.width(getPidColumnWidth(isCompact)), Alignment.End, isCompact,
            ) { onSort(SortBy.PID) }
        }
    }
}

@Composable
private fun HeaderCell(
    label: String,
    total: String?,
    active: Boolean,
    modifier: Modifier,
    alignment: Alignment.Horizontal,
    isCompact: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick).padding(
            top = if (isCompact) 4.dp else 8.dp,
            bottom = if (isCompact) 4.dp else 8.dp,
            end = if (isCompact) 6.dp else 12.dp,
        ),
        horizontalAlignment = alignment,
    ) {
        if (total != null) {
            Text(
                total,
                style = if (isCompact) MaterialTheme.typography.labelSmall.tabular else MaterialTheme.typography.titleMedium.tabular,
            )
        }
        Text(
            if (active) "$label ${if (label == "Name") "↑" else "↓"}" else label,
            style = if (isCompact) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp) else MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.groupSection(
    title: String,
    groups: List<ProcessGroup>,
    totalMemory: Long,
    apps: AppInfoCache,
    horizontalScrollState: ScrollState,
    isCompact: Boolean,
    onClick: (ProcessGroup) -> Unit,
    onTogglePin: (ProcessGroup) -> Unit,
    onEndTask: (ProcessGroup) -> Unit,
    onForceStop: (ProcessGroup) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "section-$title") {
        Text(
            "$title (${groups.size})",
            style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = if (isCompact) 12.dp else 24.dp, top = if (isCompact) 8.dp else 16.dp, bottom = if (isCompact) 2.dp else 6.dp),
        )
    }
    items(groups, key = { it.key }) { group ->
        ProcessRow(
            group = group,
            totalMemory = totalMemory,
            apps = apps,
            horizontalScrollState = horizontalScrollState,
            isCompact = isCompact,
            onClick = onClick,
            onTogglePin = onTogglePin,
            onEndTask = onEndTask,
            onForceStop = onForceStop,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcessRow(
    group: ProcessGroup,
    totalMemory: Long,
    apps: AppInfoCache,
    horizontalScrollState: ScrollState,
    isCompact: Boolean,
    onClick: (ProcessGroup) -> Unit,
    onTogglePin: (ProcessGroup) -> Unit,
    onEndTask: (ProcessGroup) -> Unit,
    onForceStop: (ProcessGroup) -> Unit,
) {
    val heat = LocalMetricPalette.current.heat
    val subtitle = if (group.processes.size > 1) {
        "${group.packageName ?: group.processes.first().name} · ${group.processes.size} processes"
    } else {
        "${group.processes.first().name} · PID ${group.processes.first().pid}"
    }

    val context = LocalContext.current
    val canClose = !group.isKernel && group.packageName != context.packageName

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .combinedClickable(
                onClick = { onClick(group) },
                onLongClick = { onTogglePin(group) },
            )
            .padding(start = if (isCompact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name Column: Fixed on left with Close button + Icon + Pin + Title
        Row(
            Modifier.width(getNameColumnWidth(isCompact)).padding(vertical = if (isCompact) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val closeBoxSize = if (isCompact) 20.dp else 28.dp
            val closeIconSize = if (isCompact) 11.dp else 15.dp
            val iconSize = if (isCompact) 20.dp else 32.dp

            // Close button at the left side of the icon
            if (canClose) {
                Box(
                    Modifier
                        .size(closeBoxSize)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { onEndTask(group) },
                            onLongClick = { onForceStop(group) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "End task (long tap for force stop)",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(closeIconSize),
                    )
                }
            } else {
                Spacer(Modifier.size(closeBoxSize))
            }

            Spacer(Modifier.width(if (isCompact) 2.dp else 4.dp))

            // Process Icon + Pin Badge
            Box(Modifier.size(iconSize)) {
                ProcessIcon(group, apps, size = iconSize)
                if (group.isPinned) {
                    val pinBadgeSize = if (isCompact) 10.dp else 14.dp
                    val pinIconSize = if (isCompact) 7.dp else 10.dp
                    Box(
                        Modifier
                            .size(pinBadgeSize)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(pinIconSize),
                        )
                    }
                }
            }

            Spacer(Modifier.width(if (isCompact) 4.dp else 8.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = if (isCompact) MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp) else MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isCompact) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Horizontally Scrollable Metrics Columns
        Row(
            Modifier.horizontalScroll(horizontalScrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // CPU
            HeatCell(formatPercentFine(group.cpu), heat.copy(alpha = heatAlpha(group.cpu / 25f)), getCpuColumnWidth(isCompact), isCompact)

            // Memory (RAM + Swap under it)
            val memoryShare = if (totalMemory > 0) group.rssBytes.toFloat() / totalMemory else 0f
            Box(
                Modifier
                    .width(getMemoryColumnWidth(isCompact))
                    .fillMaxHeight()
                    .background(heat.copy(alpha = heatAlpha(memoryShare * 8f)))
                    .padding(end = if (isCompact) 6.dp else 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (isCompact) {
                    Text(
                        formatBytes(group.rssBytes),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp).tabular,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatBytes(group.rssBytes),
                            style = MaterialTheme.typography.bodyMedium.tabular,
                        )
                        if (group.swapBytes > 0) {
                            Text(
                                "+${formatBytes(group.swapBytes)} swap",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Dedicated Swap column
            HeatCell(
                text = if (group.swapBytes > 0) formatBytes(group.swapBytes) else "0 B",
                background = Color.Transparent,
                width = getSwapColumnWidth(isCompact),
                isCompact = isCompact,
            )

            // GPU (calculated from graphics thread activity and system GPU load)
            HeatCell(
                text = if (group.gpu > 0f) formatPercentFine(group.gpu) else "0%",
                background = heat.copy(alpha = heatAlpha(group.gpu / 25f)),
                width = getGpuColumnWidth(isCompact),
                isCompact = isCompact,
            )

            // Network
            val netBytes = group.rxBytesPerSec + group.txBytesPerSec
            HeatCell(
                text = if (netBytes > 0) formatRate(netBytes) else "0 B/s",
                background = Color.Transparent,
                width = getNetColumnWidth(isCompact),
                isCompact = isCompact,
            )

            // Power (est)
            HeatCell(
                text = group.powerMw?.let { formatPower(it / 1000f) } ?: "—",
                background = Color.Transparent,
                width = getPowerColumnWidth(isCompact),
                isCompact = isCompact,
            )

            // Threads
            HeatCell(text = group.threads.toString(), background = Color.Transparent, width = getThreadsColumnWidth(isCompact), isCompact = isCompact)

            // PID
            HeatCell(
                text = (group.processes.firstOrNull()?.pid ?: 0).toString(),
                background = Color.Transparent,
                width = getPidColumnWidth(isCompact),
                isCompact = isCompact,
            )
        }
    }
}

private fun heatAlpha(intensity: Float): Float = 0.05f + 0.40f * intensity.coerceIn(0f, 1f)

@Composable
private fun HeatCell(text: String, background: Color, width: Dp, isCompact: Boolean = false) {
    Box(
        Modifier.width(width).fillMaxHeight().background(background),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text,
            style = if (isCompact) MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp).tabular else MaterialTheme.typography.bodyMedium.tabular,
            modifier = Modifier.padding(end = if (isCompact) 6.dp else 12.dp),
        )
    }
}

@Composable
private fun ProcessIcon(group: ProcessGroup, apps: AppInfoCache, size: Dp = 32.dp) {
    val bitmap = remember(group.packageName) { group.packageName?.let(apps::icon) }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(if (size < 24.dp) 4.dp else 8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (group.isKernel) Icons.Outlined.Memory else Icons.Outlined.Terminal,
                contentDescription = null,
                modifier = Modifier.size(if (size < 24.dp) 12.dp else 18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessDialog(
    group: ProcessGroup,
    canEnd: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onPinToHud: () -> Unit,
    onEnd: () -> Unit,
    onForceStop: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.packageName?.let { KeyValue("Package", it) }
                KeyValue("CPU", formatPercentFine(group.cpu))
                KeyValue("RAM", formatBytes(group.rssBytes))
                if (group.swapBytes > 0) {
                    KeyValue("Swap (zRAM)", formatBytes(group.swapBytes))
                }
                KeyValue("GPU (est)", if (group.gpu > 0f) formatPercentFine(group.gpu) else "0%")
                KeyValue("Network", "↓ ${formatRate(group.rxBytesPerSec)}  ↑ ${formatRate(group.txBytesPerSec)}")
                KeyValue("Power (est)", group.powerMw?.let { formatPower(it / 1000f) } ?: "—")
                KeyValue("Threads", group.threads.toString())
                KeyValue("User", group.processes.first().user)
                KeyValue("Pinned", if (isPinned) "Yes" else "No")
                Spacer(Modifier.height(4.dp))
                Text("Processes (${group.processes.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                group.processes.take(8).forEach { p ->
                    Text(
                        "${p.pid}  ${p.name}",
                        style = MaterialTheme.typography.bodySmall.tabular,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (group.processes.size > 8) {
                    Text("and ${group.processes.size - 8} more", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Note: GPU and Power are estimated based on graphics thread activity, system GPU load, and CPU proportion.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onTogglePin) {
                    Text(if (isPinned) "Unpin" else "Pin")
                }
                TextButton(onClick = onPinToHud) {
                    Text("Pin to HUD")
                }
                if (canEnd) {
                    TextButton(onClick = onForceStop) {
                        Text("Force stop", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onEnd) {
                        Text("End task")
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        },
        dismissButton = {
            if (canEnd) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular)
    }
}
