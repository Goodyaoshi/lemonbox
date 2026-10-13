@file:Suppress("DEPRECATION")
package com.goodyaoshi.lemonbox.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 从系统图库选图的统一入口。
 *
 * 为什么需要这一层：`ActivityResultContracts.PickVisualMedia` 会按设备能力三级回落
 * （androidx.activity 的字节码里同时含下列三个 action，为证）：
 *   1. `android.provider.action.PICK_IMAGES` —— Android 13+ 原生 Photo Picker；
 *   2. `com.google.android.gms.provider.action.PICK_IMAGES` —— GMS backport；
 *   3. 都不满足时回落 `android.intent.action.OPEN_DOCUMENT` —— 这是**文档浏览器**，
 *      侧边栏往往只有「最近 / 下载」，看不到相册里的照片，与「从相册选」的语义完全不符。
 * 旧设备（Android ≤ 12 且无 GMS backport）就落在第 3 级。
 *
 * 因此这里做自适应：
 *   - Photo Picker 可用 → 用它：无需任何权限，返回带临时授权的 URI；
 *   - 不可用 → 申请媒体读取权限后，用 `ACTION_PICK` 打开系统图库应用
 *     （返回 `content://media/...`，不带授权，故必须持有读取权限）。
 *
 * 注意：旧设备回落路径 `ACTION_PICK` **只支持单选**，[allowMultiple] 在该路径下降级为单选。
 */

/** 旧设备读取 MediaStore 图片所需的运行时权限：Android 13 起按媒体类型细分。 */
val Context.galleryReadPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** 系统 Photo Picker 是否可用；可用时选图完全不需要权限。 */
fun Context.hasSystemPhotoPicker(): Boolean =
    ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)

/** 是否已持有媒体读取权限。 */
fun Context.hasGalleryReadPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, galleryReadPermission) ==
        PackageManager.PERMISSION_GRANTED

/**
 * `ACTION_PICK`：直接打开系统图库应用选一张图（旧设备路径，需要媒体读取权限）。
 * 与 `GetContent` / `OpenDocument` 不同，它不会落到文档浏览器，而是图库本身的界面。
 */
class PickImageFromGallery : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * 返回一个「打开图库」的启动函数，调用即进图库：
 *   - 有 Photo Picker → 直接进系统图库选择器；
 *   - 没有 → 先按需申请权限，拿到后再进系统图库。
 *
 * [onPermissionDenied] 在旧设备且用户拒绝授权时回调，供界面给出提示。
 */
@Composable
fun rememberGalleryPickerLauncher(
    allowMultiple: Boolean = false,
    onPicked: (List<Uri>) -> Unit,
    onPermissionDenied: () -> Unit = {}
): () -> Unit {
    val context = LocalContext.current
    val usePhotoPicker = remember(context) { context.hasSystemPhotoPicker() }

    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPicked(listOf(uri)) }

    val multiplePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }

    val galleryPicker = rememberLauncherForActivityResult(PickImageFromGallery()) { uri ->
        if (uri != null) onPicked(listOf(uri))
    }

    // ACTION_PICK 依赖设备上的图库应用；万一没有处理器，退回系统文件选择器，避免直接崩溃。
    val contentFallback = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onPicked(listOf(uri)) }

    fun openGallery() {
        runCatching { galleryPicker.launch(Unit) }
            .onFailure { contentFallback.launch("image/*") }
    }

    // 旧设备路径：先要权限，拿到后再进图库。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) openGallery() else onPermissionDenied() }

    return {
        if (usePhotoPicker) {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (allowMultiple) multiplePicker.launch(request) else singlePicker.launch(request)
        } else if (context.hasGalleryReadPermission()) {
            openGallery()
        } else {
            permissionLauncher.launch(context.galleryReadPermission)
        }
    }
}
