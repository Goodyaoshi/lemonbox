package com.goodyaoshi.lemonbox.testing

import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * 纯 JVM 内存版 [SharedPreferences]：让单测无需 Robolectric 就能构造 [com.goodyaoshi.lemonbox.data.settings.AppPreferences]。
 * 只实现偏好读写所需的最小语义，监听器为无操作。
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, Any?> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    /** 攒到 apply/commit 时再一次性落盘，贴近真实 SharedPreferences 的批处理语义。 */
    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value; removals -= key }

        override fun putStringSet(
            key: String,
            value: MutableSet<String>?
        ): SharedPreferences.Editor = apply { pending[key] = value; removals -= key }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value; removals -= key }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value; removals -= key }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value; removals -= key }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value; removals -= key }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals += key; pending -= key }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearRequested) {
                values.clear()
                clearRequested = false
            }
            removals.forEach { values.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
            removals.clear()
        }
    }
}

/**
 * 只覆写 [getSharedPreferences] 的极简 [ContextWrapper]（base 传 null）：
 * AppPreferences 的构造只用到这一个方法，故足以在单测里安全实例化偏好层。
 */
class FakeContext : ContextWrapper(null) {
    private val prefs = FakeSharedPreferences()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}
