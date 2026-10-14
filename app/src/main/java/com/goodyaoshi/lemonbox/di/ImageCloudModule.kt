package com.goodyaoshi.lemonbox.di

import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudPurger
import com.goodyaoshi.lemonbox.data.imagecloud.ImageCloudSyncManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 云存储的绑定（F20）。
 *
 * [ImageCloudSyncManager] 同时是「同步编排」与「云端清理」的实现，但调用方各自只需要其中一面：
 * 删除物品的链路只用到 [ImageCloudPurger]，绑定成接口后它就不必依赖整个同步器。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ImageCloudModule {

    @Binds
    @Singleton
    abstract fun bindImageCloudPurger(impl: ImageCloudSyncManager): ImageCloudPurger
}
