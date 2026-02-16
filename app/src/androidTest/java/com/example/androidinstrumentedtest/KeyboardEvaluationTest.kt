package com.example.androidinstrumentedtest

import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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

    private lateinit var uiDevice: UiDevice
    private val results = mutableListOf<EvaluationResult>()
    private val tag = "KeyboardEvaluator"
    private val editTextResId = "com.example.androidinstrumentedtest:id/evaluation_edit_text"
    private val MAX_CANDIDATES_TO_CHECK = 5

    @Before
    fun setup() {
        uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun runKeyboardEvaluation() {
        val testData = readTestData()
        if (testData.isEmpty()) {
            Log.e(tag, "No test data found, aborting test.")
            return
        }

        activityRule.scenario.onActivity {
            it.setReportText("测试正在进行中...\n")
        }

        val editText = uiDevice.wait(Until.findObject(By.res(editTextResId)), 5000)
        assertNotNull("Evaluation edit text not found", editText)

        // Click to focus and bring up the keyboard initially.
        editText.click()
        Thread.sleep(1000) // Wait for keyboard to appear

        testData.forEach { (pinyin, target) ->
            val result = EvaluationResult(pinyin, target)
            Log.d(tag, "--- Testing: pinyin='$pinyin', target='$target' ---")

            var foundMatch = false
            for (i in 1..MAX_CANDIDATES_TO_CHECK) {
                // Ensure EditText is empty and focused for each attempt
                editText.click()
                editText.text = ""
                Thread.sleep(100) // Wait for UI to settle after clearing

                // Type the pinyin sequence char by char with delays
                pinyin.forEach { char ->
                    val keyCode = getKeyCode(char)
                    if (keyCode != -1) {
                        uiDevice.pressKeyCode(keyCode)
                        Thread.sleep(50) // Delay between key presses
                    }
                }
                Thread.sleep(500) // Wait for candidates to appear

                // Navigate to the i-th candidate.
                for (j in 1 until i) {
                    uiDevice.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
                    Thread.sleep(100)
                }

                // Select the candidate
                uiDevice.pressKeyCode(KeyEvent.KEYCODE_SPACE)
                Thread.sleep(200)

                val currentSelection = editText.text.trim()

                if (currentSelection == target.trim()) {
                    result.selectedWord = currentSelection
                    result.wasFound = true
                    result.selectedNo = i
                    result.message = "Success: Matched target at candidate #$i."
                    Log.i(tag, result.message)
                    foundMatch = true
                    break // Exit the candidate-checking loop
                } else {
                    // If not matched, clear the edit text for the next attempt in the loop
                    editText.text = ""
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
        val partialReport = String.format("%-7s | %-15s | %-10s | Pos: %-2d | %s\n",
            status, result.pinyinSequence, result.targetWord, result.selectedNo, result.message)

        val color = if (result.wasFound) Color.GREEN else Color.RED

        activityRule.scenario.onActivity {
            it.setReportText(partialReport, color)
        }
    }

    @After
    fun generateReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (results.isEmpty()) {
            activityRule.scenario.onActivity {
                it.setReportText("No results to display.")
            }
            return
        }

        val reportBuilder = StringBuilder()
        reportBuilder.append("=========== KEYBOARD EVALUATION REPORT ============\n")
        reportBuilder.append(String.format("%-4s | %-7s | %-15s | %-10s | %-10s | %-3s | %s\n",
            "No.", "Status", "Pinyin", "Target", "Selected", "Pos", "Message"))
        results.forEachIndexed { index, result ->
            val status = if (result.wasFound) "SUCCESS" else "FAILURE"
            reportBuilder.append(String.format("%-4d | %-7s | %-15s | %-10s | %-10s | %-3d | %s\n",
                index + 1, status, result.pinyinSequence, result.targetWord, result.selectedWord, result.selectedNo, result.message))
        }

        val totalCount = results.size
        val successCount = results.count { it.wasFound }
        val top1Count = results.count { it.selectedNo == 1 }
        val top2to5Count = results.count { it.selectedNo in 2..MAX_CANDIDATES_TO_CHECK }
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
        Log.d(tag, "\n\n" + report)

        // Save the report to a file on the device
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                val reportFile = File(dir, "keyboard_evaluation_report.txt")
                reportFile.writeText(report)
                Log.i(tag, "Test report saved to: ${reportFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to save test report.", e)
        }

        activityRule.scenario.onActivity {
            it.setReportText(report)
        }

        // Dismiss the keyboard before sleeping
        uiDevice.pressBack()

        // The test will finish after this method, which closes the app.
        // Add a long sleep to keep the app alive for inspection.
        Thread.sleep(300000) // 5 minutes
    }

    private fun readTestData(): List<Pair<String, String>> {
        val testData = mutableListOf<Pair<String, String>>()
        val fileName = "26_weijianpin.txt"
        try {
            val inputStream = InstrumentationRegistry.getInstrumentation().context.assets.open(fileName)
            BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                val parts = line.split('|')
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    testData.add(Pair(parts[0].trim(), parts[1].trim()))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading test data file: $fileName", e)
        }
        return testData
    }

    private fun getKeyCode(char: Char): Int {
        return when (char.lowercaseChar()) {
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
            else -> -1 // Invalid character
        }
    }
}
