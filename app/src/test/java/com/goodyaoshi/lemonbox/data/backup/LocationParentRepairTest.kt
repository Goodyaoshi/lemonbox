package com.goodyaoshi.lemonbox.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 父级结构自愈的判定规则。
 *
 * 这里锁住的是「远端把某条从顶级移到父级下」这一次视图的边界：只修顶级/父级已删的行，
 * 不碰有本机待推送改动的行，也不碰受保护位置与已删除的行。
 */
class LocationParentRepairTest {

    @Test
    fun `顶级位置在远端有活的父级时被挂回去`() {
        assertTrue(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = false,
                localDirty = false,
                localParentId = null,
                localParentDeleted = false,
                remoteDeletedAt = null,
                resolvedParentId = 12L
            )
        )
    }

    @Test
    fun `父级已被删除时也重新挂到新父级`() {
        assertTrue(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = false,
                localDirty = false,
                localParentId = 99L,
                localParentDeleted = true,
                remoteDeletedAt = null,
                resolvedParentId = 12L
            )
        )
    }

    @Test
    fun `已经挂好的层级不被改写`() {
        assertFalse(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = false,
                localDirty = false,
                localParentId = 7L,
                localParentDeleted = false,
                remoteDeletedAt = null,
                resolvedParentId = 12L
            )
        )
    }

    @Test
    fun `本机有待推送改动时不覆盖`() {
        assertFalse(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = false,
                localDirty = true,
                localParentId = null,
                localParentDeleted = false,
                remoteDeletedAt = null,
                resolvedParentId = 12L
            )
        )
    }

    @Test
    fun `受保护位置与墓碑不参与`() {
        assertFalse(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = true,
                localDirty = false,
                localParentId = null,
                localParentDeleted = false,
                remoteDeletedAt = null,
                resolvedParentId = 12L
            )
        )
        assertFalse(
            shouldRelinkRootLocation(
                localDeletedAt = 1L,
                localProtected = false,
                localDirty = false,
                localParentId = null,
                localParentDeleted = false,
                remoteDeletedAt = null,
                resolvedParentId = 12L
            )
        )
        assertFalse(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = false,
                localDirty = false,
                localParentId = null,
                localParentDeleted = false,
                remoteDeletedAt = 1L,
                resolvedParentId = 12L
            )
        )
    }

    @Test
    fun `远端解析不到父级时不动`() {
        assertFalse(
            shouldRelinkRootLocation(
                localDeletedAt = null,
                localProtected = false,
                localDirty = false,
                localParentId = null,
                localParentDeleted = false,
                remoteDeletedAt = null,
                resolvedParentId = null
            )
        )
    }
}
