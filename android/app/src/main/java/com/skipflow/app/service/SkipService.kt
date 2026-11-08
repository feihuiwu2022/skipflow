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
import com.skipflow.app.engine.Action
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
        /** 动作结束后再等这么久（翻页动画）才重新截图判断。 */
        private const val SETTLE_AFTER_ACTION_MS = 700L

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

    // OCR 加急和耗时统计
    private var hintPresent = false
    private var hintAt = 0L
    private var freshOcr = false
    private var lastCleanShotAt = 0L

    private val evalRunnable = Runnable {
        evalPending = false
        evaluate()
    }

    /**
     * 只在目标 App 在前台时跑：一离开（或太久没收到它的事件）就停，
     * 下一次收到目标 App 的事件时由 onAccessibilityEvent 重新启动。
     */
    private var tickerRunning = false
    private var targetInFront = false
    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            val idle = SystemClock.uptimeMillis() - lastTargetEventAt >= TICK_ACTIVE_WINDOW_MS
            if (!targetInFront || idle) {
                tickerRunning = false
                AppLog.d("轮询停止（${if (idle) "长时间无事件" else "目标 App 不在前台"}）")
                return
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        AppLog.d("轮询启动")
        handler.post(ticker)
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
        AppLog.init(this)
        KeepAlive.start(this)
        executor = ActionExecutor(this)
        audio = AudioController(this)
        ocr = ScreenOcr(
            service = this,
            isTargetForeground = { rules.forPackage(rootInActiveWindow?.packageName?.toString()) != null },
        ) {
            freshOcr = true
            evaluate()
        }
        ocr.warmUp()
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
        startTicker()
        AppLog.i("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (rules.forPackage(pkg) == null) return
        lastTargetEventAt = SystemClock.uptimeMillis()
        startTicker()
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
        KeepAlive.stop(this)
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
        val active = rootInActiveWindow
        if (active == null) {
            return leaveAd()
        }
        val pkg = active.packageName?.toString()
        val app = rules.forPackage(pkg)
        if (app == null) {
            return leaveAd()
        }
        val activity = currentActivity
        if (app.activities.isNotEmpty() && activity != null && activity !in app.activities) return leaveAd()
        targetInFront = true

        val screen = screenSize()
        val cfg = app.ocr
        val ocrNodes = cfg?.let { ocr.nodes(maxAgeMs = it.intervalMs + OCR_SLACK_MS) }.orEmpty()

        // 快速路径：刚出的 OCR 结果先只匹配"纯 OCR"规则。不用拍界面树快照——
        // 红果的树有几千个节点，vivo 上拍一次要 ~1 秒，这 1 秒就是"识别完到动手"的延迟
        if (freshOcr && ocrNodes.isNotEmpty()) {
            val ocrRoot = UiNode(cls = "#ocr-root", visible = false).apply { ocrNodes.forEach { add(it) } }
            // 按完整的优先级顺序匹配：前面的倒计时规则命中就该等，不能被后面的上滑规则抢先。
            // 只认纯 OCR 规则或"只等待"的规则——别的规则可能需要界面树里的排除条件
            val fast = RuleEngine.match(app, ocrRoot, screen)
                ?.takeIf { it.rule.ocrOnly || it.rule.action == Action.Wait }
            if (fast != null) {
                AppLog.d("OCR 快速路径命中 ${fast.rule.key}")
                freshOcr = false
                return handle(session.update(fast), screen, null, app.suspect)
            }
        }

        val root = NodeSnapshot.build(targetRoots(pkg!!, active))
        if (cfg != null) {
            // 界面树里刚出现广告迹象（不够可靠，不直接动手）：让截屏加急，省掉等下一轮的时间
            val hint = cfg.trigger != null &&
                root.walk().any { n -> n.isOnScreen(screen) && n.label?.let { cfg.trigger.containsMatchIn(it) } == true }
            if (hint && !hintPresent) {
                hintAt = SystemClock.uptimeMillis()
                ocr.urgent()
            }
            hintPresent = hint
            ocr.requestIfDue(cfg.intervalMs, cfg.regions, cfg.scale)
            ocrNodes.forEach { root.add(it) }
        }
        val match = RuleEngine.match(app, root, screen)
        if (freshOcr) {
            freshOcr = false
            if (match == null) lastCleanShotAt = ocr.timing?.requestedAt ?: 0L
        }
        handle(session.update(match), screen, root, app.suspect)
    }

    private fun handle(decision: Decision, screen: Screen, root: UiNode?, suspect: Regex?) {
        when (val d = decision) {
            Decision.Idle -> {
                if (lastStatus.isNotEmpty()) AppLog.i("广告结束")
                lastStatus = ""
                audio.restore()
                if (root != null) maybeAutoDump(suspect, root, screen)
            }
            is Decision.Waiting -> {
                status("检测到「${d.rule.name}」，等待中")
                if (d.rule.mute && Prefs.mute(this)) audio.mute()
            }
            is Decision.Act -> {
                val ok = executor.perform(d.match, screen)
                AppLog.i("「${d.match.rule.name}」第 ${d.attempt} 次处理：${if (ok) "已执行" else "执行失败"}")
                if (d.attempt == 1) logLatency()
                ocr.invalidate(settleMs = ((d.match.rule.action as? Action.Swipe)?.durationMs ?: 0L) + SETTLE_AFTER_ACTION_MS)
                if (ok && d.attempt == 1) Prefs.incHandled(this)
                lastStatus = "acted"
            }
            is Decision.GaveUp -> if (d.first) {
                AppLog.w("「${d.rule.name}」试了 ${d.rule.maxAttempts} 次仍在，停止操作，等它自行消失")
                dumpTree("gave-up")
            }
        }
    }

    /**
     * 耗时分解。广告出现的确切时刻不知道，只知道它落在"上一张没广告的截屏"和
     * "第一张有广告的截屏"之间；界面树提示可用时再给一个参考。
     */
    private fun logLatency() {
        val now = SystemClock.uptimeMillis()
        val t = ocr.timing ?: return
        val parts = mutableListOf("截屏 ${t.shotMs}", "识别 ${t.ocrMs}", "结果→动手 ${now - t.doneAt}")
        val window = if (lastCleanShotAt in 1 until t.requestedAt) "${now - t.requestedAt}~${now - lastCleanShotAt}" else "≥${now - t.requestedAt}"
        // 树里的广告节点经常一直挂着（实测能挂一分钟），太久远的提示没有参考意义
        val hint = if (hintPresent && hintAt > 0 && now - hintAt < 15_000) "，树提示→动手 ${now - hintAt}" else ""
        AppLog.i("耗时(ms)：${parts.joinToString(" + ")}；广告出现→动手 $window$hint")
    }

    private fun status(s: String) {
        if (s != lastStatus) AppLog.i(s)
        lastStatus = s
    }

    private fun leaveAd() {
        session.reset()
        if (::audio.isInitialized) audio.restore()
        lastStatus = ""
        hintPresent = false
        targetInFront = false
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
