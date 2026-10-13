package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.goodyaoshi.lemonbox.data.imagecloud.ImageUrlResolver

/**
 * 供 Compose 取用 [ImageUrlResolver]。
 *
 * 放在 ui 层而不是紧挨着 [ImageUrlResolver]：CompositionLocal 属于 UI 概念，
 * 把它放进 data 层会让纯数据模块反向依赖 Compose。项目内的同类设施
 * （`LocalAppSnackbar`、`LocalItemStatusOptions`）也都在 ui/components，此处保持一致。
 *
 * 默认 null：预览等没有 Hilt 容器的场景静默降级为「只认本地路径」，
 * 与图片云端存储引入之前的行完全一致，不会因为缺依赖而崩。
 */
val LocalImageUrlResolver = staticCompositionLocalOf<ImageUrlResolver?> { null }

/**
 * 把「本地路径 + 云端对象键」解析成图片加载器可直接使用的 model。
 *
 * 用 `remember` 缓存：`model()` 内部要判断本地文件是否存在、必要时还要生成预签名 URL，
 * 不该在每次重组时重跑。[localPath] / [objectKey] 变化（例如刚上传成功）时重算。
 *
 * 未提供解析器时直接返回本地路径，行为与该功能引入之前一致。
 */
@Composable
fun rememberImageModel(localPath: String?, objectKey: String?): Any? {
    val resolver = LocalImageUrlResolver.current ?: return localPath
    return remember(resolver, localPath, objectKey) {
        resolver.model(localPath, objectKey)
    }
}
