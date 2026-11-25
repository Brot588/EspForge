"""Import-compatibility check for the pure-python bits of the pack, run with a real
CPython 3.14 interpreter (the same minor version the Android device will run).

The shipped interpreter is bionic aarch64 and cannot execute here; this test imports the
*same* python-site payload with a 3.14 interpreter on the host to prove the pure-python
dependency set is importable on 3.14 (an area where 3.13-era wheels can silently break).
"""
import importlib
import sys
import traceback

MODULES = [
    'yaml', 'click', 'serial', 'serial.tools.list_ports', 'packaging', 'packaging.version',
    'pyparsing', 'elftools', 'construct', 'rich', 'rich_click', 'textual', 'markdown_it',
    'mdurl', 'pygments', 'intelhex', 'reedsolo', 'kconfiglib', 'esp_pylib',
    'esp_idf_monitor', 'esp_idf_size', 'esp_coredump', 'esp_idf_diag',
    'esp_idf_nvs_partition_gen', 'esp_idf_panic_decoder', 'esptool',
]

print('interpreter:', sys.version.replace('\n', ' '))
print('sys.path entries of interest:')
for p in sys.path:
    if 'python-site' in p or p.endswith('esp-idf\\tools') or p.endswith('esp-idf/tools'):
        print('   ', p)

ok, bad = [], []
for m in MODULES:
    try:
        importlib.import_module(m)
        ok.append(m)
    except Exception as e:
        bad.append((m, '%s: %s' % (type(e).__name__, e)))
print('\nOK   (%d): %s' % (len(ok), ', '.join(ok)))
if bad:
    print('\nFAILED (%d):' % len(bad))
    for m, e in bad:
        print('   %-32s %s' % (m, e))

# end-to-end: idf.py's own module-level imports
print('\n--- idf_py_actions.tools (what idf.py imports at startup) ---')
try:
    sys.path.insert(0, sys.argv[2] if len(sys.argv) > 2 else '')
    import idf_py_actions.tools as t
    print('OK idf_py_actions.tools imported; PROG=%r' % getattr(t, 'PROG', '?'))
except Exception:
    print('FAILED')
    traceback.print_exc()

sys.exit(1 if bad else 0)
