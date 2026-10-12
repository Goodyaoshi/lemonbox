package com.goodyaoshi.lemonbox.di

import com.goodyaoshi.lemonbox.data.ocr.OcrEngine
import com.goodyaoshi.lemonbox.data.ocr.PpOcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 离线文字识别（F01）：把 [OcrEngine] 绑定到 PP-OCR 的 ONNX 实现，单例复用已加载的模型会话。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(impl: PpOcrEngine): OcrEngine
}
