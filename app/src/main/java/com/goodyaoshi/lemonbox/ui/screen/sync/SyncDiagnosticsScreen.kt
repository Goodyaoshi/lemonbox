package com.goodyaoshi.lemonbox.ui.screen.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.backup.FieldDiff
import com.goodyaoshi.lemonbox.data.remote.ConflictLogEntry
import com.goodyaoshi.lemonbox.data.remote.SyncAuditRow
import com.goodyaoshi.lemonbox.data.remote.collectionLabel
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.ConflictFieldDiffLine
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.InlineNotice
import com.goodyaoshi.lemonbox.ui.components.NoticeVariant
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarmDeep
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.SyncDiagnosticsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步诊断（P9）：多设备同步「到底对不对」的可查证据页。
 *
 * 同步是后台自动的，出了问题用户只有一句「有几条冲突」可看；这一页把账摊开：
 * 逐表三方计数（本机 live / 含墓碑 / 服务端 live）、syncId 缺失的孤儿行、
 * 冲突留痕的字段级对比、服务端设备列表，以及最后的兜底手段——
 * 「以服务端为准重建本地」（危险操作：二次确认 + 撤销回执）。
 */
@Composable
fun SyncDiagnosticsScreen(
    onBack: () -> Unit,
    // 空态直达「云端同步」页（P2-23）：诊断只对已登录并开启多设备同步的设备有意义，
    // 未连接时给一条去登录/开开关的路，别让用户停在纯说明文案前。
    onOpenCloudSync: () -> Unit = {},
    viewModel: SyncDiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AppDecorativeBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    text = "同步诊断",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                if (!state.ready) {
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        Text(
                            text = "还没有连接多设备同步",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "登录并开启云同步后，这里会显示本机与服务端的逐表对账、冲突留痕与设备列表。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        // 空态给出去处（P2-23）：直接跳到「云端同步」完成登录与开关。
                        Spacer(modifier = Modifier.height(12.dp))
                        GradientButton(
                            text = "去云端同步",
                            onClick = onOpenCloudSync
                        )
                    }
                    return@Column
                }

                GradientButton(
                    text = if (state.busy) "正在对账…" else "立即对账",
                    enabled = !state.busy,
                    onClick = viewModel::refresh
                )

                if (state.skippedPayloads > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    InlineNotice(
                        message = "上次同步有 ${state.skippedPayloads} 条服务端记录无法解析，已跳过。" +
                            "这些记录不会落地到本机；请先导出本地备份，再把此数字反馈给维护者。",
                        variant = NoticeVariant.Warning
                    )
                }

                state.message?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = OrangeStart
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        NoticeLine(text = message, isError = state.messageIsError)
                    }
                }
                // 撤销回执常驻到用户离开页面：重建是危险操作，撤销入口不能藏在几秒就消失的提示条里。
                state.undoZipPath?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = viewModel::undoRebuild, enabled = !state.busy) {
                        Text(text = "撤销重建：恢复重建前的本机数据", color = StatusExpired)
                    }
                }

                state.report?.let { report ->
                    Spacer(modifier = Modifier.height(22.dp))
                    EditorSectionLabel(label = "逐表对账")
                    Spacer(modifier = Modifier.height(8.dp))
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        Text(
                            text = "「差」＝本机 − 服务端：正数是有记录还没上传，负数是有记录还没拉下来。" +
                                "服务端序号 ${report.serverSeq}，历史冲突 ${report.conflictCount} 条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "类别",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextHint,
                                modifier = Modifier.weight(1.2f)
                            )
                            Text(
                                text = "本机",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextHint,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "服务端",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextHint,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "差",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextHint,
                                modifier = Modifier.weight(0.6f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        report.rows.forEachIndexed { index, row ->
                            if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                            AuditRowLine(row)
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    EditorSectionLabel(label = "设备")
                    Spacer(modifier = Modifier.height(8.dp))
                    AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                        if (report.devices.isEmpty()) {
                            Text(
                                text = "服务端还没登记过任何设备",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            report.devices.forEachIndexed { index, device ->
                                if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                                Column {
                                    Text(
                                        text = device.name.ifEmpty { "未命名设备" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "最近活跃：${formatDateTime(device.lastSeenAt)}，" +
                                            "已同步到序号 ${device.lastPullSeq}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                EditorSectionLabel(label = "冲突留痕")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    if (state.conflicts.isEmpty()) {
                        Text(
                            text = "没有留痕。推送撞上冲突时，两边的版本会各留一份在这里，供事后逐字段对比。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else {
                        Text(
                            text = "这些条目推送时两边都改过，按本地优先收场：本机版本会重推，其它设备下一轮拉走。" +
                                "「本机值」是冲突发生那一刻的改动，可回到对应页面重新编辑。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint
                        )
                    }
                    state.conflicts.forEach { entry ->
                        Spacer(modifier = Modifier.height(12.dp))
                        ConflictEntryCard(entry = entry, diffsProvider = viewModel::diffsFor)
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                EditorSectionLabel(label = "合并上传")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    Text(
                        text = "用本机版本覆盖服务端上的同一条记录，再拉回其它设备独有的记录；" +
                            "适合确认「本机这份才对」时使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = viewModel::showUploadLocalConfirm, enabled = !state.busy) {
                        Text(text = "以本机为准合并上传…", color = OrangeStart)
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                EditorSectionLabel(label = "重建")
                Spacer(modifier = Modifier.height(8.dp))
                AppSurfaceCard(shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusExpired,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "以服务端为准重建本地",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusExpired
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "清空本机后从服务端原样拉回。重建前会自动留一份可撤销的本机备份，" +
                            "仅在确认服务端那份才对时使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = viewModel::showRebuildConfirm, enabled = !state.busy) {
                        Text(text = "重建本地数据…", color = StatusExpired)
                    }
                }

                if (state.showRebuildConfirm) {
                    AppDialog(
                        title = "清空本机并从服务端重建？",
                        subtitle = "本机所有数据（家当、记账、待办等）会先被清空，再从服务端拉回。" +
                            "本机还没同步上去的改动会随之丢失（可在重建后撤销找回）。确定要继续吗？",
                        confirmText = "清空并重建",
                        destructiveConfirm = true,
                        onConfirm = viewModel::rebuildFromServer,
                        onDismissRequest = viewModel::dismissRebuildConfirm
                    )
                }

                if (state.showUploadLocalConfirm) {
                    AppDialog(
                        title = "把本机数据合并上传到服务端？",
                        subtitle = "本机全部记录会以本机版本覆盖服务端上的同一条记录，然后拉回其它设备独有的记录；" +
                            "不会删除其它设备独有的数据。适合确认「本机这份才是对的」时使用。",
                        confirmText = "以本机为准上传",
                        destructiveConfirm = true,
                        onConfirm = viewModel::uploadLocalEverything,
                        onDismissRequest = viewModel::dismissUploadLocalConfirm
                    )
                }
            }
        }
    }
}

/** 对账一行：类别｜本机 live（含墓碑总数）｜服务端 live｜差。 */
@Composable
private fun AuditRowLine(row: SyncAuditRow) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1.2f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.missingSyncId > 0) {
                Text(
                    text = "有 ${row.missingSyncId} 行缺同步标识，推不出去",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusExpired
                )
            }
        }
        Text(
            text = row.localLive?.let { live ->
                if (row.localTotal > live) "$live（总 ${row.localTotal}）" else "$live"
            } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (row.serverKnown) "${row.serverLive}" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${row.delta}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (row.delta == 0) TextSecondary else StatusExpired,
            modifier = Modifier.weight(0.6f)
        )
    }
}

/** 一条冲突留痕：谁在何时盖的 + 本机值 / 服务端值的字段对比。 */
@Composable
private fun ConflictEntryCard(
    entry: ConflictLogEntry,
    diffsProvider: (ConflictLogEntry) -> List<FieldDiff>
) {
    val diffs = remember(entry) { diffsProvider(entry) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(SurfaceWarmDeep)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "${collectionTitle(entry.collection)} · ${formatDateTime(entry.at)}" +
                entry.updatedBy.takeIf { it.isNotBlank() }?.let { " · 由 $it 改在服务端" }.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = TextHint
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when (entry.resolution) {
                ConflictLogEntry.RESOLUTION_LOCAL ->
                    "结果：本机优先，已推上服务端，两边一致为本机那版"
                ConflictLogEntry.RESOLUTION_PENDING ->
                    "结果：本机版本已保留；重推时服务端又被改过，已排队到下一轮继续上传"
                else -> "结果：服务端版本更新，本机已改为服务端版本"
            },
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (diffs.isEmpty()) {
            Text(
                text = "留痕时服务端未带回内容，无法逐字段对比。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                diffs.forEach { diff -> ConflictFieldDiffLine(diff = diff) }
            }
        }
    }
}

/** 留痕里的集合名用与对账一致的中文标签；未知集合原样显示。 */
@Composable
private fun collectionTitle(collection: String): String = collectionLabel(collection)

/** 一行状态提示：失败用红字，中性 / 成功用次要色。 */
@Composable
private fun NoticeLine(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) StatusExpired else TextSecondary,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/** 时间戳 → 「yyyy-MM-dd HH:mm」。 */
private fun formatDateTime(timestamp: Long): String =
    if (timestamp <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
