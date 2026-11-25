#!/usr/bin/env python3
"""Resolve packages in the local Packages indices and download the .deb files."""
import gzip, os, subprocess, sys, json

DL = r'D:\DSH\espstudio\pack\dl'

MIRRORS = {
    'debian': ('https://mirrors.tuna.tsinghua.edu.cn/debian/', os.path.join(DL, 'Packages.debian-bookworm.gz')),
    'termux': ('https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/', os.path.join(DL, 'Packages.termux.gz')),
}

WANT = {
    'debian': ['libc6', 'libstdc++6', 'libgcc-s1', 'zlib1g'],
    'termux': ['python', 'libandroid-support', 'libandroid-posix-semaphore', 'libbz2', 'libcrypt',
               'libexpat', 'libffi', 'liblzma', 'libsqlite', 'ncurses', 'ncurses-ui-libs',
               'openssl', 'readline', 'zlib', 'gdbm', 'ca-certificates'],
}


def load(path):
    idx = {}
    with gzip.open(path, 'rt', encoding='utf-8', errors='replace') as f:
        cur = {}
        for line in f:
            line = line.rstrip('\n')
            if not line.strip():
                if cur.get('Package'):
                    idx.setdefault(cur['Package'], []).append(cur)
                cur = {}
                continue
            if line[0] in ' \t':
                continue
            if ':' in line:
                k, v = line.split(':', 1)
                cur.setdefault(k.strip(), v.strip())
        if cur.get('Package'):
            idx.setdefault(cur['Package'], []).append(cur)
    return idx


def main():
    repo = sys.argv[1]
    base, index = MIRRORS[repo]
    idx = load(index)
    got = []
    for name in WANT[repo]:
        if name not in idx:
            print('!! MISSING in index: %s' % name)
            continue
        p = idx[name][0]
        fn = p['Filename']
        url = base + fn
        out = os.path.join(DL, os.path.basename(fn).replace(':','_'))
        if os.path.exists(out) and os.path.getsize(out) > 0:
            print('cached  %-42s %s' % (name, os.path.basename(fn).replace(':','_')))
        else:
            print('fetch   %-42s %s' % (name, url))
            r = subprocess.run(['curl.exe', '-sL', '--retry', '3', '--retry-delay', '3',
                                '-o', out, url], capture_output=True, text=True)
            if r.returncode != 0:
                print('   FAILED rc=%d %s' % (r.returncode, r.stderr[-300:]))
                continue
        got.append(dict(pkg=name, version=p['Version'], file=os.path.basename(fn).replace(':','_'),
                        size=os.path.getsize(out), url=url))
    print(json.dumps(got, indent=1))


if __name__ == '__main__':
    main()
