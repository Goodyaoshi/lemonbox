package com.goodyaoshi.lemonbox.data.sync

import android.content.Context
import android.net.Uri
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.imagecloud.ListObjectsResult
import com.goodyaoshi.lemonbox.data.imagecloud.S3Config
import com.goodyaoshi.lemonbox.data.imagecloud.S3ImageClient
import com.goodyaoshi.lemonbox.data.imagecloud.S3ObjectEntry
import com.goodyaoshi.lemonbox.data.imagecloud.S3Outcome
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一次云同步的结果，供界面给出有内容的回执。 */
data class CloudSyncResult(
    val pushed: Int,
    val pulled: Int,
    val merged: Int,
    val message: String
)

/** 云同步的运行状态。 */
data class CloudSyncState(
    val running: Boolean = false,
    val lastError: String? = null,
    /** 上次成功同步的服务端时间；从未同步过为 null。 */
    val lastSyncedAt: Long? = null
)

/**
 * 多设备同步（云端）。
 *
 * 思路见 `docs/multi-device-sync-design.md`：**不需要服务器做原子裁决**，
 * 而是让每台设备推**不可变快照**（对象名含设备 ID + 随机串，永不重名），
 * 再各自列出并合并别人的快照。因为合并满足幂等与可交换，各设备最终收敛到同一状态。
 *
 * 与局域网同步的关系：并存。同一 WiFi 下局域网更快且不依赖外网；云同步则解决
 * 「不同网络」与「对方不在线」的场景，也是常态路径。
 *
 * 复用而非重写：导出走 [AppBackupManager.createBackupZip]，合并走
 * [AppBackupManager.importBackup]，水位线沿用 [AppPreferences] 的同伴机制
 * （同伴名固定为 [PEER_ID]）。这些逻辑早已为多设备一致设计，本类只负责搬运。
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ImageCloudConfig,
    private val client: S3ImageClient,
    private val backupManager: AppBackupManager,
    private val appPreferences: AppPreferences
) {

    private val _state = MutableStateFlow(
        CloudSyncState(lastSyncedAt = appPreferences.lastSyncAt.value)
    )
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    /**
     * 执行一次完整同步：先推自己的变更，再拉别人的。
     *
     * 顺序有讲究：**先推后拉**。若先拉后推，中间崩掉会出现「我已看到别人的数据、
     * 别人看不到我的」这种单向可见；先推则至多让本地多等一轮，不会让别人看不见我。
     *
     * 任何一步失败都不抛给调用方（后台任务不该因一次网络抖动而崩），
     * 错误进 [CloudSyncState.lastError] 由界面呈现。
     */
    suspend fun syncNow(): CloudSyncResult = withContext(Dispatchers.IO) {
        if (_state.value.running) return@withContext CloudSyncResult(0, 0, 0, "同步正在进行中")
        // 用 readiness（含 enabled）而不是只 resolve：用户在设置页关掉开关后，
        // 打开 App 的自动同步与手动「立即同步」都必须停下——关了开关数据还在往外传，
        // 与用户意愿相悖。resolve() 只覆盖「是否有配置」，不含开关。
        val readiness = config.readiness()
        val s3 = config.resolve()
        if (!readiness.ready || s3 == null) {
            val reason = readiness.firstBlocker ?: "云同步不可用"
            _state.value = _state.value.copy(lastError = reason)
            return@withContext CloudSyncResult(0, 0, 0, reason)
        }

        _state.value = _state.value.copy(running = true, lastError = null)
        try {
            val pushed = push(s3)
            val pulled = pull(s3)
            val now = System.currentTimeMillis()
            appPreferences.setLastSyncAt(now)
            _state.value = _state.value.copy(running = false, lastError = null, lastSyncedAt = now)
            CloudSyncResult(
                pushed = pushed,
                pulled = pulled.first,
                merged = pulled.second,
                message = buildMessage(pushed, pulled.first, pulled.second)
            )
        } catch (e: CancellationException) {
            // 取消要走原始路径：吞掉它会让协程在已取消后继续跑。
            _state.value = _state.value.copy(running = false)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "同步失败，请稍后重试"
            _state.value = _state.value.copy(running = false, lastError = message)
            CloudSyncResult(0, 0, 0, message)
        }
    }

    /**
     * 推送本机的增量变更。
     *
     * 无变更时**不上传**：否则每次打开 App 都会堆一个内容相同的对象。
     * 返回本次是否真的推了一个快照（1 / 0）。
     */
    private suspend fun push(s3: S3Config): Int {
        val since = appPreferences.pushWatermark(PEER_ID)
        val archive = backupManager.createBackupZip(since)
        try {
            if (archive.isEmpty) return 0
            val key = CloudSyncKeys.newRevisionKey(config.prefix(), appPreferences.deviceId)
            val payloadHash = ImageUtil.contentHash(archive.file) ?: return 0
            val outcome = client.putObject(
                config = s3,
                objectKey = key,
                payloadSha256 = payloadHash,
                file = archive.file,
                contentType = ZIP_CONTENT_TYPE
            )
            if (outcome !is S3Outcome.Success) {
                val reason = (outcome as? S3Outcome.Error)?.message ?: "上传快照失败"
                throw IllegalStateException(reason)
            }
            // 水位线推进到本次导出的时刻：这些记录已在快照里，下轮不必再传。
            appPreferences.setPushWatermark(PEER_ID, archive.exportedAt)
            return 1
        } finally {
            archive.file.delete()
        }
    }

    /**
     * 拉取并合并其他设备的快照。
     *
     * 返回 (下载份数, 合并新增/更新的条数)。
     */
    private suspend fun pull(s3: S3Config): Pair<Int, Int> {
        val listed = client.listObjects(s3, CloudSyncKeys.listPrefix(config.prefix()))
        val objects = when (listed) {
            is ListObjectsResult.Success -> listed.objects
            is ListObjectsResult.Failure -> throw IllegalStateException(listed.message)
        }

        val watermark = appPreferences.pullWatermark(PEER_ID) ?: 0L
        val fresh = selectFreshRevisions(objects, appPreferences.deviceId, watermark)

        var downloaded = 0
        var mergedCount = 0
        // 按服务端时间升序遍历，逐份处理。
        //
        // 顺序很重要：水位线的语义是「这个时间点之前的都已合并」，因此必须能判断
        // 「前面是否出现过失败」。升序处理 + 遇错即停，才能保证不跨过失败项。
        val processed = mutableListOf<Pair<Long, PullItemOutcome>>()
        for (entry in fresh) {
            // 放 cacheDir 而非系统临时目录：Android 上应用对系统 tmp 无写权限，
            // 且这里与项目其它临时文件（同步、导入）保持一致，便于统一清理。
            val local = File(
                context.cacheDir,
                "cloud-rev-${System.currentTimeMillis()}-${entry.key.hashCode()}.zip"
            )
            var outcome = PullItemOutcome.Failed
            try {
                outcome = when (client.getObject(s3, entry.key, local)) {
                    is S3Outcome.Success -> {
                        val result = backupManager.importBackup(sourceUri = Uri.fromFile(local))
                        downloaded++
                        mergedCount += result.itemAdded + result.itemUpdated
                        PullItemOutcome.Merged
                    }

                    // 对象已不在（被别的设备清理了）：它不会再回来，跨过它继续。
                    is S3Outcome.NotFound -> PullItemOutcome.Gone

                    // 其它错误（网络、权限、服务端）：停在这一份。
                    is S3Outcome.Error -> PullItemOutcome.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 合并本身出错（包损坏等）：同样视为失败，停在这里。
                outcome = PullItemOutcome.Failed
            } finally {
                local.delete()
            }
            processed += entry.lastModified to outcome
            if (outcome == PullItemOutcome.Failed) break
        }

        val newWatermark = advancePullWatermark(watermark, processed)
        if (newWatermark > watermark) appPreferences.setPullWatermark(PEER_ID, newWatermark)
        return downloaded to mergedCount
    }

    private fun resolveConfig(): S3Config? = config.resolve()

    /** 未就绪时给用户一句具体的原因，而不是笼统的「未配置」。 */
    private fun readinessReason(): String =
        config.readiness().firstBlocker ?: "云同步不可用"

    private fun buildMessage(pushed: Int, pulled: Int, merged: Int): String = when {
        pushed == 0 && pulled == 0 -> "已是最新"
        else -> buildString {
            append("同步完成")
            if (pushed > 0) append("，上传 1 份变更")
            if (pulled > 0) append("，合并 $pulled 份")
        }
    }

    private companion object {
        /**
         * 云同步在 [AppPreferences] 的水位线里用的「同伴」名。
         *
         * 复用同一套水位线机制（而非新加一套）：局域网同步是按同伴 deviceId 记的，
         * 云端只有一个共同位置，用一个固定名即可，两者互不干扰。
         */
        const val PEER_ID = "cloud"

        const val ZIP_CONTENT_TYPE = "application/zip"
    }
}

/**
 * 从列出的对象里挑出「本轮该拉取」的快照。
 *
 * 抽成顶层纯函数是因为它决定了「哪些数据会被看到」：
 * 漏掉一份不会报错、只是数据静默缺席，只有单测能提前发现。
 *
 * 三条规则：
 * - 只认本机制的快照（同前缀下将来可能还有别的用途）；
 * - 跳过自己的（数据本就在本地，拉回来只是白跑一趟网络）；
 * - 只取服务端时间晚于水位线的，并按服务端时间升序——**排序用服务端时间而非
 *   客户端时间**，因为后者可能偏移；而水位线比较必须与排序同源，否则会出现
 *   「按 A 排序、按 B 过滤」的错位。
 */
internal fun selectFreshRevisions(
    objects: List<S3ObjectEntry>,
    deviceId: String,
    watermark: Long
): List<S3ObjectEntry> =
    objects
        .filter { CloudSyncKeys.isRevisionKey(it.key) }
        .filterNot { CloudSyncKeys.isOwnRevisionKey(it.key, deviceId) }
        .filter { it.lastModified > watermark }
        .sortedBy { it.lastModified }

/** 单个远端快照在拉取时的处理结果。 */
internal enum class PullItemOutcome {
    /** 已成功下载并合并。 */
    Merged,

    /** 对象已不在存储上（被别的设备清理过）：不会再回来，可跨过。 */
    Gone,

    /** 下载或合并失败：必须停在它这里，否则它会被永久跳过。 */
    Failed
}

/**
 * 计算新的拉取水位线。
 *
 * 抽成顶层纯函数是因为这是「不丢数据」的唯一决策点：水位线的语义是
 * 「早于它的都已合并」，因此**一次失败之后的时间点一律不能采纳**——
 * 否则下次 `lastModified > watermark` 会把那份失败的数据永久排除，且毫无提示。
 *
 * [processed] 是 (服务端时间, 结果) 的升序列表，只应包含实际处理过的项。
 * 返回一个不大于「首个失败项之前最后成功项」的时间点。
 */
internal fun advancePullWatermark(
    current: Long,
    processed: List<Pair<Long, PullItemOutcome>>
): Long {
    var watermark = current
    for ((lastModified, outcome) in processed) {
        if (outcome == PullItemOutcome.Failed) break
        watermark = maxOf(watermark, lastModified)
    }
    return watermark
}
