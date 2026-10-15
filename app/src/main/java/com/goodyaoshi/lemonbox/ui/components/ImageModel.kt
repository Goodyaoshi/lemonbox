package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.goodyaoshi.lemonbox.data.imagecloud.ImageUrlResolver

/**
 * 供 Compose 取用 [ImageUrlResolver]。
 *
 * 放在 ui 层而非紧挨 [ImageUrlResolver]：CompositionLocal 属 UI 概念，放进 data 层会让
 * 纯数据模块反向依赖 Compose；项目内同类设施（LocalAppSnackbar 等）也都在 ui/components。
 * 默认 null，预览等无 Hilt 容器的场景静默降级为「只认本地路径」，不会因缺依赖而崩。
 */
val LocalImageUrlResolver = staticCompositionLocalOf<ImageUrlResolver?> { null }

/**
 * 把图片内容哈希解析成图片加载器可直接使用的 model（F22）。
 *
 * 用 `remember` 缓存：[hash] 变化（换图 / 上传完成）时重算，不在每次重组时重跑。
 * 未提供解析器时（预览等无 Hilt 容器的场景）返回 null，由占位态接管。
 */
@Composable
fun rememberImageModel(hash: String?): Any? {
    val resolver = LocalImageUrlResolver.current ?: return null
    return remember(resolver, hash) {
        resolver.modelForHash(hash)
    }
}
