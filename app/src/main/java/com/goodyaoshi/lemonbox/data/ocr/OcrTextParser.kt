package com.goodyaoshi.lemonbox.data.ocr

import com.goodyaoshi.lemonbox.util.LedgerMath

/** 记账建议：金额（分，可为空）与备注。 */
data class LedgerSuggestion(val amountCents: Long?, val remark: String)

/** 入库建议：品名。 */
data class ItemSuggestion(val name: String)

/**
 * 把 OCR 原始文本抽取为记账/入库建议（F02/F03）。
 *
 * 纯逻辑、无副作用，便于单元测试。针对收据/小票场景做启发式：
 * 金额优先取「合计/总计/应收…」等关键词行中的最大值，退回全文最大值；
 * 品名/备注取汉字最多的一行。
 */
object OcrTextParser {

    /** 金额关键词：命中这些词的行优先级更高。 */
    private val AMOUNT_KEYWORDS = listOf(
        "合计", "总计", "总额", "共计", "总价", "小计",
        "应收", "实付", "实收", "应付", "金额"
    )

    /** 金额：支持千分位与最多两位小数。 */
    private val AMOUNT_REGEX = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

    /** 合理的金额区间（分）：1 分 ~ 100 万元，过滤日期、电话等噪声数字。 */
    private const val MIN_AMOUNT_CENTS = 1L
    private const val MAX_AMOUNT_CENTS = 100_000_000L

    private const val MAX_REMARK_LENGTH = 30

    /** 从识别文本中抽取记账建议。 */
    fun parseLedger(text: String): LedgerSuggestion {
        val lines = splitLines(text)
        if (lines.isEmpty()) return LedgerSuggestion(amountCents = null, remark = "")

        val keywordAmounts = ArrayList<Long>()
        for (line in lines) {
            if (AMOUNT_KEYWORDS.any { line.contains(it) }) {
                keywordAmounts.addAll(extractAmounts(line))
            }
        }
        val amountCents = keywordAmounts.maxOrNull()
            ?: lines.flatMap { extractAmounts(it) }.maxOrNull()

        return LedgerSuggestion(amountCents = amountCents, remark = pickRemark(lines))
    }

    /** 从识别文本中抽取入库品名建议。 */
    fun parseItem(text: String): ItemSuggestion {
        return ItemSuggestion(name = pickRemark(splitLines(text)))
    }

    private fun splitLines(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** 抽取一行中的所有金额（分）。 */
    private fun extractAmounts(line: String): List<Long> =
        AMOUNT_REGEX.findAll(line).mapNotNull { match ->
            val value = match.value.replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            val cents = LedgerMath.yuanToCents(value)
            if (cents in MIN_AMOUNT_CENTS..MAX_AMOUNT_CENTS) cents else null
        }.toList()

    /** 取「最像名称」的一行：排除金额关键词行与数字过半的行，汉字最多者优先。 */
    private fun pickRemark(lines: List<String>): String =
        lines
            .filter { line -> AMOUNT_KEYWORDS.none { line.contains(it) } }
            .filter { line -> line.count { it in '0'..'9' } <= line.length / 2 }
            .filter { line -> line.count { it in '\u4e00'..'\u9fff' } >= 2 }
            .maxByOrNull { line -> line.count { it in '\u4e00'..'\u9fff' } }
            ?.take(MAX_REMARK_LENGTH)
            .orEmpty()
}
