package com.goodyaoshi.lemonbox.ui.viewmodel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [LibraryFilter] 经导航参数传递时的序列化载荷。
 *
 * 为什么需要它：家当的翻页范围不能只靠 scope 表达——分类、位置、关键词这些条件
 * 组合起来是 SearchEngine 在内存里算的，压不进少数几个 scope 常量。从家当页点进详情
 * 时把这整份条件随路由带上，详情页才能复现同一份筛选。
 *
 * 字段与 [LibraryFilter] 一一对应（grouping 只影响列表分组、不影响集合，故不参与传递）。
 */
@Serializable
internal data class LibraryFilterPayload(
    val usageStatuses: Set<Int> = emptySet(),
    val dispositions: Set<Int> = emptySet(),
    val needRestockOnly: Boolean = false,
    val categoryId: Long? = null,
    val locationId: Long? = null
)

/** 未知字段直接忽略：后续版本若给载荷加了字段，旧参数串仍可解码。 */
private val filterJson = Json { ignoreUnknownKeys = true }

internal fun encodeLibraryFilter(filter: LibraryFilter): String =
    filterJson.encodeToString(
        LibraryFilterPayload(
            usageStatuses = filter.usageStatuses,
            dispositions = filter.dispositions,
            needRestockOnly = filter.needRestockOnly,
            categoryId = filter.categoryId,
            locationId = filter.locationId
        )
    )

/**
 * 解码家当筛选条件；为空或格式不可识别时返回 null。
 *
 * 返回 null 是刻意的降级契约：调用方据此回退到按 scope 过滤的老路径，
 * 而不是因为一段导航参数不可解析就让详情页打不开。
 */
internal fun decodeLibraryFilter(raw: String?): LibraryFilter? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val payload = filterJson.decodeFromString<LibraryFilterPayload>(raw)
        LibraryFilter(
            usageStatuses = payload.usageStatuses,
            dispositions = payload.dispositions,
            needRestockOnly = payload.needRestockOnly,
            categoryId = payload.categoryId,
            locationId = payload.locationId
        )
    }.getOrNull()
}
