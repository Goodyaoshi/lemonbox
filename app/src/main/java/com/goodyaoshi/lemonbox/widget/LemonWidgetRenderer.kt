package com.goodyaoshi.lemonbox.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.goodyaoshi.lemonbox.MainActivity
import com.goodyaoshi.lemonbox.R
import com.goodyaoshi.lemonbox.data.widget.WidgetSnapshot
import com.goodyaoshi.lemonbox.util.BrandCopy
import com.goodyaoshi.lemonbox.util.LedgerMath

/** 把小组件数据快照渲染成 RemoteViews（F04）；文案与 App 内保持一致。 */
object LemonWidgetRenderer {

    private const val MAX_NAMES = 3

    fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lemonbox)
        views.setTextViewText(R.id.widget_title, BrandCopy.APP_NAME)
        views.setTextViewText(R.id.widget_tobuy, toBuyText(snapshot.toBuyNames))
        views.setTextViewText(R.id.widget_today, "今天 ${snapshot.todayReminderCount} 件事")
        views.setTextViewText(
            R.id.widget_month,
            "本月支出 ¥${formatYuan(snapshot.monthExpenseCents)}"
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        // 读屏（F18）：小组件整体作为一条入口播报，汇总核心数据，避免逐个子视图离散朗读。
        views.setContentDescription(
            R.id.widget_root,
            "${BrandCopy.APP_NAME}小组件：${toBuyText(snapshot.toBuyNames)}，" +
                "今天 ${snapshot.todayReminderCount} 件事，" +
                "本月支出 ¥${formatYuan(snapshot.monthExpenseCents)}，点按打开应用"
        )
        return views
    }

    private fun toBuyText(names: List<String>): String {
        if (names.isEmpty()) return "待买：暂时没有要买的"
        val head = names.take(MAX_NAMES).joinToString("、")
        val suffix = if (names.size > MAX_NAMES) " 等 ${names.size} 件" else ""
        return "待买：$head$suffix"
    }

    /** 分转元展示：整数不带小数点，零头保留两位。 */
    private fun formatYuan(cents: Long): String {
        val yuan = LedgerMath.centsToYuan(cents)
        return if (yuan % 1.0 == 0.0) yuan.toLong().toString() else "%.2f".format(yuan)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
