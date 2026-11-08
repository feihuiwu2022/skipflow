package com.skipflow.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.skipflow.app.ui.MainActivity

/**
 * 把无障碍服务提升为前台服务。ZTE（CpuFreezerManagerServiceV2）、MIUI 等 ROM 会冻结后台进程，
 * 被冻住的无障碍服务虽然显示"已开启"，但收不到任何事件；前台服务不会被冻结。
 */
object KeepAlive {
    private const val CHANNEL = "keepalive"
    private const val NOTIFICATION_ID = 1

    fun start(service: Service) {
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_ff)
            .setContentTitle("SkipFlow 运行中")
            .setContentText("看短剧时自动处理广告")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLog.w("前台服务启动失败（可能被系统冻结）：${e.message}")
        }
    }
}
