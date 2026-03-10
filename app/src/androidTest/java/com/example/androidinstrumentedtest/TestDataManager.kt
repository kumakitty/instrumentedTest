package com.example.androidinstrumentedtest

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestDataManager(private val context: Context) {

    private val tag = "TestDataManager"
    private val internalDataDir = File(context.filesDir, "InstrumentedTest")
    private val testDataFile = File(internalDataDir, "test_data.txt")
    // Use public Documents directory for easy access via file manager
    // Path will be: /sdcard/Documents/InstrumentedTest
    private val publicReportDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "InstrumentedTest"
    )
    private val publicDebugDir = File(publicReportDir, "test_debug")

    fun getDebugDir(): File {
        if (!publicDebugDir.exists()) {
            publicDebugDir.mkdirs()
            Log.i(tag, "创建调试目录: ${publicDebugDir.absolutePath}")
        }
        return publicDebugDir
    }

    fun readTestData(onTestAborted: (String) -> Unit): List<Pair<String, String>> {
        val testData = mutableListOf<Pair<String, String>>()

        if (!internalDataDir.exists() || !testDataFile.exists()) {
            val errorMessage = "错误：未找到测试数据。请先在主界面点击‘导入’。路径：${testDataFile.absolutePath}"
            Log.e(tag, errorMessage)
            onTestAborted(errorMessage)
            return testData
        }

        Log.i(tag, "[数据读取] 开始解析: ${testDataFile.absolutePath} (${testDataFile.length()} 字节)")
        
        try {
            var totalLines = 0
            // 显式指定 UTF-8 编码，防止乱码导致分隔符失效
            testDataFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    totalLines++
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) return@forEach

                    val parts = trimmedLine.split('|')
                    if (parts.size >= 2) {
                        val pinyin = parts[0].trim()
                        val target = parts[1].trim()
                        if (pinyin.isNotEmpty() && target.isNotEmpty()) {
                            testData.add(Pair(pinyin, target))
                        } else {
                            Log.w(tag, "[数据读取] 第 $totalLines 行内容不完整: \"$trimmedLine\"")
                        }
                    } else {
                        Log.w(tag, "[数据读取] 第 $totalLines 行格式错误 (未发现 '|'): \"$trimmedLine\"")
                    }
                }
            }
            Log.i(tag, "[数据读取] 完成。共解析出 ${testData.size} 条数据 (总行数: $totalLines)")
        } catch (e: Exception) {
            val err = "错误：文件读取失败: ${e.message}"
            Log.e(tag, err, e)
            onTestAborted(err)
        }

        if (testData.isEmpty()) {
            val err = "错误：测试数据为空。请检查文件内容是否为 'pinyin|target' 格式。"
            Log.e(tag, err)
            onTestAborted(err)
        }

        return testData
    }

    fun saveReport(report: String): File? {
        return try {
            if (!publicReportDir.exists()) publicReportDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val reportFile = File(publicReportDir, "keyboard_evaluation_report_$timestamp.txt")
            reportFile.writeText(report)
            reportFile
        } catch (e: Exception) {
            Log.e(tag, "报告保存失败", e)
            null
        }
    }
}
