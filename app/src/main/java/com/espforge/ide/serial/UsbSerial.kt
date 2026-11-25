package com.espforge.ide.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * USB 串口封装。
 *
 * Android 上没有 /dev/ttyUSB0 这种节点，只能走 UsbManager + usb-serial-for-android。
 * ESP32 开发板上常见的芯片：CP210x / CH340 / CH341 / FTDI / 以及 ESP32-S3、S2、C3
 * 自带的 USB-Serial-JTAG（CDC-ACM）。
 */
object UsbSerial {

    const val ACTION_USB_PERMISSION = "com.espforge.ide.USB_PERMISSION"

    data class PortInfo(
        val driver: UsbSerialDriver,
        val portIndex: Int,
    ) {
        val device: UsbDevice get() = driver.device
        val port: UsbSerialPort get() = driver.ports[portIndex]

        fun label(): String {
            val vid = device.vendorId
            val pid = device.productId
            val chip = when {
                vid == 4292 -> "CP210x"
                vid == 6790 -> "CH34x"
                vid == 1027 -> "FTDI"
                vid == 12346 -> "ESP32 原生 USB"
                else -> "USB 串口"
            }
            val name = device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName
            return "$chip · $name (%04X:%04X)".format(vid, pid)
        }
    }

    /** 当前插着的所有串口 */
    fun list(ctx: Context): List<PortInfo> {
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val out = ArrayList<PortInfo>()
        try {
            for (driver in UsbSerialProber.getDefaultProber().findAllDrivers(manager)) {
                for (i in driver.ports.indices) out.add(PortInfo(driver, i))
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /**
     * 申请 USB 权限。返回 true 表示已经有权限，可以直接打开；
     * 否则弹系统授权对话框，结果通过 [onResult] 回到调用方。
     */
    fun requestPermission(ctx: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Throwable) {
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags)
        manager.requestPermission(device, pi)
    }
}

/**
 * 一个已打开的串口。读写都是阻塞的，调用方自己管线程。
 */
class SerialConnection(
    private val ctx: Context,
    private val info: UsbSerial.PortInfo,
) {

    private var port: UsbSerialPort? = null
    private var baud = 115200

    val isOpen: Boolean get() = port?.isOpen == true
    val name: String get() = info.label()

    @Synchronized
    fun open(baudRate: Int = 115200): Boolean {
        close()
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = manager.openDevice(info.device) ?: return false
        val p = info.port
        return try {
            p.open(conn)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.dtr = false
            p.rts = false
            port = p
            baud = baudRate
            true
        } catch (t: Throwable) {
            try {
                p.close()
            } catch (_: Throwable) {
            }
            try {
                conn.close()
            } catch (_: Throwable) {
            }
            false
        }
    }

    @Synchronized
    fun setBaud(baudRate: Int): Boolean {
        val p = port ?: return false
        return try {
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            baud = baudRate
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun currentBaud(): Int = baud

    fun setDtr(on: Boolean) {
        try {
            port?.dtr = on
        } catch (_: Throwable) {
        }
    }

    fun setRts(on: Boolean) {
        try {
            port?.rts = on
        } catch (_: Throwable) {
        }
    }

    /** 写数据；返回写出的字节数，-1 表示失败 */
    @Synchronized
    fun write(data: ByteArray, timeoutMs: Int = 2000): Int {
        val p = port ?: return -1
        return try {
            p.write(data, timeoutMs)
            data.size
        } catch (_: Throwable) {
            -1
        }
    }

    /** 读数据；超时返回 0 字节数组 */
    fun read(timeoutMs: Int = 200): ByteArray {
        val p = port ?: return ByteArray(0)
        val buf = ByteArray(4096)
        return try {
            val n = p.read(buf, timeoutMs)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    /** 清掉接收缓冲里残留的数据 */
    fun drain() {
        var guard = 0
        while (guard++ < 64) {
            val got = read(30)
            if (got.isEmpty()) break
        }
    }

    @Synchronized
    fun close() {
        try {
            port?.close()
        } catch (_: Throwable) {
        }
        port = null
    }

    /**
     * 进入下载模式 —— 和 esptool 的 classic reset 一致：
     *   DTR=False, RTS=True  →  EN 拉低（复位）
     *   DTR=True,  RTS=False →  IO0 拉低，释放复位，芯片进 bootloader
     *   DTR=False            →  IO0 回高
     */
    fun resetIntoBootloader() {
        setDtr(false)
        setRts(true)
        Thread.sleep(100)
        setDtr(true)
        setRts(false)
        Thread.sleep(50)
        setDtr(false)
        Thread.sleep(50)
        drain()
    }

    /** 硬复位，让芯片跑 flash 里的固件 */
    fun hardReset() {
        setDtr(false)
        setRts(true)
        Thread.sleep(100)
        setRts(false)
        Thread.sleep(50)
    }
}
