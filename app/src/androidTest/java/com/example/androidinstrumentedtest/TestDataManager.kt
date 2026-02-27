package com.example.androidinstrumentedtest

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestDataManager(private val context: Context) {

    private val tag = "TestDataManager"
    // For reading test data - this uses internal app storage
    private val internalDataDir = File(context.filesDir, "InstrumentedTest")
    private val testDataFile = File(internalDataDir, "test_data.txt")

    // For writing the report - this uses public storage
    private val publicReportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "InstrumentedTest")

    fun readTestData(onTestAborted: (String) -> Unit): List<Pair<String, String>> {
        val testData = mutableListOf<Pair<String, String>>()

        if (!internalDataDir.exists() || !testDataFile.exists()) {
            val errorMessage = "错误：未在设备上找到测试数据。请先返回主应用，点击‘导入数据’按钮导入文件。应位于：${testDataFile.absolutePath}"
            Log.e(tag, errorMessage)
            onTestAborted(errorMessage)
            return testData
        }

        Log.i(tag, "Reading test data from: ${testDataFile.absolutePath}")
        try {
            BufferedReader(testDataFile.reader()).forEachLine { line ->
                val parts = line.split('|')
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    testData.add(Pair(parts[0].trim(), parts[1].trim()))
                }
            }
        } catch (e: Exception) {
            val errorMessage = "错误：读取测试数据文件时出错。"
            Log.e(tag, "$errorMessage ${testDataFile.absolutePath}", e)
            onTestAborted(errorMessage)
        }

        if (testData.isEmpty()) {
            val errorMessage = "错误：测试数据为空。请检查文件内容或确保已导入正确的文件。"
            Log.e(tag, errorMessage)
            onTestAborted(errorMessage)
        }

        return testData
    }

    fun saveReport(report: String): File? {
        return try {
            if (!publicReportDir.exists()) {
                publicReportDir.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val reportFile = File(publicReportDir, "keyboard_evaluation_report_$timestamp.txt")
            reportFile.writeText(report)
            reportFile
        } catch (e: Exception) {
            Log.e(tag, "Failed to save test report to public directory.", e)
            null
        }
    }
}
