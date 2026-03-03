package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// 评测结果数据类
data class EvaluationResult(
    val pinyinSequence: String,
    val targetWord: String,
    var selectedWord: String = "",
    var wasFound: Boolean = false,
    var selectedNo: Int = 0,
    var message: String = "",
    val attempts: MutableList<String> = mutableListOf()
)

@RunWith(AndroidJUnit4::class)
class KeyboardEvaluationTest {

    @get:Rule
    val activityRule: ActivityScenarioRule<MainActivity> = ActivityScenarioRule(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_IS_TEST_MODE, true)
        }
    )

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

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        @Suppress("DEPRECATION")
        uiDevice.setCompressedLayoutHeirarchy(false)
        testDataManager = TestDataManager(instrumentation.targetContext)
        loadManualCalibrationData()
    }

    private fun loadManualCalibrationData() {
        try {
            val mainAppContext = instrumentation.targetContext
            val dataDir = File(mainAppContext.filesDir, "InstrumentedTest")
            val calibrationFile = File(dataDir, "calibration.json")
            if (calibrationFile.exists()) {
                val json = JSONObject(calibrationFile.readText())
                val keys = listOf("2", "3", "4", "5", "6", "7", "8", "9", "0", "candidate")
                keys.forEach { keyStr ->
                    if (json.has(keyStr)) {
                        val point = json.getJSONObject(keyStr)
                        val x = point.getDouble("x").toInt()
                        val y = point.getDouble("y").toInt()
                        manualPositions[keyStr] = Rect(x - 5, y - 5, x + 5, y + 5)
                    }
                }
            }
        } catch (e: Exception) { Log.e(tag, "读取校准数据失败", e) }
    }

    private fun findSafeEditText(timeout: Long = 2000): UiObject2? {
        return uiDevice.wait(Until.findObject(By.res(editTextResId)), timeout)
    }

    private fun clearTextViaDelete(et: UiObject2?) {
        if (et == null) return
        et.click()
        val text = et.text ?: ""
        if (text.isNotEmpty() && text.uppercase() != "PINYIN WILL BE ENTERED HERE") {
            repeat(text.length) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL) }
        }
    }

    private fun updateStatus(msg: String, color: Int = Color.BLACK) {
        Log.i(tag, msg)
        activityRule.scenario.onActivity { activity: MainActivity -> activity.setReportText(msg, color) }
    }

    @Test
    fun runKeyboardEvaluation() {
        val testData = testDataManager.readTestData { errorMessage -> updateStatus(errorMessage, Color.RED) }
        if (testData.isEmpty()) return

        isNineKeyTest = testData.firstOrNull()?.first?.all { it.isDigit() } == true
        val initialET = findSafeEditText(5000) ?: return
        initialET.click(); Thread.sleep(1500)

        updateStatus("正式评测开始 (${if (isNineKeyTest) "9键" else "26键"})...", Color.BLACK)
        Thread.sleep(1000)

        testData.forEach { dataPair ->
            val pinyin = dataPair.first
            val target = dataPair.second
            val result = EvaluationResult(pinyin, target)
            Log.i(tag, "--- 正在评测: $pinyin -> $target ---")

            var foundMatch = false
            for (i in 1..maxCandidatesToCheck) {
                val et = findSafeEditText(2000) ?: return
                clearTextViaDelete(et)
                Thread.sleep(300); et.click(); Thread.sleep(200)

                pinyin.forEach { char: Char -> pressKeyInternal(char); Thread.sleep(100) }
                Thread.sleep(1000)

                if (isNineKeyTest) {
                    val candidatePos = manualPositions["candidate"]
                    if (candidatePos != null) {
                        if (i > 1) {
                            for (j in 0 until i - 1) {
                                var prevCandidate = result.attempts[j]
                                // 核心过滤：排除默认占位符文本
                                if (prevCandidate.uppercase() == "PINYIN WILL BE ENTERED HERE") prevCandidate = ""
                                
                                // 动态位移：长度*30，若为空则保底位移60像素（防长按），上限100像素（防退出）
                                val moveDistance = if (prevCandidate.isEmpty()) 60 else Math.min(prevCandidate.length * 30, 100)
                                val centerX = candidatePos.centerX()
                                val startX = centerX + (moveDistance / 2)
                                val endX = centerX - (moveDistance / 2)
                                
                                Log.d(tag, "9键模式动态滑动: 坐标 $startX -> $endX, 位移=$moveDistance (词='$prevCandidate')")
                                // 使用 10 步快速滑动，确保识别为“滚动”而非“长按”
                                uiDevice.swipe(startX, candidatePos.centerY(), endX, candidatePos.centerY(), 10)
                                Thread.sleep(600)
                            }
                        }
                        uiDevice.click(candidatePos.centerX(), candidatePos.centerY())
                    } else { manualPositions["0"]?.let { uiDevice.click(it.centerX(), it.centerY()) } }
                } else {
                    if (i > 1) {
                        repeat(i - 1) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(200) }
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
                }
                
                Thread.sleep(1000)
                val resultText = findSafeEditText(1000)?.text?.trim()?.toString() ?: ""
                val cleanResultText = if (resultText.uppercase() == "PINYIN WILL BE ENTERED HERE") "" else resultText
                result.attempts.add(cleanResultText)

                if (cleanResultText == target.trim()) {
                    result.selectedWord = cleanResultText; result.wasFound = true; result.selectedNo = i; result.message = "Success."
                    foundMatch = true; break
                } else { clearTextViaDelete(findSafeEditText(500)) }
            }
            if (!foundMatch) result.wasFound = false
            results.add(result); sendPartialReport(result)
        }
    }

    private fun sendPartialReport(result: EvaluationResult) {
        val status = if (result.wasFound) "SUCCESS" else "FAILURE"
        val report = String.format("%-7s | %-15s | %-10s | Pos: %-2d", status, result.pinyinSequence, result.targetWord, result.selectedNo)
        activityRule.scenario.onActivity { activity: MainActivity -> activity.setReportText(report, if (result.wasFound) Color.GREEN else Color.RED) }
    }

    @After
    fun generateReport() {
        if (results.isEmpty()) return
        val sb = StringBuilder("=========== KEYBOARD EVALUATION REPORT ============\n")
        val successCount = results.count { it.wasFound }
        val overallRate = (successCount.toDouble() / results.size) * 100
        sb.append(String.format("Overall Success Rate: %.2f%% (%d/%d)\n", overallRate, successCount, results.size))
        testDataManager.saveReport(sb.toString())
    }

    private fun pressKeyInternal(key: Char) {
        val keyChar = key.lowercaseChar()
        if (isNineKeyTest) {
            val targetKeyStr = when (keyChar) {
                'a', 'b', 'c' -> "2"; 'd', 'e', 'f' -> "3"; 'g', 'h', 'i' -> "4"
                'j', 'k', 'l' -> "5"; 'm', 'n', 'o' -> "6"; 'p', 'q', 'r', 's' -> "7"
                't', 'u', 'v' -> "8"; 'w', 'x', 'y', 'z' -> "9"; else -> keyChar.toString()
            }
            val manualPos = manualPositions[targetKeyStr]
            if (manualPos != null) {
                uiDevice.click(manualPos.centerX(), manualPos.centerY())
                Thread.sleep(100); return
            }
        }
        val keyCode = when (keyChar) {
            'a'->KeyEvent.KEYCODE_A; 'b'->KeyEvent.KEYCODE_B; 'c'->KeyEvent.KEYCODE_C; 'd'->KeyEvent.KEYCODE_D
            'e'->KeyEvent.KEYCODE_E; 'f'->KeyEvent.KEYCODE_F; 'g'->KeyEvent.KEYCODE_G; 'h'->KeyEvent.KEYCODE_H
            'i'->KeyEvent.KEYCODE_I; 'j'->KeyEvent.KEYCODE_J; 'k'->KeyEvent.KEYCODE_K; 'l'->KeyEvent.KEYCODE_L
            'm'->KeyEvent.KEYCODE_M; 'n'->KeyEvent.KEYCODE_N; 'o'->KeyEvent.KEYCODE_O; 'p'->KeyEvent.KEYCODE_P
            'q'->KeyEvent.KEYCODE_Q; 'r'->KeyEvent.KEYCODE_R; 's'->KeyEvent.KEYCODE_S; 't'->KeyEvent.KEYCODE_T
            'u'->KeyEvent.KEYCODE_U; 'v'->KeyEvent.KEYCODE_V; 'w'->KeyEvent.KEYCODE_W; 'x'->KeyEvent.KEYCODE_X
            'y'->KeyEvent.KEYCODE_Y; 'z'->KeyEvent.KEYCODE_Z; '0'->KeyEvent.KEYCODE_0; '1'->KeyEvent.KEYCODE_1
            '2'->KeyEvent.KEYCODE_2; '3'->KeyEvent.KEYCODE_3; '4'->KeyEvent.KEYCODE_4; '5'->KeyEvent.KEYCODE_5
            '6'->KeyEvent.KEYCODE_6; '7'->KeyEvent.KEYCODE_7; '8'->KeyEvent.KEYCODE_8; '9'->KeyEvent.KEYCODE_9
            else -> -1
        }
        if (keyCode != -1) instrumentation.sendKeyDownUpSync(keyCode)
    }
}
