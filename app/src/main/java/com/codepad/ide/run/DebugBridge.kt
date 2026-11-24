package com.codepad.ide.run

import java.util.concurrent.LinkedBlockingQueue

/**
 * Python 调试器与 UI 之间的桥：Python 侧 publish 状态、awaitCommand 阻塞等待指令。
 */
object DebugBridge {

    private val commands = LinkedBlockingQueue<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    @JvmStatic
    fun publish(state: String) {
        android.util.Log.i("CodePadDbg", "publish: " + state.take(200) + " listener=" + (listener != null))
        listener?.invoke(state)
    }

    @JvmStatic
    fun awaitCommand(): String {
        return try {
            commands.take()
        } catch (t: Throwable) {
            "stop"
        }
    }

    fun send(command: String) {
        commands.offer(command)
    }

    fun reset() {
        commands.clear()
        listener = null
    }
}
