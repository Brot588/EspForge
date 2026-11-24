package com.codepad.ide.run

import com.codepad.ide.App
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 内置 clang 工具链：首次使用时把 assets/tc.zip 解压到 files/tc。
 *
 * 目录结构：
 *   bin/clang, bin/ld.lld, bin/gdbserver, bin/llvm-dwarfdump
 *   lib/ 下的 .so
 *   lib/clang/21/...        clang 资源目录
 *   sysroot/usr/...         bionic 头文件和 crt
 */
object CToolchain {

    private const val VERSION = "1"

    val root: File get() = File(App.ctx.filesDir, "tc")

    private val marker: File get() = File(root, ".ok-$VERSION")

    fun isReady(): Boolean = marker.isFile && File(root, "bin/clang").isFile

    private val rustMarker: File get() = File(root, ".ok-rust-2")

    fun isRustReady(): Boolean = rustMarker.isFile && File(root, "bin/rustc").isFile

    /** Rust 工具链单独打包（rust.zip），解压到同一个前缀里，复用 clang/lld/sysroot */
    @Synchronized
    fun ensureRust(onStatus: (String) -> Unit = {}): Boolean {
        if (!isReady() && !ensure(onStatus)) return false
        if (isRustReady()) return true
        onStatus("解压 Rust 工具链（约 210MB）…")
        return try {
            App.ctx.assets.open("rust.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!name.contains("..")) {
                            val out = File(root, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            listOf("bin/rustc", "bin/cargo").forEach { File(root, it).setExecutable(true, false) }
            rustMarker.writeText("ok")
            onStatus("Rust 工具链就绪")
            true
        } catch (t: Throwable) {
            onStatus("Rust 工具链解压失败: ${t.message}")
            false
        }
    }

    @Synchronized
    fun ensure(onStatus: (String) -> Unit = {}): Boolean {
        if (isReady()) return true
        root.parentFile?.mkdirs()
        val tmp = File(App.ctx.filesDir, "tc-extract")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        onStatus("解压 C 工具链（约 230MB）…")
        try {
            App.ctx.assets.open("tc.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.contains("..")) {
                            entry = zip.nextEntry
                            continue
                        }
                        val out = File(tmp, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            listOf("clang", "ld.lld", "gdbserver", "llvm-dwarfdump", "llvm-symbolizer").forEach {
                File(tmp, "bin/$it").setExecutable(true, false)
            }
            if (root.exists()) root.deleteRecursively()
            val ok = tmp.renameTo(root)
            if (!ok) {
                tmp.copyRecursively(root, overwrite = true)
                tmp.deleteRecursively()
            }
            File(root, ".ok-$VERSION").writeText("ok")
            onStatus("工具链就绪")
            return true
        } catch (t: Throwable) {
            onStatus("工具链解压失败: ${t.message}")
            return false
        }
    }

    /** rustc 调链接器时不会带 --target，用包装脚本固定 target/sysroot */
    fun rustLinkerScript(): File {
        val f = File(root, "bin/rust-link.sh")
        f.writeText(
            "#!/system/bin/sh\n" +
                "exec " + File(root, "bin/clang").absolutePath +
                " --target=aarch64-linux-android26 --sysroot=" + sysroot() +
                " -fuse-ld=lld \"\$@\"\n"
        )
        f.setExecutable(true, false)
        return f
    }

    /** cargo 的 CARGO_HOME，并写入 Android 目标的链接器配置 */
    fun cargoHome(): File {
        val home = File(App.ctx.filesDir, "cargo-home")
        home.mkdirs()
        val cfg = File(home, "config.toml")
        val want = """
            [target.aarch64-linux-android]
            linker = "${rustLinkerScript().absolutePath}"

            [build]
            target = "aarch64-linux-android"
        """.trimIndent() + "\n"
        if (!cfg.isFile || cfg.readText() != want) cfg.writeText(want)
        return home
    }

    /** rustc/cargo/gdbserver 需要的环境变量 */
    fun toolEnv(): MutableMap<String, String> = mutableMapOf(
        "LD_LIBRARY_PATH" to libPath(),
        "PATH" to File(root, "bin").absolutePath + ":/system/bin:/system/xbin",
        "CARGO_HOME" to cargoHome().absolutePath,
        "HOME" to App.ctx.filesDir.absolutePath,
        "TMPDIR" to App.ctx.cacheDir.absolutePath,
    )

    // ------------------------------------------------------------------ git

    private val gitMarker: File get() = File(root, ".ok-git-1")

    fun isGitReady(): Boolean = gitMarker.isFile && File(root, "bin/git").isFile

    @Synchronized
    fun ensureGit(onStatus: (String) -> Unit = {}): Boolean {
        if (!isReady() && !ensure(onStatus)) return false
        if (isGitReady()) return true
        onStatus("解压 git（约 13MB）…")
        return try {
            App.ctx.assets.open("git.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!name.contains("..")) {
                            val out = File(root, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            listOf("bin/git", "bin/less").forEach { File(root, it).setExecutable(true, false) }
            File(root, "share/git-core/templates").mkdirs()
            gitMarker.writeText("ok")
            onStatus("git 就绪")
            true
        } catch (t: Throwable) {
            onStatus("git 解压失败: ${t.message}")
            false
        }
    }

    // ----------------------------------------------------------- rust-analyzer

    private val raMarker: File get() = File(root, ".ok-ra-1")

    fun isRaReady(): Boolean = raMarker.isFile && File(root, "bin/rust-analyzer").isFile

    @Synchronized
    fun ensureRustAnalyzer(onStatus: (String) -> Unit = {}): Boolean {
        if (!isReady() && !ensure(onStatus)) return false
        if (isRaReady()) return true
        onStatus("解压 rust-analyzer（约 105MB，含 std 源码）…")
        return try {
            App.ctx.assets.open("ra.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!name.contains("..")) {
                            val out = File(root, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            File(root, "bin/rust-analyzer").setExecutable(true, false)
            raMarker.writeText("ok")
            onStatus("rust-analyzer 就绪")
            true
        } catch (t: Throwable) {
            onStatus("rust-analyzer 解压失败: ${t.message}")
            false
        }
    }

    /** rust-analyzer 需要 rustc 报告正确的 sysroot，这里放一个包装脚本 */
    fun rustAnalyzerPath(): File {
        val dir = File(root, "bin/ra-path")
        dir.mkdirs()
        val f = File(dir, "rustc")
        f.writeText(
            "#!/system/bin/sh\n" +
                "if [ \"\$1\" = \"--print\" ] && [ \"\$2\" = \"sysroot\" ]; then echo " + root.absolutePath + "; exit 0; fi\n" +
                "exec " + File(root, "bin/rustc").absolutePath + " \"\$@\"\n"
        )
        f.setExecutable(true, false)
        return dir
    }

    fun rustAnalyzerEnv(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to libPath(),
        "PATH" to rustAnalyzerPath().absolutePath + ":" + File(root, "bin").absolutePath + ":/system/bin",
        "CARGO_HOME" to cargoHome().absolutePath,
        "RUST_SRC_PATH" to File(root, "lib/rustlib/src/rust/library").absolutePath,
        "RA_LOG" to "error",
        "HOME" to App.ctx.filesDir.absolutePath,
        "TMPDIR" to App.ctx.cacheDir.absolutePath,
    )

    // ----------------------------------------------------------------- clangd

    private val clangdMarker: File get() = File(root, ".ok-clangd-1")

    fun isClangdReady(): Boolean = clangdMarker.isFile && File(root, "bin/clangd").isFile

    @Synchronized
    fun ensureClangd(onStatus: (String) -> Unit = {}): Boolean {
        if (!isReady() && !ensure(onStatus)) return false
        if (isClangdReady()) return true
        onStatus("解压 clangd（约 18MB）…")
        return try {
            App.ctx.assets.open("clangd.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!name.contains("..")) {
                            val out = File(root, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            File(root, "bin/clangd").setExecutable(true, false)
            clangdMarker.writeText("ok")
            onStatus("clangd 就绪")
            true
        } catch (t: Throwable) {
            onStatus("clangd 解压失败: ${t.message}")
            false
        }
    }

    /** 为工程生成 compile_commands.json，放 build/ 下，不污染工程目录 */
    fun writeCompileCommands(projectRoot: File): File {
        val dir = File(App.ctx.filesDir, "build/clangd-" + projectRoot.name)
        dir.mkdirs()
        val entries = org.json.JSONArray()
        fun add(file: File) {
            val cpp = file.name.endsWith(".cpp") || file.name.endsWith(".cc") || file.name.endsWith(".cxx")
            val args = org.json.JSONArray()
            args.put("clang")
            args.put("--target=aarch64-linux-android26")
            args.put("--sysroot=" + sysroot())
            args.put("-I" + projectRoot.absolutePath)
            args.put("-I" + File(projectRoot, "include").absolutePath)
            args.put(if (cpp) "-std=c++17" else "-std=c11")
            args.put("-c")
            args.put(file.absolutePath)
            entries.put(
                org.json.JSONObject()
                    .put("directory", projectRoot.absolutePath)
                    .put("file", file.absolutePath)
                    .put("arguments", args)
            )
        }
        projectRoot.walkTopDown().forEach { f ->
            if (f.isFile && (f.name.endsWith(".c") || f.name.endsWith(".cpp") || f.name.endsWith(".cc") || f.name.endsWith(".cxx"))) {
                if (!f.absolutePath.contains("/build-") && !f.absolutePath.contains("/target/")) add(f)
            }
        }
        File(dir, "compile_commands.json").writeText(entries.toString())
        return dir
    }
    // ------------------------------------------------------------------ CMake

    private val cmakeMarker: File get() = File(root, ".ok-cmake-1")

    fun isCmakeReady(): Boolean = cmakeMarker.isFile && File(root, "bin/cmake").isFile

    @Synchronized
    fun ensureCmake(onStatus: (String) -> Unit = {}): Boolean {
        if (!isReady() && !ensure(onStatus)) return false
        if (isCmakeReady()) return true
        onStatus("解压 CMake 工具链（约 27MB）…")
        return try {
            App.ctx.assets.open("cmake.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!name.contains("..")) {
                            val out = File(root, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zip.copyTo(o, 1 shl 16) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            listOf("bin/cmake", "bin/make", "bin/ninja").forEach { File(root, it).setExecutable(true, false) }
            cmakeMarker.writeText("ok")
            onStatus("CMake 就绪")
            true
        } catch (t: Throwable) {
            onStatus("CMake 解压失败: ${t.message}")
            false
        }
    }

    /** CMake 用的 C 编译器包装：固定 target/sysroot，并自带库路径 */
    fun cmakeCompilerScript(): File {
        val f = File(root, "bin/cmake-cc.sh")
        f.writeText(
            "#!/system/bin/sh\n" +
                "export LD_LIBRARY_PATH=" + libPath() + "\n" +
                "exec " + File(root, "bin/clang").absolutePath +
                " --target=aarch64-linux-android26 --sysroot=" + sysroot() +
                " -fuse-ld=lld \"\$@\"\n"
        )
        f.setExecutable(true, false)
        return f
    }

    /** 内置工具链里没有 clang++，用一个脚本走 clang 的 C++ 驱动模式 */
    fun cxxDriver(): File {
        val f = File(root, "bin/clang++")
        if (!f.isFile || !f.canExecute()) {
            val clang = File(root, "bin/clang").absolutePath
            f.writeText("#!/system/bin/sh\nexec $clang --driver-mode=g++ \"\$@\"\n")
            f.setExecutable(true, false)
        }
        return f
    }

    /** CMake 用的 C++ 编译器包装 */
    fun cmakeCxxScript(): File {
        val f = File(root, "bin/cmake-cxx.sh")
        f.writeText(
            "#!/system/bin/sh\n" +
                "export LD_LIBRARY_PATH=" + libPath() + "\n" +
                "exec " + cxxDriver().absolutePath +
                " --target=aarch64-linux-android26 --sysroot=" + sysroot() +
                " -fuse-ld=lld -L" + libPath() + " \"\$@\"\n"
        )
        f.setExecutable(true, false)
        return f
    }

    /** Termux 的 make 把 shell 写死成 Termux 路径，这里用命令行变量覆盖 */
    fun cmakeMakeScript(): File {
        val f = File(root, "bin/cmake-make.sh")
        f.writeText(
            "#!/system/bin/sh\n" +
                "export SHELL=/system/bin/sh\n" +
                "export LD_LIBRARY_PATH=" + libPath() + "\n" +
                "exec " + File(root, "bin/make").absolutePath + " SHELL=/system/bin/sh \"\$@\"\n"
        )
        f.setExecutable(true, false)
        return f
    }

    fun cmakeEnv(): MutableMap<String, String> {
        val env = toolEnv()
        env["PREFIX"] = File(root, "sysroot/usr").absolutePath
        env["SHELL"] = "/system/bin/sh"
        return env
    }

    /** 从某个文件往上找 CMakeLists.txt */
    fun cmakeRootOf(file: File): File? {
        var dir: File? = file.parentFile
        val stop = App.ctx.filesDir.absolutePath
        var guard = 0
        while (dir != null && guard++ < 12) {
            if (File(dir, "CMakeLists.txt").isFile) return dir
            if (dir.absolutePath == stop) break
            dir = dir.parentFile
        }
        return null
    }

    @Volatile
    var lastCmakeTargets: List<File> = emptyList()
        private set

    private val preferredTargets = HashMap<String, String>()

    fun setPreferredTarget(projectPath: String, name: String) {
        preferredTargets[projectPath] = name
    }

    fun preferredTarget(projectPath: String): String? = preferredTargets[projectPath]

    /** 配置 + 构建，成功返回可执行文件 */
    fun buildCmake(sourceDir: File, log: (String) -> Unit): File? {
        if (!ensureCmake(log)) return null
        val buildDir = File(App.ctx.filesDir, "build/cmake-" + sourceDir.name)
        buildDir.mkdirs()
        val cmake = File(root, "bin/cmake").absolutePath
        val env = cmakeEnv()

        fun run(cmd: List<String>): Int {
            val pb = ProcessBuilder(cmd)
            pb.directory(sourceDir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { log(it) } }
            return p.waitFor()
        }

        val cfg = listOf(
            cmake, "-S", sourceDir.absolutePath, "-B", buildDir.absolutePath,
            "-G", "Unix Makefiles",
            "-DCMAKE_C_COMPILER=" + cmakeCompilerScript().absolutePath,
            "-DCMAKE_CXX_COMPILER=" + cmakeCxxScript().absolutePath,
            "-DCMAKE_MAKE_PROGRAM=" + cmakeMakeScript().absolutePath,
            "-DCMAKE_SYSROOT=" + sysroot(),
            "-DCMAKE_SYSTEM_VERSION=26",
            "-DCMAKE_BUILD_TYPE=Debug",
        )
        if (run(cfg) != 0) {
            log("CMake 配置失败")
            return null
        }
        if (run(listOf(cmake, "--build", buildDir.absolutePath)) != 0) {
            log("CMake 构建失败")
            return null
        }
        val exes = findExecutables(buildDir)
        lastCmakeTargets = exes
        if (exes.isEmpty()) return null
        val preferred = preferredTargets[sourceDir.absolutePath]
        return exes.firstOrNull { it.name == preferred }
            ?: exes.firstOrNull { it.name == sourceDir.name }
            ?: exes.maxByOrNull { it.lastModified() }
    }

    private fun findExecutables(buildDir: File): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 3) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    if (f.name != "CMakeFiles" && !f.name.startsWith(".")) walk(f, depth + 1)
                } else if (f.canExecute() && !f.name.contains('.')) {
                    out.add(f)
                }
            }
        }
        walk(buildDir, 0)
        return out
    }
    fun libPath(): String = File(root, "lib").absolutePath
    fun sysroot(): String = File(root, "sysroot").absolutePath
    fun binary(name: String): File = File(root, "bin/$name")

    /** 编译：返回 (成功, 输出日志) */
    fun compile(source: File, output: File): Pair<Boolean, String> {
        val cmd = listOf(
            binary("clang").absolutePath,
            "--target=aarch64-linux-android26",
            "--sysroot=${sysroot()}",
            "-g",
            "-O0",
            "-fno-omit-frame-pointer",
            "-o", output.absolutePath,
            source.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(source.parentFile)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = libPath()
        env["HOME"] = App.ctx.filesDir.absolutePath
        env["TMPDIR"] = App.ctx.cacheDir.absolutePath
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return (code == 0) to text
    }

    /** 解析 DWARF 行表：只保留 sourceName 这个文件的行，(地址 → 行号) 升序 */
    fun lineTable(binary: File, sourceName: String): List<Pair<Long, Int>> {
        val cmd = listOf(binary("llvm-dwarfdump").absolutePath, "--debug-line", binary.absolutePath)
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libPath()
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        // 先找源文件在 file_names 里的编号，Rust 的二进制里混着 std 的一堆文件
        var fileIndex = -1
        var pendingIndex = -1
        val fileRe = Regex("""file_names\[\s*(\d+)\]:""")
        val nameRe = Regex("""name:\s*"(.*)"""")
        for (line in text.lineSequence()) {
            val fm = fileRe.find(line)
            if (fm != null) {
                pendingIndex = fm.groupValues[1].toIntOrNull() ?: -1
                continue
            }
            val nm = nameRe.find(line)
            if (nm != null && pendingIndex >= 0 && fileIndex < 0) {
                val name = nm.groupValues[1]
                if (name == sourceName || name.endsWith("/" + sourceName)) fileIndex = pendingIndex
            }
        }

        val rows = ArrayList<Pair<Long, Int>>()
        val re = Regex("""^(0x[0-9a-fA-F]+)\s+(\d+)\s+(\d+)\s+(\d+)\s""")
        val all = ArrayList<Pair<Long, Int>>()
        text.lineSequence().forEach { line ->
            val m = re.find(line.trim()) ?: return@forEach
            val addr = m.groupValues[1].removePrefix("0x").toLongOrNull(16) ?: return@forEach
            val ln = m.groupValues[2].toIntOrNull() ?: return@forEach
            val file = m.groupValues[4].toIntOrNull() ?: -1
            if (addr > 0 && ln > 0) {
                all.add(addr to ln)
                if (fileIndex < 0 || file == fileIndex) rows.add(addr to ln)
            }
        }
        rows.sortBy { it.first }
        if (rows.isEmpty()) {
            all.sortBy { it.first }
            return all
        }
        return rows
    }

    /** 单文件 C++ 编译 */
    fun compileCpp(source: File, output: File): Pair<Boolean, String> {
        val cmd = listOf(
            cxxDriver().absolutePath,
            "--target=aarch64-linux-android26",
            "--sysroot=${sysroot()}",
            "-std=c++17",
            "-L" + libPath(),
            "-g",
            "-O0",
            "-fno-omit-frame-pointer",
            "-o", output.absolutePath,
            source.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(source.parentFile)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = libPath()
        env["HOME"] = App.ctx.filesDir.absolutePath
        env["TMPDIR"] = App.ctx.cacheDir.absolutePath
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return (code == 0) to text
    }

    /** 解析 DWARF 调试信息（函数范围、变量位置） */
    fun debugInfo(binary: File): DwarfInfo {
        val cmd = listOf(binary("llvm-dwarfdump").absolutePath, "--debug-info", binary.absolutePath)
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libPath()
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return DwarfParser.parse(text)
    }

    fun addressForLine(table: List<Pair<Long, Int>>, line: Int): Long? =
        table.firstOrNull { it.second == line }?.first

    fun lineForAddress(table: List<Pair<Long, Int>>, pc: Long): Int? {
        var best: Int? = null
        for (row in table) {
            if (row.first > pc) break
            best = row.second
        }
        return best
    }
}
