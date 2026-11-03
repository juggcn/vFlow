// 文件: main/java/com/chaomixian/vflow/services/UiInspectorService.kt
package com.chaomixian.vflow.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.inspect.InspectResult
import com.chaomixian.vflow.core.inspect.SelectorCandidateBuilder
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.locale.LocaleManager
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.collectWindowRoots
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.chaomixian.vflow.ui.workflow_editor.inspector.WorkflowInspectorInsertRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * UI 检查器服务
 */
class UiInspectorService : Service() {
    companion object {
        private const val INSPECT_THROTTLE_MS = 32L
        private const val COORD_LABEL_MARGIN_DP = 8
    }

    private lateinit var windowManager: WindowManager
    private var overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var floatIconView: ImageView? = null
    private var coordinateLabelView: TextView? = null
    private var selectionFrameView: View? = null

    private lateinit var iconParams: WindowManager.LayoutParams
    private lateinit var coordinateLabelParams: WindowManager.LayoutParams
    private lateinit var frameParams: WindowManager.LayoutParams

    private var currentNodeInfo: AccessibilityNodeInfo? = null

    /** 当前这一轮拾取抓到的全部窗口根节点 */
    private var currentRoots: List<AccessibilityNodeInfo> = emptyList()

    /** 当前拾取结果（含候选选择器与唯一性） */
    private var currentInspect: InspectResult? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var currentProbeX = 0
    private var currentProbeY = 0
    private var pendingInspectX = 0
    private var pendingInspectY = 0
    private var isInspectScheduled = false
    private var enableWorkflowInsert = false
    private val inspectRunnable = Runnable {
        isInspectScheduled = false
        performNodeInspection(pendingInspectX, pendingInspectY)
    }

    // 用于列表展示的数据结构
    private sealed class InspectorItem {
        data class Header(val title: String) : InspectorItem()
        data class Property(
            val label: String,
            val value: String,
            val insertRequest: WorkflowInspectorInsertRequest? = null
        ) : InspectorItem()
    }

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getLanguage(newBase)
        val localizedContext = LocaleManager.applyLanguage(newBase, languageCode)
        val context = AppearanceManager.applyDisplayScale(localizedContext)
        super.attachBaseContext(context)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enableWorkflowInsert = intent?.getBooleanExtra(
            WorkflowInspectorInsertRequest.EXTRA_ENABLE_WORKFLOW_INSERT,
            false
        ) == true
        if (floatIconView != null) {
            setupViews()
        }
        Toast.makeText(this, getString(R.string.ui_inspector_service_started), Toast.LENGTH_SHORT).show()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        setupViews()
    }

    private fun setupViews() {
        cleanupViews()
        val service = ServiceStateBus.getAccessibilityService()
        val useAccessibilityOverlay = service != null && tryCreateWith(
            service.getSystemService(WINDOW_SERVICE) as WindowManager,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )
        if (!useAccessibilityOverlay) {
            try {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                updateScreenMetrics()
                createFloatIcon()
                createSelectionFrame()
            } catch (e: Exception) {
                DebugLogger.e("UiInspector", "Error creating UI Inspector", e)
                Toast.makeText(this, getString(R.string.ui_inspector_start_failed, e.message), Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
        }
        DebugLogger.d("UiInspector", "UI Inspector Created")
    }

    private fun tryCreateWith(wm: WindowManager, windowType: Int): Boolean {
        return try {
            windowManager = wm
            overlayWindowType = windowType
            updateScreenMetrics()
            createFloatIcon()
            createSelectionFrame()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupViews() {
        listOf(coordinateLabelView, floatIconView, selectionFrameView).forEach { view ->
            view?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
        }
        coordinateLabelView = null
        floatIconView = null
        selectionFrameView = null
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun createSelectionFrame() {
        val themedContext = ThemeUtils.createThemedContext(this)
        val strokeColor = resolveThemeColor(themedContext, android.R.attr.colorError)

        selectionFrameView = View(this).apply {
            background = GradientDrawable().apply {
                setStroke(8, strokeColor)
                setColor(Color.TRANSPARENT)
                cornerRadius = 24f
            }
            visibility = View.GONE
        }

        frameParams = WindowManager.LayoutParams().apply {
            type = overlayWindowType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = 0
            height = 0
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(selectionFrameView, frameParams)
        } catch (e: Exception) { }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatIcon() {
        // 使用 ThemeUtils 确保能获取到 Material 3 动态取色
        val themedContext = ThemeUtils.createThemedContext(this)

        val containerColor = resolveThemeColor(themedContext, com.google.android.material.R.attr.colorPrimaryContainer)
        val iconColor = resolveThemeColor(themedContext, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val surfaceColor = resolveThemeColor(themedContext, com.google.android.material.R.attr.colorSurface)
        val onSurfaceColor = resolveThemeColor(themedContext, com.google.android.material.R.attr.colorOnSurface)

        coordinateLabelView = TextView(themedContext).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(onSurfaceColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), containerColor)
            }
            elevation = 22f

            val maxCoordLabel = "$screenWidth,$screenHeight"
            minWidth = ceil(paint.measureText(maxCoordLabel)).toInt() + paddingLeft + paddingRight

            isClickable = true
            isFocusable = false
            setOnClickListener {
                copyToClipboard(
                    getString(R.string.ui_inspector_current_coordinate),
                    "$currentProbeX,$currentProbeY"
                )
            }
        }

        coordinateLabelParams = WindowManager.LayoutParams().apply {
            type = overlayWindowType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 400
        }

        floatIconView = ImageView(themedContext).apply {
            setImageResource(R.drawable.rounded_architecture_24)
            setBackgroundResource(R.drawable.bg_widget_rounded)
            background.setTint(containerColor)
            setColorFilter(iconColor)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            elevation = 20f
        }

        iconParams = WindowManager.LayoutParams().apply {
            type = overlayWindowType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 400
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L
        val LONG_PRESS_TIMEOUT = 500L // 长按阈值（毫秒）

        floatIconView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = iconParams.x
                    initialY = iconParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()

                    val service = ServiceStateBus.getAccessibilityService()
                    if (service != null) {
                        // 抓全部窗口：浮层广告、PopupWindow 通常不在 rootInActiveWindow 里，
                        // 只看激活窗口会整片漏掉，这是旧版「获取不了」的主因。
                        currentRoots = runCatching { collectWindowRoots(service) }
                            .getOrDefault(emptyList())
                    } else {
                        currentRoots = emptyList()
                        Toast.makeText(this, getString(R.string.ui_inspector_enable_accessibility), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }

                    iconParams.x = initialX + dx
                    iconParams.y = initialY + dy
                    windowManager.updateViewLayout(floatIconView, iconParams)

                    scheduleInspectionAtCurrentIconCenter()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    flushPendingInspection()
                    val pressDuration = System.currentTimeMillis() - downTime
                    if (!isDragging && pressDuration >= LONG_PRESS_TIMEOUT) {
                        // 长按：录制 / 关闭菜单
                        showServiceMenu()
                    } else if (!isDragging) {
                        // 短按：显示详情
                        inspectCurrentIconCenter()
                        showNodeDetails()
                    }
                    currentRoots = emptyList()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(coordinateLabelView, coordinateLabelParams)
            windowManager.addView(floatIconView, iconParams)
            floatIconView?.post { refreshCurrentProbeCoordinate() }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.ui_inspector_float_permission_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun inspectCurrentIconCenter() {
        if (!refreshCurrentProbeCoordinate()) return
        inspectNodeAt(currentProbeX, currentProbeY)
    }

    private fun scheduleInspectionAtCurrentIconCenter() {
        if (!refreshCurrentProbeCoordinate()) return
        pendingInspectX = currentProbeX
        pendingInspectY = currentProbeY
        if (isInspectScheduled) return
        isInspectScheduled = true
        floatIconView?.postDelayed(inspectRunnable, INSPECT_THROTTLE_MS)
    }

    private fun flushPendingInspection() {
        floatIconView?.removeCallbacks(inspectRunnable)
        if (isInspectScheduled) {
            isInspectScheduled = false
            performNodeInspection(pendingInspectX, pendingInspectY)
        }
    }

    private fun refreshCurrentProbeCoordinate(): Boolean {
        val iconView = floatIconView ?: return false
        if (iconView.width == 0 || iconView.height == 0) return false

        currentProbeX = iconParams.x + iconView.width / 2
        currentProbeY = iconParams.y + iconView.height / 2
        updateCurrentCoordinateLabel(currentProbeX, currentProbeY)
        return true
    }

    private fun updateCurrentCoordinateLabel(x: Int, y: Int) {
        // 录制中在坐标前面加个实心点，一眼看出当前状态
        val newLabel = if (InteractionRecorder.isRecording) "● $x,$y" else "$x,$y"
        val labelView = coordinateLabelView ?: return

        if (labelView.text != newLabel) {
            labelView.text = newLabel
        }

        val labelWidth = labelView.width
        val labelHeight = labelView.height
        if (labelWidth <= 0 || labelHeight <= 0) {
            labelView.post { updateCurrentCoordinateLabel(x, y) }
            return
        }

        coordinateLabelParams.x = x - labelWidth / 2
        coordinateLabelParams.y = iconParams.y - labelHeight - dp(COORD_LABEL_MARGIN_DP)
        try {
            windowManager.updateViewLayout(labelView, coordinateLabelParams)
        } catch (_: Exception) { }
    }

    /**
     * 松开手指后的完整拾取：生成候选选择器并统计唯一性。
     * 唯一性要遍历整棵树，拖动过程中不做（32ms 一次会明显卡）。
     */
    private fun inspectNodeAt(x: Int, y: Int) {
        val target = findTopTarget(x, y) ?: return
        currentNodeInfo = target
        currentInspect = runCatching {
            SelectorCandidateBuilder.inspect(target, currentRoots)
        }.getOrNull()
        currentInspect?.let { drawSelectionFrame(it.bounds) }
    }

    /** 拖动过程中的轻量拾取：只找节点画框，不做唯一性统计 */
    private fun performNodeInspection(x: Int, y: Int) {
        val target = findTopTarget(x, y)
        if (target == null) {
            currentNodeInfo = null
            currentInspect = null
            selectionFrameView?.visibility = View.GONE
            return
        }
        if (target == currentNodeInfo) return
        currentNodeInfo = target
        drawSelectionFrame(Rect().also { target.getBoundsInScreen(it) })
    }

    /**
     * 在所有窗口里各找一个包含该点的最小节点，取面积最小的那个。
     * 多窗口是必须的：浮层广告是独立窗口，rootInActiveWindow 里根本没有它。
     */
    private fun findTopTarget(x: Int, y: Int): AccessibilityNodeInfo? {
        val roots = currentRoots
        if (roots.isEmpty()) return null
        return roots.asSequence()
            .mapNotNull { root -> findSmallestNodeAtPoint(root, x, y) }
            .minByOrNull { node ->
                val rect = Rect().also { node.getBoundsInScreen(it) }
                rect.width() * rect.height()
            }
    }

    private fun drawSelectionFrame(rect: Rect) {
        frameParams.x = rect.left
        frameParams.y = rect.top
        frameParams.width = rect.width()
        frameParams.height = rect.height()

        selectionFrameView?.visibility = View.VISIBLE
        try {
            windowManager.updateViewLayout(selectionFrameView, frameParams)
        } catch (_: Exception) { }
    }

    private fun findSmallestNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // 检查点是否在节点范围内
        if (!bounds.contains(x, y)) return null

        // 收集所有包含该点的子节点。
        // 这里不要求 isVisibleToUser：浮层叠在页面上时，系统常把真正的按钮判成不可见，
        // 但用户肉眼能看到也点得到它。只按 bounds 判定，最后取面积最小的即可。
        val validChildren = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSmallestNodeAtPoint(child, x, y)
            if (found != null) {
                validChildren.add(found)
            }
        }

        // 如果有有效的子节点，返回面积最小的那个
        if (validChildren.isNotEmpty()) {
            return validChildren.minByOrNull {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                rect.width() * rect.height()
            }
        }

        // 没有子节点命中时返回自身，但面积为 0 的节点（隐藏/占位）不算
        return if (bounds.width() > 0 && bounds.height() > 0) node else null
    }

    // --- 录制 ---

    /**
     * 长按悬浮图标弹出的菜单。
     *
     * 录制期间不挂任何全屏捕获层——用户照常操作 App，录制靠无障碍事件完成，
     * 这样才看得到真实界面反馈（旧的触摸回放是吃掉触摸后盲录的）。
     */
    private fun showServiceMenu() {
        val dialogContext = ThemeUtils.createThemedContext(
            ServiceStateBus.getAccessibilityService() ?: this
        )

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (InteractionRecorder.isRecording) {
            labels.add(getString(R.string.ui_inspector_menu_stop_record, InteractionRecorder.capturedCount))
            actions.add { stopRecordingAndInsert() }
        } else {
            labels.add(getString(R.string.ui_inspector_menu_start_record))
            actions.add { startRecording() }
        }
        labels.add(getString(R.string.ui_inspector_menu_close))
        actions.add { stopSelf() }

        val dialog = MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.ui_inspector_menu_title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .create()
        dialog.window?.setType(overlayWindowType)
        dialog.show()
    }

    private fun startRecording() {
        InteractionRecorder.startRecording()
        refreshCurrentProbeCoordinate()
        Toast.makeText(this, getString(R.string.ui_inspector_record_started), Toast.LENGTH_LONG).show()
    }

    private fun stopRecordingAndInsert() {
        val count = InteractionRecorder.stopRecording()
        refreshCurrentProbeCoordinate()

        if (count == 0) {
            Toast.makeText(this, getString(R.string.ui_inspector_record_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (!enableWorkflowInsert) {
            Toast.makeText(this, getString(R.string.ui_inspector_record_need_editor), Toast.LENGTH_LONG).show()
            return
        }
        sendInsertRequest(WorkflowInspectorInsertRequest.recordedSteps(count))
    }

    // --- 弹窗显示逻辑 ---

    private fun showNodeDetails() {
        val inspect = currentInspect ?: run {
            Toast.makeText(this, getString(R.string.ui_inspector_no_selection), Toast.LENGTH_SHORT).show()
            return
        }

        val items = collectNodeInfo(inspect)

        val dialogContext = ThemeUtils.createThemedContext(
            ServiceStateBus.getAccessibilityService() ?: this
        )

        val detailsView = createDetailsListView(dialogContext, items)

        val builder = MaterialAlertDialogBuilder(dialogContext)
            .setView(detailsView)
            .setPositiveButton(getString(R.string.ui_inspector_copy_all)) { _, _ ->
                val fullText = items.joinToString("\n") { item ->
                    when(item) {
                        is InspectorItem.Header -> "\n=== ${item.title} ==="
                        is InspectorItem.Property -> "${item.label}: ${item.value}"
                    }
                }
                copyToClipboard("Full Details", fullText)
            }
            .setNeutralButton(getString(R.string.ui_inspector_close_service)) { _, _ -> stopSelf() }
            .setNegativeButton(getString(R.string.ui_inspector_close), null)

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        if (enableWorkflowInsert) {
            builder.setCustomTitle(
                createDialogTitleView(dialogContext, getString(R.string.ui_inspector_properties_title)) {
                    sendInsertRequest(buildBestInsertRequest(inspect))
                    dialog.dismiss()
                }
            )
        } else {
            builder.setTitle(getString(R.string.ui_inspector_properties_title))
        }

        dialog = builder.create()

        dialog.window?.setType(overlayWindowType)

        dialog.show()
    }

    private fun collectNodeInfo(inspect: InspectResult): List<InspectorItem> {
        val list = mutableListOf<InspectorItem>()
        val node = inspect.target
        val rect = inspect.bounds
        refreshCurrentProbeCoordinate()

        val packageName = node.packageName?.toString() ?: getString(R.string.ui_inspector_unknown)
        var appName = getString(R.string.ui_inspector_unknown)
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            appName = pm.getApplicationLabel(info).toString()
        } catch (e: Exception) { }

        val currentActivity = ServiceStateBus.lastActivityClassName ?: getString(R.string.ui_inspector_unknown)
        val currentWindow = ServiceStateBus.lastWindowClassName ?: getString(R.string.ui_inspector_unknown)
        val currentAppInsertRequest = buildLaunchAppInsertRequest(packageName)
        val currentPageInsertRequest = buildLaunchActivityInsertRequest(
            ServiceStateBus.lastActivityPackageName ?: packageName,
            currentActivity
        )
        val controlId = node.viewIdResourceName ?: getString(R.string.ui_inspector_none)
        val controlText = node.text?.toString() ?: ""
        val topLeftCoordinate = "${rect.left},${rect.top}"
        val bottomRightCoordinate = "${rect.right},${rect.bottom}"
        val centerCoordinate = "${rect.centerX()},${rect.centerY()}"
        val currentCoordinate = "$currentProbeX,$currentProbeY"

        list.add(InspectorItem.Header(getString(R.string.ui_inspector_basic_info)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_current_app), "$appName ($packageName)", currentAppInsertRequest))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_current_page), currentActivity, currentPageInsertRequest))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_current_window), currentWindow))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_control_id), controlId, buildClickInsertRequest(controlId)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_control_text), controlText, buildFindTextInsertRequest(controlText)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_control_type), node.className?.toString() ?: getString(R.string.ui_inspector_unknown)))

        // 选择器候选：按「越稳越靠前」排序，长按某一行即可把选择器插入工作流
        list.add(InspectorItem.Header(getString(R.string.ui_inspector_selector_candidates)))
        if (inspect.candidates.isEmpty()) {
            list.add(
                InspectorItem.Property(
                    getString(R.string.ui_inspector_no_candidate),
                    getString(R.string.ui_inspector_no_candidate_hint)
                )
            )
        } else {
            if (inspect.sameKeyCount > 1) {
                list.add(
                    InspectorItem.Property(
                        getString(R.string.ui_inspector_duplicate_notice),
                        getString(R.string.ui_inspector_duplicate_count, inspect.sameKeyCount)
                    )
                )
            }
            if (inspect.clickableInChain) {
                list.add(
                    InspectorItem.Property(
                        getString(R.string.ui_inspector_clickable_ancestor),
                        inspect.clickNode.className?.toString() ?: getString(R.string.ui_inspector_unknown)
                    )
                )
            }
            inspect.candidates.forEach { candidate ->
                val label = if (candidate.unique) {
                    candidate.label
                } else {
                    "${candidate.label} · ${getString(R.string.ui_inspector_hit_count, candidate.hitCount)}"
                }
                list.add(
                    InspectorItem.Property(
                        label,
                        candidate.selector,
                        WorkflowInspectorInsertRequest.clickSelector(candidate.selector)
                    )
                )
            }
        }

        list.add(InspectorItem.Header(getString(R.string.ui_inspector_layout_coordinates)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_current_coordinate), currentCoordinate, WorkflowInspectorInsertRequest.click(currentCoordinate)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_top_left), topLeftCoordinate, WorkflowInspectorInsertRequest.click(topLeftCoordinate)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_bottom_right), bottomRightCoordinate, WorkflowInspectorInsertRequest.click(bottomRightCoordinate)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_center_point), centerCoordinate, WorkflowInspectorInsertRequest.click(centerCoordinate)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_coord_x), rect.left.toString(), WorkflowInspectorInsertRequest.click(topLeftCoordinate)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_coord_y), rect.top.toString(), WorkflowInspectorInsertRequest.click(topLeftCoordinate)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_width), rect.width().toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_height), rect.height().toString()))

        list.add(InspectorItem.Header(getString(R.string.ui_inspector_hierarchy)))
        val directChildCount = node.childCount
        val (totalChildCount, childTexts) = countAllChildrenAndText(node)
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_child_count), directChildCount.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_total_children), totalChildCount.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_child_text), if(childTexts.length > 300) childTexts.take(300) + "..." else childTexts))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_depth), calculateDepth(node).toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_parent), node.parent?.className?.toString() ?: getString(R.string.ui_inspector_none)))

        list.add(InspectorItem.Header(getString(R.string.ui_inspector_state)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_clickable), node.isClickable.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_long_clickable), node.isLongClickable.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_scrollable), node.isScrollable.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_checkable), node.isCheckable.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_checked), node.isChecked.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_focusable), node.isFocusable.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_focused), node.isFocused.toString()))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_visibility), if(node.isVisibleToUser) getString(R.string.ui_inspector_visible) else getString(R.string.ui_inspector_invisible)))
        list.add(InspectorItem.Property(getString(R.string.ui_inspector_enabled_status), node.isEnabled.toString()))

        return list
    }

    private fun createDialogTitleView(
        context: Context,
        title: String,
        onInsertClick: () -> Unit
    ): View {
        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }

        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val insertButton = ImageButton(context).apply {
            setImageResource(R.drawable.rounded_variable_insert_24)
            val backgroundDrawable = MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                    .setAllCornerSizes(dp(14).toFloat())
                    .build()
            ).apply {
                initializeElevationOverlay(context)
                fillColor = ColorStateList.valueOf(
                    resolveThemeColor(context, com.google.android.material.R.attr.colorSecondaryContainer)
                )
            }
            background = RippleDrawable(
                ColorStateList.valueOf(
                    resolveThemeColor(context, android.R.attr.colorControlHighlight)
                ),
                backgroundDrawable,
                null
            )
            imageTintList = ColorStateList.valueOf(
                resolveThemeColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer)
            )
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = dp(40)
            minimumHeight = dp(40)
            contentDescription = context.getString(R.string.ui_inspector_insert_click_module)
            setOnClickListener { onInsertClick() }
        }

        titleContainer.addView(titleView)
        titleContainer.addView(insertButton)
        return titleContainer
    }

    /**
     * 标题栏「插入点击」用哪个候选：
     * 有唯一命中的选择器就用「选择器点击」两步（跨布局、跨分辨率可用），
     * 一个都没有就退回中心坐标，至少能点。
     */
    private fun buildBestInsertRequest(inspect: InspectResult): WorkflowInspectorInsertRequest? {
        val best = inspect.best
        if (best != null && best.unique) {
            return WorkflowInspectorInsertRequest.clickSelector(best.selector)
        }
        return WorkflowInspectorInsertRequest.click("${inspect.centerX},${inspect.centerY}")
    }

    private fun buildClickInsertRequest(target: String): WorkflowInspectorInsertRequest? {
        if (!isInsertableValue(target)) return null
        return WorkflowInspectorInsertRequest.click(target)
    }

    private fun buildFindTextInsertRequest(text: String): WorkflowInspectorInsertRequest? {
        if (!isInsertableValue(text)) return null
        return WorkflowInspectorInsertRequest.findText(text)
    }

    private fun buildLaunchAppInsertRequest(packageName: String): WorkflowInspectorInsertRequest? {
        if (!isInsertableValue(packageName)) return null
        return WorkflowInspectorInsertRequest.launchApp(packageName)
    }

    private fun buildLaunchActivityInsertRequest(
        packageName: String,
        activityName: String
    ): WorkflowInspectorInsertRequest? {
        if (!isInsertableValue(packageName) || !isInsertableValue(activityName)) return null
        return WorkflowInspectorInsertRequest.launchActivity(packageName, activityName)
    }

    private fun sendInsertRequest(request: WorkflowInspectorInsertRequest?) {
        if (!enableWorkflowInsert || request == null) return
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(WorkflowInspectorInsertRequest.ACTION_INSERT_REQUEST).apply {
                putExtra(WorkflowInspectorInsertRequest.EXTRA_INSERT_REQUEST, request)
            }
        )
        Toast.makeText(this, R.string.ui_inspector_insert_request_sent, Toast.LENGTH_SHORT).show()
    }

    private fun isInsertableValue(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        return trimmed != getString(R.string.ui_inspector_unknown) &&
                trimmed != getString(R.string.ui_inspector_none)
    }

    /**
     * 辅助函数：解析当前主题中的颜色属性
     */
    private fun resolveThemeColor(context: Context, @AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }

    private fun createDetailsListView(context: Context, items: List<InspectorItem>): View {
        val scrollView = ScrollView(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        for (item in items) {
            val itemView = when (item) {
                is InspectorItem.Header -> createHeaderView(context, item.title)
                is InspectorItem.Property -> createPropertyRowView(context, item)
            }
            container.addView(itemView)
        }

        scrollView.addView(container)
        return scrollView
    }

    private fun createHeaderView(context: Context, title: String): View {
        return TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)

            // 使用 Primary 颜色
            setTextColor(resolveThemeColor(context, android.R.attr.colorPrimary))

            setPadding(48, 32, 48, 16)
        }
    }

    private fun createPropertyRowView(context: Context, item: InspectorItem.Property): View {
        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            // 添加点击波纹效果
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)

            isClickable = true
            setOnClickListener { copyToClipboard(item.label, item.value) }
            if (enableWorkflowInsert && item.insertRequest != null) {
                isLongClickable = true
                setOnLongClickListener {
                    sendInsertRequest(item.insertRequest)
                    true
                }
            }
            setPadding(48, 16, 24, 16)
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 标签
        val labelView = TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

        // 值
        val valueView = TextView(context).apply {
            text = item.value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface))
            typeface = Typeface.MONOSPACE
        }

        textContainer.addView(labelView)
        textContainer.addView(valueView)

        // 复制按钮
        val copyButton = ImageButton(context).apply {
            setImageResource(R.drawable.rounded_content_copy_24)
            // 添加波纹效果
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            imageTintList = ColorStateList.valueOf(resolveThemeColor(context, android.R.attr.colorPrimary))
            setPadding(24, 24, 24, 24)
            setOnClickListener { copyToClipboard(item.label, item.value) }
            if (enableWorkflowInsert && item.insertRequest != null) {
                setOnLongClickListener {
                    sendInsertRequest(item.insertRequest)
                    true
                }
            }
        }

        rowLayout.addView(textContainer)
        rowLayout.addView(copyButton)

        return rowLayout
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.ui_inspector_copied, label), Toast.LENGTH_SHORT).show()
    }

    private fun countAllChildrenAndText(node: AccessibilityNodeInfo): Pair<Int, String> {
        var count = 0
        val sb = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count++
            if (!child.text.isNullOrBlank()) sb.append("[${child.text}] ")
            val (subCount, subText) = countAllChildrenAndText(child)
            count += subCount
            sb.append(subText)
        }
        return count to sb.toString()
    }

    private fun calculateDepth(node: AccessibilityNodeInfo): Int {
        var depth = 0
        var parent = node.parent
        while (parent != null) {
            depth++
            parent = parent.parent
        }
        return depth
    }


    override fun onDestroy() {
        super.onDestroy()
        try {
            floatIconView?.removeCallbacks(inspectRunnable)
            if (coordinateLabelView != null) windowManager.removeView(coordinateLabelView)
            if (floatIconView != null) windowManager.removeView(floatIconView)
            if (selectionFrameView != null) windowManager.removeView(selectionFrameView)
        } catch (e: Exception) { }
        Toast.makeText(this, getString(R.string.ui_inspector_service_closed), Toast.LENGTH_SHORT).show()
    }
}
