package com.goodyaoshi.lemonbox.data.imagecloud

import android.content.Context
import com.goodyaoshi.lemonbox.data.local.entity.Item
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.ItemRepository
import com.goodyaoshi.lemonbox.util.ImageUtil
import dagger.hilt.android.qualifiers.ApplicationContext
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

/** 待同步的一张图：本地文件、内容哈希（对象键由哈希推导，不再单独存）。 */
private data class ImageToSync(
    val file: File,
    val hash: String,
    val objectKey: String
)

/**
 * 图片云端同步编排（F22）。
 *
 * 幂等来自「对象键 = 图片内容的 SHA-256」（见 [ImageObjectKeys]）：重复同步、多台设备
 * 同时同步同一张图，算出的键都相同，于是先探在、命中即跳过，不会重传或产生副本。
 *
 * **不需要回写**：物品的图片槽位只存内容哈希（见 [Item.imageHashes]），对象键由哈希
 * 与服务端前缀推导。早先「上传成功后把对象键写回数据库」这一步因此整个消失——
 * 库里那个哈希本来就够用，这正是「三列合并为一列」的直接收益。
 *
 * [knownRemoteKeys] 以持久化账本 [ImageCloudUploadLedger] 为底，重装重启后仍记得哪些传过；
 * 增量省下的是网络请求（账本里的键连 HEAD 都不发），本地仍要按文件名读出哈希。
 */
@Singleton
class ImageCloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ImageCloudConfig,
    private val preferences: ImageCloudPreferences,
    private val gateway: BlobGateway,
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
            if (!readiness.ready) {
                _state.value = ImageCloudSyncState(
                    running = false,
                    lastError = readiness.firstBlocker ?: "图片上传尚未就绪"
                )
                job = null
                return@launch
            }
            try {
                performSync(loader())
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
        images: List<ImageToSync>
    ): SyncRunResult {
        _state.value = ImageCloudSyncState(running = true, total = images.size)
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var lastError: String? = null
        // 删除前要判断「该内容是否被别的槽位共用」，故先统计一次引用。
        val hashRefCounts = loadHashRefCounts()

        for (image in images) {
            _state.value = _state.value.copy(currentFile = image.file.name)
            var confirmedOnCloud = false
            when (val result = uploadIfNeeded(image.objectKey, image.file)) {
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
            // 不再需要回写对象键：键由哈希推导，库里那个哈希本来就够用。
            if (confirmedOnCloud) {
                deleteLocalIfSafe(image, hashRefCounts)
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

    /** 统计每个内容哈希被多少个图片槽位引用，供 [deleteLocalIfSafe] 判断。 */
    private suspend fun loadHashRefCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        runCatching {
            // 用含软删物品的快照：回收站里的物品可能被恢复，它的图片不能被删掉。
            itemRepository.getAllItemsSnapshot()
                .flatMap { it.imageHashList() }
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
     *  3. **云端真的读得回来**——见 [BlobGateway.verifyReadable]。
     *     上传与读取是两条不同的链路，不先验证就删等于拿用户的照片赌一个未验证的假设。
     *
     * 删除后物品的图片槽位（哈希）原样保留，因此主图与顺序都不会变；
     * 读取时发现本地文件不在，自动改用云端取图。
     */
    private suspend fun deleteLocalIfSafe(
        image: ImageToSync,
        hashRefCounts: Map<String, Int>
    ) {
        val refCount = hashRefCounts[image.hash] ?: return
        if (refCount > 1) return
        val readable = gateway.verifyReadable(image.objectKey)
        if (!readable) return
        ImageUtil.deleteImage(image.file.absolutePath)
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
     * 把某个物品的**历史遗留槽位**归一成内容哈希，并同步重命名本地文件（F22）。
     *
     * 为什么需要：本方案落地前的槽位存的是「本地文件名」，而迁移无法读文件内容
     * （迁移只有数据库、拿不到 Context），因此那时算不出哈希、只能原样保留。
     * 这里补上这一步——算出哈希、把文件重命名为 `item_<hash>.jpg`、再改写槽位。
     *
     * 归一之后所有槽位都是哈希，本地查找与对象键推导才能走同一条路。
     * 文件已不在（丢过 / 只在云端）时原样保留槽位，不猜、不丢。
     */
    private suspend fun normalizeLegacySlots(item: Item): List<String> {
        val slots = item.imageHashList()
        if (slots.isEmpty() || slots.all { ImageUtil.isContentHash(it) }) return slots

        val normalized = slots.map { slot ->
            if (ImageUtil.isContentHash(slot)) return@map slot
            val legacyFile = ImageUtil.findLocalFile(context, slot) ?: return@map slot
            val hash = runCatching { sha256Hex(legacyFile) }.getOrNull() ?: return@map slot
            ImageUtil.saveImageByContent(context, legacyFile, hash)
            legacyFile.delete()
            hash
        }
        if (normalized != slots) {
            itemRepository.updateImageHashes(item.withImageHashes(normalized))
        }
        return normalized
    }

    /** 探在 + 按需上传。已在云端时直接返回 [UploadResult.AlreadyExists]，不重传。 */
    private suspend fun uploadIfNeeded(
        key: String,
        file: File
    ): UploadResult {
        if (knownRemoteKeys.contains(key)) return UploadResult.AlreadyExists

        val probe = gateway.probe(key)
        return when {
            probe.exists -> {
                markKnown(key)
                UploadResult.AlreadyExists
            }

            // 404 才是「云端确实没有」；其余状态（含 0 = 请求没走通）一律按失败处理，
            // 绝不把「探测不了」当成「没有」去覆盖——那可能误传，更可能误删本地副本。
            probe.status == HTTP_NOT_FOUND -> when (val put = gateway.upload(key, file)) {
                S3Outcome.Success -> {
                    markKnown(key)
                    UploadResult.Uploaded
                }

                is S3Outcome.Error -> UploadResult.Failed(put.message)
                S3Outcome.NotFound -> UploadResult.Failed("上传被拒绝")
            }

            else -> UploadResult.Failed(probe.message ?: "探测云端失败（${probe.status}）")
        }
    }

    private suspend fun loadAllImages(): List<ImageToSync> {
        val details = runCatching { itemRepository.getAllItems().first() }.getOrDefault(emptyList())
        return collectImages(config.prefix(), details)
    }

    /**
     * 收集现存图片：按**内容哈希**归并，同一份内容只产出一条待上传项，
     * 这正是「同一张图在多次 / 多设备同步中不重复」的根据。
     *
     * 哈希优先从文件名解析（F22）：落盘已改为 `item_<sha256>.jpg`，文件名就是哈希，
     * 不必再为每张图读盘算一遍。历史文件（旧 UUID 命名）解析不出时才回退读盘，
     * 因此无需一次性迁移旧数据。
     */
    private suspend fun collectImages(
        prefix: String,
        details: List<ItemDetail>
    ): List<ImageToSync> = withContext(Dispatchers.IO) {
        // 先归一历史遗留槽位，之后所有槽位都是内容哈希：
        // 本地文件按哈希找、对象键按哈希推导，两条路一致。
        val slotsByItem = details.associate { it.item.id to normalizeLegacySlots(it.item) }
        // 按内容归并：同一张图只产出一条待上传项（这正是「不重复上传」的根据）。
        val fileByHash = LinkedHashMap<String, File>()
        for (detail in details) {
            slotsByItem[detail.item.id].orEmpty().forEach { hash ->
                val file = ImageUtil.findLocalFile(context, hash) ?: return@forEach
                fileByHash.putIfAbsent(hash, file)
            }
        }
        fileByHash.map { (hash, file) ->
            ImageToSync(
                file = file,
                hash = hash,
                objectKey = ImageObjectKeys.forContentHash(prefix, hash)
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
     * 留下的孤儿下次再清（对象键由槽位哈希推导，线索一直留在物品行里）。
     */
    override suspend fun purgeCloudObjects(candidateKeys: Collection<String>) =
        purgeCloudObjectsInternal(candidateKeys)

    /**
     * 永久删除物品后收拾图片：先删本地残留副本，再清云端孤儿对象。
     *
     * 本地那份可能还在：上传成功才会被删，失败或从未同步过的物品到这一步仍留着本地文件。
     */
    override suspend fun purgeImagesOf(items: List<Item>) {
        if (items.isEmpty()) return
        val keys = withContext(Dispatchers.IO) {
            items.flatMap { item ->
                item.imageHashList().map { hash ->
                    ImageUtil.findLocalFile(context, hash)?.let { file ->
                        ImageUtil.deleteImage(file.absolutePath)
                    }
                    ImageObjectKeys.forContentHash(config.prefix(), hash)
                }
            }
        }
        purgeCloudObjectsInternal(keys)
    }

    private suspend fun purgeCloudObjectsInternal(candidateKeys: Collection<String>) {
        val keys = candidateKeys.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return
        if (!config.isLoggedIn()) return
        withContext(Dispatchers.IO) {
            // 用**含软删物品**的全量快照统计引用：回收站里的物品随时可能被恢复，
            // 若把它们排除在外，就会出现「删掉回收站里另一个物品还在用的图」——
            // 那个物品一旦恢复就成了没有图的空壳。
            // 读不出全库引用时宁可保留孤儿，也不冒误删在用的图的风险。
            val stillReferenced = try {
                itemRepository.getAllItemsSnapshot()
                    // 槽位存的是内容哈希，而候选键是对象键：先按当前前缀换算成对象键再比对。
                    .flatMap { item ->
                        item.imageHashList().map { ImageObjectKeys.forContentHash(config.prefix(), it) }
                    }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (e: CancellationException) {
                // 页面/任务被取消时不吞掉取消信号，否则删除循环会继续跑下去。
                throw e
            } catch (e: Exception) {
                return@withContext
            }

            for (key in purgeableCloudKeys(keys, stillReferenced)) {
                when {
                    // 对不存在的键也算删除意图已达成。
                    gateway.deleteObject(key) -> {
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
        if (!config.readiness().ready) return SyncRunResult.NotReady
        return try {
            performSync(loadAllImages())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failRun(e.message ?: "同步失败")
            SyncRunResult.Done(uploaded = 0, skipped = 0, failed = 1)
        }
    }

    /**
     * 未上传张数（本地算哈希后与账本比对，**不发任何网络请求**）。
     *
     * 未就绪时返回 **null 而不是 0**：这两个值在界面上含义完全不同——
     * 0 表示「统计过，都传完了」，null 表示「没统计」。早先一律返回 0，
     * 于是关掉开关或没填凭据时，界面会显示「所有图片都已上传」，
     * 让用户误以为云端已有备份。
     */
    suspend fun countPendingUploads(): Int? = withContext(Dispatchers.IO) {
        if (!config.readiness().ready) return@withContext null
        loadAllImages().count { image -> image.objectKey !in knownRemoteKeys }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
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
