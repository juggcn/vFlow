// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/data/FileAdvancedModule.kt
package com.chaomixian.vflow.core.workflow.module.data

import android.content.Context
import android.webkit.MimeTypeMap
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VFile
import com.chaomixian.vflow.core.workflow.module.data.FileOperationModule
import com.chaomixian.vflow.core.workflow.model.ActionStep
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件高级操作模块
 * 覆盖复制、移动、重命名、列目录、判断存在、获取属性、创建目录。
 */
class FileAdvancedModule : BaseModule() {
    companion object {
        const val OP_COPY = "copy"
        const val OP_MOVE = "move"
        const val OP_RENAME = "rename"
        const val OP_LIST = "list"
        const val OP_EXISTS = "exists"
        const val OP_INFO = "info"
        const val OP_MKDIR = "mkdir"
    }

    override val id = "vflow.data.file_advanced"
    override val metadata = ActionMetadata(
        name = "文件高级操作",
        description = "对文件或目录执行复制、移动、重命名、列出内容、判断存在、获取属性、创建目录等操作。",
        iconRes = com.chaomixian.vflow.R.drawable.ic_folder,
        category = "数据与变量",
        categoryId = "data"
    )
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.STANDARD,
        workflowStepDescription = "Advanced file operations: copy, move, rename, list directory, check existence, get file info, create directory.",
        inputHints = mapOf(
            "operation" to "One of: copy, move, rename, list, exists, info, mkdir.",
            "source_path" to "Source file or directory absolute path.",
            "target_path" to "Target path for copy/move, or new name for rename.",
        ),
        requiredInputIds = setOf("operation"),
    )

    private val fileOpModule = FileOperationModule()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "operation", "操作", ParameterType.ENUM,
            defaultValue = OP_COPY,
            options = listOf(OP_COPY, OP_MOVE, OP_RENAME, OP_LIST, OP_EXISTS, OP_INFO, OP_MKDIR),
        ),
        InputDefinition(
            "source_path", "源路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "要操作的文件或目录的绝对路径",
        ),
        InputDefinition(
            "target_path", "目标路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "复制/移动的目标完整路径；重命名时填新文件名或新路径",
            visibility = InputVisibility.whenIn("operation", listOf(OP_COPY, OP_MOVE, OP_RENAME)),
        ),
        InputDefinition(
            "recursive", "递归复制目录", ParameterType.BOOLEAN,
            defaultValue = true,
            visibility = InputVisibility.whenEquals("operation", OP_COPY),
        ),
        InputDefinition(
            "create_dirs", "自动创建父目录", ParameterType.BOOLEAN,
            defaultValue = true,
            visibility = InputVisibility.whenEquals("operation", OP_MKDIR),
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> {
        val operation = step?.parameters?.get("operation") as? String ?: OP_COPY
        val outputs = mutableListOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
            OutputDefinition("message", "结果信息", VTypeRegistry.STRING.id),
        )
        when (operation) {
            OP_LIST -> {
                outputs += OutputDefinition("files", "文件列表", VTypeRegistry.LIST.id, listElementType = VTypeRegistry.FILE.id)
                outputs += OutputDefinition("count", "数量", VTypeRegistry.NUMBER.id)
            }
            OP_EXISTS -> {
                outputs += OutputDefinition("exists", "是否存在", VTypeRegistry.BOOLEAN.id)
                outputs += OutputDefinition("is_directory", "是否为目录", VTypeRegistry.BOOLEAN.id)
            }
            OP_INFO -> {
                outputs += OutputDefinition("exists", "是否存在", VTypeRegistry.BOOLEAN.id)
                outputs += OutputDefinition("file", "文件对象", VTypeRegistry.FILE.id)
                outputs += OutputDefinition("size", "大小(字节)", VTypeRegistry.NUMBER.id)
                outputs += OutputDefinition("modified_time", "修改时间", VTypeRegistry.STRING.id)
                outputs += OutputDefinition("is_directory", "是否为目录", VTypeRegistry.BOOLEAN.id)
                outputs += OutputDefinition("is_file", "是否为文件", VTypeRegistry.BOOLEAN.id)
            }
            OP_COPY, OP_MOVE, OP_RENAME -> {
                outputs += OutputDefinition("file", "目标文件", VTypeRegistry.FILE.id)
                outputs += OutputDefinition("target_path", "目标路径", VTypeRegistry.STRING.id)
            }
            OP_MKDIR -> {
                outputs += OutputDefinition("file", "目录对象", VTypeRegistry.FILE.id)
            }
        }
        return outputs
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val currentStep = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure("执行错误", "找不到当前步骤")

        val operationInput = getInputs().first { it.id == "operation" }
        val rawOperation = currentStep.parameters["operation"] as? String ?: OP_COPY
        val operation = operationInput.normalizeEnumValue(rawOperation) ?: rawOperation

        val rawSource = currentStep.parameters["source_path"] as? String
            ?: return ExecutionResult.Failure("执行错误", "缺少源路径")
        val sourcePath = fileOpModule.resolvePathInput(rawSource) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("执行错误", "源路径无效：$rawSource")

        val source = File(sourcePath)

        return when (operation) {
            OP_COPY -> {
                val targetPath = resolveTarget(currentStep, context)
                    ?: return ExecutionResult.Failure("执行错误", "缺少目标路径")
                val target = resolveTargetFile(source, File(targetPath))
                try {
                    val result = copyRecursively(source, target, overwrite = true)
                    if (!result) return ExecutionResult.Failure("复制失败", "无法复制 $sourcePath 到 ${target.absolutePath}")
                    Success(
                        context, target,
                        "已复制到 ${target.absolutePath}"
                    )
                } catch (e: Exception) {
                    ExecutionResult.Failure("复制失败", e.message ?: e.toString())
                }
            }
            OP_MOVE -> {
                val targetPath = resolveTarget(currentStep, context)
                    ?: return ExecutionResult.Failure("执行错误", "缺少目标路径")
                val target = resolveTargetFile(source, File(targetPath))
                try {
                    // renameTo 跨分区会失败，先尝试改名，失败则复制后删除
                    if (!source.renameTo(target)) {
                        if (!copyRecursively(source, target, overwrite = true)) {
                            return ExecutionResult.Failure("移动失败", "无法移动 $sourcePath 到 ${target.absolutePath}")
                        }
                        source.deleteRecursively()
                    }
                    Success(context, target, "已移动到 ${target.absolutePath}")
                } catch (e: Exception) {
                    ExecutionResult.Failure("移动失败", e.message ?: e.toString())
                }
            }
            OP_RENAME -> {
                val targetPath = resolveTarget(currentStep, context)
                    ?: return ExecutionResult.Failure("执行错误", "缺少新名称")
                // 重命名：新名字不含路径分隔符时只改文件名，否则按路径处理
                val target = if (targetPath.contains(File.separatorChar)) {
                    File(targetPath)
                } else {
                    File(source.parentFile, targetPath)
                }
                if (target.exists()) {
                    return ExecutionResult.Failure("重命名失败", "目标已存在：${target.absolutePath}")
                }
                if (!source.renameTo(target)) {
                    return ExecutionResult.Failure("重命名失败", "无法重命名 $sourcePath 为 ${target.name}")
                }
                Success(context, target, "已重命名为 ${target.name}")
            }
            OP_LIST -> {
                if (!source.isDirectory) {
                    return ExecutionResult.Failure("列出失败", "$sourcePath 不是目录")
                }
                val files = source.listFiles()?.sortedBy { it.name } ?: emptyList()
                val vFiles = files.map { toVFile(it) }
                Success(
                    context, null,
                    "共 ${files.size} 项",
                    extraOutputs = mapOf(
                        "files" to VList(vFiles),
                        "count" to VNumber(files.size.toDouble()),
                    )
                )
            }
            OP_EXISTS -> {
                Success(
                    context, null,
                    if (source.exists()) "存在：$sourcePath" else "不存在：$sourcePath",
                    extraOutputs = mapOf(
                        "exists" to VBoolean(source.exists()),
                        "is_directory" to VBoolean(source.isDirectory),
                    )
                )
            }
            OP_INFO -> {
                if (!source.exists()) {
                    return ExecutionResult.Failure("获取失败", "路径不存在：$sourcePath")
                }
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                Success(
                    context, source,
                    "大小 ${source.length()} 字节",
                    extraOutputs = mapOf(
                        "exists" to VBoolean(true),
                        "file" to toVFile(source),
                        "size" to VNumber(source.length().toDouble()),
                        "modified_time" to VString(dateFormat.format(Date(source.lastModified()))),
                        "is_directory" to VBoolean(source.isDirectory),
                        "is_file" to VBoolean(source.isFile),
                    )
                )
            }
            OP_MKDIR -> {
                val createDirs = currentStep.parameters["create_dirs"] as? Boolean ?: true
                val ok = if (createDirs) source.mkdirs() || source.isDirectory else source.mkdir()
                if (!ok && !source.isDirectory) {
                    return ExecutionResult.Failure("创建失败", "无法创建目录：$sourcePath")
                }
                Success(context, source, "目录已就绪：$sourcePath")
            }
            else -> ExecutionResult.Failure("执行错误", "未知操作：$operation")
        }
    }

    // 构造成功结果，统一填 success/message
    private fun Success(
        context: ExecutionContext,
        file: File?,
        message: String,
        extraOutputs: Map<String, Any?> = emptyMap()
    ): ExecutionResult {
        val outputs = mutableMapOf<String, Any?>(
            "success" to VBoolean(true),
            "message" to VString(message),
        )
        file?.let { outputs["file"] = toVFile(it) }
        outputs.putAll(extraOutputs)
        return ExecutionResult.Success(outputs)
    }

    private fun resolveTarget(currentStep: ActionStep, context: ExecutionContext): String? {
        val raw = currentStep.parameters["target_path"] as? String ?: return null
        return fileOpModule.resolvePathInput(raw) { VariableResolver.resolve(it, context) }
    }

    // 目标是目录时，把源文件名拼进去
    private fun resolveTargetFile(source: File, target: File): File {
        return if (target.isDirectory) File(target, source.name) else target
    }

    private fun copyRecursively(source: File, target: File, overwrite: Boolean): Boolean {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) return false
            var allOk = true
            source.listFiles()?.forEach { child ->
                if (!copyRecursively(child, File(target, child.name), overwrite)) allOk = false
            }
            return allOk
        }
        if (target.exists() && !overwrite) return false
        target.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return source.copyTo(target, overwrite = true).length() >= 0
    }

    private fun toVFile(file: File): VFile {
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return VFile(file.toURI().toString(), mime)
    }
}
