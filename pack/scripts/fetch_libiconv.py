"""从 tunatermux 镜像取 libiconv 并解出 .so（cmake 的 libarchive 硬依赖它）"""
import gzip, io, os, re, sys, tarfile, urllib.request, lzma

MIRROR = 'https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/'
DL = r'D:\DSH\espstudio\pack\dl'
OUT = r'D:\DSH\espstudio\pack\build\libiconv'
os.makedirs(OUT, exist_ok=True)


def pkg_entries():
    """读 Packages.gz，返回 {name: (filename, version)}"""
    idx = os.path.join(DL, 'Packages.termux.gz')
    out = {}
    with gzip.open(idx, 'rt', encoding='utf-8', errors='replace') as f:
        cur = {}
        for line in f:
            line = line.rstrip('\n')
            if not line:
                if cur.get('Package'):
                    out[cur['Package']] = (cur.get('Filename'), cur.get('Version'))
                cur = {}
                continue
            if ':' in line:
                k, v = line.split(':', 1)
                cur[k.strip()] = v.strip()
    if cur.get('Package'):
        out[cur['Package']] = (cur.get('Filename'), cur.get('Version'))
    return out


pkgs = pkg_entries()
print('libiconv:', pkgs.get('libiconv'))
fn, ver = pkgs['libiconv']
url = MIRROR + fn
deb = os.path.join(DL, os.path.basename(fn))
if not os.path.exists(deb):
    print('下载', url)
    with urllib.request.urlopen(url) as r, open(deb, 'wb') as f:
        f.write(r.read())
print('deb:', deb, os.path.getsize(deb), 'bytes')

# 解 ar -> data.tar.xz
data = open(deb, 'rb').read()
assert data[:8] == b'!<arch>\n'
pos = 8
member = None
while pos + 60 <= len(data):
    hdr = data[pos:pos + 60]
    name = hdr[0:16].decode().strip()
    size = int(hdr[48:58].decode().strip())
    body = data[pos + 60:pos + 60 + size]
    pos += 60 + size + (size & 1)
    if name.startswith('data.tar'):
        member = body
        break
assert member, 'no data.tar'
print('data.tar 大小', len(member))

raw = member
if raw[:6] == b'\xfd7zXZ\x00':
    raw = lzma.decompress(raw)

count = 0
with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
    for m in tf.getmembers():
        base = os.path.basename(m.name)
        if not base.startswith('libiconv'):
            continue
        if m.isdir():
            continue
        target = os.path.join(OUT, base)
        if m.issym() or m.islnk():
            link = m.linkname
            print('symlink', base, '->', link)
            # 软链接：把真文件内容复制一份，用链接名保存（zip 不保 symlink）
            real_name = os.path.basename(link)
            real_path = os.path.join(OUT, real_name)
            if os.path.exists(real_path):
                with open(target, 'wb') as f:
                    f.write(open(real_path, 'rb').read())
                count += 1
            else:
                print('  跳过（真文件还没到）', base)
            continue
        f = tf.extractfile(m)
        if f is None:
            continue
        with open(target, 'wb') as o:
            o.write(f.read())
        count += 1
        print('提取', base, os.path.getsize(target))

print('共', count, '个文件')
