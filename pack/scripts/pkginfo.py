#!/usr/bin/env python3
"""Parse Debian/Termux Packages indices and resolve package versions."""
import gzip, sys, json, re

def parse(path):
    pkgs = []
    if path.endswith('.gz'):
        fh = gzip.open(path, 'rt', encoding='utf-8', errors='replace')
    else:
        fh = open(path, 'r', encoding='utf-8', errors='replace')
    cur = {}
    for line in fh:
        line = line.rstrip('\n')
        if not line.strip():
            if cur:
                pkgs.append(cur); cur = {}
            continue
        if line[0] in ' \t':
            continue
        if ':' in line:
            k, v = line.split(':', 1)
            cur.setdefault(k.strip(), v.strip())
    if cur:
        pkgs.append(cur)
    fh.close()
    return pkgs

def main():
    which = sys.argv[1]
    if which == 'termux':
        pkgs = parse(r'D:\DSH\espstudio\pack\dl\Packages.termux.gz')
        names = ['python','python-pyyaml','libandroid-support','ncurses','readline',
                 'libsqlite','openssl','libbz2','liblzma','libffi','libexpat','zlib',
                 'libandroid-glob','libiconv','libcrypt','ca-certificates','termux-am','dpkg']
        idx = {}
        for p in pkgs:
            idx.setdefault(p.get('Package'), []).append(p)
        for n in names:
            for p in idx.get(n, []):
                print(f"{n:22s} {p.get('Version'):22s} {p.get('Filename')}  deps={p.get('Depends','')[:160]}")
            if n not in idx:
                print(f"{n:22s} -- NOT FOUND --")
    else:
        pkgs = parse(r'D:\DSH\espstudio\pack\dl\Packages.debian-bookworm.gz')
        names = ['libc6','libstdc++6','libgcc-s1','zlib1g','libzstd1','liblzma5','libbz2-1.0','libexpat1','libssl3','libffi8','libsqlite3-0','libncursesw6','libreadline8','libtinfo6','libuuid1']
        idx = {}
        for p in pkgs:
            idx.setdefault(p.get('Package'), []).append(p)
        for n in names:
            for p in idx.get(n, []):
                print(f"{n:18s} {p.get('Version'):24s} {p.get('Filename')}")
            if n not in idx:
                print(f"{n:18s} -- NOT FOUND --")

main()
