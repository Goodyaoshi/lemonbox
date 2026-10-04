package com.goodyaoshi.lemonbox.data.search

/**
 * 本地搜索条件解析器：纯规则匹配，不依赖任何云端 AI 服务。
 *
 * 解析优先级：位置名 → 分类名 → 纯状态查询 → 普通文本。
 * 状态条件可以与位置/分类叠加，例如「冰箱里快过期的」会被解析为
 * 「位置 = 冰箱」+「提前若干天内临期」（天数由用户偏好决定）。
 */
class LocalSearchParser {

    fun parse(
        query: String,
        categoryIdsByName: Map<String, Long> = emptyMap(),
        locationIdsByName: Map<String, Long> = emptyMap(),
        expiringWithinDays: Int = DEFAULT_EXPIRING_DAYS
    ): SearchCriteria {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return SearchCriteria()
        }

        val status = extractStatus(normalized, expiringWithinDays)
        val locationHit = matchName(normalized, locationIdsByName)
        val categoryHit = if (locationHit == null) matchName(normalized, categoryIdsByName) else null

        if (locationHit == null && categoryHit == null) {
            if (status != null && normalized.isPureStatusQuery()) {
                return SearchCriteria(
                    kind = SearchIntentKind.FILTER_ITEMS,
                    status = status.status,
                    expiringWithinDays = status.expiringWithinDays,
                    sort = status.sort,
                    summary = "筛选${status.label}的物品"
                )
            }
            return SearchCriteria(
                kind = SearchIntentKind.PLAIN_TEXT,
                text = stripFillers(normalized, status)
            )
        }

        val matchedName = locationHit?.first ?: categoryHit!!.first
        val scope = if (locationHit != null) {
            "「$matchedName」里的物品"
        } else {
            "「$matchedName」分类下的物品"
        }
        return SearchCriteria(
            kind = if (locationHit != null) {
                SearchIntentKind.BROWSE_LOCATION
            } else {
                SearchIntentKind.BROWSE_CATEGORY
            },
            text = stripFillers(normalized, status, matchedName),
            categoryIds = categoryHit?.let { setOf(it.second) }.orEmpty(),
            categoryName = categoryHit?.first,
            locationIds = locationHit?.let { setOf(it.second) }.orEmpty(),
            locationName = locationHit?.first,
            status = status?.status ?: SearchItemStatus.ANY,
            expiringWithinDays = status?.expiringWithinDays,
            sort = status?.sort ?: SearchSort.CREATED_DESC,
            summary = status?.let { "查看$scope（${it.label}）" } ?: "查看$scope"
        )
    }

    /** 取命中的最长名称，最具体的名称优先（例如同时命中「厨房」和「厨房抽屉」时取后者）。 */
    private fun matchName(query: String, idsByName: Map<String, Long>): Pair<String, Long>? {
        return idsByName.entries
            .filter { it.key.isNotBlank() && query.contains(it.key, ignoreCase = true) }
            .maxByOrNull { it.key.length }
            ?.let { it.key to it.value }
    }

    private fun extractStatus(query: String, expiringWithinDays: Int): StatusFields? = when {
        containsAny(query, usedUpWords) -> StatusFields(
            status = SearchItemStatus.USED_UP,
            words = usedUpWords,
            label = "已用完"
        )

        containsAny(query, expiringWords) -> StatusFields(
            status = SearchItemStatus.IN_STOCK,
            expiringWithinDays = expiringWithinDays,
            sort = SearchSort.EXPIRE_ASC,
            words = expiringWords,
            label = "${expiringWithinDays} 天内临期"
        )

        else -> null
    }

    private fun containsAny(query: String, words: List<String>): Boolean {
        return words.any { query.contains(it, ignoreCase = true) }
    }

    /**
     * 剔除状态词、命中的位置/分类名与填充词，剩下的才是真正要匹配的物品关键词。
     * 剔除时用空格分隔，便于 [SearchEngine] 拆词后做部分匹配。
     */
    private fun stripFillers(
        query: String,
        status: StatusFields?,
        matchedName: String? = null
    ): String {
        var value = query
        if (!matchedName.isNullOrBlank()) {
            // 先连缀方位后缀一起剔除，例如「书房里」整体去掉；名称本身没有后缀时不影响
            locativeSuffixes.forEach { suffix ->
                value = value.replace(matchedName + suffix, " ", ignoreCase = true)
            }
            value = value.replace(matchedName, " ", ignoreCase = true)
        }
        status?.words?.forEach { value = value.replace(it, " ", ignoreCase = true) }
        fillerWords
            .sortedByDescending { it.length }
            .forEach { value = value.replace(it, " ", ignoreCase = true) }
        value = value.replace(SEPARATOR_REGEX, " ").trim()
        return if (value.isBlank() && matchedName.isNullOrBlank()) query else value
    }

    /** 整句只由状态词与通用词组成时，才当成纯状态查询。 */
    private fun String.isPureStatusQuery(): Boolean {
        var value = this
        (usedUpWords + expiringWords + genericWords + fillerWords)
            .sortedByDescending { it.length }
            .forEach { word -> value = value.replace(word, "", ignoreCase = true) }
        value = value.replace(SEPARATOR_REGEX, "")
        return value.isBlank()
    }

    private data class StatusFields(
        val status: SearchItemStatus = SearchItemStatus.ANY,
        val expiringWithinDays: Int? = null,
        val sort: SearchSort = SearchSort.CREATED_DESC,
        val words: List<String> = emptyList(),
        val label: String
    )

    private companion object {
        const val DEFAULT_EXPIRING_DAYS = 7

        val SEPARATOR_REGEX = Regex("[\\s,，。.!！?？、：:；;（）()【】\\[\\]{}=]+")

        val usedUpWords = listOf("已用完", "用完", "用掉", "耗尽", "空了", "USED_UP")
        val expiringWords = listOf("快过期", "即将过期", "临期", "快到期", "即将到期")

        /** 命中位置名后可以一起剔除的方位后缀（只在位置名之后匹配，避免误伤物品名）。 */
        val locativeSuffixes = listOf("里面", "里边", "里", "中", "内", "上", "下")

        /** 判断「整句是否只是状态描述」时使用的通用词，不参与关键词剔除。 */
        val genericWords = listOf(
            "物品", "东西", "商品", "全部", "所有", "查找", "搜索", "筛选", "查看",
            "一下", "帮我", "请问", "状态", "评价状态", "为", "且", "和", "以及", "的"
        )

        /**
         * 查询句里的填充词，只用于还原真正要匹配的物品关键词。
         * 注意不要放单字「里」「中」等，避免把「中国结」这类名称拆坏。
         */
        val fillerWords = listOf(
            "里有什么东西", "里面有什么", "放在哪里", "还有多少", "我想找", "我要找",
            "在哪里", "在哪儿", "里有什么", "有什么", "有没有", "还有没有", "有多少",
            "查一下", "找一下", "看一下", "搜一下", "放在哪",
            "在哪", "哪儿", "哪里", "多少", "帮我", "请问", "麻烦", "我想", "我要",
            "查找", "搜索", "查看", "看看", "一下", "放哪", "放着", "放了", "在不在",
            "我家的", "家里的", "我的",
            "里面", "里边", "上面", "下面",
            "物品", "东西", "商品", "宝贝",
            "全部", "所有", "一共", "总共",
            "的"
        )
    }
}