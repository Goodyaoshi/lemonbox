package com.goodyaoshi.lemonbox.data.imagecloud

/**
 * 图片对象的键推导（F20）。
 *
 * 核心设计：**对象键由图片内容的 SHA-256 决定**，而不是由物品 id、设备 id 或时间戳决定。
 *
 * 为什么这样做（对应「避免多个设备上传同一张图片」的要求）：
 * - 同一张图在任何设备、任何时间算出的键都相同，于是多设备上传天然落到同一个对象上；
 * - 上传前先探在，已存在就跳过，不会重复传输；
 * - 即使两台设备同时上传，也是同一个键的幂等覆盖，不会产生副本。
 *
 * 由此还带来一个副作用上的好处：判断「这张图传过没有」不需要在数据库里记标记位，
 * 只要本地文件在、就能随时重算哈希重新判断，因此本次改动**不需要任何 schema 迁移**。
 *
 * 纯逻辑、不依赖 Android 框架，可直接单测。
 */
object ImageObjectKeys {

    /** 图片统一落在该目录下，便于按前缀做生命周期 / 清理策略。 */
    const val FOLDER = "images"

    /** 落盘由 ImageUtil 统一转成 JPEG，故扩展名固定。 */
    private const val EXTENSION = "jpg"

    /**
     * 由内容哈希推导对象键：`[prefix/]images/<sha256>.jpg`。
     * [prefix] 为空或只含斜杠时不产生多余路径段。
     */
    fun forContentHash(prefix: String, sha256Hex: String): String {
        val normalizedPrefix = prefix.trim().trim('/')
        val name = "$FOLDER/$sha256Hex.$EXTENSION"
        return if (normalizedPrefix.isEmpty()) name else "$normalizedPrefix/$name"
    }
}
