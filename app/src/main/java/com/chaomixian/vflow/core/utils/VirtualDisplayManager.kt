// 文件: main/java/com/chaomixian/vflow/core/utils/VirtualDisplayManager.kt
package com.chaomixian.vflow.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
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

    /**
     * 会话引用计数。
     *
     * 两个 agent 模块（AI 智能体 / AutoGLM）可能先后或同时启动虚拟屏，原来后启动的那个
     * 会直接销毁前一个的会话，等于互相把对方的屏幕踢掉。现在按引用计数共享同一块屏。
     */
    private var refCount = 0

    /** 探测期间抓到的最新一帧，供紧接其后的截图直接复用，避免帧被消费两次导致无帧可用 */
    private var lastFrame: Bitmap? = null
    private val mutex = Mutex()
    private var backgroundHandler: Handler? = null
    private var handlerThread: HandlerThread? = null

    /**
     * 启动一个无头虚拟屏幕会话。
     *
     * 尺寸默认跟随真机主屏：写死 1080x2400 时目标 App 会按一个真机上不存在的尺寸布局，
     * 元素被截断或触发另一套响应式排版，模型看到的是假界面。
     *
     * @return 成功返回 displayId，失败返回 -1。
     */
    suspend fun startHeadlessSession(width: Int = 0, height: Int = 0, densityDpi: Int = 0): Int {
        mutex.withLock {
            // 已有会话就复用并计数，不销毁上一个 agent 的屏幕
            currentSession?.let { session ->
                refCount++
                DebugLogger.i(TAG, "复用虚拟屏幕 ID ${session.displayId}（引用计数 $refCount）")
                return session.displayId
            }

            val (w, h, dpi) = resolveSessionMetrics(width, height, densityDpi)
            DebugLogger.i(TAG, "正在创建 ImageReader Surface ($w x $h @ ${dpi}dpi)...")

            // 1. 初始化后台线程 (用于 ImageReader 回调)
            if (handlerThread == null) {
                handlerThread = HandlerThread("VirtualDisplayReader").apply { start() }
                backgroundHandler = Handler(handlerThread!!.looper)
            }

            // 2. 创建 ImageReader
            // 使用 RGBA_8888，maxImages=2 即可
            val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

            // 3. 通过 Shizuku 请求 Shell 创建 VirtualDisplay
            val displayId = ShellManager.createVirtualDisplay(imageReader.surface, w, h, dpi)

            if (displayId > 0) {
                DebugLogger.i(TAG, "虚拟屏幕创建成功: ID $displayId")
                currentSession = VirtualDisplaySession(displayId, w, h, imageReader)
                refCount = 1
                waitForFirstFrameLocked()
                return displayId
            } else {
                DebugLogger.e(TAG, "虚拟屏幕创建失败 (Shell返回 -1)")
                imageReader.close()
                return -1
            }
        }
    }

    /**
     * 解析会话尺寸与密度。
     *
     * 全部为 0 表示「跟随主屏」；显式传了非 0 值就以传入的为准（留出调试口）。
     * 取不到主屏信息时退回 1080x2400@400，至少能起一块屏。
     */
    @Suppress("DEPRECATION")
    private fun resolveSessionMetrics(width: Int, height: Int, densityDpi: Int): Triple<Int, Int, Int> {
        if (width > 0 && height > 0 && densityDpi > 0) return Triple(width, height, densityDpi)

        val fallback = Triple(1080, 2400, 400)
        val context = runCatching { LogManager.applicationContext }.getOrNull() ?: return fallback
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return fallback

        val metrics = DisplayMetrics()
        val display = runCatching { displayManager.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: return fallback
        display.getRealMetrics(metrics)

        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return fallback
        return Triple(
            metrics.widthPixels,
            metrics.heightPixels,
            if (metrics.densityDpi > 0) metrics.densityDpi else fallback.third
        )
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
     * 释放当前会话。引用计数大于 1 时只减计数，真正归零才销毁屏幕。
     */
    suspend fun release() {
        mutex.withLock {
            if (refCount > 1) {
                refCount--
                DebugLogger.i(TAG, "释放虚拟屏幕引用（剩余 $refCount）")
                return@withLock
            }
            refCount = 0
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
        refCount = 0

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
