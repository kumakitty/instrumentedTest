package com.baidu.paddle.lite.demo.ocr

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 官方 PaddleOCR 原生接口包装 (按照官方实现方式)
 * 只负责 JNI 调用和数据解析，字符转换由上层负责
 */
class OCRPredictorNative(config: Config) {
    
    private val mConfig: Config = config
    private var nativePointer: Long = 0
    
    companion object {
        private val isSOLoaded = AtomicBoolean(false)
        
        fun loadLibrary() {
            if (!isSOLoaded.get() && isSOLoaded.compareAndSet(false, true)) {
                try {
                    System.loadLibrary("Native")
                    Log.i("OCRPredictorNative", "✅ libNative.so 加载成功")
                } catch (e: Throwable) {
                    throw RuntimeException(
                        "加载 libNative.so 失败，请确保库文件存在于 APK 中",
                        e
                    )
                }
            }
        }
    }
    
    data class Config(
        var useOpencl: Int = 0,
        var cpuThreadNum: Int = 4,
        var cpuPower: String = "LITE_POWER_HIGH",
        var detModelFilename: String = "",
        var recModelFilename: String = "",
        var clsModelFilename: String = ""
    )
    
    init {
        loadLibrary()
        nativePointer = init(
            config.detModelFilename,
            config.recModelFilename,
            config.clsModelFilename,
            config.useOpencl,
            config.cpuThreadNum,
            config.cpuPower
        )
        Log.i("OCRPredictorNative", "✅ 初始化成功，指针: $nativePointer")
    }
    
    fun runImage(
        bitmap: Bitmap,
        maxSizeLen: Int = 960,
        runDet: Int = 1,
        runCls: Int = 0,
        runRec: Int = 1
    ): ArrayList<OcrResultModel> {
        Log.d("OCRPredictorNative", "开始 OCR 识别")
        val rawResults = forward(nativePointer, bitmap, maxSizeLen, runDet, runCls, runRec)
        val results = postprocess(rawResults)
        Log.d("OCRPredictorNative", "识别完成，结果数: ${results.size}")
        return results
    }
    
    fun destroy() {
        if (nativePointer != 0L) {
            release(nativePointer)
            nativePointer = 0
            Log.i("OCRPredictorNative", "✅ 资源已释放")
        }
    }
    
    private external fun init(
        detModelPath: String,
        recModelPath: String,
        clsModelPath: String,
        useOpencl: Int,
        threadNum: Int,
        cpuMode: String
    ): Long

    private external fun forward(
        pointer: Long,
        bitmap: Bitmap,
        maxSizeLen: Int,
        runDet: Int,
        runCls: Int,
        runRec: Int
    ): FloatArray

    private external fun release(pointer: Long)

    private fun postprocess(raw: FloatArray): ArrayList<OcrResultModel> {
        val results = ArrayList<OcrResultModel>()
        
        Log.d("OCRPredictorNative", "🔍 原始数据分析: FloatArray 大小 ${raw.size}")
        if (raw.isNotEmpty()) {
            Log.d("OCRPredictorNative", "  前 10 个值: ${raw.take(minOf(10, raw.size)).joinToString(",")}")
        }
        
        if (raw.isEmpty()) {
            Log.w("OCRPredictorNative", "❌ JNI 返回空数组!")
            return results
        }
        
        var begin = 0
        var itemCount = 0
        
        while (begin < raw.size) {
            try {
                if (begin + 1 >= raw.size) {
                    Log.w("OCRPredictorNative", "⚠️ 数据不足，无法读取 pointNum/wordNum")
                    break
                }
                
                val pointNum = raw[begin].toInt()
                val wordNum = raw[begin + 1].toInt()
                
                Log.d("OCRPredictorNative", "项目 $itemCount: pointNum=$pointNum wordNum=$wordNum begin=$begin")
                
                // 检查是否有足够的数据
                val requiredSize = begin + 2 + pointNum * 2 + wordNum + 2
                if (requiredSize > raw.size) {
                    Log.w("OCRPredictorNative", "⚠️ 数据不足: 需要 $requiredSize，实际 ${raw.size}")
                    break
                }
                
                val res = parse(raw, begin + 2, pointNum, wordNum)
                begin += 2 + 1 + pointNum * 2 + wordNum + 2
                results.add(res)
                itemCount++
            } catch (e: Exception) {
                Log.e("OCRPredictorNative", "❌ 解析项目 $itemCount 失败: ${e.message}", e)
                break
            }
        }
        
        Log.d("OCRPredictorNative", "✅ 解析完成: 共 $itemCount 项")
        return results
    }

    // ✅ 按照官方实现 - 只提取数据，不转换字符
    private fun parse(raw: FloatArray, begin: Int, pointNum: Int, wordNum: Int): OcrResultModel {
        var current = begin
        val res = OcrResultModel()
        
        res.confidence = raw[current]
        current++
        
        for (i in 0 until pointNum) {
            res.addPoints(
                raw[current + i * 2].toInt(),
                raw[current + i * 2 + 1].toInt()
            )
        }
        current += (pointNum * 2)
        
        // ✅ 只保存 wordIndex，不转换字符
        for (i in 0 until wordNum) {
            val index = raw[current + i].toInt()
            res.addWordIndex(index)
        }
        current += wordNum
        
        res.clsIdx = raw[current]
        res.clsConfidence = raw[current + 1]
        
        Log.d("OCRPredictorNative", "解析完成: 词数=$wordNum")
        return res
    }
}
