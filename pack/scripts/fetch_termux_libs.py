"""从 Termux 镜像批量取共享库（给 bionic cmake 补齐 libarchive 的依赖链）。

用法: python fetch_termux_libs.py libiconv libxml2 liblzma libzstd
产物落在 pack/build/termux-libs/，同名 .so 与 .so.N 都会有一份真实文件（zip 不保 symlink）。
"""
import gzip, io, lzma, os, sys, tarfile, urllib.request

MIRROR = 'https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/'
DL = r'D:\DSH\espstudio\pack\dl'
OUT = r'D:\DSH\espstudio\pack\build\termux-libs'
os.makedirs(OUT, exist_ok=True)


def load_index():
    idx = os.path.join(DL, 'Packages.termux.gz')
    out = {}
    with gzip.open(idx, 'rt', encoding='utf-8', errors='replace') as f:
        cur = {}
        for line in f:
            line = line.rstrip('\n')
            if not line:
                if cur.get('Package'):
                    out[cur['Package']] = cur
                cur = {}
                continue
            if ':' in line:
                k, v = line.split(':', 1)
                cur[k.strip()] = v.strip()
    if cur.get('Package'):
        out[cur['Package']] = cur
    return out


def ar_data(deb_path):
    data = open(deb_path, 'rb').read()
    assert data[:8] == b'!<arch>\n'
    pos, out = 8, None
    while pos + 60 <= len(data):
        hdr = data[pos:pos + 60]
        name = hdr[0:16].decode().strip()
        size = int(hdr[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name.startswith('data.tar'):
            out = body
            break
    raw = out
    if raw[:6] == b'\xfd7zXZ\x00':
        raw = lzma.decompress(raw)
    return raw


def fetch(name, index):
    if name not in index:
        print('!! 索引里没有', name)
        return 0
    fn = index[name]['Filename']
    url = MIRROR + fn
    deb = os.path.join(DL, os.path.basename(fn))
    if not os.path.exists(deb):
        print('下载', url)
        with urllib.request.urlopen(url) as r, open(deb, 'wb') as f:
            f.write(r.read())
    raw = ar_data(deb)
    got = 0
    links = []
    with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
        for m in tf.getmembers():
            base = os.path.basename(m.name)
            if '/lib/' not in m.name and not m.name.startswith('lib/'):
                continue
            if not ('.so' in base):
                continue
            if m.isdir():
                continue
            if m.issym() or m.islnk():
                links.append((base, os.path.basename(m.linkname)))
                continue
            f = tf.extractfile(m)
            if f is None:
                continue
            with open(os.path.join(OUT, base), 'wb') as o:
                o.write(f.read())
            got += 1
    for base, real in links:
        src = os.path.join(OUT, real)
        if os.path.exists(src):
            with open(os.path.join(OUT, base), 'wb') as o:
                o.write(open(src, 'rb').read())
            got += 1
        else:
            print('  symlink', base, '->', real, '（真文件不在本包）')
    print(f'{name}: {got} 个文件')
    return got


if __name__ == '__main__':
    index = load_index()
    total = 0
    for n in sys.argv[1:]:
        total += fetch(n, index)
    print('合计', total, '个文件 ->', OUT)
