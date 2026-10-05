package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerCategoryDetailViewModel
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath

/** 统计分类明细：某月某分类的账单流水，行样式复用记账主页的流水行。 */
@Composable
fun LedgerCategoryDetailScreen(
    onBack: () -> Unit,
    onNavigateToRecordEdit: (Long) -> Unit,
    viewModel: LedgerCategoryDetailViewModel = hiltViewModel()
) {
    val detail by viewModel.detail.collectAsState()

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
                Column {
                    Text(
                        text = "${detail.categoryName} · ${viewModel.monthLabel}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "当月合计 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(detail.totalCents))}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            if (detail.records.isEmpty()) {
                EmptyState(
                    title = "这个月还没有记录",
                    message = "「${detail.categoryName}」本月还没有账单，记一笔就会出现在这里。",
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(detail.records, key = { "rec-${it.record.id}" }) { recordUi ->
                        LedgerRecordRow(
                            recordUi = recordUi,
                            onOpenItem = null,
                            onClick = { onNavigateToRecordEdit(recordUi.record.id) }
                        )
                    }
                }
            }
        }
    }
}
