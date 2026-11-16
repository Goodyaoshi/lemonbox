package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.goodyaoshi.lemonbox.data.imagecloud.ImageUrlResolver
import com.goodyaoshi.lemonbox.data.settings.ImageLoadingMode

/**
 * 供 Compose 取用 [ImageUrlResolver]。
 *
 * 放在 ui 层而非紧挨 [ImageUrlResolver]：CompositionLocal 属 UI 概念，放进 data 层会让
 * 纯数据模块反向依赖 Compose；项目内同类设施（LocalAppSnackbar 等）也都在 ui/components。
 * 默认 null，预览等无 Hilt 容器的场景静默降级为「只认本地路径」，不会因缺依赖而崩。
 */
val LocalImageUrlResolver = staticCompositionLocalOf<ImageUrlResolver?> { null }

/**
 * 缩略图「取不到图」的原因（F-18）。
 *
 * 两种原因的出路完全不同，不能合并成一句「未在本机」：
 * 解析不到来源时用户能做的只有登录 / 切换模式；已经解析到来源却加载失败时，
 * 图片其实就在云端（甚至就在本机），该做的是重试，而不是让用户去登录。
 */
enum class ImageMissReason {
    /** 本机没有这张图，且当前模式也不去云端取（未登录、省流量等）。 */
    NOT_ON_DEVICE,

    /** 已解析到可加载的来源，但加载失败（断网、预签名失败、本地文件损坏等）。 */
    LOAD_FAILED
}

/**
 * 当前设备的图片加载策略（标准 / 省流量 / 省存储 / 无图）。
 * 默认标准模式，未注入时行为与改造前一致。
 */
val LocalImageLoadingMode = staticCompositionLocalOf { ImageLoadingMode.STANDARD }

/**
 * 当前包是否支持云端图片（对应 BuildConfig.IMAGE_CLOUD_SUPPORTED）。
 *
 * debug 包恒为不支持，也没有可拉取的云端副本；缺图文案据此如实说明，
 * 不再让用户去登录一个走不通的开关。默认 true：预览等未注入场景沿用正式包口径。
 */
val LocalImageCloudSupported = staticCompositionLocalOf { true }

/**
 * 把图片内容哈希解析成图片加载器可直接使用的 model（F22）。
 *
 * 用 `remember` 缓存：[hash] 或加载策略变化（换图 / 上传完成 / 用户切换模式）时重算，
 * 不在每次重组时重跑。未提供解析器时（预览等无 Hilt 容器的场景）返回 null，由占位态接管。
 */
@Composable
fun rememberImageModel(hash: String?): Any? {
    val resolver = LocalImageUrlResolver.current ?: return null
    val mode = LocalImageLoadingMode.current
    return remember(resolver, hash, mode) {
        resolver.modelForHash(hash, mode)
    }
}
