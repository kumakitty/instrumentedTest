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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var reportTextView: TextView
    private lateinit var reportScrollView: ScrollScrollView
    private lateinit var importDataButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var evaluationEditText: EditText
    
    private var calibrationView: CalibrationCircleView? = null
    private var isCalibrating = false
    private var calibrationStep = 0
    
    private val calibrationKeys = listOf(
        "2", "3", "4", "5", "6", "7", "8", "9", "0", 
        "dropdown_btn", 
        "candidate_1", "candidate_2", "candidate_3", "candidate_4", "candidate_5"
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
        "请展开全屏面板，并定位 '第1位候选词'",
        "请定位展开面板中的 '第2位候选词'",
        "请定位展开面板中的 '第3位候选词'",
        "请定位展开面板中的 '第4位候选词'",
        "请定位展开面板中的 '第5位候选词'"
    )

    private val calibrationPointsJson = JSONObject()
    private var keyboardSnapshot: Bitmap? = null
    private var keyboardSnapshotTop = 0

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null

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
        evaluationEditText = findViewById(R.id.evaluation_edit_text)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (intent.getBooleanExtra(EXTRA_IS_TEST_MODE, false)) {
            importDataButton.visibility = View.GONE
            calibrateButton.visibility = View.GONE
            findViewById<View>(R.id.test_type_radio_group).visibility = View.GONE
        } else {
            importDataButton.setOnClickListener { openFilePicker() }
            calibrateButton.setOnClickListener { requestScreenCapturePermission() }
        }
    }

    private fun requestScreenCapturePermission() {
        startMediaProjectionService()
        startActivityForResult(projectionManager!!.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    mediaProjection = projectionManager!!.getMediaProjection(resultCode, data)
                    showStageGuide(1)
                } catch (e: SecurityException) {
                    Log.e(TAG, "MediaProjection Error: ${e.message}")
                    stopMediaProjectionService()
                }
            }
        } else if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri -> copyDataToAppDirectory(uri) }
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
        val msg = if (stage == 1) 
            "1. 点击捕获后将拉起键盘。\n2. 请保持 9键数字 状态。\n3. 系统将截图进行按键和下拉按钮定位。" 
            else "1. 点击捕获后将拉起键盘。\n2. 请手动点击下拉按钮展开全屏候选词面板。\n3. 系统将截图进行候选词位置定位。"

        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("立即捕获") { _, _ -> captureKeyboardAndStart(stage) }
            .setNegativeButton("取消") { _, _ -> stopProjection() }
            .setCancelable(false).show()
    }

    private fun captureKeyboardAndStart(stage: Int) {
        evaluationEditText.setText(if (stage == 2) "a" else "")
        evaluationEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(evaluationEditText, InputMethodManager.SHOW_FORCED)

        updateStatus(if (stage == 1) "正在准备主键盘快照..." else "正在准备全屏面板快照...", Color.BLACK)

        val rootView = window.decorView
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var captured = false
            override fun onGlobalLayout() {
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val realMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(realMetrics)
                val screenHeight = realMetrics.heightPixels
                
                if ((screenHeight - rect.bottom) > screenHeight * 0.1) {
                    if (!captured) {
                        captured = true
                        rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        evaluationEditText.postDelayed({
                            val finalRect = Rect()
                            rootView.getWindowVisibleDisplayFrame(finalRect)
                            val top = finalRect.bottom
                            
                            takeScreenshot { fullBitmap ->
                                runOnUiThread {
                                    val cropHeight = Math.min(fullBitmap.height - top, screenHeight - top)
                                    keyboardSnapshot = if (cropHeight > 0) {
                                        Bitmap.createBitmap(fullBitmap, 0, top, fullBitmap.width, cropHeight)
                                    } else { fullBitmap }
                                    keyboardSnapshotTop = if (cropHeight > 0) top else 0
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
        val realMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val w = realMetrics.widthPixels; val h = realMetrics.heightPixels; val d = realMetrics.densityDpi

        val localImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val snapshotThread = HandlerThread("SnapshotThread").apply { start() }
        val handler = Handler(snapshotThread.looper)

        val localVirtualDisplay = projection.createVirtualDisplay("Snapshot", w, h, d, 16, localImageReader.surface, null, handler)
        localImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                reader.setOnImageAvailableListener(null, null)
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w
                val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                localVirtualDisplay?.release()
                localImageReader.close()
                snapshotThread.quitSafely()
                callback(Bitmap.createBitmap(bitmap, 0, 0, w, h))
            }
        }, handler)
    }

    private fun startCalibration() {
        isCalibrating = true
        calibrationStep = 0
        if (calibrationView == null) {
            calibrationView = CalibrationCircleView(this)
            addContentView(calibrationView, ViewGroup.LayoutParams(-1, -1))
        }
        calibrationView?.visibility = View.VISIBLE
        resetPosition(); updatePrompt()
    }

    private fun resumeCalibrationAfterSnapshot() {
        calibrationView?.visibility = View.VISIBLE
        resetPosition(); updatePrompt()
    }

    private fun resetPosition() {
        keyboardSnapshot?.let { calibrationView?.setInitialPosition(it.width / 2f, keyboardSnapshotTop + it.height / 2f) }
    }

    private fun updatePrompt() {
        setReportText("校准中 (${calibrationStep + 1}/${calibrationKeys.size}):\n${calibrationPrompts[calibrationStep]}", Color.BLUE)
    }

    private fun onCoordinateConfirmed(x: Float, y: Float) {
        calibrationPointsJson.put(calibrationKeys[calibrationStep], JSONObject().apply { put("x", x.toDouble()); put("y", y.toDouble()) })
        calibrationStep++
        if (calibrationStep == 10) {
            calibrationView?.visibility = View.GONE
            showStageGuide(2)
        } else if (calibrationStep < calibrationKeys.size) {
            resetPosition(); updatePrompt()
        } else {
            finishCalibration()
        }
    }

    private fun finishCalibration() {
        isCalibrating = false
        calibrationView?.visibility = View.GONE
        try {
            val dir = File(filesDir, "InstrumentedTest").apply { if (!exists()) mkdirs() }
            val file = File(dir, "calibration.json")
            FileOutputStream(file).use { it.write(calibrationPointsJson.toString().toByteArray()) }
            setReportText("✅ 校准成功！所有坐标已锁定。", Color.parseColor("#006400"))
        } catch (e: Exception) { Log.e(TAG, "Save Error: ${e.message}") }
        stopProjection()
    }

    private fun stopProjection() {
        mediaProjection?.stop(); mediaProjection = null
        stopMediaProjectionService()
    }

    private fun updateStatus(msg: String, color: Int) { runOnUiThread { setReportText(msg, color) } }

    private inner class CalibrationCircleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 0, 0); style = Paint.Style.FILL }
        private var circleX = 0f; private var circleY = 0f
        fun setInitialPosition(sx: Float, sy: Float) { circleX = sx; circleY = sy; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val loc = IntArray(2); getLocationOnScreen(loc)
            if (isCalibrating) { keyboardSnapshot?.let { canvas.drawBitmap(it, -loc[0].toFloat(), (keyboardSnapshotTop - loc[1]).toFloat(), null) } }
            val dx = circleX - loc[0]; val dy = circleY - loc[1]
            canvas.drawCircle(dx, dy, 70f, fillPaint); canvas.drawCircle(dx, dy, 70f, paint)
            canvas.drawLine(dx-35, dy, dx+35, dy, paint); canvas.drawLine(dx, dy-35, dx, dy+35, paint)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isCalibrating) return false
            if (e.action == MotionEvent.ACTION_UP) { performClick(); showConfirmDialog(circleX, circleY) } 
            else { circleX = e.rawX; circleY = e.rawY; invalidate() }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun showConfirmDialog(sx: Float, sy: Float) {
            val label = when { calibrationStep == 9 -> "下拉按钮"; calibrationStep >= 10 -> "第${calibrationStep - 9}候选词"; else -> calibrationKeys[calibrationStep] }
            AlertDialog.Builder(context).setTitle("确认").setMessage("确认红圈对准了 '$label' 吗？").setPositiveButton("确定") { _, _ -> onCoordinateConfirmed(sx, sy) }.setNegativeButton("微调", null).show()
        }
    }

    fun setReportText(text: String, color: Int) { runOnUiThread { reportTextView.text = text; reportTextView.setTextColor(color); reportScrollView.post { reportScrollView.fullScroll(View.FOCUS_DOWN) } } }
    private fun openFilePicker() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/*" }, PICK_FILE_REQUEST_CODE) }
    private fun copyDataToAppDirectory(uri: Uri) { try { val dataDir = File(filesDir, "InstrumentedTest"); if (!dataDir.exists()) dataDir.mkdirs(); contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(File(dataDir, "test_data.txt")).use { o -> i.copyTo(o) } }; setReportText("导入成功", Color.parseColor("#006400")) } catch (e: Exception) { setReportText("失败: ${e.message}", Color.RED) } }
}
class ScrollScrollView(context: Context, attrs: android.util.AttributeSet? = null) : ScrollView(context, attrs)
