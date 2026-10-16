package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.util.DateUtil
import com.tyme.lunar.LunarDay
import com.tyme.lunar.LunarMonth
import com.tyme.lunar.LunarYear
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Composable
fun ExpiryPickerDialog(
    selectedDateMillis: Long?,
    onDismissRequest: () -> Unit,
    onConfirm: (Long?) -> Unit,
    /** 弹窗标题；购买日期、开始使用等场景可换成自己的文案。 */
    title: String = "选择有效期",
    /** 可选年份区间；到期默认未来 8 年，购买日期等回溯场景传过去年份。 */
    yearRange: IntRange? = null,
    /** 清空动作；不传则不显示「清空」按钮（日期必填的场景）。 */
    onClear: (() -> Unit)? = null
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }
    val initialDate = remember(selectedDateMillis) {
        selectedDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        } ?: today
    }
    var selectedYear by remember(initialDate) { mutableIntStateOf(initialDate.year) }
    var selectedMonth by remember(initialDate) { mutableIntStateOf(initialDate.monthValue) }
    var selectedDay by remember(initialDate) { mutableIntStateOf(initialDate.dayOfMonth) }
    var selectedPanel by remember { mutableStateOf(ExpiryPanel.MONTH) }

    val years = remember(today.year, yearRange) { yearRange ?: (today.year..today.year + 8) }
    val currentYearMonth = remember(selectedYear, selectedMonth) { YearMonth.of(selectedYear, selectedMonth) }
    val maxDay = currentYearMonth.lengthOfMonth()
    if (selectedDay > maxDay) {
        selectedDay = maxDay
    }

    AppDialog(
        title = title,
        subtitle = "按年、月、日分步选择，布局固定更直观。",
        onDismissRequest = onDismissRequest,
        confirmText = "确定",
        secondaryText = onClear?.let { "清空" },
        onSecondary = onClear,
        onConfirm = {
            val millis = LocalDate.of(selectedYear, selectedMonth, selectedDay)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            onConfirm(millis)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpiryTab(
                text = "${selectedYear}年",
                selected = selectedPanel == ExpiryPanel.YEAR,
                onClick = { selectedPanel = ExpiryPanel.YEAR },
                modifier = Modifier.weight(1f)
            )
            ExpiryTab(
                text = "${selectedMonth}月",
                selected = selectedPanel == ExpiryPanel.MONTH,
                onClick = { selectedPanel = ExpiryPanel.MONTH },
                modifier = Modifier.weight(1f)
            )
            ExpiryTab(
                text = selectedDay.toString(),
                selected = selectedPanel == ExpiryPanel.DAY,
                onClick = { selectedPanel = ExpiryPanel.DAY },
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = DateUtil.formatDate(
                LocalDate.of(selectedYear, selectedMonth, selectedDay)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            ),
            // 字号走主题字阶（F6）：titleLarge 为 18sp。
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        when (selectedPanel) {
            ExpiryPanel.YEAR -> {
                FixedGridOptions(
                    columns = 3,
                    items = years.toList(),
                    label = { it.toString() },
                    selected = { selectedYear == it },
                    onSelect = {
                        selectedYear = it
                        selectedPanel = ExpiryPanel.MONTH
                    }
                )
            }

            ExpiryPanel.MONTH -> {
                FixedGridOptions(
                    columns = 4,
                    items = (1..12).toList(),
                    label = { "${it}月" },
                    selected = { selectedMonth == it },
                    onSelect = {
                        selectedMonth = it
                        selectedPanel = ExpiryPanel.DAY
                    }
                )
            }

            ExpiryPanel.DAY -> {
                FixedGridOptions(
                    columns = 7,
                    items = (1..maxDay).toList(),
                    label = { it.toString() },
                    selected = { selectedDay == it },
                    onSelect = { selectedDay = it }
                )
            }
        }
    }
}

private enum class ExpiryPanel {
    YEAR,
    MONTH,
    DAY
}

@Composable
private fun ExpiryTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // 圆角走形状令牌（F6）：收敛到 large（20dp）。
            .clip(MaterialTheme.shapes.large)
            .background(
                color = if (selected) OrangeStart.copy(alpha = 0.12f) else OrangeTint,
                shape = MaterialTheme.shapes.large
            )
            // 触达面积达标（F7）：Tab 此前竖向只有 10dp 内距、高约 40dp，补足到 48dp 最小可点区。
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = OrangeStart.copy(alpha = 0.16f)
                ),
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) OrangeStart else TextSecondary,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun <T> FixedGridOptions(
    columns: Int,
    items: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    // 审计修复：此前用 LazyVerticalGrid——它自带纵向滚动，嵌在弹窗内容区的 verticalScroll 里
    // 会与外层滚动互相打架（选日期时滚不动 / 跳一下）。选项量级极小（年 ≤ 9、月 12、日 ≤ 31），
    // 用普通网格一次铺满即可，既无嵌套滚动，也没有惰性列表的开销。
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.chunked(columns.coerceAtLeast(1)).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        ExpiryGridCell(
                            text = label(item),
                            selected = selected(item),
                            onClick = { onSelect(item) }
                        )
                    }
                }
                // 补齐最后一行的空位，保证每格等宽、列对齐。
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ExpiryGridCell(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 触达面积达标（F7）：日期格此前最小高仅 42dp，低于 48dp 最小可点区，选日期难点中。
            .minimumInteractiveComponentSize()
            // 圆角走形状令牌（F6）：收敛到 large（20dp）。
            .clip(MaterialTheme.shapes.large)
            .background(
                color = if (selected) OrangeStart.copy(alpha = 0.14f) else OrangeTint,
                shape = MaterialTheme.shapes.large
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = OrangeStart.copy(alpha = 0.16f)
                ),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) OrangeStart else TextHint,
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * 农历日期选择器：按农历年 → 月 → 日分步选择，交互与 [ExpiryPickerDialog] 一致。
 * 月份展示实际农历月名，日面板收拢到当月真实天数（小月选不到三十）。
 */
@Composable
fun LunarDatePickerDialog(
    initialLunarYear: Int,
    initialLunarMonth: Int,
    initialLunarDay: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (lunarYear: Int, lunarMonth: Int, lunarDay: Int) -> Unit
) {
    val thisYear = remember { LocalDate.now().year }
    var selectedYear by remember {
        mutableIntStateOf(initialLunarYear.coerceIn(thisYear - 120, thisYear))
    }
    var selectedMonth by remember { mutableIntStateOf(initialLunarMonth.coerceIn(1, 12)) }
    var selectedDay by remember { mutableIntStateOf(initialLunarDay.coerceIn(1, 30)) }
    var selectedPanel by remember { mutableStateOf(ExpiryPanel.YEAR) }

    // 生日是出生日期：农历年份从今年往前 120 年，近的排前面好选
    val years = remember { (thisYear - 120..thisYear).toList().asReversed() }
    val maxDay = remember(selectedYear, selectedMonth) {
        runCatching { LunarMonth.fromYm(selectedYear, selectedMonth).getDayCount() }.getOrDefault(30)
    }
    if (selectedDay > maxDay) {
        selectedDay = maxDay
    }
    val monthName = LunarMonth.fromYm(2024, selectedMonth).getName()
    val dayName = LunarDay.NAMES.getOrNull(selectedDay - 1) ?: "$selectedDay"
    // 农历年的干支名（如「庚辰」），仅用于展示；换算失败不显示
    val yearGanZhi = runCatching {
        LunarYear.fromYear(selectedYear).getSixtyCycle().getName()
    }.getOrNull()

    AppDialog(
        title = "选择农历日期",
        subtitle = "按农历年、月、日分步选择；闰月按平月过。",
        onDismissRequest = onDismissRequest,
        confirmText = "确定",
        onConfirm = { onConfirm(selectedYear, selectedMonth, selectedDay) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpiryTab(
                text = "${selectedYear}年",
                selected = selectedPanel == ExpiryPanel.YEAR,
                onClick = { selectedPanel = ExpiryPanel.YEAR },
                modifier = Modifier.weight(1f)
            )
            ExpiryTab(
                text = monthName,
                selected = selectedPanel == ExpiryPanel.MONTH,
                onClick = { selectedPanel = ExpiryPanel.MONTH },
                modifier = Modifier.weight(1f)
            )
            ExpiryTab(
                text = dayName,
                selected = selectedPanel == ExpiryPanel.DAY,
                onClick = { selectedPanel = ExpiryPanel.DAY },
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "农历${selectedYear}年${yearGanZhi?.let { "（$it）" } ?: ""}$monthName$dayName",
            // 字号走主题字阶（F6）：titleLarge 为 18sp。
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        when (selectedPanel) {
            ExpiryPanel.YEAR -> {
                FixedGridOptions(
                    columns = 4,
                    items = years,
                    label = { it.toString() },
                    selected = { selectedYear == it },
                    onSelect = {
                        selectedYear = it
                        selectedPanel = ExpiryPanel.MONTH
                    }
                )
            }

            ExpiryPanel.MONTH -> {
                FixedGridOptions(
                    columns = 4,
                    items = (1..12).toList(),
                    label = { LunarMonth.fromYm(2024, it).getName() },
                    selected = { selectedMonth == it },
                    onSelect = {
                        selectedMonth = it
                        selectedPanel = ExpiryPanel.DAY
                    }
                )
            }

            ExpiryPanel.DAY -> {
                // 农历日名是两字（初一/三十），5 列保证单元格放得下不折行
                FixedGridOptions(
                    columns = 5,
                    items = (1..maxDay).toList(),
                    label = { LunarDay.NAMES.getOrNull(it - 1) ?: it.toString() },
                    selected = { selectedDay == it },
                    onSelect = { selectedDay = it }
                )
            }
        }
    }
}
