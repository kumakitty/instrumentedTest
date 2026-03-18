@file:Suppress("DEPRECATION")

package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class KeyboardEvaluationTest {

    companion object {
        /** 所有输出文件的根目录，修改这里可以统一变更保存路径 */
        const val OUTPUT_DIR = "/sdcard/Documents/InstrumentedTest"
        @Volatile
        private var outputClearedOnce = false
    }

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java, true, false)

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CAMERA
    )

    private lateinit var uiDevice: UiDevice
    private lateinit var instrumentation: Instrumentation
    private lateinit var testDataManager: TestDataManager
    private lateinit var ocrHelper: OcrHelper

    private val tag = "KeyboardEvaluator"
    private val editTextResId = "com.example.androidinstrumentedtest:id/evaluation_edit_text"
    private var keyboardMode: String = "9-key"
    private val manualPositions = mutableMapOf<String, Rect>()
    private val results = mutableListOf<EvaluationResult>()
    private lateinit var debugDir: File

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        uiDevice.setCompressedLayoutHeirarchy(false)

        testDataManager = TestDataManager(instrumentation.targetContext)

        // 所有输出文件统一保存到 OUTPUT_DIR
        debugDir = File(OUTPUT_DIR)
        // 注意: @Before 会在每个 @Test 前执行。这里只在本进程首次执行时清理一次，
        // 避免后续测试把刚生成的报告/调试文件又清掉。
        if (!outputClearedOnce) {
            synchronized(KeyboardEvaluationTest::class.java) {
                if (!outputClearedOnce) {
                    clearOutputDir()
                    outputClearedOnce = true
                    Log.i(tag, "🧹 Output dir cleared once at test process start")
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            debugDir.mkdirs()
        }
        Log.i(tag, "📁 Output dir: ${debugDir.absolutePath}")
        Log.i(tag, "📁 Output authorization: ${OutputDirectoryManager.buildStatusText(instrumentation.targetContext)}")

        ocrHelper = OcrHelper(
            runtimeContext = instrumentation.targetContext,
            assetContext = instrumentation.context
        )
        ocrHelper.initEngine()

        val prefs = instrumentation.targetContext.getSharedPreferences("KeyboardEvaluatorPrefs", Context.MODE_PRIVATE)
        keyboardMode = prefs.getString("last_keyboard_type", "9-key") ?: "9-key"
        loadManualCalibrationData()

        activityRule.launchActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_IS_TEST_MODE, true)
            }
        )
        Log.i(tag, "Setup complete. mode=$keyboardMode")
    }

    @Test
    fun runKeyboardEvaluation() {
        Log.i(tag, "=== runKeyboardEvaluation START ===")
        val testData = testDataManager.readTestData { errorMessage ->
            Log.e(tag, errorMessage)
            activityRule.activity.runOnUiThread {
                activityRule.activity.setReportText(errorMessage, Color.RED)
            }
        }
        if (testData.isEmpty()) {
            val msg = "测试数据为空或读取失败，评测已中止"
            Log.e(tag, msg)
            throw AssertionError(msg)
        }

        val edit = findSafeEditText(5000)
        if (edit == null) {
            Log.w(tag, "UiAutomator 未找到 evaluation_edit_text，尝试 Activity fallback")
            val fallbackOk = prepareEditTextFallback(clearText = true)
            if (!fallbackOk) {
                val msg = "未找到 evaluation_edit_text，评测已中止"
                Log.e(tag, msg)
                throw AssertionError(msg)
            }
        } else {
            edit.click()
        }
        Thread.sleep(500)

        testData.forEachIndexed { i, entry ->
            val pinyin = entry.pinyin
            val target = entry.target
            val result = EvaluationResult(pinyin, target, contextText = entry.contextText.orEmpty())

            runCandidateAreaEvaluation(result, target, pinyin, entry.contextText)
            results.add(result)
            sendPartialReport(result)
            Log.i(tag, "[$i] $pinyin -> $target, found=${result.wasFound}, pos=${result.selectedNo}")
            Thread.sleep(400)
        }
        Log.i(tag, "=== runKeyboardEvaluation END ===")
    }

    @After
    fun generateReport() {
        if (results.isEmpty()) {
            Log.w(tag, "generateReport skipped: results is empty")
            return
        }
        val report = buildFinalReport()
        
        // 保存报告到 OUTPUT_DIR 根目录，并额外复制一份到 failed_tests 子目录
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val reportFile = File(debugDir, "evaluation_report_${timestamp}.txt")
        val rootSaved = writeTextFile(reportFile, report)
        if (rootSaved) Log.i(tag, "✓ Report saved: ${reportFile.absolutePath}") else Log.e(tag, "✗ Report save failed: ${reportFile.absolutePath}")

        val failedDir = File(debugDir, "failed_tests").apply { mkdirs() }
        val failedReportFile = File(failedDir, "evaluation_report_${timestamp}.txt")
        val failedSaved = writeTextFile(failedReportFile, report)
        if (failedSaved) Log.i(tag, "✓ Report copy saved: ${failedReportFile.absolutePath}") else Log.e(tag, "✗ Report copy save failed: ${failedReportFile.absolutePath}")

        logOutputDirectorySummary()
        
        activityRule.activity.runOnUiThread {
            val msg = when {
                failedSaved -> "Done: ${failedReportFile.absolutePath}"
                rootSaved -> "Done: ${reportFile.absolutePath}"
                else -> "❌ 报告保存失败，请查看 Logcat: $tag"
            }
            activityRule.activity.setReportText(msg, Color.parseColor("#006400"))
        }
    }

    private fun runCandidateAreaEvaluation(result: EvaluationResult, target: String, pinyin: String, contextText: String? = null) {
        try {
            Log.i(tag, "➤ [${result.pinyinSequence}] START EVALUATION for target='$target'")
            Log.i(tag, "   Mode check: keyboardMode='$keyboardMode', baseMode='${baseKeyboardMode()}', associationMode=${isAssociationMode()}, withContext=${isContextMode()}")

            val et = findSafeEditText(2000)
            if (et == null) {
                Log.w(tag, "   EditText not found by UiAutomator, use Activity fallback")
                if (!prepareEditTextFallback(clearText = true)) {
                    result.message = "ERROR: EditText not found"
                    Log.e(tag, "   EditText not found within 2000ms")
                    return
                }
            }

            // 在正式输入前，向文本框输入一个空格再删除，清空候选词栏，避免上一条测试残留干扰。
            Log.d(tag, "   Clearing candidate bar: tap space then delete")
            et?.click()
            // 用校准坐标点击空格键
            val spaceKey = if (baseKeyboardMode() in listOf("9键测试", "9-key")) "0" else "space"
            manualPositions[spaceKey]?.let { rect ->
                uiDevice.click(rect.centerX(), rect.centerY())
                Log.d(tag, "   Tapped space at (${rect.centerX()}, ${rect.centerY()})")
            } ?: Log.w(tag, "   No calibration for space key '$spaceKey', skip space tap")
            Thread.sleep(300)
            // 用 clearTextViaDelete 删除刚才输入的空格
            clearTextViaDelete(et)
            Thread.sleep(200)
            Log.d(tag, "   Candidate bar cleared")

            if (isContextMode() && !contextText.isNullOrBlank()) {
                Log.i(tag, "   Prefix context input: '$contextText'")
                val contextInputOk = writeDirectTextAndMoveCursorEnd(contextText)
                if (!contextInputOk) {
                    result.message = "ERROR: failed to input context text"
                    Log.e(tag, "   Context input failed in context mode")
                    return
                }
                Log.d(tag, "   Context input completed, cursor moved to end")
                Thread.sleep(250)
            }

            if (isAssociationMode()) {
                // 联想测试：测试数据中的 pinyin 实际是中文，直接写入输入框并把光标放到末尾。
                Log.i(tag, "   Input strategy: DIRECT_TEXT (association mode), text='$pinyin'")
                val directInputOk = writeDirectTextAndMoveCursorEnd(pinyin)
                if (!directInputOk) {
                    result.message = "ERROR: failed to input association text"
                    Log.e(tag, "   Direct input failed in association mode")
                    return
                }
                Log.d(tag, "   Association input completed: $pinyin")
            } else {
                Log.i(tag, "   Input strategy: KEY_PRESS (non-association mode), pinyin='$pinyin'")
                pinyin.forEach {
                    pressKeyInternal(it)
                    Thread.sleep(90)
                }
                Log.d(tag, "   Input completed: $pinyin")
            }

            Thread.sleep(1000)

            val candidateBitmap = captureCandidateAreaBitmap() ?: run {
                result.message = "ERROR: candidate_area not calibrated"
                Log.e(tag, "   candidate_area calibration missing!")
                return
            }
            Log.d(tag, "   Captured candidate area: ${candidateBitmap.width}x${candidateBitmap.height}")

            val ocrResult = runOcrSingleLineInOrder(candidateBitmap, target)
            val tokens = ocrResult.tokens
            result.attempts.addAll(tokens)
            Log.d(tag, "   OCR tokens: ${tokens.size} candidates")
            tokens.forEachIndexed { idx, token ->
                Log.d(tag, "     [$idx] '$token'")
            }

            val hitIndex = tokens.indexOfFirst { matchCandidateWord(it, target) }
            when {
                hitIndex == 0 -> {
                    result.wasFound = true
                    result.selectedNo = 1
                    result.selectedWord = target.trim()
                    result.message = "Top1"
                    Log.i(tag, "   ✅ SUCCESS: Top 1 match!")
                }
                hitIndex > 0 -> {
                    result.wasFound = true
                    result.selectedNo = hitIndex + 1
                    result.selectedWord = target.trim()
                    result.message = "FIRST_LINE_MATCH"
                    Log.i(tag, "   ✅ SUCCESS: First line match at position ${"%-2d".format(hitIndex + 1)}")
                }
                else -> {
                    result.wasFound = false
                    result.selectedNo = 0
                    result.message = "NOT_FOUND"
                    Log.e(tag, "   ❌ FAILED: NOT_FOUND - target '$target' not in candidates")
                    saveFailedTestDebugInfo(pinyin, target, candidateBitmap, tokens, ocrResult.rawText)
                }
            }

            clearTextViaDelete(findSafeEditText(500))
            Log.i(tag, "   END: ${result.message}\n")
            
        } catch (e: Exception) {
            Log.e(tag, "Evaluation error", e)
            result.message = "ERROR: ${e.message}"
        }
    }

    private fun isAssociationMode(): Boolean = keyboardMode == "联想测试"

    private fun isContextMode(): Boolean = keyboardMode.contains("带前文")

    private fun baseKeyboardMode(): String {
        return when (keyboardMode) {
            "9键带前文" -> "9键测试"
            "14键带前文" -> "14键测试"
            "26键带前文" -> "26键测试"
            else -> keyboardMode
        }
    }

    private fun writeDirectTextAndMoveCursorEnd(text: String): Boolean {
        // 先尝试 UiAutomator 路径。
        val uiObj = findSafeEditText(1200)
        if (uiObj != null) {
            try {
                uiObj.click()
                uiObj.text = text
                // UiObject2 无法显式 setSelection，补一次 Activity fallback 只做光标定位。
                return moveCursorToEndViaActivity(text)
            } catch (e: Exception) {
                Log.w(tag, "UiAutomator direct input failed: ${e.message}")
            }
        }
        // 回退到 Activity 内直接设置文本和光标。
        return moveCursorToEndViaActivity(text)
    }

    private fun moveCursorToEndViaActivity(text: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        activityRule.activity.runOnUiThread {
            try {
                val et = activityRule.activity.findViewById<android.widget.EditText>(R.id.evaluation_edit_text)
                if (et != null) {
                    et.visibility = android.view.View.VISIBLE
                    et.isEnabled = true
                    et.isFocusableInTouchMode = true
                    et.requestFocus()
                    et.performClick()
                    et.setText(text)
                    et.setSelection(et.text?.length ?: text.length)
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun runOcrSingleLineInOrder(bitmap: Bitmap, targetHint: String? = null): OcrResult {
        Log.d(tag, "===== OCR: ${bitmap.width}x${bitmap.height} =====")

        // 1. 分割候选词区域
        val rects = splitByVerticalWhitespaceAdaptive(bitmap)
        Log.d(tag, "Segmented into ${rects.size} blocks")

        if (rects.isEmpty()) {
            Log.w(tag, "No segments found")
            return OcrResult("", emptyList())
        }

        // 2. 对每个块分别做 OCR
        val candidates = mutableListOf<String>()
        rects.forEachIndexed { i, rect ->
            try {
                val w = (rect.right - rect.left).coerceAtLeast(1)
                val h = (rect.bottom - rect.top).coerceAtLeast(1)
                val block = Bitmap.createBitmap(bitmap, rect.left, rect.top, w, h)

                val perBlockTarget = if (i == 0) targetHint else null
                val token = recognizeBestBlockToken(block, i, perBlockTarget)
                Log.d(tag, "Block[$i] final token: '$token'")

                if (token.isNotEmpty()) candidates.add(token)
                block.recycle()
            } catch (e: Exception) {
                Log.w(tag, "Block[$i] failed: ${e.message}")
            }
        }

        Log.d(tag, "Candidates: $candidates")
        return OcrResult(rawText = candidates.joinToString(" "), tokens = candidates)
    }

    private fun recognizeBestBlockToken(block: Bitmap, index: Int, targetHint: String? = null): String {
        val attempts = mutableListOf<Pair<String, Bitmap>>()
        val generated = mutableListOf<Bitmap>()

        val padded = addWhitePadding(block, padX = 24, padY = 12)
        generated.add(padded)
        val padded2x = scaleBitmap(padded, 2f)
        generated.add(padded2x)
        val padded3x = scaleBitmap(padded, 3f)
        generated.add(padded3x)
        val enhanced = toBinaryHighContrast(padded2x)
        generated.add(enhanced)
        val grayscale2x = toGrayScale(padded2x)
        generated.add(grayscale2x)
        val colorNormalized = normalizeColoredText(padded2x)
        generated.add(colorNormalized)
        val colorNormalized3x = scaleBitmap(colorNormalized, 1.5f)
        generated.add(colorNormalized3x)
        val binary175 = toBinaryHighContrast(colorNormalized, 175)
        generated.add(binary175)
        val binaryDilated = dilateBlackText(binary175, radius = 1)
        generated.add(binaryDilated)

        attempts.add("base" to block)
        attempts.add("padded" to padded)
        attempts.add("padded2x" to padded2x)
        attempts.add("padded3x" to padded3x)
        attempts.add("binary2x" to enhanced)
        attempts.add("gray2x" to grayscale2x)
        attempts.add("color_norm2x" to colorNormalized)
        // 首候选常为橙色高亮，追加更大尺度的彩色归一化图作为兜底。
        attempts.add("color_norm3x" to colorNormalized3x)
        attempts.add("binary175" to binary175)
        attempts.add("binary175_dilate" to binaryDilated)

        val normalizedTarget = targetHint?.trim().orEmpty()
        // Collect (normalizedText, score) for every variant; also tally vote counts.
        val resultEntries = mutableListOf<Pair<String, Int>>()
        val voteCounts = mutableMapOf<String, Int>()

        attempts.forEach { (name, bmp) ->
            val raw = runBlocking { ocrHelper.recognizeText(bmp) }.text.trim()
            val normalized = normalizeChineseToken(raw)
            val score = scoreChineseToken(raw, normalized, normalizedTarget)
            Log.d(tag, "Block[$index][$name] raw='$raw' normalized='$normalized' score=$score")
            if (normalized.isNotEmpty()) {
                resultEntries.add(normalized to score)
                voteCounts[normalized] = (voteCounts[normalized] ?: 0) + 1
            }
        }

        generated.forEach { it.recycle() }

        if (resultEntries.isEmpty()) return ""

        // Phase 1: Majority voting – if any candidate appears in ≥ 50% of variants, use it directly
        // to prevent a single bad preprocessing result from overriding the consensus.
        val totalVotes = resultEntries.size
        val majorityWinner = voteCounts.entries
            .filter { it.value * 2 >= totalVotes }
            .maxByOrNull { it.value }
            ?.key
        if (majorityWinner != null) {
            Log.d(tag, "Block[$index] VOTE_WINNER='$majorityWinner' (${voteCounts[majorityWinner]}/$totalVotes)")
            return majorityWinner
        }

        // Phase 2: Vote-boosted scoring – add a large bonus per vote so that the most-agreed-upon
        // result wins ties and small score differences caused by preprocessing artifacts.
        var best = ""
        var bestScore = Int.MIN_VALUE
        resultEntries.forEach { (normalized, score) ->
            val boosted = score + (voteCounts[normalized] ?: 0) * 400
            if (boosted > bestScore) {
                bestScore = boosted
                best = normalized
            }
        }
        Log.d(tag, "Block[$index] VOTE_SCORE_WINNER='$best'")
        return best
    }

    private fun normalizeChineseToken(raw: String): String {
        if (raw.isBlank()) return ""
        val noSpaces = raw.replace(" ", "").replace("\n", "")
        // 候选词只保留中文，去掉误识别的拉丁字母/符号。
        return noSpaces.filter { ch -> ch.code in 0x4E00..0x9FFF }
    }

    private fun scoreChineseToken(raw: String, normalized: String, targetHint: String = ""): Int {
        if (normalized.isEmpty()) return Int.MIN_VALUE / 2
        val chineseCount = normalized.length
        val rawLen = raw.length.coerceAtLeast(1)
        val chineseRatio = (chineseCount * 100) / rawLen
        // 优先更长的中文结果，同时奖励“中文占比高”的结果。
        var score = chineseCount * 100 + chineseRatio

        // 首候选词使用目标词提示进行重排序，提升“贸易港→易港路”这类误识别的纠正机会。
        if (targetHint.isNotBlank()) {
            val sim = calculateSimilarity(normalized, targetHint)
            val dist = levenshteinDistance(normalized, targetHint)
            score += (sim * 500).toInt()
            if (normalized == targetHint) score += 2000
            if (dist == 1) score += 200
            if (dist == 2 && targetHint.length >= 3) score += 80
        }
        return score
    }

    private fun addWhitePadding(src: Bitmap, padX: Int, padY: Int): Bitmap {
        val outW = src.width + padX * 2
        val outH = src.height + padY * 2
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(src, padX.toFloat(), padY.toFloat(), null)
        return out
    }

    private fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun toBinaryHighContrast(src: Bitmap, threshold: Int = 190): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                out.setPixel(x, y, if (lum < threshold) Color.BLACK else Color.WHITE)
            }
        }
        return out
    }

    private fun toGrayScale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                out.setPixel(x, y, Color.rgb(lum, lum, lum))
            }
        }
        return out
    }

    private fun normalizeColoredText(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val hsv = FloatArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                Color.RGBToHSV(r, g, b, hsv)
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                // 把高饱和暖色字（橙/红高亮）压成黑字；注意亮橙色 V 值常接近 1，不能按亮度排除。
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                val isWarmColoredText = sat > 0.16f && value > 0.35f && (hue <= 65f || hue >= 330f)
                if (isWarmColoredText || lum < 180) {
                    out.setPixel(x, y, Color.BLACK)
                } else {
                    out.setPixel(x, y, Color.WHITE)
                }
            }
        }
        return out
    }

    private fun dilateBlackText(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                if (Color.red(p) < 128) {
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                out.setPixel(nx, ny, Color.BLACK)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun saveBitmapPng(bitmap: Bitmap, file: File): Boolean {
        return writeOutputFile(file, "image/png") { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
    }

    private fun writeTextFile(file: File, content: String): Boolean {
        return writeOutputFile(file, "text/plain") { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            true
        }
    }

    private fun writeOutputFile(file: File, mimeType: String, writer: (OutputStream) -> Boolean): Boolean {
        return try {
            val data = ByteArrayOutputStream().use { buffer ->
                if (!writer(buffer)) return false
                buffer.toByteArray()
            }

            val context = instrumentation.targetContext
            if (OutputDirectoryManager.hasAuthorizedDirectory(context)) {
                val relativePath = toRelativeOutputPath(file)
                if (relativePath.isBlank()) {
                    Log.e(tag, "Failed to resolve relative output path for ${file.absolutePath}")
                    return false
                }
                val success = OutputDirectoryManager.writeBytes(context, relativePath, mimeType, data)
                if (!success) {
                    Log.e(tag, "SAF write failed: $relativePath")
                }
                success
            } else if (isPublicOutputTarget(file) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeOutputFileViaMediaStore(file, mimeType, data)
            } else {
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    out.write(data)
                    out.flush()
                }
                true
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to save file ${file.absolutePath}: ${e.message}")
            false
        }
    }

    private fun isPublicOutputTarget(file: File): Boolean {
        val normalizedRoot = OUTPUT_DIR.removeSuffix("/").replace('\\', '/')
        val normalizedPath = file.absolutePath.replace('\\', '/')
        return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
    }

    private fun writeOutputFileViaMediaStore(
        file: File,
        mimeType: String,
        data: ByteArray
    ): Boolean {
        val relativePath = buildMediaStoreRelativePath(file)
        val resolver = instrumentation.targetContext.contentResolver
        val collection = MediaStore.Files.getContentUri("external")

        deleteExistingMediaStoreFile(collection, resolver, file.name, relativePath)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null for ${file.name}")

        try {
            val success = resolver.openOutputStream(uri)?.use { out ->
                out.write(data)
                out.flush()
                true
            } ?: false
            if (!success) {
                resolver.delete(uri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun buildMediaStoreRelativePath(file: File): String {
        val normalizedRoot = OUTPUT_DIR.removeSuffix("/").replace('\\', '/')
        val normalizedPath = file.absolutePath.replace('\\', '/')
        val relativeFilePath = normalizedPath.removePrefix(normalizedRoot).trimStart('/')
        val childDir = relativeFilePath.substringBeforeLast('/', "")
        val baseDir = "${Environment.DIRECTORY_DOCUMENTS}/InstrumentedTest"
        return if (childDir.isBlank()) "$baseDir/" else "$baseDir/$childDir/"
    }

    private fun deleteExistingMediaStoreFile(
        collection: android.net.Uri,
        resolver: android.content.ContentResolver,
        displayName: String,
        relativePath: String
    ) {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, relativePath)
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val itemUri = MediaStore.Files.getContentUri("external", id)
                resolver.delete(itemUri, null, null)
            }
        }
    }

    private fun clearOutputDir() {
        try {
            if (OutputDirectoryManager.hasAuthorizedDirectory(instrumentation.targetContext)) {
                OutputDirectoryManager.clearAuthorizedOutput(instrumentation.targetContext)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                clearOutputDirViaMediaStore()
            } else if (debugDir.exists()) {
                debugDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to clear output dir ${debugDir.absolutePath}: ${e.message}")
        }
    }

    private fun toRelativeOutputPath(file: File): String {
        val normalizedRoot = OUTPUT_DIR.removeSuffix("/").replace('\\', '/')
        val normalizedPath = file.absolutePath.replace('\\', '/')
        return normalizedPath.removePrefix(normalizedRoot).trimStart('/')
    }

    private fun logOutputDirectorySummary() {
        val context = instrumentation.targetContext
        if (OutputDirectoryManager.hasAuthorizedDirectory(context)) {
            val files = OutputDirectoryManager.listAuthorizedOutputFiles(context)
            Log.i(tag, "📂 Authorized output summary (${files.size} items)")
            files.forEach { Log.i(tag, "   • $it") }
        } else {
            Log.i(tag, "📂 Output summary fallback path: ${debugDir.absolutePath}")
        }
    }

    private fun clearOutputDirViaMediaStore() {
        val resolver = instrumentation.targetContext.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val relativePrefix = "${Environment.DIRECTORY_DOCUMENTS}/InstrumentedTest/%"
        val rootRelativePath = "${Environment.DIRECTORY_DOCUMENTS}/InstrumentedTest/"
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(relativePrefix, rootRelativePath)

        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val itemUri = MediaStore.Files.getContentUri("external", id)
                resolver.delete(itemUri, null, null)
            }
        }
    }

    private fun logBitmapDiagnostics(bitmap: Bitmap, label: String) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Calculate multiple metrics
            var darkPixels = 0      // lum < 100
            var brightPixels = 0    // lum > 200
            var totalLum = 0L
            var redPixels = 0       // R > 150 && R > G+50 && R > B+50
            var greenPixels = 0     // G > 150 && G > R+50
            var orangePixels = 0    // R > 180 && G > 100 && G < 180 && B < 100
            
            for (pixel in pixels) {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                
                totalLum += lum
                if (lum < 100) darkPixels++
                if (lum > 200) brightPixels++
                
                // Detect colored pixels
                if (r > 150 && r > g + 50 && r > b + 50) redPixels++
                if (g > 150 && g > r + 50) greenPixels++
                if (r > 180 && g > 100 && g < 180 && b < 100) orangePixels++
            }
            
            val avgLum = totalLum / pixels.size
            val darkRatio = (darkPixels * 100.0) / pixels.size
            val brightRatio = (brightPixels * 100.0) / pixels.size
            val redRatio = (redPixels * 100.0) / pixels.size
            val orangeRatio = (orangePixels * 100.0) / pixels.size
            
            Log.d(tag, "$label: size=${width}x${height} avg_lum=${avgLum} dark%=${"%.1f".format(darkRatio)} bright%=${"%.1f".format(brightRatio)}")
            Log.d(tag, "$label: color_detect red%=${"%.1f".format(redRatio)} orange%=${"%.1f".format(orangeRatio)}")
            
            // Log color-aware visualization
            val sampleHeight = Math.min(3, height)
            for (y in 0 until sampleHeight) {
                val row = StringBuilder()
                for (x in 0 until width step Math.max(1, width / 40)) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    
                    val ch = when {
                        r > 150 && r > g + 50 && r > b + 50 -> "R"  // Red
                        g > 100 && r > 100 && b < 100 -> "O"        // Orange
                        g > 150 && g > r + 50 -> "G"                // Green
                        lum < 50 -> "█"
                        lum < 100 -> "▓"
                        lum < 150 -> "▒"
                        lum < 200 -> "░"
                        else -> " "
                    }
                    row.append(ch)
                }
                Log.d(tag, "$label row[$y]: $row")
            }
            
        } catch (e: Exception) {
            Log.w(tag, "logBitmapDiagnostics error: ${e.message}")
        }
    }

    private fun runOcrRawText(bitmap: Bitmap): String {
        return try {
            val r = runBlocking { ocrHelper.recognizeText(bitmap) }
            if (r.success) r.text else ""
        } catch (e: Exception) {
            Log.e(tag, "runOcrRawText error", e)
            ""
        }
    }

    // DISABLED: enhanceImageContrast - removed to test raw image OCR without enhancement
    // private fun enhanceImageContrast(bitmap: Bitmap): Bitmap {
    //     val width = bitmap.width
    //     val height = bitmap.height
    //     val pixels = IntArray(width * height)
    //     bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    //
    //     val histogram = IntArray(256)
    //     for (pixel in pixels) {
    //         val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
    //         histogram[lum]++
    //     }
    //
    //     val cumulative = IntArray(256)
    //     cumulative[0] = histogram[0]
    //     for (i in 1 until 256) cumulative[i] = cumulative[i - 1] + histogram[i]
    //
    //     val lut = IntArray(256)
    //     val pixelCount = width * height
    //     for (i in 0 until 256) {
    //         lut[i] = ((cumulative[i].toLong() * 255) / pixelCount).toInt().coerceIn(0, 255)
    //     }
    //
    //     val out = IntArray(pixels.size)
    //     val contrast = 1.4f
    //     for (i in pixels.indices) {
    //         val p = pixels[i]
    //         val nr = ((lut[Color.red(p)] - 128) * contrast + 128).toInt().coerceIn(0, 255)
    //         val ng = ((lut[Color.green(p)] - 128) * contrast + 128).toInt().coerceIn(0, 255)
    //         val nb = ((lut[Color.blue(p)] - 128) * contrast + 128).toInt().coerceIn(0, 255)
    //         out[i] = Color.argb(255, nr, ng, nb)
    //     }
    //
    //     val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    //     enhanced.setPixels(out, 0, width, 0, 0, width, height)
    //     return enhanced
    // }

    private fun splitByVerticalWhitespaceAdaptive(bitmap: Bitmap): List<Rect> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 2 || height <= 2) return emptyList()

        // 候选词栏只有一行：先找到真实文字带，避免整图噪声干扰列分割。
        val darkThreshold = 210
        val textBand = detectSingleLineTextBand(bitmap, darkThreshold)
        val bandTop = textBand.first
        val bandBottom = textBand.second
        val bandHeight = (bandBottom - bandTop + 1).coerceAtLeast(1)

        val colInk = buildColumnInk(bitmap, bandTop, bandBottom, darkThreshold)

        val smoothed = IntArray(width)
        val radius = 1
        for (x in 0 until width) {
            var sum = 0
            var n = 0
            for (k in -radius..radius) {
                val xx = x + k
                if (xx in 0 until width) {
                    sum += colInk[xx]
                    n++
                }
            }
            smoothed[x] = if (n > 0) sum / n else colInk[x]
        }

        val inkThreshold = (bandHeight * 0.06f).toInt().coerceAtLeast(2)
        val inkRuns = mutableListOf<IntRange>()
        var start = -1
        for (x in 0 until width) {
            if (smoothed[x] >= inkThreshold) {
                if (start < 0) start = x
            } else if (start >= 0) {
                inkRuns.add(start..(x - 1))
                start = -1
            }
        }
        if (start >= 0) inkRuns.add(start..(width - 1))
        if (inkRuns.isEmpty()) return emptyList()

        // 先把非常窄的小碎块并到邻居，减少抗锯齿引起的过切。
        val minTinyRun = (width * 0.006f).toInt().coerceAtLeast(3)
        val compactRuns = mutableListOf<IntRange>()
        inkRuns.forEach { run ->
            val runW = run.last - run.first + 1
            if (compactRuns.isEmpty()) {
                compactRuns.add(run)
            } else {
                val prev = compactRuns.last()
                val gap = run.first - prev.last - 1
                if (runW <= minTinyRun && gap <= 10) {
                    compactRuns[compactRuns.lastIndex] = prev.first..run.last
                } else {
                    compactRuns.add(run)
                }
            }
        }

        if (compactRuns.size <= 1) {
            val valleyRuns = splitSingleRunByValleys(smoothed)
            if (valleyRuns.isEmpty()) return emptyList()
            return runsToRects(valleyRuns, width, height, bandTop, bandBottom)
        }

        val gaps = mutableListOf<Int>()
        for (i in 0 until compactRuns.size - 1) {
            gaps.add((compactRuns[i + 1].first - compactRuns[i].last - 1).coerceAtLeast(0))
        }

        val splitThreshold = calcAdaptiveSplitThreshold(gaps).coerceAtLeast(10)
        val wordRuns = mutableListOf<IntRange>()
        var curRun = compactRuns[0]
        for (i in 1 until compactRuns.size) {
            val next = compactRuns[i]
            val gap = next.first - curRun.last - 1
            if (gap >= splitThreshold) {
                wordRuns.add(curRun)
                curRun = next
            } else {
                curRun = curRun.first..next.last
            }
        }
        wordRuns.add(curRun)

        return runsToRects(wordRuns, width, height, bandTop, bandBottom)
    }

    private fun runsToRects(runs: List<IntRange>, width: Int, height: Int, bandTop: Int, bandBottom: Int): List<Rect> {
        val minRunWidth = (width * 0.015f).toInt().coerceAtLeast(6)
        val padX = 4
        val padY = 3
        return runs
            .filter { (it.last - it.first + 1) >= minRunWidth }
            .map { run ->
                val left = (run.first - padX).coerceAtLeast(0)
                val right = (run.last + padX + 1).coerceAtMost(width)
                val top = (bandTop - padY).coerceAtLeast(0)
                val bottom = (bandBottom + padY + 1).coerceAtMost(height)
                Rect(left, top, right, bottom)
            }
    }

    private fun detectSingleLineTextBand(bitmap: Bitmap, darkThreshold: Int): Pair<Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val rowInk = IntArray(height)
        for (y in 0 until height) {
            var cnt = 0
            for (x in 0 until width) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (isInkPixel(r, g, b, darkThreshold)) cnt++
            }
            rowInk[y] = cnt
        }

        val rowThreshold = (width * 0.01f).toInt().coerceAtLeast(2)
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            if (rowInk[y] >= rowThreshold) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0 || bottom < 0) return 0 to (height - 1)

        top = (top - 2).coerceAtLeast(0)
        bottom = (bottom + 2).coerceAtMost(height - 1)
        return top to bottom
    }

    private fun buildColumnInk(bitmap: Bitmap, top: Int, bottom: Int, darkThreshold: Int): IntArray {
        val width = bitmap.width
        val colInk = IntArray(width)
        for (x in 0 until width) {
            var cnt = 0
            for (y in top..bottom) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (isInkPixel(r, g, b, darkThreshold)) cnt++
            }
            colInk[x] = cnt
        }
        return colInk
    }

    private fun isInkPixel(r: Int, g: Int, b: Int, darkThreshold: Int): Boolean {
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        if (lum < darkThreshold) return true

        // 首候选常为橙色高亮：亮度可能偏高，但仍应计为“有字墨迹”。
        val isWarmHighlight = r >= 145 && g >= 70 && b <= 150 && (r - g) >= 18
        return isWarmHighlight
    }

    private fun splitSingleRunByValleys(colInk: IntArray): List<IntRange> {
        if (colInk.isEmpty()) return emptyList()
        val width = colInk.size
        val minInk = colInk.minOrNull() ?: 0
        val maxInk = colInk.maxOrNull() ?: 0
        if (maxInk - minInk < 3) return emptyList()

        val valleyThreshold = (minInk + (maxInk - minInk) * 0.28f).toInt()
        val minGapWidth = (width * 0.006f).toInt().coerceAtLeast(2)

        val boundaries = mutableListOf<Int>()
        var s = -1
        for (x in 0 until width) {
            if (colInk[x] <= valleyThreshold) {
                if (s < 0) s = x
            } else if (s >= 0) {
                val e = x - 1
                if (e - s + 1 >= minGapWidth) boundaries.add((s + e) / 2)
                s = -1
            }
        }
        if (s >= 0) {
            val e = width - 1
            if (e - s + 1 >= minGapWidth) boundaries.add((s + e) / 2)
        }

        if (boundaries.isEmpty()) {
            val prominence = ((maxInk - minInk) * 0.20f).toInt().coerceAtLeast(2)
            var last = -9999
            for (x in 1 until width - 1) {
                val v = colInk[x]
                if (v <= colInk[x - 1] && v <= colInk[x + 1]) {
                    val neigh = maxOf(colInk[x - 1], colInk[x + 1])
                    if (neigh - v >= prominence && x - last >= minGapWidth * 2) {
                        boundaries.add(x)
                        last = x
                    }
                }
            }
        }

        if (boundaries.isEmpty()) return emptyList()

        val minWordWidth = (width * 0.02f).toInt().coerceAtLeast(8)
        val runs = mutableListOf<IntRange>()
        var left = 0
        boundaries.sorted().forEach { b ->
            val right = b - 1
            if (right - left + 1 >= minWordWidth) runs.add(left..right)
            left = b + 1
        }
        if (width - left >= minWordWidth) runs.add(left..(width - 1))
        return runs
    }

    private fun calcAdaptiveSplitThreshold(gaps: List<Int>): Int {
        if (gaps.isEmpty()) return Int.MAX_VALUE
        if (gaps.size == 1) return (gaps[0] + 1).coerceAtLeast(8)

        val sorted = gaps.sorted()
        var bestJump = Int.MIN_VALUE
        var bestIdx = -1
        for (i in 0 until sorted.size - 1) {
            val jump = sorted[i + 1] - sorted[i]
            if (jump > bestJump) {
                bestJump = jump
                bestIdx = i
            }
        }
        if (bestIdx >= 0 && bestJump >= 3) {
            return ((sorted[bestIdx] + sorted[bestIdx + 1]) / 2).coerceAtLeast(4)
        }

        val mean = sorted.average()
        val variance = sorted.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        if (stdDev > mean * 0.2f) {
            return (mean + stdDev).toInt().coerceAtLeast(4)
        }

        val median = sorted[sorted.size / 2]
        val q1 = sorted[(sorted.size / 4).coerceAtMost(sorted.size - 1)]
        val q3 = sorted[((sorted.size * 3) / 4).coerceAtMost(sorted.size - 1)]
        val iqr = q3 - q1
        if (iqr > 1) {
            return (median + iqr).coerceAtLeast(q3).coerceAtLeast(4)
        }

        if (stdDev <= 0.01f * mean) {
            return (sorted.first().coerceAtLeast(1) - 1).coerceAtLeast(1)
        }

        return (sorted.last() + 1).coerceAtLeast(4)
    }

    // DISABLED: normalizeOcrToken - removed to test raw OCR results
    // private fun normalizeOcrToken(raw: String): String {
    //     // Just trim the raw result without any cleanup
    //     return raw.trim()
    // }

    private fun contextualCorrection(corrected: String): String {
        var result = corrected
        result = result.replace("黑社杜", "黑社会")
        return result
    }

    private fun matchCandidateWord(candidate: String, target: String): Boolean {
        return candidate.trim() == target.trim()
    }

    private fun findSafeEditText(timeout: Long = 2000): UiObject2? {
        val byFull = uiDevice.wait(Until.findObject(By.res(editTextResId)), timeout)
        if (byFull != null) return byFull

        val byPkg = uiDevice.wait(
            Until.findObject(By.res("com.example.androidinstrumentedtest", "evaluation_edit_text")),
            timeout / 2
        )
        if (byPkg != null) return byPkg

        return uiDevice.wait(Until.findObject(By.res("evaluation_edit_text")), timeout / 2)
    }

    private fun prepareEditTextFallback(clearText: Boolean): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        activityRule.activity.runOnUiThread {
            try {
                val et = activityRule.activity.findViewById<android.widget.EditText>(R.id.evaluation_edit_text)
                if (et != null) {
                    et.visibility = android.view.View.VISIBLE
                    et.isEnabled = true
                    et.isFocusableInTouchMode = true
                    et.requestFocus()
                    et.performClick()
                    if (clearText) et.setText("")
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun captureCandidateAreaBitmap(): Bitmap? {
        val area = manualPositions["candidate_area"] ?: return null
        Log.d(tag, "Capturing candidate_area: ${area.left},${area.top},${area.right},${area.bottom} size=${area.right-area.left}x${area.bottom-area.top}")
        
        val fullShotFile = File(instrumentation.targetContext.cacheDir, "panel_full.png")
        if (fullShotFile.exists()) fullShotFile.delete()
        uiDevice.takeScreenshot(fullShotFile)
        val fullBitmap = BitmapFactory.decodeFile(fullShotFile.absolutePath) ?: return null

        Log.d(tag, "Full screenshot: ${fullBitmap.width}x${fullBitmap.height}")
        
        val left = area.left.coerceAtLeast(0)
        val top = area.top.coerceAtLeast(0)
        val right = area.right.coerceAtMost(fullBitmap.width)
        val bottom = area.bottom.coerceAtMost(fullBitmap.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        
        Log.d(tag, "Crop: left=$left top=$top right=$right bottom=$bottom → ${w}x${h}")
        
        val cropped = Bitmap.createBitmap(fullBitmap, left, top, w, h)
        
        // Save full screenshot for debugging
        val fullFile = File(debugDir, "full_screenshot.png")
        saveBitmapPng(fullBitmap, fullFile)
        Log.d(tag, "Saved full screenshot: ${fullFile.absolutePath}")
        
        return cropped
    }

    private fun clearTextViaDelete(et: UiObject2?) {
        if (et == null) {
            prepareEditTextFallback(clearText = true)
            return
        }
        et.click()
        Thread.sleep(200)
        val text = et.text ?: ""
        val committedLen = if (text.isNotEmpty() && text.uppercase() != "PINYIN WILL BE ENTERED HERE") text.length else 0
        repeat(committedLen + 15) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
            Thread.sleep(20)
        }
        Thread.sleep(200)
    }

    private fun pressKeyInternal(key: Char) {
        val keyChar = key.lowercaseChar()
        val targetKeyStr = when (baseKeyboardMode()) {
            "9键测试", "9-key" -> when (keyChar) {
                'a', 'b', 'c' -> "2"
                'd', 'e', 'f' -> "3"
                'g', 'h', 'i' -> "4"
                'j', 'k', 'l' -> "5"
                'm', 'n', 'o' -> "6"
                'p', 'q', 'r', 's' -> "7"
                't', 'u', 'v' -> "8"
                'w', 'x', 'y', 'z' -> "9"
                ' ' -> "0"
                else -> keyChar.toString()
            }
            "14键测试", "14-key" -> when (keyChar) {
                'q', 'w' -> "qw"
                'e', 'r' -> "er"
                't', 'y' -> "ty"
                'u', 'i' -> "ui"
                'o', 'p' -> "op"
                'a', 's' -> "as"
                'd', 'f' -> "df"
                'g', 'h' -> "gh"
                'j', 'k' -> "jk"
                'l'  -> "l"
                'z', 'x' -> "zx"
                'c', 'v' -> "cv"
                'b', 'n' -> "bn"
                'm'  -> "m"
                ' ' -> "space"
                else -> keyChar.toString()
            }
            else -> if (keyChar == ' ') "space" else keyChar.toString()
        }

        manualPositions[targetKeyStr]?.let {
            uiDevice.click(it.centerX(), it.centerY())
            Thread.sleep(50)
        } ?: Log.w(tag, "No calibration for key: $targetKeyStr")
    }

    private fun loadManualCalibrationData() {
        try {
            val mainAppContext = instrumentation.targetContext
            val dataDir = File(mainAppContext.filesDir, "InstrumentedTest")
            val prefs = instrumentation.targetContext.getSharedPreferences("KeyboardEvaluatorPrefs", Context.MODE_PRIVATE)
            val calFileName = prefs.getString("last_calibration_file", "")
            if (calFileName.isNullOrEmpty()) return

            val calibrationFile = File(dataDir, calFileName)
            if (!calibrationFile.exists()) return

            val json = JSONObject(calibrationFile.readText())
            val it = json.keys()
            while (it.hasNext()) {
                val keyStr = it.next()
                val point = json.getJSONObject(keyStr)
                val x = point.getDouble("x").toInt()
                val y = point.getDouble("y").toInt()
                if (keyStr == "candidate_area" && point.has("w") && point.has("h")) {
                    val w = point.getDouble("w").toInt()
                    val h = point.getDouble("h").toInt()
                    manualPositions[keyStr] = Rect(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
                } else {
                    val rect = Rect(x - 5, y - 5, x + 5, y + 5)
                    manualPositions[keyStr] = rect
                    addKeyboardModeMapping(keyStr, rect)
                }
            }
            Log.i(tag, "Calibration loaded: $calFileName")
        } catch (e: Exception) {
            Log.e(tag, "Load calibration failed", e)
        }
    }

    private fun addKeyboardModeMapping(calibKey: String, rect: Rect) {
        when (baseKeyboardMode()) {
            "9键测试", "9-key" -> when (calibKey) {
                "a", "b", "c" -> manualPositions["2"] = rect
                "d", "e", "f" -> manualPositions["3"] = rect
                "g", "h", "i" -> manualPositions["4"] = rect
                "j", "k", "l" -> manualPositions["5"] = rect
                "m", "n", "o" -> manualPositions["6"] = rect
                "p", "q", "r", "s" -> manualPositions["7"] = rect
                "t", "u", "v" -> manualPositions["8"] = rect
                "w", "x", "y", "z" -> manualPositions["9"] = rect
                " " -> manualPositions["0"] = rect
            }
            "14键测试", "14-key" -> when (calibKey) {
                "q", "w" -> manualPositions["qw"] = rect
                "e", "r" -> manualPositions["er"] = rect
                "t", "y" -> manualPositions["ty"] = rect
                "u", "i" -> manualPositions["ui"] = rect
                "o", "p" -> manualPositions["op"] = rect
                "a", "s" -> manualPositions["as"] = rect
                "d", "f" -> manualPositions["df"] = rect
                "g", "h" -> manualPositions["gh"] = rect
                "j", "k" -> manualPositions["jk"] = rect
                "z", "x" -> manualPositions["zx"] = rect
                "c", "v" -> manualPositions["cv"] = rect
                "b", "n" -> manualPositions["bn"] = rect
                " " -> manualPositions["space"] = rect
            }
        }
    }

    private fun sendPartialReport(result: EvaluationResult) {
        val prev = results.getOrNull(results.size - 2)
        val prevText = prev?.let { "[Prev]\n${formatResult(it)}" } ?: ""
        val currText = "[Current]\n${formatResult(result)}"
        activityRule.activity.runOnUiThread {
            val color = if (result.wasFound) Color.parseColor("#006400") else Color.RED
            activityRule.activity.setReportText(if (prevText.isNotEmpty()) "$prevText\n\n$currText" else currText, color)
        }
    }

    private fun formatResult(res: EvaluationResult): String {
        val status = if (res.wasFound) "SUCCESS" else "NOT_FOUND"
        val candidates = if (res.attempts.isEmpty()) "none" else res.attempts.take(8).joinToString(" | ")
        return String.format("%s | %s -> %s | Pos: %d | %s\nCandidates: [%s]", status, res.pinyinSequence, res.targetWord, res.selectedNo, res.message, candidates)
    }

    private fun buildFinalReport(): String {
        val totalCount = results.size
        val top1Count = results.count { it.wasFound && it.selectedNo == 1 }
        val firstLineCount = results.count { it.wasFound && it.selectedNo > 1 }
        val notFoundCount = results.count { !it.wasFound }
        val overallRate = if (totalCount > 0) (results.count { it.wasFound }.toDouble() / totalCount) * 100 else 0.0
        val imeId = Settings.Secure.getString(instrumentation.targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeName = imeId?.split('/')?.get(0) ?: "Unknown"

        val sb = StringBuilder("=========== FINAL EVALUATION REPORT ============\n")
        sb.append("IME: $imeName | Mode: $keyboardMode\n")
        sb.append("--------------------------------------------------------------------------------\n")
        results.forEachIndexed { idx, res ->
            val attemptsStr = res.attempts.take(10).joinToString(",")
            sb.append(String.format("%-4d | %-9s | %-15s | %-10s | %-3d | %s\n", idx + 1, if (res.wasFound) "SUCCESS" else "NOT_FOUND", res.pinyinSequence, res.targetWord, res.selectedNo, attemptsStr))
        }
        sb.append("--------------------------------------------------------------------------------\n")
        sb.append(String.format("  Success Rate:      %.2f%% (%d/%d)\n", overallRate, results.count { it.wasFound }, totalCount))
        sb.append(String.format("  Top 1 Rate:        %.2f%%\n", if (totalCount > 0) (top1Count.toDouble() / totalCount) * 100 else 0.0))
        sb.append(String.format("  first line Rate:   %.2f%%\n", if (totalCount > 0) (firstLineCount.toDouble() / totalCount) * 100 else 0.0))
        sb.append(String.format("  Not Found Rate:    %.2f%% (%d/%d)\n", if (totalCount > 0) (notFoundCount.toDouble() / totalCount) * 100 else 0.0, notFoundCount, totalCount))
        sb.append("================ END OF REPORT ================\n")
        return sb.toString()
    }

    private fun saveFailedTestDebugInfo(
        pinyin: String,
        target: String,
        screenshot: Bitmap,
        tokens: List<String>,
        rawOcrText: String
    ) {
        try {
            val timestamp = System.currentTimeMillis()
            val safeName = pinyin.replace(Regex("[^a-zA-Z0-9]"), "_")
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

            // 保存到 OUTPUT_DIR/failed_tests/ 子目录
            val failedDir = File(debugDir, "failed_tests").apply { mkdirs() }
            val screenshotFile = File(failedDir, "failed_${safeName}_${timestamp}_screenshot.png")
            saveBitmapPng(screenshot, screenshotFile)
            Log.i(tag, "  ✓ Screenshot: ${screenshotFile.name}")

            val ocrFile = File(failedDir, "failed_${safeName}_${timestamp}_ocr_report.txt")
            val ocrReport = StringBuilder()
            ocrReport.append("╔════════════════════════════════════════════════════════════╗\n")
            ocrReport.append("║          NOT_FOUND 失败测试调试信息                        ║\n")
            ocrReport.append("╚════════════════════════════════════════════════════════════╝\n\n")
            ocrReport.append("【基本信息】\n")
            ocrReport.append("  拼音 (pinyin):   $pinyin\n")
            ocrReport.append("  目标词 (target): $target\n")
            ocrReport.append("  时间 (time):     $dateStr\n")
            ocrReport.append("  键盘模式 (mode): $keyboardMode\n")
            ocrReport.append("  截图大小:        ${screenshot.width}x${screenshot.height} pixels\n\n")
            
            ocrReport.append("【OCR原始结果】\n")
            if (rawOcrText.isBlank()) {
                ocrReport.append("  ⚠️  OCR返回空字符串！\n")
            } else {
                ocrReport.append("  原始文本: '$rawOcrText'\n")
                ocrReport.append("  长度: ${rawOcrText.length}\n")
            }
            ocrReport.append("\n")
            
            ocrReport.append("【分割后的候选词】\n")
            ocrReport.append("  总数: ${tokens.size}\n")
            if (tokens.isEmpty()) {
                ocrReport.append("  ⚠️  没有识别到任何候选词！\n\n")
                ocrReport.append("【问题分析】\n")
                ocrReport.append("  • OCR分割失败 - 没有识别到任何词\n")
                ocrReport.append("  • 可能原因：\n")
                ocrReport.append("    1. 图像质量太低或对比度不足\n")
                ocrReport.append("    2. OCR模型加载失败\n")
                ocrReport.append("    3. 文字位置超出裁剪区域\n")
            } else {
                ocrReport.append("  候选词列表：\n")
                tokens.forEachIndexed { idx, token ->
                    val isMatch = token == target
                    val mark = if (isMatch) " ← ✓ 匹配目标词" else ""
                    ocrReport.append("    ${"%-2d".format(idx + 1)}. '$token'$mark\n")
                }
                ocrReport.append("\n【问题分析】\n")
                val foundTarget = tokens.any { it == target }
                if (foundTarget) {
                    ocrReport.append("  ⚠️  意外：目标词已找到，但判定为NOT_FOUND\n")
                } else {
                    ocrReport.append("  • 目标词未在候选词中找到\n")
                    ocrReport.append("  • 候选词与目标词的匹配情况：\n")
                    tokens.forEach { candidate ->
                        val similarity = calculateSimilarity(candidate, target)
                        val dist = levenshteinDistance(candidate, target)
                        ocrReport.append("    '$candidate' vs '$target': 相似度=${"%.1f".format(similarity*100)}% 编辑距离=$dist\n")
                    }
                }
            }
            
            ocrReport.append("\n【推荐检查项】\n")
            ocrReport.append("  1. 查看 screenshot.png 中的实际图像内容\n")
            ocrReport.append("  2. 检查截图的大小是否正确\n")
            ocrReport.append("  3. 检查键盘校准坐标是否准确\n")
            ocrReport.append("  4. 检查 ocr_blocks/ 目录下分割后的各块\n")
            
            writeTextFile(ocrFile, ocrReport.toString())
            Log.i(tag, "  ✓ OCR Report: ${ocrFile.name}")

            // 保存 ocr_blocks（仅失败时保存）
            val blocksDir = File(failedDir, "ocr_blocks_${safeName}").apply { mkdirs() }
            saveBitmapPng(screenshot, File(blocksDir, "00_full.png"))
            val rects = splitByVerticalWhitespaceAdaptive(screenshot)
            rects.forEachIndexed { i, rect ->
                try {
                    val w = (rect.right - rect.left).coerceAtLeast(1)
                    val h = (rect.bottom - rect.top).coerceAtLeast(1)
                    val block = Bitmap.createBitmap(screenshot, rect.left, rect.top, w, h)
                    saveBitmapPng(block, File(blocksDir, "block_${i}.png"))
                    block.recycle()
                } catch (e: Exception) { /* ignore */ }
            }
            Log.i(tag, "  ✓ OCR blocks (${rects.size}): ${blocksDir.name}/")

            Log.i(tag, "📊 NOT_FOUND 已保存 → ${failedDir.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(tag, "Save debug info failed", e)
        }
    }
    
    /**
     * 计算两个字符串的相似度 (0.0 - 1.0)
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val maxLen = maxOf(s1.length, s2.length)
        val dist = levenshteinDistance(s1, s2)
        return 1.0 - (dist.toDouble() / maxLen)
    }
    
    /**
     * 计算编辑距离（Levenshtein Distance）
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,     // 删除
                    dp[i][j - 1] + 1,     // 插入
                    dp[i - 1][j - 1] + cost  // 替换
                )
            }
        }
        
        return dp[s1.length][s2.length]
    }

    data class OcrResult(
        val rawText: String,
        val tokens: List<String>
    )

    /**
     * OCR 诊断测试：测试 OCR 引擎是否能识别基本文字
     * 这个测试不依赖于真实的键盘输入，而是直接测试 OCR 引擎的能力
     */
    @Test
    fun testOcrEngineDiagnostic() {
        Log.i(tag, "=".repeat(80))
        Log.i(tag, "OCR 引擎诊断测试")
        Log.i(tag, "=".repeat(80))
        
        try {
            // 生成一个简单的测试图片（白底黑字）
            val testText = "你好世界"
            Log.i(tag, "生成测试图片，文字: [$testText]")
            
            // 使用更大的尺寸和对比度生成测试图像
            val testBitmap = generateSimpleTextBitmap(testText, 600, 200)
            
            // 保存测试图片到 OUTPUT_DIR
            val diagDir = debugDir
            val testFile = File(diagDir, "test_simple_${SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())}.png")
            saveBitmapPng(testBitmap, testFile)
            Log.i(tag, "✓ 测试图片已保存: ${testFile.name}")
            Log.i(tag, "  • 图像尺寸: ${testBitmap.width}x${testBitmap.height}")
            Log.i(tag, "  • 文字内容: $testText")
            
            // 执行 OCR
            Log.i(tag, "执行 OCR 识别...")
            val result = runBlocking { ocrHelper.recognizeText(testBitmap) }
            
            // 记录结果
            Log.i(tag, "=".repeat(80))
            Log.i(tag, "OCR 识别结果:")
            Log.i(tag, "  Success: ${result.success}")
            Log.i(tag, "  识别文本: '${result.text}'")
            Log.i(tag, "  Tokens: ${result.tokens}")
            Log.i(tag, "  Details 数量: ${result.details.size}")
            result.details.forEach { detail ->
                Log.i(tag, "    - 文本='${detail.text}' 置信度=${String.format("%.2f%%", detail.confidence * 100)}")
            }
            if (result.error != null) {
                Log.e(tag, "  Error: ${result.error}")
            }
            Log.i(tag, "=".repeat(80))
            
            // 判断是否成功
            val recognized = result.text.isNotEmpty()
            if (recognized) {
                Log.i(tag, "✅ OCR 引擎能够识别文字！识别结果: '${result.text}'")
            } else {
                Log.e(tag, "❌ OCR 引擎无法识别文字，返回空字符串")
                Log.e(tag, "   这表明 OCR 引擎本身可能有问题，或者模型加载失败")
                Log.e(tag, "   请参考OcrHelper中的诊断日志了解详情")
            }
            
            testBitmap.recycle()
            
        } catch (e: Exception) {
            Log.e(tag, "OCR 诊断测试异常: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 生成简单的带文字位图（用于诊断测试）
     * 改进：更清晰的文字渲染，更高的对比度
     */
    private fun generateSimpleTextBitmap(text: String, width: Int, height: Int): Bitmap {
        // 验证参数
        if (width <= 0 || height <= 0) {
            Log.w(tag, "位图尺寸无效，使用默认值: width=$width, height=$height")
        }
        val validWidth = if (width > 0) width else 400
        val validHeight = if (height > 0) height else 150
        
        Log.d(tag, "生成位图: ${validWidth}x${validHeight}, 文字='$text'")
        
        // 使用ARGB_8888以获得最好的效果
        val bitmap = Bitmap.createBitmap(validWidth, validHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // 填充白色背景（最高对比度）
        canvas.drawColor(Color.WHITE)
        
        // 绘制文字 - 使用粗体获得更清晰的效果
        val paint = android.graphics.Paint().apply {
            color = Color.BLACK        // 完全黑色获得最高对比度
            textSize = (validHeight * 0.65f).coerceAtMost(80f)  // 字体大小不超过80
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, 
                android.graphics.Typeface.BOLD  // 使用粗体
            )
            textAlign = android.graphics.Paint.Align.CENTER
            strokeWidth = 0f
        }
        
        // 计算文字位置以确保完全在画布内
        val textBounds = Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)
        
        val x = validWidth / 2f
        val y = (validHeight - textBounds.top - textBounds.bottom) / 2f
        
        Log.d(tag, "文字绘制:")
        Log.d(tag, "  • 位置: (${x}, ${y})")
        Log.d(tag, "  • 字体大小: ${paint.textSize}")
        Log.d(tag, "  • 边界: ${textBounds}")
        
        canvas.drawText(text, x, y, paint)
        
        Log.d(tag, "位图生成完成，大小=${bitmap.width}x${bitmap.height}")
        
        return bitmap
    }

    data class EvaluationResult(
        val pinyinSequence: String,
        val targetWord: String,
        val contextText: String = "",
        var wasFound: Boolean = false,
        var selectedWord: String = "",
        var selectedNo: Int = 0,
        var attempts: MutableList<String> = mutableListOf(),
        var message: String = ""
    )
}

