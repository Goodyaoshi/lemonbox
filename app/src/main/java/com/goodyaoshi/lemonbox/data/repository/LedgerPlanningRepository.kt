package com.goodyaoshi.lemonbox.data.repository

import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecurringRuleDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerTemplateDao
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecurringRule
import com.goodyaoshi.lemonbox.data.local.entity.LedgerTemplate
import com.goodyaoshi.lemonbox.util.MonotonicClock
import com.goodyaoshi.lemonbox.util.RecurringClock
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记账计划仓库（F05）：记账模板与周期记账规则的统一入口。
 *
 * 周期规则到点由 [fireDueRules] 生成账单；生成逻辑复用 [LedgerRepository.saveRecord]，
 * 从而自动补齐 syncId/updatedAt，保证与手动记账在下游（统计 / 同步 / 备份）完全一致。
 */
@Singleton
class LedgerPlanningRepository @Inject constructor(
    private val templateDao: LedgerTemplateDao,
    private val ruleDao: LedgerRecurringRuleDao,
    private val ledgerRepository: LedgerRepository
) {

    // ---------- 模板 ----------

    fun observeTemplates(): Flow<List<LedgerTemplate>> = templateDao.observeAll()

    /** 新增或更新模板；返回模板 id。 */
    suspend fun saveTemplate(template: LedgerTemplate): Long = if (template.id == 0L) {
        templateDao.insert(
            template.copy(
                syncId = template.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now(),
                dirty = true
            )
        )
    } else {
        templateDao.update(template.copy(updatedAt = MonotonicClock.now(), dirty = true))
        template.id
    }

    suspend fun deleteTemplate(id: Long) =
        templateDao.softDelete(id, MonotonicClock.now(), MonotonicClock.now())

    // ---------- 周期规则 ----------

    fun observeRules(): Flow<List<LedgerRecurringRule>> = ruleDao.observeAll()

    /** 新增或更新周期规则；返回规则 id。 */
    suspend fun saveRule(rule: LedgerRecurringRule): Long = if (rule.id == 0L) {
        ruleDao.insert(
            rule.copy(
                syncId = rule.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now(),
                dirty = true
            )
        )
    } else {
        ruleDao.update(rule.copy(updatedAt = MonotonicClock.now(), dirty = true))
        rule.id
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) {
        val rule = ruleDao.getById(id) ?: return
        ruleDao.update(rule.copy(enabled = enabled, updatedAt = MonotonicClock.now(), dirty = true))
    }

    suspend fun deleteRule(id: Long) =
        ruleDao.softDelete(id, MonotonicClock.now(), MonotonicClock.now())

    /** 物理清除超过保留期的软删墓碑（模板与周期规则共用同一套 30 天窗口）。 */
    suspend fun purgeDeletedOlderThan(cutoff: Long) {
        templateDao.purgeDeletedOlderThan(cutoff)
        ruleDao.purgeDeletedOlderThan(cutoff)
    }

    // ---------- 到点生成 ----------

    /**
     * 生成所有到点规则的账单，并把 [LedgerRecurringRule.nextFireAt] 推进到未来。
     * 若某规则错过了多个周期，会按发生时间补齐（上限 [MAX_CATCH_UP] 条，防跑飞）。
     * 返回本次生成的账单条数。
     */
    suspend fun fireDueRules(now: Long = System.currentTimeMillis()): Int {
        val due = ruleDao.getDueRules(now)
        if (due.isEmpty()) return 0
        var created = 0
        due.forEach { rule ->
            var fireAt = rule.nextFireAt
            var guard = 0
            var lastFired = rule.lastFiredAt
            while (fireAt <= now && guard < MAX_CATCH_UP) {
                // 确定性 syncId：规则同步到多台设备后，两边都会到点生成同一期账单。
                // 由「规则 + 触发时刻」派生同一个 syncId，两边的账单在合并时按 LWW
                // 落在同一条记录上，不会变成两份重复账单。
                val recordSyncId = recurringRecordSyncId(rule.syncId, rule.id, fireAt)
                // 该期已存在（本机先前生成过，或同步来的对方已生成）：不重复创建，
                // 但**仍要推进** lastFired/fireAt，否则这条规则会一直卡在同一期上。
                if (ledgerRepository.getRecordBySyncId(recordSyncId) == null) {
                    ledgerRepository.saveRecord(
                        LedgerRecord(
                            type = rule.type,
                            amount = rule.amount,
                            categoryId = rule.categoryId,
                            assetId = rule.assetId,
                            targetAssetId = rule.targetAssetId,
                            recordTime = fireAt,
                            remark = rule.remark.ifBlank { rule.name },
                            syncId = recordSyncId
                        )
                    )
                    created++
                }
                lastFired = fireAt
                guard++
                fireAt = RecurringClock.advance(fireAt, rule.unit, rule.repeatInterval)
            }
            // 豁免（P6 白名单）：这里推进的是本机调度游标（nextFireAt/lastFiredAt），
            // 属于设备本地演算副产品，不标脏，避免把「我这边跑到第几期」当成内容改动推给对端。
            ruleDao.update(
                rule.copy(
                    nextFireAt = fireAt,
                    lastFiredAt = lastFired,
                    updatedAt = MonotonicClock.now()
                )
            )
        }
        return created
    }

    // ---------- 备份 / 同步快照 ----------

    suspend fun getAllTemplatesSnapshot(): List<LedgerTemplate> = templateDao.getAllSnapshot()

    suspend fun getAllRulesSnapshot(): List<LedgerRecurringRule> = ruleDao.getAllSnapshot()

    // ---- 同步专用：原样落库（保留 syncId/updatedAt/墓碑），落地即标 `dirty = false`（P6）。 ----
    suspend fun insertSyncedTemplate(template: LedgerTemplate): Long =
        templateDao.insert(template.copy(dirty = false))

    suspend fun updateSyncedTemplate(template: LedgerTemplate) =
        templateDao.update(template.copy(dirty = false))

    suspend fun insertSyncedRule(rule: LedgerRecurringRule): Long =
        ruleDao.insert(rule.copy(dirty = false))

    suspend fun updateSyncedRule(rule: LedgerRecurringRule) =
        ruleDao.update(rule.copy(dirty = false))

    private companion object {
        /** 单条规则一次最多补生成的账单数，防止长期未打开应用时瞬间写入过多记录。 */
        const val MAX_CATCH_UP = 120
    }
}

/**
 * 周期生成账单的确定性 syncId：由「规则 syncId + 触发时刻」派生（F05）。
 *
 * 为什么必须确定性：周期规则现在会同步到多台设备，每台都会跑 [LedgerPlanningRepository.fireDueRules]。
 * 若各自生成随机 syncId，同一期账单在两台设备上是两条不同记录，同步后就是两份重复账单。
 * 用规则与触发时刻算出同一个 syncId，两边生成的账单在合并时按 LWW 落在同一条上，天然去重。
 *
 * [ruleSyncId] 为空（旧数据未能补齐）时回退到本地 ruleId：仍能保证本机重复运行不产生重复，
 * 只是跨设备去重能力受限。
 *
 * 纯函数，可直接单测（去重逻辑写错的表现是「账单悄悄翻倍」，从界面上看不出来）。
 */
internal fun recurringRecordSyncId(ruleSyncId: String?, ruleId: Long, fireAt: Long): String {
    val anchor = ruleSyncId?.takeIf { it.isNotBlank() } ?: "local-$ruleId"
    return "recurring:$anchor:$fireAt"
}
