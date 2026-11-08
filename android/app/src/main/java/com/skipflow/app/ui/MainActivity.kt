package com.skipflow.app.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.skipflow.app.R
import com.skipflow.app.service.AppLog
import com.skipflow.app.service.Prefs
import com.skipflow.app.service.RuleRepository
import com.skipflow.app.service.SkipService

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val logListener: () -> Unit = { refreshLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        bindSwitch(R.id.enabled, Prefs.enabled(this)) { Prefs.setEnabled(this, it) }
        bindSwitch(R.id.mute, Prefs.mute(this)) { Prefs.setMute(this, it) }
        bindSwitch(R.id.auto_dump, Prefs.autoDump(this)) { Prefs.setAutoDump(this, it) }

        findViewById<Button>(R.id.dump).setOnClickListener {
            val service = SkipService.instance ?: return@setOnClickListener toast("请先开启无障碍服务")
            toast("5 秒后导出，请切到要分析的界面")
            handler.postDelayed({ service.dumpTree("manual") }, 5000)
        }
        findViewById<Button>(R.id.reload).setOnClickListener {
            SkipService.instance?.reloadRules() ?: toast("请先开启无障碍服务")
        }
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "● 服务已开启" else "○ 服务未开启：请在无障碍设置里打开「SkipFlow 广告自动处理」"
        status.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        stats.text = "已处理广告 ${Prefs.handledCount(this)} 次\n外部规则文件：${RuleRepository.overrideFile(this)?.absolutePath}"
        AppLog.addListener(logListener)
        refreshLog()
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        super.onPause()
    }

    private fun refreshLog() {
        log.text = AppLog.snapshot().joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, SkipService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
