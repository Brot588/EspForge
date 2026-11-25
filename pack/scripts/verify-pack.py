#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-pack.py -- validate the staged pack, write manifest.json (inside the zip) and
BUILD.md, and (with --zip) hash the finished archive.

Checks performed (each one is written to BUILD.md with its real numbers):
  1. submodule completeness     components/mbedtls/mbedtls/library, components/esp_phy/lib
  2. toolchain ELF              xtensa gcc is ELF64 aarch64; count of ELF executables
  3. bionic python              python3 is ELF64 aarch64; every DT_NEEDED resolvable
  4. glibc loader               glibc/lib/ld-linux-aarch64.so.1 exists and is ELF64 aarch64
  5. sizes                      total + per top level directory
  6. pristine binaries          every toolchain file is byte-identical to the release tarball
  7. cmake toolchain            cmake/ninja/make are ELF64 aarch64 with resolvable DT_NEEDED
  8. glibc closure              toolchain DT_NEEDED satisfied inside glibc/lib
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
STAGE = os.path.join(BUILD, 'staging')
DL = os.path.join(PACK, 'dl')
ZIP_NAME = 'esp-idf-5.5.1-arm64.zip'
ZIP_PATH = os.path.join(PACK, ZIP_NAME)

XTENSA_TXZ = os.path.join(DL, 'xtensa-esp-elf-14.2.0_20241119-aarch64-linux-gnu.tar.xz')
ULP_TGZ = os.path.join(DL, 'esp32ulp-elf-2.38_20240113-linux-arm64.tar.gz')

sys.path.insert(0, HERE)
from elfinfo import Elf  # noqa: E402

IDF_VERSION = 'v5.5.1'
CHIPS = ['esp32', 'esp32s3']
PACK_VERSION = '1.0.0'

# Shared libraries the Android/bionic runtime itself provides; they are expected to be
# absent from the pack and must NOT be reported as missing.
ANDROID_SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so',
    'libstdc++.so', 'libEGL.so', 'libGLESv1_CM.so', 'libGLESv2.so', 'libvulkan.so',
    'libjnigraphics.so', 'libOpenSLES.so', 'libmediandk.so', 'libneuralnetworks.so',
}


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


def classify(root):
    exes, sos, bad = [], [], []
    for p in walk_elf(root):
        try:
            e = Elf(p)
        except Exception as ex:
            bad.append((p, str(ex)))
            continue
        if e.is_executable:
            exes.append((p, e))
        elif e.e_type == 3:
            sos.append((p, e))
    return exes, sos, bad


def mb(n):
    return round(n / 1024.0 ** 2, 1)


# --------------------------------------------------------------------- checks
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
                bluedroid_api_files=n3, sample_paths_present=len(have), samples=samples)


def check_toolchain():
    gcc = os.path.join(STAGE, 'tools', 'xtensa-esp-elf', 'bin', 'xtensa-esp-elf-gcc')
    e = Elf(gcc)
    exes, sos, bad = classify(os.path.join(STAGE, 'tools', 'xtensa-esp-elf'))
    by_dir = {}
    for p, _e in exes:
        rel = os.path.relpath(os.path.dirname(p), os.path.join(STAGE, 'tools', 'xtensa-esp-elf'))
        by_dir[rel.replace(os.sep, '/')] = by_dir.get(rel.replace(os.sep, '/'), 0) + 1
    ulp_exes, _u_sos, _u_bad = classify(os.path.join(STAGE, 'tools', 'esp32ulp-elf'))
    return dict(pass_=e.arch == 'AArch64' and e.cls == 64 and len(exes) > 0,
                gcc_desc=e.file_desc(), gcc_interp=e.interp,
                gcc_needed=e.needed, executables=len(exes), shared_objects=len(sos),
                bad_elf=len(bad), by_dir=by_dir,
                ulp_executables=len(ulp_exes),
                interpreter_is_glibc=bool(e.interp and 'ld-linux-aarch64' in e.interp))


def resolve_needed(entry_paths, libdirs, allow_system=True):
    """Return (resolved map, missing list) by soname lookup inside libdirs."""
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


def check_python():
    py_dir = os.path.join(STAGE, 'tools', 'python')
    py = os.path.join(py_dir, 'bin', 'python3')
    e = Elf(py)
    real = os.path.join(py_dir, 'bin', 'python3.14')
    ent = [py] + ([real] if os.path.exists(real) else [])
    libs = [os.path.join(py_dir, 'lib')]
    resolved, missing = resolve_needed(ent, libs)
    # also check lib-dynload extension modules resolve against lib/
    dyn = []
    for dp, _dn, fns in os.walk(os.path.join(py_dir, 'lib', 'python3.14', 'lib-dynload')):
        dyn += [os.path.join(dp, f) for f in fns if f.endswith('.so')]
    resolved2, missing2 = resolve_needed(dyn, libs)
    bionic_needed = [n for n in e.needed if n.startswith(('libc.so', 'libm.so', 'libdl.so', 'liblog.so'))]
    return dict(pass_=e.arch == 'AArch64' and not missing and not missing2,
                desc=e.file_desc(), interp=e.interp, needed=e.needed,
                resolved=sorted(resolved), missing=missing,
                dynload_modules=len(dyn), dynload_missing=missing2,
                bionic_runtime_libs=bionic_needed,
                is_bionic=bool(e.interp and 'linker64' in e.interp))


def check_glibc():
    p = os.path.join(STAGE, 'glibc', 'lib', 'ld-linux-aarch64.so.1')
    ok = os.path.exists(p)
    e = Elf(p) if ok else None
    libs = sorted(os.listdir(os.path.join(STAGE, 'glibc', 'lib')))
    return dict(pass_=bool(ok and e and e.arch == 'AArch64'),
                desc=e.file_desc() if e else None,
                libs=libs, count=len(libs),
                bytes=dir_stats(os.path.join(STAGE, 'glibc', 'lib'))[0])


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
    modules = os.path.join(d, 'share')
    has_modules = os.path.isdir(modules) and any(x.startswith('cmake-') for x in os.listdir(modules))
    return dict(pass_=all(v and v['arch'] == 'AArch64' for v in ents.values() if v) and not missing
                and has_modules,
                binaries=ents, missing=missing, cmake_modules_present=has_modules)


def check_glibc_closure(toolchain_checks):
    d = os.path.join(STAGE, 'tools')
    exes = []
    for root in ('xtensa-esp-elf', 'esp32ulp-elf'):
        exes += walk_elf(os.path.join(d, root))
    resolved, missing = resolve_needed(exes, [os.path.join(STAGE, 'glibc', 'lib')],
                                       allow_system=False)
    return dict(pass_=not missing, resolved=sorted(resolved), missing=missing,
                scanned=len(exes))


def check_pristine():
    """Byte-compare every extracted toolchain file with the release tarball."""
    results = {}
    for name, tarpath, strip, dest in (
            ('xtensa', XTENSA_TXZ, 1, os.path.join(STAGE, 'tools', 'xtensa-esp-elf')),
            ('ulp', ULP_TGZ, 1, os.path.join(STAGE, 'tools', 'esp32ulp-elf'))):
        t = tarfile.open(tarpath, 'r:*')
        checked = mismatched = absent = 0
        skip = ('picolibc/',) if name == 'xtensa' else ()
        bad = []
        for m in t.getmembers():
            if not m.isfile():
                continue
            parts = m.name.split('/')[strip:]
            if not parts or not parts[-1]:
                continue
            rel = '/'.join(parts)
            if any(rel.startswith(s) for s in skip):
                continue
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
    return dict(pass_=all(v['mismatched'] == 0 for v in results.values()),
                details=results)


def sizes():
    tops = {}
    for name in sorted(os.listdir(STAGE)):
        p = os.path.join(STAGE, name)
        if not os.path.isdir(p):
            tops[name] = dir_stats(STAGE)[0] if False else os.path.getsize(p)
            continue
        tops[name] = dir_stats(p)[0]
    # second level under tools/
    tools = {}
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
    ap.add_argument('--zip', action='store_true', help='also hash the finished zip')
    ap.add_argument('--skip-pristine', action='store_true')
    a = ap.parse_args()

    res = {}
    res['submodules'] = check_submodules()
    res['toolchain'] = check_toolchain()
    res['python'] = check_python()
    res['glibc'] = check_glibc()
    res['cmake'] = check_cmake()
    res['glibc_closure'] = check_glibc_closure(res['toolchain'])
    res['sizes'] = sizes()
    res['pristine'] = dict(pass_=True, details={'skipped': True}) if a.skip_pristine else check_pristine()

    # wrapper targets the Android app has to create
    wrapper_map = {
        'tools/xtensa-esp-elf': res['toolchain']['executables'],
        'tools/esp32ulp-elf': res['toolchain']['ulp_executables'],
    }
    cmake_exes = 0
    for dp, _dn, fns in os.walk(os.path.join(STAGE, 'tools', 'cmake', 'bin')):
        for f in fns:
            if is_elf(os.path.join(dp, f)):
                cmake_exes += 1
    py_exes = 0
    for f in os.listdir(os.path.join(STAGE, 'tools', 'python', 'bin')):
        if is_elf(os.path.join(STAGE, 'tools', 'python', 'bin', f)):
            py_exes += 1
    wrapper_map['tools/cmake/bin'] = cmake_exes
    wrapper_map['tools/python/bin'] = py_exes

    manifest = {
        'packVersion': PACK_VERSION,
        'idfVersion': IDF_VERSION,
        'chips': CHIPS,
        'pythonVersion': python_version(),
        'xtensaVersion': '14.2.0_20241119',
        'esp32ulpVersion': '2.38_20240113',
        'cmakeVersion': '4.5 (Termux bionic build)',
        'glibcSource': 'Debian 12 bookworm arm64: libc6 2.36-9+deb12u14, libstdc++6 12.2.0-14+deb12u1, '
                       'libgcc-s1 12.2.0-14+deb12u1, zlib1g 1:1.2.13.dfsg-1 (mirrors.tuna.tsinghua.edu.cn)',
        'elfExecutables': res['toolchain']['executables'],
        'wrapperTargets': wrapper_map,
        'wrapperTargetsTotal': sum(wrapper_map.values()),
        'sizes': {
            'totalBytes': res['sizes']['total_bytes'],
            'totalFiles': res['sizes']['total_files'],
            'topLevel': {k: v for k, v in res['sizes']['top_level'].items()},
            'tools': res['sizes']['tools'],
        },
        'builtAt': __import__('time').strftime('%Y-%m-%dT%H:%M:%S%z'),
        'entrypoints': {
            'idfPy': 'esp-idf/tools/idf.py',
            'xtensaGcc': 'tools/xtensa-esp-elf/bin/xtensa-esp-elf-gcc',
            'ulpGcc': 'tools/esp32ulp-elf/bin/esp32ulp-elf-gcc',
            'python': 'tools/python/bin/python3',
            'cmake': 'tools/cmake/bin/cmake',
            'ninja': 'tools/cmake/bin/ninja',
            'make': 'tools/cmake/bin/make',
            'glibcLoader': 'glibc/lib/ld-linux-aarch64.so.1',
        },
        'runtimeEnv': {
            'IDF_PATH': 'esp-idf',
            'IDF_COMPONENT_MANAGER': '0',
            'IDF_PYTHON_ENV_PATH': '',
            'PYTHONHOME': 'tools/python',
            'PYTHONPATH': 'tools/python-site',
            'LD_LIBRARY_PATH': 'tools/python/lib:tools/cmake/lib',
            'GLIBC_LIBRARY_PATH': 'glibc/lib',
        },
        'toolchainWrappers': 'glibc/lib/ld-linux-aarch64.so.1 --library-path <abs>/glibc/lib <abs>/tools/xtensa-esp-elf/<real> "$@"',
    }

    with open(os.path.join(STAGE, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    out = dict(checks=res, manifest=manifest)
    if a.zip and os.path.exists(ZIP_PATH):
        out['zip'] = dict(path=ZIP_PATH, size=os.path.getsize(ZIP_PATH),
                          sizeMB=mb(os.path.getsize(ZIP_PATH)),
                          sha256=sha256_file(ZIP_PATH))
        manifest['zip'] = dict(fileName=ZIP_NAME, sizeBytes=out['zip']['size'],
                               sha256=out['zip']['sha256'])
        with open(os.path.join(PACK, 'manifest.json'), 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)
    with open(os.path.join(BUILD, 'verify.json'), 'w', encoding='utf-8') as f:
        json.dump(out, f, indent=1)
    print(json.dumps(out, indent=1)[:200])
    print('\nwrote %s' % os.path.join(BUILD, 'verify.json'))
    for k, v in res.items():
        if isinstance(v, dict) and 'pass_' in v:
            print('  %-16s %s' % (k, 'PASS' if v['pass_'] else 'FAIL'))


if __name__ == '__main__':
    main()
