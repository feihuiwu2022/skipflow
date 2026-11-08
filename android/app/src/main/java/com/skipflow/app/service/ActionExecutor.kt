package com.skipflow.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.skipflow.app.engine.Action
import com.skipflow.app.engine.RuleMatch
import com.skipflow.app.engine.Screen
import com.skipflow.app.engine.UiNode

class ActionExecutor(private val service: AccessibilityService) {

    /** 返回动作是否成功派发（不代表广告已经消失，那要看下一次快照）。 */
    fun perform(match: RuleMatch, screen: Screen): Boolean = when (val a = match.rule.action) {
        is Action.Click -> click(match.nodes.getOrNull(a.target))
        is Action.Tap -> tap(a.x * screen.width, a.y * screen.height)
        is Action.Swipe -> swipe(
            a.fromX * screen.width, a.fromY * screen.height,
            a.toX * screen.width, a.toY * screen.height,
            a.durationMs,
        )
        Action.Back -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Action.Wait -> true
    }

    private fun click(node: UiNode?): Boolean {
        node ?: return false
        var n: UiNode? = node
        while (n != null) {
            val info = n.ref as? AccessibilityNodeInfo
            if (n.clickable && info?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            n = n.parent
        }
        // 自绘控件 / 不可点击的文字：直接点它的中心
        return tap(node.bounds.centerX.toDouble(), node.bounds.centerY.toDouble())
    }

    private fun tap(x: Double, y: Double): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, 60)
    }

    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(path, durationMs)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
