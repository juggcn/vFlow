// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/data/QrCodeModule.kt
package com.chaomixian.vflow.core.workflow.module.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

/**
 * 二维码模块
 * 支持把文本生成为二维码 PNG 图片，以及从图片中识别二维码/条码内容。
 * 生成与识别都走 zxing core（纯 Java），无额外 native 库。
 */
class QrCodeModule : BaseModule() {
    companion object {
        const val OP_GENERATE = "generate"
        const val OP_DECODE = "decode"
        private const val DEFAULT_SIZE = 512
    }

    override val id = "vflow.data.qrcode"
    override val metadata = ActionMetadata(
        name = "二维码",
        description = "把文本内容生成为二维码图片，或从图片文件中识别二维码/条码的内容。",
        iconRes = com.chaomixian.vflow.R.drawable.rounded_crop_free_24,
        category = "数据与变量",
        categoryId = "data"
    )
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "Generate a QR code image from text, or decode a QR code/barcode from an image file.",
        inputHints = mapOf(
            "operation" to "generate to create a QR image, decode to read one from an image.",
            "content" to "Text to encode (generate only).",
            "image_path" to "Image file containing the QR code (decode only).",
            "output_path" to "PNG output path (generate only).",
        ),
        requiredInputIds = setOf("operation"),
    )

    private val fileOpModule = FileOperationModule()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "operation", "操作", ParameterType.ENUM,
            defaultValue = OP_GENERATE,
            options = listOf(OP_GENERATE, OP_DECODE),
        ),
        InputDefinition(
            "content", "二维码内容", ParameterType.STRING,
            supportsRichText = true,
            hint = "要编码进二维码的文本",
            visibility = InputVisibility.whenEquals("operation", OP_GENERATE),
        ),
        InputDefinition(
            "output_path", "输出图片路径", ParameterType.STRING,
            pickerType = PickerType.FILE,
            hint = "生成的 PNG 保存路径",
            visibility = InputVisibility.whenEquals("operation", OP_GENERATE),
        ),
        InputDefinition(
            "size", "尺寸(像素)", ParameterType.NUMBER,
            defaultValue = DEFAULT_SIZE.toDouble(),
            hint = "生成的正方形边长",
            visibility = InputVisibility.whenEquals("operation", OP_GENERATE),
        ),
        InputDefinition(
            "image_path", "图片路径", ParameterType.STRING,
            pickerType = PickerType.MEDIA,
            hint = "包含二维码的图片文件",
            visibility = InputVisibility.whenEquals("operation", OP_DECODE),
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> {
        val operation = step?.parameters?.get("operation") as? String ?: OP_GENERATE
        return if (operation == OP_GENERATE) {
            listOf(
                OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
                OutputDefinition("file", "二维码图片", VTypeRegistry.FILE.id),
                OutputDefinition("output_path", "图片路径", VTypeRegistry.STRING.id),
            )
        } else {
            listOf(
                OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
                OutputDefinition(
                    "found", "是否识别到", VTypeRegistry.BOOLEAN.id,
                    conditionalOptions = listOf(
                        ConditionalOption("识别到", "true"),
                        ConditionalOption("未识别到", "false"),
                    )
                ),
                OutputDefinition("text", "识别内容", VTypeRegistry.STRING.id),
                OutputDefinition("format", "码制格式", VTypeRegistry.STRING.id),
            )
        }
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val currentStep = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure("执行错误", "找不到当前步骤")

        val operationInput = getInputs().first { it.id == "operation" }
        val rawOperation = currentStep.parameters["operation"] as? String ?: OP_GENERATE
        val operation = operationInput.normalizeEnumValue(rawOperation) ?: rawOperation

        return when (operation) {
            OP_GENERATE -> executeGenerate(currentStep, context)
            OP_DECODE -> executeDecode(currentStep, context)
            else -> ExecutionResult.Failure("执行错误", "未知操作：$operation")
        }
    }

    private suspend fun executeGenerate(step: ActionStep, context: ExecutionContext): ExecutionResult {
        val rawContent = step.parameters["content"] as? String
            ?: return ExecutionResult.Failure("生成失败", "缺少二维码内容")
        val content = VariableResolver.resolve(rawContent, context)
        if (content.isBlank()) {
            return ExecutionResult.Failure("生成失败", "二维码内容为空")
        }
        val rawOutput = step.parameters["output_path"] as? String
            ?: return ExecutionResult.Failure("生成失败", "缺少输出图片路径")
        val outputPath = fileOpModule.resolvePathInput(rawOutput) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("生成失败", "输出路径无效：$rawOutput")
        val size = (step.parameters["size"] as? Number)?.toInt()?.coerceIn(64, 4096) ?: DEFAULT_SIZE

        return try {
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            val output = File(outputPath)
            output.parentFile?.takeIf { !it.exists() }?.mkdirs()
            FileOutputStream(output).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bitmap.recycle()
            ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "file" to toVFile(output),
                    "output_path" to VString(output.absolutePath),
                )
            )
        } catch (e: Exception) {
            ExecutionResult.Failure("生成失败", e.message ?: e.toString())
        }
    }

    private suspend fun executeDecode(step: ActionStep, context: ExecutionContext): ExecutionResult {
        val rawImage = step.parameters["image_path"] as? String
            ?: return ExecutionResult.Failure("识别失败", "缺少图片路径")
        val imagePath = fileOpModule.resolvePathInput(rawImage) { VariableResolver.resolve(it, context) }
            ?: return ExecutionResult.Failure("识别失败", "图片路径无效：$rawImage")
        val imageFile = File(imagePath)
        if (!imageFile.isFile) {
            return ExecutionResult.Failure("识别失败", "图片不存在：$imagePath")
        }

        return try {
            // 先按原始尺寸解码，超大图降采样，避免 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(imagePath, options)
                ?: return ExecutionResult.Failure("识别失败", "无法解码图片：$imagePath")

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val result = try {
                MultiFormatReader().decode(
                    BinaryBitmap(HybridBinarizer(source)),
                    mapOf(DecodeHintType.TRY_HARDER to true)
                )
            } catch (e: Exception) {
                null
            }
            if (result == null) {
                // 未识别到也返回成功，用 found 区分
                ExecutionResult.Success(
                    mapOf(
                        "success" to VBoolean(true),
                        "found" to VBoolean(false),
                        "text" to VString(""),
                        "format" to VString(""),
                    )
                )
            } else {
                ExecutionResult.Success(
                    mapOf(
                        "success" to VBoolean(true),
                        "found" to VBoolean(true),
                        "text" to VString(result.text),
                        "format" to VString(result.barcodeFormat.toString()),
                    )
                )
            }
        } catch (e: Exception) {
            ExecutionResult.Failure("识别失败", e.message ?: e.toString())
        }
    }

    private fun toVFile(file: File): VFile {
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/png"
        return VFile(file.toURI().toString(), mime)
    }
}
