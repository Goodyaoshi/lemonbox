package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.goodyaoshi.lemonbox.ui.theme.OnScrim
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.ScrimFaint
import com.goodyaoshi.lemonbox.ui.theme.ScrimMedium
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary

@Composable
fun ItemImageGallery(
    imageHashes: List<String>,
    modifier: Modifier = Modifier,
    onCapturePrimary: () -> Unit,
    onCaptureDetail: () -> Unit,
    onScanBarcode: (() -> Unit)? = null,
    onRemoveImage: (Int) -> Unit,
    onMoveImage: (fromIndex: Int, toIndex: Int) -> Unit
) {
    val scanBarcodeAction = onScanBarcode

    if (imageHashes.isEmpty()) {
        if (scanBarcodeAction == null) {
            ActionImageSlot(
                title = "拍主图",
                subtitle = "先拍一张封面图",
                icon = Icons.Default.PhotoCamera,
                onClick = onCapturePrimary,
                modifier = modifier.fillMaxWidth()
            )
        } else {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionImageSlot(
                    title = "拍主图",
                    subtitle = "先拍一张封面图",
                    icon = Icons.Default.PhotoCamera,
                    onClick = onCapturePrimary,
                    modifier = Modifier.weight(1f)
                )
                ActionImageSlot(
                    title = "扫一扫",
                    subtitle = "扫条码或拍照识字",
                    icon = Icons.Default.QrCodeScanner,
                    onClick = scanBarcodeAction,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        return
    }

    val density = LocalDensity.current
    val dragStepPx = with(density) { (120.dp + 10.dp).toPx() }
    var draggingHash by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val displayHashes = remember(imageHashes, draggingHash, dragTargetIndex) {
        val hash = draggingHash
        val targetIndex = dragTargetIndex
        if (hash == null || targetIndex !in imageHashes.indices) {
            imageHashes
        } else {
            imageHashes.toMutableList().apply {
                remove(hash)
                add(targetIndex.coerceIn(0, size), hash)
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = "长按图片拖动就能调整顺序，第一张默认为你的主图",
            color = TextHint,
            // 字号走主题字阶（F6）：bodySmall 为 12sp。
            style = MaterialTheme.typography.bodySmall
        )
        LazyRow(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // key 带上位置：同一张图可能挂在多个槽位（导入了含重复图的备份），
            // 只用哈希作 key 会因重复而抛异常。项目内其它列表也是这个写法。
            itemsIndexed(displayHashes, key = { index, hash -> "$index-$hash" }) { index, hash ->
                val sourceIndex = imageHashes.indexOf(hash).takeIf { it >= 0 } ?: index
                val isDragging = draggingHash == hash
                val dragVisualOffset = if (isDragging) {
                    dragOffsetX - (dragTargetIndex - dragStartIndex) * dragStepPx
                } else {
                    0f
                }
                DraggableImageCard(
                    hash = hash,
                    title = if (index == 0) "主图" else "细节图",
                    onRemove = { onRemoveImage(sourceIndex) },
                    onMove = { deltaX ->
                        dragOffsetX += deltaX
                        val startIndex = dragStartIndex.takeIf { it in imageHashes.indices } ?: sourceIndex
                        val targetIndex = (startIndex + calculateDragSteps(dragOffsetX, dragStepPx))
                            .coerceIn(0, imageHashes.lastIndex)
                        dragTargetIndex = targetIndex
                    },
                    onDragStart = {
                        draggingHash = hash
                        dragStartIndex = sourceIndex
                        dragTargetIndex = sourceIndex
                        dragOffsetX = 0f
                    },
                    onDragEnd = {
                        val fromIndex = imageHashes.indexOf(hash).takeIf { it >= 0 } ?: dragStartIndex
                        val toIndex = dragTargetIndex
                        if (fromIndex in imageHashes.indices && toIndex in imageHashes.indices && fromIndex != toIndex) {
                            onMoveImage(fromIndex, toIndex)
                        }
                        draggingHash = null
                        dragStartIndex = -1
                        dragTargetIndex = -1
                        dragOffsetX = 0f
                    },
                    onDragCancel = {
                        draggingHash = null
                        dragStartIndex = -1
                        dragTargetIndex = -1
                        dragOffsetX = 0f
                    },
                    // 读屏可达（F8）：把拖拽排序的等价动作接回同一套换位逻辑。
                    canMoveLeft = sourceIndex > 0,
                    canMoveRight = sourceIndex < imageHashes.lastIndex,
                    onReorder = { delta ->
                        val to = (sourceIndex + delta).coerceIn(0, imageHashes.lastIndex)
                        if (to != sourceIndex) {
                            onMoveImage(sourceIndex, to)
                        }
                    },
                    isDragging = isDragging,
                    dragOffsetX = dragVisualOffset,
                    modifier = if (isDragging) {
                        Modifier
                    } else {
                        Modifier.animateItem(
                            placementSpec = spring(
                                dampingRatio = 0.84f,
                                stiffness = 360f
                            )
                        )
                    }
                )
            }
        }

        // 图片多的时候，操作入口不再塞进横向滚动列表，改为固定在下方一行，
        // 免去「扫一扫」被推到最右边、要一直往右滑才能点到。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionImageSlot(
                title = "补拍细节",
                subtitle = "继续添加图片",
                icon = Icons.Default.Add,
                onClick = onCaptureDetail,
                modifier = Modifier.weight(1f)
            )
            if (scanBarcodeAction != null) {
                ActionImageSlot(
                    title = "扫一扫",
                    subtitle = "扫条码或拍照识字",
                    icon = Icons.Default.QrCodeScanner,
                    onClick = scanBarcodeAction,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DraggableImageCard(
    hash: String,
    title: String,
    onRemove: () -> Unit,
    onMove: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    isDragging: Boolean,
    dragOffsetX: Float,
    /** 读屏可达（F8）：长按拖拽对读屏用户不可用，这里补「前移 / 后移一位」等价动作。 */
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onReorder: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = 120.dp, height = 120.dp)
            .zIndex(if (isDragging) 1f else 0f)
            // 读屏可达（F8）：把拖拽排序暴露为语义动作，读屏用户也能调序。
            .semantics {
                customActions = listOfNotNull(
                    if (canMoveLeft) {
                        CustomAccessibilityAction("前移一位") {
                            onReorder(-1)
                            true
                        }
                    } else {
                        null
                    },
                    if (canMoveRight) {
                        CustomAccessibilityAction("后移一位") {
                            onReorder(1)
                            true
                        }
                    } else {
                        null
                    }
                )
            }
            .graphicsLayer {
                translationX = dragOffsetX
                scaleX = if (isDragging) 1.03f else 1f
                scaleY = if (isDragging) 1.03f else 1f
                alpha = if (isDragging) 0.92f else 1f
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onMove(dragAmount.x)
                    }
                )
            }
    ) {
        ImagePreviewCard(
            hash = hash,
            title = title,
            onRemove = onRemove,
            modifier = Modifier.matchParentSize()
        )
    }
}

private fun calculateDragSteps(offsetPx: Float, stepPx: Float): Int {
    if (stepPx <= 0f || offsetPx == 0f) return 0
    val thresholdRatio = 0.68f
    val direction = if (offsetPx > 0f) 1 else -1
    val steps = kotlin.math.abs(offsetPx / stepPx)
    val wholeSteps = steps.toInt()
    val fractionalStep = steps - wholeSteps
    return direction * (wholeSteps + if (fractionalStep >= thresholdRatio) 1 else 0)
}

@Composable
private fun ImagePreviewCard(
    hash: String,
    title: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 与其它图片位一致：本地在就用本地，本地没了才按哈希推导对象键从云端取。
    val model = rememberImageModel(hash)
    Box(
        modifier = modifier
            .height(120.dp)
            .clip(MaterialTheme.shapes.extraLarge)
    ) {
        AsyncImage(
            model = model,
            contentDescription = title,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(ScrimFaint)
        )
        // 触达面积达标（F7）：删图热区此前就是视觉上的 26dp，手指点不中易误删；
        // 现在热区撑到 48dp，视觉圆点仍为 26dp，位置与观感基本不变。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(ScrimMedium),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除图片",
                    // 内容色走语义令牌（F6）：遮罩恒为深色，此处恒用浅色令牌。
                    tint = OnScrim,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(CircleShape)
                .background(OnScrim.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = title,
                color = OnScrim,
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 「扫一扫」识别方式选择（第 6 条）。
 *
 * 条码解码与文字识别各有独立的底层实现（前者走 ZXingLite 的相机扫码页，
 * 后者走离线 OCR 页），但用户的心智只有一个：「用相机帮我填表」。
 * 因此入口收成一个，点开后再选具体方式，省得表单里出现两个语义相近、
 * 位置还分开的识别入口（原先「拍照识字」在页头、「扫码关联」在图片区）。
 *
 * 两个 ActionImageSlot 复用图片区已有的方块样式，让选项与入口观感连续。
 */
@Composable
fun ScanInputChoiceDialog(
    onDismiss: () -> Unit,
    onScanBarcode: () -> Unit,
    onRecognizeText: () -> Unit
) {
    AppDialog(
        title = "扫一扫",
        subtitle = "选择识别方式，识别完自动回到这里填表",
        onDismissRequest = onDismiss
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionImageSlot(
                title = "扫条码",
                subtitle = "适合包装上的条码",
                icon = Icons.Default.QrCodeScanner,
                onClick = onScanBarcode,
                modifier = Modifier.weight(1f)
            )
            ActionImageSlot(
                title = "拍照识字",
                subtitle = "读出文字填品名",
                icon = Icons.Default.DocumentScanner,
                onClick = onRecognizeText,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ActionImageSlot(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(164.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(OrangeTint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(OrangeStart.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = subtitle,
                // 字号走主题字阶（F6）：bodySmall 为 12sp。
                style = MaterialTheme.typography.bodySmall,
                color = TextHint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
