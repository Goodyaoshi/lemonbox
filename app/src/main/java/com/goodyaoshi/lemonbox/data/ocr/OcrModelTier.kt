package com.goodyaoshi.lemonbox.data.ocr

/**
 * 识别模型档位（F01）：同一套 PP-OCRv6 引擎提供两档官方 ONNX 模型，
 * 由用户在界面按「体积 / 精度」自行选择，全部从百度 BOS 官方直链下载（国内可达，无需加速）。
 *
 * 分发包（tar）的大小与 SHA-256 在编译期固定，对应 paddle3.0.0 的官方发布物：
 * 下载后先校验再解压，杜绝损坏或被篡改的模型进入推理流程；识别字典随 APK 打包在 assets，
 * 与模型档位一一对应。
 */
enum class OcrModelTier(
    val id: String,
    val displayName: String,
    val summary: String,
    val dictAsset: String,
    val downloads: List<OcrModelDownload>
) {
    TINY(
        id = "tiny",
        displayName = "轻量版",
        summary = "下载快、省空间，日常小票识别够用",
        dictAsset = "ocr/ppocrv6_tiny_dict.txt",
        downloads = listOf(
            OcrModelDownload(
                url = "$BOS_BASE/PP-OCRv6_tiny_det_onnx_infer.tar",
                size = 1_792_000L,
                sha256 = "ff6ab415b0a6e0c488550f2fb5d5046f1719848df220b2dc21b56402a65bc05d",
                entry = "PP-OCRv6_tiny_det_onnx_infer/inference.onnx",
                fileName = OcrModelCatalog.DET_FILE
            ),
            OcrModelDownload(
                url = "$BOS_BASE/PP-OCRv6_tiny_rec_onnx_infer.tar",
                size = 4_526_080L,
                sha256 = "1e13b22717b1edd89d4cde4fda272b6c17d5b505c97c2baea99da1a3a2d54b29",
                entry = "PP-OCRv6_tiny_rec_onnx_infer/inference.onnx",
                fileName = OcrModelCatalog.REC_FILE
            )
        )
    ),

    SMALL(
        id = "small",
        displayName = "精准版",
        summary = "精度更高，复杂版面与密集小字更稳",
        dictAsset = "ocr/ppocrv6_small_dict.txt",
        downloads = listOf(
            OcrModelDownload(
                url = "$BOS_BASE/PP-OCRv6_small_det_onnx_infer.tar",
                size = 9_891_840L,
                sha256 = "d218f6fbf0f1c23d2161bd6ac7f5eaa6104fa89955c09290497e31008e2618e4",
                entry = "PP-OCRv6_small_det_onnx_infer/inference.onnx",
                fileName = OcrModelCatalog.DET_FILE
            ),
            OcrModelDownload(
                url = "$BOS_BASE/PP-OCRv6_small_rec_onnx_infer.tar",
                size = 21_319_680L,
                sha256 = "d267ab077a44a0eedb1ea8f8c542d263f211de8e9d7a029bf9fcfff7e5a88fb1",
                entry = "PP-OCRv6_small_rec_onnx_infer/inference.onnx",
                fileName = OcrModelCatalog.REC_FILE
            )
        )
    );

    /** 安装目录名：与官方发布物绑定，两档独立存放互不干扰。 */
    val modelVersion: String get() = "ppocr-v6-$id"

    /** 下载总体积（全部分发包之和），供确认弹窗展示。 */
    val totalBytes: Long get() = downloads.sumOf { it.size }

    companion object {

        /** 默认档位：轻量版，首次使用下载成本最低。 */
        val DEFAULT = TINY

        /** 从持久化字符串还原档位，未知值回落默认。 */
        fun fromId(raw: String?): OcrModelTier =
            entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * 单个模型分发包：官方 tar 直链与编译期固定的完整性校验值。
 * 解压时从 tar 内按 [entry] 精确提取 ONNX 模型，落盘为 [fileName]。
 */
data class OcrModelDownload(
    val url: String,
    val size: Long,
    val sha256: String,
    val entry: String,
    val fileName: String
)

private const val BOS_BASE =
    "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
