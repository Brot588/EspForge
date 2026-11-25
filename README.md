# EspForge — Android 平板上的 ESP32 IDE

> 官方 ESP-IDF 工程结构 + 平板本地编译 + USB 烧录 + 串口调试。
> 从 CodePad 派生；本文件同时是**交接文档**（换新会话时先读这里）。

- 工程目录：`D:\DSH\espstudio`
- 包名：`com.espforge.ide`　应用名：`EspForge`　版本：`0.1`
- 设备：HUAWEI BTK-W00（MatePad），Android 12，arm64-v8a，adb 序列号 `7VXYD24309200097`

---

## 一、现在能用的 / 不能用的

| 功能 | 状态 | 说明 |
|---|---|---|
| 编辑器 / 资源管理器 / Git / 搜索 | ✅ 可用 | Monaco，顶栏搜索框 + 浮层结果 |
| 工程生成 | ✅ 可用 | 官方 IDF 结构（`CMakeLists.txt` + `main/` + `sdkconfig.defaults`） |
| **平板本地编译 ESP32 固件** | ⚠️ **只在命令行验证通过** | 见下方「已验证」，App 里的 ▶ 还不能用 |
| **App 内「构建」按钮** | ❌ 不通 | 6 项修补还没写进 `Toolchain`（见第四节） |
| 终端面板 | ❌ 黑屏无提示符 | PTY 从未启动（见第五节） |
| 烧录 / 串口 | ⚠️ 代码已写，未真机验证 | `serial/EspFlasher.kt` 自己实现 esptool ROM 协议 |

### 已验证（真机，命令行 `run-as` 执行）

用 `com.espforge.ide` 自己的 uid，在 App 私有目录里完整跑通了官方流程：

```
idf.py build  →  exit=0，FAILED 数=0
build/blink.bin   207,456 字节
build/blink.elf 2,835,516 字节
```

前置条件（也是踩过的坑）：见第四节 6 项修补 + 第五节的环境变量。

---

## 二、工具链与构建环境

构建用的工具链不在仓库里，用 dsh-androidforge 插件自带的那套：

```powershell
$tc = 'D:\DSH\plugins\dsh-androidforge\toolchain'   # jdk17 / android-sdk / gradle-8.10.2
$env:JAVA_HOME="$tc\jdk17"; $env:ANDROID_HOME="$tc\android-sdk"; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME="$tc\gradle-home"; $env:ANDROID_USER_HOME="$tc\android-user"
$env:TEMP="$tc\tmp"; $env:TMP=$env:TEMP          # 必须，否则 gradle/adb 起不来
& "$tc\gradle-8.10.2\bin\gradle.bat" -p D:\DSH\espstudio assembleDebug --no-daemon --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 27 MB），另存 `dist\EspForge-0.1-arm64.apk`。

**adb 的坑**：adb 默认往 `%TEMP%\adb.log` 写日志，那个文件被沙箱 ACL 锁住会 `Permission denied`，
daemon 起不来。每次用 adb 前设 `$env:TEMP='D:\DSH\espstudio\.adbtmp'`。
5. 装机：`adb install -r app\build\outputs\apk\debug\app-debug.apk`

---

## 三、资源包（不进 APK）

`pack\esp-idf-5.2.5-arm64.zip`　**343.9 MiB**　sha256 `66a39432767dbc7131c81194d4f6871960dae392fef00f2166613398168a4f13`

```
esp-idf/                  ESP-IDF v5.2.5（裁掉 .git/docs/examples）
tools/xtensa-esp32-elf/   官方 per-chip 交叉工具链 12.2.0（原始二进制，未改）
tools/xtensa-esp32s3-elf/
tools/cmake/              bionic cmake 4.5 + ninja 1.13 + make，含 api-level.h
tools/python/             bionic python 3.14.10（Termux 构建）
tools/python-site/        esptool 4.14 / kconfiglib / pyyaml / pyelftools …
glibc/                    aarch64 glibc 运行时（Debian bookworm）
manifest.json
```

设备上的位置：`/data/user/0/com.espforge.ide/files/pack`（当前是 tar 侧载的 1.1 GB 解压版）。

重建脚本在 `pack/scripts/`：`build-pack-525.py`（可续跑）、`verify-pack-525.py`、`make-docs-525.py`。

### 关键设计：glibc 工具链怎么在 bionic 上跑

工具链是 glibc 二进制，Android 是 bionic，直接 exec 会找不到 `/lib/ld-linux-aarch64.so.1`。

- **不要**对工具链二进制做 `patchelf`（会破坏 ELF 段布局，`cc1` 段错误）
- 做法：`native/execshim.c`（NDK 编译，7.9 KB，产物在 `app/src/main/assets/execshim`）
  —— 一个原生 ELF 垫片，从 `/proc/self/exe` 向上找 `glibc/lib/ld-linux-aarch64.so.1` 定位 pack 根，
  然后 `execv(loader, [--library-path <glibc/lib> <自身>.real 原参数])`
- `Toolchain.wrapGlibc()` 把每个 ELF 可执行文件改名为 `*.real`，原位置放这个垫片
- **必须用 ELF 垫片，不能用 shell 脚本**：ninja 用 `posix_spawn` 直接 exec，脚本形式会 `code=127`
- macOS/Linux 侧不得改工具链二进制；wrapper 由 App 在解压后生成

---

## 四、必须落地的 6 项修补（这是「App 里构建不通」的唯一原因）

这些修补目前**只手工打在设备和 staging 上**，没进 App 代码。要写进 `run/Toolchain.kt`，
在资源包解压/就绪时幂等执行：

| # | 修补 | 参考脚本 | 为什么 |
|---|---|---|---|
| 1 | `tools/cmake/bin/{ninja,cmake,make}` 里的 `/data/data/com.termux/files/usr/bin/sh` → `/system/bin/sh`（等长替换 + NUL 填充） | `pack/scripts/patch-termux-shell.py` | Termux 版 ninja 把 shell 硬编码成 Termux 路径，导致每条命令 `127` |
| 2 | `tools/python-site/serial/tools/list_ports_posix.py`：给 `sys.platform=='android'` 加分支返回 `[]` | `patch-pyserial-android.py` | 平台未知时 pyserial 直接 `raise ImportError`，esptool 一 import 就炸 |
| 3 | `tools/python/lib/python3.*/site-packages/espforge_pack.pth` 写入 `$P/tools/python-site` | — | `idf.py` 给子进程的环境会丢 `PYTHONPATH`，改用 `.pth` 不依赖环境变量 |
| 4 | `esp-idf/tools/cmake/gdbinit.cmake`：`__generate_gdbinit()` 开头，若 `$ENV{ESP_ROM_ELF_DIR}` 为空直接 `return()` | `patch-gdbinit.py` | 包里没有 rom-elfs，`file(TO_CMAKE_PATH)` 参数不足会中断 configure |
| 5 | 工程模板 `sdkconfig.defaults` 加 `CONFIG_MBEDTLS_CERTIFICATE_BUNDLE=n` | `fs/Templates.kt` | Termux 的 `cryptography` 需要 `_cffi_backend`（包里没有），关掉证书包生成就够了 |
| 6 | `Toolchain.env()` **删掉 `IDF_PYTHON_ENV_PATH`** | `run/Toolchain.kt` | 子代理明确要求；IDF 会按 venv 校验依赖 |

另外：`Toolchain.isReady()` 依赖 pack 根目录的 `.ok-1` 标记。手工 tar 解压的包没有这个标记，
会导致 App 认为「没装资源包」而直接弹提示 —— `wrapGlibc()` 成功后要写这个 marker。

---

## 五、构建时正确的环境变量

```sh
P=<pack 根>                       # /data/user/0/com.espforge.ide/files/pack
APP=/data/user/0/com.espforge.ide
IDF_PATH=$P/esp-idf
IDF_TOOLS_PATH=$APP/files/idf-tools
PYTHONHOME=$P/tools/python        # Termux 前缀写死，必须覆盖
PYTHONPATH=$P/tools/python-site   # 配合第 3 项 .pth 更稳
PATH=$P/tools/python/bin:$P/tools/xtensa-esp32-elf/bin:$P/tools/cmake/bin:/system/bin
LD_LIBRARY_PATH=$P/tools/python/lib:$P/tools/cmake/lib     # 只给 bionic 程序；glibc 垫片里会 unset
HOME=$APP/files/home
TMPDIR=$APP/cache/tmp
IDF_COMPONENT_MANAGER=0
IDF_PYTHON_CHECK_CONSTRAINTS=no
ESPSTUDIO_SKIP_DEP_CHECK=1        # 对应 idf_tools.py 的补丁
IDF_TARGET=esp32
PREFIX=$P/tools/cmake             # Termux 版 cmake 靠它读 include/android/api-level.h
```

**不要设 `IDF_PYTHON_ENV_PATH`**。

`idf_tools.py` 的三个补丁在 `pack/scripts/patch-idf-android.py`：Android 平台别名、
跳过依赖检查（`ESPSTUDIO_SKIP_DEP_CHECK`）、单个扩展导入失败不致命。

---

## 六、终端为什么是黑屏（未修）

日志实测：

```
I EspForge: createTerminalWebView: 创建 WebView   ← WebView 建了
I EspForge: onTerminalReady: terminalReady=true   ← 前端就绪
（没有 ensureTerminal / startSession 的任何日志）  ← PTY 从未启动
```

`xterm` 起来了、光标在闪，但从来没启动 PTY，所以一个字节都没有。

下一步：把 `startSession()` 直接放到 `onTerminalReady()` 的同一层调用（`startSession` 本身安全：
前端未就绪时输出会进 `pendingTerminal` 缓存、就绪后回放），并打日志 `pty fd=… pid=…`；
失败时在终端里显示原因而不是留一片黑。

---

## 七、UI 约定（别改坏）

- 键盘：`windowSoftInputMode=adjustNothing`（**代码里 `MainActivity.onCreate` 也有一行，改 manifest 不够**），
  内容列**不要**加 `imePadding()`。键盘直接盖住底部面板，不挤压编辑区。
- 底部 Dock **永远保持挂载**：不要按 `searchOpen`/键盘把高度收 0、更不要从组合里摘掉，
  否则终端 WebView 反复 attach，点「终端」标签直接崩。
- 终端 WebView 只在「终端」页挂载（其它页挂载会盖住构建/烧录面板，`AndroidView` 是原生层压不住）。
- 顶栏：图标统一灰色剪影，当前激活项变绿。搜索框是顶栏内联的 300dp 圆角长条（靠右贴图标），
  结果用 `Popup` 浮层（左对齐、零动画、14dp 圆角），不要用 `DropdownMenu`（有入场动画会瞬间盖住输入框）。

---

## 八、代码地图

```
app/src/main/java/com/espforge/ide/
├─ IdeController.kt   状态中枢：工程/文件/构建/串口/烧录/调试/Git
├─ Ui.kt              Compose 界面（顶栏、资源管理器、编辑器、底部 Dock：终端/构建/烧录/串口/调试/Git）
├─ MainActivity.kt    WebView 创建、资源包文件选择器、窗口软键盘策略
├─ run/
│   ├─ Toolchain.kt   资源包导入、glibc 垫片生成、IDF 环境变量、进程执行 ★主要待改
│   ├─ GdbClient.kt   GDB RSP 客户端（调试用，未接入）
│   └─ DwarfInfo.kt   DWARF 解析（未接入）
├─ serial/
│   ├─ UsbSerial.kt   UsbManager + usb-serial-for-android，DTR/RTS 复位时序
│   └─ EspFlasher.kt  esptool ROM loader 协议（SLIP / FLASH_BEGIN|DATA|END）
├─ fs/{Projects,Templates,Workspace,SearchService}.kt
└─ term/{Pty,TerminalSession}.kt
native/execshim.c     glibc 入口垫片（NDK: aarch64-linux-android26-clang -O2 -fPIE -pie）
pack/scripts/         资源包构建 + 各类补丁脚本
docs/DESIGN.md        设计说明（为什么不用 proot/patchelf/JTAG 等）
```

---

## 九、下一步（按顺序，别跳）

1. 把第四节 6 项修补 + `.ok-1` 标记做进 `Toolchain`，然后**在平板点 ▶ 构建**，截图构建面板出 `blink.bin`
2. 修终端：`onTerminalReady` 里直接起 PTY，截图看到 `esp32>` 之类的提示符
3. 烧录：用 `build/flasher_args.json` + `EspFlasher` 真机烧一次，串口看到日志
4. gdbstub 断点调试（复用 `GdbClient` + `DwarfInfo`）
5. 重新打资源包 zip（当前那个 344 MB 的还不含第四节 6 项修补）

---

## 十、常用 adb 命令

```powershell
$env:TEMP='D:\DSH\espstudio\.adbtmp'; $env:TMP=$env:TEMP
$adb='D:\DSH\plugins\dsh-androidforge\toolchain\android-sdk\platform-tools\adb.exe'

& $adb devices
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.espforge.ide
& $adb shell am start -n com.espforge.ide/.MainActivity
& $adb shell "run-as com.espforge.ide sh -c 'ls files/pack'"        # 看 App 私有目录
& $adb logcat -d -s EspForge                                        # 只看自己的日志
& $adb shell screencap -p /sdcard/s.png; & $adb pull /sdcard/s.png $env:TEMP\s.png
& $adb shell input tap <x> <y>                                      # 屏幕实际 2200x1440
```

坐标换算：截图预览宽 1596 → 实际 2200，比例 1.378。顶栏搜索图标实际位置约 `(1428, 121)`。

---

## 十一、许可证与来源

GPL-3.0（沿用 CodePad）。ESP-IDF、GCC 工具链、CMake、Python 等第三方组件许可见
`THIRD_PARTY_NOTICES.md`。原始 CodePad 项目：https://github.com/Brot588/CodePad-Android
