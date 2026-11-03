// 文件: main/java/com/chaomixian/vflow/core/utils/VirtualDisplayManager.kt
package com.chaomixian.vflow.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.services.ShellManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 虚拟屏幕管理器 (Headless 模式)。
 * 使用 ImageReader 作为 Surface 接收端，配合 Shizuku 创建后台虚拟屏幕。
 */
object VirtualDisplayManager {
    private const val TAG = "VirtualDisplayManager"

    data class VirtualDisplaySession(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val imageReader: ImageReader
    )

    private var currentSession: VirtualDisplaySession? = null

    /** 探测期间抓到的最新一帧，供紧接其后的截图直接复用，避免帧被消费两次导致无帧可用 */
    private var lastFrame: Bitmap? = null
    private val mutex = Mutex()
    private var backgroundHandler: Handler? = null
    private var handlerThread: HandlerThread? = null

    /**
     * 启动一个无头虚拟屏幕会话。
     * @return 成功返回 displayId，失败返回 -1。
     */
    suspend fun startHeadlessSession(width: Int = 1080, height: Int = 2400, densityDpi: Int = 400): Int {
        mutex.withLock {
            // 如果已有会话，先清理
            releaseLocked()

            DebugLogger.i(TAG, "正在创建 ImageReader Surface ($width x $height)...")

            // 1. 初始化后台线程 (用于 ImageReader 回调)
            if (handlerThread == null) {
                handlerThread = HandlerThread("VirtualDisplayReader").apply { start() }
                backgroundHandler = Handler(handlerThread!!.looper)
            }

            // 2. 创建 ImageReader
            // 使用 RGBA_8888，maxImages=2 即可
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // 3. 通过 Shizuku 请求 Shell 创建 VirtualDisplay
            val displayId = ShellManager.createVirtualDisplay(imageReader.surface, width, height, densityDpi)

            if (displayId > 0) {
                DebugLogger.i(TAG, "虚拟屏幕创建成功: ID $displayId")
                currentSession = VirtualDisplaySession(displayId, width, height, imageReader)
                waitForFirstFrameLocked()
                return displayId
            } else {
                DebugLogger.e(TAG, "虚拟屏幕创建失败 (Shell返回 -1)")
                imageReader.close()
                return -1
            }
        }
    }

    private suspend fun waitForFirstFrameLocked(timeoutMs: Long = 1500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val bitmap = captureCurrentFrame()
            if (bitmap != null) {
                bitmap.recycle()
                return
            }
            delay(100)
        }
        DebugLogger.w(TAG, "等待虚拟屏幕首帧超时，后续截图将继续重试")
    }

    /**
     * 释放当前会话。
     */
    suspend fun release() {
        mutex.withLock {
            releaseLocked()
        }
    }

    private suspend fun releaseLocked() {
        currentSession?.let { session ->
            DebugLogger.i(TAG, "正在销毁虚拟屏幕 ID: ${session.displayId}")
            // 1. 通知 Shell 销毁 VirtualDisplay
            ShellManager.destroyVirtualDisplay(session.displayId)
            // 2. 关闭 ImageReader
            try {
                session.imageReader.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        currentSession = null

        lastFrame?.let { if (!it.isRecycled) it.recycle() }
        lastFrame = null

        // 停止后台线程
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
    }

    /**
     * 获取当前的 Display ID。
     */
    fun getCurrentDisplayId(): Int {
        return currentSession?.displayId ?: -1
    }

    fun getCurrentSessionSize(): Pair<Int, Int>? {
        val session = currentSession ?: return null
        return session.width to session.height
    }

    /**
     * 直接从 ImageReader 获取最新一帧的 Bitmap。
     * 这比 'screencap' 命令快得多，且不需要 root。
     */
    fun captureCurrentFrame(): Bitmap? {
        val session = currentSession ?: return null

        // 等待界面稳定期间已经抓过一帧，直接交出去（所有权转移，由调用方负责 recycle）
        lastFrame?.let { cached ->
            lastFrame = null
            if (!cached.isRecycled) return cached
        }

        try {
            // 获取最新图像
            val image = session.imageReader.acquireLatestImage() ?: return null
            return try {
                imageToBitmap(image, session)
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "抓取屏幕帧失败", e)
            return null
        }
    }

    /**
     * 探测「有没有新帧」，并把这一帧缓存下来供随后的 [captureCurrentFrame] 复用。
     *
     * 返回帧时间戳；返回 null 表示此刻没有待消费的帧，也就是界面这段时间没有刷新。
     * ImageReader 是生产者-消费者模型，探到帧就必须消费，不缓存的话后面的截图会拿不到画面，
     * 所以这里顺手把像素转成 Bitmap 留下来。
     */
    fun peekFrameTimestamp(): Long? {
        val session = currentSession ?: return null

        val image = try {
            session.imageReader.acquireLatestImage()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "探测虚拟屏帧失败", e)
            null
        } ?: return null

        return try {
            // 先转成功再替换缓存，中途异常时不能把旧引用指向已 recycle 的 Bitmap
            val bitmap = imageToBitmap(image, session)
            lastFrame?.let { if (!it.isRecycled) it.recycle() }
            lastFrame = bitmap
            image.timestamp
        } catch (e: Exception) {
            DebugLogger.e(TAG, "缓存虚拟屏帧失败", e)
            null
        } finally {
            image.close()
        }
    }

    /** 把 ImageReader 的一帧转成 Bitmap，并按 rowPadding 裁掉右侧填充 */
    private fun imageToBitmap(image: Image, session: VirtualDisplaySession): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * session.width

        // 创建 Bitmap
        val bitmap = Bitmap.createBitmap(
            session.width + rowPadding / pixelStride,
            session.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 如果有 padding，裁剪一下
        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, session.width, session.height)
            bitmap.recycle()
            cropped
        }
    }
}
