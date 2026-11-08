package com.skipflow.app.engine

/** 屏幕坐标矩形。不用 android.graphics.Rect，是为了让引擎能在纯 JVM 单测里跑。 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun isEmpty() = width <= 0 || height <= 0
}

data class Screen(val width: Int, val height: Int)

/**
 * 界面树快照里的一个节点。设备上由 NodeSnapshot 从 AccessibilityNodeInfo 构建，
 * 单测里直接手写。[ref] 在设备上指向原始的 AccessibilityNodeInfo，用来执行点击。
 */
class UiNode(
    val text: String? = null,
    val desc: String? = null,
    val id: String? = null,
    val cls: String? = null,
    val bounds: Box = Box(0, 0, 0, 0),
    val clickable: Boolean = false,
    val visible: Boolean = true,
    val ref: Any? = null,
) {
    var parent: UiNode? = null
        private set
    private val _children = mutableListOf<UiNode>()
    val children: List<UiNode> get() = _children

    fun add(child: UiNode): UiNode {
        child.parent = this
        _children += child
        return this
    }

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in _children) yieldAll(c.walk())
    }

    /** 真的在屏幕上：可见、非空、且与屏幕相交。ViewPager 里预加载的离屏页面会被这里过滤掉。 */
    fun isOnScreen(screen: Screen): Boolean =
        visible && !bounds.isEmpty() &&
            bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < screen.width && bounds.top < screen.height

    /** 文本优先，没有就用 content-desc。字节系 App 很多文字只放在 desc 里。 */
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }

    override fun toString() = "UiNode(${id ?: cls}, label=$label, $bounds)"
}
