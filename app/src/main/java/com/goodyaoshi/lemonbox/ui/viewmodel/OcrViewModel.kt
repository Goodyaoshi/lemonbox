package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.ocr.ItemSuggestion
import com.goodyaoshi.lemonbox.data.ocr.LedgerSuggestion
import com.goodyaoshi.lemonbox.data.ocr.OcrEngine
import com.goodyaoshi.lemonbox.data.ocr.OcrLine
import com.goodyaoshi.lemonbox.data.ocr.OcrModelManager
import com.goodyaoshi.lemonbox.data.ocr.OcrModelState
import com.goodyaoshi.lemonbox.data.ocr.OcrTextParser
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** F01 文字识别页的可变状态：识别中的进度、选中的图片、识别结果与抽取出的建议。 */
data class OcrUiState(
    val isRecognizing: Boolean = false,
    val imagePath: String? = null,
    val lines: List<OcrLine> = emptyList(),
    val fullText: String = "",
    val ledgerSuggestion: LedgerSuggestion? = null,
    val itemSuggestion: ItemSuggestion? = null,
    val error: String? = null
)

/**
 * 文字识别页 ViewModel（F01/F02/F03）。
 *
 * 模型下载一律走 [OcrModelManager] 的显式接口，本类不做任何自动/后台触发，
 * 只在用户点击并确认后调用 [startDownload]，下载进度与取消能力都透传给 UI。
 */
@HiltViewModel
class OcrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: OcrModelManager,
    private val engine: OcrEngine
) : ViewModel() {

    /** 模型安装状态，直接透传管理器，UI 据此展示下载/进度/已就绪。 */
    val modelState: StateFlow<OcrModelState> = modelManager.state

    /** 是否已配置模型下载地址（编译期决定），未配置时 UI 禁用下载入口并给出说明。 */
    val isConfigured: Boolean get() = modelManager.isConfigured

    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    /** 当前是否处于移动数据网络，供下载前确认文案使用。 */
    fun isMeteredConnection(): Boolean = modelManager.isMeteredConnection()

    fun startDownload() = modelManager.startDownload()

    fun cancelDownload() = modelManager.cancelDownload()

    fun deleteModel() {
        modelManager.delete()
        resetResult()
    }

    /** 识别一张图片：保存到内部路径 → 降采样解码 → 离线识别 → 抽取记账/入库建议。 */
    fun recognize(uri: Uri) {
        if (_uiState.value.isRecognizing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRecognizing = true, error = null)
            try {
                val path = withContext(Dispatchers.IO) { ImageUtil.saveImageToInternal(context, uri) }
                if (path == null) {
                    fail("这张图片读不出来，换一张再试试")
                    return@launch
                }
                if (!engine.isReady()) {
                    fail("识别模型还没准备好，先下载模型")
                    return@launch
                }
                val bitmap = withContext(Dispatchers.IO) {
                    ImageUtil.decodeDownsampledBitmap(path, MAX_IMAGE_SIZE)
                }
                if (bitmap == null) {
                    fail("图片解析失败，换一张再试试")
                    return@launch
                }
                val result = try {
                    engine.recognize(bitmap)
                } finally {
                    bitmap.recycle()
                }

                val previous = _uiState.value.imagePath
                if (previous != null && previous != path) ImageUtil.deleteImage(previous)
                _uiState.value = OcrUiState(
                    isRecognizing = false,
                    imagePath = path,
                    lines = result.lines,
                    fullText = result.fullText,
                    ledgerSuggestion = OcrTextParser.parseLedger(result.fullText),
                    itemSuggestion = OcrTextParser.parseItem(result.fullText)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message?.takeIf { it.isNotBlank() } ?: "识别失败，请重试")
            }
        }
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** 清空识别结果（用于重新识别或删除模型后）。 */
    fun resetResult() {
        _uiState.value = OcrUiState()
    }

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(isRecognizing = false, error = message)
    }

    private companion object {
        /** 识别前的最长边上限：兼顾清晰度与内存占用（检测侧仍会再缩到 960）。 */
        const val MAX_IMAGE_SIZE = 1600
    }
}
