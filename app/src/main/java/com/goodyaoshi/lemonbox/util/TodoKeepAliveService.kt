package com.goodyaoshi.lemonbox.util

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * 提醒保活前台服务：常驻一条最低重要度的通知，把进程保持在「前台服务」状态。
 * 国产 ROM（MIUI 等）划卡清理会对普通后台进程执行 force stop，连带取消全部提醒闹钟；
 * 有前台服务的应用不在划卡强杀之列，是提醒类应用防「划卡丢提醒」的标准手段。
 * 通知本身无声、不弹横幅，只想关掉的话可在设置里关闭保活开关。
 */
class TodoKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createKeepAliveChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.KEEP_ALIVE_NOTIFICATION_ID,
            NotificationHelper.buildKeepAliveNotification(this),
            type
        )
        return START_STICKY
    }
}
