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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioAttributes
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    @Volatile private var playbackCaptureActive = false
    @Volatile private var playbackAudioRecord: AudioRecord? = null
    @Volatile private var stoppingProjection = false
    private var lastText = ""
    private var overlayView: LinearLayout? = null
    private var overlayTextView: TextView? = null
    private var overlayManager: WindowManager? = null
    @Volatile private var overlayDismissed = false
    @Volatile private var latestDisplayText = "ScreenTranslator 正在识别屏幕…"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val translationExecutor = Executors.newSingleThreadExecutor()
    private val playbackTranscriptionExecutor = Executors.newSingleThreadExecutor()
    private val translationLock = Any()
    private var pendingTranslation: Pair<String, String>? = null
    private var translationWorkerRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (audioActive || playbackCaptureActive) stopScreenCaptureOnly() else cleanup()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ENABLE_AUDIO) {
            audioActive = true
            promoteForeground()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISABLE_AUDIO) {
            audioActive = false
            stopPlaybackCapture()
            if (projection != null) promoteForeground()
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_PLAYBACK_AUDIO) {
            startPlaybackCapture()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_PLAYBACK_AUDIO) {
            stopPlaybackCapture()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW_TRANSLATION) {
            overlayDismissed = false
            val requestedText = intent.getStringExtra(EXTRA_OCR_TEXT).orEmpty()
            updateOverlay(requestedText.ifBlank { latestDisplayText })
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRANSLATE_TEXT) {
            val text = intent.getStringExtra(EXTRA_OCR_TEXT).orEmpty().trim()
            val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE).orEmpty().ifBlank { "speech" }
            if (text.isNotBlank()) enqueueTranslation(text, sourceType)
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        promoteForeground(includeProjection = true)

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
            lastText = ""
            worker = HandlerThread("ScreenOCR").also { it.start() }
            handler = Handler(worker!!.looper)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (stoppingProjection) return
                    if (audioActive) stopScreenCaptureOnly(stopProjection = false) else cleanup()
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
        latestDisplayText = initialText
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
        latestDisplayText = text
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

    private fun startPlaybackCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sendResult("内部音频捕获需要 Android 10 或更高版本。")
            return
        }
        val activeProjection = projection
        if (activeProjection == null) {
            sendResult("请先点击“授权并开始实时识别”，完成 Android 屏幕捕获授权，再启动内部音频翻译。")
            return
        }
        if (playbackCaptureActive) {
            sendResult("内部音频捕获已经在运行。")
            return
        }
        val prefs = getSharedPreferences("translator_settings", MODE_PRIVATE)
        val language = prefs.getString("audio_language", "en-US").orEmpty().ifBlank { "en-US" }
        val sampleRate = 16000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            sendResult("设备不支持所需的 PCM 音频格式，无法启动内部音频捕获。")
            return
        }
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 4, sampleRate * 2 * 8))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                sendResult("内部音频录制器初始化失败。请确认已授权屏幕捕获，并检查目标应用是否允许音频捕获。")
                return
            }
            playbackAudioRecord = record
            playbackCaptureActive = true
            promoteForeground()
            sendResult("已启动 Android 内部播放音频捕获。请播放视频；音频将分段送到配置的语音识别 API，再翻译为中文。")
            Thread({
                val buffer = ByteArray(8192)
                val chunk = ByteArrayOutputStream(sampleRate * 2 * 8)
                var lastSubmit = System.currentTimeMillis()
                try {
                    record.startRecording()
                    while (playbackCaptureActive && !finished) {
                        val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            chunk.write(buffer, 0, count)
                            val prefsNow = getSharedPreferences("translator_settings", MODE_PRIVATE)
                            val fastModeNow = prefsNow.getBoolean("fast_sentence_mode", false)
                            val targetSeconds = if (fastModeNow) 3 else 8
                            val targetBytes = sampleRate * 2 * targetSeconds
                            val now = System.currentTimeMillis()
                            if (chunk.size() >= targetBytes || now - lastSubmit >= targetSeconds * 1000L) {
                                val pcm = chunk.toByteArray()
                                chunk.reset()
                                lastSubmit = now
                                if (pcm.size >= sampleRate * 2) {
                                    val wav = pcmToWav(pcm, sampleRate, 1, 16)
                                    playbackTranscriptionExecutor.execute {
                                        val transcript = transcribePlaybackAudio(wav, language)
                                        if (transcript.isNotBlank() && !transcript.startsWith("语音识别 API")) {
                                            handleTranscribedSpeech(transcript, fastModeNow)
                                        } else if (transcript.startsWith("语音识别 API")) {
                                            sendResult(transcript)
                                        }
                                    }
                                }
                            }
                        } else if (count < 0) {
                            sendResult("内部音频读取失败，错误码：" + count)
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (playbackCaptureActive) sendResult("内部音频捕获失败：" + e.javaClass.simpleName + "：" + (e.message ?: "未知错误"))
                } finally {
                    try { record.stop() } catch (_: Exception) {}
                    record.release()
                    if (playbackAudioRecord === record) playbackAudioRecord = null
                    playbackCaptureActive = false
                }
            }, "PlaybackAudioCapture").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            playbackCaptureActive = false
            sendResult("无法启动内部音频捕获：" + e.javaClass.simpleName + "：" + (e.message ?: "未知错误"))
        }
    }

    private fun stopPlaybackCapture() {
        playbackCaptureActive = false
        val record = playbackAudioRecord
        playbackAudioRecord = null
        try { record?.stop() } catch (_: Exception) {}
        // The capture thread owns final release after stop() unblocks its read loop.
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val output = ByteArrayOutputStream(pcm.size + 44)
        val data = DataOutputStream(output)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        data.writeBytes("RIFF")
        writeLittleEndianInt(data, 36 + pcm.size)
        data.writeBytes("WAVE")
        data.writeBytes("fmt ")
        writeLittleEndianInt(data, 16)
        writeLittleEndianShort(data, 1)
        writeLittleEndianShort(data, channels)
        writeLittleEndianInt(data, sampleRate)
        writeLittleEndianInt(data, byteRate)
        writeLittleEndianShort(data, channels * bitsPerSample / 8)
        writeLittleEndianShort(data, bitsPerSample)
        data.writeBytes("data")
        writeLittleEndianInt(data, pcm.size)
        data.write(pcm)
        data.flush()
        return output.toByteArray()
    }

    private fun writeLittleEndianInt(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value shr 8) and 0xff)
        out.writeByte((value shr 16) and 0xff)
        out.writeByte((value shr 24) and 0xff)
    }

    private fun writeLittleEndianShort(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value shr 8) and 0xff)
    }

    private fun transcribePlaybackAudio(wav: ByteArray, language: String): String {
        val prefs = getSharedPreferences("translator_settings", MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trimEnd('/')
        val key = prefs.getString("api_key", "").orEmpty()
        val model = prefs.getString("speech_model", "whisper-1").orEmpty().ifBlank { "whisper-1" }
        if (endpoint.isBlank() || key.isBlank()) return "语音识别 API：请先配置 API 地址和 API Key。"
        var connection: HttpURLConnection? = null
        return try {
            val url = if (endpoint.endsWith("/audio/transcriptions")) endpoint else endpoint + "/audio/transcriptions"
            val boundary = "----ScreenTranslator" + System.currentTimeMillis()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Authorization", "Bearer " + key)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            }
            DataOutputStream(connection.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--" + boundary + "\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("model", model)
                val lang = language.substringBefore('-').lowercase()
                if (lang.isNotBlank()) field("language", lang)
                out.writeBytes("--" + boundary + "\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n--" + boundary + "--\r\n")
                out.flush()
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) JSONObject(response).optString("text").trim()
            else "语音识别 API 返回 HTTP " + code + "：" + response.take(1000)
        } catch (e: Exception) {
            "语音识别 API 请求失败：" + e.javaClass.simpleName + "：" + (e.message ?: "未知错误")
        } finally {
            connection?.disconnect()
        }
    }

    private val speechSentenceLock = Any()
    private var pendingSpeechSentence = ""

    private fun handleTranscribedSpeech(transcript: String, fastMode: Boolean) {
        if (!fastMode) {
            enqueueTranslation(transcript.trim(), "speech")
            return
        }
        // Keep incomplete fragments until punctuation indicates a likely sentence boundary.
        // For very long fragments, use a clause boundary rather than waiting indefinitely.
        val ready = mutableListOf<String>()
        synchronized(speechSentenceLock) {
            val incoming = transcript.trim()
            if (incoming.isBlank()) return
            pendingSpeechSentence = (pendingSpeechSentence + " " + incoming).trim()
            val pieces = Regex("(?<=[.!?。！？；;])\\s*").split(pendingSpeechSentence)
            if (pieces.size > 1) {
                val completedText = pieces.dropLast(1).joinToString(" ").trim()
                if (completedText.isNotBlank()) ready.addAll(splitSpeechSentences(completedText))
                pendingSpeechSentence = pieces.last().trim()
            }
            if (pendingSpeechSentence.length >= 48) {
                val clauseBoundary = Regex("(?<=[,，、:：])\\s*").split(pendingSpeechSentence)
                if (clauseBoundary.size > 1) {
                    val completedClause = clauseBoundary.dropLast(1).joinToString(" ").trim()
                    if (completedClause.isNotBlank()) ready.add(completedClause)
                    pendingSpeechSentence = clauseBoundary.last().trim()
                }
            }
        }
        ready.filter { it.isNotBlank() }.forEach { enqueueTranslation(it, "speech") }
    }

    private fun splitSpeechSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?。！？；;])\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
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
        if (prefs.getBoolean("offline_translation", false)) {
            return translateOffline(sourceText)
        }
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

    private fun translateOffline(sourceText: String): String {
        if (sourceText.isBlank()) return ""
        val detector = LanguageIdentification.getClient()
        var detectedLanguage = "und"
        val detectLatch = CountDownLatch(1)
        detector.identifyLanguage(sourceText)
            .addOnSuccessListener { detectedLanguage = it; detectLatch.countDown() }
            .addOnFailureListener { detectLatch.countDown() }
        if (!detectLatch.await(20, TimeUnit.SECONDS)) {
            detector.close()
            return "离线翻译失败：语言识别超时。"
        }
        detector.close()
        if (detectedLanguage == "zh") return sourceText
        val sourceLanguage = TranslateLanguage.fromLanguageTag(detectedLanguage)
            ?: return "离线翻译暂不支持识别出的语言（$detectedLanguage）。请切换在线翻译。"
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        val translator: Translator = Translation.getClient(options)
        try {
            var downloadError: Exception? = null
            val downloadLatch = CountDownLatch(1)
            translator.downloadModelIfNeeded()
                .addOnSuccessListener { downloadLatch.countDown() }
                .addOnFailureListener { error -> downloadError = error; downloadLatch.countDown() }
            if (!downloadLatch.await(90, TimeUnit.SECONDS)) {
                return "离线翻译模型准备超时。请保持网络连接后重试。"
            }
            if (downloadError != null) {
                return "离线翻译模型下载失败：${downloadError?.message ?: "请检查网络后重试"}"
            }
            var translated = ""
            var translationError: Exception? = null
            val translateLatch = CountDownLatch(1)
            translator.translate(sourceText.take(4000))
                .addOnSuccessListener { translated = it; translateLatch.countDown() }
                .addOnFailureListener { error -> translationError = error; translateLatch.countDown() }
            if (!translateLatch.await(60, TimeUnit.SECONDS)) {
                return "离线翻译超时，请重试或切换在线翻译。"
            }
            if (translationError != null) {
                return "离线翻译失败：${translationError?.message ?: "未知错误"}"
            }
            return translated.ifBlank { "离线翻译未返回结果。" }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return "离线翻译已中断。"
        } catch (e: Exception) {
            return "离线翻译失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
        } finally {
            translator.close()
        }
    }

    private fun stopScreenCaptureOnly(stopProjection: Boolean = true) {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        handler = null
        if (playbackCaptureActive) {
            // Keep the MediaProjection alive: AudioPlaybackCaptureConfiguration depends on it.
            promoteForeground()
            updateOverlay("屏幕识别已停止，内部音频翻译仍在运行。")
            return
        }
        val oldProjection = projection
        projection = null
        if (stopProjection && oldProjection != null) {
            stoppingProjection = true
            try { oldProjection.stop() } catch (_: Exception) {} finally { stoppingProjection = false }
        }
        if (audioActive) {
            promoteForeground()
            updateOverlay("屏幕识别已停止，语音翻译仍在运行。")
        }
    }

    private fun cleanup() {
        if (finished) return
        finished = true
        stopPlaybackCapture()
        translationExecutor.shutdownNow()
        playbackTranscriptionExecutor.shutdownNow()
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
        const val ACTION_START_PLAYBACK_AUDIO = "com.screentranslator.app.START_PLAYBACK_AUDIO"
        const val ACTION_STOP_PLAYBACK_AUDIO = "com.screentranslator.app.STOP_PLAYBACK_AUDIO"
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
