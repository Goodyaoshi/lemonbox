package com.goodyaoshi.lemonbox.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 提醒可靠性的系统状态查询与设置页跳转。
 * 厂商的「自启动」等开关是私有设置、没有公开 API 可以静默打开，
 * 这里的策略是：直接把对应的系统开关页弹到用户面前，点一下即永久生效。
 */
object ReminderReliability {

    /** 是否已加入电池优化白名单（省电策略不再限制进程与闹钟）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 弹系统的一键对话框：用户点「允许」即加入电池优化白名单，无需翻设置。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppDetails(context) }
    }

    /**
     * 打开厂商的自启动管理页：MIUI 优先，华为/OPPO/vivo 依次尝试，最终退回应用详情页。
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            // MIUI 自启动管理
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // 华为启动管理
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // OPPO / ColorOS 自启动
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            // vivo 后台弹出与自启动
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )
        candidates.firstOrNull { component ->
            val intent = Intent().setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.isSuccess
        } ?: openAppDetails(context)
    }

    /** 应用详情页：兜底入口，任何 ROM 都能到达。 */
    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
