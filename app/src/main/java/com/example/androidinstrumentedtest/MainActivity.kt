package com.example.androidinstrumentedtest

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.provider.Settings
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
    private lateinit var keyboardTypeSpinner: Spinner
    private lateinit var calibrationFileSpinner: Spinner
    private lateinit var currentTestDataText: TextView
    private lateinit var evaluationEditText: EditText
    
    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "KeyboardEvaluatorPrefs"
    private val KEY_KEYBOARD_TYPE = "last_keyboard_type"
    private val KEY_CALIBRATION_FILE = "last_calibration_file"
    
    private var calibrationView: CalibrationCircleView? = null
    private var isCalibrating = false
    private var calibrationStep = 0
    
    private val keyboardOptions = listOf("9键测试", "26键测试", "14键测试", "联想测试")
    
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
        private const val PICK_DATA_REQUEST_CODE = 1001
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1002
        private const val TAG = "Calibration"
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        reportTextView = findViewById(R.id.report_text_view)
        reportScrollView = findViewById(R.id.report_scroll_view)
        importDataButton = findViewById(R.id.import_data_button)
        calibrateButton = findViewById(R.id.calibrate_button)
        startTestButton = findViewById(R.id.start_test_button)
        keyboardTypeSpinner = findViewById(R.id.keyboard_type_spinner)
        calibrationFileSpinner = findViewById(R.id.calibration_file_spinner)
        currentTestDataText = findViewById(R.id.current_test_data_text)
        evaluationEditText = findViewById(R.id.evaluation_edit_text)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupKeyboardSpinner()
        refreshCalibrationList()
        refreshFileInfo()

        if (intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)) {
            findViewById<View>(R.id.start_test_button).visibility = View.GONE
        } else {
            importDataButton.setOnClickListener { openFilePicker(PICK_DATA_REQUEST_CODE) }
            calibrateButton.setOnClickListener { requestScreenCapturePermission() }
            startTestButton.setOnClickListener { runInstrumentationTest() }
        }
    }

    private fun setupKeyboardSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, keyboardOptions)
        keyboardTypeSpinner.adapter = adapter
        
        // 恢复上次选择的键盘项
        val lastKb = prefs.getString(KEY_KEYBOARD_TYPE, keyboardOptions[0])
        val pos = keyboardOptions.indexOf(lastKb)
        if (pos != -1) keyboardTypeSpinner.setSelection(pos)

        keyboardTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                val selected = keyboardOptions[p2]
                prefs.edit().putString(KEY_KEYBOARD_TYPE, selected).apply()
                autoSelectMatchingCalibration()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun refreshCalibrationList() {
        val dir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
        val calFiles = dir.listFiles { _, name -> name.startsWith("cal_") && name.endsWith(".json") }
        val fileNames = calFiles?.map { it.name }?.toMutableList() ?: mutableListOf()
        
        if (fileNames.isEmpty()) {
            fileNames.add("未找到校准文件")
        } else {
            // 排序，保证稳定性
            fileNames.sort()
        }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fileNames)
        calibrationFileSpinner.adapter = adapter
        
        // 尝试恢复上次选择的校准文件
        val lastCal = prefs.getString(KEY_CALIBRATION_FILE, "")
        val pos = fileNames.indexOf(lastCal)
        if (pos != -1) {
            calibrationFileSpinner.setSelection(pos)
        } else {
            autoSelectMatchingCalibration()
        }

        calibrationFileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = parent?.getItemAtPosition(position).toString()
                if (selected != "未找到校准文件") {
                    prefs.edit().putString(KEY_CALIBRATION_FILE, selected).apply()
                    loadCalibrationData(selected)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun autoSelectMatchingCalibration() {
        val imeName = getCurrentImeName()
        val kbType = keyboardTypeSpinner.selectedItem.toString().replace(" ", "_")
        val expectedPrefix = "cal_${imeName}_${kbType}"
        
        val adapter = calibrationFileSpinner.adapter
        for (i in 0 until adapter.count) {
            val fileName = adapter.getItem(i).toString()
            if (fileName.startsWith(expectedPrefix)) {
                calibrationFileSpinner.setSelection(i)
                break
            }
        }
    }

    private fun refreshFileInfo() {
        val configDir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
        val nameRefFile = File(configDir, "last_test_data_name.txt")
        val originalName = if (nameRefFile.exists()) nameRefFile.readText() else "test_data.txt"
        val testDataFile = File(configDir, "test_data.txt")
        if (testDataFile.exists()) {
            val preview = try {
                testDataFile.bufferedReader().useLines { lines -> lines.take(3).joinToString("\n") }
            } catch (e: Exception) { "" }
            currentTestDataText.text = "当前测试文件: $originalName\n数据预览:\n$preview"
        } else {
            currentTestDataText.text = "当前测试文件: 无"
        }
    }

    private fun getCurrentImeName(): String {
        val imeId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return imeId?.split('/')?.get(0)?.split('.')?.last() ?: "UnknownIME"
    }

    private fun loadCalibrationData(fileName: String) {
        try {
            val file = File(File(filesDir, "InstrumentedTest"), fileName)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val keysToClear = mutableListOf<String>()
                val itClear = calibrationPointsJson.keys()
                while (itClear.hasNext()) { keysToClear.add(itClear.next()) }
                keysToClear.forEach { calibrationPointsJson.remove(it) }
                
                val itNew = json.keys()
                while (itNew.hasNext()) {
                    val key = itNew.next()
                    calibrationPointsJson.put(key, json.get(key))
                }
                Log.i(TAG, "[校准] 已加载: $fileName")
                setReportText("✅ 已加载校准: $fileName", Color.parseColor("#006400"))
            }
        } catch (e: Exception) { Log.e(TAG, "加载配置失败", e) }
    }

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
                Bitmap.createBitmap(screenshot, left, top, cropW, cropH)
            } else {
                screenshot
            }

            val image = InputImage.fromBitmap(imageToProcess, 0)
            recognizer.process(image).addOnSuccessListener { visionText ->
                val allTokens = mutableListOf<String>()
                val sortedBlocks = visionText.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                sortedBlocks.forEach { block ->
                    val parts = block.text.split(Regex("[\\s\\n]+")).filter { it.isNotBlank() }
                    allTokens.addAll(parts)
                }
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
        if (resultCode != Activity.RESULT_OK || data == null) return
        
        when (requestCode) {
            SCREEN_CAPTURE_REQUEST_CODE -> {
                try {
                    mediaProjection = projectionManager!!.getMediaProjection(resultCode, data)
                    showStageGuide(1)
                } catch (e: Exception) { stopMediaProjectionService() }
            }
            PICK_DATA_REQUEST_CODE -> {
                data.data?.let { uri -> 
                    val realName = getFileNameFromUri(uri)
                    File(File(filesDir, "InstrumentedTest"), "last_test_data_name.txt").writeText(realName)
                    copyFileToInternal(uri, "test_data.txt", "[测试数据]") 
                }
            }
        }
    }

    @SuppressLint("Range")
    private fun getFileNameFromUri(uri: Uri): String {
        var name = "unknown_file"
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        } else if (uri.scheme == "file") {
            name = File(uri.path!!).name
        }
        return name
    }

    private fun copyFileToInternal(uri: Uri, targetFileName: String, logLabel: String) {
        try {
            val dir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
            val targetFile = File(dir, targetFileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            refreshFileInfo()
            setReportText("✅ $logLabel 导入成功！\n文件: $targetFileName", Color.parseColor("#006400"))
        } catch (e: Exception) { setReportText("❌ $logLabel 失败: ${e.message}", Color.RED) }
    }

    private fun finishCalibration() {
        isCalibrating = false; calibrationView?.visibility = View.GONE
        try {
            val imeName = getCurrentImeName()
            val kbType = keyboardTypeSpinner.selectedItem.toString().replace(" ", "_")
            val fileName = "cal_${imeName}_${kbType}.json"
            val file = File(File(filesDir, "InstrumentedTest"), fileName)
            
            FileOutputStream(file).use { it.write(calibrationPointsJson.toString().toByteArray()) }
            Log.i(TAG, "[校准文件] 坐标已自动保存: $fileName")
            refreshCalibrationList()
            setReportText("✅ 校准成功！文件已保存: $fileName", Color.parseColor("#006400"))
        } catch (e: Exception) { Log.e(TAG, "Save Error", e) }
        stopProjection()
    }

    private fun runInstrumentationTest() {
        val cmd = "am instrument -w com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner"
        try {
            Runtime.getRuntime().exec(cmd)
            setReportText("🚀 已尝试启动测试。请查看 Logcat。", Color.BLUE)
        } catch (e: Exception) { setReportText("❌ 启动失败: ${e.message}", Color.RED) }
    }

    private fun startMediaProjectionService() {
        val intent = Intent(this, MediaProjectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopMediaProjectionService() { stopService(Intent(this, MediaProjectionService::class.java)) }
    private fun stopProjection() { mediaProjection?.stop(); mediaProjection = null; stopMediaProjectionService() }

    private fun showStageGuide(stage: Int) {
        val title = if (stage == 1) "阶段1：主键盘校准" else "阶段2：全屏面板校准"
        val msg = if (stage == 1) "请保持键盘开启状态，点击捕获截图。" else "请手动展开全屏面板，点击捕获截图。"
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("立即捕获") { _, _ -> captureKeyboardAndStart(stage) }
            .setNegativeButton("取消") { _, _ -> stopProjection() }.setCancelable(false).show()
    }

    private fun captureKeyboardAndStart(stage: Int) {
        evaluationEditText.setText(if (stage == 2) "a" else ""); evaluationEditText.requestFocus()
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
                if ((realHeight - rect.bottom) > realHeight * 0.1 && !captured) {
                    captured = true; rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    evaluationEditText.postDelayed({
                        takeScreenshot { fullBitmap ->
                            runOnUiThread {
                                val finalRect = Rect(); rootView.getWindowVisibleDisplayFrame(finalRect)
                                val top = finalRect.bottom
                                val windowBottom = rootView.height 
                                val cropHeight = Math.max(0, windowBottom - top)
                                keyboardSnapshot = if (cropHeight > 0) Bitmap.createBitmap(fullBitmap, 0, top, fullBitmap.width, cropHeight) else fullBitmap
                                keyboardSnapshotTop = top
                                imm.hideSoftInputFromWindow(evaluationEditText.windowToken, 0)
                                if (stage == 1) startCalibration() else resumeCalibrationAfterSnapshot()
                            }
                        }
                    }, 5000)
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
    private fun openFilePicker(requestCode: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, requestCode) }
}

class ScrollScrollView(context: Context, attrs: android.util.AttributeSet? = null) : ScrollView(context, attrs)
