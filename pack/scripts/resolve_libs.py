#!/usr/bin/env python3
"""Compute the DT_NEEDED closure of a set of ELF entry points against a set of .so files."""
import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from elfinfo import Elf


def index_so(roots):
    """Map soname -> file path for every ELF file under roots."""
    names = {}
    files = []
    for root in roots:
        for dp, _dn, fn in os.walk(root):
            for f in fn:
                p = os.path.join(dp, f)
                try:
                    if open(p, 'rb').read(4) != b'\x7fELF':
                        continue
                except OSError:
                    continue
                files.append(p)
                try:
                    e = Elf(p)
                except Exception:
                    continue
                key = e.soname or os.path.basename(p)
                names.setdefault(key, p)
                names.setdefault(os.path.basename(p), p)
    return names, files


def closure(entries, names, system_prefixes=('libc.so', 'libm.so', 'libdl.so', 'liblog.so',
                                             'libz.so', 'libstdc++.so', 'libgcc_s.so')):
    """Return (seen, missing). system ones are resolved by the bionic runtime, still reported."""
    seen, missing, queue = {}, [], list(entries)
    while queue:
        p = queue.pop()
        if p in seen:
            continue
        try:
            e = Elf(p)
        except Exception:
            continue
        seen[p] = e
        for n in e.needed:
            if n in seen or any(s in n for s in []):
                continue
            hit = names.get(n)
            if hit:
                if hit not in seen:
                    queue.append(hit)
            else:
                if n not in missing:
                    missing.append(n)
    return seen, missing


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('libroots', nargs='+')
    ap.add_argument('--entry', action='append', default=[])
    a = ap.parse_args()
    names, files = index_so(a.libroots)
    print(json.dumps(dict(elf_files=len(files), sonames=len(names)), indent=1))
    if a.entry:
        seen, missing = closure(a.entry, names)
        print('closure files: %d' % len(seen))
        for p, e in seen.items():
            print('  %-60s <- %s' % (os.path.basename(p), ','.join(e.needed)))
        print('MISSING: %s' % (missing or 'none'))


if __name__ == '__main__':
    main()
