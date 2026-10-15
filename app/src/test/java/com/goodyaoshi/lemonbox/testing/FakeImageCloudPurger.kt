package com.goodyaoshi.lemonbox.testing

import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPurger
import com.goodyaoshi.lemonbox.data.local.entity.Item

/**
 * 云端清理器的测试替身：只记录被请求清理的键与物品，不碰网络、不碰文件系统。
 *
 * 生产实现要 Context、Keystore 与 S3 客户端才能构造，而测试关心的是
 * 「删除物品时到底把哪些键交给了清理器」，故用这个轻量替身观察调用。
 */
class FakeImageCloudPurger : ImageCloudPurger {

    /** 历次调用收到的键，按调用顺序累积，便于断言「一次删除后清理了哪些键」。 */
    val requestedKeys = mutableListOf<String>()

    /** 历次被请求收拾的物品，便于断言「删了哪几条」。 */
    val purgedItems = mutableListOf<Item>()

    override suspend fun purgeCloudObjects(candidateKeys: Collection<String>) {
        requestedKeys += candidateKeys
    }

    override suspend fun purgeImagesOf(items: List<Item>) {
        purgedItems += items
    }
}
