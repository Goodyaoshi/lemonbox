package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagPurple
import com.goodyaoshi.lemonbox.ui.theme.TagPurpleText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/** 两个维度各自的候选状态（内置 + 用户自定义）。 */
data class ItemStatusCatalogState(
    val usage: List<ItemStatusOption>,
    val disposition: List<ItemStatusOption>
) {
    fun optionsFor(dimension: StatusDimension): List<ItemStatusOption> = when (dimension) {
        StatusDimension.USAGE -> usage
        StatusDimension.DISPOSITION -> disposition
    }
}

/**
 * 全应用可用的状态目录（内置 + 用户自定义）。
 * 默认只含内置状态，由 MainActivity 注入真实目录，因此组件单独预览也能工作。
 */
val LocalItemStatusOptions = staticCompositionLocalOf {
    ItemStatusCatalogState(
        usage = ItemStatusCatalog.builtInUsage,
        disposition = ItemStatusCatalog.builtInDisposition
    )
}

/** 取某个维度的候选状态列表。 */
@Composable
@ReadOnlyComposable
fun statusOptionsFor(dimension: StatusDimension): List<ItemStatusOption> =
    LocalItemStatusOptions.current.optionsFor(dimension)

/** 按维度与编号取选项；未知编号退化为「状态 N」，避免出现空白。 */
@Composable
@ReadOnlyComposable
fun itemStatusOption(dimension: StatusDimension, code: Int): ItemStatusOption {
    val state = LocalItemStatusOptions.current
    return state.optionsFor(dimension).firstOrNull { it.code == code }
        ?: ItemStatusOption(
            code = code,
            label = ItemStatusCatalog.fallbackLabel(code),
            dimension = dimension,
            isBuiltIn = false
        )
}

/** 状态标签的配色：未使用/在库=绿、使用中=橙、已用完=红、借出=蓝、送人=紫、丢弃=红。 */
@Composable
@ReadOnlyComposable
fun itemStatusColors(dimension: StatusDimension, code: Int): Pair<Color, Color> =
    when (dimension) {
        StatusDimension.USAGE -> when (code) {
            Item.USAGE_UNUSED -> TagGreen to TagGreenText
            Item.USAGE_IN_USE -> TagOrange to TagOrangeText
            Item.USAGE_USED_UP -> TagRed to TagRedText
            else -> TagPurple to TagPurpleText
        }

        StatusDimension.DISPOSITION -> when (code) {
            Item.DISPOSITION_IN_STOCK -> TagGreen to TagGreenText
            Item.DISPOSITION_LENT_OUT -> TagBlue to TagBlueText
            Item.DISPOSITION_GIVEN_AWAY -> TagPurple to TagPurpleText
            Item.DISPOSITION_DISCARDED -> TagRed to TagRedText
            else -> TagPurple to TagPurpleText
        }
    }

/** 状态维度对应的行标题与弹窗标题。 */
fun statusDimensionTitle(dimension: StatusDimension): String = when (dimension) {
    StatusDimension.USAGE -> "使用进度"
    StatusDimension.DISPOSITION -> "物品去向"
}

/** 物品状态选择弹窗：点选某个维度下的状态后立即生效并关闭。 */
@Composable
fun StatusPickerDialog(
    dimension: StatusDimension,
    selectedCode: Int,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    val options = statusOptionsFor(dimension)
    val title = statusDimensionTitle(dimension)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center
        ) {
            AppSurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(22.dp),
                shadowElevation = 26.dp
            ) {
                Text(
                    text = "修改$title",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "选中后立即保存。更多选项可在「我的 - 分类与状态」里自定义。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(options, key = { it.code }) { option ->
                        StatusOptionRow(
                            dimension = dimension,
                            option = option,
                            selected = option.code == selectedCode,
                            onClick = {
                                onSelect(option.code)
                                onDismissRequest()
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PickerActionButton(
                        text = "取消",
                        modifier = Modifier.weight(1f),
                        onClick = onDismissRequest
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusOptionRow(
    dimension: StatusDimension,
    option: ItemStatusOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val (container, content) = itemStatusColors(dimension, option.code)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) OrangeStart.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillTag(
            text = option.label,
            backgroundColor = container,
            contentColor = content
        )
        if (!option.isBuiltIn) {
            Text(
                text = "自定义",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Box(modifier = Modifier.weight(1f))
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(OrangeStart.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}