package com.codepad.ide.editor

import android.util.Base64
import android.webkit.JavascriptInterface

class EditorBridge(private val host: Host) {

    interface Host {
        fun onEditorReady()
        fun onEditorChange(text: String)
        fun onSave(text: String)
        fun onRun(text: String)
        fun onLog(message: String)
        fun onCommand(id: String)
        fun onRequestIme()
        fun onLspRequest(payload: String)
        fun openPath(path: String)
        fun webPreviewUrl(): String
        fun setPreviewVisible(visible: Boolean)
        fun openPathAt(path: String, line: Int, col: Int)
        fun status(message: String)
    }

    @JavascriptInterface
    fun ready() = host.onEditorReady()

    @JavascriptInterface
    fun change(text: String) = host.onEditorChange(text)

    @JavascriptInterface
    fun save(text: String) = host.onSave(text)

    @JavascriptInterface
    fun run(text: String) = host.onRun(text)

    @JavascriptInterface
    fun log(message: String) = host.onLog(message)

    @JavascriptInterface
    fun command(id: String) = host.onCommand(id)

    @JavascriptInterface
    fun requestIme() = host.onRequestIme()

    @JavascriptInterface
    fun lspRequest(payload: String) = host.onLspRequest(payload)

    @JavascriptInterface
    fun openPath(path: String) = host.openPath(path)

    @JavascriptInterface
    fun webPreviewUrl(): String = host.webPreviewUrl()

    @JavascriptInterface
    fun setPreviewVisible(visible: Boolean) = host.setPreviewVisible(visible)

    @JavascriptInterface
    fun openPathAt(path: String, line: Int, col: Int) = host.openPathAt(path, line, col)

    @JavascriptInterface
    fun status(message: String) = host.status(message)
}

class TerminalBridge(
    private val onInput: (ByteArray) -> Unit,
    private val onReady: () -> Unit,
    private val onResize: (Int, Int) -> Unit,
) {

    @JavascriptInterface
    fun input(base64: String) {
        onInput(Base64.decode(base64, Base64.DEFAULT))
    }

    @JavascriptInterface
    fun ready() = onReady()

    @JavascriptInterface
    fun resize(cols: Int, rows: Int) = onResize(cols, rows)
}
