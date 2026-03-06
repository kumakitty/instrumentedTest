package com.example.androidinstrumentedtest

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var reportTextView: TextView
    private lateinit var reportScrollView: ScrollScrollView
    private lateinit var importDataButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var startTestButton: Button
    private lateinit var evaluationEditText: EditText
    
    private var calibrationView: CalibrationCircleView? = null
    private var isCalibrating = false
    private var isVerifying = false
    private var calibrationStep = 0
    
    // 校准序列
    private val calibrationKeys = listOf(
        "2", "3", "4", "5", "6", "7", "8", "9", "0", 
        "dropdown_btn", 
        "candidate_area"
    )
    private val calibrationPrompts = listOf(
        "请将红圈拖到 'ABC' (2键) 中心并确认",
        "请将红圈拖到 'DEF' (3键) 中心并确认",
        "请将红圈拖到 'GHI' (4键) 中心并确认",
        "请将红圈拖到 'JKL' (5键) 中心并确认",
        "请将红圈拖到 'MNO' (6键) 中心并确认",
        "请将红圈拖到 'PQRS' (7键) 中心并确认",
        "请将红圈拖到 'TUV' (8键) 中心并确认",
        "请将红圈拖到 'WXYZ' (9键) 中心并确认",
        "请将红圈拖到 '空格' 键中心并确认",
        "请将红圈拖到 '候选词下拉按钮' 中心并确认",
        "请使用红框圈出 '前5位候选词' 所在的区域 (可拖动右下角缩放) 并确认"
    )

    private val calibrationPointsJson = JSONObject()
    private var keyboardSnapshot: Bitmap? = null
    private var keyboardSnapshotTop = 0

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    companion object {
        const val EXTRA_IS_TEST_MODE = "is_test_mode"
        private const val PICK_FILE_REQUEST_CODE = 1001
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1002
        private const val TAG = "Calibration"
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        reportTextView = findViewById(R.id.report_text_view)
        reportScrollView = findViewById(R.id.report_scroll_view)
        importDataButton = findViewById(R.id.import_data_button)
        calibrateButton = findViewById(R.id.calibrate_button)
        startTestButton = findViewById(R.id.start_test_button)
        evaluationEditText = findViewById(R.id.evaluation_edit_text)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 核心修复：启动时从本地加载校准数据
        loadCalibrationData()

        if (intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)) {
            importDataButton.visibility = View.GONE
            calibrateButton.visibility = View.GONE
            startTestButton.visibility = View.GONE
            findViewById<View>(R.id.test_type_radio_group).visibility = View.GONE
        } else {
            importDataButton.setOnClickListener { openFilePicker() }
            calibrateButton.setOnClickListener { requestScreenCapturePermission() }
            startTestButton.setOnClickListener { runInstrumentationTest() }
        }
    }

    private fun runInstrumentationTest() {
        val cmd = "am instrument -w com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner"
        Log.i(TAG, "正在启动测试: $cmd")
        try {
            // 注意：直接在普通 App 中执行 am instrument 需要 root 权限或特定的系统签名。
            // 这里我们尝试通过 Runtime 执行，但在非 root 设备上通常会失败。
            // 更好的做法是提示用户在 PC 端运行该命令，或者通过 Shell 脚本触发。
            val process = Runtime.getRuntime().exec(cmd)
            Thread {
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "[Test Output] $line")
                }
            }.start()
            setReportText("🚀 已尝试启动测试。请查看 Logcat 获取详细输出。", Color.BLUE)
        } catch (e: Exception) {
            Log.e(TAG, "启动测试失败", e)
            setReportText("❌ 启动失败: ${e.message}\n请尝试在 ADB 中手动运行该命令。", Color.RED)
        }
    }

    private fun loadCalibrationData() {
        try {
            val dir = File(filesDir, "InstrumentedTest")
            val file = File(dir, "calibration.json")
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    calibrationPointsJson.put(key, json.get(key))
                }
                Log.i(TAG, "已加载本地校准配置")
            }
        } catch (e: Exception) { Log.e(TAG, "加载配置失败", e) }
    }

    /**
     * 接口：获取面板上的候选词。程序会严格裁剪用户在校准时圈出的矩形区域，避免干扰。
     */
    fun getAllOcrTokens(screenshot: Bitmap, onResult: (List<String>) -> Unit) {
        try {
            val areaJson = calibrationPointsJson.optJSONObject("candidate_area")
            val imageToProcess = if (areaJson != null) {
                val cx = areaJson.getInt("x"); val cy = areaJson.getInt("y")
                val w = areaJson.getInt("w"); val h = areaJson.getInt("h")
                val left = Math.max(0, cx - w / 2)
                val top = Math.max(0, cy - h / 2)
                val cropW = Math.min(w, screenshot.width - left)
                val cropH = Math.min(h, screenshot.height - top)
                Log.i(TAG, "[OCR] 裁剪区域: ($left, $top, $cropW, $cropH)")
                Bitmap.createBitmap(screenshot, left, top, cropW, cropH)
            } else {
                Log.w(TAG, "[OCR] 未发现裁剪区，将识别全屏 (可能被干扰)")
                screenshot
            }

            val image = InputImage.fromBitmap(imageToProcess, 0)
            recognizer.process(image).addOnSuccessListener { visionText ->
                val allTokens = mutableListOf<String>()
                // 按照从上到下、从左到右的顺序排列 OCR 文本块
                val sortedBlocks = visionText.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                sortedBlocks.forEach { block ->
                    val parts = block.text.split(Regex("[\\s\\n]+")).filter { it.isNotBlank() }
                    allTokens.addAll(parts)
                }
                Log.i(TAG, "[OCR-Result] 识别到的词条: $allTokens")
                onResult(allTokens)
            }.addOnFailureListener { onResult(emptyList()) }
        } catch (e: Exception) { onResult(emptyList()) }
    }

    private fun requestScreenCapturePermission() {
        startMediaProjectionService()
        startActivityForResult(projectionManager!!.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            try {
                mediaProjection = projectionManager!!.getMediaProjection(resultCode, data)
                showStageGuide(1)
            } catch (e: SecurityException) {
                Log.e(TAG, "MediaProjection Error: ${e.message}")
                stopMediaProjectionService()
            }
        } else if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            data.data?.let { uri -> copyDataToAppDirectory(uri) }
        }
    }

    private fun startMediaProjectionService() {
        val intent = Intent(this, MediaProjectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopMediaProjectionService() {
        stopService(Intent(this, MediaProjectionService::class.java))
    }

    private fun showStageGuide(stage: Int) {
        val title = if (stage == 1) "阶段1：主键盘校准" else "阶段2：全屏面板校准"
        val msg = if (stage == 1) "请保持9键状态，点击捕获截图。" else "请手动展开全屏面板，点击捕获截图。"
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("立即捕获") { _, _ -> captureKeyboardAndStart(stage) }
            .setNegativeButton("取消") { _, _ -> stopProjection() }.setCancelable(false).show()
    }

    private fun captureKeyboardAndStart(stage: Int) {
        evaluationEditText.setText(if (stage == 2) "a" else "")
        evaluationEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(evaluationEditText, InputMethodManager.SHOW_FORCED)
        val rootView = window.decorView
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var captured = false
            override fun onGlobalLayout() {
                val rect = Rect(); rootView.getWindowVisibleDisplayFrame(rect)
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val realHeight = metrics.heightPixels
                if ((realHeight - rect.bottom) > realHeight * 0.1) {
                    if (!captured) {
                        captured = true; rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        evaluationEditText.postDelayed({
                            takeScreenshot { fullBitmap ->
                                runOnUiThread {
                                    val finalRect = Rect(); rootView.getWindowVisibleDisplayFrame(finalRect)
                                    val top = finalRect.bottom
                                    val windowBottom = rootView.height 
                                    val cropHeight = Math.max(0, windowBottom - top)
                                    
                                    keyboardSnapshot = if (cropHeight > 0) {
                                        Bitmap.createBitmap(fullBitmap, 0, top, fullBitmap.width, cropHeight)
                                    } else { fullBitmap }
                                    keyboardSnapshotTop = top
                                    imm.hideSoftInputFromWindow(evaluationEditText.windowToken, 0)
                                    if (stage == 1) startCalibration() else resumeCalibrationAfterSnapshot()
                                }
                            }
                        }, 5000)
                    }
                }
            }
        })
    }

    @SuppressLint("WrongConstant")
    private fun takeScreenshot(callback: (Bitmap) -> Unit) {
        val projection = mediaProjection ?: return
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels; val d = metrics.densityDpi

        val localImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val snapshotThread = HandlerThread("SnapshotThread").apply { start() }
        val handler = Handler(snapshotThread.looper)
        val localVirtualDisplay = projection.createVirtualDisplay("Snapshot", w, h, d, 16, localImageReader.surface, null, handler)
        localImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                reader.setOnImageAvailableListener(null, null)
                val planes = image.planes; val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w
                val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer); image.close()
                localVirtualDisplay?.release(); localImageReader.close(); snapshotThread.quitSafely()
                callback(Bitmap.createBitmap(bitmap, 0, 0, w, h))
            }
        }, handler)
    }

    private fun startCalibration() {
        isCalibrating = true; calibrationStep = 0
        if (calibrationView == null) { calibrationView = CalibrationCircleView(this); addContentView(calibrationView, ViewGroup.LayoutParams(-1, -1)) }
        calibrationView?.visibility = View.VISIBLE; resetIndicatorPosition(); updatePrompt()
    }

    private fun resumeCalibrationAfterSnapshot() {
        calibrationView?.visibility = View.VISIBLE
        resetIndicatorPosition(); updatePrompt()
    }

    private fun resetIndicatorPosition() {
        keyboardSnapshot?.let { calibrationView?.setInitialPosition(it.width / 2f, keyboardSnapshotTop + it.height / 2f) }
    }

    private fun updatePrompt() { setReportText("校准中 (${calibrationStep + 1}/${calibrationKeys.size}):\n${calibrationPrompts[calibrationStep]}", Color.BLUE) }

    private fun onCoordinateConfirmed(x: Float, y: Float) {
        val key = calibrationKeys[calibrationStep]
        val point = JSONObject().apply { put("x", x.toDouble()); put("y", y.toDouble()) }
        if (key == "candidate_area") { calibrationView?.let { point.put("w", it.rectW.toDouble()); point.put("h", it.rectH.toDouble()) } }
        calibrationPointsJson.put(key, point)
        calibrationStep++
        if (calibrationStep == 10) { calibrationView?.visibility = View.GONE; showStageGuide(2) } 
        else if (calibrationStep < calibrationKeys.size) { resetIndicatorPosition(); updatePrompt() } 
        else { finishCalibration() }
    }

    private fun finishCalibration() {
        isCalibrating = false; calibrationView?.visibility = View.GONE
        try {
            val dir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
            val file = File(dir, "calibration.json"); FileOutputStream(file).use { it.write(calibrationPointsJson.toString().toByteArray()) }
            Log.i(TAG, "[校准文件] 校准流程完成，坐标已自动保存: ${file.absolutePath}")
            setReportText("✅ 校准成功！区域已锁定。", Color.parseColor("#006400"))
        } catch (e: Exception) { Log.e(TAG, "Save Error", e) }
        stopProjection()
    }

    private fun stopProjection() { mediaProjection?.stop(); mediaProjection = null; stopMediaProjectionService() }
    private fun updateStatus(msg: String, color: Int) { runOnUiThread { setReportText(msg, color) } }

    private inner class CalibrationCircleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 0, 0); style = Paint.Style.FILL }
        private var circleX = 0f; private var circleY = 0f
        var rectW = 600f; var rectH = 120f
        private var isDragging = false; private var isResizing = false

        fun setInitialPosition(sx: Float, sy: Float) { circleX = sx; circleY = sy; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val loc = IntArray(2); getLocationOnScreen(loc)
            if (isCalibrating) { keyboardSnapshot?.let { canvas.drawBitmap(it, -loc[0].toFloat(), (keyboardSnapshotTop - loc[1]).toFloat(), null) } }
            val dx = circleX - loc[0]; val dy = circleY - loc[1]
            if (calibrationStep == 10) {
                val left = dx - rectW/2; val top = dy - rectH/2; val right = dx + rectW/2; val bottom = dy + rectH/2
                canvas.drawRect(left, top, right, bottom, fillPaint); canvas.drawRect(left, top, right, bottom, paint)
                canvas.drawCircle(right, bottom, 25f, paint); canvas.drawCircle(right, bottom, 15f, fillPaint)
            } else { canvas.drawCircle(dx, dy, 70f, fillPaint); canvas.drawCircle(dx, dy, 70f, paint) }
            canvas.drawLine(dx-35, dy, dx+35, dy, paint); canvas.drawLine(dx, dy-35, dx, dy+35, paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isCalibrating) return false
            val sx = e.rawX; val sy = e.rawY
            val loc = IntArray(2); getLocationOnScreen(loc)
            val vx = sx - loc[0]; val vy = sy - loc[1]
            val cx = circleX - loc[0]; val cy = circleY - loc[1]
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (calibrationStep == 10) {
                        val hX = cx + rectW/2; val hY = cy + rectH/2
                        if (sqrt((vx-hX).pow(2) + (vy-hY).pow(2)) < 60) { isResizing = true; return true }
                    }
                    isDragging = true; circleX = sx; circleY = sy; invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) { rectW = Math.max(100f, (vx - cx) * 2); rectH = Math.max(60f, (vy - cy) * 2) }
                    else if (isDragging) { circleX = sx; circleY = sy }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> { isDragging = false; isResizing = false; performClick(); showConfirmDialog(circleX, circleY) }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun showConfirmDialog(sx: Float, sy: Float) {
            val label = if (calibrationStep == 9) "下拉按钮" else if (calibrationStep == 10) "候选词区域" else calibrationKeys[calibrationStep]
            AlertDialog.Builder(context).setTitle("确认").setMessage("对准 '$label' 了吗？").setPositiveButton("确定") { _, _ -> onCoordinateConfirmed(sx, sy) }.setNegativeButton("微调", null).show()
        }
    }
    fun setReportText(text: String, color: Int) { runOnUiThread { reportTextView.text = text; reportTextView.setTextColor(color); reportScrollView.post { reportScrollView.fullScroll(View.FOCUS_DOWN) } } }
    private fun openFilePicker() { 
        Log.i(TAG, "正在打开文件选择器...")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/*" }, PICK_FILE_REQUEST_CODE) 
    }
    private fun copyDataToAppDirectory(uri: Uri) { 
        try { 
            Log.i(TAG, "[测试数据] 开始从 URI 导入: $uri")
            val dir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
            val targetFile = File(dir, "test_data.txt")
            
            contentResolver.openInputStream(uri)?.use { i -> 
                FileOutputStream(targetFile).use { o -> i.copyTo(o) } 
            }
            
            val fileSize = targetFile.length()
            Log.i(TAG, "[测试数据] 导入成功。路径: ${targetFile.absolutePath}, 大小: $fileSize bytes")
            
            val preview = targetFile.bufferedReader().useLines { lines ->
                lines.take(3).joinToString("\n")
            }
            Log.i(TAG, "[测试数据] 内容预览 (前3行):\n$preview")

            if (fileSize == 0L) {
                setReportText("⚠️ 导入的测试数据文件为空！", Color.RED)
            } else {
                setReportText("✅ 测试数据导入成功！\n大小: $fileSize 字节\n预览:\n$preview", Color.parseColor("#006400")) 
            }
        } catch (e: Exception) { 
            Log.e(TAG, "[测试数据] 导入失败", e)
            setReportText("❌ 失败: ${e.message}", Color.RED) 
        } 
    }
}

class ScrollScrollView(context: Context, attrs: android.util.AttributeSet? = null) : ScrollView(context, attrs)
