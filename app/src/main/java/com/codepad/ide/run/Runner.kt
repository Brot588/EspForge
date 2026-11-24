package com.codepad.ide.run

import android.util.Log
import com.codepad.ide.App
import com.codepad.ide.ext.LangSpec
import com.codepad.ide.ext.RunKind
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行器：按语言把源码交给对应的运行时，输出直接汇入底部终端。
 */
class Runner(private val sink: (String) -> Unit) {

    private val cyan = "\u001b[38;5;39m"
    private val green = "\u001b[38;5;41m"
    private val red = "\u001b[38;5;203m"
    private val dim = "\u001b[38;5;244m"
    private val reset = "\u001b[0m"

    @Volatile
    private var shellProcess: Process? = null

    val canStop: Boolean get() = shellProcess?.isAlive == true

    fun stop() {
        try {
            shellProcess?.destroyForcibly()
        } catch (_: Throwable) {
        }
        shellProcess = null
    }

    fun run(file: File, lang: LangSpec, content: String) {
        sink("$cyan▶ ${lang.name} · ${file.name}$reset\r\n")
        sink("$dim${file.absolutePath}$reset\r\n")

        when (lang.runKind) {
            RunKind.PYTHON -> runPython(file)
            RunKind.JAVASCRIPT -> runJs(content, file)
            RunKind.C -> runC(content, file)
            RunKind.CPP -> runCpp(file)
            RunKind.RUST -> runRust(file)
            RunKind.SHELL -> runShell("sh " + shq(file.absolutePath), file.parentFile, file)
            RunKind.COMMAND -> {
                val template = lang.command
                if (template.isNullOrBlank()) {
                    unavailable(lang)
                } else {
                    val cmd = template
                        .replace("\$FILE", shq(file.absolutePath))
                        .replace("\$DIR", shq(file.parentFile.absolutePath))
                        .replace("\$NAME", file.nameWithoutExtension)
                    runShell(cmd, file.parentFile, file)
                }
            }
            RunKind.WEB -> sink("${dim}HTML 请在编辑器里点 ▶ 预览$reset\r\n")
            RunKind.NONE -> unavailable(lang)
        }
    }

    fun runShellCommand(cmd: String, cwd: File) {
        runShell(cmd, cwd, null)
    }

    // ---------------------------------------------------------------- Python

    private val pythonStarted = AtomicBoolean(false)

    fun ensurePython(): Python {
        if (!pythonStarted.get()) {
            synchronized(pythonStarted) {
                if (!pythonStarted.get()) {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(App.ctx))
                    }
                    pythonStarted.set(true)
                }
            }
        }
        return Python.getInstance()
    }

    private fun runPython(file: File) {
        Thread({
            val start = System.currentTimeMillis()
            try {
                val py = ensurePython()
                val json = py.getModule("runner").callAttr("run_file", file.absolutePath).toString()
                val o = JSONObject(json)
                val out = o.optString("out", "")
                if (out.isNotEmpty()) sink(out.replace("\n", "\r\n"))
                val code = o.optInt("code", 0)
                finish(code, System.currentTimeMillis() - start)
            } catch (t: Throwable) {
                Log.w("CodePad", "python failed", t)
                sink("$red${t.javaClass.simpleName}: ${t.message ?: ""}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            }
        }, "run-python").start()
    }

    // ------------------------------------------------------------ JavaScript

    private fun runJs(content: String, file: File) {
        Thread({
            val start = System.currentTimeMillis()
            try {
                if (!QuickJs.loaded) {
                    sink("${red}JavaScript 运行时未加载 (libquickjsbridge.so)$reset\r\n")
                    finish(1, 0)
                    return@Thread
                }
                val json = QuickJs.eval(content, file.name)
                val o = JSONObject(json)
                val out = o.optString("out", "")
                if (out.isNotEmpty()) sink(out.replace("\n", "\r\n"))
                val err = o.optString("error", "")
                if (err.isNotEmpty()) {
                    sink("$red$err$reset\r\n")
                    finish(1, System.currentTimeMillis() - start)
                } else {
                    finish(0, System.currentTimeMillis() - start)
                }
            } catch (t: Throwable) {
                sink("$red${t.message ?: t.javaClass.simpleName}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            }
        }, "run-js").start()
    }

    // ------------------------------------------------------------------- C

    private fun runC(content: String, file: File) {
        val cmakeRoot = CToolchain.cmakeRootOf(file)
        if (cmakeRoot != null) {
            runCmakeProject(file, cmakeRoot)
            return
        }
        Thread({
            val start = System.currentTimeMillis()
            try {
                if (!Tcc.loaded) {
                    sink("${red}C 运行时未加载 (libtccbridge.so)。$reset\r\n")
                    sink("${dim}提示：终端里可用扩展安装 tcc 后，用外置编译。$reset\r\n")
                    finish(1, 0)
                    return@Thread
                }
                val json = Tcc.compileAndRun(content, file.name, App.tccIncludeDir().absolutePath)
                val o = JSONObject(json)
                val out = o.optString("out", "")
                if (out.isNotEmpty()) sink(out.replace("\n", "\r\n"))
                val err = o.optString("error", "")
                if (err.isNotEmpty()) sink("$red$err$reset\r\n")
                finish(o.optInt("code", 0), System.currentTimeMillis() - start)
            } catch (t: Throwable) {
                sink("$red${t.message ?: t.javaClass.simpleName}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            }
        }, "run-c").start()
    }

    /** 有 CMakeLists.txt 的工程：cmake 配置 + 构建，然后运行产物 */
    private fun runCmakeProject(file: File, root: File) {
        Thread({
            val start = System.currentTimeMillis()
            try {
                sink("${dim}CMake 工程：${root.name}$reset\r\n")
                val exe = CToolchain.buildCmake(root) { line -> sink(line + "\r\n") }
                if (exe == null) {
                    finish(1, System.currentTimeMillis() - start)
                    return@Thread
                }
                sink("${dim}运行 ${exe.name}$reset\r\n")
                val rc = streamProcess(listOf(exe.absolutePath), root, CToolchain.cmakeEnv())
                finish(rc, System.currentTimeMillis() - start)
            } catch (t: Throwable) {
                sink("$red${t.message ?: t.javaClass.simpleName}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            } finally {
                shellProcess = null
            }
        }, "run-cmake").start()
    }
    private fun runCpp(file: File) {
        val cmakeRoot = CToolchain.cmakeRootOf(file)
        if (cmakeRoot != null) {
            runCmakeProject(file, cmakeRoot)
            return
        }
        Thread({
            val start = System.currentTimeMillis()
            try {
                if (!CToolchain.isReady() && !CToolchain.ensure { m -> sink("$dim$m$reset\r\n") }) {
                    finish(1, System.currentTimeMillis() - start)
                    return@Thread
                }
                val buildDir = File(App.ctx.filesDir, "build").apply { mkdirs() }
                val exe = File(buildDir, file.nameWithoutExtension + ".cpp.bin")
                sink("${dim}clang++ 编译 ${file.name} …$reset\r\n")
                val (ok, out) = CToolchain.compileCpp(file, exe)
                if (out.isNotBlank()) sink(out.replace("\n", "\r\n"))
                if (!ok || !exe.isFile) {
                    finish(1, System.currentTimeMillis() - start)
                    return@Thread
                }
                exe.setExecutable(true, false)
                val rc = streamProcess(listOf(exe.absolutePath), file.parentFile, CToolchain.cmakeEnv())
                finish(rc, System.currentTimeMillis() - start)
            } catch (t: Throwable) {
                sink("$red${t.message ?: t.javaClass.simpleName}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            } finally {
                shellProcess = null
            }
        }, "run-cpp").start()
    }
    // ----------------------------------------------------------------- Rust

    private fun cargoProjectRoot(file: File): File? {
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

    /** 跑一个进程并把输出实时灌进终端，返回退出码 */
    private fun streamProcess(cmd: List<String>, cwd: File?, env: Map<String, String>): Int {
        val pb = ProcessBuilder(cmd)
        if (cwd != null) pb.directory(cwd)
        pb.redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        shellProcess = p
        p.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> sink(line + "\r\n") }
        }
        return p.waitFor()
    }

    private fun runRust(file: File) {
        Thread({
            val start = System.currentTimeMillis()
            try {
                if (!CToolchain.isReady()) {
                    sink("${dim}准备 C 工具链（Rust 链接复用 clang/lld）…$reset\r\n")
                    if (!CToolchain.ensure { msg -> sink("$dim$msg$reset\r\n") }) {
                        finish(1, System.currentTimeMillis() - start)
                        return@Thread
                    }
                }
                if (!CToolchain.isRustReady()) {
                    if (!CToolchain.ensureRust { msg -> sink("$dim$msg$reset\r\n") }) {
                        finish(1, System.currentTimeMillis() - start)
                        return@Thread
                    }
                }
                val env = CToolchain.toolEnv()

                // 有 Cargo.toml 就走 cargo，否则单文件 rustc
                val cargoRoot = cargoProjectRoot(file)
                if (cargoRoot != null) {
                    sink("${dim}cargo run · ${cargoRoot.name}$reset\r\n")
                    val rc = streamProcess(
                        listOf(File(CToolchain.root, "bin/cargo").absolutePath, "run", "--quiet"),
                        cargoRoot,
                        env,
                    )
                    finish(rc, System.currentTimeMillis() - start)
                    return@Thread
                }

                val buildDir = File(App.ctx.filesDir, "build").apply { mkdirs() }
                val exe = File(buildDir, file.nameWithoutExtension + ".rs.bin")
                buildDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(exe.name + ".")) f.deleteRecursively()
                }
                file.parentFile?.listFiles()?.forEach { f ->
                    if (f.name.startsWith(file.nameWithoutExtension + "_rs")) f.deleteRecursively()
                }
                sink("${dim}rustc 编译 ${file.name} …$reset\r\n")
                val code = streamProcess(
                    listOf(
                        File(CToolchain.root, "bin/rustc").absolutePath,
                        "--edition", "2021",
                        "-C", "linker=" + CToolchain.rustLinkerScript().absolutePath,
                        "-o", exe.absolutePath,
                        file.absolutePath,
                    ),
                    file.parentFile,
                    env,
                )
                if (code != 0 || !exe.isFile) {
                    sink("${red}编译失败（退出码 $code）$reset\r\n")
                    finish(1, System.currentTimeMillis() - start)
                    return@Thread
                }
                exe.setExecutable(true, false)
                val rc = streamProcess(listOf(exe.absolutePath), file.parentFile, env)
                finish(rc, System.currentTimeMillis() - start)
            } catch (t: Throwable) {
                sink("$red${t.message ?: t.javaClass.simpleName}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            } finally {
                shellProcess = null
            }
        }, "run-rust").start()
    }

    // --------------------------------------------------------------- Shell

    private fun runShell(cmd: String, cwd: File, file: File?) {
        Thread({
            val start = System.currentTimeMillis()
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                pb.directory(cwd)
                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["HOME"] = App.ctx.filesDir.absolutePath
                env["PATH"] = "${App.ctx.filesDir.absolutePath}/bin:/system/bin:/system/xbin"
                env["TMPDIR"] = App.ctx.cacheDir.absolutePath
                env["TERM"] = "xterm-256color"
                env["LANG"] = "C.UTF-8"
                val p = pb.start()
                shellProcess = p
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> sink(line + "\r\n") }
                }
                val code = p.waitFor()
                finish(code, System.currentTimeMillis() - start)
            } catch (t: Throwable) {
                sink("$red${t.message ?: t.javaClass.simpleName}$reset\r\n")
                finish(1, System.currentTimeMillis() - start)
            } finally {
                shellProcess = null
            }
        }, "run-shell").start()
    }

    // -------------------------------------------------------------- helpers

    private fun finish(code: Int, ms: Long) {
        val color = if (code == 0) green else red
        sink("$color● 进程结束，退出码 $code$reset$dim  (${ms}ms)$reset\r\n")
    }

    private fun unavailable(lang: LangSpec) {
        sink("$red${lang.name} 暂无可用的内置运行时。$reset\r\n")
        when (lang.id) {

            else -> sink("${dim}可以为该语言编写扩展 manifest，指定 run 命令。$reset\r\n")
        }
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}