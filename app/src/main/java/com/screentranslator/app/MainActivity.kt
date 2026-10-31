package com.screentranslator.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "ScreenTranslator"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "ScreenTranslator Android 基础工程 v0.1\n\n屏幕 OCR、语音识别、悬浮字幕与自定义翻译接口将在后续版本实现。"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })
        setContentView(layout)
    }
}
