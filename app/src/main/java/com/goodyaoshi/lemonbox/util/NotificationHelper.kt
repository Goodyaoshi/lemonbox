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

    private const val TODO_CHANNEL_ID = "todo_reminder"
    private const val TODO_CHANNEL_NAME = "家务提醒"

    /** 家务提醒通知的 id 基数：每条提醒用自己的 id 通知，互不顶掉。 */
    private const val TODO_NOTIFICATION_ID_BASE = 2000L

    /** 汇总通知里单件物品的展示信息。 */
    data class ExpiryNotificationItem(
        val name: String,
        val daysLeft: Long
    )

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "用于提醒即将到期或已到期的物品"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                TODO_CHANNEL_ID,
                TODO_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "用于家务提醒与做菜前的准备提醒（如解冻肉）"
            }
        )
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

    /**
     * 单条家务提醒通知（解冻肉、洗衣服等）。点通知打开应用，首页卡片能看到当天待办。
     * 防重复由调用方 [TodoReminderWorker] 负责触发后立即推进 nextFireAt / 完成提醒。
     */
    fun showTodoReminder(
        context: Context,
        title: String,
        note: String,
        reminderId: Long
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = contentIntent?.let {
            android.app.PendingIntent.getActivity(
                context,
                reminderId.toInt(),
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, TODO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "别忘了这件事" })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify((TODO_NOTIFICATION_ID_BASE + reminderId).toInt(), notification)
    }
}