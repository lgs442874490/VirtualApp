#!/usr/bin/env python3
"""团结(Tuanjie) bundle 加密 key 搜索：
用 metadata 全文的 16 字节候选串做 AES-128-ECB，验证签名
  AES_ECB(key, key_sig) ^ data_sig == b"#$unity3dchina!@"
"""
import re
import struct
import sys

# ---- 纯 Python AES-128 ECB 单块加密 ----
_SBOX = bytes.fromhex(
    '637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0'
    'b7fd9326363ff7cc34a5e5f171d8311504c723c31896059a071280e2eb27b275'
    '09832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cf'
    'd0efaafb434d338545f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2'
    'cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814de5e0bdb'
    'e0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08'
    'ba78252e1ca6b4c6e8dd741f4bbd8b8a703eb5664803f60e613557b986c11d9e'
    'e1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16')
_RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36]


def _xtime(a):
    a <<= 1
    return (a ^ 0x1B) & 0xFF if a & 0x100 else a


def aes128_ecb_encrypt_block(key16, block16):
    def key_expansion(k):
        w = [list(k[i * 4:i * 4 + 4]) for i in range(4)]
        for i in range(4, 44):
            t = list(w[i - 1])
            if i % 4 == 0:
                t = t[1:] + t[:1]
                t = [_SBOX[b] for b in t]
                t[0] ^= _RCON[i // 4 - 1]
            w.append([w[i - 4][j] ^ t[j] for j in range(4)])
        rks = []
        for r in range(11):
            rk = []
            for c in range(4):
                rk += w[r * 4 + c]
            rks.append(rk)
        return rks

    rks = key_expansion(key16)
    s = list(block16)
    def add_rk(s, rk):
        for i in range(16):
            s[i] ^= rk[i]
    def sub_shift(s):
        s[:] = [_SBOX[b] for b in s]
        s[1], s[5], s[9], s[13] = s[5], s[9], s[13], s[1]
        s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
        s[3], s[7], s[11], s[15] = s[15], s[3], s[7], s[11]
    def mix_col(s, i):
        a, b, c, d = s[i], s[i + 1], s[i + 2], s[i + 3]
        s[i] = _xtime(a) ^ (_xtime(b) ^ b) ^ c ^ d
        s[i + 1] = a ^ _xtime(b) ^ (_xtime(c) ^ c) ^ d
        s[i + 2] = a ^ b ^ _xtime(c) ^ (_xtime(d) ^ d)
        s[i + 3] = (_xtime(a) ^ a) ^ b ^ c ^ _xtime(d)
    add_rk(s, rks[0])
    for rnd in range(1, 10):
        sub_shift(s)
        for i in (0, 4, 8, 12):
            mix_col(s, i)
        add_rk(s, rks[rnd])
    sub_shift(s)
    add_rk(s, rks[10])
    return bytes(s)


TARGET_SIG = b'#$unity3dchina!@'


def find_key(key_sig, data_sig, candidates):
    for k in candidates:
        ks = aes128_ecb_encrypt_block(k, key_sig)
        sig = bytes(x ^ y for x, y in zip(data_sig, ks))
        if sig == TARGET_SIG:
            return k
    return None


def main():
    bundle = open('luapatch64.sample.unity3d', 'rb').read()
    # UnityPy 布局: unknown(4)@50 data(16)@54 key(16)@70 skip@86 data_sig@87 key_sig@103
    data = bundle[54:70]
    key = bundle[70:86]
    data_sig = bundle[87:103]
    key_sig = bundle[103:119]
    print('data    =', data.hex())
    print('keyvec  =', key.hex())
    print('data_sig=', data_sig.hex())
    print('key_sig =', key_sig.hex())

    md = open('global-metadata.dat.plain', 'rb').read()
    cands = set(m.group(0) for m in re.finditer(rb'(?=[ -~]{16})[ -~]{16}', md))
    # 也纳入 keva 可能路径的常见串——先纯 metadata
    print('候选 16 字节串: %d 个' % len(cands))
    found = find_key(key_sig, data_sig, cands)
    if found:
        print('★ KEY 命中: %r' % found)
        return 0
    print('metadata 16字节串未命中，扩展：所有 16 字节窗口（慢）')
    step = 1
    for off in range(0, len(md) - 16, step):
        k = md[off:off + 16]
        if find_key(key_sig, data_sig, (k,)):
            print('★ KEY 命中 @0x%x: %r' % (off, k))
            return 0
        if off % 1000000 == 0:
            print('  ...0x%x' % off, flush=True)
    print('未找到')
    return 1


if __name__ == '__main__':
    sys.exit(main())
