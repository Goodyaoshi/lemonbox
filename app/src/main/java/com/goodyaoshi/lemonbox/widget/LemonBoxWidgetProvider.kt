package com.goodyaoshi.lemonbox.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import com.goodyaoshi.lemonbox.util.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 桌面小组件（F04）：无需解锁即可看到待买清单、今天的事与本月支出。
 *
 * 系统在添加 / 更新小组件时回调 [onUpdate]；这里用 goAsync 切到协程读库，
 * 再统一下发给所有实例。后台周期刷新由 [com.goodyaoshi.lemonbox.util.WidgetRefreshWorker] 负责。
 */
@AndroidEntryPoint
class LemonBoxWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var updater: LemonWidgetUpdater

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 有实例时才需要周期刷新；升级/重建后系统会重新回调 onUpdate，这里补一次排期。
        WidgetRefreshWorker.schedule(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                updater.refresh()
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 最后一个实例被移除：停止每 30 分钟的进程唤醒。
        WidgetRefreshWorker.cancel(context)
    }
}
