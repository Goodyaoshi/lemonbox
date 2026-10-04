package com.goodyaoshi.lemonbox.data.local.entity

import androidx.room.Embedded

data class ItemDetail(
    @Embedded val item: Item,
    val categoryName: String? = null,
    /** 分类图标的 key，来自所属分类；用于家当卡片展示分类图标。 */
    val categoryIcon: String? = null,
    val locationName: String? = null
)
