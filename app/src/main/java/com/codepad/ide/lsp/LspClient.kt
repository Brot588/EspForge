package com.codepad.ide.lsp

import com.codepad.ide.run.CToolchain
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 极简 LSP 客户端：只实现 clangd 需要的 initialize / didOpen / didChange / completion / hover / definition。
 * 走 stdio，Content-Length 帧。
 */
class LspClient(
    private val projectRoot: File,
    private val exePath: String,
    private val extraArgs: List<String> = emptyList(),
    private val envExtra: Map<String, String> = emptyMap(),
) {

    private var process: Process? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null
    private val pending = ConcurrentHashMap<Int, (JSONObject?) -> Unit>()
    private val nextId = AtomicInteger(1)
    private val writeLock = Any()
    private val opened = HashSet<String>()
    private var version = 1

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running && process?.isAlive == true

    fun start(compileCommandsDir: File?, log: (String) -> Unit = {}): Boolean {
        if (isRunning()) return true
        val exe = File(exePath)
        if (!exe.isFile) {
            log("LSP 服务不存在: $exePath")
            return false
        }
        return try {
            val cmd = mutableListOf(exe.absolutePath)
            cmd.addAll(extraArgs)
            if (compileCommandsDir != null) {
                cmd.add("--compile-commands-dir=" + compileCommandsDir.absolutePath)
            }
            val pb = ProcessBuilder(cmd)
            pb.directory(projectRoot)
            pb.environment().putAll(CToolchain.toolEnv())
            pb.environment().putAll(envExtra)
            val p = pb.start()
            process = p
            out = p.outputStream
            running = true
            reader = Thread({ readLoop(p) }, "clangd-read").also {
                it.isDaemon = true
                it.start()
            }
            Thread({
                try {
                    p.errorStream.bufferedReader().forEachLine { line -> log("clangd: $line") }
                } catch (_: Throwable) {
                }
            }, "clangd-err").also { it.isDaemon = true; it.start() }

            request(
                "initialize",
                JSONObject()
                    .put("processId", android.os.Process.myPid())
                    .put("rootUri", uriOf(projectRoot.absolutePath))
                    .put("capabilities", JSONObject().put("textDocument", JSONObject())),
            ) { }
            notify("initialized", JSONObject())
            true
        } catch (t: Throwable) {
            log("clangd 启动失败: ${t.message}")
            running = false
            false
        }
    }

    fun uriFor(path: String): String = uriOf(path)

    private fun uriOf(path: String): String {
        // 中文/空格路径必须百分号编码，否则 rust-analyzer 认不出文件
        return try {
            "file://" + java.net.URI(null, null, path, null).rawPath
        } catch (_: Throwable) {
            "file://" + path
        }
    }

    private fun readLoop(p: Process) {
        try {
            val input = p.inputStream
            while (running) {
                var length = -1
                // 读头部
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) {
                        length = line.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                }
                if (length <= 0) continue
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val msg = JSONObject(String(body, Charsets.UTF_8))
                if (msg.has("id") && msg.has("method")) {
                    // 服务端发来的请求（workspace/configuration 等），一律回 null
                    send(
                        JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", msg.get("id"))
                            .put("result", JSONObject.NULL)
                    )
                } else if (msg.has("id")) {
                    val id = msg.optInt("id", -1)
                    val res = msg.optJSONObject("result")
                    if (res == null && !msg.optBoolean("__ok", false)) {
                        android.util.Log.i("CodePadLsp", "resp without result: " + msg.toString().take(240))
                    }
                    pending.remove(id)?.invoke(res)
                }
            }
        } catch (_: Throwable) {
        } finally {
            running = false
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun send(obj: JSONObject) {
        val body = obj.toString().toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            try {
                val o = out ?: return
                o.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                o.write(body)
                o.flush()
            } catch (_: Throwable) {
            }
        }
    }

    fun request(method: String, params: JSONObject, cb: (JSONObject?) -> Unit): Int {
        val id = nextId.getAndIncrement()
        pending[id] = cb
        send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
        )
        return id
    }

    fun notify(method: String, params: JSONObject) {
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
    }

    fun didOpenOrChange(path: String, languageId: String, text: String) {
        val uri = uriOf(path)
        if (!opened.contains(uri)) {
            opened.add(uri)
            notify(
                "textDocument/didOpen",
                JSONObject().put(
                    "textDocument",
                    JSONObject()
                        .put("uri", uri)
                        .put("languageId", languageId)
                        .put("version", 1)
                        .put("text", text)
                )
            )
        } else {
            version++
            notify(
                "textDocument/didChange",
                JSONObject()
                    .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                    .put(
                        "contentChanges",
                        org.json.JSONArray().put(JSONObject().put("text", text))
                    )
            )
        }
    }

    fun stop() {
        running = false
        try {
            request("shutdown", JSONObject()) { }
            notify("exit", JSONObject())
        } catch (_: Throwable) {
        }
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        out = null
        pending.clear()
        opened.clear()
    }
}
