"""Import-compatibility check for the pure-python part of the pack, run with a real
CPython 3.14 interpreter (same minor version as the Termux python inside the pack).

The shipped interpreter is bionic aarch64 and cannot execute on Windows, so this imports
the *same* python-site payload with a 3.14 interpreter on the host: it proves the
pure-python dependency set is importable on 3.14, where 3.12-era wheels often break.

Usage:  python.exe test_imports_314.py <python-site-dir> [<esp-idf/tools-dir>]

Modules that are simply not part of the (per-IDF release) requirement set are reported as
skipped, not as failures; a module that IS present in python-site and fails is a failure.
"""
import importlib
import importlib.util
import os
import sys
import traceback

# modules ESP-IDF's own code imports (idf.py startup path + the tools it shells out to)
WANTED = [
    'yaml', 'click', 'serial', 'serial.tools.list_ports', 'packaging', 'packaging.version',
    'pyparsing', 'elftools', 'construct', 'rich', 'future', 'kconfiglib', 'esp_pylib',
    'esp_idf_monitor', 'esp_idf_size', 'esp_coredump', 'esp_idf_diag',
    'esp_idf_nvs_partition_gen', 'esp_idf_panic_decoder', 'esptool', 'rich_click',
    'textual', 'markdown_it', 'mdurl', 'pygments',
]

# dist name -> import name, for the few that differ
SITE = sys.argv[1] if len(sys.argv) > 1 else ''
IDF_TOOLS = sys.argv[2] if len(sys.argv) > 2 else ''


def present(mod):
    root = mod.split('.')[0]
    if not SITE or not os.path.isdir(SITE):
        return True
    return os.path.exists(os.path.join(SITE, root + '.py')) or \
        os.path.isdir(os.path.join(SITE, root)) or \
        os.path.exists(os.path.join(SITE, root + '.so'))


print('interpreter:', sys.version.replace('\n', ' '))
print('python-site:', SITE)
print('idf tools  :', IDF_TOOLS)

ok, failed, skipped = [], [], []
for m in WANTED:
    try:
        importlib.import_module(m)
        ok.append(m)
    except ModuleNotFoundError as e:
        if present(m) or (e.name or '') not in (m, m.split('.')[0]):
            failed.append((m, 'ModuleNotFoundError: %s' % e))
        else:
            skipped.append(m)
    except Exception as e:
        failed.append((m, '%s: %s' % (type(e).__name__, e)))

print('\nOK (%d): %s' % (len(ok), ', '.join(ok)))
if skipped:
    print('SKIPPED, not in this pack (%d): %s' % (len(skipped), ', '.join(skipped)))
if failed:
    print('FAILED (%d):' % len(failed))
    for m, e in failed:
        print('   %-30s %s' % (m, e))

if IDF_TOOLS:
    print('\n--- idf_py_actions.tools (imported by idf.py at startup) ---')
    try:
        sys.path.insert(0, IDF_TOOLS)
        import idf_py_actions.tools as t
        print('OK idf_py_actions.tools imported; PROG=%r' % getattr(t, 'PROG', '?'))
    except Exception:
        print('FAILED')
        traceback.print_exc()
        failed.append(('idf_py_actions.tools', 'import failed'))

sys.exit(1 if failed else 0)
