package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.chaomixian.vflow.core.logging.DebugLogger

/**
 * 屏幕类触发器（GKD 订阅 / 元素触发）共用的窗口扫描与节流常量
 */

/** 窗口扫描日志标签 */
private const val WINDOW_SCAN_TAG = "WindowScan"

/** 窗口内容变化事件去抖间隔（毫秒）。列表滚动时该事件每秒可达几十次，不去抖会刷爆匹配通道 */
internal const val TRIGGER_CONTENT_DEBOUNCE_MS = 300L

/** 事件后补查次数：广告控件常在页面出现后 50~300ms 才渲染完成，事件瞬间查一次容易漏 */
internal const val TRIGGER_RECHECK_TIMES = 5

/** 事件后补查间隔（毫秒） */
internal const val TRIGGER_RECHECK_INTERVAL_MS = 150L

/**
 * 上一次打印过日志的窗口快照。
 *
 * 触发器每 150ms 就会重查一轮窗口，如果每次都打一行，几秒钟就能把 5000 条的日志缓冲冲干净，
 * 真正出问题时反而什么都不剩。所以只在窗口数量发生变化时才输出。
 */
@Volatile
private var lastScanSignature: String? = null

/**
 * 收集无障碍服务可见的所有窗口根节点，含 Dialog / PopupWindow / 悬浮层。
 *
 * 三条原则：
 * 1. 每个窗口单独容错——读不到 root 只跳过该窗口，绝不因为一次异常丢掉整片窗口；
 * 2. 不做任何「谁是主窗口」的判断。窗口面积、层级、id 在不同 ROM 上表现不一致，
 *    曾据此实现的「仅浮层」在部分机型上会把真广告全部过滤掉，因此彻底移除；
 * 3. rootInActiveWindow 始终并入——浮层有焦点时它常是唯一能拿到浮层的地方。
 *
 * 带 100ms 快照：一次无障碍事件里元素触发器和 GKD 触发器会各调一次，
 * 窗口列表在这几十毫秒内不会变，重复枚举纯属浪费。快照为空时不缓存，避免把「扫不到」固定住。
 */
internal fun collectWindowRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
    val now = SystemClock.uptimeMillis()
    windowSnapshot?.let { snap ->
        if (now - snap.at <= WINDOW_SNAPSHOT_TTL_MS) return snap.roots
    }

    val roots = scanWindowRoots(service)
    if (roots.isNotEmpty()) {
        windowSnapshot = WindowSnapshot(now, roots)
    }
    return roots
}

/** 窗口快照 */
private class WindowSnapshot(val at: Long, val roots: List<AccessibilityNodeInfo>)

/** 快照有效期（毫秒） */
private const val WINDOW_SNAPSHOT_TTL_MS = 100L

@Volatile
private var windowSnapshot: WindowSnapshot? = null

private fun scanWindowRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()

    val windows = runCatching { service.windows }.getOrNull()
    if (!windows.isNullOrEmpty()) {
        for (window in windows) {
            // root 偶发为 null（窗口刚被添加、或正在销毁），重试一次再放弃
            val root = runCatching { window.root }.getOrNull()
                ?: runCatching { window.root }.getOrNull()
            if (root == null) {
                val windowId = runCatching { window.id }.getOrDefault(-1)
                DebugLogger.d(WINDOW_SCAN_TAG, "窗口 id=$windowId 无根节点，跳过")
                continue
            }
            if (result.none { it == root }) result.add(root)
        }
    }

    // 激活窗口兜底：浮层弹出时它常是 rootInActiveWindow，
    // 但未必出现在 windows 列表里（或列表里拿不到 root）。
    runCatching { service.rootInActiveWindow }.getOrNull()?.let { activeRoot ->
        if (result.none { it == activeRoot }) result.add(activeRoot)
    }

    // 只在窗口数量变化时输出，避免每轮扫描刷屏冲掉日志
    val signature = "${result.size}:" + result.joinToString(",") { it.className?.toString() ?: "?" }
    if (signature != lastScanSignature) {
        lastScanSignature = signature
        val message = "窗口变化 -> 共 ${result.size} 个窗口根节点"
        if (result.isEmpty()) DebugLogger.w(WINDOW_SCAN_TAG, message) else DebugLogger.d(WINDOW_SCAN_TAG, message)
    }

    return result
}
