"""CodePad Python 调试器：断点 / 单步 / 变量 / 调用栈。

通过 Java 桥 DebugBridge 与 UI 通信：
  publish(json)  -> 把暂停状态推给界面
  awaitCommand() -> 阻塞等待界面按钮（continue/step/next/return/stop）
"""

import io
import json
import sys
import traceback

_bridge = None


def _b():
    global _bridge
    if _bridge is None:
        from java import jclass

        _bridge = jclass("com.codepad.ide.run.DebugBridge")
    return _bridge


class _State:
    def __init__(self):
        self.filename = None
        self.breakpoints = set()
        self.mode = "continue"
        self.depth = 0
        self.running = True


_s = _State()


def _depth(frame):
    d = 0
    f = frame
    while f is not None:
        d += 1
        f = f.f_back
    return d


def _fmt(value):
    try:
        text = repr(value)
    except BaseException:
        text = "<unrepr>"
    if len(text) > 140:
        text = text[:137] + "..."
    return text


def _snapshot(frame, reason):
    variables = []
    try:
        items = list(frame.f_locals.items())
    except BaseException:
        items = []
    for name, value in items[:80]:
        if name.startswith("__"):
            continue
        variables.append({"name": name, "value": _fmt(value)})

    stack = []
    f = frame
    while f is not None and len(stack) < 12:
        stack.append(
            {
                "file": f.f_code.co_filename.split("/")[-1],
                "line": f.f_lineno,
                "func": f.f_code.co_name,
            }
        )
        f = f.f_back

    return {
        "type": "pause",
        "reason": reason,
        "file": frame.f_code.co_filename,
        "line": frame.f_lineno,
        "vars": variables,
        "stack": stack,
    }


def _pause(frame, reason):
    _b().publish(json.dumps(_snapshot(frame, reason)))
    cmd = _b().awaitCommand()
    if cmd == "stop":
        _s.running = False
        _s.mode = "continue"
        raise SystemExit(0)
    _s.depth = _depth(frame)
    if cmd == "step":
        _s.mode = "step"
    elif cmd == "next":
        _s.mode = "next"
    elif cmd == "return":
        _s.mode = "return"
    else:
        _s.mode = "continue"


def _trace(frame, event, arg):
    if not _s.running:
        return None
    if event == "call":
        return _trace
    if event != "line":
        return _trace
    filename = frame.f_code.co_filename
    if _s.filename is None or filename != _s.filename:
        return _trace

    line = frame.f_lineno
    if _s.mode == "continue":
        if line in _s.breakpoints:
            _pause(frame, "breakpoint")
        return _trace

    depth = _depth(frame)
    if _s.mode == "step":
        _pause(frame, "step")
    elif _s.mode == "next" and depth <= _s.depth:
        _pause(frame, "next")
    elif _s.mode == "return" and depth < _s.depth:
        _pause(frame, "return")
    return _trace


def debug_file(path, breakpoints_json):
    """返回 {"out": "...", "code": N}"""
    try:
        bps = json.loads(breakpoints_json) if breakpoints_json else []
    except BaseException:
        bps = []

    _s.filename = path
    _s.breakpoints = set(int(x) for x in bps)
    _s.mode = "continue"
    _s.depth = 0
    _s.running = True

    buf = io.StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout = sys.stderr = buf
    code = 0
    try:
        with open(path, "r", encoding="utf-8") as f:
            source = f.read()
        globs = {"__name__": "__main__", "__file__": path, "__builtins__": __builtins__}
        sys.argv = [path]
        sys.settrace(_trace)
        try:
            exec(compile(source, path, "exec"), globs)
        finally:
            sys.settrace(None)
    except SystemExit as e:
        code = int(e.code) if isinstance(e.code, int) else 0
    except BaseException:
        traceback.print_exc()
        code = 1
    finally:
        sys.settrace(None)
        sys.stdout, sys.stderr = old_out, old_err
        try:
            _b().publish(json.dumps({"type": "exit", "code": code}))
        except BaseException:
            pass

    return json.dumps({"out": buf.getvalue(), "code": code})
