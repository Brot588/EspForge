#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-pack.py -- build the offline resource pack for the ESP32 IDE Android tablet app.

Output:  <pack>/esp-idf-5.5.1-arm64.zip  (+ .sha256, manifest.json, BUILD.md is written by
         a separate step from the JSON report this script emits with --report)

Layout inside the zip (consumed by the Android app, do not change):

    esp-idf/                 ESP-IDF v5.5.1 source tree (trimmed, submodules complete)
    tools/xtensa-esp-elf/    Espressif aarch64-linux-gnu GCC toolchain (UNMODIFIED binaries)
    tools/esp32ulp-elf/      ULP toolchain (UNMODIFIED binaries)
    tools/cmake/             bionic cmake/ninja/make + lib + share
    tools/python/            Termux bionic python 3.x (bin + lib + deps)
    tools/python-site/       pure-python pip deps (PYTHONPATH target)
    glibc/lib/               aarch64 glibc runtime for the glibc toolchains
    manifest.json

Design constraints honoured here:
  * toolchain ELF binaries are never patched (no patchelf, no PT_INTERP edits);
    the Android app generates `ld-linux-aarch64.so.1 --library-path ... <real> "$@"` wrappers
    at unpack time, so this script only records how many wrappers are needed.
  * symlinks from tar/deb are materialised as real file copies (ZipInputStream cannot
    restore symlinks).
  * .a files are never deleted (static linking needs them).

Stages are resumable: each prints a JSON blob and skips itself if already done (use --force).
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.dirname(HERE)                 # D:\DSH\espstudio\pack
DL = os.path.join(PACK, 'dl')
BUILD = os.path.join(PACK, 'build')
STAGE = os.path.join(BUILD, 'staging')
SCRATCH = os.path.join(BUILD, 'scratch')
LOCKS = os.path.join(BUILD, 'locks')
ZIP_NAME = 'esp-idf-5.5.1-arm64.zip'
ZIP_PATH = os.path.join(PACK, ZIP_NAME)

CMAKE_ZIP = r'D:\DSH\codepad\app\src\main\assets\cmake.zip'

IDF_ZIP = os.path.join(DL, 'esp-idf-v5.5.1.zip')
IDF_ROOT = 'esp-idf-v5.5.1/'
XTENSA_TXZ = os.path.join(DL, 'xtensa-esp-elf-14.2.0_20241119-aarch64-linux-gnu.tar.xz')
ULP_TGZ = os.path.join(DL, 'esp32ulp-elf-2.38_20240113-linux-arm64.tar.gz')

XTENSA_SHA = 'ac2b311dc0003386425086bfc813bf2aeb3cdf3b117845802df6ebef5f69955f'
ULP_SHA = 'ecce0788ce1000e5c669c5adaf2fd5bf7f9bf96dcdbd3555d1d9ce4dcb311038'

SV = 'tools'
P_IDF = os.path.join(STAGE, 'esp-idf')
P_XTENSA = os.path.join(STAGE, 'tools', 'xtensa-esp-elf')
P_ULP = os.path.join(STAGE, 'tools', 'esp32ulp-elf')
P_CMAKE = os.path.join(STAGE, 'tools', 'cmake')
P_PY = os.path.join(STAGE, 'tools', 'python')
P_SITE = os.path.join(STAGE, 'tools', 'python-site')
P_GLIBC = os.path.join(STAGE, 'glibc', 'lib')

sys.path.insert(0, HERE)
from elfinfo import Elf          # noqa: E402
import debx                      # noqa: E402

JSON_OUT = {}


# ----------------------------------------------------------------------------- helpers
def log(msg):
    print(msg, flush=True)


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
            p = os.path.join(dp, f)
            try:
                total += os.path.getsize(p)
                files += 1
            except OSError:
                pass
    return total, files


def rm(path):
    if os.path.isdir(path):
        shutil.rmtree(path, ignore_errors=True)
    elif os.path.exists(path):
        try:
            os.remove(path)
        except OSError:
            pass


def lock(name):
    os.makedirs(LOCKS, exist_ok=True)
    with open(os.path.join(LOCKS, name + '.done'), 'w') as f:
        f.write(time.strftime('%Y-%m-%dT%H:%M:%S'))


def locked(name):
    return os.path.exists(os.path.join(LOCKS, name + '.done'))


def is_elf(path):
    try:
        if os.path.getsize(path) < 64:
            return False
        with open(path, 'rb') as f:
            return f.read(4) == b'\x7fELF'
    except OSError:
        return False


def write_stream(reader, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'wb') as out:
        shutil.copyfileobj(reader, out, 1 << 20)


# ----------------------------------------------------------------------------- stage: esp-idf
SKIP_DIRS_IDF = ('.git', '.gitlab', '.github', 'docs')
KEEP_EXAMPLES = ('examples/get-started/hello_world/', 'examples/get-started/blink/')


def stage_idf(force=False):
    if locked('idf') and not force:
        log('[idf] already staged')
        return
    rm(P_IDF)
    os.makedirs(P_IDF, exist_ok=True)
    z = zipfile.ZipFile(IDF_ZIP)
    infos = z.infolist()
    skipped = 0
    log('[idf] extracting (filtered) ...')
    n = 0
    for i in infos:
        name = i.filename
        if not name.startswith(IDF_ROOT):
            continue
        rel = name[len(IDF_ROOT):]
        if not rel or rel.endswith('/'):
            continue
        parts = rel.split('/')
        if parts[0] in SKIP_DIRS_IDF:
            continue
        if parts[0] == 'examples' and not any(rel.startswith(k) for k in KEEP_EXAMPLES):
            continue
        if '__pycache__' in parts or rel.endswith('.pyc'):
            continue
        dest = os.path.join(P_IDF, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(i) as src, open(dest, 'wb') as out:
            shutil.copyfileobj(src, out, 1 << 20)
        n += 1
    # post-trim: unit-test-app / tools/test_* / tools/<x>/test
    removed = []
    for pat in ('tools/unit-test-app', 'tools/test_*'):
        for p in glob.glob(os.path.join(P_IDF, pat.replace('/', os.sep))):
            removed.append(os.path.relpath(p, P_IDF).replace(os.sep, '/'))
            rm(p)
    for sub in sorted(os.listdir(os.path.join(P_IDF, 'tools'))):
        p = os.path.join(P_IDF, 'tools', sub, 'test')
        if os.path.isdir(p):
            removed.append('tools/%s/test' % sub)
            rm(p)
    sz, nf = dir_stats(P_IDF)
    skipped = len([i for i in infos if i.filename.startswith(IDF_ROOT)]) - n
    log('[idf] wrote %d files (skipped %d zip entries), trimmed: %s' % (n, skipped, removed))
    log('[idf] size %.1f MB / %d files' % (sz / 1024 ** 2, nf))
    JSON_OUT.setdefault('stages', {})['idf'] = dict(files=nf, bytes=sz, trimmed=removed)
    lock('idf')


# ----------------------------------------------------------------------------- stage: toolchains
def tar_extract_materialised(tar_path, dest, strip=0):
    """Extract tar (xz/gz) materialising symlinks and hardlinks as real file copies."""
    t = tarfile.open(tar_path, 'r:*')
    members = t.getmembers()
    links = []
    nfiles = 0
    for m in members:
        name = m.name
        parts = name.split('/')
        if strip:
            parts = parts[strip:]
        if not parts or not parts[-1]:
            continue
        rel = '/'.join(parts)
        destp = os.path.join(dest, rel.replace('/', os.sep))
        if m.isdir():
            os.makedirs(destp, exist_ok=True)
            continue
        if m.issym() or m.islnk():
            base = os.path.dirname(rel)
            target = m.linkname
            if m.islnk():
                tparts = target.split('/')[strip:] if strip else target.split('/')
                resolved = '/'.join(tparts)
            else:
                resolved = os.path.normpath(os.path.join(base, target)).replace(os.sep, '/')
            links.append((rel, resolved))
            continue
        f = t.extractfile(m)
        if f is None:
            continue
        write_stream(f, destp)
        nfiles += 1
    linkmap = dict(links)
    for rel, resolved in links:
        d = os.path.join(dest, rel.replace('/', os.sep))
        cur, seen = resolved, set()
        src = None
        while cur not in seen:
            seen.add(cur)
            cand = os.path.join(dest, cur.replace('/', os.sep))
            if os.path.isfile(cand):
                src = cand
                break
            cur = linkmap.get(cur)
            if cur is None:
                break
        os.makedirs(os.path.dirname(d), exist_ok=True)
        if src:
            shutil.copyfile(src, d)
        else:
            log('   !! unresolved link %s -> %s' % (rel, resolved))
    return dict(files=nfiles, links=len(links))


def prune_toolchain(root):
    """Remove gdb and docs; never touch .a / .o / ELF binaries used by the compiler."""
    killed = []
    for pat in ('bin/*gdb*', 'share/doc', 'share/man', 'share/info'):
        for p in glob.glob(os.path.join(root, pat)):
            killed.append(os.path.relpath(p, root).replace(os.sep, '/'))
            rm(p)
    # gdb-related helper dirs inside the toolchain
    for sub in ('share/gdb', 'libexec/gdb'):
        p = os.path.join(root, sub)
        if os.path.exists(p):
            killed.append(sub)
            rm(p)
    return killed


def stage_xtensa(force=False):
    if locked('xtensa') and not force:
        log('[xtensa] already staged')
        return
    got = sha256_file(XTENSA_TXZ)
    if got != XTENSA_SHA:
        raise SystemExit('xtensa sha256 mismatch: %s' % got)
    rm(P_XTENSA)
    os.makedirs(P_XTENSA, exist_ok=True)
    log('[xtensa] extracting ...')
    info = tar_extract_materialised(XTENSA_TXZ, P_XTENSA, strip=0)
    killed = prune_toolchain(P_XTENSA)
    sz, nf = dir_stats(P_XTENSA)
    log('[xtensa] files=%d links=%d pruned=%s' % (info['files'], info['links'], killed))
    log('[xtensa] size %.1f MB / %d files' % (sz / 1024 ** 2, nf))
    JSON_OUT.setdefault('stages', {})['xtensa'] = dict(files=nf, bytes=sz, pruned=killed, **info)
    lock('xtensa')


def stage_ulp(force=False):
    if locked('ulp') and not force:
        log('[ulp] already staged')
        return
    got = sha256_file(ULP_TGZ)
    if got != ULP_SHA:
        raise SystemExit('ulp sha256 mismatch: %s' % got)
    rm(P_ULP)
    os.makedirs(P_ULP, exist_ok=True)
    log('[ulp] extracting ...')
    # tar has a single root dir "esp32ulp-elf/"
    info = tar_extract_materialised(ULP_TGZ, P_ULP, strip=1)
    killed = prune_toolchain(P_ULP)
    sz, nf = dir_stats(P_ULP)
    log('[ulp] files=%d links=%d pruned=%s size %.1f MB' % (info['files'], info['links'], killed, sz / 1024 ** 2))
    JSON_OUT.setdefault('stages', {})['ulp'] = dict(files=nf, bytes=sz, pruned=killed, **info)
    lock('ulp')


# ----------------------------------------------------------------------------- stage: cmake
def stage_cmake(force=False):
    if locked('cmake') and not force:
        log('[cmake] already staged')
        return
    rm(P_CMAKE)
    os.makedirs(P_CMAKE, exist_ok=True)
    z = zipfile.ZipFile(CMAKE_ZIP)
    n = 0
    for i in z.infolist():
        rel = i.filename
        if rel.endswith('/'):
            continue
        if rel.startswith('__MACOSX'):
            continue
        # keep bin/, lib/, share/ - share/cmake-*/Modules is required at runtime
        if not re.match(r'^(bin|lib|share)/', rel):
            log('[cmake] skipping unexpected entry %s' % rel)
            continue
        dest = os.path.join(P_CMAKE, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(i) as src, open(dest, 'wb') as out:
            shutil.copyfileobj(src, out, 1 << 20)
        n += 1
    sz, nf = dir_stats(P_CMAKE)
    log('[cmake] extracted %d entries, %.1f MB' % (n, sz / 1024 ** 2))
    JSON_OUT.setdefault('stages', {})['cmake'] = dict(files=nf, bytes=sz)
    lock('cmake')


# ----------------------------------------------------------------------------- stage: python
TERMUX_DEBS = ['python', 'libandroid-support', 'libandroid-posix-semaphore', 'libbz2', 'libcrypt',
               'libexpat', 'libffi', 'liblzma', 'libsqlite', 'ncurses', 'ncurses-ui-libs',
               'openssl', 'readline', 'zlib', 'gdbm']
TERMUX_EXTRA = ['python-cryptography', 'python-psutil']
TERMUX_PREFIX = os.path.join('data', 'data', 'com.termux', 'files', 'usr')
TERMINFO_KEEP = ('x', 'v', 'a', 'd', 's', 't', 'r')


def find_deb(pkg_base):
    """pkg_base e.g. 'python' -> matching .deb in dl/ (version + arch ignored)."""
    cands = [p for p in glob.glob(os.path.join(DL, '*.deb'))
             if os.path.basename(p).startswith(pkg_base + '_') and
             not os.path.basename(p).startswith(pkg_base + '-')]
    if not cands:
        return None
    cands.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return cands[0]


def deb_usr_root(deb, name):
    """Extract a deb into scratch and return its .../usr root."""
    safe = re.sub(r'[^A-Za-z0-9_.+-]', '_', name)
    d = os.path.join(SCRATCH, 'deb', safe)
    usr = os.path.join(d, TERMUX_PREFIX)
    if not os.path.isdir(usr):
        rm(d)
        debx.extract(deb, d)
    return usr


def stage_python(force=False):
    if locked('python') and not force:
        log('[python] already staged')
        return
    rm(P_PY)
    os.makedirs(os.path.join(P_PY, 'bin'), exist_ok=True)
    os.makedirs(os.path.join(P_PY, 'lib'), exist_ok=True)

    deb = find_deb('python')
    usr = deb_usr_root(deb, 'python')
    # which python3.x ?
    real = [f for f in os.listdir(os.path.join(usr, 'bin')) if re.match(r'^python3\.\d+$', f)]
    if not real:
        raise SystemExit('no python3.x binary in %s' % deb)
    exe = real[0]
    ver = exe[len('python'):]
    log('[python] termux python version %s from %s' % (ver, os.path.basename(deb)))

    # bin: real binary + the conventional names, as real copies (no symlinks in the zip)
    src_bin = os.path.join(usr, 'bin', exe)
    for name in (exe, 'python3', 'python'):
        shutil.copy2(src_bin, os.path.join(P_PY, 'bin', name))

    # stdlib
    stdlib_src = os.path.join(usr, 'lib', 'python' + ver)
    stdlib_dst = os.path.join(P_PY, 'lib', 'python' + ver)
    EXCLUDE_STDLIB = {'test', 'idlelib', 'turtledemo', 'ensurepip', 'lib2to3', 'pydoc_data'}
    n = 0
    for dp, dns, fns in os.walk(stdlib_src):
        dns[:] = [d for d in dns if d not in EXCLUDE_STDLIB and d != '__pycache__']
        rel = os.path.relpath(dp, stdlib_src)
        if rel != '.' and rel.split(os.sep)[0] in EXCLUDE_STDLIB:
            continue
        for f in fns:
            if f.endswith('.pyc'):
                continue
            s = os.path.join(dp, f)
            d = os.path.join(stdlib_dst, rel, f)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(s, d)
            n += 1
    log('[python] stdlib files: %d' % n)

    # shared libs from the python package itself
    libs_from_python = []
    for p in glob.glob(os.path.join(usr, 'lib', '*.so*')):
        if os.path.isfile(p):
            shutil.copy2(p, os.path.join(P_PY, 'lib', os.path.basename(p)))
            libs_from_python.append(os.path.basename(p))

    # dependency .so files
    dep_libs = []
    for pkg in TERMUX_DEBS:
        if pkg == 'python':
            continue
        d = find_deb(pkg)
        if not d:
            log('[python] !! dependency deb missing: %s' % pkg)
            continue
        u = deb_usr_root(d, pkg)
        for p in glob.glob(os.path.join(u, 'lib', '*.so*')):
            if os.path.isfile(p):
                shutil.copy2(p, os.path.join(P_PY, 'lib', os.path.basename(p)))
                dep_libs.append(os.path.basename(p))
        # terminfo for curses/readline (subset)
        ti = os.path.join(u, 'share', 'terminfo')
        if os.path.isdir(ti):
            for sub in os.listdir(ti):
                if sub[:1] not in TERMINFO_KEEP:
                    continue
                for dp, _dn, fns in os.walk(os.path.join(ti, sub)):
                    for f in fns:
                        if '\x00' in f or not f:
                            continue
                        s = os.path.join(dp, f)
                        rel = os.path.relpath(s, ti)
                        d2 = os.path.join(P_PY, 'share', 'terminfo', rel)
                        os.makedirs(os.path.dirname(d2), exist_ok=True)
                        try:
                            shutil.copy2(s, d2)
                        except OSError:
                            pass

    # compiled site-packages from termux (cryptography, psutil) - installed into python-site
    os.makedirs(P_SITE, exist_ok=True)
    extra = {}
    for pkg in TERMUX_EXTRA:
        d = find_deb(pkg)
        if not d:
            log('[python] !! termux extra missing: %s' % pkg)
            continue
        u = deb_usr_root(d, pkg)
        sp = os.path.join(u, 'lib', 'python' + ver, 'site-packages')
        cnt = 0
        if os.path.isdir(sp):
            for dp, dns, fns in os.walk(sp):
                dns[:] = [x for x in dns if x != '__pycache__']
                rel = os.path.relpath(dp, sp)
                for f in fns:
                    if f.endswith('.pyc'):
                        continue
                    s = os.path.join(dp, f)
                    dst = os.path.join(P_SITE, rel, f) if rel != '.' else os.path.join(P_SITE, f)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copy2(s, dst)
                    cnt += 1
        extra[pkg] = cnt
        log('[python] %s -> python-site files: %d' % (pkg, cnt))

    sz, nf = dir_stats(P_PY)
    log('[python] %s: %.1f MB / %d files; libs: %d' % (ver, sz / 1024 ** 2, nf, len(dep_libs)))
    JSON_OUT.setdefault('stages', {})['python'] = dict(
        version=ver, deb=os.path.basename(deb), files=nf, bytes=sz,
        stdlib_files=n, dep_libs=dep_libs, extra=extra)
    lock('python')


# ----------------------------------------------------------------------------- stage: python-site
SITE_PKGS = [
    'packaging', 'click', 'pyserial', 'pyparsing', 'pyelftools', 'construct', 'rich',
    'esp-idf-kconfig', 'esp-idf-monitor', 'esp-idf-size', 'esp-idf-diag', 'esptool',
    'esp-coredump', 'esp-idf-nvs-partition-gen', 'esp-idf-panic-decoder', 'setuptools',
]
# packages shipped as sdist-only / platform-only on PyPI; resolved as pure-python by hand
SDIST_VENDORED = {
    'pyyaml': ('lib/yaml', 'yaml'),
}
# provided from Termux debs (compiled against bionic) instead of PyPI
FROM_TERMUX = ('cryptography', 'psutil')


def pip(args, dry=False):
    cmd = [sys.executable, '-m', 'pip'] + args
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0 and not dry:
        log('   pip rc=%d: %s' % (r.returncode, (r.stderr or r.stdout).strip().splitlines()[-1:]))
    return r.returncode == 0, (r.stdout or '') + (r.stderr or '')


PLATFORM_FLAGS = ['--only-binary=:all:', '--platform', 'any', '--abi', 'none',
                  '--python-version', '3.12', '--implementation', 'py']


def site_install(pkg, target):
    """Install one package (no deps) preferring a pure-python wheel, then sdist build."""
    ok, out = pip(['install', '--disable-pip-version-check', '--target', target, '--no-compile',
                   '--upgrade', '--no-deps', '--no-warn-script-location'] + PLATFORM_FLAGS + [pkg])
    if ok:
        return 'wheel-pure'
    ok2, out2 = pip(['install', '--disable-pip-version-check', '--target', target, '--no-compile',
                     '--upgrade', '--no-deps', '--no-warn-script-location',
                     '--no-binary=:all:'] + [pkg])
    if ok2:
        return 'sdist-built'
    return 'FAILED'


def vendor_sdist(pkg, subdir, modname, target):
    """Extract a pure python package straight out of its sdist (no build step at all)."""
    d = os.path.join(SCRATCH, 'sdist')
    os.makedirs(d, exist_ok=True)
    ok, out = pip(['download', '--no-deps', '--no-binary=:all:', '-d', d, '--no-build-isolation',
                   pkg])
    tars = glob.glob(os.path.join(d, pkg.replace('-', '_') + '-*.tar.gz')) + \
        glob.glob(os.path.join(d, pkg + '-*.tar.gz'))
    if not tars:
        return None
    tars.sort(key=os.path.getmtime, reverse=True)
    t = tarfile.open(tars[0], 'r:gz')
    base = t.getmembers()[0].name.split('/')[0]
    src_prefix = base + '/' + subdir + '/'
    dest_root = os.path.join(target, modname)
    n = 0
    for m in t.getmembers():
        if not m.isfile() or not m.name.startswith(src_prefix):
            continue
        rel = m.name[len(src_prefix):]
        if not rel or '__pycache__' in rel:
            continue
        write_stream(t.extractfile(m), os.path.join(dest_root, rel.replace('/', os.sep)))
        n += 1
    t.close()
    ver = base[len(pkg) + 1:] if base.startswith(pkg) else ''
    return dict(version=ver, files=n, sdist=os.path.basename(tars[0]))


def audit_site():
    """Report distributions whose Requires-Dist are not satisfiable inside python-site."""
    have = set()
    for d in os.listdir(P_SITE):
        if d.endswith(('.dist-info', '.egg-info')):
            have.add(re.split(r'-\d', d)[0].lower().replace('_', '-'))
    for d in os.listdir(P_SITE):
        if os.path.isdir(os.path.join(P_SITE, d)) and d not in have:
            have.add(d.lower().replace('_', '-'))
    missing = {}
    for d in os.listdir(P_SITE):
        md = os.path.join(P_SITE, d, 'METADATA')
        if not d.endswith('.dist-info') or not os.path.isfile(md):
            continue
        name = d.split('-')[0]
        try:
            text = open(md, 'r', encoding='utf-8', errors='replace').read()
        except OSError:
            continue
        for line in text.splitlines():
            if not line.startswith('Requires-Dist:'):
                continue
            spec = line.split(':', 1)[1].strip()
            dep, _, marker = spec.partition(';')
            dep_name = re.split(r'[<>=!~\[ (]', dep.strip())[0].lower().replace('_', '-')
            marker = marker.strip()
            if marker and ('extra ==' in marker or 'extra =="' in marker):
                continue                      # optional extras are not installed
            if marker and 'win32' in marker:
                continue
            if dep_name in have or dep_name in (n.lower() for n in FROM_TERMUX):
                continue
            if dep_name in ('pyyaml', 'cryptography', 'psutil'):
                continue
            if 'sys_platform == "win32"' in marker or 'platform_system == "Windows"' in marker:
                continue
            missing.setdefault(dep_name, []).append(name)
    return dict(installed=sorted(have), missing=missing)


def stage_site(force=False):
    if locked('site') and not force:
        log('[site] already staged')
        return
    os.makedirs(P_SITE, exist_ok=True)
    result = {}
    log('[site] installing %d packages ...' % len(SITE_PKGS))
    pending = list(SITE_PKGS)
    rounds = 0
    while pending and rounds < 4:
        rounds += 1
        for pkg in pending:
            how = site_install(pkg, P_SITE)
            result[pkg] = how
            log('   %-28s %s' % (pkg, how))
        pending = []
        # resolve transitive deps that pip never saw because of --no-deps
        aud = audit_site()
        for dep in sorted(aud['missing']):
            if dep in result:
                continue
            result[dep] = site_install(dep, P_SITE)
            log('   dep %-24s %s' % (dep, result[dep]))
            pending.append(dep)
        pending = []
    # vendor sdist-only pure packages
    for pkg, (sub, mod) in SDIST_VENDORED.items():
        info = vendor_sdist(pkg, sub, mod, P_SITE)
        result[pkg] = info or 'FAILED'
        log('[site] vendored %s: %s' % (pkg, info))
    aud = audit_site()
    sz, nf = dir_stats(P_SITE)
    log('[site] %.1f MB / %d files; unsatisfied deps: %s' % (sz / 1024 ** 2, nf, aud['missing']))
    JSON_OUT.setdefault('stages', {})['site'] = dict(
        result=result, files=nf, bytes=sz, missing=aud['missing'], installed=aud['installed'])
    lock('site')



# ----------------------------------------------------------------------------- stage: glibc
GLIBC_DEBS = ['libc6', 'libstdc++6', 'libgcc-s1', 'zlib1g']
GLIBC_REQUIRED = ['ld-linux-aarch64.so.1', 'libc.so.6', 'libm.so.6', 'libstdc++.so.6',
                  'libgcc_s.so.1', 'libz.so.1', 'libdl.so.2', 'libpthread.so.0',
                  'librt.so.1', 'libresolv.so.2', 'libutil.so.1', 'libanl.so.1']


def stage_glibc(force=False):
    if locked('glibc') and not force:
        log('[glibc] already staged')
        return
    rm(P_GLIBC)
    os.makedirs(P_GLIBC, exist_ok=True)
    index = {}
    for pkg in GLIBC_DEBS:
        d = find_deb(pkg)
        if not d:
            raise SystemExit('missing deb for %s' % pkg)
        safe = re.sub(r'[^A-Za-z0-9_.+-]', '_', pkg)
        dst = os.path.join(SCRATCH, 'deb', 'debian_' + safe)
        if not os.path.isdir(os.path.join(dst, 'usr')):
            rm(dst)
            debx.extract(d, dst)
        for dp, _dn, fns in os.walk(dst):
            for f in fns:
                p = os.path.join(dp, f)
                if '.so' in f:
                    index.setdefault(f, p)
        log('[glibc] indexed %s (%s)' % (pkg, os.path.basename(d)))

    # closure over toolchain ELF files
    entries = []
    for root in (P_XTENSA, P_ULP):
        for dp, _dn, fns in os.walk(root):
            for f in fns:
                if f.endswith(('.so', '.a', '.o')) or not is_elf(os.path.join(dp, f)):
                    continue
                entries.append(os.path.join(dp, f))
    needed = set(GLIBC_REQUIRED)
    queue = list(entries)
    seen = set()
    while queue:
        p = queue.pop()
        if p in seen:
            continue
        seen.add(p)
        try:
            e = Elf(p)
        except Exception:
            continue
        for n in e.needed:
            if n in needed:
                continue
            if n in index or n in index:
                needed.add(n)
                queue.append(index[n])
            else:
                # bionic-only libs (liblog.so etc.) never show up for glibc binaries, but be noisy
                if n.startswith('libc.so') or n.startswith('libm.so'):
                    needed.add(n)
    missing = [n for n in sorted(needed) if n not in index]
    copied = []
    for n in sorted(needed):
        if n in index:
            shutil.copy2(index[n], os.path.join(P_GLIBC, n))
            copied.append(n)
    sz, nf = dir_stats(P_GLIBC)
    log('[glibc] copied %d libs, %.1f MB; unresolved: %s' % (len(copied), sz / 1024 ** 2, missing))
    JSON_OUT.setdefault('stages', {})['glibc'] = dict(libs=copied, bytes=sz, files=nf,
                                                      unresolved=missing, closure_scanned=len(seen))
    lock('glibc')


# ----------------------------------------------------------------------------- zip
def collect_zip_entries():
    rows = []
    for dp, dns, fns in os.walk(STAGE):
        dns.sort()
        for f in sorted(fns):
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, STAGE).replace(os.sep, '/')
            rows.append((rel, p))
    rows.sort()
    return rows


def exec_mode(path):
    """Mode bits stored in the zip. Android unzip via ZipInputStream ignores these, but
    they document intent and are used by the build script's own wrapper counting."""
    if is_elf(path):
        try:
            e = Elf(path)
            if e.is_executable:
                return 0o755
        except Exception:
            pass
        return 0o644
    try:
        with open(path, 'rb') as f:
            head = f.read(2)
        if head == b'#!':
            return 0o755
    except OSError:
        pass
    return 0o644


def make_zip():
    rows = collect_zip_entries()
    log('[zip] %d entries -> %s' % (len(rows), ZIP_PATH))
    rm(ZIP_PATH)
    t0 = time.time()
    fixed_time = (2025, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(ZIP_PATH, 'w', zipfile.ZIP_DEFLATED, compresslevel=6,
                         allowZip64=True) as zf:
        for i, (rel, p) in enumerate(rows, 1):
            zi = zipfile.ZipInfo(rel, date_time=fixed_time)
            zi.compress_type = zipfile.ZIP_DEFLATED
            mode = exec_mode(p)
            zi.external_attr = (mode & 0xFFFF) << 16
            zi.file_size = os.path.getsize(p)
            with open(p, 'rb') as src, zf.open(zi, 'w') as dst:
                shutil.copyfileobj(src, dst, 1 << 20)
            if i % 5000 == 0:
                log('[zip] %d/%d (%.0fs)' % (i, len(rows), time.time() - t0))
    log('[zip] done in %.0fs, %.1f MB' % (time.time() - t0, os.path.getsize(ZIP_PATH) / 1024 ** 2))
    return ZIP_PATH


# ----------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', default='all')
    ap.add_argument('--force', action='store_true')
    a = ap.parse_args()

    os.makedirs(LOCKS, exist_ok=True)
    stages = {
        'idf': stage_idf, 'xtensa': stage_xtensa, 'ulp': stage_ulp, 'cmake': stage_cmake,
        'python': stage_python, 'glibc': stage_glibc, 'site': stage_site,
    }
    if a.stage == 'all':
        for fn in stages.values():
            fn(force=a.force)
        make_zip()
    elif a.stage in stages:
        stages[a.stage](force=a.force)
    elif a.stage == 'zip':
        make_zip()
    else:
        raise SystemExit('unknown stage %s' % a.stage)
    print(json.dumps(JSON_OUT, indent=1))


if __name__ == '__main__':
    main()
