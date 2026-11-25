# EspForge 设计说明

Android 平板上的 ESP32 开发环境：**官方 ESP-IDF 工程结构 + 离线构建 + USB 烧录 + 串口调试**。
从 CodePad 派生（保留了编辑器 / 文件树 / Git / 终端 / 全局搜索 / 设置这些跟语言无关的部分）。

---

## 1. 为什么这件事比「带个编译器」难

ESP-IDF 要跑起来需要四样东西，而 Android 一个都不白给：

| 需要 | Android 的现实 | 本项目的解法 |
| --- | --- | --- |
| `idf.py`（Python） | 没有系统 Python，Chaquopy 的解释器是进程内的，没有 `python3` 可执行文件 | 打包 Termux 的 **bionic python3**（配 `PYTHONHOME` + `PYTHONPATH`） |
| `cmake` / `ninja` / `make` | 没有；官方 aarch64 包是 glibc 的 | 用 CodePad 已验证的 **Termux bionic 版**（`assets/cmake.zip`） |
| `xtensa-esp-elf-gcc` | Espressif 只发 **glibc**（aarch64-linux-gnu）二进制 | 见 §2 |
| 串口 `/dev/ttyUSB0` | 非 root 拿不到 tty 节点 | `UsbManager` + usb-serial-for-android，自己实现 esptool 协议 |

### 2. 核心难题：glibc 工具链跑在 bionic 上

`xtensa-esp-elf-gcc` 的 ELF 头里写着 `PT_INTERP = /lib/ld-linux-aarch64.so.1`，Android 没有这个文件，
内核直接 `ENOENT`（Termux 社区至今没有 bionic 版 xtensa 工具链，见 termux-packages#26987）。

两条错的路和一个对的：

- ❌ **patchelf 改 PT_INTERP**：有明确报告会把 ELF 段布局改坏，`cc1` 直接段错误（Flipper 社区踩过）。
  本项目资源包里的二进制因此**保持原样未修改**。
- ❌ **proot + Debian rootfs**：能跑，但慢（ptrace 拦截每个 syscall）、占地 1GB+、且要打包 proot。
- ✅ **自带 glibc + shell wrapper**：资源包带一份 aarch64 glibc，解压后由 App **为每个 ELF 可执行文件**
  生成一个 wrapper：

  ```sh
  #!/system/bin/sh
  unset LD_LIBRARY_PATH
  exec <pack>/glibc/lib/ld-linux-aarch64.so.1 \
       --library-path <pack>/glibc/lib:<tc>/lib \
       <原文件>.real "$@"
  ```

  `gcc → cc1 / as / collect2 / ld` 这条链子上每个可执行文件都要包，缺一个就是
  `cannot execute cc1`。wrapper 必须 `unset LD_LIBRARY_PATH`，否则 glibc 会去加载 bionic 的 libc。

生成逻辑在 `run/Toolchain.wrapGlibc()`，幂等（已包过就跳过），解压资源包后自动执行。

### 3. 资源包为什么不在 APK 里

| 组成 | 压缩后 |
| --- | --- |
| ESP-IDF v5.5 源码（裁剪） | ~90 MB |
| xtensa-esp-elf（aarch64，含 gcc/cc1/as/ld） | ~177 MB |
| glibc 运行时 + python + cmake/ninja + site-packages | ~60 MB |

合计 300～400 MB，塞进 APK 会让安装和更新都变得不可接受。所以做成
**独立 zip + 应用内导入**（系统文件选择器 → 解压到私有目录 → 生成 wrapper）。
换芯片包、清缓存、备份都只是一份文件的事。

### 4. 烧录：为什么自己写 esptool 协议

`esptool.py` 依赖 pyserial，而 pyserial 要有 tty 节点。Android 给的是 `UsbDeviceConnection`
的文件描述符，套不进 `serial.Serial`。

`serial/EspFlasher.kt` 因此直接实现 ROM loader 协议：

```
RESET(DTR/RTS) → SYNC(0x08) → [CHANGE_BAUD(0x0F)] → FLASH_BEGIN(0x02)
   → FLASH_DATA(0x03) × N（每块 1KB + 16 字节块头）→ FLASH_END(0x04) → 硬复位
```

- 帧格式：SLIP（0xC0 包边界，0xDB 转义）
- 每包 `[0x00, op, len_lo, len_hi, chk..]`，校验是数据按 uint32 小端逐字 XOR
- 烧什么、烧到哪：读 `build/flasher_args.json`（IDF 自己生成的，包含 bootloader / 分区表 / app 的偏移）
- 先只实现「ROM loader、不传 stub」：115200/460800 下 1MB 约 30–120 秒，换来的是少 500 行不可测代码。
  （stub 能快 5–10 倍，等真机验证过 ROM 路径再加）

### 5. 调试：走 ESP-IDF 自带的串口 gdbstub，不是 JTAG

JTAG/OpenOCD 需要直接访问 `usbdevfs`，非 root 的普通 App 做不到——这条路**在非 root 平板上不存在**。

可用的真实断点调试是 ESP-IDF 的 `esp_gdbstub`（Kconfig：`CONFIG_ESP_SYSTEM_GDBSTUB_RUNTIME=y`，
模板里的 `sdkconfig.defaults` 已经打开），它通过 **UART0** 跑 GDB Remote Serial Protocol。于是：

```
App 的 GDB RSP 客户端（复用 CodePad 的 GdbClient + DwarfInfo）
        │  USB 串口
        ▼
   ESP32 上的 esp_gdbstub  →  断点 / 单步 / 寄存器 / 内存
        │
        ▼
   .elf 里的 DWARF  →  行号、变量名、结构体字段
```

代价：gdbstub 运行时会占用串口（日志看不到），所以「串口监视」和「调试」是两个互斥模式。

---

## 6. 代码地图

```
app/src/main/java/com/espstudio/ide/
├─ IdeController.kt     状态中枢：工程/文件/构建/串口/烧录/调试/Git
├─ Ui.kt                Compose 界面（编辑器 + 底栏：终端/构建/烧录/串口/调试/Git）
├─ run/
│   ├─ Toolchain.kt      资源包导入、glibc wrapper、IDF 环境变量、进程执行
│   ├─ GdbClient.kt      GDB RSP 客户端（调试）
│   └─ DwarfInfo.kt      DWARF 解析（变量/行号）
├─ serial/
│   ├─ UsbSerial.kt      UsbManager + usb-serial-for-android 封装、DTR/RTS 复位时序
│   └─ EspFlasher.kt     esptool ROM loader 协议（SLIP + FLASH_*）
├─ fs/
│   ├─ Projects.kt       工程 = 目录
│   └─ Templates.kt      ESP-IDF 工程模板（blink / hello / wifi / blank）
└─ editor/Bridges.kt     Monaco / xterm 的 JS ↔ Kotlin 桥
```

## 7. 当前进度与下一步

- [x] 从 CodePad 拆出独立工程，去掉 Python/JS/Rust/TCC/clangd/网页预览
- [x] 工程模板换成官方 ESP-IDF 结构
- [x] 资源包导入 + glibc wrapper 生成
- [x] `idf.py build` 驱动 + 实时日志
- [x] USB 串口 + esptool 烧录协议 + 串口监视
- [ ] **真机验证**：构建产物、烧录、串口日志
- [ ] gdbstub 断点调试面板（复用 GdbClient / DwarfInfo）
- [ ] panic 地址符号化（`xtensa-esp-elf-addr2line`）

## 8. 已知风险

1. **glibc 在 bionic 上的实际行为**只能在真机上验证（静态检查无法证明）。
2. **Termux python 的 `sys.prefix`** 依赖 `PYTHONHOME` 覆盖，若 IDF 有硬编码路径假设可能踩坑。
3. **ROM loader 的块大小/超时**在不同芯片（ESP32 vs S3）上有差异，需要真机跑通后再调。
4. `targetSdk 28` 是为了能在私有目录 `exec` 二进制（同 CodePad 的取舍），代价是不能上架 Google Play。
