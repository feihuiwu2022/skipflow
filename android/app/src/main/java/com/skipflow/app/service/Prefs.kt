package com.skipflow.app.service

import android.content.Context

object Prefs {
    private const val FILE = "skipflow"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUTE = "mute"
    private const val KEY_AUTO_DUMP = "auto_dump"
    private const val KEY_HANDLED = "handled_count"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_ENABLED, v).apply()

    /** 默认关闭：用户反馈广告期间不需要静音，想要的可以在主界面打开。 */
    fun mute(c: Context) = sp(c).getBoolean(KEY_MUTE, false)
    fun setMute(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_MUTE, v).apply()

    /** 碰到疑似广告但没有规则命中时，自动导出界面树（用于补规则）。 */
    fun autoDump(c: Context) = sp(c).getBoolean(KEY_AUTO_DUMP, true)
    fun setAutoDump(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AUTO_DUMP, v).apply()

    fun handledCount(c: Context) = sp(c).getInt(KEY_HANDLED, 0)
    fun incHandled(c: Context) = sp(c).edit().putInt(KEY_HANDLED, handledCount(c) + 1).apply()
}
