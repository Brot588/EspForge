package com.espstudio.ide.run

import android.content.Context
import android.net.Uri
import com.espstudio.ide.App
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ESP-IDF 资源包管理器。
 *
 * 资源包（用户导入的 zip）解压到 files/pack：
 *
 *   esp-idf/                ESP-IDF 源码树
 *   tools/xtensa-esp-elf/   glibc 交叉工具链（未修改的原始二进制）
 *   tools/esp32ulp-elf/
 *   tools/cmake/            bionic 的 cmake/ninja/make（RPATH 已修好）
 *   tools/python/           bionic 的 python3（Termux 构建）
 *   tools/python-site/      pip 依赖（kconfiglib / pyyaml / esptool …）
 *   glibc/                  aarch64 glibc 运行时，给 glibc 工具链用
 *   manifest.json
 *
 * 关键点：ESP 的交叉工具链是 glibc 二进制，Android 是 bionic，内核会去找
 * /lib/ld-linux-aarch64.so.1 而失败。这里在解压后为每个 ELF 可执行文件生成
 * 一个 shell wrapper：
 *
 *   #!/system/bin/sh
 *   unset LD_LIBRARY_PATH
 *   exec <glibc>/lib/ld-linux-aarch64.so.1 --library-path <glibc>/lib:<tc>/lib <xxx>.real "$@"
 *
 * 不能改用 patchelf 直接改 PT_INTERP：那会破坏 ELF 段布局，cc1 直接段错误。
 */
object Toolchain {

    private const val PACK_VERSION = "1"

    val root: File get() = File(App.ctx.filesDir, "pack")

    private val marker: File get() = File(root, ".ok-$PACK_VERSION")

    val idfPath: File get() = File(root, "esp-idf")
    val glibcDir: File get() = File(root, "glibc")
    val glibcLoader: File get() = File(glibcDir, "lib/ld-linux-aarch64.so.1")

    fun xtensaDir() = File(root, "tools/xtensa-esp-elf")
    fun ulpDir() = File(root, "tools/esp32ulp-elf")
    fun cmakeDir() = File(root, "tools/cmake")
    fun pythonDir() = File(root, "tools/python")
    fun pythonSite() = File(root, "tools/python-site")

    fun pythonExe(): File = File(pythonDir(), "bin/python3")
    fun cmakeExe(): File = File(cmakeDir(), "bin/cmake")

    val home: File get() = File(App.ctx.filesDir, "home")
    val tmp: File get() = File(App.ctx.cacheDir, "tmp")
    val idfTools: File get() = File(App.ctx.filesDir, "idf-tools")

    // ------------------------------------------------------------------ 状态

    fun isReady(): Boolean = marker.isFile &&
        idfPath.isDirectory &&
        pythonExe().isFile &&
        glibcLoader.isFile &&
        File(xtensaDir(), "bin/xtensa-esp-elf-gcc.real").isFile

    /** manifest 里的信息；资源包缺失时返回 null */
    fun manifest(): JSONObject? = try {
        val f = File(root, "manifest.json")
        if (f.isFile) JSONObject(f.readText()) else null
    } catch (_: Throwable) {
        null
    }

    fun packSummary(): String {
        val m = manifest() ?: return "未安装资源包"
        val idf = m.optString("idfVersion", "?")
        val py = m.optString("pythonVersion", "?")
        return "ESP-IDF $idf · Python $py"
    }

    /** 缺少什么，用于给用户提示 */
    fun missing(): List<String> {
        if (!root.isDirectory) return listOf("资源包未导入")
        val out = ArrayList<String>()
        if (!idfPath.isDirectory) out.add("esp-idf/")
        if (!pythonExe().isFile) out.add("tools/python/bin/python3")
        if (!cmakeExe().isFile) out.add("tools/cmake/bin/cmake")
        if (!glibcLoader.isFile) out.add("glibc/lib/ld-linux-aarch64.so.1")
        if (!File(xtensaDir(), "bin/xtensa-esp-elf-gcc").isFile) out.add("tools/xtensa-esp-elf/bin/xtensa-esp-elf-gcc")
        return out
    }

    // ------------------------------------------------------------- 导入资源包

    /**
     * 从系统文件选择器拿到的 uri 解压资源包。先解压到临时目录，成功后整体替换，
     * 避免半途失败留下一个坏的工具链。
     */
    @Synchronized
    fun importPack(uri: Uri, onStatus: (String) -> Unit = {}): Boolean {
        val ctx = App.ctx
        val staging = File(ctx.filesDir, "pack-staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        onStatus("读取资源包…")
        return try {
            val input: InputStream = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开所选文件")
            var lastTick = 0L
            var bytes = 0L
            BufferedInputStream(input, 1 shl 16).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.contains("..") || name.startsWith("/")) {
                            zip.closeEntry(); entry = zip.nextEntry; continue
                        }
                        val out = File(staging, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o -> bytes += zip.copyTo(o, 1 shl 16) }
                            if (name.contains("bin/") || name.contains("libexec/") || name.endsWith(".so")) {
                                out.setExecutable(true, false)
                            }
                        }
                        zip.closeEntry()
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 400) {
                            lastTick = now
                            onStatus("解压资源包… ${bytes / (1 shl 20)} MB")
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            // 顶层目录校验：资源包必须是「解压后就是 pack 根」的结构
            if (!File(staging, "manifest.json").isFile && !File(staging, "esp-idf").isDirectory) {
                staging.deleteRecursively()
                onStatus("这个 zip 不是有效的资源包（缺少 esp-idf/ 或 manifest.json）")
                return false
            }
            onStatus("安装资源包…")
            if (root.exists()) root.deleteRecursively()
            if (!staging.renameTo(root)) {
                staging.copyRecursively(root, overwrite = true)
                staging.deleteRecursively()
            }
            onStatus("生成 glibc 包装脚本…")
            wrapGlibc { msg -> onStatus(msg) }
            File(root, ".ok-$PACK_VERSION").writeText("ok")
            onStatus("资源包就绪")
            true
        } catch (t: Throwable) {
            try {
                staging.deleteRecursively()
            } catch (_: Throwable) {
            }
            onStatus("导入失败: ${t.message}")
            false
        }
    }

    // ---------------------------------------------------------- glibc wrapper

    /**
     * 为 glibc 工具链里的每个 ELF 可执行文件生成 wrapper，并把原文件改名成 *.real。
     * 幂等：已经包过（存在 .real）就跳过。
     */
    @Synchronized
    fun wrapGlibc(onStatus: (String) -> Unit = {}): Int {
        if (!glibcLoader.isFile) {
            onStatus("缺少 glibc 运行时，跳过包装")
            return 0
        }
        val loader = glibcLoader.absolutePath
        val libPath = glibcDir.absolutePath + "/lib"
        var count = 0
        val dirs = listOf(
            File(xtensaDir(), "bin"),
            File(xtensaDir(), "xtensa-esp-elf/bin"),
            File(ulpDir(), "bin"),
            File(ulpDir(), "esp32ulp-elf/bin"),
        ).filter { it.isDirectory } +
            (File(xtensaDir(), "libexec/gcc").listFiles()?.flatMap { ver ->
                ver.listFiles()?.filter { it.isDirectory } ?: emptyList()
            } ?: emptyList()) +
            (File(ulpDir(), "libexec/gcc").listFiles()?.flatMap { ver ->
                ver.listFiles()?.filter { it.isDirectory } ?: emptyList()
            } ?: emptyList())

        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                if (f.name.endsWith(".real")) continue
                if (f.name.contains(".so")) continue
                val real = File(dir, f.name + ".real")
                if (real.exists()) continue
                if (!isElfExecutable(f)) continue
                try {
                    if (!f.renameTo(real)) continue
                    val tcLib = dir.parentFile?.absolutePath + "/lib"
                    val wrapper = "#!/system/bin/sh\n" +
                        "unset LD_LIBRARY_PATH\n" +
                        "unset LD_PRELOAD\n" +
                        "exec \"$loader\" --library-path \"$libPath:$tcLib\" \"" +
                        real.absolutePath + "\" \"\$@\"\n"
                    f.writeText(wrapper)
                    f.setExecutable(true, false)
                    real.setExecutable(true, false)
                    count++
                } catch (_: Throwable) {
                }
            }
        }
        if (count > 0) onStatus("已包装 $count 个工具链程序")
        else onStatus("工具链包装已是最新")
        return count
    }

    /** ELF 且不是可重定位目标（.o），用来区分可执行文件与共享库 */
    private fun isElfExecutable(f: File): Boolean {
        return try {
            val head = ByteArray(18)
            f.inputStream().use { it.read(head) }
            if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
            ) return false
            // e_type: 1=REL, 2=EXEC, 3=DYN(PIE/so), 4=CORE
            val type = (head[16].toInt() and 0xFF) or ((head[17].toInt() and 0xFF) shl 8)
            type == 2 || type == 3
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ 环境

    /** 给 bionic 程序（python / cmake / ninja / git）用的库路径 */
    fun bionicLibPath(): String {
        val parts = ArrayList<String>()
        parts.add(File(pythonDir(), "lib").absolutePath)
        parts.add(File(cmakeDir(), "lib").absolutePath)
        if (gitRoot().isDirectory) parts.add(File(gitRoot(), "lib").absolutePath)
        return parts.joinToString(":")
    }

    fun gitRoot(): File = File(App.ctx.filesDir, "git")

    val gitExe: File get() = File(gitRoot(), "bin/git")

    /** 构建 / 烧录时统一使用的环境变量 */
    fun env(extra: Map<String, String> = emptyMap()): Map<String, String> {
        home.mkdirs()
        tmp.mkdirs()
        idfTools.mkdirs()
        val path = listOf(
            File(pythonDir(), "bin").absolutePath,
            File(xtensaDir(), "bin").absolutePath,
            File(ulpDir(), "bin").absolutePath,
            File(cmakeDir(), "bin").absolutePath,
            File(gitRoot(), "bin").absolutePath,
            "/system/bin",
            "/system/xbin",
        ).joinToString(":")
        val m = LinkedHashMap<String, String>()
        m["PATH"] = path
        m["LD_LIBRARY_PATH"] = bionicLibPath()
        m["PYTHONPATH"] = pythonSite().absolutePath
        m["PYTHONHOME"] = pythonDir().absolutePath
        m["HOME"] = home.absolutePath
        m["TMPDIR"] = tmp.absolutePath
        m["IDF_PATH"] = idfPath.absolutePath
        m["IDF_TOOLS_PATH"] = idfTools.absolutePath
        m["IDF_PYTHON_ENV_PATH"] = pythonDir().absolutePath
        // 离线：不启用组件管理器，也不去网上拉东西
        m["IDF_COMPONENT_MANAGER"] = "0"
        m["ESP_IDF_VERSION"] = manifest()?.optString("idfVersion", "5.5.1") ?: "5.5.1"
        m["TERM"] = "xterm-256color"
        m.putAll(extra)
        return m
    }

    /** 检查工具链是否真的能跑起来（输出版本号） */
    fun selfCheck(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val probes = listOf(
            "python3" to listOf(pythonExe().absolutePath, "--version"),
            "cmake" to listOf(cmakeExe().absolutePath, "--version"),
            "xtensa-gcc" to listOf(File(xtensaDir(), "bin/xtensa-esp-elf-gcc").absolutePath, "--version"),
        )
        for ((name, cmd) in probes) {
            out.add(name to runCapture(cmd, idfPath, 15000).trim().lineSequence().firstOrNull().orEmpty())
        }
        return out
    }

    // ------------------------------------------------------------ 进程执行

    @Volatile
    private var current: Process? = null

    /** 中断当前正在跑的命令（构建中断） */
    fun cancel() {
        try {
            current?.destroyForcibly()
        } catch (_: Throwable) {
        }
        current = null
    }

    /** 跑一个命令并把 stdout+stderr 按行回调；返回退出码 */
    fun runStreaming(
        cmd: List<String>,
        cwd: File,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(cwd)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment().putAll(env)
            val p = pb.start()
            current = p
            p.inputStream.bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    onLine(line)
                    line = r.readLine()
                }
            }
            p.waitFor()
        } catch (t: Throwable) {
            onLine("! ${t.message}")
            -1
        } finally {
            current = null
        }
    }

    /** 短命令，返回合并后的输出 */
    fun runCapture(cmd: List<String>, cwd: File, timeoutMs: Long = 20000): String {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(cwd)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment().putAll(env())
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        } catch (t: Throwable) {
            "! ${t.message}"
        }
    }

    // ------------------------------------------------------------ 内置 git

    @Synchronized
    fun ensureGit(onStatus: (String) -> Unit = {}): Boolean {
        if (gitExe.isFile) return true
        onStatus("解压 git…")
        val dst = gitRoot()
        return try {
            App.ctx.assets.open("git.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        if (!e.name.contains("..")) {
                            val o = File(dst, e.name)
                            if (e.isDirectory) o.mkdirs() else {
                                o.parentFile?.mkdirs()
                                o.outputStream().use { out -> zip.copyTo(out, 1 shl 16) }
                            }
                            if (e.name.contains("bin/")) o.setExecutable(true, false)
                        }
                        zip.closeEntry()
                        e = zip.nextEntry
                    }
                }
            }
            gitExe.setExecutable(true, false)
            true
        } catch (t: Throwable) {
            onStatus("git 解压失败: ${t.message}")
            false
        }
    }

    /** Termux 二进制有时需要一个可写的 HOME 才能正常工作 */
    fun ensureDirs() {
        home.mkdirs()
        tmp.mkdirs()
        idfTools.mkdirs()
    }
}
