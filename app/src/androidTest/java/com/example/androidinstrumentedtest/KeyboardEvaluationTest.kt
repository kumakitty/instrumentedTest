@file:Suppress("DEPRECATION")

package com.example.androidinstrumentedtest

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class KeyboardEvaluationTest {

    @get:Rule
    @Suppress("DEPRECATION")
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

    private var keyboardMode: String = "9键测试"
    private val manualPositions = mutableMapOf<String, Rect>()
    private lateinit var debugDir: File
    
    /**
     * 常见 OCR 误识别纠正映射表
     * 基于 ML Kit 中文 OCR 的常见错误进行手动纠正
     * 格式: 错误识别 -> 正确字符的列表（按概率排序）
     */
    private val ocrErrorCorrections = mapOf(
        "黑" to listOf("嘿"),      // 黑 vs 嘿 - 最常见的混淆
        "費" to listOf("费"),      // 繁体费 -> 简体费
        "鎖" to listOf("锁"),      // 繁体锁 -> 简体锁
    )
    
    private val recognizer by lazy {
        // ML Kit 16.0.0 版本默认支持简体中文
        // 如果 OCR 识别出繁体字，后续会通过 convertTraditionalToSimplified 转换
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Before
    fun setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        uiDevice = UiDevice.getInstance(instrumentation)
        @Suppress("DEPRECATION")
        uiDevice.setCompressedLayoutHeirarchy(false)
        testDataManager = TestDataManager(instrumentation.targetContext)

        // Put debug artifacts under Documents/InstrumentedTest/test_debug
        debugDir = testDataManager.getDebugDir()
        if (debugDir.exists()) {
            debugDir.deleteRecursively()
        }
        debugDir.mkdirs()
        Log.i(tag, "调试目录: ${debugDir.absolutePath}")

        val prefs = instrumentation.targetContext.getSharedPreferences("KeyboardEvaluatorPrefs", Context.MODE_PRIVATE)
        keyboardMode = prefs.getString("last_keyboard_type", "9键测试") ?: "9键测试"
        Log.i(tag, "检测到测试模式: $keyboardMode")

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
                        if (keyStr == "candidate_area" && point.has("w") && point.has("h")) {
                            val w = point.getDouble("w").toInt()
                            val h = point.getDouble("h").toInt()
                            manualPositions[keyStr] = Rect(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
                        } else {
                            manualPositions[keyStr] = Rect(x - 5, y - 5, x + 5, y + 5)
                            // 对于9键/14键模式，需要添加键盘模式转换映射
                            addKeyboardModeMapping(keyStr, Rect(x - 5, y - 5, x + 5, y + 5))
                        }
                    }
                    Log.i(tag, "已加载校准文件: $calFileName")
                }
            }
        } catch (e: Exception) { Log.e(tag, "加载校准数据失败", e) }
    }

    /**
     * 根据不同键盘模式添加按键映射
     * 校准文件可能是26键格式，需要转换为9键/14键格式
     */
    private fun addKeyboardModeMapping(calibKey: String, rect: Rect) {
        // 如果校准文件是26键格式，为9键和14键模式添���映射
        when (keyboardMode) {
            "9键测试" -> {
                // 26键到9键的映射
                when (calibKey) {
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
            }
            "14键测试" -> {
                // 26键到14键的映射
                when (calibKey) {
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
            // 26键模式直接使用原始按键标识
        }
    }

    private fun findSafeEditText(timeout: Long = 2000): UiObject2? {
        return uiDevice.wait(Until.findObject(By.res(editTextResId)), timeout)
    }

    private fun clearTextViaDelete(et: UiObject2?) {
        if (et == null) return
        et.click(); Thread.sleep(300)
        val text = et.text ?: ""
        val committedLen = if (text.isNotEmpty() && text.uppercase() != "PINYIN WILL BE ENTERED HERE") text.length else 0
        repeat(committedLen + 15) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL); Thread.sleep(20) }
        Thread.sleep(200)
    }

    @Test
    fun runKeyboardEvaluation() {
        val testData = testDataManager.readTestData { errorMessage ->
            Log.e(tag, errorMessage)
            activityRule.activity.runOnUiThread { activityRule.activity.setReportText(errorMessage, Color.RED) }
        }
        if (testData.isEmpty()) return

        val initialET = findSafeEditText(5000) ?: return
        initialET.click(); Thread.sleep(800)

        activityRule.activity.runOnUiThread { activityRule.activity.setReportText("评测开始 ($keyboardMode)...", Color.BLACK) }

        testData.forEachIndexed { index, dataPair ->
            val pinyin = dataPair.first
            val target = dataPair.second
            val result = EvaluationResult(pinyin, target)

            updateTestingStatus(pinyin, target)
            Log.i(tag, ">>> [${index + 1}/${testData.size}] 评测中: $pinyin -> $target")

            runCandidateAreaEvaluation(result, target, pinyin)

            results.add(result)
            sendPartialReport(result)
            Thread.sleep(600)
        }

        activityRule.activity.runOnUiThread {
            activityRule.activity.setReportText("测试完成", Color.parseColor("#006400"))
        }
    }

    private fun runCandidateAreaEvaluation(result: EvaluationResult, target: String, pinyin: String) {
        try {
            val et = findSafeEditText(2000) ?: run {
                result.message = "ERROR: 输入框不存在"
                return
            }
            clearTextViaDelete(et)

            // 1) 模拟点击按键输入 pinyin
            pinyin.forEach { char ->
                pressKeyInternal(char)
                Thread.sleep(90)
            }

            // 2) 输入完成后等待 1s，等待候选词出现
            Thread.sleep(1000)

            // 3) 只截取 candidate_area 区域
            val candidateBitmap = captureCandidateAreaBitmap() ?: run {
                result.message = "ERROR: candidate_area 未校准"
                return
            }

            // 单行候选词严格按空白切分，不固定列数。
            val ocrResult = runOcrSingleLineInOrder(candidateBitmap)
            val tokens = ocrResult.tokens
            result.attempts.addAll(tokens)

            // 5) 对比 target：第1位=Top1，其它位=first line，否则not found
            val hitIndex = tokens.indexOfFirst { matchCandidateWord(it, target) }
            when {
                hitIndex == 0 -> {
                    result.wasFound = true
                    result.selectedNo = 1
                    result.selectedWord = target.trim()
                    result.message = "Top1"
                }
                hitIndex > 0 -> {
                    result.wasFound = true
                    result.selectedNo = hitIndex + 1
                    result.selectedWord = target.trim()
                    result.message = "FIRST_LINE_MATCH"
                }
                else -> {
                    result.wasFound = false
                    result.selectedNo = 0
                    result.message = "NOT_FOUND"
                    saveFailedTestDebugInfo(pinyin, target, candidateBitmap, tokens, ocrResult.rawText)
                }
            }

            clearTextViaDelete(findSafeEditText(500))
        } catch (e: Exception) {
            Log.e(tag, "评测流程异常", e)
            result.message = "ERROR: ${e.message}"
        }
    }

    private fun captureCandidateAreaBitmap(): Bitmap? {
        val area = manualPositions["candidate_area"] ?: return null
        val fullShotFile = File(instrumentation.targetContext.cacheDir, "panel_full.png")
        if (fullShotFile.exists()) fullShotFile.delete()
        uiDevice.takeScreenshot(fullShotFile)
        val fullBitmap = BitmapFactory.decodeFile(fullShotFile.absolutePath) ?: return null

        val left = area.left.coerceAtLeast(0)
        val top = area.top.coerceAtLeast(0)
        val right = area.right.coerceAtMost(fullBitmap.width)
        val bottom = area.bottom.coerceAtMost(fullBitmap.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(fullBitmap, left, top, w, h)
    }

    /**
     * 改进的单行OCR分割策略 - 纯图像列分割方案
     *
     * 问题背景：
     * - 候选词区域是连续图像行，词间只有视觉空白（空像素），不是实际字符
     * - OCR原始文本根本没有空白符（候选词直接连接）
     * - 例子："消費者 修房子 小饭桌新发見" → 实际是最后两词无空白
     *
     * 解决方案：纯图像列分割（四步）
     * 1. 计算每列的暗像素密度（墨迹分布）
     * 2. 识别连续的墨迹块（候选词的位置）
     * 3. 用统计方法判断相邻块间隙是否为词界
     * 4. 逐块单独OCR识别
     */
    private fun runOcrSingleLineInOrder(bitmap: Bitmap): OcrResult {
        val latch = CountDownLatch(1)
        var rawText = ""

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                rawText = visionText.text.replace("\n", " ").trim()
                rawText = convertTraditionalToSimplified(rawText)  // 转换为简体中文
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
        latch.await(10, TimeUnit.SECONDS)

        Log.d(tag, "OCR rawText: '$rawText'")

        // 主要方案：使用图像分割获取候选词区域
        val segmentRects = splitByVerticalWhitespaceAdaptive(bitmap)
        Log.d(tag, "Image segments: ${segmentRects.size}")

        val allTokens = mutableListOf<String>()
        segmentRects.forEachIndexed { idx, rect ->
            val w = (rect.right - rect.left).coerceAtLeast(1)
            val h = (rect.bottom - rect.top).coerceAtLeast(1)
            
            // 验证分割矩形的合理性
            val minSegmentWidth = bitmap.width * 0.03f  // 最小宽度：图像宽度的3%
            if (w < minSegmentWidth) {
                Log.w(tag, "  segment[$idx] 宽度过小: ${w}px < ${minSegmentWidth}px，可能是分割错误")
            }
            if (h < 4) {
                Log.w(tag, "  segment[$idx] 高度过小: ${h}px，可能是分割错误")
            }
            
            // 调试信息：记录分割矩形
            Log.d(tag, "  segment[$idx] rect: (${rect.left},${rect.top}) - (${rect.right},${rect.bottom}) size: ${w}x${h}")
            
            val part = Bitmap.createBitmap(bitmap, rect.left, rect.top, w, h)
            val segmentRawText = runOcrRawText(part)
            
            Log.d(tag, "  segment[$idx] OCR raw: '$segmentRawText'")

            // 对每个图像分割的区域，按 OCR 原始文本中的空白符进一步分割
            // 这样可以处理像 "发型风险" 这样相连的多个候选词
            val tokens = segmentRawText.split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { normalizeOcrToken(it) }
                .filter { it.isNotEmpty() }
            
            if (tokens.isNotEmpty()) {
                allTokens.addAll(tokens)
                tokens.forEachIndexed { ti, token ->
                    Log.d(tag, "    → token[$ti] '$token'")
                }
            } else {
                Log.w(tag, "  segment[$idx] 无有效token产生")
            }
        }

        Log.d(tag, "Final tokens: ${allTokens.size}")
        allTokens.forEachIndexed { i, token ->
            Log.d(tag, "  [$i] '$token'")
        }

        return OcrResult(rawText = rawText, tokens = allTokens)
    }


    private fun runOcrRawText(bitmap: Bitmap): String {
        val latch = CountDownLatch(1)
        var raw = ""
        
        // 图像预处理：增强对比度以提高OCR识别精度
        val enhancedBitmap = enhanceImageContrast(bitmap)
        
        recognizer.process(InputImage.fromBitmap(enhancedBitmap, 0))
            .addOnSuccessListener {
                raw = it.text
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
        latch.await(10, TimeUnit.SECONDS)
        return raw
    }

    /**
     * 图像对比度增强 - 改善 OCR 识别精度
     * 通过直方图均衡化和亮度调整，增强文字与背景的对比度
     */
    private fun enhanceImageContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 计算直方图
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            histogram[lum]++
        }

        // 计算累积直方图（用于均衡化）
        val cumulative = IntArray(256)
        cumulative[0] = histogram[0]
        for (i in 1 until 256) {
            cumulative[i] = cumulative[i - 1] + histogram[i]
        }

        // 计算映射表
        val lut = IntArray(256)
        val pixelCount = width * height
        for (i in 0 until 256) {
            // 规范化到 0-255 范围
            lut[i] = ((cumulative[i].toLong() * 255) / pixelCount).toInt().coerceIn(0, 255)
        }

        // 应用直方图均衡化 + 额外的对比度提升
        val enhancedPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val alpha = Color.alpha(pixel)
            
            // 对每个通道应用映射
            val newR = lut[r].coerceIn(0, 255)
            val newG = lut[g].coerceIn(0, 255)
            val newB = lut[b].coerceIn(0, 255)
            
            // 额外提升对比度（将接近中值的像素推向极端）
            val contrast = 1.3f  // 对比度系数
            val newR2 = ((newR - 128) * contrast + 128).toInt().coerceIn(0, 255)
            val newG2 = ((newG - 128) * contrast + 128).toInt().coerceIn(0, 255)
            val newB2 = ((newB - 128) * contrast + 128).toInt().coerceIn(0, 255)
            
            enhancedPixels[i] = Color.argb(alpha, newR2, newG2, newB2)
        }

        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhanced.setPixels(enhancedPixels, 0, width, 0, 0, width, height)
        
        Log.d(tag, "图像对比度增强: ${width}x${height}")
        return enhanced
    }

    /**
     * 改进的自适应图像列分割算法
     *
     * 三步处理：
     * 1. 计算每列的暗像素数量（墨迹密度）
     * 2. 识别连续的"墨迹块"（候选词的起止列）
     * 3. 用自适应阈值判断块间间隙是否为词界
     * 4. 逐块单独OCR
     */
    private fun splitByVerticalWhitespaceAdaptive(bitmap: Bitmap): List<Rect> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 2 || height <= 2) return emptyList()

        // Step 1: 计算每列的暗像素数量
        val darkCount = IntArray(width)
        val darkThreshold = 235  // RGB > 235 为"亮像素"
        for (x in 0 until width) {
            var cnt = 0
            for (y in 0 until height) {
                val p = bitmap.getPixel(x, y)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum < darkThreshold) cnt++
            }
            darkCount[x] = cnt
        }

        Log.d(tag, "Column darkness distribution: width=$width, height=$height")

        // Step 2: 识别墨迹块（连续的暗列）
        val inkThreshold = (height * 0.05f).toInt().coerceAtLeast(1)  // 至少5%的列高要有墨迹
        Log.d(tag, "Ink threshold: $inkThreshold (height * 5%)")

        val inkRuns = mutableListOf<IntRange>()
        var start = -1
        for (x in 0 until width) {
            if (darkCount[x] >= inkThreshold) {
                if (start < 0) start = x
            } else if (start >= 0) {
                inkRuns.add(start..(x - 1))
                start = -1
            }
        }
        if (start >= 0) inkRuns.add(start..(width - 1))

        Log.d(tag, "Ink runs (continuous dark columns): ${inkRuns.size}")
        inkRuns.forEachIndexed { idx, range ->
            Log.d(tag, "  run[$idx]: cols ${range.first}-${range.last} (${range.last - range.first + 1} cols wide)")
        }

        if (inkRuns.size <= 1) {
            Log.d(tag, "Only 1 or 0 ink run(s), cannot split further")
            return emptyList()
        }

        // Step 3: 计算相邻块的间隙
        val gaps = mutableListOf<Int>()
        for (i in 0 until inkRuns.size - 1) {
            val gap = inkRuns[i + 1].first - inkRuns[i].last - 1
            gaps.add(gap.coerceAtLeast(0))
        }

        Log.d(tag, "Gaps between ink runs: $gaps (${gaps.size} gaps)")

        // Step 4: 用统计方法自动判断词界阈值
        val splitThreshold = calcAdaptiveSplitThreshold(gaps)
        Log.d(tag, "Adaptive split threshold: $splitThreshold")

        // Step 5: 按阈值分割
        val wordRuns = mutableListOf<IntRange>()
        var curRun = inkRuns[0]
        for (i in 1 until inkRuns.size) {
            val nextRun = inkRuns[i]
            val gap = nextRun.first - curRun.last - 1
            if (gap >= splitThreshold) {
                // 这是一个词界
                wordRuns.add(curRun)
                curRun = nextRun
                Log.d(tag, "  Split at gap=$gap >= $splitThreshold")
            } else {
                // 合并（同一个词的不同笔画）
                curRun = curRun.first..nextRun.last
                Log.d(tag, "  Merge at gap=$gap < $splitThreshold, now curRun=${curRun.first}-${curRun.last}")
            }
        }
        wordRuns.add(curRun)

        Log.d(tag, "Final word runs: ${wordRuns.size}")
        wordRuns.forEachIndexed { idx, run ->
            Log.d(tag, "  word[$idx]: cols ${run.first}-${run.last} (${run.last - run.first + 1} cols wide)")
        }

        // Step 6: 转换为 Rect，加边距
        val minRunWidth = (width * 0.015f).toInt().coerceAtLeast(6)
        val padX = 4
        val padY = 2
        return wordRuns
            .filter { (it.last - it.first + 1) >= minRunWidth }
            .map { run ->
                val left = (run.first - padX).coerceAtLeast(0)
                val right = (run.last + padX + 1).coerceAtMost(width)
                val top = (0 - padY).coerceAtLeast(0)
                val bottom = (height + padY).coerceAtMost(bitmap.height)
                Rect(left, top, right, bottom)
            }
    }

    /**
     * 自适应阈值计算 - 用统计方法找出"词界"间隙
     *
     * 核心思想：
     * - 同一个词的不同笔画间隙 → 小（通常 1-3 px）
     * - 不同词之间的间隙 → 大（通常 8-20 px）
     *
     * 多层启发式策略：
     * 1. 双峰聚类：寻找最大跳跃点
     * 2. 均值偏离：用均值作为参考
     * 3. 中位数分析：鲁棒性更好
     * 4. 均匀分布检测：所有间隙都相等时采用激进拆分
     * 5. 保守上限：确保不漏分
     */
    private fun calcAdaptiveSplitThreshold(gaps: List<Int>): Int {
        if (gaps.isEmpty()) return Int.MAX_VALUE
        if (gaps.size == 1) return (gaps[0] + 1).coerceAtLeast(8)

        val sorted = gaps.sorted()
        Log.d(tag, "Sorted gaps: $sorted")

        // 方法1：寻找最大跳跃点（双峰检测）
        var bestJump = Int.MIN_VALUE
        var bestIdx = -1
        for (i in 0 until sorted.size - 1) {
            val jump = sorted[i + 1] - sorted[i]
            Log.d(tag, "  Jump at idx $i: ${sorted[i]} → ${sorted[i + 1]} (jump=$jump)")
            if (jump > bestJump) {
                bestJump = jump
                bestIdx = i
            }
        }

        Log.d(tag, "Best jump: $bestJump at idx $bestIdx")

        // 如果有明显的双峰（跳跃 >= 3px），用跳跃点中值
        if (bestIdx >= 0 && bestJump >= 3) {
            val threshold = (sorted[bestIdx] + sorted[bestIdx + 1]) / 2
            Log.d(tag, "Strategy 1 (Dual-peak): threshold = (${sorted[bestIdx]} + ${sorted[bestIdx + 1]}) / 2 = $threshold")
            return threshold.coerceAtLeast(4)
        }

        // 方法2：用均值 + 标准差分析
        val mean = sorted.average()
        val variance = sorted.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        Log.d(tag, "Mean: $mean, StdDev: $stdDev")

        if (stdDev > mean * 0.2f) {  // 标准差显著（> 20%均值）
            val threshold = (mean + stdDev).toInt()
            Log.d(tag, "Strategy 2 (Mean+StdDev): threshold = $mean + $stdDev = $threshold")
            return threshold.coerceAtLeast(4)
        }

        // 方法3：用中位数 + 四分位数范围
        val median = sorted[sorted.size / 2]
        val q1Idx = sorted.size / 4
        val q3Idx = (sorted.size * 3) / 4
        val q1 = sorted[q1Idx.coerceAtMost(sorted.size - 1)]
        val q3 = sorted[q3Idx.coerceAtMost(sorted.size - 1)]
        val iqr = q3 - q1

        Log.d(tag, "Median: $median, Q1: $q1, Q3: $q3, IQR: $iqr")

        if (iqr > 1) {
            // IQR大于1说明有较大的变异性
            val threshold = (median + iqr).coerceAtLeast(q3)
            Log.d(tag, "Strategy 3 (Median+IQR): threshold = max($median + $iqr, $q3) = $threshold")
            return threshold.coerceAtLeast(4)
        }

        // 方法4：均匀分布检测 - 所有间隙都相等
        if (stdDev <= 0.01f * mean) {  // 标准差接近0（间隙均匀分布）
            // 假设候选词是均匀分布的，采用激进拆分
            // 用间隙的最小值 - 1 作为阈值（这样所有间隙都会被拆开）
            val threshold = sorted.first().coerceAtLeast(1)
            Log.d(tag, "Strategy 4 (Uniform): All gaps are equal, using aggressive split, threshold = ${threshold - 1}")
            return (threshold - 1).coerceAtLeast(1)
        }

        // 方法5：保守兜底，用最大间隙 + 1
        val threshold = sorted.last() + 1
        Log.d(tag, "Strategy 5 (Conservative): threshold = ${sorted.last()} + 1 = $threshold")
        return threshold.coerceAtLeast(4)
    }

    private fun normalizeOcrToken(raw: String): String {
        // 保留所有有效字符（中文、英文、数字）
        // 只移除：换行、制表符、空白、特殊符号、表情符等
        val cleaned = raw
            .replace(Regex("[\\r\\n\\t]+"), "")  // 移除换行和制表符
            .replace(Regex("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]"), "")  // 只保留字母、数字、汉字（CJK范围）
            .trim()
        
        if (cleaned.isEmpty()) {
            Log.d(tag, "normalizeOcrToken: '$raw' → (empty after normalization)")
            return ""
        }
        
        // 检测并纠正 OCR 误识别的字符
        val corrected = correctOcrErrors(cleaned)
        if (corrected != cleaned) {
            Log.w(tag, "OCR错误纠正: '$cleaned' → '$corrected'")
        }
        
        val simplified = convertTraditionalToSimplified(corrected)
        
        // 增强的调试日志 - 追踪每一步的转换
        Log.d(tag, "normalizeOcrToken详细: raw='$raw'")
        Log.d(tag, "  step1_clean: '$cleaned'")
        Log.d(tag, "  step2_correct: '$corrected'")
        Log.d(tag, "  step3_simplified: '$simplified'")
        
        // 检查是否有意外的字符变化
        if (simplified.contains("律") && !raw.contains("律")) {
            Log.e(tag, "⚠️ 警告: 检测到异常字符生成! raw='$raw' 不含律，但simplified='$simplified'含律")
        }
        
        return simplified
    }

    /**
     * OCR 误识别检测和纠正
     * 对于多字词汇，逐字符检查是否存在常见的OCR误识别，并进行纠正
     */
    private fun correctOcrErrors(text: String): String {
        var result = text
        
        // 逐个字符检查并纠正
        for (i in text.indices) {
            val char = text[i].toString()
            if (ocrErrorCorrections.containsKey(char)) {
                val candidates = ocrErrorCorrections[char] ?: continue
                
                // 对于在候选词列表中的候选项，使用第一个候选项作为纠正值
                // 在实际应用中，可以结合上下文和目标词来更智能地选择
                val correction = candidates.firstOrNull { it.length == 1 }
                if (correction != null) {
                    result = result.replaceFirst(char, correction)
                    Log.w(tag, "Corrected OCR error at position $i: '$char' → '$correction'")
                }
            }
        }
        
        return result
    }

    /**
     * 繁简体转换 - 使用 OpenCC4j 库处理 OCR 候选词
     * 将 OCR 输出的任何繁体字转换为简体
     * 这样确保 candidates 都是简体，便于与 target 匹配
     */
    private fun convertTraditionalToSimplified(text: String): String {
        return try {
            // 使用 OpenCC4j 的正确 API：toSimple()
            val simplified = ZhConverterUtil.toSimple(text)
            
            if (simplified != text) {
                Log.d(tag, "OpenCC 转换: '$text' → '$simplified'")
            }
            simplified
        } catch (e: Exception) {
            Log.w(tag, "OpenCC 转换失败: ${e.message}，返回原文本")
            text
        }
    }

    /**
     * 简体中文候选词精确匹配函数
     * 只支持精确匹配，不允许模糊匹配
     * 原因：模糊匹配（编辑距离）会导致错误的 SUCCESS
     * 例如："发泄" 不应该匹配 "发型"（相差1字符）
     */
    private fun matchCandidateWord(candidate: String, target: String): Boolean {
        val candTrimmed = candidate.trim()
        val targetTrimmed = target.trim()

        // 只支持精确匹配
        val isExactMatch = candTrimmed == targetTrimmed
        
        if (isExactMatch) {
            Log.d(tag, "Match [EXACT]: '$candTrimmed' == '$targetTrimmed'")
        } else {
            Log.d(tag, "NoMatch: '$candTrimmed' != '$targetTrimmed'")
        }
        
        return isExactMatch
    }

    private fun formatResult(res: EvaluationResult): String {
        val status = if (res.wasFound) "SUCCESS" else "NOT_FOUND"
        val candidates = if (res.attempts.isEmpty()) "无" else res.attempts.take(8).joinToString(" | ")
        return String.format("%s | %s -> %s | Pos: %d | %s\n候选词: [%s]", status, res.pinyinSequence, res.targetWord, res.selectedNo, res.message, candidates)
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

        // 在最终报告中添加调试目录信息
        if (debugDir.exists() && debugDir.listFiles()?.isNotEmpty() == true) {
            val debugSummary = StringBuilder("\n\n========== 失败测试调试信息 ==========\n")
            debugSummary.append("调试文件目录: ${debugDir.absolutePath}\n")
            debugSummary.append("失败测试数: ${results.count { !it.wasFound }}\n")
            val failedFiles = debugDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            debugSummary.append("调试文件总数: ${failedFiles.size}\n\n")
            failedFiles.forEach { file ->
                debugSummary.append("  - ${file.name}\n")
            }
            debugSummary.append("=====================================\n")
            Log.i(tag, debugSummary.toString())
        }

        if (reportFile != null && reportFile.exists()) {
            try {
                uiDevice.executeShellCommand("am start -a android.intent.action.VIEW -d \"file://${reportFile.absolutePath}\" -t \"text/plain\"")
            } catch (e: Exception) { Log.e(tag, "无法自动打开报告文件", e) }
        }

        activityRule.activity.runOnUiThread {
            activityRule.activity.setReportText("测试完成\n报告: ${reportFile?.absolutePath ?: "保存失败"}", Color.parseColor("#006400"))
        }
        Thread.sleep(3000)
    }

    private fun buildFinalReport(): String {
        val totalCount = results.size
        val top1Count = results.count { it.wasFound && it.selectedNo == 1 }
        val firstLineCount = results.count { it.wasFound && it.selectedNo > 1 }
        val notFoundCount = results.count { !it.wasFound }
        val overallRate = (results.count { it.wasFound }.toDouble() / totalCount) * 100
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
        sb.append(String.format("  Top 1 Rate:        %.2f%%\n", (top1Count.toDouble() / totalCount) * 100))
        sb.append(String.format("  first line Rate:   %.2f%%\n", (firstLineCount.toDouble() / totalCount) * 100))
        sb.append(String.format("  Not Found Rate:    %.2f%% (%d/%d)\n", (notFoundCount.toDouble() / totalCount) * 100, notFoundCount, totalCount))
        sb.append("================ END OF REPORT ================\n")
        return sb.toString()
    }

    private fun pressKeyInternal(key: Char) {
        val keyChar = key.lowercaseChar()
        val targetKeyStr = when (keyboardMode) {
            "9键测试" -> {
                when (keyChar) {
                    'a', 'b', 'c' -> "2"; 'd', 'e', 'f' -> "3"; 'g', 'h', 'i' -> "4"
                    'j', 'k', 'l' -> "5"; 'm', 'n', 'o' -> "6"; 'p', 'q', 'r', 's' -> "7"
                    't', 'u', 'v' -> "8"; 'w', 'x', 'y', 'z' -> "9"; ' ' -> "0"
                    else -> keyChar.toString()
                }
            }
            "14键测试" -> {
                when (keyChar) {
                    'q', 'w' -> "qw"; 'e', 'r' -> "er"; 't', 'y' -> "ty"; 'u', 'i' -> "ui"; 'o', 'p' -> "op"
                    'a', 's' -> "as"; 'd', 'f' -> "df"; 'g', 'h' -> "gh"; 'j', 'k' -> "jk"
                    'z', 'x' -> "zx"; 'c', 'v' -> "cv"; 'b', 'n' -> "bn"
                    ' ' -> "space"
                    else -> keyChar.toString()
                }
            }
            else -> { // 26键测试
                if (keyChar == ' ') "space" else keyChar.toString()
            }
        }
        manualPositions[targetKeyStr]?.let {
            uiDevice.click(it.centerX(), it.centerY())
            Thread.sleep(50)
        } ?: Log.e(tag, "未找到按键 '$targetKeyStr' 的校准坐标点！")
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
            val safeFileName = pinyin.replace(Regex("[^a-zA-Z0-9]"), "_")

            // 保存截图
            val screenshotFile = File(debugDir, "failed_${safeFileName}_${timestamp}.png")
            screenshotFile.outputStream().use { out ->
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // 保存OCR识别结果
            val ocrFile = File(debugDir, "failed_${safeFileName}_${timestamp}_ocr.txt")
            val ocrReport = StringBuilder()
            ocrReport.append("拼音: $pinyin\n")
            ocrReport.append("目标词: $target\n")
            ocrReport.append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))}\n")
            ocrReport.append("键盘模式: $keyboardMode\n")

            ocrReport.append("\n========== OCR原始文本 ==========" + "\n")
            ocrReport.append(if (rawOcrText.isBlank()) "<empty>\n" else "$rawOcrText\n")

            ocrReport.append("\n========== OCR原始文本字符解析（含码点）==========" + "\n")
            if (rawOcrText.isBlank()) {
                ocrReport.append("<empty>\n")
            } else {
                rawOcrText.forEachIndexed { idx, ch ->
                    val code = ch.code
                    val desc = when {
                        ch == ' ' -> "[SPACE U+0020]"
                        ch.isWhitespace() -> "[${ch.category} U+${code.toString(16).uppercase().padStart(4, '0')}]"
                        else -> "[$ch]"
                    }
                    ocrReport.append("$idx: $desc\n")
                }
            }

            ocrReport.append("\n========== 按 \\s+ 切分后的OCR结果 (${tokens.size}个) ==========" + "\n")
            if (tokens.isEmpty()) {
                ocrReport.append("<分割失败，0个token>\n")
            } else {
                tokens.forEachIndexed { idx, token ->
                    val mark = if (token == target) " ← [目标词]" else ""
                    ocrReport.append("${idx + 1}. $token$mark\n")
                }
            }

            ocrReport.append("\n========== 调试日志（从 logcat 中提取相关日志） ==========" + "\n")
            ocrReport.append("见设备 logcat: KeyboardEvaluator 标签\n")

            ocrFile.writeText(ocrReport.toString())

            Log.i(tag, "已保存失败测试调试信息:")
            Log.i(tag, "  截图: ${screenshotFile.absolutePath}")
            Log.i(tag, "  OCR: ${ocrFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "保存失败测试调试信息时出错", e)
        }
    }

    data class OcrResult(
        val rawText: String,
        val tokens: List<String>
    )


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
