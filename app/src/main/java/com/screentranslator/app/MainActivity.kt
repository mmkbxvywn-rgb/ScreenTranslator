package com.screentranslator.app

import android.Manifest
import android.app.Activity
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
            if (text.isBlank()) {
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

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, dp(16))
            text = "先配置并保存 API，再点“授权并开始实时识别”。授权后应用每隔约 1.6 秒读取一帧画面；识别到的文字发生变化时才请求翻译，以减少重复调用。"
        }
        page.addView(statusText)

        page.addView(TextView(this).apply {
            text = "隐私提示：屏幕内容会先在设备上进行 OCR；识别出的新文字会发送到你配置的 API 服务商进行翻译。请勿在敏感页面启动。API Key 保存在本机应用私有存储。当前版本实现持续屏幕 OCR、变化文本翻译、悬浮字幕，以及通过麦克风进行语音识别与翻译。语音识别会使用设备的语音识别服务。"
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        endpointInput.setText(preferences.getString("endpoint", "https://api.openai.com/v1"))
        apiKeyInput.setText(preferences.getString("api_key", ""))
        modelInput.setText(preferences.getString("model", "gpt-4o-mini"))
        audioLanguageInput.setText(preferences.getString("audio_language", "en-US"))
        speechModelInput.setText(preferences.getString("speech_model", "whisper-1"))
        setContentView(scroll)
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
        listeningForAudio = true
        audioButton.text = "停止内部音频捕获"
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_PLAYBACK_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
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

    private fun showStatus(message: String) {
        statusText.text = message
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
