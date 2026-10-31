package com.screentranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenTranslator")
            .setContentText("正在读取一次屏幕画面以识别文字")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            sendResult("未获得屏幕捕捉授权。")
            stopSelf()
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
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
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
            handler?.postDelayed({ recognizeFrame() }, 900)
        } catch (e: Exception) {
            sendResult("启动屏幕捕捉失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            cleanup()
        }
        return START_NOT_STICKY
    }

    private fun recognizeFrame() {
        if (finished) return
        val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            handler?.postDelayed({ recognizeFrame() }, 300)
            return
        }
        val input = try { InputImage.fromMediaImage(image, 0) } catch (e: Exception) {
            image.close()
            sendResult("无法读取屏幕画面：${e.message ?: "未知错误"}")
            cleanup()
            return
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                sendResult(if (text.isBlank()) "本次画面没有识别到文字。" else text)
            }
            .addOnFailureListener { e ->
                sendResult("OCR 识别失败：${e.message ?: "未知错误"}")
            }
            .addOnCompleteListener {
                image.close()
                recognizer.close()
                cleanup()
            }
    }

    private fun sendResult(text: String) {
        sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName).putExtra(EXTRA_OCR_TEXT, text))
    }

    private fun cleanup() {
        if (finished) return
        finished = true
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OCR_TEXT = "ocr_text"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 2401
    }
}
