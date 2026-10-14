#!/usr/bin/env python3
"""libil2cpp.so 立即数/字面量扫描器 —— 定位自研解密函数（立项文档阶段1）。

背景：字符串层搜不到 HTPX/UnityFS/2101（作者防字符串搜索，魔数比较走 ARM64
立即数），故在指令层扫描：
  1. 字面量池原始字节：LDR Xn, =const 形式的 32/64 位常量直接躺在 .rodata/.text；
  2. MOVZ+MOVK 指令对：movz wN,#lo16 + movk wN,#hi16,lsl#16 拼出 32 位比较常量；
  3. MOVZ 单发 16 位比较值（HT/HTPX 半字、2101 等）；
  4. CMP/SubsImm 的 imm12（2101 < 4096，会走 CMP #imm 而非 MOVZ）。

纯 Python，无第三方依赖。60MB 扫描约 10-30 秒。

用法：
  python3 imm_scan.py <so 路径>            # 全量目标扫描报告
  python3 imm_scan.py <so 路径> --all      # 额外导出全部 MOVZ+MOVK 拼合常量直方图
"""
import re
import struct
import sys

# ---- 目标常量（32 位比较值） ----
TARGETS_U32 = [
    ('il2cpp_std_magic', 0xFAB11BAF),   # 标准 il2cpp sanity 魔数，解密产物必过校验
    ('HTPX_le', 0x58505448),            # "HTPX" 小端 u32
    ('HTPX_be', 0x48545058),            # "HTPX" 大端 u32
    ('unity_Unit_le', 0x74696E55),      # "Unit"
    ('unity_yFS0_le', 0x00534679),      # "yFS\0"
    ('unity_yFS_le', 0x46535379),       # 另一种对齐 "yFSS"? 备用
    ('tea_delta', 0x9E3779B9),          # TEA/XXTEA delta
    ('md5_init_a', 0x67452301),         # MD5/SHA1 初值
    ('sha256_h0', 0x6A09E667),          # SHA256 IV[0]
    ('aes_sbox_dw0', 0x7B777C63),       # AES S-box 首双字（LE）
    ('aes_inv_dw0', 0xD56A0952),        # AES 逆 S-box 首双字
    ('crc32_poly', 0xEDB88320),         # CRC32 反射多项式
    ('sm4_sbox_dw0', 0xFEE990D6),       # SM4 S-box 首双字
]

# 有嫌疑的 16 位单发立即数（MOVZ hw=0 或 CMP 不到的半字比较）
SINGLES_U16 = {
    0x1BAF: 'magic_lo', 0xFAB1: 'magic_hi',
    0x5448: 'HT_le', 0x5850: 'PX_le',
    0x5058: 'PX_be', 0x4854: 'HT_be',
    0x6E55: 'Un_le', 0x7469: 'it_le', 0x4679: 'yF_le', 0x0053: 'S0_hi',
    0x5346: 'FS_be', 0x7955: 'Uy_be', 0x4653: 'FS_le2',
    0x0835: '2101',
}

RAW_BYTE_PATTS = [
    ('il2cpp_magic@literal', b'\xaf\x1b\xb1\xfa'),
    ('HTPX@literal', b'HTPX'),
    ('HTPX_rev@literal', b'XPTH'),
    ('UnityFS@literal', b'UnityFS'),
    ('tea_delta@literal', b'\xb9\x79\x37\x9e'),
    ('aes_sbox@literal', b'\x63\x7c\x77\x7b'),
    ('aes_inv@literal', b'\x52\x09\x6a\xd5'),
    ('crc32@literal', b'\x20\x83\xb8\xed'),
]

# ARM64 指令顶层字段
MOVZ_TOP = (0x52800000, 0xD2800000)
MOVK_TOP = (0x72800000, 0xF2800000)
MARK = re.compile(rb'[\x52\x72\xd2\xf2]')


def decode_insn(w):
    top = w & 0xFF800000
    if top in MOVZ_TOP:
        kind = 'movz'
    elif top in MOVK_TOP:
        kind = 'movk'
    else:
        return None
    return kind, (w >> 21) & 3, (w >> 5) & 0xFFFF, w & 0x1F


def elf_load_segments(path):
    """ELF64 PT_LOAD 段表：[(p_offset, p_vaddr, p_filesz)]，文件偏移→虚拟地址换算用。"""
    data = open(path, 'rb').read(0x40)
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]
    segs = []
    with open(path, 'rb') as f:
        f.seek(e_phoff)
        ph = f.read(e_phentsize * e_phnum)
    for i in range(e_phnum):
        p_type, p_flags, p_off, p_va, _pa, p_filesz = struct.unpack_from('<IIQQQQ', ph, i * e_phentsize)
        if p_type == 1:  # PT_LOAD
            segs.append((p_off, p_va, p_filesz))
    return segs


def off2vaddr(segs, off):
    for p_off, p_va, p_filesz in segs:
        if p_off <= off < p_off + p_filesz:
            return p_va + (off - p_off)
    return None


def scan(path, dump_all=False):
    data = open(path, 'rb').read()
    segs = elf_load_segments(path)
    print('ELF PT_LOAD 段: %d 个；文件 %d B' % (len(segs), len(data)))

    movz, movk = [], []   # (insn_off, rd, hw, imm)
    cmp835 = 0            # CMP/SUBS wN,#2101
    for m in MARK.finditer(data):
        off = m.start()
        if off % 4 != 3:
            continue
        w = int.from_bytes(data[off - 3:off + 1], 'little')
        d = decode_insn(w)
        if d is None:
            continue
        kind, hw, imm, rd = d
        (movz if kind == 'movz' else movk).append((off - 3, rd, hw, imm))
        if (w & 0xFFC00000) == 0x71000000 and ((w >> 10) & 0xFFF) == 0x835 and (w & 0x1F) == 31:
            cmp835 += 1
    print('MOVZ=%d MOVK=%d CMP#2101=%d' % (len(movz), len(movk), cmp835))

    # --- 1) 字面量池原始字节 ---
    print('\n== 字面量池原始字节 ==')
    for name, patt in RAW_BYTE_PATTS:
        hits, pos = [], 0
        while True:
            i = data.find(patt, pos)
            if i < 0:
                break
            hits.append(i)
            pos = i + 1
            if len(hits) >= 8:
                break
        if hits:
            vs = ', '.join('0x%x' % off2vaddr(segs, h) if off2vaddr(segs, h) is not None else '?%d' % h
                           for h in hits)
            print('  [HIT] %-24s ×%d @ %s' % (name, len(hits), vs))
        else:
            print('  [ - ] %s' % name)

    # --- 2) MOVZ+MOVK 拼 32 位常量（窗口 8 条指令） ---
    mz_by_rd = {}
    for off, rd, hw, imm in movz:
        mz_by_rd.setdefault(rd, []).append((off, hw, imm))

    def vstr(off):
        v = off2vaddr(segs, off)
        return '0x%x' % v if v is not None else '?%d' % off

    print('\n== MOVZ+MOVK 32 位常量命中 ==')
    any_hit = False
    mk_hw1 = [t for t in movk if t[2] == 1]
    for off_k, rd, hw, imm_hi in mk_hw1:
        for off_z, hwz, imm_lo in mz_by_rd.get(rd, []):
            if not (0 < off_k - off_z <= 32):   # 8 条指令
                continue
            if hwz != 0:
                continue
            const = (imm_hi << 16) | imm_lo
            for name, tv in TARGETS_U32:
                if const == tv:
                    any_hit = True
                    print('  [HIT] %-18s = 0x%08X  movz@%s + movk@%s (w%d/x%d)'
                          % (name, const, vstr(off_z), vstr(off_k), rd, rd))
            break   # 只配最近一条 movz
    if not any_hit:
        print('  [ - ] 无')

    # --- 3) MOVZ 单发 16 位嫌疑值 ---
    print('\n== MOVZ 单发嫌疑半字 ==')
    cnt = {}
    for off, rd, hw, imm in movz:
        if imm in SINGLES_U16:
            cnt.setdefault(imm, []).append((off, rd))
    for imm, name in sorted(SINGLES_U16.items()):
        if imm in cnt:
            lst = cnt[imm]
            vs = ', '.join(vstr(o) for o, _ in lst[:6])
            print('  [HIT] 0x%04X %-8s ×%d @ %s' % (imm, name, len(lst), vs))

    # --- 4) 全量拼合常量直方图（可选） ---
    if dump_all:
        hist = {}
        for off_k, rd, hw, imm_hi in mk_hw1:
            for off_z, hwz, imm_lo in mz_by_rd.get(rd, []):
                if 0 < off_k - off_z <= 32 and hwz == 0:
                    hist[(imm_hi << 16) | imm_lo] = hist.get((imm_hi << 16) | imm_lo, 0) + 1
                    break
        out = path + '.consts.txt'
        with open(out, 'w') as f:
            for c, n in sorted(hist.items(), key=lambda kv: -kv[1]):
                f.write('0x%08X %d\n' % (c, n))
        print('\n全量常量表 → %s（%d 个不同常量）' % (out, len(hist)))
    return 0


if __name__ == '__main__':
    so = sys.argv[1]
    scan(so, dump_all='--all' in sys.argv)
