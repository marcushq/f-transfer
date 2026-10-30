package dev.fujitransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.snapshotFlow
import dev.fujitransfer.ui.MiniMode
import dev.fujitransfer.ui.PipChoice
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a transfer going while the app is in the background: a foreground service with a
 * progress notification (and a "Dừng" button), plus Wi-Fi and wake locks so the phone doesn't
 * throttle Wi-Fi or sleep when the screen goes off. Starts with a transfer, stops after it.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var lowLatency: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var watching = false
    private val bubble by lazy { Bubble(this) }
    private var reported = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = (application as FujiApp).session
        if (intent?.action == ACTION_STOP) {
            session.cancelTransfer()
            return START_NOT_STICKY
        }
        val first = session.ui.value.transfer
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progress(first),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        hold()
        if (!watching) {
            watching = true
            PipChoice.load(this)
            // The round badge over other apps: bubble mode, while the app itself is out of sight.
            scope.launch {
                combine(session.ui.map { it.transfer }, AppVisible.visible, snapshotFlow { PipChoice.mode }) { t, visible, mode ->
                    Triple(t, visible, mode)
                }.collect { (t, visible, mode) ->
                    if (t == null) return@collect // the end is handled below
                    if (!visible && mode == MiniMode.Bubble && Bubble.allowed(this@TransferService)) bubble.show(t) else bubble.hide()
                }
            }
            scope.launch {
                var seen = first != null
                session.ui.map { it.transfer to (it.notice ?: it.error) }.distinctUntilChanged().collect { (t, message) ->
                    if (t != null) {
                        seen = true
                        val n = progress(t)
                        notify(ID_PROGRESS, n)
                        if (!reported && Build.VERSION.SDK_INT >= 36) {
                            reported = true
                            // Why the island did or didn't appear, for the connection log.
                            val nm = getSystemService(NotificationManager::class.java)
                            val promoted = nm.activeNotifications.firstOrNull { it.id == ID_PROGRESS }
                                ?.let { it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 }
                            session.appendLog(
                                "Live Update: đủ điều kiện=${n.hasPromotableCharacteristics()}, " +
                                    "được phép=${nm.canPostPromotedNotifications()}, đã lên đảo=$promoted",
                            )
                        }
                    } else if (seen) {
                        // Done (or stopped, or the connection dropped): leave a summary behind.
                        message?.let { notify(ID_DONE, done(it)) }
                        if (bubble.shown) {
                            bubble.show(null) // a tick for a moment
                            delay(2_500)
                        }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        bubble.hide()
        wifiLock?.takeIf { it.isHeld }?.release()
        lowLatency?.takeIf { it.isHeld }?.release()
        wake?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Wi-Fi at full power, CPU awake: without these a screen-off phone slows the camera link to a crawl. */
    @Suppress("DEPRECATION")
    private fun hold() {
        if (wake == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FujiTransfer:transfer").apply { setReferenceCounted(false); acquire() }
            if (Build.VERSION.SDK_INT >= 29) {
                lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FujiTransfer:latency").apply { setReferenceCounted(false); acquire() }
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FujiTransfer:transfer").apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(t: Transfer?): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 36) return liveProgress(t, stop)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Dừng", stop)
        if (t == null) return b.setContentTitle("Đang chuẩn bị tải ảnh").setProgress(0, 0, true).addExtras(LiveUpdates.extras(null)).build()
        val speed = if (t.bytesPerSecond > 0) " · %.1f MB/s".format(t.bytesPerSecond / 1_048_576).replace('.', ',') else ""
        // Overall progress: whole files done plus the share of the current one.
        val part = if (t.fileTotal > 0) t.fileDone.toDouble() / t.fileTotal else 0.0
        val permille = (((t.index + part) / t.total.coerceAtLeast(1)) * 1000).toInt()
        return b.setContentTitle("Đang tải ảnh ${t.index + 1}/${t.total}")
            .setContentText(t.fileName + speed)
            .setSubText(if (t.failed > 0) "${t.failed} lỗi" else null)
            .setProgress(1000, permille, false)
            // Android 16 Live Update: a "3/12" chip on the status bar (vivo's Origin Island, etc.).
            .addExtras(LiveUpdates.extras("${t.index + 1}/${t.total}"))
            .build()
    }

    /**
     * Android 16: the same progress as a Live Update — ProgressStyle, asking to be promoted,
     * with "3/12" as the status-bar chip (vivo's Origin Island and other skins build on this).
     */
    @RequiresApi(36)
    private fun liveProgress(t: Transfer?, stop: PendingIntent): Notification {
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000).setColor(0xFFFF9D28.toInt())))
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            // API 36 has no setter for the request yet; it is this extra (what NotificationCompat sets).
            .addExtras(LiveUpdates.extras(null))
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Dừng", stop).build())
        if (t == null) {
            return b.setContentTitle("Đang chuẩn bị tải ảnh").setStyle(style.setProgressIndeterminate(true)).build()
        }
        val speed = if (t.bytesPerSecond > 0) " · %.1f MB/s".format(t.bytesPerSecond / 1_048_576).replace('.', ',') else ""
        val part = if (t.fileTotal > 0) t.fileDone.toDouble() / t.fileTotal else 0.0
        val permille = (((t.index + part) / t.total.coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
        return b.setContentTitle("Đang tải ảnh ${t.index + 1}/${t.total}")
            .setContentText(t.fileName + speed)
            .setSubText(if (t.failed > 0) "${t.failed} lỗi" else null)
            .setShortCriticalText("${t.index + 1}/${t.total}")
            .setStyle(style.setProgress(permille))
            .build()
    }

    private fun done(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Fuji Transfer")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    private fun notify(id: Int, n: Notification) {
        // Without the notification permission the transfer still runs; it just isn't shown.
        try {
            NotificationManagerCompat.from(this).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        // IMPORTANCE_DEFAULT without sound: a Live Update may not sit in a low-importance channel.
        private const val CHANNEL = "transfer_live"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
        private const val ACTION_STOP = "dev.fujitransfer.STOP"

        /** Stops the running transfer (the notification's and the floating window's "Dừng"). */
        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1, Intent(context, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun start(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Chuyển ảnh", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Tiến độ khi đang tải ảnh từ máy ảnh"
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
