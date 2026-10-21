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
import com.goodyaoshi.lemonbox.data.local.dao.localStamp
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /** 上次同步中因载荷无法解析而跳过的服务端记录数；非 0 表示有数据没能落地。 */
    val lastSkippedPayloads: Int = 0,
    /** 上次同步是否只拉了一部分（达到 [MAX_PULL_PAGES]），界面据此提示再同步一次。 */
    val lastTruncated: Boolean = false,
    /**
     * 当前阶段的可读进度（「正在拉取：第 2 页，已收 200 条」）。
     *
     * 同步可能在首次登录/大量变更时持续几十秒，只显示「正在同步…」会让用户以为死机；
     * 界面直接展示这句，[running] 为 false 时应为 null。
     */
    val phase: String? = null,
    /** [phase] 对应的已处理条数（已收 + 已推），供界面显示进度。 */
    val processed: Int = 0
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
            lastSyncedAt = appPreferences.cloudLastSyncAt.value,
            // 冲突数从持久化读回：后台同步（约每小时一次）产生的冲突，
            // 用户往往不当场打开设置页，不落盘就永远看不到。
            lastConflicts = appPreferences.cloudLastConflicts(),
            lastSkippedPayloads = appPreferences.cloudLastSkippedPayloads()
        )
    )
    val state: StateFlow<RemoteSyncState> = _state.asStateFlow()

    /**
     * 同一时刻只允许一轮同步。
     *
     * 原先只靠 `_state.running` 做闸门，是「先读后写」的竞态：手动点击与前台/写入触发
     * 同时进来时会各自通过检查，两轮同步并发推拉同一条记录。用 Mutex 的 tryLock 做成
     * 原子的「谁先到谁跑」，后来者立刻拿到明确回执，而不是静默排队。
     */
    private val syncMutex = Mutex()

    /** 当前同步协程；供界面「取消同步」中止。 */
    @Volatile
    private var activeJob: Job? = null

    /**
     * 执行一次完整同步：先拉别人的变更落地，再推自己的。失败只进状态，不抛给调用方。
     *
     * [includeSelf] 拉取时不排除本设备写过的记录——只有「以服务端为准重建」用它：
     * 重建后本机是空的，服务端上很多记录本就是这台设备写的，排除自己就拉不回全量。
     *
     * [forceUploadLocal] 是「以本机为准合并上传」：先把本机**全量**记录强制写到服务端
     * （跳过 baseSeq 冲突检查），再拉回其它设备独有的记录。用于用户在对账页确认
     * 「本机这份才是对的、服务端少了/旧了」之后的一次性收敛；日常同步不走这条路。
     */
    suspend fun syncNow(
        includeSelf: Boolean = false,
        forceUploadLocal: Boolean = false
    ): RemoteSyncResult = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) {
            // 后台触发（前台切换 / 写入后 15 秒）撞上正在跑的同步是常态，不是错误：
            // 只回执给调用方，不写 lastError，避免界面莫名弹一条红色提示。
            return@withContext RemoteSyncResult(0, 0, 0, "同步正在进行中，请稍候再试")
        }
        val job = currentCoroutineContext()[Job]
        activeJob = job
        try {
            // 总超时兜底：网络卡住时 OkHttp 自己的超时是按单次请求算的，翻页/重试叠加起来
            // 仍可能让用户盯着「正在同步…」很久。到点直接停（底下的 Call 会被取消）。
            withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                runSync(includeSelf, forceUploadLocal)
            } ?: timeoutResult()
        } finally {
            if (activeJob === job) activeJob = null
            _state.value = _state.value.copy(running = false, phase = null)
            syncMutex.unlock()
        }
    }

    /** 取消正在进行的同步；已完成的批次保留，下次同步会从水位继续。 */
    fun cancel() {
        activeJob?.cancel()
    }

    private fun timeoutResult(): RemoteSyncResult {
        val message = "同步超时（${SYNC_TIMEOUT_MS / 1000} 秒），已停止。请检查网络后重试"
        _state.value = _state.value.copy(running = false, phase = null, lastError = message)
        return RemoteSyncResult(0, 0, 0, message)
    }

    /** [syncNow] 的主体；由总超时与互斥锁包裹，这里只管一轮同步本身。 */
    private suspend fun runSync(
        includeSelf: Boolean,
        forceUploadLocal: Boolean
    ): RemoteSyncResult {
        val blocker = if (!client.isConfigured()) {
            "这个安装包没有配置同步服务器地址"
        } else {
            config.remoteSyncBlocker()
        }
        if (blocker != null) {
            _state.value = _state.value.copy(lastError = blocker)
            return RemoteSyncResult(0, 0, 0, blocker)
        }

        _state.value = _state.value.copy(
            running = true,
            lastError = null,
            phase = "准备同步…",
            processed = 0
        )
        return try {
            // 旧库升级/导入的历史行可能没有 syncId；全量导出会静默跳过它们。
            // 先统一回填一次，否则「立即同步」和「以本机为准合并上传」都会
            // 显示成功但实际一条也推不出去。
            backfillMissingSyncIdsIfNeeded()
            // 先看服务端轻量元信息：序号没变时连业务数据都不用拉；旧服务端没有
            // /v1/meta 时 runCatching 返回 null，自动回退到原来的纯增量逻辑。
            val meta = runCatching { client.meta() }.getOrNull()
            val pendingBeforePull = loadPendingSeqs()
            val watermark = remoteSyncDao.state(SyncStateKeys.PULL_WATERMARK) ?: 0L

            var forceUpload = forceUploadLocal ||
                remoteSyncDao.state(SyncStateKeys.FULL_UPLOAD_REQUIRED) == 1L
            var needSnapshot = false
            if (meta != null) {
                // 服务端被清空/重建的两种可观测形态：
                // 1) 全局序号回到 0（reset / 新库）；
                // 2) 本机水位比服务端当前序号还大（旧水位已越过新库）。
                val serverWiped = meta.seq == 0L || (watermark > 0L && watermark > meta.seq)
                var incomingHash: Long? = null
                var instanceChanged = false
                if (meta.instanceId.isNotBlank()) {
                    incomingHash = payloadHash(meta.instanceId)
                    val localHash = remoteSyncDao.state(SyncStateKeys.INSTANCE_HASH)
                    instanceChanged = localHash != null && localHash != incomingHash
                }
                if (instanceChanged || serverWiped) {
                    // 本机是唯一数据源：重置水位/快照标记，并持久化「必须全量上传」，
                    // 直到 uploadAll 完整成功才清掉。这样即使中途断网/被杀进程，
                    // 下一轮也会继续回灌，而不是因为 instance 已更新就只推 dirty。
                    forceUpload = true
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.FULL_UPLOAD_REQUIRED, 1L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PULL_WATERMARK, 0L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.SNAPSHOT_DONE, 0L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PREFERENCES_SEQ, 0L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PREFERENCES_HASH, 0L))
                }
                if (incomingHash != null) {
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.INSTANCE_HASH, incomingHash))
                }
                needSnapshot = remoteSyncDao.state(SyncStateKeys.SNAPSHOT_DONE) != 1L
            }
            if (meta == null) {
                // 旧服务端没有 /v1/meta：用 /v1/stats 的 seq 兜底判断「服务端被清空」。
                // 否则 reset 后 App 会静默走旧增量，dirty=0 的记录永远不会再推上去。
                val stats = runCatching { client.stats() }.getOrNull()
                val serverSeq = stats?.seq ?: -1L
                if (stats != null && (serverSeq == 0L || (watermark > 0L && watermark > serverSeq))) {
                    forceUpload = true
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.FULL_UPLOAD_REQUIRED, 1L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PULL_WATERMARK, 0L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.SNAPSHOT_DONE, 0L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PREFERENCES_SEQ, 0L))
                    remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PREFERENCES_HASH, 0L))
                }
            }

            val pushed: PushOutcome
            val pulled: PullOutcome
            if (forceUpload) {
                // 本地优先：先覆盖服务端，再拉回别人独有的数据。顺序不能反——
                // 先拉会把服务端旧版本落地，随后又被本机全量覆盖，白跑一趟。
                pushed = push(pendingBeforePull, uploadAll = true)
                // 整轮全量推送完整成功后才清标记；异常/取消时保持 1，下一轮重试。
                remoteSyncDao.putState(SyncStateRow(SyncStateKeys.FULL_UPLOAD_REQUIRED, 0L))
                if (needSnapshot) {
                    // push 之后序号已经变了，重新读一次 meta 再钉快照的 upTo。
                    val fresh = runCatching { client.meta(includeCollections = true) }.getOrNull() ?: meta
                    pulled = if (fresh != null) {
                        pullSnapshot(fresh, loadPendingSeqs())
                    } else {
                        pull(loadPendingSeqs(), includeSelf)
                    }
                } else {
                    pulled = pull(loadPendingSeqs(), includeSelf)
                }
            } else {
                // 脏账本读两次：拉取前那次决定「跳过哪些本机未上传的改动」，
                // 拉取后那次决定「还剩哪些要推」——落地会清掉一批脏位，沿用旧账本会白白重推一遍。
                pulled = if (needSnapshot && meta != null) {
                    // 快照需要集合清单；日常 meta 不带 collections，避免全表统计。
                    val snapshotMeta = runCatching { client.meta(includeCollections = true) }
                        .getOrNull() ?: meta
                    pullSnapshot(snapshotMeta, pendingBeforePull)
                } else {
                    pull(pendingBeforePull, includeSelf)
                }
                pushed = push(loadPendingSeqs())
            }
            val now = System.currentTimeMillis()
            appPreferences.setCloudLastSyncAt(now)
            appPreferences.setCloudLastConflicts(pushed.conflicts)
            // 坏载荷计数只增不减：本轮水位已经越过它们，下一轮不会再遇到；
            // 若这里直接写 0，用户就再也看不到「有数据没落地」的线索。
            // 重建成功后由 rebuildFromServer 显式清零。
            val skippedPayloads = maxOf(
                pulled.skippedPayloads,
                appPreferences.cloudLastSkippedPayloads()
            )
            appPreferences.setCloudLastSkippedPayloads(skippedPayloads)
            _state.value = _state.value.copy(
                running = false,
                lastError = null,
                lastSyncedAt = now,
                lastConflicts = pushed.conflicts,
                lastSkippedPayloads = skippedPayloads,
                lastTruncated = pulled.truncated,
                phase = null
            )
            RemoteSyncResult(
                pushed = pushed.applied,
                pulled = pulled.downloaded,
                merged = pulled.merged,
                conflicts = pushed.conflicts,
                truncated = pulled.truncated,
                message = if (forceUpload) {
                    "已按本机为准合并上传：" +
                        remoteSyncMessage(pushed.applied, pulled.downloaded, pushed.conflicts, pulled.truncated)
                } else {
                    remoteSyncMessage(
                        pushed.applied,
                        pulled.downloaded,
                        pushed.conflicts,
                        pulled.truncated
                    )
                }
            )
        } catch (e: CancellationException) {
            // 取消要走原始路径：吞掉它会让协程在已取消后继续跑。
            _state.value = _state.value.copy(running = false, phase = null)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "同步失败，请稍后重试"
            _state.value = _state.value.copy(running = false, phase = null, lastError = message)
            RemoteSyncResult(0, 0, 0, message)
        }
    }

    /**
     * 「以本机为准合并上传」：把本机全量记录覆盖到服务端，再拉回其它设备独有的记录。
     * 对账页在「本机与服务端数量/内容对不上」时提供这个动作。
     */
    suspend fun uploadLocalEverything(): RemoteSyncResult =
        syncNow(forceUploadLocal = true)

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
            // 水位按「扫描位置」推进，而不是按本页可见记录的最后一条：
            // 服务端会过滤掉本设备自己写过的行，一页 200 条可能全被过滤而 changes 为空，
            // 但扫描位置其实已经到了 200。继续用旧水位就会永远卡在这批记录前面，
            // 另一台设备之后写的任何新数据都拉不到。
            val advance = if (page.scannedSeq > 0) page.scannedSeq else page.lastSeq
            lastHasMore = page.hasMore
            _state.value = _state.value.copy(
                phase = "正在拉取：第 $pageCount 页，已收 ${landed.size} 条",
                processed = landed.size
            )
            if (advance <= cursor) break
            lastSeq = advance
            cursor = advance
            // 注意：**不要**在 changes 为空时 break——整页可能恰好都是本机记录。
            if (!page.hasMore) break
        }

        // 达到页数上限且服务端仍说还有：把「没拉完」如实带回去，界面提示再同步一次，
        // 而不是让用户以为「已是最新」。
        val truncated = pageCount >= MAX_PULL_PAGES && lastHasMore
        val applied = applyPulledRecords(landed, lastSeq)
        return PullOutcome(
            downloaded = landed.size,
            merged = applied.merged,
            truncated = truncated,
            skippedPayloads = applied.skipped
        )
    }

    /**
     * 首次 / 重建 / 服务端换库：按集合拉服务端当前活数据快照。
     *
     * 与 [pull] 的区别：
     * - 只按集合翻页，不扫墓碑，也不因为本设备自己的写入而多翻空页；
     * - 用 [SyncMetaResponse.seq] 钉住一致点，快照期间其他设备的写入随后由 [pull] 补齐；
     * - 完成后把 `SNAPSHOT_DONE` 置 1，日常同步不再走这条路。
     */
    private suspend fun pullSnapshot(
        meta: SyncMetaResponse,
        pending: Map<String, Map<String, Long>>
    ): PullOutcome {
        val landed = mutableListOf<LandedRecord>()
        var truncated = false
        // 日常 meta 为避免全表统计不返回 collections；统计请求失败时回退到本地固定集合，
        // 多几个空集合请求，但不能因为「没拿到清单」而漏拉数据。
        val collections = meta.collections
            .map { it.collection }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { SyncTables.BY_COLLECTION.keys.toList() }

        collections.forEachIndexed { index, collection ->
            var cursor = 0L
            var pages = 0
            var done = false
            while (pages < MAX_PULL_PAGES) {
                pages++
                val page = client.snapshot(collection, cursor, meta.seq)
                page.changes.forEach { change ->
                    val col = change.collection.ifBlank { collection }
                    // 本机这条也改过且还没推上去：先留本机改动，随后由 push 处理。
                    if (pending[col]?.containsKey(change.key) == true) return@forEach
                    landed += LandedRecord(
                        collection = col,
                        key = change.key,
                        payload = change.payload,
                        deleted = change.deleted,
                        seq = change.seq
                    )
                }
                _state.value = _state.value.copy(
                    phase = "正在拉取快照：${index + 1}/${collections.size} 个集合",
                    processed = landed.size
                )
                if (page.done || page.nextCursor <= cursor) {
                    done = true
                    break
                }
                cursor = page.nextCursor
            }
            if (!done) truncated = true
        }

        // 快照未完整拉完时**不能**标记完成，也不能把水位钉到 meta.seq：
        // 否则截断点之前的剩余记录会被永久跨过。这里保持 SNAPSHOT_DONE=0 与水位不动，
        // 下一轮同步会重新走快照；已落地的记录重复合并是幂等的。
        val applied = applyPulledRecords(
            landed,
            lastSeq = if (truncated) 0L else meta.seq,
            snapshotDone = !truncated
        )
        if (truncated) {
            return PullOutcome(
                downloaded = landed.size,
                merged = applied.merged,
                truncated = true,
                skippedPayloads = applied.skipped
            )
        }
        // 快照只覆盖到 meta.seq；之后的变化用增量补齐，避免漏掉快照期间的并发写入。
        val tail = pull(pending, includeSelf = true)
        return PullOutcome(
            downloaded = landed.size + tail.downloaded,
            merged = applied.merged + tail.merged,
            truncated = tail.truncated,
            skippedPayloads = applied.skipped + tail.skippedPayloads
        )
    }

    /**
     * 把拉回的一批记录重组为一份清单，走既有的合并通道落地，并把水位与记账一起写进同一个事务。
     *
     * 复用而不另写一套合并：那套规则（按 syncId 对齐、较新者胜、墓碑传播、父级重映射、
     * 内置数据补齐）早已为多设备一致调过，服务端同步只是换了个「数据从哪来」的入口。
     */
    private suspend fun applyPulledRecords(
        landed: List<LandedRecord>,
        lastSeq: Long,
        snapshotDone: Boolean = false
    ): ApplyOutcome {
        var merged = 0
        var skipped = 0
        if (landed.isNotEmpty()) {
            val payload = syncRecordsToPayload(
                landed.map { SyncRecord(it.collection, it.key, it.payload, it.deleted) },
                appPreferences.deviceId,
                onSkipped = { skipped++ }
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
        }
        // 关键：合并落地**不能**包在 `appDatabase.withTransaction` 里。
        // `importBackup` 会调用 `seedProtectedData()`，后者切到 Dispatchers.IO 直接拿
        // `openHelper.writableDatabase` 执行 SQL；而 Room 的协程事务已经把连接占住，
        // 另一个线程再去连接池取连接就会与事务线程互等——没有任何异常，同步永远停在
        // 「正在同步…」。海量用户反馈的卡死正是这条路径。
        //
        // 因此顺序改为「先落地（自带幂等），再单独用一个短事务记账」：中途被杀只会让
        // 下次重复拉取同一批（合并幂等），不会出现「水位推进了但数据没落地」。
        appDatabase.withTransaction {
            // 合并通道里的墓碑副作用（`markPurged` 等）会把本机行重新标脏，而这些行的
            // syncId 恰好就在刚拉回的名单里：合并之后再统一清一遍脏并写回服务端序号。
            // 不做这一步，它们会被当成本机新改动反复推上去；叠加「服务端不比对载荷、
            // 每次成功推送都发新序号」，就是两台设备之间序号无限递增的乒乓。
            //
            // 但「本机这版比服务端新」是唯一的例外：合并策略按 updatedAt 保留了本机版本、
            // 跳过了服务端版本。此时若也清脏，本机内容就再也不会被推上去（对账永久差一条）。
            // 用户明确要求「本地优先」——保留脏位，交给本轮推送把本机版本覆盖到服务端。
            landed.forEach { record ->
                val localStamp = remoteSyncDao.localStamp(record.collection, record.key)
                val localNewer = record.collection != SyncCollections.PREFERENCES &&
                    localStamp != null &&
                    localStamp > payloadUpdatedAt(record.payload)
                markLanded(record.collection, record.key, record.seq, keepDirty = localNewer)
            }
            // 落地成功才推进水位：推进早了会让失败那批被永久跨过且毫无提示。
            if (lastSeq > 0) {
                remoteSyncDao.putState(SyncStateRow(SyncStateKeys.PULL_WATERMARK, lastSeq))
            }
            // 快照完成标记与水位同事务写入：下次同步不再重复走全量快照。
            if (snapshotDone) {
                remoteSyncDao.putState(SyncStateRow(SyncStateKeys.SNAPSHOT_DONE, 1L))
            }
            // 首次成功落地＝服务端已开始确认本机数据；写一次 server_ack 标记，
            // 供启动清理据此判断「墓碑可以被物理清除」的前提（P6-d）。
            if (remoteSyncDao.state(SyncStateKeys.SERVER_ACK) == null) {
                remoteSyncDao.putState(SyncStateRow(SyncStateKeys.SERVER_ACK, 1L))
            }
        }
        return ApplyOutcome(merged = merged, skipped = skipped)
    }

    /**
     * 为历史遗留行补 `syncId`：只跑一次，之后由 [SyncStateKeys.SYNC_ID_BACKFILLED] 记住。
     * 直接走 SupportSQLiteDatabase，避免在 Room 事务里再取连接造成互等。
     */
    private suspend fun backfillMissingSyncIdsIfNeeded() {
        if (remoteSyncDao.state(SyncStateKeys.SYNC_ID_BACKFILLED) == 1L) return
        withContext(Dispatchers.IO) {
            val db = appDatabase.openHelper.writableDatabase
            SyncTables.TABLES.distinct().forEach { table ->
                db.execSQL(
                    "UPDATE $table SET syncId = lower(hex(randomblob(16))), dirty = 1, serverSeq = 0 " +
                        "WHERE syncId IS NULL OR syncId = ''"
                )
            }
        }
        remoteSyncDao.putState(SyncStateRow(SyncStateKeys.SYNC_ID_BACKFILLED, 1L))
    }

    /** 按 UTF-8 字节数分批，避免整批 JSON 超过服务端 `PUSH_MAX_BODY_BYTES`。 */
    private fun chunkForPush(records: List<SyncRecord>): List<List<SyncRecord>> {
        val batches = mutableListOf<List<SyncRecord>>()
        var current = mutableListOf<SyncRecord>()
        var bytes = 0
        for (record in records) {
            val size = record.payload.toByteArray(Charsets.UTF_8).size + 160
            if (current.isNotEmpty() &&
                (current.size >= PUSH_LIMIT || bytes + size > PUSH_MAX_BATCH_BYTES)
            ) {
                batches += current
                current = mutableListOf()
                bytes = 0
            }
            current += record
            bytes += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /**
     * 推送本机需要上传的记录（外加恒推的整块设置 `preferences`）。
     *
     * 日常同步只推「本机改过、还没被服务端确认」的行：`dirty = 1` **或** `serverSeq = 0`
     * （后者是历史遗留/从本地备份恢复、从未上过服务端的记录，不补推就永远对不上）。
     * [uploadAll] 为真时改为推送本机全量快照，并带 `force = true` 跳过冲突检查——
     * 只由用户在诊断页主动执行「以本机为准合并上传」触发。
     *
     * 每条带 [PushChange.baseSeq]＝本机上次见到的服务端序号（本地 `serverSeq` 列），
     * 所以「本机自己写过、服务端按设备排除掉」的记录也有正确的进度，不会被一律判成冲突。
     *
     * 撞上冲突按「本地优先」收场（见 [resolveConflicts]）。
     * 服务端单次上限 200 条，故本地先分批再逐批发；一批批推完为止。
     */
    private suspend fun push(
        pending: Map<String, Map<String, Long>>,
        uploadAll: Boolean = false
    ): PushOutcome {
        // 偏好是整块设置、没有 dirty/serverSeq 列；它的序号单独记在 sync_state 里。
        val preferencesSeq = remoteSyncDao.state(SyncStateKeys.PREFERENCES_SEQ)
        // 内容签名：载荷没变就不推。否则每次「回到前台 / 写后」的空同步都会推一次
        // preferences，服务端给它发新号，所有设备的增量水线都要多扫这一行。
        val preferences = backupManager.preferenceSyncRecord()
        val preferencesHash = payloadHash(preferences.payload)
        val preferencesChanged =
            remoteSyncDao.state(SyncStateKeys.PREFERENCES_HASH) != preferencesHash
        // 没有实体 dirty 时只推整块偏好，不再读全库快照：
        // Workers 免费档 CPU/内存都紧，空同步读一遍所有表纯属浪费。
        val hasDirty = pending.values.any { it.isNotEmpty() }
        val records = when {
            uploadAll -> backupManager.snapshotRecords()
            hasDirty -> backupManager.snapshotRecords().filter { record ->
                pending[record.collection]?.containsKey(record.key) == true ||
                    (record.collection == SyncCollections.PREFERENCES && preferencesChanged)
            }
            preferencesChanged -> listOf(preferences)
            else -> emptyList()
        }
        if (records.isEmpty() && uploadAll) {
            // 不能静默返回 0：本机有记录却导不出同步条目，通常是没有 syncId 或载荷解码失败。
            // 这里把数字带进异常，直接显示在同步回执里，不再让用户以为“已是最新”。
            val audits = SyncTables.BY_COLLECTION.keys.mapNotNull { remoteSyncDao.tableAudit(it) }
            val missing = audits.sumOf { it.missingSyncId }
            val live = audits.sumOf { it.live }
            if (missing > 0) {
                throw IllegalStateException(
                    "本机有 $missing 条记录缺少 syncId，无法上传；请保留数据并反馈这个数字"
                )
            }
            if (live > 0) {
                throw IllegalStateException(
                    "本机有 $live 条记录，但无法生成同步载荷（可能载荷损坏）；请先导出本地备份"
                )
            }
        }
        if (records.isEmpty()) return PushOutcome(applied = 0, conflicts = 0)

        var applied = 0
        var conflicts = 0
        // 按 UTF-8 字节数分批：固定 100 条在 payload 较大时会超过服务端 2MB 请求体上限，
        // 整批被 413 拒绝，表现就是“一条都没上传”。
        val batches = chunkForPush(records)
        batches.forEachIndexed { index, batch ->
            _state.value = _state.value.copy(
                phase = "正在上传：第 ${index + 1}/${batches.size} 批，已上传 $applied 条",
                processed = applied
            )
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
                        deleted = record.deleted,
                        force = uploadAll
                    )
                }
            )
            // 服务端已写下的：把序号记回本机并清脏。服务端对「载荷完全相同」的条目
            // 不再发号，而是回原序号，所以清脏同样成立，且不会产生序号增长。
            response.applied.forEach { markLanded(it.collection, it.key, it.seq) }
            applied += response.applied.count { !it.noop }
            if (response.applied.any { it.collection == SyncCollections.PREFERENCES }) {
                remoteSyncDao.putState(
                    SyncStateRow(SyncStateKeys.PREFERENCES_HASH, preferencesHash)
                )
            }
            conflicts += resolveConflicts(batch, response.conflicts)
        }
        return PushOutcome(applied = applied, conflicts = conflicts)
    }

    /**
     * 推送冲突的收场：**本地优先**，同时不让整块偏好被旧版本盖掉。
     *
     * 冲突本身只说明「服务端比我上次见到的新」，不说明服务端那版内容更晚。用户明确要求
     * 「本地优先」：只要本机还有未上传的改动（dirty=1 或 serverSeq=0），就把本机版本
     * 带着服务端当前序号重推一次，让服务端与其它设备最终收敛到本机这版——多个设备各自的
     * 数据因此都能合并进服务端，而不是被某个先推的旧版本永久占位。
     *
     * 例外是整块偏好 `preferences`：它没有逐条 dirty 位、每次同步都恒推，若也无条件本地胜，
     * 一台没改过设置的设备就会把另一台刚改的设置整块盖回去。故偏好仍按载荷的 `updatedAt`
     * 比较，只有本机确实更晚才重推。
     *
     * 重推期间又被别人改了（再撞冲突）**不覆盖本机**：保留脏位、留痕为
     * [ConflictLogEntry.RESOLUTION_PENDING]，下一轮同步继续以本机版本重推。
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
            val sameContent = local != null &&
                conflict.payload == local.payload &&
                conflict.deleted == local.deleted
            when {
                // 两版一模一样：只是序号落后，对齐即可。
                sameContent -> markLanded(conflict.collection, conflict.key, conflict.seq)
                // 本地没有对应记录（不应发生）：只能采用服务端版本。
                local == null -> {
                    serverWins += conflict
                    diverged++
                }
                // 整块偏好：只有本机确实改得更晚才本地胜，避免旧设置盖掉新设置。
                conflict.collection == SyncCollections.PREFERENCES -> {
                    if (payloadUpdatedAt(local.payload) > payloadUpdatedAt(conflict.payload)) {
                        localWins += conflict to local
                        diverged++
                    } else {
                        serverWins += conflict
                        diverged++
                    }
                }
                // 逐条实体：本地优先。本机这版尚未被服务端确认，就送上去。
                else -> {
                    localWins += conflict to local
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
            // 重推又撞冲突：保持本机脏位，不落地服务端版本；下一轮同步再试。
            // 这里必须显式写回 serverSeq 并保留 dirty —— 本机记录可能原本是
            // `dirty=0, serverSeq=0` 的补推记录，不标脏下一轮就不会再推，本机版本
            // 会永远停在本地。写入冲突序号后，下一轮 baseSeq 与服务端对齐即可直写。
            val stillConflicting = retry.conflicts
                .map { it.collection to it.key }
                .toSet()
            val localWinsByKey = localWins.associate { (conflict, _) ->
                (conflict.collection to conflict.key) to conflict
            }
            retry.conflicts.forEach { conflict ->
                markLanded(conflict.collection, conflict.key, conflict.seq, keepDirty = true)
            }
            val retrySeq = retry.applied.associate { (it.collection to it.key) to it.seq }
            conflictStore.append(localWins.map { (conflict, local) ->
                val stillPending = (conflict.collection to conflict.key) in stillConflicting
                ConflictLogEntry(
                    collection = conflict.collection,
                    key = conflict.key,
                    seq = retrySeq[conflict.collection to conflict.key]
                        ?: localWinsByKey[conflict.collection to conflict.key]?.seq
                        ?: conflict.seq,
                    baseSeq = conflict.baseSeq,
                    localPayload = local.payload,
                    remotePayload = conflict.payload,
                    at = System.currentTimeMillis(),
                    updatedBy = conflict.updatedBy,
                    resolution = if (stillPending) {
                        ConflictLogEntry.RESOLUTION_PENDING
                    } else {
                        ConflictLogEntry.RESOLUTION_LOCAL
                    }
                )
            })
        }

        if (serverWins.isNotEmpty()) landConflicts(batch, serverWins)
        return diverged
    }

    /**
     * 记下「这一条已在服务端」：写回服务端序号；[keepDirty] 为真时保留脏位。
     *
     * 整块设置另把序号存进 sync_state。`keepDirty` 只对逐条实体有意义：
     * 拉取时发现本机这版更新、合并策略跳过了服务端版本，就保留脏位让本轮推送覆盖服务端。
     */
    private suspend fun markLanded(
        collection: String,
        key: String,
        seq: Long,
        keepDirty: Boolean = false
    ) {
        remoteSyncDao.markSynced(collection, key, seq, dirty = keepDirty)
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
        // 同 applyPulledRecords：importBackup 会跨线程直接使用 SQLite，必须放在 Room
        // 事务之外；记账本身很短，单独一个事务即可。
        appDatabase.withTransaction {
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
        // 重建的语义是「以服务端为准」：把当前本机偏好的签名先写进 sync_state，
        // 让随后那轮同步不要因为「偏好哈希为空」而把本机偏好推回服务端。
        // 偏好本身会随快照从服务端拉回，由既有的合并规则落地。
        remoteSyncDao.putState(
            SyncStateRow(
                SyncStateKeys.PREFERENCES_HASH,
                payloadHash(backupManager.preferenceSyncRecord().payload)
            )
        )

        val result = syncNow(includeSelf = true)
        val error = _state.value.lastError
        if (error != null) {
            RebuildOutcome(
                success = false,
                message = "重建中断：$error。本机数据可能不完整，可用「撤销重建」恢复重建前的备份。",
                undoZipPath = undoFile.path
            )
        } else {
            appPreferences.setCloudLastSkippedPayloads(0)
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

        /** 单批请求体的保守字节上限：服务端 `PUSH_MAX_BODY_BYTES` 是 2MB，留出 JSON 包装余量。 */
        const val PUSH_MAX_BATCH_BYTES = 1_500_000

        /** 一次同步最多翻这么多页，避免服务端异常时无限翻页（200 × 100 = 2 万条）。 */
        const val MAX_PULL_PAGES = 100

        /** 一轮同步的总超时：到点中止并给出可读回执，不让界面无限停在「正在同步…」。 */
        const val SYNC_TIMEOUT_MS = 5 * 60 * 1000L
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

/** 落地结果：合并条数与因载荷无法解析而跳过的条数。 */
private data class ApplyOutcome(val merged: Int, val skipped: Int)

/** 拉取的汇总：本轮落地条数与合并计数。 */
private data class PullOutcome(
    val downloaded: Int,
    val merged: Int,
    val truncated: Boolean = false,
    /** 本轮因载荷无法解析而跳过的记录数；非 0 表示有数据没能落地。 */
    val skippedPayloads: Int = 0
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
        if (conflicts > 0) append("，$conflicts 条两边都改过，已按本地优先合并")
        if (truncated) append("；还有更多变更未拉取，请再次同步")
    }
}

/**
 * 载荷内容签名（SHA-256 前 8 字节），用于判断整块设置是否真的变了。
 *
 * 只用来做「有没有变」的比较，不用于安全场景，取前 8 字节足够；
 * `SyncStateRow.value` 是 Long，正好放得下。
 */
private fun payloadHash(payload: String): Long {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
    return value
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
