// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/data/ArchiveModule.kt
package com.chaomixian.vflow.core.workflow.module.data

import android.webkit.MimeTypeMap
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VFile
import com.chaomixian.vflow.core.workflow.module.data.FileOperationModule
import com.chaomixian.vflow.core.workflow.model.ActionStep
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 压缩解压模块
 * 支持把文件或整个目录压缩为 zip，以及把 zip 解压到指定目录。
 */
class ArchiveModule : BaseModule() {
    companion object {
        const val OP_COMPRESS = "zip_compress"
        const val OP_EXTRACT = "zip_extract"
        private const val BUFFER_SIZE = 64 * 1024
    }

    override val id = "vflow.data.archive"
    override val metadata = ActionMetadata(
        name = "压缩解压",
        description = "把文件或目录压缩为 zip 压缩包，或把 zip 压缩包解压到指定目录。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_layers_fill_24,
        category = "数据与变量",
        categoryId = "data"
    )
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.STANDARD,
        workflowStepDescription = "Compress files/directories into a zip archive or extract a zip archive to a directory.",
        inputHints = mapOf(
            "operation" to "zip_compress to create a zip, zip_extract to unpack one.",
            "source_path" to "File/directory to compress, or the zip file to extract.",
            "target_path" to "Output zip path (compress) or target directory (extract).",
        ),
        requiredInputIds = setOf("operation"),
    )

    private val fileOpModule = FileOperationModule()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "operation", "操作", ParameterType.ENUM,
            defaultValue = OP_COMPRESS,
            options = listOf(OP_COMPRESS, OP_EXTRACT),
        ),
        InputDefinition(
            "source_path", "源路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "压缩：文件或目录；解压：zip 文件",
        ),
        InputDefinition(
            "target_path", "目标路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "压缩：输出的 zip 完整路径；解压：目标目录",
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("message", "结果信息", VTypeRegistry.STRING.id),
        OutputDefinition("file", "输出文件", VTypeRegistry.FILE.id),
        OutputDefinition("count", "处理数量", VTypeRegistry.NUMBER.id),
    )

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val currentStep = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure("执行错误", "找不到当前步骤")

        val operationInput = getInputs().first { it.id == "operation" }
        val rawOperation = currentStep.parameters["operation"] as? String ?: OP_COMPRESS
        val operation = operationInput.normalizeEnumValue(rawOperation) ?: rawOperation

        val rawSource = currentStep.parameters["source_path"] as? String
            ?: return ExecutionResult.Failure("执行错误", "缺少源路径")
        val sourcePath = fileOpModule.resolvePathInput(rawSource) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("执行错误", "源路径无效：$rawSource")
        val rawTarget = currentStep.parameters["target_path"] as? String
            ?: return ExecutionResult.Failure("执行错误", "缺少目标路径")
        val targetPath = fileOpModule.resolvePathInput(rawTarget) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("执行错误", "目标路径无效：$rawTarget")

        val source = File(sourcePath)

        return try {
            when (operation) {
                OP_COMPRESS -> {
                    if (!source.exists()) {
                        return ExecutionResult.Failure("压缩失败", "路径不存在：$sourcePath")
                    }
                    val target = File(targetPath)
                    target.parentFile?.takeIf { !it.exists() }?.mkdirs()
                    var count = 0
                    ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
                        if (source.isFile) {
                            addToZip(zos, source, source.name)
                            count = 1
                        } else {
                            val baseDir = source.absoluteFile.parentFile
                            source.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = file.relativeTo(baseDir).path
                                addToZip(zos, file, entryName)
                                count++
                            }
                        }
                    }
                    ExecutionResult.Success(
                        mapOf(
                            "success" to VBoolean(true),
                            "message" to VString("已压缩 $count 个文件到 ${target.absolutePath}"),
                            "file" to toVFile(target),
                            "count" to VNumber(count.toDouble()),
                        )
                    )
                }
                OP_EXTRACT -> {
                    if (!source.isFile) {
                        return ExecutionResult.Failure("解压失败", "zip 文件不存在：$sourcePath")
                    }
                    val targetDir = File(targetPath)
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        return ExecutionResult.Failure("解压失败", "无法创建目标目录：$targetPath")
                    }
                    var count = 0
                    ZipInputStream(FileInputStream(source).buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(targetDir, entry.name)
                            // 防御 zip 路径穿越（zip slip）
                            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator) &&
                                outFile.canonicalPath != targetDir.canonicalPath
                            ) {
                                entry = zis.nextEntry
                                continue
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos, BUFFER_SIZE)
                                }
                                count++
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    ExecutionResult.Success(
                        mapOf(
                            "success" to VBoolean(true),
                            "message" to VString("已解压 $count 个文件到 ${targetDir.absolutePath}"),
                            "file" to toVFile(targetDir),
                            "count" to VNumber(count.toDouble()),
                        )
                    )
                }
                else -> ExecutionResult.Failure("执行错误", "未知操作：$operation")
            }
        } catch (e: Exception) {
            ExecutionResult.Failure("压缩解压失败", e.message ?: e.toString())
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { fis ->
            fis.copyTo(zos, BUFFER_SIZE)
        }
        zos.closeEntry()
    }

    private fun toVFile(file: File): VFile {
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return VFile(file.toURI().toString(), mime)
    }
}
