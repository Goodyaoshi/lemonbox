package com.goodyaoshi.lemonbox.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.goodyaoshi.lemonbox.R

object NotificationHelper {

    private const val CHANNEL_ID = "expiry_reminder"
    private const val CHANNEL_NAME = "到期提醒"
    private const val SUMMARY_NOTIFICATION_ID = 1001

    /** 汇总通知里单件物品的展示信息。 */
    data class ExpiryNotificationItem(
        val name: String,
        val daysLeft: Long
    )

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "用于提醒即将到期或已到期的物品"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * 把多件临期/过期物品合并为一条汇总通知，避免同一时刻刷屏。
     * 去重（按天/按时间点）由调用方 [ExpiryCheckWorker] 通过 AppPreferences 负责。
     */
    fun showExpirySummary(
        context: Context,
        items: List<ExpiryNotificationItem>
    ) {
        if (items.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val expiredCount = items.count { it.daysLeft < 0 }
        val todayCount = items.count { it.daysLeft == 0L }
        val soonCount = items.size - expiredCount - todayCount

        val title = "柠檬百宝箱 · ${items.size} 件物品需要留意"
        val summaryText = buildString {
            if (expiredCount > 0) append("$expiredCount 件已过期")
            if (todayCount > 0) {
                if (isNotEmpty()) append("，")
                append("$todayCount 件今天到期")
            }
            if (soonCount > 0) {
                if (isNotEmpty()) append("，")
                append("$soonCount 件即将到期")
            }
        }

        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
        items.take(5).forEach { item ->
            style.addLine("「${item.name}」${describeDays(item.daysLeft)}")
        }
        if (items.size > 5) {
            style.setSummaryText("另有 ${items.size - 5} 件…")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun describeDays(daysLeft: Long): String = when {
        daysLeft < 0 -> "已过期 ${-daysLeft} 天"
        daysLeft == 0L -> "今天到期"
        else -> "还有 $daysLeft 天到期"
    }
}