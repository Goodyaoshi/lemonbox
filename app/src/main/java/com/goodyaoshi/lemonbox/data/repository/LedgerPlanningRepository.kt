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

    suspend fun getTemplate(id: Long): LedgerTemplate? = templateDao.getById(id)

    /** 新增或更新模板；返回模板 id。 */
    suspend fun saveTemplate(template: LedgerTemplate): Long = if (template.id == 0L) {
        templateDao.insert(
            template.copy(
                syncId = template.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now()
            )
        )
    } else {
        templateDao.update(template.copy(updatedAt = MonotonicClock.now()))
        template.id
    }

    suspend fun deleteTemplate(id: Long) =
        templateDao.softDelete(id, MonotonicClock.now(), MonotonicClock.now())

    // ---------- 周期规则 ----------

    fun observeRules(): Flow<List<LedgerRecurringRule>> = ruleDao.observeAll()

    suspend fun getRule(id: Long): LedgerRecurringRule? = ruleDao.getById(id)

    /** 新增或更新周期规则；返回规则 id。 */
    suspend fun saveRule(rule: LedgerRecurringRule): Long = if (rule.id == 0L) {
        ruleDao.insert(
            rule.copy(
                syncId = rule.syncId ?: UUID.randomUUID().toString(),
                updatedAt = MonotonicClock.now()
            )
        )
    } else {
        ruleDao.update(rule.copy(updatedAt = MonotonicClock.now()))
        rule.id
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) {
        val rule = ruleDao.getById(id) ?: return
        ruleDao.update(rule.copy(enabled = enabled, updatedAt = MonotonicClock.now()))
    }

    suspend fun deleteRule(id: Long) =
        ruleDao.softDelete(id, MonotonicClock.now(), MonotonicClock.now())

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
                ledgerRepository.saveRecord(
                    LedgerRecord(
                        type = rule.type,
                        amount = rule.amount,
                        categoryId = rule.categoryId,
                        assetId = rule.assetId,
                        targetAssetId = rule.targetAssetId,
                        recordTime = fireAt,
                        remark = rule.remark.ifBlank { rule.name }
                    )
                )
                lastFired = fireAt
                created++
                guard++
                fireAt = RecurringClock.advance(fireAt, rule.unit, rule.repeatInterval)
            }
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

    suspend fun insertSyncedTemplate(template: LedgerTemplate): Long = templateDao.insert(template)

    suspend fun updateSyncedTemplate(template: LedgerTemplate) = templateDao.update(template)

    suspend fun insertSyncedRule(rule: LedgerRecurringRule): Long = ruleDao.insert(rule)

    suspend fun updateSyncedRule(rule: LedgerRecurringRule) = ruleDao.update(rule)

    private companion object {
        /** 单条规则一次最多补生成的账单数，防止长期未打开应用时瞬间写入过多记录。 */
        const val MAX_CATCH_UP = 120
    }
}
