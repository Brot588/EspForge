# ESP Studio

> Android 平板上的 **ESP32 / ESP32-S3 开发环境**：官方 ESP-IDF 工程结构，离线编译，USB 烧录，串口与断点调试。

从 [CodePad](https://github.com/Brot588/CodePad-Android) 派生：编辑体验、文件树、Git、终端、全局搜索都保留，
但**只做 ESP32**——Python / JavaScript / Rust 的运行时、clangd 补全、网页预览全部去掉了。

## 它做什么

| 能力 | 实现 |
| --- | --- |
| 工程生成 | 官方 ESP-IDF 结构（`CMakeLists.txt` + `main/` + `sdkconfig.defaults`），模板：Blink / Hello / Wi-Fi / 空白 |
| 编辑 | Monaco（VS Code 同款内核），多标签、全局搜索、括号匹配 |
| 编译 | 真正的 `idf.py build`：xtensa-esp-elf-gcc + cmake + ninja + python，全部跑在平板上 |
| 烧录 | 自己实现的 esptool ROM loader 协议，走 `UsbManager`（CP210x / CH340 / FTDI / ESP32 原生 USB） |
| 监视 | 串口终端（xterm.js），可切换波特率、复位、进下载模式 |
| 调试 | ESP-IDF 自带的串口 gdbstub：断点 / 单步 / 变量（进行中） |
| 版本控制 | 内置 git（状态 / diff / 暂存 / 提交） |

## 为什么是「单独的资源包」

ESP-IDF 的工具链是 glibc 二进制（aarch64-linux-gnu），Android 是 bionic，两者不兼容；
再加上 IDF 源码本身，压缩后 300–400 MB。所以：

- **APK 只有 ~27 MB**：编辑器、界面、烧录协议、串口
- **资源包**是一个 zip：ESP-IDF + xtensa 工具链 + glibc 运行时 + python + cmake/ninja
- 首次使用从「⋮ → ESP-IDF 资源包」导入，之后完全离线

glibc 工具链怎么在 bionic 上跑、资源包为什么这么设计，见 [docs/DESIGN.md](docs/DESIGN.md)。

## 构建

```powershell
# 用 dsh-androidforge 插件自带的工具链（JDK17 + SDK35 + Gradle 8.10.2）
$env:CODEPAD_TOOLCHAIN = "D:\DSH\plugins\dsh-androidforge\toolchain"
pwsh -File .\build.ps1
```

或者自己准备 JDK 17 + Android SDK 35：

```powershell
$env:JAVA_HOME   = "C:\path\to\jdk17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 资源包

`pack/` 下有生成脚本，产物是 `esp-idf-<ver>-arm64.zip`：

```
esp-idf/                 ESP-IDF 源码（裁剪）
tools/xtensa-esp-elf/    glibc 交叉工具链（原始二进制，不改）
tools/cmake/             bionic 的 cmake / ninja / make
tools/python/            bionic 的 python3
tools/python-site/       kconfiglib / pyyaml / esptool …
glibc/                   aarch64 glibc 运行时
manifest.json
```

打包步骤见 `pack/BUILD.md`。

## 限制

- 仅 **arm64-v8a**，`targetSdk 28`（为了能在私有目录 exec 二进制，代价是不能上 Google Play）
- 只支持 **ESP32 / ESP32-S3**
- 没有 JTAG 调试（非 root 的 App 无法直接访问 usbdevfs），断点调试走 ESP-IDF 的 UART gdbstub
- 平板横屏体验最佳

## 许可证

GPL-3.0（沿用 CodePad）。ESP-IDF、GCC 工具链、CMake、Python 等第三方组件的许可见 `THIRD_PARTY_NOTICES.md`。
