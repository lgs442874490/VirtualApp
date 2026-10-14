#!/usr/bin/env python3
"""bundle(2101) 数据区解密器 — 转写自 libil2cpp.so @0x3165580（PNG 校准版）。

架构（2026-10-13 实证）：
  文件 = 明文头(UnityFS 50B + pad + blocksInfo 明文 LZ4) + 加密数据区。
  数据区按 4096B 窗口喂给 0x3165580，每窗独立解密：
    1. 头 20B（5×u32 LE: b0..b4）+ w22=min(len,4096)
    2. 19 轮哈希: 输入 = b3(4)+b1(4)+b4(4)+w22(4)+b2[0:3]，
       初值 0xFFFFFFFF，cur = T[(cur^b)&0xFF] ^ (cur>>8); cur += 16
    3. w25 = 0x82D63B58 + ~cur
    4. key 阵列: k1=(u32@[20:24]+6533)^w25, k2=(u32@[28:32]+6529)^w25,
                 k3=(u32@[24:28]+8216)^w25, k4=w25^(w22+8211)
    5. 头 32B XOR 0xA6；seg1: RC4(w25, rol6+58) 解 [32:160)
    6. key2mat = RC4(k4, rol6+58) 解 [32:160) 的副本
    7. 中段: [160, w22) 每 128B 块 j 用算子 table[j%9]&3（4 算子，HTPX 同构）
    8. 尾段: <128B 逐字节
  RC4: KSA key = 4B LE；PRGA out = ((k rol6)+58) ^ c
"""
import struct

M = 0xFFFFFFFF
POLY_BUNDLE = 0x04C11EB7
TAIL_BUNDLE = 0x82D63B58
X_CONST = 16

SO = open('/tmp/libil2cpp_ext.so', 'rb').read()


def build_table(poly):
    tbl = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        tbl.append(c)
    return tbl


TBL = build_table(POLY_BUNDLE)


def load_optable():
    """sp+208 的 256B 算子/常量表：q 装载 + 首部改写。"""
    t = bytearray(256)
    # (qreg, src_off, dst_off) —— 0x3165e5c-0x3165ef0 的装载与 stp 顺序
    loads = [
        (0x313e000 + 1680, 0), (0x313e000 + 1744, 16), (0x313e000 + 1360, 32),
        (0x313e000 + 1696, 48), (0x313e000 + 1776, 64), (0x313e000 + 1728, 80),
        (0x313e000 + 1456, 96), (0x313e000 + 1344, 112), (0x313e000 + 1632, 128),
        (0x313e000 + 1600, 144), (0x313e000 + 1568, 160), (0x313e000 + 1616, 176),
        (0x313e000 + 1472, 192), (0x313e000 + 1488, 208), (0x313e000 + 1312, 224),
        (0x313e000 + 1376, 240),
    ]
    for src, dst in loads:
        t[dst:dst + 16] = SO[src:src + 16]
    # 改写: [0]=1393 [1]=k3 [4]=k1 [5]=k2 [7]=k4 [8]=1384 (k* 运行时填)
    struct.pack_into('<I', t, 0, 1393)
    struct.pack_into('<I', t, 32, 1384)
    return t


OPTABLE_BASE = load_optable()


def rc4_rol6(key4, data):
    """KSA(key LE 4B) + PRGA out=((k rol6)+58)^c，返回新 bytes。"""
    S = list(range(256))
    j = 0
    for i in range(256):
        j = (j + S[i] + key4[i & 3]) & 0xFF
        S[i], S[j] = S[j], S[i]
    out = bytearray(len(data))
    i = j = 0
    for n, c in enumerate(data):
        i = (i + 1) & 0xFF
        j = (j + S[i]) & 0xFF
        S[i], S[j] = S[j], S[i]
        k = S[(S[i] + S[j]) & 0xFF]
        k = (((k << 6) | (k >> 2)) & 0xFF)
        out[n] = ((k + 58) & 0xFF) ^ c
    return bytes(out)


def apply_mid_op(block, key2, table, keyarr, op):
    """中段 128B = 32×u32，四种算子（0x31661c0/0x3166210/0x3166248/0x316629c）。"""
    b = struct.unpack('<32I', block)
    k2 = struct.unpack('<32I', key2)
    out = [0] * 32
    # 跳转表 @0x3141EF0 = [0, 55, 20, 34]：op0→A(0x31661c0) op1→D(0x316629c) op2→B(0x3166210) op3→C(0x3166248)
    if op == 0:  # A @0x31661c0: block[i] ^= (32-i) ^ key2[i] ^ table[i%9]
        for i in range(32):
            out[i] = (b[i] ^ ((32 - i) & 0xFF) ^ k2[i] ^ struct.unpack_from('<I', table, (i % 9) * 4)[0]) & M
    elif op == 1:  # D @0x316629c: block[i] = block[i]^key2[i]^keyarr[key2[i]&3]
        for i in range(32):
            out[i] = (b[i] ^ k2[i] ^ keyarr[k2[i] & 3]) & M
    elif op == 2:  # B @0x3166210: block[i] = (block[i]^i)^key2[i]^keyarr[key2[i]&3]
        for i in range(32):
            out[i] = ((b[i] ^ i) ^ k2[i] ^ keyarr[k2[i] & 3]) & M
    else:  # C @0x3166248: block[i] = block[i]^key2[i]^i ^keyarr[table[i%9]&3]
        for i in range(32):
            sel = struct.unpack_from('<I', table, (i % 9) * 4)[0] & 3
            out[i] = ((b[i] ^ k2[i] ^ i) ^ keyarr[sel]) & M
    return struct.pack('<32I', *out)


def decrypt_window(win):
    """解密一个 ≤4096B 窗口（in-place bytearray），返回 bytearray。"""
    win = bytearray(win)
    n = len(win)
    w22 = min(n, 4096)
    if w22 < 32:
        return win
    b1, b2, b3, b4 = struct.unpack('<4I', win[4:20])
    mat = struct.pack('<IIII', b3, b1, b4, w22) + struct.pack('<I', b2)[:3]
    cur = 0xFFFFFFFF
    for x in mat:
        cur = TBL[(cur ^ x) & 0xFF] ^ (cur >> 8)
        cur = (cur + X_CONST) & M
    w25 = (TAIL_BUNDLE + (~cur & M)) & M
    u20, u24, u28 = struct.unpack('<3I', win[20:32])
    k1 = ((u20 + 6533) & M) ^ w25
    k2 = ((u28 + 6529) & M) ^ w25
    k3 = ((u24 + 8216) & M) ^ w25
    k4 = w25 ^ ((w22 + 8211) & M)
    keyarr = [k1, k2, k4, k3]
    for i in range(32):
        win[i] ^= 0xA6
    seg = rc4_rol6(struct.pack('<I', w25), win[32:160])
    win[32:160] = seg
    key2 = bytearray(rc4_rol6(struct.pack('<I', k4), win[32:160]))
    table = bytearray(OPTABLE_BASE)
    struct.pack_into('<I', table, 4, k3)
    struct.pack_into('<I', table, 16, k1)
    struct.pack_into('<I', table, 20, k2)
    struct.pack_into('<I', table, 28, k4)
    body = w22 - 32
    if body <= 128:
        return win
    rem = w22 - 160
    nfull = rem // 128
    for j in range(nfull):
        op = struct.unpack_from('<I', table, (j % 9) * 4)[0] & 3
        base = 160 + j * 128
        win[base:base + 128] = apply_mid_op(win[base:base + 128], key2, table, keyarr, op)
    r = rem % 128
    if r:
        base = 160 + nfull * 128
        for i in range(r):
            kk = key2[i]
            t = struct.unpack_from('<I', table, (keyarr[i & 3] % 9) * 4)[0]
            v = (((t + kk) & M) ^ (kk ^ win[base + i] ^ i)) & 0xFF
            win[base + i] = v
    return win


def decrypt_region(data):
    """数据区按 4096 窗口解密，返回拼接 bytes。"""
    out = bytearray()
    for off in range(0, len(data), 4096):
        out += decrypt_window(data[off:off + 4096])
    return bytes(out)
