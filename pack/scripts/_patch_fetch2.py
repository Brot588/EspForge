p = r'D:\DSH\espstudio\pack\scripts\fetch_pkgs.py'
s = open(p, encoding='utf-8').read()
old = "'python-cryptography', 'python-psutil', 'python-cffi', 'python-pip'],"
new = "'python-cryptography', 'python-psutil', 'python-cffi', 'python-pip',\n               'libc++', 'zstd', 'libandroid-glob'],"
if old in s:
    open(p, 'w', encoding='utf-8').write(s.replace(old, new))
    print('patched')
else:
    print('pattern missing')
