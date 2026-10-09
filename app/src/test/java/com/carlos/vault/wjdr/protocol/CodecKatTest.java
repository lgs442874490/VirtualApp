package com.carlos.vault.wjdr.protocol;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * TC-X01 / TC-X02 / TC-X03：帧 / XOR / RC4 编解码，与原版 wjdr 协议栈对齐。
 * RC4 期望值由独立 Python 实现（标准 KSA+PRGA）预计算（默认密钥 "4b5eea17"）。
 */
public class CodecKatTest {

    private static final byte[] KEY = "4b5eea17".getBytes(StandardCharsets.UTF_8);
    private static final String PT = "hello sproto frame 0123456789 abcdefghijklmnopqrstuvwxyz";
    private static final String RC4_CT_HEX =
            "91f8cfdf22222bd3a52f4032d5537dc94aec1178ee650053164cf45e187279b93da8ff2b58c9f2e6869f5efc471c17bd80169b298b1fcf18";
    private static final String RC4_PARTIAL10_HEX =
            "91f8cfdf22222bd3a52f746f206672616d652030313233343536373839206162636465666768696a6b6c6d6e6f707172737475767778797a";
    private static final String XOR_HEX_PREFIX =
            "5c0759090a414247460d410a4507435659071555545302030154025d5c415055570650030209585d";

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------- FrameCodec：2 字节大端长度前缀 ----------

    @Test
    public void frame_prependAndRead() {
        byte[] payload = {1, 2, 3};
        byte[] frame = FrameCodec.prependLength(payload);
        assertEquals(5, frame.length);
        assertEquals(0, frame[0]);
        assertEquals(3, frame[1]);
        assertEquals(3, FrameCodec.readLength(frame));
        assertEquals(0, FrameCodec.readLength(FrameCodec.prependLength(new byte[0])));
    }

    // ---------- XorCodec：循环 XOR，对称可逆 ----------

    @Test
    public void xor_knownAnswer() {
        byte[] out = XorCodec.apply(PT.getBytes(StandardCharsets.UTF_8), KEY);
        assertEquals(XOR_HEX_PREFIX, hex(out).substring(0, XOR_HEX_PREFIX.length()));
    }

    @Test
    public void xor_symmetric() {
        byte[] in = PT.getBytes(StandardCharsets.UTF_8);
        byte[] enc = XorCodec.apply(in, KEY);
        assertTrue(Arrays.equals(in, XorCodec.apply(enc, KEY)));
    }

    @Test
    public void xor_nullSafety() {
        assertNull(XorCodec.apply(null, KEY));
        byte[] in = {1, 2};
        assertTrue(Arrays.equals(in, XorCodec.apply(in, null)));
        assertTrue(Arrays.equals(in, XorCodec.apply(in, new byte[0])));
    }

    // ---------- Rc4StreamCodec：流式、部分加密、并发安全 ----------

    @Test
    public void rc4_knownAnswer_andRoundtrip() {
        Rc4StreamCodec enc = new Rc4StreamCodec(KEY);
        byte[] ct = enc.apply(PT.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
        assertEquals(RC4_CT_HEX, hex(ct));
        Rc4StreamCodec dec = new Rc4StreamCodec(KEY); // 新实例从头解密
        assertTrue(Arrays.equals(PT.getBytes(StandardCharsets.UTF_8), dec.apply(ct, Integer.MAX_VALUE)));
    }

    @Test
    public void rc4_streamingProperty() {
        // 行为基线（与原版 wjdr 反编译类逐字节一致）：apply 的 PRGA 游标 i/j 是方法内局部变量，
        // 每次调用从头扫起，但 S 盒状态跨调用演化——因此「同实例分段加密」≠「整段一次加密」，
        // 也不同于标准 RC4 流语义。真实会话中收发各持一个实例、按服务端同款节拍调用。
        // 本用例锁定该基线：若实现被改动（如改为真流式），与游戏服务端的互操作性需要重新验证。
        byte[] a = "0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] b = "ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        byte[] both = (new String(a) + new String(b)).getBytes(StandardCharsets.UTF_8);

        Rc4StreamCodec whole = new Rc4StreamCodec(KEY);
        byte[] expected = whole.apply(both, Integer.MAX_VALUE);

        Rc4StreamCodec split = new Rc4StreamCodec(KEY);
        byte[] ca = split.apply(a, Integer.MAX_VALUE);
        byte[] cb = split.apply(b, Integer.MAX_VALUE);

        // 第一段：两次调用都从游标 0 起步 ⇒ 前缀一致
        assertEquals(hex(expected, 10), hex(ca, 10));
        // 第二段：整段继续游标 10；分段调用游标重置 ⇒ 两者不同（基线快照）
        assertNotEquals(hex(expected, both.length), hex(concat(ca, cb), both.length));
        // S 盒演化证据：同实例对同样数据两次加密，密文不同（状态未重置）
        assertNotEquals(hex(ca, 10), hex(cb, 10));
    }

    private static byte[] concat(byte[] x, byte[] y) {
        byte[] r = new byte[x.length + y.length];
        System.arraycopy(x, 0, r, 0, x.length);
        System.arraycopy(y, 0, r, x.length, y.length);
        return r;
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < b.length; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    @Test
    public void rc4_partialCryptLength() {
        // cryptLength=10：只加密前 10 字节，其余原样（对齐原版 crypt_length 头字段语义）
        Rc4StreamCodec c = new Rc4StreamCodec(KEY);
        byte[] out = c.apply(PT.getBytes(StandardCharsets.UTF_8), 10);
        assertEquals(RC4_PARTIAL10_HEX, hex(out));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rc4_emptyKeyRejected() {
        new Rc4StreamCodec(new byte[0]);
    }

    @Test
    public void rc4_concurrentApplyIsThreadSafe() throws Exception {
        final Rc4StreamCodec c = new Rc4StreamCodec(KEY);
        final byte[] in = PT.getBytes(StandardCharsets.UTF_8);
        Thread[] threads = new Thread[4];
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    for (int k = 0; k < 200; k++) {
                        c.apply(in, in.length); // 仅验证不抛异常/不死锁（状态被锁保护）
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join(5000);
        assertEquals(0, errors.get());
    }
}
