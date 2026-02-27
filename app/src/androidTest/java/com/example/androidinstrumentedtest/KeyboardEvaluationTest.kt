package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 定义一个数据类来存储每行的评测结果
data class EvaluationResult(
    val pinyinSequence: String,
    val targetWord: String,
    var selectedWord: String = "",
    var wasFound: Boolean = false,
    var selectedNo: Int = 0, // Position of the matched candidate (1-5), or 0 if not found
    var message: String = ""
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
    private val MAX_CANDIDATES_TO_CHECK = 5
    private lateinit var testDataManager: TestDataManager

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        testDataManager = TestDataManager(instrumentation.targetContext)
    }

    private fun findEditText() = uiDevice.findObject(By.res(editTextResId))

    @Test
    fun runKeyboardEvaluation() {
        val testData = testDataManager.readTestData { errorMessage ->
            activityRule.scenario.onActivity {
                it.setReportText(errorMessage, Color.RED)
            }
        }

        if (testData.isEmpty()) {
            Log.e(tag, "Aborting test due to empty or unreadable test data.")
            return
        }

        activityRule.scenario.onActivity {
            it.setReportText("测试正在进行中...", Color.BLACK)
        }

        uiDevice.wait(Until.findObject(By.res(editTextResId)), 5000)
        
        findEditText()?.click()
        Thread.sleep(1000) // Wait for keyboard to appear

        testData.forEach { (pinyin, target) ->
            val result = EvaluationResult(pinyin, target)
            Log.d(tag, "--- Testing: pinyin='$pinyin', target='$target' ---")

            var foundMatch = false
            for (i in 1..MAX_CANDIDATES_TO_CHECK) {
                val currentEditText = findEditText()
                assertNotNull("Evaluation edit text not found", currentEditText)
                
                currentEditText.click()
                currentEditText.text = ""

                pinyin.forEach { char ->
                    pressKey(char)
                    Thread.sleep(50)
                }

                Thread.sleep(1500)
                // 模拟输入方向向下键，展开输入法候选词
                uiDevice.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
                Thread.sleep(300)
                // 再模拟输入方向向上键，回到第一行候选词
                uiDevice.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
                Thread.sleep(300)

                // 选择候选词：第1个候选词默认按空格键，后续通过向右键移动后再按空格
                if (i > 1) {
                    repeat(i - 1) {
                        uiDevice.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
                        Thread.sleep(200)
                    }
                }
                uiDevice.pressKeyCode(KeyEvent.KEYCODE_SPACE)
                Thread.sleep(500) 

                val resultEditText = findEditText()
                val currentSelection = resultEditText?.text?.trim() ?: ""

                if (currentSelection == target.trim()) {
                    result.selectedWord = currentSelection
                    result.wasFound = true
                    result.selectedNo = i
                    result.message = "Success: Matched target at candidate #$i."
                    Log.i(tag, result.message)
                    foundMatch = true
                    break
                } else {
                    findEditText()?.text = ""
                }
            }

            if (!foundMatch) {
                result.wasFound = false
                result.selectedNo = 0
                result.message = "Failure: Target not found in the first $MAX_CANDIDATES_TO_CHECK candidates."
                Log.w(tag, result.message)
            }

            results.add(result)
            sendPartialReport(result)
        }
    }

    private fun sendPartialReport(result: EvaluationResult) {
        val status = if (result.wasFound) "SUCCESS" else "FAILURE"
        val partialReport = String.format(
            "%-7s | %-15s | %-10s | Pos: %-2d | %s",
            status, result.pinyinSequence, result.targetWord, result.selectedNo, result.message
        )
        val color = if (result.wasFound) Color.GREEN else Color.RED
        activityRule.scenario.onActivity {
            it.setReportText(partialReport, color)
        }
    }

    @After
    fun generateReport() {
        if (results.isEmpty()) return

        val deviceModel = Build.MODEL
        val context = instrumentation.targetContext
        val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeName = imeId?.split('/')?.get(0) ?: "Unknown IME"

        val testMode = if (results.first().pinyinSequence.all { it.isDigit() }) "9-key" else "26-key"

        val reportBuilder = StringBuilder()
        reportBuilder.append("=========== KEYBOARD EVALUATION REPORT ============\n")
        reportBuilder.append("Device: $deviceModel\n")
        reportBuilder.append("Input Method: $imeName\n")
        reportBuilder.append("Test Mode: $testMode\n")
        reportBuilder.append("--------------------------------------------------\n")
        reportBuilder.append(String.format("%-4s | %-7s | %-15s | %-10s | %-10s | %-3s | %s\n", "No.", "Status", "Pinyin", "Target", "Selected", "Pos", "Message"))
        
        results.forEachIndexed { index, result ->
            val status = if (result.wasFound) "SUCCESS" else "FAILURE"
            reportBuilder.append(String.format("%-4d | %-7s | %-15s | %-10s | %-10s | %-3d | %s\n",
                index + 1, status, result.pinyinSequence, result.targetWord, result.selectedWord, result.selectedNo, result.message))
        }

        val totalCount = results.size
        val successCount = results.count { it.wasFound }
        val top1Count = results.count { it.selectedNo == 1 }
        val top2to5Count = results.count { it.selectedNo in 2..5 }
        val notFoundCount = results.count { it.selectedNo == 0 }

        val overallSuccessRate = if (totalCount > 0) (successCount.toDouble() / totalCount) * 100 else 0.0
        val top1Rate = if (totalCount > 0) (top1Count.toDouble() / totalCount) * 100 else 0.0
        val top2to5Rate = if (totalCount > 0) (top2to5Count.toDouble() / totalCount) * 100 else 0.0
        val notFoundRate = if (totalCount > 0) (notFoundCount.toDouble() / totalCount) * 100 else 0.0

        reportBuilder.append("--------------------------------------------------\n")
        reportBuilder.append("Summary:\n")
        reportBuilder.append(String.format("  Overall Success Rate: %.2f%% (%d/%d)\n", overallSuccessRate, successCount, totalCount))
        reportBuilder.append(String.format("  - Top 1 Match Rate:   %.2f%% (%d/%d)\n", top1Rate, top1Count, totalCount))
        reportBuilder.append(String.format("  - Top 2-5 Match Rate: %.2f%% (%d/%d)\n", top2to5Rate, top2to5Count, totalCount))
        reportBuilder.append(String.format("  - Not Found Rate:     %.2f%% (%d/%d)\n", notFoundRate, notFoundCount, totalCount))
        reportBuilder.append("================ END OF REPORT ================\n")

        val report = reportBuilder.toString()
        val reportFile = testDataManager.saveReport(report)
        
        if (reportFile != null) {
            Log.i(tag, "测试报告已导出至: ${reportFile.absolutePath}")
            activityRule.scenario.onActivity {
                it.setReportText("测试完成！报告路径：\n${reportFile.absolutePath}", Color.BLUE)
            }
        } else {
            Log.e(tag, "保存测试报告失败")
        }
        
        uiDevice.pressBack()
    }

    private fun pressKey(key: Char) {
        val keyCode = getKeyCode(key.lowercaseChar())
        if (keyCode != -1) {
            instrumentation.sendKeyDownUpSync(keyCode)
        }
    }

    private fun getKeyCode(char: Char): Int {
        return when (char) {
            'a' -> KeyEvent.KEYCODE_A
            'b' -> KeyEvent.KEYCODE_B
            'c' -> KeyEvent.KEYCODE_C
            'd' -> KeyEvent.KEYCODE_D
            'e' -> KeyEvent.KEYCODE_E
            'f' -> KeyEvent.KEYCODE_F
            'g' -> KeyEvent.KEYCODE_G
            'h' -> KeyEvent.KEYCODE_H
            'i' -> KeyEvent.KEYCODE_I
            'j' -> KeyEvent.KEYCODE_J
            'k' -> KeyEvent.KEYCODE_K
            'l' -> KeyEvent.KEYCODE_L
            'm' -> KeyEvent.KEYCODE_M
            'n' -> KeyEvent.KEYCODE_N
            'o' -> KeyEvent.KEYCODE_O
            'p' -> KeyEvent.KEYCODE_P
            'q' -> KeyEvent.KEYCODE_Q
            'r' -> KeyEvent.KEYCODE_R
            's' -> KeyEvent.KEYCODE_S
            't' -> KeyEvent.KEYCODE_T
            'u' -> KeyEvent.KEYCODE_U
            'v' -> KeyEvent.KEYCODE_V
            'w' -> KeyEvent.KEYCODE_W
            'x' -> KeyEvent.KEYCODE_X
            'y' -> KeyEvent.KEYCODE_Y
            'z' -> KeyEvent.KEYCODE_Z
            '0' -> KeyEvent.KEYCODE_0
            '1' -> KeyEvent.KEYCODE_1
            '2' -> KeyEvent.KEYCODE_2
            '3' -> KeyEvent.KEYCODE_3
            '4' -> KeyEvent.KEYCODE_4
            '5' -> KeyEvent.KEYCODE_5
            '6' -> KeyEvent.KEYCODE_6
            '7' -> KeyEvent.KEYCODE_7
            '8' -> KeyEvent.KEYCODE_8
            '9' -> KeyEvent.KEYCODE_9
            else -> -1
        }
    }
}
