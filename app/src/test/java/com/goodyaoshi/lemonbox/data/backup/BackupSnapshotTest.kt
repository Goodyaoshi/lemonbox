package com.goodyaoshi.lemonbox.data.backup

import com.goodyaoshi.lemonbox.data.local.entity.Anniversary
import com.goodyaoshi.lemonbox.data.local.entity.ItemStatusOption
import com.goodyaoshi.lemonbox.data.local.entity.StatusDimension
import com.goodyaoshi.lemonbox.data.meal.MealSpec
import com.goodyaoshi.lemonbox.data.meal.Recipe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 备份快照的序列化契约：
 * - 新字段（纪念日/提醒/偏好）能完整往返；
 * - 旧版本备份 JSON（缺新字段、菜谱缺 syncId）用空默认值兼容读入。
 */
class BackupSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `纪念日快照往返保留全部业务字段`() {
        val snapshot = AnniversarySnapshot(
            id = 3,
            name = "在一起",
            note = "2020-01-01 开始",
            type = Anniversary.TYPE_BIRTHDAY,
            date = "2020-01-01",
            isLunar = false,
            repeatUnit = "YEAR",
            repeatInterval = 1,
            remindDays = "0,7",
            enabled = true,
            createdAt = 1_000L,
            syncId = "abc",
            updatedAt = 2_000L,
            deletedAt = null
        )
        val decoded = json.decodeFromString<AnniversarySnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, decoded)
        // 设备本地的通知去重标记不进快照（类里就没有这个字段）
        assertTrue(json.encodeToString(snapshot).contains("lastNotifiedDate").not())
        assertTrue(json.encodeToString(snapshot).contains("repeatUnit"))
    }

    @Test
    fun `纪念日旧JSON缺type按倒数日兜底且非法周期归一`() {
        // 旧备份没有 type 字段，读入按倒数日（TYPE_COUNTDOWN）处理
        val legacy = json.decodeFromString<AnniversarySnapshot>(
            """{"id":1,"name":"生日","date":"2000-03-05","createdAt":1}"""
        )
        assertEquals(Anniversary.TYPE_COUNTDOWN, legacy.type)

        // 非法重复单位导入端归一化为不重复（normalizeRepeatUnit）
        assertEquals("NONE", Anniversary.normalizeRepeatUnit("wat"))
        assertEquals("YEAR", Anniversary.normalizeRepeatUnit("year"))

        // 新 JSON 显式 type 正确读入
        val typed = json.decodeFromString<AnniversarySnapshot>(
            """{"id":2,"name":"小宝出生","date":"2020-06-01","createdAt":1,"type":2}"""
        )
        assertEquals(Anniversary.TYPE_BIRTHDAY, typed.type)
    }

    @Test
    fun `提醒快照可还原成实体`() {
        val snapshot = ReminderSnapshot(
            id = 9,
            title = "解冻鸡肉",
            fireTime = "08:30",
            createdAt = 5_000L,
            syncId = "r1",
            updatedAt = 6_000L
        )
        val decoded = json.decodeFromString<ReminderSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, decoded)

        val reminder = decoded.toReminder(targetId = 77)
        assertEquals(77L, reminder.id)
        assertEquals("解冻鸡肉", reminder.title)
        assertEquals("08:30", reminder.fireTime)
        assertEquals("r1", reminder.syncId)
        // updatedAt 缺失时退回 createdAt 的兜底
        val noUpdate = snapshot.copy(updatedAt = null).toReminder(1)
        assertEquals(5_000L, noUpdate.updatedAt)
    }

    @Test
    fun `偏好快照往返`() {
        val prefs = PreferencesSnapshot(
            recipes = listOf(Recipe(id = 100, name = "西红柿炒蛋", syncId = "recipe-1")),
            weeklyMenu = mapOf("2026-10-05" to MealSpec()),
            cookedMenuDates = setOf("2026-10-05"),
            customStatuses = listOf(
                CustomStatusSnapshot.fromOption(
                    ItemStatusOption(
                        code = 901,
                        label = "借给人",
                        dimension = StatusDimension.DISPOSITION,
                        isBuiltIn = false
                    )
                )
            ),
            reminderLadder = listOf(1, 3, 7),
            reminderTimes = listOf("09:00"),
            mealPrepDayShift = -1
        )
        val decoded = json.decodeFromString<PreferencesSnapshot>(json.encodeToString(prefs))
        assertEquals(prefs, decoded)
        assertEquals("借给人", decoded.customStatuses.first().label)
    }

    @Test
    fun `菜谱旧JSON缺syncId按null读入`() {
        val legacy = """{"id":100,"name":"可乐鸡翅","ingredients":[]}"""
        val recipe = json.decodeFromString<Recipe>(legacy)
        assertEquals(100L, recipe.id)
        assertEquals("可乐鸡翅", recipe.name)
        assertNull(recipe.syncId)
    }

    @Test
    fun `偏好快照旧JSON缺新增字段按空默认读入`() {
        val legacy = """{"recipes":[],"weeklyMenu":{}}"""
        val prefs = json.decodeFromString<PreferencesSnapshot>(legacy)
        assertTrue(prefs.cookedMenuDates.isEmpty())
        assertTrue(prefs.reminderLadder.isEmpty())
        assertNull(prefs.mealPrepDayShift)
    }

    @Test
    fun `自定义状态维度名非法时还原为null`() {
        val broken = CustomStatusSnapshot(code = 902, label = "坏数据", dimension = "NOPE")
        assertNull(broken.toOption())
    }
}
