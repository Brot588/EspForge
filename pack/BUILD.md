# ESP-IDF v5.2.5 离线资源包（Android arm64 平板）

为 Android 平板上的 ESP32 IDE 提供**解压即用**的编译环境：ESP-IDF v5.2.5 源码 + 官方 per-chip
xtensa 工具链（glibc/aarch64）+ bionic 版 cmake/ninja/make + bionic CPython + 纯 Python 依赖
+ aarch64 glibc 运行时。

> **为什么是 5.2.5**：IDF 从 5.3 起把 xtensa 工具链统一成 `xtensa-esp-elf`，`xtensa-esp32-elf-gcc`
> 这些 per-chip 名字变成 Rust 写的调度器，靠 `/proc/self/exe` 判断自己是哪个工具；经
> `ld-linux-aarch64.so.1` 启动时该路径是 loader，必然断言失败
> （`Called tool must have pattern "xtensa-esp*-elf-*"`）。5.2.5 仍是普通 gcc/binutils，
> 可以被 loader wrapper 直接拉起（已在真机验证）。

## 1. 产物

| 项目 | 值 |
| --- | --- |
| 文件名 | `esp-idf-5.2.5-arm64.zip` |
| 字节数 | 360624747 |
| 大小 | 343.9 MiB (360.6 MB) |
| SHA-256 | `4cbb75c1a137505af64e91edc83c172e7cd3d578219932a7d892f700d3c2431a` |
| zip 条目数 | 24351 |
| 构建时间 | 2026-11-25T23:50:13+0800 |

## 2. 体积构成（解压后 / zip 内压缩后）

| 顶层目录 | 解压后 | zip 内 | 占比 | 文件数 |
| --- | ---: | ---: | ---: | ---: |
| `tools/xtensa-esp32s3-elf` | 388.4 MiB | 118.2 MiB | 34.9% | 1905 |
| `tools/xtensa-esp32-elf` | 384.3 MiB | 117.9 MiB | 34.8% | 1944 |
| `esp-idf` | 284.5 MiB | 57.7 MiB | 17.0% | 15375 |
| `tools/python` | 50.1 MiB | 19.7 MiB | 5.8% | 763 |
| `tools/cmake` | 32.6 MiB | 13.4 MiB | 4.0% | 1832 |
| `tools/python-site` | 33.7 MiB | 10.2 MiB | 3.0% | 2519 |
| `glibc` | 5.0 MiB | 1.9 MiB | 0.6% | 12 |
| `manifest.json` | 0.0 MiB | 0.0 MiB | 0.0% | 1 |
| **合计** | **1178.8 MiB** | **339.0 MiB** | 100% | 24351 |

解压后总计 **1178.8 MiB**，24351 个文件；zip 实际大小 **343.9 MiB**。

> **关于 300MB 目标**：当前 zip 为 343.9 MiB（360.6 MB），超出 300MB 目标约 43 MiB。
> 两个 per-chip 工具链合计占压缩后 236 MiB（约 69%），且无法安全再裁：
> `libexec/gcc/*/cc1`、`cc1plus`、`collect2`、`lto1` 是编译器本体；sysroot 里的 `no-rtti` 多库
> 变体是 IDF 默认配置（`CONFIG_COMPILER_CXX_RTTI=n` 会加 `-fno-rtti`）**必需**的；`esp32-psram`
> 变体是开启 PSRAM 的板子（WROVER 等）必需。删任何一个都会让默认工程链接失败，因此保持原样。

## 3. 验证结果

| # | 检查项 | 结果 | 关键数字 |
| --- | --- | --- | --- |
| 1 | submodule 完整性 | PASS | mbedtls/mbedtls/library 171 个文件；esp_phy/lib 47 个文件；bluedroid/api 18 个文件 |
| 2 | 工具链 ELF | PASS | ELF 64-bit LSB AArch64, EXEC；ELF 64-bit LSB AArch64, EXEC |
|  |  |  | PT_INTERP 均为 `/lib/ld-linux-aarch64.so.1`；ELF 可执行文件合计 **92**（esp32 46 / esp32s3 46） |
| 3 | bionic python | PASS | ELF 64-bit LSB AArch64, DYN，interp `/system/bin/linker64`；DT_NEEDED（libandroid-support.so, libpython3.14.so, libc.so）全部命中 `tools/python/lib`，`libc.so`/`libm.so`/`libdl.so` 由 Android 提供；75 个 lib-dynload 扩展依赖同样全部命中 |
| 4 | glibc loader | PASS | `glibc/lib/ld-linux-aarch64.so.1` ELF 64-bit LSB AArch64, DYN；共 12 个库 / 5.0 MiB |
| 5 | glibc 闭包 | PASS | 扫描 116 个工具链 ELF，DT_NEEDED 全部落在 `glibc/lib/`；缺失：无 |
| 6 | cmake/ninja/make | PASS | 三者均 aarch64 bionic；`libarchive` 的 libiconv/libxml2.so.16/liblzma.so.5、cmake/ninja 的 libc++_shared.so 与 cmake 的 libz.so.1 全部命中；`include/android/api-level.h` = `#define __ANDROID_API__ 28` |
| 7 | 二进制未被修改 | PASS | 按发布 tarball 逐字节 sha256 比对：esp32 1914 个、esp32s3 1875 个全部一致，0 处不一致 |
| 8 | IDF 补丁 | PASS | platform-alias=True，skip-dep-check=True，extension-tolerant=True |

补充（在 Windows 主机上用 CPython **3.14.0**，与包内 Termux python 同 minor 版本验证）：
`python-site` 里 22 个模块全部 import 成功、3 个跳过（不属于 5.2.5 依赖集：`future`、
`esp_idf_diag`、`esp_idf_nvs_partition_gen`），且 `idf_py_actions.tools`（`idf.py` 启动时
导入的模块，含 `from esp_idf_monitor import get_ansi_converter`、`import yaml`）可正常加载。
`cryptography` / `psutil` 是 bionic 的 `.abi3.so`，无法在 Windows 上跑，只做了架构与依赖检查。

## 4. 打过的补丁

### 4.1 IDF（`scripts/patch-idf-android.py`）

| # | 文件 / 位置 | 修改 | 结果 |
| --- | --- | --- | --- |
| 1 | `tools/idf_tools.py` `Platforms.get()` | 平台别名含 android 时映射为 `linux-arm64` / `linux64` | True |
| 2 | `tools/idf_tools.py` `action_check_python_dependencies()` | `ESPSTUDIO_SKIP_DEP_CHECK=1` 时立即返回 | True |
| 3 | `tools/idf.py` 扩展加载循环 | 单个 `*_ext` 导入失败只告警 | True |

**与 5.5.x 的差异（重要）**：5.2.5 的锚点和 5.5 不一样，原脚本的两个锚点在那里匹配不到，
我按 5.2.5 的实际代码新增了两组锚点：

- 平台补丁：5.2.5 的 `Platforms.get()` 没有 `hasn't been added yet` 的 raise，改为在
  `Platforms.detect_linux_arm_platform(...)` 之前兜底。**注意**：5.2.5 的
  `PLATFORM_FROM_NAME` 里本来就有 `'Linux-aarch64' -> linux-arm64`（`idf_tools.py:157`），
  所以 Android 上 `CURRENT_PLATFORM` 已经能正确解析；这条补丁是防御性的（Android 若上报
  `android-aarch64` 之类别名也能通过）。
- 依赖检查补丁：5.2.5 的 `action_check_python_dependencies()` 没有 docstring，且函数第一句就会
  `get_python_env_path()` + `fatal("... doesn't exist! Please run the install script...")`。
  这个补丁**必需**：5.2.5 的 `idf.py check_environment()` 会无条件 `subprocess` 调用
  `idf_tools.py check-python-dependencies`，失败就 `SystemExit(1)`，而离线包没有 venv。
  App 必须设置 `ESPSTUDIO_SKIP_DEP_CHECK=1`。

### 4.2 cmake（原 cmake.zip 不完整，真机上撞出来的）

- 补库到 `tools/cmake/lib/`：`libiconv.so`、`libcharset.so`、`libxml2.so`、`libxml2.so.16`、
  `libxml2.so.16.1.4`、`liblzma.so`、`liblzma.so.5`、`liblzma.so.5.8.4`（来源 `build/termux-libs`，
  脚本 `scripts/fetch_termux_libs.py`），另加 `libc++_shared.so`（cmake、ninja 都要）与
  `libz.so.1`（Android 只提供 `libz.so`）。
- 补头文件 `tools/cmake/include/android/api-level.h`，内容一行 `#define __ANDROID_API__ 28`。
  `share/cmake-4.5/Modules/CMakeDetermineSystem.cmake:38` 会 `file(READ $ENV{PREFIX}/include/android/api-level.h)`
  并用正则 `#define __ANDROID_API__ ([0-9]+)` 取值 —— 所以 App 必须把 **`PREFIX` 指向
  `tools/cmake`**。

## 5. python-site 内容

按 `esp-idf/tools/requirements/requirements.core.txt` 解析（16 项），排除 `idf-component-manager`
（离线不用；`IDF_COMPONENT_MANAGER=0` 时不会被导入）：

```
```

安装方式（每包一行）：

```
```

- **`esptool>=4.6,<5`**：5.2.5 会调用 `esptool.get_default_connected_device()`，esptool 5.x 已移除
  该 API，因此明确钉在 4.x（与 5.2.5 发布时的版本线一致）。
- **`pyyaml`**：PyPI 已不再发布 `py3-none-any` 纯 wheel（只剩平台 wheel + sdist），直接从 sdist
  取纯 Python 实现（`lib/yaml`，17 个文件），不编译 C 扩展；`yaml.safe_load` 可用。
- **`cryptography` / `psutil`**：来自 Termux deb（bionic、`.abi3.so`），不是 PyPI。
- **`cffi` 未安装**：Termux 的 cryptography 50.0.2 是全 Rust 绑定（`_rust.abi3.so`），代码里没有
  `import cffi`（唯一出现是注释），且 Termux 仓库本身也没有 cffi 包；其 METADATA 虽声明
  `cffi>=2.0.0`，但运行期不需要。
- `python-site` 里还包含这些包带进来的传递依赖：

```

```

## 6. App 端解压 / 运行注意点

1. **wrapper 必须原地替换**（不只是放进 PATH）：`gcc` 会用绝对路径 exec
   `libexec/gcc/xtensa-esp32-elf/12.2.0/cc1`、`cc1plus`、`collect2`、`lto1`，这些位置同样必须
   是能跑的入口。做法：把真实 ELF 改名为 `<name>.real`，在同名位置写 0755 的 shell 脚本：

   ```
   #!/system/bin/sh
   exec "$BASE/glibc/lib/ld-linux-aarch64.so.1" --library-path "$BASE/glibc/lib" \
        "$BASE/tools/xtensa-esp32-elf/<相对路径>/<name>.real" "$@"
   ```

   `bin/` 下的 28 个也可以原地替换（或做成 wrapper 目录 + PATH 前置，两者都行），但要保证
   gcc 通过 `COMPILER_PATH`/`-B` 找到的 `as`、`ld`、`collect2` 也是 wrapper。

   **需要 wrapper 的 ELF 总数：92**（仅 glibc 工具链）。

| 目录 | ELF 数 |
| --- | ---: |
| `tools/xtensa-esp32-elf/bin` | 28 |
| `tools/xtensa-esp32-elf/libexec/gcc/xtensa-esp32-elf/12.2.0` | 6 |
| `tools/xtensa-esp32-elf/libexec/gcc/xtensa-esp32-elf/12.2.0/install-tools` | 1 |
| `tools/xtensa-esp32-elf/libexec/gcc/xtensa-esp32-elf/12.2.0/plugin` | 1 |
| `tools/xtensa-esp32-elf/xtensa-esp32-elf/bin` | 10 |
| `tools/xtensa-esp32s3-elf/bin` | 28 |
| `tools/xtensa-esp32s3-elf/libexec/gcc/xtensa-esp32s3-elf/12.2.0` | 6 |
| `tools/xtensa-esp32s3-elf/libexec/gcc/xtensa-esp32s3-elf/12.2.0/install-tools` | 1 |
| `tools/xtensa-esp32s3-elf/libexec/gcc/xtensa-esp32s3-elf/12.2.0/plugin` | 1 |
| `tools/xtensa-esp32s3-elf/xtensa-esp32s3-elf/bin` | 10 |

   `tools/cmake/bin`（3 个：cmake/ninja/make）和 `tools/python/bin`（3 个：python/python3/
   python3.14）是 **bionic** 程序，**不需要** ld.so wrapper，直接 exec 即可。

2. **不要 patchelf**：所有工具链 ELF 与发布 tarball 逐字节一致（见第 3 节检查 7）。改 PT_INTERP
   会破坏段布局，`cc1` 会段错误。
3. **环境变量**（缺一不可）：

   ```
   IDF_PATH=esp-idf
   IDF_COMPONENT_MANAGER=0
   ESPSTUDIO_SKIP_DEP_CHECK=1
   IDF_PYTHON_ENV_PATH=
   PREFIX=tools/cmake
   PYTHONHOME=tools/python
   PYTHONPATH=tools/python-site
   LD_LIBRARY_PATH=tools/python/lib:tools/cmake/lib
   GLIBC_LIBRARY_PATH=glibc/lib
   ```

   - `PYTHONHOME` 指向 `tools/python`：Termux python 编译前缀是 `/data/data/com.termux/files/usr`，
     必须用 `PYTHONHOME` 重定位；否则找不到 stdlib。
   - `PREFIX` 指向 `tools/cmake`（`CMakeDetermineSystem.cmake` 读它下面的 `include/android/api-level.h`）。
   - `ESPSTUDIO_SKIP_DEP_CHECK=1` 让 `idf.py` 跳过 venv 依赖校验（见 4.1）。
   - `IDF_COMPONENT_MANAGER=0` 让 `idf.py` 不 import 组件管理器。
   - `LD_LIBRARY_PATH` 必须含 `tools/python/lib`（libpython3.14.so 等）与 `tools/cmake/lib`。
4. **解压后 chmod**：`ZipInputStream` 不还原权限。所有 ELF 与 `*.sh` 需 `0755`（zip 里已写入
   unix 权限位，但不要依赖）。
5. **路径不要有空格**：IDF 的 CMake 拼参数时对空格处理不完善。
6. `glibc/lib` 里都是真实文件副本（不是符号链接），因为 zip 不承载符号链接。
7. `tools/python/bin/python` / `python3` / `python3.14` 是同一份二进制的三份副本（无符号链接），
   方便 IDF 的 venv/解释器检查。

## 7. 与计划/规范的偏离

- **没有 `tools/esp32ulp-elf`**：新结构清单里没列，因此 ULP（esp32 ULP-FSM/RISC-V 协处理器）
  相关构建不可用；普通 esp32/esp32s3 工程不受影响。
- **裁剪了 `share/doc`、`share/man`、`share/info`、`bin/*gdb*`、`bin/*lto-dump*`**：不含 gdb
  （不用 JTAG）；`lto-dump` 只是转储 LTO 中间表示的工具，编译链接用的是 `libexec/.../lto1`
  （保留）。**`.a`/`.o` 一个都没删。**
- **python 是 3.14.10**：Termux 滚动仓库目前只提供 3.14（没有 3.12/3.13）。IDF 5.2.5 官方
  支持 3.8–3.12，因此对纯 Python 依赖做了 3.14 全量 import 验证（第 3 节末），但如果真机遇到
  3.14 标准库行为差异（例如 `distutils` 移除后的边角），需要换 python。
- **`esptool` 钉在 4.x**（见第 5 节）。
- **IDF 补丁的 5.2 锚点**是我按实际代码新写的（见 4.1）。

## 8. 复现步骤

```powershell
cd D:\DSH\espstudio\pack
# 1) 缓存下载到 pack\dl（可重复执行、断点续传）
python scripts\fetch_pkgs.py debian
python scripts\fetch_pkgs.py termux
python scripts\fetch_termux_libs.py libiconv libxml2 liblzma
curl.exe -L --retry 3 -C - -o dl\esp-idf-v5.2.5.zip https://dl.espressif.com/github_assets/espressif/esp-idf/releases/download/v5.2.5/esp-idf-v5.2.5.zip
curl.exe -L --retry 3 -C - -o dl\xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz https://dl.espressif.com/github_assets/espressif/crosstool-NG/releases/download/esp-12.2.0_20230208/xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz
curl.exe -L --retry 3 -C - -o dl\xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz https://dl.espressif.com/github_assets/espressif/crosstool-NG/releases/download/esp-12.2.0_20230208/xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz

# 2) 分阶段构建（可中断续跑，锁目录 build\locks525）
foreach ($s in "idf","patch","toolchains","cmake","python","site","glibc") {
  python scripts\build-pack-525.py --stage $s
}

# 3) 验证 + 出包
python scripts\verify-pack-525.py
python scripts\build-pack-525.py --stage zip
python scripts\verify-pack-525.py --zip   # 回填 sha256 / 大小到 pack\manifest.json
python scripts\make-docs-525.py          # 生成本文件
```

依赖：Windows + Python 3.11+、`curl.exe`、能访问 `dl.espressif.com` / `mirrors.tuna.tsinghua.edu.cn` /
PyPI 镜像。全流程不需要 Android 设备，也不修改 `app/` 下任何源码。

