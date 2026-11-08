package com.skipflow.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.skipflow.app.engine.Box
import com.skipflow.app.engine.Rule
import com.skipflow.app.engine.UiNode

/**
 * 截屏 + 端侧 OCR，把识别到的文字行变成 cls="#ocr" 的虚拟节点并入界面树。
 *
 * 为什么需要：红果的广告层在无障碍树里和屏幕不一致——广告在屏幕上时节点是 hidden、高度为 0，
 * 划走之后节点却原样留在树里。只有"看屏幕"才可靠。
 *
 * 截图只在内存里识别，不落盘；只在规则里的目标 App 在前台时才截。需要 Android 11+。
 */
class ScreenOcr(
    private val service: AccessibilityService,
    /**
     * 截图回来时目标 App 是否仍在前台。截屏要 0.5~2 秒，期间用户可能切到别的 App
     * （实测截到过证券 App 的界面），这种截图直接丢弃，不做识别。
     */
    private val isTargetForeground: () -> Boolean,
    /** 每次识别完成后回调（主线程），让服务立刻用新结果评估，不等下一轮轮询。 */
    private val onResult: () -> Unit,
) {

    private class Line(val text: String, val box: Box)

    /** 最近一次识别的耗时分解，用于在日志里显示"慢在哪"。 */
    class Timing(val requestedAt: Long, val shotMs: Long, val ocrMs: Long, val doneAt: Long)

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var lines: List<Line> = emptyList()
    private var capturedAt = 0L
    private var requestedAt = 0L
    private var busy = false
    /** 界面树出现了广告迹象：正在进行的截屏可能是在那之前截的，允许提前再截一张。 */
    private var urgentSince = 0L
    /** 动作后的稳定期：在此之前不截图，截于此前的结果也不用。 */
    private var settleUntil = 0L
    /** 每次截屏 +1；看门狗重置或被加急截屏顶替后，迟到的旧回调凭它识别并丢弃。 */
    private var generation = 0
    private var failureLogged = false
    /** 识别前的缩放比例。实测 720p 上缩到 0.5 会把"上滑"认成"土滑"，且没变快，默认不缩。 */
    private var scale = 1f

    var timing: Timing? = null
        private set

    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 距上次截图超过 intervalMs 就再截一张。系统限制两次截图至少间隔 1 秒。
     * regions 是要识别的屏幕区域 [l, t, r, b]（0~1）：只识别规则关心的条带，
     * 低端机上整屏 OCR 要 1~4 秒，只识别上下两条能快好几倍。
     */
    fun requestIfDue(intervalMs: Long, regions: List<List<Double>>, scale: Float) {
        if (!supported) return
        this.scale = scale
        val now = SystemClock.uptimeMillis()
        if (now < settleUntil) return
        val sinceLast = now - requestedAt
        if (busy) {
            // 加急：正在跑的那张是在广告迹象出现前截的，结果没用，满 1 秒就顶替它
            val stale = urgentSince > requestedAt && sinceLast >= MIN_SHOT_INTERVAL_MS
            // 看门狗：有的 ROM 截屏回调偶尔不回来，卡住就放弃这一轮
            val stuck = sinceLast >= BUSY_TIMEOUT_MS
            if (!stale && !stuck) return
            AppLog.d(if (stuck) "OCR 超时 ${sinceLast}ms，重置" else "OCR 加急顶替")
        } else {
            val due = sinceLast >= intervalMs || urgentSince > requestedAt
            if (!due || sinceLast < MIN_SHOT_INTERVAL_MS) return
        }
        requestedAt = now
        busy = true
        capture(++generation, regions, now)
    }

    /** 界面树里刚出现广告迹象时调用。 */
    fun urgent() {
        urgentSince = SystemClock.uptimeMillis()
    }

    /** 最近一次识别结果（太旧就当没有），每次新建节点，便于挂到新快照的树上。 */
    fun nodes(maxAgeMs: Long): List<UiNode> {
        if (SystemClock.uptimeMillis() - capturedAt > maxAgeMs) return emptyList()
        if (capturedAt < settleUntil) return emptyList()
        return lines.map { UiNode(text = it.text, cls = OCR_CLASS, bounds = it.box, visible = true) }
    }

    /**
     * 执行动作后调用：动作之前、以及动作进行中（滑动 + 翻页动画）截的图都不代表新画面。
     * 2026-11-08 实测 bug：滑动动画期间截到的图里广告还在，冷却一结束就凭这张旧图又滑了一次，漏掉一集。
     * 所以 settleMs 内既不截图，也不认这段时间内截的图。
     */
    fun invalidate(settleMs: Long) {
        settleUntil = SystemClock.uptimeMillis() + settleMs
        lines = emptyList()
        capturedAt = 0L
        // requestedAt 保留：系统要求两次截屏间隔 ≥1 秒，清零会导致下一次截屏直接失败（错误码 3）
        urgentSince = 0L
        busy = false
        generation++
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun capture(gen: Int, regions: List<List<Double>>, startedAt: Long) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val takenAt = SystemClock.uptimeMillis()
                    if (!isTargetForeground()) {
                        result.hardwareBuffer.close()
                        if (gen == generation) busy = false
                        AppLog.d("截图回来时前台已不是目标 App，丢弃")
                        return
                    }
                    val screen = result.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    if (gen != generation || screen == null) {
                        screen?.recycle()
                        if (gen == generation) busy = false
                        return
                    }
                    val stacked = Stacked.build(screen, regions, scale)
                    screen.recycle()
                    recognize(gen, stacked, startedAt, takenAt)
                }

                override fun onFailure(errorCode: Int) {
                    AppLog.d("截屏失败 gen=$gen code=$errorCode")
                    if (gen == generation) busy = false
                    // 错误码 3 = 两次截屏间隔太短，偶发、下一轮自动恢复，不算故障
                    if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !failureLogged) {
                        failureLogged = true
                        AppLog.w("截屏失败（错误码 $errorCode），OCR 可能不可用")
                    }
                }
            },
        )
    }

    /**
     * 一次识别所有区域：把各区域竖着拼成一张图。ML Kit 每次调用有固定开销，
     * 实测 vivo 上两次调用要 ~2 秒，拼成一张只付一次。
     */
    private fun recognize(gen: Int, stacked: Stacked, startedAt: Long, takenAt: Long) {
        // 只用一个 complete 回调：success 和 complete 两个监听器的执行顺序没有保证
        recognizer.process(InputImage.fromBitmap(stacked.bitmap, 0)).addOnCompleteListener { task ->
            stacked.bitmap.recycle()
            if (gen != generation) return@addOnCompleteListener
            val doneAt = SystemClock.uptimeMillis()
            lines = if (task.isSuccessful) {
                task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { r -> stacked.toScreen(r.left, r.top, r.right, r.bottom)?.let { Line(line.text, it) } }
                }
            } else {
                emptyList()
            }
            // 画面内容对应的是发起截屏的时刻，按它算结果的"年龄"
            capturedAt = startedAt
            timing = Timing(startedAt, takenAt - startedAt, doneAt - takenAt, doneAt)
            AppLog.d("OCR 完成 gen=$gen 识别 ${doneAt - takenAt}ms ${lines.size} 行: ${lines.joinToString(" | ") { it.text }.take(120)}")
            busy = false
            onResult()
        }
    }

    /** 预热：第一次识别要加载模型，实测超过 5 秒，放在服务启动时做掉。 */
    fun warmUp() {
        if (!supported) return
        val blank = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        recognizer.process(InputImage.fromBitmap(blank, 0)).addOnCompleteListener {
            blank.recycle()
            AppLog.d("OCR 模型已预热")
        }
    }

    /** 竖向拼接的区域图，以及把拼接图坐标换算回屏幕坐标。 */
    private class Stacked(val bitmap: Bitmap, private val segments: List<Segment>, private val scale: Float) {
        /** top 是该区域在拼接图里的起始 y（已缩放），srcLeft/srcTop 是它在屏幕上的位置。 */
        class Segment(val top: Int, val height: Int, val srcLeft: Int, val srcTop: Int)

        fun toScreen(l: Int, t: Int, r: Int, b: Int): Box? {
            val cy = (t + b) / 2
            val s = segments.firstOrNull { cy >= it.top && cy < it.top + it.height } ?: return null
            fun x(v: Int) = s.srcLeft + (v / scale).toInt()
            fun y(v: Int) = s.srcTop + ((v - s.top) / scale).toInt()
            return Box(x(l), y(t), x(r), y(b))
        }

        companion object {
            fun build(screen: Bitmap, regions: List<List<Double>>, scale: Float): Stacked {
                val rects = regions.map { r ->
                    val l = (r[0] * screen.width).toInt().coerceIn(0, screen.width - 1)
                    val t = (r[1] * screen.height).toInt().coerceIn(0, screen.height - 1)
                    val w = ((r[2] - r[0]) * screen.width).toInt().coerceIn(1, screen.width - l)
                    val h = ((r[3] - r[1]) * screen.height).toInt().coerceIn(1, screen.height - t)
                    Rect(l, t, l + w, t + h)
                }
                val outW = maxOf(1, (rects.maxOf { it.width() } * scale).toInt())
                val segments = mutableListOf<Segment>()
                var y = 0
                for (rect in rects) {
                    val h = maxOf(1, (rect.height() * scale).toInt())
                    segments += Segment(y, h, rect.left, rect.top)
                    y += h
                }
                val out = Bitmap.createBitmap(outW, maxOf(1, y), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                rects.forEachIndexed { i, rect ->
                    val seg = segments[i]
                    val dst = Rect(0, seg.top, (rect.width() * scale).toInt(), seg.top + seg.height)
                    canvas.drawBitmap(screen, rect, dst, paint)
                }
                return Stacked(out, segments, scale)
            }
        }
    }

    companion object {
        const val OCR_CLASS = Rule.OCR_CLASS
        private const val BUSY_TIMEOUT_MS = 5000L
        /** AccessibilityService.takeScreenshot 两次调用的最小间隔。 */
        private const val MIN_SHOT_INTERVAL_MS = 1000L
    }
}
