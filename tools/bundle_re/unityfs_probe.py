#!/usr/bin/env python3
"""UnityFS 探针：解析无尽冬日(com.gof.china)定制 bundle 的工具底座。

立项背景见 design/libEncryptor逆向立项-20261012.md。
本脚本是 2026-10-12 会话踩坑后固化的可用代码：
  - 头部解析（实测该游戏 bundle version=2101，非标准 6/7）
  - 纯 Python LZ4 块解码（pip 离线，无法装 UnityPy/lz4）
  - blocksInfo 三种解释逐一尝试（明文/LZ4/LZMA + 头部/尾部定位）
  - 数据块贪心解码（blocksInfo 加密未解时的兜底，可恢复大部分 lua 常量）
  - 关键字搜索（验证解密/解码是否成功）

用法：
  python3 unityfs_probe.py parse  <bundle 路径>          # 头部+blocksInfo 尝试
  python3 unityfs_probe.py greedy <bundle 路径> [输出]   # 贪心解码数据块
  python3 unityfs_probe.py search <解码后文件> <关键词>   # UTF-8 关键字计数
"""
import struct
import sys
import lzma

# ---- LZ4 块解码（纯 Python；LZ4HC 压缩数据与普通 LZ4 块格式相同） ----

def lz4_decompress(src, uncompressed_size):
    dst = bytearray()
    i, n = 0, len(src)
    while i < n:
        token = src[i]; i += 1
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[i]; i += 1
                lit += b
                if b != 255: break
        dst += src[i:i + lit]; i += lit
        if i >= n: break
        off = src[i] | (src[i + 1] << 8); i += 2
        ml = token & 0xF
        if ml == 15:
            while True:
                b = src[i]; i += 1
                ml += b
                if b != 255: break
        ml += 4
        start = len(dst) - off
        if start < 0:
            raise ValueError('bad match offset %d at dstlen %d' % (off, len(dst)))
        for j in range(ml):
            dst.append(dst[start + j])
    if len(dst) != uncompressed_size:
        raise ValueError('size mismatch got=%d want=%d' % (len(dst), uncompressed_size))
    return bytes(dst)


def lz4_greedy(src):
    """容错解码：非法匹配即停。返回 (输出, 消费字节数)。"""
    dst = bytearray()
    i, n = 0, len(src)
    while i < n:
        try:
            token = src[i]; i += 1
            lit = token >> 4
            if lit == 15:
                while True:
                    b = src[i]; i += 1
                    lit += b
                    if b != 255: break
            dst += src[i:i + lit]; i += lit
            if i >= n: break
            off = src[i] | (src[i + 1] << 8); i += 2
            ml = token & 0xF
            if ml == 15:
                while True:
                    b = src[i]; i += 1
                    ml += b
                    if b != 255: break
            ml += 4
            start = len(dst) - off
            if start < 0:
                return dst, i
            for j in range(ml):
                dst.append(dst[start + j])
        except IndexError:
            return dst, i
    return dst, i


# ---- UnityFS 头部 ----

class Header:
    def __init__(self, data):
        off = 9  # "UnityFS\0"
        self.version = struct.unpack('>I', data[off:off + 4])[0]; off += 4

        def rdstr(o):
            e = data.index(b'\0', o)
            return data[o:e].decode(), e + 1
        self.unity_ver, off = rdstr(off)
        self.unity_rev, off = rdstr(off)
        self.size = struct.unpack('>Q', data[off:off + 8])[0]; off += 8
        self.cinfo = struct.unpack('>I', data[off:off + 4])[0]; off += 4
        self.uinfo = struct.unpack('>I', data[off:off + 4])[0]; off += 4
        self.flags = struct.unpack('>I', data[off:off + 4])[0]; off += 4
        self.head_end = off
        self.comp_type = self.flags & 0x3F         # 0无 1LZMA 2LZ4 3LZ4HC
        self.info_at_end = bool(self.flags & 0x40)
        self.info_compressed = bool(self.flags & 0x80)  # 注意：实测 2101 包该位语义存疑，见立项文档

    def __str__(self):
        return ('version=%d unity=%s/%s size=%d cinfo=%d uinfo=%d flags=0x%03x '
                'comp=%d info_at_end=%s info_compressed=%s head_end=%d') % (
            self.version, self.unity_ver, self.unity_rev, self.size,
            self.cinfo, self.uinfo, self.flags, self.comp_type,
            self.info_at_end, self.info_compressed, self.head_end)


def try_blocks_info(data, h):
    """blocksInfo 三种解释逐一尝试，返回 (来源, bytes) 或 None。
    实测（2026-10-12，luapatch64 样本）：三种全失败 → blocksInfo 加密，见立项文档第 3 节。"""
    cands = []
    if h.info_at_end:
        cands.append(('end', data[h.size - h.cinfo:h.size]))
    cands.append(('head', data[h.head_end:h.head_end + h.cinfo]))
    for loc, raw in cands:
        for algo in ('raw', 'lz4', 'lzma'):
            try:
                if algo == 'raw':
                    out = raw
                elif algo == 'lz4':
                    out = lz4_decompress(raw, h.uinfo)
                else:
                    out = lzma.decompress(raw)
                bc = struct.unpack('>I', out[16:20])[0]
                if 0 < bc < 10000:   # 合理块数
                    return '%s/%s' % (loc, algo), out
                print('  [try %s/%s] 解出但 blockCount=%d 不合理' % (loc, algo, bc))
            except Exception as e:
                print('  [try %s/%s] %s' % (loc, algo, str(e)[:60]))
    return None


def parse(path):
    data = open(path, 'rb').read()
    h = Header(data)
    print('HEADER:', h)
    r = try_blocks_info(data, h)
    if r:
        src, info = r
        print('BLOCKSINFO: 来源=%s 长度=%d → bundle 未加密，可继续标准解析' % (src, len(info)))
        open(path + '.blocksinfo.bin', 'wb').write(info)
        return 0
    print('BLOCKSINFO: 三种解释全部失败 → 加密（大概率 libEncryptor.so 定制算法）')
    print('数据块从偏移 %d 开始（头尾各留 %d B 给 blocksInfo）。' % (h.head_end, h.cinfo))
    return 2


def greedy(path, out_path=None):
    data = open(path, 'rb').read()
    h = Header(data)
    data_start = h.head_end if h.info_at_end else h.head_end + h.cinfo
    data_end = h.size - h.cinfo if h.info_at_end else h.size
    out = bytearray()
    pos = data_start
    while pos < data_end:
        dst, consumed = lz4_greedy(data[pos:data_end])
        if consumed == 0:
            pos += 1   # 失配步进重同步
            continue
        out += dst
        pos += consumed
    out_path = out_path or (path + '.greedy.bin')
    open(out_path, 'wb').write(out)
    print('贪心解码 %d B → %s（%d B）' % (data_end - data_start, out_path, len(out)))
    return 0


def search(path, keyword):
    data = open(path, 'rb').read()
    kw = keyword.encode('utf-8') if not keyword.startswith('b') else eval(keyword)
    cnt = data.count(kw)
    idx = data.find(kw)
    print('%r → %s' % (keyword, ('%d 处，首现 @%d' % (cnt, idx)) if cnt else '无'))
    if cnt:
        ctx = data[max(0, idx - 60):idx + 120]
        import re
        print('上下文可读串:', [s.decode('utf-8', 'replace') for s in
                          re.findall(rb'[ -~]{4,60}', ctx)][:6])
    return 0


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'parse'
    if cmd == 'parse':
        sys.exit(parse(sys.argv[2]))
    if cmd == 'greedy':
        sys.exit(greedy(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None))
    if cmd == 'search':
        sys.exit(search(sys.argv[2], sys.argv[3]))
    print(__doc__)
    sys.exit(1)
