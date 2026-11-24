package com.codepad.ide.run

import android.util.Log
import com.codepad.ide.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * C 调试器：clang 编译（-g）→ gdbserver 启动 → RSP 客户端控制。
 * 断点地址来自 DWARF 行表，暂停位置用 PC 反查行号。
 */
object CDebugger {

    private var server: Process? = null
    private var client: GdbClient? = null
    private var worker: Thread? = null
    private val commands = LinkedBlockingQueue<String>()

    @Volatile
    private var running = false

    private var table: List<Pair<Long, Int>> = emptyList()
    private var dwarf: DwarfInfo? = null
    private var lastCommand = "c"
    private var srcLines: List<String> = emptyList()
    private var srcPath = ""

    fun isRunning(): Boolean = running

    fun start(
        source: File,
        breakpoints: List<Int>,
        rust: Boolean,
        publish: (String) -> Unit,
        log: (String) -> Unit,
    ) {
        stop()
        commands.clear()
        running = true
        worker = Thread({
            try {
                runSession(source, breakpoints, rust, publish, log)
            } catch (t: Throwable) {
                log("C 调试失败: " + (t.message ?: t.javaClass.simpleName))
                publish("{\"type\":\"exit\",\"code\":1}")
            } finally {
                running = false
                cleanup()
            }
        }, "c-debug").also { it.start() }
    }

    private fun runSession(
        source: File,
        breakpoints: List<Int>,
        rust: Boolean,
        publish: (String) -> Unit,
        log: (String) -> Unit,
    ) {
        if (!CToolchain.isReady()) {
            log("正在准备 C 工具链…")
            if (!CToolchain.ensure { msg -> log(msg) }) {
                publish("{\"type\":\"exit\",\"code\":1}")
                return
            }
        }
        val buildDir = File(App.ctx.filesDir, "build").apply { mkdirs() }
        var exe = File(buildDir, source.nameWithoutExtension + if (rust) ".rs.dbg" else ".debug")
        var ok: Boolean
        val cmakeRoot = if (rust) null else CToolchain.cmakeRootOf(source)
        if (cmakeRoot != null) {
            log("CMake 构建 ${cmakeRoot.name} …")
            val built = CToolchain.buildCmake(cmakeRoot) { line -> log(line) }
            ok = built != null
            if (built != null) exe = built
        } else {
            log("编译 ${source.name} …")
            val result = when {
                rust -> compileRust(source, exe)
                source.name.endsWith(".cpp") || source.name.endsWith(".cc") || source.name.endsWith(".cxx") ->
                    CToolchain.compileCpp(source, exe)
                else -> CToolchain.compile(source, exe)
            }
            ok = result.first
            if (result.second.isNotBlank()) log(result.second.trimEnd())
        }
        if (!ok) {
            log("编译失败")
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }

        table = CToolchain.lineTable(exe, source.name)
        srcLines = try { source.readLines() } catch (_: Throwable) { emptyList() }
        srcPath = source.absolutePath
        dwarf = CToolchain.debugInfo(exe)
        log("调试信息：${table.size} 行 / ${dwarf?.all()?.size ?: 0} 个函数")

        val port = 50000 + (System.nanoTime() % 10000).toInt()
        log("启动 gdbserver :$port")
        val pb = ProcessBuilder(CToolchain.binary("gdbserver").absolutePath, ":$port", exe.absolutePath)
        pb.directory(source.parentFile)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = CToolchain.libPath()
        pb.environment()["HOME"] = App.ctx.filesDir.absolutePath
        pb.environment()["SHELL"] = "/system/bin/sh"
        val process = pb.start()
        server = process

        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.i("CodePadC", "gdbserver: $line")
                }
            } catch (_: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }

        Thread.sleep(300)
        val socket = Socket()
        var connected = false
        for (attempt in 0 until 40) {
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                connected = true
                break
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        if (!connected) {
            log("连接 gdbserver 失败")
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }
        val gdb = GdbClient(socket)
        client = gdb

        Log.i("CodePadRSP", "qSupported -> " + gdb.send("qSupported:multiprocess+;swbreak+;hwbreak+"))
        val stopReply = gdb.send("?")
        Log.i("CodePadRSP", "? -> " + stopReply)
        val pid = Regex("thread:p([0-9a-fA-F]+)\\.").find(stopReply)?.groupValues?.get(1)?.toIntOrNull(16)
        val base = pid?.let { loadBase(it, exe.absolutePath) } ?: 0L
        Log.i("CodePadRSP", "pid=" + pid + " base=0x" + java.lang.Long.toHexString(base))

        val addresses = ArrayList<Long>()
        for (bp in breakpoints) {
            val a = CToolchain.addressForLine(table, bp)
            if (a != null) addresses.add(base + a) else log("第 $bp 行没有可执行代码，已跳过")
        }

        for (addr in addresses) {
            val reply = gdb.send("Z0,%x,4".format(addr))
            Log.i("CodePadRSP", "Z0 0x%x -> %s".format(addr, reply))
            if (reply != "OK") log("断点设置失败 @0x%x".format(addr))
        }

        log("已进入调试")
        var command = "c"
        lastCommand = "c"
        var lastPc = -1L
        var lastPublished = -1
        while (running) {
            // 停在断点上时，直接 c/s 会再次撞到同一个断点：
            // 先临时撤掉该断点、单步越过、再装回去（GDB 的做法）
            if (lastPc > 0) {
                val hits = addresses.filter { it == lastPc }
                if (hits.isNotEmpty()) {
                    hits.forEach { gdb.send("z0,%x,4".format(it)) }
                    gdb.send("s")
                    hits.forEach { gdb.send("Z0,%x,4".format(it)) }
                }
            }
            val reply = gdb.send(command)
            Log.i("CodePadRSP", cmd_reply_log(command, reply))
            if (reply.startsWith("W") || reply.startsWith("X")) {
                val code = reply.removePrefix("W").substring(0, 2).toIntOrNull(16) ?: 0
                log("程序退出，退出码 $code")
                publish("{\"type\":\"exit\",\"code\":$code}")
                return
            }
            if (!reply.startsWith("T") && !reply.startsWith("S")) {
                log("未知停止包: $reply")
                return
            }
            var pc = gdb.readPc()
            lastPc = pc
            var line = CToolchain.lineForAddress(table, pc - base) ?: 0
            if (command == "s") {
                var guard = 0
                // 一直走到「新的、有实际内容」的源码行（跳过 } 这种块边界）
                while (running && guard++ < 300) {
                    val meaningless = line == lastPublished || isTrivialLine(line)
                    if (!meaningless) break
                    val r2 = gdb.send("s")
                    if (r2.startsWith("W") || r2.startsWith("X")) {
                        val code = r2.removePrefix("W").substring(0, 2).toIntOrNull(16) ?: 0
                        publish("{\"type\":\"exit\",\"code\":$code}")
                        return
                    }
                    pc = gdb.readPc()
                    line = CToolchain.lineForAddress(table, pc - base) ?: line
                }
            }
            lastPublished = line
            Log.i("CodePadRSP", "pc=0x" + java.lang.Long.toHexString(pc) + " line=" + line)
            val reason = if (lastCommand == "c") "断点" else "单步"
            val regs = gdb.readRegisters()
            val vars = if (regs != null) evalVars(gdb, regs, dwarf?.at(pc - base), base) else emptyList()
            val frames = if (regs != null) walkFrames(gdb, regs, base, dwarf, source) else emptyList()
            publish(buildPause(source, line, vars, frames, reason))
            val next = commands.poll(600, TimeUnit.SECONDS) ?: "k"
            if (next == "k") {
                try {
                    gdb.send("k")
                } catch (_: Throwable) {
                }
                publish("{\"type\":\"exit\",\"code\":0}")
                return
            }
            lastCommand = next
            command = next
        }
    }

    private fun cmd_reply_log(command: String, reply: String): String = "$command -> $reply"

    /** PIE 可执行文件的加载基址：/proc/pid/maps 中 offset 为 0 的那段 */
    private fun loadBase(pid: Int, exePath: String): Long {
        return try {
            val lines = File("/proc/$pid/maps").readLines()
            var best = -1L
            for (line in lines) {
                if (!line.contains(exePath)) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) continue
                val start = parts[0].substringBefore("-").toLongOrNull(16) ?: continue
                val offset = parts[2].toLongOrNull(16) ?: continue
                if (offset == 0L) return start
                if (best < 0 || start < best) best = start
            }
            if (best > 0) best else 0L
        } catch (t: Throwable) {
            0L
        }
    }

    private fun isTrivialLine(line: Int): Boolean {
        if (line <= 0) return true
        val text = srcLines.getOrNull(line - 1)?.trim() ?: return false
        return text.isEmpty() || text == "}" || text == "{" || text == "};" || text == "};" || text == ")"
    }

    private fun compileRust(source: File, exe: File): Pair<Boolean, String> {
        val cmd = listOf(
            File(CToolchain.root, "bin/rustc").absolutePath,
            "--edition", "2021",
            "-g", "-C", "debuginfo=2", "-C", "opt-level=0",
            "-C", "linker=" + CToolchain.rustLinkerScript().absolutePath,
            "-o", exe.absolutePath,
            source.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(source.parentFile)
        pb.redirectErrorStream(true)
        pb.environment().putAll(CToolchain.toolEnv())
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return (code == 0) to text
    }

    private fun buildPause(
        source: File,
        line: Int,
        vars: List<Pair<String, String>>,
        frames: List<Triple<String, String, Int>>,
        reason: String,
    ): String {
        val varsJson = JSONArray()
        vars.forEach { (n, v) -> varsJson.put(JSONObject().put("name", n).put("value", v)) }
        val stackJson = JSONArray()
        frames.forEach { (func, file, ln) ->
            stackJson.put(JSONObject().put("func", func).put("file", file).put("line", ln))
        }
        return JSONObject()
            .put("type", "pause")
            .put("reason", reason)
            .put("line", line)
            .put("file", source.absolutePath)
            .put("vars", varsJson)
            .put("stack", stackJson)
            .toString()
    }

    private fun evalVars(
        gdb: GdbClient,
        regs: LongArray,
        func: DwarfFunc?,
        base: Long,
    ): List<Pair<String, String>> {
        if (func == null) return emptyList()
        val frameBase = regs.getOrNull(func.frameBaseReg) ?: 0L
        val out = ArrayList<Pair<String, String>>()
        for (v in func.vars) {
            try {
                if (v.location.kind == "reg") {
                    val value = regs.getOrNull(v.location.reg) ?: continue
                    out.add(v.name to java.lang.Long.toString(value))
                    continue
                }
                val addr = when (v.location.kind) {
                    "fbreg" -> frameBase + v.location.offset
                    "breg" -> (regs.getOrNull(v.location.reg) ?: continue) + v.location.offset
                    "addr" -> v.location.addr + base
                    else -> continue
                }

                val text = if (v.typeOffset != 0L) {
                    formatTyped(gdb, v.typeOffset, addr, 0)
                } else {
                    val size = sizeOf(v.typeName)
                    val bytes = gdb.readMemory(addr, size) ?: continue
                    formatValue(v.typeName, bytes)
                }
                out.add(v.name to text)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    // -------- 结构化取值（结构体 / String / Vec / 数组） --------

    private fun formatTyped(gdb: GdbClient, typeOffset: Long, addr: Long, depth: Int): String {
        val info = dwarf ?: return "?"
        if (depth > 3) return "…"
        val t = info.typeAt(typeOffset) ?: return "?"
        return when (t.tag) {
            "DW_TAG_base_type" -> {
                val size = if (t.byteSize > 0) t.byteSize else 8
                val bytes = gdb.readMemory(addr, size) ?: return "?"
                formatValue(t.name, bytes)
            }
            "DW_TAG_pointer_type", "DW_TAG_reference_type" -> {
                val v = gdb.readLong(addr) ?: return "?"
                "0x" + java.lang.Long.toHexString(v)
            }
            "DW_TAG_typedef", "DW_TAG_const_type" -> formatTyped(gdb, t.ref, addr, depth)
            "DW_TAG_structure_type", "DW_TAG_class_type", "DW_TAG_union_type" -> {
                val vecType = info.typeAt(t.members.firstOrNull { it.name == "vec" }?.typeOffset ?: 0)
                when {
                    t.name.endsWith("String") && vecType != null -> {
                        val bytes = readVecBytes(gdb, info, vecType, addr + t.members.first { it.name == "vec" }.offset)
                        if (bytes == null) "?" else "\"" + escapeText(String(bytes, Charsets.UTF_8)) + "\""
                    }
                    t.name == "&str" || t.name.endsWith("::&str") -> readStrSlice(gdb, info, t, addr)
                    t.name.contains("Vec<") && !t.name.contains("RawVec") ->
                        readRustVec(gdb, info, t, addr)
                    t.name.startsWith("Option<") && t.members.isEmpty() -> "None"
                    else -> formatStruct(gdb, info, t, addr, depth)
                }
            }
            "DW_TAG_array_type" -> {
                val elem = info.typeAt(t.ref) ?: return "?"
                val elemSize = if (elem.byteSize > 0) elem.byteSize else 1
                val n = t.count.coerceIn(0, 16)
                val parts = ArrayList<String>()
                for (i in 0 until n) {
                    parts.add(formatTyped(gdb, t.ref, addr + i * elemSize, depth + 1))
                }
                "[" + parts.joinToString(", ") + "]"
            }
            else -> "?"
        }
    }

    private fun formatStruct(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long, depth: Int): String {
        if (t.members.isEmpty()) return "{}"
        val parts = ArrayList<String>()
        for (m in t.members.take(8)) {
            val v = if (m.typeOffset != 0L) {
                formatTyped(gdb, m.typeOffset, addr + m.offset, depth + 1)
            } else {
                val raw = gdb.readLong(addr + m.offset) ?: 0L
                "0x" + java.lang.Long.toHexString(raw)
            }
            parts.add(m.name + ": " + v)
        }
        if (t.members.size > 8) parts.add("…")
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun untag(p: Long): Long = p and 0x00FFFFFFFFFFFFFL

    /** 在结构体（含嵌套成员）里按名字找字段偏移 */
    private fun findMemberOffset(info: DwarfInfo, t: DwarfType, name: String, base: Int = 0, depth: Int = 0): Int? {
        for (m in t.members) {
            if (m.name == name) return base + m.offset
            if (depth < 4) {
                val sub = info.typeAt(m.typeOffset)
                if (sub != null) {
                    val r = findMemberOffset(info, sub, name, base + m.offset, depth + 1)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    /** 通过 DWARF 成员名字定位 Vec 的 ptr/len（Rust 会重排字段，不能按固定偏移读） */
    private fun vecPtrLen(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): Pair<Long, Long>? {
        val lenOff = findMemberOffset(info, t, "len") ?: return null
        val ptrOff = findMemberOffset(info, t, "ptr") ?: return null
        val ptr = gdb.readLong(addr + ptrOff) ?: return null
        val len = gdb.readLong(addr + lenOff) ?: return null
        return untag(ptr) to len
    }

    private fun readVecBytes(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): ByteArray? {
        val (ptr, len) = vecPtrLen(gdb, info, t, addr) ?: return null
        if (ptr == 0L || len <= 0 || len > 65536) return ByteArray(0)
        return gdb.readMemory(ptr, len.toInt())
    }

    private fun readStrSlice(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): String {
        val ptrOff = findMemberOffset(info, t, "data_ptr") ?: findMemberOffset(info, t, "ptr") ?: return "?"
        val lenOff = findMemberOffset(info, t, "length") ?: findMemberOffset(info, t, "len") ?: return "?"
        val ptr = untag(gdb.readLong(addr + ptrOff) ?: return "?")
        val len = gdb.readLong(addr + lenOff) ?: return "?"
        if (ptr == 0L || len <= 0 || len > 65536) return "\"\""
        val bytes = gdb.readMemory(ptr, len.toInt()) ?: return "\"\""
        return "\"" + escapeText(String(bytes, Charsets.UTF_8)) + "\""
    }

    private fun readRustVec(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): String {
        val (ptr, len) = vecPtrLen(gdb, info, t, addr) ?: return formatStruct(gdb, info, t, addr, 0)
        if (ptr == 0L || len <= 0) return "[]"
        val inner = t.name.substringAfter('<').substringBefore(',').substringBefore('>').trim()
        val elem = info.baseTypeByName(inner)
            ?: return "[len=" + len + "]"
        val n = minOf(len, 8L).toInt()
        val parts = ArrayList<String>()
        for (i in 0 until n) {
            val b = gdb.readMemory(ptr + i.toLong() * elem.byteSize, elem.byteSize) ?: break
            parts.add(formatValue(elem.name, b))
        }
        if (len > n) parts.add("…")
        return "[" + parts.joinToString(", ") + "]"
    }

    private fun escapeText(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
    private fun sizeOf(type: String): Int {
        if (type.endsWith("*")) return 8
        Regex("""\[\d+]""").find(type)?.let {
            return it.value.drop(1).dropLast(1).toIntOrNull() ?: 8
        }
        // Rust 基本类型
        when (type) {
            "i8", "u8", "bool", "char" -> return 1
            "i16", "u16" -> return 2
            "i32", "u32", "f32" -> return 4
            "i64", "u64", "isize", "usize", "f64" -> return 8
        }
        if (type.startsWith("&") || type.startsWith("*")) return 8
        return when {
            type.startsWith("double") || type.startsWith("long") -> 8
            type.startsWith("float") -> 4
            type.startsWith("short") -> 2
            type.startsWith("char") -> 1
            else -> 4
        }
    }

    private fun formatValue(type: String, bytes: ByteArray): String {
        var value = 0L
        for (i in 0 until minOf(bytes.size, 8)) {
            value = value or ((bytes[i].toLong() and 0xff) shl (8 * i))
        }
        if (type.endsWith("*")) return "0x" + java.lang.Long.toHexString(value)
        if (type.startsWith("char") && type.contains("[")) {
            val text = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
            return "\"" + text.replace("\n", "\\n").replace("\"", "\\\"") + "\""
        }
        when (type) {
            "f32" -> return java.lang.Float.intBitsToFloat(value.toInt()).toString()
            "f64" -> return java.lang.Double.longBitsToDouble(value).toString()
            "bool" -> return if (value != 0L) "true" else "false"
        }
        if (type.startsWith("u")) return java.lang.Long.toUnsignedString(value)
        if (type.startsWith("double")) return java.lang.Double.longBitsToDouble(value).toString()
        if (type.startsWith("float")) return java.lang.Float.intBitsToFloat(value.toInt()).toString()
        return when (bytes.size) {
            1 -> bytes[0].toString()
            2 -> value.toShort().toString()
            4 -> value.toInt().toString()
            else -> value.toString()
        }
    }

    private fun walkFrames(
        gdb: GdbClient,
        regs: LongArray,
        base: Long,
        dwarf: DwarfInfo?,
        source: File,
    ): List<Triple<String, String, Int>> {
        val frames = ArrayList<Triple<String, String, Int>>()
        val pc = regs.getOrNull(32) ?: return frames
        frameOf(pc, base, dwarf, source)?.let { frames.add(it) }
        // 叶子函数可能不建帧指针，x30 里就是调用者的返回地址
        val lr = regs.getOrNull(30) ?: 0L
        if (lr > 0) {
            frameOf(lr, base, dwarf, source)?.let { frames.add(it) }
        }
        var fp = regs.getOrNull(29) ?: return frames
        var guard = 0
        while (guard++ < 12 && fp > 0x1000) {
            val ret = gdb.readLong(fp + 8) ?: break
            val nextFp = gdb.readLong(fp) ?: break
            if (ret == 0L) break
            // 只保留能落到已知函数里的帧，链条一旦跑偏就停
            val frame = frameOf(ret, base, dwarf, source) ?: break
            frames.add(frame)
            if (nextFp <= fp) break
            fp = nextFp
        }
        return frames
    }

    private fun frameOf(pc: Long, base: Long, dwarf: DwarfInfo?, source: File): Triple<String, String, Int>? {
        val rel = pc - base
        val func = dwarf?.at(rel) ?: return null
        // 只有本文件的函数才去查行号，std 里的帧会落到错误的行上
        val inSource = func.declFile.isEmpty() || func.declFile.endsWith(source.name)
        val line = if (inSource) CToolchain.lineForAddress(table, rel) ?: 0 else 0
        return Triple(func.name, source.name, line)
    }

    fun command(cmd: String) {
        commands.offer(cmd)
    }

    fun stop() {
        running = false
        commands.offer("k")
        client?.close()
        cleanup()
    }

    private fun cleanup() {
        try {
            server?.destroy()
        } catch (_: Throwable) {
        }
        server = null
        client = null
    }
}
