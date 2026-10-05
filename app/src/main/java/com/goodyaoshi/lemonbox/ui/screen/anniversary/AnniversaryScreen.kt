package com.goodyaoshi.lemonbox.ui.screen.anniversary

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryRow
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryViewModel

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
                    text = "纪念日",
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
                AnniversaryContent(
                    rows = rows,
                    onToggle = { row, enabled ->
                        viewModel.setEnabled(row.anniversary, enabled)
                    },
                    onOpen = { onOpenEdit(it.anniversary.id) }
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
                .background(
                    brush = Brush.linearGradient(colors = listOf(LemonStart, LemonEnd))
                )
                .clickable { onOpenEdit(null) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "新增纪念日",
                tint = OnLemon,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** 纪念日列表主体：空态一句话引导；卡片点进去编辑，开关直接停用提醒。 */
@Composable
private fun AnniversaryContent(
    rows: List<AnniversaryRow>,
    onToggle: (AnniversaryRow, Boolean) -> Unit,
    onOpen: (AnniversaryRow) -> Unit
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
                message = "点右下角的「+」，把在一起的日子、生日和要倒数的事记下来。"
            )
        } else {
            rows.forEach { row ->
                AnniversaryCard(
                    row = row,
                    onToggle = { enabled -> onToggle(row, enabled) },
                    onClick = { onOpen(row) }
                )
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
