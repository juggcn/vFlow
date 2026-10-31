// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/system/ContactModule.kt
package com.chaomixian.vflow.core.workflow.module.system

import android.content.Context
import android.provider.ContactsContract
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager

/**
 * 联系人模块
 * 支持按姓名或号码模糊查询系统联系人，以及新增联系人。
 */
class ContactModule : BaseModule() {
    companion object {
        const val OP_QUERY = "query"
        const val OP_ADD = "add"
        private const val MAX_RESULTS = 50
    }

    override val id = "vflow.system.contact"
    override val metadata = ActionMetadata(
        name = "联系人",
        description = "按姓名或号码查询系统联系人（返回号码列表），或新增联系人。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_swap_calls_24,
        category = "应用与系统",
        categoryId = "device"
    )
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.HIGH,
        workflowStepDescription = "Query system contacts by name or phone substring, or add a new contact.",
        inputHints = mapOf(
            "operation" to "query or add.",
            "keyword" to "Name or phone substring to search (query).",
            "name" to "Contact display name (add).",
            "phone" to "Contact phone number (add).",
        ),
        requiredInputIds = setOf("operation"),
    )

    override val requiredPermissions = listOf(PermissionManager.CONTACTS)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "operation", "操作", ParameterType.ENUM,
            defaultValue = OP_QUERY,
            options = listOf(OP_QUERY, OP_ADD),
        ),
        InputDefinition(
            "keyword", "搜索关键词", ParameterType.STRING,
            hint = "姓名或号码的一部分，留空返回前若干个联系人",
            visibility = InputVisibility.whenEquals("operation", OP_QUERY),
        ),
        InputDefinition(
            "name", "联系人姓名", ParameterType.STRING,
            visibility = InputVisibility.whenEquals("operation", OP_ADD),
        ),
        InputDefinition(
            "phone", "电话号码", ParameterType.STRING,
            visibility = InputVisibility.whenEquals("operation", OP_ADD),
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> {
        val operation = step?.parameters?.get("operation") as? String ?: OP_QUERY
        return if (operation == OP_QUERY) {
            listOf(
                OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
                OutputDefinition("count", "匹配数量", VTypeRegistry.NUMBER.id),
                OutputDefinition("names", "姓名列表", VTypeRegistry.LIST.id, listElementType = VTypeRegistry.STRING.id),
                OutputDefinition("phones", "号码列表", VTypeRegistry.LIST.id, listElementType = VTypeRegistry.STRING.id),
                OutputDefinition("first_name", "第一个姓名", VTypeRegistry.STRING.id),
                OutputDefinition("first_phone", "第一个号码", VTypeRegistry.STRING.id),
            )
        } else {
            listOf(
                OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
                OutputDefinition("message", "结果信息", VTypeRegistry.STRING.id),
            )
        }
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val currentStep = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure("执行错误", "找不到当前步骤")

        val operationInput = getInputs().first { it.id == "operation" }
        val rawOperation = currentStep.parameters["operation"] as? String ?: OP_QUERY
        val operation = operationInput.normalizeEnumValue(rawOperation) ?: rawOperation

        val appContext = context.applicationContext

        return when (operation) {
            OP_QUERY -> {
                val rawKeyword = currentStep.parameters["keyword"] as? String ?: ""
                val keyword = VariableResolver.resolve(rawKeyword, context).trim()

                // 先查联系人主表，再按 contact_id 取号码
                val uri = ContactsContract.Contacts.CONTENT_URI
                val selection = if (keyword.isEmpty()) null
                else "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ? OR ${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
                val selectionArgs = if (keyword.isEmpty()) null
                else arrayOf("$keyword%", "%$keyword%")

                val names = mutableListOf<String>()
                val phones = mutableListOf<String>()

                appContext.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                    selection, selectionArgs,
                    "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE LOCALIZED ASC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                    while (cursor.moveToNext() && names.size < MAX_RESULTS) {
                        val contactId = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        // 查每个联系人的第一个可用号码
                        val phone = queryFirstPhone(appContext, contactId)
                        names += name
                        phones += phone
                    }
                }

                ExecutionResult.Success(
                    mapOf(
                        "success" to VBoolean(true),
                        "count" to VNumber(names.size.toDouble()),
                        "names" to VList(names.map { VString(it) }),
                        "phones" to VList(phones.map { VString(it) }),
                        "first_name" to VString(names.firstOrNull() ?: ""),
                        "first_phone" to VString(phones.firstOrNull() ?: ""),
                    )
                )
            }
            OP_ADD -> {
                val rawName = currentStep.parameters["name"] as? String
                    ?: return ExecutionResult.Failure("添加失败", "缺少联系人姓名")
                val name = VariableResolver.resolve(rawName, context).trim()
                if (name.isEmpty()) {
                    return ExecutionResult.Failure("添加失败", "联系人姓名为空")
                }
                val rawPhone = currentStep.parameters["phone"] as? String
                    ?: return ExecutionResult.Failure("添加失败", "缺少电话号码")
                val phone = VariableResolver.resolve(rawPhone, context).trim()
                if (phone.isEmpty()) {
                    return ExecutionResult.Failure("添加失败", "电话号码为空")
                }

                // 一次事务写入姓名 + 手机号
                val ops = arrayListOf<android.content.ContentProviderOperation>()
                ops += android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
                ops += android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
                ops += android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
                appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                ExecutionResult.Success(
                    mapOf(
                        "success" to VBoolean(true),
                        "message" to VString("已添加联系人：$name"),
                    )
                )
            }
            else -> ExecutionResult.Failure("执行错误", "未知操作：$operation")
        }
    }

    // 取联系人的第一个手机号，没有就返回空串
    private fun queryFirstPhone(context: Context, contactId: Long): String {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) ?: ""
            }
        }
        return ""
    }
}
