package io.github.p1neapplexpress.openflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.ui.MainActivity
import java.util.Locale

class VpnNotificationManager(private val service: Service) {

    companion object {
        const val CHANNEL_ID = "io.github.p1neapplexpress.libp1npplydtransport.so.vpn"
        private const val RECOVERY_CHANNEL_ID = "terzaet.recovery"
        const val NOTIFICATION_ID = 1
        private const val RECOVERY_NOTIFICATION_ID = 2
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val uid = Process.myUid()
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSampleAt = 0L

    private val speedUpdater = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = (now - lastSampleAt).coerceAtLeast(1)
            val rxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            val txBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            val rxPerSec = (rxBytes - lastRxBytes) * 1000 / elapsedMs
            val txPerSec = (txBytes - lastTxBytes) * 1000 / elapsedMs
            lastRxBytes = rxBytes
            lastTxBytes = txBytes
            lastSampleAt = now
            updateContent("↑ ${formatSpeed(txPerSec)}   ↓ ${formatSpeed(rxPerSec)}")
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun startForeground() {
        createChannel()
        service.startForeground(NOTIFICATION_ID, buildNotification(service.getString(R.string.notify_msg)))
    }

    fun startSpeedUpdates() {
        lastRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        lastTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        lastSampleAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(speedUpdater)
        handler.post(speedUpdater)
    }

    fun stopSpeedUpdates() {
        handler.removeCallbacks(speedUpdater)
    }

    fun updateContent(text: String) {
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    fun showRecoverySuccess() {
        createChannel()
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(service, RECOVERY_CHANNEL_ID)
            .setContentTitle("Соединение восстановлено")
            .setContentText("TERZAET снова защищает интернет-соединение")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(0xFF4D7A42.toInt())
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        mgr.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.notify_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(0xFF4D7A42.toInt())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun contentIntent() = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    service.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        if (mgr.getNotificationChannel(RECOVERY_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "Восстановление соединения",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }
}
