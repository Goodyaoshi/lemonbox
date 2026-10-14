package com.goodyaoshi.lemonbox.ui.screen.anniversary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryRow
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryViewModel
import java.time.Instant
import java.time.ZoneOffset

/**
 * 纪念日列表页：在一起的累计、生日/周年、考试倒数都收在这，
 * 新增与编辑统一走右下角 FAB 与卡片点击，与菜谱库页同一套骨架。
 */
@Composable
fun AnniversaryScreen(
    onBack: () -> Unit,
    onOpenEdit: (Long?) -> Unit,
    viewModel: AnniversaryViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsState()
    // 「在一起」配置（IA-6）：从全局设置下沉到本页，页头齿轮进入。
    val togetherStartDate by viewModel.togetherStartDate.collectAsState()
    val togetherLabelName by viewModel.togetherLabelName.collectAsState()
    var showTogetherSettings by remember { mutableStateOf(false) }
    var showTogetherDatePicker by remember { mutableStateOf(false) }
    var showTogetherLabelDialog by remember { mutableStateOf(false) }
    // 列表行删除（IA-6）：左滑露出删除，确认后软删并可撤销；互斥滑开状态与待删行在此托管。
    var openedAnniversaryId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteRow by remember { mutableStateOf<AnniversaryRow?>(null) }
    val appSnackbar = LocalAppSnackbar.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 点击空白处收起侧滑行的背景热区：用 pointerInput 而非 clickable，
            // 只接管触摸、不向无障碍树暴露一个无标签的“整屏可点”节点（同待买页写法）。
            .pointerInput(Unit) {
                detectTapGestures(onTap = { openedAnniversaryId = null })
            }
    ) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "纪念日",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                // 域内设置入口（IA-6）：「在一起」起始日与计数前缀收在这里。
                IconButton(onClick = { showTogetherSettings = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "在一起设置",
                        tint = TextPrimary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                AnniversaryContent(
                    rows = rows,
                    onToggle = { row, enabled ->
                        viewModel.setEnabled(row.anniversary, enabled)
                    },
                    onOpen = { onOpenEdit(it.anniversary.id) },
                    onAdd = { onOpenEdit(null) },
                    openedItemKey = openedAnniversaryId,
                    onOpenedItemChange = { openedAnniversaryId = it as Long? },
                    onDeleteRequest = { row -> pendingDeleteRow = row }
                )
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        LemonFab(
            icon = Icons.Default.Add,
            contentDescription = "新增纪念日",
            onClick = { onOpenEdit(null) },
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        // 「在一起」设置（IA-6）：齿轮进总入口，再分别改起始日与前缀。
        if (showTogetherSettings) {
            AppDialog(
                title = "在一起设置",
                subtitle = "设置后首页会用「${togetherLabelName}第 N 天」显示在一起的天数。",
                onDismissRequest = { showTogetherSettings = false },
                confirmText = "完成",
                onConfirm = { showTogetherSettings = false }
            ) {
                SettingRow(
                    label = "在一起起始日",
                    value = togetherStartDate ?: "未设置，按纪念日派生",
                    actionLabel = if (togetherStartDate == null) "设置" else "修改",
                    onAction = {
                        showTogetherSettings = false
                        showTogetherDatePicker = true
                    }
                )
                SettingRow(
                    label = "计数前缀",
                    value = "首页显示为「${togetherLabelName}第 N 天」",
                    actionLabel = "修改",
                    onAction = {
                        showTogetherSettings = false
                        showTogetherLabelDialog = true
                    }
                )
            }
        }
        if (showTogetherDatePicker) {
            DatePickerCardDialog(
                title = "在一起起始日",
                initialDate = togetherStartDate,
                onDismiss = { showTogetherDatePicker = false },
                onConfirm = { date ->
                    viewModel.setTogetherStartDate(date)
                    showTogetherDatePicker = false
                }
            )
        }
        if (showTogetherLabelDialog) {
            TogetherLabelDialog(
                initialName = togetherLabelName,
                onDismiss = { showTogetherLabelDialog = false },
                onConfirm = { name ->
                    viewModel.setTogetherLabelName(name)
                    showTogetherLabelDialog = false
                }
            )
        }

        // 列表行删除确认（IA-6）：删除是破坏性操作先确认；软删后给一次按快照复活的机会，
        // 想慢慢改的入口仍在编辑页底部，这里解决「只想删个过期倒数日却要走四层」的问题。
        pendingDeleteRow?.let { row ->
            AppDialog(
                title = "删除纪念日",
                onDismissRequest = { pendingDeleteRow = null },
                confirmText = "删除",
                destructiveConfirm = true,
                onConfirm = {
                    viewModel.delete(row.anniversary)
                    appSnackbar?.showUndo("已删除「${row.name}」", DeleteCopy.UNDO_ACTION) {
                        viewModel.restore(row.anniversary)
                    }
                    pendingDeleteRow = null
                }
            ) {
                Text(
                    text = "确定要删除「${row.name}」吗？",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** 弹窗里的一行设置：左侧标签与当前值，右侧动作。 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = value,
                fontSize = 12.sp,
                color = if (value.startsWith("未设置")) TextSecondary else OrangeStart
            )
        }
        Text(
            text = actionLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** 日期选择弹窗：以 yyyy-MM-dd 文本回调；点「清除」回传 null 表示取消设置。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DatePickerCardDialog(
    title: String,
    initialDate: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    // DatePicker 以 UTC 毫秒表示日期，这里统一用 UTC 换算，避免时区导致日期偏移一天。
    val initialMillis = remember(initialDate) {
        initialDate
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    AppDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmText = "确定",
        onConfirm = {
            onConfirm(
                pickerState.selectedDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                }
            )
        },
        secondaryText = initialDate?.let { "清除" },
        onSecondary = { onConfirm(null) }
    ) {
        DatePicker(state = pickerState)
    }
}

/** 「计数前缀」编辑弹窗：限长，留空由偏好层回落到默认「我们」。 */
@Composable
private fun TogetherLabelDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    AppDialog(
        title = "计数前缀",
        onDismissRequest = onDismiss,
        subtitle = "首页会显示为「前缀第 N 天」，例如「我们第 128 天」。",
        confirmText = "保存",
        onConfirm = { onConfirm(text) }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.take(AppPreferences.MAX_TOGETHER_LABEL_LENGTH) },
            singleLine = true,
            label = { Text("前缀") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 纪念日列表主体：空态一句话引导；卡片点进去编辑，左滑露出删除，开关直接停用提醒。 */
@Composable
private fun AnniversaryContent(
    rows: List<AnniversaryRow>,
    onToggle: (AnniversaryRow, Boolean) -> Unit,
    onOpen: (AnniversaryRow) -> Unit,
    onAdd: () -> Unit,
    openedItemKey: Any?,
    onOpenedItemChange: (Any?) -> Unit,
    onDeleteRequest: (AnniversaryRow) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rows.isEmpty()) {
            EmptyState(
                title = "还没有纪念日",
                // 去掉「点右下角」这种屏外指路（I5），空态里直接给「添加纪念日」。
                message = "把在一起的日子、生日和要倒数的事记下来，往后就不会错过。",
                actionLabel = "添加纪念日",
                onAction = onAdd
            )
        } else {
            rows.forEach { row ->
                // 左滑露出删除（IA-6）：与待买清单同一套侧滑语言，点击卡片进编辑的行为不变。
                SwipeRevealItem(
                    itemKey = row.anniversary.id,
                    openedItemKey = openedItemKey,
                    onOpenedItemChange = onOpenedItemChange,
                    actions = listOf(
                        SwipeActionSpec(
                            label = "删除",
                            icon = Icons.Default.Delete,
                            backgroundColor = StatusExpired,
                            onClick = { onDeleteRequest(row) }
                        )
                    )
                ) { _, _ ->
                    AnniversaryCard(
                        row = row,
                        onToggle = { enabled -> onToggle(row, enabled) },
                        onClick = { onOpen(row) }
                    )
                }
            }
        }
    }
}

/** 单张纪念日卡：上行=图标+名称+日期标签+开关，下行=状态文字；停用的整行降透明度。
 *  状态独立成行，生日这类长状态（下次 M/D · 还有 N 天 · 满 N 岁）不再把名称挤没。 */
@Composable
private fun AnniversaryCard(
    row: AnniversaryRow,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    AppSurfaceCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 12.dp,
        modifier = Modifier.alpha(if (row.enabled) 1f else 0.55f),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(OrangeTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    PillTag(
                        text = row.dateLabel,
                        backgroundColor = SurfaceWarmDeep,
                        contentColor = TextSecondary
                    )
                }
                row.note.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        fontSize = 12.sp,
                        color = TextHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    text = row.statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OrangeStart,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = LemonEnd,
                    checkedThumbColor = CardWhite
                ),
                modifier = Modifier.size(42.dp)
            )
        }
    }
}
