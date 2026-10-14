#!/usr/bin/env python3
"""bundle 尾部 blocksInfo 解密暴力搜索 v2 —— 先廉价过滤，再 LZ4 验证。

过滤: 解密后 blocksInfo[16:20] (LE u32 blockCount) 必须 1..4096。
候选族:
  A. 逐字节异或位置函数: b[i] ^ (i+K), K∈0..255; b[i]^(i*K); b[i]^(i*i)
  B. 逐 u32 项异或计数器: entry[j] ^ (K+j), K∈0..65535（16位计数器，低2字节
     改变/高2字节=明文直通——与实测"首字节缓增、次字节恒02"吻合）
  C. HTPX w26 族 RC4 流（多种 n 取值 / 是否 rol6+58 / 是否跳过前 50B）
"""
import struct
import sys
import unityfs_probe
from htpx_decrypt import tbl_std, derive_w26, ksa, prga, transform, P

data = open(sys.argv[1] if len(sys.argv) > 1 else 'luapatch64.sample.unity3d', 'rb').read()
_h = unityfs_probe.Header(data)
size, cinfo, uinfo, flags = _h.size, _h.cinfo, _h.uinfo, _h.flags
tail = data[size - cinfo:size] if _h.info_at_end else data[_h.head_end:_h.head_end + cinfo]
print('size=%d cinfo=%d uinfo=%d flags=0x%x tail=%dB' % (size, cinfo, uinfo, flags, len(tail)))
print('tail[0:32]:', tail[:32].hex())


def bc_ok(raw):
    if len(raw) < 20:
        return False
    bc = struct.unpack_from('<I', raw, 16)[0]
    return 1 <= bc <= 4096


def lz4_try(raw, tag):
    try:
        out = unityfs_probe.lz4_decompress(raw[:cinfo], uinfo)
    except Exception:
        return False
    bc = struct.unpack('>I', out[16:20])[0] if len(out) >= 20 else -1
    print('  [HIT-LZ4] %s → %dB  blockCount(BE)=%d  [20:28]=%s' % (tag, len(out), bc, out[20:28].hex()))
    open('blocksinfo.bin', 'wb').write(out)
    return True


def gen_candidates():
    # A 族
    for k in range(256):
        yield ('xor-i+%d' % k, bytes(c ^ ((i + k) & 0xFF) for i, c in enumerate(tail)))
    for k in (2, 3, 4, 8):
        yield ('xor-i*%d' % k, bytes(c ^ ((i * k) & 0xFF) for i, c in enumerate(tail)))
    yield ('xor-i*i', bytes(c ^ ((i * i) & 0xFF) for i, c in enumerate(tail)))
    # B 族: u32 entry[j] ^ (K+j) —— 4 字节小端计数器加法
    arr = struct.unpack('<%dI' % (len(tail) // 4), tail)
    for K in range(0, 1 << 16):
        yield ('entry^K+%d' % K, struct.pack('<%dI' % len(arr), *[(v ^ ((K + j) & 0xFFFFFFFF)) for j, v in enumerate(arr)]))
    # B2: entry[j] ^ (K+4j)（按字节位置推进的 32 位计数器）
    for K in range(0, 1 << 16, 1):
        yield ('entry^K+4j(%d)' % K, struct.pack('<%dI' % len(arr), *[(v ^ ((K + 4 * j) & 0xFFFFFFFF)) for j, v in enumerate(arr)]))
    # C 族
    for n in (len(data) - 8, len(data), cinfo, uinfo, cinfo + 8, uinfo + 8, size - 8, size):
        w26 = derive_w26(tbl_std(P), n)
        for kname, key in (('w26', w26), ('k2', (struct.unpack_from('<I', data, 32)[0] ^ w26) & 0xFFFFFFFF)):
            S = ksa(struct.pack('<I', key))
            ks = prga(S, cinfo)
            yield ('%s(n=%d)-raw' % (kname, n), bytes(a ^ b for a, b in zip(tail, ks)))
            yield ('%s(n=%d)-rol' % (kname, n), bytes(a ^ b for a, b in zip(tail, transform(ks))))


n_cand = 0
survivors = []
for tag, raw in gen_candidates():
    n_cand += 1
    if bc_ok(raw):
        survivors.append((tag, raw))
        print('  [survivor] %s → %s' % (tag, raw[:32].hex()))
print('候选 %d，过滤幸存 %d' % (n_cand, len(survivors)))

hits = 0
for tag, raw in survivors:
    if lz4_try(raw, tag):
        hits += 1
    bc = struct.unpack_from('<I', raw, 16)[0]
    print('  survivor %s: blockCount=%d' % (tag, bc))
print('完成。LZ4 命中 %d' % hits)
