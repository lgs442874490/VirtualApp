#!/usr/bin/env python3
"""HTPX 解密器 —— libil2cpp.so 0x377A028 函数的 Python 复现（立项阶段2）。

算法（反汇编实测）：
  容器: [0:4]="HTPX" [4:8]=size(u32) [8:]=payload(size-8 字节, 整体加密)
  解密后: [0:4]=0xFAB11BAF [4:8]=版本号(游戏运行时回写) [8:]=il2cpp 元数据本体
  key 材料:
    keyobj  = rodata double@0x3721328 (8B) + u32 0x82D63B78
    P       = keyobj[0:4]  (CRC 表"多项式")
    K48     = keyobj[4:8]
    CRC 表  = 256×u32, 8 级位下降混入 P（递归形式经 oracle 枚举确定）
    w26     = 0x82D63B78 + ~crc_hash(size-8)   ← RC4 4 字节 key
      hash: c=tab[255&~n]; c=(c^0xFFFFFF)+K48; 3 轮(字节 n>>8/16/24):
            c = tab[(c^b)&0xFF] ^ (c>>8); c += K48
    S0      = rodata@0x371E520.. 的 256 字节（16×16B 固定映射，非单位置换!）
    变换    = rol8(K,6) + 58
  三段:
    [0:128)              RC4(S0,w26) 流
    [128:128+128*nchunks) 128B 块 × n=(size-136)>>7, 算子= key9[n%9]&3:
        0: c^j ^pad[j] ^key4[pad[j]&3]      (j=0..31, u32)
        1: c^32 ^pad[j] ^key9[j%9]
        2: c^pad[j] ^key4[pad[j]&3]
        3: c^j ^pad[j] ^key4[key9[j%9]&3]
       pad  = transform(RC4(S0, key2)) 首128B, key2 = u32@32 ^ w26
       key9/key4 = 14×u32 表（混入 u32@24/40/84 与 4 个立即数）
    尾部 (<128B)          逐字节: pad[i]^(c^i) ^ (k + k//255),
       k = key9[key4[i&3]%9], magic 0x80808081>>39 = k//255
"""
import struct
import sys

SO = 'libil2cpp.so'
M = 0xFFFFFFFF

_seg = None
def so_bytes(vaddr, n):
    """按 PT_LOAD 段映射读 .so 虚拟地址内容。"""
    global _seg
    if _seg is None:
        import struct as st
        hdr = open(SO, 'rb').read(0x40)
        phoff = st.unpack_from('<Q', hdr, 0x20)[0]
        phentsize = st.unpack_from('<H', hdr, 0x36)[0]
        phnum = st.unpack_from('<H', hdr, 0x38)[0]
        with open(SO, 'rb') as f:
            f.seek(phoff)
            ph = f.read(phentsize * phnum)
        _seg = []
        for i in range(phnum):
            pt, fl, off, va, pa, fsz = st.unpack_from('<IIQQQQ', ph, i * phentsize)
            if pt == 1:
                _seg.append((off, va, fsz))
    for off, va, fsz in _seg:
        if va <= vaddr < va + fsz:
            with open(SO, 'rb') as f:
                f.seek(off + (vaddr - va))
                return f.read(n)
    raise ValueError('vaddr 0x%x 不在任何 PT_LOAD 内' % vaddr)


# ---- 固定常量（反汇编提取） ----
KEYOBJ = so_bytes(0x3721328, 8) + struct.pack('<I', 0x82D63B78)
P = struct.unpack('<I', KEYOBJ[0:4])[0]
K48 = struct.unpack('<I', KEYOBJ[4:8])[0]
K812 = 0x82D63B78

S0_MAP = [(0, 1680), (16, 1744), (32, 1360), (48, 1696), (64, 1776), (80, 1728),
          (96, 1456), (112, 1344), (128, 1632), (144, 1600), (160, 1568),
          (176, 1616), (192, 1472), (208, 1488), (224, 1312), (240, 1376)]
S0 = bytearray(256)
_base = 0x371E000
for dst, o in S0_MAP:
    S0[dst:dst + 16] = so_bytes(_base + o, 16)
S0 = bytes(S0)


def rol8(v):
    return ((v << 6) | (v >> 2)) & 0xFF


def transform(ks):
    return bytes((rol8(k) + 58) & 0xFF for k in ks)


def ksa(key):
    S = list(S0)
    j = 0
    for i in range(256):
        j = (j + S[i] + key[i & 3]) & 0xFF
        S[i], S[j] = S[j], S[i]
    return S


def prga(S, n):
    i = j = 0
    out = bytearray()
    for _ in range(n):
        i = (i + 1) & 0xFF
        j = (j + S[i]) & 0xFF
        S[i], S[j] = S[j], S[i]
        out.append(S[(S[i] + S[j]) & 0xFF])
    return bytes(out)


# ---- CRC 表（递归形式候选，oracle 裁决） ----

def tbl_std(P):
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ (P if c & 1 else 0)
        t.append(c & M)
    return t


def tbl_prexor(P):
    # c' = ((c^P)>>1) ^ (P if (c^P)&1==0 else 0)
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            u = c ^ P
            c = ((u >> 1) ^ (P if (u & 1) == 0 else 0)) & M
        t.append(c)
    return t


def tbl_ladder(P):
    # 按 0x377A14C 起 8 级展开逐级转写：
    # 每级: t=v>>1, 测 v&1; 置位则 u=P^t, v'=u>>1, 测 u&1, 置位则 v''=P^v' …
    t = []
    for i in range(256):
        v = i & M
        for _ in range(8):
            tt = v >> 1
            if not (v & 1):
                v = tt
                continue
            u = P ^ tt
            v = u >> 1
            if u & 1:
                v = P ^ v
        t.append(v & M)
    return t


def crc_hash(table, n):
    c = table[(~n) & 0xFF]
    c = ((c ^ 0xFFFFFF) + K48) & M
    for sh in (8, 16, 24):
        b = (n >> sh) & 0xFF
        c = (table[(c ^ b) & 0xFF] ^ (c >> 8)) & M
        c = (c + K48) & M
    return ((~c) & M)


def derive_w26(table, n):
    return (K812 + crc_hash(table, n)) & M


def expand_keys(buf, plain128, w26):
    """buf=加密文件全文 bytes；plain128=前128字节明文（key2流解密的栈副本）；返回 (key9[9], key4[4])。"""
    u32 = lambda o: struct.unpack_from('<I', buf, o)[0]
    pu = [struct.unpack_from('<I', plain128, k * 4)[0] for k in range(16)]
    key2 = (u32(32) ^ w26) & M
    w1 = 20243 | (913 << 16)        # 0x03914F13
    w14c = 57358 | (52 << 16)       # 0x0034E00E
    w0 = 28236 | (131 << 16)        # 0x00836E4C
    w14d = 47537 | (795 << 16)      # 0x031BB9B1
    w13 = (u32(84) + 480616) & M
    w14 = (pu[15] + 10963341) & M
    w15 = (pu[11] + 3441317) & M
    w12 = (u32(40) + 530782) & M
    w13 ^= key2
    w15 ^= w26
    w9 = w14 ^ key2
    w9 = w9 ^ w14c
    w12 ^= w26
    w17 = pu[3] ^ w1
    w15b = w9 ^ w15
    w13b = w13 ^ w0
    w14e = pu[7] ^ w17
    w16b = pu[8] ^ w15b
    w12b = w13b ^ w12
    w9c = u32(24) ^ w14d
    w8b = w12b ^ u32(32)
    key9 = [w17, w9, w14e, w16b, w12b, w13b, w9c, w15b, w8b]
    key4 = [w12, w13, w15, w9 if False else (pu[15] + 10963341 & M) ^ key2]
    # key4 = [sp+48,52,56,60] = w12_B, w13_A, w15_A, w9_A
    key4 = [w12, w13, w15, (pu[15] + 10963341) & M ^ key2]
    return key9, key4


def decrypt(data, verbose=True):
    size = struct.unpack_from('<I', data, 4)[0]
    assert data[0:4] == b'HTPX', 'magic 不是 HTPX'
    if size != len(data) and verbose:
        print('  [warn] 头部 size=%d != 文件大小 %d，按头部 size 处理' % (size, len(data)))
        data = data[:size]
    payload = bytearray(data[8:])
    n = len(payload)

    table = None
    w26 = None
    for name, tbl in (('std', tbl_std(P)), ('prexor', tbl_prexor(P)), ('ladder', tbl_ladder(P))):
        w = derive_w26(tbl, n)
        ks = transform(prga(ksa(struct.pack('<I', w)), 8))
        hdr = struct.unpack('<II', bytes(a ^ b for a, b in zip(payload[0:8], ks)))
        # payload[0:8] = stringLiteralCount + stringLiteralOffset（FAB11BAF/版本写在文件头）
        if 0 < hdr[0] < n and 0 < hdr[1] < size:
            table, w26 = tbl, w
            if verbose:
                print('  CRC 递归命中: %s  w26=0x%08X  stringLit cnt=%d off=%d'
                      % (name, w, hdr[0], hdr[1]))
            break
    else:
        raise RuntimeError('三种 CRC 递归均未命中合理明文头')

    # 第一段: payload[0:128) ← RC4(w26)
    ks128 = transform(prga(ksa(struct.pack('<I', w26)), 128))
    for i in range(min(128, n)):
        payload[i] ^= ks128[i]
    if n <= 128:
        return bytes(payload), w26

    # 栈副本 = 前128B明文：先 memcpy 密文，再用 key2 流解密（loop1）
    # 中间块/尾部的 pad、key9/key4 全部从这个明文副本取数
    key2 = (struct.unpack_from('<I', data, 32)[0] ^ w26) & M
    ks2 = transform(prga(ksa(struct.pack('<I', key2)), 128))
    plain128 = bytes(a ^ b for a, b in zip(data[8:136], ks2))
    pad_u32 = struct.unpack('<32I', plain128)
    key9, key4 = expand_keys(data, plain128, w26)

    # 中间块
    nch = (size - 136) >> 7
    for c in range(nch):
        off = 128 + c * 128
        ch = list(struct.unpack_from('<32I', payload, off))
        case = key9[c % 9] & 3
        # 跳表字节 @0x3722EF6: case0→A, case1→D, case2→B, case3→C
        if case == 0:    # A: ^j ^pad ^key4[pad&3]
            for j in range(32):
                p = pad_u32[j]
                ch[j] = (ch[j] ^ j ^ p ^ key4[p & 3]) & M
        elif case == 1:  # D: ^j ^pad ^key4[key9[j%9]&3]
            for j in range(32):
                p = pad_u32[j]
                ch[j] = (ch[j] ^ j ^ p ^ key4[key9[j % 9] & 3]) & M
        elif case == 2:  # B: ^(32-j) ^pad ^key9[j%9]（w0 从 32 逐轮减 1）
            for j in range(32):
                ch[j] = (ch[j] ^ (32 - j) ^ pad_u32[j] ^ key9[j % 9]) & M
        else:            # C: ^pad ^key4[pad&3]（无 ^j）
            for j in range(32):
                p = pad_u32[j]
                ch[j] = (ch[j] ^ p ^ key4[p & 3]) & M
        struct.pack_into('<32I', payload, off, *ch)

    # 尾部
    rem = n & 0x7F
    toff = n & ~0x7F
    for i in range(rem):
        k = key9[key4[i & 3] % 9]
        k = (k + k // 255) & M
        payload[toff + i] ^= (plain128[i] ^ i ^ k) & 0xFF
    return bytes(payload), w26


if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else 'global-metadata.dat'
    data = open(path, 'rb').read()
    print('输入: %s (%d B) magic=%s size@4=%d' % (
        path, len(data), data[0:4], struct.unpack_from('<I', data, 4)[0]))
    payload, w26 = decrypt(data)
    ver = 29
    plain = b'\xaf\x1b\xb1\xfa' + struct.pack('<I', ver) + payload
    out = path + '.plain'
    open(out, 'wb').write(plain)
    print('解密完成 → %s (%d B)' % (out, len(plain)))
    import re
    strs = re.findall(rb'[ -~]{8,40}', plain[:8192])
    print('头部 u32:', [struct.unpack_from('<I', plain, i)[0] for i in range(0, 64, 4)])
    print('首部可读串:', [s.decode() for s in strs[:12]])
    for kw in (b'mscorlib', b'UnityEngine', b'System.', b'Il2Cpp'):
        print('全文 %r ×%d' % (kw, plain.count(kw)))
