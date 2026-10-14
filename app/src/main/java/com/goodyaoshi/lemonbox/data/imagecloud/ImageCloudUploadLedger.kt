package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已确认存在于云端的对象键账本（F20）。
 *
 * 持久化的必要性：进程内缓存重启即归零，会误报「全部未上传」，也让补传重新探一遍整库。
 * 存独立 SharedPreferences 且不参与备份——它是本机对云端状态的观察，不属于用户数据。
 */
@Singleton
class ImageCloudUploadLedger @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读出全部已确认键。
     *
     * 必须拷贝一份：`getStringSet` 返回的是内部实例，直接持有并修改会触发
     * `IllegalStateException`（SharedPreferences 要求调用方自行复制）。
     */
    fun keys(): Set<String> =
        preferences.getStringSet(KEY_UPLOADED, null)?.toSet() ?: emptySet()

    /** 批量登记为「已确认」；已存在的自动去重，全部重复则不写盘。 */
    fun addAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val merged = HashSet(keys())
        if (!merged.addAll(keys)) return
        preferences.edit().putStringSet(KEY_UPLOADED, merged).apply()
    }

    /** 清空账本（换了存储地址或凭据后，旧键不再适用）。 */
    fun clear() {
        preferences.edit().remove(KEY_UPLOADED).apply()
    }

    /** 已确认的张数，供界面展示。 */
    fun count(): Int = preferences.getStringSet(KEY_UPLOADED, null)?.size ?: 0

    private companion object {
        const val PREFS_NAME = "lemon_image_cloud_ledger"
        const val KEY_UPLOADED = "uploaded_object_keys"
    }
}
