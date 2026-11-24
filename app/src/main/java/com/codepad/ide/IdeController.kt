package com.codepad.ide

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.codepad.ide.editor.EditorBridge
import com.codepad.ide.ext.LangSpec
import com.codepad.ide.ext.Languages
import com.codepad.ide.fs.FileNode
import com.codepad.ide.fs.Project
import com.codepad.ide.fs.Projects
import com.codepad.ide.fs.Workspace
import com.codepad.ide.run.CDebugger
import com.codepad.ide.run.CToolchain
import com.codepad.ide.run.DebugBridge
import com.codepad.ide.run.QuickJs
import com.codepad.ide.run.Runner
import com.codepad.ide.term.TerminalSession
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

const val TERMINAL_TAB = "terminal"
const val GIT_TAB = "git"
const val DEBUG_TAB = "debug"

@SuppressLint("SetJavaScriptEnabled")
class IdeController(private val activity: MainActivity) {

    // ---------------------------------------------------------------- 工程
    var project by mutableStateOf<Project?>(null)
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    // ---------------------------------------------------------------- 文件
    var files by mutableStateOf<List<FileNode>>(emptyList())
        private set
    var tabs by mutableStateOf<List<File>>(emptyList())
        private set
    var active by mutableStateOf<File?>(null)
        private set
    var activeLang by mutableStateOf<LangSpec?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------- UI
    var showTerminal by mutableStateOf(true)
    var showSidebar by mutableStateOf(true)

    /** 编辑区是否持有焦点：键盘弹出时只有编辑区需要让位，终端里输入时不能把终端藏起来 */
    var editorFocused by mutableStateOf(false)
    var bottomTab by mutableStateOf(TERMINAL_TAB)
    var terminalHeight by mutableStateOf(260)
    var statusLine by mutableStateOf("就绪")
    var dialog by mutableStateOf<String?>(null)
    var dialogTarget by mutableStateOf<File?>(null)
    var toast by mutableStateOf<String?>(null)

    var pendingExport: File? = null

    var editorView: WebView? = null
    var terminalView: WebView? = null
    private var editorReady = false
    private var terminalReady = false
    private var savedText: String = ""
    private var liveText: String = ""

    private val pendingTerminal = ByteArrayOutputStream()

    val runner = Runner { text -> appendTerminal(text) }

    lateinit var session: TerminalSession
        private set

    val editorBridge = EditorBridge(object : EditorBridge.Host {
        override fun onEditorReady() {
            ui {
                editorReady = true
                sendActiveToEditor()
                ensureWebPreview(active)
                applyEditorSettings()
                statusLine = "编辑器就绪"
            }
        }

        override fun onEditorChange(text: String) {
            ui {
                liveText = text
                dirty = text != savedText
                schedulePreviewPush()
            }
        }

        override fun onSave(text: String) {
            ui { saveBuffer(text) }
        }

        override fun onRun(text: String) {
            ui {
                saveBuffer(text)
                runActive()
            }
        }

        override fun onLog(message: String) {
            android.util.Log.i("CodePadJS", "js: $message")
            ui { statusLine = message }
        }

        override fun onCommand(id: String) {
            ui { handleCommand(id) }
        }

        override fun onRequestIme() {
            ui { showIme() }
        }

        override fun onLspRequest(payload: String) {
            this@IdeController.onLspRequest(payload)
        }

        override fun webPreviewUrl(): String = this@IdeController.webPreviewUrl()

        override fun setPreviewVisible(visible: Boolean) {
            ui { webPreviewVisible = visible }
        }

        override fun openPath(path: String) {
            ui {
                val f = File(path)
                if (f.isFile) open(f)
            }
        }
    })


    // --------------------------------------------------------------- 工程

    fun refreshProjects() {
        projects = Projects.list()
    }

    fun switchProject(p: Project, persist: Boolean = true) {
        if (dirty) saveBuffer(null)
        // 切工程时把还在跑/还在调的东西收掉
        if (debugActive) endDebug("已切换工程")
        lsp?.stop()
        lsp = null
        lspProject = ""
        raLsp?.stop()
        raLsp = null
        runner.stop()
        project = p
        PreviewFiles.setRoot(p.dir)
        if (persist) Projects.setCurrent(p.id)
        expanded = setOf(p.dir.absolutePath)
        tabs = emptyList()
        active = null
        activeLang = null
        dirty = false
        savedText = ""
        liveText = ""
        js("CP.clear()")
        refreshProjects()
        refreshTree()
        // 终端切到新工程目录
        restartTerminal(p.dir)
        statusLine = "工程：${p.name}"
    }

    fun createProject(name: String, template: String) {
        val p = Projects.create(name.ifBlank { "未命名工程" }, template)
        refreshProjects()
        switchProject(p)
        if (template == com.codepad.ide.fs.Templates.RUST) {
            val main = File(p.dir, "src/main.rs")
            if (main.isFile) open(main)
        }
        toast = "已创建工程 ${p.name}"
    }

    fun renameCurrentProject(name: String) {
        val p = project ?: return
        val oldPath = p.dir.absolutePath
        val updated = Projects.renameProject(p, name)
        val newPath = updated.dir.absolutePath
        project = updated
        if (oldPath != newPath) {
            fun remap(f: File): File =
                if (f.absolutePath.startsWith(oldPath)) File(newPath + f.absolutePath.removePrefix(oldPath)) else f

            tabs = tabs.map { remap(it) }
            active = active?.let { remap(it) }
            expanded = expanded.map {
                if (it.startsWith(oldPath)) newPath + it.removePrefix(oldPath) else it
            }.toSet()
            restartTerminal(updated.dir)
        }
        refreshProjects()
        refreshTree()
        toast = "工程已重命名"
    }

    fun deleteCurrentProject() {
        val p = project ?: return
        Projects.delete(p)
        Projects.setCurrent(null)
        expanded = emptySet()
        tabs = emptyList()
        active = null
        refreshProjects()
        val next = projects.firstOrNull() ?: Projects.create("示例工程", com.codepad.ide.fs.Templates.MULTI)
        switchProject(next)
        toast = "工程已删除"
    }

    // --------------------------------------------------------------- 文件树

    fun refreshTree() {
        val p = project ?: return
        files = Workspace.visibleTree(p.dir, expanded)
    }

    fun toggleFolder(node: FileNode) {
        if (!node.isDir) return
        val path = node.file.absolutePath
        expanded = if (expanded.contains(path)) expanded - path else expanded + path
        refreshTree()
    }

    fun expandFolder(file: File) {
        expanded = expanded + file.absolutePath
        refreshTree()
    }

    private fun open(file: File) {
        if (file.isDirectory) return
        if (active == file) return
        if (dirty) saveBuffer(null)
        if (!tabs.contains(file)) tabs = tabs + file
        active = file
        activeLang = Languages.forFile(file)
        ensureWebPreview(file)
        val text = Workspace.readText(file)
        savedText = text
        liveText = text
        dirty = false
        // 展开父目录链
        var parent = file.parentFile
        val root = project?.dir
        if (root != null) {
            var add = expanded
            while (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
                add = add + parent.absolutePath
                parent = parent.parentFile
            }
            if (add != expanded) {
                expanded = add
                refreshTree()
            }
        }
        sendActiveToEditor()
    }

    fun openFile(file: File) = open(file)

    fun closeTab(file: File) {
        // 关掉正在编辑且有改动的标签前先落盘，别把改动丢了
        if (file == active && dirty) saveBuffer(null)
        tabs = tabs.filter { it != file }
        if (active == file) {
            val next = tabs.lastOrNull()
            active = null
            if (next != null) open(next) else {
                activeLang = null
                js("CP.clear()")
            }
        }
    }

    private fun sendActiveToEditor() {
        val f = active ?: return
        val lang = activeLang ?: Languages.forFile(f)
        val text = liveText.takeIf { dirty } ?: savedText
        if (!editorReady) return
        val root = project?.dir ?: f.parentFile
        val payload = JSONObject()
            .put("path", Workspace.relativePath(root, f))
            .put("language", lang.monaco)
            .put("content", text)
            .put("absPath", f.absolutePath)
        js("CP.load($payload)")
        statusLine = "${lang.name} · ${f.name}"
    }

    private fun saveBuffer(text: String?) {
        val f = active ?: return
        val content = text ?: liveText
        try {
            Workspace.writeText(f, content)
            savedText = content
            liveText = content
            dirty = false
            statusLine = "已保存 ${f.name}"
        } catch (t: Throwable) {
            toast = "保存失败: ${t.message}"
        }
    }

    // --------------------------------------------------------------- 节点操作

    fun createNode(parent: File, name: String, isDir: Boolean) {
        if (name.isBlank()) return
        try {
            val root = project?.dir ?: return
            if (!Workspace.isUnder(parent, root)) return
            if (isDir) {
                val d = Workspace.createDir(parent, name)
                expandFolder(d)
            } else {
                val f = Workspace.createFile(parent, name)
                expandFolder(parent)
                refreshTree()
                open(f)
            }
            refreshTree()
            statusLine = "已创建 $name"
        } catch (t: Throwable) {
            toast = "创建失败: ${t.message}"
        }
    }

    fun renameNode(file: File, newName: String) {
        try {
            val renamed = Workspace.rename(file, newName)
            if (renamed == null) {
                toast = "重命名失败：名称重复或非法"
                return
            }
            // 重命名文件夹时，里面的已打开文件也要跟着改路径
            fun remap(f: File): File =
                if (Workspace.isUnder(f, file)) File(renamed, f.relativeTo(file).path) else f

            tabs = tabs.map { remap(it) }
            active = active?.let { remap(it) }
            activeLang = active?.let { Languages.forFile(it) } ?: activeLang
            expanded = expanded.map {
                if (it == file.absolutePath || Workspace.isUnder(File(it), file)) {
                    renamed.absolutePath + it.removePrefix(file.absolutePath)
                } else it
            }.toSet()
            refreshTree()
            statusLine = "已重命名为 ${renamed.name}"
        } catch (t: Throwable) {
            toast = "重命名失败: ${t.message}"
        }
    }

    fun deleteNode(file: File) {
        try {
            Workspace.deleteRecursively(file)
            tabs = tabs.filter { !Workspace.isUnder(it, file) }
            if (active != null && Workspace.isUnder(active!!, file)) {
                active = null
                activeLang = null
                js("CP.clear()")
                dirty = false
                savedText = ""
                liveText = ""
            }
            refreshTree()
            statusLine = "已删除 ${file.name}"
        } catch (t: Throwable) {
            toast = "删除失败: ${t.message}"
        }
    }

    // --------------------------------------------------------------- 移动

    /** 工程内所有文件夹（含根目录），用于"移动到文件夹"选择器 */
    fun allFolders(): List<File> {
        val root = project?.dir ?: return emptyList()
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            out.add(dir)
            if (depth >= 6) return
            Workspace.children(dir).filter { it.isDirectory }.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    fun moveNode(src: File, destDir: File) {
        val root = project?.dir ?: return
        if (!destDir.isDirectory) {
            toast = "目标不是文件夹"
            return
        }
        if (!Workspace.isUnder(src, root) || !Workspace.isUnder(destDir, root)) {
            toast = "只能在当前工程内移动"
            return
        }
        if (Workspace.isUnder(destDir, src)) {
            toast = "不能移动到自身或自己的子文件夹里"
            return
        }
        if (src.parentFile?.absolutePath == destDir.absolutePath) return

        val target = File(destDir, src.name)
        if (target.exists()) {
            toast = "目标文件夹里已有同名项"
            return
        }
        var ok = false
        try {
            ok = src.renameTo(target)
        } catch (_: Throwable) {
        }
        if (!ok) {
            try {
                copyRecursive(src, target)
                Workspace.deleteRecursively(src)
                ok = true
            } catch (t: Throwable) {
                toast = "移动失败: ${t.message}"
                return
            }
        }
        if (!ok) {
            toast = "移动失败"
            return
        }

        fun remap(f: File): File =
            if (Workspace.isUnder(f, src)) File(target, f.relativeTo(src).path) else f

        tabs = tabs.map { remap(it) }
        active = active?.let { remap(it) }
        activeLang = active?.let { Languages.forFile(it) } ?: activeLang
        expanded = expanded.map {
            if (it == src.absolutePath || Workspace.isUnder(File(it), src)) {
                target.absolutePath + it.removePrefix(src.absolutePath)
            } else it
        }.toSet()
        expandFolder(destDir)
        refreshTree()
        toast = "已移动到 " + (if (destDir == root) "工程根目录" else destDir.name)
    }

    private fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = false)
        }
    }

    // --------------------------------------------------------------- 运行

    fun runActive() {
        val f = active ?: return
        val lang = activeLang ?: Languages.forFile(f)
        if (lang.runKind == com.codepad.ide.ext.RunKind.WEB) {
            saveBuffer(null)
            previewHtml(f)
            return
        }
        showTerminal = true
        ensureTerminal()
        runner.run(f, lang, liveText)
    }

    fun stopRun() {
        runner.stop()
        appendTerminal("\u001b[38;5;244m■ 已终止\u001b[0m\r\n")
    }

    // --------------------------------------------------------------- 调试

    var debugActive by mutableStateOf(false)
        private set
    var debugPaused by mutableStateOf(false)
        private set
    var debugReason by mutableStateOf("")
        private set
    var debugLine by mutableStateOf(0)
        private set
    var debugVars by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var debugStack by mutableStateOf<List<String>>(emptyList())
        private set

    fun startDebug() {
        val f = active ?: run {
            toast = "先打开一个文件"
            return
        }
        val debugExt = listOf(".py", ".js", ".c", ".cpp", ".cc", ".cxx", ".rs")
        if (debugExt.none { f.name.endsWith(it) }) {
            toast = "目前支持 Python / JavaScript / C / Rust 调试"
            return
        }
        if (debugActive) {
            toast = "已经在调试中"
            return
        }
        saveBuffer(null)
        DebugBridge.reset()
        showTerminal = true
        bottomTab = DEBUG_TAB
        debugActive = true
        debugPaused = false
        debugReason = "启动中…"
        debugLine = 0
        debugVars = emptyList()
        debugStack = emptyList()
        DebugBridge.listener = { json ->
            ui { onDebugState(json) }
        }

        val path = f.absolutePath
        editorView?.evaluateJavascript("CP.getBreakpoints()") { raw ->
            val bps = try {
                val v = org.json.JSONTokener(raw ?: "[]").nextValue()
                if (v is String) v else "[]"
            } catch (t: Throwable) {
                "[]"
            }
            if (listOf(".c", ".cpp", ".cc", ".cxx", ".rs").any { f.name.endsWith(it) }) {
                startCDebug(f, parseLines(bps), rust = f.name.endsWith(".rs"))
            } else if (f.name.endsWith(".js")) {
                debugKind = "js"
                debugThread = Thread({ runJsDebug(f, bps) }, "debug-js").also { it.start() }
            } else {
                debugKind = "py"
                debugThread = Thread({ runPyDebug(path, bps) }, "debug-python").also { it.start() }
            }
        }
    }

    private fun parseLines(json: String): List<Int> {
        val out = ArrayList<Int>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) out.add(arr.getInt(i))
        } catch (_: Throwable) {
        }
        return out
    }

    private fun startCDebug(file: File, breakpoints: List<Int>, rust: Boolean = false) {
        debugKind = if (rust) "rust" else "c"
        DebugBridge.reset()
        showTerminal = true
        bottomTab = DEBUG_TAB
        debugActive = true
        debugPaused = false
        debugReason = "准备中…"
        debugLine = 0
        debugVars = emptyList()
        debugStack = emptyList()
        DebugBridge.listener = { json -> ui { onDebugState(json) } }
        CDebugger.start(
            file,
            breakpoints,
            rust,
            publish = { json -> DebugBridge.publish(json) },
            log = { msg -> appendTerminal(msg + "\r\n") },
        )
    }
    private fun runPyDebug(path: String, bps: String) {
        var outText = ""
        var code = 0
        try {
            val py = runner.ensurePython()
            val json = py.getModule("debugger").callAttr("debug_file", path, bps).toString()
            val o = JSONObject(json)
            outText = o.optString("out", "")
            code = o.optInt("code", 0)
        } catch (t: Throwable) {
            outText = "调试器启动失败: " + (t.message ?: t.javaClass.simpleName)
            code = 1
        } finally {
            val text = outText
            val exitCode = code
            ui {
                if (text.isNotEmpty()) appendTerminal(text.replace("\n", "\r\n"))
                appendTerminal("\u001b[38;5;244m● 调试结束，退出码 $exitCode\u001b[0m\r\n")
                endDebug("已结束")
            }
        }
    }

    private fun runJsDebug(file: File, bps: String) {
        var outText = ""
        var code = 0
        try {
            val json = QuickJs.evalDebug(Workspace.readText(file), file.name, bps)
            val o = JSONObject(json)
            outText = o.optString("out", "")
            val err = o.optString("error", "")
            if (err.isNotEmpty()) {
                outText = outText + err
                code = 1
            }
        } catch (t: Throwable) {
            outText = "调试器启动失败: " + (t.message ?: t.javaClass.simpleName)
            code = 1
        } finally {
            val text = outText
            val exitCode = code
            ui {
                if (text.isNotEmpty()) appendTerminal(text.replace("\n", "\r\n"))
                appendTerminal("\u001b[38;5;244m● 调试结束，退出码 $exitCode\u001b[0m\r\n")
                endDebug("已结束")
            }
        }
    }
    private var debugThread: Thread? = null

    private var debugKind = ""

    private fun onDebugState(json: String) {
        android.util.Log.i("CodePadDbg", "onDebugState: " + json.take(200))
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return
        }
        when (o.optString("type")) {
            "pause" -> {
                debugPaused = true
                debugReason = when (o.optString("reason")) {
                    "breakpoint" -> "断点"
                    "step" -> "单步进入"
                    "next" -> "单步跳过"
                    "return" -> "跳出"
                    else -> o.optString("reason")
                }
                debugLine = o.optInt("line", 0)
                val vars = ArrayList<Pair<String, String>>()
                o.optJSONArray("vars")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        vars.add(item.optString("name") to item.optString("value"))
                    }
                }
                debugVars = vars
                val stack = ArrayList<String>()
                o.optJSONArray("stack")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val ln = item.optInt("line")
                        stack.add(
                            if (ln > 0) item.optString("func") + " · " + item.optString("file") + ":" + ln
                            else item.optString("func")
                        )
                    }
                }
                debugStack = stack
                if (debugLine > 0) js("CP.setDebugLine($debugLine)")
            }
            "exit" -> {
                debugPaused = false
                if (isNativeDebug()) endDebug("已结束")
            }
        }
    }

    fun debugContinue() {
        if (isNativeDebug()) {
            cCommand("c")
        } else {
            debugSend("continue")
        }
    }

    fun debugStepOver() {
        if (isNativeDebug()) cCommand("s") else debugSend("next")
    }

    fun debugStepInto() {
        if (isNativeDebug()) cCommand("s") else debugSend("step")
    }

    fun debugStepOut() {
        if (isNativeDebug()) cCommand("s") else debugSend("return")
    }

    fun debugStop() {
        if (isNativeDebug()) {
            CDebugger.stop()
        } else {
            DebugBridge.send("stop")
        }
        endDebug("已停止")
    }

    private fun isNativeDebug(): Boolean = debugKind == "c" || debugKind == "rust"

    private fun cCommand(cmd: String) {
        debugPaused = false
        debugReason = "运行中…"
        js("CP.clearDebugLine()")
        CDebugger.command(cmd)
    }

    private fun debugSend(cmd: String) {
        if (!debugActive) return
        debugPaused = false
        debugReason = "运行中…"
        js("CP.clearDebugLine()")
        DebugBridge.send(cmd)
    }

    private fun endDebug(message: String) {
        android.util.Log.i("CodePadDbg", "endDebug: " + message + " kind=" + debugKind)
        if (isNativeDebug()) CDebugger.stop()
        bottomTab = TERMINAL_TAB
        debugActive = false
        debugPaused = false
        debugReason = message
        debugVars = emptyList()
        debugStack = emptyList()
        debugLine = 0
        js("CP.clearDebugLine()")
        DebugBridge.reset()
    }

    // --------------------------------------------------------------- 终端

    // --------------------------------------------------------- 网页实时预览

    var webPreviewVisible by mutableStateOf(false)
    var webPreviewPath by mutableStateOf<String?>(null)

    private fun isWebFile(f: File?): Boolean {
        if (f == null) return false
        return f.extension.lowercase() in setOf("html", "htm", "css", "js", "mjs", "svg")
    }

    private fun resolvePreviewHtml(f: File): File? {
        if (f.extension.lowercase() in setOf("html", "htm")) return f
        var dir: File? = f.parentFile
        var guard = 0
        while (dir != null && guard++ < 8) {
            val idx = File(dir, "index.html")
            if (idx.isFile) return idx
            dir = dir.parentFile
        }
        return null
    }

    /** 生成 iframe 用的 /preview/ URL */
    private fun previewRelUrl(f: File): String? {
        val root = PreviewFiles.dir ?: project?.dir ?: return null
        val html = resolvePreviewHtml(f) ?: return null
        if (!html.absolutePath.startsWith(root.absolutePath)) return null
        val rel = html.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        val enc = rel.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://appassets.androidplatform.net/preview/" + enc
    }

    fun webPreviewUrl(): String {
        val f = active ?: return ""
        if (!isWebFile(f)) return ""
        return previewRelUrl(f) ?: ""
    }

    fun ensureWebPreview(f: File?) {
        if (f == null) return
        if (!isWebFile(f)) {
            js("CP.hidePreview()")
            return
        }
        val html = resolvePreviewHtml(f)
        val url = previewRelUrl(f)
        if (url == null || html == null) {
            js("CP.hidePreview()")
            return
        }
        webPreviewVisible = true
        webPreviewPath = html.absolutePath
        js("CP.setPreview(" + org.json.JSONObject.quote(url) + ", " + org.json.JSONObject.quote(html.name) + ")")
    }

    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var previewPush: Runnable? = null

    /** 编辑网页文件时，把未保存的内容推给预览 iframe */
    private fun schedulePreviewPush() {
        if (!webPreviewVisible) return
        val f = active ?: return
        if (!isWebFile(f)) return
        previewPush?.let { previewHandler.removeCallbacks(it) }
        val r = Runnable {
            val file = active ?: return@Runnable
            if (!isWebFile(file)) return@Runnable
            PreviewFiles.putOverride(file.absolutePath, liveText)
            js("CP.reloadPreview()")
        }
        previewPush = r
        previewHandler.postDelayed(r, 450)
    }

    fun toggleWebPreviewMenu() {
        val f = active
        if (f == null || !isWebFile(f)) {
            toast = "当前文件不是网页（html/css/js）"
            return
        }
        js("CP.togglePreview()")
    }

    fun reloadWebPreview() {
        js("CP.reloadPreview()")
    }

    // ------------------------------------------------------------- HTML 预览

    var htmlPreviewPath by mutableStateOf<String?>(null)

    fun previewHtml(file: File) {
        try {
            val i = android.content.Intent(activity, HtmlPreviewActivity::class.java)
            i.putExtra("path", file.absolutePath)
            activity.startActivity(i)
        } catch (t: Throwable) {
            toast = "预览失败: ${t.message}"
        }
    }

    fun closeHtmlPreview() {
        htmlPreviewPath = null
        closeDialog()
    }

    // ------------------------------------------------------------------ git

    var gitRepo by mutableStateOf(false)
    var gitBranch by mutableStateOf("")
    var gitStatus by mutableStateOf<List<String>>(emptyList())
    var gitDiff by mutableStateOf("")
    var gitCommitMsg by mutableStateOf("")
    var gitBusy by mutableStateOf(false)
    var gitSelected by mutableStateOf<String?>(null)
    var gitLastCommit by mutableStateOf("")
    var gitTab by mutableStateOf("changes")
    var gitHistory by mutableStateOf<List<String>>(emptyList())
    var gitDiscardPath by mutableStateOf<String?>(null)

    fun gitRefresh() {
        val dir = project?.dir ?: return
        gitBusy = true
        Thread({
            try {
                if (!com.codepad.ide.run.CToolchain.ensureGit { m -> ui { appendTerminal(m + "\r\n") } }) {
                    ui { toast = "git 不可用"; gitBusy = false }
                    return@Thread
                }
                val repo = com.codepad.ide.run.GitUtil.hasRepo(dir)
                val st = if (repo) com.codepad.ide.run.GitUtil.status(dir) else emptyList()
                val diff = if (repo) com.codepad.ide.run.GitUtil.diff(dir) else ""
                val br = if (repo) com.codepad.ide.run.GitUtil.branch(dir) else ""
                val last = if (repo) com.codepad.ide.run.GitUtil.lastCommit(dir) else ""
                ui {
                    gitRepo = repo
                    gitStatus = st
                    gitDiff = diff
                    gitBranch = br
                    gitLastCommit = last
                    gitSelected = null
                    gitBusy = false
                }
            } catch (t: Throwable) {
                ui {
                    toast = "git 刷新失败: ${t.message}"
                    gitBusy = false
                }
            }
        }, "git-refresh").start()
    }

    fun gitSelectFile(path: String) {
        val dir = project?.dir ?: return
        gitSelected = path
        Thread({
            try {
                val d = com.codepad.ide.run.GitUtil.diffFor(dir, path)
                ui { gitDiff = d }
            } catch (_: Throwable) {
            }
        }, "git-diff").start()
    }

    fun gitStage(path: String) = gitAction("git-add") {
        com.codepad.ide.run.GitUtil.add(it, path)
    }

    fun gitUnstage(path: String) = gitAction("git-unstage") {
        com.codepad.ide.run.GitUtil.unstage(it, path)
    }

    fun gitStageAll() = gitAction("git-add-all") {
        com.codepad.ide.run.GitUtil.addAll(it)
    }

    fun gitDiscard(path: String) {
        val dir = project?.dir ?: return
        Thread({
            try {
                val tracked = com.codepad.ide.run.GitUtil.status(dir).none { it.drop(3).trim() == path && it.startsWith("??") }
                if (tracked) {
                    com.codepad.ide.run.GitUtil.discard(dir, path)
                } else {
                    com.codepad.ide.fs.Workspace.deleteRecursively(File(dir, path))
                }
                ui {
                    gitDiscardPath = null
                    toast = "已丢弃 $path"
                }
                gitRefresh()
            } catch (t: Throwable) {
                ui { toast = "丢弃失败: ${t.message}" }
            }
        }, "git-discard").start()
    }

    fun gitShowHistory() {
        val dir = project?.dir ?: return
        Thread({
            try {
                val lines = com.codepad.ide.run.GitUtil.logLines(dir)
                ui {
                    gitHistory = lines
                    gitTab = "history"
                }
            } catch (t: Throwable) {
                ui { toast = "读取历史失败: ${t.message}" }
            }
        }, "git-log").start()
    }

    private fun gitAction(name: String, op: (File) -> Unit) {
        val dir = project?.dir ?: return
        Thread({
            try {
                op(dir)
                gitRefresh()
            } catch (t: Throwable) {
                ui { toast = "git 操作失败: ${t.message}" }
            }
        }, name).start()
    }

    fun gitInit() {
        val dir = project?.dir ?: return
        Thread({
            try {
                com.codepad.ide.run.CToolchain.ensureGit { m -> ui { appendTerminal(m + "\r\n") } }
                val out = com.codepad.ide.run.GitUtil.run(dir, listOf("init"))
                ui {
                    appendTerminal(out.replace("\n", "\r\n"))
                    toast = "已初始化 git 仓库"
                }
                gitRefresh()
            } catch (t: Throwable) {
                ui { toast = "git init 失败: ${t.message}" }
            }
        }, "git-init").start()
    }

    fun gitCommit() {
        val dir = project?.dir ?: return
        val msg = gitCommitMsg.trim()
        if (msg.isEmpty()) {
            toast = "先写提交信息"
            return
        }
        Thread({
            try {
                val out = com.codepad.ide.run.GitUtil.run(dir, listOf("commit", "-m", msg))
                ui {
                    appendTerminal(out.replace("\n", "\r\n"))
                    if (out.contains("nothing to commit") || out.contains("no changes added")) {
                        toast = "没有暂存的改动（先点 ＋ 或「全部暂存」）"
                    } else {
                        gitCommitMsg = ""
                        toast = "已提交"
                    }
                }
                gitRefresh()
            } catch (t: Throwable) {
                ui { toast = "提交失败: ${t.message}" }
            }
        }, "git-commit").start()
    }

    // ---------------------------------------------------------------- clangd

    private var lsp: com.codepad.ide.lsp.LspClient? = null
    private var lspProject: String = ""

    private fun ensureLsp(): com.codepad.ide.lsp.LspClient? {
        val p = project ?: return null
        val existing = lsp
        if (existing != null && existing.isRunning() && lspProject == p.dir.absolutePath) return existing
        existing?.stop()
        if (!CToolchain.ensureClangd { msg -> ui { appendTerminal(msg + "\r\n") } }) return null
        val dir = CToolchain.writeCompileCommands(p.dir)
        val client = com.codepad.ide.lsp.LspClient(
            p.dir,
            File(CToolchain.root, "bin/clangd").absolutePath,
            listOf(
                "--background-index=false",
                "--pch-storage=memory",
                "--log=error",
                "--header-insertion=never",
            ),
        )
        if (!client.start(dir) { line -> android.util.Log.i("CodePadLsp", line) }) return null
        lsp = client
        lspProject = p.dir.absolutePath
        android.util.Log.i("CodePadLsp", "clangd started for " + p.dir.name)
        return client
    }

    private var raLsp: com.codepad.ide.lsp.LspClient? = null
    private var raRoot: String = ""

    private fun cargoRootOf(file: File): File? {
        var dir: File? = file.parentFile
        val stop = App.ctx.filesDir.absolutePath
        var guard = 0
        while (dir != null && guard++ < 12) {
            if (File(dir, "Cargo.toml").isFile) return dir
            if (dir.absolutePath == stop) break
            dir = dir.parentFile
        }
        return null
    }

    private fun ensureRaLsp(root: File): com.codepad.ide.lsp.LspClient? {
        val existing = raLsp
        if (existing != null && existing.isRunning() && raRoot == root.absolutePath) return existing
        existing?.stop()
        if (!CToolchain.ensureRustAnalyzer { msg -> ui { appendTerminal(msg + "\r\n") } }) return null
        val client = com.codepad.ide.lsp.LspClient(
            root,
            File(CToolchain.root, "bin/rust-analyzer").absolutePath,
            emptyList(),
            CToolchain.rustAnalyzerEnv(),
        )
        if (!client.start(null) { line -> android.util.Log.i("CodePadRa", line) }) return null
        raLsp = client
        raRoot = root.absolutePath
        android.util.Log.i("CodePadRa", "rust-analyzer started for " + root.name)
        // 预热：rust-analyzer 首次加载工作区要几十秒，先打开当前文件并发一个无害请求
        val f = active
        if (f != null) {
            client.didOpenOrChange(f.absolutePath, "rust", liveText)
            try {
                client.request(
                    "textDocument/documentSymbol",
                    JSONObject().put("textDocument", JSONObject().put("uri", client.uriFor(f.absolutePath))),
                ) { }
            } catch (_: Throwable) {
            }
        }
        return client
    }

    fun onLspRequest(payload: String) {
        val o = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            return
        }
        val id = o.optInt("id")
        val method = o.optString("method")
        val lang = o.optString("lang")
        val params = o.optJSONObject("params") ?: JSONObject()
        Thread({
            val file = active
            val client = if (lang == "rust") {
                val cargoRoot = file?.let { cargoRootOf(it) }
                if (cargoRoot == null) {
                    replyLsp(id, null)
                    return@Thread
                }
                ensureRaLsp(cargoRoot)
            } else {
                ensureLsp()
            }
            if (client == null) {
                replyLsp(id, null)
                return@Thread
            }
            val f = active
            if (f != null) {
                val langId = when {
                    f.name.endsWith(".rs") -> "rust"
                    f.name.endsWith(".c") -> "c"
                    else -> "cpp"
                }
                client.didOpenOrChange(f.absolutePath, langId, liveText)
            }
            client.request(method, params) { resp -> replyLsp(id, resp) }
        }, "lsp-req").start()
    }

    private fun replyLsp(id: Int, resp: JSONObject?) {
        val json = resp?.toString() ?: "null"
        ui {
            editorView?.evaluateJavascript("CP.lspDone($id, " + JSONObject.quote(json) + ")", null)
        }
    }
    // ------------------------------------------------------- 导出 / 导入

    /** 打包当前工程到缓存，然后让用户选保存位置 */
    fun startExport() {
        val p = project ?: return
        Thread({
            try {
                val zip = File(App.ctx.cacheDir, "${p.dir.name}.zip")
                zipDir(p.dir, zip)
                ui {
                    pendingExport = zip
                    activity.launchExport("${p.dir.name}.zip")
                }
            } catch (t: Throwable) {
                ui { toast = "导出失败: ${t.message}" }
            }
        }, "export").start()
    }

    fun finishExport(uri: Uri) {
        val src = pendingExport ?: return
        try {
            App.ctx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            toast = "已导出 ${src.name}"
        } catch (t: Throwable) {
            toast = "写出失败: ${t.message}"
        } finally {
            src.delete()
            pendingExport = null
        }
    }

    fun startImport() {
        activity.launchImport()
    }

    fun finishImport(uri: Uri) {
        Thread({
            try {
                val tmp = File(App.ctx.cacheDir, "import.zip")
                App.ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val display = queryDisplayName(uri)?.removeSuffix(".zip")
                val tmpDir = File(App.ctx.cacheDir, "import-tmp")
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()
                unzipInto(tmp, tmpDir)
                tmp.delete()
                // 优先用包里的工程名
                val metaName = try {
                    JSONObject(File(tmpDir, ".codepad.json").readText()).optString("name")
                } catch (_: Throwable) {
                    ""
                }
                val name = metaName.ifBlank { display ?: "导入工程" }
                val p = com.codepad.ide.fs.Projects.create(name, com.codepad.ide.fs.Templates.EMPTY)
                tmpDir.copyRecursively(p.dir, overwrite = true)
                tmpDir.deleteRecursively()
                ui {
                    refreshProjects()
                    switchProject(p)
                    toast = "已导入 ${p.name}"
                }
            } catch (t: Throwable) {
                ui { toast = "导入失败: ${t.message}" }
            }
        }, "import").start()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        App.ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun zipDir(dir: File, out: File) {
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
            val root = dir.toPath()
            dir.walkTopDown().forEach { f ->
                val rel = root.relativize(f.toPath()).toString().replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                if (rel.startsWith("target/") || rel.contains("/target/")) return@forEach
                if (f.isDirectory) {
                    zos.putNextEntry(java.util.zip.ZipEntry("$rel/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(java.util.zip.ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun unzipInto(zip: File, dir: File) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!name.contains("..") && !name.startsWith("/")) {
                    val target = File(dir, name)
                    if (e.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }
    /** CMake 多目标：选择一个默认运行的可执行文件 */
    fun chooseRunTarget() {
        if (CToolchain.lastCmakeTargets.isEmpty()) {
            toast = "先运行一次 CMake 工程，再选目标"
            return
        }
        openDialog("runTarget", null)
    }

    fun setRunTarget(name: String) {
        val f = active
        val root = if (f != null) CToolchain.cmakeRootOf(f) else null
        val key = root?.absolutePath ?: project?.dir?.absolutePath ?: return
        CToolchain.setPreferredTarget(key, name)
        toast = "运行目标：$name"
    }

    fun ensureTerminal() {
        if (::session.isInitialized && session.running) return
        session = newSession()
        loadSettings()
        if (terminalReady) startSession()
    }

    private fun newSession(): TerminalSession = TerminalSession(
        onOutput = { bytes -> appendTerminalBytes(bytes) },
        onExit = { code ->
            ui {
                appendTerminal("\r\n\u001b[38;5;244m[shell exited: $code]\u001b[0m\r\n")
            }
        }
    )

    /** 生成终端初始化脚本：彩色提示符、别名、欢迎横幅 */
    private fun shellInitFile(): File {
        val dir = File(App.ctx.filesDir, "terminal")
        dir.mkdirs()
        val f = File(dir, "init.sh")
        val script = """
            |# CodePad shell init (auto-generated)
            |__esc=${'$'}(printf '\033')
            |
            |__cp_path() {
            |  __p="${'$'}PWD"
            |  case "${'$'}__p" in
            |    /data/data/com.codepad.ide/files*) __p="${'$'}{__p#/data/data/com.codepad.ide/files}" ;;
            |    /data/user/0/com.codepad.ide/files*) __p="${'$'}{__p#/data/user/0/com.codepad.ide/files}" ;;
            |  esac
            |  case "${'$'}__p" in
            |    "") printf '~' ;;
            |    *) printf '~%s' "${'$'}__p" ;;
            |  esac
            |}
            |
            |__cp_git() {
            |  [ -f .git/HEAD ] || return
            |  __h=${'$'}(cat .git/HEAD 2>/dev/null)
            |  case "${'$'}__h" in
            |    ref:*) printf ' %s' "${'$'}{__h#ref: refs/heads/}" ;;
            |    *) printf ' %s' "${'$'}(printf '%s' "${'$'}__h" | cut -c1-7)" ;;
            |  esac
            |}
            |
            |PS1="${'$'}{__esc}[38;5;39m╭─${'$'}{__esc}[0m ${'$'}{__esc}[38;5;244m\${'$'}(date +%H:%M)${'$'}{__esc}[0m ${'$'}{__esc}[38;5;81m${'$'}{CODEPAD_PROJECT}${'$'}{__esc}[0m ${'$'}{__esc}[38;5;250m\${'$'}(__cp_path)${'$'}{__esc}[0m${'$'}{__esc}[38;5;36m\${'$'}(__cp_git)${'$'}{__esc}[0m
            |${'$'}{__esc}[38;5;41m╰─❯${'$'}{__esc}[0m "
            |
            |alias ls='ls --color=auto'
            |alias ll='ls -l'
            |alias la='ls -la'
            |alias cls='clear'
            |alias ..='cd ..'
            |alias ...='cd ../..'
            |
        """.trimMargin()
        f.writeText(script)
        return f
    }
    private fun prepareCargo() {
        try {
            if (com.codepad.ide.run.CToolchain.isRustReady()) com.codepad.ide.run.CToolchain.cargoHome()
        } catch (_: Throwable) {
        }
    }

    private fun startSession() {
        prepareCargo()
        val cwd = project?.dir ?: App.ctx.filesDir
        val env = arrayOf(
            "HOME=" + cwd.absolutePath,
            "PATH=" + App.ctx.filesDir.absolutePath + "/bin:" + com.codepad.ide.run.CToolchain.root.absolutePath + "/bin:/system/bin:/system/xbin",
            "CARGO_HOME=" + java.io.File(App.ctx.filesDir, "cargo-home").apply { mkdirs() }.absolutePath,
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TMPDIR=" + App.ctx.cacheDir.absolutePath,
            "ENV=" + shellInitFile().absolutePath,
            "CODEPAD_PROJECT=" + (project?.name ?: "CodePad"),
        )
        val ok = session.start(cwd.absolutePath, 24, 80, env)
        if (!ok) {
            appendTerminal("\u001b[31mPTY 启动失败 (libptybridge.so)\u001b[0m\r\n")
        }
    }

    fun onTerminalReady() {
        terminalReady = true
        applyTerminalSettings()
        ui {
            val pending = pendingTerminal.toByteArray()
            pendingTerminal.reset()
            if (pending.isNotEmpty()) {
                termJs("CP.term.write('" + Base64.encodeToString(pending, Base64.NO_WRAP) + "')")
            }
            ensureTerminal()
        }
    }

    fun appendTerminal(text: String) {
        appendTerminalBytes(text.toByteArray(Charsets.UTF_8))
    }

    private fun appendTerminalBytes(bytes: ByteArray) {
        ui {
            if (!terminalReady) {
                pendingTerminal.write(bytes)
                return@ui
            }
            termJs("CP.term.write('" + Base64.encodeToString(bytes, Base64.NO_WRAP) + "')")
        }
    }

    fun terminalInput(bytes: ByteArray) {
        session.write(bytes)
    }

    fun terminalResize(cols: Int, rows: Int) {
        if (cols < 10 || rows < 2) return
        if (::session.isInitialized && session.running) session.resizeCols(cols, rows)
    }

    fun clearTerminal() = termJs("CP.term.clear()")

    fun restartTerminal(cwd: java.io.File? = null) {
        try {
            session.close()
        } catch (_: Throwable) {
        }
        session = newSession()
        clearTerminal()
        appendTerminal("\r\n")
        if (terminalReady) {
            terminalWorkingDir = cwd
            startSessionIn(cwd)
        }
    }

    private var terminalWorkingDir: File? = null

    private fun startSessionIn(cwd: File?) {
        val dir = cwd ?: terminalWorkingDir ?: project?.dir ?: App.ctx.filesDir
        terminalWorkingDir = dir
        val env = arrayOf(
            "HOME=" + dir.absolutePath,
            "PATH=" + App.ctx.filesDir.absolutePath + "/bin:" + com.codepad.ide.run.CToolchain.root.absolutePath + "/bin:/system/bin:/system/xbin",
            "CARGO_HOME=" + java.io.File(App.ctx.filesDir, "cargo-home").apply { mkdirs() }.absolutePath,
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TMPDIR=" + App.ctx.cacheDir.absolutePath,
            "ENV=" + shellInitFile().absolutePath,
            "CODEPAD_PROJECT=" + (project?.name ?: "CodePad"),
        )
        session.start(dir.absolutePath, 24, 80, env)
    }

    // --------------------------------------------------------------- 编辑器

    // ---------------------------------------------------------------- 设置

    private val settingsPrefs
        get() = App.ctx.getSharedPreferences("codepad-settings", android.content.Context.MODE_PRIVATE)

    var editorFontSize by mutableStateOf(13)
    var editorTabSize by mutableStateOf(4)
    var editorWordWrap by mutableStateOf(false)
    var terminalFontSize by mutableStateOf(12.5f)
    var settingsOpen by mutableStateOf(false)

    private fun loadSettings() {
        val sp = settingsPrefs
        editorFontSize = sp.getInt("editorFont", 13)
        editorTabSize = sp.getInt("tabSize", 4)
        editorWordWrap = sp.getBoolean("wordWrap", false)
        terminalFontSize = sp.getFloat("terminalFont", 12.5f)
    }

    fun applyEditorSettings() {
        js(
            "CP.applySettings({fontSize:" + editorFontSize +
                ",tabSize:" + editorTabSize +
                ",wordWrap:" + editorWordWrap + "})"
        )
    }

    fun applyTerminalSettings() {
        termJs("CP.setFontSize(" + terminalFontSize + ")")
    }

    fun changeEditorFontSize(v: Int) {
        editorFontSize = v.coerceIn(9, 28)
        settingsPrefs.edit().putInt("editorFont", editorFontSize).apply()
        applyEditorSettings()
    }

    fun changeTabSize(v: Int) {
        editorTabSize = v.coerceIn(1, 8)
        settingsPrefs.edit().putInt("tabSize", editorTabSize).apply()
        applyEditorSettings()
    }

    fun changeWordWrap(v: Boolean) {
        editorWordWrap = v
        settingsPrefs.edit().putBoolean("wordWrap", v).apply()
        applyEditorSettings()
    }

    fun changeTerminalFontSize(v: Float) {
        terminalFontSize = v.coerceIn(9f, 26f)
        settingsPrefs.edit().putFloat("terminalFont", terminalFontSize).apply()
        applyTerminalSettings()
    }

    // ------------------------------------------------------------ 全局搜索

    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchCase by mutableStateOf(false)
    var searchBusy by mutableStateOf(false)
    var searchResults by mutableStateOf<List<com.codepad.ide.fs.SearchService.Hit>>(emptyList())
    var searchSummary by mutableStateOf("")

    fun toggleSearch() {
        searchOpen = !searchOpen
        if (searchOpen) showSidebar = true
    }

    fun runSearch() {
        val root = project?.dir ?: return
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            searchResults = emptyList()
            searchSummary = ""
            return
        }
        searchBusy = true
        Thread({
            val hits = try {
                com.codepad.ide.fs.SearchService.search(root, q, searchCase)
            } catch (t: Throwable) {
                emptyList()
            }
            ui {
                searchResults = hits
                searchSummary = when {
                    hits.isEmpty() -> "没有匹配"
                    hits.size >= 300 -> "300+ 处（已截断）"
                    else -> "${hits.size} 处匹配"
                }
                searchBusy = false
            }
        }, "search").start()
    }

    fun openHit(h: com.codepad.ide.fs.SearchService.Hit) {
        val f = File(h.path)
        if (!f.isFile) {
            toast = "文件不存在了"
            return
        }
        open(f)
        val line = h.line
        val col = h.col
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            js("CP.gotoLine($line,$col)")
        }, 220)
    }

    fun showIme() {
        try {
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            editorView?.let { imm.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        } catch (_: Throwable) {
        }
    }

    fun js(code: String) {
        editorView?.evaluateJavascript(code, null)
    }

    fun termJs(code: String) {
        terminalView?.evaluateJavascript(code, null)
    }

    fun ui(block: () -> Unit) {
        activity.runOnUiThread {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }

    // --------------------------------------------------------------- 命令

    private fun handleCommand(id: String) {
        when (id) {
            "save" -> js("CP.requestSave()")
            "toggleBreakpoint" -> js("CP.toggleBreakpointAtCursor()")
            "run" -> js("CP.requestRun()")
            "newProject" -> openDialog("newProject")
            "newFile" -> openDialog("newFile", project?.dir)
            "newFolder" -> openDialog("newFolder", project?.dir)
            "rename" -> active?.let { openDialog("renameNode", it) }
            "delete" -> active?.let { openDialog("deleteNode", it) }
            "renameProject" -> openDialog("renameProject")
            "deleteProject" -> openDialog("deleteProject")
            "toggleTerminal" -> showTerminal = !showTerminal
            "toggleSidebar" -> showSidebar = !showSidebar
            "reloadExtensions" -> reloadExtensions()
            "about" -> openDialog("about")
        }
    }

    fun openDialog(name: String, target: File? = null) {
        dialogTarget = target
        dialog = name
    }

    fun reloadExtensions() {
        Languages.reload()
        refreshTree()
        toast = "扩展已重新加载"
    }

    fun closeDialog() {
        dialog = null
        dialogTarget = null
    }

    fun handleBack(): Boolean {
        if (dialog != null) {
            closeDialog()
            return true
        }
        if (showTerminal) {
            showTerminal = false
            return true
        }
        return false
    }

    // 放到最后：确保所有 Compose 状态属性都已初始化，避免 init 里访问到 null 委托
    init {
        session = newSession()
        val p = Projects.bootstrap()
        switchProject(p, persist = false)
    }
}