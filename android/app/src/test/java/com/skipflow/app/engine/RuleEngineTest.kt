package com.skipflow.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleEngineTest {
    private val screen = Screen(720, 1600)
    // 单测工作目录是 android/app，规则文件在仓库根 rules/ 下
    private val app = RuleSet.parse(File("../../rules/default.json").readText()).forPackage("com.phoenix.read")!!

    private fun n(text: String? = null, desc: String? = null, id: String? = null, b: Box, visible: Boolean = true, clickable: Boolean = false) =
        UiNode(text = text, desc = desc, id = id?.let { "com.phoenix.read:id/$it" }, bounds = b, visible = visible, clickable = clickable)

    /** 2026-11-08 在 ZTE 7534N 上抓到的「上滑继续观看短剧」广告（samples/hongguo-ad-swipe.*）。 */
    private fun swipeAdTree(visible: Boolean = true) = UiNode(cls = "FrameLayout", bounds = Box(0, 0, 720, 1555)).apply {
        add(UiNode(id = "com.phoenix.read:id/l0", bounds = Box(0, 0, 720, 1555)).apply {
            add(n(desc = "爱聊", b = Box(152, 1140, 216, 1184), visible = visible))
            add(n(desc = "商家推荐", b = Box(164, 1200, 260, 1240), visible = visible))
            add(n(desc = "精彩应用", b = Box(300, 1200, 396, 1240), visible = visible))
        })
        add(n(text = "上滑继续观看短剧", id = "aq9", b = Box(272, 1524, 496, 1555), visible = visible))
        add(n(text = "返回", id = "kfs", b = Box(88, 66, 688, 154)))
    }

    private fun episodeTree() = UiNode(cls = "FrameLayout", bounds = Box(0, 0, 720, 1555)).apply {
        add(n(text = "第3集", b = Box(88, 66, 300, 154)))
        add(n(text = "全屏观看", b = Box(272, 990, 448, 1040), clickable = true))
        add(n(text = "选集 · 已完结 · 全139集", b = Box(32, 1500, 600, 1580), clickable = true))
    }

    /** 屏幕 OCR 识别到的文字行（ScreenOcr 生成的虚拟节点）。 */
    private fun ocr(text: String, b: Box) = UiNode(text = text, cls = "#ocr", bounds = b)

    @Test fun swipeAdIsDetectedFromOcr() {
        // 2026-11-08 18:44 实测：广告在屏幕上时，树里的广告节点是 hidden，只有 OCR 能看到
        val root = swipeAdTree(visible = false).add(ocr("上滑继续观看短剧", Box(272, 1520, 496, 1556)))
        val m = RuleEngine.match(app, root, screen)
        assertEquals("swipe-continue", m?.rule?.key)
        assertTrue(m!!.rule.action is Action.Swipe)
    }

    @Test fun swipeToleratesOcrTypos() {
        // 2026-11-08 vivo 实测 OCR 结果：一个字认错很常见
        for (text in listOf("土滑继续观看短剧 会", "上滑继续观看短剧公", "上滑继续观春短剧会", "上清继续观看短剧")) {
            val root = episodeTree().add(ocr(text, Box(228, 1524, 452, 1562)))
            assertEquals(text, "swipe-continue", RuleEngine.match(app, root, screen)?.rule?.key)
        }
    }

    @Test fun staleAdNodesInTreeAloneDoNotTriggerSwipe() {
        // 2026-11-08 18:12 实测：划到正片后，广告节点仍以"可见"状态留在树里
        assertNull(RuleEngine.match(app, swipeAdTree(visible = true), screen))
    }

    @Test fun countdownVariantWaitsInsteadOfSwiping() {
        // 2026-11-08 19:27 vivo 实测：带倒计时的上滑广告，倒计时期间滑不动
        for (text in listOf("7秒后可继续上滑观看短剧 会", "5秒后可继续止滑观看短剧会", "4秒后可继续上滑观看短剧会")) {
            val ocrOnlyRoot = UiNode(visible = false).add(ocr(text, Box(150, 1524, 560, 1562)))
            val m = RuleEngine.match(app, ocrOnlyRoot, screen)
            assertEquals(text, "countdown", m?.rule?.key)
            assertEquals(Action.Wait, m!!.rule.action)
        }
        // 倒计时结束后照常上滑
        val ready = UiNode(visible = false).add(ocr("上滑继续观看短剧 会", Box(228, 1524, 452, 1562)))
        assertEquals("swipe-continue", RuleEngine.match(app, ready, screen)?.rule?.key)
    }

    @Test fun normalEpisodeBottomBarOcrDoesNotSwipe() {
        // 正片底部 OCR 实测内容
        for (text in listOf("选集·已完结·全1000集", "0作者声明:内容由AI生成", "分享")) {
            val root = episodeTree().add(ocr(text, Box(32, 1500, 600, 1580)))
            assertNull(text, RuleEngine.match(app, root, screen))
        }
    }

    @Test fun ocrTextOutsideBottomRegionIsIgnored() {
        val root = episodeTree().add(ocr("上滑继续观看短剧", Box(272, 600, 496, 636)))
        assertNull(RuleEngine.match(app, root, screen))
    }

    @Test fun swipeActsImmediatelyAndOcrHasTrigger() {
        val swipe = app.rules.first { it.key == "swipe-continue" }
        assertEquals(0L, swipe.delayMs)
        val ocr = app.ocr!!
        assertEquals(1000L, ocr.intervalMs)
        assertTrue(ocr.trigger!!.containsMatchIn("上滑继续观看短剧"))
        assertTrue(ocr.trigger!!.containsMatchIn("广告"))
        assertTrue(!ocr.trigger!!.containsMatchIn("免广告"))
    }

    @Test fun onlyPureOcrRulesTakeTheFastPath() {
        val byKey = app.rules.associateBy { it.key }
        assertTrue(byKey.getValue("swipe-continue").ocrOnly)
        // 跳过按钮要靠界面树里的"Xs后"排除，不能只看 OCR
        assertTrue(!byKey.getValue("skip-button").ocrOnly)
    }

    @Test fun normalEpisodeIsIgnored() {
        assertNull(RuleEngine.match(app, episodeTree(), screen))
    }

    @Test fun offscreenOrHiddenNodesAreIgnored() {
        val hidden = UiNode(bounds = Box(0, 0, 720, 1555)).add(n(text = "跳过", b = Box(600, 80, 700, 130), visible = false))
        assertNull(RuleEngine.match(app, hidden, screen))
        val offscreen = UiNode(bounds = Box(0, 0, 720, 1555)).add(n(text = "跳过", b = Box(600, 1700, 700, 1750)))
        assertNull(RuleEngine.match(app, offscreen, screen))
    }

    @Test fun skipButtonClickedOnlyWhenNoCountdown() {
        val ready = UiNode(bounds = Box(0, 0, 720, 1555)).add(n(text = "跳过", b = Box(600, 80, 700, 130), clickable = true))
        assertEquals("skip-button", RuleEngine.match(app, ready, screen)?.rule?.key)

        val counting = UiNode(bounds = Box(0, 0, 720, 1555))
            .add(n(text = "跳过", b = Box(600, 80, 700, 130)))
            .add(n(text = "5s后可跳过", b = Box(500, 80, 600, 130)))
        assertEquals("countdown", RuleEngine.match(app, counting, screen)?.rule?.key)
    }

    @Test fun selectorShortIdAndRegion() {
        val node = n(text = "x", id = "aq9", b = Box(272, 1524, 496, 1555))
        assertTrue(Selector(id = "aq9").matches(node, screen))
        assertTrue(Selector(id = "com.phoenix.read:id/aq9").matches(node, screen))
        assertTrue(Selector(region = listOf(0.0, 0.9, 1.0, 1.0)).matches(node, screen))
        assertTrue(!Selector(region = listOf(0.0, 0.0, 1.0, 0.5)).matches(node, screen))
    }
}
