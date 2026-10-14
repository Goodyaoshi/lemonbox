package com.goodyaoshi.lemonbox.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图只在云端、本地已删」的槽位占位（F20）。
 *
 * 为什么需要它：图片路径与对象键必须按下标一一对应，而 `encodeImagePaths` 会过滤空项——
 * 本地文件删掉后槽位若留空，它后面的键就会整体前移、安到别的图上，界面会显示错的图片。
 * 用一个非空且唯一的哨兵路径占住位置即可：读取侧的「本地不可用则回退云端」逻辑
 * 对哨兵路径天然成立（`File("cloud:...").isFile` 恒为 false）。
 *
 * 同时锁住 `imageObjectKeyList()` 的回退：即使键列没写，也能从占位路径里取回键。
 */
class CloudSlotTest {

    @Test
    fun pathRoundTripKeepsObjectKey() {
        val key = "images/abc123.jpg"
        val path = Item.cloudSlotPath(key)

        assertTrue(Item.isCloudSlot(path))
        assertEquals(key, Item.cloudSlotObjectKey(path))
    }

    @Test
    fun prefixIsStableAndNonEmpty() {
        // 前缀本身必须非空，否则占位会退化成空串、被 encodeImagePaths 过滤掉。
        assertTrue(Item.CLOUD_SLOT_PREFIX.isNotBlank())
        assertFalse(Item.CLOUD_SLOT_PREFIX.contains('\n'))
    }

    @Test
    fun realPathIsNotMistakenForCloudSlot() {
        val real = "/data/user/0/com.goodyaoshi.lemonbox/files/images/item_1.jpg"

        assertFalse(Item.isCloudSlot(real))
        assertNull(Item.cloudSlotObjectKey(real))
    }

    @Test
    fun emptyOrNullPathIsNotCloudSlot() {
        assertFalse(Item.isCloudSlot(""))
        assertNull(Item.cloudSlotObjectKey(""))
    }

    @Test
    fun prefixWithoutKeyYieldsNullObjectKey() {
        // 只有前缀、后面没东西时不该当成有效键。
        assertNull(Item.cloudSlotObjectKey(Item.CLOUD_SLOT_PREFIX))
    }

    @Test
    fun pathListKeepsSlotSoIndicesStayAligned() {
        // 关键回归：占位路径非空，encode/decode 之后必须仍在原位，不能被过滤掉。
        val paths = listOf("/local/a.jpg", Item.cloudSlotPath("images/b.jpg"), "/local/c.jpg")
        val item = Item(id = 1L, name = "x").withImages(
            paths = paths,
            objectKeys = listOf("k1", "k2", "k3")
        )

        assertEquals(paths.size, item.imagePathList().size)
        assertEquals(paths, item.imagePathList())
        assertEquals(listOf("k1", "k2", "k3"), item.imageObjectKeyList())
    }

    @Test
    fun objectKeysFallBackToSlotPathWhenKeyColumnIsBlank() {
        // 键列缺失时（例如某条链路只写了占位路径），仍要能取回云端引用。
        val key = "images/only-in-cloud.jpg"
        val item = Item(id = 1L, name = "x", imagePaths = Item.cloudSlotPath(key))

        assertEquals(listOf(key), item.imageObjectKeyList())
        assertEquals(key, item.primaryImageObjectKey())
    }

    @Test
    fun keyColumnWinsOverSlotPathWhenBothPresent() {
        val item = Item(
            id = 1L,
            name = "x",
            imagePaths = Item.cloudSlotPath("images/from-path.jpg"),
            imageObjectKeys = "images/from-column.jpg"
        )

        assertEquals(listOf("images/from-column.jpg"), item.imageObjectKeyList())
    }
}
