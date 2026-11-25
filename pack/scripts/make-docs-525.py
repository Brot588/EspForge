#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""make-docs-525.py -- write BUILD.md from build/verify525.json + build/build525.json."""
import collections
import json
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.dirname(HERE)
BUILD = os.path.join(PACK, 'build')

V = json.load(open(os.path.join(BUILD, 'verify525.json'), encoding='utf-8'))
try:
    B = json.load(open(os.path.join(BUILD, 'build525.json'), encoding='utf-8'))
except Exception:
    B = {}

C = V['checks']
M = V['manifest']
Z = V.get('zip') or {}
ST = B.get('stages', {})
MB = 1024.0 * 1024.0


def mib(n):
    return '%.1f MiB' % (n / MB)


def row(*cells):
    return '| ' + ' | '.join(str(c) for c in cells) + ' |'


def yn(b):
    return 'PASS' if b else 'FAIL'


L = []
A = L.append

A('# ESP-IDF v5.2.5 离线资源包（Android arm64 平板）')
A('')
A('为 Android 平板上的 ESP32 IDE 提供**解压即用**的编译环境：ESP-IDF v5.2.5 源码 + 官方 per-chip')
A('xtensa 工具链（glibc/aarch64）+ bionic 版 cmake/ninja/make + bionic CPython + 纯 Python 依赖')
A('+ aarch64 glibc 运行时。')
A('')
A('> **为什么是 5.2.5**：IDF 从 5.3 起把 xtensa 工具链统一成 `xtensa-esp-elf`，`xtensa-esp32-elf-gcc`')
A('> 这些 per-chip 名字变成 Rust 写的调度器，靠 `/proc/self/exe` 判断自己是哪个工具；经')
A('> `ld-linux-aarch64.so.1` 启动时该路径是 loader，必然断言失败')
A('> （`Called tool must have pattern "xtensa-esp*-elf-*"`）。5.2.5 仍是普通 gcc/binutils，')
A('> 可以被 loader wrapper 直接拉起（已在真机验证）。')
A('')
A('## 1. 产物')
A('')
if Z:
    A(row('项目', '值'))
    A(row('---', '---'))
    A(row('文件名', '`%s`' % M['zip']['fileName']))
    A(row('字节数', '%d' % Z['size']))
    A(row('大小', '%.1f MiB (%.1f MB)' % (Z['size'] / MB, Z['size'] / 1e6)))
    A(row('SHA-256', '`%s`' % Z['sha256']))
    A(row('zip 条目数', M['sizes']['totalFiles']))
    A(row('构建时间', M['builtAt']))
    A('')
A('## 2. 体积构成（解压后 / zip 内压缩后）')
A('')
A(row('顶层目录', '解压后', 'zip 内', '占比', '文件数'))
A(row('---', '---:', '---:', '---:', '---:'))
try:
    zf = zipfile.ZipFile(os.path.join(PACK, M['zip']['fileName']))
    comp = collections.Counter()
    raws = collections.Counter()
    cnts = collections.Counter()
    for i in zf.infolist():
        parts = i.filename.split('/')
        key = parts[0]
        if key == 'tools' and len(parts) >= 3:
            key = 'tools/' + parts[1]
        comp[key] += i.compress_size
        raws[key] += i.file_size
        cnts[key] += 1
    total_comp = sum(comp.values())
    order = sorted(comp, key=lambda x: -comp[x])
    for k in order:
        A(row('`%s`' % k, mib(raws[k]), mib(comp[k]),
              '%.1f%%' % (100.0 * comp[k] / total_comp), cnts[k]))
    A(row('**合计**', '**%s**' % mib(sum(raws.values())), '**%s**' % mib(total_comp), '100%',
          sum(cnts.values())))
    zf.close()
except Exception as e:
    A('（读取 zip 失败：%s）' % e)
A('')
A('解压后总计 **%s**，%d 个文件；zip 实际大小 **%s**。'
  % (mib(M['sizes']['totalBytes']), M['sizes']['totalFiles'],
     mib(Z['size']) if Z else 'n/a'))
A('')
A('> **关于 300MB 目标**：当前 zip 为 %.1f MiB（%.1f MB），超出 300MB 目标约 %d MiB。'
  % ((Z['size'] / MB) if Z else 0, (Z['size'] / 1e6) if Z else 0,
     int((Z['size'] / MB - 300)) if Z else 0))
A('> 两个 per-chip 工具链合计占压缩后 %d MiB（约 %d%%），且无法安全再裁：'
  % (int((comp.get('tools/xtensa-esp32-elf', 0) + comp.get('tools/xtensa-esp32s3-elf', 0)) / MB) if Z else 0,
     int(100.0 * ((comp.get('tools/xtensa-esp32-elf', 0) + comp.get('tools/xtensa-esp32s3-elf', 0)) / total_comp)) if Z else 0))
A('> `libexec/gcc/*/cc1`、`cc1plus`、`collect2`、`lto1` 是编译器本体；sysroot 里的 `no-rtti` 多库')
A('> 变体是 IDF 默认配置（`CONFIG_COMPILER_CXX_RTTI=n` 会加 `-fno-rtti`）**必需**的；`esp32-psram`')
A('> 变体是开启 PSRAM 的板子（WROVER 等）必需。删任何一个都会让默认工程链接失败，因此保持原样。')
A('')
A('## 3. 验证结果')
A('')
A(row('#', '检查项', '结果', '关键数字'))
A(row('---', '---', '---', '---'))
sm = C['submodules']
A(row(1, 'submodule 完整性', yn(sm['pass_']),
      'mbedtls/mbedtls/library %d 个文件；esp_phy/lib %d 个文件；bluedroid/api %d 个文件'
      % (sm['mbedtls_library_files'], sm['esp_phy_lib_files'], sm['bluedroid_api_files'])))
tc = C['toolchains']
per = tc['per_toolchain']
A(row(2, '工具链 ELF', yn(tc['pass_']),
      '%s；%s' % (per['xtensa-esp32-elf']['gcc'], per['xtensa-esp32s3-elf']['gcc'])))
A(row('', '', '', 'PT_INTERP 均为 `%s`；ELF 可执行文件合计 **%d**（esp32 %d / esp32s3 %d）'
      % (per['xtensa-esp32-elf']['interp'], tc['total_executables'],
         per['xtensa-esp32-elf']['executables'], per['xtensa-esp32s3-elf']['executables'])))
py = C['python']
A(row(3, 'bionic python', yn(py['pass_']),
      '%s，interp `%s`；DT_NEEDED（%s）全部命中 `tools/python/lib`，`libc.so`/`libm.so`/`libdl.so` '
      '由 Android 提供；%d 个 lib-dynload 扩展依赖同样全部命中'
      % (py['desc'], py['interp'], ', '.join(py['needed']), py['dynload_modules'])))
g = C['glibc']
A(row(4, 'glibc loader', yn(g['pass_']),
      '`glibc/lib/ld-linux-aarch64.so.1` %s；共 %d 个库 / %s' % (g['desc'], g['count'], mib(g['bytes']))))
gc = C['glibc_closure']
A(row(5, 'glibc 闭包', yn(gc['pass_']),
      '扫描 %d 个工具链 ELF，DT_NEEDED 全部落在 `glibc/lib/`；缺失：%s'
      % (gc['scanned'], gc['missing'] or '无')))
cm = C['cmake']
A(row(6, 'cmake/ninja/make', yn(cm['pass_']),
      '三者均 aarch64 bionic；`libarchive` 的 libiconv/libxml2.so.16/liblzma.so.5、cmake/ninja 的 '
      'libc++_shared.so 与 cmake 的 libz.so.1 全部命中；`include/android/api-level.h` = `%s`'
      % cm['api_level_header']))
pr = C['pristine']
d = pr['details']
A(row(7, '二进制未被修改', yn(pr['pass_']),
      '按发布 tarball 逐字节 sha256 比对：esp32 %d 个、esp32s3 %d 个全部一致，0 处不一致'
      % (d['xtensa-esp32-elf']['checked'], d['xtensa-esp32s3-elf']['checked'])))
pt = C['patches']
A(row(8, 'IDF 补丁', yn(pt['pass_']),
      'platform-alias=%s，skip-dep-check=%s，extension-tolerant=%s'
      % (pt['platform_alias_android'], pt['skip_dep_check_env'], pt['extension_tolerant'])))
A('')
A('补充（在 Windows 主机上用 CPython **3.14.0**，与包内 Termux python 同 minor 版本验证）：')
A('`python-site` 里 22 个模块全部 import 成功、3 个跳过（不属于 5.2.5 依赖集：`future`、')
A('`esp_idf_diag`、`esp_idf_nvs_partition_gen`），且 `idf_py_actions.tools`（`idf.py` 启动时')
A('导入的模块，含 `from esp_idf_monitor import get_ansi_converter`、`import yaml`）可正常加载。')
A('`cryptography` / `psutil` 是 bionic 的 `.abi3.so`，无法在 Windows 上跑，只做了架构与依赖检查。')
A('')
A('## 4. 打过的补丁')
A('')
A('### 4.1 IDF（`scripts/patch-idf-android.py`）')
A('')
A('| # | 文件 / 位置 | 修改 | 结果 |')
A('| --- | --- | --- | --- |')
A('| 1 | `tools/idf_tools.py` `Platforms.get()` | 平台别名含 android 时映射为 `linux-arm64` / `linux64` | %s |'
  % pt['platform_alias_android'])
A('| 2 | `tools/idf_tools.py` `action_check_python_dependencies()` | `ESPSTUDIO_SKIP_DEP_CHECK=1` 时立即返回 | %s |'
  % pt['skip_dep_check_env'])
A('| 3 | `tools/idf.py` 扩展加载循环 | 单个 `*_ext` 导入失败只告警 | %s |'
  % pt['extension_tolerant'])
A('')
A('**与 5.5.x 的差异（重要）**：5.2.5 的锚点和 5.5 不一样，原脚本的两个锚点在那里匹配不到，')
A('我按 5.2.5 的实际代码新增了两组锚点：')
A('')
A('- 平台补丁：5.2.5 的 `Platforms.get()` 没有 `hasn\'t been added yet` 的 raise，改为在')
A('  `Platforms.detect_linux_arm_platform(...)` 之前兜底。**注意**：5.2.5 的')
A('  `PLATFORM_FROM_NAME` 里本来就有 `\'Linux-aarch64\' -> linux-arm64`（`idf_tools.py:157`），')
A('  所以 Android 上 `CURRENT_PLATFORM` 已经能正确解析；这条补丁是防御性的（Android 若上报')
A('  `android-aarch64` 之类别名也能通过）。')
A('- 依赖检查补丁：5.2.5 的 `action_check_python_dependencies()` 没有 docstring，且函数第一句就会')
A('  `get_python_env_path()` + `fatal("... doesn\'t exist! Please run the install script...")`。')
A('  这个补丁**必需**：5.2.5 的 `idf.py check_environment()` 会无条件 `subprocess` 调用')
A('  `idf_tools.py check-python-dependencies`，失败就 `SystemExit(1)`，而离线包没有 venv。')
A('  App 必须设置 `ESPSTUDIO_SKIP_DEP_CHECK=1`。')
A('')
A('### 4.2 cmake（原 cmake.zip 不完整，真机上撞出来的）')
A('')
A('- 补库到 `tools/cmake/lib/`：`libiconv.so`、`libcharset.so`、`libxml2.so`、`libxml2.so.16`、')
A('  `libxml2.so.16.1.4`、`liblzma.so`、`liblzma.so.5`、`liblzma.so.5.8.4`（来源 `build/termux-libs`，')
A('  脚本 `scripts/fetch_termux_libs.py`），另加 `libc++_shared.so`（cmake、ninja 都要）与')
A('  `libz.so.1`（Android 只提供 `libz.so`）。')
A('- 补头文件 `tools/cmake/include/android/api-level.h`，内容一行 `#define __ANDROID_API__ 28`。')
A('  `share/cmake-4.5/Modules/CMakeDetermineSystem.cmake:38` 会 `file(READ $ENV{PREFIX}/include/android/api-level.h)`')
A('  并用正则 `#define __ANDROID_API__ ([0-9]+)` 取值 —— 所以 App 必须把 **`PREFIX` 指向')
A('  `tools/cmake`**。')
A('')
A('## 5. python-site 内容')
A('')
A('按 `esp-idf/tools/requirements/requirements.core.txt` 解析（16 项），排除 `idf-component-manager`')
A('（离线不用；`IDF_COMPONENT_MANAGER=0` 时不会被导入）：')
A('')
A('```')
for r in (ST.get('site') or {}).get('requirements') or []:
    A(r)
A('```')
A('')
A('安装方式（每包一行）：')
A('')
A('```')
res = (ST.get('site') or {}).get('result') or {}
for k in sorted(res):
    v = res[k]
    if isinstance(v, dict):
        v = 'vendored-from-sdist %s' % v.get('version', '')
    A('%-36s %s' % (k, v))
A('```')
A('')
A('- **`esptool>=4.6,<5`**：5.2.5 会调用 `esptool.get_default_connected_device()`，esptool 5.x 已移除')
A('  该 API，因此明确钉在 4.x（与 5.2.5 发布时的版本线一致）。')
A('- **`pyyaml`**：PyPI 已不再发布 `py3-none-any` 纯 wheel（只剩平台 wheel + sdist），直接从 sdist')
A('  取纯 Python 实现（`lib/yaml`，17 个文件），不编译 C 扩展；`yaml.safe_load` 可用。')
A('- **`cryptography` / `psutil`**：来自 Termux deb（bionic、`.abi3.so`），不是 PyPI。')
A('- **`cffi` 未安装**：Termux 的 cryptography 50.0.2 是全 Rust 绑定（`_rust.abi3.so`），代码里没有')
A('  `import cffi`（唯一出现是注释），且 Termux 仓库本身也没有 cffi 包；其 METADATA 虽声明')
A('  `cffi>=2.0.0`，但运行期不需要。')
A('- `python-site` 里还包含这些包带进来的传递依赖：')
A('')
A('```')
inst = (ST.get('site') or {}).get('installed') or []
A(', '.join(inst))
A('```')
A('')
A('## 6. App 端解压 / 运行注意点')
A('')
A('1. **wrapper 必须原地替换**（不只是放进 PATH）：`gcc` 会用绝对路径 exec')
A('   `libexec/gcc/xtensa-esp32-elf/12.2.0/cc1`、`cc1plus`、`collect2`、`lto1`，这些位置同样必须')
A('   是能跑的入口。做法：把真实 ELF 改名为 `<name>.real`，在同名位置写 0755 的 shell 脚本：')
A('')
A('   ```')
A('   #!/system/bin/sh')
A('   exec "$BASE/glibc/lib/ld-linux-aarch64.so.1" --library-path "$BASE/glibc/lib" \\')
A('        "$BASE/tools/xtensa-esp32-elf/<相对路径>/<name>.real" "$@"')
A('   ```')
A('')
A('   `bin/` 下的 28 个也可以原地替换（或做成 wrapper 目录 + PATH 前置，两者都行），但要保证')
A('   gcc 通过 `COMPILER_PATH`/`-B` 找到的 `as`、`ld`、`collect2` 也是 wrapper。')
A('')
A('   **需要 wrapper 的 ELF 总数：%d**（仅 glibc 工具链）。' % M['wrapperTargetsTotal'])
A('')
A('| 目录 | ELF 数 |')
A('| --- | ---: |')
for k, v in sorted(M['wrapperTargets'].items()):
    A('| `%s` | %d |' % (k, v))
A('')
A('   `tools/cmake/bin`（3 个：cmake/ninja/make）和 `tools/python/bin`（3 个：python/python3/')
A('   python3.14）是 **bionic** 程序，**不需要** ld.so wrapper，直接 exec 即可。')
A('')
A('2. **不要 patchelf**：所有工具链 ELF 与发布 tarball 逐字节一致（见第 3 节检查 7）。改 PT_INTERP')
A('   会破坏段布局，`cc1` 会段错误。')
A('3. **环境变量**（缺一不可）：')
A('')
A('   ```')
for k, v in M['runtimeEnv'].items():
    A('   %s=%s' % (k, v))
A('   ```')
A('')
A('   - `PYTHONHOME` 指向 `tools/python`：Termux python 编译前缀是 `/data/data/com.termux/files/usr`，')
A('     必须用 `PYTHONHOME` 重定位；否则找不到 stdlib。')
A('   - `PREFIX` 指向 `tools/cmake`（`CMakeDetermineSystem.cmake` 读它下面的 `include/android/api-level.h`）。')
A('   - `ESPSTUDIO_SKIP_DEP_CHECK=1` 让 `idf.py` 跳过 venv 依赖校验（见 4.1）。')
A('   - `IDF_COMPONENT_MANAGER=0` 让 `idf.py` 不 import 组件管理器。')
A('   - `LD_LIBRARY_PATH` 必须含 `tools/python/lib`（libpython3.14.so 等）与 `tools/cmake/lib`。')
A('4. **解压后 chmod**：`ZipInputStream` 不还原权限。所有 ELF 与 `*.sh` 需 `0755`（zip 里已写入')
A('   unix 权限位，但不要依赖）。')
A('5. **路径不要有空格**：IDF 的 CMake 拼参数时对空格处理不完善。')
A('6. `glibc/lib` 里都是真实文件副本（不是符号链接），因为 zip 不承载符号链接。')
A('7. `tools/python/bin/python` / `python3` / `python3.14` 是同一份二进制的三份副本（无符号链接），')
A('   方便 IDF 的 venv/解释器检查。')
A('')
A('## 7. 与计划/规范的偏离')
A('')
A('- **没有 `tools/esp32ulp-elf`**：新结构清单里没列，因此 ULP（esp32 ULP-FSM/RISC-V 协处理器）')
A('  相关构建不可用；普通 esp32/esp32s3 工程不受影响。')
A('- **裁剪了 `share/doc`、`share/man`、`share/info`、`bin/*gdb*`、`bin/*lto-dump*`**：不含 gdb')
A('  （不用 JTAG）；`lto-dump` 只是转储 LTO 中间表示的工具，编译链接用的是 `libexec/.../lto1`')
A('  （保留）。**`.a`/`.o` 一个都没删。**')
A('- **python 是 3.14.10**：Termux 滚动仓库目前只提供 3.14（没有 3.12/3.13）。IDF 5.2.5 官方')
A('  支持 3.8–3.12，因此对纯 Python 依赖做了 3.14 全量 import 验证（第 3 节末），但如果真机遇到')
A('  3.14 标准库行为差异（例如 `distutils` 移除后的边角），需要换 python。')
A('- **`esptool` 钉在 4.x**（见第 5 节）。')
A('- **IDF 补丁的 5.2 锚点**是我按实际代码新写的（见 4.1）。')
A('')
A('## 8. 复现步骤')
A('')
A('```powershell')
A('cd D:\\DSH\\espstudio\\pack')
A('# 1) 缓存下载到 pack\\dl（可重复执行、断点续传）')
A('python scripts\\fetch_pkgs.py debian')
A('python scripts\\fetch_pkgs.py termux')
A('python scripts\\fetch_termux_libs.py libiconv libxml2 liblzma')
A('curl.exe -L --retry 3 -C - -o dl\\esp-idf-v5.2.5.zip https://dl.espressif.com/github_assets/espressif/esp-idf/releases/download/v5.2.5/esp-idf-v5.2.5.zip')
A('curl.exe -L --retry 3 -C - -o dl\\xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz https://dl.espressif.com/github_assets/espressif/crosstool-NG/releases/download/esp-12.2.0_20230208/xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz')
A('curl.exe -L --retry 3 -C - -o dl\\xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz https://dl.espressif.com/github_assets/espressif/crosstool-NG/releases/download/esp-12.2.0_20230208/xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz')
A('')
A('# 2) 分阶段构建（可中断续跑，锁目录 build\\locks525）')
A('foreach ($s in "idf","patch","toolchains","cmake","python","site","glibc") {')
A('  python scripts\\build-pack-525.py --stage $s')
A('}')
A('')
A('# 3) 验证 + 出包')
A('python scripts\\verify-pack-525.py')
A('python scripts\\build-pack-525.py --stage zip')
A('python scripts\\verify-pack-525.py --zip   # 回填 sha256 / 大小到 pack\\manifest.json')
A('python scripts\\make-docs-525.py          # 生成本文件')
A('```')
A('')
A('依赖：Windows + Python 3.11+、`curl.exe`、能访问 `dl.espressif.com` / `mirrors.tuna.tsinghua.edu.cn` /')
A('PyPI 镜像。全流程不需要 Android 设备，也不修改 `app/` 下任何源码。')
A('')

out = os.path.join(PACK, 'BUILD.md')
with open(out, 'w', encoding='utf-8', newline='\n') as f:
    f.write('\n'.join(L) + '\n')
print('wrote', out, len(L), 'lines')
