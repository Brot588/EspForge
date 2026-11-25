#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-pack-525.py -- build the offline resource pack for the ESP32 IDE Android tablet app.

Target: ESP-IDF v5.2.5 with the official *per-chip* xtensa toolchains.

Why 5.2.5 and not 5.5.1: IDF >= 5.3 ships the unified `xtensa-esp-elf` toolchain whose
per-chip names (`xtensa-esp32-elf-gcc`, ...) are tiny Rust dispatchers that identify
themselves via /proc/self/exe and assert the basename matches `xtensa-esp*-elf-*`.
Started through `ld-linux-aarch64.so.1 --library-path ...`, /proc/self/exe is the loader,
so they abort. 5.2.5 still ships plain gcc/binutils per chip, which work fine behind a
loader wrapper.  (Verified on device by the parent agent.)

Layout inside the zip (consumed by the Android app, do not change):

    esp-idf/                    ESP-IDF v5.2.5 source tree (trimmed, submodules complete)
    tools/xtensa-esp32-elf/     per-chip GCC 12.2.0 toolchain (UNMODIFIED binaries)
    tools/xtensa-esp32s3-elf/   per-chip GCC 12.2.0 toolchain (UNMODIFIED binaries)
    tools/cmake/                bionic cmake/ninja/make + lib/ (+ android/api-level.h)
    tools/python/               Termux bionic python 3.x
    tools/python-site/          pure-python pip deps
    glibc/lib/                  aarch64 glibc runtime for the glibc toolchains
    manifest.json

Hard rules honoured here:
  * no patchelf, no PT_INTERP edits: toolchain ELF files are byte-identical to the release
    tarballs (verify-pack-525.py re-hashes every file against the tarball to prove it).
    The Android app generates `ld-linux-aarch64.so.1 --library-path <glibc/lib> <real> "$@"`
    wrappers at unpack time.
  * symlinks/hardlinks from tar and deb archives are materialised as real file copies,
    because the app unzips with ZipInputStream, which cannot recreate symlinks.
  * .a / .o files are never deleted (static linking needs them).

Stages are resumable (locks under build/locks525); use --force to redo one.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.dirname(HERE)
DL = os.path.join(PACK, 'dl')
BUILD = os.path.join(PACK, 'build')
STAGE = os.path.join(BUILD, 'staging525')
SCRATCH = os.path.join(BUILD, 'scratch')
LOCKS = os.path.join(BUILD, 'locks525')
TERMUX_LIBS = os.path.join(BUILD, 'termux-libs')
ZIP_NAME = 'esp-idf-5.2.5-arm64.zip'
ZIP_PATH = os.path.join(PACK, ZIP_NAME)

CMAKE_ZIP = r'D:\DSH\codepad\app\src\main\assets\cmake.zip'

IDF_ZIP = os.path.join(DL, 'esp-idf-v5.2.5.zip')
IDF_ROOT = 'esp-idf-v5.2.5/'

# (staged name, archive, sha256)
TOOLCHAINS = [
    ('xtensa-esp32-elf', os.path.join(DL, 'xtensa-esp32-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz'),
     '9e211a182b6ea0396a41c78f52f51d964e7875fe274ea9c81111bf0dbc90c516'),
    ('xtensa-esp32s3-elf', os.path.join(DL, 'xtensa-esp32s3-elf-12.2.0_20230208-aarch64-linux-gnu.tar.xz'),
     '30a1fed3ab6341feb1ae986ee55f227df6a594293ced13c65a0136eb4681087d'),
]

P_IDF = os.path.join(STAGE, 'esp-idf')
P_TOOLS = os.path.join(STAGE, 'tools')
P_CMAKE = os.path.join(P_TOOLS, 'cmake')
P_PY = os.path.join(P_TOOLS, 'python')
P_SITE = os.path.join(P_TOOLS, 'python-site')
P_GLIBC = os.path.join(STAGE, 'glibc', 'lib')

sys.path.insert(0, HERE)
from elfinfo import Elf          # noqa: E402
import debx                      # noqa: E402

JSON_OUT = {}
PATCH_REPORT = {}


def log(msg):
    print(msg, flush=True)


# ----------------------------------------------------------------------------- helpers
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


def stage_patch(force=False):
    """Apply the two/three Android patches from patch-idf-android.py and record the outcome."""
    if locked('patch') and not force:
        log('[patch] already applied')
        return
    script = os.path.join(HERE, 'patch-idf-android.py')
    targets = [os.path.join(P_IDF, 'tools', 'idf_tools.py'),
               os.path.join(P_IDF, 'tools', 'idf.py')]
    r = subprocess.run([sys.executable, script] + targets, capture_output=True, text=True)
    out = (r.stdout or '') + (r.stderr or '')
    log(out.strip())
    hits = {}
    for key in ('platform', 'dep-check', 'extension'):
        if '%s: 已打补丁' % key in out or '%s: 修正旧补丁' % key in out:
            hits[key] = 'patched'
        elif '%s: 已有' % key in out:
            hits[key] = 'already'
        elif '%s: 找不到锚点' % key:
            hits[key] = 'ANCHOR-NOT-FOUND'
        else:
            hits[key] = 'unknown'
    log('[patch] result: %s' % hits)
    PATCH_REPORT.update(hits)
    JSON_OUT.setdefault('stages', {})['patch'] = dict(result=hits, raw=out.strip().splitlines())
    lock('patch')


# ----------------------------------------------------------------------------- toolchains
def tar_extract_materialised(tar_path, dest, strip=0, skip_prefixes=()):
    """Extract tar (xz/gz), materialising symlinks and hardlinks as real file copies."""
    t = tarfile.open(tar_path, 'r:*')
    members = t.getmembers()
    links = []
    nfiles = nskipped = 0
    for m in members:
        parts = m.name.split('/')
        if strip:
            parts = parts[strip:]
        if not parts or not parts[-1]:
            continue
        rel = '/'.join(parts)
        if any(rel.startswith(p) for p in skip_prefixes):
            nskipped += 1
            continue
        destp = os.path.join(dest, rel.replace('/', os.sep))
        if m.isdir():
            os.makedirs(destp, exist_ok=True)
            continue
        if m.issym() or m.islnk():
            target = m.linkname
            if m.islnk():
                tparts = target.split('/')[strip:] if strip else target.split('/')
                resolved = '/'.join(tparts)
            else:
                resolved = os.path.normpath(os.path.join(os.path.dirname(rel), target)).replace(os.sep, '/')
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
        cur, seen, src = resolved, set(), None
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
    return dict(files=nfiles, links=len(links), skipped=nskipped)


def prune_toolchain(root):
    """Remove gdb, lto-dump and docs; never touch .a / .o / ELF files used by the compiler."""
    killed = []
    for pat in ('bin/*gdb*', 'bin/*lto-dump*', 'share/doc', 'share/man', 'share/info',
                'share/gdb', 'libexec/gdb'):
        for p in glob.glob(os.path.join(root, pat)):
            killed.append(os.path.relpath(p, root).replace(os.sep, '/'))
            rm(p)
    return killed


def stage_toolchains(force=False):
    for name, tarpath, sha in TOOLCHAINS:
        if locked('tc_' + name) and not force:
            log('[%s] already staged' % name)
            continue
        if not os.path.exists(tarpath):
            raise SystemExit('missing archive %s' % tarpath)
        got = sha256_file(tarpath)
        if got != sha:
            raise SystemExit('%s sha256 mismatch: %s' % (name, got))
        dest = os.path.join(P_TOOLS, name)
        rm(dest)
        os.makedirs(dest, exist_ok=True)
        log('[%s] extracting ...' % name)
        # tar root is the toolchain prefix (bin/, lib/, share/, libexec/, <chip>-elf/ sysroot)
        info = tar_extract_materialised(tarpath, dest, strip=1)
        killed = prune_toolchain(dest)
        sz, nf = dir_stats(dest)
        log('[%s] files=%d links=%d pruned=%s -> %.1f MB / %d files'
            % (name, info['files'], info['links'], killed, sz / 1024 ** 2, nf))
        JSON_OUT.setdefault('stages', {})['tc_' + name] = dict(
            files=nf, bytes=sz, pruned=killed, links=info['links'], sha256=sha)
        lock('tc_' + name)


# ----------------------------------------------------------------------------- cmake
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
        if rel.endswith('/') or rel.startswith('__MACOSX'):
            continue
        # share/cmake-*/Modules is required at runtime, bin/ and lib/ obviously too
        if not re.match(r'^(bin|lib|share)/', rel):
            log('[cmake] skipping unexpected entry %s' % rel)
            continue
        dest = os.path.join(P_CMAKE, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(i) as src, open(dest, 'wb') as out:
            shutil.copyfileobj(src, out, 1 << 20)
        n += 1
    libdir = os.path.join(P_CMAKE, 'lib')
    copied = []
    # libarchive.so (bionic) DT_NEEDED libiconv.so / libxml2.so.16 / liblzma.so.5, which the
    # Termux cmake package gets from other packages; cmake.zip does not bundle them.
    if os.path.isdir(TERMUX_LIBS):
        for f in sorted(os.listdir(TERMUX_LIBS)):
            src = os.path.join(TERMUX_LIBS, f)
            if os.path.isfile(src):
                shutil.copy2(src, os.path.join(libdir, f))
                copied.append(f)
    # cmake/ninja DT_NEEDED libc++_shared.so; cmake DT_NEEDED libz.so.1 (Android only ships
    # libz.so). Both come from Termux packages that cmake.zip does not include.
    for pkg, names in (('libc++', ['libc++_shared.so']), ('zlib', ['libz.so.1'])):
        d = find_deb(pkg)
        if not d:
            log('[cmake] !! deb missing for %s' % pkg)
            continue
        u = deb_usr_root(d, pkg)
        for nm in names:
            src = os.path.join(u, 'lib', nm)
            if os.path.isfile(src):
                shutil.copy2(src, os.path.join(libdir, nm))
                copied.append(nm)
            else:
                log('[cmake] !! %s not found in %s' % (nm, pkg))
    # CMakeDetermineSystem.cmake reads $ENV{PREFIX}/include/android/api-level.h on Android
    inc = os.path.join(P_CMAKE, 'include', 'android')
    os.makedirs(inc, exist_ok=True)
    with open(os.path.join(inc, 'api-level.h'), 'w', newline='\n') as f:
        f.write('#define __ANDROID_API__ 28\n')
    sz, nf = dir_stats(P_CMAKE)
    log('[cmake] %d zip entries + %d termux libs, %.1f MB' % (n, len(copied), sz / 1024 ** 2))
    JSON_OUT.setdefault('stages', {})['cmake'] = dict(files=nf, bytes=sz,
                                                      termux_libs=copied,
                                                      api_level_header='include/android/api-level.h')
    lock('cmake')


# ----------------------------------------------------------------------------- python
TERMUX_DEBS = ['python', 'libandroid-support', 'libandroid-posix-semaphore', 'libbz2', 'libcrypt',
               'libexpat', 'libffi', 'liblzma', 'libsqlite', 'ncurses', 'ncurses-ui-libs',
               'openssl', 'readline', 'zlib', 'gdbm', 'libc++', 'zstd', 'libandroid-glob']
TERMUX_EXTRA = ['python-cryptography', 'python-psutil']
TERMUX_PREFIX = os.path.join('data', 'data', 'com.termux', 'files', 'usr')
TERMINFO_KEEP = ('x', 'v', 'a', 'd', 's', 't', 'r')


def find_deb(pkg_base):
    cands = [p for p in glob.glob(os.path.join(DL, '*.deb'))
             if os.path.basename(p).startswith(pkg_base + '_') and
             not os.path.basename(p).startswith(pkg_base + '-')]
    if not cands:
        return None
    cands.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return cands[0]


def deb_usr_root(deb, name):
    safe = re.sub(r'[^A-Za-z0-9_.+-]', '_', name)
    d = os.path.join(SCRATCH, 'deb', safe)
    usr = os.path.join(d, TERMUX_PREFIX)
    if not os.path.isdir(usr):
        rm(d)
        debx.extract(deb, d)
    return usr


def install_termux_extras(ver):
    """Copy bionic-compiled site-packages (cryptography, psutil) out of Termux debs."""
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
    return extra


def python_ver_on_disk():
    p = os.path.join(P_PY, 'lib')
    for d in os.listdir(p):
        m = re.match(r'^python(3\.\d+)$', d)
        if m:
            return m.group(1)
    raise SystemExit('cannot determine staged python version')


def stage_python(force=False):
    if locked('python') and not force:
        log('[python] already staged')
        return
    rm(P_PY)
    os.makedirs(os.path.join(P_PY, 'bin'), exist_ok=True)
    os.makedirs(os.path.join(P_PY, 'lib'), exist_ok=True)

    deb = find_deb('python')
    usr = deb_usr_root(deb, 'python')
    real = [f for f in os.listdir(os.path.join(usr, 'bin')) if re.match(r'^python3\.\d+$', f)]
    exe = real[0]
    ver = exe[len('python'):]
    log('[python] termux python %s from %s' % (ver, os.path.basename(deb)))

    src_bin = os.path.join(usr, 'bin', exe)
    for name in (exe, 'python3', 'python'):
        shutil.copy2(src_bin, os.path.join(P_PY, 'bin', name))

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

    for p in glob.glob(os.path.join(usr, 'lib', '*.so*')):
        if os.path.isfile(p):
            shutil.copy2(p, os.path.join(P_PY, 'lib', os.path.basename(p)))

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
        ti = os.path.join(u, 'share', 'terminfo')
        if os.path.isdir(ti):
            for sub in os.listdir(ti):
                if sub[:1] not in TERMINFO_KEEP:
                    continue
                for dp, _dn, fns in os.walk(os.path.join(ti, sub)):
                    for f in fns:
                        if not f:
                            continue
                        s = os.path.join(dp, f)
                        d2 = os.path.join(P_PY, 'share', 'terminfo', os.path.relpath(s, ti))
                        os.makedirs(os.path.dirname(d2), exist_ok=True)
                        try:
                            shutil.copy2(s, d2)
                        except OSError:
                            pass

    os.makedirs(P_SITE, exist_ok=True)
    extra = install_termux_extras(ver)

    sz, nf = dir_stats(P_PY)
    log('[python] %s: %.1f MB / %d files; libs: %d' % (ver, sz / 1024 ** 2, nf, len(dep_libs)))
    JSON_OUT.setdefault('stages', {})['python'] = dict(
        version=ver, deb=os.path.basename(deb), files=nf, bytes=sz,
        stdlib_files=n, dep_libs=sorted(set(dep_libs)), extra=extra)
    lock('python')


# ----------------------------------------------------------------------------- python-site
EXCLUDE_FROM_REQS = ('idf-component-manager',)   # offline pack: no registry access
# IDF 5.2.5 calls esptool.get_default_connected_device(), which esptool 5.x removed.
# Pin the 4.x line that 5.2.5 shipped with.
REQ_OVERRIDES = {'esptool': 'esptool>=4.6,<5'}
SDIST_VENDORED = {'pyyaml': ('lib/yaml', 'yaml')}
FROM_TERMUX = ('cryptography', 'psutil')
# pulled in by "cryptography" when resolved from PyPI; our cryptography comes from Termux
# (Rust/abi3 build, no cffi), so a Windows-built cffi would be dead weight.
NOT_NEEDED = {'cffi', 'pycparser'}


def read_requirements():
    p = os.path.join(P_IDF, 'tools', 'requirements', 'requirements.core.txt')
    pkgs = []
    for line in open(p, 'r', encoding='utf-8', errors='replace'):
        line = line.split('#')[0].strip()
        if not line:
            continue
        if ';' in line:
            spec, marker = line.split(';', 1)
            if 'python_version' in marker:      # only for <3.8, irrelevant here
                continue
            line = spec.strip()
        name = re.split(r'[<>=!~\[ ]', line)[0].strip()
        if not name or name in EXCLUDE_FROM_REQS:
            continue
        pkgs.append(line)
    seen, out = set(), []
    for p in pkgs:
        n = re.split(r'[<>=!~\[ ]', p)[0].strip().lower()
        if n not in seen:
            seen.add(n)
            out.append(REQ_OVERRIDES.get(n, p))
    return out


def pip(args):
    cmd = [sys.executable, '-m', 'pip'] + args
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        tail = (r.stderr or r.stdout).strip().splitlines()
        log('   pip rc=%d: %s' % (r.returncode, tail[-1:] and tail[-1] or ''))
    return r.returncode == 0, (r.stdout or '') + (r.stderr or '')


PLATFORM_FLAGS = ['--only-binary=:all:', '--platform', 'any', '--abi', 'none',
                  '--python-version', '3.12', '--implementation', 'py']


def site_install(pkg, target):
    ok, _ = pip(['install', '--disable-pip-version-check', '--target', target, '--no-compile',
                 '--upgrade', '--no-deps', '--no-warn-script-location'] + PLATFORM_FLAGS + [pkg])
    if ok:
        return 'wheel-pure'
    ok2, _ = pip(['install', '--disable-pip-version-check', '--target', target, '--no-compile',
                  '--upgrade', '--no-deps', '--no-warn-script-location', '--no-binary=:all:', pkg])
    return 'sdist-built' if ok2 else 'FAILED'


def vendor_sdist(pkg, subdir, modname, target):
    d = os.path.join(SCRATCH, 'sdist')
    os.makedirs(d, exist_ok=True)
    ok, _ = pip(['download', '--no-deps', '--no-binary=:all:', '-d', d, '--no-build-isolation', pkg])
    tars = glob.glob(os.path.join(d, pkg + '-*.tar.gz')) + \
        glob.glob(os.path.join(d, pkg.replace('-', '_') + '-*.tar.gz'))
    if not tars:
        return None
    tars.sort(key=os.path.getmtime, reverse=True)
    t = tarfile.open(tars[0], 'r:gz')
    base = t.getmembers()[0].name.split('/')[0]
    prefix = base + '/' + subdir + '/'
    n = 0
    for m in t.getmembers():
        if not m.isfile() or not m.name.startswith(prefix):
            continue
        rel = m.name[len(prefix):]
        if not rel or '__pycache__' in rel:
            continue
        write_stream(t.extractfile(m), os.path.join(target, modname, rel.replace('/', os.sep)))
        n += 1
    t.close()
    return dict(version=base[len(pkg) + 1:], files=n, sdist=os.path.basename(tars[0]))


def audit_site():
    have = set()
    for d in os.listdir(P_SITE):
        if d.endswith(('.dist-info', '.egg-info')):
            have.add(re.split(r'-\d', d)[0].lower().replace('_', '-'))
        elif os.path.isdir(os.path.join(P_SITE, d)):
            have.add(d.lower().replace('_', '-'))
    missing = {}
    for d in os.listdir(P_SITE):
        md = os.path.join(P_SITE, d, 'METADATA')
        if not d.endswith('.dist-info') or not os.path.isfile(md):
            continue
        name = d.split('-')[0]
        text = open(md, 'r', encoding='utf-8', errors='replace').read()
        for line in text.splitlines():
            if not line.startswith('Requires-Dist:'):
                continue
            spec = line.split(':', 1)[1].strip()
            dep, _, marker = spec.partition(';')
            dep_name = re.split(r'[<>=!~\[ (]', dep.strip())[0].lower().replace('_', '-')
            marker = marker.strip()
            if marker and ('extra ==' in marker or 'extra =="' in marker):
                continue
            if marker and ('win32' in marker or 'Windows' in marker):
                continue
            if not dep_name or dep_name in have or dep_name in NOT_NEEDED:
                continue
            if dep_name in FROM_TERMUX or dep_name == 'pyyaml':
                continue
            missing.setdefault(dep_name, []).append(name)
    return dict(installed=sorted(have), missing=missing)


def stage_site(force=False):
    if locked('site') and not force:
        log('[site] already staged')
        return
    # rebuild python-site from scratch so the result is exactly: termux extras + pip payload
    rm(P_SITE)
    os.makedirs(P_SITE, exist_ok=True)
    ver = python_ver_on_disk()
    install_termux_extras(ver)
    reqs = read_requirements()
    log('[site] requirements.core.txt (%d): %s' % (len(reqs), ', '.join(reqs)))
    result, done = {}, set()
    pending = list(reqs)
    rounds = 0
    while pending and rounds < 6:
        rounds += 1
        for pkg in pending:
            name = re.split(r'[<>=!~\[ ]', pkg)[0].strip().lower()
            if name in done:
                continue
            done.add(name)
            if name in FROM_TERMUX:
                result[pkg] = 'from-termux'
                log('   %-34s from-termux (bionic build)' % pkg)
                continue
            if name in NOT_NEEDED:
                result[pkg] = 'skipped (not needed on bionic)'
                log('   %-34s skipped' % pkg)
                continue
            how = site_install(pkg, P_SITE)
            result[pkg] = how
            log('   %-34s %s' % (pkg, how))
        aud = audit_site()
        pending = [d for d in sorted(aud['missing']) if d not in done]
    for pkg, (sub, mod) in SDIST_VENDORED.items():
        info = vendor_sdist(pkg, sub, mod, P_SITE)
        result[pkg] = info or 'FAILED'
        log('[site] vendored %s: %s' % (pkg, info))
    aud = audit_site()
    sz, nf = dir_stats(P_SITE)
    failed = [k for k, v in result.items() if v == 'FAILED']
    log('[site] %.1f MB / %d files; unsatisfied: %s; failed: %s'
        % (sz / 1024 ** 2, nf, aud['missing'], failed))
    JSON_OUT.setdefault('stages', {})['site'] = dict(
        requirements=reqs, result=result, files=nf, bytes=sz,
        missing=aud['missing'], installed=aud['installed'], failed=failed,
        from_termux=list(FROM_TERMUX), not_needed=sorted(NOT_NEEDED))
    lock('site')


# ----------------------------------------------------------------------------- glibc
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
        dst = os.path.join(SCRATCH, 'deb', 'debian_' + re.sub(r'[^A-Za-z0-9_.+-]', '_', pkg))
        if not os.path.isdir(os.path.join(dst, 'usr')):
            rm(dst)
            debx.extract(d, dst)
        for dp, _dn, fns in os.walk(dst):
            for f in fns:
                if '.so' in f:
                    index.setdefault(f, os.path.join(dp, f))
        log('[glibc] indexed %s (%s)' % (pkg, os.path.basename(d)))

    entries = []
    for name, _t, _s in TOOLCHAINS:
        root = os.path.join(P_TOOLS, name)
        for dp, _dn, fns in os.walk(root):
            for f in fns:
                p = os.path.join(dp, f)
                if f.endswith(('.so', '.a', '.o')) or not is_elf(p):
                    continue
                entries.append(p)
    needed = set(GLIBC_REQUIRED)
    queue, seen = list(entries), set()
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
            if n in index:
                needed.add(n)
                queue.append(index[n])
            elif n.startswith(('libc.so', 'libm.so')):
                needed.add(n)
    missing = [n for n in sorted(needed) if n not in index]
    copied = []
    for n in sorted(needed):
        if n in index:
            shutil.copy2(index[n], os.path.join(P_GLIBC, n))
            copied.append(n)
    sz, nf = dir_stats(P_GLIBC)
    log('[glibc] %d libs, %.1f MB; unresolved: %s' % (len(copied), sz / 1024 ** 2, missing))
    JSON_OUT.setdefault('stages', {})['glibc'] = dict(libs=copied, bytes=sz, files=nf,
                                                      unresolved=missing, scanned=len(seen))
    lock('glibc')


# ----------------------------------------------------------------------------- zip
def collect_zip_entries():
    rows = []
    for dp, dns, fns in os.walk(STAGE):
        dns.sort()
        for f in sorted(fns):
            p = os.path.join(dp, f)
            rows.append((os.path.relpath(p, STAGE).replace(os.sep, '/'), p))
    rows.sort()
    return rows


def exec_mode(path):
    if is_elf(path):
        try:
            if Elf(path).is_executable:
                return 0o755
        except Exception:
            pass
        return 0o644
    try:
        with open(path, 'rb') as f:
            if f.read(2) == b'#!':
                return 0o755
    except OSError:
        pass
    return 0o644


def make_zip(level=9):
    rows = collect_zip_entries()
    log('[zip] %d entries -> %s' % (len(rows), ZIP_PATH))
    rm(ZIP_PATH)
    t0 = time.time()
    fixed_time = (2025, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(ZIP_PATH, 'w', zipfile.ZIP_DEFLATED, compresslevel=level,
                         allowZip64=True) as zf:
        for i, (rel, p) in enumerate(rows, 1):
            zi = zipfile.ZipInfo(rel, date_time=fixed_time)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi._compresslevel = level
            zi.external_attr = (exec_mode(p) & 0xFFFF) << 16
            zi.file_size = os.path.getsize(p)
            with open(p, 'rb') as src, zf.open(zi, 'w') as dst:
                shutil.copyfileobj(src, dst, 1 << 20)
            if i % 5000 == 0:
                log('[zip] %d/%d (%.0fs)' % (i, len(rows), time.time() - t0))
    log('[zip] done in %.0fs, %.1f MB' % (time.time() - t0, os.path.getsize(ZIP_PATH) / 1024 ** 2))
    return ZIP_PATH


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', default='all')
    ap.add_argument('--force', action='store_true')
    a = ap.parse_args()

    os.makedirs(LOCKS, exist_ok=True)
    stages = {
        'idf': stage_idf, 'patch': stage_patch, 'toolchains': stage_toolchains,
        'cmake': stage_cmake, 'python': stage_python, 'glibc': stage_glibc,
        'site': stage_site,
    }
    order = ['idf', 'patch', 'toolchains', 'cmake', 'python', 'site', 'glibc']
    if a.stage == 'all':
        for k in order:
            stages[k](force=a.force)
        make_zip()
    elif a.stage in stages:
        stages[a.stage](force=a.force)
    elif a.stage == 'zip':
        make_zip()
    else:
        raise SystemExit('unknown stage %s' % a.stage)
    os.makedirs(BUILD, exist_ok=True)
    with open(os.path.join(BUILD, 'build525.json'), 'w', encoding='utf-8') as f:
        json.dump(JSON_OUT, f, indent=1, ensure_ascii=False)
    print(json.dumps(JSON_OUT, indent=1))


if __name__ == '__main__':
    main()
