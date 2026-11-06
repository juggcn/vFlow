// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentUtils.kt
package com.chaomixian.vflow.core.workflow.module.interaction

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.utils.VirtualDisplayManager
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.ServiceStateBus
import com.chaomixian.vflow.services.ShellManager
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar

/**
 * Agent 感知工具类。
 */
object AgentUtils {

    private const val TAG = "AgentUtils"

    /** 发给视觉模型的截图长边上限。多数模型内部会缩到 1280~1568，传原图纯浪费带宽和 token */
    private const val MAX_IMAGE_LONG_SIDE = 1280
    private const val IMAGE_JPEG_QUALITY = 85

    /** 一次 UI 树最多回报的节点数，避免超长文本被字符数盲截 */
    private const val MAX_TREE_NODES = 240

    /** 截图缓存文件保留数量，长任务不清理会堆几十张 */
    private const val KEEP_SCREENSHOT_FILES = 8

    data class ScreenshotResult(
        val base64: String?,
        val path: String?,
        val width: Int,
        val height: Int
    )

    suspend fun captureScreen(
        context: Context,
        execContext: com.chaomixian.vflow.core.execution.ExecutionContext
    ): ScreenshotResult {
        // 检查是否有目标显示 ID
        val displayId = execContext.getVariableAsInt("_target_display_id") ?: 0

        // 针对虚拟屏幕的处理逻辑 (ID > 0)
        if (displayId > 0) {
            // 优先尝试：从 VirtualDisplayManager 内存抓取 (最快)
            if (displayId == VirtualDisplayManager.getCurrentDisplayId()) {
                val bitmap = VirtualDisplayManager.captureCurrentFrame()
                if (bitmap != null) {
                    return bitmapToScreenshotResult(bitmap, execContext.workDir, "virtual_display")
                } else {
                    DebugLogger.w(TAG, "虚拟屏幕 (ID: $displayId) 内存帧为空，准备回退到 Shell...")
                }
            }

            // 回退方案：使用 Shell screencap 指定 Display ID (较慢但稳)
            // 显式指定 -d displayId
            val shellResult = captureShellScreenshot(context, execContext.workDir, displayId)
            if (shellResult != null) {
                return shellResult
            }

            // 3. 如果都失败了，返回空结果，而不是去截主屏
            DebugLogger.e(TAG, "无法获取虚拟屏幕 (ID: $displayId) 的截图。")
            return ScreenshotResult(null, null, 0, 0)
        }

        // 主屏幕处理逻辑 (ID == 0)
        val captureModule = ModuleRegistry.getModule("vflow.system.capture_screen")
        if (captureModule == null) {
            DebugLogger.e(TAG, "未找到截屏模块")
            return ScreenshotResult(null, null, 0, 0)
        }

        val tempContext = execContext.copy(variables = mutableMapOf("mode" to VObjectFactory.from("自动")))
        val result = captureModule.execute(tempContext) { }

        if (result is ExecutionResult.Success) {
            val imageVar = result.outputs["image"] as? VImage
            if (imageVar != null) {
                val uri = Uri.parse(imageVar.uriString)
                // 获取原图 Base64 和 原始尺寸
                val (base64, width, height) = imageUriToBase64(context, uri)
                return ScreenshotResult(base64, uri.path, width, height)
            }
        }
        return ScreenshotResult(null, null, 0, 0)
    }

    private fun bitmapToScreenshotResult(bitmap: Bitmap, workDir: File, prefix: String): ScreenshotResult {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val file = File(workDir, "${prefix}_$timestamp.jpg")
        val output = ByteArrayOutputStream()
        var scaled: Bitmap? = null
        return try {
            // 虚拟屏同样是长边限 1280，不然 1080p 帧直接进模型太浪费
            val longSide = maxOf(bitmap.width, bitmap.height)
            val target = if (longSide > MAX_IMAGE_LONG_SIDE) {
                val ratio = MAX_IMAGE_LONG_SIDE.toFloat() / longSide
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxOf(1, (bitmap.width * ratio).toInt()),
                    maxOf(1, (bitmap.height * ratio).toInt()),
                    true
                ).also { scaled = it }
            } else {
                bitmap
            }
            target.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, output)
            val bytes = output.toByteArray()
            file.outputStream().use { it.write(bytes) }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ScreenshotResult(base64, Uri.fromFile(file).toString(), target.width, target.height)
        } finally {
            scaled?.let { if (!it.isRecycled) it.recycle() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 使用 Shell screencap 命令截取指定 Display 的屏幕
     */
    private suspend fun captureShellScreenshot(context: Context, workDir: java.io.File, displayId: Int): ScreenshotResult? {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "screenshot_vd_${displayId}_$timestamp.png"
        val cacheFile = java.io.File(workDir, fileName)
        val path = cacheFile.absolutePath

        // [关键] 显式指定 -d displayId，确保截取的是后台虚拟屏幕
        val command = "screencap -d $displayId -p \"$path\""

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                ShellManager.execShellCommand(context, command, ShellManager.ShellMode.AUTO)
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val uri = Uri.fromFile(cacheFile)
                    val (base64, width, height) = imageUriToBase64(context, uri)
                    ScreenshotResult(base64, path, width, height)
                } else {
                    DebugLogger.w(TAG, "Shell 截图未能生成文件 (Display $displayId)")
                    null
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Shell 截图异常", e)
                null
            }
        }
    }

    /**
     * 提取并精简当前屏幕的 UI 树结构。
     * 增加了严格的可见性过滤，防止 AI 看到屏幕外或被遮挡的内容。
     * 增加 displayId 参数以支持多屏幕。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun dumpHierarchy(context: Context, displayId: Int = 0): String = dumpUi(context, displayId).text

    data class UiDump(
        val text: String,
        val nodes: List<AccessibilityNodeInfo>,
        val truncated: Boolean
    )

    /**
     * 抓取 UI 树，同时给出「节点编号 → 节点」的对应关系，供 click_node 使用。
     *
     * 原来按 take(16000) 字符盲截，经常切在节点中间；这里改成按节点数截断，
     * 并且给每个节点编上号，模型只需要报编号，不用做坐标算术。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun dumpUi(context: Context, displayId: Int = 0): UiDump {
        val root = rootFor(displayId)
            ?: return UiDump("UI Hierarchy unavailable (Service not running or window not found)", emptyList(), false)
        val screenBounds = screenBoundsOf(context)

        val nodes = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (truncated) return
            if (nodes.size >= MAX_TREE_NODES) {
                truncated = true
                return
            }
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!Rect.intersects(rect, screenBounds)) return
            if (rect.width() < 1 || rect.height() < 1) return

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            val hasContent = !text.isNullOrBlank() || !desc.isNullOrBlank()
            val isInteractive = isClickable || isEditable || isScrollable
            val hasId = !viewId.isNullOrBlank()

            if (hasContent || isInteractive || hasId) {
                val index = nodes.size
                nodes.add(node)
                val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
                sb.append("  ".repeat(depth)).append("[").append(index).append("] <").append(className)
                if (!text.isNullOrBlank()) sb.append(" text=\"${text.replace("\n", " ").replace("\"", "'")}\"")
                if (!desc.isNullOrBlank()) sb.append(" desc=\"${desc.replace("\n", " ").replace("\"", "'")}\"")
                if (hasId) sb.append(" id=\"${viewId!!.substringAfter(":id/")}\"")
                if (isClickable) sb.append(" clickable=\"true\"")
                if (isEditable) sb.append(" editable=\"true\"")
                if (isScrollable) sb.append(" scrollable=\"true\"")

                val visibleRect = Rect(rect)
                visibleRect.intersect(screenBounds)
                sb.append(" bounds=\"[${visibleRect.left},${visibleRect.top}][${visibleRect.right},${visibleRect.bottom}]\"")
                sb.append(" />\n")
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }

        try {
            visit(root, 0)
        } catch (e: Exception) {
            sb.append("Error dumping hierarchy: ${e.message}")
        }
        return UiDump(sb.toString(), nodes, truncated)
    }

    /** 按 dumpUi 的同一顺序重新收集节点，用于按编号点击（编号只在同一帧内有效） */
    @RequiresApi(Build.VERSION_CODES.R)
    fun collectVisibleNodes(context: Context, displayId: Int = 0): List<AccessibilityNodeInfo> {
        return dumpUi(context, displayId).nodes
    }

    /** 当前前台应用，模型需要知道自己在哪个 App 里 */
    fun getForegroundApp(context: Context): String {
        val service = ServiceStateBus.getAccessibilityService() ?: return "unknown"
        val pkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (pkg.isNullOrBlank()) return "unknown"
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
        return if (label.isBlank() || label == pkg) pkg else "$label ($pkg)"
    }

    /**
     * 计算截图指纹（灰度均值哈希），用于判断动作前后画面有没有变化。
     * 返回 null 表示无法计算。
     */
    fun screenshotFingerprint(base64: String?): Long? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            if (small !== bitmap) bitmap.recycle()

            val pixels = IntArray(64)
            small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
            small.recycle()

            val grays = IntArray(64) { i ->
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                (r * 299 + g * 587 + b * 114) / 1000
            }
            val avg = grays.average()
            var hash = 0L
            grays.forEach { gray -> hash = (hash shl 1) or (if (gray > avg) 1L else 0L) }
            hash
        } catch (e: Exception) {
            DebugLogger.w(TAG, "计算截图指纹失败: ${e.message}")
            null
        }
    }

    /**
     * 计算结果：界面有没有动、实际等了多久、是不是被硬超时截断的。
     */
    data class UiStableResult(
        val changed: Boolean,
        val waitedMs: Long,
        val timedOut: Boolean
    )

    /**
     * 等待界面稳定（事件驱动，仅主屏有效）。
     *
     * 判定分两段：
     * 1. 动作发出后先等第一次内容变化，最多 [initialGraceMs]。一次都没等到就说明这个动作
     *    压根没引起界面刷新（点击没生效），直接返回 changed=false。
     * 2. 等到变化之后，每次事件都重置计时，静默满 [quietMs] 认为稳定。
     *    整体最多等 [timeoutMs]：视频播放、进度条、倒计时这类页面永远等不到静默，
     *    没有硬超时就会卡死。
     *
     * 虚拟屏没有无障碍事件流，调用方应改为固定延时。
     */
    suspend fun awaitUiStable(
        quietMs: Long = 300L,
        timeoutMs: Long = 2000L,
        initialGraceMs: Long = 350L
    ): UiStableResult {
        val start = SystemClock.elapsedRealtime()
        // 锚定到发起时刻：只看「这之后」的新事件，历史时间戳不算数
        var lastChange = start
        var sawChange = false

        while (true) {
            val now = SystemClock.elapsedRealtime()
            val busChangeAt = ServiceStateBus.lastContentChangeAt
            if (busChangeAt > lastChange) {
                lastChange = busChangeAt
                sawChange = true
            }

            val elapsed = now - start
            if (elapsed >= timeoutMs) {
                return UiStableResult(changed = sawChange, waitedMs = elapsed, timedOut = true)
            }
            if (!sawChange && elapsed >= initialGraceMs) {
                return UiStableResult(changed = false, waitedMs = elapsed, timedOut = false)
            }
            if (sawChange && now - lastChange >= quietMs) {
                return UiStableResult(changed = true, waitedMs = elapsed, timedOut = false)
            }
            delay(50L)
        }
    }

    /**
     * 等待界面稳定（虚拟屏，靠 ImageReader 的帧信号驱动）。
     *
     * 虚拟屏拿不到无障碍事件流，改用「有没有新帧」代替「有没有内容变化事件」，
     * 两段判定与 [awaitUiStable] 一致：
     * 1. 动作发出后先等第一帧新画面，最多 [initialGraceMs]。一次都没等到说明动作没生效。
     * 2. 之后每来一帧就重置计时，连续 [quietMs] 没有新帧认为界面稳定。
     * 整体最多等 [timeoutMs]：视频、进度条这类持续刷新的页面永远等不到静默。
     *
     * 与主屏相比首帧宽容度更大：帧要等整屏合成完才产生，不像无障碍事件是控件级即时上报。
     * 探测到的帧会缓存在 [VirtualDisplayManager] 里，随后的截图直接复用，不会重复消费。
     */
    suspend fun awaitVirtualUiStable(
        quietMs: Long = 300L,
        timeoutMs: Long = 2000L,
        initialGraceMs: Long = 450L,
        pollMs: Long = 150L
    ): UiStableResult {
        val start = SystemClock.elapsedRealtime()
        var lastFrameAt = start
        var sawFrame = false

        while (true) {
            if (VirtualDisplayManager.peekFrameTimestamp() != null) {
                lastFrameAt = SystemClock.elapsedRealtime()
                sawFrame = true
            }

            val now = SystemClock.elapsedRealtime()
            val elapsed = now - start
            if (elapsed >= timeoutMs) {
                return UiStableResult(changed = sawFrame, waitedMs = elapsed, timedOut = true)
            }
            if (!sawFrame && elapsed >= initialGraceMs) {
                return UiStableResult(changed = false, waitedMs = elapsed, timedOut = false)
            }
            if (sawFrame && now - lastFrameAt >= quietMs) {
                return UiStableResult(changed = true, waitedMs = elapsed, timedOut = false)
            }
            delay(pollMs)
        }
    }

    /** 清理历史截图，只保留最近几张 */
    fun cleanupScreenshots(workDir: File) {
        runCatching {
            val files = workDir.listFiles { file ->
                file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".png"))
            } ?: return
            if (files.size <= KEEP_SCREENSHOT_FILES) return
            files.sortedByDescending { it.lastModified() }
                .drop(KEEP_SCREENSHOT_FILES)
                .forEach { it.delete() }
        }
    }

    private fun screenBoundsOf(context: Context): Rect {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun rootFor(displayId: Int): AccessibilityNodeInfo? {
        val service = ServiceStateBus.getAccessibilityService() ?: return null
        return if (displayId > 0) {
            service.windows.find { it.displayId == displayId }?.root
        } else {
            service.rootInActiveWindow
        }
    }

    private fun imageUriToBase64(context: Context, uri: Uri): Triple<String?, Int, Int> {
        return try {
            val bitmap = decodeDownsampled(context, uri) ?: return Triple(null, 0, 0)

            val output = ByteArrayOutputStream()
            // 长边已经限到 1280，质量 85 足够看清文字，体积比原图小一大半
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, output)

            val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val width = bitmap.width
            val height = bitmap.height

            // 立即回收 Bitmap，释放内存
            bitmap.recycle()

            Triple(base64, width, height)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "图片处理失败", e)
            Triple(null, 0, 0)
        }
    }

    /** 按长边限制解码，避免把 2K / 4K 原图直接塞给模型 */
    private fun decodeDownsampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_LONG_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longSide = maxOf(raw.width, raw.height)
        if (longSide <= MAX_IMAGE_LONG_SIDE) return raw

        val scale = MAX_IMAGE_LONG_SIDE.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            raw,
            maxOf(1, (raw.width * scale).toInt()),
            maxOf(1, (raw.height * scale).toInt()),
            true
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    // 获取最近 30 天使用过的应用列表，按时长排序
    fun getRecentApps(context: Context): String {
        // 1. 检查权限
        if (!PermissionManager.isGranted(context, PermissionManager.USAGE_STATS)) {
            // 如果没权限，回退到获取所有应用（原来的逻辑）
            return getInstalledApps(context)
        }

        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val pm = context.packageManager

            // 2. 获取过去 30 天的数据
            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            val startTime = calendar.timeInMillis

            // queryAndAggregateUsageStats 返回 Map<packageName, UsageStats>
            val statsMap = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)

            if (statsMap.isEmpty()) {
                return getInstalledApps(context) // 系统未记录到数据，回退
            }

            // 3. 获取所有可启动的 App (作为白名单，过滤掉系统后台服务)
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val launchableApps = pm.queryIntentActivities(intent, 0)
            val launchablePackageNames = launchableApps.map { it.activityInfo.packageName }.toSet()

            // 4. 过滤和排序
            val recentAppList = statsMap.values
                .filter {
                    it.totalTimeInForeground > 0 && // 确实使用过
                            launchablePackageNames.contains(it.packageName) // 是用户可见的 App
                }
                .sortedByDescending { it.totalTimeInForeground } // 按使用时长降序
                .mapNotNull { usageStats ->
                    try {
                        val appInfo = pm.getApplicationInfo(usageStats.packageName, 0)
                        val appName = appInfo.loadLabel(pm).toString()
                        // 格式: 微信 (com.tencent.mm)
                        "$appName (${usageStats.packageName})"
                    } catch (e: Exception) {
                        null
                    }
                }

            if (recentAppList.isEmpty()) {
                return getInstalledApps(context)
            }

            return recentAppList.joinToString(", ")

        } catch (e: Exception) {
            DebugLogger.e(TAG, "获取应用使用记录失败", e)
            return getInstalledApps(context) // 出错回退
        }
    }

    // 原有的全量获取方法，作为回退方案
    private fun getInstalledApps(context: Context): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = pm.queryIntentActivities(intent, 0)

        val appList = apps.mapNotNull { resolveInfo ->
            try {
                val appName = resolveInfo.loadLabel(pm).toString()
                val packageName = resolveInfo.activityInfo.packageName
                "$appName ($packageName)"
            } catch (e: Exception) {
                null
            }
        }.joinToString(", ")

        return if (appList.isNotEmpty()) appList else "No launchable apps found."
    }

    /**
     * 获取当前界面信息。
     * 1. 虚拟屏（displayId > 0）：按 displayId 从无障碍窗口列表里取，不能看全局焦点。
     * 2. 主屏：优先 Shell (dumpsys window) 拿精确的 Activity 名。
     * 3. 都失败时回退到 AccessibilityService 的包名。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun getCurrentUIInfo(context: Context, displayId: Int = 0): String {
        // 虚拟屏必须按 displayId 取窗口。原来的实现直接看全局 mCurrentFocus，
        // 那玩意儿跟随的是主屏，会把主屏的 App 名报给模型 —— 模型据此判断
        // "我现在在哪个页面"会全错，然后对着虚拟屏乱点。
        if (displayId > 0) {
            getDisplayUiInfo(displayId)?.let { return it }
        } else if (ShellManager.isShizukuActive(context) || ShellManager.isRootAvailable()) {
            try {
                // 如果是虚拟屏幕，dumpsys 可能需要更复杂的 grep，或者假设焦点正确
                // 暂时使用全局 mCurrentFocus，它通常跟随用户或者最新活动的 display
                val result = ShellManager.execShellCommand(context, "dumpsys window | grep mCurrentFocus", ShellManager.ShellMode.AUTO)

                // 常见输出格式：
                // mCurrentFocus=Window{2b2c9d5 u0 com.package/com.package.Activity}
                // 或者在某些设备上：mCurrentFocus=null
                if (result.contains("/")) {
                    // 尝试提取 com.pkg/com.pkg.Activity
                    // 匹配 "u0 space package/activity" 或者 "package/activity}"
                    val match = Regex("u0\\s+(\\S+)/(\\S+)[}\\s]").find(result)
                    if (match != null) {
                        return "${match.groupValues[1]}/${match.groupValues[2].replace("}", "")}"
                    }

                    // 备用简单正则：匹配斜杠两侧的非空白字符
                    val simpleMatch = Regex("(\\S+\\.\\S+)/(\\S+\\.\\S+)").find(result)
                    if (simpleMatch != null) {
                        return simpleMatch.value.replace("}", "")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.w("AutoGLM", "Shell fetch activity failed: ${e.message}")
            }
        }

        // 尝试无障碍服务 (仅获取包名)
        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            val root = if(displayId > 0) service.windows.find { it.displayId == displayId }?.root else service.rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    return pkg
                }
            }
        }

        return "Unknown UI"
    }

    /**
     * 取指定屏幕上的界面信息。
     *
     * 优先 TYPE_APPLICATION 窗口；一个都没有时退化到该屏任意有包名的窗口
     * （系统弹窗、输入法这类）；连包名都取不到时返回带 displayId 的说明，
     * 至少让模型知道「这是虚拟屏，不是主屏」。
     */
    private fun getDisplayUiInfo(displayId: Int): String? {
        val service = ServiceStateBus.getAccessibilityService() ?: return null
        val windows = runCatching { service.windows }.getOrNull()
            ?.filter { it.displayId == displayId }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val appPkg = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root?.packageName?.toString()
            ?.takeIf { it.isNotBlank() }
        val pkg = appPkg ?: windows.firstNotNullOfOrNull {
            it.root?.packageName?.toString()?.takeIf { name -> name.isNotBlank() }
        }

        return if (!pkg.isNullOrBlank()) pkg else "unknown (display $displayId)"
    }

    /**
     * 在指定屏幕上强制停止顶层应用。
     * 用于在销毁虚拟屏幕前清理现场，防止应用跳回主屏幕。
     */
    suspend fun killTopAppOnDisplay(context: Context, displayId: Int) {
        if (displayId <= 0) return

        DebugLogger.i(TAG, "正在清理虚拟屏幕 (ID: $displayId) 的应用...")
        val foundPackages = mutableSetOf<String>()

        // 1. 尝试无障碍服务 (轻量级)
        val service = ServiceStateBus.getAccessibilityService()
        if (service != null) {
            try {
                // 查找该显示器上的所有应用窗口
                val targetWindows = service.windows.filter {
                    it.displayId == displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                targetWindows.mapNotNull { it.root?.packageName?.toString() }.forEach { foundPackages.add(it) }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "无障碍获取窗口失败: ${e.message}")
            }
        }

        // 2. 如果无障碍没找到，或者应用不可交互，尝试 Shell Dumpsys (更底层，更可靠)
        if (foundPackages.isEmpty()) {
            if (ShellManager.isShizukuActive(context) || ShellManager.isRootAvailable()) {
                DebugLogger.i(TAG, "无障碍未检测到应用，尝试 Shell Dumpsys 深度查找...")
                try {
                    // 查询 Activity 栈信息，并过滤出指定 Display ID 的部分
                    // grep 参数 -A 50 表示显示匹配行后的 50 行，通常包含了该 Display 下的 Task 和 Activity 信息
                    val cmd = "dumpsys activity activities | grep -A 50 \"Display #$displayId\" | grep \"ActivityRecord{\""
                    val output = ShellManager.execShellCommand(context, cmd, ShellManager.ShellMode.AUTO)

                    // 正则提取包名：ActivityRecord{... com.package.name/...}
                    val regex = Regex("ActivityRecord\\{[^ ]+ [^ ]+ ([^/ ]+)/")
                    regex.findAll(output).forEach { matchResult ->
                        val pkg = matchResult.groupValues[1]
                        DebugLogger.d(TAG, "通过 Dumpsys 发现应用: $pkg")
                        foundPackages.add(pkg)
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Shell 查找应用失败", e)
                }
            }
        }

        if (foundPackages.isNotEmpty()) {
            foundPackages.forEach { pkg ->
                // 过滤掉系统UI、vFlow自身、以及桌面Launcher (防止杀掉桌面导致黑屏/闪屏)
                if (pkg != "com.android.systemui" && pkg != context.packageName && !pkg.contains("launcher", ignoreCase = true)) {
                    DebugLogger.w(TAG, "检测到残留应用: $pkg，正在强制停止...")
                    ShellManager.execShellCommand(context, "am force-stop $pkg", ShellManager.ShellMode.AUTO)
                }
            }
        } else {
            DebugLogger.w(TAG, "未在虚拟屏幕检测到活动应用，跳过清理。")
        }
    }
}
