package com.skipflow.app.engine

import org.json.JSONArray
import org.json.JSONObject

sealed interface Action {
    /** 点击 rule.all[target] 命中的节点（自身不可点就向上找可点击的祖先，再不行按坐标点）。 */
    data class Click(val target: Int) : Action
    /** 按屏幕比例坐标点击。 */
    data class Tap(val x: Double, val y: Double) : Action
    /** 按屏幕比例坐标滑动。红果实测：250ms 的快滑会被忽略，500ms 才生效。 */
    data class Swipe(val fromX: Double, val fromY: Double, val toX: Double, val toY: Double, val durationMs: Long) : Action
    data object Back : Action
    /** 只识别不操作（倒计时广告），通常配合 mute 使用。 */
    data object Wait : Action

    companion object {
        fun fromJson(o: JSONObject): Action = when (val type = o.getString("type")) {
            "click" -> Click(o.optInt("target", 0))
            "tap" -> o.getJSONArray("at").let { Tap(it.getDouble(0), it.getDouble(1)) }
            "swipe" -> {
                val from = o.getJSONArray("from")
                val to = o.getJSONArray("to")
                Swipe(from.getDouble(0), from.getDouble(1), to.getDouble(0), to.getDouble(1), o.optLong("durationMs", 500))
            }
            "back" -> Back
            "wait" -> Wait
            else -> throw IllegalArgumentException("未知动作类型: $type")
        }
    }
}

/**
 * 一条规则：all 里每个选择器都要命中，any 至少命中一个（为空则忽略），none 一个都不能命中。
 * 命中后先等 delayMs（给广告"可以跳过"留出时间），执行动作后 cooldownMs 内不重复，
 * 最多尝试 maxAttempts 次——防止界面树没刷新时连续误滑掉正片。
 */
class Rule(
    val key: String,
    val name: String,
    val enabled: Boolean = true,
    val all: List<Selector>,
    val any: List<Selector> = emptyList(),
    val none: List<Selector> = emptyList(),
    val action: Action,
    val delayMs: Long = 0,
    val cooldownMs: Long = 2000,
    val maxAttempts: Int = 2,
    val mute: Boolean = false,
) {
    /** 只依赖屏幕 OCR 的规则：不用拍界面树快照就能判断，走快速路径。 */
    val ocrOnly: Boolean get() = (all + any + none).all { it.cls == OCR_CLASS }

    companion object {
        const val OCR_CLASS = "#ocr"

        fun fromJson(o: JSONObject) = Rule(
            key = o.getString("key"),
            name = o.optString("name", o.getString("key")),
            enabled = o.optBoolean("enabled", true),
            all = selectors(o.optJSONArray("all")),
            any = selectors(o.optJSONArray("any")),
            none = selectors(o.optJSONArray("none")),
            action = Action.fromJson(o.getJSONObject("action")),
            delayMs = o.optLong("delayMs", 0),
            cooldownMs = o.optLong("cooldownMs", 2000),
            maxAttempts = o.optInt("maxAttempts", 2),
            mute = o.optBoolean("mute", false),
        ).also { require(it.all.isNotEmpty()) { "规则 ${it.key} 的 all 不能为空" } }

        private fun selectors(a: JSONArray?): List<Selector> =
            if (a == null) emptyList() else List(a.length()) { Selector.fromJson(a.getJSONObject(it)) }
    }
}

class AppRules(
    val packageName: String,
    val name: String,
    /** 只在这些 Activity 里生效；为空表示整个 App。 */
    val activities: List<String>,
    /** 没有规则命中、但屏幕上出现这些字样时自动导出界面树，用来收集新广告样式。 */
    val suspect: Regex?,
    /** 非空时开启截屏 OCR。识别出的文字行以 cls="#ocr" 的节点出现在树里。 */
    val ocr: OcrConfig?,
    val rules: List<Rule>,
)

/**
 * regions：只识别这些屏幕区域 [l, t, r, b]（0~1），默认整屏。
 * trigger：界面树里一出现匹配的文字就加急截屏（树不可靠，只用来提速，不用来动手）。
 * scale：识别前缩放比例，字大的界面用 0.5 能快好几倍。
 */
class OcrConfig(
    val intervalMs: Long,
    val regions: List<List<Double>>,
    val trigger: Regex? = null,
    val scale: Float = 1f,
) {
    companion object {
        private val FULL_SCREEN = listOf(listOf(0.0, 0.0, 1.0, 1.0))

        fun fromJson(o: JSONObject): OcrConfig {
            val a = o.optJSONArray("regions")
            val regions = if (a == null) FULL_SCREEN else List(a.length()) { i ->
                val r = a.getJSONArray(i)
                List(4) { r.getDouble(it) }
            }
            return OcrConfig(
                intervalMs = o.optLong("intervalMs", 1000),
                regions = regions,
                trigger = o.optStringOrNull("trigger")?.toRegex(),
                scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.2f, 1f),
            )
        }
    }
}

class RuleSet(val version: Int, val apps: List<AppRules>) {
    fun forPackage(pkg: String?): AppRules? = apps.firstOrNull { it.packageName == pkg }
    val packages: List<String> get() = apps.map { it.packageName }

    companion object {
        fun parse(json: String): RuleSet {
            val root = JSONObject(json)
            val apps = root.getJSONArray("apps")
            return RuleSet(
                version = root.optInt("version", 1),
                apps = List(apps.length()) { i ->
                    val a = apps.getJSONObject(i)
                    val rules = a.getJSONArray("rules")
                    val activities = a.optJSONArray("activities")
                    AppRules(
                        packageName = a.getString("package"),
                        name = a.optString("name", a.getString("package")),
                        activities = if (activities == null) emptyList() else List(activities.length()) { activities.getString(it) },
                        suspect = a.optStringOrNull("suspect")?.toRegex(),
                        ocr = a.optJSONObject("ocr")?.let { OcrConfig.fromJson(it) },
                        rules = List(rules.length()) { Rule.fromJson(rules.getJSONObject(it)) },
                    )
                },
            )
        }
    }
}
