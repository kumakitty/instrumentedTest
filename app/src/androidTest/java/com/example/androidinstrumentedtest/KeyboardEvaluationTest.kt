package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

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

    // 用于记录校准后的按键位置坐标
    private val calibratedPositions = mutableMapOf<Char, Rect>()

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        @Suppress("DEPRECATION")
        uiDevice.setCompressedLayoutHeirarchy(false)
        testDataManager = TestDataManager(instrumentation.targetContext)
    }

    private fun findSafeEditText(timeout: Long = 2000): UiObject2? {
        return uiDevice.wait(Until.findObject(By.res(editTextResId)), timeout)
    }

    /**
     * 模拟物理删除键清空文本，确保触发输入法重置逻辑
     */
    private fun clearTextViaDelete(et: UiObject2?) {
        if (et == null) return
        et.click()
        val text = et.text ?: ""
        if (text.isNotEmpty() && text != "PINYIN WILL BE ENTERED HERE") {
            repeat(text.length) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
            }
        }
    }

    private fun updateStatus(msg: String, color: Int = Color.BLACK) {
        Log.i(tag, msg)
        activityRule.scenario.onActivity { activity: MainActivity ->
            activity.setReportText(msg, color)
        }
    }

    /**
     * 9键智能精准路径校准逻辑
     */
    private fun calibrateNineKeyLayout(): Boolean {
        updateStatus("开始9键智能路径探测校准...")
        val et = findSafeEditText()
        val myPkg = instrumentation.targetContext.packageName
        val notMyPkgPattern = Pattern.compile("^(?!$myPkg$).*")
        val promptText = "PINYIN WILL BE ENTERED HERE"

        val expectedChars = mapOf(
            '2' to "A", '3' to "D", '4' to "G", '5' to "J",
            '6' to "M", '7' to "P", '8' to "T", '9' to "W"
        )

        var anchorDigit: Char? = null
        var anchorX = 0; var anchorY = 0
        var keyWidth = 0; var keyHeight = 0

        // 1. 扫描寻找第一个标杆并锁定坐标
        val buttons = uiDevice.findObjects(By.pkg(notMyPkgPattern).clickable(true))
            .filter { it.visibleBounds.top > uiDevice.displayHeight / 2 && it.visibleBounds.height() > 50 }
            .sortedWith(compareBy<UiObject2>({ it.visibleBounds.top }, { it.visibleBounds.left }))
        
        val buttonBounds = buttons.map { it.visibleBounds }

        for (rect in buttonBounds) {
            val cx = rect.centerX(); val cy = rect.centerY()
            clearTextViaDelete(findSafeEditText())
            uiDevice.swipe(cx, cy, cx, cy, 40) 
            
            val start = System.currentTimeMillis()
            var out = ""
            while (System.currentTimeMillis() - start < 2000) {
                out = findSafeEditText(500)?.text?.trim()?.toString()?.uppercase() ?: ""
                if (out.isNotEmpty() && out != promptText) break
                Thread.sleep(200)
            }

            if (out.length == 1) {
                anchorDigit = expectedChars.entries.find { it.value == out }?.key
                if (anchorDigit == '8') { 
                    anchorX = cx; anchorY = cy
                    keyWidth = if (rect.width() > uiDevice.displayWidth / 2) uiDevice.displayWidth / 3 else rect.width()
                    keyHeight = rect.height()
                    calibratedPositions[anchorDigit] = rect
                    updateStatus("锁定标杆 '$anchorDigit' (坐标: $anchorX, $anchorY)，开始探测...", Color.BLUE)
                    break
                }
            }
        }

        if (calibratedPositions['8'] == null) return false

        // 2. 以像素为单位执行路径探测 (30像素步进，加快速度)
        fun probePath(targetDigit: Char, startX: Int, startY: Int, dxSign: Int, dySign: Int): Rect? {
            val expected = expectedChars[targetDigit]
            val maxDistance = uiDevice.displayWidth / 2
            
            for (dist in 30..maxDistance step 30) {
                val tx = startX + dist * dxSign
                val ty = startY + dist * dySign
                if (tx < 0 || tx > uiDevice.displayWidth || ty < 0 || ty > uiDevice.displayHeight) break
                
                updateStatus("探测点 ($tx, $ty)...")
                clearTextViaDelete(et)
                uiDevice.swipe(tx, ty, tx, ty, 40)
                
                val pStart = System.currentTimeMillis()
                var pOut = ""
                while (System.currentTimeMillis() - pStart < 1000) {
                    pOut = findSafeEditText(300)?.text?.trim()?.toString()?.uppercase() ?: ""
                    if (pOut.isNotEmpty() && pOut != promptText) break
                }
                
                if (pOut.length == 1 && (expected == null || pOut == expected)) {
                    updateStatus("找到 $targetDigit (坐标: $tx, $ty)", Color.GREEN)
                    return Rect(tx - 5, ty - 5, tx + 5, ty + 5)
                }
            }
            return null
        }

        val ax = calibratedPositions['8']!!.centerX()
        val ay = calibratedPositions['8']!!.centerY()
        calibratedPositions['7'] = probePath('7', ax, ay, -1, 0) ?: return false
        calibratedPositions['9'] = probePath('9', ax, ay, 1, 0) ?: return false
        val r5 = probePath('5', ax, ay, 0, -1) ?: return false
        calibratedPositions['5'] = r5
        val c5x = r5.centerX(); val c5y = r5.centerY()
        calibratedPositions['4'] = probePath('4', c5x, c5y, -1, 0) ?: return false
        calibratedPositions['6'] = probePath('6', c5x, c5y, 1, 0) ?: return false
        val r2 = probePath('2', c5x, c5y, 0, -1) ?: return false
        calibratedPositions['2'] = r2
        val c2x = r2.centerX(); val c2y = r2.centerY()
        calibratedPositions['1'] = probePath('1', c2x, c2y, -1, 0) ?: return false
        calibratedPositions['3'] = probePath('3', c2x, c2y, 1, 0) ?: return false
        
        // 3. 探测空格键：判定标准改为点击后文本框内确实增加了一个空格
        updateStatus("正在步进探测空格键位置...")
        for (dist in 30..400 step 30) {
            val tx = ax; val ty = ay + dist
            if (ty > uiDevice.displayHeight) break
            
            val safeEt = findSafeEditText(1000)
            clearTextViaDelete(safeEt)
            Thread.sleep(300)
            
            uiDevice.click(tx, ty)
            Thread.sleep(600)
            val after = findSafeEditText(1000)?.text?.toString() ?: ""
            if (after == " ") { 
                calibratedPositions[' '] = Rect(tx - 5, ty - 5, tx + 5, ty + 5)
                updateStatus("锁定空格键 (坐标: $tx, $ty)", Color.GREEN)
                break
            }
        }

        updateStatus("9键精准校准成功！", Color.GREEN)
        clearTextViaDelete(et)
        return true
    }

    @Test
    fun runKeyboardEvaluation() {
        val testData = testDataManager.readTestData { errorMessage ->
            updateStatus(errorMessage, Color.RED)
        }

        if (testData.isEmpty()) return

        val initialET = findSafeEditText(5000) ?: return
        initialET.click(); Thread.sleep(1500)

        val isNineKeyTest = testData.firstOrNull()?.first?.all { it.isDigit() } == true
        if (isNineKeyTest) {
            if (!calibrateNineKeyLayout()) return
        }

        findSafeEditText(2000)?.click()
        updateStatus("正式评测开始...", Color.BLACK)
        Thread.sleep(1000)

        testData.forEach { dataPair ->
            val pinyin = dataPair.first; val target = dataPair.second
            val result = EvaluationResult(pinyin, target)
            Log.i(tag, "--- 正在评测: $pinyin -> $target ---")

            var foundMatch = false
            for (i in 1..maxCandidatesToCheck) {
                val currentEditText = findSafeEditText(2000) ?: return
                clearTextViaDelete(currentEditText)
                Thread.sleep(300)

                // 正式输入前确保焦点
                currentEditText.click(); Thread.sleep(200)

                pinyin.forEach { char: Char ->
                    pressKeyInternal(char)
                    Thread.sleep(100)
                }

                if (testData.firstOrNull()?.first?.all { it.isDigit() } != true) {
                    Thread.sleep(1500)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                    Thread.sleep(300); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(300)
                } else {
                    Thread.sleep(1000)
                }

                if (i > 1) {
                    repeat(i - 1) { 
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                        Thread.sleep(200) 
                    }
                }
                
                // 9键直接点击锁定的空格坐标
                if (isNineKeyTest && calibratedPositions.containsKey(' ')) {
                    val p = calibratedPositions[' ']!!
                    uiDevice.click(p.centerX(), p.centerY())
                } else {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
                }
                Thread.sleep(1000)

                val resultText = findSafeEditText(1000)?.text?.trim()?.toString() ?: ""
                result.attempts.add(resultText)

                if (resultText == target.trim()) {
                    result.selectedWord = resultText; result.wasFound = true; result.selectedNo = i; result.message = "Success: Matched."
                    foundMatch = true; break
                } else {
                    clearTextViaDelete(findSafeEditText(500))
                }
            }

            if (!foundMatch) {
                result.wasFound = false; result.selectedNo = 0; result.message = "Failure: Not found."
            }
            results.add(result); sendPartialReport(result)
        }
    }

    private fun sendPartialReport(result: EvaluationResult) {
        val status = if (result.wasFound) "SUCCESS" else "FAILURE"
        val report = String.format("%-7s | %-15s | %-10s | Pos: %-2d | %s",
            status, result.pinyinSequence, result.targetWord, result.selectedNo, result.message)
        activityRule.scenario.onActivity { activity: MainActivity ->
            activity.setReportText(report, if (result.wasFound) Color.GREEN else Color.RED)
        }
    }

    @After
    fun generateReport() {
        if (results.isEmpty()) return
        val context = instrumentation.targetContext
        val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeName = imeId?.split('/')?.get(0) ?: "Unknown"
        val testMode = if (results.firstOrNull()?.pinyinSequence?.all { it.isDigit() } == true) "9-key" else "26-key"

        val sb = StringBuilder("=========== KEYBOARD EVALUATION REPORT ============\n")
        sb.append("Device: ${Build.MODEL}\nInput Method: $imeName\nTest Mode: $testMode\n")
        sb.append("--------------------------------------------------\n")
        sb.append(String.format("%-4s | %-7s | %-15s | %-10s | %-20s | %-3s | %s\n", "No.", "Status", "Pinyin", "Target", "Selected Attempts", "Pos", "Message"))
        
        results.forEachIndexed { idx, res ->
            val attemptsStr = res.attempts.joinToString(",")
            sb.append(String.format("%-4d | %-7s | %-15s | %-10s | %-20s | %-3d | %s\n",
                idx + 1, if (res.wasFound) "SUCCESS" else "FAILURE", res.pinyinSequence, res.targetWord, attemptsStr, res.selectedNo, res.message))
        }

        val totalCount = results.size
        val successCount = results.count { it.wasFound }
        val overallRate = if (totalCount > 0) (successCount.toDouble() / totalCount) * 100 else 0.0

        sb.append("--------------------------------------------------\nSummary:\n")
        sb.append(String.format("  Overall Success Rate: %.2f%% (%d/%d)\n", overallRate, successCount, totalCount))
        sb.append("================ END OF REPORT ================\n")

        val reportFile = testDataManager.saveReport(sb.toString())
        if (reportFile != null) {
            updateStatus("评测完成！报告导出：\n${reportFile.absolutePath}", Color.BLUE)
        }
        try { uiDevice.pressBack() } catch (e: Exception) {}
    }

    private fun pressKeyInternal(key: Char) {
        val keyChar = key.lowercaseChar()
        if (keyChar.isDigit()) {
            calibratedPositions[keyChar]?.let { uiDevice.click(it.centerX(), it.centerY()); Thread.sleep(100); return }
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
