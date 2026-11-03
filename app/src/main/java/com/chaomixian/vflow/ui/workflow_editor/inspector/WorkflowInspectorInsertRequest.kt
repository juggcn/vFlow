package com.chaomixian.vflow.ui.workflow_editor.inspector

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class WorkflowInspectorInsertRequest(
    val type: Type,
    val value: String? = null,
    val packageName: String? = null,
    val activityName: String? = null
) : Parcelable {

    enum class Type {
        CLICK,
        UI_SELECTOR,
        LAUNCH_APP,
        LAUNCH_ACTIVITY,
        FIND_TEXT,

        /**
         * 按选择器点击：插入「UI选择器」+「点击」两步，点击的 target 用魔法变量
         * 引用前一步的 element。目标是没有任何 ID 可填的控件时用这个。
         */
        CLICK_SELECTOR
    }

    companion object {
        const val ACTION_INSERT_REQUEST = "com.chaomixian.vflow.action.WORKFLOW_INSPECTOR_INSERT"
        const val EXTRA_ENABLE_WORKFLOW_INSERT = "enable_workflow_insert"
        const val EXTRA_INSERT_REQUEST = "insert_request"

        fun click(target: String) = WorkflowInspectorInsertRequest(
            type = Type.CLICK,
            value = target
        )

        fun clickSelector(selector: String) = WorkflowInspectorInsertRequest(
            type = Type.CLICK_SELECTOR,
            value = selector
        )

        fun uiSelector(selector: String) = WorkflowInspectorInsertRequest(
            type = Type.UI_SELECTOR,
            value = selector
        )

        fun launchApp(packageName: String) = WorkflowInspectorInsertRequest(
            type = Type.LAUNCH_APP,
            packageName = packageName,
            activityName = "LAUNCH"
        )

        fun launchActivity(packageName: String, activityName: String) = WorkflowInspectorInsertRequest(
            type = Type.LAUNCH_ACTIVITY,
            packageName = packageName,
            activityName = activityName
        )

        fun findText(text: String) = WorkflowInspectorInsertRequest(
            type = Type.FIND_TEXT,
            value = text
        )
    }
}
