package com.goodyaoshi.lemonbox.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTextParserTest {

    // ---------- parseLedger：金额 ----------

    @Test
    fun parseLedger_prefersKeywordLineAmountOverLargerElsewhere() {
        // 「应收」行金额优先，即使别处有更大的数字
        val text = """
            商品A 100.00
            应收 20.00
        """.trimIndent()

        val suggestion = OcrTextParser.parseLedger(text)

        assertEquals(2000L, suggestion.amountCents)
    }

    @Test
    fun parseLedger_fallsBackToMaxAmountWhenNoKeyword() {
        val text = """
            苹果 12.50
            香蕉 8.00
        """.trimIndent()

        val suggestion = OcrTextParser.parseLedger(text)

        assertEquals(1250L, suggestion.amountCents)
    }

    @Test
    fun parseLedger_ignoresOutOfRangeNumbers() {
        // 电话/长数字（超过 100 万元）被过滤，保留合理金额
        val text = """
            电话 13800138000
            合计 25.00
        """.trimIndent()

        val suggestion = OcrTextParser.parseLedger(text)

        assertEquals(2500L, suggestion.amountCents)
    }

    @Test
    fun parseLedger_parsesCommaSeparatedAmount() {
        val text = """
            订单
            合计 1,234.56
        """.trimIndent()

        val suggestion = OcrTextParser.parseLedger(text)

        assertEquals(123456L, suggestion.amountCents)
    }

    @Test
    fun parseLedger_emptyText_returnsNullAmountAndBlankRemark() {
        val suggestion = OcrTextParser.parseLedger("   \n  \n")

        assertNull(suggestion.amountCents)
        assertEquals("", suggestion.remark)
    }

    // ---------- parseLedger：备注 ----------

    @Test
    fun parseLedger_picksChineseHeaviestLineAsRemark() {
        val text = """
            2026-10-11 超市
            西红柿鸡蛋面 12.50
            合计 20.50
        """.trimIndent()

        val suggestion = OcrTextParser.parseLedger(text)

        // 纯数字占比过半的日期行被排除，汉字最多的一行胜出
        assertEquals("西红柿鸡蛋面 12.50", suggestion.remark)
    }

    @Test
    fun parseLedger_remarkExcludesKeywordLines() {
        val text = """
            火锅食材
            合计 88.00
        """.trimIndent()

        val suggestion = OcrTextParser.parseLedger(text)

        assertEquals("火锅食材", suggestion.remark)
    }

    @Test
    fun parseLedger_remarkTruncatedToMaxLength() {
        val longName = "柠".repeat(40)
        val text = "$longName\n合计 10.00"

        val suggestion = OcrTextParser.parseLedger(text)

        assertEquals(30, suggestion.remark.length)
    }

    // ---------- parseItem：品名 ----------

    @Test
    fun parseItem_returnsChineseName() {
        val text = """
            物品名称
            价格 15.00
        """.trimIndent()

        val suggestion = OcrTextParser.parseItem(text)

        assertEquals("物品名称", suggestion.name)
    }

    @Test
    fun parseItem_ignoresPureNumberLines() {
        val text = """
            1200
            牛奶
        """.trimIndent()

        val suggestion = OcrTextParser.parseItem(text)

        assertEquals("牛奶", suggestion.name)
    }

    @Test
    fun parseItem_emptyText_returnsBlankName() {
        val suggestion = OcrTextParser.parseItem("\n\n")

        assertEquals("", suggestion.name)
    }
}
