package io.github.androidmonitor.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.androidmonitor.MainActivity
import io.github.androidmonitor.MonitorApp
import io.github.androidmonitor.R
import io.github.androidmonitor.data.OverlayType
import io.github.androidmonitor.data.ProcessGroup
import io.github.androidmonitor.data.ProcessListState
import io.github.androidmonitor.data.Snapshot
import io.github.androidmonitor.ui.theme.AndroidMonitorTheme
import io.github.androidmonitor.ui.theme.tabular
import io.github.androidmonitor.ui.util.formatBytes
import io.github.androidmonitor.ui.util.formatPercent
import io.github.androidmonitor.ui.util.formatPercentFine
import io.github.androidmonitor.ui.util.formatRate
import io.github.androidmonitor.ui.util.formatTemperature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the live notification and floating WindowManager overlays.
 */
class MonitorOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // Live state updated by the polling loop and consumed by the overlay Compose UI
    private var liveSnapshot by mutableStateOf<Snapshot?>(null)
    private var liveProcesses by mutableStateOf<List<ProcessGroup>>(emptyList())
    private var isMinimized by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startInForeground()
        startPolling()
        syncOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                syncOverlay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification(liveSnapshot, liveProcesses.firstOrNull())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        val app = application as? MonitorApp ?: return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val monitorState = runCatching { app.systemSampler.sample() }.getOrNull()
                val snapshot = monitorState?.snapshot
                val procState = runCatching { app.processSampler.sample() }.getOrNull()
                val groups = if (procState is ProcessListState.Ready) procState.groups else emptyList()

                scope.launch(Dispatchers.Main) {
                    liveSnapshot = snapshot
                    liveProcesses = groups
                    if (app.settings.notificationEnabled.value) {
                        updateNotification(snapshot, groups.firstOrNull())
                    }
                }

                delay(app.settings.refreshMs.value)
            }
        }
    }

    private fun syncOverlay() {
        val app = application as? MonitorApp ?: return
        val overlayEnabled = app.settings.overlayEnabled.value
        val hasOverlayPermission = Settings.canDrawOverlays(this)

        if (overlayEnabled && hasOverlayPermission) {
            if (overlayView == null) {
                showOverlay()
            }
        } else {
            removeOverlay()
            if (!app.settings.notificationEnabled.value) {
                stopSelf()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        val app = application as? MonitorApp ?: return

        val lifecycleOwner = OverlayLifecycleOwner().also {
            it.onCreate()
            it.onStart()
        }
        overlayLifecycleOwner = lifecycleOwner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }
        overlayParams = params

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            lifecycleOwner.attach(this)
            setContent {
                AndroidMonitorTheme {
                    val overlayType by app.settings.overlayType.collectAsStateWithLifecycle()
                    val overlayTopCount by app.settings.overlayTopCount.collectAsStateWithLifecycle()
                    val overlayScale by app.settings.overlayScale.collectAsStateWithLifecycle()
                    val overlaySingleProcessKey by app.settings.overlaySingleProcessKey.collectAsStateWithLifecycle()
                    val opacity by app.settings.overlayOpacity.collectAsStateWithLifecycle()

                    OverlayContent(
                        snapshot = liveSnapshot,
                        processes = liveProcesses,
                        overlayType = overlayType,
                        topCount = overlayTopCount,
                        scale = overlayScale,
                        opacity = opacity,
                        isMinimized = isMinimized,
                        onToggleMinimize = { isMinimized = !isMinimized },
                        onCycleScale = {
                            val next = when {
                                overlayScale < 0.95f -> 1.0f
                                overlayScale < 1.15f -> 1.25f
                                else -> 0.85f
                            }
                            app.settings.setOverlayScale(next)
                        },
                        singleProcessKey = overlaySingleProcessKey,
                        onSelectProcessKey = { app.settings.setOverlaySingleProcessKey(it) },
                        onClose = {
                            app.settings.setOverlayEnabled(false)
                            removeOverlay()
                            if (!app.settings.notificationEnabled.value) stopSelf()
                        },
                        onEndProcess = { group ->
                            scope.launch { app.processSampler.endTask(group) }
                        },
                    )
                }
            }
        }

        // Enable touch dragging of the overlay
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        composeView.setOnTouchListener { _, event ->
            val p = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = initialX + (event.rawX - initialTouchX).toInt()
                    p.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(composeView, p) }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(composeView, params)
            overlayView = composeView
        } catch (_: Exception) {
            lifecycleOwner.onDestroy()
            overlayLifecycleOwner = null
            overlayView = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayLifecycleOwner?.onDestroy()
            overlayLifecycleOwner = null
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live System Monitor",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Live CPU, memory, GPU, and network stats"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(snapshot: Snapshot?, topProcess: ProcessGroup?) {
        val notification = buildNotification(snapshot, topProcess)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(snapshot: Snapshot?, topProcess: ProcessGroup?): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cpuStr = snapshot?.cpu?.usage?.let(::formatPercent) ?: "—"
        val ramStr = snapshot?.memory?.let { "${formatBytes(it.usedBytes)} (${formatPercent(it.usedPercent)})" } ?: "—"
        val netStr = snapshot?.network?.let { "↓ ${formatRate(it.rxBytesPerSec)}  ↑ ${formatRate(it.txBytesPerSec)}" } ?: "—"
        val gpuStr = snapshot?.gpu?.usage?.let(::formatPercent) ?: "—"
        val tempStr = snapshot?.battery?.tempC?.let(::formatTemperature) ?: "—"

        val title = "CPU: $cpuStr  •  RAM: $ramStr"
        val subtitle = "GPU: $gpuStr  •  $netStr  •  $tempStr"
        val bigText = buildString {
            append("CPU: ").append(cpuStr)
            if (snapshot?.cpu?.peakFreqMhz != null) append(" @ ").append(snapshot.cpu.peakFreqMhz).append(" MHz")
            append("\nRAM: ").append(ramStr)
            if ((snapshot?.memory?.swapTotalBytes ?: 0) > 0) {
                append("  (Swap: ").append(formatBytes(snapshot?.memory?.swapUsedBytes ?: 0)).append(")")
            }
            append("\nGPU: ").append(gpuStr)
            append("\nNetwork: ").append(netStr)
            append("\nBattery: ").append(tempStr)
            if (topProcess != null) {
                append("\nTop app: ").append(topProcess.label).append(" (").append(formatPercentFine(topProcess.cpu)).append(" CPU)")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Open Monitor", openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "io.github.androidmonitor.monitor_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_UPDATE = "io.github.androidmonitor.action.UPDATE_OVERLAY"
        const val ACTION_STOP = "io.github.androidmonitor.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, MonitorOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorOverlayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun update(context: Context) {
            val intent = Intent(context, MonitorOverlayService::class.java).apply { action = ACTION_UPDATE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/**
 * Renders the floating overlay variations.
 */
@Composable
private fun OverlayContent(
    snapshot: Snapshot?,
    processes: List<ProcessGroup>,
    overlayType: OverlayType,
    topCount: Int,
    scale: Float = 1.0f,
    opacity: Float,
    isMinimized: Boolean,
    onToggleMinimize: () -> Unit,
    onCycleScale: () -> Unit = {},
    singleProcessKey: String? = null,
    onSelectProcessKey: (String?) -> Unit = {},
    onClose: () -> Unit,
    onEndProcess: (ProcessGroup) -> Unit,
) {
    if (isMinimized) {
        // Minimized floating pill (44dp circle showing live CPU)
        Surface(
            onClick = onToggleMinimize,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = opacity),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.size((46 * scale).dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Speed,
                        contentDescription = "Expand HUD",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatPercent(snapshot?.cpu?.usage),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        return
    }

    // Expanded Overlay Window
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = opacity),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.width((260 * scale).dp),
    ) {
        Column(Modifier.padding((12 * scale).dp)) {
            // Header bar with drag handle and controls
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (overlayType) {
                            OverlayType.RESOURCE_HUD -> "System HUD"
                            OverlayType.TOP_PROCESSES -> "Top $topCount Apps"
                            OverlayType.SINGLE_PROCESS -> "Process HUD"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCycleScale, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.AspectRatio, contentDescription = "Resize", modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = onToggleMinimize, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Minimize", modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            when (overlayType) {
                OverlayType.RESOURCE_HUD -> ResourceHudBody(snapshot)
                OverlayType.TOP_PROCESSES -> TopProcessesBody(processes, topCount, onEndProcess)
                OverlayType.SINGLE_PROCESS -> {
                    val target = processes.firstOrNull { it.key == singleProcessKey } ?: processes.firstOrNull()
                    SingleProcessBody(target, onEndProcess)
                }
            }
        }
    }
}

@Composable
private fun ResourceHudBody(snapshot: Snapshot?) {
    val cpu = snapshot?.cpu
    val mem = snapshot?.memory
    val net = snapshot?.network
    val battery = snapshot?.battery

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OverlayMetricRow("CPU", formatPercent(cpu?.usage), cpu?.peakFreqMhz?.let { "$it MHz" } ?: "")
        OverlayMetricRow("RAM", mem?.let { formatBytes(it.usedBytes) } ?: "—", mem?.let { "${formatPercent(it.usedPercent)}" } ?: "")
        if ((mem?.swapTotalBytes ?: 0) > 0) {
            OverlayMetricRow("Swap", formatBytes(mem?.swapUsedBytes ?: 0), "zRAM")
        }
        OverlayMetricRow("GPU", snapshot?.gpu?.usage?.let(::formatPercent) ?: "—", snapshot?.gpu?.model ?: "")
        OverlayMetricRow("Net ↓", net?.rxBytesPerSec?.let(::formatRate) ?: "—", "↑ " + (net?.txBytesPerSec?.let(::formatRate) ?: "—"))
        OverlayMetricRow("Battery", battery?.tempC?.let(::formatTemperature) ?: "—", battery?.powerW?.let { "${String.format("%.1f", it)} W" } ?: "")
    }
}

@Composable
private fun TopProcessesBody(
    processes: List<ProcessGroup>,
    count: Int,
    onEndProcess: (ProcessGroup) -> Unit,
) {
    val top = processes.filter { it.isApp }.take(count)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        top.forEachIndexed { i, group ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${i + 1}. ${group.label}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatPercentFine(group.cpu),
                    style = MaterialTheme.typography.bodySmall.tabular,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatBytes(group.rssBytes),
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (top.isEmpty()) {
            Text("No active app processes", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SingleProcessBody(
    process: ProcessGroup?,
    onEndProcess: (ProcessGroup) -> Unit,
) {
    if (process == null) {
        Text("No process selected", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(process.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${process.packageName ?: process.processes.first().name} · PID ${process.processes.first().pid}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OverlayMetricRow("CPU", formatPercentFine(process.cpu), "")
        OverlayMetricRow("RAM", formatBytes(process.rssBytes), if (process.swapBytes > 0) "+${formatBytes(process.swapBytes)} swap" else "")
        OverlayMetricRow("Threads", process.threads.toString(), "User: ${process.processes.first().user}")
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { onEndProcess(process) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("End task", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OverlayMetricRow(label: String, value: String, secondary: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodySmall.tabular, fontWeight = FontWeight.SemiBold)
            if (secondary.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(secondary, style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * Supplies the Lifecycle and SavedStateRegistry required for a ComposeView
 * attached directly to a WindowManager overlay window.
 */
private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    fun attach(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }
}
