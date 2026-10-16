package com.goodyaoshi.lemonbox.ui.viewmodel

/**
 * 乐观并发检测（本地、不联网）。
 *
 * 编辑页载入记录时记下它的 `updatedAt`（[loadedUpdatedAt]），保存前再读一次库里的当前值
 * （[currentUpdatedAt]），两者不等即说明这条在编辑期间被改过——可能是同步合并拉回了别的
 * 设备的更新，也可能是本机其它入口改了它。此时不静默覆盖，交由用户裁决。
 *
 * **为什么不做「编辑锁」**：本 App 是「本地优先 + 无服务器中转」，离线也必须能写；
 * 真正的分布式锁无处安放（设备崩溃后会悬挂），且需要实时心跳才有意义——与产品定位相悖。
 * 乐观检查只依赖本机，天然相容。
 *
 * 任一端为 null（新建记录、或旧数据没有 updatedAt）时不判为冲突——宁可不提示，也不误报。
 */
internal fun isStaleEdit(loadedUpdatedAt: Long?, currentUpdatedAt: Long?): Boolean =
    loadedUpdatedAt != null && currentUpdatedAt != null && currentUpdatedAt != loadedUpdatedAt
