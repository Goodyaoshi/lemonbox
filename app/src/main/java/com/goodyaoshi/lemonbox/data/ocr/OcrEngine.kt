package com.goodyaoshi.lemonbox.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/** 一行识别结果：文本、置信度、在原图中的位置。 */
data class OcrLine(val text: String, val confidence: Float, val box: Rect)

/** 整张图片的识别结果。 */
data class OcrResult(val lines: List<OcrLine>, val fullText: String)

/** 离线文字识别引擎抽象，便于替换实现与测试。 */
interface OcrEngine {

    /** 模型是否已就绪（已下载并校验）。 */
    fun isReady(): Boolean

    /** 识别一张图片，返回按阅读顺序排列的文本行。 */
    suspend fun recognize(bitmap: Bitmap): OcrResult
}
