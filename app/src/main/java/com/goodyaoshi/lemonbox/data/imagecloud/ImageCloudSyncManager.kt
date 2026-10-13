package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
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
     * 为什么需要它：上传按**内容**去重（同一张图只传一次），但回写要按**位置**——
     * 同一份内容可能同时被多个物品、或多个图片槽位引用，每个位置都得记上对象键，
     * 否则只有"第一个碰到"的那张能记住、其余仍是未上传。
     */
    val references: List<ImageReference>
) {
    data class ImageReference(val itemId: Long, val index: Int)
}

/**
 * 图片云端同步编排（F20）。
 *
 * 幂等设计（对应「需要考虑现存图片迁移，避免重复迁移」）：
 * 对象键由图片**内容的 SHA-256** 决定（见 [ImageObjectKeys]），于是
 *  1. 同一张图重复同步，键相同 → 先 HEAD 探在，命中即跳过，不会重传；
 *  2. 多台设备各自同步，同一张图算出同一个键 → 落到同一对象，不会各存一份；
 *  3. 两台设备并发上传同一张图，也只是同键幂等覆盖，不产生副本。
 * 判断依据是「内容」而不是「是否传过」的标记位，本地无需记录任何状态，
 * 因此本次功能**不需要数据库迁移**。
 *
 * [knownRemoteKeys] 以持久化账本 [ImageCloudUploadLedger] 为底：重装或重启后仍然记得
 * 哪些图传过，于是「补传」只需处理真正缺的那些，不会把整库重新探一遍。
 * 增量省下的是**网络请求**——账本里已有的键连 HEAD 都不发；
 * 本地仍要读一遍文件算哈希（对象键由内容决定，这是无法回避的代价）。
 */
@Singleton
class ImageCloudSyncManager @Inject constructor(
    private val config: ImageCloudConfig,
    private val preferences: ImageCloudPreferences,
    private val credentialStore: ImageCredentialStore,
    private val client: S3ImageClient,
    private val itemRepository: ItemRepository,
    private val ledger: ImageCloudUploadLedger
) {

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

    /** 同步全部家当图片（存量迁移入口）。重复触发无副作用：已在云端的会被跳过。 */
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

    private fun launchSync(loader: suspend () -> List<ImageToSync>) {
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
     */
    private suspend fun performSync(s3: S3Config, images: List<ImageToSync>): SyncRunResult {
        _state.value = ImageCloudSyncState(running = true, total = images.size)
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var lastError: String? = null

        for (image in images) {
            _state.value = _state.value.copy(currentFile = image.file.name)
            when (val result = uploadIfNeeded(s3, image.objectKey, image.hash, image.file)) {
                UploadResult.Uploaded -> {
                    uploaded++
                    writeBackObjectKeys(image)
                }

                UploadResult.AlreadyExists -> {
                    skipped++
                    // 云端本来就有：同样要补记键，否则每次同步都会重探这张图。
                    writeBackObjectKeys(image)
                }

                is UploadResult.Failed -> {
                    failed++
                    lastError = result.message
                }
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
