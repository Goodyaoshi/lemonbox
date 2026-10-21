package com.goodyaoshi.lemonbox.data.local.dao

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 扫描 DAO 源码里所有 `@Query` 的 UPDATE 语句（P7 防回归）：
 * 要么刷新 `updatedAt`，要么在 [WHITELIST] 里。
 *
 * 背景：多条写路径曾漏刷 updatedAt，行虽改了、跨设备 LWW 合并却按「较旧」处理，
 * 表现为「改了又被还原」。Room 的 `@Query` UPDATE 不会自动维护 updatedAt，
 * 漏写编译器不报、界面上看不出，只能靠这类源码扫描兜底。
 */
class DaoUpdateSqlAuditTest {

    @Test
    fun `所有 UPDATE 语句要么刷 updatedAt 要么在白名单`() {
        val updates = scanUpdateQueries()
        assertTrue("一条 UPDATE 都没扫到：扫描器或源码路径出了问题", updates.isNotEmpty())
        val offenders = updates.filter { update ->
            update.method !in WHITELIST[update.file].orEmpty() && !REFRESHES_UPDATED_AT.containsMatchIn(update.sql)
        }
        assertTrue(
            "以下 UPDATE 漏刷 updatedAt（新增白名单须写明理由）:\n" +
                offenders.joinToString("\n") { "  ${it.file}#${it.method}: ${it.sql.take(140)}" },
            offenders.isEmpty()
        )
    }

    @Test
    fun `白名单条目必须真实存在且确实没刷 updatedAt`() {
        val updates = scanUpdateQueries()
        val byKey = updates.associateBy { it.file to it.method }
        val problems = WHITELIST.flatMap { (file, methods) ->
            methods.map { method ->
                val update = byKey[file to method]
                when {
                    update == null -> "$file#$method: 扫描不到（改名或删除后请同步清理白名单）"
                    REFRESHES_UPDATED_AT.containsMatchIn(update.sql) ->
                        "$file#$method: 已刷 updatedAt，白名单条目已过时，请移除"
                    else -> null
                }
            }
        }.filterNotNull()
        assertTrue(
            "白名单需要维护:\n" + problems.joinToString("\n") { "  $it" },
            problems.isEmpty()
        )
    }
}

/** 一条扫描到的 UPDATE 语句：所在文件、方法名、拼接后的 SQL。 */
private data class UpdateQuery(val file: String, val method: String, val sql: String)

private val DAO_SEGMENTS = listOf("src", "main", "java", "com", "goodyaoshi", "lemonbox", "data", "local", "dao")

private val FUN_NAME = Regex("""\bfun\s+(\w+)\s*\(""")
private val UPDATE_OR_SET = Regex("""\bUPDATE\b|\bSET\b""")
private val REFRESHES_UPDATED_AT = Regex("""updatedAt\s*=\s*:""")

/**
 * 白名单：文件名 → 刻意不刷 `updatedAt` 的方法名集合。每条都要写明理由，
 * 并被「白名单条目必须真实存在且确实没刷 updatedAt」盯着，防止烂在原地。
 */
private val WHITELIST: Map<String, Set<String>> = mapOf(
    // 同步记账（P6）：只写 serverSeq 并清 dirty。updatedAt 是「内容什么时候改的」的证据，
    // 记账不该碰它——把本机时间写进去会污染 LWW 合并所依赖的「对方修改时间」语义。
    "RemoteSyncDao.kt" to setOf(
        "markCategorySynced", "markLocationSynced", "markItemSynced",
        "markLedgerAssetSynced", "markLedgerCategorySynced", "markLedgerRecordSynced",
        "markLedgerBudgetSynced", "markAnniversarySynced", "markReminderSynced",
        "markTimeCapsuleSynced", "markPackingTripSynced", "markPackingItemSynced",
        "markLedgerTemplateSynced", "markLedgerRecurringRuleSynced"
    ),
    // 传图副产品（P6 白名单）：imageHashes 是本机图片管线的缓存，两台设备各自算得一致；
    // 刷 updatedAt 会让「刚下载完图片」的行在 LWW 里压过对方真实的编辑（改了却被还原），
    // 故同样保持不刷时间戳、不标脏（见 ItemDao.updateImageHashes 的说明）。
    "ItemDao.kt" to setOf("updateImageHashes")
)

/** 从仓库根或 app 模块目录向上找 DAO 源码目录；都找不到返回 null。 */
private fun resolveDaoDir(): Path? {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        for (prefix in listOf(dir, dir.resolve("app"))) {
            var candidate = prefix
            DAO_SEGMENTS.forEach { candidate = candidate.resolve(it) }
            if (Files.isDirectory(candidate)) return candidate
        }
        dir = dir.parent
    }
    return null
}

/** 扫描 DAO 目录下每个 `*Dao.kt`，摘出 SQL 里带 UPDATE/SET 的 `@Query` 方法。 */
private fun scanUpdateQueries(): List<UpdateQuery> {
    val daoDir = resolveDaoDir() ?: error("找不到 DAO 源码目录，请从仓库根或 app 模块目录运行测试")
    val results = mutableListOf<UpdateQuery>()
    Files.list(daoDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith("Dao.kt") }
            .sorted()
            .forEach { path -> results += scanFile(path.fileName.toString(), path) }
    }
    return results
}

private fun scanFile(file: String, path: Path): List<UpdateQuery> {
    val lines = Files.readAllLines(path)
    val results = mutableListOf<UpdateQuery>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].contains("@Query(")) {
            i++
            continue
        }
        // 从 @Query( 起逐行拼到方法声明为止：注解的字符串参数与方法名都在这段里。
        val chunk = StringBuilder()
        var method: String? = null
        var j = i
        while (j < lines.size) {
            chunk.append(lines[j]).append('\n')
            val match = FUN_NAME.find(lines[j])
            if (match != null) {
                method = match.groupValues[1]
                break
            }
            j++
        }
        val name = method
        if (name != null) {
            val sql = collectStringLiterals(chunk.toString()).joinToString(" ")
            if (UPDATE_OR_SET.containsMatchIn(sql)) {
                results += UpdateQuery(file, name, sql)
            }
        }
        i = j + 1
    }
    return results
}

/** 摘出代码块里的全部字符串字面量（支持 `"…"` 转义拼接与 `"""…"""` 原样串）。 */
private fun collectStringLiterals(chunk: String): List<String> {
    val literals = mutableListOf<String>()
    var i = 0
    while (i < chunk.length) {
        when {
            chunk.startsWith("\"\"\"", i) -> {
                val end = chunk.indexOf("\"\"\"", i + 3)
                if (end < 0) break
                literals += chunk.substring(i + 3, end)
                i = end + 3
            }
            chunk[i] == '"' -> {
                val sb = StringBuilder()
                i++
                while (i < chunk.length && chunk[i] != '"') {
                    if (chunk[i] == '\\' && i + 1 < chunk.length) {
                        i++
                        sb.append(
                            when (chunk[i]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> chunk[i]
                            }
                        )
                    } else {
                        sb.append(chunk[i])
                    }
                    i++
                }
                i++ // 收尾引号
                literals += sb.toString()
            }
            else -> i++
        }
    }
    return literals
}
