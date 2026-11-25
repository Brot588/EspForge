#!/usr/bin/env python3
"""
Minimal pure-python ELF inspector (replacement for file(1) + readelf(1) on Windows).

Reports for each ELF file: class, endianness, e_type, e_machine, PT_INTERP,
DT_NEEDED list, DT_SONAME, DT_RUNPATH/RPATH.
"""
import struct, sys, json, os

PT_LOAD, PT_DYNAMIC, PT_INTERP = 1, 2, 3
DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ, DT_SONAME, DT_RPATH, DT_RUNPATH = 0, 1, 5, 10, 14, 15, 29

MACHINES = {
    3: 'i386', 8: 'MIPS', 20: 'PowerPC', 40: 'ARM', 62: 'x86-64',
    94: 'Xtensa', 183: 'AArch64', 243: 'RISC-V',
}
E_TYPES = {0: 'NONE', 1: 'REL', 2: 'EXEC', 3: 'DYN', 4: 'CORE'}
ET_EXEC, ET_DYN = 2, 3


class Elf(object):
    def __init__(self, path):
        self.path = path
        with open(path, 'rb') as f:
            self.data = f.read()
        d = self.data
        if len(d) < 52 or d[0:4] != b'\x7fELF':
            raise ValueError('not an ELF file')
        self.cls = 32 if d[4] == 1 else 64
        self.endian = '<' if d[5] == 1 else '>'
        e = self.endian
        if self.cls == 64:
            (self.e_type, self.e_machine) = struct.unpack_from(e + 'HH', d, 16)
            self.e_phoff, = struct.unpack_from(e + 'Q', d, 32)
            self.e_phentsize, self.e_phnum = struct.unpack_from(e + 'HH', d, 54)
        else:
            (self.e_type, self.e_machine) = struct.unpack_from(e + 'HH', d, 16)
            self.e_phoff, = struct.unpack_from(e + 'I', d, 28)
            self.e_phentsize, self.e_phnum = struct.unpack_from(e + 'HH', d, 42)
        self.arch = MACHINES.get(self.e_machine, 'EM_%d' % self.e_machine)
        self.type = E_TYPES.get(self.e_type, str(self.e_type))
        self.segments = []
        self.interp = None
        self.needed = []
        self.soname = None
        self.runpath = None
        self.rpath = None
        self._parse_phdrs()

    def _parse_phdrs(self):
        d, e, n = self.data, self.endian, self.e_phnum
        for i in range(n):
            off = self.e_phoff + i * self.e_phentsize
            if off + (56 if self.cls == 64 else 32) > len(d):
                break
            if self.cls == 64:
                p_type, p_flags, p_off, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = \
                    struct.unpack_from(e + 'IIQQQQQQ', d, off)
            else:
                p_type, p_off, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = \
                    struct.unpack_from(e + 'IIIIIIII', d, off)
            seg = dict(type=p_type, offset=p_off, vaddr=p_vaddr, filesz=p_filesz,
                       memsz=p_memsz, flags=p_flags)
            self.segments.append(seg)
            if p_type == PT_INTERP and p_filesz:
                self.interp = d[p_off:p_off + p_filesz].split(b'\0')[0].decode('utf-8', 'replace')
        for seg in self.segments:
            if seg['type'] == PT_DYNAMIC:
                self._parse_dynamic(seg)
                break

    def _vaddr_to_off(self, vaddr):
        for s in self.segments:
            if s['type'] == PT_LOAD and s['vaddr'] <= vaddr < s['vaddr'] + s['filesz']:
                return s['offset'] + (vaddr - s['vaddr'])
        return None

    def _parse_dynamic(self, seg):
        d, e = self.data, self.endian
        entsz = 16 if self.cls == 64 else 8
        fmt = e + ('qQ' if self.cls == 64 else 'iI')
        ents = []
        off = seg['offset']
        end = min(off + seg['filesz'], len(d))
        while off + entsz <= end:
            tag, val = struct.unpack_from(fmt, d, off)
            if tag == DT_NULL:
                break
            ents.append((tag, val))
            off += entsz
        strtab = strsz = None
        for tag, val in ents:
            if tag == DT_STRTAB:
                strtab = val
            elif tag == DT_STRSZ:
                strsz = val
        self.needed, self.soname, self.runpath, self.rpath = [], None, None, None
        if strtab is None:
            return
        base = self._vaddr_to_off(strtab)
        if base is None:
            return
        if strsz:
            strdata = d[base:base + strsz]
        else:
            strdata = d[base:base + 65536]

        def s(idx):
            if idx >= len(strdata):
                return ''
            return strdata[idx:strdata.index(b'\0', idx)].decode('utf-8', 'replace')

        for tag, val in ents:
            if tag == DT_NEEDED:
                self.needed.append(s(val))
            elif tag == DT_SONAME:
                self.soname = s(val)
            elif tag == DT_RUNPATH:
                self.runpath = s(val)
            elif tag == DT_RPATH:
                self.rpath = s(val)

    @property
    def is_executable(self):
        """True for program binaries that the Android linker would try to exec:
        ET_EXEC, or ET_DYN with a PT_INTERP (PIE executables). excludes .so."""
        return self.e_type == ET_EXEC or (self.e_type == ET_DYN and self.interp)

    def file_desc(self):
        return 'ELF %d-bit %s %s, %s' % (
            self.cls, 'LSB' if self.endian == '<' else 'MSB',
            'AArch64' if self.arch == 'AArch64' else self.arch, self.type)

    def as_dict(self):
        return dict(path=self.path, cls=self.cls, arch=self.arch, type=self.type,
                    interp=self.interp, needed=self.needed, soname=self.soname,
                    runpath=self.runpath, rpath=self.rpath,
                    is_executable=self.is_executable)


def main():
    args = sys.argv[1:]
    as_json = '--json' in args
    paths = [a for a in args if not a.startswith('--')]
    out = []
    for p in paths:
        try:
            e = Elf(p)
            out.append(e.as_dict())
            if not as_json:
                print('%s: %s' % (p, e.file_desc()))
                if e.interp:
                    print('   interp: %s' % e.interp)
                if e.needed:
                    print('   needed: %s' % ', '.join(e.needed))
                if e.runpath or e.rpath:
                    print('   rpath/runpath: %s %s' % (e.runpath, e.rpath))
                if e.soname:
                    print('   soname: %s' % e.soname)
        except Exception as ex:
            out.append(dict(path=p, error=str(ex)))
            if not as_json:
                print('%s: NOT ELF (%s)' % (p, ex))
    if as_json:
        print(json.dumps(out, indent=2))


if __name__ == '__main__':
    main()
