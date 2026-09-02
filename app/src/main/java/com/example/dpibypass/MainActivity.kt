package com.example.dpibypass

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val VPN_REQUEST = 100
    private val NOTIF_REQUEST = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "DPI Bypass"
            textSize = 22f
        }

        val description = TextView(this).apply {
            text = """
                1. Нажми СТАРТ.
                2. Разреши создание VPN-подключения.
                3. Держи приложение в памяти.
                
                Обход работает для выбранных пакетов:
                YouTube, YouTube Music, Chrome.
            """.trimIndent()
            textSize = 14f
            setPadding(0, 24, 0, 48)
        }

        val startButton = Button(this).apply {
            text = "Старт"
            setOnClickListener {
                startVpn()
            }
        }

        val stopButton = Button(this).apply {
            text = "Стоп"
            setOnClickListener {
                stopVpn()
            }
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(startButton)
        layout.addView(stopButton)

        setContentView(layout)

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_REQUEST
                )
            }
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST)
        } else {
            onActivityResult(VPN_REQUEST, RESULT_OK, null)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, DpiVpnService::class.java).apply {
            action = DpiVpnService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Останавливаю VPN", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            val intent = Intent(this, DpiVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
