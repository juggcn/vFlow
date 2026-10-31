// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/network/HttpTransferModule.kt
package com.chaomixian.vflow.core.workflow.module.network

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 网络传输模块
 * 支持把 URL 下载保存为文件（带进度），以及通过 multipart/form-data 上传文件。
 */
class HttpTransferModule : BaseModule() {
    companion object {
        const val OP_DOWNLOAD = "download"
        const val OP_UPLOAD = "upload"
        private const val BUFFER_SIZE = 64 * 1024
    }

    override val id = "vflow.network.http_transfer"
    override val metadata = ActionMetadata(
        name = "下载/上传文件",
        description = "从 URL 下载文件到本地（支持进度与断点信息），或通过 multipart 表单把本地文件上传到服务器。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_download_24,
        category = "网络",
        categoryId = "network"
    )
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.STANDARD,
        workflowStepDescription = "Download a URL to a local file with progress, or upload a local file via multipart/form-data.",
        inputHints = mapOf(
            "operation" to "download or upload.",
            "url" to "Remote URL.",
            "save_path" to "Local file path to save (download).",
            "file_path" to "Local file to upload (upload).",
        ),
        requiredInputIds = setOf("operation"),
    )

    private val fileOpModule = FileOperationModule()
    // 上传大文件不设整体超时限制，只限制连接
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "operation", "操作", ParameterType.ENUM,
            defaultValue = OP_DOWNLOAD,
            options = listOf(OP_DOWNLOAD, OP_UPLOAD),
        ),
        InputDefinition(
            "url", "URL", ParameterType.STRING,
            hint = "下载地址或上传接口地址",
        ),
        InputDefinition(
            "save_path", "保存路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "下载内容保存为的本地文件",
            visibility = InputVisibility.whenEquals("operation", OP_DOWNLOAD),
        ),
        InputDefinition(
            "file_path", "文件路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "要上传的本地文件",
            visibility = InputVisibility.whenEquals("operation", OP_UPLOAD),
        ),
        InputDefinition(
            "form_field", "表单字段名", ParameterType.STRING,
            defaultValue = "file",
            hint = "multipart 表单里文件对应的字段名",
            visibility = InputVisibility.whenEquals("operation", OP_UPLOAD),
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> {
        val operation = step?.parameters?.get("operation") as? String ?: OP_DOWNLOAD
        val common = listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
            OutputDefinition("status_code", "HTTP状态码", VTypeRegistry.NUMBER.id),
            OutputDefinition("message", "结果信息", VTypeRegistry.STRING.id),
        )
        return if (operation == OP_DOWNLOAD) {
            common + listOf(
                OutputDefinition("file", "下载的文件", VTypeRegistry.FILE.id),
                OutputDefinition("size", "文件大小(字节)", VTypeRegistry.NUMBER.id),
            )
        } else {
            common + listOf(
                OutputDefinition("response_text", "响应内容", VTypeRegistry.STRING.id),
            )
        }
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val currentStep = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure("执行错误", "找不到当前步骤")

        val operationInput = getInputs().first { it.id == "operation" }
        val rawOperation = currentStep.parameters["operation"] as? String ?: OP_DOWNLOAD
        val operation = operationInput.normalizeEnumValue(rawOperation) ?: rawOperation

        val rawUrl = currentStep.parameters["url"] as? String
            ?: return ExecutionResult.Failure("执行错误", "缺少 URL")
        val url = VariableResolver.resolve(rawUrl, context).trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ExecutionResult.Failure("执行错误", "URL 必须以 http:// 或 https:// 开头：$url")
        }

        return try {
            when (operation) {
                OP_DOWNLOAD -> executeDownload(currentStep, context, url, onProgress)
                OP_UPLOAD -> executeUpload(currentStep, context, url, onProgress)
                else -> ExecutionResult.Failure("执行错误", "未知操作：$operation")
            }
        } catch (e: InterruptedException) {
            ExecutionResult.Failure("已取消", "传输被中断")
        } catch (e: Exception) {
            ExecutionResult.Failure("传输失败", e.message ?: e.toString())
        }
    }

    private suspend fun executeDownload(
        step: ActionStep,
        context: ExecutionContext,
        url: String,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val rawSave = step.parameters["save_path"] as? String
            ?: return ExecutionResult.Failure("下载失败", "缺少保存路径")
        val savePath = fileOpModule.resolvePathInput(rawSave) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("下载失败", "保存路径无效：$rawSave")

        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ExecutionResult.Failure(
                    "下载失败",
                    "服务器返回 HTTP ${response.code}",
                    partialOutputs = mapOf("status_code" to VNumber(response.code.toDouble()))
                )
            }
            val body = response.body
                ?: return ExecutionResult.Failure("下载失败", "响应没有内容")
            val output = File(savePath)
            output.parentFile?.takeIf { !it.exists() }?.mkdirs()

            val total = body.contentLength().let { if (it >= 0) it else -1L }
            var downloaded = 0L
            var lastPercent = -1
            FileOutputStream(output).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        fos.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(ProgressUpdate("下载中 $percent%", percent))
                            }
                        }
                    }
                }
            }
            ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "status_code" to VNumber(response.code.toDouble()),
                    "message" to VString("已下载 ${formatSize(downloaded)} 到 ${output.absolutePath}"),
                    "file" to toVFile(output),
                    "size" to VNumber(downloaded.toDouble()),
                )
            )
        }
    }

    private suspend fun executeUpload(
        step: ActionStep,
        context: ExecutionContext,
        url: String,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val rawFile = step.parameters["file_path"] as? String
            ?: return ExecutionResult.Failure("上传失败", "缺少文件路径")
        val filePath = fileOpModule.resolvePathInput(rawFile) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("上传失败", "文件路径无效：$rawFile")
        val file = File(filePath)
        if (!file.isFile) {
            return ExecutionResult.Failure("上传失败", "文件不存在：$filePath")
        }
        val rawField = step.parameters["form_field"] as? String ?: "file"
        val formField = VariableResolver.resolve(rawField, context).trim().ifBlank { "file" }

        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        onProgress(ProgressUpdate("上传中…", 0))
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(formField, file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(requestBody).build()
        return client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return ExecutionResult.Failure(
                    "上传失败",
                    "服务器返回 HTTP ${response.code}",
                    partialOutputs = mapOf(
                        "status_code" to VNumber(response.code.toDouble()),
                        "response_text" to VString(responseText.take(2000)),
                    )
                )
            }
            onProgress(ProgressUpdate("上传完成", 100))
            ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "status_code" to VNumber(response.code.toDouble()),
                    "message" to VString("上传成功，服务器返回 HTTP ${response.code}"),
                    "response_text" to VString(responseText),
                )
            )
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1 shl 30 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
            bytes >= 1 shl 20 -> "%.2f MB".format(bytes / 1024.0 / 1024)
            bytes >= 1 shl 10 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun toVFile(file: File): VFile {
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return VFile(file.toURI().toString(), mime)
    }
}
