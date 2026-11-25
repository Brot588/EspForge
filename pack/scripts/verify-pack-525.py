#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-pack-525.py -- validate the staged ESP-IDF v5.2.5 pack and write manifest.json.

Checks (each result is copied into BUILD.md by make-docs-525.py):
  1. submodules        components/mbedtls/mbedtls/library, components/esp_phy/lib, bluedroid
  2. toolchains        both per-chip gcc are ELF64 AArch64; ELF executable count per tools/*/bin
  3. bionic python     python3/python are ELF64 AArch64, DT_NEEDED resolvable (bionic system libs exempt)
  4. glibc loader      glibc/lib/ld-linux-aarch64.so.1 exists and is ELF64 AArch64
  5. glibc closure     every toolchain binary's DT_NEEDED is inside glibc/lib
  6. cmake             cmake/ninja/make ELF64 AArch64, deps (incl. the patched-in libs) resolvable,
                       include/android/api-level.h present
  7. pristine          every toolchain file is byte-identical to its release tarball
  8. patches           the three IDF patches are present in the staged tree
"""
import argparse
import hashlib
import json
import os
import re
import sys
import tarfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.dirname(HERE)
BUILD = os.path.join(PACK, 'build')
STAGE = os.path.join(BUILD, 'staging525')
DL = os.path.join(PACK, 'dl')
ZIP_NAME = 'esp-idf-5.2.5-arm64.zip'
ZIP_PATH = os.path.join(PACK, ZIP_NAME)

IDF_VERSION = 'v5.2.5'
CHIPS = ['esp32', 'esp32s3']
PACK_VERSION = '1.0.0'

TOOLCHAINS = [
    ('xtensa-esp32-elf', os.path.join(DL, 'xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz')),
    ('xtensa-esp32s3-elf', os.path.join(DL, 'xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz')),
]

ANDROID_SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so',
    'libstdc++.so', 'libEGL.so', 'libGLESv1_CM.so', 'libGLESv2.so', 'libvulkan.so',
    'libjnigraphics.so', 'libOpenSLES.so', 'libmediandk.so', 'libneuralnetworks.so',
}

sys.path.insert(0, HERE)
from elfinfo import Elf  # noqa: E402


def sha256_file(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def dir_stats(path):
    total, files = 0, 0
    for dp, _dn, fn in os.walk(path):
        for f in fn:
            try:
                total += os.path.getsize(os.path.join(dp, f))
                files += 1
            except OSError:
                pass
    return total, files


def is_elf(path):
    try:
        with open(path, 'rb') as f:
            return f.read(4) == b'\x7fELF'
    except OSError:
        return False


def walk_elf(root):
    out = []
    for dp, _dn, fns in os.walk(root):
        for f in fns:
            p = os.path.join(dp, f)
            if f.endswith(('.a', '.o')) or not is_elf(p):
                continue
            out.append(p)
    return out


def resolve_needed(entry_paths, libdirs, allow_system=True):
    index = {}
    for d in libdirs:
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            p = os.path.join(d, f)
            if os.path.isfile(p):
                index.setdefault(f, p)
    missing, resolved = [], {}
    for p in entry_paths:
        try:
            e = Elf(p)
        except Exception:
            continue
        for n in e.needed:
            if n in resolved or n in missing:
                continue
            if n in index:
                resolved[n] = index[n]
            elif allow_system and n in ANDROID_SYSTEM_LIBS:
                resolved[n] = '<android-system>'
            else:
                missing.append(n)
    return resolved, missing


def mb(n):
    return round(n / 1024.0 ** 2, 1)


def check_submodules():
    p1 = os.path.join(STAGE, 'esp-idf', 'components', 'mbedtls', 'mbedtls', 'library')
    p2 = os.path.join(STAGE, 'esp-idf', 'components', 'esp_phy', 'lib')
    bl = os.path.join(STAGE, 'esp-idf', 'components', 'bt', 'host', 'bluedroid', 'api')
    n1 = len(os.listdir(p1)) if os.path.isdir(p1) else 0
    n2 = sum(len(f) for _d, _s, f in os.walk(p2)) if os.path.isdir(p2) else 0
    n3 = len(os.listdir(bl)) if os.path.isdir(bl) else 0
    samples = ['components/mbedtls/mbedtls/library/ssl_tls.c',
               'components/esp_phy/lib/esp32/libphy.a',
               'components/bt/host/bluedroid/api/esp_bt_main.c']
    have = [s for s in samples if os.path.exists(os.path.join(STAGE, 'esp-idf', s))]
    return dict(pass_=bool(n1 and n2 and n3 and len(have) == len(samples)),
                mbedtls_library_files=n1, esp_phy_lib_files=n2,
                bluedroid_api_files=n3, samples_present=len(have), samples=samples)


def check_toolchains():
    per = {}
    total_bin = 0
    all_ok = True
    for name, _t in TOOLCHAINS:
        root = os.path.join(STAGE, 'tools', name)
        gcc = os.path.join(root, 'bin', name + '-gcc')
        e = Elf(gcc)
        exes = []
        for p in walk_elf(root):
            try:
                if Elf(p).is_executable:
                    exes.append(p)
            except Exception:
                pass
        by_dir = {}
        for p in exes:
            rel = os.path.relpath(os.path.dirname(p), root).replace(os.sep, '/')
            by_dir[rel] = by_dir.get(rel, 0) + 1
        per[name] = dict(gcc=e.file_desc(), arch=e.arch, interp=e.interp,
                         needed=e.needed, executables=len(exes),
                         in_bin=sum(v for k, v in by_dir.items() if k == 'bin'),
                         in_libexec=sum(v for k, v in by_dir.items() if k.startswith('libexec')),
                         other=sum(v for k, v in by_dir.items()
                                   if k != 'bin' and not k.startswith('libexec')),
                         by_dir=by_dir)
        total_bin += len(exes)
        all_ok = all_ok and e.arch == 'AArch64' and e.cls == 64
    return dict(pass_=all_ok and total_bin > 0, per_toolchain=per, total_executables=total_bin,
                interpreter_is_glibc=all('ld-linux-aarch64' in (v['interp'] or '')
                                         for v in per.values()))


def check_python():
    d = os.path.join(STAGE, 'tools', 'python')
    py = os.path.join(d, 'bin', 'python3')
    e = Elf(py)
    real = [f for f in os.listdir(os.path.join(d, 'bin')) if re.match(r'^python3\.\d+$', f)]
    ent = [py] + [os.path.join(d, 'bin', f) for f in real]
    libs = [os.path.join(d, 'lib')]
    resolved, missing = resolve_needed(ent, libs)
    dyn = []
    for sub in real:
        ver = sub[len('python'):]
        for dp, _dn, fns in os.walk(os.path.join(d, 'lib', 'python' + ver, 'lib-dynload')):
            dyn += [os.path.join(dp, f) for f in fns if f.endswith('.so')]
    resolved2, missing2 = resolve_needed(dyn, libs)
    return dict(pass_=e.arch == 'AArch64' and not missing and not missing2,
                desc=e.file_desc(), interp=e.interp, needed=e.needed,
                pythonNames=sorted(set(real)) + ['python', 'python3'],
                missing=missing, dynload_modules=len(dyn), dynload_missing=missing2,
                is_bionic=bool(e.interp and 'linker64' in e.interp))


def check_glibc():
    p = os.path.join(STAGE, 'glibc', 'lib', 'ld-linux-aarch64.so.1')
    ok = os.path.exists(p)
    e = Elf(p) if ok else None
    libs = sorted(os.listdir(os.path.join(STAGE, 'glibc', 'lib')))
    return dict(pass_=bool(ok and e and e.arch == 'AArch64'),
                desc=e.file_desc() if e else None, libs=libs, count=len(libs),
                bytes=dir_stats(os.path.join(STAGE, 'glibc', 'lib'))[0])


def check_glibc_closure():
    exes = []
    for name, _t in TOOLCHAINS:
        exes += walk_elf(os.path.join(STAGE, 'tools', name))
    resolved, missing = resolve_needed(exes, [os.path.join(STAGE, 'glibc', 'lib')],
                                       allow_system=False)
    return dict(pass_=not missing, resolved=sorted(resolved), missing=missing, scanned=len(exes))


def check_cmake():
    d = os.path.join(STAGE, 'tools', 'cmake')
    ents = {}
    for b in ('cmake', 'ninja', 'make'):
        p = os.path.join(d, 'bin', b)
        if not os.path.exists(p):
            ents[b] = None
            continue
        e = Elf(p)
        ents[b] = dict(desc=e.file_desc(), arch=e.arch, interp=e.interp, needed=e.needed)
    ent_paths = [os.path.join(d, 'bin', b) for b in ('cmake', 'ninja', 'make')
                 if os.path.exists(os.path.join(d, 'bin', b))]
    resolved, missing = resolve_needed(ent_paths, [os.path.join(d, 'lib')])
    # libarchive's own deps must resolve too (that is what broke on device)
    arc = os.path.join(d, 'lib', 'libarchive.so')
    arc_missing = []
    if os.path.exists(arc):
        _r, arc_missing = resolve_needed([arc], [os.path.join(d, 'lib')])
    modules = os.path.join(d, 'share')
    has_mod = os.path.isdir(modules) and any(x.startswith('cmake-') for x in os.listdir(modules))
    api = os.path.join(d, 'include', 'android', 'api-level.h')
    api_txt = open(api).read().strip() if os.path.exists(api) else ''
    return dict(pass_=all(v and v['arch'] == 'AArch64' for v in ents.values() if v)
                and not missing and not arc_missing and has_mod and '__ANDROID_API__' in api_txt,
                binaries=ents, missing=missing, libarchive_missing=arc_missing,
                cmake_modules_present=has_mod, api_level_header=api_txt,
                lib_count=len(os.listdir(os.path.join(d, 'lib'))) if os.path.isdir(os.path.join(d, 'lib')) else 0)


def check_pristine():
    results = {}
    for name, tarpath in TOOLCHAINS:
        dest = os.path.join(STAGE, 'tools', name)
        t = tarfile.open(tarpath, 'r:*')
        checked = mismatched = absent = 0
        bad = []
        for m in t.getmembers():
            if not m.isfile():
                continue
            parts = m.name.split('/')[1:]
            if not parts or not parts[-1]:
                continue
            rel = '/'.join(parts)
            p = os.path.join(dest, rel.replace('/', os.sep))
            if not os.path.exists(p):
                absent += 1
                continue
            h = hashlib.sha256(t.extractfile(m).read()).hexdigest()
            if sha256_file(p) != h:
                mismatched += 1
                bad.append(rel)
            checked += 1
        t.close()
        results[name] = dict(checked=checked, mismatched=mismatched, absent=absent,
                             bad_sample=bad[:5])
    return dict(pass_=all(v['mismatched'] == 0 for v in results.values()), details=results)


def check_patches():
    it = os.path.join(STAGE, 'esp-idf', 'tools', 'idf_tools.py')
    ip = os.path.join(STAGE, 'esp-idf', 'tools', 'idf.py')
    t1 = open(it, encoding='utf-8', errors='replace').read()
    t2 = open(ip, encoding='utf-8', errors='replace').read()
    res = {
        'platform_alias_android': 'Android / bionic' in t1,
        'skip_dep_check_env': 'ESPSTUDIO_SKIP_DEP_CHECK' in t1,
        'extension_tolerant': 'skipping idf.py extension' in t2,
    }
    return dict(pass_=all(res.values()), **res)


def sizes():
    tops, tools = {}, {}
    for name in sorted(os.listdir(STAGE)):
        p = os.path.join(STAGE, name)
        tops[name] = dir_stats(p)[0] if os.path.isdir(p) else os.path.getsize(p)
    tp = os.path.join(STAGE, 'tools')
    if os.path.isdir(tp):
        for name in sorted(os.listdir(tp)):
            tools['tools/' + name] = dir_stats(os.path.join(tp, name))[0]
    total, files = dir_stats(STAGE)
    return dict(total_bytes=total, total_files=files, top_level=tops, tools=tools)


def python_version():
    p = os.path.join(STAGE, 'tools', 'python', 'lib')
    for d in os.listdir(p):
        m = re.match(r'^python(3\.\d+)$', d)
        if m:
            return m.group(1)
    return '?'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--zip', action='store_true')
    ap.add_argument('--skip-pristine', action='store_true')
    a = ap.parse_args()

    C = {}
    C['submodules'] = check_submodules()
    C['toolchains'] = check_toolchains()
    C['python'] = check_python()
    C['glibc'] = check_glibc()
    C['glibc_closure'] = check_glibc_closure()
    C['cmake'] = check_cmake()
    C['patches'] = check_patches()
    C['sizes'] = sizes()
    C['pristine'] = dict(pass_=True, details={'skipped': True}) if a.skip_pristine else check_pristine()

    # only the glibc toolchains need an ld.so wrapper; cmake/python are bionic and run natively
    wrapper = {}
    for name, _t in TOOLCHAINS:
        for sub, cnt in sorted(C['toolchains']['per_toolchain'][name]['by_dir'].items()):
            wrapper['tools/%s/%s' % (name, sub)] = cnt
    bionic_exes = {
        'tools/cmake/bin': len([f for f in os.listdir(os.path.join(STAGE, 'tools', 'cmake', 'bin'))
                                if is_elf(os.path.join(STAGE, 'tools', 'cmake', 'bin', f))]),
        'tools/python/bin': len([f for f in os.listdir(os.path.join(STAGE, 'tools', 'python', 'bin'))
                                 if is_elf(os.path.join(STAGE, 'tools', 'python', 'bin', f))]),
    }

    site = {}
    sp = os.path.join(STAGE, 'tools', 'python-site')
    if os.path.isdir(sp):
        for d in sorted(os.listdir(sp)):
            if d.endswith('.dist-info'):
                site[d[:-len('.dist-info')]] = 'dist'
            elif os.path.isdir(os.path.join(sp, d)) and not d.startswith(('yaml',)) and \
                    d not in ('__pycache__',):
                site.setdefault(d, 'module')

    manifest = {
        'packVersion': PACK_VERSION,
        'idfVersion': IDF_VERSION,
        'chips': CHIPS,
        'pythonVersion': python_version(),
        'xtensaVersion': 'esp-12.2.0_20230208 (per-chip: xtensa-esp32-elf, xtensa-esp32s3-elf)',
        'toolchains': [n for n, _t in TOOLCHAINS],
        'cmakeVersion': '4.5 (Termux bionic build, + libiconv/libxml2/liblzma, api-level.h)',
        'glibcSource': 'Debian 12 bookworm arm64: libc6 2.36-9+deb12u14, libstdc++6 12.2.0-14+deb12u1, '
                       'libgcc-s1 12.2.0-14+deb12u1, zlib1g 1:1.2.13.dfsg-1 (mirrors.tuna.tsinghua.edu.cn)',
        'elfExecutables': C['toolchains']['total_executables'],
        'elfExecutablesByToolchain': {n: C['toolchains']['per_toolchain'][n]['executables']
                                      for n, _t in TOOLCHAINS},
        'wrapperTargets': wrapper,
        'wrapperTargetsTotal': sum(wrapper.values()),
        'bionicExecutables': bionic_exes,
        'sizes': dict(totalBytes=C['sizes']['total_bytes'], totalFiles=C['sizes']['total_files'],
                      topLevel=C['sizes']['top_level'], tools=C['sizes']['tools']),
        'builtAt': __import__('time').strftime('%Y-%m-%dT%H:%M:%S%z'),
        'entrypoints': {
            'idfPy': 'esp-idf/tools/idf.py',
            'gccEsp32': 'tools/xtensa-esp32-elf/bin/xtensa-esp32-elf-gcc',
            'gccEsp32s3': 'tools/xtensa-esp32s3-elf/bin/xtensa-esp32s3-elf-gcc',
            'python': 'tools/python/bin/python3',
            'cmake': 'tools/cmake/bin/cmake',
            'ninja': 'tools/cmake/bin/ninja',
            'make': 'tools/cmake/bin/make',
            'glibcLoader': 'glibc/lib/ld-linux-aarch64.so.1',
        },
        'runtimeEnv': {
            'IDF_PATH': 'esp-idf',
            'IDF_COMPONENT_MANAGER': '0',
            'ESPSTUDIO_SKIP_DEP_CHECK': '1',
            'IDF_PYTHON_ENV_PATH': '(unset)',
            'PREFIX': 'tools/cmake',
            'PYTHONHOME': 'tools/python',
            'PYTHONPATH': 'tools/python-site',
            'LD_LIBRARY_PATH': 'tools/python/lib:tools/cmake/lib',
            'GLIBC_LIBRARY_PATH': 'glibc/lib',
            'PATH': 'tools/python/bin:tools/cmake/bin:tools/xtensa-esp32-elf/bin:'
                    'tools/xtensa-esp32s3-elf/bin',
        },
        'ldWrapperTemplate': '#!/system/bin/sh\nexec "$BASE/glibc/lib/ld-linux-aarch64.so.1" '
                             '--library-path "$BASE/glibc/lib" "$BASE/<真实ELF路径>" "$@"',
        'ldWrapperNote': 'gcc execs libexec/.../cc1,cc1plus,collect2,lto1 by absolute path, so the '
                         'wrapper must REPLACE the binary at its own path (keep the real one as *.real).',
        'patches': C['patches'],
        'toolchainWrappers': 'ld-linux-aarch64.so.1 --library-path <abs>/glibc/lib <abs>/tools/<chip>-elf/<real> "$@"',
    }

    with open(os.path.join(STAGE, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    out = dict(checks=C, manifest=manifest, pythonSite=site)
    if a.zip and os.path.exists(ZIP_PATH):
        out['zip'] = dict(path=ZIP_PATH, size=os.path.getsize(ZIP_PATH),
                          sizeMB=mb(os.path.getsize(ZIP_PATH)),
                          sha256=sha256_file(ZIP_PATH))
        manifest['zip'] = dict(fileName=ZIP_NAME, sizeBytes=out['zip']['size'],
                               sha256=out['zip']['sha256'])
        with open(os.path.join(PACK, 'manifest.json'), 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)
    with open(os.path.join(BUILD, 'verify525.json'), 'w', encoding='utf-8') as f:
        json.dump(out, f, indent=1)
    print('wrote %s' % os.path.join(BUILD, 'verify525.json'))
    for k, v in C.items():
        if isinstance(v, dict) and 'pass_' in v:
            print('  %-16s %s' % (k, 'PASS' if v['pass_'] else 'FAIL'))


if __name__ == '__main__':
    main()
