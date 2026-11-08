package com.skipflow.app.engine

import org.json.JSONObject

/**
 * 选择器：所有给出的条件同时满足才算命中（AND）。
 *
 * - id：完整 id（com.x:id/abc）或只写短名（abc）
 * - text / desc / label：包含匹配；label 同时看 text 和 content-desc
 * - xxxRegex：正则 containsMatchIn
 * - cls：类名后缀匹配，如 "TextView"
 * - region：[l, t, r, b]，0~1 的屏幕比例，节点中心点须落在其中
 */
class Selector(
    val id: String? = null,
    val text: String? = null,
    val textRegex: Regex? = null,
    val desc: String? = null,
    val descRegex: Regex? = null,
    val label: String? = null,
    val labelRegex: Regex? = null,
    val cls: String? = null,
    val clickable: Boolean? = null,
    val region: List<Double>? = null,
) {
    fun matches(n: UiNode, screen: Screen): Boolean {
        val nodeText = n.text
        val nodeDesc = n.desc
        if (id != null && !idMatches(n.id)) return false
        if (text != null && nodeText?.contains(text) != true) return false
        if (textRegex != null && (nodeText == null || !textRegex.containsMatchIn(nodeText))) return false
        if (desc != null && nodeDesc?.contains(desc) != true) return false
        if (descRegex != null && (nodeDesc == null || !descRegex.containsMatchIn(nodeDesc))) return false
        if (label != null && nodeText?.contains(label) != true && nodeDesc?.contains(label) != true) return false
        if (labelRegex != null && listOfNotNull(nodeText, nodeDesc).none { labelRegex.containsMatchIn(it) }) return false
        if (cls != null && n.cls?.endsWith(cls) != true) return false
        if (clickable != null && n.clickable != clickable) return false
        if (region != null) {
            val cx = n.bounds.centerX.toDouble() / screen.width
            val cy = n.bounds.centerY.toDouble() / screen.height
            if (cx < region[0] || cy < region[1] || cx > region[2] || cy > region[3]) return false
        }
        return true
    }

    /** 在整棵树里找第一个"在屏幕上"且命中的节点。 */
    fun find(root: UiNode, screen: Screen): UiNode? =
        root.walk().firstOrNull { it.isOnScreen(screen) && matches(it, screen) }

    private fun idMatches(nodeId: String?): Boolean =
        nodeId != null && (nodeId == id || nodeId.endsWith(":id/$id"))

    companion object {
        fun fromJson(o: JSONObject) = Selector(
            id = o.optStringOrNull("id"),
            text = o.optStringOrNull("text"),
            textRegex = o.optStringOrNull("textRegex")?.toRegex(),
            desc = o.optStringOrNull("desc"),
            descRegex = o.optStringOrNull("descRegex")?.toRegex(),
            label = o.optStringOrNull("label"),
            labelRegex = o.optStringOrNull("labelRegex")?.toRegex(),
            cls = o.optStringOrNull("cls"),
            clickable = if (o.has("clickable")) o.getBoolean("clickable") else null,
            region = o.optJSONArray("region")?.let { a -> List(a.length()) { a.getDouble(it) } },
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
