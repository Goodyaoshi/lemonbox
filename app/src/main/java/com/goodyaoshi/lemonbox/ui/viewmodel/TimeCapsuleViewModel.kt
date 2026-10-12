package com.goodyaoshi.lemonbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.local.entity.TimeCapsule
import com.goodyaoshi.lemonbox.data.repository.TimeCapsuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 时间胶囊列表（F14）：按解锁时间升序展示，未到时间的只显示「还有 N 天可打开」。
 */
@HiltViewModel
class TimeCapsuleViewModel @Inject constructor(
    private val repository: TimeCapsuleRepository
) : ViewModel() {

    val capsules: StateFlow<List<TimeCapsule>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建胶囊；[unlockAt] 为可打开的起始时间戳。 */
    fun create(
        title: String,
        content: String,
        unlockAt: Long,
        forWhom: String?,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.create(
                TimeCapsule(
                    title = title.trim(),
                    content = content.trim(),
                    unlockAt = unlockAt,
                    forWhom = forWhom?.trim()?.takeIf { it.isNotBlank() }
                )
            )
            onDone()
        }
    }

    /** 打开胶囊，记录首次打开时间。 */
    fun open(capsule: TimeCapsule) {
        viewModelScope.launch { repository.open(capsule) }
    }

    fun delete(capsule: TimeCapsule) {
        viewModelScope.launch { repository.softDelete(capsule) }
    }
}
