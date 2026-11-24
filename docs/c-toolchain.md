# C 调试工具链（实验）

目标：在设备上编译带 DWARF 的 C 程序，并用 gdbserver 调试。

## 已验证的事实

- Termux 的 clang 21.1.8 二进制可以直接在 Android 上跑，不需要 proot
  - 关键是 ELF 的 `DT_RUNPATH` 被硬编码成 `/data/data/com.termux/files/usr/lib`
  - 用脚本把该字符串替换成 `$ORIGIN/../lib`（等长替换，NUL 填充）后即可重定位
- 链接需要 NDK sysroot 的 `usr/lib/aarch64-linux-android/<api>` 和 `libunwind.a`
- 产物是正常的 bionic 可执行文件，能直接在设备上运行
- `gdbserver` 在 app 自己的 uid 下（run-as）能创建并 ptrace 子进程，监听端口正常
  - 说明「子进程 ptrace」没有被 SELinux 拦

## 复现步骤（当前脚本化）

1. 从清华镜像下载 deb（clang、libllvm、lld、llvm、libcompiler-rt、ndk-sysroot、libc++、
   libffi、libxml2、ncurses、zlib、libzstd、libiconv、liblzma、libandroid-glob、libthread-db、gdbserver）
2. Python 脚本解 ar + data.tar.xz 到 staging 前缀
3. `patch_rpath.py` 把 `/data/data/com.termux/files/usr/lib` 替换成 `$ORIGIN/../lib`
4. 组装 sysroot：
   - `usr/include`（NDK 公共头）
   - `usr/lib/aarch64-linux-android/26/*`（crt、libc.so 等 stub）
   - 补一个 `libunwind.a`（来自 NDK clang 的 resource dir）
5. 设备上：
   ```
   LD_LIBRARY_PATH=<prefix>/lib <prefix>/bin/clang \
     --target=aarch64-linux-android26 --sysroot=<sysroot> -g -O0 -o hello hello.c
   ```
6. 调试：
   ```
   LD_LIBRARY_PATH=<prefix>/lib <prefix>/bin/gdbserver :1234 ./hello
   ```

## 体积

| 组件 | 大小 |
| --- | --- |
| libLLVM.so | 127 MB |
| libclang-cpp.so | 60 MB |
| 其余库 + 头文件 + 资源目录 | ~40 MB |

stripped 之后预计 100–120 MB，需要作为 APK assets 首启解压。

## 下一步

1. Kotlin 侧实现 GDB Remote 协议客户端（`qSupported`、`?`、`Z0` 断点、`c`/`s`、`g`/`m`）
2. 用 `llvm-dwarfdump --debug-line` 解析行号 → 地址（避免自己写 DWARF 解析）
3. 用 `llvm-symbolizer` 把 PC 还原成 `函数 + 行号` 做调用栈
4. 变量：解析 `--debug-info` 里 `DW_OP_fbreg` 的简单局部变量（int/long/指针）
5. 打包进 APK：assets 里放压缩包，首启解压到 `files/tc`

## 已落地（APK 2.0）

- `assets/tc.zip`（69MB）首启解压到 `files/tc`，APK 约 100MB
- targetSdk 28：`untrusted_app_27` 域允许执行 app 私有目录里的二进制（untrusted_app 会拒绝 `execute_no_trans`）
- 编译：`clang --target=aarch64-linux-android26 --sysroot=... -g -O0 -fno-omit-frame-pointer`
- 链接所需：`sysroot/usr/lib/aarch64-linux-android/26`（crt + libc stub）与 `lib/clang/21/lib/aarch64-unknown-linux-android26/libclang_rt.builtins.a`
- gdbserver 需要 `SHELL=/system/bin/sh`，否则它会去找 Termux 的 sh
- PIE：断点地址 = `Z0` 的地址要加 `/proc/<pid>/maps` 里的加载基址
- 寄存器：`g` 包是 RLE 压缩的，PC 在解码后第 33 个 8 字节寄存器
- 单步：停在断点上直接 `s` 会再次撞断点，必须先 `z0` 再 `s` 再 `Z0`