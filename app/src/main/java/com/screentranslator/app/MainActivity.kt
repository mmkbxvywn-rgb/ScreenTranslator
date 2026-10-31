package com.screentranslator.app

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var audioLanguageInput: EditText
    private lateinit var speechModelInput: EditText
    private lateinit var statusText: TextView
    private lateinit var audioButton: Button
    private lateinit var translationModeButton: Button
    private lateinit var speechTimingButton: Button
    private var fastSentenceMode = false
    private var offlineTranslation = false
    private var listeningForAudio = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var lastOverlayText = ""

    private val preferences by lazy { getSharedPreferences("translator_settings", MODE_PRIVATE) }
    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_TRANSLATION_RESULT) {
                val source = intent.getStringExtra(ScreenCaptureService.EXTRA_SOURCE_TEXT).orEmpty()
                val translated = intent.getStringExtra(ScreenCaptureService.EXTRA_TRANSLATION_TEXT).orEmpty()
                val sourceType = intent.getStringExtra(ScreenCaptureService.EXTRA_SOURCE_TYPE).orEmpty()
                lastOverlayText = if (sourceType == "speech") {
                    "语音识别：\n$source\n\n—— 中文翻译 ——\n$translated"
                } else {
                    "$source\n\n—— 中文翻译 ——\n$translated"
                }
                showStatus(lastOverlayText)
                return
            }
            val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
            if (text.startsWith("已启动 Android 内部播放音频捕获")) {
                listeningForAudio = true
                audioButton.text = "停止内部音频捕获"
                showStatus(text)
            } else if (text.startsWith("请先点击") || text.startsWith("内部音频捕获需要") ||
                text.startsWith("无法启动内部音频捕获") || text.startsWith("内部音频录制器初始化失败") ||
                text.startsWith("内部音频读取失败") || text.startsWith("语音识别 API")) {
                listeningForAudio = false
                audioButton.text = "开始内部音频捕获与翻译"
                showStatus(text)
            } else if (text.isBlank()) {
                showStatus("屏幕识别没有返回内容。")
            } else if (text.startsWith("未获得") || text.startsWith("启动屏幕") ||
                text.startsWith("无法读取") || text.startsWith("OCR 识别失败")) {
                showStatus(text)
            } else {
                lastOverlayText = text
                showStatus("正在实时识别屏幕文字…\n\n最新识别结果：\n$text\n\n翻译在前台或后台服务中继续处理。")
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ScreenTranslator"

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(page) }

        page.addView(TextView(this).apply {
            text = "ScreenTranslator"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        page.addView(TextView(this).apply {
            text = "实时屏幕文字识别与 AI 翻译"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(24))
        })

        val captureButton = Button(this).apply {
            text = "授权并开始实时识别"
            setOnClickListener {
                saveSettings()
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val overlayIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivityForResult(overlayIntent, REQUEST_OVERLAY)
                    showStatus("请在系统设置中允许 ScreenTranslator 显示在其他应用上层，然后返回应用并再次点击开始。")
                    return@setOnClickListener
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
            }
        }
        page.addView(captureButton, matchWidth())

        val stopButton = Button(this).apply {
            text = "停止屏幕识别"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                })
                showStatus("已发送停止指令。若系统屏幕共享通知仍显示，请稍候确认服务已结束。")
            }
        }
        page.addView(stopButton, matchWidth())

        val overlayButton = Button(this).apply {
            text = "重新显示悬浮字幕"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        REQUEST_OVERLAY
                    )
                    showStatus("请先允许显示在其他应用上层，再点击一次重新显示悬浮字幕。")
                    return@setOnClickListener
                }
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SHOW_TRANSLATION
                    putExtra(ScreenCaptureService.EXTRA_OCR_TEXT, "")
                })
                showStatus("已请求重新显示悬浮字幕。")
            }
        }
        page.addView(overlayButton, matchWidth())

        audioButton = Button(this).apply {
            text = "开始内部音频捕获与翻译"
            setOnClickListener {
                if (listeningForAudio) stopAudioTranslation() else requestAudioPermissionAndStart()
            }
        }
        page.addView(audioButton, matchWidth())

        translationModeButton = Button(this).apply {
            setOnClickListener {
                offlineTranslation = !offlineTranslation
                preferences.edit().putBoolean("offline_translation", offlineTranslation).apply()
                updateTranslationModeButton()
                showStatus(if (offlineTranslation)
                    "已切换为离线翻译。首次使用某个语言时需要下载对应模型；下载完成后翻译可在无网络时运行。语音转文字仍使用配置的在线 /audio/transcriptions 接口。"
                else "已切换为在线翻译，将使用你配置的 /chat/completions API。")
            }
        }
        page.addView(translationModeButton, matchWidth())
        val downloadOfflineButton = Button(this).apply {
            text = "下载/检查离线翻译模型"
            setOnClickListener { downloadOfflineModel() }
        }
        page.addView(downloadOfflineButton, matchWidth())

        speechTimingButton = Button(this).apply {
            setOnClickListener {
                fastSentenceMode = !fastSentenceMode
                preferences.edit().putBoolean("fast_sentence_mode", fastSentenceMode).apply()
                updateSpeechTimingButton()
                showStatus(if (fastSentenceMode)
                    "已切换为快速按句模式：约每 3 秒提交一段音频，识别到完整句子或明显分句后立即翻译。响应更快，但短音频和句子切分可能降低识别/翻译准确性。"
                else
                    "已切换为稳妥延迟模式：约每 8 秒提交一段音频，保留更多上下文，通常更利于识别和翻译完整语意，但字幕会更慢。")
            }
        }
        page.addView(speechTimingButton, matchWidth())

        endpointInput = field(page, "API 地址（OpenAI 兼容接口）", "https://api.openai.com/v1")
        apiKeyInput = field(page, "API Key", "")
        apiKeyInput.inputType = 0x00000081
        modelInput = field(page, "模型名称", "gpt-4o-mini")
        audioLanguageInput = field(page, "音频语言代码（例如 en-US、ja-JP、ko-KR）", "en-US")
        speechModelInput = field(page, "语音识别模型（API 需支持 /audio/transcriptions）", "whisper-1")

        val saveButton = Button(this).apply {
            text = "保存配置"
            setOnClickListener {
                saveSettings()
                showStatus("配置已保存在本机应用数据中。")
            }
        }
        page.addView(saveButton, matchWidth())

        val testButton = Button(this).apply {
            text = "测试 API 连接"
            setOnClickListener { testApi() }
        }
        page.addView(testButton, matchWidth())
        val speechTestButton = Button(this).apply {
            text = "单独测试语音识别 API"
            setOnClickListener { testSpeechApi() }
        }
        page.addView(speechTestButton, matchWidth())

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, dp(16))
            text = "先配置并保存 API，再点“授权并开始实时识别”。授权后应用每隔约 1.6 秒读取一帧画面；识别到的文字发生变化时才请求翻译，以减少重复调用。"
        }
        page.addView(statusText)

        page.addView(TextView(this).apply {
            text = "隐私提示：屏幕内容会先在设备上进行 OCR；识别出的新文字会发送到你配置的 API 服务商进行翻译。请勿在敏感页面启动。API Key 保存在本机应用私有存储。当前版本实现持续屏幕 OCR、变化文本翻译、悬浮字幕，以及通过 Android 内部播放音频捕获进行语音识别与翻译。捕获的音频片段会发送到你配置的 /audio/transcriptions 接口。"
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        endpointInput.setText(preferences.getString("endpoint", "https://api.openai.com/v1"))
        apiKeyInput.setText(preferences.getString("api_key", ""))
        modelInput.setText(preferences.getString("model", "gpt-4o-mini"))
        audioLanguageInput.setText(preferences.getString("audio_language", "en-US"))
        speechModelInput.setText(preferences.getString("speech_model", "whisper-1"))
        offlineTranslation = preferences.getBoolean("offline_translation", false)
        fastSentenceMode = preferences.getBoolean("fast_sentence_mode", false)
        updateTranslationModeButton()
        updateSpeechTimingButton()
        setContentView(scroll)
        styleInterface(page)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT).apply {
                addAction(ScreenCaptureService.ACTION_TRANSLATION_RESULT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(ocrReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(ocrReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(ocrReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopAudioTranslation()
        super.onDestroy()
    }

    @Deprecated("Deprecated by Android; retained for compatibility with screen-capture consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            showStatus(if (Settings.canDrawOverlays(this)) "悬浮字幕权限已开启，请再次点击开始识别。" else "尚未授予悬浮字幕权限。")
            return
        }
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            showStatus("你取消了屏幕捕捉授权。")
            return
        }
        lastOverlayText = ""
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        showStatus("屏幕捕捉已授权，正在启动持续 OCR…")
    }

    private fun requestAudioPermissionAndStart() {
        saveSettings()
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
            showStatus("请先允许显示在其他应用上层，再点击开始内部音频翻译。")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            return
        }
        startPlaybackAudioCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startPlaybackAudioCapture()
            else showStatus("未获得录音权限。Android 内部播放音频捕获同样要求授予此权限，但音频源是系统播放流而非麦克风。")
        }
    }

    private fun startPlaybackAudioCapture() {
        listeningForAudio = false
        audioButton.text = "正在启动内部音频捕获…"
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_PLAYBACK_AUDIO
        }
        startService(serviceIntent)
        showStatus("正在请求 Android 内部播放音频捕获。请先完成屏幕捕获授权；如果尚未授权，请点击上方“授权并开始实时识别”，然后再次启动本功能。语音识别将调用你配置的 /audio/transcriptions 接口。")
    }

    private fun stopAudioTranslation() {
        listeningForAudio = false
        try {
            startService(Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP_PLAYBACK_AUDIO
            })
        } catch (_: Exception) {}
        audioButton.text = "开始内部音频捕获与翻译"
        showStatus("已请求停止内部播放音频捕获。")
    }

    private fun field(parent: LinearLayout, label: String, initial: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        })
        return EditText(this).also { input ->
            input.setSingleLine(true)
            input.setText(initial)
            input.hint = label
            parent.addView(input, matchWidth())
        }
    }

    private fun saveSettings() {
        preferences.edit()
            .putString("endpoint", endpointInput.text.toString().trim().trimEnd('/'))
            .putString("api_key", apiKeyInput.text.toString().trim())
            .putString("model", modelInput.text.toString().trim())
            .putString("audio_language", audioLanguageInput.text.toString().trim().ifBlank { "en-US" })
            .putString("speech_model", speechModelInput.text.toString().trim().ifBlank { "whisper-1" })
            .apply()
    }

    private fun testApi() {
        saveSettings()
        val endpoint = preferences.getString("endpoint", "").orEmpty().trimEnd('/')
        val key = preferences.getString("api_key", "").orEmpty()
        val model = preferences.getString("model", "").orEmpty()
        if (endpoint.isBlank() || key.isBlank() || model.isBlank()) {
            showStatus("请先填写完整的 API 地址、API Key 和模型名称。")
            return
        }
        val url = if (endpoint.endsWith("/chat/completions")) endpoint else "$endpoint/chat/completions"
        showStatus("正在测试连接…")
        thread {
            var connection: HttpURLConnection? = null
            val result = try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", "Reply with OK only.")
                    ))
                    .put("max_tokens", 10)
                    .toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) "连接成功（HTTP $code）。服务已返回响应。"
                else "接口返回 HTTP $code：$response".take(1200)
            } catch (e: Exception) {
                "连接失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}".take(800)
            } finally {
                connection?.disconnect()
            }
            runOnUiThread { showStatus(result) }
        }
    }


    private fun downloadOfflineModel() {
        val languageTag = audioLanguageInput.text.toString().trim().ifBlank { "en-US" }
        val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag.substringBefore('-').lowercase())
        if (sourceLanguage == null || sourceLanguage == TranslateLanguage.CHINESE) {
            showStatus("无法下载对应模型。请填写视频原始语言代码，例如 en-US、ja-JP、ko-KR、fr-FR、de-DE。")
            return
        }
        showStatus("正在检查/下载 $languageTag → 中文的离线翻译模型。请保持网络连接，首次下载可能需要一些时间……")
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage).setTargetLanguage(TranslateLanguage.CHINESE).build())
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.close()
                showStatus("离线翻译模型已准备好：$languageTag → 中文。文字翻译可离线运行；语音转文字仍需要在线语音识别 API。")
            }
            .addOnFailureListener { error ->
                translator.close()
                showStatus("离线模型下载失败：\${error.message ?: error.javaClass.simpleName}。请检查网络、存储空间或 Google Play 服务后重试。")
            }
    }


    private fun testSpeechApi() {
        saveSettings()
        val endpoint = preferences.getString("endpoint", "").orEmpty().trimEnd('/')
        val key = preferences.getString("api_key", "").orEmpty()
        val model = preferences.getString("speech_model", "whisper-1").orEmpty().ifBlank { "whisper-1" }
        if (endpoint.isBlank() || key.isBlank()) {
            showStatus("请先填写 API 地址和 API Key。语音模型填写在独立的“语音识别模型”栏。")
            return
        }
        val url = when {
            endpoint.endsWith("/audio/transcriptions") -> endpoint
            endpoint.endsWith("/chat/completions") -> endpoint.removeSuffix("/chat/completions") + "/audio/transcriptions"
            else -> "$endpoint/audio/transcriptions"
        }
        showStatus("正在单独测试语音识别接口（模型：$model）……")
        thread {
            var connection: HttpURLConnection? = null
            val result = try {
                val wav = silentWavForApiTest()
                val boundary = "----ScreenTranslatorTest" + System.currentTimeMillis()
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                java.io.DataOutputStream(connection.outputStream).use { out ->
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                    out.write(model.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"test.wav\"\r\n")
                    out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                    out.write(wav)
                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) "语音 API 可用（HTTP $code）。响应：$response".take(1000)
                else "语音 API 测试失败（HTTP $code）。聊天接口连接成功不代表语音接口可用。请检查服务商是否支持 /audio/transcriptions、模型“$model”是否受支持、API 根地址是否正确。返回：$response".take(1400)
            } catch (e: Exception) {
                "语音 API 请求失败：" + e.javaClass.simpleName + "：" + (e.message ?: "未知错误") + "。请检查网络、API 地址和服务商接口兼容性。".take(1000)
            } finally {
                connection?.disconnect()
            }
            runOnUiThread { showStatus(result) }
        }
    }

    private fun silentWavForApiTest(): ByteArray {
        val rate = 16000
        val pcm = ByteArray(rate)
        val bytes = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(bytes)
        fun leInt(v: Int) {
            out.writeByte(v and 255)
            out.writeByte((v shr 8) and 255)
            out.writeByte((v shr 16) and 255)
            out.writeByte((v shr 24) and 255)
        }
        fun leShort(v: Int) {
            out.writeByte(v and 255)
            out.writeByte((v shr 8) and 255)
        }
        out.writeBytes("RIFF")
        leInt(36 + pcm.size)
        out.writeBytes("WAVEfmt ")
        leInt(16)
        leShort(1)
        leShort(1)
        leInt(rate)
        leInt(rate * 2)
        leShort(2)
        leShort(16)
        out.writeBytes("data")
        leInt(pcm.size)
        out.write(pcm)
        out.flush()
        return bytes.toByteArray()
    }

    private fun updateTranslationModeButton() {
        translationModeButton.text = if (offlineTranslation)
            "翻译模式：离线（点击切换在线）"
        else
            "翻译模式：在线（点击切换离线）"
    }

    private fun updateSpeechTimingButton() {
        speechTimingButton.text = if (fastSentenceMode)
            "语音节奏：快速按句（点击切换稳妥延迟）"
        else
            "语音节奏：稳妥延迟（点击切换快速按句）"
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }


    private fun styleInterface(root: LinearLayout) {
        root.setBackgroundColor(Color.rgb(244, 247, 251))
        fun styleChildren(group: android.view.ViewGroup) {
            for (i in 0 until group.childCount) {
                val view = group.getChildAt(i)
                when (view) {
                    is Button -> {
                        view.isAllCaps = false
                        view.textSize = 15f
                        view.setTextColor(Color.WHITE)
                        view.backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 93, 170))
                        val lp = view.layoutParams as? LinearLayout.LayoutParams
                        if (lp != null) {
                            lp.topMargin = dp(5)
                            lp.bottomMargin = dp(5)
                            view.layoutParams = lp
                        }
                        view.minHeight = dp(48)
                    }
                    is EditText -> {
                        view.textSize = 15f
                        view.setPadding(dp(12), dp(10), dp(12), dp(10))
                        view.background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            setStroke(dp(1), Color.rgb(210, 219, 232))
                            cornerRadius = dp(10).toFloat()
                        }
                    }
                    is TextView -> {
                        if (view.textSize >= 26f) {
                            view.setTextColor(Color.rgb(27, 48, 79))
                            view.typeface = android.graphics.Typeface.DEFAULT_BOLD
                        } else {
                            view.setTextColor(Color.rgb(65, 77, 96))
                        }
                    }
                    is android.view.ViewGroup -> styleChildren(view)
                }
            }
        }
        styleChildren(root)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE = 502
        private const val REQUEST_OVERLAY = 503
        private const val REQUEST_AUDIO_PERMISSION = 504
    }
}
