package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ListObjectsV2 响应解析（多设备同步的入站入口）。
 *
 * 为什么要单测：这段逻辑的输入完全来自服务端，一旦解析错（键被截断、
 * 时间戳读成 0、分页 token 漏掉），同步会表现为「拉不到东西」或「重复合并」，
 * 而在真机上很难分辨是哪一种。用响应样本锁住最省事。
 */
class ListObjectsParseTest {

    /** 一段贴近真实 Filebase/R2 的响应：两个对象 + 未截断。 */
    private val twoObjects = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
            <Name>lemonbox</Name>
            <Prefix>sync/rev-</Prefix>
            <KeyCount>2</KeyCount>
            <MaxKeys>1000</MaxKeys>
            <IsTruncated>false</IsTruncated>
            <Contents>
                <Key>sync/rev-devA-1.json</Key>
                <LastModified>2026-10-14T12:00:00.000Z</LastModified>
                <ETag>&quot;abc&quot;</ETag>
                <Size>2048</Size>
            </Contents>
            <Contents>
                <Key>sync/rev-devB-2.json</Key>
                <LastModified>2026-10-14T13:30:00.000Z</LastModified>
                <ETag>&quot;def&quot;</ETag>
                <Size>4096</Size>
            </Contents>
        </ListBucketResult>
    """.trimIndent()

    @Test
    fun parse_extractsAllEntries() {
        val page = parseListObjectsXml(twoObjects)

        assertEquals(2, page.entries.size)
        assertEquals("sync/rev-devA-1.json", page.entries[0].key)
        assertEquals("sync/rev-devB-2.json", page.entries[1].key)
        assertEquals(2048L, page.entries[0].size)
        assertEquals(4096L, page.entries[1].size)
    }

    @Test
    fun parse_convertsLastModifiedToEpochMillis() {
        val page = parseListObjectsXml(twoObjects)

        // 2026-10-14T12:00:00Z
        assertEquals(1_791_979_200_000L, page.entries[0].lastModified)
    }

    @Test
    fun parse_reportsNotTruncatedWithoutToken() {
        val page = parseListObjectsXml(twoObjects)

        assertFalse(page.isTruncated)
        assertNull(page.nextContinuationToken)
    }

    @Test
    fun parse_truncatedResponseKeepsContinuationToken() {
        val xml = """
            <ListBucketResult>
                <IsTruncated>true</IsTruncated>
                <NextContinuationToken>TOKEN-abc123</NextContinuationToken>
                <Contents>
                    <Key>sync/rev-devA-1.json</Key>
                    <LastModified>2026-10-14T12:00:00.000Z</LastModified>
                    <Size>10</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertTrue(page.isTruncated)
        assertEquals("TOKEN-abc123", page.nextContinuationToken)
        assertEquals(1, page.entries.size)
    }

    @Test
    fun parse_emptyBucketReturnsNoEntries() {
        val xml = """
            <ListBucketResult>
                <KeyCount>0</KeyCount>
                <IsTruncated>false</IsTruncated>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertTrue(page.entries.isEmpty())
        assertFalse(page.isTruncated)
    }

    @Test
    fun parse_unescapesXmlEntitiesInKey() {
        // 对象键里的 & 会被服务端写成 &amp;；不还原就会拿到错误的键、下载 404。
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <Key>sync/rev-dev&amp;A.json</Key>
                    <LastModified>2026-10-14T12:00:00.000Z</LastModified>
                    <Size>1</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertEquals("sync/rev-dev&A.json", page.entries[0].key)
    }

    @Test
    fun parse_missingLastModifiedFallsBackToZero() {
        // 时间戳读不出来时给 0，而不是崩：调用方按「不早于水位线」过滤即可跳过它。
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <Key>sync/rev-devA-1.json</Key>
                    <Size>1</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertEquals(1, page.entries.size)
        assertEquals(0L, page.entries[0].lastModified)
    }

    @Test
    fun parse_ignoresContentsWithoutKey() {
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <LastModified>2026-10-14T12:00:00.000Z</LastModified>
                    <Size>1</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun parse_handlesCommonPrefixesAndDelimiterFields() {
        // 带 CommonPrefixes 的响应里不能把前缀误当成对象。
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <CommonPrefixes><Prefix>sync/base-</Prefix></CommonPrefixes>
                <Contents>
                    <Key>sync/rev-devA-1.json</Key>
                    <LastModified>2026-10-14T12:00:00.000Z</LastModified>
                    <Size>7</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertEquals(1, page.entries.size)
        assertEquals("sync/rev-devA-1.json", page.entries[0].key)
    }

    @Test
    fun parse_emptyTokenNormalizesToNull() {
        // 有些实现即使未截断也会回一个空 token；归一成 null 才不会让翻页死循环。
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <NextContinuationToken></NextContinuationToken>
            </ListBucketResult>
        """.trimIndent()

        val page = parseListObjectsXml(xml)

        assertNull(page.nextContinuationToken)
    }
}
