package com.vektr.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * TunnelForegroundService — keeps the Vektr Tunnel alive during large file transfers.
 *
 * Android may kill background processes to reclaim memory. A Foreground Service
 * with a persistent notification prevents this, ensuring a 2GB WAV transfer
 * completes without interruption — even if the user switches apps.
 *
 * The service carries zero audio data. It only holds the CPU/WiFi wake locks
 * that Android needs to see in order to keep the DataChannel alive.
 */
class TunnelForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID   = "vektr_tunnel_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.vektr.tunnel.ACTION_START_TUNNEL"
        const val ACTION_STOP  = "com.vektr.tunnel.ACTION_STOP_TUNNEL"

        const val EXTRA_FILE_NAME = "extra_file_name"
    }

    inner class TunnelBinder : Binder() {
        fun getService(): TunnelForegroundService = this@TunnelForegroundService
    }

    private val binder = TunnelBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "audio file"
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification("Tunneling: $fileName"))
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Updates the notification text while the transfer is in progress. */
    fun updateProgress(fileName: String, progressPct: Int, bitrateKbps: Double) {
        val text = "$fileName — $progressPct% · ${bitrateKbps.toInt()} kbps"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── Notification helpers ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Vektr Tunnel Transfer",
            NotificationManager.IMPORTANCE_LOW   // Silent — no sound/vibration during transfer
        ).apply {
            description = "Active P2P audio transfer"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, VektrActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VEKTR TUNNEL — ACTIVE")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
