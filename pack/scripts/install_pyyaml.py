"""把 PyYAML 的纯 Python 实现装进资源包的 python-site。

IDF 的 tools/idf_py_actions/tools.py 无条件 import yaml；PyYAML 的纯 Python 实现
（不含 _yaml C 扩展）在这些场景下够用，而且不需要为 Android ABI 编译。
"""
import io, json, os, pathlib, tarfile, urllib.request

PACK = pathlib.Path(r'D:\DSH\espstudio\pack')
SITE = PACK / 'build/staging525/tools/python-site'
DL = PACK / 'dl/pyyaml'
DL.mkdir(parents=True, exist_ok=True)

meta = json.load(urllib.request.urlopen('https://pypi.org/pypi/PyYAML/json'))
sdist = next(u for u in meta['urls'] if u['packagetype'] == 'sdist')
print('PyYAML', sdist['filename'], sdist['size'], 'bytes')

tar = DL / sdist['filename']
if not tar.exists():
    with urllib.request.urlopen(sdist['url']) as r, open(tar, 'wb') as f:
        f.write(r.read())
    print('下载完成')

with tarfile.open(tar) as tf:
    yaml_members = [m for m in tf.getmembers() if '/lib/yaml/' in m.name and m.isfile()]
    print('yaml 文件数:', len(yaml_members))
    dst = SITE / 'yaml'
    if dst.exists():
        for p in sorted(dst.rglob('*'), reverse=True):
            p.unlink() if p.is_file() else p.rmdir()
    dst.mkdir(parents=True, exist_ok=True)
    for m in yaml_members:
        rel = m.name.split('/lib/yaml/', 1)[1]
        out = dst / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(tf.extractfile(m).read())
print('装到', dst, sum(1 for _ in dst.rglob('*.py')), '个 .py')

# 也塞一份到 5.5.1 的 staging（备用）
alt = PACK / 'build/staging/tools/python-site/yaml'
if (PACK / 'build/staging/tools/python-site').is_dir() and not alt.exists():
    import shutil
    shutil.copytree(dst, alt)
    print('同时复制到 5.5.1 staging')
