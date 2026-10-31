package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import com.chaomixian.vflow.core.workflow.model.TriggerSpec

/**
 * 触发器参数指纹。
 *
 * 触发器状态（选择器、冷却、规则集等）是缓存在 handler 里的，改工作流参数后必须重建，
 * 否则要重启无障碍服务才生效。这里把参数序列化成稳定字符串取哈希：
 * Map 的 key 顺序不保证一致，所以先按 key 排序再拼接。
 */
internal fun TriggerSpec.fingerprint(): Int {
    var h = 17
    parameters.entries.sortedBy { it.key }.forEach { (k, v) ->
        h = 31 * h + k.hashCode()
        h = 31 * h + (v?.hashCode() ?: 0)
    }
    return h
}

/**
 * 单次检查内的选择器查询缓存键：窗口根节点 + 选择器实例。
 *
 * 同一轮检查里元素触发器的多个选择器、GKD 的多个规则都要遍历同一棵 UI 树，
 * 复用「已查过且结果相同」的结论可以省掉大量重复遍历。
 * 两次查询之间页面不会变，因此只在一次检查内有效，不跨轮次。
 */
internal fun queryCacheKey(
    root: android.view.accessibility.AccessibilityNodeInfo,
    selector: li.songe.selector.Selector
): Long = (System.identityHashCode(root).toLong() shl 32) or
        (System.identityHashCode(selector).toLong() and 0xFFFFFFFFL)
