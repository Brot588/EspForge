package com.espforge.ide.run

import android.content.Context
import android.net.Uri
import com.espforge.ide.App
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
 * 一个原生 ELF 垫片（assets/execshim，见 native/execshim.c）：
 *
 *   <tool>               装好的垫片
 *   <tool>.real          原来的工具链二进制
 *
 * 垫片会 exec 成 `<glibc>/lib/ld-linux-aarch64.so.1 --library-path <glibc>/lib <xxx>.real "$@"`。
 * 必须用 ELF 垫片而不是 shell 脚本：ninja 用 posix_spawn 直接 exec，脚本形式会 127。
 *
 * 不能改用 patchelf 直接改 PT_INTERP：那会破坏 ELF 段布局，cc1 直接段错误。
 */
object Toolchain {

    /** 资源包内容布局版本。修补逻辑变了就 +1，老包的 .ok 标记自动失效并重跑修补。 */
    private const val PACK_VERSION = "4"

    val root: File get() = File(App.ctx.filesDir, "pack")

    private val marker: File get() = File(root, ".ok-$PACK_VERSION")

    val idfPath: File get() = File(root, "esp-idf")
    val glibcDir: File get() = File(root, "glibc")
    val glibcLoader: File get() = File(glibcDir, "lib/ld-linux-aarch64.so.1")

    /** 目标芯片对应的工具链目录：tools/xtensa-esp32-elf 或 tools/xtensa-esp32s3-elf */
    fun xtensaDir(chip: String = "esp32") = File(root, "tools/xtensa-$chip-elf")
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
        File(xtensaDir("esp32"), "bin/xtensa-esp32-elf-gcc.real").isFile

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
        if (!File(xtensaDir("esp32"), "bin/xtensa-esp32-elf-gcc").isFile) {
            out.add("tools/xtensa-esp32-elf/bin/xtensa-esp32-elf-gcc")
        }
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
        return importPack({ ctx.contentResolver.openInputStream(uri) }, onStatus)
    }

    /** 从本地文件导入（测试/侧载用：/sdcard/Android/data/<pkg>/files/pack.zip） */
    @Synchronized
    fun importPack(file: File, onStatus: (String) -> Unit = {}): Boolean {
        if (!file.isFile) {
            onStatus("文件不存在: ${file.absolutePath}")
            return false
        }
        return importPack({ file.inputStream() }, onStatus)
    }

    @Synchronized
    fun importPack(open: () -> InputStream?, onStatus: (String) -> Unit = {}): Boolean {
        val ctx = App.ctx
        val staging = File(ctx.filesDir, "pack-staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        onStatus("读取资源包…")
        return try {
            val input: InputStream = open() ?: throw IllegalStateException("无法打开所选文件")
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
            onStatus("修正文件权限…")
            ensureExecutable { msg -> onStatus(msg) }
            onStatus("生成 glibc 包装脚本…")
            wrapGlibc { msg -> onStatus(msg) }
            onStatus("应用资源包修补…")
            applyPackPatches { msg -> onStatus(msg) }
            val complete = markReadyIfComplete()
            onStatus(if (complete) "资源包就绪" else "资源包不完整：" + missing().joinToString("、"))
            complete
        } catch (t: Throwable) {
            try {
                staging.deleteRecursively()
            } catch (_: Throwable) {
            }
            onStatus("导入失败: ${t.message}")
            false
        }
    }

    /**
     * ZipInputStream 不还原文件权限，解压出来的二进制默认是 0644，直接 exec 会
     * Permission denied。所以导入后要把所有该能执行的地方补成 755。
     */
    @Synchronized
    fun ensureExecutable(onStatus: (String) -> Unit = {}): Int {
        var count = 0
        fun fix(dir: File) {
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.isDirectory) continue
                try {
                    if (f.setExecutable(true, false)) count++
                    f.setReadable(true, false)
                } catch (_: Throwable) {
                }
            }
        }
        // glibc 的 loader 和 .so 都要能读，loader 还要能执行
        val glibcLib = File(glibcDir, "lib")
        if (glibcLib.isDirectory) fix(glibcLib)
        // 各工具链的 bin 目录
        val bins = ArrayList<File>()
        File(root, "tools").listFiles()?.forEach { t ->
            File(t, "bin").takeIf { it.isDirectory }?.let { bins.add(it) }
            File(t, "lib").takeIf { it.isDirectory }?.let { bins.add(it) }
            t.listFiles()?.forEach { sub ->
                File(sub, "bin").takeIf { it.isDirectory }?.let { bins.add(it) }
            }
            File(t, "libexec").takeIf { it.isDirectory }?.let { le ->
                le.listFiles()?.forEach { ver ->
                    ver.listFiles()?.forEach { inner -> if (inner.isDirectory) bins.add(inner) }
                }
            }
        }
        bins.forEach { fix(it) }
        // python 的 .so 只需要可读，但顺手一起处理
        if (count > 0) onStatus("修正 $count 个文件的可执行权限")
        return count
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
        val shim = ensureShim() ?: run {
            onStatus("缺少 execshim 垫片，无法包装工具链")
            return 0
        }
        var count = 0
        val roots = listOf(xtensaDir("esp32"), xtensaDir("esp32s3")).filter { it.isDirectory }
        val dirs = ArrayList<File>()
        for (tcRoot in roots) {
            val target = tcRoot.name // xtensa-esp32-elf / xtensa-esp32s3-elf
            dirs.add(File(tcRoot, "bin"))
            dirs.add(File(tcRoot, "$target/bin"))
            File(tcRoot, "libexec/gcc").listFiles()?.forEach { ver ->
                ver.listFiles()?.filter { it.isDirectory }?.let { dirs.addAll(it) }
            }
        }

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
                    // 用原生 ELF 垫片顶替原来的文件名：ninja 用 posix_spawn 直接 exec，
                    // shell 脚本形式会失败（exit 127），ELF 才行。
                    shim.copyTo(f, overwrite = true)
                    f.setExecutable(true, false)
                    real.setExecutable(true, false)
                    count++
                } catch (_: Throwable) {
                }
            }
        }
        if (count > 0) onStatus("已包装 $count 个工具链程序")
        return count
    }

    /**
     * 把 assets 里的 execshim（原生 aarch64 ELF）释放到 pack 根目录。
     * 它负责 exec 成 `ld.so --library-path ... <tool>.real <args>`。
     */
    @Synchronized
    fun ensureShim(): File? {
        val dst = File(root, "execshim")
        try {
            val asset = App.ctx.assets.open("execshim").use { it.readBytes() }
            val cur = if (dst.isFile) dst.readBytes() else null
            // 内容比对：垫片升级（比如新增了 GCC_* 变量清理）后要能覆盖旧的
            if (cur == null || !cur.contentEquals(asset)) dst.writeBytes(asset)
            dst.setExecutable(true, false)
            if (dst.isFile && dst.length() > 1024) return dst
        } catch (_: Throwable) {
        }
        return null
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

    // ---------------------------------------------------------- 资源包修补
    //
    // 资源包里的二进制/脚本是 Termux 或上游原样打包的，直接在这个 App 里用会坏。
    // 解压后（或首次启动发现没就绪时）幂等地做这些修补，等价于 pack/scripts/*.py：
    //
    //  1. tools/cmake/bin/{ninja,cmake,make} 里硬编码的 Termux 路径 → /system/bin/sh
    //     （否则 ninja 跑的每条命令都 code=127）
    //  2. pyserial 的 list_ports_posix.py 在 Android 上返回空列表，不要 raise ImportError
    //     （esptool import 它就炸，构建时 elf2image 用到）
    //  3. site-packages/espforge_pack.pth 把 tools/python-site 塞进 sys.path
    //     （idf.py 的子进程会丢 PYTHONPATH）
    //  4. gdbinit.cmake 在 ESP_ROM_ELF_DIR 为空时直接 return（包里没有 rom-elfs）
    //  5. 工程模板 sdkconfig.defaults 关掉证书包 —— 见 fs/Templates.kt
    //  6. env() 不设 IDF_PYTHON_ENV_PATH —— 见下面 env()
    //
    // 还有两项是为了在 App 进程里（有 seccomp 过滤）能跑 glibc 工具链：
    //  7. glibc 的 ld.so / libc.so.6 里 set_robust_list(#99) / rseq(#293) 调用点
    //     改成 nop（app seccomp 会 KILL_PROCESS 这两个 syscall）
    //  8. execshim 垫片清掉 GCC_EXEC_PREFIX/COLLECT_GCC*（带着它们 cc1 会 SIGSYS），
    //     并刷新已经包过的工具
    //
    // 全部幂等；返回值是本次实际改动的位置数，0 表示已是最新。

    @Synchronized
    fun applyPackPatches(onStatus: (String) -> Unit = {}): Int {
        var changed = 0
        changed += patchTermuxShell()
        changed += patchPyserial()
        changed += patchPythonPath()
        changed += patchGdbinit()
        changed += patchGlibcSyscalls()
        // 垫片本身也升级过（新增 GCC_* 环境变量清理）：已包装的工具换成新垫片
        ensureShim()?.let { changed += refreshWrappedShims(it) }
        if (changed > 0) onStatus("已应用资源包修补（$changed 处）")
        return changed
    }

    /**
     * 5. 把 glibc 里几个被 app seccomp 杀掉（KILL_PROCESS → SIGSYS）的 syscall 处理掉。
     *
     *   set_robust_list(#99) / rseq(#293)：把 mov 后面紧跟的 `svc #0` 换成 `nop`。
     *     glibc 在 ld.so 和 libc 的早期初始化里就会调它们，导致任何 glibc 程序
     *     一 exec 就 "Bad system call"。返回值 glibc 都忽略/能降级，跳过安全
     *     （代价：PTHREAD_MUTEX_ROBUST 的 robust 语义没了，工具链不用它）。
     *
     *   faccessat2(#439) → faccessat(#48)：直接改 syscall 号。
     *     glibc 的 access() 优先用 faccessat2，Android 会杀它（实测 ld 就是这样死的）；
     *     老 syscall 在 flags=0（access() 的用法）时语义一致。
     */
    private fun patchGlibcSyscalls(): Int {
        val dir = File(glibcDir, "lib")
        if (!dir.isDirectory) return 0
        val nop = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())
        val moves = arrayOf(
            byteArrayOf(0x68, 0x0c, 0x80.toByte(), 0xd2.toByte()),            // mov x8, #99
            byteArrayOf(0xa8.toByte(), 0x24, 0x80.toByte(), 0xd2.toByte()),   // mov x8, #293
        )
        val accessat2 = byteArrayOf(0xe8.toByte(), 0x36, 0x80.toByte(), 0xd2.toByte()) // mov x8, #439
        val accessat = byteArrayOf(0x08, 0x06, 0x80.toByte(), 0xd2.toByte())           // mov x8, #48
        var total = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() < 4096) return@forEach
            val data = try {
                f.readBytes()
            } catch (_: Throwable) {
                return@forEach
            }
            var hits = 0
            for (mv in moves) hits += nopSvcAfter(data, mv, nop)
            hits += replaceAll(data, accessat2, accessat)
            if (hits > 0) {
                try {
                    f.writeBytes(data)
                    total += hits
                } catch (_: Throwable) {
                }
            }
        }
        return total
    }

    /** 把 mov 之后 40 字节内第一条 `svc #0` 换成 nop（参数准备指令可能夹在中间） */
    private fun nopSvcAfter(data: ByteArray, mv: ByteArray, nop: ByteArray): Int {
        var hits = 0
        var i = 0
        val limit = data.size - mv.size
        scan@ while (i <= limit) {
            var k = 0
            while (k < 4 && data[i + k] == mv[k]) k++
            if (k != 4) {
                i++
                continue@scan
            }
            val stop = minOf(i + 40, data.size - 4)
            var j = i + 4
            while (j <= stop) {
                if (data[j] == 0x01.toByte() && data[j + 1] == 0x00.toByte() &&
                    data[j + 2] == 0x00.toByte() && data[j + 3] == 0xd4.toByte()
                ) {
                    for (b in 0 until 4) data[j + b] = nop[b]
                    hits++
                    i = j + 4
                    continue@scan
                }
                j++
            }
            i++
        }
        return hits
    }

    /** 等长替换（4 字节指令改 syscall 号） */
    private fun replaceAll(data: ByteArray, from: ByteArray, to: ByteArray): Int {
        var hits = 0
        var i = 0
        val limit = data.size - from.size
        while (i <= limit) {
            var k = 0
            while (k < from.size && data[i + k] == from[k]) k++
            if (k == from.size) {
                for (b in from.indices) data[i + b] = to[b]
                hits++
                i += from.size
            } else {
                i++
            }
        }
        return hits
    }

    /** 把已经包装过的工具（旁边有同名 .real 的）换成当前垫片 */
    private fun refreshWrappedShims(shim: File): Int {
        val bytes = try {
            shim.readBytes()
        } catch (_: Throwable) {
            return 0
        }
        val dirs = ArrayList<File>()
        for (tcRoot in listOf(xtensaDir("esp32"), xtensaDir("esp32s3"))) {
            if (!tcRoot.isDirectory) continue
            dirs.add(File(tcRoot, "bin"))
            dirs.add(File(tcRoot, "${tcRoot.name}/bin"))
            File(tcRoot, "libexec/gcc").listFiles()?.forEach { ver ->
                ver.listFiles()?.filter { it.isDirectory }?.let { dirs.addAll(it) }
            }
        }
        var n = 0
        for (dir in dirs) {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.name.endsWith(".real")) return@forEach
                if (!File(dir, f.name + ".real").isFile) return@forEach
                val cur = try {
                    f.readBytes()
                } catch (_: Throwable) {
                    return@forEach
                }
                if (!cur.contentEquals(bytes)) {
                    try {
                        f.writeBytes(bytes)
                        f.setExecutable(true, false)
                        n++
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return n
    }

    /** 1. Termux 二进制里硬编码的 shell 路径改成 Android 的等长替换 */
    private fun patchTermuxShell(): Int {
        val dir = File(cmakeDir(), "bin")
        if (!dir.isDirectory) return 0
        val pairs = listOf(
            "/data/data/com.termux/files/usr/bin/sh".toByteArray() to "/system/bin/sh".toByteArray(),
            "/data/data/com.termux/files/usr/bin/bash".toByteArray() to "/system/bin/sh".toByteArray(),
        )
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() < 4096) return@forEach
            for ((old, new) in pairs) n += patchEqualLength(f, old, new)
        }
        return n
    }

    /**
     * 在文件里做等长字节替换，多出来的字节用 NUL 填 —— C 字符串到第一个 NUL 结束，
     * 所以填充安全。这样不用改 ELF 的布局/长度。
     */
    private fun patchEqualLength(file: File, old: ByteArray, new: ByteArray): Int {
        if (new.size > old.size) return 0
        val data = try {
            file.readBytes()
        } catch (_: Throwable) {
            return 0
        }
        val first = old[0]
        var hits = 0
        var i = 0
        val limit = data.size - old.size
        while (i <= limit) {
            if (data[i] != first) {
                i++
                continue
            }
            var j = 1
            while (j < old.size && data[i + j] == old[j]) j++
            if (j == old.size) {
                System.arraycopy(new, 0, data, i, new.size)
                var k = new.size
                while (k < old.size) {
                    data[i + k] = 0
                    k++
                }
                hits++
                i += old.size
            } else {
                i++
            }
        }
        if (hits > 0) {
            try {
                file.writeBytes(data)
            } catch (_: Throwable) {
                return 0
            }
        }
        return hits
    }

    /** 2. pyserial：Android 上给 comports 一个空实现 */
    private fun patchPyserial(): Int {
        val f = File(pythonSite(), "serial/tools/list_ports_posix.py")
        if (!f.isFile) return 0
        val text = try {
            f.readText()
        } catch (_: Throwable) {
            return 0
        }
        if (text.contains("plat[:7] == 'android'") || text.contains("sys.platform == 'android'")) {
            return 0 // 已经打过
        }
        val anchor = "else:\n    # platform detection has failed"
        if (!text.contains(anchor)) return 0
        val branch = "elif plat[:7] == 'android':  # EspForge: bionic 没有 /dev/tty*，串口走 UsbManager\n" +
            "    def comports(include_links=False):\n" +
            "        return []\n\n"
        return try {
            f.writeText(text.replaceFirst(anchor, branch + anchor))
            1
        } catch (_: Throwable) {
            0
        }
    }

    /** 3. 用 .pth 把 python-site 加进 sys.path（不依赖环境变量传递） */
    private fun patchPythonPath(): Int {
        val lib = File(pythonDir(), "lib")
        val sitePackages = lib.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("python3") }
            ?.map { File(it, "site-packages") }
            ?.firstOrNull { it.isDirectory }
            ?: return 0
        val pth = File(sitePackages, "espforge_pack.pth")
        val want = pythonSite().absolutePath + "\n"
        if (pth.isFile) {
            val cur = try {
                pth.readText()
            } catch (_: Throwable) {
                ""
            }
            if (cur == want) return 0
        }
        return try {
            pth.writeText(want)
            1
        } catch (_: Throwable) {
            0
        }
    }

    /** 4. gdbinit.cmake：没有 rom-elfs 时别让 configure 死在 file(TO_CMAKE_PATH) */
    private fun patchGdbinit(): Int {
        val f = File(idfPath, "tools/cmake/gdbinit.cmake")
        if (!f.isFile) return 0
        val text = try {
            f.readText()
        } catch (_: Throwable) {
            return 0
        }
        if (text.contains("EspForge: 资源包不含 esp-rom-elfs")) return 0
        val anchor = "    file(TO_CMAKE_PATH \$ENV{ESP_ROM_ELF_DIR} ESP_ROM_ELF_DIR)\n"
        if (!text.contains(anchor)) return 0
        val guard = "    if(\"\$ENV{ESP_ROM_ELF_DIR}\" STREQUAL \"\")\n" +
            "        # EspForge: 资源包不含 esp-rom-elfs，且不用 OpenOCD/JTAG，\n" +
            "        # 跳过 gdbinit 生成，避免 file(TO_CMAKE_PATH) 参数不足而中断 configure。\n" +
            "        return()\n" +
            "    endif()\n" +
            anchor
        return try {
            f.writeText(text.replaceFirst(anchor, guard))
            1
        } catch (_: Throwable) {
            0
        }
    }

    /** 删掉旧版本的 .ok 标记，只留当前 PACK_VERSION 的 */
    fun pruneOldMarkers() {
        try {
            root.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(".ok-") && f.name != marker.name) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 一条龙把资源包准备到可用：权限 → glibc 垫片 → 内容修补 → .ok 标记。
     * 幂等，已就绪时直接返回。
     */
    @Synchronized
    fun finalizePack(onStatus: (String) -> Unit = {}): Boolean {
        if (!root.isDirectory) {
            onStatus("资源包未导入")
            return false
        }
        pruneOldMarkers()
        if (isReady()) return true
        ensureDirs()
        ensureExecutable(onStatus)
        wrapGlibc(onStatus)
        applyPackPatches(onStatus)
        val ok = markReadyIfComplete()
        if (ok) onStatus("资源包就绪") else onStatus("资源包不完整：" + missing().joinToString("、"))
        return ok
    }

    /** 关键文件齐全就写 .ok 标记；返回是否就绪 */
    private fun markReadyIfComplete(): Boolean {
        val ok = idfPath.isDirectory &&
            pythonExe().isFile &&
            cmakeExe().isFile &&
            glibcLoader.isFile &&
            File(xtensaDir("esp32"), "bin/xtensa-esp32-elf-gcc").isFile
        if (ok) {
            try {
                // 只留当前版本的标记，别在包里攒一堆 .ok-1/.ok-2/.ok-3
                root.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(".ok-") && f.name != marker.name) f.delete()
                }
                marker.writeText("ok")
            } catch (_: Throwable) {
            }
        }
        return ok
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
    fun env(chip: String = "esp32", extra: Map<String, String> = emptyMap()): Map<String, String> {
        home.mkdirs()
        tmp.mkdirs()
        idfTools.mkdirs()
        val path = listOf(
            File(pythonDir(), "bin").absolutePath,
            File(xtensaDir(chip), "bin").absolutePath,
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
        // 注意：不要设 IDF_PYTHON_ENV_PATH。IDF 会按 venv 校验依赖，而我们没有真 venv，
        // 依赖都放在 PYTHONPATH / .pth 里；设了反而会走进 "venv doesn't exist" 的检查。
        // 离线：不启用组件管理器，也不去网上拉东西
        m["IDF_COMPONENT_MANAGER"] = "0"
        m["IDF_TARGET"] = chip
        m["ESP_IDF_VERSION"] = manifest()?.optString("idfVersion", "5.2.5") ?: "5.2.5"
        // IDF 按 venv 里的 python 校验依赖版本；离线包把依赖放在 PYTHONPATH 里，跳过这个校验
        m["IDF_PYTHON_CHECK_CONSTRAINTS"] = "no"
        m["ESPSTUDIO_SKIP_DEP_CHECK"] = "1"
        // Termux 版 cmake 会读 $PREFIX/include/android/api-level.h
        m["PREFIX"] = cmakeDir().absolutePath
        m["TERM"] = "xterm-256color"
        m.putAll(extra)
        return m
    }

    /**
     * 构建前的平台能力自检：App 进程里到底能不能跑 glibc 工具链。
     *
     * 部分 Android 设备的 app seccomp 过滤器会用 KILL_PROCESS 杀掉
     * set_robust_list —— 而 glibc 在 __pthread_initialize_minimal_internal 里
     * 每次启动都会调它。结果是所有 glibc 二进制一 exec 就 "Bad system call"
     * （SIGSYS，退出码 159），连 `gcc --version` 都过不去。这跟资源包、工程
     * 配置都无关，App 也绕不过去（seccomp 过滤器只能叠加、不能放宽）。
     * 早点探测、把原因说清楚，比让 cmake 抛一堆看不懂的错好。
     *
     * @return null 表示能跑；否则是人类可读的原因
     */
    fun appProcessBlockedReason(): String? {
        val gcc = File(xtensaDir("esp32"), "bin/xtensa-esp32-elf-gcc")
        if (!gcc.isFile) return "缺少 tools/xtensa-esp32-elf/bin/xtensa-esp32-elf-gcc"
        val out = StringBuilder()
        val code = runStreaming(listOf(gcc.absolutePath, "--version"), root, env()) { line ->
            if (out.length < 512) out.append(line).append('\n')
        }
        if (code == 0) return null
        val tail = out.toString().trim()
        return if (code == 159 || tail.contains("Bad system call")) {
            "本设备禁止 App 进程运行 glibc 程序：seccomp 过滤器拦截 set_robust_list" +
                "（glibc 每次启动都会调用），进程直接收到 SIGSYS。\n" +
                "ESP 的交叉工具链是 glibc 二进制，所以 App 内编译在这台设备上不可用。" +
                "资源包和工程都没问题，改用电脑（或 adb）侧 idf.py 可以正常编译。"
        } else {
            "工具链自检失败：gcc --version 退出码 $code" + if (tail.isEmpty()) "" else "\n$tail"
        }
    }

    /** 检查工具链是否真的能跑起来（输出版本号） */
    fun selfCheck(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val probes = listOf(
            "python3" to listOf(pythonExe().absolutePath, "--version"),
            "cmake" to listOf(cmakeExe().absolutePath, "--version"),
            "xtensa-gcc" to listOf(
                File(xtensaDir("esp32"), "bin/xtensa-esp32-elf-gcc").absolutePath,
                "--version",
            ),
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

    /**
     * IDF 会检查 <IDF_TOOLS_PATH>/python_env/<idf>_<py>_env/bin/python 是否存在。
     * 我们不建真实 venv（依赖在 PYTHONPATH 里），只造一个转发到 pack python 的壳。
     */
    fun ensureIdfPythonEnv(): File? {
        ensureDirs()
        val idfVer = manifest()?.optString("idfVersion", "5.2.5") ?: "5.2.5"
        val idfTag = "idf" + idfVer.split(".").take(2).joinToString(".")
        val pyTag = "py" + runCapture(
            listOf(pythonExe().absolutePath, "-c", "import sys;print('%d.%d'%sys.version_info[:2])"),
            root, 15000,
        ).trim().ifBlank { "3.11" }
        val env = File(idfTools, "python_env/${idfTag}_${pyTag}_env")
        val bin = File(env, "bin")
        bin.mkdirs()
        val shim = "#!/system/bin/sh\nexec \"" + pythonExe().absolutePath + "\" \"\$@\"\n"
        for (name in listOf("python", "python3")) {
            val f = File(bin, name)
            if (!f.isFile || f.readText() != shim) f.writeText(shim)
            f.setExecutable(true, false)
        }
        return bin
    }

    /** Termux 二进制有时需要一个可写的 HOME 才能正常工作 */
    fun ensureDirs() {
        home.mkdirs()
        tmp.mkdirs()
        idfTools.mkdirs()
    }
}
