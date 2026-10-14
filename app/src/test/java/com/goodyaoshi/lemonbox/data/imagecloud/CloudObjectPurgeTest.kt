package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些云端对象可以安全删除」的判断（F20）。
 *
 * 这是防误删的唯一一道闸门：对象键由图片**内容哈希**推导，同一张图可能被多件物品、
 * 多个槽位共用（例如同一张照片同时挂在两件家当上）。删错的表现是
 * 「某个物品的图凭空消失」，而且不可逆——因此把判断本身单独锁住。
 *
 * 判断是纯函数，不依赖 Android 框架，可直接在 JVM 跑。
 */
class CloudObjectPurgeTest {

    @Test
    fun dropsKeysStillReferenced() {
        // 关键场景：这张图还被另一件物品（或回收站里待恢复的物品）引用着，绝不能删。
        val result = purgeableCloudKeys(
            candidates = listOf("images/a.jpg", "images/b.jpg"),
            stillReferenced = setOf("images/a.jpg")
        )

        assertEquals(listOf("images/b.jpg"), result)
    }

    @Test
    fun keepsNothingWhenEverythingIsStillReferenced() {
        // 同一张图挂在多件物品上，删掉其中一件不该影响这张图。
        val result = purgeableCloudKeys(
            candidates = listOf("images/shared.jpg", "images/shared.jpg"),
            stillReferenced = setOf("images/shared.jpg")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun deletesWhenNothingReferencesIt() {
        val result = purgeableCloudKeys(
            candidates = listOf("images/orphan.jpg"),
            stillReferenced = emptySet()
        )

        assertEquals(listOf("images/orphan.jpg"), result)
    }

    @Test
    fun blanksAreDroppedSoNoEmptyKeyIsRequested() {
        // 物品有图但未上传时对象键是空串占位，不能拿它去发 DELETE 请求。
        val result = purgeableCloudKeys(
            candidates = listOf("", "  ", "images/a.jpg"),
            stillReferenced = emptySet()
        )

        assertEquals(listOf("images/a.jpg"), result)
    }

    @Test
    fun duplicatesAreCollapsed() {
        // 同一张图被多个槽位引用时，候选里会出现重复键——只应发一次删除。
        val result = purgeableCloudKeys(
            candidates = listOf("images/a.jpg", "images/a.jpg", "images/a.jpg"),
            stillReferenced = emptySet()
        )

        assertEquals(listOf("images/a.jpg"), result)
    }

    @Test
    fun emptyCandidatesYieldNothing() {
        assertTrue(purgeableCloudKeys(emptyList(), setOf("images/a.jpg")).isEmpty())
    }

    @Test
    fun preservesCandidateOrderForDelete() {
        val result = purgeableCloudKeys(
            candidates = listOf("images/c.jpg", "images/a.jpg", "images/b.jpg"),
            stillReferenced = setOf("images/a.jpg")
        )

        assertEquals(listOf("images/c.jpg", "images/b.jpg"), result)
    }

    @Test
    fun referencedBlanksDoNotBlockRealKeys() {
        // 参照集合里的空串是「未上传」占位，不应影响真实键的判定。
        val result = purgeableCloudKeys(
            candidates = listOf("images/a.jpg"),
            stillReferenced = setOf("", "  ")
        )

        assertEquals(listOf("images/a.jpg"), result)
    }
}
