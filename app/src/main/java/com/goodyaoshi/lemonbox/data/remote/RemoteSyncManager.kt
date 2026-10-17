package com.goodyaoshi.lemonbox.data.remote

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.SyncCollections
import com.goodyaoshi.lemonbox.data.backup.SyncRecord
import com.goodyaoshi.lemonbox.data.backup.SyncTables
import com.goodyaoshi.lemonbox.data.backup.syncRecordsToPayload
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudConfig
import com.goodyaoshi.lemonbox.data.local.dao.RemoteSyncDao
import com.goodyaoshi.lemonbox.data.local.dao.markSynced
import com.goodyaoshi.lemonbox.data.local.dao.pendingSeqs
import com.goodyaoshi.lemonbox.data.local.database.AppDatabase
import com.goodyaoshi.lemonbox.data.local.entity.SyncStateKeys
import com.goodyaoshi.lemonbox.data.local.entity.SyncStateRow
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
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

/** 一次同步的结果，供界面给出有内容的回执。 */
data class RemoteSyncResult(
    val pushed: Int,
    val pulled: Int,
    val merged: Int,
    val message: String,
    /** 服务端判定「两边都改过」而未覆盖的条数（这些条目服务端版本胜出）。 */
    val conflicts: Int = 0
)

/** 同步的运行状态。 */
data class RemoteSyncState(
    val running: Boolean = false,
    val lastError: String? = null,
    /** 上次成功同步的时间；从未同步过为 null。 */
    val lastSyncedAt: Long? = null,
    /** 上次同步中服务端报出的冲突条数；用于在界面提示「有内容两边都改过」。 */
    val lastConflicts: Int = 0
)

/**
 * 多设备同步（服务端记录权威）。
 *
 * 与旧的「对象存储里摆 ZIP 快照」最根本的区别：**同步的不再是不可变快照，而是逐条记录**，
 * 每条带服务端单调序号（`server_seq`）。序号由服务端发号，取代了墙钟 LWW 与
 * 「按时间比大小」的水位线，两台设备的时钟差不参与任何取舍。
 *
 * 一轮的顺序是**先拉后推**：
 * 1. 拉：按上次水位线增量拉取别人改过的记录，重组成一份清单交给既有的合并通道落地
 *    （`AppBackupManager.importBackup`），落地与水位推进写在**同一个事务**里——推进早了
 *    会让失败那批被永久跨过且毫无提示。
 * 2. 推：只推本机 `dirty = 1` 的那一小批（连同整块设置 `preferences`），每条带 `baseSeq`
 *    ＝本机上次见到的服务端序号；服务端发现有人在这之后改过就**不覆盖**，只回报冲突。
 *
 * 增量的三个要点（P6）：
 *  - **只推脏记录**：不再每次把整库当快照推上去，往返体积与序号消耗都只跟「这次真正改了什么」相关。
 *  - **拉取跳过本机脏记录**：服务端上那一版可能是旧的，直接盖下来会吃掉本机还没上传的改动；
 *    跳过之后由推送用陈旧 `baseSeq` 撞出冲突并如实回报，两边都不被静默改写。
 *  - **落地后统一清脏**：合并通道里的墓碑副作用（`markPurged` 等）会把刚拉回的行重新标脏，
 *    而服务端不比对载荷、每次成功推送都发新序号，不清就会与对端形成序号无限递增的乒乓。
 *
 * 与局域网同步的关系：并存。同一 WiFi 下局域网更快、也不依赖外网；
 * 服务端同步则解决「不同网络」与「对方不在线」的场景，是常态路径。
 *
 * 未登录或未开启多设备同步时直接返回，由界面提示原因——此时本机就是纯本地。
 * 任何一步失败都不抛给调用方（后台任务不该因一次网络抖动而崩），错误进 [RemoteSyncState.lastError]。
 */
@Singleton
class RemoteSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: RemoteSyncClient,
    private val backupManager: AppBackupManager,
    private val appPreferences: AppPreferences,
    private val config: ImageCloudConfig,
    private val remoteSyncDao: RemoteSyncDao,
    private val appDatabase: AppDatabase
) {

    private val _state = MutableStateFlow(
        RemoteSyncState(
            lastSyncedAt = appPreferences.lastSyncAt.value,
            // 冲突数从持久化读回：后台同步（约每小时一次）产生的冲突，
            // 用户往往不当场打开设置页，不落盘就永远看不到。
            lastConflicts = appPreferences.cloudLastConflicts()
        )
    )
    val state: StateFlow<RemoteSyncState> = _state.asStateFlow()

    /** 执行一次完整同步：先拉别人的变更落地，再推自己的。失败只进状态，不抛给调用方。 */
    suspend fun syncNow(): RemoteSyncResult = withContext(Dispatchers.IO) {
        if (_state.value.running) return@withContext RemoteSyncResult(0, 0, 0, "同步正在进行中")
        val blocker = if (!client.isConfigured()) {
            "这个安装包没有配置同步服务器地址"
        } else {
            config.remoteSyncBlocker()
        }
        if (blocker != null) {
            _state.value = _state.value.copy(lastError = blocker)
            return@withContext RemoteSyncResult(0, 0, 0, blocker)
        }

        _state.value = _state.value.copy(running = true, lastError = null)
        try {
            // 脏账本读两次：拉取前那次决定「跳过哪些本机未上传的改动」，
            // 拉取后那次决定「还剩哪些要推」——落地会清掉一批脏位，沿用旧账本会白白重推一遍。
            val pendingBeforePull = loadPendingSeqs()
            val pulled = pull(pendingBeforePull)
            val pushed = push(loadPendingSeqs())
            val now = System.currentTimeMillis()
            appPreferences.setLastSyncAt(now)
            appPreferences.setCloudLastConflicts(pushed.conflicts)
            _state.value = _state.value.copy(
                running = false,
                lastError = null,
                lastSyncedAt = now,
                lastConflicts = pushed.conflicts
            )
            RemoteSyncResult(
                pushed = pushed.applied,
                pulled = pulled.downloaded,
                merged = pulled.merged,
                conflicts = pushed.conflicts,
                message = remoteSyncMessage(pushed.applied, pulled.downloaded, pushed.conflicts)
            )
        } catch (e: CancellationException) {
            // 取消要走原始路径：吞掉它会让协程在已取消后继续跑。
            _state.value = _state.value.copy(running = false)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "同步失败，请稍后重试"
            _state.value = _state.value.copy(running = false, lastError = message)
            RemoteSyncResult(0, 0, 0, message)
        }
    }

    /** 本机全部「改过、还没被服务端确认」的记录：集合名 → (syncId → 本地 serverSeq)。 */
    private suspend fun loadPendingSeqs(): Map<String, Map<String, Long>> =
        SyncTables.BY_COLLECTION.keys.associateWith { collection ->
            remoteSyncDao.pendingSeqs(collection)
        }

    /**
     * 按服务端序号增量拉取，并重组落地。
     *
     * 分页由 [PullResponse.hasMore] 驱动：一页满了就接着拉下一批，直到服务端说没有了。
     * 分段拉取也顺带避开 Workers 免费档的单次 CPU 上限（拉太多会被限流）。
     */
    private suspend fun pull(pending: Map<String, Map<String, Long>>): PullOutcome {
        // 水位优先读 sync_state（与落地同事务）；旧安装包的水位还留在偏好里，回落到它以免从头拉全库。
        val sinceSeq = remoteSyncDao.state(SyncStateKeys.PULL_WATERMARK)
            ?: appPreferences.pullWatermark(LEGACY_PEER_ID)
            ?: 0L
        val landed = mutableListOf<LandedRecord>()
        var cursor = sinceSeq
        var lastSeq = sinceSeq
        var pageCount = 0

        while (pageCount < MAX_PULL_PAGES) {
            pageCount++
            val page = client.pull(since = cursor, limit = PULL_LIMIT)
            page.changes.forEach { change ->
                // 本机这条也改过且还没推上去：先留本机改动（服务端那一版可能是旧的，
                // 盖下来就等于把本机还没上传的编辑吃掉）。随后推送会用陈旧 baseSeq 撞出冲突，
                // 由服务端如实回报，两边都不被静默改写。
                if (pending[change.collection]?.containsKey(change.key) == true) return@forEach
                landed += LandedRecord(
                    collection = change.collection,
                    key = change.key,
                    payload = change.payload,
                    deleted = change.deleted,
                    seq = change.seq
                )
            }
            lastSeq = page.lastSeq
            if (!page.hasMore) break
            // 没前进就不再来一轮：服务端行为异常时无限翻页会把一次同步拖到天荒地老。
            if (page.changes.isEmpty() || lastSeq <= cursor) break
            cursor = lastSeq
        }

        val merged = applyPulledRecords(landed, lastSeq)
        return PullOutcome(downloaded = landed.size, merged = merged)
    }

    /**
     * 把拉回的一批记录重组为一份清单，走既有的合并通道落地，并把水位与记账一起写进同一个事务。
     *
     * 复用而不另写一套合并：那套规则（按 syncId 对齐、较新者胜、墓碑传播、父级重映射、
     * 内置数据补齐）早已为多设备一致调过，服务端同步只是换了个「数据从哪来」的入口。
     */
    private suspend fun applyPulledRecords(landed: List<LandedRecord>, lastSeq: Long): Int =
        appDatabase.withTransaction {
            var merged = 0
            if (landed.isNotEmpty()) {
                val payload = syncRecordsToPayload(
                    landed.map { SyncRecord(it.collection, it.key, it.payload, it.deleted) },
                    appPreferences.deviceId
                )
                // 走 ZIP 中转而非直传清单：合并通道的入口就是「一个备份文件」，
                // 这样校验（清单 SHA-256）与导入的临时文件清理都沿用既有实现，不必再开一条旁路。
                val file = File(context.cacheDir, "remote-pull-${System.currentTimeMillis()}.zip")
                try {
                    backupManager.writePayloadZipTo(payload, file)
                    val result = backupManager.importBackup(Uri.fromFile(file))
                    merged = result.totalAdded + result.totalUpdated
                } finally {
                    file.delete()
                }
                // 合并通道里的墓碑副作用（`markPurged` 等）会把本机行重新标脏，而这些行的
                // syncId 恰好就在刚拉回的名单里：合并之后再统一清一遍脏并写回服务端序号。
                // 不做这一步，它们会被当成本机新改动反复推上去；叠加「服务端不比对载荷、
                // 每次成功推送都发新序号」，就是两台设备之间序号无限递增的乒乓。
                landed.forEach { markLanded(it.collection, it.key, it.seq) }
            }
            // 落地成功才推进水位：推进早了会让失败那批被永久跨过且毫无提示。
            if (lastSeq > 0) {
                remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PULL_WATERMARK, lastSeq))
            }
            // 首次成功落地＝服务端已开始确认本机数据；写一次 server_ack 标记，
            // 供启动清理据此判断「墓碑可以被物理清除」的前提（P6-d）。
            if (remoteSyncDao.state(SyncStateKeys.SERVER_ACK) == null) {
                remoteSyncDao.putState(SyncStateRow(SyncStateKeys.SERVER_ACK, 1L))
            }
            merged
        }

    /**
     * 推送本机 `dirty = 1` 的记录（外加恒推的整块设置 `preferences`）。
     *
     * 每条带 [PushChange.baseSeq]＝本机上次见到的服务端序号（本地 `serverSeq` 列），
     * 所以「本机自己写过、服务端按设备排除掉」的记录也有正确的进度，不会被一律判成冲突。
     *
     * 服务端单次上限 200 条，故本地先分批再逐批发；一批批推完为止。
     */
    private suspend fun push(pending: Map<String, Map<String, Long>>): PushOutcome {
        // 偏好是整块设置、没有 dirty/serverSeq 列，故恒推；它的序号单独记在 sync_state 里。
        val preferencesSeq = remoteSyncDao.state(SyncStateKeys.PREFERENCES_SEQ)
        val records = backupManager.snapshotRecords().filter { record ->
            record.collection == SyncCollections.PREFERENCES ||
                pending[record.collection]?.containsKey(record.key) == true
        }
        if (records.isEmpty()) return PushOutcome(applied = 0, conflicts = 0)

        var applied = 0
        var conflicts = 0
        records.chunked(PUSH_LIMIT).forEach { batch ->
            val response = client.push(
                batch.map { record ->
                    PushChange(
                        collection = record.collection,
                        key = record.key,
                        payload = record.payload,
                        baseSeq = if (record.collection == SyncCollections.PREFERENCES) {
                            preferencesSeq ?: 0L
                        } else {
                            pending[record.collection]?.get(record.key) ?: 0L
                        },
                        deleted = record.deleted
                    )
                }
            )
            // 服务端已写下的：把序号记回本机并清脏，否则下一轮还会推同一条（服务端不比对载荷）。
            response.applied.forEach { markLanded(it.collection, it.key, it.seq) }
            // 判为冲突而未覆盖的：同样把序号推进到服务端那一版并清脏，避免本机每次同步都拿
            // 同一个陈旧 baseSeq 反复撞同一堵墙。条数如实回报，由界面告知用户。
            response.conflicts.forEach { markLanded(it.collection, it.key, it.seq) }
            applied += response.applied.size
            conflicts += response.conflicts.size
        }
        return PushOutcome(applied = applied, conflicts = conflicts)
    }

    /** 记下「这一条已在服务端」：写回服务端序号、清脏；整块设置另把序号存进 sync_state。 */
    private suspend fun markLanded(collection: String, key: String, seq: Long) {
        remoteSyncDao.markSynced(collection, key, seq)
        if (collection == SyncCollections.PREFERENCES) {
            remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PREFERENCES_SEQ, seq))
        }
    }

    private companion object {
        /**
         * 旧版把服务端序号水位存在 [AppPreferences] 里用的「同伴」名。
         *
         * 与旧的云端快照同步（`cloud`）刻意分开：那套水位线记的是**墙钟时间**，
         * 而复用同一个键会让它被当成服务端序号读走（时间戳恒大于任何序号），
         * 于是 `since` 巨大、一条都拉不下来，且完全看不出问题。
         * P6 起水位搬进 `sync_state` 表，这里只为兼容旧安装包读一次。
         */
        const val LEGACY_PEER_ID = "d1"

        /** 单次拉取条数上限（与服务端 `PULL_MAX_LIMIT` 对齐）。 */
        const val PULL_LIMIT = 500

        /** 单次推送条数上限（与服务端 `PUSH_MAX_CHANGES` 对齐）。 */
        const val PUSH_LIMIT = 200

        /** 一次同步最多翻这么多页，避免服务端异常时无限翻页（500 × 40 = 2 万条）。 */
        const val MAX_PULL_PAGES = 40
    }
}

/** 一条已从服务端拉回、待落地的记录（含服务端序号，落地后要写回本机）。 */
private data class LandedRecord(
    val collection: String,
    val key: String,
    val payload: String,
    val deleted: Boolean,
    val seq: Long
)

/** 拉取的汇总：本轮落地条数与合并计数。 */
private data class PullOutcome(
    val downloaded: Int,
    val merged: Int
)

/** 推送的汇总：服务端已写下的条数与「未覆盖」的冲突条数。 */
private data class PushOutcome(val applied: Int, val conflicts: Int)

/**
 * 一次同步的用户回执文案。
 *
 * 抽成顶层纯函数以便单测：冲突提示是用户唯一能察觉「自己的改动没上去」的线索，
 * 漏拼接或拼错（比如 0 条也报冲突）会直接误导用户，而这类字符串问题编译器不管。
 *
 * [conflicts] 为 0 时不出现冲突字样——每次同步都挂一句「0 条冲突」纯属噪声。
 */
internal fun remoteSyncMessage(pushed: Int, pulled: Int, conflicts: Int): String = when {
    pushed == 0 && pulled == 0 && conflicts == 0 -> "已是最新"
    else -> buildString {
        append("同步完成")
        if (pushed > 0) append("，上传 $pushed 条")
        if (pulled > 0) append("，收到 $pulled 条")
        if (conflicts > 0) append("，$conflicts 条两边都改过，以服务端为准")
    }
}
