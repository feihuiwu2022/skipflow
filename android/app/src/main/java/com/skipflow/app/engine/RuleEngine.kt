package com.skipflow.app.engine

/** [nodes] 与 rule.all 一一对应，是每个选择器命中的节点。 */
class RuleMatch(val rule: Rule, val nodes: List<UiNode>)

object RuleEngine {
    /** 按规则顺序返回第一条命中的规则；规则顺序就是优先级。 */
    fun match(app: AppRules, root: UiNode, screen: Screen): RuleMatch? {
        for (rule in app.rules) {
            if (!rule.enabled) continue
            val nodes = ArrayList<UiNode>(rule.all.size)
            for (sel in rule.all) nodes += sel.find(root, screen) ?: break
            if (nodes.size != rule.all.size) continue
            if (rule.any.isNotEmpty() && rule.any.none { it.find(root, screen) != null }) continue
            if (rule.none.any { it.find(root, screen) != null }) continue
            return RuleMatch(rule, nodes)
        }
        return null
    }
}
