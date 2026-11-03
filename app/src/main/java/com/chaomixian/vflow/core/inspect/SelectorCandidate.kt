// 文件: main/java/com/chaomixian/vflow/core/inspect/SelectorCandidate.kt
// 描述: 由无障碍节点生成一组候选选择器，并判断每个候选是否唯一命中。
//
// 设计要点:
// 1. 每个候选单独统计命中数：vid 候选统计同 vid 节点数、text 候选统计同文本节点数。
//    早期版本用一个「复合属性」计数给所有候选共用，结果 vid 明明唯一却被判成多命中，
//    插入时只能退回坐标——这是「获取不准」的一个主要来源；
// 2. 每个候选都用 Selector.parse 做语法校验，非法直接丢弃，避免把错字符串塞进工作流；
// 3. 唯一性统计不依赖 Transform 遍历，拾取器可以脱离触发器上下文独立工作，
//    也便于用节点上限保护主线程；
// 4. 正则候选一律用 [0-9] 这类字符类代替 \d，避免选择器字符串里的反斜杠转义歧义。

package com.chaomixian.vflow.core.inspect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.chaomixian.vflow.core.logging.DebugLogger
import li.songe.selector.Selector

/**
 * 一个候选选择器。
 *
 * @param selector 选择器字符串（GKD 语法）
 * @param label 人类可读的来源说明，显示在拾取器详情里
 * @param confidence 置信度，仅用于排序和提示，不参与匹配
 * @param hitCount 该属性在屏幕上命中的节点数；1 表示唯一
 */
data class SelectorCandidate(
    val selector: String,
    val label: String,
    val confidence: Int,
    val hitCount: Int
) {
    val unique: Boolean get() = hitCount <= 1
}

/**
 * 一次拾取的结果。
 */
class InspectResult(
    /** 点坐标命中的最小节点 */
    val target: AccessibilityNodeInfo,
    /** 沿父链找到的可点击节点；找不到时为 target 本身 */
    val clickNode: AccessibilityNodeInfo,
    /** 是否是沿父链上溯才找到的可点击节点 */
    val clickableInChain: Boolean,
    val text: String,
    val desc: String,
    val viewId: String,
    val className: String,
    val bounds: Rect,
    /** 目标整体（class + vid + text + desc）完全相同的节点数，用于提示「这屏上有多个」 */
    val sameKeyCount: Int,
    val candidates: List<SelectorCandidate>
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /** 首选候选：优先唯一命中，其次置信度最高 */
    val best: SelectorCandidate? = candidates.firstOrNull { it.unique } ?: candidates.firstOrNull()

    /** 目标是否完全没有可识别信息（无 vid / text / desc） */
    val anonymous: Boolean
        get() = viewId.isBlank() && text.isBlank() && desc.isBlank()
}

object SelectorCandidateBuilder {

    private const val TAG = "SelectorPicker"

    /** 遍历节点上限，防止异常页面把主线程卡住 */
    private const val MAX_WALK_NODES = 3000

    /** 向上找可点击祖先、锚点祖先的最大层数 */
    private const val MAX_ANCESTOR_DEPTH = 6

    /** 文本长度上限，超过的当作长正文，不适合做选择器 */
    private const val MAX_TEXT_LEN = 30

    /**
     * 对目标节点做一次完整拾取。
     *
     * @param target 命中节点
     * @param roots 所有窗口的根节点，用于统计命中数；传空列表时一律按「未知、视为唯一」处理
     */
    fun inspect(target: AccessibilityNodeInfo, roots: List<AccessibilityNodeInfo>): InspectResult {
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        val clickNode = findClickable(target)
        val text = target.text?.toString()?.trim().orEmpty()
        val desc = target.contentDescription?.toString()?.trim().orEmpty()
        val viewId = target.viewIdResourceName.orEmpty()
        val className = target.className?.toString().orEmpty()
        val counts = countAttributes(roots, target)
        val candidates = buildCandidates(
            target = target,
            clickNode = clickNode ?: target,
            text = text,
            desc = desc,
            viewId = viewId,
            className = className,
            counts = counts
        )

        return InspectResult(
            target = target,
            clickNode = clickNode ?: target,
            clickableInChain = clickNode != null && clickNode != target,
            text = text,
            desc = desc,
            viewId = viewId,
            className = className,
            bounds = bounds,
            sameKeyCount = counts.byKey,
            candidates = candidates
        )
    }

    /**
     * 给录制用的稳定标识：录制结束后节点对象可能已经失效，只留字符串。
     */
    fun fingerprint(result: InspectResult): String {
        return "${result.clickNode.className}|${result.clickNode.viewIdResourceName}|" +
            "${result.bounds.left},${result.bounds.top},${result.bounds.right},${result.bounds.bottom}"
    }

    // region 候选生成

    private fun buildCandidates(
        target: AccessibilityNodeInfo,
        clickNode: AccessibilityNodeInfo,
        text: String,
        desc: String,
        viewId: String,
        className: String,
        counts: AttributeCounts
    ): List<SelectorCandidate> {
        val out = mutableListOf<SelectorCandidate>()

        val tag = simpleName(className)
        val clickTag = simpleName(clickNode.className?.toString().orEmpty())
        val vid = shortVid(viewId)
        val clickVid = shortVid(clickNode.viewIdResourceName.orEmpty())

        fun add(selector: String, label: String, confidence: Int, hitCount: Int) {
            if (selector.isBlank()) return
            if (out.any { it.selector == selector }) return
            if (!isValidSelector(selector)) {
                DebugLogger.d(TAG, "丢弃非法选择器: $selector")
                return
            }
            out.add(SelectorCandidate(selector, label, confidence, hitCount))
        }

        // 1. 控件 ID：最稳，跨布局可用
        if (vid.isNotEmpty()) {
            add("$tag[vid=\"$vid\"]", "控件 ID", 95, counts.byVid)
        }

        // 2. 可点击父节点带 ID：目标本身不可点击时，点父节点才是对的
        if (clickVid.isNotEmpty() && clickVid != vid) {
            add("$clickTag[vid=\"$clickVid\"]", "可点击父节点 ID", 92, counts.byClickVid)
        }

        // 3. 文本 / 描述精确匹配
        if (text.isNotEmpty() && text.length <= MAX_TEXT_LEN) {
            add("$tag[text=${quote(text)}]", "文本精确", 90, counts.byText)
        }
        if (desc.isNotEmpty() && desc.length <= MAX_TEXT_LEN) {
            add("$tag[desc=${quote(desc)}]", "描述精确", 88, counts.byDesc)
        }

        // 4. 动态文案：把数字泛化成 [0-9]+，应付「跳过 5」「还剩 3 秒」这类
        regexPatternOf(text)?.let { pattern ->
            add("$tag[text~=\"$pattern\"]", "文本正则（数字泛化）", 72, counts.byText)
        }

        // 5. 父链锚点：目标自身没有标识时，用最近的带标识祖先定位
        val anchor = findAnchor(clickNode)
        if (anchor != null) {
            val (anchorSelector, depth) = anchor
            val targetSelector = when {
                text.isNotEmpty() && text.length <= MAX_TEXT_LEN -> "$tag[text=${quote(text)}]"
                desc.isNotEmpty() && desc.length <= MAX_TEXT_LEN -> "$tag[desc=${quote(desc)}]"
                vid.isNotEmpty() -> "$tag[vid=\"$vid\"]"
                clickVid.isNotEmpty() -> "$clickTag[vid=\"$clickVid\"]"
                clickTag.isNotEmpty() -> clickTag
                else -> ""
            }
            if (targetSelector.isNotEmpty()) {
                val op = if (depth == 1) ">" else ">$depth"
                add("$anchorSelector $op $targetSelector", "父链定位（上溯 $depth 层）", 68, counts.byKey)
            }
        }

        // 6. 兜底：只靠类型，命中数通常很多，仅在没有别的办法时使用
        if (clickNode.isClickable && clickTag.isNotEmpty()) {
            add("$clickTag[clickable=true]", "类型 + 可点击", 35, counts.byKey)
        }

        return out.sortedWith(
            compareByDescending<SelectorCandidate> { it.unique }
                .thenByDescending { it.confidence }
        )
    }

    /** 沿父链找最近的可点击节点；target 本身可点击时返回 null */
    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return null
        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * 向上找最近的、带 vid / text / desc 的祖先，返回选择器与层数。
     * 层数用于拼 GKD 关系运算符：1 层用 ">"，n 层用 ">n"。
     */
    private fun findAnchor(node: AccessibilityNodeInfo): Pair<String, Int>? {
        var current = node.parent
        var depth = 1
        while (current != null && depth <= MAX_ANCESTOR_DEPTH) {
            val tag = simpleName(current.className?.toString().orEmpty())
            if (tag.isNotEmpty()) {
                val vid = shortVid(current.viewIdResourceName.orEmpty())
                val text = current.text?.toString()?.trim().orEmpty()
                val desc = current.contentDescription?.toString()?.trim().orEmpty()
                val selector = when {
                    vid.isNotEmpty() -> "$tag[vid=\"$vid\"]"
                    text.isNotEmpty() && text.length <= MAX_TEXT_LEN -> "$tag[text=${quote(text)}]"
                    desc.isNotEmpty() && desc.length <= MAX_TEXT_LEN -> "$tag[desc=${quote(desc)}]"
                    else -> null
                }
                if (selector != null && isValidSelector(selector)) return selector to depth
            }
            current = current.parent
            depth++
        }
        return null
    }

    /** 把数字串泛化成 [0-9]+，其余正则元字符转义；没有数字则不生成 */
    private fun regexPatternOf(text: String): String? {
        if (text.isEmpty() || text.length > MAX_TEXT_LEN) return null
        if (text.none { it.isDigit() }) return null

        val sb = StringBuilder("^")
        var inDigit = false
        for (ch in text) {
            if (ch.isDigit()) {
                if (!inDigit) {
                    sb.append("[0-9]+")
                    inDigit = true
                }
            } else {
                inDigit = false
                if (ch in "\\^$.|?*+()[]{}") sb.append('\\').append(ch) else sb.append(ch)
            }
        }
        sb.append('$')
        return sb.toString()
    }

    private fun isValidSelector(selector: String): Boolean {
        return runCatching { Selector.parse(selector) }.isSuccess
    }

    /** 选择器里的字符串字面量，统一用双引号包裹并转义 */
    private fun quote(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n', '\r', '\t' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** android.widget.TextView -> TextView；空串原样返回 */
    private fun simpleName(className: String): String {
        if (className.isBlank()) return ""
        return if (className.contains('.')) className.substringAfterLast('.') else className
    }

    /** com.foo.bar:id/btn_skip -> btn_skip */
    private fun shortVid(viewId: String): String {
        if (viewId.isBlank()) return ""
        return when {
            viewId.contains(":id/") -> viewId.substringAfter(":id/")
            viewId.contains("/id/") -> viewId.substringAfter("/id/")
            else -> viewId.substringAfterLast('/')
        }
    }

    // endregion

    // region 命中统计

    /**
     * 每类属性的命中数。
     *
     * vid / text / desc 分别统计，而不是共用一个复合计数——
     * 复合计数会把「同 class 同文本但不同 vid」的节点也算进来，
     * 让本来唯一的 vid 候选被误判成多命中。
     */
    private class AttributeCounts(
        val byKey: Int,
        val byVid: Int,
        val byText: Int,
        val byDesc: Int,
        val byClickVid: Int
    )

    private fun countAttributes(
        roots: List<AccessibilityNodeInfo>,
        target: AccessibilityNodeInfo
    ): AttributeCounts {
        val key = keyOf(target)
        val vid = target.viewIdResourceName?.takeIf { it.isNotBlank() }
        val text = target.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = target.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val clickNode = findClickable(target) ?: target
        val clickVid = clickNode.viewIdResourceName?.takeIf { it.isNotBlank() }

        // 没有窗口信息时无法判断，按唯一处理（给出最乐观的候选）
        if (roots.isEmpty()) {
            return AttributeCounts(1, 1, 1, 1, 1)
        }

        var byKey = 0
        var byVid = 0
        var byText = 0
        var byDesc = 0
        var byClickVid = 0
        var visited = 0

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { stack.addLast(it) }

        while (stack.isNotEmpty() && visited < MAX_WALK_NODES) {
            val node = stack.removeLast()
            visited++

            val nodeVid = node.viewIdResourceName
            if (key != null && keyOf(node) == key) byKey++
            if (vid != null && nodeVid == vid) byVid++
            if (clickVid != null && nodeVid == clickVid) byClickVid++
            if (text != null && node.text?.toString() == text) byText++
            if (desc != null && node.contentDescription?.toString() == desc) byDesc++

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return AttributeCounts(
            byKey = byKey.coerceAtLeast(1),
            byVid = byVid.coerceAtLeast(1),
            byText = byText.coerceAtLeast(1),
            byDesc = byDesc.coerceAtLeast(1),
            byClickVid = byClickVid.coerceAtLeast(1)
        )
    }

    private fun keyOf(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (className.isEmpty() && viewId.isEmpty() && text.isEmpty() && desc.isEmpty()) return null
        return "$className|$viewId|$text|$desc"
    }

    // endregion
}
