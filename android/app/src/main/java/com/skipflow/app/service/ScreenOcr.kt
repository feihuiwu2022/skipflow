package com.skipflow.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.skipflow.app.engine.Box
import com.skipflow.app.engine.UiNode

/**
 * 截屏 + 端侧 OCR，把识别到的文字行变成 cls="#ocr" 的虚拟节点并入界面树。
 *
 * 为什么需要：红果的广告层在无障碍树里和屏幕不一致——广告在屏幕上时节点是 hidden、高度为 0，
 * 划走之后节点却原样留在树里。只有"看屏幕"才可靠。
 *
 * 截图只在内存里识别，不落盘；只在规则里的目标 App 在前台时才截。需要 Android 11+。
 */
class ScreenOcr(private val service: AccessibilityService) {

    private class Line(val text: String, val box: Box)

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var lines: List<Line> = emptyList()
    private var capturedAt = 0L
    private var requestedAt = 0L
    private var busy = false
    /** 每次截屏 +1；看门狗重置后，迟到的旧回调凭它识别并丢弃。 */
    private var generation = 0
    private var failureLogged = false

    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 距上次截图超过 intervalMs 就再截一张（系统限制两次截图至少间隔约 1 秒）。
     * regions 是要识别的屏幕区域 [l, t, r, b]（0~1）：只识别规则关心的条带，
     * 低端机上整屏 OCR 要 1~4 秒，只识别上下两条能快好几倍。
     */
    fun requestIfDue(intervalMs: Long, regions: List<List<Double>>) {
        if (!supported) return
        val now = SystemClock.uptimeMillis()
        // 看门狗：有的 ROM 截屏回调偶尔不回来，卡住就放弃这一轮
        if (busy && now - requestedAt < BUSY_TIMEOUT_MS) return
        if (busy) Log.w(AppLog.TAG, "OCR 超时 ${now - requestedAt}ms，重置")
        if (!busy && now - requestedAt < intervalMs) return
        requestedAt = now
        busy = true
        capture(++generation, regions)
    }

    /** 最近一次识别结果（太旧就当没有），每次新建节点，便于挂到新快照的树上。 */
    fun nodes(maxAgeMs: Long): List<UiNode> {
        if (SystemClock.uptimeMillis() - capturedAt > maxAgeMs) return emptyList()
        return lines.map { UiNode(text = it.text, cls = OCR_CLASS, bounds = it.box, visible = true) }
    }

    /** 执行动作后调用：动作之前的截图已经不代表屏幕了，避免拿旧结果重复动手。 */
    fun invalidate() {
        lines = emptyList()
        capturedAt = 0L
        requestedAt = 0L
        busy = false
        generation++
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun capture(gen: Int, regions: List<List<Double>>) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val takenAt = SystemClock.uptimeMillis()
                    val screen = result.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    if (gen != generation || screen == null) {
                        screen?.recycle()
                        if (gen == generation) busy = false
                        return
                    }
                    val crops = regions.map { r ->
                        val l = (r[0] * screen.width).toInt().coerceIn(0, screen.width - 1)
                        val t = (r[1] * screen.height).toInt().coerceIn(0, screen.height - 1)
                        val w = ((r[2] - r[0]) * screen.width).toInt().coerceIn(1, screen.width - l)
                        val h = ((r[3] - r[1]) * screen.height).toInt().coerceIn(1, screen.height - t)
                        Triple(Bitmap.createBitmap(screen, l, t, w, h), l, t)
                    }
                    screen.recycle()
                    recognize(gen, crops, 0, mutableListOf(), takenAt)
                }

                override fun onFailure(errorCode: Int) {
                    if (gen == generation) busy = false
                    if (!failureLogged) {
                        failureLogged = true
                        AppLog.w("截屏失败（错误码 $errorCode），OCR 不可用")
                    }
                }
            },
        )
    }

    /** 逐个区域识别，坐标平移回整屏后合并。 */
    private fun recognize(gen: Int, crops: List<Triple<Bitmap, Int, Int>>, index: Int, acc: MutableList<Line>, takenAt: Long) {
        if (gen != generation) {
            crops.drop(index).forEach { it.first.recycle() }
            return
        }
        if (index == crops.size) {
            lines = acc
            capturedAt = takenAt
            busy = false
            return
        }
        val (bitmap, dx, dy) = crops[index]
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                text.textBlocks.flatMap { it.lines }.forEach { line ->
                    val r = line.boundingBox ?: return@forEach
                    acc += Line(line.text, Box(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy))
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                recognize(gen, crops, index + 1, acc, takenAt)
            }
    }

    companion object {
        const val OCR_CLASS = "#ocr"
        private const val BUSY_TIMEOUT_MS = 5000L
    }
}
