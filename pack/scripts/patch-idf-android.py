"""让 ESP-IDF 能在 Android（无 venv、离线）上跑起来。两处补丁：

1. idf_tools.Platforms.get()：Android-aarch64 当作 linux-arm64
   （只影响「该用哪套预编译工具链」的判断；本项目工具链随资源包自带）

2. idf_tools.action_check_python_dependencies()：ESPSTUDIO_SKIP_DEP_CHECK=1 时直接跳过。
   IDF 要求 python 依赖装在一个由它自己创建的 venv 里，并逐版本比对 constraint；
   离线资源包把依赖放在 PYTHONPATH 里，这个检查只会误报。跳过它，真缺东西会在
   build 时以 ImportError 暴露，更好排查。
"""
import pathlib
import sys

PLATFORM_ANCHOR = """        if not platform_name:
            raise ValueError(f"Support for platform '{platform_alias}' hasn't been added yet.")
"""

PLATFORM_NEW = """        if not platform_name and 'android' in platform_alias.lower():
            # Android / bionic：当成 linux-arm64 处理（工具链随资源包自带，不经过这里下载）
            platform_name = (
                PLATFORM_LINUX_ARM64
                if ('aarch64' in platform_alias.lower() or 'arm64' in platform_alias.lower())
                else PLATFORM_LINUX64
            )
        if not platform_name:
            raise ValueError(f"Support for platform '{platform_alias}' hasn't been added yet.")
"""

DEP_ANCHOR = """def action_check_python_dependencies(args):  # type: ignore
    \"\"\"
    Checks if all the dependencies (from requirements, constraints...) are installed properly.
    Raises SystemExit if not.
    \"\"\"
"""

DEP_NEW = """def action_check_python_dependencies(args):  # type: ignore
    \"\"\"
    Checks if all the dependencies (from requirements, constraints...) are installed properly.
    Raises SystemExit if not.
    \"\"\"
    import os as _os
    if _os.environ.get('ESPSTUDIO_SKIP_DEP_CHECK', '').lower() in ('1', 'yes', 'true'):
        # ESP Studio 离线资源包：依赖在 PYTHONPATH 里，不按 venv 版本约束校验。
        info('Skipping Python dependency check (ESP Studio offline pack)')
        return
"""


EXT_ANCHOR = """            if name.endswith('_ext'):
                extensions.append((name, import_module(name)))
"""

EXT_NEW = """            if name.endswith('_ext'):
                try:
                    extensions.append((name, import_module(name)))
                except Exception as _e:  # ESP Studio: 单个扩展导入失败不该让整个 idf.py 挂掉
                    print_warning('WARNING: skipping idf.py extension %s: %s' % (name, _e))
"""


def patch_file(path: pathlib.Path) -> bool:
    t = path.read_text(encoding='utf-8', errors='replace')
    changed = False

    # 1) android 平台
    if 'Platforms.PLATFORM_LINUX_ARM64' in t:
        start = t.index("        if not platform_name and 'android' in platform_alias.lower():")
        end = t.index(PLATFORM_ANCHOR, start) + len(PLATFORM_ANCHOR)
        t = t[:start] + PLATFORM_NEW + t[end:]
        changed = True
        print('  platform: 修正旧补丁')
    elif 'Android / bionic' not in t:
        if PLATFORM_ANCHOR in t:
            t = t.replace(PLATFORM_ANCHOR, PLATFORM_NEW, 1)
            changed = True
            print('  platform: 已打补丁')
        else:
            print('  platform: 找不到锚点')

    # 2) 跳过依赖检查
    if 'ESPSTUDIO_SKIP_DEP_CHECK' not in t:
        if DEP_ANCHOR in t:
            t = t.replace(DEP_ANCHOR, DEP_NEW, 1)
            changed = True
            print('  dep-check: 已打补丁')
        else:
            print('  dep-check: 找不到锚点')
    else:
        print('  dep-check: 已有')

    # 3) 单个 idf.py 扩展导入失败不致命
    if 'skipping idf.py extension' not in t:
        if EXT_ANCHOR in t:
            t = t.replace(EXT_ANCHOR, EXT_NEW, 1)
            changed = True
            print('  extension: 已打补丁')
        else:
            print('  extension: 找不到锚点')
    else:
        print('  extension: 已有')

    if changed:
        path.write_text(t, encoding='utf-8')
    return changed


if __name__ == '__main__':
    roots = sys.argv[1:]
    if not roots:
        base = r'D:\DSH\espstudio\pack\build\staging\esp-idf\tools'
        roots = [base + r'\idf_tools.py', base + r'\idf.py']
    for t in roots:
        p = pathlib.Path(t)
        if not p.is_file():
            print('missing:', p)
            continue
        print(p)
        patch_file(p)
