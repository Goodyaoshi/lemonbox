package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.goodyaoshi.lemonbox.ui.theme.LeafGreen
import com.goodyaoshi.lemonbox.ui.theme.LemonSlice
import com.goodyaoshi.lemonbox.ui.theme.LightTagPurpleText
import com.goodyaoshi.lemonbox.ui.theme.MintGreen
import com.goodyaoshi.lemonbox.ui.theme.SageAccent
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.StatusInfo
import com.goodyaoshi.lemonbox.ui.theme.StatusNormal
import com.goodyaoshi.lemonbox.ui.theme.StatusWarning
import com.goodyaoshi.lemonbox.ui.theme.TagBlue
import com.goodyaoshi.lemonbox.ui.theme.TagBlueText
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrange
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TagPurple
import com.goodyaoshi.lemonbox.ui.theme.TagPurpleText
import com.goodyaoshi.lemonbox.ui.theme.WarmBrownSoft

// ============================================================
// 分类配色的唯一来源（P2：全局同一套映射）
//
// 关键点：用「分类身份」而不是「列表下标」取色。
// 旧实现里标签按 text.hashCode、饼图按金额排序后的下标取色，导致同一分类
// 换个页面/换个月份就可能变色。这里统一改为对稳定标识（优先 categoryId，
// 无 id 时退回名称）做散列 -> 固定色槽，保证「一个分类永远一个色」。
// ============================================================

/**
 * 稳定色槽：对 [categoryId]（优先）/ [name] 做 FNV-1a 散列，不依赖平台字符串
 * 哈希的实现细节，跨版本、跨进程结果一致。返回 `[0, slots)`。
 */
fun categorySlot(categoryId: Long?, name: String?, slots: Int): Int {
    if (slots <= 0) return 0
    val seed = if (categoryId != null) "id:$categoryId" else "name:${name.orEmpty()}"
    var hash = -2128831035 // 0x811C9DC5，FNV-1a 偏移基数
    for (ch in seed) {
        hash = hash xor ch.code
        hash *= 16777619
    }
    return ((hash % slots) + slots) % slots
}

/** 标签式分类色：(底色, 文字色)，随深浅主题变化，与 [CategoryTag] 共用同一色槽。 */
@Composable
fun categoryTagColors(categoryId: Long?, name: String): Pair<Color, Color> {
    val pairs = listOf(
        TagBlue to TagBlueText,
        TagGreen to TagGreenText,
        TagOrange to TagOrangeText,
        TagPurple to TagPurpleText
    )
    return pairs[categorySlot(categoryId, name, pairs.size)]
}

/** 实心分类色板：饼图扇区 / 图例圆点共用，与标签色槽同源。 */
@Composable
fun categorySolidColors(): List<Color> = listOf(
    LeafGreen,
    MintGreen,
    LemonSlice,
    SageAccent,
    StatusWarning,
    StatusInfo,
    StatusExpired,
    StatusNormal,
    LightTagPurpleText,
    WarmBrownSoft
)
