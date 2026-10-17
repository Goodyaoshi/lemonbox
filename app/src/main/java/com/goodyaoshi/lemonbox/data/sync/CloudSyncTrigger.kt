package com.goodyaoshi.lemonbox.data.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.room.invalidationTrackerFlow
import com.goodyaoshi.lemonbox.data.local.database.AppDatabase
import com.goodyaoshi.lemonbox.data.remote.RemoteSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多设备同步的**即时触发**层，补上「打开 App / 6 小时后台」之外的两个窗口。
 *
 * 为什么需要它：原先同步只有三处触发——冷启动、约 6 小时的周期任务、设置页手动。
 * 而国产 ROM 对 WorkManager 周期任务限制严格，`UNMETERED` 又要求 WiFi，
 * 结果就是「改了东西，对方几天都看不到」。本类把同步触发拉回到**用户在场的时刻**：
 *
 * 1. **回到前台**：App 从后台切回就同步一次。冷启动的首次 `onStart` 也在内，
 *    因此 [LemonApplication] 里原先那处单独的冷启动同步已由本类统一接管。
 * 2. **本地写入后**：任意同步表发生变化，等一小段静默（[WRITE_DEBOUNCE_MS]）再同步一次，
 *    把「改完立即推上去」变成常态，而不必等下一次前台切换或后台任务。
 *
 * 与周期性任务的关系：三者并存、互为兜底。未登录 / 未开启多设备同步时
 * [RemoteSyncManager.syncNow] 内部会直接返回，故这里不必再判开关。
 *
 * **不会自我循环**：同步自身写库（拉取合并）也会触发本层，但合并对「无变化的记录」
 * 不落库（见 `BackupMergeStrategy` 的 `isRemoteNewer` 判断），于是一轮空同步后不再产生
 * 新的表变化，触发自然收敛；叠加 [WRITE_DEBOUNCE_MS] 的静默窗口，最坏也只是多跑一次空同步。
 */
@Singleton
class CloudSyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val syncManager: RemoteSyncManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已 onStart 未 onStop 的 Activity 数；仅在主线程的生命周期回调里读写，无需加锁。 */
    private var startedActivities = 0

    /** 上次触发同步的墙钟时间，用于给「回到前台」去抖（见 [FOREGROUND_MIN_INTERVAL_MS]）。 */
    private val lastTriggerAt = AtomicLong(0)

    @Volatile
    private var started = false

    /** 幂等：冷启动只调用一次。 */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true

        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                // 计数从 0 变 1 才是「真正回到前台」——App 内部的 Activity 切换
                // （如进相机再回来）不会归零，故不会误触发。
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities == 0) requestSync(FOREGROUND_MIN_INTERVAL_MS)
                    startedActivities++
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )

        scope.launch {
            // invalidationTrackerFlow 来自 room-ktx：任意同步表变化时发射一次。
            // debounce 让「静默 [WRITE_DEBOUNCE_MS] 内没有新变化」才真正同步，
            // 使「连续录入多条」只在停手后推一次，而不是每条都推。
            // emitInitialState = false：订阅时不立刻发一次，只对真实的写操作响应。
            database.invalidationTrackerFlow(*SYNC_TABLES, emitInitialState = false)
                .debounce(WRITE_DEBOUNCE_MS)
                .collect { requestSync(minIntervalMs = 0) }
        }
    }

    /**
     * 请求一次同步。[minIntervalMs] 为距上次触发的最小间隔，用于抑制「频繁切出切回」
     * 造成的连发；写后触发靠 debounce 节流，传 0 即可。
     */
    private fun requestSync(minIntervalMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt.get() < minIntervalMs) return
        lastTriggerAt.set(now)
        scope.launch { syncManager.syncNow() }
    }

    private companion object {
        /**
         * 参与同步的本地表（与 `AppBackupManager.createBackupZip` 覆盖的实体一致）。
         * 任一变化即视为「有新东西要同步」。用表名而非实体类，是 `invalidationTrackerFlow` 的入参要求。
         */
        val SYNC_TABLES = arrayOf(
            "items",
            "categories",
            "locations",
            "reminders",
            "anniversaries",
            "ledger_records",
            "ledger_categories",
            "ledger_assets",
            "ledger_budgets",
            "ledger_templates",
            "ledger_recurring_rules",
            "time_capsules",
            "packing_trips",
            "packing_items"
        )

        /** 写入后的静默窗口：连续操作停手后再推，避免逐条上传。 */
        const val WRITE_DEBOUNCE_MS = 15_000L

        /** 回到前台的去抖间隔：短时间内反复切出切回只同步一次。 */
        const val FOREGROUND_MIN_INTERVAL_MS = 30_000L
    }
}
