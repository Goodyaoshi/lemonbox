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
    val message: String,
    /** 本次合并中「双方都改过」的真冲突条数（LWW 静默取舍的数量）。 */
    val conflicts: Int = 0
)

/** 云同步的运行状态。 */
data class CloudSyncState(
    val running: Boolean = false,
    val lastError: String? = null,
    /** 上次成功同步的服务端时间；从未同步过为 null。 */
    val lastSyncedAt: Long? = null,
    /** 上次同步遇到的冲突条数；用于在界面提示「有内容被较新的一方覆盖」。 */
    val lastConflicts: Int = 0
)

/**
 * 多设备同步（云端）。
 *
 * **不需要服务器做原子裁决**：而是让每台设备推**不可变快照**
 * （对象名含设备 ID + 随机串，永不重名），再各自列出并合并别人的快照。
 * 因为合并满足幂等与可交换，各设备最终收敛到同一状态。
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
        CloudSyncState(
            lastSyncedAt = appPreferences.lastSyncAt.value,
            // 冲突数从持久化读回：后台同步（约 6 小时一次）产生的冲突，
            // 用户往往不当场打开设置页，不落盘就永远看不到。
            lastConflicts = appPreferences.cloudLastConflicts()
        )
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
        // 用云同步专用的就绪判断，**不看图片上传开关**：同步的是文字数据（物品、记账等），
        // 与「要不要把照片传上去」是两回事。早先共用同一开关时，关掉图片上传会把
        // 文字同步一并停掉——用户只想省空间，却连数据都不再同步了。
        val blocker = config.cloudSyncBlocker()
        val s3 = config.resolve()
        if (blocker != null || s3 == null) {
            val reason = blocker ?: "云同步不可用"
            _state.value = _state.value.copy(lastError = reason)
            return@withContext CloudSyncResult(0, 0, 0, reason)
        }

        _state.value = _state.value.copy(running = true, lastError = null)
        try {
            val pushed = push(s3)
            val pulled = pull(s3)
            maybeWriteBase(s3, pushed)
            // 清理放在最后：前两步都成功才收拾旧对象，否则刚推的可能还没被别的设备拉走。
            // 清理失败不影响本次结果（对象多留一轮而已），故单独兜住异常。
            runCatching { cleanup(s3) }
            val now = System.currentTimeMillis()
            appPreferences.setLastSyncAt(now)
            appPreferences.setCloudLastConflicts(pulled.conflicts)
            _state.value = _state.value.copy(
                running = false,
                lastError = null,
                lastSyncedAt = now,
                lastConflicts = pulled.conflicts
            )
            CloudSyncResult(
                pushed = pushed,
                pulled = pulled.downloaded,
                merged = pulled.merged,
                conflicts = pulled.conflicts,
                message = cloudSyncMessage(pushed, pulled.downloaded, pulled.conflicts)
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
            // 记一笔「自上次 base 以来攒了多少增量」，供 maybeWriteBase 决定何时刷新全量。
            appPreferences.setCloudPushesSinceBase(appPreferences.cloudPushesSinceBase() + 1)
            return 1
        } finally {
            archive.file.delete()
        }
    }

    /**
     * 按策略写一份全量 checkpoint（base）。
     *
     * 为什么要 base：新设备首次加入时 `pullWatermark` 为空，没有 base 就得从第一条 rev
     * 开始重放整部历史。有了一份全量快照，新设备先拉它、再拉其后的增量即可。
     *
     * 触发条件（满足其一）：
     * - 从未写过 base：新设备随时可能加入，先备一份；
     * - 自上次 base 以来已推送 [BASE_EVERY_PUSHES] 次增量：攒得够多，值得刷新；
     * - 距上次 base 已超 [BASE_MAX_AGE_MS]：久未刷新，避免 base 与现状差太远。
     *
     * 无变更（[pushed] 为 0）时不写：base 与 rev 不同，它是全量，白写一份纯属浪费流量。
     * 上传失败不抛异常——base 是**优化**而非正确性依赖，没有它只是新设备首次同步慢一点。
     */
    private suspend fun maybeWriteBase(s3: S3Config, pushed: Int) {
        val now = System.currentTimeMillis()
        val lastBaseAt = appPreferences.cloudBaseAt()
        val due = lastBaseAt == null ||
            appPreferences.cloudPushesSinceBase() >= BASE_EVERY_PUSHES ||
            now - lastBaseAt >= BASE_MAX_AGE_MS
        if (!due) return
        // 没有新变更也允许在「从未写过 base」时补一份：首次加入的优化值得，
        // 且此时并非每次同步都触发（写完就记下时间与计数）。
        if (pushed == 0 && lastBaseAt != null) return

        val archive = backupManager.createBackupZip(since = null)
        try {
            val key = CloudSyncKeys.newBaseKey(config.prefix(), appPreferences.deviceId)
            val payloadHash = ImageUtil.contentHash(archive.file) ?: return
            val outcome = client.putObject(
                config = s3,
                objectKey = key,
                payloadSha256 = payloadHash,
                file = archive.file,
                contentType = ZIP_CONTENT_TYPE
            )
            if (outcome is S3Outcome.Success) {
                appPreferences.setCloudBaseAt(now)
                appPreferences.setCloudPushesSinceBase(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // base 写失败不影响本次同步；下次再试。
        } finally {
            archive.file.delete()
        }
    }

    /**
     * 拉取并合并其他设备的快照。
     *
     * 首次加入（`pullWatermark` 为空）时**先拉最近的一份 base（全量）**，把它当作
     * 起点：否则要从第一条 rev 开始重放整部历史。base 是别人设备上的全量状态，
     * 合并进来即等价于「把历史一次性补上」，其后的增量 rev 照常处理。
     *
     * 返回下载份数、合并条数与冲突条数。
     */
    private suspend fun pull(s3: S3Config): PullResult {
        val listed = client.listObjects(s3, CloudSyncKeys.listPrefix(config.prefix()))
        val objects = when (listed) {
            is ListObjectsResult.Success -> listed.objects
            is ListObjectsResult.Failure -> throw IllegalStateException(listed.message)
        }

        val storedWatermark = appPreferences.pullWatermark(PEER_ID)
        var watermark = storedWatermark ?: 0L
        var downloaded = 0
        var mergedCount = 0
        var conflicts = 0

        // 首次加入：先拉最近一份 base。它可能来自任意一台设备（含自己——自己的
        // base 也是有效起点，但数据本就在本地，故排除自己的以省一次下载）。
        if (storedWatermark == null) {
            val base = selectLatestBase(objects, appPreferences.deviceId)
            if (base != null) {
                val attempt = downloadAndMerge(s3, base)
                if (attempt.outcome == PullItemOutcome.Merged) {
                    downloaded++
                    mergedCount += attempt.count
                    conflicts += attempt.conflicts
                    // base 是别人某时刻的全量状态，其后的 rev 里可能有更新的内容，
                    // 因此水位线推到 base 的服务端时间，让后续 rev 能被纳入。
                    watermark = maxOf(watermark, base.lastModified)
                }
                // base 不在或读失败：不回退——没有它只是首次同步慢一点，
                // 后面的 rev 仍会逐个补齐。
            }
        }

        val fresh = selectFreshRevisions(objects, appPreferences.deviceId, watermark)

        // 按服务端时间升序遍历，逐份处理。
        //
        // 顺序很重要：水位线的语义是「这个时间点之前的都已合并」，因此必须能判断
        // 「前面是否出现过失败」。升序处理 + 遇错即停，才能保证不跨过失败项。
        val processed = mutableListOf<Pair<Long, PullItemOutcome>>()
        for (entry in fresh) {
            val attempt = downloadAndMerge(s3, entry)
            when (attempt.outcome) {
                PullItemOutcome.Merged -> {
                    downloaded++
                    mergedCount += attempt.count
                    conflicts += attempt.conflicts
                }
                PullItemOutcome.Gone, PullItemOutcome.Failed -> Unit
            }
            processed += entry.lastModified to attempt.outcome
            if (attempt.outcome == PullItemOutcome.Failed) break
        }

        val newWatermark = advancePullWatermark(watermark, processed)
        if (newWatermark > watermark) appPreferences.setPullWatermark(PEER_ID, newWatermark)
        return PullResult(downloaded = downloaded, merged = mergedCount, conflicts = conflicts)
    }

    /**
     * 下载一份快照并合并，同时统计其中的真冲突条数。
     *
     * 冲突统计走 [AppBackupManager.previewBackup]：它以本机上次同步时间为基线，
     * 把「双方都改过」的条目标为冲突（与局域网同步用的是同一套语义）。
     * 云端同步是后台自动进行的，不可能像局域网那样弹窗逐条勾选，因此这里的冲突
     * 只做**计数与展示**——让用户知道「有 N 条被较新的一方覆盖」，而不是静默吃掉。
     */
    private suspend fun downloadAndMerge(s3: S3Config, entry: S3ObjectEntry): MergeAttempt {
        // 放 cacheDir 而非系统临时目录：Android 上应用对系统 tmp 无写权限，
        // 且这里与项目其它临时文件（同步、导入）保持一致，便于统一清理。
        val local = File(
            context.cacheDir,
            "cloud-rev-${System.currentTimeMillis()}-${entry.key.hashCode()}.zip"
        )
        return try {
            when (client.getObject(s3, entry.key, local)) {
                is S3Outcome.Success -> {
                    val uri = Uri.fromFile(local)
                    val conflicts = runCatching {
                        backupManager.previewBackup(
                            sourceUri = uri,
                            syncedAfter = appPreferences.lastSyncAt.value
                        ).conflicts
                    }.getOrDefault(0)
                    val result = backupManager.importBackup(sourceUri = uri)
                    MergeAttempt(
                        outcome = PullItemOutcome.Merged,
                        count = result.itemAdded + result.itemUpdated,
                        conflicts = conflicts
                    )
                }

                // 对象已不在（被别的设备清理了）：它不会再回来，跨过它继续。
                is S3Outcome.NotFound -> MergeAttempt(PullItemOutcome.Gone)

                // 其它错误（网络、权限、服务端）：停在这一份。
                is S3Outcome.Error -> MergeAttempt(PullItemOutcome.Failed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 合并本身出错（包损坏等）：同样视为失败，停在这里。
            MergeAttempt(PullItemOutcome.Failed)
        } finally {
            local.delete()
        }
    }

    /**
     * 收拾旧快照：保留规则见 [selectCleanupCandidates]。
     *
     * 为什么必须保守：清理掉**别的设备还没拉走**的 rev，那些变更就永远看不到了。
     * 因此规则取「最近 N 条」与「最近 7 天」的**并集**，给足拉取窗口。
     * base 一律不在此清理——它是新设备加入的起点。
     */
    private suspend fun cleanup(s3: S3Config) {
        val listed = client.listObjects(s3, CloudSyncKeys.listPrefix(config.prefix()))
        val objects = (listed as? ListObjectsResult.Success)?.objects ?: return
        if (objects.isEmpty()) return
        // 「现在」取已列出对象里最新的服务端时间，而**不是客户端时钟**：
        // 两者可能偏移（同 [CloudSyncKeys] 对「排序一律用服务端 LastModified」的考量），
        // 而清理是不可逆操作，误删别人还没拉走的 rev 无从恢复。用服务端锚点只可能偏保守
        // （长时间不同步时窗口顺延，最多是少删几个对象），不会因客户端时钟快而多删。
        val serverNow = objects.maxOf { it.lastModified }
        val doomed = selectCleanupCandidates(
            objects = objects,
            nowMillis = serverNow,
            keepRecentCount = REV_KEEP_RECENT,
            keepWindowMillis = REV_KEEP_WINDOW_MS
        )
        for (key in doomed) {
            // 删除失败无所谓：下一轮还会再试，对象多留一轮不影响正确性。
            client.deleteObject(s3, key)
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

        /** 攒够这么多次增量推送就刷新一份全量 base（新设备加入时少重放）。 */
        const val BASE_EVERY_PUSHES = 20

        /** 距上次 base 超过这么久也刷新一份，避免 base 与现状差太远。 */
        const val BASE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** 清理时至少保留的最近 rev 条数。 */
        const val REV_KEEP_RECENT = 20

        /** 清理时至少保留的时间窗口（近 7 天的 rev 一律保留）。 */
        const val REV_KEEP_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** 一次拉取的汇总：[downloaded] 份快照、合并 [merged] 条、其中 [conflicts] 条真冲突。 */
internal data class PullResult(
    val downloaded: Int,
    val merged: Int,
    val conflicts: Int
)

/** 单份快照的处理结果与其副作用计数。 */
internal data class MergeAttempt(
    val outcome: PullItemOutcome,
    val count: Int = 0,
    val conflicts: Int = 0
)

/**
 * 挑出「首次加入该拉的」全量 checkpoint：所有 base 里服务端时间最近的一份，
 * 排除本机自己写的（数据本就在本地）。
 *
 * 抽成顶层纯函数以便单测：选错的表现是「新设备从很旧的状态起步」，
 * 界面上完全看不出来，只会觉得「怎么少了一些东西」。
 */
internal fun selectLatestBase(
    objects: List<S3ObjectEntry>,
    deviceId: String
): S3ObjectEntry? =
    objects
        .filter { CloudSyncKeys.isBaseKey(it.key) }
        .filterNot { CloudSyncKeys.isOwnBaseKey(it.key, deviceId) }
        .maxByOrNull { it.lastModified }

/**
 * 挑出可以安全删除的旧 rev。
 *
 * 规则是「最近 [keepRecentCount] 条」与「最近 [keepWindowMillis] 内」的**并集**再取补集——
 * 两个条件任意一条成立就保留，因此：
 * - 短时间内连推很多次：最近的 N 条全保留（条数兜底）；
 * - 长期没同步：7 天内的全保留（时间兜底），越久远的才清理。
 *
 * **base 绝不清理**（[CloudSyncKeys.isBaseKey] 已排除）：它是新设备加入的起点。
 * 排序用服务端时间——与水位线同源，避免客户端时钟偏移导致的误判。
 */
internal fun selectCleanupCandidates(
    objects: List<S3ObjectEntry>,
    nowMillis: Long,
    keepRecentCount: Int,
    keepWindowMillis: Long
): List<String> {
    val revisions = objects
        .filter { CloudSyncKeys.isRevisionKey(it.key) }
        .sortedByDescending { it.lastModified }
    if (revisions.isEmpty()) return emptyList()
    val cutoff = nowMillis - keepWindowMillis
    return revisions
        .drop(keepRecentCount)
        .filter { it.lastModified < cutoff }
        .map { it.key }
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

/**
 * 一次云同步的用户回执文案。
 *
 * 抽成顶层纯函数以便单测：冲突提示是用户唯一能察觉「数据被自动取舍了」的线索，
 * 漏拼接或拼错（比如 0 条也报冲突）会直接误导用户，而这类字符串问题编译器不管。
 *
 * [conflicts] 为 0 时不出现冲突字样——每次同步都挂一句「0 条冲突」纯属噪声。
 */
internal fun cloudSyncMessage(pushed: Int, pulled: Int, conflicts: Int): String = when {
    pushed == 0 && pulled == 0 && conflicts == 0 -> "已是最新"
    else -> buildString {
        append("同步完成")
        if (pushed > 0) append("，上传 1 份变更")
        if (pulled > 0) append("，合并 $pulled 份")
        if (conflicts > 0) append("，$conflicts 条冲突取较新一方")
    }
}
