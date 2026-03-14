package com.example.androidinstrumentedtest

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ✅ 按照官方设计 - 使用 OcrPredictor 而不是直接用 OCRPredictorNative
 */
class OcrHelper(
    private val runtimeContext: Context,
    private val assetContext: Context = runtimeContext
) {
    private var predictor: OcrPredictor? = null
    private val tag = "OcrHelper"
    
    data class RecognitionResult(
        val success: Boolean,
        val text: String,
        val tokens: List<String>,
        val details: List<TextDetail> = emptyList(),
        val error: String? = null
    )
    
    data class TextDetail(
        val text: String,
        val confidence: Float = 0f,
        val bounds: List<FloatArray> = emptyList()
    )
    
    fun initEngine(): Boolean {
        try {
            Log.i(tag, "初始化 OCR 引擎...")
            // ✅ 使用单例模式获取 OcrPredictor - 避免并发初始化导致的 assetContext 混乱
            predictor = OcrPredictor.getInstance(runtimeContext, assetContext)
            return predictor!!.init()
        } catch (e: Exception) {
            Log.e(tag, "初始化失败: ${e.message}", e)
            return false
        }
    }
    
    fun isInitialized(): Boolean = predictor != null
    
    suspend fun recognizeText(bitmap: Bitmap): RecognitionResult = withContext(Dispatchers.Default) {
        try {
            if (predictor == null) {
                Log.e(tag, "❌ 预测器未初始化")
                return@withContext RecognitionResult(false, "", emptyList(), error = "未初始化")
            }
            
            Log.i(tag, "📸 开始 OCR 识别")
            val results = predictor!!.runImage(bitmap)
            Log.i(tag, "✅ OCR 完成，检测到 ${results.size} 个结果")
            
            if (results.isEmpty()) {
                Log.w(tag, "⚠️ 没有识别结果")
                return@withContext RecognitionResult(true, "", emptyList())
            }
            
            val textList = results.mapNotNull { result ->
                val text = result.label
                if (text.isNotEmpty()) {
                    Log.d(tag, "✓ 识别文本: '$text'")
                    text
                } else {
                    Log.w(tag, "⚠️ 空标签")
                    null
                }
            }.filter { it.isNotEmpty() }
            
            val fullText = textList.joinToString("")
            Log.i(tag, "📝 最终识别文本: '$fullText'")
            
            RecognitionResult(true, fullText, textList)
        } catch (e: Exception) {
            Log.e(tag, "❌ OCR 异常: ${e.message}", e)
            RecognitionResult(false, "", emptyList(), error = e.message)
        }
    }
    
    fun release() {
        predictor?.release()
        predictor = null
    }
}
