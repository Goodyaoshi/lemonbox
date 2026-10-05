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
    private const val TODO_CHANNEL_NAME = "待办提醒"

    private const val ANNIVERSARY_CHANNEL_ID = "anniversary_reminder"
    private const val ANNIVERSARY_CHANNEL_NAME = "纪念日提醒"
    private const val ANNIVERSARY_SUMMARY_NOTIFICATION_ID = 1002

    /** 待办提醒通知的 id 基数：每条提醒用自己的 id 通知，互不顶掉；「完成」按钮撤通知也用它。 */
    const val TODO_NOTIFICATION_ID_BASE = 2000L

    private const val KEEP_ALIVE_CHANNEL_ID = "keep_alive"
    private const val KEEP_ALIVE_CHANNEL_NAME = "提醒保活"
    const val KEEP_ALIVE_NOTIFICATION_ID = 1999

    /** 汇总通知里单件物品的展示信息。 */
    data class ExpiryNotificationItem(
        val name: String,
        val daysLeft: Long
    )

    /** 汇总通知里单个纪念日的展示信息（行文案由 Worker 组装）。 */
    data class AnniversaryNotificationItem(
        val name: String,
        val lineText: String
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
                description = "用于待办提醒与做菜前的准备提醒（如解冻肉）"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ANNIVERSARY_CHANNEL_ID,
                ANNIVERSARY_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "用于生日、纪念日与倒数的提前提醒"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                KEEP_ALIVE_CHANNEL_ID,
                KEEP_ALIVE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持提醒服务常驻，避免被系统省电策略终止提醒"
                setShowBadge(false)
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
     * 把多个临近的纪念日合并为一条汇总通知（生日/倒数/周年等）。
     * 去重（按天/按时间点、逐条记录）由调用方 [AnniversaryCheckWorker] 负责。
     */
    fun showAnniversarySummary(
        context: Context,
        items: List<AnniversaryNotificationItem>
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

        val todayCount = items.count { it.lineText == "就是今天" }
        val title = "柠檬百宝箱 · ${items.size} 个纪念日要记着"
        val summaryText = if (todayCount > 0) {
            "今天就是 $todayCount 个，别忘了准备"
        } else {
            "提前提醒，方便准备心意"
        }

        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
        items.take(5).forEach { item ->
            style.addLine("「${item.name}」${item.lineText}")
        }
        if (items.size > 5) {
            style.setSummaryText("另有 ${items.size - 5} 个…")
        }

        val notification = NotificationCompat.Builder(context, ANNIVERSARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(ANNIVERSARY_SUMMARY_NOTIFICATION_ID, notification)
    }

    /**
     * 单条待办提醒通知（解冻肉、洗衣服等）。点通知打开应用，首页卡片能看到当天待办；
     * 通知自带「完成」按钮，点一下直接完结这条待办。
     * 防重复由调用方 [TodoReminderWorker] 通过 notifiedAt 标记负责。
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
        val completeIntent = android.app.PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            TodoCompleteReceiver.intent(context, reminderId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TODO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "别忘了这件事" })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .addAction(0, "完成", completeIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify((TODO_NOTIFICATION_ID_BASE + reminderId).toInt(), notification)
    }

    /** 保活服务独立拉起时确保渠道存在（幂等，与 createChannel 重复调用无副作用）。 */
    fun createKeepAliveChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                KEEP_ALIVE_CHANNEL_ID,
                KEEP_ALIVE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持提醒服务常驻，避免被系统省电策略终止提醒"
                setShowBadge(false)
            }
        )
    }

    /** 保活前台服务的常驻通知：最低重要度，无声无横幅，不占角标。 */
    fun buildKeepAliveNotification(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, KEEP_ALIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("柠檬提醒服务运行中")
            .setContentText("保持常驻，到点准时提醒")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
}