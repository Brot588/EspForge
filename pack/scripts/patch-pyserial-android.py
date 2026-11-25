"""让 pyserial 在 Android 上不再抛 ImportError。

pyserial 的 list_ports_posix 在平台未知时直接 raise，而 esptool 一 import
serial.tools.list_ports 就会炸（构建时 elf2image 用 esptool 打包 bin）。
Android 上根本没有 /dev/tty*，串口由 App 自己通过 UsbManager 实现，
所以这里返回空列表即可。
"""
import pathlib

OLD = "    raise ImportError(\"Sorry: no implementation for your platform ('{}') available\".format(os.name))\n"
NEW = """    if sys.platform == 'android':
        # EspForge: Android 没有 /dev/tty* 端口，串口由 App 用 UsbManager 实现
        def comports(include_links=False):
            return []
    else:
        raise ImportError("Sorry: no implementation for your platform ('{}') available".format(os.name))
"""

p = pathlib.Path(r"D:\DSH\espstudio\pack\build\staging525\tools\python-site\serial\tools\list_ports_posix.py")
s = p.read_text(encoding="utf-8", errors="replace")
if "EspForge: Android" in s:
    print("already patched")
elif OLD in s:
    p.write_text(s.replace(OLD, NEW, 1), encoding="utf-8")
    print("patched:", p)
else:
    print("PATTERN NOT FOUND")
