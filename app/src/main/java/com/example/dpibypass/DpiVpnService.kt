package com.example.dpibypass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream

class DpiVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.example.dpibypass.ACTION_STOP"

        private const val CHANNEL_ID = "dpi_bypass_vpn"
        private const val NOTIFICATION_ID = 1

        private const val PROXY_PORT = 8444
        private const val VPN_ADDRESS = "10.10.10.1"
    }

    private var proxy: LocalDpiProxy? = null
    private var pfd: ParcelFileDescriptor? = null
    private var tunThread: Thread? = null

    @Volatile
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val stopIntent = Intent(this, DpiVpnService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DPI Bypass")
            .setContentText("Локальный обход DPI активен")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Стоп",
                stopPendingIntent
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startVpn()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun startVpn() {
        if (running) return

        try {
            proxy = LocalDpiProxy(PROXY_PORT, this).also {
                it.start()
            }

            val builder = Builder()
                .setSession("DPI Bypass")
                .setMtu(1500)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")

            if (Build.VERSION.SDK_INT >= 29) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(VPN_ADDRESS, PROXY_PORT)
                )
            }

            val targetPackages = listOf(
                "com.google.android.youtube",
                "com.google.android.apps.youtube.music",
                "com.android.chrome",
                "com.android.vending"
            )

            targetPackages.forEach { pkg ->
                runCatching {
                    builder.addAllowedApplication(pkg)
                }
            }

            pfd = builder.establish() ?: run {
                stopVpn()
                stopSelf()
                return
            }

            running = true

            tunThread = Thread {
                dropTun(pfd!!)
            }.apply {
                start()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
            stopSelf()
        }
    }

    /**
     * Читает и дропает все пакеты, которые идут напрямую в TUN.
     * Это ломает прямой трафик разрешённых приложений и заставляет их
     * падать на системный HTTP-прокси, если приложение умеет его использовать.
     */
    private fun dropTun(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32768)

        try {
            while (running) {
                val n = input.read(buffer)
                if (n < 0) break
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            runCatching { input.close() }
        }
    }

    private fun stopVpn() {
        running = false

        runCatching { proxy?.stop() }
        proxy = null

        runCatching { tunThread?.interrupt() }
        tunThread = null

        runCatching { pfd?.close() }
        pfd = null

        stopForeground(true)
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
