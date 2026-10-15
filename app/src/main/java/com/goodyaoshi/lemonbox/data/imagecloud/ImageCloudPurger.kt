package com.goodyaoshi.lemonbox.data.imagecloud

import com.goodyaoshi.lemonbox.data.local.entity.Item

/**
 * 清理云端图片对象的能力（F22）。
 *
 * 单独抽成接口，是为了让「删除物品」这条业务链路不必依赖整个同步器：同步器要拿到
 * Context、凭据存储、S3 客户端才能构造，而调用方只关心「把没人用的对象删掉」这一件事。
 * 接口也让这条链路可以单测（见 `FakeImageCloudPurger`）。
 */
interface ImageCloudPurger {

    /**
     * 清理已无任何物品引用的云端对象。
     *
     * 实现必须自行保证「仍被引用就不删」——对象键由内容哈希推导，同一张图可能挂在
     * 多件物品上。清理失败应静默跳过（物品删除本身已完成，不该反过来阻断用户操作）。
     */
    suspend fun purgeCloudObjects(candidateKeys: Collection<String>)

    /**
     * 物品被永久删除后收拾它的图片：先删本地残留副本，再清云端已无人引用的对象。
     *
     * 收一个 [Item] 列表而不是键列表，是因为调用方（回收站清空、墓碑到期清理）手上
     * 只有被删的物品行；由实现去推导槽位哈希、本地文件与对象键，调用方便不必各自
     * 依赖 Context 与存储前缀配置。
     */
    suspend fun purgeImagesOf(items: List<Item>)
}
