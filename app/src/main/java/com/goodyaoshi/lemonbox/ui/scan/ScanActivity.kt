package com.goodyaoshi.lemonbox.ui.scan

import android.content.Intent
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.analyze.Analyzer
import com.king.zxing.BarcodeCameraScanActivity
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import com.king.zxing.analyze.MultiFormatAnalyzer

/** 扫码返回的条形码内容所在的 Extra Key。 */
const val EXTRA_BARCODE = "com.goodyaoshi.lemonbox.extra.BARCODE"

/**
 * 条形码扫描页；基于 ZXingLite 的 [BarcodeCameraScanActivity]，
 * 复用其自带扫码布局，识别成功后将条码文本回传给调用方。
 */
class ScanActivity : BarcodeCameraScanActivity() {

    override fun createAnalyzer(): Analyzer<Result>? {
        val decodeConfig = DecodeConfig().apply {
            // 同时支持一维码（CODE_128 / EAN_13 / UPC_A 等）与二维码
            setHints(DecodeFormatManager.ALL_HINTS)
            setFullAreaScan(true)
        }
        return MultiFormatAnalyzer(decodeConfig)
    }

    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        // 停止分析，避免重复回调
        cameraScan.setAnalyzeImage(false)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, result.result.text))
        finish()
    }
}
