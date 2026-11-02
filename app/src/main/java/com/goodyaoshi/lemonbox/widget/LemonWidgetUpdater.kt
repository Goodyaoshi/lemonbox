package com.goodyaoshi.lemonbox.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.goodyaoshi.lemonbox.data.widget.WidgetDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小组件刷新器（F04）：把「取数 + 渲染 + 分发到所有实例」收在一处，
 * 供 [LemonBoxWidgetProvider]（收到系统更新广播时）与
 * [com.goodyaoshi.lemonbox.util.WidgetRefreshWorker]（后台周期刷新）复用。
 */
@Singleton
class LemonWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataProvider: WidgetDataProvider
) {

    /**
     * 应用级异步刷新：从 Activity / Application 这类没有长效作用域的位置调用。
     * 用独立的 IO 作用域而不是调用方的生命周期作用域，避免 Activity 退栈时连同取数协程一起取消，
     * 导致「改完数据回桌面仍是旧值」。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, LemonBoxWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        val views = LemonWidgetRenderer.render(context, dataProvider.snapshot())
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
