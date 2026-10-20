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
import com.goodyaoshi.lemonbox.data.local.dao.SyncTableAudit
import com.goodyaoshi.lemonbox.data.local.dao.markSynced
import com.goodyaoshi.lemonbox.data.local.dao.pendingSeqs
import com.goodyaoshi.lemonbox.data.local.dao.tableAudit
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一次同步的结果，供界面给出有内容的回执。 */
data class RemoteSyncResult(
    val pushed: Int,
    val pulled: Int,
    val merged: Int,
    val message: String,
    /** 两边都改过而分出胜负的条数（按「较新的改动为准」收场，内容一致的不计）。 */
    val conflicts: Int = 0,
    /** 本次是否因页数上限只拉了一部分；为真时调用方应提示「再次同步继续」。 */
    val truncated: Boolean = false
)

/** 同步的运行状态。 */
data class RemoteSyncState(
    val running: Boolean = false,
    val lastError: String? = null,
    /** 上次成功同步的时间；从未同步过为 null。 */
    val lastSyncedAt: Long? = null,
    /** 上次同步中服务端报出的冲突条数；用于在界面提示「有内容两边都改过」。 */
    val lastConflicts: Int = 0,
    /** 上次同步是否只拉了一部分（达到 [MAX_PULL_PAGES]），界面据此提示再同步一次。 */
    val lastTruncated: Boolean = false
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
 *    冲突按「较新者胜」收场（见 [resolveConflicts]）：本机 `updatedAt` 更新就把本机版
 *    带着服务端当前序号重推上去，让对端下一轮拉走；服务端较新才落地服务端版本。
 *    缺了这一步，两台设备各自改过同一条时就成了「谁先推谁占位」，后改的那份永远堵在本机。
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
    private val conflictStore: RemoteConflictStore,
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

    /**
     * 执行一次完整同步：先拉别人的变更落地，再推自己的。失败只进状态，不抛给调用方。
     *
     * [includeSelf] 拉取时不排除本设备写过的记录——只有「以服务端为准重建」用它：
     * 重建后本机是空的，服务端上很多记录本就是这台设备写的，排除自己就拉不回全量。
     */
    suspend fun syncNow(includeSelf: Boolean = false): RemoteSyncResult = withContext(Dispatchers.IO) {
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
            val pulled = pull(pendingBeforePull, includeSelf)
            val pushed = push(loadPendingSeqs())
            val now = System.currentTimeMillis()
            appPreferences.setLastSyncAt(now)
            appPreferences.setCloudLastConflicts(pushed.conflicts)
            _state.value = _state.value.copy(
                running = false,
                lastError = null,
                lastSyncedAt = now,
                lastConflicts = pushed.conflicts,
                lastTruncated = pulled.truncated
            )
            RemoteSyncResult(
                pushed = pushed.applied,
                pulled = pulled.downloaded,
                merged = pulled.merged,
                conflicts = pushed.conflicts,
                truncated = pulled.truncated,
                message = remoteSyncMessage(
                    pushed.applied,
                    pulled.downloaded,
                    pushed.conflicts,
                    pulled.truncated
                )
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
    private suspend fun pull(
        pending: Map<String, Map<String, Long>>,
        includeSelf: Boolean
    ): PullOutcome {
        // 水位优先读 sync_state（与落地同事务）；旧安装包的水位还留在偏好里，回落到它以免从头拉全库。
        val sinceSeq = remoteSyncDao.state(SyncStateKeys.PULL_WATERMARK)
            ?: appPreferences.pullWatermark(LEGACY_PEER_ID)
            ?: 0L
        val landed = mutableListOf<LandedRecord>()
        var cursor = sinceSeq
        var lastSeq = sinceSeq
        var pageCount = 0
        var lastHasMore = false

        while (pageCount < MAX_PULL_PAGES) {
            pageCount++
            val page = client.pull(since = cursor, limit = PULL_LIMIT, includeSelf = includeSelf)
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
            lastHasMore = page.hasMore
            if (!page.hasMore) break
            // 没前进就不再来一轮：服务端行为异常时无限翻页会把一次同步拖到天荒地老。
            if (page.changes.isEmpty() || lastSeq <= cursor) break
            cursor = lastSeq
        }

        // 达到页数上限且服务端仍说还有：把「没拉完」如实带回去，界面提示再同步一次，
        // 而不是让用户以为「已是最新」。
        val truncated = pageCount >= MAX_PULL_PAGES && lastHasMore
        val merged = applyPulledRecords(landed, lastSeq)
        return PullOutcome(downloaded = landed.size, merged = merged, truncated = truncated)
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
     * 撞上冲突按「较新者胜」收场（见 [resolveConflicts]）。
     * 服务端单次上限 200 条，故本地先分批再逐批发；一批批推完为止。
     */
    private suspend fun push(pending: Map<String, Map<String, Long>>): PushOutcome {
        // 偏好是整块设置、没有 dirty/serverSeq 列，故恒推；它的序号单独记在 sync_state 里。
        val preferencesSeq = remoteSyncDao.state(SyncStateKeys.PREFERENCES_SEQ)
        // 没有实体 dirty 时只推整块偏好，不再读全库快照：
        // Workers 免费档 CPU/内存都紧，空同步读一遍所有表纯属浪费。
        val hasDirty = pending.values.any { it.isNotEmpty() }
        val records = if (hasDirty) {
            backupManager.snapshotRecords().filter { record ->
                record.collection == SyncCollections.PREFERENCES ||
                    pending[record.collection]?.containsKey(record.key) == true
            }
        } else {
            listOf(backupManager.preferenceSyncRecord())
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
            applied += response.applied.size
            conflicts += resolveConflicts(batch, response.conflicts)
        }
        return PushOutcome(applied = applied, conflicts = conflicts)
    }

    /**
     * 推送冲突的收场（LWW）：两台设备各改过同一条时，**较新的改动胜出**，而不是谁先推谁占位。
     *
     * 冲突本身只说明「服务端比我上次见到的新」，不说明服务端那版内容更晚——先推的设备
     * 完全可能带着更旧的 `updatedAt`。逐条对比两版载荷的更新时间：
     *  - 两版内容一致：只是序号落后，对齐序号即可，不计冲突也不留痕——没有分岔就没有「改过」；
     *  - 本机较新：带着服务端当前序号把本机版重推一次（服务端见 `baseSeq` 相等即放行），
     *    对端下一轮拉走。这是较新改动到达另一台设备的唯一通路：只落地服务端版并清脏的话，
     *    本机这版就永远堵在本机，另一台设备再也看不到；
     *  - 服务端较新（或比不出先后，如旧服务端不带载荷）：按既有 [landConflicts] 落地服务端版本。
     * 重推期间又被别人改了（再撞冲突）就退回「服务端为准」，下一轮同步自会再收敛。
     *
     * 返回真正分过岔的条数，供回执与界面提示。
     */
    private suspend fun resolveConflicts(batch: List<SyncRecord>, conflicts: List<PushConflict>): Int {
        if (conflicts.isEmpty()) return 0
        val serverWins = mutableListOf<PushConflict>()
        val localWins = mutableListOf<Pair<PushConflict, SyncRecord>>()
        var diverged = 0

        conflicts.forEach { conflict ->
            val local = batch.firstOrNull {
                it.collection == conflict.collection && it.key == conflict.key
            }
            when {
                // 两版一模一样：只是序号落后，对齐即可。
                local != null && conflict.payload == local.payload && conflict.deleted == local.deleted ->
                    markLanded(conflict.collection, conflict.key, conflict.seq)
                // 本机改得更晚：先占住服务端的是旧版，把本机版送上去才算「较新者胜」。
                local != null && payloadUpdatedAt(local.payload) > payloadUpdatedAt(conflict.payload) -> {
                    localWins += conflict to local
                    diverged++
                }
                // 服务端较新（或载荷缺失比不出）：按既有路径落地服务端版本。
                else -> {
                    serverWins += conflict
                    diverged++
                }
            }
        }

        if (localWins.isNotEmpty()) {
            val retry = client.push(localWins.map { (conflict, local) ->
                PushChange(
                    collection = local.collection,
                    key = local.key,
                    payload = local.payload,
                    baseSeq = conflict.seq,
                    deleted = local.deleted
                )
            })
            retry.applied.forEach { markLanded(it.collection, it.key, it.seq) }
            // 重推期间又被别人改了：与真正的服务端胜出一起走落地路径，下一轮同步再收敛。
            serverWins += retry.conflicts
            val retrySeq = retry.applied.associate { (it.collection to it.key) to it.seq }
            conflictStore.append(localWins.map { (conflict, local) ->
                ConflictLogEntry(
                    collection = conflict.collection,
                    key = conflict.key,
                    seq = retrySeq[conflict.collection to conflict.key] ?: conflict.seq,
                    baseSeq = conflict.baseSeq,
                    localPayload = local.payload,
                    remotePayload = conflict.payload,
                    at = System.currentTimeMillis(),
                    updatedBy = conflict.updatedBy,
                    resolution = ConflictLogEntry.RESOLUTION_LOCAL
                )
            })
        }

        if (serverWins.isNotEmpty()) landConflicts(batch, serverWins)
        return diverged
    }

    /** 记下「这一条已在服务端」：写回服务端序号、清脏；整块设置另把序号存进 sync_state。 */
    private suspend fun markLanded(collection: String, key: String, seq: Long) {
        remoteSyncDao.markSynced(collection, key, seq)
        if (collection == SyncCollections.PREFERENCES) {
            remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PREFERENCES_SEQ, seq))
        }
    }

    /**
     * 把一批推送冲突落地为服务端版本，并写入冲突留痕（P9）。
     *
     * 落地与拉取共用同一条合并通道（ZIP 中转 + `importBackup`），「较新者胜、墓碑传播、
     * 引用重映射」规则天然一致；服务端已是权威，落地默认不标脏（P11），但合并里的
     * 墓碑副作用（`markPurged` 等）仍会把行标脏——所以落地之后**再**逐条 [markLanded]
     * 清脏，顺序反了会把刚落地的行当成本机新改动推回去。
     *
     * 服务端载荷为空的旧版冲突（服务端未升级）只对齐序号、不落地内容，与旧行为一致。
     * 留痕是非事务的旁路：失败只影响「以后能不能回看对比」，不影响数据一致。
     */
    private suspend fun landConflicts(batch: List<SyncRecord>, conflicts: List<PushConflict>) {
        val landable = conflicts.filter { it.payload.isNotEmpty() }
        appDatabase.withTransaction {
            if (landable.isNotEmpty()) {
                val payload = syncRecordsToPayload(
                    landable.map { SyncRecord(it.collection, it.key, it.payload, it.deleted) },
                    appPreferences.deviceId
                )
                val file = File(context.cacheDir, "remote-conflict-${System.currentTimeMillis()}.zip")
                try {
                    backupManager.writePayloadZipTo(payload, file)
                    backupManager.importBackup(Uri.fromFile(file))
                } finally {
                    file.delete()
                }
            }
            conflicts.forEach { markLanded(it.collection, it.key, it.seq) }
        }
        conflictStore.append(
            conflicts.map { conflict ->
                val local = batch.firstOrNull {
                    it.collection == conflict.collection && it.key == conflict.key
                }
                ConflictLogEntry(
                    collection = conflict.collection,
                    key = conflict.key,
                    seq = conflict.seq,
                    baseSeq = conflict.baseSeq,
                    localPayload = local?.payload.orEmpty(),
                    remotePayload = conflict.payload,
                    at = System.currentTimeMillis(),
                    updatedBy = conflict.updatedBy
                )
            }
        )
    }

    // ---------- 对账与诊断（P9） ----------

    /**
     * 逐表对账：本机各同步表的计数对照服务端各集合的计数。
     *
     * 网络失败（未登录、断网）原样抛 [RemoteApiException]，由界面转成提示——
     * 对账是用户主动动作，静默返回空表反而让人误以为「没问题」。
     */
    suspend fun audit(): SyncAuditReport {
        val stats = client.stats()
        val serverByCollection = stats.collections.associateBy { it.collection }
        val rows = SyncTables.BY_COLLECTION.keys.map { collection ->
            val local = remoteSyncDao.tableAudit(collection)
            val server = serverByCollection[collection]
            SyncAuditRow(
                collection = collection,
                label = collectionLabel(collection),
                localLive = local?.live,
                localTotal = local?.let { it.live + it.tombstones } ?: 0,
                missingSyncId = local?.missingSyncId ?: 0,
                localDirty = local?.dirty ?: 0,
                serverLive = server?.live ?: 0,
                serverDeleted = server?.deleted ?: 0,
                serverKnown = server != null
            )
        }
        return SyncAuditReport(
            serverSeq = stats.seq,
            conflictCount = stats.conflictCount,
            rows = rows,
            devices = stats.devices,
            generatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 「以服务端为准重建本地」：本机数据可能已经乱掉，用服务端的权威版本整个重来一遍。
     *
     * 步骤（每一步都为失败留了退路）：
     * 1. 闸门检查——未配置 / 未登录 / 未开启一律拒绝，本机数据一个字节都不动；
     * 2. 先探一次服务端（stats）——连不上就放弃，**清库之前**一切失败都是零损失；
     * 3. 导出本机全量快照存进 filesDir 作为**撤销点**（不是 cacheDir，重建后 App 随时可能被杀）；
     * 4. 清空本地数据库（含 sync_state：水位显式归零，偏好序号一并归零）；
     * 5. 跑一轮带 [includeSelf] 的完整同步，把服务端全量拉回来。
     *
     * 重建中途失败（断网等）本机会停在「不完整」状态，撤销点就是那时的救命稻草，
     * 故失败的回执里也带上撤销路径。
     */
    suspend fun rebuildFromServer(): RebuildOutcome = withContext(Dispatchers.IO) {
        val blocker = if (!client.isConfigured()) {
            "这个安装包没有配置同步服务器地址"
        } else {
            config.remoteSyncBlocker()
        }
        if (blocker != null) return@withContext RebuildOutcome(success = false, message = blocker)
        if (_state.value.running) {
            return@withContext RebuildOutcome(success = false, message = "同步正在进行中，稍后再试")
        }

        val stats = try {
            client.stats()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext RebuildOutcome(
                success = false,
                message = "连不上服务端（${e.message ?: "网络异常"}），本机数据未动"
            )
        }
        if (stats.seq <= 0) {
            return@withContext RebuildOutcome(success = false, message = "服务端上还没有任何同步数据，无需重建")
        }

        // 撤销点：重建前的本机全量。
        val archive = backupManager.createBackupZip(null)
        val undoFile = File(context.filesDir, "rebuild-undo-${System.currentTimeMillis()}.zip")
        if (archive.file.absolutePath != undoFile.absolutePath) {
            if (!archive.file.renameTo(undoFile)) {
                archive.file.copyTo(undoFile, overwrite = true)
                archive.file.delete()
            }
        }

        // 清库（sync_state 一并清掉），水位显式归零才能从头拉全量。
        appDatabase.clearAllTables()
        remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PULL_WATERMARK, 0L))

        val result = syncNow(includeSelf = true)
        val error = _state.value.lastError
        if (error != null) {
            RebuildOutcome(
                success = false,
                message = "重建中断：$error。本机数据可能不完整，可用「撤销重建」恢复重建前的备份。",
                undoZipPath = undoFile.path
            )
        } else {
            RebuildOutcome(success = true, message = "重建完成：${result.message}", undoZipPath = undoFile.path)
        }
    }

    /**
     * 撤销重建：把重建前的快照导回本机。
     *
     * 必须 [AppBackupManager.importBackup] `markDirty = true`（P11）：快照里有本机自建、
     * 从没上过服务端的记录，不标脏就永远不会被增量推送带走，服务端缺数据的局面原样保留。
     * 服务端已有的行会以 baseSeq=0 推上去并按冲突处理——内容与服务器一致的只是白耗一轮
     * 序号，内容有差异的进冲突留痕（字段对比可查），两边都不静默丢。
     */
    suspend fun undoRebuild(undoZipPath: String): String = withContext(Dispatchers.IO) {
        val file = File(undoZipPath)
        if (!file.isFile) return@withContext "找不到重建前的备份文件，无法撤销"
        try {
            val result = backupManager.importBackup(Uri.fromFile(file), markDirty = true)
            file.delete()
            "已恢复重建前数据（新增 ${result.totalAdded}、更新 ${result.totalUpdated} 条），" +
                "打开 App 时会自动同步回服务端"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "撤销失败：${e.message ?: "未知错误"}（备份文件保留在原处，可重试）"
        }
    }

    /** 读取冲突留痕（新痕在前），诊断页逐条展示字段对比。 */
    suspend fun conflictLog(): List<ConflictLogEntry> = conflictStore.read()

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

        /** 单次拉取条数上限（与服务端 `PULL_MAX_LIMIT` 对齐；刻意压小以降低单请求 CPU/内存）。 */
        const val PULL_LIMIT = 200

        /** 单次推送条数上限（与服务端 `PUSH_MAX_CHANGES` 对齐）。 */
        const val PUSH_LIMIT = 100

        /** 一次同步最多翻这么多页，避免服务端异常时无限翻页（200 × 100 = 2 万条）。 */
        const val MAX_PULL_PAGES = 100
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
    val merged: Int,
    val truncated: Boolean = false
)

/** 推送的汇总：服务端已写下的条数与冲突分出胜负的条数。 */
private data class PushOutcome(val applied: Int, val conflicts: Int)

/**
 * 一次同步的用户回执文案。
 *
 * 抽成顶层纯函数以便单测：冲突提示是用户唯一能察觉「两边都改过同一条」的线索，
 * 漏拼接或拼错（比如 0 条也报冲突）会直接误导用户，而这类字符串问题编译器不管。
 *
 * [conflicts] 为 0 时不出现冲突字样——每次同步都挂一句「0 条冲突」纯属噪声。
 */
internal fun remoteSyncMessage(
    pushed: Int,
    pulled: Int,
    conflicts: Int,
    truncated: Boolean = false
): String = when {
    pushed == 0 && pulled == 0 && conflicts == 0 && !truncated -> "已是最新"
    else -> buildString {
        append("同步完成")
        if (pushed > 0) append("，上传 $pushed 条")
        if (pulled > 0) append("，收到 $pulled 条")
        if (conflicts > 0) append("，$conflicts 条两边都改过，已按较新的改动为准")
        if (truncated) append("；还有更多变更未拉取，请再次同步")
    }
}

/**
 * 从快照载荷里取 `updatedAt`（毫秒）；缺字段或解不开按 0。
 *
 * 冲突取舍只比这一个数：所有实体快照都有同名的更新时间字段，按「取字段」而非逐类型
 * 反序列化，省掉为 14 种 Snapshot 各写一套比较器。取不到时按 0 处理会让「本机较新」
 * 的判断保守地倾向服务端，宁可少推一轮也不与对端乒乓。
 */
internal fun payloadUpdatedAt(payload: String?): Long {
    if (payload.isNullOrBlank()) return 0L
    return runCatching {
        (Json.parseToJsonElement(payload) as? JsonObject)
            ?.get("updatedAt")
            ?.jsonPrimitive
            ?.longOrNull
            ?: 0L
    }.getOrDefault(0L)
}

/** 集合名 → 界面中文标签（P9 诊断页）。 */
internal fun collectionLabel(collection: String): String = when (collection) {
    SyncCollections.CATEGORIES -> "分类"
    SyncCollections.LOCATIONS -> "位置"
    SyncCollections.ITEMS -> "家当"
    SyncCollections.LEDGER_ASSETS -> "账户"
    SyncCollections.LEDGER_CATEGORIES -> "记账分类"
    SyncCollections.LEDGER_RECORDS -> "账目"
    SyncCollections.LEDGER_BUDGETS -> "预算"
    SyncCollections.ANNIVERSARIES -> "纪念日"
    SyncCollections.REMINDERS -> "待办提醒"
    SyncCollections.TIME_CAPSULES -> "时间胶囊"
    SyncCollections.PACKING_TRIPS -> "打包行程"
    SyncCollections.PACKING_ITEMS -> "打包条目"
    SyncCollections.LEDGER_TEMPLATES -> "记账模板"
    SyncCollections.LEDGER_RECURRING_RULES -> "周期记账"
    SyncCollections.PREFERENCES -> "整块偏好"
    else -> collection
}

/**
 * 对账报告里一行的三方计数（P9）。
 *
 * [localLive] 为 null 表示本机没有对应表（整块偏好）：它不分条，无法按行数对。
 * [localTotal] 含墓碑；[missingSyncId] 是推不出去也拉不回来的活行数，非 0 就是账要出事的信号。
 * [serverKnown] 为假表示服务端上还没有这个集合的任何记录。
 */
data class SyncAuditRow(
    val collection: String,
    val label: String,
    val localLive: Int?,
    val localTotal: Int,
    val missingSyncId: Int,
    val localDirty: Int,
    val serverLive: Int,
    val serverDeleted: Int,
    val serverKnown: Boolean
) {
    /** 本机 live 与服务端 live 的差；为 0 也可能是两边恰好同时错，但数量对不上必有一方账不平。 */
    val delta: Int get() = (localLive ?: 0) - serverLive
}

/** 一次对账的总览：逐表计数、设备列表与服务端台账（P9）。 */
data class SyncAuditReport(
    /** 服务端当前序号水位。 */
    val serverSeq: Long,
    /** 服务端冲突台账累计条数（历史累计，只增不减）。 */
    val conflictCount: Int,
    val rows: List<SyncAuditRow>,
    val devices: List<SyncStatsDevice>,
    val generatedAt: Long
)

/** 一次重建的结果：成败、回执文案与撤销点的文件路径（撤销点在重建成功后由界面持有）。 */
data class RebuildOutcome(
    val success: Boolean,
    val message: String,
    val undoZipPath: String? = null
)
