package com.dsh.scanner

import android.app.Application
import com.dsh.scanner.core.ScannerCore

/**
 * 宿主 Application：尽早初始化 OpenCV native 库。
 * 在 Application 而不是 Activity 里初始化，可以避免首个 Scanner
 * 构造时的 native 加载延迟尖峰（ScannerCore.init 幂等，漏调也有兜底）。
 */
class ScannerApplication : Application() {

    /** OpenCV 是否初始化成功；失败时 Activity 会提示用户。 */
    var openCvReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        openCvReady = ScannerCore.init()
    }
}
