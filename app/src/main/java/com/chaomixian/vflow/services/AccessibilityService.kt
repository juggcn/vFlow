// 文件: main/java/com/chaomixian/vflow/services/AccessibilityService.kt
package com.chaomixian.vflow.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.chaomixian.vflow.core.logging.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class AccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VFlowAccessibility"
    }

    private var serviceScope = createServiceScope()
    private var serviceStateCleaned = false
    private val instanceId = Integer.toHexString(System.identityHashCode(this))
    private var connectionSession = 0
    private var connectedAtElapsed = 0L
    private var eventCountSinceConnect = 0
    private var interruptCountSinceConnect = 0
    private var loggedFirstEventAfterConnect = false
    private val packageActivityCache = mutableMapOf<String, Set<String>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!serviceScope.isActive) {
            serviceScope = createServiceScope()
        }
        serviceStateCleaned = false
        connectionSession += 1
        connectedAtElapsed = SystemClock.elapsedRealtime()
        eventCountSinceConnect = 0
        interruptCountSinceConnect = 0
        loggedFirstEventAfterConnect = false
        ServiceStateBus.onAccessibilityServiceConnected(this, this)
        AccessibilityKeepAliveManager.onAccessibilityConnected(this)
        logLifecycle(
            level = "D",
            event = "onServiceConnected",
            extra = "settingsEnabled=${AccessibilityServiceStatus.isEnabledInSettings(this)} " +
                "scopeActive=${serviceScope.isActive} busConnected=${ServiceStateBus.isAccessibilityServiceRunning()}"
        )
    }

    /**
     * onAccessibilityEvent 现在将事件推送到 ServiceStateBus 的 Flow 中，
     * 而不是发送广播。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        eventCountSinceConnect += 1
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()

        if (!loggedFirstEventAfterConnect) {
            loggedFirstEventAfterConnect = true
            logLifecycle(
                level = "D",
                event = "firstEventAfterConnect",
                extra = "type=${eventTypeName(event.eventType)} package=$packageName class=$className"
            )
        }

        // 交互录制：点击与文本输入直接交给录制器。未在录制时它在第一行就返回，
        // 不会对普通触发流程产生任何开销。必须放在 packageName 判空之前，
        // 因为录制依赖 event.source，而部分事件拿不到包名。
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            InteractionRecorder.onAccessibilityEvent(this, event)
        }

        // TYPE_WINDOWS_CHANGED: 窗口列表变化。浮层广告（WindowManager 添加的悬浮窗、PopupWindow）
        // 不是 Activity，系统只发这个事件、不发 TYPE_WINDOW_STATE_CHANGED；
        // 不处理它，浮层广告弹出时触发器根本不会被唤醒，只有底层页面的文字变化在触发检查。
        // 这类事件的包名/类名都可能为空，所以放在判空之前处理，
        // 且复用「内容变化」通道：只唤醒检查，不污染 lastWindowClassName 等页面状态。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val pkg = (packageName ?: "").ifBlank { ServiceStateBus.lastWindowPackageName ?: "" }
            markPeerContentChanged(packageName)
            serviceScope.launch {
                ServiceStateBus.postWindowContentChanged(pkg, className ?: "")
            }
        }

        if (packageName == null) return

        if (className == null) return

        // TYPE_WINDOW_STATE_CHANGED: 窗口状态变化（Activity切换、Dialog显示等）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val confirmedActivityClassName = resolveConfirmedActivityClassName(packageName, className)
            serviceScope.launch {
                ServiceStateBus.postWindowChangeEvent(packageName, className, confirmedActivityClassName)
            }
        }

        // TYPE_WINDOW_CONTENT_CHANGED: 窗口内容变化（控件出现/消失/更新）
        // 用于检测 vFlow 应用自己界面上的控件变化
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            markPeerContentChanged(packageName)
            serviceScope.launch {
                ServiceStateBus.postWindowContentChanged(packageName, className)
            }
        }
    }

    /**
     * 记录「目标 App 的界面动了」这个事实，供 AI 智能体的等待界面稳定逻辑使用。
     *
     * 必须排除 vFlow 自己的包名：智能体每步都会刷新悬浮窗里的状态文字，
     * 那同样是 TYPE_WINDOW_CONTENT_CHANGED，混进来会让静默判定永远不成立。
     */
    private fun markPeerContentChanged(eventPackage: String?) {
        if (eventPackage.isNullOrBlank()) return
        if (eventPackage == this.packageName.toString()) return
        ServiceStateBus.markContentChanged()
    }

    override fun onInterrupt() {
        interruptCountSinceConnect += 1
        logLifecycle(
            level = "W",
            event = "onInterrupt",
            extra = "interruptCount=$interruptCountSinceConnect settingsEnabled=${AccessibilityServiceStatus.isEnabledInSettings(this)}"
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupServiceState(
            event = "onUnbind",
            extra = "intentAction=${intent?.action ?: "null"}"
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupServiceState(event = "onDestroy")
        super.onDestroy()
    }

    private fun cleanupServiceState(event: String, extra: String = "") {
        if (serviceStateCleaned) {
            logLifecycle(level = "D", event = "${event}_ignored", extra = "reason=already_cleaned")
            return
        }
        serviceStateCleaned = true
        AccessibilityKeepAliveManager.onAccessibilityDisconnected(this)
        ServiceStateBus.onAccessibilityServiceDisconnected(this, this)
        serviceScope.cancel()
        logLifecycle(level = "W", event = event, extra = extra)
    }

    private fun createServiceScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun resolveConfirmedActivityClassName(packageName: String, className: String): String? {
        val activityClassNames = packageActivityCache.getOrPut(packageName) {
            loadActivityClassNames(packageName)
        }
        return className.takeIf { it in activityClassNames }
    }

    private fun loadActivityClassNames(packageName: String): Set<String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }
            extractActivityClassNames(packageInfo)
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun extractActivityClassNames(packageInfo: PackageInfo): Set<String> {
        val activities = packageInfo.activities ?: return emptySet()
        return activities.mapNotNull { it.name }.toSet()
    }

    private fun logLifecycle(level: String, event: String, extra: String = "") {
        val connectedDuration = if (connectedAtElapsed == 0L) -1L else SystemClock.elapsedRealtime() - connectedAtElapsed
        val rootAvailable = runCatching { rootInActiveWindow != null }.getOrDefault(false)
        val baseMessage = buildString {
            append("event=")
            append(event)
            append(" instance=")
            append(instanceId)
            append(" session=")
            append(connectionSession)
            append(" connectedForMs=")
            append(connectedDuration)
            append(" eventCount=")
            append(eventCountSinceConnect)
            append(" rootAvailable=")
            append(rootAvailable)
            append(" serviceInfoNull=")
            append(serviceInfo == null)
            if (extra.isNotBlank()) {
                append(' ')
                append(extra)
            }
        }

        when (level) {
            "W" -> DebugLogger.w(TAG, baseMessage)
            "E" -> DebugLogger.e(TAG, baseMessage)
            "I" -> DebugLogger.i(TAG, baseMessage)
            else -> DebugLogger.d(TAG, baseMessage)
        }
    }

    private fun eventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            else -> "TYPE_$eventType"
        }
    }
}
