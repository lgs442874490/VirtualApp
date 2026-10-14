#!/usr/bin/env python3
"""迷你 ARM64 模拟器 v2 — 追踪 libil2cpp.so 解密函数的精确寄存器流。"""
import struct

M64 = (1 << 64) - 1
M32 = (1 << 32) - 1


class Halt(Exception):
    def __init__(self, pc, why):
        self.pc = pc
        self.why = why


class Sim:
    def __init__(self, so):
        self.so = so
        self.x = [0] * 32
        self.q = {}           # SIMD 寄存器（bytes16）
        self.sp = 0x7000000
        self.stack = {}
        self.chunks = {}      # base -> bytearray
        self.hooks = {}       # addr -> fn(sim)
        self.nf = self.zf = self.cf = self.vf = False
        self.steps = 0

    def chunk(self, addr, data):
        self.chunks[addr] = bytearray(data)

    def _base(self, addr):
        for base, buf in self.chunks.items():
            if base <= addr < base + len(buf):
                return base, buf
        if 0x5000000 <= addr < 0x7800000:
            return None, None  # 栈区（稀疏）
        raise Halt(addr, 'unmapped %#x' % addr)

    def read(self, addr, n):
        try:
            base, buf = self._base(addr)
            if buf is not None:
                return bytes(buf[addr - base:addr - base + n])
        except Halt:
            pass
        out = bytearray()
        for i in range(n):
            out += self.stack.get(addr + i, b'\x00')
        return bytes(out)

    def write(self, addr, data):
        try:
            base, buf = self._base(addr)
            if buf is not None:
                buf[addr - base:addr - base + len(data)] = data
                return
        except Halt:
            pass
        for i, b in enumerate(data):
            self.stack[addr + i] = bytes([b])

    def run(self, start, end, max_steps=20_000_000):
        pc = start
        while pc != end:
            self.steps += 1
            if self.steps > max_steps:
                raise Halt(pc, 'step limit')
            ins = struct.unpack_from('<I', self.so, pc)[0]
            pc = self.step(pc, ins, end)
        return

    # ---- 标志 ----
    def set_flags_sub(self, a, b, mask):
        res = (a - b) & mask
        self.cf = a >= b
        self.zf = res == 0
        sb = 1 << (63 if mask == M64 else 31)
        self.nf = bool(res & sb)
        self.vf = ((a ^ b) & sb) and ((res ^ a) & sb)
        return res

    def set_flags_add(self, a, b, mask):
        res = (a + b) & mask
        self.cf = res < a
        self.zf = res == 0
        sb = 1 << (63 if mask == M64 else 31)
        self.nf = bool(res & sb)
        self.vf = (not ((a ^ b) & sb)) and ((res ^ a) & sb)
        return res

    def cond(self, c):
        n, z, cb, v = self.nf, self.zf, self.cf, self.vf
        return [z, not z, cb, not cb, n, not n, v, not v,
                cb and not z, not cb or z, n == v, n != v,
                not z and n == v, z or n != v, True, False][c]

    @staticmethod
    def _dec_bitm(N, immr, imms, sf):
        """decode logical immediate (ARM decode_bitmasks)"""
        imm = (N << 6) | ((~imms) & 0x3F)
        if imm == 0:
            return 0
        length = imm.bit_length() - 1  # HighestSetBit
        esize = 1 << length
        levels = (1 << length) - 1
        S = imms & levels
        R = immr & levels
        welem = (1 << (S + 1)) - 1
        R %= esize
        w = ((welem >> R) | (welem << (esize - R))) & ((1 << esize) - 1)
        res = 0
        for i in range(64 // esize):
            res |= w << (i * esize)
        return res & M64

    def step(self, pc, ins, end):
        x = self.x
        sf = (ins >> 31) & 1
        mask = M64 if sf else M32

        rd = ins & 0x1F
        rn = (ins >> 5) & 0x1F
        # B / BL
        if (ins >> 26) in (0x05, 0x25):
            imm = ins & 0x3FFFFFF
            if imm & (1 << 25):
                imm -= 1 << 26
            if (ins >> 31) & 1:  # BL
                x[30] = pc + 4
                t = pc + (imm << 2)
                if t in self.hooks:
                    self.hooks[t](self)
                    return pc + 4
                return t
            return pc + (imm << 2)
        # B.cond
        if (ins & 0xFF000010) == 0x54000000:
            imm = (ins >> 5) & 0x7FFFF
            if imm & (1 << 18):
                imm -= 1 << 19
            if self.cond(ins & 0xF):
                return pc + (imm << 2)
            return pc + 4
        # CBZ/CBNZ
        if (ins & 0x7E000000) == 0x34000000:
            rt = ins & 0x1F
            val = x[rt] & mask
            imm = (ins >> 5) & 0x7FFFF
            if imm & (1 << 18):
                imm -= 1 << 19
            if (val == 0) != bool((ins >> 24) & 1):
                return pc + (imm << 2)
            return pc + 4
        # TBZ/TBNZ
        if (ins & 0x7E000000) == 0x36000000:
            bit = ((ins >> 31) & 1) << 5 | ((ins >> 19) & 0x1F)
            val = (x[ins & 0x1F] >> bit) & 1
            imm = (ins >> 5) & 0x3FFF
            if imm & (1 << 13):
                imm -= 1 << 14
            if val == bool((ins >> 24) & 1):  # TBNZ: bit==1 jump; TBZ: bit==0 jump
                return pc + (imm << 2)
            return pc + 4
        # RET / BR / BLR
        if ins == 0xD65F03C0:
            return x[30]
        if (ins & 0xFFFFFC1F) == 0xD61F0000:
            return x[(ins >> 5) & 0x1F]
        if (ins & 0xFFFFFC1F) == 0xD63F0000:
            t = x[(ins >> 5) & 0x1F]
            x[30] = pc + 4
            if t in self.hooks:
                self.hooks[t](self)
                return pc + 4
            raise Halt(pc, 'blr %#x no hook' % t)

        # ADD/SUB imm (12-bit)
        if (ins & 0x1F000000) in (0x11000000, 0x31000000, 0x51000000, 0x71000000,
                                   0x91000000, 0xB1000000, 0xD1000000, 0xF1000000):
            imm12 = (ins >> 10) & 0xFFF
            sh = ((ins >> 22) & 3) == 1
            b = (imm12 << 12) if sh else imm12
            a = self.sp if rn == 31 else x[rn]
            a &= mask
            sub = (ins >> 30) & 1
            setf = (ins >> 29) & 1
            res = (a - b) & mask if sub else (a + b) & mask
            if setf:
                if sub:
                    self.set_flags_sub(a, b, mask)
                else:
                    self.set_flags_add(a, b, mask)
            if rd == 31:
                if not setf:  # SUBS/ADDS 的 rd=31 是 xzr（丢弃），仅 MOV sp 写回
                    self.sp = res & M64
            else:
                x[rd] = res & (M64 if sf else M32)
            return pc + 4

        # ADD/SUB/CMP shifted/extended register
        if (ins >> 24) & 0x1F in (0x0B, 0x2B, 0x4B, 0x6B, 0x8B, 0xAB, 0xCB, 0xEB):
            rm = (ins >> 16) & 0x1F
            imm6 = (ins >> 10) & 0x3F
            sub = (ins >> 30) & 1
            setf = (ins >> 29) & 1
            a = x[rn] & mask
            if (ins >> 21) & 1:  # extended register
                et = (ins >> 13) & 7
                sh = (ins >> 10) & 7
                v = x[rm]
                if et in (0, 4): v &= 0xFF          # UXTB/SXTB
                elif et in (1, 5): v &= 0xFFFF      # UXTH/SXTH
                elif et in (2, 6): v &= M32         # UXTW/SXTW
                b = (v << sh) & mask
            else:
                b = (x[rm] << imm6) & mask
            res = (a - b) & mask if sub else (a + b) & mask
            if setf:
                if sub:
                    self.set_flags_sub(a, b, mask)
                else:
                    self.set_flags_add(a, b, mask)
            if rd != 31:
                x[rd] = res
            return pc + 4

        # logical shifted register: AND/BIC/ORR/ORN/EOR/EON/ANDS
        if (ins & 0x1F000000) == 0x0A000000:
            rm = (ins >> 16) & 0x1F
            imm6 = (ins >> 10) & 0x3F
            N = (ins >> 21) & 1
            opc = (ins >> 29) & 3
            v = x[rm] & mask
            shift_type = (ins >> 22) & 3
            bw = 64 if sf else 32
            if shift_type == 0:  # LSL
                v = (v << imm6) & mask
            elif shift_type == 1:  # LSR
                v = v >> imm6
            elif shift_type == 2:  # ASR
                sign = (v >> (bw - 1)) & 1
                v = v >> imm6
                if sign and imm6:
                    v |= (((1 << bw) - 1) << (bw - imm6)) & mask
            else:  # ROR
                v = ((v >> (imm6 % bw)) | (v << ((bw - imm6) % bw))) & mask
            if N:
                v = (~v) & mask
            a = x[rn] & mask
            if opc == 0:
                res = a & v
                if (ins >> 29) & 1:  # ANDS
                    self.zf = res == 0
                    self.nf = bool(res & (1 << (63 if sf else 31)))
            elif opc == 1:
                res = a | v
            elif opc == 2:
                res = a ^ v
            else:
                res = a & v
            if rd != 31:
                x[rd] = res
            return pc + 4

        # MOV wide: MOVZ/MOVK/MOVN
        if (ins & 0x1F800000) == 0x12800000:
            rd = ins & 0x1F
            imm16 = (ins >> 5) & 0xFFFF
            hw = (ins >> 21) & 3
            opc = (ins >> 29) & 3
            v = imm16 << (16 * hw)
            if opc == 0:  # MOVN
                v = (~v) & mask
            elif opc == 3:  # MOVK
                cur = x[rd]
                v = (cur & ~(0xFFFF << (16 * hw))) | v
            x[rd] = v & mask
            return pc + 4
        # ORR imm (MOV alias) & ANDS imm
        if (ins & 0x1F800000) == 0x12000000 or (ins & 0x1F800000) == 0x72000000:
            imms = (ins >> 10) & 0x3F
            immr = (ins >> 16) & 0x3F
            N = (ins >> 22) & 1
            val = self._dec_bitm(N, immr, imms, sf)
            a = x[rn] & mask
            opc = (ins >> 29) & 3
            if opc == 1:  # ORR
                res = a | val
            elif opc == 0:  # AND（ANDS 单独）
                res = a & val
            elif opc == 2:  # EOR
                res = a ^ val
            else:  # ANDS/BIC 变体
                res = a & val
                self.zf = res == 0
                self.nf = bool(res & (1 << (63 if sf else 31)))
            if rd != 31:
                x[rd] = res & mask
            return pc + 4

        # bitfield: SBFM/BFM/UBFM (LSL/LSR/ASR/UBFX/SBFX/BFI/BFXIL)
        if (ins & 0x1F800000) == 0x13000000:
            immr = (ins >> 16) & 0x3F
            imms = (ins >> 10) & 0x3F
            opc = (ins >> 29) & 3
            bits = 64 if sf else 32
            src = x[rn] & mask
            if opc == 2:  # UBFM
                if imms + 1 == bits:  # LSR
                    res = src >> immr
                elif imms < immr:  # LSL (immr = bits - shift)
                    sh = (bits - immr) % bits
                    res = (src << sh) & mask
                else:  # UBFX / LSR part
                    width = imms - immr + 1
                    res = (src >> immr) & ((1 << width) - 1)
            elif opc == 0:  # SBFM
                if imms + 1 == bits:
                    sh = immr
                    sign = (src >> (bits - 1)) & 1
                    res = src >> sh
                    if sign:
                        res |= ((1 << bits) - 1) << (bits - sh) if sh else 0
                    res &= mask
                elif imms < immr:  # ASR/SGN 提取
                    width = imms + 1
                    sh = (bits - immr) % bits
                    v = (src << sh) & mask
                    sign = (v >> (width - 1)) & 1
                    res = v & ((1 << width) - 1)
                    if sign:
                        res |= ~((1 << width) - 1) & mask
                else:  # SBFX / SXTW
                    width = imms - immr + 1
                    v = (src >> immr) & ((1 << width) - 1)
                    sign = (v >> (width - 1)) & 1
                    res = v
                    if sign:
                        res |= ~((1 << width) - 1) & mask
            else:  # BFM (BFI/BFXIL)
                dst = x[rd]
                if imms >= immr:
                    width = imms - immr + 1
                    ins_v = (src >> immr) if False else src  # BFI: rn 低 width 位
                    maskv = ((1 << width) - 1) << immr
                    res = (dst & ~maskv) | ((src & ((1 << width) - 1)) << immr)
                else:
                    width = imms + 1
                    lsb = (bits - immr) & (bits - 1)  # BFXIL: lsb = bits - immr
                    ins_v = (src >> lsb) & ((1 << width) - 1)
                    maskv = (1 << width) - 1
                    res = (dst & ~maskv) | ins_v
                res &= mask
            x[rd] = res & mask
            return pc + 4

        # CSEL/CSINC/CSINV/CSNEG
        if (ins & 0x1FE00800) in (0x0A800000, 0x1A800000):
            rm = (ins >> 16) & 0x1F
            cond = (ins >> 12) & 0xF
            opc = (ins >> 29) & 3
            op2 = (ins >> 10) & 3
            if self.cond(cond):
                res = x[rn] & mask
            else:
                res = x[rm] & mask
                if op2 == 1:
                    res = (res + 1) & mask if opc != 3 else (-res) & mask
                elif op2 == 2:
                    res = (~res) & mask
            if rd != 31:
                x[rd] = res
            return pc + 4
        # CCMP/CCMN (imm or register)
        if (ins & 0x7F800000) == 0x7A000000:
            if not ((ins >> 10) & 1):  # bit10=0 register form
                b = x[(ins >> 16) & 0x1F] & mask
            else:
                b = (ins >> 16) & 0x1F
            cond = (ins >> 12) & 0xF
            nzcv = ins & 0xF
            if self.cond(cond):
                a = x[rn] & mask
                if (ins >> 21) & 1:  # CCMN
                    self.set_flags_add(a, b, mask)
                else:
                    self.set_flags_sub(a, b, mask)
            else:
                self.nf = bool(nzcv & 8)
                self.zf = bool(nzcv & 4)
                self.cf = bool(nzcv & 2)
                self.vf = bool(nzcv & 1)
            return pc + 4
        # REV
        if (ins & 0x7FFFF000) == 0x5AC00000:
            v = x[rn]
            if sf:
                res = int.from_bytes(struct.pack('<Q', v), 'big')
            else:
                res = int.from_bytes(struct.pack('<I', v & M32), 'big')
            x[rd] = res
            return pc + 4
        # CLZ
        if (ins & 0x7FFFF000) == 0x5AC10000:
            v = x[rn] & mask
            x[rd] = mask + 1 - v.bit_length() if v else (64 if sf else 32)
            return pc + 4
        # MADD/MSUB
        if (ins & 0x1F000000) == 0x1B000000 and (ins >> 21) & 7 in (0, 1):
            rm = (ins >> 16) & 0x1F
            ra = (ins >> 10) & 0x1F
            a = x[rn] & M32
            b = x[rm] & M32
            c = x[ra] & mask
            if (ins >> 21) & 1:
                res = (c - a * b) & mask
            else:
                res = (c + a * b) & mask
            x[rd] = res
            return pc + 4
        # UMULH/SMULH（Ra=11111）；同掩码下 Ra≠11111 是 MADD 族别名（UMULL/SMULL = 低 64 位积）
        if (ins & 0x7F200000) == 0x1B200000 and (ins >> 21) & 1:
            rm = (ins >> 16) & 0x1F
            ra = (ins >> 10) & 0x1F
            a = x[rn]; b = x[rm]
            if ra == 31:  # UMULH/SMULH：高 64 位
                res = (a * b) >> 64
            else:  # UMULL/SMULL：低 64 位
                res = (a * b) & M64
            x[rd] = res & M64
            return pc + 4
        # UMULL/SMULL (31=1 opc=00/01, bit21=1)
        if (ins & 0x1F000000) == 0x1B000000 and ((ins >> 27) & 1) and (ins >> 21) & 0x3F == 0x1F:
            rm = (ins >> 16) & 0x1F
            a = x[rn] & M32
            b = x[rm] & M32
            x[rd] = (a * b) & M64
            return pc + 4
        # UDIV/SDIV
        if (ins & 0x7F000000) == 0x1AC00000:
            rm = (ins >> 16) & 0x1F
            a = x[rn] & mask
            b = x[rm] & mask
            if b == 0:
                res = 0
            elif (ins >> 21) & 1:  # SDIV
                sa = a - (mask + 1) if a >> (63 if sf else 31) else a
                sb = b - (mask + 1) if b >> (63 if sf else 31) else b
                res = int(abs(sa) // abs(sb)) * (1 if (sa >= 0) == (sb >= 0) else -1)
                res &= mask
            else:
                res = a // b
            x[rd] = res & mask
            return pc + 4
        # LSLV/LSRV/ASRV/RORV
        if (ins & 0x7F200000) == 0x1AC20000:
            rm = (ins >> 16) & 0x1F
            opc = (ins >> 29) & 3
            amt = x[rm] & (63 if sf else 31)
            v = x[rn] & mask
            if opc == 0:
                res = (v << amt) & mask
            elif opc == 1:
                res = v >> amt
            elif opc == 2:
                sign = (v >> (63 if sf else 31)) & 1
                res = v >> amt
                if sign and amt:
                    res |= ((mask << ((64 if sf else 32) - amt)) & mask)
            else:
                bw = 64 if sf else 32
                res = ((v >> (amt & (bw - 1))) | (v << ((bw - amt) & (bw - 1)))) & mask
            x[rd] = res & mask
            return pc + 4
        # ADRP/ADR
        if (ins & 0x1F000000) in (0x10000000, 0x90000000):
            immlo = (ins >> 29) & 3
            immhi = (ins >> 5) & 0x7FFFF
            imm = (immhi << 2) | immlo
            if imm & (1 << 20):
                imm -= 1 << 21
            if (ins >> 31) & 1:
                x[rd] = (((pc >> 12) + imm) << 12) & M64
            else:
                x[rd] = (pc + imm) & M64
            return pc + 4
        # SIMD: MOVI / EOR vector
        if (ins & 0xFF800000) in (0x0F000000, 0x4F000000):
            # MOVI 8-bit shifted family (movi v.16B/#8B, movi v.2D #0 简化)
            rd = ins & 0x1F
            immh = (ins >> 16) & 0xF
            abc = (ins >> 16) & 7
            cmode = (ins >> 12) & 0xF
            defg = (ins >> 5) & 0x1F
            h = (ins >> 11) & 1
            op = (ins >> 29) & 1
            if op == 0 and cmode in (0, 8, 0xE, 0xE ^ 2) or True:
                # 8-bit: imm8 = abc:defgh (cmode 0) ；cmode 0xE/... 处理常用 166/89
                imm8 = (abc << 5) | defg
                if cmode in (0, 0xE):  # 8-bit fill / 32-bit fill (shift=0)
                    n = 16 if (ins >> 30) & 1 else 8
                    self.q[rd] = bytes([imm8]) * n
                elif cmode in (0xC, 0xD):
                    sh = h << 3
                    v = imm8 << sh
                    word = v.to_bytes(4, 'little')
                    self.q[rd] = word * 4
            return pc + 4
        if (ins >> 24) & 0xFF in (0x4E, 0x6E) and ((ins >> 21) & 7) in (1, 3) and ((ins >> 24) & 0x3) == 2:
            # EOR (vector): 3-source same
            rd = ins & 0x1F
            rn2 = (ins >> 5) & 0x1F
            rm2 = (ins >> 16) & 0x1F
            n = 16 if (ins >> 30) & 1 else 8
            b = self.q.get(rn2, bytes(16))
            c = self.q.get(rm2, bytes(16))
            self.q[rd] = bytes(p ^ q_ for p, q_ in zip(b[:n], c[:n])) + b[n:]
            return pc + 4
        # MRS / NOP / DMB
        if (ins & 0xFFF00000) == 0xD5300000 or ins in (0xD503201F, 0xD503201F):
            if (ins >> 20) == 0xD53:
                x[rd] = 0
            return pc + 4
        # LDR/STR unsigned imm (32/64-bit GPR)
        if (ins & 0x3F000000) == 0x39000000:
            size = (ins >> 30) & 3
            rt = ins & 0x1F
            imm12 = (ins >> 10) & 0xFFF
            off = imm12 << size
            base = self.sp if rn == 31 else x[rn]
            addr = (base + off) & M64
            n = 1 << size
            if (ins >> 22) & 3 == 1:
                x[rt] = int.from_bytes(self.read(addr, n), 'little')
            elif (ins >> 22) & 3 == 0:
                self.write(addr, (x[rt] & ((1 << (8 * n)) - 1)).to_bytes(n, 'little'))
            else:
                raise Halt(pc, 'ldrs')
            return pc + 4
        # LDRB/STRB/LDRH/STRH imm9 (unscaled) + LDUR/STUR + register offset
        if (ins & 0x3F000000) == 0x38000000:
            size = (ins >> 30) & 3
            rt = ins & 0x1F
            if ((ins >> 10) & 3) == 2:  # register offset
                rm = (ins >> 16) & 0x1F
                opt = (ins >> 13) & 7
                sh = ((ins >> 12) & 1) * size  # S 位：shift = size 字段（byte<<0/half<<1/word<<2/dword<<3）
                if opt in (2, 6):  # UXTW/SXTW (32-bit source)
                    off = (x[rm] & M32) << sh
                else:  # LSL/SXTX (64-bit)
                    off = (x[rm] << sh) & M64
                base = self.sp if rn == 31 else x[rn]
                addr = (base + off) & M64
            else:
                imm9 = (ins >> 12) & 0x1FF
                if imm9 & 0x100:
                    imm9 -= 0x200
                base = self.sp if rn == 31 else x[rn]
                addr = (base + imm9) & M64
                mode = (ins >> 10) & 3
                if mode in (1, 3) and rn != 31:  # post/pre-index writeback
                    x[rn] = (x[rn] + imm9) & M64
            n = 1 << size
            if (ins >> 22) & 3 in (1, 3):
                x[rt] = int.from_bytes(self.read(addr, n), 'little')
            else:
                self.write(addr, (x[rt] & ((1 << (8 * n)) - 1)).to_bytes(n, 'little'))
            return pc + 4
        # LDR/STR SIMD imm (s/d/q 全 size)
        if (ins & 0x3F000000) == 0x3D000000 and (ins >> 26) & 1:
            size = (ins >> 30) & 3
            rt = ins & 0x1F
            rn2 = (ins >> 5) & 0x1F
            opc = (ins >> 22) & 3
            simd_base = self.sp if rn2 == 31 else x[rn2]
            if opc == 1:  # unsigned imm LDR
                imm12 = (ins >> 10) & 0xFFF
                addr = (simd_base + (imm12 << size)) & M64
                n = 16 if size == 0 else (1 << size)
                self.q[rt] = self.read(addr, n)
                return pc + 4
            if opc == 0:  # unsigned imm STR
                imm12 = (ins >> 10) & 0xFFF
                addr = (simd_base + (imm12 << size)) & M64
                n = 16 if size == 0 else (1 << size)
                self.write(addr, self.q.get(rt, b'\x00' * 16)[:n])
                return pc + 4
        if (ins & 0x3FC00000) == 0x3D800000:
            size = (ins >> 30) & 3
            rt = ins & 0x1F
            opc = (ins >> 22) & 3
            if opc == 1:  # unsigned imm
                imm12 = (ins >> 10) & 0xFFF
                addr = (x[rn] + (imm12 << size)) & M64
                n = 1 << size if size else 16
                if size == 0:
                    n = 16
                if (ins >> 22) & 3 == 1:
                    self.q[rt] = self.read(addr, n)
                else:
                    self.write(addr, self.q.get(rt, b'\x00' * n)[:n])
                return pc + 4
        if (ins & 0xBFFC0000) == 0x3DC00000:  # LDR q, [xn, #imm]
            rt = ins & 0x1F
            imm = ((ins >> 10) & 0xFFF) * 16
            self.q[rt] = self.read((x[rn] + imm) & M64, 16)
            return pc + 4
        # LDP/STP
        if (ins >> 25) & 0x7F in (0x14, 0x15, 0x34, 0x35, 0x54, 0x55, 0x16, 0x56, 0x36, 0x57, 0x2E, 0x2F):
            opc = (ins >> 30) & 3
            ld = (ins >> 22) & 1
            mode = (ins >> 23) & 3
            rt = ins & 0x1F
            rn2 = (ins >> 5) & 0x1F
            rt2 = (ins >> 10) & 0x1F
            simm = (ins >> 15) & 0x7F
            if simm & 0x40:
                simm -= 0x80
            simd = (ins >> 26) & 1
            if simd:
                size = 16
            else:
                size = 4 << (opc == 2)
                if opc == 2:
                    size = 8
                else:
                    size = 4 if opc == 0 else (8 if opc == 2 else 4)
            if (ins >> 31) & 1 and not simd:
                size = 8
            off = simm * size
            if mode == 3:  # pre-index
                base = (self.sp if rn2 == 31 else x[rn2]) + off
                if rn2 == 31:
                    self.sp = base
                else:
                    x[rn2] = base
                a1, a2 = base, base + size
            else:
                base = self.sp if rn2 == 31 else x[rn2]
                if mode == 1:  # post-index
                    a1, a2 = base, base + size
                    if rn2 == 31:
                        self.sp = base + 2 * size
                    else:
                        x[rn2] = base + 2 * size
                else:  # signed offset
                    a1, a2 = base + off, base + off + size
            if ld:
                if simd:
                    self.q[rt] = self.read(a1, size)
                    self.q[rt2] = self.read(a2, size)
                else:
                    x[rt] = int.from_bytes(self.read(a1, size), 'little')
                    x[rt2] = int.from_bytes(self.read(a2, size), 'little')
            else:
                if simd:
                    self.write(a1, self.q.get(rt, b'\x00' * 16)[:size])
                    self.write(a2, self.q.get(rt2, b'\x00' * 16)[:size])
                else:
                    self.write(a1, (x[rt] & ((1 << (8 * size)) - 1)).to_bytes(size, 'little'))
                    self.write(a2, (x[rt2] & ((1 << (8 * size)) - 1)).to_bytes(size, 'little'))
            return pc + 4
        raise Halt(pc, 'unhandled %#x' % ins)
