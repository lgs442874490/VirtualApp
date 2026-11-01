package com.carlos.vault.protocapture;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 抓包帧切分纯函数（2 字节大端长度前缀）。 */
public class ProtoFrameAccumulatorTest {

    private static byte[] frame(byte[] payload) {
        byte[] f = new byte[payload.length + 2];
        f[0] = (byte) ((payload.length >> 8) & 0xFF);
        f[1] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, f, 2, payload.length);
        return f;
    }

    @Test
    public void singleFrameInOneChunk() {
        ProtoFrameAccumulator a = new ProtoFrameAccumulator();
        List<byte[]> out = a.feed(frame(new byte[]{1, 2, 3}), 0, 5);
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).length);
        assertEquals(3, out.get(0)[2]);
    }

    @Test
    public void partialFrameThenRest() {
        ProtoFrameAccumulator a = new ProtoFrameAccumulator();
        byte[] f = frame(new byte[]{9, 8, 7, 6});
        assertTrue(a.feed(f, 0, 2).isEmpty());
        assertTrue(a.feed(f, 2, 2).isEmpty());
        List<byte[]> out = a.feed(f, 4, f.length - 4);
        assertEquals(1, out.size());
        assertEquals(4, out.get(0).length);
    }

    @Test
    public void twoFramesSticky() {
        ProtoFrameAccumulator a = new ProtoFrameAccumulator();
        byte[] f1 = frame(new byte[]{1});
        byte[] f2 = frame(new byte[]{2, 2});
        byte[] both = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, both, 0, f1.length);
        System.arraycopy(f2, 0, both, f1.length, f2.length);
        List<byte[]> out = a.feed(both, 0, both.length);
        assertEquals(2, out.size());
        assertEquals(1, out.get(0).length);
        assertEquals(2, out.get(1).length);
    }

    @Test
    public void zeroLengthMarksBroken() {
        ProtoFrameAccumulator a = new ProtoFrameAccumulator();
        assertTrue(a.feed(new byte[]{0, 0}, 0, 2).isEmpty());
        assertTrue(a.broken());
        assertTrue("broken 后不再解析", a.feed(frame(new byte[]{1}), 0, 3).isEmpty());
    }

    @Test
    public void resetRecovers() {
        ProtoFrameAccumulator a = new ProtoFrameAccumulator();
        a.feed(new byte[]{0, 0}, 0, 2);
        assertTrue(a.broken());
        a.reset();
        assertFalse(a.broken());
        assertEquals(1, a.feed(frame(new byte[]{5}), 0, 3).size());
    }
}
