package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.goodyaoshi.lemonbox.data.settings.ImageLoadingMode
import com.goodyaoshi.lemonbox.ui.theme.OnScrim
import com.goodyaoshi.lemonbox.ui.theme.OnScrimScrim
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.ScrimStrong
import kotlinx.coroutines.launch

@Composable
fun ImagePreviewOverlay(
    imageHashes: List<String>,
    initialIndex: Int,
    onDismissRequest: () -> Unit,
    // 取不到图时用名称首字占位：全屏预览此前只铺一层黑底，
    // 没同步到本机的图点开就是一片空，看不出是「没图」还是「没加载完」。
    itemName: String = ""
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, imageHashes.lastIndex),
        pageCount = { imageHashes.size }
    )
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScrimStrong)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(hash = imageHashes[page], itemName = itemName)
            }

            PreviewIconButton(
                icon = Icons.Default.Close,
                contentDescription = "关闭预览",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp),
                onClick = onDismissRequest
            )

            if (pagerState.currentPage > 0) {
                PreviewIconButton(
                    icon = Icons.Default.ChevronLeft,
                    contentDescription = "上一张",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                )
            }

            if (pagerState.currentPage < imageHashes.lastIndex) {
                PreviewIconButton(
                    icon = Icons.Default.ChevronRight,
                    contentDescription = "下一张",
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                )
            }

            Text(
                text = "${pagerState.currentPage + 1}/${imageHashes.size}",
                // 内容色走语义令牌（F6）：遮罩恒为深色，此处恒为浅色令牌 [OnScrim]。
                color = OnScrim,
                // 字号走主题字阶（F6）：labelMedium 为 12sp。
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp)
                    .clip(CircleShape)
                    .background(OnScrimScrim)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    // 读屏可达（F8）：页码是翻页后的状态信息，补 liveRegion 让读屏播报「第几张」，
                    // 否则视障用户滑到下一张也不知道当前进度。
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "第 ${pagerState.currentPage + 1} 张，共 ${imageHashes.size} 张"
                    }
            )
        }
    }
}

@Composable
private fun ZoomableImage(hash: String, itemName: String) {
    // 预览页同样走统一解析：本地原图不在时从云端取，保证换设备也能放大看。
    val model = rememberImageModel(hash)
    val mode = LocalImageLoadingMode.current
    // 换图或换模式后重试：与列表缩略图的失败判定保持同一口径。
    var retryKey by remember(hash) { mutableIntStateOf(0) }
    var failed by remember(hash, model, retryKey) { mutableStateOf(false) }
    var scale by remember(hash) { mutableFloatStateOf(1f) }
    var offsetX by remember(hash) { mutableFloatStateOf(0f) }
    var offsetY by remember(hash) { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(hash) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    if (nextScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (model == null || failed) {
            // 取不到图时不铺空白：首字 + 一句能说明原因的提示，
            // 让「无图模式」和「图还没同步过来」在界面上可区分。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = itemName.trim().take(1).ifBlank { "?" },
                    style = MaterialTheme.typography.displayMedium,
                    color = OnScrim.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (mode) {
                        ImageLoadingMode.NO_IMAGE -> "当前是无图模式，可在「设置」里切换显示方式"
                        else -> "这张图还没同步到这台设备"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnScrim.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                // 只有「有图可拉、但这次没拉下来」才给重试；无图模式/未登录时重试没有意义。
                if (model != null) {
                    Text(
                        text = "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = OrangeStart,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OnScrimScrim)
                            .clickable { retryKey++ }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            // key 换掉旧实例，Coil 才会重新发起请求，而不是沿用上一次失败的画布。
            key(retryKey) {
                AsyncImage(
                    model = model,
                    contentDescription = "图片预览",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                    contentScale = ContentScale.Fit,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Error -> failed = true
                            is AsyncImagePainter.State.Success -> failed = false
                            else -> Unit
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PreviewIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            // 衬底色与内容色走语义令牌（F6）：遮罩恒为深色，此处恒为浅色令牌。
            .background(OnScrimScrim)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = OnScrim
        )
    }
}
