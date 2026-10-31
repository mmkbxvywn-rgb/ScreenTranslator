package com.screentranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import java.nio.ByteBuffer
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
    private var overlayView: LinearLayout? = null
    private var overlayTextView: TextView? = null
    private var overlayManager: WindowManager? = null
    @Volatile private var overlayDismissed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val translationExecutor = Executors.newSingleThreadExecutor()
    private val translationLock = Any()
    private var pendingTranslation: Pair<String, String>? = null
    private var translationWorkerRunning = false

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
            overlayDismissed = false
            updateOverlay(intent.getStringExtra(EXTRA_OCR_TEXT).orEmpty())
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRANSLATE_TEXT) {
            val text = intent.getStringExtra(EXTRA_OCR_TEXT).orEmpty().trim()
            val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE).orEmpty().ifBlank { "speech" }
            if (text.isNotBlank()) enqueueTranslation(text, sourceType)
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
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
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
            overlayDismissed = false
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
        val bitmap = try {
            imageToBitmap(image)
        } catch (e: Exception) {
            image.close()
            sendResult("无法读取屏幕画面：${e.message ?: "未知错误"}")
            handler?.postDelayed({ recognizeFrame() }, RETRY_DELAY_MS)
            return
        }
        image.close()
        val input = InputImage.fromBitmap(bitmap, 0)

        processing = true
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    sendResult(text)
                    enqueueTranslation(text, "screen")
                }
            }
            .addOnFailureListener { e ->
                sendResult("OCR 识别失败：${e.message ?: "未知错误"}")
            }
            .addOnCompleteListener {
                bitmap.recycle()
                recognizer.close()
                processing = false
                if (!finished) handler?.postDelayed({ recognizeFrame() }, CAPTURE_INTERVAL_MS)
            }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val source = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val rowBytes = width * 4
        val packed = ByteBuffer.allocateDirect(rowBytes * height)
        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            source.position(y * rowStride)
            source.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.flip()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(packed)
        }
    }

    private fun sendResult(text: String) {
        val isError = text.startsWith("OCR 识别失败") || text.startsWith("无法读取") || text.startsWith("启动屏幕") || text.startsWith("未获得")
        if (!isError) {
            updateOverlay(text)
        }
        sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName).putExtra(EXTRA_OCR_TEXT, text))
    }

    private fun showOverlay(initialText: String) {
        if (!android.provider.Settings.canDrawOverlays(this) || overlayDismissed) return
        mainHandler.post {
            if (overlayView != null || finished || overlayDismissed) return@post
            try {
                val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val textView = TextView(this).apply {
                    text = initialText
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(12, 10, 8, 10)
                    maxLines = 7
                    layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
                }
                val closeButton = Button(this).apply {
                    text = "×"
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    minWidth = 48
                    minHeight = 48
                    setPadding(4, 0, 4, 0)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener {
                        overlayDismissed = true
                        removeOverlay()
                    }
                }
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.argb(228, 20, 20, 20))
                    addView(textView)
                    addView(closeButton, LinearLayout.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    ))
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
                manager.addView(container, params)
                overlayManager = manager
                overlayView = container
                overlayTextView = textView
            } catch (_: Exception) {
                // OCR still works if Android denies the overlay.
            }
        }
    }

    private fun updateOverlay(text: String) {
        mainHandler.post {
            if (overlayDismissed) return@post
            val textView = overlayTextView
            if (textView == null) showOverlay(text) else textView.text = text
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            try { overlayView?.let { overlayManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null
            overlayTextView = null
            overlayManager = null
        }
    }

    private fun enqueueTranslation(sourceText: String, sourceType: String) {
        synchronized(translationLock) {
            pendingTranslation = sourceText to sourceType
            if (translationWorkerRunning) return
            translationWorkerRunning = true
            translationExecutor.execute { drainTranslationQueue() }
        }
    }

    private fun drainTranslationQueue() {
        while (!finished) {
            val next = synchronized(translationLock) {
                val item = pendingTranslation
                if (item == null) {
                    translationWorkerRunning = false
                    null
                } else {
                    pendingTranslation = null
                    item
                }
            } ?: return

            val sourceText = next.first
            val sourceType = next.second
            val translated = translateWithConfiguredApi(sourceText)
            if (finished) return
            val displayText = if (sourceType == "speech") {
                "语音识别：\n$sourceText\n\n—— 中文翻译 ——\n$translated"
            } else {
                "$sourceText\n\n—— 中文翻译 ——\n$translated"
            }
            updateOverlay(displayText)
            sendBroadcast(Intent(ACTION_TRANSLATION_RESULT).setPackage(packageName)
                .putExtra(EXTRA_SOURCE_TEXT, sourceText)
                .putExtra(EXTRA_TRANSLATION_TEXT, translated)
                .putExtra(EXTRA_SOURCE_TYPE, sourceType))
        }
    }

    private fun translateWithConfiguredApi(sourceText: String): String {
        val prefs = getSharedPreferences("translator_settings", MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trimEnd('/')
        val key = prefs.getString("api_key", "").orEmpty()
        val model = prefs.getString("model", "").orEmpty()
        if (endpoint.isBlank() || key.isBlank() || model.isBlank()) {
            return "尚未配置翻译 API。请返回应用填写 API 地址、API Key 和模型并保存。"
        }
        var connection: HttpURLConnection? = null
        return try {
            val url = if (endpoint.endsWith("/chat/completions")) endpoint else "$endpoint/chat/completions"
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Translate the provided text into natural Simplified Chinese. Preserve names, numbers, line breaks, and meaning. If the text is already Chinese, return it without commentary. Output only the translation."))
                .put(JSONObject().put("role", "user").put("content", sourceText.take(12000)))
            val body = JSONObject().put("model", model).put("messages", messages).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() } ?: "API 返回成功，但响应中没有翻译文本。"
            } else "翻译接口返回 HTTP $code：$response".take(1200)
        } catch (e: Exception) {
            "翻译失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun cleanup() {
        if (finished) return
        finished = true
        translationExecutor.shutdownNow()
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

    private fun promoteForeground(includeProjection: Boolean = projection != null) {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenTranslator")
            .setContentText(if (projection != null) "正在识别屏幕文字" else "正在聆听语音并准备翻译")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = 0
            if (includeProjection || projection != null) types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        const val ACTION_TRANSLATE_TEXT = "com.screentranslator.app.TRANSLATE_TEXT"
        const val ACTION_TRANSLATION_RESULT = "com.screentranslator.app.TRANSLATION_RESULT"
        const val ACTION_ENABLE_AUDIO = "com.screentranslator.app.ENABLE_AUDIO"
        const val ACTION_DISABLE_AUDIO = "com.screentranslator.app.DISABLE_AUDIO"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_SOURCE_TEXT = "source_text"
        const val EXTRA_TRANSLATION_TEXT = "translation_text"
        const val EXTRA_SOURCE_TYPE = "source_type"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 2401
        private const val FIRST_FRAME_DELAY_MS = 900L
        private const val CAPTURE_INTERVAL_MS = 1600L
        private const val RETRY_DELAY_MS = 300L
    }
}
