package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagRed
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.util.DateUtil

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemCard(
    itemDetail: ItemDetail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * I8：行尾常驻「更多」按钮的回调。滑动不是唯一入口，
     * 不熟悉手势或使用读屏的用户也能直接打开完整操作面板；
     * 传 null（如只读的过期列表）时不渲染该按钮。
     */
    onMore: (() -> Unit)? = null
) {
    val item = itemDetail.item
    val primaryImagePath = item.primaryImagePath()

    AppSurfaceCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        shadowElevation = 12.dp,
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (primaryImagePath.isNotEmpty()) {
                AsyncImage(
                    model = primaryImagePath,
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(OrangeStart.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.name.take(1),
                        color = OrangeStart,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                itemDetail.categoryName?.let { category ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = categoryIconFor(itemDetail.categoryIcon),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = OrangeStart
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = category,
                            fontSize = 12.sp,
                            color = OrangeStart,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                itemDetail.locationName?.let { location ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = location,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (item.trackMode == Item.TRACK_CONSUMABLE && item.consumedQuantity > 0) {
                            "剩余 ${item.quantity}${item.unit} · 已用 ${item.consumedQuantity}"
                        } else {
                            "数量 ${item.quantity}${item.unit}"
                        },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    item.price?.let { price ->
                        Text(
                            text = DateUtil.formatCurrency(price),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                // 使用周期：只对持续使用型展示「用了多少天 / 日均」，按件消耗型看库存即可。
                val usageDayCount = if (item.trackMode == Item.TRACK_DURABLE) {
                    Item.usageDays(
                        startUseTime = item.startUseTime,
                        purchaseDate = item.purchaseDate,
                        createdAt = item.createdAt,
                        usageEndedAt = item.usageEndedAt,
                        usageStatus = item.usageStatus,
                        disposition = item.disposition
                    )
                } else {
                    0
                }
                if (usageDayCount > 0) {
                    val dailyCost = Item.averageDailyCost(item.price, item.quantity, usageDayCount)
                    Text(
                        text = if (dailyCost != null) {
                            "已用 ${usageDayCount}天 ｜ 日均 ${DateUtil.formatCurrency(dailyCost)}"
                        } else {
                            "已用 ${usageDayCount}天"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 状态标签并列展示：三个维度（去向 / 待买 / 使用状态）互不挤出，
                // 到期倒计时恒显，避免「只看得到一个标签」而漏掉关键信息。
                val statusTags = buildList {
                    if (item.disposition != Item.DISPOSITION_IN_STOCK) {
                        val (container, content) =
                            itemStatusColors(StatusDimension.DISPOSITION, item.disposition)
                        add(
                            Triple(
                                itemStatusOption(StatusDimension.DISPOSITION, item.disposition).label,
                                container,
                                content
                            )
                        )
                    }
                    if (item.needRestock) {
                        add(Triple("待买", TagOrange, TagOrangeText))
                    }
                    if (item.usageStatus != Item.USAGE_IN_USE) {
                        val (container, content) =
                            itemStatusColors(StatusDimension.USAGE, item.usageStatus)
                        add(
                            Triple(
                                itemStatusOption(StatusDimension.USAGE, item.usageStatus).label,
                                container,
                                content
                            )
                        )
                    }
                    item.expireTime?.let { expireTime ->
                        val days = DateUtil.daysUntil(expireTime)
                        val (container, content) = when {
                            days <= 3 -> TagRed to TagRedText
                            days <= 7 -> StatusWarning.copy(alpha = 0.12f) to StatusWarning
                            else -> StatusNormal.copy(alpha = 0.12f) to StatusNormal
                        }
                        add(Triple(DateUtil.expiryCountdownText(expireTime), container, content))
                    }
                }

                if (statusTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        statusTags.forEach { (text, container, content) ->
                            PillTag(
                                text = text,
                                backgroundColor = container,
                                contentColor = content
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            // I8：行尾常驻「更多」按钮，与左滑露出的是同一组动作，
            // 让入口可见、可被读屏聚焦，不再把手势当作唯一通道；
            // 热区对齐 48dp（图标仍 18dp），视觉克制但便于点按。
            onMore?.let { openMore ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = openMore),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(18.dp),
                        tint = TextSecondary
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(OrangeTint, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
            }
        }
    }
}
