"""把 Termux 二进制里硬编码的 shell 路径改成 Android 系统的 /system/bin/sh。

Termux 的 ninja 被 patch 成固定用
    /data/data/com.termux/files/usr/bin/sh
来跑每一条构建命令。这个路径在我们的 App 里不存在，于是 ninja 的每条命令都以
code=127 失败（"command not found"），即使被执行的程序本身完全正常。

做法：在二进制里做**等长**替换，多余字节用 NUL 填 —— 和 CodePad 改写 DT_RUNPATH
是同一招。C 字符串到第一个 NUL 结束，所以填充是安全的。

用法：python patch-termux-shell.py <文件或目录> ...
"""
import pathlib
import sys

OLD = b"/data/data/com.termux/files/usr/bin/sh\x00"
NEW_PATH = b"/system/bin/sh"

# 也处理 Termux 的 bash（个别工具会找 bash）
OLD_BASH = b"/data/data/com.termux/files/usr/bin/bash\x00"
NEW_BASH = b"/system/bin/sh"


def patch_bytes(data: bytes, old: bytes, new: bytes) -> tuple[bytes, int]:
    if len(new) + 1 > len(old):
        raise ValueError(f"新路径太长：{new!r} 无法塞进 {old!r}")
    n = data.count(old)
    if not n:
        return data, 0
    filler = new + b"\x00" * (len(old) - len(new) - 1) + b"\x00"
    assert len(filler) == len(old), (len(filler), len(old))
    return data.replace(old, filler), n


def patch_file(p: pathlib.Path) -> int:
    try:
        data = p.read_bytes()
    except Exception as e:
        print("  跳过（读不了）", p, e)
        return 0
    total = 0
    data, n = patch_bytes(data, OLD, NEW_PATH)
    total += n
    data, n2 = patch_bytes(data, OLD_BASH, NEW_BASH)
    total += n2
    if total:
        p.write_bytes(data)
        print(f"  修好 {total} 处: {p.name}")
    return total


if __name__ == '__main__':
    targets = [pathlib.Path(a) for a in sys.argv[1:]]
    if not targets:
        targets = [pathlib.Path(r'D:\DSH\espstudio\pack\build\staging525')]
    grand = 0
    for t in targets:
        print(t)
        if t.is_dir():
            for f in t.rglob("*"):
                if f.is_file() and f.stat().st_size > 4096:
                    grand += patch_file(f)
        else:
            grand += patch_file(t)
    print("合计修补", grand, "处")
