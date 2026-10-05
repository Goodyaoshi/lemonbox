package com.goodyaoshi.lemonbox.ui.screen.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.dao.LedgerAssetWithBalance
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.AppDialog
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorInputBox
import com.goodyaoshi.lemonbox.ui.components.EditorSectionLabel
import com.goodyaoshi.lemonbox.ui.components.EmptyState
import com.goodyaoshi.lemonbox.ui.components.GradientButton
import com.goodyaoshi.lemonbox.ui.components.SegmentedTabs
import com.goodyaoshi.lemonbox.ui.components.SwipeActionSpec
import com.goodyaoshi.lemonbox.ui.components.SwipeRevealItem
import com.goodyaoshi.lemonbox.ui.components.ledgerIconFor
import com.goodyaoshi.lemonbox.ui.components.ledgerIconOptions
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.LedgerAssetsViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.LEDGER_ASSET_TYPE_CREDIT
import com.goodyaoshi.lemonbox.ui.viewmodel.LEDGER_ASSET_TYPE_NORMAL
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath

/** 分 → 金额文本（带 ¥）。 */
private fun formatCents(cents: Long): String =
    DateUtil.formatCurrency(LedgerMath.centsToYuan(cents))

/**
 * 资产负债登记：顶部汇总卡（总资产/总负债/净资产）+ 资产/负债切换 +
 * 账户增删改。余额口径复用记账页的账户聚合，不落库。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerAssetsScreen(
    onBack: () -> Unit,
    viewModel: LedgerAssetsViewModel = hiltViewModel()
) {
    val assets by viewModel.assetsWithBalance.collectAsState()
    val summary by viewModel.summary.collectAsState()
    var tabIndex by remember { mutableIntStateOf(0) }
    var editingAsset by remember { mutableStateOf<LedgerAssetWithBalance?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var openedAssetId by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerAssetWithBalance?>(null) }
    var deleteBlockedName by remember { mutableStateOf<String?>(null) }

    val currentType = if (tabIndex == 1) LEDGER_ASSET_TYPE_CREDIT else LEDGER_ASSET_TYPE_NORMAL
    val groupLabel = if (tabIndex == 1) "负债" else "资产"
    val visibleAssets = remember(assets, tabIndex) {
        assets.filter {
            it.type == if (tabIndex == 1) LEDGER_ASSET_TYPE_CREDIT else LEDGER_ASSET_TYPE_NORMAL
        }
    }

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
                    text = "资产负债",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                AppSurfaceCard(
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    shadowElevation = 12.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SummaryItem(
                            label = "总资产",
                            value = formatCents(summary.totalAssetCents),
                            valueColor = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryItem(
                            label = "总负债",
                            value = formatCents(summary.totalLiabilityCents),
                            valueColor = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryItem(
                            label = "净资产",
                            value = formatCents(summary.netAssetCents),
                            valueColor = OrangeStart,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (assets.any { it.type == LEDGER_ASSET_TYPE_CREDIT }) {
                        Text(
                            text = "信用卡的欠款已算进总负债",
                            fontSize = 11.sp,
                            color = TextHint,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SegmentedTabs(
                    labels = listOf("资产", "负债"),
                    selectedIndex = tabIndex,
                    onSelect = { tabIndex = it }
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (visibleAssets.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "还没有${groupLabel}账户",
                            message = if (tabIndex == 1) {
                                "有信用卡的话登记进来，欠款会自动算进负债。"
                            } else {
                                "点下方按钮，把现金、银行卡都登记进来吧。"
                            },
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    items(visibleAssets, key = { it.id }) { asset ->
                        SwipeRevealItem(
                            itemKey = asset.id,
                            openedItemKey = openedAssetId,
                            onOpenedItemChange = { openedAssetId = it as Long? },
                            actions = listOf(
                                SwipeActionSpec(
                                    label = "删除",
                                    icon = Icons.Filled.Delete,
                                    backgroundColor = StatusExpired,
                                    onClick = { pendingDelete = asset }
                                )
                            )
                        ) { _, _ ->
                            LedgerAssetRow(
                                asset = asset,
                                onClick = { editingAsset = asset }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                GradientButton(
                    text = "添加$groupLabel",
                    onClick = { showAddDialog = true }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAddDialog) {
        LedgerAssetEditDialog(
            existing = null,
            defaultType = currentType,
            onDismissRequest = { showAddDialog = false },
            onConfirm = { name, iconKey, initialBalanceCents, type ->
                viewModel.saveAsset(
                    id = null,
                    name = name,
                    iconKey = iconKey,
                    initialBalanceCents = initialBalanceCents,
                    type = type
                ) { showAddDialog = false }
            }
        )
    }

    editingAsset?.let { asset ->
        LedgerAssetEditDialog(
            existing = asset,
            defaultType = currentType,
            onDismissRequest = { editingAsset = null },
            onConfirm = { name, iconKey, initialBalanceCents, type ->
                viewModel.saveAsset(
                    id = asset.id,
                    name = name,
                    iconKey = iconKey,
                    initialBalanceCents = initialBalanceCents,
                    type = type
                ) { editingAsset = null }
            }
        )
    }

    pendingDelete?.let { asset ->
        AppDialog(
            title = "删除账户",
            subtitle = "如果还有账单在用它，就暂时删不掉。",
            onDismissRequest = { pendingDelete = null },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.deleteAsset(asset.id) { success ->
                    if (!success) deleteBlockedName = asset.name
                }
                pendingDelete = null
            }
        ) {
            Text(
                text = "确定要删除「${asset.name}」吗？",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }

    deleteBlockedName?.let { name ->
        AppDialog(
            title = "暂时不能删除",
            subtitle = "「$name」还有账单在用，删掉会让历史账单对不上号。",
            onDismissRequest = { deleteBlockedName = null },
            confirmText = "知道了",
            dismissText = null
        ) {
            Text(
                text = "可以先把相关账单删掉或换到别的账户，再回来删除哦。",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/** 账户行：图标 + 名称 + 当前余额，点击进入编辑。 */
@Composable
private fun LedgerAssetRow(
    asset: LedgerAssetWithBalance,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
                imageVector = ledgerIconFor(asset.icon),
                contentDescription = null,
                tint = OrangeStart,
                modifier = Modifier.size(19.dp)
            )
        }
        Text(
            text = asset.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        Text(
            text = formatCents(asset.balance),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}

/** 汇总卡里的单项：标签 + 金额。 */
@Composable
private fun SummaryItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextHint
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 账户编辑弹窗：名称 + 图标 + 初始余额 + 资产/负债性质。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerAssetEditDialog(
    existing: LedgerAssetWithBalance?,
    defaultType: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (name: String, iconKey: String, initialBalanceCents: Long, type: Int) -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var iconKey by remember(existing) {
        mutableStateOf(existing?.icon?.takeIf { it.isNotBlank() } ?: "cash")
    }
    var balanceText by remember(existing) {
        mutableStateOf(LedgerMath.centsToInputText(existing?.initialBalance ?: 0L))
    }
    var type by remember(existing) {
        mutableIntStateOf(existing?.type ?: defaultType)
    }
    AppDialog(
        title = if (existing == null) "添加账户" else "编辑账户",
        subtitle = "普通账户计入资产，信用卡计入负债。",
        onDismissRequest = onDismissRequest,
        confirmText = "保存",
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            onConfirm(name, iconKey, LedgerMath.parseCentsInput(balanceText) ?: 0L, type)
        }
    ) {
        EditorInputBox(
            value = name,
            onValueChange = { name = it },
            placeholder = "账户名称，比如 微信、招行信用卡"
        )
        Spacer(modifier = Modifier.height(12.dp))
        EditorSectionLabel(label = "初始余额（元）")
        Spacer(modifier = Modifier.height(8.dp))
        EditorInputBox(
            value = balanceText,
            onValueChange = { balanceText = it },
            placeholder = "现在有多少钱就填多少"
        )
        Spacer(modifier = Modifier.height(12.dp))
        EditorSectionLabel(label = "图标")
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ledgerIconOptions.forEach { option ->
                IconPickCell(
                    option = option,
                    selected = iconKey == option.key,
                    onClick = { iconKey = option.key }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SegmentedTabs(
            labels = listOf("资产", "负债"),
            selectedIndex = if (type == LEDGER_ASSET_TYPE_CREDIT) 1 else 0,
            onSelect = {
                type = if (it == 1) LEDGER_ASSET_TYPE_CREDIT else LEDGER_ASSET_TYPE_NORMAL
            }
        )
    }
}

