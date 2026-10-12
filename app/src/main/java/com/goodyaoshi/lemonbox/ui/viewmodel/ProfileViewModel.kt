package com.goodyaoshi.lemonbox.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodyaoshi.lemonbox.data.backup.AppBackupManager
import com.goodyaoshi.lemonbox.data.backup.BackupChanges
import com.goodyaoshi.lemonbox.data.backup.toUserMessage
import com.goodyaoshi.lemonbox.data.export.ReadableExportManager
import com.goodyaoshi.lemonbox.data.repository.AnniversaryRepository
import com.goodyaoshi.lemonbox.data.repository.ReminderRepository
import com.goodyaoshi.lemonbox.data.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** 待确认的导入：已解析出变更清单，等用户勾选后再落库。 */
data class PendingImport(
    val sourceUri: Uri,
    val changes: BackupChanges
)

data class BackupUiState(
    val isBusy: Boolean = false,
    /** 处理中弹窗的标题，区分导出 / 导入，让等待有明确所指。 */
    val busyLabel: String? = null,
    val message: String? = null,
    /** 结果是否为失败：用于成功 / 失败分级提示（文案与语气不同）。 */
    val isError: Boolean = false,
    /** 非空的导入预览：弹出变更清单让用户逐条勾选。 */
    val pendingImport: PendingImport? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: AppBackupManager,
    private val readableExportManager: ReadableExportManager,
    reminderRepository: ReminderRepository,
    anniversaryRepository: AnniversaryRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    // 家当域的计数（待买 / 临期 / 回收站）已随「家当域」迁到 HouseholdViewModel，此处不再保留。

    /** 她的昵称：头像首字与「我的」页称呼都取自这里，可在「我的」页修改。 */
    val nickname: StateFlow<String> = appPreferences.nickname

    fun setNickname(value: String) {
        appPreferences.setNickname(value)
    }

    /** 今天（含已提醒还没完成）的待办数量：直接复用「今天的事」的同一份口径（IA-7），两处数字恒一致。 */
    val dueReminderCount: StateFlow<Int> = reminderRepository.getDueTodayReminders()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 纪念日数量：「常用」组纪念日入口的徽标。 */
    val anniversaryCount: StateFlow<Int> = anniversaryRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    fun exportBackup(targetUri: Uri) {
        viewModelScope.launch {
            runBackupAction(busyLabel = "正在导出备份包…") {
                backupManager.exportBackup(targetUri)
                // 回执交代产物（G-7）：带上文件名与大小，让她知道备份包去哪了、有多大；
                // 查询失败时 buildString 退化为原文案，不打断成功路径。
                buildString {
                    append("备份导出完成")
                    describeExportedFile(targetUri)?.let { (name, size) ->
                        if (!name.isNullOrBlank()) append(" · ").append(name)
                        size?.let { append("（").append(formatReadableSize(it)).append("）") }
                    }
                }
            }
        }
    }

    /** 人类可读报告导出（F12）：生成可阅读、可打印的 HTML 清单。 */
    fun exportReadable(targetUri: Uri) {
        viewModelScope.launch {
            runBackupAction(busyLabel = "正在生成报告…") {
                readableExportManager.exportHtml(targetUri)
                "可读报告已导出"
            }
        }
    }

    fun importBackup(sourceUri: Uri) {
        viewModelScope.launch {
            _backupState.value = _backupState.value.copy(
                isBusy = true,
                busyLabel = "正在读取备份包…",
                message = null,
                isError = false
            )
            // 先只读不写：解析出变更清单，让用户勾选后再真正落库。
            runCatching { backupManager.previewBackup(sourceUri) }
                .onSuccess { changes ->
                    if (changes.hasChanges) {
                        _backupState.value = _backupState.value.copy(
                            isBusy = false,
                            busyLabel = null,
                            pendingImport = PendingImport(sourceUri, changes)
                        )
                    } else {
                        // 没有家当层面的差异（比如重复导入同一份），跳过勾选直接合并。
                        applyImport(sourceUri, keepKeys = null, skipped = 0)
                    }
                }
                .onFailure { error ->
                    _backupState.value = _backupState.value.copy(
                        isBusy = false,
                        busyLabel = null,
                        message = error.message ?: "导入失败了，你待会儿再试一次",
                        isError = true
                    )
                }
        }
    }

    /** 用户在变更清单里勾选完，按所选导入；未勾选的条目这次跳过。 */
    fun confirmImport(selectedKeys: Set<String>) {
        val pending = _backupState.value.pendingImport ?: return
        _backupState.value = _backupState.value.copy(pendingImport = null)
        viewModelScope.launch {
            val skipped = (pending.changes.changes.size - selectedKeys.size).coerceAtLeast(0)
            applyImport(pending.sourceUri, keepKeys = selectedKeys, skipped = skipped)
        }
    }

    /** 用户取消导入预览：什么都不写，安静退出。 */
    fun cancelImport() {
        _backupState.value = _backupState.value.copy(pendingImport = null)
    }

    private suspend fun applyImport(sourceUri: Uri, keepKeys: Set<String>?, skipped: Int) {
        _backupState.value = _backupState.value.copy(
            isBusy = true,
            busyLabel = "正在导入并合并…",
            message = null,
            isError = false
        )
        runCatching { backupManager.importBackup(sourceUri, keepKeys = keepKeys) }
            .onSuccess { result ->
                val outcome = if (skipped > 0) result.copy(userSkipped = skipped) else result
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = outcome.toUserMessage(),
                    isError = false
                )
            }
            .onFailure { error ->
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = error.message ?: "导入失败了，你待会儿再试一次",
                    isError = true
                )
            }
    }

    fun consumeBackupMessage() {
        _backupState.value = _backupState.value.copy(message = null, isError = false)
    }

    /**
     * 预览「恢复内置分类」将新增的内置分类名（G-6）：与 [restoreBuiltInData] 的补种同口径，
     * 先列明细让用户确认，不再点了就往整理过的分类树里塞回不用的分类。
     */
    suspend fun previewMissingBuiltInCategories(): List<String> =
        backupManager.previewRestoreBuiltIn()

    /** 恢复内置分类：补齐食材/零食等受保护分类并去重，用于同步后分类缺失时手动找回。 */
    fun restoreBuiltInData() {
        viewModelScope.launch {
            runBackupAction(busyLabel = "正在恢复内置分类…") {
                backupManager.restoreBuiltInData()
                "内置分类已补齐，去「分类」里看看"
            }
        }
    }

    /** 查询导出文件的显示名与大小（G-7）；任何查询异常都按无回执处理，返回 null 走降级文案。 */
    private fun describeExportedFile(uri: Uri): Pair<String?, Long?>? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            name to size
        }
    }.getOrNull()

    /** 大小人类可读（G-7）：MB/KB 一位小数（整数省略 .0），不足 1KB 直接展示 B。 */
    private fun formatReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        val value = if (kb >= 1024) kb / 1024.0 else kb
        val unit = if (kb >= 1024) "MB" else "KB"
        return String.format(Locale.US, "%.1f", value).removeSuffix(".0") + " $unit"
    }

    private suspend fun runBackupAction(
        busyLabel: String,
        action: suspend () -> String
    ) {
        _backupState.value = _backupState.value.copy(
            isBusy = true,
            busyLabel = busyLabel,
            message = null,
            isError = false
        )
        runCatching { action() }
            .onSuccess { message ->
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = message,
                    isError = false
                )
            }
            .onFailure { error ->
                _backupState.value = _backupState.value.copy(
                    isBusy = false,
                    busyLabel = null,
                    message = error.message ?: "操作没成功，你待会儿再试一次",
                    isError = true
                )
            }
    }
}
