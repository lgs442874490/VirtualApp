package com.carlos.vault.gameupdate;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M3 APK 校验器纯逻辑：size / versionCode 提升 / sha256（RFC 6070 风格已知向量）/
 * 签名比对 / 结果汇总。读包信息（PackageArchiveInfo）只在真机路径，不在 JVM 单测。
 */
public class GameApkVerifierTest {

    // ---------- size ----------

    @Test
    public void sizeMatches() {
        assertTrue(GameApkVerifier.sizeMatches(887587540L, 887587540L));
        assertFalse(GameApkVerifier.sizeMatches(887587539L, 887587540L));   // 差 1 字节也拒
        assertTrue(GameApkVerifier.sizeMatches(123L, 0L));                  // 预期未知 → 不拦
        assertTrue(GameApkVerifier.sizeMatches(123L, -1L));
    }

    // ---------- versionCode 提升（「版本未提升」也算失败） ----------

    @Test
    public void versionImproved() {
        assertTrue(GameApkVerifier.versionImproved(147, 146));
        assertFalse(GameApkVerifier.versionImproved(147, 147));   // 相同 → 拒（未提升）
        assertFalse(GameApkVerifier.versionImproved(146, 147));   // 回退 → 拒（防降级坏包）
        assertFalse(GameApkVerifier.versionImproved(0, 0));       // 非法新包
        assertFalse(GameApkVerifier.versionImproved(-1, 5));
        assertTrue(GameApkVerifier.versionImproved(147, -1));     // 容器版本读不到 → 只要求新包合法
    }

    // ---------- sha256 ----------

    @Test
    public void sha256_knownVector() throws Exception {
        File f = File.createTempFile("sha", ".bin");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("abc");
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GameApkVerifier.sha256Hex(f));
    }

    @Test
    public void sha256_largeFile_streamsCorrectly() throws Exception {
        File f = File.createTempFile("sha-big", ".bin");
        f.deleteOnExit();
        byte[] buf = new byte[64 * 1024 * 3 + 7];   // 跨多个 64KB 块 + 非对齐尾巴
        byte v = 1;
        for (int i = 0; i < buf.length; i++) {
            buf[i] = v++;
            if (v == 0) v = 1;
        }
        Files.write(f.toPath(), buf);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(buf);
        StringBuilder expect = new StringBuilder();
        for (byte b : md.digest()) {
            expect.append(Character.forDigit((b >> 4) & 0xF, 16));
            expect.append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(expect.toString(), GameApkVerifier.sha256Hex(f));
    }

    // ---------- 签名 ----------

    @Test
    public void signatureMatches_byteWise() {
        assertTrue(GameApkVerifier.signatureMatches(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}));
        assertFalse(GameApkVerifier.signatureMatches(new byte[]{1, 2}, new byte[]{1, 2, 3}));
        assertFalse(GameApkVerifier.signatureMatches(new byte[]{1, 2, 3}, null));
        assertFalse(GameApkVerifier.signatureMatches(null, new byte[]{1, 2, 3}));
        // 双方都读不到 → 一致（由调用方降级为「跳过签名校验」警告）
        assertTrue(GameApkVerifier.signatureMatches(null, null));
    }

    // ---------- 结果汇总 ----------

    @Test
    public void result_okAndSummary() {
        GameApkVerifier.Result ok = new GameApkVerifier.Result();
        assertTrue(ok.ok());
        assertEquals("", ok.summary());

        GameApkVerifier.Result bad = new GameApkVerifier.Result();
        bad.errors.add("安装包大小不符");
        bad.errors.add("版本未提升：新包 versionCode 146，容器内已是 146");
        bad.warnings.add("无法读取已装游戏签名，本次跳过签名一致性校验");
        assertFalse(bad.ok());
        String s = bad.summary();
        assertTrue(s, s.contains("· 安装包大小不符"));
        assertTrue(s, s.contains("· 版本未提升"));
        assertTrue(s, s.contains("⚠ 无法读取已装游戏签名"));
        assertTrue(s, s.contains("146"));   // 原文逐条保留
    }

    @Test
    public void errorTexts_areUserReadable() {
        assertTrue(GameApkVerifier.sizeError(100, 200).contains("100"));
        assertTrue(GameApkVerifier.pkgError("com.bad.game").contains("com.gof.china"));
        assertTrue(GameApkVerifier.versionError(146, 146).contains("146"));
        assertTrue(GameApkVerifier.shaError("aa", "bb").contains("sha256"));
        assertTrue(GameApkVerifier.sigMismatchError().contains("签名"));
        assertTrue(GameApkVerifier.sigUnavailableWarning().contains("跳过签名"));
    }

    // ---------- 字符串常量 ----------

    @Test
    public void gamePkg_locked() {
        assertEquals("com.gof.china", GameApkVerifier.GAME_PKG);
        assertArrayEquals(new byte[]{}, new byte[]{});
    }
}
