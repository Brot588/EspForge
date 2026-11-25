#!/usr/bin/env python3
"""For each candidate PyPI package, report whether a pure-python wheel (py3-none-any) exists."""
import re, subprocess, sys, concurrent.futures as cf

MIRROR = 'https://mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/%s/'

PKGS = ['pyyaml', 'packaging', 'click', 'pyserial', 'pyparsing', 'pyelftools', 'construct',
        'rich', 'esp-idf-kconfig', 'esp-idf-monitor', 'esp-idf-size', 'esp-idf-diag',
        'esptool', 'esp-coredump', 'esp-idf-nvs-partition-gen', 'esp-idf-panic-decoder',
        'bitstring', 'ecdsa', 'intelhex', 'reedsolo', 'cryptography', 'psutil', 'future',
        'markdown-it-py', 'mdurl', 'pygments', 'typing-extensions', 'colorama', 'setuptools',
        'kconfiglib', 'pyserial', 'esp-idf-monitor']


def probe(pkg):
    url = MIRROR % pkg
    r = subprocess.run(['curl.exe', '-sL', '--max-time', '40', url], capture_output=True, text=True)
    html = r.stdout
    if not html:
        return pkg, 'NO-INDEX', []
    pure = sorted(set(re.findall(r'>([^<>]*?-py3-none-any\.whl)<', html)))
    anyw = sorted(set(re.findall(r'>([^<>]*?-py2\.py3-none-any\.whl)<', html)))
    allw = sorted(set(re.findall(r'>([^<>]*?\.whl)<', html)))
    if pure or anyw:
        rel = (pure or anyw)[-1]
        return pkg, 'PURE', rel
    if allw:
        return pkg, 'PLATFORM-ONLY', allw[-1]
    return pkg, 'SDIST-ONLY', ''


with cf.ThreadPoolExecutor(max_workers=8) as ex:
    for pkg, kind, sample in ex.map(probe, PKGS):
        print('%-26s %-15s %s' % (pkg, kind, sample))
