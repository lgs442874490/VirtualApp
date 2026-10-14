import struct, sys
from arm64_mini import Sim, Halt

so = open('/tmp/libil2cpp_ext.so', 'rb').read()
d = open('luapatch64_current.unity3d', 'rb').read()
blk = bytearray(d[288:288+58293])

sim = Sim(so)
BUF = 0x10000000
sim.chunk(BUF, bytes(blk))
sim.chunk(0x3133000, so[0x3133000:0x31e73a0])

alloc_pool = [0x20000000]
def hook_malloc(sim):
    sim.x[0] = alloc_pool[0]
    sim.chunk(alloc_pool[0], b'\x00' * 65536)
    alloc_pool[0] += 0x10000
sim.hooks[0x31e0a94] = hook_malloc
sim.hooks[0x31e0b08] = lambda s: None
sim.hooks[0x31e6da0] = lambda s: s.x.__setitem__(0, 9)  # strlen -> 9 (odd -> tbnz bit0 takes main path)
def hook_strchr(sim):
    # 0x3165300: leaf strchr-ish，只破坏 w8/x8，返回匹配指针或 -1
    a = sim.x[0]
    sim.x[8] = a
    sim.x[0] = 0xFFFFFFFFFFFFFFFF
    for i in range(4096):
        c = sim.read(a + i, 1)
        if c == b'\x00':
            break
        if c == b'f':
            sim.x[0] = a + i
            break
sim.hooks[0x3165300] = hook_strchr
sim.hooks[0x31e6d50] = lambda s: None
sim.hooks[0x31e6d80] = lambda s: None   # memcpy-like
sim.hooks[0x31e6db0] = lambda s: None
sim.hooks[0x31e6dc0] = lambda s: None
sim.hooks[0x31c84c0] = lambda s: None

GLOB = 0x30000000
sim.chunk(GLOB, b'\x00' * 4096)
ALLOC1024 = 0x31000000
READFN   = 0x31000010
sim.write(GLOB + 0, struct.pack('<Q', GLOB + 0x100))
sim.write(GLOB + 0x100 + 352, struct.pack('<Q', ALLOC1024))
sim.write(GLOB + 0x100 + 384, struct.pack('<Q', READFN))
sim.write(GLOB + 0x100 + 392, struct.pack('<Q', READFN))
sim.write(0x31EE260, struct.pack('<Q', GLOB))

def hook_alloc1024(sim):
    a = alloc_pool[0]
    sim.chunk(a, b'\x00' * 4096)
    alloc_pool[0] += 0x10000
    sim.x[0] = a
def hook_read(sim):
    ln = sim.x[2]
    sim.write(sim.x[0], sim.read(sim.x[1], min(ln, 4096)))
sim.hooks[ALLOC1024] = hook_alloc1024
sim.hooks[READFN] = hook_read

sim.x[0] = BUF
sim.x[1] = 58293
END = 0xdead0000
sim.x[30] = END
try:
    sim.run(0x3165580, END)
    print('完成! steps=%d' % sim.steps)
except Halt as e:
    if e.pc in (0, 0xdead0000) or 'unmapped' in str(e.why):
        print('函数返回 (steps=%d)' % sim.steps)
    else:
        print('Halted @%#x: %s (steps=%d)' % (e.pc, e.why, sim.steps))
print('x20=%#x x21=%#x x22=%#x x23=%#x w24=%#x w25=%#x w19=%#x w0=%#x' % (
    sim.x[20], sim.x[21], sim.x[22], sim.x[23], sim.x[24] & 0xFFFFFFFF,
    sim.x[25] & 0xFFFFFFFF, sim.x[19] & 0xFFFFFFFF, sim.x[0] & 0xFFFFFFFF))
print('stack@[0x31ee608]:', sim.read(0x31ee608, 8).hex())
print('stack@[GLOB]:', sim.read(0x30000000, 8).hex())
print('stack@[GLOB+0x100+392]:', sim.read(0x30000288, 8).hex())
new = bytes(sim.read(BUF, 58293))
diff = [i for i in range(58293) if new[i] != blk[i]]
print('修改字节数: %d, 首: %s' % (len(diff), diff[:8]))
if diff:
    i = diff[0]
    print('首改 @%d:\n  原: %s\n  新: %s' % (i, blk[i:i+24].hex(), new[i:i+24].hex()))
    open('blk0_simdec.bin','wb').write(new)
