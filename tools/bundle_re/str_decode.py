#!/usr/bin/env python3
"""解码 libil2cpp.so 明文区的"栈上异或字符串"混淆。

模式：mov 常量入寄存器 → strb (base+i)^C 到栈缓冲 → 第二遍 strb b_i^(base+i)
     → 最终缓冲 = 常量串 C。两次异或互相抵消。
实现：线性扫描 objdump 文本，跟踪 w0-w30 的立即数（mov/movk），
     重放 add/eor/strb 序列，从栈偏移重建字符串。
"""
import re
import struct
import subprocess
import sys

START = int(sys.argv[1], 0) if len(sys.argv) > 1 else 0x3771000
END = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x3775000
SO = 'libil2cpp.so'

out = subprocess.run(['objdump', '-d', '--start-address=0x%x' % START,
                      '--stop-address=0x%x' % END, SO],
                     capture_output=True, text=True).stdout

regs = {}          # reg -> 32bit 值
stack = {}         # sp_off -> byte
strings = {}       # 首个栈偏移 -> [bytes]

mov_re = re.compile(r'(w\d+|wzr), #(0x[0-9a-f]+|\d+)$')
movk_re = re.compile(r'(w\d+), #(0x[0-9a-f]+|\d+), lsl #16$')
eor_re = re.compile(r'(w\d+), (w\d+), (w\d+)$')
eor_imm_re = re.compile(r'(w\d+), (w\d+), #(0x[0-9a-f]+|\d+)$')
add_re = re.compile(r'(w\d+), (w\d+), #(0x[0-9a-f]+|\d+)$')
strb_re = re.compile(r'w\d+, \[sp, #(0x[0-9a-f]+|\d+)\]')
str_re = re.compile(r'w\d+, \[sp, #(0x[0-9a-f]+|\d+)\]')
ldrb_re = re.compile(r'(w\d+), \[sp, #(0x[0-9a-f]+|\d+)\]')
ldp_re = re.compile(r'(w\d+), (w\d+), \[sp, #(0x[0-9a-f]+|\d+)\]')


def val(tok):
    return int(tok, 0)


for line in out.splitlines():
    m = re.match(r'\s*([0-9a-f]+):\s+((?:[0-9a-f]{2} ){3}[0-9a-f]{2})\s+(.*)$', line)
    if not m:
        continue
    addr, raw, ins = m.groups()
    ins = ins.split('//')[0].strip()
    parts = ins.split(None, 1)
    if not parts:
        continue
    op, rest = parts[0], parts[1] if len(parts) > 1 else ''

    if op == 'mov':
        mm = mov_re.match(rest)
        if mm and mm.group(1) != 'wzr':
            regs[mm.group(1)] = val(mm.group(2))
    elif op == 'movk':
        mm = movk_re.match(rest)
        if mm and mm.group(1) in regs:
            regs[mm.group(1)] = (regs[mm.group(1)] & 0xFFFF) | (val(mm.group(2)) << 16)
    elif op == 'mov' and rest.endswith('wzr') is False:
        pass
    elif op in ('movz',):
        mm = mov_re.match(rest)
        if mm:
            regs[mm.group(1)] = val(mm.group(2))
    elif op == 'add':
        mm = add_re.match(rest)
        if mm:
            src = regs.get(mm.group(2), 0)
            regs[mm.group(1)] = (src + val(mm.group(3))) & 0xFFFFFFFF
        else:
            mm2 = re.match(r'(w\d+), (w\d+), (w\d+)$', rest)
            if mm2 and mm2.group(2) in regs and mm2.group(3) in regs:
                regs[mm2.group(1)] = (regs[mm2.group(2)] + regs[mm2.group(3)]) & 0xFFFFFFFF
    elif op == 'eor':
        mm = eor_imm_re.match(rest)
        if mm:
            src = regs.get(mm.group(2), 0)
            regs[mm.group(1)] = (src ^ val(mm.group(3))) & 0xFFFFFFFF
        else:
            mm2 = eor_re.match(rest)
            if mm2 and mm2.group(2) in regs and mm2.group(3) in regs:
                regs[mm2.group(1)] = (regs[mm2.group(2)] ^ regs[mm2.group(3)]) & 0xFFFFFFFF
    elif op == 'strb':
        mm = strb_re.match(rest)
        if mm:
            wd = rest.split(',')[0].strip()
            off = int(mm.group(1), 0)
            if wd == 'wzr':
                stack[off] = 0
                # 快照：回溯连续可打印字节
                run = []
                o = off - 1
                while o in stack and 32 <= stack[o] < 127:
                    run.append(stack[o])
                    o -= 1
                if len(run) >= 4:
                    print('0x%s: %r' % (addr, bytes(reversed(run)).decode()))
            elif wd in regs:
                stack[off] = regs[wd] & 0xFF
    elif op == 'ldr':
        mm = re.match(r'(w\d+), \[sp, #(0x[0-9a-f]+|\d+)\]', rest)
        if mm:
            off = int(mm.group(2), 0)
            if off in stack:
                regs[mm.group(1)] = (stack[off] | (stack.get(off + 1, 0) << 8) |
                                     (stack.get(off + 2, 0) << 16) | (stack.get(off + 3, 0) << 24))
    elif op == 'ldrb':
        mm = ldrb_re.match(rest)
        if mm:
            off = int(mm.group(2), 0)
            if off in stack:
                regs[mm.group(1)] = stack[off]
    elif op == 'str':
        mm = str_re.match(rest)
        if mm:
            wd = rest.split(',')[0].strip()
            if wd in regs:
                off = int(mm.group(1), 0)
                stack[off] = regs[wd] & 0xFF
                stack[off + 1] = (regs[wd] >> 8) & 0xFF
                stack[off + 2] = (regs[wd] >> 16) & 0xFF
                stack[off + 3] = (regs[wd] >> 24) & 0xFF
    elif op == 'stp':
        mm = re.match(r'(w\d+), (w\d+), \[sp, #(0x[0-9a-f]+|\d+)\]', rest)
        if mm:
            for w, o in ((mm.group(1), 0), (mm.group(2), 4)):
                if w in regs:
                    off = int(mm.group(3), 0) + o
                    stack[off] = regs[w] & 0xFF
                    stack[off + 1] = (regs[w] >> 8) & 0xFF
                    stack[off + 2] = (regs[w] >> 16) & 0xFF
                    stack[off + 3] = (regs[w] >> 24) & 0xFF

# 从栈内容提取可打印串
if stack:
    offs = sorted(stack)
    run = []
    for o in offs:
        c = stack[o]
        if 32 <= c < 127:
            run.append((o, c))
        else:
            if len(run) >= 4:
                s = bytes(c for _, c in run).decode()
                print('0x%x: %r' % (run[0][0], s))
                strings[run[0][0]] = s
            run = []
    if len(run) >= 4:
        s = bytes(c for _, c in run).decode()
        print('0x%x: %r' % (run[0][0], s))
