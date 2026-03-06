package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KeyboardEvaluationTest {

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java, true, false)

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var uiDevice: UiDevice
    private lateinit var instrumentation: Instrumentation
    private val results = mutableListOf<EvaluationResult>()
    private val tag = "KeyboardEvaluator"
    private val editTextResId = "com.example.androidinstrumentedtest:id/evaluation_edit_text"
    private lateinit var testDataManager: TestDataManager
    
    private var isNineKeyTest: Boolean = true
    private val manualPositions = mutableMapOf<String, Rect>()

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        @Suppress("DEPRECATION")
        uiDevice.setCompressedLayoutHeirarchy(false)
        testDataManager = TestDataManager(instrumentation.targetContext)
        
        val prefs = instrumentation.targetContext.getSharedPreferences("KeyboardEvaluatorPrefs", Context.MODE_PRIVATE)
        val kbType = prefs.getString("last_keyboard_type", "9键测试")
        isNineKeyTest = kbType == "9键测试"
        Log.i(tag, "检测到测试模式: $kbType (isNineKey=$isNineKeyTest)")

        loadManualCalibrationData()
        
        activityRule.launchActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_IS_TEST_MODE, true)
            }
        )
    }

    private fun loadManualCalibrationData() {
        try {
            val mainAppContext = instrumentation.targetContext
            val dataDir = File(mainAppContext.filesDir, "InstrumentedTest")
            val prefs = instrumentation.targetContext.getSharedPreferences("KeyboardEvaluatorPrefs", Context.MODE_PRIVATE)
            val calFileName = prefs.getString("last_calibration_file", "")
            
            if (!calFileName.isNullOrEmpty()) {
                val calibrationFile = File(dataDir, calFileName)
                if (calibrationFile.exists()) {
                    val json = JSONObject(calibrationFile.readText())
                    val it = json.keys()
                    while (it.hasNext()) {
                        val keyStr = it.next()
                        val point = json.getJSONObject(keyStr)
                        val x = point.getDouble("x").toInt()
                        val y = point.getDouble("y").toInt()
                        manualPositions[keyStr] = Rect(x - 5, y - 5, x + 5, y + 5)
                    }
                    Log.i(tag, "已加载校准文件: $calFileName")
                }
            }
        } catch (e: Exception) { Log.e(tag, "加载校准数据失败", e) }
    }

    private fun findSafeEditText(timeout: Long = 2000): UiObject2? {
        return uiDevice.wait(Until.findObject(By.res(editTextResId)), timeout)
    }

    private fun clearTextViaDelete(et: UiObject2?) {
        if (et == null) return
        et.click(); Thread.sleep(300)
        // 注意：由于删除键位置可能不固定，清空仍然保留物理按键发送方式，仅评测输入改为点击位置
        val text = et.text ?: ""
        val committedLen = if (text.isNotEmpty() && text.uppercase() != "PINYIN WILL BE ENTERED HERE") text.length else 0
        repeat(committedLen + 15) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL); Thread.sleep(20) }
        Thread.sleep(200)
    }

    private fun updateStatus(msg: String, color: Int = Color.BLACK) {
        Log.i(tag, msg)
        activityRule.activity.runOnUiThread { activityRule.activity.setReportText(msg, color) }
    }

    @Test
    fun runKeyboardEvaluation() {
        val testData = testDataManager.readTestData { errorMessage -> updateStatus(errorMessage, Color.RED) }
        if (testData.isEmpty()) return

        val initialET = findSafeEditText(5000) ?: return
        initialET.click(); Thread.sleep(1500)

        updateStatus("评测开始 (${if (isNineKeyTest) "9键OCR" else "26键OCR"})...", Color.BLACK)

        testData.forEachIndexed { index, dataPair ->
            val pinyin = dataPair.first
            val target = dataPair.second
            val result = EvaluationResult(pinyin, target)
            
            updateTestingStatus(pinyin, target)
            Log.i(tag, ">>> [${index + 1}/${testData.size}] 评测中: $pinyin -> $target")

            runGenericOcrEvaluation(result, target, pinyin)

            results.add(result)
            sendPartialReport(result) 
            Thread.sleep(1200)
        }
    }

    private fun runGenericOcrEvaluation(result: EvaluationResult, target: String, pinyin: String) {
        try {
            val et = findSafeEditText(2000) ?: return
            clearTextViaDelete(et)
            
            // 1. 输入拼音（全部使用模拟位置点击）
            pinyin.forEach { char -> pressKeyInternal(char); Thread.sleep(120) }
            Thread.sleep(800)
            
            // 2. 模拟点击空格键获取锚点
            pressKeyInternal(' ')
            Thread.sleep(800)
            val anchorWord = findSafeEditText(1000)?.text?.trim()?.toString() ?: ""
            if (anchorWord.isEmpty()) { result.message = "ERROR: Anchor Empty"; return }

            // 3. 重新输入并展开面板进行 OCR
            performOcrAndMatch(result, target, pinyin, anchorWord)
        } catch (e: Exception) { Log.e(tag, "评测流程异常", e) }
    }

    private fun performOcrAndMatch(result: EvaluationResult, target: String, pinyin: String, anchorWord: String) {
        clearTextViaDelete(findSafeEditText(1000))
        pinyin.forEach { char -> pressKeyInternal(char); Thread.sleep(120) }
        Thread.sleep(800)
        
        val dropdown = manualPositions["dropdown_btn"] ?: return
        uiDevice.click(dropdown.centerX(), dropdown.centerY())
        Thread.sleep(1800)

        val cacheFile = File(instrumentation.targetContext.cacheDir, "panel_ocr.png")
        if (cacheFile.exists()) cacheFile.delete()
        uiDevice.takeScreenshot(cacheFile)
        val screenshot = BitmapFactory.decodeFile(cacheFile.absolutePath)
        
        var allTokens = listOf<String>()
        val latch = CountDownLatch(1)
        activityRule.activity.runOnUiThread {
            activityRule.activity.getAllOcrTokens(screenshot) { tokens ->
                allTokens = tokens; latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)

        val anchorIdx = allTokens.indexOfFirst { it == anchorWord }
        if (anchorIdx != -1) {
            val candidates = mutableListOf<String>()
            candidates.add(allTokens[anchorIdx])
            for (k in 1 until 5) { if (anchorIdx + k < allTokens.size) candidates.add(allTokens[anchorIdx + k]) }
            
            var matchedIdx = -1
            candidates.forEachIndexed { i, word ->
                result.attempts.add(word)
                if (matchedIdx == -1 && word == target.trim()) matchedIdx = i
            }

            if (matchedIdx != -1) {
                result.selectedWord = candidates[matchedIdx]; result.wasFound = true
                result.selectedNo = matchedIdx + 1; result.message = "OCR Success"
            } else { result.message = "Not in Top 5" }
        } else { result.message = "NOT_FOUND: Anchor mismatch" }
        
        uiDevice.pressBack(); Thread.sleep(500)
        clearTextViaDelete(findSafeEditText(500))
    }

    private fun formatResult(res: EvaluationResult): String {
        val status = if (res.wasFound) "SUCCESS" else "NOT_FOUND"
        val candidates = if (res.attempts.isEmpty()) "无" else res.attempts.take(5).joinToString(" | ")
        return String.format("%s | %s -> %s | Pos: %d\n候选词: [%s]",
            status, res.pinyinSequence, res.targetWord, res.selectedNo, candidates)
    }

    private fun updateTestingStatus(pinyin: String, target: String) {
        val prev = results.lastOrNull()
        val prevText = prev?.let { "【前次结果】\n${formatResult(it)}" } ?: "【前次结果】\n等待中..."
        val currText = "【正在测试】\n$pinyin -> $target ..."
        activityRule.activity.runOnUiThread { activityRule.activity.setReportText("$prevText\n\n$currText", Color.DKGRAY) }
    }

    private fun sendPartialReport(result: EvaluationResult) {
        val prev = results.getOrNull(results.size - 2)
        val prevText = prev?.let { "【前次结果】\n${formatResult(it)}" } ?: ""
        val currText = "【当前结果】\n${formatResult(result)}"
        Log.i(tag, "[ITEM RESULT] ${result.pinyinSequence} -> ${result.targetWord} | ${if(result.wasFound) "SUCCESS" else "NOT_FOUND"}")
        activityRule.activity.runOnUiThread {
            val color = if (result.wasFound) Color.parseColor("#006400") else Color.RED
            activityRule.activity.setReportText(if (prevText.isNotEmpty()) "$prevText\n\n$currText" else currText, color)
        }
    }

    @After
    fun generateReport() {
        if (results.isEmpty()) return
        val finalReport = buildFinalReport()
        val reportFile = testDataManager.saveReport(finalReport)
        
        if (reportFile != null && reportFile.exists()) {
            try {
                uiDevice.executeShellCommand("am start -a android.intent.action.VIEW -d \"file://${reportFile.absolutePath}\" -t \"text/plain\"")
                Log.i(tag, "测试完成，已发出打开报告指令: ${reportFile.absolutePath}")
            } catch (e: Exception) { Log.e(tag, "无法自动打开报告文件", e) }
        }
        Thread.sleep(8000)
    }

    private fun buildFinalReport(): String {
        val totalCount = results.size
        val top1Count = results.count { it.wasFound && it.selectedNo == 1 }
        val top2_5Count = results.count { it.wasFound && it.selectedNo in 2..5 }
        val notFoundCount = results.count { !it.wasFound }

        val overallRate = (results.count { it.wasFound }.toDouble() / totalCount) * 100
        val top1Rate = (top1Count.toDouble() / totalCount) * 100
        val top2_5Rate = (top2_5Count.toDouble() / totalCount) * 100
        val notFoundRate = (notFoundCount.toDouble() / totalCount) * 100

        val imeId = Settings.Secure.getString(instrumentation.targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeName = imeId?.split('/')?.get(0) ?: "Unknown"

        val sb = StringBuilder("=========== FINAL EVALUATION REPORT ============\n")
        sb.append("IME: $imeName | Mode: ${if (isNineKeyTest) "9-key" else "26-key"}\n")
        sb.append("--------------------------------------------------------------------------------\n")
        results.forEachIndexed { idx, res ->
            val attemptsStr = res.attempts.joinToString(",")
            sb.append(String.format("%-4d | %-9s | %-15s | %-10s | %-3d | %s\n",
                idx + 1, if (res.wasFound) "SUCCESS" else "NOT_FOUND", res.pinyinSequence, res.targetWord, res.selectedNo, attemptsStr))
        }
        sb.append("--------------------------------------------------------------------------------\n")
        sb.append(String.format("  Success Rate:   %.2f%% (%d/%d)\n", overallRate, results.count { it.wasFound }, totalCount))
        sb.append(String.format("  Top 1 Rate:     %.2f%%\n", top1Rate))
        sb.append(String.format("  Top 2-5 Rate:   %.2f%%\n", top2_5Rate))
        sb.append(String.format("  Not Found Rate: %.2f%% (%d/%d)\n", notFoundRate, notFoundCount, totalCount))
        sb.append("================ END OF REPORT ================\n")
        return sb.toString()
    }

    private fun pressKeyInternal(key: Char) {
        val keyChar = key.lowercaseChar()
        val targetKeyStr = if (isNineKeyTest) {
            when (keyChar) {
                'a', 'b', 'c' -> "2"; 'd', 'e', 'f' -> "3"; 'g', 'h', 'i' -> "4"
                'j', 'k', 'l' -> "5"; 'm', 'n', 'o' -> "6"; 'p', 'q', 'r', 's' -> "7"
                't', 'u', 'v' -> "8"; 'w', 'x', 'y', 'z' -> "9"
                ' ' -> "0"
                else -> keyChar.toString()
            }
        } else {
            if (keyChar == ' ') "space" else keyChar.toString()
        }
        
        manualPositions[targetKeyStr]?.let {
            uiDevice.click(it.centerX(), it.centerY())
            Thread.sleep(50)
        } ?: Log.e(tag, "未找到按键 '$targetKeyStr' 的校准坐标点！")
    }

    data class EvaluationResult(
        val pinyinSequence: String,
        val targetWord: String,
        var wasFound: Boolean = false,
        var selectedWord: String = "",
        var selectedNo: Int = 0,
        var attempts: MutableList<String> = mutableListOf(),
        var message: String = ""
    )
}
