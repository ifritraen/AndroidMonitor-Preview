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
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

        val onDrag: (Float, Float) -> Unit = { dx, dy ->
            overlayParams?.let { p ->
                p.x += dx.toInt()
                p.y += dy.toInt()
                runCatching { windowManager.updateViewLayout(overlayView, p) }
            }
        }

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
                        onCycleType = {
                            val next = when (overlayType) {
                                OverlayType.RESOURCE_HUD -> OverlayType.TOP_PROCESSES
                                OverlayType.TOP_PROCESSES -> OverlayType.SINGLE_PROCESS
                                OverlayType.SINGLE_PROCESS -> OverlayType.RESOURCE_HUD
                            }
                            app.settings.setOverlayType(next)
                        },
                        singleProcessKey = overlaySingleProcessKey,
                        onClose = {
                            app.settings.setOverlayEnabled(false)
                            removeOverlay()
                            if (!app.settings.notificationEnabled.value) stopSelf()
                        },
                        onDrag = onDrag,
                    )
                }
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
 * Renders the floating compact overlay strip / ticker.
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
    onCycleType: () -> Unit,
    singleProcessKey: String? = null,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val containerBg = Color(0xEE141414).copy(alpha = opacity.coerceIn(0.25f, 1.0f))
    val borderColor = Color(0x38FFFFFF)

    if (isMinimized) {
        // Ultra-compact minimized pill (e.g. ⠿ CPU 14% ✕)
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(containerBg)
                .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "⠿",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.clickable { onToggleMinimize() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CPU ", color = Color(0xFF4FC3F7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(formatPercent(snapshot?.cpu?.usage), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0x28FFFFFF))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color(0xFFE0E0E0), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    // Sleek thin ticker strip
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(containerBg)
            .border(1.dp, borderColor, RoundedCornerShape(17.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drag handle with its own dedicated gesture detector
        Box(
            modifier = Modifier
                .size(24.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⠿", color = Color(0xFFAAAAAA), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))

        // Center content area depending on overlayType
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (overlayType) {
                OverlayType.RESOURCE_HUD -> ResourceTicker(snapshot)
                OverlayType.TOP_PROCESSES -> TopProcessesTicker(processes, topCount)
                OverlayType.SINGLE_PROCESS -> SingleProcessTicker(processes, singleProcessKey)
            }
        }

        Spacer(Modifier.width(8.dp))

        // Mode switch badge button (HUD / TOP / PROC)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x28FFFFFF))
                .clickable { onCycleType() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (overlayType) {
                    OverlayType.RESOURCE_HUD -> "HUD"
                    OverlayType.TOP_PROCESSES -> "TOP"
                    OverlayType.SINGLE_PROCESS -> "PROC"
                },
                color = Color(0xFFE0E0E0),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.width(4.dp))

        // Minimize button "–"
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0x22FFFFFF))
                .clickable { onToggleMinimize() },
            contentAlignment = Alignment.Center,
        ) {
            Text("–", color = Color(0xFFE0E0E0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(4.dp))

        // Close button "✕"
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0x22FFFFFF))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color(0xFFE0E0E0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResourceTicker(snapshot: Snapshot?) {
    val cpu = snapshot?.cpu
    val mem = snapshot?.memory
    val gpu = snapshot?.gpu
    val net = snapshot?.network
    val battery = snapshot?.battery

    Row(verticalAlignment = Alignment.CenterVertically) {
        // CPU
        Text("CPU ", color = Color(0xFF4FC3F7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(formatPercent(cpu?.usage), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

        // Separator
        Text(" • ", color = Color(0xFF555555), fontSize = 11.sp, fontWeight = FontWeight.Bold)

        // RAM
        Text("RAM ", color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(mem?.let { formatBytes(it.usedBytes) } ?: "—", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

        // Separator
        Text(" • ", color = Color(0xFF555555), fontSize = 11.sp, fontWeight = FontWeight.Bold)

        // GPU
        Text("GPU ", color = Color(0xFFFF80AB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(gpu?.usage?.let(::formatPercent) ?: "—", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

        // Separator
        Text(" • ", color = Color(0xFF555555), fontSize = 11.sp, fontWeight = FontWeight.Bold)

        // NET
        val netRate = (net?.rxBytesPerSec ?: 0L) + (net?.txBytesPerSec ?: 0L)
        Text("NET ", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(formatRate(netRate), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

        // Temp
        battery?.tempC?.let { temp ->
            Text(" • ", color = Color(0xFF555555), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(formatTemperature(temp), color = Color(0xFFFF8A65), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TopProcessesTicker(processes: List<ProcessGroup>, count: Int) {
    val top = processes.filter { it.isApp }.take(count.coerceIn(1, 4))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (top.isEmpty()) {
            Text("No active app processes", color = Color(0xFFB0B0B0), fontSize = 11.sp)
        } else {
            top.forEachIndexed { i, group ->
                if (i > 0) {
                    Text(" • ", color = Color(0xFF555555), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text("${i + 1}. ", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                Text(group.label, color = Color(0xFF4FC3F7), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(2.dp))
                Text(formatPercentFine(group.cpu), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SingleProcessTicker(processes: List<ProcessGroup>, key: String?) {
    val target = processes.firstOrNull { it.key == key } ?: processes.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (target == null) {
            Text("No process selected", color = Color(0xFFB0B0B0), fontSize = 11.sp)
        } else {
            Text(target.label, color = Color(0xFF4FC3F7), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(": ", color = Color(0xFFAAAAAA), fontSize = 11.sp)
            Text("${formatPercentFine(target.cpu)} CPU", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(" • ", color = Color(0xFF555555), fontSize = 11.sp)
            Text(formatBytes(target.rssBytes), color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(" • ", color = Color(0xFF555555), fontSize = 11.sp)
            Text("${target.threads} th", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
