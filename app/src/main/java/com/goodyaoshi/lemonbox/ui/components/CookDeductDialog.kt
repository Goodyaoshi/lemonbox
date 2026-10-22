package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.data.local.entity.formatQuantity
import com.goodyaoshi.lemonbox.data.meal.DeductAction
import com.goodyaoshi.lemonbox.data.meal.DeductPlan
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary

/**
 * 「做了」之前的扣料确认清单。
 *
 * 扣库存是全 App 最大的一次隐性写操作，所以这里不自动执行：
 * 逐条列出「要扣什么、扣多少、扣完剩多少」，用户可取消任意一条；
 * 缺料与调料不扣，但也要列出来，让结果可解释。
 *
 * 菜单是按「菜谱原份」排的，实际常做双份或只做一人份：份量选择与逐行改量
 * 都在确认前完成，扣的就是最终要扣的数字，避免先扣再手动补回去。
 */
@Composable
fun CookDeductDialog(
    plan: DeductPlan,
    onToggle: (Int) -> Unit,
    onScale: (Double) -> Unit,
    onAmount: (Int, Double) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AppDialog(
        title = "确认扣料",
        subtitle = "勾选的食材会按用量从家当里扣掉；不扣的也会列出来，方便核对。",
        onDismissRequest = onDismiss,
        confirmText = if (plan.deductCount > 0) "确认，扣 ${plan.deductCount} 样" else "记「做过」",
        onConfirm = onConfirm
    ) {
        if (plan.canScale) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = "做几份",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                SCALE_OPTIONS.forEach { option ->
                    EditorSelectionChip(
                        text = option.label,
                        selected = kotlin.math.abs(plan.scale - option.factor) < 1e-6,
                        onClick = { onScale(option.factor) }
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            plan.lines.forEachIndexed { index, line ->
                val enabled = line.action == DeductAction.DEDUCT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onToggle(index) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (enabled) {
                        Checkbox(checked = line.selected, onCheckedChange = { onToggle(index) })
                    } else {
                        // 不扣的行不占勾选框：留白比「灰掉的框」更少误导。
                        Spacer(modifier = Modifier.size(width = 48.dp, height = 1.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = line.itemName.ifBlank { line.label },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            if (enabled) {
                                Text(
                                    text = "  扣 ${formatQuantity(line.amount)}${line.unit}" +
                                        "（剩 ${formatQuantity(line.afterAmount)}${line.unit}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        val note = when (line.action) {
                            DeductAction.MISSING -> "家里没有，已提醒补货"
                            DeductAction.SKIP -> line.reason
                            else -> line.reason
                        }
                        if (note.isNotBlank()) {
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (line.action == DeductAction.MISSING) StatusExpired else TextHint
                            )
                        }
                    }
                    if (enabled && line.selected) {
                        // 逐行微调：菜谱用量是按平均口味写的，家里的肉块大小、锅大小都会让实际用量不同。
                        val step = amountStep(line.unit)
                        IconButton(
                            onClick = { onAmount(index, line.amount - step) },
                            enabled = line.amount > 0.0
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "减少${line.label}的扣量",
                                tint = if (line.amount > 0.0) TextSecondary else TextHint
                            )
                        }
                        IconButton(
                            onClick = { onAmount(index, line.amount + step) },
                            enabled = line.amount < line.beforeAmount
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "增加${line.label}的扣量",
                                tint = if (line.amount < line.beforeAmount) TextSecondary else TextHint
                            )
                        }
                    }
                }
            }
        }
        if (plan.missingLabels.isNotEmpty()) {
            Text(
                text = "还缺：${plan.missingLabels.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = TextHint,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 份量选项；0.5 半份 / 1 原份 / 1.5 加半份 / 2 双份。 */
private data class ScaleOption(val label: String, val factor: Double)

private val SCALE_OPTIONS = listOf(
    ScaleOption("半份", 0.5),
    ScaleOption("原份", 1.0),
    ScaleOption("1.5 份", 1.5),
    ScaleOption("双份", 2.0)
)

/**
 * 每次点 +/- 的步长。
 *
 * 克/毫升按 50 调（一盘菜的用量量级），按个数/袋数按 1 调；
 * 其余单位（勺、把等）按 0.5，避免「一勺」直接变成两勺。
 */
private fun amountStep(unit: String): Double = when (unit) {
    "克", "毫升" -> 50.0
    "个", "袋", "盒", "瓶", "包", "根", "片", "瓣", "条", "块" -> 1.0
    else -> 0.5
}
