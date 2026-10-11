package com.goodyaoshi.lemonbox.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首帧加载标记，专治「首帧假空态」。
 *
 * 页面普遍用 `StateFlow` + `emptyList()`（或空对象）作初值，数据到达前的第一帧会被画成「空」，
 * 很容易被误读成「数据被清空了」。把「还没读到」用 [isLoading] 单独标出来，
 * 页面即可在加载期显示骨架屏，只在「确认已加载且为空」时才显示空态。
 *
 * P2 增补：除「加载中」外，再补一条 [error] 通道，用于把「读取失败」与「真的为空」区分开——
 * 失败时页面显示可重试的 [ErrorState]，而不是骗人的空态。配合 `retry()`（各 ViewModel 自己实现，
 * 通常是把上游流重新订阅）即可闭环。
 *
 * 用法：把上游冷流接上 [markLoaded]，例如
 * ```
 * private val loading = LoadingState()
 * val isLoading: StateFlow<Boolean> = loading.isLoading
 *
 * val items = repo.observeItems()
 *     .onEach { loading.markLoaded() }
 *     .catch { loading.markError(it.message) }
 *     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
 * ```
 */
class LoadingState {

    private val _isLoading = MutableStateFlow(true)

    /** 首个数据尚未到达时为 true。 */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 读取失败时的错误文案；为 null 表示没有错误。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 上游首个（或任意一个）数据到达时调用；重复调用无副作用。 */
    fun markLoaded() {
        if (_isLoading.value) _isLoading.value = false
        _error.value = null
    }

    /** 上游读取失败时调用：停止加载态并记下错误，页面据此显示可重试的错误态。 */
    fun markError(message: String? = null) {
        _isLoading.value = false
        _error.value = message?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR
    }

    /** 重试前复位：回到加载中、清掉错误，等待上游重新发射。 */
    fun beginRetry() {
        _error.value = null
        _isLoading.value = true
    }

    companion object {
        private const val DEFAULT_ERROR = "数据没能读出来，请稍后再试。"
    }
}
