#!/usr/bin/env python3
"""块 0 全解 + LZ4 oracle 实验。

语义全部按反汇编核实（2026-10-14）：
  - x20 = EE DD 位置 + 19 = 44；材料 [44:64)；u20/u24/u28 = @[64/68/72]
  - TAIL = 0x82D63B78；w25 = TAIL + ~cur（20 轮 CRC 哈希）
  - keyarr = [k1,k2,k4,k3]；key2 = RC4(k4) 解 seg1 副本
  - 表(sp+208) = [0x571, k3, F3a, F3b, k1, k2, F3c, k4, 0x568]
    （块 0 实测 F3 = [0x892, 0x750, 0x746]）
  - 分派 op = table[j%9]&3：op0→A op1→D op2→B op3→C
  - A: c ^ (32-i) ^ key2[i] ^ table[i%9]（全字）
  - B: c ^ i ^ key2[i] ^ keyarr[key2[i]&3]
  - C: c ^ key2[i] ^ i ^ keyarr[table[i%9]&3]
  - D: c ^ key2[i] ^ keyarr[key2[i]&3]
  - 尾段: out = (t + ((t*0x80800001)>>39)) ^ key2[i] ^ c ^ i（字节）
"""
import struct

M = 0xFFFFFFFF


def build_table(poly):
    tbl = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        tbl.append(c)
    return tbl


TBL = build_table(0x04C11EB7)


def rc4_rol6(key4, data):
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
        k = ((k << 6) | (k >> 2)) & 0xFF
        out[n] = ((k + 58) & 0xFF) ^ c
    return bytes(out)


def lz4_decode(data, verbose=True):
    """带诊断的 LZ4 block 解码，返回 (out, ok, death信息)。"""
    out = bytearray()
    i = 0
    n = len(data)
    while i < n:
        tok = data[i]
        i += 1
        ll = tok >> 4
        if ll == 15:
            while True:
                if i >= n:
                    return bytes(out), False, ('lit_len_overrun', i, len(out))
                b = data[i]
                i += 1
                ll += b
                if b != 255:
                    break
        if i + ll > n:
            return bytes(out), False, ('lit_overrun', i, len(out))
        out += data[i:i + ll]
        i += ll
        ml = tok & 0xF
        if ml == 0:
            continue  # 最后一个 token 可以只有字面量
        if i + 2 > n:
            return bytes(out), False, ('off_overrun', i, len(out))
        off = data[i] | (data[i + 1] << 8)
        i += 2
        if off == 0 or off > len(out):
            return bytes(out), False, ('bad_offset', i - 2, off, len(out))
        ml += 4
        if ml == 18:
            while True:
                if i >= n:
                    return bytes(out), False, ('ml_len_overrun', i, len(out))
                b = data[i]
                i += 1
                ml += b
                if b != 255:
                    break
        pos = len(out) - off
        for _ in range(ml):
            out.append(out[pos])
            pos += 1
    return bytes(out), True, ('end', i, len(out))


def decrypt_block0(blk, tail_const=0x82D63B78, fix_tail=True, verbose=True):
    blk = bytearray(blk)
    x20 = 44
    win = blk[x20:x20 + 4096]
    w22 = min(len(win), 4096)
    b1, b2, b3, b4 = struct.unpack('<4I', win[4:20])
    mat = struct.pack('<IIII', b3, b1, b4, w22) + struct.pack('<I', b2)[:3]
    cur = 0xFFFFFFFF
    for x in mat:
        cur = TBL[(cur ^ x) & 0xFF] ^ (cur >> 8)
        cur = (cur + 16) & M
    w25 = (tail_const + (~cur & M)) & M
    if verbose:
        print('w22=%d w25=%#x' % (w22, w25))
    u20, u24, u28 = struct.unpack('<3I', win[20:32])
    k1 = ((u20 + 6533) & M) ^ w25
    k2 = ((u28 + 6529) & M) ^ w25
    k3 = ((u24 + 8216) & M) ^ w25
    k4 = w25 ^ ((w22 + 8211) & M)
    keyarr = [k1, k2, k4, k3]
    if verbose:
        print('keyarr=[k1,k2,k4,k3]=[%08x, %08x, %08x, %08x]' % (k1, k2, k4, k3))
    for i in range(32):
        win[i] ^= 0xA6
    seg = rc4_rol6(struct.pack('<I', w25), win[32:160])
    win[32:160] = seg
    key2 = bytearray(rc4_rol6(struct.pack('<I', k4), win[32:160]))
    if verbose:
        print('key2 首 8B =', key2[:8].hex())
    # 表：块 0 实测（sp+208 残留 S-box 值 0x892/0x750/0x746）
    table = [0x571, k3, 0x892, 0x750, k1, k2, 0x746, k4, 0x568]
    k2w = struct.unpack('<32I', bytes(key2))
    rem = w22 - 160
    nfull = rem // 128
    ops = []
    for j in range(nfull):
        op = table[j % 9] & 3
        ops.append('DCBA'[ (op) ] if False else ['A','D','B','C'][op])
        base = 160 + j * 128
        b = struct.unpack_from('<32I', win, base)
        out = [0] * 32
        for i in range(32):
            if op == 0:    # A
                out[i] = (b[i] ^ ((32 - i) & 0xFF) ^ k2w[i] ^ table[i % 9]) & M
            elif op == 1:  # D
                out[i] = (b[i] ^ k2w[i] ^ keyarr[k2w[i] & 3]) & M
            elif op == 2:  # B
                out[i] = ((b[i] ^ i) ^ k2w[i] ^ keyarr[k2w[i] & 3]) & M
            else:          # C
                sel = table[i % 9] & 3
                out[i] = ((b[i] ^ k2w[i] ^ i) ^ keyarr[sel]) & M
        struct.pack_into('<32I', win, base, *out)
    if verbose:
        print('ops j0..%d: %s' % (nfull - 1, ''.join(ops)))
    r = rem % 128
    if r:
        base = 160 + nfull * 128
        for i in range(r):
            t = table[keyarr[i & 3] % 9]
            if fix_tail:
                t2 = (t + ((t * 0x80800001) >> 39)) & M
            else:
                t2 = (t + key2[i]) & M
            v = ((t2 ^ key2[i] ^ win[base + i] ^ i) & 0xFF)
            win[base + i] = v
    blk[x20:x20 + 4096] = win
    return bytes(blk), keyarr


def main():
    blk = open('luapatch64_current.unity3d', 'rb').read()[288:288 + 58293]
    sim = open('blk0_simdec.bin', 'rb').read()
    dec, keyarr = decrypt_block0(blk)
    open('blk0_pydec.bin', 'wb').write(dec)
    # 与 simdec 对拍（[0:1367) 是 LZ4 已验证区）
    diff = [i for i in range(5000) if dec[i] != sim[i]]
    print('pydec vs simdec 前 5000B 差异: %d 处, first=%s' % (len(diff), diff[:12]))
    v1367 = all(dec[i] == sim[i] for i in range(1367))
    print('[0:1367) 与 simdec 一致:', v1367)
    out, ok, info = lz4_decode(dec)
    print('LZ4(pydec): ok=%s info=%s' % (ok, info))
    out2, ok2, info2 = lz4_decode(sim)
    print('LZ4(simdec): ok=%s info=%s' % (ok2, info2))


if __name__ == '__main__':
    main()
