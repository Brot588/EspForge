package com.codepad.ide.run

import android.util.Base64

/**
 * QuickJS 绑定：一个体积很小、启动极快、支持 ES2020 的 JS 引擎。
 */
object QuickJs {

    var loaded: Boolean = false
        private set

    init {
        loaded = try {
            System.loadLibrary("quickjsbridge")
            true
        } catch (t: Throwable) {
            false
        }
    }

    external fun nativeCreate(): Long

    external fun nativeEval(handle: Long, code: String, filename: String): String

    external fun nativeDestroy(handle: Long)

    external fun nativeDebugStart(file: String, breakpoints: String)

    external fun nativeDebugStop()

    /** 带断点执行：暂停/继续由 C 侧通过 DebugBridge 与 UI 交互 */
    fun evalDebug(code: String, filename: String, breakpoints: String): String {
        nativeDebugStart(filename, breakpoints)
        val h = nativeCreate()
        return try {
            nativeEval(h, code, filename)
        } finally {
            try {
                nativeDestroy(h)
            } catch (_: Throwable) {
            }
            nativeDebugStop()
        }
    }

    fun eval(code: String, filename: String): String {
        val h = nativeCreate()
        return try {
            nativeEval(h, code, filename)
        } finally {
            try {
                nativeDestroy(h)
            } catch (_: Throwable) {
            }
        }
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
