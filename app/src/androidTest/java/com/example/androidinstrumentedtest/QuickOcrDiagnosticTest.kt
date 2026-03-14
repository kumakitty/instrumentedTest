package com.example.androidinstrumentedtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * 快速 OCR 诊断测试
 * 直接测试 OCR 引擎的基本能力
 */
@RunWith(AndroidJUnit4::class)
class QuickOcrDiagnosticTest {
    
    private val tag = "QuickOcrDiag"
    
    @Before
    fun setUp() {
        Log.i(tag, "================== QuickOcrDiagnosticTest 开始 ==================")
    }
    
    @Test
    fun testOcrBasicCapability() {
        Log.i(tag, "测试 OCR 引擎基本识别能力...")
        
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val debugDir = File(context.filesDir, "quick_ocr_diag").apply { mkdirs() }
        
        // 创建 OcrHelper 实例
        val ocrHelper = OcrHelper(context, assetContext)
        
        // 初始化引擎
        Log.i(tag, "初始化 OCR 引擎...")
        ocrHelper.initEngine()
        
        // 生成测试图片
        val testCases = listOf(
            "你" to "单个字",
            "中文" to "两个字",
            "测试" to "测试词",
            "ABC" to "英文",
            "123" to "数字"
        )
        
        for ((text, description) in testCases) {
            try {
                Log.i(tag, "-".repeat(70))
                Log.i(tag, "测试: $description - [$text]")
                
                // 生成带文字的图片
                val bitmap = createTextBitmap(text, 300, 120)
                
                // 保存图片用于调试
                val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())
                val imageFile = File(debugDir, "test_${text}_${timestamp}.png")
                saveBitmap(bitmap, imageFile)
                Log.i(tag, "图片已保存: ${imageFile.name}")
                
                // 执行 OCR
                Log.i(tag, "执行 OCR...")
                val result = runBlocking { ocrHelper.recognizeText(bitmap) }
                
                // 记录结果
                Log.i(tag, "OCR 结果:")
                Log.i(tag, "  - Success: ${result.success}")
                Log.i(tag, "  - Text: '${result.text}'")
                Log.i(tag, "  - Tokens: ${result.tokens}")
                Log.i(tag, "  - Details 数量: ${result.details.size}")
                
                if (result.details.isNotEmpty()) {
                    result.details.forEach { detail ->
                        Log.i(tag, "    • '${detail.text}' (conf=${String.format("%.2f%%", detail.confidence * 100)})")
                    }
                }
                
                if (result.error != null) {
                    Log.e(tag, "  - Error: ${result.error}")
                }
                
                // 判断识别是否成功
                if (result.text.isNotEmpty()) {
                    Log.i(tag, "✅ 识别成功: '${result.text}'")
                } else {
                    Log.e(tag, "❌ 识别失败: 返回空字符串")
                }
                
                bitmap.recycle()
                
            } catch (e: Exception) {
                Log.e(tag, "测试异常: ${e.message}", e)
            }
        }
        
        Log.i(tag, "-".repeat(70))
        Log.i(tag, "================== QuickOcrDiagnosticTest 完成 ==================")
    }
    
    private fun createTextBitmap(text: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // 白色背景
        canvas.drawColor(Color.WHITE)
        
        // 黑色文字
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = (height * 0.7f).coerceAtLeast(20f)
            isAntiAlias = true
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        
        canvas.drawText(text, (width / 2).toFloat(), (height / 2 + 15).toFloat(), paint)
        
        return bitmap
    }
    
    private fun saveBitmap(bitmap: Bitmap, file: File) {
        try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            Log.w(tag, "保存位图失败: ${e.message}")
        }
    }
}

