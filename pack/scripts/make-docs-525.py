#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""make-docs-525.py -- write BUILD.md from build/verify525.json + build/build525.json."""
import json
import os
import sys

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
A('本项目为一个离线 Android 平板 ESP32 IDE 提供**解压即用**的编译环境：')
A('ESP-IDF v5.2.5 源码 + 官方 per-chip xtensa 工具链（glibc/aarch64）+ bionic 版')
A('cmake/ninja/make + bionic CPython + 纯 Python 依赖 + aarch64 glibc 运行时。')
A('')
A('> 为什么是 5.2.5：从 IDF 5.3 起 Espressif 把 xtensa 工具链统一成 `xtensa-esp-elf`，')
A('> `xtensa-esp32-elf-gcc` 等名字变成 Rust 写的调度器，靠 `/proc/self/exe` 判断自身身份；')
A('> 经 `ld-linux-aarch64.so.1` 启动时该路径是 loader，必然断言失败')
A('> （`Called tool must have pattern "xtensa-esp*-elf-*"`）。')
A('> 5.2.5 仍是普通 gcc/binutils，可被 loader wrapper 直接拉起（已在真机验证）。')
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
A('## 2. 目录结构与体积')
A('')
A(row('路径', '字节数', '大小'))
A(row('---', '---:', '---:'))
for k, v in sorted(M['sizes']['topLevel'].items(), key=lambda x: -x[1]):
    A(row('`%s`' % k, v, mib(v)))
A(row('**合计**', '**%d**' % M['sizes']['totalBytes'], '**%s**' % mib(M['sizes']['totalBytes'])))
A('')
A('`tools/` 下细分：')
A('')
A(row('路径', '字节数', '大小'))
A(row('---', '---:', '---:'))
for k, v in sorted(M['sizes']['tools'].items(), key=lambda x: -x[1]):
    A(row('`%s`' % k, v, mib(v)))
A('')
A('## 3. 验证结果')
A('')
A(row('检查项', '结果', '关键数字'))
A(row('---', '---', '---'))
sm = C['submodules']
A(row('1. submodule 完整性', yn(sm['pass_']),
      'mbedtls/mbedtls/library %d 个文件；esp_phy/lib %d 个文件；bluedroid/api %d 个文件'
      % (sm['mbedtls_library_files'], sm['esp_phy_lib_files'], sm['bluedroid_api_files'])))
tc = C['toolchains']
per = tc['per_toolchain']
A(row('2. 工具链 ELF', yn(tc['pass_']),
      '%s；%s' % (per['xtensa-esp32-elf']['gcc'], per['xtensa-esp32s3-elf']['gcc'])))
A(row('', '', 'ELF 可执行文件合计 **%d** 个（esp32: %d / esp32s3: %d），'
      'PT_INTERP 均为 `%s`'
      % (tc['total_executables'],
         per['xtensa-esp32-elf']['executables'], per['xtensa-esp32s3-elf']['executables'],
         per['xtensa-esp32-elf']['interp'])))
A(row('', '', '分布：esp32 bin/ %d、libexec/ %d、其他 %d；esp32s3 bin/ %d、libexec/ %d、其他 %d'
      % (per['xtensa-esp32-elf']['in_bin'], per['xtensa-esp32-elf']['in_libexec'],
         per['xtensa-esp32-elf']['other'], per['xtensa-esp32s3-elf']['in_bin'],
         per['xtensa-esp32s3-elf']['in_libexec'], per['xtensa-esp32s3-elf']['other'])))
py = C['python']
A(row('3. bionic python', yn(py['pass_']),
      '%s，interp `%s`；DT_NEEDED %s 全部命中 tools/python/lib（libc.so/libm.so/libdl.so '
      '由 Android 系统提供）；%d 个 lib-dynload 扩展模块依赖亦全部命中'
      % (py['desc'], py['interp'], ', '.join(n for n in py['needed']), py['dynload_modules'])))
g = C['glibc']
A(row('4. glibc loader', yn(g['pass_']),
      '`glibc/lib/ld-linux-aarch64.so.1` %s，共 %d 个库 / %s' % (g['desc'], g['count'], mib(g['bytes']))))
gc = C['glibc_closure']
A(row('5. glibc 闭包', yn(gc['pass_']),
      '扫描 %d 个工具链 ELF，DT_NEEDED 全部落在 `glibc/lib/`（缺失项：%s）'
      % (gc['scanned'], gc['missing'] or '无')))
cm = C['cmake']
A(row('6. cmake/ninja/make', yn(cm['pass_']),
      '三者均 aarch64 bionic；libarchive 依赖（libiconv/libxml2.so.16/liblzma.so.5）全部命中；'
      '`include/android/api-level.h` = `%s`' % cm['api_level_header']))
pr = C['pristine']
d = pr['details']
A(row('7. 二进制未被修改', yn(pr['pass_']),
      'esp32: %d 个文件逐字节比对 sha256，0 处不一致（%d 个已裁剪）；esp32s3: %d 个，0 处不一致（%d 个已裁剪）'
      % (d['xtensa-esp32-elf']['checked'], d['xtensa-esp32-elf']['absent'],
         d['xtensa-esp32s3-elf']['checked'], d['xtensa-esp32s3-elf']['absent'])))
pt = C['patches']
A(row('8. IDF 补丁', yn(pt['pass_']),
      'Android 平台别名 %s；ESPSTUDIO_SKIP_DEP_CHECK %s；扩展导入容错 %s'
      % (pt['platform_alias_android'], pt['skip_dep_check_env'], pt['extension_tolerant'])))
A('')
A('补充：用 **CPython 3.14.0**（与包内 Termux python 同 minor 版本）实测 `python-site` 的')
A('每个依赖都能 import，且 `idf_py_actions.tools`（`idf.py` 启动时导入的模块）可正常加载；')
A('`cryptography`/`psutil` 是 Termux 针对 bionic 编译的 `.abi3.so`，无法在 Windows 上验证，')
A('只做了 ELF 架构与依赖检查。')
A('')
A('## 4. 打包时打的补丁')
A('')
A('`scripts/patch-idf-android.py` 作用在 `esp-idf/tools/idf_tools.py` 与 `esp-idf/tools/idf.py`：')
A('')
A('| # | 位置 | 修改 | 目的 | 结果 |')
A('| --- | --- | --- | --- | --- |')
A('| 1 | `idf_tools.py` `Platforms.get()` | 平台别名含 android 时映射到 `linux-arm64` | 让 IDF 在 Android 上不抛 “platform not supported” | %s |'
  % pt['platform_alias_android'])
A('| 2 | `idf_tools.py` `action_check_python_dependencies()` | `ESPSTUDIO_SKIP_DEP_CHECK=1` 时跳过 | 依赖在 PYTHONPATH 里，IDF 的 venv 版本校验只会误报 | %s |'
  % pt['skip_dep_check_env'])
A('| 3 | `idf.py` 扩展加载循环 | 单个 `*_ext` 导入失败只告警 | 少一个可选扩展不该让整个 idf.py 挂掉 | %s |'
  % pt['extension_tolerant'])
A('')
A('App 必须设置 `ESPSTUDIO_SKIP_DEP_CHECK=1` 才会生效（否则 IDF 的依赖校验仍会报错）。')
A('')
A('## 5. cmake 的额外修补（原 cmake.zip 不完整）')
A('')
A('- 补库：把 Termux 的 `libiconv.so`、`libcharset.so`、`libxml2.so*`、`liblzma.so*` 复制进')
A('  `tools/cmake/lib/`，否则 bionic `libarchive.so` 缺 `DT_NEEDED` 无法加载（真机已撞到）。')
A('- 补头文件：`tools/cmake/include/android/api-level.h`，内容一行 `#define __ANDROID_API__ 28`。')
A('  `share/cmake-4.5/Modules/CMakeDetermineSystem.cmake:38` 会读 `$ENV{PREFIX}/include/android/api-level.h`，')
A('  因此 App 必须把环境变量 `PREFIX` 指向 `tools/cmake`（不是 NDK，是包内这个目录）。')
A('')
A('## 6. python-site 包清单')
A('')
A('按 `esp-idf/tools/requirements/requirements.core.txt` 解析（原样保留版本约束），排除')
A('`idf-component-manager`（离线用不到，且 `IDF_COMPONENT_MANAGER=0` 时不会被导入）：')
A('')
A('```')
reqs = (ST.get('site') or {}).get('requirements') or []
for r in reqs:
    A(r)
A('```')
A('')
A('安装结果（每包来源）：')
A('')
A('```')
for k, v in sorted(((ST.get('site') or {}).get('result') or {}).items()):
    A('%-36s %s' % (k, v))
A('```')
A('')
A('- `pyyaml`：PyPI 已不再发布 `py3-none-any` 纯 wheel（只有平台 wheel + sdist），')
A('  直接从 sdist 里取纯 Python 实现（`lib/yaml`），不编译 C 扩展；`yaml.safe_load` 可用。')
A('- `cryptography` / `psutil`：从 Termux deb 取 bionic 编译版本（`.abi3.so`），')
A('  满足 ESP-IDF 对 `cryptography` 的硬依赖（mbedtls 证书 bundle 生成脚本会 import 它）。')
A('')
A('## 7. App 端解压与运行注意点')
A('')
A('1. **wrapper 生成**：`tools/xtensa-esp32-elf` 与 `tools/xtensa-esp32s3-elf` 下的 ELF 可执行文件')
A('   一律包一层：')
A('')
A('   ```')
A('   #!/system/bin/sh')
A('   exec "$BASE/glibc/lib/ld-linux-aarch64.so.1" --library-path "$BASE/glibc/lib" \\')
A('        "$BASE/tools/xtensa-esp32-elf/<相对路径>/<真实文件>" "$@"')
A('   ```')
A('')
A('   `wrapperTargets` 里给了每个目录的 ELF 可执行文件数量，可用于核对（总计 %d 个）：'
  % M['wrapperTargetsTotal'])
A('')
A('| 目录 | 需要 wrapper 的 ELF 数 |')
A('| --- | ---: |')
for k, v in sorted(M['wrapperTargets'].items()):
    A('| `%s` | %d |' % (k, v))
A('')
A('2. **不要 patchelf**：包里工具链二进制与发布 tarball 逐字节一致（见检查项 7）。')
A('   改 PT_INTERP 会破坏段布局导致 `cc1` 段错误。')
A('3. **环境变量**（缺一不可）：')
A('')
A('   ```')
for k, v in M['runtimeEnv'].items():
    A('   %s=%s' % (k, v))
A('   ```')
A('')
A('   - `PYTHONHOME` 指向 `tools/python`：Termux python 编译时前缀是')
A('     `/data/data/com.termux/files/usr`，必须用 `PYTHONHOME` 重定位到解压目录。')
A('   - `PYTHONPATH` 指向 `tools/python-site`。')
A('   - `LD_LIBRARY_PATH` 必须含 `tools/python/lib`（libpython3.14.so 等）与 `tools/cmake/lib`。')
A('   - `PREFIX` 指向 `tools/cmake`，供 `CMakeDetermineSystem.cmake` 读 api-level。')
A('4. **解压后 chmod**：ZipInputStream 不还原权限。ELF 可执行文件与 `*.sh` 需要 `0755`；')
A('   目录 `0755`。zip 里已尽力写入 unix 权限位（external_attr），但不要依赖它。')
A('5. **路径不能有空格**：IDF 的 CMake 拼 `-L` 参数时对空格处理不完善，解压目录建议')
A('   无空格（例如 `files/tc`）。')
A('6. `glibc/lib` 里所有同名库都写成了**真实文件**（`libc.so.6` 等是副本，不是符号链接）——')
A('   因为 zip 无法承载符号链接。')
A('')
A('## 8. 与规范/计划的偏离')
A('')
A('- **没有 `tools/esp32ulp-elf`**：新的包结构清单里未包含 ULP 工具链，因此 ULP 相关构建')
A('  （esp32 的 ULP-FSM/RISC-V 协处理器程序）不可用；普通 esp32/esp32s3 工程不受影响。')
A('- **裁剪掉了 `share/doc`、`share/man`、`share/info`、`bin/*gdb*`、`bin/*lto-dump*`**：')
A('  不含 gdb（真机不用 JTAG），`lto-dump` 只是转储 LTO 中间表示的工具，编译/链接用的是')
A('  `libexec/gcc/.../lto1`（保留）。`.a`/`.o` 一个都没删。')
A('- **python 是 3.14**：Termux 滚动仓库当前只提供 3.14.10，没有 3.12/3.13。')
A('  IDF 5.2.5 官方支持 3.8–3.12，因此用 CPython 3.14 做了全量 import 验证（见第 3 节），')
A('  但运行时若出现 3.14 特有的标准库行为差异，需要改 TERMUX 包或换 python。')
A('- **`idf-component-manager` 未安装**：离线包不访问组件注册表；配 `IDF_COMPONENT_MANAGER=0` 使用。')
A('')
A('## 9. 复现步骤')
A('')
A('```powershell')
A('cd D:\\DSH\\espstudio\\pack')
A('# 1) 下载缓存到 pack\\dl（可重复运行，断点续传）')
A('python scripts\\fetch_pkgs.py debian')
A('python scripts\\fetch_pkgs.py termux')
A('python scripts\\fetch_termux_libs.py libiconv libxml2 liblzma')
A('curl.exe -L --retry 3 -C - -o dl\\esp-idf-v5.2.5.zip https://dl.espressif.com/github_assets/espressif/esp-idf/releases/download/v5.2.5/esp-idf-v5.2.5.zip')
A('curl.exe -L --retry 3 -C - -o dl\\xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz https://dl.espressif.com/github_assets/espressif/crosstool-NG/releases/download/esp-12.2.0_20230208/xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz')
A('curl.exe -L --retry 3 -C - -o dl\\xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz https://dl.espressif.com/github_assets/espressif/crosstool-NG/releases/download/esp-12.2.0_20230208/xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz')
A('# 2) 分阶段构建（可中断续跑，锁在 build\\locks525）')
A('python scripts\\build-pack-525.py --stage idf')
A('python scripts\\build-pack-525.py --stage patch')
A('python scripts\\build-pack-525.py --stage toolchains')
A('python scripts\\build-pack-525.py --stage cmake')
A('python scripts\\build-pack-525.py --stage python')
A('python scripts\\build-pack-525.py --stage site')
A('python scripts\\build-pack-525.py --stage glibc')
A('# 3) 验证 + 出 zip')
A('python scripts\\verify-pack-525.py')
A('python scripts\\build-pack-525.py --stage zip')
A('python scripts\\verify-pack-525.py --zip   # 回填 sha256/大小到 manifest.json')
A('python scripts\\make-docs-525.py          # 生成本文件')
A('```')
A('')

out = os.path.join(PACK, 'BUILD.md')
with open(out, 'w', encoding='utf-8', newline='\n') as f:
    f.write('\n'.join(L) + '\n')
print('wrote', out, len(L), 'lines')
