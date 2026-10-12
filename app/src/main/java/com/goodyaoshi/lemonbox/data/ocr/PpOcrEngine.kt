package com.goodyaoshi.lemonbox.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 PP-OCRv4（ONNX Runtime）的离线识别引擎。
 *
 * 流程：DB 检测（[detect]）得到文本框 → 逐框裁剪缩放（[recognizeBox]）→ CTC 贪心解码。
 *
 * 说明（与报告原描述的偏差）：
 * - **不引入 OpenCV**：检测后处理用纯 Kotlin 连通域求轴对齐框，避免额外原生依赖；
 * - **不做 180° 方向分类（cls）**，正常拍摄的收据/小票场景足够。
 */
@Singleton
class PpOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: OcrModelManager
) : OcrEngine {

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null

    /** 识别字符表：`["blank"] + 字典 + [" "]`。 */
    private var character: List<String> = emptyList()

    override fun isReady(): Boolean = modelManager.isInstalled()

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        ensureLoaded()
        val det = detSession!!
        val rec = recSession!!
        val boxes = detect(det, bitmap)
        val lines = boxes.mapNotNull { box -> runCatching { recognizeBox(rec, bitmap, box) }.getOrNull() }
        OcrResult(lines = lines, fullText = lines.joinToString("\n") { it.text })
    }

    private fun ensureLoaded() {
        if (detSession != null && recSession != null && character.isNotEmpty()) return
        synchronized(lock) {
            if (detSession != null && recSession != null && character.isNotEmpty()) return
            val environment = OrtEnvironment.getEnvironment()
            env = environment
            character = loadCharacter()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(2)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                detSession = environment.createSession(
                    modelManager.filePath(OcrModelCatalog.DET_FILE).absolutePath, options
                )
                recSession = environment.createSession(
                    modelManager.filePath(OcrModelCatalog.REC_FILE).absolutePath, options
                )
            } finally {
                options.close()
            }
        }
    }

    private fun loadCharacter(): List<String> {
        val file = modelManager.filePath(OcrModelCatalog.DICT_FILE)
        if (!file.exists()) return emptyList()
        val dict = file.readLines().filter { it.isNotEmpty() }
        return listOf(BLANK) + dict + listOf(" ")
    }

    // ---------------------------------------------------------------- 检测

    /** DB 检测：返回原图坐标系下、按阅读顺序排列的文本框。 */
    private fun detect(session: OrtSession, bitmap: Bitmap): List<Rect> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()

        val maxSide = maxOf(srcW, srcH)
        val ratio = if (maxSide > DET_LIMIT) DET_LIMIT.toDouble() / maxSide else 1.0
        var inputH = Math.round(srcH * ratio).toInt().coerceAtLeast(MIN_SIDE)
        var inputW = Math.round(srcW * ratio).toInt().coerceAtLeast(MIN_SIDE)
        inputH = (inputH + ALIGN - 1) / ALIGN * ALIGN
        inputW = (inputW + ALIGN - 1) / ALIGN * ALIGN

        val prob = runDet(session, bitmap, inputW, inputH)
        val inverseRatio = 1.0 / ratio

        // 二值化 + 连通域
        val mask = BooleanArray(inputH * inputW)
        var hasText = false
        for (i in mask.indices) {
            if (prob[i] > DET_THRESHOLD) {
                mask[i] = true
                hasText = true
            }
        }
        if (!hasText) return emptyList()

        val visited = BooleanArray(inputH * inputW)
        val stack = IntArray(inputH * inputW)
        val boxes = ArrayList<Rect>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = inputW
            var minY = inputH
            var maxX = 0
            var maxY = 0
            var area = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val x = cur % inputW
                val y = cur / inputW
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= inputH) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= inputW) continue
                        val n = ny * inputW + nx
                        if (mask[n] && !visited[n]) {
                            visited[n] = true
                            stack[sp++] = n
                        }
                    }
                }
            }
            if (area < MIN_AREA) continue
            val h = maxY - minY + 1
            if (h * inverseRatio < MIN_TEXT_HEIGHT) continue

            val w = maxX - minX + 1
            val boxArea = w * h
            val perimeter = 2 * (w + h)
            val offset = (boxArea.toDouble() * UNCLIP_RATIO / perimeter).toInt().coerceIn(1, MAX_UNCLIP)

            val x0 = (minX - offset).coerceAtLeast(0)
            val y0 = (minY - offset).coerceAtLeast(0)
            val x1 = (maxX + offset).coerceAtMost(inputW - 1)
            val y1 = (maxY + offset).coerceAtMost(inputH - 1)

            val left = (x0 * inverseRatio).toInt().coerceIn(0, srcW - 1)
            val top = (y0 * inverseRatio).toInt().coerceIn(0, srcH - 1)
            val right = ((x1 + 1) * inverseRatio).toInt().coerceIn(left + 1, srcW)
            val bottom = ((y1 + 1) * inverseRatio).toInt().coerceIn(top + 1, srcH)
            boxes.add(Rect(left, top, right, bottom))
        }
        return sortReadingOrder(boxes)
    }

    /** 执行检测推理，返回 [inputH]×[inputW] 的概率图。 */
    private fun runDet(session: OrtSession, bitmap: Bitmap, inputW: Int, inputH: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)
        val pixels = IntArray(inputW * inputH)
        scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        if (scaled !== bitmap) scaled.recycle()

        val channelSize = inputH * inputW
        val buffer = FloatBuffer.allocate(3 * channelSize)
        for (i in 0 until channelSize) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buffer.put(i, (r - DET_MEAN[0]) / DET_STD[0])
            buffer.put(channelSize + i, (g - DET_MEAN[1]) / DET_STD[1])
            buffer.put(2 * channelSize + i, (b - DET_MEAN[2]) / DET_STD[2])
        }
        buffer.rewind()

        val tensor = OnnxTensor.createTensor(
            env, buffer, longArrayOf(1L, 3L, inputH.toLong(), inputW.toLong())
        )
        try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val map = (result.get(0) as OnnxTensor).value as Array<Array<Array<FloatArray>>>
                val rows = map[0][0]
                val out = FloatArray(channelSize)
                for (y in 0 until inputH) {
                    System.arraycopy(rows[y], 0, out, y * inputW, inputW)
                }
                return out
            }
        } finally {
            tensor.close()
        }
    }

    /** 按阅读顺序（自上而下、同行自左向右）排序。 */
    private fun sortReadingOrder(boxes: List<Rect>): List<Rect> {
        if (boxes.size <= 1) return boxes
        val avgHeight = boxes.sumOf { it.height() }.toDouble() / boxes.size
        val tolerance = (avgHeight * 0.5).toInt().coerceAtLeast(1)
        return boxes.sortedWith(compareBy({ it.top / tolerance }, { it.left }))
    }

    // ---------------------------------------------------------------- 识别

    /** 裁剪单个文本框并识别。 */
    private fun recognizeBox(session: OrtSession, src: Bitmap, box: Rect): OcrLine? {
        val cropW = box.width()
        val cropH = box.height()
        if (cropW <= 0 || cropH <= 0) return null

        val crop = Bitmap.createBitmap(src, box.left, box.top, cropW, cropH)
        var recW = Math.round(recWidthRatio(cropW, cropH)).toInt().coerceAtLeast(1)
        if (recW > REC_MAX_WIDTH) recW = REC_MAX_WIDTH
        val scaled = Bitmap.createScaledBitmap(crop, recW, REC_HEIGHT, true)
        if (scaled !== crop) crop.recycle()
        val pixels = IntArray(recW * REC_HEIGHT)
        scaled.getPixels(pixels, 0, recW, 0, 0, recW, REC_HEIGHT)
        if (scaled !== src) scaled.recycle()

        val channelSize = REC_HEIGHT * recW
        val buffer = FloatBuffer.allocate(3 * channelSize)
        for (i in 0 until channelSize) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buffer.put(i, (r - 0.5f) / 0.5f)
            buffer.put(channelSize + i, (g - 0.5f) / 0.5f)
            buffer.put(2 * channelSize + i, (b - 0.5f) / 0.5f)
        }
        buffer.rewind()

        val tensor = OnnxTensor.createTensor(
            env, buffer, longArrayOf(1L, 3L, REC_HEIGHT.toLong(), recW.toLong())
        )
        try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                val (text, confidence) = ctcDecode(logits[0])
                if (text.isBlank()) return null
                return OcrLine(text = text, confidence = confidence, box = box)
            }
        } finally {
            tensor.close()
        }
    }

    private fun recWidthRatio(w: Int, h: Int): Double = w.toDouble() * REC_HEIGHT / h

    /** CTC 贪心解码：跳过 blank 与重复，置信度取已选字符概率均值。 */
    private fun ctcDecode(logits: Array<FloatArray>): Pair<String, Float> {
        if (logits.isEmpty() || character.isEmpty()) return "" to 0f
        val sb = StringBuilder()
        var prev = -1
        var sum = 0f
        var count = 0
        for (row in logits) {
            var best = 0
            var bestVal = row[0]
            for (c in 1 until row.size) {
                if (row[c] > bestVal) {
                    bestVal = row[c]
                    best = c
                }
            }
            if (best != 0 && best != prev) {
                character.getOrNull(best)?.let { sb.append(it) }
                sum += bestVal
                count++
            }
            prev = best
        }
        val confidence = if (count > 0) sum / count else 0f
        return sb.toString() to confidence
    }

    private companion object {
        const val BLANK = "blank"

        /** 检测输入最长边上限。 */
        const val DET_LIMIT = 960

        /** 输入宽高对齐到 32 的倍数。 */
        const val ALIGN = 32

        /** 输入宽高下限。 */
        const val MIN_SIDE = 32

        /** 概率阈值。 */
        const val DET_THRESHOLD = 0.3f

        /** 连通域最小像素数。 */
        const val MIN_AREA = 3

        /** 过滤过矮的文本框（原图像素）。 */
        const val MIN_TEXT_HEIGHT = 6.0

        /** DB unclip 扩边比例与上限。 */
        const val UNCLIP_RATIO = 1.5
        const val MAX_UNCLIP = 20

        /** 识别输入高度与最大宽度。 */
        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 320

        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
