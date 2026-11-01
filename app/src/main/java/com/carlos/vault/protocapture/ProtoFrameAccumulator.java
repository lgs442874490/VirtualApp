package com.carlos.vault.protocapture;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TCP 流 → sproto 帧切分：帧格式=2 字节大端长度前缀 + payload（与游戏 FrameCodec 1:1）。
 * 纯函数/无 Android 依赖，单测覆盖粘包/半包/连续帧。
 */
public final class ProtoFrameAccumulator {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final int maxFrame;
    private boolean broken;

    public ProtoFrameAccumulator() {
        this(65535);
    }

    public ProtoFrameAccumulator(int maxFrame) {
        this.maxFrame = Math.max(1, Math.min(65535, maxFrame));
    }

    /** 喂入新字节，返回本次能完整解出的帧（payload，不含长度前缀）。 */
    public List<byte[]> feed(byte[] data, int off, int len) {
        List<byte[]> out = new ArrayList<>();
        if (data == null || len <= 0 || broken) return out;
        buf.write(data, off, len);
        byte[] all = buf.toByteArray();
        int pos = 0;
        while (all.length - pos >= 2) {
            int flen = ((all[pos] & 0xFF) << 8) | (all[pos + 1] & 0xFF);
            if (flen <= 0 || flen > maxFrame) {
                broken = true;
                buf.reset();
                return out;
            }
            if (all.length - pos - 2 < flen) break;
            byte[] frame = new byte[flen];
            System.arraycopy(all, pos + 2, frame, 0, flen);
            out.add(frame);
            pos += 2 + flen;
        }
        buf.reset();
        if (pos < all.length) buf.write(all, pos, all.length - pos);
        return out;
    }

    public boolean broken() {
        return broken;
    }

    public void reset() {
        buf.reset();
        broken = false;
    }
}
