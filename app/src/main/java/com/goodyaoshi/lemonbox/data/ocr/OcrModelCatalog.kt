package com.goodyaoshi.lemonbox.data.ocr

import android.content.Context
import java.io.File

/**
 * 离线 OCR 模型安装路径与固定文件名（F01）。
 *
 * 模型文件不随 APK 分发（体积大），改为在用户显式确认后从官方百度 BOS 直链下载；
 * 下载地址与校验值固定在 [OcrModelTier]，识别字典随 APK 走 assets，无需远程清单。
 */
object OcrModelCatalog {

    /** 解压后的检测模型文件名（各档位安装目录内同名）。 */
    const val DET_FILE = "det.onnx"

    /** 解压后的识别模型文件名。 */
    const val REC_FILE = "rec.onnx"

    /** 模型安装目录：`noBackupFilesDir/ocr-models/{版本}`，不参与系统备份、随卸载清除。 */
    fun modelDir(context: Context, tier: OcrModelTier): File =
        File(File(context.noBackupFilesDir, "ocr-models"), tier.modelVersion)

    /** 安装完成标记文件，记录版本与各分发包校验值。 */
    fun markerFile(context: Context, tier: OcrModelTier): File =
        File(modelDir(context, tier), "installed.json")
}
