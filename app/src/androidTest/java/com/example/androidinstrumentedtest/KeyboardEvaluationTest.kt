package com.example.androidinstrumentedtest

import android.content.Intent
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
    private val startButtonResId = "com.example.androidinstrumentedtest:id/start_test_button"

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

        val startButton = uiDevice.wait(Until.findObject(By.res(startButtonResId)), 5000)
        assertNotNull("Start button not found", startButton)
        startButton.click()

        val editText = uiDevice.wait(Until.findObject(By.res(editTextResId)), 5000)
        assertNotNull("Evaluation edit text not found", editText)
        editText.click()

        testData.forEach { (pinyin, target) ->
            val result = EvaluationResult(pinyin, target)
            Log.d(tag, "--- Testing: pinyin='$pinyin', target='$target' ---")

            editText.text = ""
            pinyin.forEach { char ->
                val keyCode = getKeyCode(char)
                if (keyCode != -1) uiDevice.pressKeyCode(keyCode)
            }

            // Wait for candidates to appear.
            Thread.sleep(2000)

            // Press space to select the first candidate.
            uiDevice.pressKeyCode(KeyEvent.KEYCODE_SPACE)

            result.selectedWord = editText.text

            if (result.selectedWord.trim() == target.trim()) {
                result.wasFound = true
                result.message = "Success: First candidate matched target."
                Log.i(tag, result.message)
            } else {
                result.wasFound = false
                val selectedForMessage = if (result.selectedWord.isBlank()) "<empty>" else result.selectedWord
                result.message = "Failure: First candidate ('$selectedForMessage') != target."
                Log.w(tag, result.message)
            }
            results.add(result)
        }
    }

    @After
    fun generateReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (results.isEmpty()) {
            context.sendBroadcast(Intent(MainActivity.TEST_FINISHED))
            return
        }

        val reportBuilder = StringBuilder()
        reportBuilder.append("=========== KEYBOARD EVALUATION REPORT ============\n")
        reportBuilder.append(String.format("%-4s | %-7s | %-15s | %-10s | %-10s | %s\n",
            "No.", "Status", "Pinyin", "Target", "Selected", "Message"))
        results.forEachIndexed { index, result ->
            val status = if (result.wasFound) "SUCCESS" else "FAILURE"
            reportBuilder.append(String.format("%-4d | %-7s | %-15s | %-10s | %-10s | %s\n",
                index + 1, status, result.pinyinSequence, result.targetWord, result.selectedWord, result.message))
        }

        val successCount = results.count { it.wasFound }
        val totalCount = results.size
        val successRate = if (totalCount > 0) (successCount.toDouble() / totalCount) * 100 else 0.0

        reportBuilder.append("--------------------------------------------------\n")
        reportBuilder.append(String.format("KEYBOARD EVALUATION REPORT Summary: %d out of %d tests succeeded. (%.2f%%)\n", successCount, totalCount, successRate))
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

        val intent = Intent(MainActivity.TEST_FINISHED).apply {
            putExtra(MainActivity.EXTRA_REPORT, report)
        }
        context.sendBroadcast(intent)
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
