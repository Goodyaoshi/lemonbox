package com.goodyaoshi.lemonbox.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goodyaoshi.lemonbox.data.repository.LedgerPlanningRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 周期记账调度（F05）：定时把到点的周期规则生成账单。
 *
 * 记录时间取规则自身的 [com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule.nextFireAt]，
 * 因此即便任务因系统限制延后执行，生成的账单日期仍然准确。
 */
@HiltWorker
class RecurringLedgerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val planningRepository: LedgerPlanningRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        planningRepository.fireDueRules()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ledger_recurring"

        /** 应用启动时调用：每 6 小时检查一次是否有到点的周期规则。 */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecurringLedgerWorker>(6, TimeUnit.HOURS).build()
            )
        }
    }
}
