package com.screentranslator.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
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
    private lateinit var statusText: TextView
    private var receiverRegistered = false
    private var lastTranslatedText = ""

    private val preferences by lazy { getSharedPreferences("translator_settings", MODE_PRIVATE) }
    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
            if (text.isBlank()) {
                showStatus("屏幕识别没有返回内容。")
            } else if (text.startsWith("未获得") || text.startsWith("启动屏幕") ||
                text.startsWith("无法读取") || text.startsWith("OCR 识别失败")) {
                showStatus(text)
            } else {
                showStatus("正在实时识别屏幕文字…\n\n最新识别结果：\n$text\n\n正在请求翻译…")
                if (text != lastTranslatedText) {
                    lastTranslatedText = text
                    translateText(text)
                }
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

        endpointInput = field(page, "API 地址（OpenAI 兼容接口）", "https://api.openai.com/v1")
        apiKeyInput = field(page, "API Key", "")
        apiKeyInput.inputType = 0x00000081
        modelInput = field(page, "模型名称", "gpt-4o-mini")

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
            text = "隐私提示：屏幕内容会先在设备上进行 OCR；识别出的新文字会发送到你配置的 API 服务商进行翻译。请勿在敏感页面启动。API Key 保存在本机应用私有存储。当前版本实现持续屏幕 OCR 和变化文本翻译；悬浮字幕、语音转写尚未实现。"
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        endpointInput.setText(preferences.getString("endpoint", "https://api.openai.com/v1"))
        apiKeyInput.setText(preferences.getString("api_key", ""))
        modelInput.setText(preferences.getString("model", "gpt-4o-mini"))
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)
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
        lastTranslatedText = ""
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        showStatus("屏幕捕捉已授权，正在启动持续 OCR…")
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

    private fun translateText(sourceText: String) {
        saveSettings()
        val endpoint = preferences.getString("endpoint", "").orEmpty().trimEnd('/')
        val key = preferences.getString("api_key", "").orEmpty()
        val model = preferences.getString("model", "").orEmpty()
        if (endpoint.isBlank() || key.isBlank() || model.isBlank()) {
            showStatus("OCR 已完成，但尚未翻译：请填写并保存 API 地址、API Key 和模型名称。\n\n识别结果：\n$sourceText")
            return
        }
        thread {
            var connection: HttpURLConnection? = null
            val result = try {
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
                    .put(JSONObject().put("role", "system").put("content", "Translate the provided screen text into natural Simplified Chinese. Preserve names, numbers, line breaks, and meaning. If the text is already Chinese, briefly explain that it is already Chinese. Output only the translation."))
                    .put(JSONObject().put("role", "user").put("content", sourceText.take(12000)))
                val body = JSONObject().put("model", model).put("messages", messages).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val json = JSONObject(response)
                    json.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                        ?.takeIf { it.isNotBlank() } ?: "API 返回成功，但响应中没有翻译文本。"
                } else "翻译接口返回 HTTP $code：$response".take(1500)
            } catch (e: Exception) {
                "翻译失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
            } finally {
                connection?.disconnect()
            }
            runOnUiThread {
                showStatus("实时屏幕识别：\n$sourceText\n\n—— 翻译 ——\n$result\n\n（识别仍在后台继续；停止请点“停止屏幕识别”）")
            }
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
    }
}
