package com.chaomixian.vflow.ui.workflow_editor

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.google.gson.Gson
import org.json.JSONObject
import java.io.File

/**
 * 编辑器草稿。
 *
 * Activity 被系统回收（退到桌面、长时间后台、切去别的 App 录屏）时，未保存的改动会随内存一起消失。
 * 这里在 onStop 落一份草稿，下次按 workflowId 匹配捡回来。
 *
 * 只存一份，后写覆盖先写——跟记事本的"恢复上次未保存内容"是一个语义，不做多草稿管理。
 *
 * 注意：外层字段名写死成字面量，不用 Gson 直接反射 [Draft]。
 * [Draft] 没有被 proguard 的 keep 规则保护，release 包会把它连同字段名一起混淆成 a/b/c/d/e，
 * 而混淆结果每次编译都可能变——上一版写的草稿到下一版就读不出来了。
 * Gson 反射读不到字段不会抛异常，只会留下一堆 null，接下来的 isBlank()/addAll() 就 NPE 闪退。
 * [ActionStep] 有 keep 规则、字段名稳定，所以只有步列表继续交给 Gson。
 */
object EditorDraftStore {

    private const val TAG = "EditorDraftStore"
    private const val FILE_NAME = "editor_draft.json"

    /** 草稿格式版本。解析不出来或版本对不上，直接当没有草稿并删除文件 */
    private const val SCHEMA_VERSION = 2

    private const val KEY_VERSION = "v"
    private const val KEY_SAVED_AT = "savedAt"
    private const val KEY_WORKFLOW_ID = "workflowId"
    private const val KEY_NAME = "name"
    private const val KEY_TRIGGER_STEPS = "triggerSteps"
    private const val KEY_ACTION_STEPS = "actionSteps"

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

    private val gson by lazy { Gson() }

    private fun fileOf(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(context: Context, draft: Draft): Boolean {
        if (draft.isEmpty) return false
        return runCatching {
            val root = JSONObject().apply {
                put(KEY_VERSION, SCHEMA_VERSION)
                put(KEY_SAVED_AT, System.currentTimeMillis())
                put(KEY_WORKFLOW_ID, draft.workflowId ?: JSONObject.NULL)
                put(KEY_NAME, draft.name)
                put(KEY_TRIGGER_STEPS, gson.toJson(draft.triggerSteps))
                put(KEY_ACTION_STEPS, gson.toJson(draft.actionSteps))
            }
            fileOf(context).writeText(root.toString())
            true
        }.getOrElse {
            DebugLogger.e(TAG, "草稿写入失败", it)
            false
        }
    }

    fun load(context: Context): Draft? {
        val file = fileOf(context)
        if (!file.exists()) return null

        val draft = runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt(KEY_VERSION, 0) != SCHEMA_VERSION) {
                DebugLogger.d(TAG, "草稿版本不匹配，按无草稿处理")
                return@runCatching null
            }
            Draft(
                workflowId = root.optStringOrNull(KEY_WORKFLOW_ID),
                name = root.optString(KEY_NAME),
                triggerSteps = readSteps(root.optString(KEY_TRIGGER_STEPS)),
                actionSteps = readSteps(root.optString(KEY_ACTION_STEPS)),
                savedAt = root.optLong(KEY_SAVED_AT)
            )
        }.getOrElse {
            DebugLogger.e(TAG, "草稿读取失败，按无草稿处理", it)
            null
        }

        // 读不出来就丢掉，否则每次进编辑器都要白读一遍
        if (draft == null) clear(context)
        return draft
    }

    fun clear(context: Context) {
        runCatching { fileOf(context).delete() }
    }

    private fun readSteps(raw: String): List<ActionStep> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            // ActionStep 有 keep 规则，字段名跨版本稳定；数组类型也不带泛型，不受混淆影响
            gson.fromJson(raw, Array<ActionStep>::class.java)?.toList() ?: emptyList()
        }.getOrElse {
            DebugLogger.e(TAG, "草稿步骤解析失败，丢弃该部分", it)
            emptyList()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).ifBlank { null }
    }
}
