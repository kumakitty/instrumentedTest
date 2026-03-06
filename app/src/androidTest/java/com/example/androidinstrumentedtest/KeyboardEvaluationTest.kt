package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
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
    private val maxCandidatesToCheck = 5
    private lateinit var testDataManager: TestDataManager
    
    private var isNineKeyTest: Boolean = false
    private val manualPositions = mutableMapOf<String, Rect>()
    private var lastManualPositionsJson: String = ""

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        @Suppress("DEPRECATION")
        uiDevice.setCompressedLayoutHeirarchy(false)
        testDataManager = TestDataManager(instrumentation.targetContext)
        
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
            val calibrationFile = File(dataDir, "calibration.json")
            if (calibrationFile.exists()) {
                lastManualPositionsJson = calibrationFile.readText()
                val json = JSONObject(lastManualPositionsJson)
                val keys = listOf("2", "3", "4", "5", "6", "7", "8", "9", "0", "dropdown_btn", "candidate_area")
                keys.forEach { keyStr ->
                    if (json.has(keyStr)) {
                        val point = json.getJSONObject(keyStr)
                        val x = point.getDouble("x").toInt()
                        val y = point.getDouble("y").toInt()
                        manualPositions[keyStr] = Rect(x - 5, y - 5, x + 5, y + 5)
                    }
                }
            }
        } catch (e: Exception) { Log.e(tag, "加载校准数据失败", e) }
    }

    private fun findSafeEditText(timeout: Long = 2000): UiObject2? {
        return uiDevice.wait(Until.findObject(By.res(editTextResId)), timeout)
    }

    private fun clearTextViaDelete(et: UiObject2?) {
        if (et == null) return
        et.click()
        Thread.sleep(300)
        val text = et.text ?: ""
        val committedLen = if (text.isNotEmpty() && text.uppercase() != "PINYIN WILL BE ENTERED HERE") text.length else 0
        repeat(committedLen) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL); Thread.sleep(20) }
        repeat(15) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL); Thread.sleep(20) }
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

        isNineKeyTest = testData.any { it.first.any { c -> c.isDigit() } }
        val initialET = findSafeEditText(5000) ?: return
        initialET.click(); Thread.sleep(1500)

        updateStatus("评测开始 (${if (isNineKeyTest) "9键模式" else "26键模式"})...", Color.BLACK)

        testData.forEachIndexed { index, dataPair ->
            val pinyin = dataPair.first
            val target = dataPair.second
            val result = EvaluationResult(pinyin, target)
            
            updateTestingStatus(pinyin, target)
            Log.i(tag, ">>> [${index + 1}/${testData.size}] 评测中: $pinyin -> $target")

            if (isNineKeyTest) {
                run9KeyEvaluation(result, target, pinyin)
            } else {
                run26KeyEvaluation(result, target, pinyin)
            }

            results.add(result)
            sendPartialReport(result) 
            Thread.sleep(1200)
        }
    }

    private fun run9KeyEvaluation(result: EvaluationResult, target: String, pinyin: String) {
        try {
            val et = findSafeEditText(2000) ?: return
            clearTextViaDelete(et)
            pinyin.forEach { char -> pressKeyInternal(char); Thread.sleep(120) }
            Thread.sleep(1000)
            manualPositions["0"]?.let { uiDevice.click(it.centerX(), it.centerY()) }
            Thread.sleep(800)
            val anchorWord = findSafeEditText(1000)?.text?.trim()?.toString() ?: ""
            if (anchorWord.isEmpty()) { result.message = "ERROR: Anchor Empty"; return }

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
        } catch (e: Exception) { Log.e(tag, "9键流程异常", e) }
    }

    private fun run26KeyEvaluation(result: EvaluationResult, target: String, pinyin: String) {
        for (i in 1..maxCandidatesToCheck) {
            val et = findSafeEditText(2000) ?: return
            clearTextViaDelete(et)
            pinyin.forEach { char -> pressKeyInternal(char); Thread.sleep(100) }
            Thread.sleep(1000)
            if (i > 1) { repeat(i - 1) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(200) } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE); Thread.sleep(1000)
            val resText = findSafeEditText(1000)?.text?.trim()?.toString() ?: ""
            val clean = if (resText.uppercase().contains("PINYIN")) "" else resText
            result.attempts.add(clean)
            if (clean == target.trim()) {
                result.selectedWord = clean; result.wasFound = true; result.selectedNo = i; result.message = "Success"
                return
            }
        }
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
        val fullText = "$prevText\n\n$currText"
        activityRule.activity.runOnUiThread { activityRule.activity.setReportText(fullText, Color.DKGRAY) }
    }

    private fun sendPartialReport(result: EvaluationResult) {
        val prev = results.getOrNull(results.size - 2)
        val prevText = prev?.let { "【前次结果】\n${formatResult(it)}" } ?: ""
        val currText = "【当前结果】\n${formatResult(result)}"
        val fullReport = if (prevText.isNotEmpty()) "$prevText\n\n$currText" else currText
        Log.i(tag, "[ITEM RESULT] ${result.pinyinSequence} -> ${result.targetWord} | ${if(result.wasFound) "SUCCESS" else "NOT_FOUND"}")
        activityRule.activity.runOnUiThread {
            val color = if (result.wasFound) Color.parseColor("#006400") else Color.RED
            activityRule.activity.setReportText(fullReport, color)
        }
    }

    @After
    fun generateReport() {
        if (results.isEmpty()) return
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
        sb.append(String.format("%-4s | %-9s | %-15s | %-10s | %-3s | %s\n", "No.", "Status", "Pinyin", "Target", "Pos", "Attempts"))
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
        
        val finalReport = sb.toString()
        val reportFile = testDataManager.saveReport(finalReport)
        
        if (reportFile != null && reportFile.exists()) {
            try {
                // 核心策略：通过 shell 指令强制调起文件查看器，并休眠确保 Intent 发出
                val viewCmd = "am start -a android.intent.action.VIEW -d \"file://${reportFile.absolutePath}\" -t \"text/plain\""
                uiDevice.executeShellCommand(viewCmd)
                Log.i(tag, "测试完成，已尝试自动打开报告: ${reportFile.absolutePath}")
            } catch (e: Exception) { Log.e(tag, "无法自动打开报告文件", e) }
        }
        // 重要：休眠 5 秒，防止测试进程立即退出导致应用无法切换
        Thread.sleep(5000)
    }

    private fun pressKeyInternal(key: Char) {
        val keyChar = key.lowercaseChar()
        if (isNineKeyTest) {
            val targetKeyStr = when (keyChar) {
                'a', 'b', 'c' -> "2"; 'd', 'e', 'f' -> "3"; 'g', 'h', 'i' -> "4"
                'j', 'k', 'l' -> "5"; 'm', 'n', 'o' -> "6"; 'p', 'q', 'r', 's' -> "7"
                't', 'u', 'v' -> "8"; 'w', 'x', 'y', 'z' -> "9"; else -> keyChar.toString()
            }
            manualPositions[targetKeyStr]?.let { uiDevice.click(it.centerX(), it.centerY()); Thread.sleep(50); return }
        }
        val keyCode = when (keyChar) {
            'a'->KeyEvent.KEYCODE_A; 'b'->KeyEvent.KEYCODE_B; 'c'->KeyEvent.KEYCODE_C; 'd'->KeyEvent.KEYCODE_D
            'e'->KeyEvent.KEYCODE_E; 'f'->KeyEvent.KEYCODE_F; 'g'->KeyEvent.KEYCODE_G; 'h'->KeyEvent.KEYCODE_H
            'i'->KeyEvent.KEYCODE_I; 'j'->KeyEvent.KEYCODE_J; 'k'->KeyEvent.KEYCODE_K; 'l'->KeyEvent.KEYCODE_L
            'm'->KeyEvent.KEYCODE_M; 'n'->KeyEvent.KEYCODE_N; 'o'->KeyEvent.KEYCODE_O; 'p'->KeyEvent.KEYCODE_P
            'q'->KeyEvent.KEYCODE_Q; 'r'->KeyEvent.KEYCODE_R; 's'->KeyEvent.KEYCODE_S; 't'->KeyEvent.KEYCODE_T
            'u'->KeyEvent.KEYCODE_U; 'v'->KeyEvent.KEYCODE_V; 'w'->KeyEvent.KEYCODE_W; 'x'->KeyEvent.KEYCODE_X
            'y'->KeyEvent.KEYCODE_Y; 'z'->KeyEvent.KEYCODE_Z; ' '->KeyEvent.KEYCODE_SPACE; '0','1','2','3','4','5','6','7','8','9'->(keyChar.code - '0'.code + KeyEvent.KEYCODE_0)
            else -> -1
        }
        if (keyCode != -1) instrumentation.sendKeyDownUpSync(keyCode)
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
