package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun ItemCard(
    itemDetail: ItemDetail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
                        text = "数量 ${item.quantity}${item.unit}",
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

                // 使用周期：单独占一行，天数和日均不会被数量/价格挤掉。
                val usageDayCount = Item.usageDays(
                    startUseTime = item.startUseTime,
                    purchaseDate = item.purchaseDate,
                    createdAt = item.createdAt,
                    usageEndedAt = item.usageEndedAt,
                    usageStatus = item.usageStatus,
                    disposition = item.disposition
                )
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 卡片空间有限，只展示一个最能说明问题的状态标签，按优先级取一个。
                    val statusTag: Triple<String, Color, Color>? = when {
                        item.disposition != Item.DISPOSITION_IN_STOCK -> {
                            val (container, content) =
                                itemStatusColors(StatusDimension.DISPOSITION, item.disposition)
                            Triple(
                                itemStatusOption(StatusDimension.DISPOSITION, item.disposition).label,
                                container,
                                content
                            )
                        }

                        item.needRestock -> Triple("待买", TagOrange, TagOrangeText)

                        item.usageStatus != Item.USAGE_IN_USE -> {
                            val (container, content) =
                                itemStatusColors(StatusDimension.USAGE, item.usageStatus)
                            Triple(
                                itemStatusOption(StatusDimension.USAGE, item.usageStatus).label,
                                container,
                                content
                            )
                        }

                        else -> null
                    }

                    statusTag?.let { (text, container, content) ->
                        PillTag(
                            text = text,
                            backgroundColor = container,
                            contentColor = content
                        )
                    }

                    item.expireTime?.let { expireTime ->
                        val days = DateUtil.daysUntil(expireTime)
                        val (container, content) = when {
                            days < 0 -> TagRed to TagRedText
                            days <= 3 -> TagRed to TagRedText
                            days <= 7 -> StatusWarning.copy(alpha = 0.12f) to StatusWarning
                            else -> StatusNormal.copy(alpha = 0.12f) to StatusNormal
                        }
                        PillTag(
                            text = DateUtil.expiryCountdownText(expireTime),
                            backgroundColor = container,
                            contentColor = content
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
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
