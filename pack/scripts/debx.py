#!/usr/bin/env python3
"""
Extract .deb (ar archive -> data.tar.{xz,gz}) without dpkg/ar/tar binaries.

Symlinks are recorded and then MATERIALISED as independent copies of their final
target file, because the Android side unzips with ZipInputStream and cannot
recreate symlinks (and Windows cannot create them without privileges).

Usage:
  python debx.py list <deb>
  python debx.py extract <deb> <destdir> [--modes-json <path>] [--only <prefix>]
"""
import io, json, os, shutil, struct, sys, tarfile

AR_MAGIC = b'!<arch>\n'


def ar_members(path):
    with open(path, 'rb') as f:
        data = f.read()
    if not data.startswith(AR_MAGIC):
        raise ValueError('not an ar archive: %s' % path)
    off = len(AR_MAGIC)
    out = []
    while off + 60 <= len(data):
        hdr = data[off:off + 60]
        name = hdr[0:16].decode('utf-8', 'replace').strip()
        size = int(hdr[48:58].decode('ascii').strip())
        body = data[off + 60:off + 60 + size]
        out.append((name, body))
        off += 60 + size + (size & 1)
    return out


def data_tar(deb):
    for name, body in ar_members(deb):
        if name.startswith('data.tar'):
            return name, body
    raise ValueError('no data.tar member in %s' % deb)


def norm(name):
    while name.startswith('./'):
        name = name[2:]
    return name


def extract(deb, dest, modes_json=None, only=None):
    _name, body = data_tar(deb)
    tf = tarfile.open(fileobj=io.BytesIO(body))
    members = [m for m in tf.getmembers() if norm(m.name)]
    links = []          # (destpath, resolved_rel_target)
    modes = {}
    n_files = n_links = 0
    for m in members:
        rel = norm(m.name)
        if only and not (rel == only or rel.startswith(only.rstrip('/') + '/')):
            continue
        if rel.endswith('/'):
            continue
        target = os.path.join(dest, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if m.issym():
            raw = m.linkname
            # resolve relative to the link's directory
            base = os.path.dirname(rel)
            resolved = os.path.normpath(os.path.join(base, raw)).replace(os.sep, '/')
            n_links += 1
            links.append((rel, resolved))
            continue
        if m.issym() or m.islnk():
            base = os.path.dirname(rel)
            resolved = os.path.normpath(os.path.join(base, m.linkname)).replace(os.sep, '/')
            links.append((rel, resolved))
            continue
        if not m.isfile():
            continue
        f = tf.extractfile(m)
        if f is None:
            continue
        with open(target, 'wb') as out:
            shutil.copyfileobj(f, out)
        mode = m.mode & 0o7777
        if mode & 0o111:
            modes[rel] = mode
        n_files += 1
    # materialise symlinks: copy final regular-file target content
    unresolved = []
    for rel, resolved in links:
        src = None
        seen = set()
        cur = resolved
        while True:
            if cur in seen:
                break
            seen.add(cur)
            cand = os.path.join(dest, cur.replace('/', os.sep))
            if os.path.isfile(cand):
                src = cand
                break
            nxt = dict(links).get(cur)
            if nxt is None:
                break
            cur = nxt
        dst = os.path.join(dest, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if src:
            shutil.copy2(src, dst)
            modes.setdefault(rel, 0o755)
        else:
            unresolved.append((rel, resolved))
    if modes_json:
        os.makedirs(os.path.dirname(os.path.abspath(modes_json)), exist_ok=True)
        prev = {}
        if os.path.exists(modes_json):
            with open(modes_json, 'r', encoding='utf-8') as f:
                prev = json.load(f)
        prev.update(modes)
        with open(modes_json, 'w', encoding='utf-8') as f:
            json.dump(prev, f, indent=0, sort_keys=True)
    return dict(files=n_files, symlinks=n_links, modes=len(modes), unresolved=unresolved)


def main():
    cmd = sys.argv[1]
    deb = sys.argv[2]
    if cmd == 'list':
        print('members:')
        for name, body in ar_members(deb):
            print('  %-16s %d' % (name, len(body)))
        tn, body = data_tar(deb)
        print('data member: %s (%d bytes compressed)' % (tn, len(body)))
        tf = tarfile.open(fileobj=io.BytesIO(body))
        ms = [m for m in tf.getmembers() if norm(m.name)]
        print('entries: %d' % len(ms))
        for m in ms:
            k = 'link->' + m.linkname if (m.issym() or m.islnk()) else ('dir' if m.isdir() else 'file')
            print('  %-8s %-8o %s %s' % (k, m.mode, m.name, ''))
        return
    if cmd == 'extract':
        dest = sys.argv[3]
        modes_json = None
        only = None
        rest = sys.argv[4:]
        i = 0
        while i < len(rest):
            if rest[i] == '--modes-json':
                modes_json = rest[i + 1]; i += 2
            elif rest[i] == '--only':
                only = rest[i + 1]; i += 2
            else:
                i += 1
        info = extract(deb, dest, modes_json, only)
        print(json.dumps(info, indent=2))


if __name__ == '__main__':
    main()
