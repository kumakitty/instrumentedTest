package com.example.androidinstrumentedtest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var startTestButton: Button
    private lateinit var reportTextView: TextView
    private var isTestMode = false

    companion object {
        const val TEST_READY = "com.example.androidinstrumentedtest.TEST_READY"
        const val START_EVALUATION = "com.example.androidinstrumentedtest.START_EVALUATION"
        const val TEST_FINISHED = "com.example.androidinstrumentedtest.TEST_FINISHED"
        const val EXTRA_REPORT = "com.example.androidinstrumentedtest.EXTRA_REPORT"
        const val EXTRA_IS_TEST_MODE = "is_test_mode"
    }

    private val testStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TEST_READY -> {
                    startTestButton.isEnabled = true
                    reportTextView.text = "测试已就绪，请点击‘开始测试’按钮。"
                }
                TEST_FINISHED -> {
                    startTestButton.isEnabled = true
                    val report = intent.getStringExtra(EXTRA_REPORT)
                    reportTextView.text = report
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startTestButton = findViewById(R.id.start_test_button)
        reportTextView = findViewById(R.id.report_text_view)
        
        isTestMode = intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)

        if (isTestMode) {
            startTestButton.isEnabled = false
            reportTextView.text = "正在等待测试环境就绪..."

            startTestButton.setOnClickListener {
                sendBroadcast(Intent(START_EVALUATION))
                startTestButton.isEnabled = false
                reportTextView.text = "测试正在进行中..."
            }

            val filter = IntentFilter().apply {
                addAction(TEST_READY)
                addAction(TEST_FINISHED)
            }
            ContextCompat.registerReceiver(this, testStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            startTestButton.isEnabled = true
            val infoMessage = "这是一个测试工具应用。\n请从Android Studio运行 'KeyboardEvaluationTest' 来开始评测。"
            reportTextView.text = infoMessage
            startTestButton.setOnClickListener {
                Toast.makeText(this, infoMessage, Toast.LENGTH_LONG).show()
            }
        }

        Toast.makeText(this, "请先将输入法切换到中文输入法", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTestMode) {
            unregisterReceiver(testStateReceiver)
        }
    }
}
