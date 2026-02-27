package com.example.androidinstrumentedtest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var reportTextView: TextView
    private lateinit var reportScrollView: ScrollView
    private lateinit var importDataButton: Button
    private lateinit var testTypeRadioGroup: RadioGroup

    companion object {
        const val EXTRA_IS_TEST_MODE = "is_test_mode"
        private const val PICK_FILE_REQUEST_CODE = 1001
        private const val TEST_DATA_FILENAME = "test_data.txt"
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        reportTextView = findViewById(R.id.report_text_view)
        reportScrollView = findViewById(R.id.report_scroll_view)
        importDataButton = findViewById(R.id.import_data_button)
        testTypeRadioGroup = findViewById(R.id.test_type_radio_group)

        val isTestMode = intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)

        if (isTestMode) {
            importDataButton.visibility = View.GONE
            testTypeRadioGroup.visibility = View.GONE
        } else {
            findViewById<RadioButton>(R.id.radio_26_key).isChecked = true
            updateImportPrompt(R.id.radio_26_key)

            testTypeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                updateImportPrompt(checkedId)
            }

            importDataButton.setOnClickListener {
                openFilePicker()
            }
        }

        Toast.makeText(this, "请先将输入法切换到中文输入法", Toast.LENGTH_LONG).show()
    }

    private fun updateImportPrompt(checkedId: Int) {
        val promptText = if (checkedId == R.id.radio_26_key) {
            "请导入26键测试数据文件（例如：pinyin|文字）"
        } else {
            "请导入9键测试数据文件（例如：1234|文字）"
        }
        reportTextView.text = promptText
        reportTextView.setTextColor(Color.BLACK)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                copyDataToAppDirectory(uri)
            }
        }
    }

    private fun copyDataToAppDirectory(uri: Uri) {
        try {
            // Use internal storage for more reliability
            val dataDir = File(filesDir, "InstrumentedTest")
            if (!dataDir.exists()) {
                dataDir.mkdirs()
            }

            val destinationFile = File(dataDir, TEST_DATA_FILENAME)

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream -> // This will overwrite the file if it exists
                    inputStream.copyTo(outputStream)
                }
            }

            val successMessage = "成功导入测试数据: '${destinationFile.name}'\n文件已保存至 '${dataDir.absolutePath}' 目录。\n\n现在可以运行 'KeyboardEvaluationTest' 了。"
            setReportText(successMessage, Color.GREEN)

        } catch (e: Exception) {
            val errorMessage = "错误：导入文件失败。\n${e.message}"
            setReportText(errorMessage, Color.RED)
        }
    }

    fun setReportText(text: String, color: Int) {
        runOnUiThread {
            reportTextView.text = text
            reportTextView.setTextColor(color)
            reportScrollView.post {
                reportScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
