package com.espstudio.ide.term

/**
 * JNI 桥：原生层负责 forkpty + execve，提供真正的 TTY，
 * 这样 vim / top / python 交互式程序才能在终端里正常工作。
 */
object Pty {

    var loaded: Boolean = false
        private set

    init {
        loaded = try {
            System.loadLibrary("ptybridge")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 返回 "fd:pid" */
    external fun start(
        program: String,
        args: Array<String>,
        cwd: String,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): String

    external fun resize(fd: Int, rows: Int, cols: Int)

    external fun waitFor(pid: Int): Int

    external fun kill(pid: Int)
}
