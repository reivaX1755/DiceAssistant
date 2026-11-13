package com.diceassistant.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class CaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler()

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var lastProcessedAt = 0L
    private var lastCandidate: String? = null
    private var stableFrames = 0
    private var emptyFrames = 0
    private var lastDisplayText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCapture(resultCode, resultData)
        } catch (error: Exception) {
            broadcastStatus("No se pudo iniciar la captura: ${error.localizedMessage ?: "error"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Android no ha concedido la captura de pantalla")

        val metrics = getScreenMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        workerThread = HandlerThread("DiceAssistantVision").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)

        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastProcessedAt < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastProcessedAt = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image)
                val reading = DiceDetector.detect(bitmap)
                bitmap.recycle()
                handleReading(reading)
            } catch (_: Exception) {
                // Una captura parcial puede fallar durante una transición/rotación.
                // Se espera al siguiente fotograma sin interrumpir el servicio.
            } finally {
                image.close()
            }
        }, workerHandler)

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, workerHandler)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "DiceAssistantDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            workerHandler
        )

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mainHandler.post { ensureBubble() }
        broadcastStatus("Detección activa")
    }

    @Suppress("DEPRECATION")
    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManagerForMetrics().currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.density = resources.displayMetrics.density
            metrics.densityDpi = resources.displayMetrics.densityDpi
            metrics.scaledDensity = resources.displayMetrics.scaledDensity
            metrics.xdpi = resources.displayMetrics.xdpi
            metrics.ydpi = resources.displayMetrics.ydpi
        } else {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun windowManagerForMetrics(): WindowManager =
        getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        paddedBitmap.recycle()
        return cropped
    }

    private fun handleReading(reading: DiceDetector.Reading?) {
        if (reading == null) {
            emptyFrames++
            stableFrames = 0
            lastCandidate = null
            if (emptyFrames >= 3) {
                mainHandler.post { bubble?.visibility = View.GONE }
            }
            return
        }

        emptyFrames = 0
        val key = reading.values.joinToString(",")
        if (key == lastCandidate) stableFrames++ else {
            lastCandidate = key
            stableFrames = 1
        }

        if (stableFrames >= 2) {
            val display = "SUMA ${reading.sum}\n${reading.values.joinToString(" + ")}"
            if (display != lastDisplayText) {
                lastDisplayText = display
                mainHandler.post { showReading(reading, display) }
                val resultIntent = Intent(ACTION_RESULT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_DISPLAY_RESULT, display)
                sendBroadcast(resultIntent)
            } else {
                mainHandler.post { bubble?.visibility = View.VISIBLE }
            }
        }
    }

    private fun ensureBubble() {
        if (bubble != null || windowManager == null) return

        val bubbleBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(238, 20, 28, 48))
            setStroke(dp(2), Color.rgb(38, 211, 125))
        }

        val textView = TextView(this).apply {
            text = "Dice Assistant"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = bubbleBackground
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnTouchListener(BubbleDragListener())
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.heightPixels * 0.13f).toInt()
        }

        try {
            windowManager?.addView(textView, params)
            bubble = textView
            bubbleParams = params
        } catch (_: Exception) {
            // El permiso puede revocarse mientras la detección está activa.
            stopSelf()
        }
    }

    private fun showReading(reading: DiceDetector.Reading, display: String) {
        ensureBubble()
        bubble?.text = display
        bubble?.visibility = View.VISIBLE
    }

    private inner class BubbleDragListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var initialX = 0
        private var initialY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - startRawX).toInt()
                    params.y = initialY + (event.rawY - startRawY).toInt()
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) { }
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return false
        }
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dice Assistant captura de pantalla",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indica que Dice Assistant está leyendo la pantalla para reconocer los dados."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Dice Assistant está activo")
            .setContentText("Reconociendo dados en pantalla")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopService)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try {
            bubble?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        bubble = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        workerThread?.quitSafely()
        workerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_RESULT = "com.diceassistant.app.ACTION_RESULT"
        const val ACTION_STATUS = "com.diceassistant.app.ACTION_STATUS"
        const val ACTION_STOP = "com.diceassistant.app.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_DISPLAY_RESULT = "displayResult"
        const val EXTRA_STATUS = "status"

        private const val CHANNEL_ID = "dice_assistant_capture"
        private const val NOTIFICATION_ID = 8142
        private const val FRAME_INTERVAL_MS = 180L
    }
}
