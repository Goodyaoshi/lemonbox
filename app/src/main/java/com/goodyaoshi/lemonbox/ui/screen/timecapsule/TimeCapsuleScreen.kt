package com.goodyaoshi.lemonbox.ui.screen.timecapsule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.TimeCapsule
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.LemonFab
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.TimeCapsuleViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间胶囊（F14）：写给未来某天的一段话，到点前只显示倒计时，到点后才可打开阅读。
 */
@Composable
fun TimeCapsuleScreen(
    onBack: () -> Unit,
    viewModel: TimeCapsuleViewModel = hiltViewModel()
) {
    val capsules by viewModel.capsules.collectAsState()
    var showCompose by remember { mutableStateOf(false) }
    var readingId by remember { mutableStateOf<Long?>(null) }
    // 删除入口（P3）：胶囊原先只能写不能删，这里补上左滑删除并二次确认。
    // 撤销提示条（P3-16）：软删只是写墓碑，删除后可在提示条里一键恢复。
    var openedKey by remember { mutableStateOf<Any?>(null) }
    var pendingDelete by remember { mutableStateOf<TimeCapsule?>(null) }
    val appSnackbar = LocalAppSnackbar.current

    Box(modifier = Modifier.fillMaxSize()) {
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
                    text = "时间胶囊",
                    // 字号走主题字阶（F6）：titleLarge 为 18sp。
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                CapsuleContent(
                    capsules = capsules,
                    openedKey = openedKey,
                    onOpenedChange = { openedKey = it },
                    onAdd = { showCompose = true },
                    // 只有已解锁的卡可点（未解锁卡在 CapsuleCard 里被禁用），点开即记录首次阅读。
                    onOpen = { capsule ->
                        viewModel.open(capsule)
                        readingId = capsule.id
                    },
                    onDeleteRequest = { pendingDelete = it }
                )
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        LemonFab(
            icon = Icons.Default.Add,
            contentDescription = "写一封时间胶囊",
            onClick = { showCompose = true },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }

    if (showCompose) {
        ComposeCapsuleDialog(
            onDismiss = { showCompose = false },
            onConfirm = { title, content, forWhom, unlockAt ->
                viewModel.create(title, content, unlockAt, forWhom) { showCompose = false }
            }
        )
    }

    val reading = capsules.firstOrNull { it.id == readingId }
    if (reading != null) {
        AppDialog(
            title = reading.title,
            onDismissRequest = { readingId = null },
            confirmText = "看完了",
            dismissText = null
        ) {
            reading.forWhom?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "写给：$it",
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
            }
            Text(
                text = reading.content.ifBlank { "（没有写正文）" },
                // 字号走主题字阶（F6）：titleMedium 为 16sp。
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }
    }

    // 删除确认（P3）：破坏性操作先弹二次确认；软删写墓碑，删错可在提示条里撤销（P3-16）。
    pendingDelete?.let { target ->
        AppDialog(
            title = "删除时间胶囊",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.delete(target)
                // 撤销提示条（P3-16）：与全 App 删除动作一致，软删后给一次反悔机会（复活墓碑）。
                appSnackbar?.showUndo("已删除「${target.title}」", DeleteCopy.UNDO_ACTION) {
                    viewModel.restore(target)
                }
                pendingDelete = null
            }
        ) {
            Text(
                // 文案与实现对齐（P3-16）：实际是软删、可撤销，不再说「无法恢复」。
                text = "确定要删除「${target.title}」吗？删除后可在提示条里撤销。",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun CapsuleContent(
    capsules: List<TimeCapsule>,
    openedKey: Any?,
    onOpenedChange: (Any?) -> Unit,
    onAdd: () -> Unit,
    onOpen: (TimeCapsule) -> Unit,
    onDeleteRequest: (TimeCapsule) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (capsules.isEmpty()) {
            EmptyState(
                title = "还没有时间胶囊",
                message = "写一段话给未来的自己或她，定个开启的日子，到那天再见。",
                actionLabel = "写一封",
                onAction = onAdd
            )
        } else {
            capsules.forEach { capsule ->
                // 左滑露出删除（P3）：与待买/打包同一套侧滑语言，未解锁的卡也能删。
                SwipeRevealItem(
                    itemKey = capsule.id,
                    openedItemKey = openedKey,
                    onOpenedItemChange = onOpenedChange,
                    actions = listOf(
                        SwipeActionSpec(
                            label = "删除",
                            icon = Icons.Default.Delete,
                            backgroundColor = StatusExpired
                        ) { onDeleteRequest(capsule) }
                    )
                ) { _, _ ->
                    CapsuleCard(capsule = capsule, onClick = { onOpen(capsule) })
                }
            }
        }
    }
}

@Composable
private fun CapsuleCard(capsule: TimeCapsule, onClick: () -> Unit) {
    val unlocked = capsule.isUnlocked()
    AppSurfaceCard(
        // 圆角走形状令牌（F6）：22dp 卡片收敛到 extraLarge（24dp）。
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 12.dp,
        // 未解锁卡整体弱化并禁用（P1）：点了没反应会让人以为卡死，这里直接把「点不动」表达出来；
        // 传 onClick = null 走非交互分支（不走 Material3 禁用态，免得卡底色被压到近乎透明）；
        // 倒计时改由下方徽标承载，不再依赖点击弹提示。
        modifier = Modifier.alpha(if (unlocked) 1f else 0.6f),
        onClick = if (unlocked) onClick else null
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
                    imageVector = when {
                        !unlocked -> Icons.Default.Lock
                        capsule.openedAt == null -> Icons.Default.MailOutline
                        else -> Icons.Outlined.MarkEmailRead
                    },
                    contentDescription = null,
                    tint = OrangeStart,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = capsule.title,
                        // 字号走主题字阶（F6）：titleMedium 为 16sp。
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    PillTag(
                        text = formatDate(capsule.unlockAt),
                        backgroundColor = SurfaceWarmDeep,
                        contentColor = TextSecondary
                    )
                }
                capsule.forWhom?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "写给：$it",
                        // 字号走主题字阶（F6）：bodySmall 为 12sp。
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                if (unlocked) {
                    Text(
                        text = statusText(capsule),
                        // 字号走主题字阶（F6）：bodySmall 为 12sp。
                        style = MaterialTheme.typography.bodySmall,
                        color = OrangeStart,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    // 倒计时徽标（P1）：未解锁卡把「还有几天」做成徽标，一眼看清状态。
                    PillTag(
                        text = "还有 ${capsule.daysUntilUnlock()} 天开启",
                        backgroundColor = SurfaceWarmDeep,
                        contentColor = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/** 写新胶囊：标题 / 正文 / 写给谁 / 解锁日期（默认一年后）。 */
@Composable
private fun ComposeCapsuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String, forWhom: String?, unlockAt: Long) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var forWhom by remember { mutableStateOf("") }
    val defaultUnlock = remember { LocalDate.now().plusYears(1) }
    var unlockDate by remember { mutableStateOf(defaultUnlock) }
    var showDatePicker by remember { mutableStateOf(false) }

    AppDialog(
        title = "写一封时间胶囊",
        onDismissRequest = onDismiss,
        confirmText = "封存",
        confirmEnabled = title.isNotBlank(),
        onConfirm = {
            onConfirm(title, content, forWhom, toStartOfDayMillis(unlockDate))
        }
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(MAX_TITLE) },
            singleLine = true,
            label = { Text("标题") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = content,
            onValueChange = { content = it.take(MAX_CONTENT) },
            label = { Text("想说的话") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = forWhom,
            onValueChange = { forWhom = it.take(MAX_FOR_WHOM) },
            singleLine = true,
            label = { Text("写给谁（可选）") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 圆角走形状令牌（F6）：16dp 收敛到 medium。
                .clip(MaterialTheme.shapes.medium)
                .background(SurfaceWarmDeep)
                .clickable { showDatePicker = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "开启日期",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDate(toStartOfDayMillis(unlockDate)),
                // 字号走主题字阶（F6）：14sp 日期值用 titleSmall 承载强调。
                style = MaterialTheme.typography.titleSmall,
                color = OrangeStart
            )
        }
    }

    if (showDatePicker) {
        ExpiryPickerDialog(
            selectedDateMillis = toStartOfDayMillis(unlockDate),
            onDismissRequest = { showDatePicker = false },
            onConfirm = { millis ->
                millis?.let { unlockDate = fromMillis(it) }
                showDatePicker = false
            },
            title = "选择开启日期",
            yearRange = LocalDate.now().year..(LocalDate.now().year + 30)
        )
    }
}

private fun statusText(capsule: TimeCapsule): String {
    if (!capsule.isUnlocked()) {
        return "还有 ${capsule.daysUntilUnlock()} 天可以打开"
    }
    val opened = capsule.openedAt
    return if (opened == null) "可以打开了 · 点开看看" else "已于 ${formatDate(opened)} 打开"
}

private val capsuleDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(capsuleDateFormatter)

private fun fromMillis(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun toStartOfDayMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private const val MAX_TITLE = 40
private const val MAX_CONTENT = 4000
private const val MAX_FOR_WHOM = 20
