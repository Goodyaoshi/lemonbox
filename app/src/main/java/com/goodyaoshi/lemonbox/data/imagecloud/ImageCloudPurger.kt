package com.goodyaoshi.lemonbox.data.imagecloud

/**
 * 清理云端图片对象的能力（F20）。
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
}
