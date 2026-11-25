package com.espstudio.ide.term

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 一个真正的 PTY 会话：Kotlin 侧只负责转发字节，ANSI 交给 xterm.js 渲染。
 */
class TerminalSession(
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var fd = -1
    private var pid = -1
    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private var waiter: Thread? = null

    @Volatile
    private var closed = false

    val running: Boolean get() = pid > 0 && !closed

    fun start(cwd: String, rows: Int, cols: Int, env: Array<String>): Boolean {
        if (!Pty.loaded) return false
        return try {
            val res = Pty.start("/system/bin/sh", arrayOf("sh", "-i"), cwd, env, rows, cols)
            val parts = res.split(":")
            fd = parts[0].toInt()
            pid = parts[1].toInt()
            pfd = ParcelFileDescriptor.adoptFd(fd)
            input = FileInputStream(pfd!!.fileDescriptor)
            output = FileOutputStream(pfd!!.fileDescriptor)

            reader = Thread({
                val buf = ByteArray(8192)
                try {
                    while (!closed) {
                        val n = input!!.read(buf)
                        if (n <= 0) break
                        if (n == buf.size) onOutput(buf) else onOutput(buf.copyOf(n))
                    }
                } catch (_: Throwable) {
                }
            }, "pty-reader").also { it.isDaemon = true; it.start() }

            waiter = Thread({
                val code = try {
                    Pty.waitFor(pid)
                } catch (t: Throwable) {
                    -1
                }
                if (!closed) onExit(code)
            }, "pty-waiter").also { it.isDaemon = true; it.start() }

            true
        } catch (t: Throwable) {
            close()
            false
        }
    }

    fun write(data: ByteArray) {
        try {
            output?.write(data)
            output?.flush()
        } catch (_: Throwable) {
        }
    }

    fun write(text: String) = write(text.toByteArray())

    fun resizeCols(cols: Int, rows: Int) {
        if (fd > 0) {
            try {
                Pty.resize(fd, rows, cols)
            } catch (_: Throwable) {
            }
        }
    }

    fun close() {
        closed = true
        if (pid > 0) {
            try {
                Pty.kill(pid)
            } catch (_: Throwable) {
            }
        }
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }
        pfd = null
        input = null
        output = null
        pid = -1
        fd = -1
    }
}
