package com.skipflow.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.skipflow.app.engine.AdSession
import com.skipflow.app.engine.AdSession.Decision
import com.skipflow.app.engine.RuleEngine
import com.skipflow.app.engine.RuleSet
import com.skipflow.app.engine.Screen
import com.skipflow.app.engine.UiNode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 核心：监听目标 App 的界面变化 → 拍界面树快照 → 规则匹配 → 状态机决策 → 执行动作。
 *
 * 触发方式有两种：
 * - 事件驱动：目标 App 的窗口/内容变化事件，节流后评估一次；
 * - 定时轮询：目标 App 在前台时每 TICK_MS 评估一次。广告的 delay/冷却要靠它推进，
 *   因为视频播放期间不一定有新的无障碍事件。
 */
class SkipService : AccessibilityService() {

    companion object {
        const val ACTION_DUMP = "com.skipflow.app.DUMP"
        const val ACTION_RELOAD = "com.skipflow.app.RELOAD"

        private const val EVAL_THROTTLE_MS = 250L
        private const val TICK_MS = 600L
        /** 超过这么久没收到目标 App 的事件，就停止轮询（省电）。 */
        private const val TICK_ACTIVE_WINDOW_MS = 60_000L
        private const val AUTO_DUMP_INTERVAL_MS = 20_000L
        private const val MAX_DUMPS = 60
        private const val OCR_SLACK_MS = 3000L

        @Volatile
        var instance: SkipService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val session = AdSession { SystemClock.uptimeMillis() }
    private lateinit var executor: ActionExecutor
    private lateinit var audio: AudioController
    private lateinit var ocr: ScreenOcr
    private var rules = RuleSet(1, emptyList())

    private var currentActivity: String? = null
    private var lastTargetEventAt = 0L
    private var lastAutoDumpAt = 0L
    private var evalPending = false
    private var lastStatus = ""

    private val evalRunnable = Runnable {
        evalPending = false
        evaluate()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - lastTargetEventAt < TICK_ACTIVE_WINDOW_MS) evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** adb 调试入口。只接受持有 DUMP 权限的发送方（adb shell 有，普通 App 没有）。 */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUMP -> dumpTree("adb")
                ACTION_RELOAD -> reloadRules()
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        KeepAlive.start(this)
        executor = ActionExecutor(this)
        audio = AudioController(this)
        ocr = ScreenOcr(this)
        reloadRules()
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP)
            addAction(ACTION_RELOAD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        }
        handler.post(ticker)
        AppLog.i("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (rules.forPackage(pkg) == null) return
        lastTargetEventAt = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateActivity(pkg, event.className?.toString())
        }
        if (!evalPending) {
            evalPending = true
            handler.postDelayed(evalRunnable, EVAL_THROTTLE_MS)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        if (::audio.isInitialized) audio.restore()
        AppLog.i("无障碍服务已停止")
        super.onDestroy()
    }

    fun reloadRules() {
        val loaded = try {
            RuleRepository.load(this)
        } catch (e: Exception) {
            AppLog.w("规则加载失败: ${e.message}")
            return
        }
        rules = loaded.rules
        // 只订阅规则里列出的 App 的事件：不读取其他 App 的任何内容
        serviceInfo = serviceInfo.apply { packageNames = loaded.rules.packages.toTypedArray() }
        leaveAd()
        val count = loaded.rules.apps.sumOf { it.rules.size }
        AppLog.i("规则已加载（${loaded.source}）：${loaded.rules.apps.size} 个 App，$count 条规则")
    }

    /** WINDOW_STATE_CHANGED 的 className 可能是弹窗或 View，只记录真正的 Activity。 */
    private fun updateActivity(pkg: String, cls: String?) {
        cls ?: return
        val isActivity = runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
        if (isActivity) currentActivity = cls
    }

    private fun evaluate() {
        if (!Prefs.enabled(this)) return leaveAd()
        val active = rootInActiveWindow ?: return leaveAd()
        val pkg = active.packageName?.toString()
        val app = rules.forPackage(pkg) ?: return leaveAd()
        val activity = currentActivity
        if (app.activities.isNotEmpty() && activity != null && activity !in app.activities) return leaveAd()

        val screen = screenSize()
        val root = NodeSnapshot.build(targetRoots(pkg!!, active))
        app.ocr?.let { cfg ->
            ocr.requestIfDue(cfg.intervalMs, cfg.regions)
            // 截屏 + 识别本身要 1~2 秒，结果按截屏时刻算龄，留足余量
            ocr.nodes(maxAgeMs = cfg.intervalMs + OCR_SLACK_MS).forEach { root.add(it) }
        }
        val match = RuleEngine.match(app, root, screen)
        when (val d = session.update(match)) {
            Decision.Idle -> {
                if (lastStatus.isNotEmpty()) AppLog.i("广告结束")
                lastStatus = ""
                audio.restore()
                maybeAutoDump(app.suspect, root, screen)
            }
            is Decision.Waiting -> {
                status("检测到「${d.rule.name}」，等待中")
                if (d.rule.mute && Prefs.mute(this)) audio.mute()
            }
            is Decision.Act -> {
                val ok = executor.perform(d.match, screen)
                ocr.invalidate()
                AppLog.i("「${d.match.rule.name}」第 ${d.attempt} 次处理：${if (ok) "已执行" else "执行失败"}")
                if (ok && d.attempt == 1) Prefs.incHandled(this)
                lastStatus = "acted"
            }
            is Decision.GaveUp -> if (d.first) {
                AppLog.w("「${d.rule.name}」试了 ${d.rule.maxAttempts} 次仍在，停止操作，等它自行消失")
                dumpTree("gave-up")
            }
        }
    }

    private fun status(s: String) {
        if (s != lastStatus) AppLog.i(s)
        lastStatus = s
    }

    private fun leaveAd() {
        session.reset()
        if (::audio.isInitialized) audio.restore()
        lastStatus = ""
    }

    /** 目标 App 的所有应用窗口（Activity + 广告弹窗），拿不到就退回到活动窗口。 */
    private fun targetRoots(pkg: String, active: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val roots = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == pkg }
        return roots.ifEmpty { listOf(active) }
    }

    private fun screenSize(): Screen {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Screen(b.width(), b.height())
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Screen(m.widthPixels, m.heightPixels)
        }
    }

    private fun maybeAutoDump(suspect: Regex?, root: UiNode, screen: Screen) {
        suspect ?: return
        if (!Prefs.autoDump(this)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoDumpAt < AUTO_DUMP_INTERVAL_MS) return
        val hit = root.walk().firstOrNull { n -> n.isOnScreen(screen) && n.label?.let { suspect.containsMatchIn(it) } == true }
            ?: return
        lastAutoDumpAt = now
        dumpTree("suspect: ${hit.label}")
    }

    /**
     * 导出当前所有窗口的界面树到
     * /sdcard/Android/data/com.skipflow.app/files/dumps/，同时生成 .txt 精简版。
     */
    fun dumpTree(reason: String): File? {
        val dir = getExternalFilesDir("dumps") ?: return null
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        if (roots.isEmpty()) {
            AppLog.w("导出失败：拿不到界面树")
            return null
        }
        val root = NodeSnapshot.build(roots)
        if (::ocr.isInitialized) ocr.nodes(maxAgeMs = 5000).forEach { root.add(it) }
        val screen = screenSize()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val json = org.json.JSONObject().apply {
            put("reason", reason)
            put("time", stamp)
            put("package", rootInActiveWindow?.packageName?.toString())
            put("activity", currentActivity)
            put("screen", "${screen.width}x${screen.height}")
            put("tree", NodeSnapshot.toJson(root))
        }
        val file = File(dir, "dump-$stamp.json").apply { writeText(json.toString(1)) }
        File(dir, "dump-$stamp.txt").writeText("# $reason | ${json.opt("package")} | $currentActivity\n" + NodeSnapshot.toOutline(root))
        dir.listFiles()?.sortedBy { it.name }?.dropLast(MAX_DUMPS * 2)?.forEach { it.delete() }
        AppLog.i("界面树已导出（$reason）：${file.name}")
        return file
    }
}
