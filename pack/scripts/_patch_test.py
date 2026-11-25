p = r'D:\DSH\espstudio\pack\scripts\test_imports_314.py'
s = open(p, encoding='utf-8').read()
old = "'mdurl', 'pygments', 'bitstring', 'ecdsa', 'intelhex', 'reedsolo', 'kconfiglib',"
new = "'mdurl', 'pygments', 'intelhex', 'reedsolo', 'kconfiglib', 'esp_pylib',"
if old in s:
    open(p, 'w', encoding='utf-8').write(s.replace(old, new))
    print('patched')
else:
    print('pattern missing')
    print([l for l in s.splitlines() if 'bitstring' in l])
