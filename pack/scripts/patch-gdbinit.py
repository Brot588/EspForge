import pathlib, sys

OLD = "    file(TO_CMAKE_PATH $ENV{ESP_ROM_ELF_DIR} ESP_ROM_ELF_DIR)\n"
NEW = """    if("$ENV{ESP_ROM_ELF_DIR}" STREQUAL "")
        # EspForge: 资源包不含 esp-rom-elfs，且我们不用 OpenOCD/JTAG，
        # 跳过 gdbinit 生成，避免 file(TO_CMAKE_PATH) 参数不足而中断 configure。
        return()
    endif()
    file(TO_CMAKE_PATH $ENV{ESP_ROM_ELF_DIR} ESP_ROM_ELF_DIR)
"""

for t in sys.argv[1:]:
    p = pathlib.Path(t)
    if not p.is_file():
        print("missing:", p)
        continue
    s = p.read_text(encoding="utf-8", errors="replace")
    if "EspForge: 资源包不含 esp-rom-elfs" in s:
        print("already patched:", p)
        continue
    if OLD not in s:
        print("PATTERN NOT FOUND:", p)
        continue
    p.write_text(s.replace(OLD, NEW, 1), encoding="utf-8")
    print("patched:", p)
