package com.chaomixian.vflow.core.module

import li.songe.selector.FastQuery
import li.songe.selector.property.CompareOperator
import li.songe.selector.MatchOption
import li.songe.selector.QueryContext
import li.songe.selector.Selector
import li.songe.selector.Transform
import org.junit.Test

/**
 * selector 语义探针：确认连接符方向、属性匹配能力。
 * 目的只有一个 —— 回答「能不能用 selector 判断节点是否身处广告容器内」。
 *
 * 实测结论（2026-10-31，树结构见 buildTree）：
 *   [text*="跳过"]                          -> 跳过 3 + 跳过片头（两条都中）
 *   [name*="openadsdk"] > [text*="跳过"]    -> 仅 跳过 3
 *   [text*="跳过"] << [name*="openadsdk"]   -> 返回广告容器节点本身
 *   [text*="跳过"] > [name*="openadsdk"]    -> 空
 *
 * 即：`[祖先条件] > [目标条件]` 表示「目标节点须位于匹配祖先条件的子树内」，
 * 返回目标节点。可以用它把「跳过」限定在广告 SDK 容器里。
 * 局限：容器类名随 SDK 而变（openadsdk / qq.e / kwad / mobads …），
 * 且同窗口内的开屏广告不一定有带包名的容器。
 * 注：窗口层级（主窗口 / 浮层）在不同 ROM 上不可靠，已不再作为判据。
 */
class SelectorSemanticsProbeTest {

    private class N(
        val cls: String?,
        val text: String? = null,
        val desc: String? = null,
        val id: String? = null,
    ) {
        var parent: N? = null
        val children = mutableListOf<N>()
        fun add(vararg c: N): N {
            c.forEach { it.parent = this; children.add(it) }
            return this
        }
        override fun toString() = "N(cls=$cls, text=$text)"
    }

    private fun attrOf(target: Any?, name: String): Any? {
        val t = target as? N ?: return null
        return when (name) {
            "text" -> t.text
            "desc", "contentDescription" -> t.desc
            "id", "viewId" -> t.id
            "name", "class", "className" -> t.cls
            else -> null
        }
    }

    private val transform = Transform<N>(
        getAttr = { target, name ->
            when (target) {
                is QueryContext<*> -> when (name) {
                    "prev" -> target.prev
                    "current" -> target.current
                    else -> attrOf(target.current, name)
                }
                is N -> attrOf(target, name)
                else -> null
            }
        },
        getName = { it.cls },
        getChildren = { it.children.asSequence() },
        getParent = { it.parent },
        traverseFastQueryDescendants = { root, fastQueryList ->
            sequence {
                val stack = mutableListOf(root)
                while (stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.size - 1)
                    var match = true
                    for (fq in fastQueryList) {
                        if (fq is FastQuery.Text) {
                            val v = node.text ?: ""
                            val ok = when (fq.operator) {
                                is CompareOperator.Equal -> v == fq.value
                                is CompareOperator.NotEqual -> v != fq.value
                                is CompareOperator.Start -> v.startsWith(fq.value)
                                is CompareOperator.Include -> v.contains(fq.value)
                                is CompareOperator.End -> v.endsWith(fq.value)
                                else -> false
                            }
                            if (!ok) { match = false; break }
                        }
                    }
                    if (match) yield(node)
                    for (i in node.children.size - 1 downTo 0) stack.add(node.children[i])
                }
            }
        }
    )

    /**
     * 树结构：
     * root
     *  ├─ 广告容器 (cls 含 openadsdk)
     *  │    ├─ skipAd  text="跳过 3"
     *  │    └─ adTag   text="广告"
     *  └─ 正文 "跳过片头"
     */
    private fun buildTree(): Pair<N, N> {
        val root = N("android.widget.FrameLayout")
        val adContainer = N("com.bytedance.sdk.openadsdk.core.nativeexpress.NativeExpressView")
        val skipAd = N("android.widget.TextView", text = "跳过 3")
        val adTag = N("android.widget.TextView", text = "广告")
        adContainer.add(skipAd, adTag)
        val normal = N("android.widget.TextView", text = "跳过片头")
        root.add(adContainer, normal)
        return root to skipAd
    }

    private fun query(source: String, fastQuery: Boolean = true): List<String> {
        val (root, _) = buildTree()
        val sel = try {
            Selector.parse(source)
        } catch (e: Exception) {
            return listOf("PARSE_FAIL: ${e.message}")
        }
        return transform.querySelectorAll(root, sel, MatchOption(fastQuery = fastQuery))
            .map { "${it.text}|${it.cls}" }
            .toList()
    }

    @Test
    fun probe() {
        val cases = listOf(
            "[text*=\"跳过\"]",
            "[text*=\"跳过\"] << [name*=\"openadsdk\"]",
            "[name*=\"openadsdk\"] << [text*=\"跳过\"]",
            "[text*=\"跳过\"] > [name*=\"openadsdk\"]",
            "[name*=\"openadsdk\"] > [text*=\"跳过\"]",
            "[text*=\"跳过\"] < [name*=\"openadsdk\"]",
            "[name*=\"openadsdk\"] < [text*=\"跳过\"]",
            "[text*=\"跳过\" && name*=\"TextView\"]",
        )
        val sb = StringBuilder()
        sb.appendLine("========== selector 语义探针 ==========")
        cases.forEach { src ->
            val r = query(src)
            sb.appendLine(src)
            sb.appendLine("    -> " + r.ifEmpty { listOf("<空>") }.joinToString(" ;; "))
        }
        sb.appendLine("=======================================")
        println(sb)
    }
}
