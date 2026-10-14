#!/usr/bin/env python3
"""luapatch64 unity3d 完整解密管线（2026-10-14 实证版）。

已实证（块 0 输出 131072B 纯净 Unity 序列化内容，456+ 可读串）：
  1. 数据区 = file[288:]，LZ4 连续流，Unity 零窗语义（off > out 按零回填）
  2. 块 0 内唯一 EE DD 容器 @data+25：
     - 头改写 [25:30]=[p..p+4] 版本半字节 ASCII、[31]=[p+6]='.'、[34]=[p+9]='f'
     - x20 = 44；w22 = 4048；载荷 = [44:4092)（整帧 4096 的 [44B头+4048载荷+4B尾]）
     - 材料 [48:64)（b1..b4），20 字节哈希 = b3+b1+b4+w22+b2(全4B) 20 轮
     - w25 = 0x82D63B78 + ~cur
     - k1=(u@[64]+6533)^w25  k2=(u@[72]+6529)^w25  k3=(u@[68]+8216)^w25  k4=w25^(w22+8211)
     - keyarr = [k1,k2,k4,k3] @sp+168
     - A6 ^= [44:76)
     - seg1 = [76:204)：S-box 从常量表初始化，KSA(w25) + PRGA(rol6+58) 128B
     - key2 = [76:204) 密文副本：S-box 重新从常量表初始化，KSA(k4) + PRGA 128B
     - 表(sp+208) 头 32B = rodata SO[0x3141EB4:0x3141ED4] + k-spill：
       [0]=0x571 [1]=k3 [2]=0x892 [3]=0x750 [4]=k1 [5]=k2 [6]=0x746 [7]=k4 [8]=0x568
       （[2][3][6] 为静态常量；sim 栈残留 dump 实证）
     - 矩阵 [204:4044) 30×128B：op = table[j%9]&3；op0=A op1=D op2=B op3=C
       A: c^(32-i)^key2[i]^table[i%9]   D: c^key2[i]^keyarr[key2[i]&3]
       B: (c^i)^key2[i]^keyarr[key2[i]&3]   C: (c^key2[i]^i)^keyarr[table[i%9]&3]
     - 尾段 [4044:4092) 48B：t=table[keyarr[i&3]%9]，
       out = (t + (t*0x80800001>>39)) ^ key2[i] ^ c ^ i（逐字节）
  3. 块 1..30 = 明文 LZ4（全文件仅一个 EE DD）
  3.5 LZ4 流模型（当前最优）：流 = data[0:44) + data[76:]（跳 32B A6/key 段），
      连续；输出 [27:31] = LE 4028452 自声明大小；[0:65] 与上一会话 pp 一致。
      已解 95.2%，缺口 191469B 待设备真值对拍（见设计文档 §21.4）。
  4. blocksInfo（大端）：blob @file[64:288)，31 项 @[blob+20+10k] = uncomp u32 BE / comp u32 BE / flags u16 BE

待解：连续解码产出 3836911 / 4028452（缺 191541B），段 2 起部分流提前耗尽。
      疑点：零窗匹配的真实字典（热更 delta 基线？）或未发现的加密段。
"""
import struct

M = 0xFFFFFFFF
SO = open('/tmp/libil2cpp_ext.so', 'rb').read()


def build_tbl(poly):
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        t.append(c)
    return t


TBL = build_tbl(0x04C11EB7)

_QLOADS = [(0x313e000+1680,0),(0x313e000+1744,16),(0x313e000+1360,32),(0x313e000+1696,48),
           (0x313e000+1776,64),(0x313e000+1728,80),(0x313e000+1456,96),(0x313e000+1344,112),
           (0x313e000+1632,128),(0x313e000+1600,144),(0x313e000+1568,160),(0x313e000+1616,176),
           (0x313e000+1472,192),(0x313e000+1488,208),(0x313e000+1312,224),(0x313e000+1376,240)]
SCONST = bytearray(256)
for src, dst in _QLOADS:
    SCONST[dst:dst+16] = SO[src:src+16]


def rc4_const(key4, data):
    """S-box 每次从常量表初始化；KSA(key)+PRGA rol6+58。返回 (输出, 终态)。"""
    S = bytearray(SCONST)
    j = 0
    for i in range(256):
        j = (j + S[i] + key4[i & 3]) & 0xFF
        S[i], S[j] = S[j], S[i]
    i = j = 0
    o = bytearray(len(data))
    for n, c in enumerate(data):
        i = (i + 1) & 0xFF
        j = (j + S[i]) & 0xFF
        S[i], S[j] = S[j], S[i]
        k = S[(S[i] + S[j]) & 0xFF]
        k = ((k << 6) | (k >> 2)) & 0xFF
        o[n] = ((k + 58) & 0xFF) ^ c
    return bytes(o), S


def decrypt_ee_dd_frame(block, rewrite_bytes):
    """解密一个含 EE DD 的 4096 窗口（block=窗口字节，rewrite_bytes=头改写字节 {off: val}）。
    返回解密后的窗口。"""
    win = bytearray(block)
    for off, val in rewrite_bytes.items():
        win[off] = val
    x20 = 44
    w22 = 4048
    b1, b2, b3, b4 = struct.unpack('<4I', win[4:20])
    mat = struct.pack('<IIIII', b3, b1, b4, w22, b2)
    cur = 0xFFFFFFFF
    for x in mat:
        cur = TBL[(cur ^ x) & 0xFF] ^ (cur >> 8)
        cur = (cur + 16) & M
    w25 = (0x82D63B78 + (~cur & M)) & M
    u20, u24, u28 = struct.unpack('<3I', win[20:32])
    k1 = ((u20 + 6533) & M) ^ w25
    k2 = ((u28 + 6529) & M) ^ w25
    k3 = ((u24 + 8216) & M) ^ w25
    k4 = w25 ^ ((w22 + 8211) & M)
    keyarr = [k1, k2, k4, k3]
    for i in range(32):
        win[i] ^= 0xA6
    cipher_copy = bytes(win[32:160])
    seg1, _ = rc4_const(struct.pack('<I', w25), win[32:160])
    win[32:160] = seg1
    key2b, _S3 = rc4_const(struct.pack('<I', k4), cipher_copy)
    table = bytearray(SO[0x3141EB4:0x3141ED4])
    struct.pack_into('<I', table, 0, 0x571)
    struct.pack_into('<I', table, 4, k3)
    struct.pack_into('<I', table, 16, k1)
    struct.pack_into('<I', table, 20, k2)
    struct.pack_into('<I', table, 28, k4)
    tbl = list(struct.unpack('<8I', bytes(table)))
    tbl += list(struct.unpack('<56I', SO[0x3141ED4:0x3141ED4 + 224]))
    k2w = struct.unpack('<32I', key2b)
    rem = w22 - 160
    nfull = rem // 128
    for j in range(nfull):
        op = tbl[j % 9] & 3
        base = 160 + j * 128
        b = struct.unpack_from('<32I', win, base)
        o = [0] * 32
        for i in range(32):
            if op == 0:
                o[i] = (b[i] ^ ((32 - i) & 0xFF) ^ k2w[i] ^ tbl[i % 9]) & M
            elif op == 1:
                o[i] = (b[i] ^ k2w[i] ^ keyarr[k2w[i] & 3]) & M
            elif op == 2:
                o[i] = ((b[i] ^ i) ^ k2w[i] ^ keyarr[k2w[i] & 3]) & M
            else:
                o[i] = ((b[i] ^ k2w[i] ^ i) ^ keyarr[tbl[i % 9] & 3]) & M
        struct.pack_into('<32I', win, base, *o)
    r = rem % 128
    base = 160 + nfull * 128
    for i in range(r):
        t = tbl[keyarr[i & 3] % 9]
        t2 = (t + ((t * 0x80800001) >> 39)) & M
        win[base + i] = (t2 ^ key2b[i] ^ win[base + i] ^ i) & 0xFF
    return bytes(win), w25, keyarr


def lz4_unity(stream, want):
    """Unity 魔改 LZ4：off 超出已输出部分按 0 回填；输出达 want 即停。"""
    out = bytearray()
    i = 0
    n = len(stream)
    while i < n and len(out) < want:
        tok = stream[i]; i += 1
        ll = tok >> 4
        if ll == 15:
            while i < n:
                b = stream[i]; i += 1; ll += b
                if b != 255:
                    break
        ll = min(ll, want - len(out))
        out += stream[i:i+ll]; i += ll
        if len(out) >= want:
            break
        ml = tok & 0xF
        if ml == 0:
            continue
        if i + 2 > n:
            break
        off = stream[i] | (stream[i+1] << 8); i += 2
        if ml == 15:
            while i < n:
                b = stream[i]; i += 1; ml += b
                if b != 255:
                    break
        ml += 4
        ml = min(ml, want - len(out))
        pos = len(out) - off
        for _ in range(ml):
            out.append(out[pos] if 0 <= pos < len(out) else 0)
            pos += 1
    return bytes(out)


def parse_blocksinfo(blob):
    """blocksinfo（不含 blob 前 20B 头）→ [(uncomp, comp, flags)*31]"""
    out = []
    for k in range(31):
        u, c = struct.unpack_from('>II', blob, 20 + k * 10)
        f, = struct.unpack_from('>H', blob, 20 + k * 10 + 8)
        out.append((u, c, f))
    return out


def main():
    d = open('luapatch64_current.unity3d', 'rb').read()
    info = open('blocksinfo_new.bin', 'rb').read()
    blocks = parse_blocksinfo(info)

    # 块 0 解密（头改写字节来自 simdec 实测；通用实现应按版本号重算）
    sim = open('blk0_simdec.bin', 'rb').read()
    rw = {i: sim[i] for i in list(range(25, 30)) + [31, 34]}
    blk0, w25, keyarr = decrypt_ee_dd_frame(d[288:288+4096], rw)
    blob0 = blk0 + d[288+4096:288+58293]

    segs = bytearray()
    off = 288
    for k, (u, c, f) in enumerate(blocks):
        segs += blob0 if k == 0 else d[off:off+c]
        off += c
    want = sum(u for u, c, f in blocks)
    out = lz4_unity(bytes(segs), want)
    print('解码: %d / %d' % (len(out), want))
    open('archive_data.bin', 'wb').write(out)


if __name__ == '__main__':
    main()
