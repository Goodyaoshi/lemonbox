package com.goodyaoshi.lemonbox.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一条冲突的留痕：本机当时推的载荷与服务端胜出的载荷都在，诊断页据此逐字段对比。
 *
 * 不进 Room：冲突日志是诊断用的「事后可查」，不是业务数据，
 * 用 JSON 文件存可以免掉一次 schema 迁移，丢了他不影响任何账实一致。
 */
@Serializable
data class ConflictLogEntry(
    val collection: String,
    val key: String,
    /** 服务端胜出版本的序号。 */
    val seq: Long,
    /** 本机推送时带的 baseSeq（本机上次见到的服务端序号）。 */
    val baseSeq: Long,
    /** 本机当时推上去的载荷（冲突发生前的本机版本）。 */
    val localPayload: String,
    /** 服务端胜出的载荷（冲突发生后的权威版本）。 */
    val remotePayload: String,
    /** 冲突发生时刻（本机时钟，毫秒）。 */
    val at: Long,
    /** 服务端上最后改这条的人（账号名），冲突提示里说明「是谁盖的」。 */
    val updatedBy: String = ""
)

/**
 * 冲突日志（P9）：每次推送撞出冲突就追加一条留痕，键相同则覆盖旧痕。
 *
 * 界面提示「有几条两边都改过」只是一句话，用户真正想弄清「我丢了什么」时，
 * 到诊断页逐条看字段对比——这要求冲突发生的那一刻把两版载荷都存下来，
 * 等用户想起来再看时本机早已被服务端版本盖掉，对比不出来了。
 *
 * 上限 [MAX_ENTRIES] 条、新痕在前：冲突是小概率事件，留太多没有读者，
 * 反而让文件无限长。
 */
@Singleton
class RemoteConflictStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /** 读全部留痕（新痕在前）；没有或文件损坏返回空——日志丢了不该挡诊断页。 */
    suspend fun read(): List<ConflictLogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.takeIf { it.isFile }?.readText() ?: return@runCatching emptyList()
            json.decodeFromString(ListSerializer(ConflictLogEntry.serializer()), text)
        }.getOrDefault(emptyList())
    }

    /** 追加一批冲突留痕：同 (collection, key) 的旧痕被新痕覆盖，超出上限裁掉最老的。 */
    suspend fun append(entries: List<ConflictLogEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            val existing = read().associateBy { it.collection to it.key }.toMutableMap()
            entries.forEach { entry -> existing[entry.collection to entry.key] = entry }
            val merged = existing.values
                .sortedByDescending { it.at }
                .take(MAX_ENTRIES)
            runCatching {
                file.writeText(json.encodeToString(ListSerializer(ConflictLogEntry.serializer()), merged))
            }
        }
    }

    private companion object {
        const val FILE_NAME = "sync_conflicts.json"
        const val MAX_ENTRIES = 50
    }
}
