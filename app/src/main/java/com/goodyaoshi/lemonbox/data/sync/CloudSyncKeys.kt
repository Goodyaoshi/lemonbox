package com.goodyaoshi.lemonbox.data.sync

import java.util.UUID

/**
 * 云端同步的对象键推导（多设备同步）。
 *
 * 布局：
 * ```
 * <prefix>/sync/rev-{deviceId}-{nonce}.zip     增量快照
 * <prefix>/sync/base-{deviceId}-{nonce}.zip    全量 checkpoint
 * ```
 *
 * 推上去的是 `createBackupZip` 的产物（ZIP），因此扩展名是 `.zip` 而不是 `.json`——
 * 名字与实际内容一致，将来排障时不必再猜。
 *
 * **为什么对象名里带设备 ID 与随机串**：多台设备各自往同一个前缀下推快照，
 * 若用固定名字（如 `latest.zip`）就会互相覆盖——而对象存储没有原子的读-改-写，
 * 覆盖即丢失。让每个快照都是**新对象、永不重名**，冲突就不存在了：
 * 谁都不覆盖谁，各设备读到的都是同一批不可变对象，再在本地合并。
 *
 * **为什么 nonce 不参与排序**：排序一律用服务端返回的 `LastModified`。
 * 客户端时钟可能偏移，把时间写进对象名会诱使调用方拿名字排序，从而引入时钟问题。
 *
 * **base 与 rev 的分工**：rev 是本机的**增量**变更；base 是本机的**全量**状态。
 * 新设备首次加入时先拉最近的一份 base，再从它的 `LastModified` 往后拉增量 rev，
 * 避免从第一条 rev 开始重放整部历史。base 也带设备 ID 与随机串——它同样不可变、
 * 谁都不覆盖谁，多台设备各写各的，取最近一份即可。
 *
 * 纯逻辑，不依赖 Android 框架，可直接单测。
 */
object CloudSyncKeys {

    /** 快照统一落在该目录下，便于按前缀列举与生命周期清理。 */
    const val FOLDER = "sync"

    private const val REV_PREFIX = "rev-"
    private const val BASE_PREFIX = "base-"
    private const val EXTENSION = ".zip"

    /** 列列举用的前缀：`[prefix/]sync/`（以 `/` 结尾，避免匹配到同名的兄弟目录）。 */
    fun listPrefix(prefix: String): String {
        val normalized = prefix.trim().trim('/')
        return if (normalized.isEmpty()) "$FOLDER/" else "$normalized/$FOLDER/"
    }

    /**
     * 一个新的增量快照键。每次调用都产生**不同**的键（nonce 随机），
     * 因此重复推送不会覆盖上一次——历史得以保留，其他设备也还能拉到。
     */
    fun newRevisionKey(prefix: String, deviceId: String, nonce: String = UUID.randomUUID().toString()): String =
        "${listPrefix(prefix)}$REV_PREFIX${sanitize(deviceId)}-$nonce$EXTENSION"

    /**
     * 一个新的全量 checkpoint 键。同样带设备 ID 与随机串：多台设备各写各的互不覆盖，
     * 新设备取最近一份即可。
     */
    fun newBaseKey(prefix: String, deviceId: String, nonce: String = UUID.randomUUID().toString()): String =
        "${listPrefix(prefix)}$BASE_PREFIX${sanitize(deviceId)}-$nonce$EXTENSION"

    /**
     * 判断某个键是否是本机制产生的快照。列举带前缀的对象时用来滤掉无关对象
     * （同前缀下将来可能还有别的用途）。
     */
    fun isRevisionKey(key: String): Boolean {
        val name = key.substringAfterLast('/')
        return name.startsWith(REV_PREFIX) && name.endsWith(EXTENSION)
    }

    /** 判断某个键是否是一份全量 checkpoint。 */
    fun isBaseKey(key: String): Boolean {
        val name = key.substringAfterLast('/')
        return name.startsWith(BASE_PREFIX) && name.endsWith(EXTENSION)
    }

    /** 是否本机制产生的任一快照（rev 或 base）。 */
    fun isSnapshotKey(key: String): Boolean = isRevisionKey(key) || isBaseKey(key)

    /**
     * 是否本机自己推上去的快照。
     *
     * 跳过自己的快照不是正确性要求（合并同一份数据是幂等的），而是省一次下载：
     * 自己的数据早就在本地，拉回来只是白跑一趟网络。
     */
    fun isOwnRevisionKey(key: String, deviceId: String): Boolean =
        key.substringAfterLast('/').startsWith("$REV_PREFIX${sanitize(deviceId)}-")

    /** 是否本机自己推上去的全量 checkpoint。 */
    fun isOwnBaseKey(key: String, deviceId: String): Boolean =
        key.substringAfterLast('/').startsWith("$BASE_PREFIX${sanitize(deviceId)}-")

    /**
     * 设备 ID 可能含任意字符（UUID 不含，但用户可换别的实现），
     * 这里只保留安全字符，避免斜杠把对象键切出意外的层级。
     */
    private fun sanitize(value: String): String =
        value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
}
