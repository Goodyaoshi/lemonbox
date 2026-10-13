package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已确认存在于云端的对象键账本（F20）。
 *
 * 为什么需要持久化这份集合：图片要能「先删本地、后按需取回」，就必须能离线回答
 * 「这张图到底传上去没有」。仅靠 [ImageCloudSyncManager] 那份进程内缓存，
 * 重装 / 重启后一切归零，界面会误报「全部未上传」，后台补传也会把整库重探一遍。
 *
 * 记录的是**对象键**（内容哈希推导而来），因此：
 * - 键天然与内容绑定，图片内容变了下标自然失效，不会出现「记着传过、其实传的是旧图」；
 * - 换设备重新同步时，本机账本为空，但云端已有对象——探在命中后会补记进来。
 *
 * 存独立 SharedPreferences，不参与备份 / 导出：它是本机对云端状态的观察，不属于用户数据。
 *
 * 写入用 `apply()` 异步落盘：这是「已经成功上传」的回执，极端情况下丢了最多重传一次
 * （对象键幂等，无副作用），不值得为它阻塞同步主流程。
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
     * 注意必须拷贝一份：`getStringSet` 返回的对象在部分实现里是内部实例，
     * 直接持有并修改会触发 `IllegalStateException`（SharedPreferences 明确要求调用方自行复制）。
     */
    fun keys(): Set<String> =
        preferences.getStringSet(KEY_UPLOADED, null)?.toSet() ?: emptySet()

    /** 批量登记为「已确认」；已存在的会自动去重。 */
    fun addAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val merged = HashSet(keys())
        if (!merged.addAll(keys)) return
        preferences.edit().putStringSet(KEY_UPLOADED, merged).apply()
    }

    /** 清空账本（例如用户换了存储地址 / 凭据，旧键不再适用）。 */
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
