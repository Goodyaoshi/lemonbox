package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

@Composable
fun EditorSectionLabel(
    label: String,
    tag: String? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
        tag?.let {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
            PillTag(
                text = it,
                backgroundColor = TagBlue,
                contentColor = TagBlueText
            )
        }
    }
}

@Composable
fun EditorInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Int = 56,
    readOnly: Boolean = false,
    onFocus: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(minHeight.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(OrangeTint)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 15.sp,
                color = TextHint
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocus?.invoke()
                        }
                    }
                    .focusable(enabled = !readOnly),
                singleLine = singleLine,
                readOnly = readOnly,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = TextPrimary
                ),
                cursorBrush = SolidColor(OrangeStart)
            )
            trailingContent?.invoke()
        }
    }
}

@Composable
fun EditorSelectionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) OrangeStart.copy(alpha = 0.12f) else OrangeTint)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (selected) OrangeStart else TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) OrangeStart else TextSecondary
        )
    }
}

/**
 * 到期提醒阶梯选择器：可多选 1/3/7/14/30/60/90 天，点「跟随默认」即清空、沿用全局阶梯。
 * 未选择任何档位时物品跟随全局默认阶梯。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderLadderPicker(
    selectedDays: List<Int>,
    defaultLadder: List<Int>,
    onDaysChange: (List<Int>) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppPreferences.REMINDER_LADDER_OPTIONS.forEach { days ->
            val selected = selectedDays.contains(days)
            EditorSelectionChip(
                text = "$days 天",
                selected = selected,
                onClick = {
                    onDaysChange(
                        if (selected) selectedDays - days else (selectedDays + days).sorted()
                    )
                }
            )
        }
        EditorSelectionChip(
            text = "跟随默认",
            selected = selectedDays.isEmpty(),
            onClick = { onDaysChange(emptyList()) }
        )
    }
    Spacer(modifier = Modifier.size(8.dp))
    Text(
        text = if (defaultLadder.isEmpty()) {
            "跟随默认：临期与到期当天提醒"
        } else {
            "跟随默认：${defaultLadder.joinToString("、") { "$it 天" }}"
        },
        fontSize = 12.sp,
        color = TextHint
    )
}

@Composable
fun BarcodeAssociationCard(
    barcode: String,
    onClear: () -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        containerColor = OrangeTint,
        shadowElevation = 6.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(OrangeStart.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已关联条码",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    // 该卡底为 OrangeTint，正文取语义「橙标签文字」，深色模式下随主题切换。
                    color = TagOrangeText
                )
                Text(
                    text = barcode,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = "移除",
                fontSize = 12.sp,
                color = OrangeStart,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun QuantityStepper(
    quantity: Int,
    unit: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton(text = "-", onClick = onDecrease)
        Text(
            text = "$quantity $unit",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        StepperButton(text = "+", onClick = onIncrease)
    }
}

@Composable
private fun StepperButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SurfaceWarmDeep)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 常用单位；超过这个范围走「自定义」。 */
private val COMMON_UNITS = listOf("个", "件", "箱", "瓶", "盒", "袋", "包", "卷", "双", "套")

/**
 * 单位选择行：常用单位胶囊 + 「自定义」输入。
 * 单位跟随物品本身，保存后所有数量展示都会带上它（如「3 瓶」「2 箱」）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitPickerRow(
    unit: String,
    onUnitChange: (String) -> Unit
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        COMMON_UNITS.forEach { candidate ->
            val selected = candidate == unit
            Text(
                text = candidate,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) OrangeStart else TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) OrangeTint else SurfaceWarmDeep)
                    .clickable { onUnitChange(candidate) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        // 当前单位是自定义值时，把它的胶囊一并展示为选中态，避免看起来没单位。
        if (unit.isNotBlank() && unit !in COMMON_UNITS) {
            Text(
                text = unit,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OrangeStart,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(OrangeTint)
                    .clickable {
                        customInput = unit
                        showCustomDialog = true
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Text(
            text = "自定义",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = OrangeStart,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SurfaceWarmDeep)
                .clickable {
                    customInput = if (unit in COMMON_UNITS) "" else unit
                    showCustomDialog = true
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }

    if (showCustomDialog) {
        AppDialog(
            title = "自定义单位",
            subtitle = "比如 包、罐、提、盒装，保存后跟着数量一起展示。",
            onDismissRequest = { showCustomDialog = false },
            confirmText = "确定",
            confirmEnabled = customInput.isNotBlank(),
            onConfirm = {
                onUnitChange(customInput.trim())
                showCustomDialog = false
            }
        ) {
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it },
                singleLine = true,
                label = { Text("单位名称") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
