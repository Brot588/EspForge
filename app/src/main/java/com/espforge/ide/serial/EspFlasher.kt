package com.espforge.ide.serial

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ESP32 ROM bootloader 串口协议（esptool 的等价实现）。
 *
 * 为什么不用 esptool.py：它依赖 pyserial，而 Android 上没有 tty 节点，
 * UsbManager 给的是一份 fd，套不进 pyserial 的 Serial 类。所以这里直接用
 * Kotlin 实现 SLIP 帧 + 命令包，走 UsbSerial。
 *
 * 只实现「ROM loader」这条路径（不传 stub）：
 *   SYNC → (可选 CHANGE_BAUD) → FLASH_BEGIN / FLASH_DATA / FLASH_END
 * 传 stub 能快 5~10 倍，但需要额外的 MEM_BEGIN 上传和 stub 自己的命令集，
 * 先用 ROM 路径保证能用；115200 下 1MB 大约 2 分钟。
 */
class EspFlasher(
    private val serial: SerialConnection,
    private val log: (String) -> Unit = {},
) {

    companion object {
        private const val OP_FLASH_BEGIN = 0x02
        private const val OP_FLASH_DATA = 0x03
        private const val OP_FLASH_END = 0x04
        private const val OP_SYNC = 0x08
        private const val OP_CHANGE_BAUD = 0x0F
        private const val OP_ERASE_FLASH = 0xD0

        private const val SLIP_END = 0xC0
        private const val SLIP_ESC = 0xDB
        private const val SLIP_ESC_END = 0xDC
        private const val SLIP_ESC_ESC = 0xDD

        /** ROM loader 每次最多 1KB，这是硬件限制 */
        private const val BLOCK = 0x400
    }

    class FlashError(message: String) : Exception(message)

    // ------------------------------------------------------------- SLIP 编解码

    private fun slipEncode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(SLIP_END)
        for (b in payload) {
            val v = b.toInt() and 0xFF
            when (v) {
                SLIP_END -> {
                    out.write(SLIP_ESC); out.write(SLIP_ESC_END)
                }
                SLIP_ESC -> {
                    out.write(SLIP_ESC); out.write(SLIP_ESC_ESC)
                }
                else -> out.write(v)
            }
        }
        out.write(SLIP_END)
        return out.toByteArray()
    }

    private fun slipDecode(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            val v = raw[i].toInt() and 0xFF
            if (v == SLIP_ESC && i + 1 < raw.size) {
                i++
                when (raw[i].toInt() and 0xFF) {
                    SLIP_ESC_END -> out.write(SLIP_END)
                    SLIP_ESC_ESC -> out.write(SLIP_ESC)
                    else -> out.write(raw[i].toInt())
                }
            } else {
                out.write(v)
            }
            i++
        }
        return out.toByteArray()
    }

    /** 读到一帧完整 SLIP 包（不含两端的 0xC0） */
    private fun readFrame(timeoutMs: Int): ByteArray {
        val buf = ByteArrayOutputStream(64)
        var started = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = serial.read(60)
            if (chunk.isEmpty()) continue
            for (b in chunk) {
                val v = b.toInt() and 0xFF
                if (v == SLIP_END) {
                    if (started && buf.size() > 0) return buf.toByteArray()
                    started = true
                    buf.reset()
                } else if (started) {
                    buf.write(v)
                }
            }
        }
        throw FlashError("等待芯片响应超时")
    }

    // --------------------------------------------------------------- 命令包

    private fun checksum(data: ByteArray): Long {
        var state = 0L
        var i = 0
        while (i + 3 < data.size) {
            val word = (data[i].toLong() and 0xFF) or
                ((data[i + 1].toLong() and 0xFF) shl 8) or
                ((data[i + 2].toLong() and 0xFF) shl 16) or
                ((data[i + 3].toLong() and 0xFF) shl 24)
            state = state xor word
            i += 4
        }
        return state and 0xFFFFFFFFL
    }

    private fun u32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /**
     * 发一条命令并等响应。返回响应里的 payload（前 2 字节是状态，已校验）。
     */
    private fun command(op: Int, data: ByteArray = ByteArray(0), timeoutMs: Int = 5000): ByteArray {
        val chk = checksum(data)
        val header = byteArrayOf(
            0x00,
            op.toByte(),
            (data.size and 0xFF).toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
            (chk and 0xFF).toByte(),
            ((chk shr 8) and 0xFF).toByte(),
            ((chk shr 16) and 0xFF).toByte(),
            ((chk shr 24) and 0xFF).toByte(),
        )
        val packet = slipEncode(concat(header, data))
        if (serial.write(packet) < 0) throw FlashError("串口写入失败")

        val frame = slipDecode(readFrame(timeoutMs))
        if (frame.size < 8) throw FlashError("响应太短（${frame.size} 字节）")
        val dir = frame[0].toInt() and 0xFF
        val respOp = frame[1].toInt() and 0xFF
        val size = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        if (dir != 0x01) throw FlashError("响应方向字节不对：0x%02X".format(dir))
        if (respOp != op) throw FlashError("响应命令不匹配：期望 0x%02X，收到 0x%02X".format(op, respOp))
        if (size <= 0) return ByteArray(0)
        if (frame.size < 8 + size) throw FlashError("响应长度不足")
        val payload = frame.copyOfRange(8, 8 + size)
        if (payload.size >= 2) {
            val status = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
            if (status != 0) throw FlashError("芯片返回错误状态 0x%02X（命令 0x%02X）".format(status, op))
            return payload.copyOfRange(2, payload.size)
        }
        return payload
    }

    // ---------------------------------------------------------------- 握手

    /** 同步：让 ROM loader 认识我们，返回芯片在 loader 里上报的寄存器值 */
    fun sync(attempts: Int = 7): Boolean {
        val payload = concat(byteArrayOf(0x07, 0x07, 0x12, 0x20), ByteArray(32) { 0x55 })
        repeat(attempts) { i ->
            try {
                log(if (i == 0) "同步芯片…" else "重试同步（${i + 1}/$attempts）…")
                serial.write(slipEncode(concat(byteArrayOf(0x00, OP_SYNC.toByte(), 0, 0, 0, 0, 0, 0), payload)))
                val frame = slipDecode(readFrame(3000))
                if (frame.size >= 2 && (frame[1].toInt() and 0xFF) == OP_SYNC) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** 切换波特率，失败就退回原波特率 */
    fun changeBaud(newBaud: Int, oldBaud: Int): Boolean {
        return try {
            log("切换波特率到 $newBaud…")
            command(OP_CHANGE_BAUD, concat(u32(newBaud), u32(oldBaud)), 5000)
            Thread.sleep(50)
            if (!serial.setBaud(newBaud)) return false
            serial.drain()
            val ok = sync(3)
            if (!ok) {
                serial.setBaud(oldBaud)
                serial.drain()
                log("新波特率不通，退回 $oldBaud")
            }
            ok
        } catch (t: Throwable) {
            log("切换波特率失败：${t.message}")
            serial.setBaud(oldBaud)
            false
        }
    }

    // ---------------------------------------------------------------- 擦除

    fun eraseFlash() {
        log("擦除整片 flash（可能要十几秒）…")
        command(OP_ERASE_FLASH, u32(0), 60000)
        log("擦除完成")
    }

    // ---------------------------------------------------------------- 烧录

    private fun flashBegin(offset: Int, size: Int) {
        val blocks = (size + BLOCK - 1) / BLOCK
        val eraseSize = (size + 0xFFF) and 0xFFF.inv()
        command(OP_FLASH_BEGIN, concat(u32(eraseSize), u32(blocks), u32(BLOCK), u32(offset)), 15000)
    }

    private fun flashData(seq: Int, chunk: ByteArray) {
        // 16 字节块头：序号 + 3 个保留 0
        val header = concat(u32(seq), u32(0), u32(0), u32(0))
        val payload = concat(header, chunk)
        command(OP_FLASH_DATA, payload, 15000)
    }

    private fun flashEnd(reboot: Boolean) {
        command(OP_FLASH_END, u32(if (reboot) 0 else 1), 30000)
    }

    /**
     * 烧一个文件。返回写入字节数。
     * 进度回调参数：已写字节 / 总字节
     */
    fun flashFile(offset: Int, data: ByteArray, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val total = data.size
        log("写入 0x%08X  %d 字节".format(offset, total))
        flashBegin(offset, total)
        var seq = 0
        var pos = 0
        while (pos < total) {
            val n = minOf(BLOCK, total - pos)
            val chunk = data.copyOfRange(pos, pos + n)
            // 最后一块不满要补齐，芯片按块校验
            val full = if (n < BLOCK) chunk + ByteArray(BLOCK - n) { 0xFF.toByte() } else chunk
            flashData(seq, full)
            seq++
            pos += n
            onProgress(pos, total)
        }
    }

    // ----------------------------------------------------------- 上层入口

    data class Image(val offset: Int, val file: File)

    /** 解析 idf.py 生成的 build/flasher_args.json */
    fun parseFlasherArgs(projectDir: File): Pair<List<Image>, Map<String, String>> {
        val f = File(projectDir, "build/flasher_args.json")
        if (!f.isFile) throw FlashError("找不到 build/flasher_args.json —— 先构建一次")
        val o = JSONObject(f.readText())
        val files = o.optJSONObject("flash_files") ?: throw FlashError("flasher_args.json 里没有 flash_files")
        val images = ArrayList<Image>()
        val keys = files.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val off = k.removePrefix("0x").toLong(16).toInt()
            val rel = files.getString(k)
            val file = File(projectDir, "build/$rel")
            if (!file.isFile) {
                // 有些 IDF 版本写的是相对 build 目录的路径
                val alt = File(projectDir, rel)
                if (alt.isFile) images.add(Image(off, alt))
                else throw FlashError("固件文件缺失：$rel")
            } else {
                images.add(Image(off, file))
            }
        }
        images.sortBy { it.offset }
        val settings = HashMap<String, String>()
        o.optJSONObject("flash_settings")?.let { s ->
            for (k in listOf("flash_mode", "flash_size", "flash_freq")) {
                if (s.has(k)) settings[k] = s.getString(k)
            }
        }
        return images to settings
    }

    /**
     * 完整流程：复位进 bootloader → 同步 → 烧所有 image → 硬复位。
     */
    fun flashAll(
        images: List<Image>,
        baud: Int = 115200,
        fastBaud: Int = 460800,
        eraseFirst: Boolean = false,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ) {
        val totalBytes = images.sumOf { it.file.length().toInt() }
        log("共 ${images.size} 个文件，$totalBytes 字节")

        serial.resetIntoBootloader()
        if (!sync()) throw FlashError("芯片没有响应。检查：板子是否进了下载模式、USB 线是否只供电、串口芯片驱动是否匹配")
        log("已进入 ROM bootloader")

        if (fastBaud > baud) {
            if (changeBaud(fastBaud, baud)) log("当前波特率 $fastBaud") else log("继续用 $baud")
        }

        if (eraseFirst) eraseFlash()

        var done = 0
        for (img in images) {
            val data = img.file.readBytes()
            val name = img.file.name
            flashFile(img.offset, data) { pos, total ->
                onProgress(name, done + pos, totalBytes.coerceAtLeast(1))
            }
            done += data.size
        }

        log("复位芯片…")
        try {
            flashEnd(reboot = true)
        } catch (_: Throwable) {
        }
        Thread.sleep(120)
        try {
            serial.hardReset()
        } catch (_: Throwable) {
        }
        log("烧录完成")
    }
}
