package com.espforge.ide

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.espforge.ide.editor.EditorBridge
import com.espforge.ide.ext.LangSpec
import com.espforge.ide.ext.Languages
import com.espforge.ide.fs.FileNode
import com.espforge.ide.fs.Project
import com.espforge.ide.fs.Projects
import com.espforge.ide.fs.Workspace
import com.espforge.ide.run.Toolchain
import com.espforge.ide.term.TerminalSession
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

const val TERMINAL_TAB = "terminal"
const val BUILD_TAB = "build"
const val FLASH_TAB = "flash"
const val SERIAL_TAB = "serial"
const val DEBUG_TAB = "debug"
const val GIT_TAB = "git"

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

    /**
     * 底部面板（终端/串口）里的 WebView 是否拿到了焦点。
     * 为 true 且键盘弹出时，UI 会把整个底部面板抬到键盘上方 ——
     * 编辑器/搜索框获得焦点时不动，保持「键盘直接盖住、不挤压编辑区」的约定。
     */
    var dockImeFocused by mutableStateOf(false)
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

    lateinit var session: TerminalSession
        private set

    val editorBridge = EditorBridge(object : EditorBridge.Host {
        override fun onEditorReady() {
            ui {
                editorReady = true
                sendActiveToEditor()
                applyEditorSettings()
                statusLine = "编辑器就绪"
            }
        }

        override fun onEditorChange(text: String) {
            ui {
                liveText = text
                dirty = text != savedText
            }
        }

        override fun onSave(text: String) {
            ui { saveBuffer(text) }
        }

        override fun onRun(text: String) {
            ui {
                saveBuffer(text)
                build()
            }
        }

        override fun onLog(message: String) {
            android.util.Log.i("ESPStudioJS", "js: $message")
            ui { statusLine = message }
        }

        override fun onCommand(id: String) {
            ui { handleCommand(id) }
        }

        override fun onRequestIme() {
            ui { showIme() }
        }

        override fun onLspRequest(payload: String) {
            // clangd 已移除：ESP-IDF 工程不需要语言服务器
        }

        override fun webPreviewUrl(): String = ""

        override fun openPathAt(path: String, line: Int, col: Int) {
            ui {
                val f = File(path)
                if (!f.isFile) {
                    toast = "定义所在文件打不开: " + path
                    return@ui
                }
                open(f)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    js("CP.gotoLine($line,$col)")
                }, 260)
            }
        }

        override fun status(message: String) {
            ui { statusLine = message }
        }

        override fun setPreviewVisible(visible: Boolean) {
            // 网页预览已移除
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
        project = p
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
        val main = File(p.dir, "main/main.c")
        if (main.isFile) open(main)
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
        val next = projects.firstOrNull() ?: Projects.create("blink", com.espforge.ide.fs.Templates.BLINK)
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

    // ----------------------------------------------------------- ESP32 构建

    var chip by mutableStateOf("esp32")
        private set
    var building by mutableStateOf(false)
        private set
    var buildLog by mutableStateOf("")
        private set
    var buildOk by mutableStateOf<Boolean?>(null)
        private set
    var buildInfo by mutableStateOf("就绪")
        private set
    var packReady by mutableStateOf(false)
        private set
    var packStatus by mutableStateOf("")
        private set
    var packBusy by mutableStateOf(false)
        private set
    private var buildThread: Thread? = null
    private val buildBuf = StringBuilder()

    val chips = listOf("esp32", "esp32s3")

    fun chipLabel(id: String = chip): String = when (id) {
        "esp32" -> "ESP32"
        "esp32s3" -> "ESP32-S3"
        "esp32s2" -> "ESP32-S2"
        "esp32c3" -> "ESP32-C3"
        "esp32c6" -> "ESP32-C6"
        else -> id.uppercase()
    }

    fun refreshPack() {
        Toolchain.pruneOldMarkers()
        packReady = Toolchain.isReady()
        packStatus = if (!packReady) {
            val miss = Toolchain.missing()
            if (miss.isEmpty()) "资源包不完整" else "缺少：" + miss.joinToString("、")
        } else {
            Toolchain.packSummary()
        }
        // 资源包已经解压但还没整理好（上次中途失败、或 tar 侧载的包没有 .ok 标记）
        // → 后台补一次 finalizePack：权限、glibc 垫片、6 项内容修补、.ok 标记
        if (!packReady && File(Toolchain.root, "manifest.json").isFile) {
            Thread({
                val ok = Toolchain.finalizePack { msg -> ui { packStatus = msg } }
                ui {
                    packReady = ok && Toolchain.isReady()
                    if (packReady) {
                        packStatus = Toolchain.packSummary()
                        appendBuild("资源包已整理就绪")
                    }
                }
            }, "pack-finalize").start()
        }
    }

    fun importPack(uri: Uri) {
        if (packBusy) return
        packBusy = true
        packStatus = "准备导入…"
        Thread({
            val ok = Toolchain.importPack(uri) { msg -> ui { packStatus = msg } }
            ui {
                packBusy = false
                refreshPack()
                toast = if (ok) "资源包已安装" else packStatus
            }
        }, "pack-import").start()
    }

    /** 拉起系统文件选择器导入资源包 */
    fun pickPack() {
        activity.launchPackImport()
    }

    /**
     * 侧载路径：/sdcard/Android/data/<pkg>/files/pack.zip 存在时直接导入，
     * 不用走文件选择器（adb push 之后可以在界面里点这个）。
     */
    fun externalPackFile(): File? {
        val dir = activity.getExternalFilesDir(null) ?: return null
        val f = File(dir, "pack.zip")
        return if (f.isFile) f else null
    }

    fun importSideLoadedPack() {
        val f = externalPackFile()
        if (f == null) {
            toast = "没找到 " + (activity.getExternalFilesDir(null)?.absolutePath ?: "外部目录") + "/pack.zip"
            return
        }
        if (packBusy) return
        packBusy = true
        packStatus = "准备导入…"
        Thread({
            val ok = Toolchain.importPack(f) { msg -> ui { packStatus = msg } }
            ui {
                packBusy = false
                refreshPack()
                toast = if (ok) "资源包已安装" else packStatus
            }
        }, "pack-import").start()
    }

    fun chooseChip(id: String) {
        chip = id
        toast = "目标芯片：" + chipLabel(id)
    }

    private fun appendBuild(line: String) {
        buildBuf.append(line).append('\n')
        if (buildBuf.length > 500_000) buildBuf.delete(0, buildBuf.length - 400_000)
        buildLog = buildBuf.toString()
    }

    /** 运行 idf.py build；输出实时进「构建」面板 */
    fun build() {
        if (building) return
        val p = project ?: run {
            toast = "先新建一个工程"
            return
        }
        saveBuffer(null)
        building = true
        buildOk = null
        buildBuf.setLength(0)
        buildLog = ""
        buildInfo = "构建中…"
        bottomTab = BUILD_TAB
        showTerminal = true
        val started = System.currentTimeMillis()
        buildThread = Thread({
            // 资源包没整理好就先补一遍，别让用户看到「还没装资源包」的假提示
            var ready = Toolchain.isReady()
            if (!ready) {
                ui { packStatus = "整理资源包…" }
                ready = Toolchain.finalizePack { msg -> ui { packStatus = msg } }
                ui {
                    packReady = ready && Toolchain.isReady()
                    if (packReady) packStatus = Toolchain.packSummary()
                }
            }
            if (!ready) {
                ui {
                    building = false
                    buildOk = false
                    buildInfo = "资源包不完整"
                    appendBuild("── 资源包不完整：$packStatus ──")
                    toast = "资源包不完整，先导入"
                    openDialog("pack")
                }
            } else {
                // 先确认这台设备的 App 进程能不能跑 glibc 工具链（seccomp 可能直接杀进程）
                val blocked = Toolchain.appProcessBlockedReason()
                if (blocked != null) {
                    ui {
                        building = false
                        buildOk = false
                        buildInfo = "本设备不支持 App 内编译"
                        statusLine = buildInfo
                        appendBuild("── 本设备不支持 App 内编译 ──")
                        blocked.lines().forEach { appendBuild(it) }
                        toast = "App 内编译被系统禁止，原因见构建面板"
                    }
                } else {
                    Toolchain.ensureIdfPythonEnv()
                    val code = Toolchain.runStreaming(
                        listOf(
                            Toolchain.pythonExe().absolutePath,
                            File(Toolchain.idfPath, "tools/idf.py").absolutePath,
                            "build",
                        ),
                        p.dir,
                        Toolchain.env(chip),
                    ) { line -> ui { appendBuild(line) } }
                    val secs = (System.currentTimeMillis() - started) / 1000
                    ui {
                        building = false
                        buildOk = code == 0
                        buildInfo = if (code == 0) "构建成功 · ${secs}s" else "构建失败（$code）· ${secs}s"
                        statusLine = buildInfo
                        appendBuild(if (code == 0) "── 构建成功 ──" else "── 构建失败 ──")
                    }
                }
            }
        }, "esp-build").also { it.start() }
    }

    fun stopBuild() {
        Toolchain.cancel()
        building = false
        buildOk = null
        buildInfo = "已中断"
        appendBuild("── 已中断 ──")
    }

    fun cleanBuild() {
        if (building) return
        val p = project ?: return
        buildBuf.setLength(0)
        val code = Toolchain.runStreaming(
            listOf(
                Toolchain.pythonExe().absolutePath,
                File(Toolchain.idfPath, "tools/idf.py").absolutePath,
                "fullclean",
            ),
            p.dir,
            Toolchain.env(chip),
        ) { line -> appendBuild(line) }
        buildOk = null
        buildInfo = if (code == 0) "已清理构建目录" else "清理失败（$code）"
    }

    /** 工具链自检：把 python / cmake / gcc 的版本号打出来 */
    fun runSelfCheck() {
        bottomTab = BUILD_TAB
        showTerminal = true
        buildBuf.setLength(0)
        appendBuild("检查工具链…")
        Thread({
            val rows = try {
                Toolchain.selfCheck()
            } catch (t: Throwable) {
                listOf("error" to (t.message ?: "?"))
            }
            ui { rows.forEach { (k, v) -> appendBuild("$k: ${v.ifBlank { "(无输出)" }}") } }
        }, "selfcheck").start()
    }

    // --------------------------------------------------------------- 调试

    var debugActive by mutableStateOf(false)
        private set
    var debugPaused by mutableStateOf(false)
        private set
    var debugReason by mutableStateOf("")
    var debugPrep by mutableStateOf("")
        private set
    var debugLine by mutableStateOf(0)
        private set
    var debugVars by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var debugStack by mutableStateOf<List<String>>(emptyList())
        private set

    fun startDebug() {
        toast = "调试器还没接上（下一步做串口 gdbstub）"
    }

    fun debugContinue() {}
    fun debugStepOver() {}
    fun debugStepInto() {}
    fun debugStepOut() {}
    fun debugStop() {}
    fun endDebug(message: String) {
        debugActive = false
        debugPaused = false
        debugReason = message
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
                if (!Toolchain.ensureGit { m -> ui { appendTerminal(m + "\r\n") } }) {
                    ui { toast = "git 不可用"; gitBusy = false }
                    return@Thread
                }
                val repo = com.espforge.ide.run.GitUtil.hasRepo(dir)
                val st = if (repo) com.espforge.ide.run.GitUtil.status(dir) else emptyList()
                val diff = if (repo) com.espforge.ide.run.GitUtil.diff(dir) else ""
                val br = if (repo) com.espforge.ide.run.GitUtil.branch(dir) else ""
                val last = if (repo) com.espforge.ide.run.GitUtil.lastCommit(dir) else ""
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
                val d = com.espforge.ide.run.GitUtil.diffFor(dir, path)
                ui { gitDiff = d }
            } catch (_: Throwable) {
            }
        }, "git-diff").start()
    }

    fun gitStage(path: String) = gitAction("git-add") {
        com.espforge.ide.run.GitUtil.add(it, path)
    }

    fun gitUnstage(path: String) = gitAction("git-unstage") {
        com.espforge.ide.run.GitUtil.unstage(it, path)
    }

    fun gitStageAll() = gitAction("git-add-all") {
        com.espforge.ide.run.GitUtil.addAll(it)
    }

    fun gitDiscard(path: String) {
        val dir = project?.dir ?: return
        Thread({
            try {
                val tracked = com.espforge.ide.run.GitUtil.status(dir).none { it.drop(3).trim() == path && it.startsWith("??") }
                if (tracked) {
                    com.espforge.ide.run.GitUtil.discard(dir, path)
                } else {
                    com.espforge.ide.fs.Workspace.deleteRecursively(File(dir, path))
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
                val lines = com.espforge.ide.run.GitUtil.logLines(dir)
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
                Toolchain.ensureGit { m -> ui { appendTerminal(m + "\r\n") } }
                val out = com.espforge.ide.run.GitUtil.run(dir, listOf("init"))
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
                val out = com.espforge.ide.run.GitUtil.run(dir, listOf("commit", "-m", msg))
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
                val p = com.espforge.ide.fs.Projects.create(name, com.espforge.ide.fs.Templates.EMPTY)
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
    // ------------------------------------------------------------ 串口 / 烧录

    var serialPorts by mutableStateOf<List<com.espforge.ide.serial.UsbSerial.PortInfo>>(emptyList())
        private set
    var serialOpen by mutableStateOf(false)
        private set
    var serialLabel by mutableStateOf("")
        private set
    var serialBaud by mutableStateOf(115200)
    var serialInfo by mutableStateOf("未连接")
    var flashing by mutableStateOf(false)
        private set
    var flashInfo by mutableStateOf("未烧录")
        private set
    var flashLog by mutableStateOf("")
        private set
    var flashProgress by mutableStateOf(0)
        private set

    private var conn: com.espforge.ide.serial.SerialConnection? = null
    private var serialThread: Thread? = null
    @Volatile
    private var serialStop = false
    var serialView: WebView? = null
    var serialReady = false
    private val flashBuf = StringBuilder()

    fun appendFlash(line: String) {
        flashBuf.append(line).append('\n')
        if (flashBuf.length > 200_000) flashBuf.delete(0, flashBuf.length - 150_000)
        flashLog = flashBuf.toString()
    }

    /** 清空烧录日志 */
    fun clearFlashLog() {
        flashBuf.setLength(0)
        flashLog = ""
    }

    /** 枚举当前插着的 USB 串口 */
    fun scanPorts() {
        serialPorts = com.espforge.ide.serial.UsbSerial.list(activity)
        statusLine = if (serialPorts.isEmpty()) "没找到 USB 串口" else "找到 ${serialPorts.size} 个串口"
    }

    fun connectSerial(index: Int = 0) {
        if (serialOpen) {
            toast = "串口已经打开了"
            return
        }
        val list = com.espforge.ide.serial.UsbSerial.list(activity)
        serialPorts = list
        if (list.isEmpty()) {
            toast = "没有检测到 USB 设备：检查 OTG 线、板子供电、串口芯片型号"
            return
        }
        val info = list.getOrNull(index) ?: list.first()
        com.espforge.ide.serial.UsbSerial.requestPermission(activity, info.device) { granted ->
            ui {
                if (!granted) {
                    toast = "USB 权限被拒绝"
                    return@ui
                }
                val c = com.espforge.ide.serial.SerialConnection(activity, info)
                if (!c.open(serialBaud)) {
                    toast = "打开串口失败（设备可能被别的 App 占用）"
                    return@ui
                }
                conn = c
                serialOpen = true
                serialLabel = c.name
                serialInfo = c.name + " · " + serialBaud + " bps"
                bottomTab = SERIAL_TAB
                showTerminal = true
                serialJs("CP.term.clear()")
                startSerialReader()
                statusLine = "串口已连接：" + c.name
            }
        }
    }

    fun disconnectSerial() {
        stopSerialReader()
        try {
            conn?.close()
        } catch (_: Throwable) {
        }
        conn = null
        serialOpen = false
        serialInfo = "未连接"
        statusLine = "串口已断开"
    }

    private fun startSerialReader() {
        val c = conn ?: return
        serialStop = false
        serialThread = Thread({
            while (!serialStop && c.isOpen) {
                val data = c.read(80)
                if (data.isNotEmpty()) {
                    val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                    serialJs("CP.term.write('$b64')")
                }
            }
        }, "serial-reader").also { it.start() }
    }

    private fun stopSerialReader() {
        serialStop = true
        try {
            serialThread?.join(600)
        } catch (_: Throwable) {
        }
        serialThread = null
    }

    /** 串口面板里的键盘输入 → 设备 */
    fun serialInput(bytes: ByteArray) {
        conn?.write(bytes)
    }

    fun serialReset() {
        val c = conn ?: return
        toast = "复位芯片"
        try {
            c.hardReset()
        } catch (_: Throwable) {
        }
    }

    fun serialToBootloader() {
        val c = conn ?: return
        toast = "进入下载模式"
        try {
            c.resetIntoBootloader()
        } catch (_: Throwable) {
        }
    }

    fun changeSerialBaud(baud: Int) {
        serialBaud = baud
        if (conn?.setBaud(baud) == true) {
            serialInfo = (conn?.name ?: "") + " · " + baud + " bps"
        }
    }

    fun serialJs(code: String) {
        try {
            if (serialReady) serialView?.evaluateJavascript(code, null)
        } catch (_: Throwable) {
        }
    }

    /**
     * 烧录：读 build/flasher_args.json，按里面的偏移写进 flash。
     * 等价于 `idf.py -p <port> flash`，只是串口走 UsbManager。
     */
    fun flash() {
        if (flashing) return
        val p = project ?: run {
            toast = "先新建一个工程"
            return
        }
        if (!serialOpen) {
            toast = "先连上 USB 串口"
            connectSerial()
            bottomTab = SERIAL_TAB
            return
        }
        val c = conn ?: return
        val images: List<com.espforge.ide.serial.EspFlasher.Image>
        try {
            images = com.espforge.ide.serial.EspFlasher(c).parseFlasherArgs(p.dir).first
        } catch (t: Throwable) {
            toast = t.message ?: "读取烧录参数失败"
            bottomTab = BUILD_TAB
            return
        }
        if (images.isEmpty()) {
            toast = "没有可烧录的固件"
            return
        }
        flashing = true
        flashProgress = 0
        flashInfo = "烧录中…"
        flashBuf.setLength(0)
        flashLog = ""
        bottomTab = FLASH_TAB
        showTerminal = true
        stopSerialReader()
        val wasOpen = serialOpen
        Thread({
            val flasher = com.espforge.ide.serial.EspFlasher(c) { line -> ui { appendFlash(line) } }
            try {
                flasher.flashAll(images, baud = serialBaud, fastBaud = 460800) { name, done, total ->
                    ui {
                        flashInfo = "烧录 $name  $done / $total"
                        flashProgress = if (total > 0) done * 100 / total else 0
                    }
                }
                ui {
                    flashing = false
                    flashProgress = 100
                    flashInfo = "烧录完成，已复位"
                    statusLine = "烧录完成"
                    toast = "烧录完成"
                }
            } catch (t: Throwable) {
                ui {
                    flashing = false
                    flashInfo = "烧录失败"
                    statusLine = "烧录失败"
                    appendFlash("! " + (t.message ?: "未知错误"))
                    toast = "烧录失败：" + (t.message ?: "")
                }
            } finally {
                ui { if (wasOpen && serialOpen) startSerialReader() }
            }
        }, "esp-flash").start()
    }

    fun ensureTerminal() {
        android.util.Log.i("EspForge", "ensureTerminal enter")
        android.util.Log.i("EspForge", "ensureTerminal: initialized=" + ::session.isInitialized + " running=" + (if (::session.isInitialized) session.running else false))
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
            |    /data/data/com.espforge.ide/files*) __p="${'$'}{__p#/data/data/com.espforge.ide/files}" ;;
            |    /data/user/0/com.espforge.ide/files*) __p="${'$'}{__p#/data/user/0/com.espforge.ide/files}" ;;
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
            |PS1="${'$'}{__esc}[38;5;41m❯${'$'}{__esc}[0m ${'$'}{__esc}[38;5;250m\${'$'}(__cp_path)${'$'}{__esc}[0m${'$'}{__esc}[38;5;36m\${'$'}(__cp_git)${'$'}{__esc}[0m "
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
    private fun startSession() {
        android.util.Log.i("EspForge", "startSession: cwd=" + (project?.dir?.absolutePath ?: "?"))
        val cwd = project?.dir ?: App.ctx.filesDir
        val env = arrayOf(
            "HOME=" + cwd.absolutePath,
            "PATH=" + Toolchain.env()["PATH"] ?: "/system/bin:/system/xbin",
            "CARGO_HOME=" + java.io.File(App.ctx.filesDir, "cargo-home").apply { mkdirs() }.absolutePath,
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TMPDIR=" + App.ctx.cacheDir.absolutePath,
            "ENV=" + shellInitFile().absolutePath,
            "CODEPAD_PROJECT=" + (project?.name ?: "CodePad"),
        )
        val ok = session.start(cwd.absolutePath, 24, 80, env)
        android.util.Log.i("EspForge", "startSession: ok=$ok ${session.info} cwd=" + cwd.absolutePath)
        if (!ok) {
            appendTerminal(
                "\u001b[31mPTY 启动失败\u001b[0m（libptybridge.so 没加载：\u001b[38;5;244m" +
                    (if (com.espforge.ide.term.Pty.loaded) "库已加载，forkpty/exec 失败" else "找不到 libptybridge.so") +
                    "\u001b[0m）\r\n"
            )
        }
    }

    fun onTerminalReady() {
        android.util.Log.i("EspForge", "onTerminalReady: enter")
        // 这个回调来自 WebView 的 JavaBridge 线程。evaluateJavascript 只能在主线程调用，
        // 之前在这里直接调 applyTerminalSettings() 会抛 "WebView method called on wrong
        // thread"，整段中断 —— PTY 永远没启动，终端黑屏。所有 WebView 交互都放主线程。
        ui {
            terminalReady = true
            android.util.Log.i("EspForge", "onTerminalReady: on ui thread, starting pty")
            try {
                applyTerminalSettings()
            } catch (t: Throwable) {
                android.util.Log.w("EspForge", "applyTerminalSettings failed: " + t.message)
            }
            val pending = pendingTerminal.toByteArray()
            pendingTerminal.reset()
            if (pending.isNotEmpty()) {
                termJs("CP.term.write('" + Base64.encodeToString(pending, Base64.NO_WRAP) + "')")
            }
            ensureTerminal()
        }
    }

    fun appendTerminal(text: String) {
        android.util.Log.i("EspForge", "appendTerminal len=" + text.length)
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
            "PATH=" + (Toolchain.env()["PATH"] ?: "/system/bin:/system/xbin"),
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

    /** 从 PackageManager 读真实版本号，避免硬编码落后 */
    val appVersion: String by lazy {
        try {
            val pi = App.ctx.packageManager.getPackageInfo(App.ctx.packageName, 0)
            pi.versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
    }

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
    var searchResults by mutableStateOf<List<com.espforge.ide.fs.SearchService.Hit>>(emptyList())
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
                com.espforge.ide.fs.SearchService.search(root, q, searchCase)
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

    fun openHit(h: com.espforge.ide.fs.SearchService.Hit) {
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
        refreshPack()
    }
}