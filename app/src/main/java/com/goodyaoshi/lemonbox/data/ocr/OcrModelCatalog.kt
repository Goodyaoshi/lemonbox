package com.goodyaoshi.lemonbox.data.ocr

import android.content.Context
import com.goodyaoshi.lemonbox.BuildConfig
import java.io.File

/**
 * 离线 OCR 模型清单（F01）。
 *
 * 模型文件不随 APK 分发（体积大），改为在用户显式确认后从服务端下载。
 * 下载地址由 `local.properties` 的 `lemonOcrModelBaseUrl` 注入，编译期写入
 * [BuildConfig.OCR_MODEL_BASE_URL]；未配置时 [isConfigured] 为 false，
 * 应用内会明确提示「未配置下载地址」，绝不会静默失败。
 *
 * 服务端需在 [manifestUrl] 提供 `manifest.json`，声明每个文件的大小与 SHA-256，
 * 供客户端下载后校验完整性（详见 [OcrModelManager]）。
 */
object OcrModelCatalog {

    /** 模型基址（去掉结尾斜杠）。空串表示尚未配置。 */
    val baseUrl: String = BuildConfig.OCR_MODEL_BASE_URL.trim().removeSuffix("/")

    /** 是否已配置模型下载地址。 */
    val isConfigured: Boolean = baseUrl.isNotEmpty()

    /** 模型版本号：变更模型文件时递增，用于分目录存放与失效判断。 */
    const val MODEL_VERSION = "ppocr-v6"

    /** 文字检测模型（PP-OCRv6 tiny，ONNX）。 */
    const val DET_FILE = "PP-OCRv6_tiny_det.onnx"

    /** 文字识别模型（PP-OCRv6 tiny，ONNX）。 */
    const val REC_FILE = "PP-OCRv6_tiny_rec.onnx"

    /** 识别字典（每行一个字符，取自 v6 rec 模型 inference.yml 的 character_dict）。 */
    const val DICT_FILE = "ppocrv6_dict.txt"

    /** 必须齐备的模型文件。 */
    val requiredFiles: List<String> = listOf(DET_FILE, REC_FILE, DICT_FILE)

    /** 服务端清单地址。 */
    val manifestUrl: String get() = "$baseUrl/manifest.json"

    /** 单个模型文件的下载地址。 */
    fun fileUrl(name: String): String = "$baseUrl/$name"

    /** 模型安装目录：`noBackupFilesDir/ocr-models/{版本}`，不参与系统备份、随卸载清除。 */
    fun modelDir(context: Context): File =
        File(File(context.noBackupFilesDir, "ocr-models"), MODEL_VERSION)

    /** 安装完成标记文件，记录版本与各文件校验值。 */
    fun markerFile(context: Context): File = File(modelDir(context), "installed.json")
}
