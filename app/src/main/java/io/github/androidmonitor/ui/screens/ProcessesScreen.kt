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

private val NameColumnWidth = 200.dp
private val CpuColumnWidth = 76.dp
private val MemoryColumnWidth = 104.dp
private val SwapColumnWidth = 84.dp
private val GpuColumnWidth = 68.dp
private val NetColumnWidth = 88.dp
private val PowerColumnWidth = 84.dp
private val ThreadsColumnWidth = 64.dp
private val PidColumnWidth = 60.dp

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
        ScreenTitle("Processes", Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp))
        when (val s = state) {
            ProcessListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ProcessListState.NeedsAccess -> Box(Modifier.padding(horizontal = 24.dp)) {
                NoticeCard(
                    icon = Icons.Outlined.Lock,
                    title = "The process list needs Shizuku or root",
                    body = "Android hides other apps' processes from regular apps. Connect Shizuku " +
                        "(no root needed) or root to see what's using your CPU and memory, and to end tasks.",
                    actionLabel = "Set up access",
                    onAction = onOpenAccess,
                )
            }
            is ProcessListState.Error -> Box(Modifier.padding(horizontal = 24.dp)) {
                NoticeCard(icon = Icons.Outlined.ErrorOutline, title = "Something went wrong", body = s.message)
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
    val horizontalScrollState = rememberScrollState()

    val visible = remember(state, query, sortBy, showKernel, pinnedKeys) {
        val trimmed = query.trim()
        state.groups
            .map { it.copy(isPinned = pinnedKeys.contains(it.key)) }
            .filter { (showKernel || !it.isKernel) && (trimmed.isEmpty() || it.matches(trimmed)) }
            .sortedWith(sortBy.comparator(pinnedKeys))
    }
    val apps = visible.filter { it.isApp }
    val system = visible.filterNot { it.isApp }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search processes") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        FilterChip(
            selected = showKernel,
            onClick = { showKernel = !showKernel },
            label = { Text("Kernel") },
        )
        FilterChip(
            selected = grouping == ProcessGrouping.MIXED,
            onClick = {
                vm.settings.setProcessGrouping(
                    if (grouping == ProcessGrouping.MIXED) ProcessGrouping.SEPARATE else ProcessGrouping.MIXED,
                )
            },
            label = { Text(if (grouping == ProcessGrouping.MIXED) "Mixed" else "Separated") },
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${state.processCount} processes · ${state.threadCount} threads" +
                if (pinnedKeys.isNotEmpty()) " · ${pinnedKeys.size} pinned" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Slide → for more metrics",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    HeaderRow(sortBy, { sortBy = it }, monitor, horizontalScrollState)
    HorizontalDivider()

    LazyColumn(Modifier.fillMaxSize()) {
        if (grouping == ProcessGrouping.MIXED) {
            items(visible, key = { it.key }) { group ->
                ProcessRow(
                    group = group,
                    totalMemory = totalMemory,
                    apps = vm.apps,
                    horizontalScrollState = horizontalScrollState,
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
) {
    val snapshot = monitor?.snapshot
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeaderCell("Name", null, sortBy == SortBy.NAME, Modifier.width(NameColumnWidth), Alignment.Start) {
            onSort(SortBy.NAME)
        }
        Row(Modifier.horizontalScroll(scrollState), verticalAlignment = Alignment.Bottom) {
            HeaderCell(
                "CPU", snapshot?.cpu?.usage?.let(::formatPercent), sortBy == SortBy.CPU,
                Modifier.width(CpuColumnWidth), Alignment.End,
            ) { onSort(SortBy.CPU) }
            HeaderCell(
                "Memory", snapshot?.memory?.usedPercent?.let(::formatPercent), sortBy == SortBy.MEMORY,
                Modifier.width(MemoryColumnWidth), Alignment.End,
            ) { onSort(SortBy.MEMORY) }
            HeaderCell(
                "Swap", snapshot?.memory?.swapUsedBytes?.let(::formatBytes), sortBy == SortBy.SWAP,
                Modifier.width(SwapColumnWidth), Alignment.End,
            ) { onSort(SortBy.SWAP) }
            HeaderCell(
                "GPU", snapshot?.gpu?.usage?.let(::formatPercent), sortBy == SortBy.GPU,
                Modifier.width(GpuColumnWidth), Alignment.End,
            ) { onSort(SortBy.GPU) }
            HeaderCell(
                "Network", snapshot?.network?.rxBytesPerSec?.let(::formatRate), sortBy == SortBy.NETWORK,
                Modifier.width(NetColumnWidth), Alignment.End,
            ) { onSort(SortBy.NETWORK) }
            HeaderCell(
                "Power (est)", snapshot?.battery?.powerW?.let(::formatPower), sortBy == SortBy.POWER,
                Modifier.width(PowerColumnWidth), Alignment.End,
            ) { onSort(SortBy.POWER) }
            HeaderCell(
                "Threads", null, sortBy == SortBy.THREADS,
                Modifier.width(ThreadsColumnWidth), Alignment.End,
            ) { onSort(SortBy.THREADS) }
            HeaderCell(
                "PID", null, sortBy == SortBy.PID,
                Modifier.width(PidColumnWidth), Alignment.End,
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
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick).padding(top = 8.dp, bottom = 8.dp, end = 12.dp),
        horizontalAlignment = alignment,
    ) {
        if (total != null) Text(total, style = MaterialTheme.typography.titleMedium.tabular)
        Text(
            if (active) "$label ${if (label == "Name") "↑" else "↓"}" else label,
            style = MaterialTheme.typography.labelMedium,
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
    onClick: (ProcessGroup) -> Unit,
    onTogglePin: (ProcessGroup) -> Unit,
    onEndTask: (ProcessGroup) -> Unit,
    onForceStop: (ProcessGroup) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "section-$title") {
        Text(
            "$title (${groups.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp),
        )
    }
    items(groups, key = { it.key }) { group ->
        ProcessRow(
            group = group,
            totalMemory = totalMemory,
            apps = apps,
            horizontalScrollState = horizontalScrollState,
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
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name Column: Fixed on left with Close button + Icon + Pin + Title
        Row(
            Modifier.width(NameColumnWidth).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Close button at the left side of the icon
            if (canClose) {
                Box(
                    Modifier
                        .size(28.dp)
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
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(28.dp))
            }

            Spacer(Modifier.width(4.dp))

            // Process Icon + Pin Badge
            Box(Modifier.size(32.dp)) {
                ProcessIcon(group, apps)
                if (group.isPinned) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Horizontally Scrollable Metrics Columns
        Row(
            Modifier.horizontalScroll(horizontalScrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // CPU
            HeatCell(formatPercentFine(group.cpu), heat.copy(alpha = heatAlpha(group.cpu / 25f)), CpuColumnWidth)

            // Memory (RAM + Swap under it)
            val memoryShare = if (totalMemory > 0) group.rssBytes.toFloat() / totalMemory else 0f
            Box(
                Modifier
                    .width(MemoryColumnWidth)
                    .fillMaxHeight()
                    .background(heat.copy(alpha = heatAlpha(memoryShare * 8f)))
                    .padding(end = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
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

            // Dedicated Swap column
            HeatCell(
                text = if (group.swapBytes > 0) formatBytes(group.swapBytes) else "0 B",
                background = Color.Transparent,
                width = SwapColumnWidth,
            )

            // GPU (Android drivers do not expose per-process GPU to userspace)
            HeatCell(text = "—", background = Color.Transparent, width = GpuColumnWidth)

            // Network
            val netBytes = group.rxBytesPerSec + group.txBytesPerSec
            HeatCell(
                text = if (netBytes > 0) formatRate(netBytes) else "—",
                background = Color.Transparent,
                width = NetColumnWidth,
            )

            // Power (est)
            HeatCell(
                text = group.powerMw?.let { formatPower(it / 1000f) } ?: "—",
                background = Color.Transparent,
                width = PowerColumnWidth,
            )

            // Threads
            HeatCell(text = group.threads.toString(), background = Color.Transparent, width = ThreadsColumnWidth)

            // PID
            HeatCell(
                text = (group.processes.firstOrNull()?.pid ?: 0).toString(),
                background = Color.Transparent,
                width = PidColumnWidth,
            )
        }
    }
}

private fun heatAlpha(intensity: Float): Float = 0.05f + 0.40f * intensity.coerceIn(0f, 1f)

@Composable
private fun HeatCell(text: String, background: Color, width: Dp) {
    Box(
        Modifier.width(width).fillMaxHeight().background(background),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium.tabular, modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun ProcessIcon(group: ProcessGroup, apps: AppInfoCache) {
    val bitmap = remember(group.packageName) { group.packageName?.let(apps::icon) }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (group.isKernel) Icons.Outlined.Memory else Icons.Outlined.Terminal,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
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
                KeyValue("GPU", "N/A (Driver limitation)")
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
                    "Note: Android kernel & proprietary GPU drivers (Adreno, Mali) do not expose per-process GPU usage to userspace.",
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
