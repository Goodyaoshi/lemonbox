package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.util.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 图片云端同步的进度与结果。 */
data class ImageCloudSyncState(
    val running: Boolean = false,
    /** 本次待处理的图片总数（已按内容去重）。 */
    val total: Int = 0,
    val uploaded: Int = 0,
    /** 云端已存在、无需重传的数量——这就是「避免重复迁移」的可观测证据。 */
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentFile: String? = null,
    val lastError: String? = null,
    val lastFinishedAt: Long? = null
) {
    val processed: Int get() = uploaded + skipped + failed

    val fraction: Float
        get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** 单张图片的处理结果，用于把「新传上去」与「云端本来就有」分开计数。 */
private sealed interface UploadResult {
    data object Uploaded : UploadResult
    data object AlreadyExists : UploadResult
    data class Failed(val message: String) : UploadResult
}

/** 待同步的一张图：本地文件、内容哈希、对象键，以及引用它的物品位置。 */
private data class ImageToSync(
    val file: File,
    val hash: String,
    val objectKey: String,
    /**
     * 引用这张图的物品位置（物品 id + 图片下标）。
     *
     * 上传按**内容**去重（同一张图只传一次），但回写要按**位置**——同一份内容可能被多个
     * 物品/多个槽位引用，只记首个引用会让其余位置永远显示未上传。
     */
    val references: List<ImageReference>
) {
    data class ImageReference(val itemId: Long, val index: Int)
}

/**
 * 图片云端同步编排（F20）。
 *
 * 幂等来自「对象键 = 图片内容的 SHA-256」（见 [ImageObjectKeys]）：重复同步、多台设备
 * 同时同步同一张图，算出的键都相同，于是先 HEAD 探在、命中即跳过，不会重传或产生副本。
 * 判断依据是内容而非「是否传过」的标记位，故本地无需记录状态、也不需要数据库迁移。
 *
 * [knownRemoteKeys] 以持久化账本 [ImageCloudUploadLedger] 为底，重装重启后仍记得哪些传过；
 * 增量省下的是网络请求（账本里的键连 HEAD 都不发），本地仍要算一遍哈希。
 */
@Singleton
class ImageCloudSyncManager @Inject constructor(
    private val config: ImageCloudConfig,
    private val preferences: ImageCloudPreferences,
    private val credentialStore: ImageCredentialStore,
    private val client: S3ImageClient,
    private val itemRepository: ItemRepository,
    private val ledger: ImageCloudUploadLedger,
    private val restoreStore: ImageRestoreStore
) : ImageCloudPurger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(ImageCloudSyncState())
    val state: StateFlow<ImageCloudSyncState> = _state.asStateFlow()

    /** 已确认存在于云端的对象键；以持久化账本为初值（见类注释）。 */
    private val knownRemoteKeys = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(ledger.keys())
    }

    /**
     * 测试连通性。允许传入尚未保存的凭据，这样「填完就点测试」不必先落盘；
     * 未传时用本地已保存的那份。刻意不检查开关状态——用户往往先验证再开启。
     */
    suspend fun testConnection(draft: ImageCredentials? = null): S3Outcome =
        withContext(Dispatchers.IO) {
            if (!config.hasConnectionConfig()) {
                return@withContext S3Outcome.Error("请先补齐存储地址与存储桶")
            }
            val credentials = draft ?: credentialStore.load()
            if (credentials == null || !credentials.isComplete) {
                return@withContext S3Outcome.Error("请先填写访问凭据")
            }
            client.checkBucket(
                S3Config(
                    endpoint = config.endpoint(),
                    region = config.region(),
                    bucket = config.bucket(),
                    accessKey = credentials.accessKey,
                    secretKey = credentials.secretKey
                )
            )
        }

    /**
     * 同步全部家当图片（存量迁移入口）。重复触发无副作用：已在云端的会被跳过。
     * 确认云端已有后即删除本地副本——图片以云端为唯一来源，本地不再长期保留。
     */
    fun syncAll() = launchSync { loadAllImages() }

    /**
     * 同步单个物品的图片（保存后自动上传的入口）。
     *
     * 这是「不该打扰用户」的路径：开关关闭、未就绪、或图片为空时都静默跳过，
     * 不弹任何提示——用户此刻关心的是「东西存好了」，不是云端有没有传上去。
     * 没传成功的会在下次手动全量同步时补上。
     */
    fun syncItem(itemId: Long) {
        if (!preferences.autoUpload.value) return
        launchSync {
            val detail = runCatching { itemRepository.getItemDetailById(itemId).first() }.getOrNull()
            if (detail == null) emptyList() else collectImages(config.prefix(), listOf(detail))
        }
    }

    /** 取消进行中的同步；已上传的对象保留，下次接着来即可。 */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, currentFile = null)
    }

    private fun launchSync(
        loader: suspend () -> List<ImageToSync>
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            val readiness = config.readiness()
            val s3 = config.resolve()
            if (!readiness.ready || s3 == null) {
                _state.value = ImageCloudSyncState(
                    running = false,
                    lastError = readiness.firstBlocker ?: "图片云端存储尚未就绪"
                )
                job = null
                return@launch
            }
            try {
                performSync(s3, loader())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failRun(e.message ?: "同步失败")
            } finally {
                job = null
            }
        }
    }

    /**
     * 共同内核：逐张探在 + 按需上传，全程维护 [state]。
     * UI 触发的 [syncAll] / [syncItem] 与后台补传 [syncPendingNow] 都走这里，
     * 两边的进度口径与幂等行为因此完全一致，不会各写一套而漂移。
     *
     * 云端确认已有该图后即删除本地副本（见 [deleteLocalIfSafe]）：图片以云端为唯一来源，
     * 本地不再长期保留。删除受三道闸门保护，任一不过就保留，因此不会丢图。
     */
    private suspend fun performSync(
        s3: S3Config,
        images: List<ImageToSync>
    ): SyncRunResult {
        _state.value = ImageCloudSyncState(running = true, total = images.size)
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var lastError: String? = null
        // 删除前要判断「该路径是否被别的槽位共用」，故先统计一次引用。
        val pathRefCounts = loadPathRefCounts()

        for (image in images) {
            _state.value = _state.value.copy(currentFile = image.file.name)
            var confirmedOnCloud = false
            when (val result = uploadIfNeeded(s3, image.objectKey, image.hash, image.file)) {
                UploadResult.Uploaded -> {
                    uploaded++
                    confirmedOnCloud = true
                }

                UploadResult.AlreadyExists -> {
                    skipped++
                    // 云端本来就有：同样要补记键，否则每次同步都会重探这张图。
                    confirmedOnCloud = true
                }

                is UploadResult.Failed -> {
                    failed++
                    lastError = result.message
                }
            }
            // 只有云端确实拿到了这张图，才有资格动本地副本。
            if (confirmedOnCloud) {
                writeBackObjectKeys(image)
                deleteLocalIfSafe(s3, image, pathRefCounts)
            }
            _state.value = _state.value.copy(
                uploaded = uploaded,
                skipped = skipped,
                failed = failed,
                lastError = lastError
            )
        }

        _state.value = _state.value.copy(
            running = false,
            currentFile = null,
            lastFinishedAt = System.currentTimeMillis()
        )
        return SyncRunResult.Done(uploaded, skipped, failed)
    }

    /** 统计每个本地图片路径被多少个图片槽位引用，供 [deleteLocalIfSafe] 判断。 */
    private suspend fun loadPathRefCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        runCatching {
            // 用含软删物品的快照：回收站里的物品可能被恢复，它的图片不能被删掉。
            itemRepository.getAllItemsSnapshot()
                .flatMap { it.imagePathList() }
                .groupingBy { it }
                .eachCount()
        }.getOrDefault(emptyMap())
    }

    /**
     * 云端确认已有这张图后，删除本地副本。
     *
     * 三道闸门，任一不过就保留本地副本（宁可多占一点空间，也不能丢图）：
     *  1. 该路径若被多个图片槽位引用（同一张图可能被选给多件物品），删掉会让别的图一起消失；
     *  2. 路径不在引用统计里（数据异常），保守不删；
     *  3. **云端真的读得回来**——见 [S3ImageClient.verifyPresignedReadable]。
     *     上传与读取是两条不同的签名路径，不先验证就删等于拿用户的照片赌一个未验证的假设。
     *
     * 删除后物品的 `imagePaths` 仍保留这个路径（指向已不存在的文件）、对象键原样保留，
     * 因此下标不会错位；读取时发现本地文件不在，自动改用云端取图。
     */
    private suspend fun deleteLocalIfSafe(
        s3: S3Config,
        image: ImageToSync,
        pathRefCounts: Map<String, Int>
    ) {
        val path = image.file.absolutePath
        val refCount = pathRefCounts[path] ?: return
        if (refCount > 1) return
        val readable = withContext(Dispatchers.IO) {
            client.verifyPresignedReadable(s3, image.objectKey)
        }
        if (!readable) return
        ImageUtil.deleteImage(path)
    }

    private fun failRun(message: String) {
        _state.value = _state.value.copy(
            running = false,
            currentFile = null,
            lastError = message,
            lastFinishedAt = System.currentTimeMillis()
        )
    }

    /**
     * 把「这张图在云端的存在」落到引用它的每个物品位置上。
     *
     * 先读回物品、把键写进对应下标、再用 [Item.withImages] 保证与图片列表等长，
     * 最后由仓库只更新键列（不刷新 updatedAt）。
     * 单个物品写失败不影响其他物品，也不影响整体同步结果——图片本身已经安全上传了。
     */
    private suspend fun writeBackObjectKeys(image: ImageToSync) {
        image.references.groupBy { it.itemId }.forEach { (itemId, positions) ->
            runCatching {
                val item = itemRepository.getItemById(itemId) ?: return@forEach
                val paths = item.imagePathList()
                val keys = item.imageObjectKeyList().toMutableList()
                var changed = false
                positions.forEach { reference ->
                    if (reference.index in keys.indices && keys[reference.index] != image.objectKey) {
                        keys[reference.index] = image.objectKey
                        changed = true
                    }
                }
                // 逐张图上传时会多次命中同一物品；没有实际变化就不写库，省掉无谓的写。
                if (changed) {
                    itemRepository.updateImageObjectKeys(item.withImages(paths, keys))
                }
            }
        }
    }

    /** 探在 + 按需上传。已在云端时直接返回 [UploadResult.AlreadyExists]，不重传。 */
    private suspend fun uploadIfNeeded(
        s3: S3Config,
        key: String,
        hash: String,
        file: File
    ): UploadResult {
        if (knownRemoteKeys.contains(key)) return UploadResult.AlreadyExists

        return when (val head = client.headObject(s3, key)) {
            S3Outcome.Success -> {
                markKnown(key)
                UploadResult.AlreadyExists
            }

            S3Outcome.NotFound -> when (val put = client.putObject(s3, key, hash, file)) {
                S3Outcome.Success -> {
                    markKnown(key)
                    UploadResult.Uploaded
                }

                is S3Outcome.Error -> UploadResult.Failed(put.message)
                S3Outcome.NotFound -> UploadResult.Failed("上传被拒绝")
            }

            is S3Outcome.Error -> UploadResult.Failed(head.message)
        }
    }

    private suspend fun loadAllImages(): List<ImageToSync> {
        val details = runCatching { itemRepository.getAllItems().first() }.getOrDefault(emptyList())
        return collectImages(config.prefix(), details)
    }

    /**
     * 收集现存图片：按**内容哈希**归并，同一份内容只产出一条待上传项，
     * 这正是「同一张图在多次 / 多设备同步中不重复」的根据；
     * 同时把「哪些物品的哪一张图用了这份内容」全部记进 [ImageToSync.references]，
     * 供上传成功后逐个位置回写对象键——只记首个引用会让其余位置永远显示未上传。
     *
     * 哈希在这里算好并随 [ImageToSync] 带走，后续不再重复计算。
     */
    private suspend fun collectImages(
        prefix: String,
        details: List<ItemDetail>
    ): List<ImageToSync> = withContext(Dispatchers.IO) {
        val fileByHash = HashMap<String, File>()
        val referencesByHash = LinkedHashMap<String, MutableList<ImageToSync.ImageReference>>()
        for (detail in details) {
            val itemId = detail.item.id
            detail.item.imagePathList().forEachIndexed { index, path ->
                val file = File(path)
                if (!file.isFile) return@forEachIndexed
                val hash = runCatching { sha256Hex(file) }.getOrNull() ?: return@forEachIndexed
                fileByHash[hash] = file
                referencesByHash.getOrPut(hash) { mutableListOf() } +=
                    ImageToSync.ImageReference(itemId, index)
            }
        }
        referencesByHash.map { (hash, references) ->
            ImageToSync(
                file = fileByHash.getValue(hash),
                hash = hash,
                objectKey = ImageObjectKeys.forContentHash(prefix, hash),
                references = references
            )
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 登记「云端已有该对象」：内存缓存 + 持久化账本一起更新。 */
    private fun markKnown(key: String) {
        if (knownRemoteKeys.add(key)) ledger.addAll(listOf(key))
    }

    /**
     * 遗忘全部「已确认」记录。换了存储地址 / 存储桶 / 前缀或凭据后必须调用：
     * 那些键属于旧目标，继续信任会让补传误判成「都传过了」而漏掉真正该传的图。
     * 界面会退化为「先探在再上传」，只是多一轮 HEAD，不会丢图。
     */
    fun forgetKnownKeys() {
        knownRemoteKeys.clear()
        ledger.clear()
    }

    /**
     * 清理已无引用的云端对象（物品被永久删除后调用）。
     *
     * 为什么需要：本地副本在上传成功时就被删掉了，云端成了唯一副本；物品永久删除后，
     * 它的对象就成了孤儿，不清理只会越积越多。
     *
     * 为什么先算引用：对象键由内容哈希推导，同一张图可能被多件物品、多个槽位共用，
     * 直接删会误伤仍在使用的图片。只有确认全库再无任何位置引用该键时才删。
     *
     * 失败与未就绪都静默跳过：物品删除本身已经完成，云端清理失败不该反过来阻断用户操作，
     * 留下的孤儿下次再清（对象键持久化在物品行里，不会失去线索）。
     */
    override suspend fun purgeCloudObjects(candidateKeys: Collection<String>) =
        purgeCloudObjectsInternal(candidateKeys)

    private suspend fun purgeCloudObjectsInternal(candidateKeys: Collection<String>) {
        val keys = candidateKeys.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return
        val s3 = config.resolve() ?: return
        withContext(Dispatchers.IO) {
            // 用**含软删物品**的全量快照统计引用：回收站里的物品随时可能被恢复，
            // 若把它们排除在外，就会出现「删掉回收站里另一个物品还在用的图」——
            // 那个物品一旦恢复就成了没有图的空壳。
            // 读不出全库引用时宁可保留孤儿，也不冒误删在用的图的风险。
            val stillReferenced = try {
                itemRepository.getAllItemsSnapshot()
                    .flatMap { it.imageObjectKeyList() }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (e: CancellationException) {
                // 页面/任务被取消时不吞掉取消信号，否则删除循环会继续跑下去。
                throw e
            } catch (e: Exception) {
                return@withContext
            }

            for (key in purgeableCloudKeys(keys, stillReferenced)) {
                when (client.deleteObject(s3, key)) {
                    // S3 对不存在的键也返回成功，NotFound 同样算已达成删除意图。
                    is S3Outcome.Success, S3Outcome.NotFound -> {
                        knownRemoteKeys.remove(key)
                        ledger.removeAll(listOf(key))
                        // 云端对象已删，本地回填副本随之作废，一并清掉免得白占空间。
                        restoreStore.delete(key)
                    }
                    // 删除失败：保留账本记录，下次再试。
                    else -> Unit
                }
            }
        }
    }

    /**
     * 后台补传：就地执行（不占用 UI 的 [job]），返回本次结果供 WorkManager 决定是否重试。
     * 未就绪时返回 [SyncRunResult.NotReady] 而不抛异常——配置缺失不该让后台任务反复重试。
     */
    suspend fun syncPendingNow(): SyncRunResult {
        val s3 = config.resolve() ?: return SyncRunResult.NotReady
        return try {
            performSync(s3, loadAllImages())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failRun(e.message ?: "同步失败")
            SyncRunResult.Done(uploaded = 0, skipped = 0, failed = 1)
        }
    }

    /**
     * 未上传张数（本地算哈希后与账本比对，**不发任何网络请求**）。
     * 未就绪时返回 0：功能没开就不该在界面上吓唬用户。
     */
    suspend fun countPendingUploads(): Int = withContext(Dispatchers.IO) {
        if (!config.readiness().ready) return@withContext 0
        loadAllImages().count { image -> image.objectKey !in knownRemoteKeys }
    }
}

/** 一次同步的结果；[Done.allSucceeded] 供后台任务判断要不要重试。 */
sealed interface SyncRunResult {
    /** 配置未就绪（未开启 / 缺凭据 / debug 包），本次什么都没做。 */
    data object NotReady : SyncRunResult

    data class Done(val uploaded: Int, val skipped: Int, val failed: Int) : SyncRunResult {
        val allSucceeded: Boolean get() = failed == 0
    }
}

/**
 * 从候选键里筛出**确认无人引用**、可以安全删除的键。
 *
 * 抽成不依赖 Android 的顶层纯函数以便单测：这是防误删的唯一一道判断，写错的表现是
 * 「删掉别的物品还在用的图」（对象键由内容哈希推导，同一张图可能挂在多件物品上），
 * 且不可逆。空串是「该槽位未上传」的占位，重复项来自同一张图被多个槽位引用，
 * 两者都在这里滤掉，避免拿它们去发 DELETE 请求。
 */
internal fun purgeableCloudKeys(
    candidates: Collection<String>,
    stillReferenced: Set<String>
): List<String> = candidates
    .filter { it.isNotBlank() }
    .distinct()
    .filterNot { it in stillReferenced }
