"""列出 ELF 的 DT_NEEDED（以及 PT_INTERP），用来找出缺失的共享库。

不依赖 readelf（Windows 上没有），直接解 ELF 头 + 程序头里的 PT_DYNAMIC。
"""
import struct, sys, os


def elf_info(path):
    d = open(path, 'rb').read()
    if d[:4] != b'\x7fELF':
        return None
    is64 = d[4] == 2
    lit = '<' if d[5] == 1 else '>'
    if is64:
        e_phoff = struct.unpack_from(lit + 'Q', d, 0x20)[0]
        e_phentsize = struct.unpack_from(lit + 'H', d, 0x36)[0]
        e_phnum = struct.unpack_from(lit + 'H', d, 0x38)[0]
    else:
        e_phoff = struct.unpack_from(lit + 'I', d, 0x1C)[0]
        e_phentsize = struct.unpack_from(lit + 'H', d, 0x2A)[0]
        e_phnum = struct.unpack_from(lit + 'H', d, 0x2C)[0]

    interp = None
    dyn = None
    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if is64:
            p_type = struct.unpack_from(lit + 'I', d, off)[0]
            p_offset = struct.unpack_from(lit + 'Q', d, off + 8)[0]
            p_vaddr = struct.unpack_from(lit + 'Q', d, off + 16)[0]
            p_filesz = struct.unpack_from(lit + 'Q', d, off + 32)[0]
        else:
            p_type = struct.unpack_from(lit + 'I', d, off)[0]
            p_offset = struct.unpack_from(lit + 'I', d, off + 4)[0]
            p_vaddr = struct.unpack_from(lit + 'I', d, off + 8)[0]
            p_filesz = struct.unpack_from(lit + 'I', d, off + 16)[0]
        if p_type == 3:  # PT_INTERP
            interp = d[p_offset:p_offset + p_filesz].split(b'\x00')[0].decode()
        elif p_type == 2:  # PT_DYNAMIC
            dyn = (p_offset, p_filesz)
        elif p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))

    needed = []
    rpath = None
    if dyn:
        off, size = dyn
        entsize = 16 if is64 else 8
        fmt = lit + ('qQ' if is64 else 'iI')
        strtab_addr = None
        entries = []
        for i in range(size // entsize):
            tag, val = struct.unpack_from(fmt, d, off + i * entsize)
            entries.append((tag, val))
            if tag == 5:      # DT_STRTAB
                strtab_addr = val
            if tag == 0:
                break

        def addr_to_off(addr):
            for vaddr, p_off, fsz in loads:
                if vaddr <= addr < vaddr + fsz:
                    return p_off + (addr - vaddr)
            return None

        str_off = addr_to_off(strtab_addr) if strtab_addr is not None else None
        if str_off is not None:
            for tag, val in entries:
                if tag == 1:  # DT_NEEDED
                    s = str_off + val
                    end = d.index(b'\x00', s)
                    needed.append(d[s:end].decode())
                elif tag == 15:  # DT_RPATH
                    rpath = d[str_off + val:d.index(b'\x00', str_off + val)].decode()
                elif tag == 29:  # DT_RUNPATH
                    rpath = d[str_off + val:d.index(b'\x00', str_off + val)].decode()
    return {'interp': interp, 'needed': needed, 'rpath': rpath,
            'machine': struct.unpack_from(lit + 'H', d, 18)[0]}


if __name__ == '__main__':
    for p in sys.argv[1:]:
        if not os.path.isfile(p):
            print('missing:', p)
            continue
        i = elf_info(p)
        if not i:
            print(p, '不是 ELF')
            continue
        print(os.path.basename(p), '| machine', i['machine'], '| interp', i['interp'], '| rpath', i['rpath'])
        for n in i['needed']:
            print('    NEEDED', n)
