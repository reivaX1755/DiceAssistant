package com.diceassistant.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var projectionManager: MediaProjectionManager
    private var receiverRegistered = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureService.ACTION_RESULT -> {
                    val display = intent.getStringExtra(CaptureService.EXTRA_DISPLAY_RESULT)
                    if (display != null) resultText.text = display
                }
                CaptureService.ACTION_STATUS -> {
                    val message = intent.getStringExtra(CaptureService.EXTRA_STATUS) ?: return
                    statusText.text = "Estado: $message"
                    if (message.startsWith("No se pudo iniciar")) {
                        startButton.isEnabled = true
                        stopButton.isEnabled = false
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        startButton.setOnClickListener { beginStartFlow() }
        stopButton.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            setRunningUi(false)
        }

        val filter = IntentFilter().apply {
            addAction(CaptureService.ACTION_RESULT)
            addAction(CaptureService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(resultReceiver, filter)
        }
        receiverRegistered = true

        setRunningUi(false)
    }

    private fun beginStartFlow() {
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "Permite que Dice Assistant se muestre sobre otras aplicaciones."
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, REQUEST_OVERLAY)
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        requestCapturePermission()
    }

    private fun requestCapturePermission() {
        statusText.text = "Acepta la captura de pantalla de Android para iniciar la detección."
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Uses the platform activity-result flow for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    beginStartFlow()
                } else {
                    statusText.text = "Falta el permiso para mostrar la burbuja flotante."
                }
            }

            REQUEST_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val serviceIntent = Intent(this, CaptureService::class.java).apply {
                        putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
                    else startService(serviceIntent)
                    setRunningUi(true)
                } else {
                    statusText.text = "Captura cancelada. No se ha iniciado la detección."
                    setRunningUi(false)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            // La notificación no es imprescindible para detectar, aunque Android puede
            // mostrarla de forma limitada si el permiso se deniega.
            requestCapturePermission()
        }
    }

    private fun setRunningUi(running: Boolean) {
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        statusText.text = if (running) {
            "Estado: detección activa. Abre el juego; la suma aparecerá en una burbuja."
        } else {
            "Estado: detenido"
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(resultReceiver)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_CAPTURE = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
    }
}
