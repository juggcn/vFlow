// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/system/SendSmsModule.kt
package com.chaomixian.vflow.core.workflow.module.system

import android.telephony.SmsManager
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager

/**
 * 发送短信模块
 * 通过系统 SmsManager 以本机身份发送短信。
 * 注意：部分运营商对长短信分条计费，超过单条长度时系统会自动分多条发送。
 */
class SendSmsModule : BaseModule() {
    override val id = "vflow.system.send_sms"
    override val metadata = ActionMetadata(
        name = "发送短信",
        description = "向指定号码发送短信内容，内容过长时系统自动分条发送。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_sms_24,
        category = "应用与系统",
        categoryId = "device"
    )
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.HIGH,
        workflowStepDescription = "Send an SMS message to a phone number via the system SMS manager.",
        inputHints = mapOf(
            "phone" to "Destination phone number.",
            "message" to "SMS content.",
        ),
        requiredInputIds = setOf("phone", "message"),
    )

    override val requiredPermissions = listOf(PermissionManager.SMS_SEND)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition("phone", "收件人号码", ParameterType.STRING),
        InputDefinition("message", "短信内容", ParameterType.STRING, supportsRichText = true),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("message", "结果信息", VTypeRegistry.STRING.id),
    )

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val currentStep = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure("执行错误", "找不到当前步骤")

        val rawPhone = currentStep.parameters["phone"] as? String
            ?: return ExecutionResult.Failure("发送失败", "缺少收件人号码")
        val phone = VariableResolver.resolve(rawPhone, context).trim()
        if (phone.isEmpty()) {
            return ExecutionResult.Failure("发送失败", "收件人号码为空")
        }
        val rawContent = currentStep.parameters["message"] as? String
            ?: return ExecutionResult.Failure("发送失败", "缺少短信内容")
        val content = VariableResolver.resolve(rawContent, context)
        if (content.isEmpty()) {
            return ExecutionResult.Failure("发送失败", "短信内容为空")
        }

        return try {
            val smsManager = SmsManager.getDefault()
            // 长内容自动分条发送
            val parts = smsManager.divideMessage(content)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phone, null, content, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            }
            ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "message" to VString("短信已提交发送到 $phone" + if (parts.size > 1) "（分 ${parts.size} 条）" else ""),
                )
            )
        } catch (e: Exception) {
            ExecutionResult.Failure("发送失败", e.message ?: e.toString())
        }
    }
}
