import io
import json
import sys
import traceback


def run_file(path):
    buf = io.StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout = sys.stderr = buf
    code = 0
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
        g = {"__name__": "__main__", "__file__": path, "__builtins__": __builtins__}
        sys.argv = [path]
        exec(compile(src, path, "exec"), g)
    except SystemExit as e:
        if isinstance(e.code, int):
            code = e.code
        elif e.code is None:
            code = 0
        else:
            print(e.code)
            code = 1
    except BaseException:
        traceback.print_exc()
        code = 1
    finally:
        sys.stdout, sys.stderr = old_out, old_err
    return json.dumps({"out": buf.getvalue(), "code": code})


def info():
    import platform
    return json.dumps({
        "version": sys.version.split()[0],
        "machine": platform.machine(),
        "executable": sys.executable,
    })
