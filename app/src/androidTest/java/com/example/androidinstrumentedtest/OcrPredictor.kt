package com.example.androidinstrumentedtest

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.baidu.paddle.lite.demo.ocr.OCRPredictorNative
import com.baidu.paddle.lite.demo.ocr.OcrResultModel
import java.io.File

/**
 * ✅ 按照官方 PaddleOCR 设计 - 上层管理字典和字符转换
 * 使用单例模式避免多个测试并发初始化导致的状态混乱
 */
class OcrPredictor(
    private val runtimeContext: Context,
    private val assetContext: Context = runtimeContext
) {
    private var predictor: OCRPredictorNative? = null
    private val wordLabels = mutableListOf<String>()
    private val tag = "OcrPredictor"
    
    companion object {
        @Volatile
        private var instance: OcrPredictor? = null
        private val initLock = Object()
        
        /**
         * 获取单例实例 - 确保只初始化一次
         */
        fun getInstance(runtimeContext: Context, assetContext: Context = runtimeContext): OcrPredictor {
            if (instance == null) {
                synchronized(initLock) {
                    if (instance == null) {
                        instance = OcrPredictor(runtimeContext, assetContext)
                    }
                }
            }
            return instance!!
        }
        
        /**
         * 重置单例（测试使用）
         */
        fun reset() {
            synchronized(initLock) {
                instance?.release()
                instance = null
            }
        }
    }
    
    fun init(): Boolean {
        // 如果已初始化过，直接返回成功
        if (predictor != null && wordLabels.isNotEmpty()) {
            Log.i(tag, "⏭️ OCR 已初始化，跳过重复初始化")
            return true
        }
        
        try {
            Log.i(tag, "初始化 OCR 预测器...")
            
            // 1. 复制模型文件
            val cacheDir = runtimeContext.cacheDir
            val modelsDir = File(cacheDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()
            Log.i(tag, "✅ 模型目录: ${modelsDir.absolutePath}")
            
            listOf("det_db.nb", "rec_crnn.nb", "cls.nb").forEach { model ->
                val dst = File(modelsDir, model)
                if (!dst.exists()) {
                    try {
                        val input = assetContext.assets.open("models/$model")
                        val output = dst.outputStream()
                        input.copyTo(output)
                        output.close()
                        input.close()
                        Log.i(tag, "✅ 复制模型: $model (${dst.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(tag, "❌ 复制失败: $model ${e.message}")
                        return false
                    }
                } else {
                    Log.i(tag, "✅ 模型已存在: $model (${dst.length()} bytes)")
                }
            }
            
            // 2. 加载字典文件
            if (!loadDictionary()) {
                Log.e(tag, "字典加载失败")
                return false
            }
            
            // 3. 初始化 OCR 引擎
            Log.i(tag, "加载 libNative.so...")
            OCRPredictorNative.loadLibrary()
            
            val detPath = File(modelsDir, "det_db.nb").absolutePath
            val recPath = File(modelsDir, "rec_crnn.nb").absolutePath
            val clsPath = File(modelsDir, "cls.nb").absolutePath
            
            Log.i(tag, "模型路径:")
            Log.i(tag, "  det: $detPath")
            Log.i(tag, "  rec: $recPath")
            Log.i(tag, "  cls: $clsPath")
            
            val config = OCRPredictorNative.Config(
                useOpencl = 0,
                cpuThreadNum = 4,
                cpuPower = "LITE_POWER_HIGH",
                detModelFilename = detPath,
                recModelFilename = recPath,
                clsModelFilename = clsPath
            )
            predictor = OCRPredictorNative(config)
            
            Log.i(tag, "✅ OCR 初始化成功")
            return true
        } catch (e: Exception) {
            Log.e(tag, "❌ 初始化失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * ✅ 按照官方方式 - 从 assets 加载字典文件
     */
    private fun loadDictionary(): Boolean {
        return try {
            wordLabels.clear()
            // 索引0必须留空或放特殊标记（如 ' '），绝不能放实际字符
            wordLabels.add("")  // 修改点1: 改为空字符串
            
            val input = assetContext.assets.open("labels/ppocr_keys_v1.txt")
            val lines = input.bufferedReader().readLines()
            input.close()
            
            for (line in lines) {
                if (line.isNotEmpty()) {
                    wordLabels.add(line.trim())
                }
            }
            // 修改点2: 不要手动添加空格，ppocr_keys_v1.txt已包含
            
            Log.i(tag, "✅ 字典加载成功: ${wordLabels.size} 个字符")
            
            // 添加验证：打印前10个字符检查
            Log.i(tag, "字典前10项: ${wordLabels.subList(0, minOf(10, wordLabels.size))}")
            true
        } catch (e: Exception) {
            Log.e(tag, "❌ 字典加载失败", e)
            false
        }
    }
    
    /**
     * 运行 OCR 识别 - 并在此处进行字符转换
     */
    fun runImage(bitmap: Bitmap): List<OcrResultModel> {
        if (predictor == null) {
            Log.e(tag, "❌ 预测器未初始化")
            return emptyList()
        }
        
        Log.i(tag, "📸 开始 OCR 推理 (bitmap: ${bitmap.width}x${bitmap.height})")
        
        // 获取原始结果 (只有 wordIndex,没有 label)
        val rawResults = predictor!!.runImage(bitmap, 960, 1, 0, 1)
        Log.i(tag, "✅ 推理完成: 检测到 ${rawResults.size} 个字段")
        
        if (rawResults.isEmpty()) {
            Log.w(tag, "⚠️ 没有检测到任何文字")
            return emptyList()
        }
        
        // ✅ 按照官方方式 - 在此处转换 wordIndex 为 label
        rawResults.forEachIndexed { index, result ->
            Log.d(tag, "检测结果 $index: wordNum=${result.wordIndex.size} confidence=${result.confidence}")
            
            val textBuilder = StringBuilder()
            for (wordIdx in result.wordIndex) {
                if (wordIdx >= 0 && wordIdx < wordLabels.size) {
                    textBuilder.append(wordLabels[wordIdx])
                } else {
                    Log.w(tag, "⚠️ 字典索引越界: $wordIdx (字典大小: ${wordLabels.size})")
                    textBuilder.append("×")
                }
            }
            result.label = textBuilder.toString()
            Log.d(tag, "转换结果 $index: indices=${result.wordIndex} → label='${result.label}'")
        }
        
        return rawResults
    }
    
    fun release() {
        predictor?.destroy()
        predictor = null
        wordLabels.clear()
    }
}
