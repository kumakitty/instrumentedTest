package com.baidu.paddle.lite.demo.ocr

import android.graphics.Point

/**
 * OCR 识别结果模型
 * 包含检测到的文本及其几何信息
 */
data class OcrResultModel(
    val points: MutableList<Point> = mutableListOf(),
    val wordIndex: MutableList<Int> = mutableListOf(),
    var label: String = "",
    var confidence: Float = 0f,
    var clsIdx: Float = 0f,
    var clsLabel: String = "",
    var clsConfidence: Float = 0f
) {
    fun addPoints(x: Int, y: Int) {
        points.add(Point(x, y))
    }
    
    fun addWordIndex(index: Int) {
        wordIndex.add(index)
    }
}
