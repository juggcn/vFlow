// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentTools.kt
package com.chaomixian.vflow.core.workflow.module.interaction

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.types.complex.VScreenElement
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.utils.VFlowImeManager
import com.chaomixian.vflow.core.utils.VirtualDisplayManager
import com.chaomixian.vflow.services.ServiceStateBus
import com.chaomixian.vflow.services.ShellManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.random.Random

/**
 * Agent 专用工具集。
 * 封装了底层的操作逻辑，提供高容错性的原子操作供 AI 调用。
 */
class AgentTools(private val context: ExecutionContext) {

    private val appContext = context.applicationContext
    private val TAG = "AgentTools"

    private companion object {
        /** 手势回调超时：无障碍服务中途掉线时 dispatchGesture 的 callback 不会回来，
         *  直接判失败，避免整条工作流无限挂起（正常手势远小于该值）。 */
        private const val GESTURE_TIMEOUT_MS = 8_000L
    }

    /**
     * 带超时地等待手势结果。
     * 超时按「手势未完成」处理，让上层继续走重试或换策略，而不是卡死。
     */
    private suspend fun awaitGesture(deferred: CompletableDeferred<Boolean>): Boolean {
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) { deferred.await() } ?: run {
            DebugLogger.w(TAG, "手势回调超时（${GESTURE_TIMEOUT_MS}ms），按失败处理")
            false
        }
    }

    // 获取目标显示 ID，默认为 0 (主屏幕)
    // 现在 variables 是 Map<String, VObject>，使用 getVariableAsInt 获取
    private val targetDisplayId: Int
        get() = context.getVariableAsInt("_target_display_id") ?: 0

    // 辅助方法：构建 shell 命令的 display 参数
    private fun getDisplayOption(): String {
        val id = targetDisplayId
        return if (id > 0) "-d $id" else ""
    }

    private fun targetScreenSize(): Pair<Int, Int> {
        if (targetDisplayId > 0) {
            VirtualDisplayManager.getCurrentSessionSize()?.let { return it }
        }
        val displayMetrics = appContext.resources.displayMetrics
        return displayMetrics.widthPixels to displayMetrics.heightPixels
    }

    /**
     * 启动应用。
     * 增强版：支持指定 Display ID，确保后台启动。
     */
    suspend fun launchApp(appNameOrPackage: String): String {
        val pm = appContext.packageManager
        var targetPackage = appNameOrPackage.trim()

        // 1. 智能提取包名
        val packageRegex = Regex("[\\(（]([a-zA-Z0-9_\\.]+)[\\)）]")
        val match = packageRegex.find(targetPackage)
        if (match != null) {
            val extracted = match.groupValues[1]
            if (extracted.contains(".")) {
                targetPackage = extracted
            }
        }

        // 2. 如果是纯名称，搜索包名
        if (!targetPackage.contains(".")) {
            val packages = pm.getInstalledPackages(0)
            val matchApp = packages.find { pkg ->
                val label = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: ""
                label.equals(targetPackage, ignoreCase = true) || label.contains(targetPackage, ignoreCase = true)
            }
            if (matchApp != null) {
                targetPackage = matchApp.packageName
            } else {
                return "Failed: 未找到名称包含 '$appNameOrPackage' 的应用。"
            }
        }

        // 3. 准备启动
        // 如果是在虚拟屏幕，且应用可能已经在主屏运行，先强制停止它，
        // 这样可以确保它在新的 Display 上冷启动，避免 Android 只是将主屏的 Activity 提到前台。
        if (targetDisplayId > 0 && ShellManager.isShizukuActive(appContext)) {
            // 简单的防冲突策略：先杀后启
            DebugLogger.d(TAG, "为了在虚拟屏幕启动，正在停止主屏可能存在的实例: $targetPackage")
            ShellManager.execShellCommand(appContext, "am force-stop $targetPackage", ShellManager.ShellMode.AUTO)
            delay(500) // 等待停止
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) return "Failed: 无法获取启动意图 ($targetPackage)。"

        // --- 策略 A: Shell 启动 (最可靠，支持 --display) ---
        if (ShellManager.isShizukuActive(appContext) || ShellManager.isRootAvailable()) {
            val component = launchIntent.component?.flattenToShortString()
            if (component != null) {
                // -W: 等待启动完成
                // --display <id>: 指定屏幕
                // -f 0x10000000: FLAG_ACTIVITY_NEW_TASK
                val displayFlag = if (targetDisplayId > 0) "--display $targetDisplayId" else ""
                val cmd = "am start -W $displayFlag -n $component -f 0x10000000"

                DebugLogger.d(TAG, "执行 Shell 启动命令: $cmd")
                val result = ShellManager.execShellCommand(appContext, cmd, ShellManager.ShellMode.AUTO)

                if (!result.startsWith("Error")) {
                    return "Success: Shell Launched ($targetPackage) on display $targetDisplayId."
                }
                DebugLogger.w(TAG, "Shell 启动失败: $result，回退到 Java API")
            }
        }

        // --- 策略 B: Java API 启动 (回退方案) ---
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // [关键] 使用 ActivityOptions 指定目标屏幕
            val optionsBundle = if (targetDisplayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DebugLogger.d(TAG, "使用 ActivityOptions 指定目标屏幕: $targetDisplayId")
                val options = ActivityOptions.makeBasic()
                options.launchDisplayId = targetDisplayId
                options.toBundle()
            } else {
                null
            }

            appContext.startActivity(launchIntent, optionsBundle)
            return "Success: Java API Launched ($targetPackage)."
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Java API 启动失败", e)
            return "Failed: Launch exception: ${e.message}"
        }
    }

    /**
     * 主动等待一段时间。
     * 当 AI 发现正在生成内容或加载时，应调用此工具而不是盲目操作。
     */
    suspend fun wait(seconds: Int): String {
        val safeSeconds = seconds.coerceIn(1, 60) // 限制单次等待 1-60 秒
        DebugLogger.d(TAG, "Agent 主动等待 $safeSeconds 秒...")
        delay(safeSeconds * 1000L)
        return "Success: Waited for $safeSeconds seconds. Check the screen status again."
    }

    /**
     * 直接点击指定坐标。
     * 这是 AI 基于视觉判断后的首选操作方式。
     */
    suspend fun clickPoint(x: Int, y: Int): String {
        DebugLogger.d(TAG, "Agent请求点击坐标: ($x, $y) Display: $targetDisplayId")
        if (clickCoordinates(x, y)) {
            return "Success: Tapped at ($x, $y)."
        }
        return "Failed: Could not tap at ($x, $y)."
    }

    /**
     * 长按指定坐标
     */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 1000): String {
        DebugLogger.d(TAG, "Agent请求长按坐标: ($x, $y), 时长: $durationMs Display: $targetDisplayId")

        // 虚拟屏幕强制走 Shell
        if (targetDisplayId > 0) {
            if (ShellManager.isShizukuActive(appContext)) {
                val result = ShellManager.execShellCommand(appContext, "input ${getDisplayOption()} swipe $x $y $x $y $durationMs", ShellManager.ShellMode.AUTO)
                if (!result.startsWith("Error")) return "Success: Long Pressed (Shell/Virtual) at ($x, $y)."
            }
            return "Failed: Virtual display requires Shizuku/Root."
        }

        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val deferred = CompletableDeferred<Boolean>()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { deferred.complete(true) }
                override fun onCancelled(g: GestureDescription?) { deferred.complete(false) }
            }, null)
            if (awaitGesture(deferred)) return "Success: Long Pressed at ($x, $y)."
        }
        // Shell 回退
        if (ShellManager.isShizukuActive(appContext)) {
            // input swipe x y x y duration
            val result = ShellManager.execShellCommand(appContext, "input swipe $x $y $x $y $durationMs", ShellManager.ShellMode.AUTO)
            if (!result.startsWith("Error")) return "Success: Long Pressed (Shell) at ($x, $y)."
        }
        return "Failed: Could not long press."
    }

    /**
     * 双击指定坐标
     */
    suspend fun doubleTap(x: Int, y: Int): String {
        DebugLogger.d(TAG, "Agent请求双击坐标: ($x, $y) Display: $targetDisplayId")

        // 虚拟屏幕强制走 Shell
        if (targetDisplayId > 0) {
            if (ShellManager.isShizukuActive(appContext)) {
                val res1 = ShellManager.execShellCommand(appContext, "input ${getDisplayOption()} tap $x $y", ShellManager.ShellMode.AUTO)
                val res2 = ShellManager.execShellCommand(appContext, "input ${getDisplayOption()} tap $x $y", ShellManager.ShellMode.AUTO)
                if (!res1.startsWith("Error") && !res2.startsWith("Error")) return "Success: Double Tapped (Shell/Virtual) at ($x, $y)."
            }
            return "Failed: Virtual display requires Shizuku/Root."
        }

        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            // 创建两个连续的点击笔画
            val stroke1 = GestureDescription.StrokeDescription(path, 0, 50)
            val stroke2 = GestureDescription.StrokeDescription(path, 100, 50) // 间隔 50ms (0+50+interval)

            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()

            val deferred = CompletableDeferred<Boolean>()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { deferred.complete(true) }
                override fun onCancelled(g: GestureDescription?) { deferred.complete(false) }
            }, null)
            if (awaitGesture(deferred)) return "Success: Double Tapped at ($x, $y)."
        }

        // Shell 回退 (模拟两次 tap)
        if (ShellManager.isShizukuActive(appContext)) {
            val res1 = ShellManager.execShellCommand(appContext, "input tap $x $y", ShellManager.ShellMode.AUTO)
            val res2 = ShellManager.execShellCommand(appContext, "input tap $x $y", ShellManager.ShellMode.AUTO)
            if (!res1.startsWith("Error") && !res2.startsWith("Error")) return "Success: Double Tapped (Shell) at ($x, $y)."
        }
        return "Failed: Could not double tap."
    }

    /**
     * 智能点击元素 (基于文本/ID)。
     * 仅作为备用方案，当视觉识别失败时使用。
     * 1. 无障碍查找 (快速，准确，但可能被屏蔽)
     * 2. 全局模糊扫描 (处理文本细微差异)
     * 3. OCR 视觉查找 (最终兜底，专门对付微信/游戏等非标准UI)
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun clickElement(target: String): String {
        DebugLogger.d(TAG, "Agent请求点击元素(文本/ID): $target Display: $targetDisplayId")
        val service = ServiceStateBus.getAccessibilityService()

        // 虚拟屏幕暂不支持无障碍查找（除非遍历 Windows，这里简化为直接走 OCR 或 失败）
        // 如果是主屏幕 (targetDisplayId == 0)，则尝试无障碍
        if (targetDisplayId == 0 && service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                // 1.1 快速查找
                var bestNode: AccessibilityNodeInfo? = null
                val textNodes = root.findAccessibilityNodeInfosByText(target)
                if (!textNodes.isNullOrEmpty()) {
                    bestNode = textNodes.find { it.isVisibleToUser }
                }
                if (bestNode == null) {
                    val idNodes = root.findAccessibilityNodeInfosByViewId(target)
                    if (!idNodes.isNullOrEmpty()) {
                        bestNode = idNodes.find { it.isVisibleToUser }
                    }
                }

                // 1.2 评分模糊查找
                if (bestNode == null) {
                    val cleanTarget = normalizeText(target)
                    val deepScanResult = findBestNodeFuzzy(root, cleanTarget)
                    if (deepScanResult != null && calculateMatchScore(deepScanResult, cleanTarget) < 40) {
                        bestNode = deepScanResult
                    }
                }

                // 1.3 如果找到节点，执行点击
                if (bestNode != null) {
                    val result = clickAccessibilityNode(bestNode, target)
                    // 如果点击成功，直接返回；如果失败，继续尝试 OCR
                    if (!result.startsWith("Failed")) return result
                }
            }
        } else if (targetDisplayId > 0 && service != null) {
            // 尝试在虚拟屏幕上查找 (如果支持多窗口检索)
            val windows = service.windows
            val targetWindow = windows.find { it.displayId == targetDisplayId }
            val root = targetWindow?.root
            if (root != null) {
                // 复用上面的查找逻辑
                var bestNode: AccessibilityNodeInfo? = null
                val textNodes = root.findAccessibilityNodeInfosByText(target)
                if (!textNodes.isNullOrEmpty()) bestNode = textNodes.find { it.isVisibleToUser }

                if (bestNode != null) {
                    val rect = Rect()
                    bestNode.getBoundsInScreen(rect)
                    // 使用坐标点击，因为 action_click 可能无法跨屏
                    if (clickCoordinates(rect.centerX(), rect.centerY())) {
                        return "Success: 在虚拟屏幕找到并点击了 '$target'。"
                    }
                }
            }
        }

        // --- 阶段 2: OCR 视觉查找 (无障碍失效时回落) ---
        DebugLogger.w(TAG, "无障碍查找失败(或处于虚拟屏幕)，启动 OCR 视觉定位: $target")
        if (clickByOCR(target)) {
            return "Success: 无障碍树中未找到，但通过 OCR 视觉识别并点击了 '$target'。"
        }

        return "Failed: 在屏幕上未找到包含 '$target' 的元素 (无障碍和OCR均失败)。"
    }

    /**
     * 等待元素消失。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun waitForElementToDisappear(target: String, timeoutMillis: Long = 30000): String {
        DebugLogger.d(TAG, "开始等待元素消失: $target, 超时: ${timeoutMillis}ms")
        val service = ServiceStateBus.getAccessibilityService()
            ?: return "Error: 无障碍服务未运行，无法检测屏幕状态。"

        val cleanTarget = normalizeText(target)
        val startTime = System.currentTimeMillis()

        val result = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                // 如果是虚拟屏幕，尝试获取对应窗口
                val root = if (targetDisplayId > 0) {
                    service.windows.find { it.displayId == targetDisplayId }?.root
                } else {
                    service.rootInActiveWindow
                }

                if (root == null) {
                    delay(500)
                    continue
                }
                var isFound = !root.findAccessibilityNodeInfosByText(target).isNullOrEmpty()
                if (!isFound) {
                    val deepScanResult = findBestNodeFuzzy(root, cleanTarget)
                    if (deepScanResult != null && calculateMatchScore(deepScanResult, cleanTarget) < 40) {
                        isFound = true
                    }
                }

                if (!isFound) {
                    DebugLogger.d(TAG, "元素 '$target' 已消失，耗时: ${System.currentTimeMillis() - startTime}ms")
                    return@withTimeoutOrNull "Success: 元素 '$target' 已消失。"
                }
                delay(1000)
            }
        }
        return (result ?: "Failed: 等待元素 '$target' 消失超时。") as String
    }

    // --- 内部点击实现 ---

    /**
     * 执行无障碍节点的点击逻辑
     */
    private suspend fun clickAccessibilityNode(node: AccessibilityNodeInfo, targetDesc: String): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val (screenWidth, screenHeight) = targetScreenSize()
        val screenRect = Rect(0, 0, screenWidth, screenHeight)

        // 坐标有效性检查
        if (rect.intersect(screenRect)) {
            // 优先物理点击
            if (clickCoordinates(rect.centerX(), rect.centerY())) {
                return "Success: 已点击 '$targetDesc' (物理模拟)。"
            }
        }

        // 回退逻辑点击
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "Success: 已通过无障碍API点击 '$targetDesc'。"
        }

        // 尝试父节点
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Success: 点击了父容器。"
            }
            parent = parent.parent
        }

        return "Failed"
    }

    /**
     * 使用 OCR 模块查找并点击文字
     */
    private suspend fun clickByOCR(targetText: String): Boolean {
        // 1. 获取截图模块
        val ocrModule = ModuleRegistry.getModule("vflow.interaction.ocr") ?: return false

        val imagePath = if (targetDisplayId > 0) {
            AgentUtils.captureScreen(appContext, context).path
        } else {
            // 使用临时的 Context，避免污染主流程变量
            val captureModule = ModuleRegistry.getModule("vflow.system.capture_screen") ?: return false
            val captureContext = context.copy(
                variables = mutableMapOf("mode" to VObjectFactory.from("auto"))
            )
            val captureRes = captureModule.execute(captureContext) { } // 静默执行
            if (captureRes is ExecutionResult.Success) {
                (captureRes.outputs["image"] as? VImage)?.uriString
            } else {
                null
            }
        }

        if (imagePath == null) {
            DebugLogger.e(TAG, "OCR 准备失败: 无法截屏")
            return false
        }

        // 3. 执行 OCR 查找
        val ocrParams = mutableMapOf<String, Any?>(
            "mode" to "find",
            "target_text" to targetText,
            "language" to "mixed",
            "search_strategy" to "default"
        )

        val ocrMagicVars: MutableMap<String, VObject> = mutableMapOf("image" to VImage(imagePath))

        val ocrContext = context.copy(
            variables = ExecutionContext.mutableMapToVObjectMap(ocrParams).toMutableMap(),
            magicVariables = ocrMagicVars
        )

        DebugLogger.d(TAG, "正在执行 OCR 查找: $targetText")
        val ocrRes = ocrModule.execute(ocrContext) { }

        if (ocrRes is ExecutionResult.Success) {
            val found = (ocrRes.outputs["found"] as? VBoolean)?.raw == true
            if (found) {
                val firstMatch = ocrRes.outputs["first_match"] as? VScreenElement
                if (firstMatch != null) {
                    val x = firstMatch.bounds.centerX()
                    val y = firstMatch.bounds.centerY()
                    DebugLogger.i(TAG, "OCR 找到 '$targetText' 位于 ($x, $y)，正在点击...")
                    return clickCoordinates(x, y)
                }
            }
        } else if (ocrRes is ExecutionResult.Failure) {
            DebugLogger.e(TAG, "OCR 执行失败: ${ocrRes.errorMessage}")
        }

        DebugLogger.w(TAG, "OCR 未找到文本: $targetText")
        return false
    }

    // --- 辅助方法 ---

    private fun calculateMatchScore(node: AccessibilityNodeInfo, cleanTarget: String): Int {
        val text = normalizeText(node.text?.toString() ?: "")
        val desc = normalizeText(node.contentDescription?.toString() ?: "")
        val id = normalizeText(node.viewIdResourceName?.substringAfter(":id/") ?: "")
        val scoreText = getScore(text, cleanTarget)
        val scoreDesc = getScore(desc, cleanTarget)
        val scoreId = if (id.isNotEmpty()) getScore(id, cleanTarget) else Int.MAX_VALUE
        return minOf(scoreText, scoreDesc, scoreId)
    }

    private fun getScore(candidate: String, target: String): Int {
        if (candidate.isEmpty()) return Int.MAX_VALUE
        if (candidate == target) return 0
        val lenDiff = abs(candidate.length - target.length)
        if (target.contains(candidate)) return 10 + lenDiff
        if (candidate.contains(target)) return 50 + lenDiff
        return Int.MAX_VALUE
    }

    private fun findBestNodeFuzzy(root: AccessibilityNodeInfo, cleanTarget: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var currentBest: AccessibilityNodeInfo? = null
        var currentMinScore = Int.MAX_VALUE
        while (!queue.isEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val score = calculateMatchScore(node, cleanTarget)
                if (score < currentMinScore) {
                    currentMinScore = score
                    currentBest = node
                    if (score == 0) return node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return currentBest
    }

    private fun normalizeText(text: String): String {
        return text.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "").lowercase()
    }

    suspend fun clickCoordinates(x: Int, y: Int): Boolean {
        // 如果是虚拟屏幕，优先使用 Shell input
        if (targetDisplayId > 0) {
            if (ShellManager.isShizukuActive(appContext)) {
                val cmd = "input ${getDisplayOption()} tap $x $y"
                val result = ShellManager.execShellCommand(appContext, cmd, ShellManager.ShellMode.AUTO)
                return !result.startsWith("Error")
            }
            return false
        }

        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            val deferred = CompletableDeferred<Boolean>()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { deferred.complete(true) }
                override fun onCancelled(g: GestureDescription?) { deferred.complete(false) }
            }, null)
            if (awaitGesture(deferred)) return true
        }
        if (ShellManager.isShizukuActive(appContext)) {
            val result = ShellManager.execShellCommand(appContext, "input tap $x $y", ShellManager.ShellMode.AUTO)
            return !result.startsWith("Error")
        }
        return false
    }

    // 检查是否只包含 ASCII 字符
    private fun isAscii(text: String): Boolean {
        return text.all { it.code < 128 }
    }

    /**
     * 智能输入文本
     * 1. 尝试无障碍直接 SET_TEXT (最快，最稳，不占剪贴板)
     * 2. 如果是 ASCII 且有 Shell，使用 input text
     * 3. 如果是中文/Unicode 且有 Shell，使用 vFlow IME，
     * 如果失败回落到 剪贴板 + KEYCODE_PASTE (解决乱码问题)
     */
    suspend fun inputText(text: String): String {
        // 如果是虚拟屏幕，强制使用 Shell input text (不完全支持中文，除非用 IME 广播)
        if (targetDisplayId > 0) {
            if (ShellManager.isShizukuActive(appContext)) {
                // 虚拟屏幕输入简单文本
                val safeText = text.replace(" ", "%s").replace("\"", "\\\"")
                val cmd = "input ${getDisplayOption()} text \"$safeText\""
                val result = ShellManager.execShellCommand(appContext, cmd, ShellManager.ShellMode.AUTO)
                return if (!result.startsWith("Error")) "Success: Shell input to virtual display." else "Failed"
            }
            return "Failed: Virtual display input requires Shizuku."
        }

        // 1. 无障碍直接输入
        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            val focusNode = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusNode != null && focusNode.isEditable) {
                val args = android.os.Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                if (focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return "Success: 已通过无障碍接口输入文本。"
                }
            }
        }

        // 2. Shell 方案
        if (ShellManager.isShizukuActive(appContext) || ShellManager.isRootAvailable()) {
            // 优先尝试 VFlow IME (最稳，支持中文，不干扰剪贴板)
            val imeSuccess = VFlowImeManager.inputText(appContext, text)
            if (imeSuccess) {
                return "Success: 通过 vFlow IME 输入了文本。"
            }

            // 失败回退：剪贴板粘贴
            try {
                val clipboardContext = ServiceStateBus.getAccessibilityService() ?: appContext
                val clipboard = clipboardContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Agent Input", text)
                clipboard.setPrimaryClip(clip)
                delay(200)
                val result = ShellManager.execShellCommand(appContext, "input keyevent 279", ShellManager.ShellMode.AUTO)
                if (!result.startsWith("Error")) return "Success: 通过 Shell (剪贴板粘贴) 输入了文本 (IME回退)。"
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Shell 粘贴输入失败", e)
            }
        }

        return "Failed: 无法输入文本。无障碍未找到焦点框，且 Shell 方案也不可用。"
    }

    suspend fun scroll(direction: String): String {
        val (screenWidth, screenHeight) = targetScreenSize()
        val cx = (screenWidth / 2).toFloat()
        val h = screenHeight.toFloat()
        val w = screenWidth.toFloat()
        val cy = (h / 2)
        val path = Path()

        // 模拟自然滑动，增加随机偏移
        val xOffset = Random.nextInt(-50, 50)
        val startX = (cx + xOffset)

        when (direction.lowercase()) {
            "down" -> { path.moveTo(startX, h * 0.8f); path.lineTo(startX, h * 0.2f) }
            "up" -> { path.moveTo(startX, h * 0.2f); path.lineTo(startX, h * 0.8f) }
            "right" -> { path.moveTo(w * 0.8f, cy); path.lineTo(w * 0.2f, cy) }
            "left" -> { path.moveTo(w * 0.2f, cy); path.lineTo(w * 0.8f, cy) }
            else -> return "Error: 未知方向"
        }

        // 虚拟屏幕强制走 Shell
        if (targetDisplayId > 0) {
            if (ShellManager.isShizukuActive(appContext)) {
                val (sx, sy, ex, ey) = when (direction.lowercase()) {
                    "down" -> listOf(startX, h * 0.8f, startX, h * 0.2f)
                    "up" -> listOf(startX, h * 0.2f, startX, h * 0.8f)
                    "right" -> listOf(w * 0.8f, cy, w * 0.2f, cy)
                    "left" -> listOf(w * 0.2f, cy, w * 0.8f, cy)
                    else -> listOf(0f,0f,0f,0f)
                }
                val cmd = "input ${getDisplayOption()} swipe ${sx.toInt()} ${sy.toInt()} ${ex.toInt()} ${ey.toInt()} 300"
                val result = ShellManager.execShellCommand(appContext, cmd, ShellManager.ShellMode.AUTO)
                return if (!result.startsWith("Error")) "Success: Scrolled (Shell/Virtual)." else "Failed"
            }
            return "Failed: Virtual display requires Shizuku."
        }

        val service = ServiceStateBus.getAccessibilityService() ?: return "Error: 服务未运行。"
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        val deferred = CompletableDeferred<Boolean>()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { deferred.complete(true) }
            override fun onCancelled(g: GestureDescription?) { deferred.complete(false) }
        }, null)
        return if (awaitGesture(deferred)) "Success: 向 $direction 滚动完成。" else "Failed: 滚动被取消。"
    }

    suspend fun pressKey(action: String): String {
        // 拉下通知栏：用下滑模拟，比 GLOBAL_ACTION_NOTIFICATIONS（API 31+）兼容性好
        if (action.equals("notifications", ignoreCase = true)) {
            val (width, height) = targetScreenSize()
            swipe(width / 2, 1, width / 2, (height * 0.75).toInt(), 250)
            return "Success: 已拉下通知栏。"
        }

        // 虚拟屏幕按键
        if (targetDisplayId > 0 && ShellManager.isShizukuActive(appContext)) {
            val keyCode = when(action.lowercase()) {
                "back" -> "4"
                "home" -> "3"
                "recents" -> "187"
                else -> return "Error: Unknown key"
            }
            val cmd = "input ${getDisplayOption()} keyevent $keyCode"
            val result = ShellManager.execShellCommand(appContext, cmd, ShellManager.ShellMode.AUTO)
            return if (!result.startsWith("Error")) "Success: Press Key ($action) on Virtual." else "Failed"
        }

        val service = ServiceStateBus.getAccessibilityService() ?: return "Error: 服务未运行。"
        val key = when(action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return "Error: 未知按键"
        }
        service.performGlobalAction(key)
        return "Success: 执行了按键。"
    }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): String {
        DebugLogger.d(TAG, "Agent请求滑动: ($startX, $startY) -> ($endX, $endY) Display: $targetDisplayId")

        // 虚拟屏幕强制走 Shell
        if (targetDisplayId > 0) {
            if (ShellManager.isShizukuActive(appContext)) {
                val result = ShellManager.execShellCommand(appContext, "input ${getDisplayOption()} swipe $startX $startY $endX $endY $duration", ShellManager.ShellMode.AUTO)
                if (!result.startsWith("Error")) return "Success: Swiped (Shell/Virtual) from ($startX, $startY) to ($endX, $endY)."
            }
            return "Failed: Virtual display swipe requires Shizuku."
        }

        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            val path = Path()
            path.moveTo(startX.toFloat(), startY.toFloat())
            path.lineTo(endX.toFloat(), endY.toFloat())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            val deferred = CompletableDeferred<Boolean>()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { deferred.complete(true) }
                override fun onCancelled(g: GestureDescription?) { deferred.complete(false) }
            }, null)
            if (awaitGesture(deferred)) return "Success: Swiped from ($startX, $startY) to ($endX, $endY)."
        }

        // Shell 回退
        if (ShellManager.isShizukuActive(appContext)) {
            val result = ShellManager.execShellCommand(appContext, "input swipe $startX $startY $endX $endY $duration", ShellManager.ShellMode.AUTO)
            if (!result.startsWith("Error")) return "Success: Swiped (Shell) from ($startX, $startY) to ($endX, $endY)."
        }

        return "Failed: Could not swipe (Accessibility & Shell both failed)."
    }

    // ---------------- 以下为模型侧增强工具 ----------------

    /** 在无障碍树里按文本 / ID / 模糊匹配找节点 */
    private fun findNode(target: String): AccessibilityNodeInfo? {
        if (target.isBlank()) return null
        val service = ServiceStateBus.getAccessibilityService() ?: return null
        val root = if (targetDisplayId > 0) {
            service.windows.find { it.displayId == targetDisplayId }?.root
        } else {
            service.rootInActiveWindow
        } ?: return null

        root.findAccessibilityNodeInfosByText(target)?.firstOrNull { it.isVisibleToUser }?.let { return it }
        root.findAccessibilityNodeInfosByViewId(target)?.firstOrNull { it.isVisibleToUser }?.let { return it }

        val cleanTarget = normalizeText(target)
        val fuzzy = findBestNodeFuzzy(root, cleanTarget)
        return if (fuzzy != null && calculateMatchScore(fuzzy, cleanTarget) < 40) fuzzy else null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val id = node.viewIdResourceName?.substringAfter(":id/")?.takeIf { it.isNotBlank() }
        return (text ?: desc ?: id ?: "节点").replace("\n", " ").take(40)
    }

    /**
     * 按 UI 树里的编号点击。编号来自本帧的 dumpUi，模型不需要自己算坐标。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun clickNode(index: Int): String {
        val nodes = AgentUtils.collectVisibleNodes(appContext, targetDisplayId)
        if (nodes.isEmpty()) return "Failed: 当前没有可用的 UI 节点（无障碍未运行或界面无内容）。"
        val node = nodes.getOrNull(index)
            ?: return "Failed: 节点编号 $index 不存在，当前共 ${nodes.size} 个节点。请看最新的 UI 树重新选。"
        val label = "[$index] ${nodeLabel(node)}"
        return if (targetDisplayId == 0) {
            clickAccessibilityNode(node, label)
        } else {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (clickCoordinates(rect.centerX(), rect.centerY())) "Success: 已点击 $label。" else "Failed: 点击 $label 失败。"
        }
    }

    /** 长按某个元素，用于菜单、删除、拖拽前的选择 */
    suspend fun longPressElement(target: String, durationMs: Long = 1000): String {
        val node = findNode(target) ?: return "Failed: 未找到 '$target'。"
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val result = longPress(rect.centerX(), rect.centerY(), durationMs)
        return if (result.startsWith("Failed")) "Failed: 长按 '$target' 失败。" else "Success: 已长按 '$target'。"
    }

    /** 在某个元素区域内滑动，用于滑块、横向轮播、局部列表 */
    suspend fun swipeElement(target: String, direction: String): String {
        val node = findNode(target) ?: return "Failed: 未找到 '$target'。"
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfW = (rect.width() / 4).coerceAtLeast(1)
        val halfH = (rect.height() / 4).coerceAtLeast(1)

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(cx, cy + halfH, cx, cy - halfH)
            "down" -> listOf(cx, cy - halfH, cx, cy + halfH)
            "left" -> listOf(cx + halfW, cy, cx - halfW, cy)
            "right" -> listOf(cx - halfW, cy, cx + halfW, cy)
            else -> return "Failed: direction 只能是 up / down / left / right。"
        }
        swipe(startX, startY, endX, endY, 300)
        return "Success: 已在 '$target' 区域内向 $direction 滑动。"
    }

    /** 一次性取当前屏的全部文本，避免为了读文案反复截图 */
    @RequiresApi(Build.VERSION_CODES.R)
    fun getPageText(): String {
        val nodes = AgentUtils.collectVisibleNodes(appContext, targetDisplayId)
        val texts = nodes.mapNotNull { node ->
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
        if (texts.isEmpty()) return "当前页面没有可读文本。"
        val joined = texts.joinToString("\n")
        return if (joined.length > 4000) joined.take(4000) + "\n...(已截断)" else joined
    }

    /** 连续滚动直到找到目标，省掉模型自己滚好几次 */
    suspend fun scrollUntil(target: String, direction: String, maxSwipes: Int): String {
        val times = maxSwipes.coerceIn(1, 12)
        repeat(times) { i ->
            findNode(target)?.let { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                return "Success: 滚动 ${i + 1} 次后找到 '$target'，bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]。请再调用 click_element 或 click_point 完成点击。"
            }
            scroll(direction)
            delay(400)
        }
        return "Failed: 连续滚动 $times 次仍未找到 '$target'。"
    }
}
