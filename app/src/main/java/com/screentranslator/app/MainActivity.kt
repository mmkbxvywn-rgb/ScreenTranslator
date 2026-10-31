package com.screentranslator.app

import android.app.Activity
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

    private val preferences by lazy { getSharedPreferences("translator_settings", MODE_PRIVATE) }

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
            text = "实时屏幕翻译 · API 配置"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(24))
        })

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
            setPadding(0, dp(12), 0, dp(16))
            text = "请填写接口地址、API Key 和模型名称，然后保存或测试连接。"
        }
        page.addView(statusText)

        page.addView(TextView(this).apply {
            text = "说明\n• API 地址填写服务商提供的根地址，通常以 /v1 结尾。\n• API Key 仅保存在本机应用私有存储中；不要把含密钥的截图或日志分享给他人。\n• 当前版本先完成自定义接口配置与连通性测试；屏幕 OCR、实时语音转写及悬浮字幕仍在开发中。"
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        })

        endpointInput.setText(preferences.getString("endpoint", "https://api.openai.com/v1"))
        apiKeyInput.setText(preferences.getString("api_key", ""))
        modelInput.setText(preferences.getString("model", "gpt-4o-mini"))
        setContentView(scroll)
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
                if (code in 200..299) {
                    "连接成功（HTTP $code）。服务已返回响应。"
                } else {
                    "接口返回 HTTP $code：$response".take(1200)
                }
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
}
