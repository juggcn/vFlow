package com.chaomixian.vflow.ui.workflow_editor

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.google.gson.Gson
import java.io.File

/**
 * 编辑器草稿。
 *
 * Activity 被系统回收（退到桌面、长时间后台、切去别的 App 录屏）时，未保存的改动会随内存一起消失。
 * 这里在 onStop 落一份草稿，下次按 workflowId 匹配捡回来。
 *
 * 只存一份，后写覆盖先写——跟记事本的"恢复上次未保存内容"是一个语义，不做多草稿管理。
 */
object EditorDraftStore {

    private const val TAG = "EditorDraftStore"
    private const val FILE_NAME = "editor_draft.json"

    data class Draft(
        val workflowId: String? = null,
        val name: String = "",
        val triggerSteps: List<ActionStep> = emptyList(),
        val actionSteps: List<ActionStep> = emptyList(),
        val savedAt: Long = 0L
    ) {
        /** 空草稿没有恢复价值，别拿它覆盖用户正在编辑的内容 */
        val isEmpty: Boolean
            get() = name.isBlank() && triggerSteps.isEmpty() && actionSteps.isEmpty()
    }

    private fun fileOf(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(context: Context, draft: Draft): Boolean {
        if (draft.isEmpty) return false
        return runCatching {
            fileOf(context).writeText(
                Gson().toJson(draft.copy(savedAt = System.currentTimeMillis()))
            )
            true
        }.getOrElse {
            DebugLogger.e(TAG, "草稿写入失败", it)
            false
        }
    }

    fun load(context: Context): Draft? {
        val file = fileOf(context)
        if (!file.exists()) return null
        return runCatching {
            Gson().fromJson(file.readText(), Draft::class.java)
        }.getOrElse {
            DebugLogger.e(TAG, "草稿读取失败", it)
            null
        }
    }

    fun clear(context: Context) {
        runCatching { fileOf(context).delete() }
    }
}
