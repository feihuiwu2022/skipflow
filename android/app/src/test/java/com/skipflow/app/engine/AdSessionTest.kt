package com.skipflow.app.engine

import com.skipflow.app.engine.AdSession.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSessionTest {
    private var now = 0L
    private val session = AdSession { now }

    private fun rule(key: String = "r", action: Action = Action.Back, delay: Long = 800, cooldown: Long = 2500, max: Int = 2) =
        Rule(key = key, name = key, all = listOf(Selector(text = "x")), action = action, delayMs = delay, cooldownMs = cooldown, maxAttempts = max)

    private fun hit(r: Rule) = RuleMatch(r, emptyList())

    @Test fun waitsForDelayThenActsWithCooldownAndGivesUp() {
        val r = rule()
        assertTrue(session.update(hit(r)) is Decision.Waiting)        // t=0 刚出现
        now = 500; assertTrue(session.update(hit(r)) is Decision.Waiting)
        now = 900; assertEquals(1, (session.update(hit(r)) as Decision.Act).attempt)
        now = 2000; assertTrue(session.update(hit(r)) is Decision.Waiting) // 冷却中
        now = 3500; assertEquals(2, (session.update(hit(r)) as Decision.Act).attempt)
        now = 6100
        assertEquals(true, (session.update(hit(r)) as Decision.GaveUp).first)
        now = 6700
        assertEquals(false, (session.update(hit(r)) as Decision.GaveUp).first)
    }

    @Test fun adDisappearingResetsTheSessionAfterCooldown() {
        val r = rule(delay = 0)
        assertTrue(session.update(hit(r)) is Decision.Act)
        now = 3000
        assertEquals(Decision.Idle, session.update(null))
        now = 3100
        assertEquals(1, (session.update(hit(r)) as Decision.Act).attempt)
    }

    @Test fun missingFramesDuringCooldownDoNotResetAttempts() {
        // 动手后 OCR 结果被清空，冷却期内看不到广告不算结束；广告还在就记作第 2 次
        val r = rule(delay = 0)
        assertTrue(session.update(hit(r)) is Decision.Act)
        now = 500; assertTrue(session.update(null) is Decision.Waiting)
        now = 2600; assertEquals(2, (session.update(hit(r)) as Decision.Act).attempt)
        now = 5200; assertTrue(session.update(hit(r)) is Decision.GaveUp)
    }

    @Test fun switchingRuleRestartsTimer() {
        val countdown = rule("countdown", action = Action.Wait)
        val skip = rule("skip", delay = 300)
        assertTrue(session.update(hit(countdown)) is Decision.Waiting)
        now = 5000; assertTrue(session.update(hit(countdown)) is Decision.Waiting) // Wait 型永远不动手
        now = 5100; assertTrue(session.update(hit(skip)) is Decision.Waiting)      // 换成跳过按钮，重新计 delay
        now = 5400; assertTrue(session.update(hit(skip)) is Decision.Act)
    }
}
