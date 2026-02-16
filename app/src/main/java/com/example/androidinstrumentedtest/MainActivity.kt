package com.example.androidinstrumentedtest

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var reportTextView: TextView
    private lateinit var reportScrollView: ScrollView
    private var isTestMode = false
    private lateinit var defaultTextColor: ColorStateList

    companion object {
        const val EXTRA_IS_TEST_MODE = "is_test_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        reportTextView = findViewById(R.id.report_text_view)
        reportScrollView = findViewById(R.id.report_scroll_view)
        defaultTextColor = reportTextView.textColors

        isTestMode = intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)

        if (isTestMode) {
            reportTextView.text = "测试正在进行中...\n"
        } else {
            val infoMessage = "这是一个测试工具应用。\n请从Android Studio运行 'KeyboardEvaluationTest' 来开始评测。"
            reportTextView.text = infoMessage
        }

        Toast.makeText(this, "请先将输入法切换到中文输入法", Toast.LENGTH_LONG).show()
    }

    fun appendReportText(text: String) {
        reportTextView.append(text)
        reportScrollView.post {
            reportScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun setReportText(text: String) {
        reportTextView.text = text
        reportTextView.setTextColor(defaultTextColor)
        reportScrollView.post {
            reportScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun setReportText(text: String, color: Int) {
        reportTextView.text = text
        reportTextView.setTextColor(color)
        reportScrollView.post {
            reportScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
