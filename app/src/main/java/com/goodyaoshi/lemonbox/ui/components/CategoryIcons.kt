package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Microwave
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.ui.graphics.vector.ImageVector

/** 分类图标选项：key 持久化在 Category.icon，label 用于选择器展示。 */
internal data class CategoryIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector
)

/** 分类可选图标目录，分类页与家当卡片共用。 */
internal val categoryIconOptions = listOf(
    CategoryIconOption("food", "食品", Icons.Default.Fastfood),
    CategoryIconOption("medicine", "药品", Icons.Default.MedicalServices),
    CategoryIconOption("home", "家居", Icons.Default.Home),
    CategoryIconOption("digital", "数码", Icons.Default.Devices),
    CategoryIconOption("clothes", "衣物", Icons.Default.Checkroom),
    CategoryIconOption("stationery", "文具", Icons.AutoMirrored.Filled.StickyNote2),
    CategoryIconOption("tool", "工具", Icons.Default.Handyman),
    CategoryIconOption("kitchen", "厨具", Icons.Default.Kitchen),
    CategoryIconOption("appliance", "家电", Icons.Default.Microwave),
    CategoryIconOption("coffee", "饮品", Icons.Default.LocalCafe),
    CategoryIconOption("laundry", "洗护", Icons.Default.LocalLaundryService),
    CategoryIconOption("cleaning", "清洁", Icons.Default.CleaningServices),
    CategoryIconOption("pet", "宠物", Icons.Default.Pets),
    CategoryIconOption("child", "母婴", Icons.Default.ChildCare),
    CategoryIconOption("game", "娱乐", Icons.Default.SportsEsports),
    CategoryIconOption("bag", "收纳", Icons.Default.Backpack),
    CategoryIconOption("care", "个护", Icons.Default.SelfImprovement),
    CategoryIconOption("basket", "囤货", Icons.Default.ShoppingBasket),
    CategoryIconOption("drink", "酒水", Icons.Default.WineBar),
    CategoryIconOption("snack", "零食", Icons.Default.Restaurant),
    CategoryIconOption("bath", "洗浴", Icons.Default.WaterDrop),
    CategoryIconOption("health", "保健", Icons.Default.MonitorHeart),
    CategoryIconOption("safe", "防护", Icons.Default.Shield),
    CategoryIconOption("toy", "玩具", Icons.Default.Toys),
    CategoryIconOption("beauty", "美护", Icons.Default.Spa),
    CategoryIconOption("battery", "电池", Icons.Default.Power),
    CategoryIconOption("other", "其它", Icons.Default.Apps)
)

/** 图标 key → 图标；空或未知一律回退到「其它」。 */
internal fun categoryIconFor(key: String?): ImageVector =
    categoryIconOptions.firstOrNull { it.key == key }?.icon ?: Icons.Default.Apps