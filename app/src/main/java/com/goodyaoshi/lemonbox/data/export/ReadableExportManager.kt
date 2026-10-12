package com.goodyaoshi.lemonbox.data.export

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.local.dao.AnniversaryDao
import com.goodyaoshi.lemonbox.data.local.dao.ItemDao
import com.goodyaoshi.lemonbox.data.local.dao.LedgerRecordDao
import com.goodyaoshi.lemonbox.data.local.entity.LedgerRecord
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.BrandCopy
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 人类可读导出（F12）：除 ZIP 备份外，另出一份可阅读、可打印（浏览器「打印 → 存为 PDF」）的
 * HTML 清单，覆盖家当、待买、临期、收支与纪念日，作为留念或打印。
 *
 * 与备份包互补：备份用于「原样搬走数据」，这份报告用于「给人看」。
 */
@Singleton
class ReadableExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemDao: ItemDao,
    private val ledgerRecordDao: LedgerRecordDao,
    private val anniversaryDao: AnniversaryDao,
    private val appPreferences: AppPreferences
) {

    /** 把可读报告写入用户选择的文件。 */
    suspend fun exportHtml(targetUri: Uri) {
        val html = buildHtml()
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
            out.write(html.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入所选文件")
    }

    private suspend fun buildHtml(): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nickname = appPreferences.nickname.value

        val items = itemDao.getActiveItemsSync()
        val toBuyNames = itemDao.getToBuyNames()
        val anniversaries = anniversaryDao.getActiveSnapshot().filter { it.enabled }

        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = today.withDayOfMonth(1).plusMonths(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val yearStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yearEnd = today.withDayOfYear(1).plusYears(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val monthExpense = sumExpense(monthStart, monthEnd)
        val monthIncome = sumIncome(monthStart, monthEnd)
        val yearExpense = sumExpense(yearStart, yearEnd)
        val yearIncome = sumIncome(yearStart, yearEnd)

        val totalValue = items.sumOf { (it.price ?: 0.0) * it.quantity }
        val expiring = items
            .mapNotNull { item ->
                val expire = item.expireTime ?: return@mapNotNull null
                item to DateUtil.daysUntil(expire)
            }
            .filter { it.second <= EXPIRING_WINDOW_DAYS }
            .sortedBy { it.second }

        return buildString {
            append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>${esc(BrandCopy.APP_NAME)} · 数据报告</title>")
            append(STYLE)
            append("</head><body>")
            append("<header><h1>${esc(BrandCopy.APP_NAME)} · 数据报告</h1>")
            append("<p class=\"muted\">${if (nickname.isBlank()) "" else "为 ${esc(nickname)} 整理 · "}生成于 ")
            append(today.format(REPORT_DATE)).append("</p></header>")

            append("<section><h2>家当总览</h2>")
            append("<p>在库物品 <strong>${items.size}</strong> 件")
            append(" · 估值约 <strong>¥${yuan(totalValue)}</strong></p></section>")

            append("<section><h2>待买清单</h2>")
            if (toBuyNames.isEmpty()) {
                append("<p class=\"muted\">暂无待买物品。</p>")
            } else {
                append("<ul>")
                toBuyNames.forEach { append("<li>${esc(it)}</li>") }
                append("</ul>")
            }
            append("</section>")

            append("<section><h2>临期提醒（${EXPIRING_WINDOW_DAYS} 天内）</h2>")
            if (expiring.isEmpty()) {
                append("<p class=\"muted\">近 ${EXPIRING_WINDOW_DAYS} 天内没有临期物品。</p>")
            } else {
                append("<table><thead><tr><th>物品</th><th>数量</th><th>状态</th></tr></thead><tbody>")
                expiring.forEach { (item, days) ->
                    val status = if (days < 0) "已过期 ${-days} 天" else "还有 $days 天"
                    append("<tr><td>${esc(item.name)}</td>")
                    append("<td>${item.quantity}${esc(item.unit)}</td>")
                    append("<td>${esc(status)}</td></tr>")
                }
                append("</tbody></table>")
            }
            append("</section>")

            append("<section><h2>记账</h2><table>")
            append("<thead><tr><th>周期</th><th>支出</th><th>收入</th></tr></thead><tbody>")
            append("<tr><td>本月</td><td>¥${yuanCents(monthExpense)}</td><td>¥${yuanCents(monthIncome)}</td></tr>")
            append("<tr><td>本年</td><td>¥${yuanCents(yearExpense)}</td><td>¥${yuanCents(yearIncome)}</td></tr>")
            append("</tbody></table></section>")

            append("<section><h2>纪念日</h2>")
            if (anniversaries.isEmpty()) {
                append("<p class=\"muted\">还没有纪念日。</p>")
            } else {
                append("<ul>")
                anniversaries.sortedBy { it.date }.forEach {
                    append("<li>${esc(it.name)} — ${esc(it.date)}</li>")
                }
                append("</ul>")
            }
            append("</section>")

            append("<footer class=\"muted\">由 ${esc(BrandCopy.APP_NAME)} 导出</footer>")
            append("</body></html>")
        }
    }

    private suspend fun sumExpense(start: Long, end: Long): Long =
        ledgerRecordDao.sumByTypeBetween(start, end)
            .firstOrNull { it.type == LedgerRecord.TYPE_EXPENSE }?.total ?: 0L

    private suspend fun sumIncome(start: Long, end: Long): Long =
        ledgerRecordDao.sumByTypeBetween(start, end)
            .firstOrNull { it.type == LedgerRecord.TYPE_INCOME }?.total ?: 0L

    private fun yuan(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

    private fun yuanCents(cents: Long): String = yuan(LedgerMath.centsToYuan(cents))

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        const val EXPIRING_WINDOW_DAYS = 30L
        val REPORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")

        val STYLE = """
            <style>
            body{font-family:-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
                 color:#3B2F23;background:#FCF7F0;margin:0;padding:32px;line-height:1.6}
            header{border-bottom:2px solid #F0E3D2;padding-bottom:12px;margin-bottom:20px}
            h1{font-size:22px;margin:0 0 4px;color:#B25A1E}
            h2{font-size:16px;margin:24px 0 8px;color:#B25A1E}
            section{margin-bottom:16px;background:#fff;border:1px solid #F0E3D2;border-radius:14px;padding:16px}
            table{width:100%;border-collapse:collapse;font-size:14px}
            th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #F0E3D2}
            th{color:#6B5844;font-weight:600}
            ul{margin:6px 0;padding-left:20px}
            .muted{color:#9A8B78;font-size:13px}
            footer{margin-top:24px;text-align:center}
            @media print{body{background:#fff}section{break-inside:avoid}}
            </style>
        """.trimIndent()
    }
}
