package com.dsh.scanner.core

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * module 级初始化入口：初始化 OpenCV native 库。
 *
 * 宿主应在自定义 `Application.onCreate` 中调用一次：
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ScannerCore.init()
 *     }
 * }
 * ```
 *
 * 即使宿主漏调，[Scanner] 构造时也会兜底执行（幂等、开销极小），
 * 但仍建议显式初始化：越早加载 native 库，首帧分析越不会出现延迟尖峰。
 */
object ScannerCore {

    @Volatile
    private var initialized = false

    /**
     * 初始化 OpenCV native 库；幂等，可安全重复调用。
     * @return false 表示初始化失败，宿主应提示用户而不是继续分析。
     */
    fun init(): Boolean {
        if (initialized) return true
        return try {
            initialized = OpenCVLoader.initLocal()
            if (!initialized) {
                Log.e(TAG, "OpenCV initLocal() returned false")
            }
            initialized
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init failed", t)
            false
        }
    }

    /** 是否已完成初始化。 */
    fun isInitialized(): Boolean = initialized

    private const val TAG = "PaperScan"
}
