package com.goodyaoshi.lemonbox.data.imagecloud

/**
 * 图片对象的键推导（F20）。
 *
 * 对象键由图片内容的 SHA-256 决定（非物品 id / 设备 id / 时间戳）：同一张图在任何设备
 * 算出的键都相同，故多设备上传天然落到同一对象、上传前探在即可跳过重复。纯逻辑，可直接单测。
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
