import re
import sys

p = r'D:\DSH\espstudio\pack\scripts\fetch_pkgs.py'
s = open(p, encoding='utf-8').read()
old = "'openssl', 'readline', 'zlib', 'gdbm', 'ca-certificates'],"
new = ("'openssl', 'readline', 'zlib', 'gdbm', 'ca-certificates',\n"
       "               'python-cryptography', 'python-psutil', 'python-cffi', 'python-pip'],")
if old in s:
    s = s.replace(old, new)
    open(p, 'w', encoding='utf-8').write(s)
    print('patched fetch_pkgs.py')
else:
    print('pattern not found')
    sys.exit(1)
