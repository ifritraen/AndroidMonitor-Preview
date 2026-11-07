package io.github.androidmonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.androidmonitor.data.DefaultTab
import io.github.androidmonitor.ui.screens.AccessScreen
import io.github.androidmonitor.ui.screens.PerformanceScreen
import io.github.androidmonitor.ui.screens.ProcessesScreen

enum class Destination(val label: String, val icon: ImageVector) {
    PERFORMANCE("Performance", Icons.Outlined.Speed),
    PROCESSES("Processes", Icons.AutoMirrored.Outlined.ViewList),
    ACCESS("Access", Icons.Outlined.AdminPanelSettings),
}

fun DefaultTab.toDestination(): Destination = when (this) {
    DefaultTab.PERFORMANCE -> Destination.PERFORMANCE
    DefaultTab.PROCESSES -> Destination.PROCESSES
}

@Composable
fun AppRoot(vm: MonitorViewModel = viewModel()) {
    var destination by rememberSaveable { mutableStateOf(vm.settings.defaultTab.value.toDestination()) }
    var isBottomBarVisible by rememberSaveable { mutableStateOf(true) }
    // Collected here so graphs keep their history while other tabs are open.
    val monitor by vm.monitor.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.access.refresh() }
    BackHandler(enabled = destination != Destination.PERFORMANCE) { destination = Destination.PERFORMANCE }

    // Scroll connection for scrollable containers
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -12f) {
                    isBottomBarVisible = false
                } else if (available.y > 12f) {
                    isBottomBarVisible = true
                }
                return Offset.Zero
            }
        }
    }

    // Surface (not a plain background) so text defaults to onBackground instead of black.
    Surface(
        Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .pointerInput(Unit) {
                awaitEachGesture {
                    var totalDeltaY = 0f
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val drag = event.changes.firstOrNull() ?: break
                        if (!drag.pressed) break
                        val delta = drag.position.y - drag.previousPosition.y
                        totalDeltaY += delta
                        if (totalDeltaY < -24f) {
                            isBottomBarVisible = false
                            totalDeltaY = 0f
                        } else if (totalDeltaY > 24f) {
                            isBottomBarVisible = true
                            totalDeltaY = 0f
                        }
                    }
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val openAccess = { destination = Destination.ACCESS }
            val content: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    when (destination) {
                        Destination.PERFORMANCE -> PerformanceScreen(monitor, wide, openAccess)
                        Destination.PROCESSES -> ProcessesScreen(vm, monitor, openAccess)
                        Destination.ACCESS -> AccessScreen(vm)
                    }
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Spacer(Modifier.height(12.dp))
                        Destination.entries.forEach { d ->
                            NavigationRailItem(
                                selected = destination == d,
                                onClick = { destination = d },
                                icon = { Icon(d.icon, contentDescription = null) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                    content(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom,
                                ),
                            ),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    content(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            ),
                    )

                    // Slim, compact, floating bottom navigation bar
                    AnimatedVisibility(
                        visible = isBottomBarVisible,
                        enter = slideInVertically(initialOffsetY = { it * 2 }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it * 2 }) + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.height(46.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Destination.entries.forEach { d ->
                                    val isSelected = destination == d
                                    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                                } else {
                                                    Color.Transparent
                                                },
                                            )
                                            .clickable { destination = d }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            d.icon,
                                            contentDescription = d.label,
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else inactiveColor,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Text(
                                            d.label,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 11.5.sp,
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else inactiveColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
