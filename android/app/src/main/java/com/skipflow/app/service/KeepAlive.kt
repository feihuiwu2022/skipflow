package com.skipflow.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.skipflow.app.ui.MainActivity

/**
 * 保活。ZTE（CpuFreezerManagerServiceV2）、MIUI 等 ROM 会冻结后台进程，
 * 被冻住的无障碍服务虽然显示"已开启"，但收不到任何事件。
 *
 * 两层：
 * 1. 前台服务 + 常驻通知；
 * 2. 一个 1×1 像素、透明、不接收触摸的无障碍悬浮窗。2026-11-08 实测 ZTE 7534N：
 *    即使放行了自启动/后台运行、开着前台服务，进程仍在离开 App 约 30 秒后被冻结；
 *    这类冻结器只看进程有没有可见窗口。无障碍服务加悬浮窗不需要额外权限。
 */
object KeepAlive {
    private const val CHANNEL = "keepalive"
    private const val NOTIFICATION_ID = 1

    private var overlay: View? = null

    fun start(service: AccessibilityService) {
        startForeground(service)
        addOverlay(service)
    }

    fun stop(service: AccessibilityService) {
        overlay?.let { v -> runCatching { service.getSystemService(WindowManager::class.java).removeView(v) } }
        overlay = null
    }

    private fun startForeground(service: Service) {
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

    private fun addOverlay(service: AccessibilityService) {
        if (overlay != null) return
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val view = View(service)
        try {
            service.getSystemService(WindowManager::class.java).addView(view, params)
            overlay = view
        } catch (e: Exception) {
            AppLog.w("保活悬浮窗添加失败：${e.message}")
        }
    }
}
