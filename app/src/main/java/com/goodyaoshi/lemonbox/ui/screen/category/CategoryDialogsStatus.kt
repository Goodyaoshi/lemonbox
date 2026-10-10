package com.goodyaoshi.lemonbox.ui.screen.category

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Microwave
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.Category
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusCatalog
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.Location
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.CategoryIconOption
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.categoryIconFor
import com.goodyaoshi.lemonbox.ui.components.categoryIconOptions
import com.goodyaoshi.lemonbox.ui.components.itemStatusColors
import com.goodyaoshi.lemonbox.ui.components.statusDimensionTitle
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.CategoryViewModel
import com.goodyaoshi.lemonbox.util.DateUtil


// 家当设置页的弹窗与「状态选项 / 有效期与提醒」面板（F9）。
// 原先这些私有 Composable 与入口 CategoryScreen 同处一个近 1400 行的页面文件，
// 改一处弹窗要在整个文件里翻找；拆出后本文件只关心「弹窗与状态维护」。
// 由 CategoryScreen 调用，故放宽到 internal（同包可见）。

/**
 * 「有效期与提醒」面板：家当录入页的有效期快捷档位 + 到期提醒阶梯，
 * 原来分散在设置页，合并进家当设置统一维护。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpiryReminderPanel(
    reminderLadder: List<Int>,
    onToggleLadder: (Int) -> Unit,
    expiryQuickOptions: List<String>,
    onToggleQuickOption: (String) -> Unit,
    onOpenQuickCustom: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Text(
                    text = "有效期快捷选项",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "你在家当录入页「有效期」看到的快捷档位，按你常买物品的保质期自定义（支持 x天/x周/x月/x年）。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val candidates =
                        (DateUtil.EXPIRY_QUICK_CANDIDATES + expiryQuickOptions).distinct()
                    candidates.forEach { code ->
                        OptionChip(
                            label = DateUtil.expiryQuickLabel(code),
                            selected = expiryQuickOptions.contains(code),
                            onClick = { onToggleQuickOption(code) }
                        )
                    }
                    OptionChip(
                        label = "自定义…",
                        selected = false,
                        onClick = onOpenQuickCustom
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (expiryQuickOptions.isEmpty()) {
                        "尚未设置快捷档位，录入时可直接选日期"
                    } else {
                        "当前档位：" + expiryQuickOptions.joinToString("、") {
                            DateUtil.expiryQuickLabel(it)
                        }
                    },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        item {
            Column {
                Text(
                    text = "到期提醒阶梯",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "可以多选。物品有效期进入你选的档位时提醒一次，没单独设置过的物品都跟着这份默认阶梯。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppPreferences.REMINDER_LADDER_OPTIONS.forEach { days ->
                        OptionChip(
                            label = "$days 天",
                            selected = reminderLadder.contains(days),
                            onClick = { onToggleLadder(days) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (reminderLadder.isEmpty()) {
                        "尚未选择档位，临期与到期当天仍会提醒"
                    } else {
                        "当前阶梯：${reminderLadder.joinToString("、") { "$it 天" }}"
                    },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 有效期快捷档位自定义：输入数值 + 选单位（天/周/月/年），追加到现有档位并去重。 */
@Composable
internal fun ExpiryQuickDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("d") }
    var added by remember { mutableStateOf(DateUtil.DEFAULT_EXPIRY_QUICK_CODES) }
    val unitOptions = listOf("d" to "天", "w" to "周", "m" to "月", "y" to "年")

    AppDialog(
        title = "自定义有效期快捷",
        onDismissRequest = onDismiss,
        confirmText = "保存",
        onConfirm = { onConfirm(added) }
    ) {
        Column {
            Text(
                text = "输入数值并选好单位，就能加进快捷档位。",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    EditorInputBox(
                        value = amountText,
                        onValueChange = { input ->
                            amountText = input.filter { it.isDigit() }.take(3)
                        },
                        placeholder = "数值"
                    )
                }
                unitOptions.forEach { (code, label) ->
                    OptionChip(
                        label = label,
                        selected = unit == code,
                        onClick = { unit = code }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val canAdd = (amountText.toIntOrNull() ?: 0) > 0
            OptionChip(
                label = if (canAdd) {
                    "添加：${DateUtil.expiryQuickLabel(amountText.toInt().toString() + unit)}"
                } else {
                    "添加"
                },
                selected = canAdd,
                onClick = {
                    if (!canAdd) return@OptionChip
                    added = DateUtil.normalizeExpiryQuickCodes(
                        added + (amountText.toInt().toString() + unit)
                    )
                    amountText = ""
                }
            )
            if (added.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "当前档位：" + added.joinToString("、") { DateUtil.expiryQuickLabel(it) },
                    fontSize = 12.sp,
                    color = OrangeStart,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 单选胶囊：选中态用品牌色铺底 + 白字，未选中态用浅底 + 次级文字。 */
@Composable
internal fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) OrangeStart else SurfaceWarmDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) CardWhite else TextSecondary
        )
    }
}

/** 状态选项面板：内置选项只读，自定义选项可点改名、可删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusOptionsPanel(
    customStatuses: List<ItemStatusOption>,
    onAdd: (StatusDimension) -> Unit,
    onEdit: (ItemStatusOption) -> Unit,
    onRemove: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(StatusDimension.entries.toList(), key = { it.name }) { dimension ->
            Column {
                Text(
                    text = statusDimensionTitle(dimension),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ItemStatusCatalog.builtInFor(dimension).forEach { option ->
                        StatusChip(
                            label = option.label,
                            tint = itemStatusColors(dimension, option.code).second
                        )
                    }
                    customStatuses
                        .filter { it.dimension == dimension }
                        .forEach { option ->
                            StatusChip(
                                label = option.label,
                                tint = itemStatusColors(dimension, option.code).second,
                                onEdit = { onEdit(option) },
                                onRemove = { onRemove(option.code) }
                            )
                        }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(SurfaceWarmDeep)
                            .clickable { onAdd(dimension) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = OrangeStart,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "自定义",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = OrangeStart
                        )
                    }
                }
            }
        }
    }
}

/** 状态胶囊：内置状态只读；自定义状态可点主体改名，右侧带删除按钮。 */
@Composable
internal fun StatusChip(
    label: String,
    tint: Color,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(enabled = onEdit != null) { onEdit?.invoke() }
            .padding(
                start = 14.dp,
                end = if (onRemove != null) 6.dp else 14.dp,
                top = 9.dp,
                bottom = 9.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = tint
        )
        onRemove?.let { remove ->
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = remove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除该自定义状态",
                    tint = tint,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
internal fun CategoryEditorDialog(
    title: String,
    subtitle: String,
    inputName: String,
    selectedIconKey: String,
    onNameChange: (String) -> Unit,
    onIconSelect: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AppDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        confirmEnabled = inputName.isNotBlank(),
        onConfirm = onConfirm
    ) {
        OutlinedTextField(
            value = inputName,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("分类名称") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "选择图标",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.height(220.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categoryIconOptions.size, key = { index -> categoryIconOptions[index].key }) { index ->
                val option = categoryIconOptions[index]
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selectedIconKey == option.key) OrangeStart.copy(alpha = 0.1f)
                            else OrangeTint
                        )
                        .border(
                            width = 1.dp,
                            color = if (selectedIconKey == option.key) {
                                OrangeStart.copy(alpha = 0.42f)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onIconSelect(option.key) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.label,
                        tint = if (selectedIconKey == option.key) OrangeStart else TextHint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
