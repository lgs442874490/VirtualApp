#!/usr/bin/env python3
"""已知明文攻击：验证 HTPX metadata 是否为 8 字节固定 pad XOR（立项阶段2锚点A）。

依据反汇编（libil2cpp.so 0x37779A4 函数）：RC4(自定义S0+4B key) 首 8 字节
→ 每字节 rol6 后 +58 → 与密文 8 字节块 XOR；key 全部来自编译期常量 → pad 恒定。
pad 未知但明文已知：il2cpp 标准头 = 0xFAB11BAF + 版本号(u32)。
本脚本暴力枚举「HTPX 头部长度 × metadata 版本」，按明文可读性打分。
"""
import struct
import sys

DATA = open(sys.argv[1] if len(sys.argv) > 1 else 'global-metadata.dat', 'rb').read()

MAGIC = struct.pack('<I', 0xFAB11BAF)
VERS = [29, 31, 30, 28, 27, 32]
HDRS = [8, 12, 16, 20, 24, 32, 48, 64]

KEYWORDS = [b'mscorlib', b'System.', b'UnityEngine', b'Il2Cpp']


def printable_ratio(b):
    ok = sum(1 for c in b if 32 <= c < 127 or c in (0, 9, 10, 13))
    return ok / max(1, len(b))


print('%-4s %-4s %-22s %-8s %s' % ('hdr', 'ver', 'pad', 'print%', 'keywords'))
best = None
for hdr in HDRS:
    enc = DATA[hdr:hdr + 8]
    for ver in VERS:
        plain8 = MAGIC + struct.pack('<I', ver)
        pad = bytes(a ^ b for a, b in zip(enc, plain8))
        body = DATA[hdr:]
        out = bytes(c ^ pad[i % 8] for i, c in enumerate(body[:65536]))
        ratio = printable_ratio(out[:8192])
        hits = {kw.decode(): out.count(kw) for kw in KEYWORDS}
        tag = ''
        if best is None or ratio > best[0]:
            best = (ratio, hdr, ver, pad)
            tag = ' <-- best'
        print('%-4d %-4d %-22s %-8.1f %s%s' % (
            hdr, ver, pad.hex(), ratio * 100,
            {k: v for k, v in hits.items() if v}, tag))

ratio, hdr, ver, pad = best
print('\nBEST: hdr=%d ver=%d pad=%s print=%.1f%%' % (hdr, ver, pad.hex(), ratio * 100))
out = bytes(c ^ pad[i % 8] for i, c in enumerate(DATA[hdr:]))
open('metadata_plain.bin', 'wb').write(out)
import re
strs = re.findall(rb'[ -~]{8,40}', out[:4096])
print('头部字段:', [struct.unpack_from('<I', out, i)[0] for i in range(0, 64, 4)])
print('首部可读串:', [s.decode() for s in strs[:10]])
for kw in KEYWORDS:
    print('全文 %r ×%d' % (kw, out.count(kw)))
