package com.skipflow.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.skipflow.app.engine.Box
import com.skipflow.app.engine.UiNode
import org.json.JSONArray
import org.json.JSONObject

/** AccessibilityNodeInfo 树 → UiNode 快照，以及快照 → JSON（调试导出用）。 */
object NodeSnapshot {
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 80

    /** 多个窗口（Activity + 弹窗）合并到一个虚拟根节点下。 */
    fun build(roots: List<AccessibilityNodeInfo>): UiNode {
        val top = UiNode(cls = "#windows", visible = false)
        val budget = intArrayOf(MAX_NODES)
        for (r in roots) top.add(convert(r, 0, budget))
        return top
    }

    private fun convert(info: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode {
        budget[0]--
        val rect = Rect().also { info.getBoundsInScreen(it) }
        val node = UiNode(
            text = info.text?.toString(),
            desc = info.contentDescription?.toString(),
            id = info.viewIdResourceName,
            cls = info.className?.toString(),
            bounds = Box(rect.left, rect.top, rect.right, rect.bottom),
            clickable = info.isClickable,
            visible = info.isVisibleToUser,
            ref = info,
        )
        if (depth < MAX_DEPTH) {
            for (i in 0 until info.childCount) {
                if (budget[0] <= 0) break
                val child = info.getChild(i) ?: continue
                node.add(convert(child, depth + 1, budget))
            }
        }
        return node
    }

    fun toJson(node: UiNode): JSONObject = JSONObject().apply {
        node.cls?.let { put("cls", it.substringAfterLast('.')) }
        node.id?.let { put("id", it) }
        node.text?.let { put("text", it) }
        node.desc?.let { put("desc", it) }
        val b = node.bounds
        put("bounds", "[${b.left},${b.top}][${b.right},${b.bottom}]")
        if (node.clickable) put("clickable", true)
        if (!node.visible) put("visible", false)
        if (node.children.isNotEmpty()) {
            put("children", JSONArray().apply { node.children.forEach { put(toJson(it)) } })
        }
    }

    /** 一行一个节点的精简文本，方便在 logcat / 文件里肉眼看。只列出有文字或 id 的节点。 */
    fun toOutline(root: UiNode): String = buildString {
        fun visit(n: UiNode, depth: Int) {
            if (n.label != null || n.id != null) {
                append("  ".repeat(depth.coerceAtMost(20)))
                append(n.cls?.substringAfterLast('.') ?: "?")
                n.id?.let { append(" #").append(it.substringAfter(":id/")) }
                n.text?.let { append(" text=\"").append(it).append('"') }
                n.desc?.let { append(" desc=\"").append(it).append('"') }
                val b = n.bounds
                append(" [${b.left},${b.top}][${b.right},${b.bottom}]")
                if (n.clickable) append(" clk")
                if (!n.visible) append(" hidden")
                append('\n')
            }
            n.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
    }
}
