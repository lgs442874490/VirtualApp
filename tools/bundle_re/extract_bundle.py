#!/usr/bin/env python3
"""团结 2101 bundle 全量解包器（立项阶段2-3）。

布局（libil2cpp.so 静态分析 + 样本验证）：
  [0:50]    UnityFS 头（version=2101, BE）
  [64:64+192] blocksInfo（明文 LZ4HC，16 对齐），解码后全字段大端：
            hash16 + blockCount>u32 + N×(unc u32, comp u32, flags u16) + nodeCount + node(name...)
  [64:64+Σcomp] 数据块流；每块:
            [0:32]  块头（含 key 种子，游戏 DLL 解密器同布局）
            [32:comp] RC4(key=w26(comp_size)) + 每字节 rol6 后 +58 的流密文
  全部块拼接解压 = 容器流: [0:331] blocksInfo 原文 + Unity 序列化资产
"""
import struct
import sys
from htpx_decrypt import tbl_std, derive_w26, ksa, prga, transform, P
import unityfs_probe


def lz4_decode(src, target):
    dst = bytearray(); i = 0; n = len(src)
    while i < n and len(dst) < target:
        token = src[i]; i += 1
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[i]; i += 1; lit += b
                if b != 255: break
        dst += src[i:i+lit]; i += lit
        if len(dst) >= target or i >= n: break
        off = src[i] | (src[i+1] << 8); i += 2
        ml = token & 0xF
        if ml == 15:
            while True:
                b = src[i]; i += 1; ml += b
                if b != 255: break
        ml += 4
        s = len(dst) - off
        if s < 0:
            raise ValueError('bad offset %d at %d (块损坏或方案不符)' % (off, len(dst)))
        for j in range(ml):
            dst.append(dst[s + j])
    return bytes(dst[:target])


def parse_blocks_info(bi):
    bc = struct.unpack_from('>I', bi, 16)[0]
    blocks = []
    off = 20
    for _ in range(bc):
        unc, comp, fl = struct.unpack_from('>IIH', bi, off)
        blocks.append((unc, comp, fl))
        off += 10
    nc = struct.unpack_from('>I', bi, off)[0]; off += 4
    nodes = []
    for _ in range(nc):
        o, s = struct.unpack_from('>QQ', bi, off); off += 16
        f = struct.unpack_from('>I', bi, off)[0]; off += 4
        e = bi.index(b'\x00', off)
        nodes.append((bi[off:e].decode(), o, s, f))
        off = e + 1
    return blocks, nodes


def unpack(path, out_prefix=None):
    d = open(path, 'rb').read()
    # blocksInfo 明文 LZ4 流起点：头部后对齐到 16
    start = (50 + 15) // 16 * 16
    bi = lz4_decode(d[start:start + 4096], 331)
    blocks, nodes = parse_blocks_info(bi)
    print('blocksInfo: %d 块, 节点 %s' % (len(blocks), [(n, o, s) for n, o, s, f in nodes]))
    data0 = start + 192 if d[start:start+16] == b'\x00'*16 or True else start
    # 数据块流紧跟 blocksInfo 压缩流之后（每块自带 32B 块头）
    pos = start + 192
    stream = bytearray()
    for i, (unc, comp, fl) in enumerate(blocks):
        blk = d[pos:pos + comp]
        pos += comp
        key = struct.pack('<I', derive_w26(tbl_std(P), comp))
        ks = transform(prga(ksa(key), max(0, comp - 32)))
        body = bytes(a ^ b for a, b in zip(blk[32:], ks))
        out = lz4_decode(body, unc)
        print('  块%d: unc=%d comp=%d → 解压 %d B %s' % (i, unc, comp, len(out), '✓' if len(out) == unc else '✗'))
        stream += out
    out_prefix = out_prefix or path + '.stream'
    open(out_prefix, 'wb').write(stream)
    print('容器流 %d B → %s' % (len(stream), out_prefix))
    return bytes(stream)


if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else 'luapatch64.sample.unity3d'
    stream = unpack(path)
    kw = sys.argv[2] if len(sys.argv) > 2 else '雪原贸易站'
    c = stream.count(kw.encode())
    print('搜索 %r: %d 处' % (kw, c))
    if c:
        i = stream.find(kw.encode())
        print('首现 @%d: %r' % (i, stream[max(0,i-40):i+80]))
