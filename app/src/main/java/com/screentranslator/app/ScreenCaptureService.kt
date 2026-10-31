package com.screentranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var finished = false
    @Volatile private var processing = false
    @Volatile private var audioActive = false
    private var lastText = ""
    private var overlayView: TextView? = null
    private var overlayManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ENABLE_AUDIO) {
            audioActive = true
            promoteForeground()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISABLE_AUDIO) {
            audioActive = false
            if (projection != null) promoteForeground()
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW_TRANSLATION) {
            updateOverlay(intent.getStringExtra(EXTRA_OCR_TEXT).orEmpty())
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        promoteForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            sendResult("未获得屏幕捕捉授权。")
            cleanup()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            worker = HandlerThread("ScreenOCR").also { it.start() }
            handler = Handler(worker!!.looper)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, ImageFormat.YUV_420_888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup()
                }
            }, handler)
            virtualDisplay = projection?.createVirtualDisplay(
                "ScreenTranslatorOCR",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            showOverlay("ScreenTranslator 正在识别屏幕…")
            handler?.postDelayed({ recognizeFrame() }, FIRST_FRAME_DELAY_MS)
        } catch (e: Exception) {
            sendResult("启动屏幕捕捉失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            cleanup()
        }
        return START_NOT_STICKY
    }

    private fun recognizeFrame() {
        if (finished || processing) return
        val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            handler?.postDelayed({ recognizeFrame() }, RETRY_DELAY_MS)
            return
        }
        val input = try {
            InputImage.fromMediaImage(image, 0)
        } catch (e: Exception) {
            image.close()
            sendResult("无法读取屏幕画面：${e.message ?: "未知错误"}")
            handler?.postDelayed({ recognizeFrame() }, RETRY_DELAY_MS)
            return
        }

        processing = true
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                // Only send changed text to avoid repeatedly calling the translation API
                // for the same video frame/subtitle.
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    sendResult(text)
                }
            }
            .addOnFailureListener { e ->
                sendResult("OCR 识别失败：${e.message ?: "未知错误"}")
            }
            .addOnCompleteListener {
                image.close()
                recognizer.close()
                processing = false
                if (!finished) handler?.postDelayed({ recognizeFrame() }, CAPTURE_INTERVAL_MS)
            }
    }

    private fun sendResult(text: String) {
        if (!text.startsWith("OCR 识别失败") && !text.startsWith("无法读取")) updateOverlay(text)
        sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName).putExtra(EXTRA_OCR_TEXT, text))
    }

    private fun showOverlay(initialText: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        mainHandler.post {
            if (overlayView != null || finished) return@post
            try {
                val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = TextView(this).apply {
                    text = initialText
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(220, 20, 20, 20))
                    setPadding(24, 16, 24, 16)
                    maxLines = 6
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (resources.displayMetrics.density * 72).toInt()
                }
                manager.addView(view, params)
                overlayManager = manager
                overlayView = view
            } catch (_: Exception) {
                // OCR still works if Android denies the overlay.
            }
        }
    }

    private fun updateOverlay(text: String) {
        mainHandler.post {
            val view = overlayView
            if (view == null) showOverlay(text) else view.text = text
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            try { overlayView?.let { overlayManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null
            overlayManager = null
        }
    }

    private fun cleanup() {
        if (finished) return
        finished = true
        removeOverlay()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground() {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenTranslator")
            .setContentText(if (projection != null) "正在识别屏幕文字" else "正在聆听语音并准备翻译")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = 0
            if (projection != null) types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (audioActive) types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (types == 0) types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "屏幕识别", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OCR_RESULT = "com.screentranslator.app.OCR_RESULT"
        const val ACTION_STOP = "com.screentranslator.app.STOP_CAPTURE"
        const val ACTION_SHOW_TRANSLATION = "com.screentranslator.app.SHOW_TRANSLATION"
        const val ACTION_ENABLE_AUDIO = "com.screentranslator.app.ENABLE_AUDIO"
        const val ACTION_DISABLE_AUDIO = "com.screentranslator.app.DISABLE_AUDIO"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OCR_TEXT = "ocr_text"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 2401
        private const val FIRST_FRAME_DELAY_MS = 900L
        private const val CAPTURE_INTERVAL_MS = 1600L
        private const val RETRY_DELAY_MS = 300L
    }
}
