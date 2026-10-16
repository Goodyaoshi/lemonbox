package com.goodyaoshi.lemonbox.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditConflictTest {

    @Test
    fun stale_whenUpdatedAtChanged() {
        assertTrue(isStaleEdit(loadedUpdatedAt = 100L, currentUpdatedAt = 200L))
    }

    @Test
    fun notStale_whenUnchanged() {
        assertFalse(isStaleEdit(loadedUpdatedAt = 100L, currentUpdatedAt = 100L))
    }

    @Test
    fun notStale_whenLoadedMissing() {
        assertFalse(isStaleEdit(loadedUpdatedAt = null, currentUpdatedAt = 200L))
    }

    @Test
    fun notStale_whenCurrentMissing() {
        assertFalse(isStaleEdit(loadedUpdatedAt = 100L, currentUpdatedAt = null))
    }

    @Test
    fun notStale_whenBothMissing() {
        assertFalse(isStaleEdit(loadedUpdatedAt = null, currentUpdatedAt = null))
    }
}
