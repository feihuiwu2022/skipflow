package com.skipflow.app.engine

/**
 * 广告状态机：把"每一帧的规则命中结果"变成"现在该不该动手"。
 *
 * 不用固定 sleep：广告出现的时机不固定，靠 delayMs / cooldownMs / maxAttempts
 * 在连续的快照之间计时。同一条规则持续命中视为同一个广告；规则消失或换了规则就重新计时。
 */
class AdSession(private val clock: () -> Long) {

    sealed interface Decision {
        /** 当前不是广告。 */
        data object Idle : Decision
        /** 是广告，但还没到动手的时候（等 delay / 冷却中 / 纯等待型规则）。 */
        data class Waiting(val rule: Rule) : Decision
        /** 现在执行动作。attempt 从 1 开始。 */
        data class Act(val match: RuleMatch, val attempt: Int) : Decision
        /** 试了 maxAttempts 次广告还在，放弃，等广告自己消失。first 只在第一次放弃时为 true。 */
        data class GaveUp(val rule: Rule, val first: Boolean) : Decision
    }

    var current: Rule? = null
        private set
    private var firstSeenAt = 0L
    private var lastActionAt = 0L
    private var attempts = 0
    private var gaveUp = false

    fun update(match: RuleMatch?): Decision {
        val now = clock()
        if (match == null) {
            // 动手后的冷却期内看不到广告，不急着判定结束：截图要重新拍、OCR 偶尔漏一帧。
            // 否则每次动手都会马上"结束"，attempts 清零，maxAttempts 就形同虚设。
            val rule = current
            if (rule != null && attempts > 0 && now - lastActionAt < rule.cooldownMs) return Decision.Waiting(rule)
            reset()
            return Decision.Idle
        }
        val rule = match.rule
        if (rule.key != current?.key) {
            reset()
            current = rule
            firstSeenAt = now
        }
        if (rule.action is Action.Wait) return Decision.Waiting(rule)
        if (now - firstSeenAt < rule.delayMs) return Decision.Waiting(rule)
        if (attempts > 0 && now - lastActionAt < rule.cooldownMs) return Decision.Waiting(rule)
        if (attempts >= rule.maxAttempts) {
            val first = !gaveUp
            gaveUp = true
            return Decision.GaveUp(rule, first)
        }
        attempts++
        lastActionAt = now
        return Decision.Act(match, attempts)
    }

    fun reset() {
        current = null
        firstSeenAt = 0L
        lastActionAt = 0L
        attempts = 0
        gaveUp = false
    }
}
