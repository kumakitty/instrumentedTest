package com.example.androidinstrumentedtest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
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

    companion object {
        const val EXTRA_IS_TEST_MODE = "is_test_mode"
        private const val PICK_FILE_REQUEST_CODE = 1001
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        reportTextView = findViewById(R.id.report_text_view)
        reportScrollView = findViewById(R.id.report_scroll_view)
        importDataButton = findViewById(R.id.import_data_button)

        val isTestMode = intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)

        if (isTestMode) {
            // In test mode, UI is controlled by the test.
            importDataButton.visibility = View.GONE // Hide the button during test runs
        } else {
            // In normal mode, allow user to import data.
            reportTextView.text = "请点击按钮导入测试数据文件。"
            importDataButton.setOnClickListener {
                openFilePicker()
            }
        }

        Toast.makeText(this, "请先将输入法切换到中文输入法", Toast.LENGTH_LONG).show()
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
                copyDataToPublicDirectory(uri)
            }
        }
    }

    private fun copyDataToPublicDirectory(uri: Uri) {
        try {
            val dataDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "InstrumentedTest")
            if (!dataDir.exists()) {
                dataDir.mkdirs()
            }

            // Extract original file name to use as the destination
            val fileName = getFileName(uri) ?: "imported_test_data.txt"
            val destinationFile = File(dataDir, fileName)

            // Clear the directory before copying the new file
            dataDir.listFiles()?.forEach { it.delete() }

            // Copy the selected file to the public directory
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val successMessage = "成功导入测试数据: '${destinationFile.name}'\n文件已保存至 'Documents/InstrumentedTest/' 目录。\n\n现在可以运行 'KeyboardEvaluationTest' 了。"
            setReportText(successMessage, Color.GREEN)

        } catch (e: Exception) {
            val errorMessage = "错误：导入文件失败。\n${e.message}"
            setReportText(errorMessage, Color.RED)
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                if (cut != null) {
                    result = result?.substring(cut + 1)
                }
            }
        }
        return result
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
