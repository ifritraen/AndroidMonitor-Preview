package io.github.androidmonitor.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@Composable
private fun CompactChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}

private fun getNameColumnWidth(isCompact: Boolean) = if (isCompact) 125.dp else 190.dp
private fun getCpuColumnWidth(isCompact: Boolean) = if (isCompact) 48.dp else 72.dp
private fun getMemoryColumnWidth(isCompact: Boolean) = if (isCompact) 68.dp else 96.dp
private fun getGpuColumnWidth(isCompact: Boolean) = if (isCompact) 44.dp else 64.dp
private fun getPowerColumnWidth(isCompact: Boolean) = if (isCompact) 54.dp else 80.dp
private fun getThreadsColumnWidth(isCompact: Boolean) = if (isCompact) 36.dp else 56.dp
private fun getPidColumnWidth(isCompact: Boolean) = if (isCompact) 42.dp else 56.dp

private enum class MemorySortMode {
    RAM_DESC,
    SWAP_DESC,
    RAM_ASC,
    SWAP_ASC,
}

private enum class SortBy(val label: String, val defaultAscending: Boolean = false) {
    NAME("Name", defaultAscending = true),
    CPU("CPU"),
    MEMORY("Memory"),
    GPU("GPU"),
    POWER("Power"),
    THREADS("Threads"),
    PID("PID", defaultAscending = true);

    fun comparator(
        pinnedKeys: Set<String>,
        ascending: Boolean,
        memoryMode: MemorySortMode,
    ): Comparator<ProcessGroup> {
        val base = when (this) {
            NAME -> {
                val c = compareBy<ProcessGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                if (ascending) c else c.reversed()
            }
            CPU -> {
                val c = compareByDescending<ProcessGroup> { it.cpu }.thenByDescending { it.rssBytes }
                if (ascending) c.reversed() else c
            }
            MEMORY -> when (memoryMode) {
                MemorySortMode.RAM_DESC -> compareByDescending<ProcessGroup> { it.rssBytes }.thenByDescending { it.cpu }
                MemorySortMode.SWAP_DESC -> compareByDescending<ProcessGroup> { it.swapBytes }.thenByDescending { it.rssBytes }
                MemorySortMode.RAM_ASC -> compareBy<ProcessGroup> { it.rssBytes }.thenBy { it.cpu }
                MemorySortMode.SWAP_ASC -> compareBy<ProcessGroup> { it.swapBytes }.thenBy { it.rssBytes }
            }
            GPU -> {
                val c = compareByDescending<ProcessGroup> { it.gpu }.thenByDescending { it.cpu }
                if (ascending) c.reversed() else c
            }
            POWER -> {
                val c = compareByDescending<ProcessGroup> { it.powerMw ?: 0f }.thenByDescending { it.cpu }
                if (ascending) c.reversed() else c
            }
            THREADS -> {
                val c = compareByDescending<ProcessGroup> { it.threads }.thenByDescending { it.cpu }
                if (ascending) c.reversed() else c
            }
            PID -> {
                val c = compareBy<ProcessGroup> { it.processes.firstOrNull()?.pid ?: 0 }
                if (ascending) c else c.reversed()
            }
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
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var showReduceSwapDialog by remember { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.CPU) }
    var sortAscending by rememberSaveable { mutableStateOf(false) }
    var memorySortMode by rememberSaveable { mutableStateOf(MemorySortMode.RAM_DESC) }
    var showKernel by rememberSaveable { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val totalMemory = monitor?.snapshot?.memory?.totalBytes ?: 0L
    val pinnedKeys by vm.settings.pinnedProcesses.collectAsStateWithLifecycle()
    val lockedKeys by vm.settings.lockedProcesses.collectAsStateWithLifecycle()
    val grouping by vm.settings.processGrouping.collectAsStateWithLifecycle()
    val isCompact by vm.settings.compactProcesses.collectAsStateWithLifecycle()
    val horizontalScrollState = rememberScrollState()

    val visible = remember(state, query, sortBy, sortAscending, memorySortMode, showKernel, pinnedKeys, lockedKeys) {
        val trimmed = query.trim()
        state.groups
            .map { it.copy(isPinned = pinnedKeys.contains(it.key), isLocked = lockedKeys.contains(it.key)) }
            .filter { (showKernel || !it.isKernel) && (trimmed.isEmpty() || it.matches(trimmed)) }
            .sortedWith(sortBy.comparator(pinnedKeys, sortAscending, memorySortMode))
    }
    val (apps, system) = remember(visible) { visible.partition { it.isApp } }

    if (isSearching) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
                .height(34.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(17.dp))
                .padding(horizontal = 10.dp),
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
                IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    isSearching = false
                    query = ""
                },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pinnedCount = pinnedKeys.size
                val lockedCount = lockedKeys.size
                val extraInfo = buildString {
                    if (pinnedCount > 0) append(" · ${pinnedCount}📌")
                    if (lockedCount > 0) append(" · ${lockedCount}🔒")
                }
                Text(
                    "${state.processCount} procs · ${state.threadCount} thr$extraInfo",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactChip(
                    label = "Compact",
                    selected = isCompact,
                    onClick = { vm.settings.setCompactProcesses(!isCompact) },
                )
                CompactChip(
                    label = "Kernel",
                    selected = showKernel,
                    onClick = { showKernel = !showKernel },
                )
                CompactChip(
                    label = "Unified",
                    selected = grouping == ProcessGrouping.MIXED,
                    onClick = {
                        vm.settings.setProcessGrouping(
                            if (grouping == ProcessGrouping.MIXED) ProcessGrouping.SEPARATE else ProcessGrouping.MIXED,
                        )
                    },
                )
                IconButton(
                    onClick = { showReduceSwapDialog = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.CleaningServices,
                        contentDescription = "Reduce Swap",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { isSearching = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    HeaderRow(
        sortBy = sortBy,
        sortAscending = sortAscending,
        memorySortMode = memorySortMode,
        onSort = { clicked ->
            if (clicked == SortBy.MEMORY) {
                if (sortBy != SortBy.MEMORY) {
                    sortBy = SortBy.MEMORY
                    memorySortMode = MemorySortMode.RAM_DESC
                } else {
                    memorySortMode = when (memorySortMode) {
                        MemorySortMode.RAM_DESC -> MemorySortMode.SWAP_DESC
                        MemorySortMode.SWAP_DESC -> MemorySortMode.RAM_ASC
                        MemorySortMode.RAM_ASC -> MemorySortMode.SWAP_ASC
                        MemorySortMode.SWAP_ASC -> MemorySortMode.RAM_DESC
                    }
                }
            } else {
                if (sortBy == clicked) {
                    sortAscending = !sortAscending
                } else {
                    sortBy = clicked
                    sortAscending = clicked.defaultAscending
                }
            }
        },
        monitor = monitor,
        scrollState = horizontalScrollState,
        isCompact = isCompact,
    )
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
        val isLocked = lockedKeys.contains(selected.key)
        ProcessDialog(
            group = selected,
            canEnd = selected.packageName != null && selected.packageName != context.packageName,
            isPinned = isPinned,
            isLocked = isLocked,
            onDismiss = { selectedKey = null },
            onTogglePin = {
                vm.togglePin(selected)
                Toast.makeText(context, if (!isPinned) "Pinned ${selected.label}" else "Unpinned ${selected.label}", Toast.LENGTH_SHORT).show()
            },
            onToggleLock = {
                vm.toggleLock(selected) { ok ->
                    val action = if (!isLocked) "Locked in RAM" else "Unlocked RAM for"
                    val status = if (ok) "" else " (Priority command had warnings)"
                    Toast.makeText(context, "$action ${selected.label}$status", Toast.LENGTH_SHORT).show()
                }
            },
            onPinToHud = {
                if (!Settings.canDrawOverlays(context)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                    Toast.makeText(context, "Grant overlay permission to show HUD", Toast.LENGTH_LONG).show()
                } else {
                    vm.settings.setOverlaySingleProcessKey(selected.key)
                    vm.settings.setOverlayType(OverlayType.SINGLE_PROCESS)
                    vm.settings.setOverlayEnabled(true)
                    MonitorOverlayService.start(context)
                    MonitorOverlayService.update(context)
                    Toast.makeText(context, "Pinned ${selected.label} to HUD", Toast.LENGTH_SHORT).show()
                }
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

    if (showReduceSwapDialog) {
        val swapUsed = monitor?.snapshot?.memory?.swapUsedBytes ?: 0L
        val memoryPercent = monitor?.snapshot?.memory?.usedPercent ?: 0f
        val candidateApps = remember(state.groups, pinnedKeys, lockedKeys) {
            state.groups.filter { group ->
                group.isApp &&
                    group.packageName != null &&
                    group.packageName != context.packageName &&
                    !pinnedKeys.contains(group.key) &&
                    !lockedKeys.contains(group.key)
            }.sortedByDescending { it.swapBytes + it.rssBytes }
        }

        ReduceSwapDialog(
            swapUsedBytes = swapUsed,
            memoryPercent = memoryPercent,
            candidateApps = candidateApps,
            onDismiss = { showReduceSwapDialog = false },
            onClean = { selectedPackages ->
                showReduceSwapDialog = false
                Toast.makeText(context, "Cleaning ${selectedPackages.size} background apps & compacting zRAM...", Toast.LENGTH_SHORT).show()
                vm.reduceSwap(selectedPackages) { freedBytes ->
                    val freedText = if (freedBytes > 0) "Freed ${formatBytes(freedBytes)} swap!" else "Cleanup complete!"
                    Toast.makeText(context, freedText, Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
private fun HeaderRow(
    sortBy: SortBy,
    sortAscending: Boolean,
    memorySortMode: MemorySortMode,
    onSort: (SortBy) -> Unit,
    monitor: MonitorState?,
    scrollState: ScrollState,
    isCompact: Boolean,
) {
    val snapshot = monitor?.snapshot
    Row(
        Modifier.fillMaxWidth().padding(start = if (isCompact) 4.dp else 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeaderCell(
            label = "Name",
            total = null,
            subTotal = null,
            active = sortBy == SortBy.NAME,
            ascending = sortAscending,
            modifier = Modifier.width(getNameColumnWidth(isCompact)),
            alignment = Alignment.Start,
            isCompact = isCompact,
        ) {
            onSort(SortBy.NAME)
        }
        Row(Modifier.horizontalScroll(scrollState), verticalAlignment = Alignment.Bottom) {
            HeaderCell(
                label = "CPU",
                total = snapshot?.cpu?.usage?.let(::formatPercent),
                subTotal = null,
                active = sortBy == SortBy.CPU,
                ascending = sortAscending,
                modifier = Modifier.width(getCpuColumnWidth(isCompact)),
                alignment = Alignment.End,
                isCompact = isCompact,
            ) { onSort(SortBy.CPU) }

            val memoryLabel = if (sortBy == SortBy.MEMORY && (memorySortMode == MemorySortMode.SWAP_DESC || memorySortMode == MemorySortMode.SWAP_ASC)) {
                "Swap"
            } else {
                "Memory"
            }
            val memoryAsc = memorySortMode == MemorySortMode.RAM_ASC || memorySortMode == MemorySortMode.SWAP_ASC
            HeaderCell(
                label = memoryLabel,
                total = snapshot?.memory?.usedPercent?.let(::formatPercent),
                subTotal = snapshot?.memory?.swapUsedBytes?.takeIf { it > 0 }?.let { "${formatBytes(it)} swap" },
                active = sortBy == SortBy.MEMORY,
                ascending = memoryAsc,
                modifier = Modifier.width(getMemoryColumnWidth(isCompact)),
                alignment = Alignment.End,
                isCompact = isCompact,
            ) { onSort(SortBy.MEMORY) }

            HeaderCell(
                label = "GPU",
                total = snapshot?.gpu?.usage?.let(::formatPercent),
                subTotal = null,
                active = sortBy == SortBy.GPU,
                ascending = sortAscending,
                modifier = Modifier.width(getGpuColumnWidth(isCompact)),
                alignment = Alignment.End,
                isCompact = isCompact,
            ) { onSort(SortBy.GPU) }
            HeaderCell(
                label = "Power",
                total = snapshot?.battery?.powerW?.let(::formatPower),
                subTotal = null,
                active = sortBy == SortBy.POWER,
                ascending = sortAscending,
                modifier = Modifier.width(getPowerColumnWidth(isCompact)),
                alignment = Alignment.End,
                isCompact = isCompact,
            ) { onSort(SortBy.POWER) }
            HeaderCell(
                label = "Threads",
                total = null,
                subTotal = null,
                active = sortBy == SortBy.THREADS,
                ascending = sortAscending,
                modifier = Modifier.width(getThreadsColumnWidth(isCompact)),
                alignment = Alignment.End,
                isCompact = isCompact,
            ) { onSort(SortBy.THREADS) }
            HeaderCell(
                label = "PID",
                total = null,
                subTotal = null,
                active = sortBy == SortBy.PID,
                ascending = sortAscending,
                modifier = Modifier.width(getPidColumnWidth(isCompact)),
                alignment = Alignment.End,
                isCompact = isCompact,
            ) { onSort(SortBy.PID) }
        }
    }
}

@Composable
private fun HeaderCell(
    label: String,
    total: String?,
    subTotal: String? = null,
    active: Boolean,
    ascending: Boolean = false,
    modifier: Modifier,
    alignment: Alignment.Horizontal,
    isCompact: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick).padding(
            top = if (isCompact) 2.dp else 8.dp,
            bottom = if (isCompact) 2.dp else 8.dp,
            end = if (isCompact) 4.dp else 10.dp,
        ),
        horizontalAlignment = alignment,
    ) {
        if (total != null) {
            Text(
                total,
                style = if (isCompact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp).tabular else MaterialTheme.typography.titleMedium.tabular,
            )
        }
        if (subTotal != null) {
            Text(
                subTotal,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (isCompact) 9.sp else 10.sp).tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (active) "$label ${if (ascending) "↑" else "↓"}" else label,
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
            style = if (isCompact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = if (isCompact) 8.dp else 24.dp, top = if (isCompact) 4.dp else 16.dp, bottom = if (isCompact) 1.dp else 6.dp),
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
            Modifier.width(getNameColumnWidth(isCompact)).padding(vertical = if (isCompact) 1.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val closeBoxSize = if (isCompact) 18.dp else 28.dp
            val closeIconSize = if (isCompact) 10.dp else 15.dp
            val iconSize = if (isCompact) 18.dp else 32.dp

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

            // Process Icon + Pin/Lock Badges
            Box(Modifier.size(iconSize)) {
                ProcessIcon(group, apps, size = iconSize)
                if (group.isPinned) {
                    val pinBadgeSize = if (isCompact) 8.dp else 14.dp
                    val pinIconSize = if (isCompact) 5.dp else 10.dp
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
                if (group.isLocked) {
                    val lockBadgeSize = if (isCompact) 8.dp else 14.dp
                    val lockIconSize = if (isCompact) 5.dp else 10.dp
                    Box(
                        Modifier
                            .size(lockBadgeSize)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(Color(0xFFFFB300)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "Locked in RAM",
                            tint = Color.Black,
                            modifier = Modifier.size(lockIconSize),
                        )
                    }
                }
            }

            Spacer(Modifier.width(if (isCompact) 3.dp else 8.dp))

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
            val memoryShare = if (totalMemory > 0) (group.rssBytes + group.swapBytes).toFloat() / totalMemory else 0f
            Box(
                Modifier
                    .width(getMemoryColumnWidth(isCompact))
                    .fillMaxHeight()
                    .background(heat.copy(alpha = heatAlpha(memoryShare * 8f)))
                    .padding(end = if (isCompact) 4.dp else 10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatBytes(group.rssBytes),
                        style = if (isCompact) MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp).tabular else MaterialTheme.typography.bodyMedium.tabular,
                    )
                    if (group.swapBytes > 0) {
                        Text(
                            "+${formatBytes(group.swapBytes)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = if (isCompact) 9.sp else 10.sp).tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // GPU (calculated from graphics thread activity and system GPU load)
            HeatCell(
                text = if (group.gpu > 0f) formatPercentFine(group.gpu) else "0%",
                background = heat.copy(alpha = heatAlpha(group.gpu / 25f)),
                width = getGpuColumnWidth(isCompact),
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
            modifier = Modifier.padding(end = if (isCompact) 4.dp else 10.dp),
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
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleLock: () -> Unit,
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
                KeyValue(
                    "Network",
                    if (group.rxBytesPerSec != null && group.txBytesPerSec != null) {
                        "↓ ${formatRate(group.rxBytesPerSec)}  ↑ ${formatRate(group.txBytesPerSec)}"
                    } else {
                        "— (OS restricted)"
                    },
                )
                KeyValue("Power", group.powerMw?.let { formatPower(it / 1000f) } ?: "—")
                KeyValue("Threads", group.threads.toString())
                KeyValue("User", group.processes.first().user)
                KeyValue("Pinned", if (isPinned) "Yes" else "No")
                KeyValue("Locked in RAM", if (isLocked) "Yes (Unkillable)" else "No")
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
                    "Note: GPU is estimated based on graphics thread activity and system GPU load. Locked apps have OOM score 0 and battery exemption to avoid being killed in background.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onTogglePin) {
                        Text(if (isPinned) "Unpin" else "Pin")
                    }
                    TextButton(onClick = onToggleLock) {
                        Text(if (isLocked) "Unlock RAM" else "Lock in RAM")
                    }
                    TextButton(onClick = onPinToHud) {
                        Text("Pin to HUD")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
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
            }
        },
        dismissButton = {
            if (canEnd) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ReduceSwapDialog(
    swapUsedBytes: Long,
    memoryPercent: Float,
    candidateApps: List<ProcessGroup>,
    onDismiss: () -> Unit,
    onClean: (Set<String>) -> Unit,
) {
    var selectedPkgs by remember {
        mutableStateOf(candidateApps.mapNotNull { it.packageName }.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Reduce Swap & Clean RAM")
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "RAM: ${formatPercent(memoryPercent)} · Swap (zRAM): ${formatBytes(swapUsedBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reclaims background app memory safely. Pinned and locked apps are protected and exempted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${selectedPkgs.size} of ${candidateApps.size} apps selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            selectedPkgs = if (selectedPkgs.size == candidateApps.size) {
                                emptySet()
                            } else {
                                candidateApps.mapNotNull { it.packageName }.toSet()
                            }
                        },
                    ) {
                        Text(if (selectedPkgs.size == candidateApps.size) "Deselect all" else "Select all", fontSize = 12.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                if (candidateApps.isEmpty()) {
                    Text(
                        "No background apps eligible for cleanup (all running apps are pinned, locked, or system).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                    ) {
                        items(candidateApps, key = { it.key }) { group ->
                            val pkg = group.packageName ?: return@items
                            val isChecked = selectedPkgs.contains(pkg)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPkgs = if (isChecked) selectedPkgs - pkg else selectedPkgs + pkg
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedPkgs = if (checked) selectedPkgs + pkg else selectedPkgs - pkg
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        group.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val swapInfo = if (group.swapBytes > 0) "+${formatBytes(group.swapBytes)} swap" else "0 swap"
                                    Text(
                                        "${formatBytes(group.rssBytes)} RAM · $swapInfo",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Root actions: Drops OS caches & triggers zRAM memory compaction.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onClean(selectedPkgs) },
            ) {
                Text("Clean (${selectedPkgs.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
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
