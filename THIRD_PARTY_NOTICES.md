# 第三方组件与许可证

CodePad 的 APK 里打包了若干第三方程序和库。它们各自保留原有许可证，
本文件用于履行署名与源码获取义务。

**重要**：仓库里的 CodePad 源码使用 GPL-3.0，但这**不会改变**下列组件的许可证。
它们是作为独立程序随 APK 一同分发的（进程边界隔离，非链接合并）。

## 内置工具链（以 Termux 构建产物为基础，做过 RUNPATH 重写）

| 组件 | 版本 | 许可证 | 源码 |
|---|---|---|---|
| LLVM / clang / clangd / lld | 21.1.8 | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |
| gdb / gdbserver | 随 Termux 打包 | **GPL-3.0** | https://sourceware.org/git/?p=binutils-gdb.git |
| git | 2.56.1 | **GPL-2.0** | https://github.com/git/git |
| CMake | 4.5.1 | BSD-3-Clause | https://github.com/Kitware/CMake |
| GNU Make | 随 Termux 打包 | GPL-3.0 | https://www.gnu.org/software/make/ |
| Rust (rustc / cargo) | 1.100.0 | MIT OR Apache-2.0 | https://github.com/rust-lang/rust |
| rust-src（标准库源码） | 1.100.0 | MIT OR Apache-2.0 | https://github.com/rust-lang/rust |
| rust-analyzer | 20261123 | MIT OR Apache-2.0 | https://github.com/rust-lang/rust-analyzer |
| Termux 打包脚本与补丁 | — | 各组件原许可证 | https://github.com/termux/termux-packages |

上述二进制来自 Termux 的 aarch64 包，仅将 ELF 的 `DT_RUNPATH`
由 `/data/data/com.termux/files/usr/lib` 重写为 `$ORIGIN/../lib`，
未修改程序逻辑。需要源码请访问上表链接，或 Termux 对应包的构建记录。

## 应用内嵌运行时

| 组件 | 许可证 | 说明 / 源码 |
|---|---|---|
| CPython (via Chaquopy 16.0.0) | PSF-2.0 | https://github.com/python/cpython ，https://chaquo.com/chaquopy/ |
| QuickJS | MIT | https://bellard.org/quickjs/ （`app/src/main/jni/quickjs_jni.c` 为其 JNI 封装） |
| TinyCC (libtcc) | LGPL-2.1 | https://bellard.org/tcc/ （用于快速 C 运行） |

## 前端与图标

| 组件 | 许可证 | 源码 |
|---|---|---|
| Monaco Editor | MIT | https://github.com/microsoft/monaco-editor |
| xterm.js | MIT | https://github.com/xtermjs/xterm.js |
| Material Icon Theme | MIT | https://github.com/PKief/vscode-material-icon-theme |
| AndroidSVG | Apache-2.0 | https://github.com/BigBadaboom/androidsvg |

## AndroidX / Jetpack Compose

Apache-2.0，见 https://developer.android.com/jetpack

---

## GPL 合规说明

- 本 APK 包含 **GPL-2.0**（git）与 **GPL-3.0**（gdb/gdbserver、make）组件。
- 这些组件以独立可执行文件形式随 APK 分发，CodePad 通过命令行调用它们，
  属于「聚合分发」，不是衍生作品。
- 分发本 APK 时，必须同时提供上述组件的**完整对应源码**（或书面 offer），
  源码地址见上表。
- `git` 为 GPL-2.0 **only**，不可按 GPL-3.0 再许可。
- 如果你二次分发本 APK，请保留本文件并遵守各组件许可证。
