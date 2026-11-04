package com.goodyaoshi.lemonbox.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.analyze.Analyzer
import com.king.zxing.BarcodeCameraScanActivity
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import com.king.zxing.analyze.MultiFormatAnalyzer
import com.goodyaoshi.lemonbox.R
import com.goodyaoshi.lemonbox.util.BrandCopy

/** 扫码返回的条形码内容所在的 Extra Key。 */
const val EXTRA_BARCODE = "com.goodyaoshi.lemonbox.extra.BARCODE"

/** 用户拒绝相机权限时带回的标记：调用方据此给出「去哪里开启」的说明。 */
const val EXTRA_CAMERA_DENIED = "com.goodyaoshi.lemonbox.extra.CAMERA_DENIED"

/** 相机权限被拒绝后的统一说明文案（扫码入口共三处：首页、快速录入、编辑）。 */
fun scanPermissionDeniedMessage(): String =
    "扫一扫需要相机权限。可到系统设置 › 应用 › ${BrandCopy.APP_NAME} › 权限 里打开「相机」后再试。"

/**
 * 条形码扫描页；基于 ZXingLite 的 [BarcodeCameraScanActivity]。
 *
 * 库自带的布局是黑底 + 青色取景框，没有标题、提示和返回键，进入后像另一个 App。
 * 这里换成自绘的 `activity_scan.xml`，并把取景框改到品牌叶绿；
 * 三个 id 通过 getter 指向自己的资源，避免跨模块 R.id 数值不一致。
 */
class ScanActivity : BarcodeCameraScanActivity() {

    override fun getLayoutId(): Int = R.layout.activity_scan

    override fun getPreviewViewId(): Int = R.id.scan_preview

    override fun getFlashlightId(): Int = R.id.scan_flashlight

    override fun getViewfinderViewId(): Int = R.id.scan_viewfinder

    override fun initUI() {
        super.initUI()
        findViewById<View>(R.id.scan_back)?.setOnClickListener { finish() }
        findViewById<com.king.view.viewfinderview.ViewfinderView>(R.id.scan_viewfinder)?.apply {
            setFrameColor(FRAME_COLOR)
            setFrameCornerColor(FRAME_COLOR)
            // 扫描线用柠檬黄，和 App 主视觉一致，而不是库默认的青蓝。
            setLaserColor(LASER_COLOR)
            setMaskColor(MASK_COLOR)
            setLabelText("把条码或二维码放进框内")
            setLabelTextColor(Color.WHITE)
        }
    }

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

    /**
     * 相机权限被拒绝时，库会直接结束页面，调用方只拿到一个 RESULT_CANCELED——
     * 用户看到的是「点了扫一扫，页面闪一下就没了」，不知道是权限问题。
     * 这里补一个标记与系统设置入口，让调用方能把原因说清楚。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val cameraDenied = permissions.indices.any { index ->
            permissions[index] == Manifest.permission.CAMERA &&
                grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
        }
        if (cameraDenied) {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_CAMERA_DENIED, true))
            finish()
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private companion object {
        /** 取景框 / 四角：品牌亮叶绿（深底上足够醒目）。 */
        val FRAME_COLOR = 0xFF8FD06A.toInt()

        /** 扫描线：柠檬黄。 */
        val LASER_COLOR = 0xFFEFC22E.toInt()

        /** 取景框外的压暗遮罩。 */
        val MASK_COLOR = 0x99000000.toInt()
    }
}
