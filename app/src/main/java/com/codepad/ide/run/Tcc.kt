package com.codepad.ide.run

/**
 * TinyCC 绑定：把 C 源码编译到内存再执行，不需要在设备上落盘生成 ELF。
 */
object Tcc {

    var loaded: Boolean = false
        private set

    init {
        loaded = try {
            System.loadLibrary("tccbridge")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 返回 {"out": "...", "error": "...", "code": 0} */
    external fun compileAndRun(code: String, filename: String, includeDir: String): String
}
