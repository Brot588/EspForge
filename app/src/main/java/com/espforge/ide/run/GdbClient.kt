package com.espforge.ide.run

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

/**
 * 极简 GDB Remote Serial Protocol 客户端（够用版）：
 * qSupported / ? / Z0 / z0 / c / s / g / m / k
 */
class GdbClient(private val socket: Socket) {

    private val input = BufferedInputStream(socket.getInputStream())
    private val out: OutputStream = socket.getOutputStream()

    private fun checksum(s: String): Int {
        var sum = 0
        for (ch in s) sum = (sum + ch.code) and 0xff
        return sum
    }

    fun send(command: String): String {
        val packet = "$$command#%02x".format(checksum(command))
        out.write(packet.toByteArray(Charsets.US_ASCII))
        out.flush()
        return readPacket()
    }

    private fun readPacket(): String {
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("gdbserver 连接已关闭")
            when (c) {
                '+'.code -> continue
                '-'.code -> continue
                '$'.code -> {
                    val sb = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) throw IOException("gdbserver 连接已关闭")
                        if (b == '#'.code) break
                        sb.append(b.toChar())
                    }
                    input.read()
                    input.read()
                    out.write('+'.code)
                    out.flush()
                    return sb.toString()
                }
                else -> continue
            }
        }
    }

    /** 读全部通用寄存器（g 包，RLE 解码后前 34 个 8 字节） */
    fun readRegisters(): LongArray? {
        val raw = send("g")
        if (raw.isEmpty() || raw[0] == 'E') return null
        val hex = decodeRle(raw)
        val count = minOf(34, hex.length / 16)
        if (count == 0) return null
        val regs = LongArray(count)
        for (i in 0 until count) {
            regs[i] = parseLittleEndian(hex.substring(i * 16, (i + 1) * 16))
        }
        return regs
    }

    fun readMemory(addr: Long, len: Int): ByteArray? {
        if (len <= 0) return null
        val reply = send("m%x,%x".format(addr, len))
        if (reply.isEmpty() || reply[0] == 'E') return null
        val hex = decodeRle(reply)
        val n = minOf(len, hex.length / 2)
        if (n <= 0) return null
        val out = ByteArray(n)
        for (i in 0 until n) {
            val b = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = b.toByte()
        }
        return out
    }

    fun readLong(addr: Long): Long? {
        val bytes = readMemory(addr, 8) ?: return null
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[i].toLong() and 0xff) shl (8 * i))
        return v
    }
    /** 读 PC：gdbserver 的 g 包会做 RLE 压缩，先解码再取第 33 个 8 字节寄存器 */
    fun readPc(): Long {
        val raw = send("g")
        if (raw.isEmpty() || raw[0] == 'E') return -1
        val hex = decodeRle(raw)
        if (hex.length < 33 * 16) {
            android.util.Log.w("CodePadRSP", "g too short: " + hex.length)
            return -1
        }
        return parseLittleEndian(hex.substring(32 * 16, 33 * 16))
    }

    /** GDB remote 的游程编码：c*<n> 表示把 c 重复 (n-29) 次 */
    private fun decodeRle(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '*' && sb.isNotEmpty() && i + 1 < text.length) {
                val count = text[i + 1].code - 29
                val last = sb[sb.length - 1]
                repeat(count.coerceAtLeast(0)) { sb.append(last) }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun parseLittleEndian(hex: String): Long {
        var value = 0L
        var i = 0
        var byteIndex = 0
        while (i + 1 < hex.length && byteIndex < 8) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: return -1
            value = value or (b.toLong() shl (8 * byteIndex))
            i += 2
            byteIndex++
        }
        return if (byteIndex == 0) -1 else value
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }
}
