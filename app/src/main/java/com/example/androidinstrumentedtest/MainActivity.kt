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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.sqrt

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var reportTextView: TextView
    private lateinit var reportScrollView: ScrollScrollView
    private lateinit var mainScrollView: ScrollView
    private lateinit var mainContentContainer: LinearLayout
    private lateinit var importDataButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var startTestButton: Button
    private lateinit var selectOutputDirButton: Button
    private lateinit var keyboardTypeSpinner: Spinner
    private lateinit var calibrationFileSpinner: Spinner
    private lateinit var currentTestDataText: TextView
    private lateinit var outputDirText: TextView
    private lateinit var appInfoText: TextView
    private lateinit var evaluationEditText: EditText
    
    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "KeyboardEvaluatorPrefs"
    private val KEY_KEYBOARD_TYPE = "last_keyboard_type"
    private val KEY_CALIBRATION_FILE = "last_calibration_file"
    
    private var calibrationView: CalibrationCircleView? = null
    private var isCalibrating = false
    private var calibrationStep = 0
    // Guard report area during calibration guide so load text does not overwrite prompt.
    private var isCalibrationGuideActive = false
    private var cachedTestDataHeader: String? = null
    private var isCalibrationUiShiftApplied = false
    private var calibrationUiShiftPx = 0

    private val keyboardOptions = listOf("9键测试", "26键测试", "14键测试", "联想测试")
    
    // 全量校准序列 (9键用)
    private val calibrationKeys9 = listOf(
        "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "candidate_area"
    )
    // 26键校准序列：a-z + 空格 + 区域
    private val calibrationKeys26 = listOf(
        "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
        "a", "s", "d", "f", "g", "h", "j", "k", "l",
        "z", "x", "c", "v", "b", "n", "m",
        "space",
        "candidate_area"
    )
    // 14键校准序列
    private val calibrationKeys14 = listOf(
        "qw", "er", "ty", "ui", "op",
        "as", "df", "gh", "jk", "l",
        "zx", "cv", "bn", "m",
        "space",
        "candidate_area"
    )
    
    private var currentCalibrationKeys = calibrationKeys9

    private val calibrationPointsJson = JSONObject()
    private var keyboardSnapshot: Bitmap? = null
    private var keyboardSnapshotTop = 0

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    
    // 注: TextRecognizer 已移除，改用 PaddleOCR 本地引擎（见 OcrHelper.kt）
    // private val recognizer: TextRecognizer by lazy {
    //     TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    // }

    companion object {
        const val EXTRA_IS_TEST_MODE = "is_test_mode"
        private const val PICK_DATA_REQUEST_CODE = 1001
        private const val SCREEN_CAPTURE_REQUEST_CODE = 1002
        private const val OPEN_DIRECTORY_REQUEST_CODE = 1003
        private const val TAG = "Calibration"
        // 修改这里即可更新作者信息显示
        private const val APP_AUTHOR = "KUMA"
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        mainScrollView = findViewById(R.id.main_scroll_view)
        mainContentContainer = findViewById(R.id.main_content_container)
        reportTextView = findViewById(R.id.report_text_view)
        reportScrollView = findViewById(R.id.report_scroll_view)
        importDataButton = findViewById(R.id.import_data_button)
        calibrateButton = findViewById(R.id.calibrate_button)
        startTestButton = findViewById(R.id.start_test_button)
        selectOutputDirButton = findViewById(R.id.select_output_dir_button)
        keyboardTypeSpinner = findViewById(R.id.keyboard_type_spinner)
        calibrationFileSpinner = findViewById(R.id.calibration_file_spinner)
        currentTestDataText = findViewById(R.id.current_test_data_text)
        outputDirText = findViewById(R.id.output_dir_text)
        appInfoText = findViewById(R.id.app_info_text)
        evaluationEditText = findViewById(R.id.evaluation_edit_text)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        calibrationUiShiftPx = dpToPx(220)

        setupKeyboardSpinner()
        refreshCalibrationList()
        refreshFileInfo()
        refreshOutputDirectoryInfo()
        refreshAppInfoLabel()

        if (intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)) {
            findViewById<View>(R.id.start_test_button).visibility = View.GONE
        } else {
            importDataButton.setOnClickListener { openFilePicker(PICK_DATA_REQUEST_CODE) }
            calibrateButton.setOnClickListener { requestScreenCapturePermission() }
            selectOutputDirButton.setOnClickListener { requestDirectoryPermission() }
            startTestButton.setOnClickListener { runInstrumentationTest() }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun applyCalibrationGuideShiftAndScroll() {
        if (!isCalibrationUiShiftApplied) {
            isCalibrationUiShiftApplied = true
            mainContentContainer.translationY = -calibrationUiShiftPx.toFloat()
        }
        mainScrollView.post {
            val targetY = (reportScrollView.top - dpToPx(16)).coerceAtLeast(0)
            mainScrollView.smoothScrollTo(0, targetY)
        }
    }

    private fun restoreCalibrationGuideShiftAndScroll() {
        if (!isCalibrationUiShiftApplied) return
        isCalibrationUiShiftApplied = false
        mainContentContainer.translationY = 0f
    }

    private fun setupKeyboardSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, keyboardOptions)
        keyboardTypeSpinner.adapter = adapter
        
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
        if (fileNames.isEmpty()) fileNames.add("未找到校准文件") else fileNames.sort()
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fileNames)
        calibrationFileSpinner.adapter = adapter
        
        val lastCal = prefs.getString(KEY_CALIBRATION_FILE, "") ?: ""
        val pos = fileNames.indexOf(lastCal)
        if (pos != -1) calibrationFileSpinner.setSelection(pos) else autoSelectMatchingCalibration()

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
            if (adapter.getItem(i).toString().startsWith(expectedPrefix)) {
                calibrationFileSpinner.setSelection(i); break
            }
        }
    }

    private fun refreshFileInfo() {
        val configDir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
        val nameRefFile = File(configDir, "last_test_data_name.txt")
        val originalName = if (nameRefFile.exists()) nameRefFile.readText() else "test_data.txt"
        val testDataFile = File(configDir, "test_data.txt")
        if (testDataFile.exists()) {
            val preview = try { testDataFile.bufferedReader().useLines { lines -> lines.take(3).joinToString("\n") } } catch (e: Exception) { "" }
            currentTestDataText.text = "当前测试文件: $originalName\n数据预览:\n$preview"
        } else {
            currentTestDataText.text = "当前测试文件: 无"
        }
    }

    private fun refreshOutputDirectoryInfo() {
        outputDirText.text = OutputDirectoryManager.buildStatusText(this)
    }

    private fun refreshAppInfoLabel() {
        val version = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "--"
        }.getOrDefault("--")
        appInfoText.text = "版本: $version  作者: $APP_AUTHOR"
    }

    private fun requestDirectoryPermission() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, OPEN_DIRECTORY_REQUEST_CODE)
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
                val keysToClear = mutableListOf<String>(); val itClear = calibrationPointsJson.keys()
                while (itClear.hasNext()) { keysToClear.add(itClear.next()) }
                keysToClear.forEach { calibrationPointsJson.remove(it) }
                json.keys().forEach { calibrationPointsJson.put(it, json.get(it)) }
                Log.i(TAG, "[校准] 已加载: $fileName")
                if (!isCalibrationGuideActive && !isCalibrating) {
                    setReportText("✅ 已加载校准: $fileName", Color.parseColor("#006400"))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "加载配置失败", e) }
    }

    fun getAllOcrTokens(screenshot: Bitmap, onResult: (List<String>) -> Unit) {
        // 注: OCR 功能已移至测试代码（OcrHelper.kt）
        // 校准工具不需要 OCR，直接返回空列表
        try {
            // 原 ML Kit 代码已注释
            // recognizer.process(InputImage.fromBitmap(imageToProcess, 0))...
            onResult(emptyList())  // 返回空列表
        } catch (e: Exception) { onResult(emptyList()) }
    }

    private fun requestScreenCapturePermission() {
        startMediaProjectionService()
        startActivityForResult(projectionManager!!.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && (resultCode != Activity.RESULT_OK || data == null)) {
            // User canceled capture or failed to grant permission; reset guide state.
            isCalibrationGuideActive = false
            isCalibrating = false
            exitCalibrationUiMode()
            restoreCalibrationGuideShiftAndScroll()
            stopProjection()
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) return
        when (requestCode) {
            SCREEN_CAPTURE_REQUEST_CODE -> {
                try { mediaProjection = projectionManager!!.getMediaProjection(resultCode, data); startCalibrationSequence() } catch (e: Exception) { stopMediaProjectionService() }
            }
            PICK_DATA_REQUEST_CODE -> {
                data.data?.let { uri -> 
                    val realName = getFileNameFromUri(uri); File(File(filesDir, "InstrumentedTest"), "last_test_data_name.txt").writeText(realName)
                    copyFileToInternal(uri, "test_data.txt", "[测试数据]") 
                }
            }
            OPEN_DIRECTORY_REQUEST_CODE -> {
                val treeUri = data.data ?: return
                val incomingFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val takeFlags = if (incomingFlags != 0) {
                    incomingFlags
                } else {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                }
                contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                OutputDirectoryManager.saveAuthorizedTreeUri(this, treeUri)
                refreshOutputDirectoryInfo()
                setReportText("✅ 已授权测试输出目录: ${OutputDirectoryManager.getAuthorizedDirectoryLabel(this) ?: treeUri}", Color.parseColor("#006400"))
            }
        }
    }

    private fun startCalibrationSequence() {
        val kbType = keyboardTypeSpinner.selectedItem.toString()
        currentCalibrationKeys = when (kbType) {
            "26键测试" -> calibrationKeys26
            "14键测试" -> calibrationKeys14
            else -> calibrationKeys9
        }
        isCalibrationGuideActive = true
        enterCalibrationUiMode()
        applyCalibrationGuideShiftAndScroll()
        setReportText("请点击测试文本框拉起输入法键盘，然后点击立即捕获。", Color.BLUE)
        showStageGuide()
    }

    private fun showStageGuide() {
        applyCalibrationGuideShiftAndScroll()
        val title = "键盘校准"
        val msg = "请按以下步骤操作：\n\n1. 点击下方测试文本框\n2. 拉起输入法键盘\n3. 点击【立即捕获】按钮进行截屏\n\n（键盘需要保持打开状态）"
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("立即捕获") { _, _ -> captureKeyboardAndStart() }
            .setCancelable(false)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        setReportText("📝 校准步骤：\n1. 点击测试框\n2. 拉起键盘\n3. 点击立即捕获", android.graphics.Color.BLUE)
    }

    private fun captureKeyboardAndStart() {
        applyCalibrationGuideShiftAndScroll()
        evaluationEditText.setText("")
        evaluationEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(evaluationEditText, InputMethodManager.SHOW_FORCED)
        val rootView = window.decorView
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var captured = false
            override fun onGlobalLayout() {
                val rect = Rect(); rootView.getWindowVisibleDisplayFrame(rect)
                val metrics = DisplayMetrics(); windowManager.defaultDisplay.getRealMetrics(metrics)
                if ((metrics.heightPixels - rect.bottom) > metrics.heightPixels * 0.1 && !captured) {
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
                                startCalibration()
                            }
                        }
                    }, 5000)
                }
            }
        })
    }

    private fun startCalibration() {
        isCalibrationGuideActive = false
        isCalibrating = true
        calibrationStep = 0
        if (calibrationView == null) { calibrationView = CalibrationCircleView(this); addContentView(calibrationView, ViewGroup.LayoutParams(-1, -1)) }
        calibrationView?.visibility = View.VISIBLE
        resetIndicatorPosition()
        updatePrompt()
    }

    private fun resetIndicatorPosition() {
        keyboardSnapshot?.let {
            calibrationView?.setInitialPosition(it.width / 2f, keyboardSnapshotTop + it.height / 2f)
        }
    }

    private fun updatePrompt() {
        val key = currentCalibrationKeys[calibrationStep]
        val prompt = when (key) {
            "candidate_area" -> "请使用红框圈出 '首行候选词' 所在的区域 并确认"
            "space" -> "请将红圈拖到 '空格键' 中心并确认"
            else -> "请将红圈拖到按键 '$key' 中心并确认"
        }
        val displayText = "校准中 (${calibrationStep + 1}/${currentCalibrationKeys.size}):\n$prompt"
        setReportText(displayText, Color.BLUE)
    }

    private fun onCoordinateConfirmed(x: Float, y: Float) {
        val key = currentCalibrationKeys[calibrationStep]
        val point = JSONObject().apply { put("x", x.toDouble()); put("y", y.toDouble()) }
        if (key == "candidate_area") { calibrationView?.let { point.put("w", it.rectW.toDouble()); point.put("h", it.rectH.toDouble()) } }
        calibrationPointsJson.put(key, point)
        calibrationStep++

        if (calibrationStep < currentCalibrationKeys.size) {
            resetIndicatorPosition()
            updatePrompt()
        } else {
            finishCalibration()
        }
    }

    private fun finishCalibration() {
        isCalibrationGuideActive = false
        isCalibrating = false; calibrationView?.visibility = View.GONE
        exitCalibrationUiMode()
        restoreCalibrationGuideShiftAndScroll()
        try {
            val imeName = getCurrentImeName(); val kbType = keyboardTypeSpinner.selectedItem.toString().replace(" ", "_")
            val fileName = "cal_${imeName}_${kbType}.json"; val file = File(File(filesDir, "InstrumentedTest"), fileName)
            FileOutputStream(file).use { it.write(calibrationPointsJson.toString().toByteArray()) }
            refreshCalibrationList(); setReportText("✅ 校准成功！文件已保存: $fileName", Color.parseColor("#006400"))
        } catch (e: Exception) { Log.e(TAG, "Save Error", e) }
        stopProjection()
    }

    private inner class CalibrationCircleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 0, 0); style = Paint.Style.FILL }
        private var circleX = 0f; private var circleY = 0f; var rectW = 600f; var rectH = 120f
        private var isDragging = false; private var isResizing = false
        fun setInitialPosition(sx: Float, sy: Float) { circleX = sx; circleY = sy; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val loc = IntArray(2); getLocationOnScreen(loc); val dx = circleX - loc[0]; val dy = circleY - loc[1]
            if (isCalibrating) { keyboardSnapshot?.let { canvas.drawBitmap(it, -loc[0].toFloat(), (keyboardSnapshotTop - loc[1]).toFloat(), null) } }
            if (currentCalibrationKeys[calibrationStep] == "candidate_area") {
                val left = dx - rectW/2; val top = dy - rectH/2; val right = dx + rectW/2; val bottom = dy + rectH/2
                canvas.drawRect(left, top, right, bottom, fillPaint); canvas.drawRect(left, top, right, bottom, paint)
                canvas.drawCircle(right, bottom, 25f, paint)
            } else { canvas.drawCircle(dx, dy, 70f, fillPaint); canvas.drawCircle(dx, dy, 70f, paint) }
            canvas.drawLine(dx-35, dy, dx+35, dy, paint); canvas.drawLine(dx, dy-35, dx, dy+35, paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isCalibrating) return false
            val sx = e.rawX; val sy = e.rawY; val loc = IntArray(2); getLocationOnScreen(loc); val vx = sx - loc[0]; val vy = sy - loc[1]; val cx = circleX - loc[0]; val cy = circleY - loc[1]
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (currentCalibrationKeys[calibrationStep] == "candidate_area") {
                        if (sqrt((vx-(cx+rectW/2)).pow(2) + (vy-(cy+rectH/2)).pow(2)) < 60) { isResizing = true; return true }
                    }
                    isDragging = true; circleX = sx; circleY = sy; invalidate()
                }
                MotionEvent.ACTION_MOVE -> { if (isResizing) { rectW = Math.max(100f, (vx - cx) * 2); rectH = Math.max(60f, (vy - cy) * 2) } else if (isDragging) { circleX = sx; circleY = sy }; invalidate() }
                MotionEvent.ACTION_UP -> { isDragging = false; isResizing = false; performClick(); showConfirmDialog(circleX, circleY) }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun showConfirmDialog(sx: Float, sy: Float) {
            val label = currentCalibrationKeys[calibrationStep]
            AlertDialog.Builder(context).setTitle("确认").setMessage("对准 '$label' 了吗？").setPositiveButton("确定") { _, _ -> onCoordinateConfirmed(sx, sy) }.setNegativeButton("微调", null).show()
        }
    }

    private fun takeScreenshot(callback: (Bitmap) -> Unit) {
        val projection = mediaProjection ?: return
        val metrics = DisplayMetrics(); windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels; val d = metrics.densityDpi
        val localImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val snapshotThread = HandlerThread("SnapshotThread").apply { start() }
        val handler = Handler(snapshotThread.looper)
        val localVirtualDisplay = projection.createVirtualDisplay("Snapshot", w, h, d, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, localImageReader.surface, null, handler)
        localImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                reader.setOnImageAvailableListener(null, null)
                val buffer = image.planes[0].buffer; val pixelStride = image.planes[0].pixelStride; val rowStride = image.planes[0].rowStride
                val bitmap = Bitmap.createBitmap(w + (rowStride - pixelStride * w) / pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer); image.close(); localVirtualDisplay?.release(); localImageReader.close(); snapshotThread.quitSafely()
                callback(Bitmap.createBitmap(bitmap, 0, 0, w, h))
            }
        }, handler)
    }

    @SuppressLint("Range")
    private fun getFileNameFromUri(uri: Uri): String {
        var name = "unknown_file"
        if (uri.scheme == "content") { contentResolver.query(uri, null, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)) } }
        else if (uri.scheme == "file") { name = File(uri.path!!).name }
        return name
    }

    private fun copyFileToInternal(uri: Uri, targetFileName: String, logLabel: String) {
        try {
            val targetFile = File(File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }, targetFileName)
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(targetFile).use { output -> input.copyTo(output) } }
            refreshFileInfo(); setReportText("✅ $logLabel 导入成功！", Color.parseColor("#006400"), isFileRelated = true)
        } catch (e: Exception) { setReportText("❌ $logLabel 失败: ${e.message}", Color.RED, isFileRelated = true) }
    }

    private fun enterCalibrationUiMode() {
        val header = currentTestDataText.text?.toString()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        cachedTestDataHeader = if (header.isNotEmpty()) header else "当前测试文件: 无"
        currentTestDataText.text = cachedTestDataHeader
        currentTestDataText.maxLines = 1
        currentTestDataText.visibility = View.VISIBLE
    }

    private fun exitCalibrationUiMode() {
        currentTestDataText.maxLines = Int.MAX_VALUE
        refreshFileInfo()
    }

    fun setReportText(text: String, color: Int) {
        setReportText(text, color, false)
    }

    fun setReportText(text: String, color: Int, isFileRelated: Boolean) {
         runOnUiThread {
             if (isCalibrationGuideActive && isFileRelated) return@runOnUiThread
             reportTextView.text = text
             reportTextView.setTextColor(color)
             reportScrollView.post { reportScrollView.fullScroll(View.FOCUS_DOWN) }
         }
     }

    private fun openFilePicker(requestCode: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, requestCode) }

    private fun runInstrumentationTest() {
        try {
            Runtime.getRuntime().exec("am instrument -w com.example.androidinstrumentedtest.test/androidx.test.runner.AndroidJUnitRunner")
            setReportText("🚀 已尝试启动测试。请查看 Logcat。", Color.BLUE)
        } catch (e: Exception) {
            setReportText("❌ 启动失败: ${e.message}", Color.RED)
        }
    }

    private fun startMediaProjectionService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, MediaProjectionService::class.java))
        } else {
            startService(Intent(this, MediaProjectionService::class.java))
        }
    }

    private fun stopMediaProjectionService() {
        stopService(Intent(this, MediaProjectionService::class.java))
    }

    private fun stopProjection() {
        mediaProjection?.stop()
        mediaProjection = null
        stopMediaProjectionService()
    }
}

class ScrollScrollView(context: Context, attrs: android.util.AttributeSet? = null) : ScrollView(context, attrs)
