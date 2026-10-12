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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.TimeCapsule
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.ExpiryPickerDialog
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
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
    val appSnackbar = LocalAppSnackbar.current
    var showCompose by remember { mutableStateOf(false) }
    var readingId by remember { mutableStateOf<Long?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
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
                    onAdd = { showCompose = true },
                    onOpen = { capsule ->
                        if (capsule.isUnlocked()) {
                            viewModel.open(capsule)
                            readingId = capsule.id
                        } else {
                            appSnackbar?.postMessage(
                                "还有 ${capsule.daysUntilUnlock()} 天才能打开"
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd)))
                .clickable { showCompose = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "写一封时间胶囊",
                tint = OnLemon,
                modifier = Modifier.size(28.dp)
            )
        }
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
                Text(text = "写给：$it", fontSize = 12.sp, color = TextHint)
            }
            Text(
                text = reading.content.ifBlank { "（没有写正文）" },
                fontSize = 15.sp,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun CapsuleContent(
    capsules: List<TimeCapsule>,
    onAdd: () -> Unit,
    onOpen: (TimeCapsule) -> Unit
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
                CapsuleCard(capsule = capsule, onClick = { onOpen(capsule) })
            }
        }
    }
}

@Composable
private fun CapsuleCard(capsule: TimeCapsule, onClick: () -> Unit) {
    val unlocked = capsule.isUnlocked()
    AppSurfaceCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shadowElevation = 12.dp,
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
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
                        fontSize = 12.sp,
                        color = TextHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    text = statusText(capsule),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unlocked) OrangeStart else TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
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
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceWarmDeep)
                .clickable { showDatePicker = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "开启日期",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDate(toStartOfDayMillis(unlockDate)),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
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
