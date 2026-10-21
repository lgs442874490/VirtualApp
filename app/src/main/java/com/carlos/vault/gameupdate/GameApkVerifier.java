package com.carlos.vault.gameupdate;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 游戏 APK 校验器（M3）：下载完成 → 安装之前，把能挡的都挡住。
 *
 * <p>校验项（契约 §4.2）：size、包名（com.gof.china）、versionCode 必须高于容器内已装版
 * （「版本未提升」也算失败）、可选 sha256、签名与容器内已装包一致。
 * 签名读取依赖容器/系统 PackageManager，<b>读不到时降级为警告</b>（不阻断安装，
 * 弹窗提示），其余校验失败为硬错误。
 *
 * <p>判定与文案的纯函数部分（size/version/摘要计算/结果汇总）JVM 可单测；
 * 读包信息（{@link PackageManager#getPackageArchiveInfo}）只在真机路径走。
 */
public final class GameApkVerifier {

    public static final String GAME_PKG = "com.gof.china";

    /** 校验结果：errors 非空 = 拒绝安装；warnings 只提示不拦截。 */
    public static class Result {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() {
            return errors.isEmpty();
        }

        /** 弹窗/诊断用的一句话汇总（原文逐条保留）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < errors.size(); i++) {
                sb.append(i == 0 ? "" : "\n").append("· ").append(errors.get(i));
            }
            for (String w : warnings) {
                sb.append(sb.length() == 0 ? "" : "\n").append("⚠ ").append(w);
            }
            return sb.toString();
        }
    }

    private GameApkVerifier() {
    }

    // ---------- 纯函数（JVM 单测） ----------

    /** 预期大小 &lt;=0 视为未知 → 不拦；否则必须精确一致。 */
    public static boolean sizeMatches(long actualBytes, long expectedBytes) {
        return expectedBytes <= 0 || actualBytes == expectedBytes;
    }

    /**
     * versionCode 必须严格高于容器内已装版才允许覆盖安装；
     * 容器内版本读不到（&lt;=0）时只要求新包 versionCode 合法（&gt;0）。
     */
    public static boolean versionImproved(long newVersionCode, long installedVersionCode) {
        if (newVersionCode <= 0) return false;
        return installedVersionCode <= 0 || newVersionCode > installedVersionCode;
    }

    /** 流式 sha256（小写 hex）；文件不存在/读取失败抛 IOException。JVM 可测（RFC 6234 向量）。 */
    public static String sha256Hex(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 不可用", e);
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static String sizeError(long actualBytes, long expectedBytes) {
        return "安装包大小不符：实际 " + GameApkDownloader.formatBytes(actualBytes)
                + "，预期 " + GameApkDownloader.formatBytes(expectedBytes) + "，可能下载不完整";
    }

    public static String pkgError(String apkPackage) {
        return "安装包不是「无尽冬日」：" + apkPackage + " ≠ " + GAME_PKG;
    }

    public static String versionError(long newVersionCode, long installedVersionCode) {
        return "版本未提升：新包 versionCode " + newVersionCode
                + (installedVersionCode > 0 ? "，容器内已是 " + installedVersionCode : "");
    }

    public static String shaError(String actualHex, String expectedHex) {
        return "安装包内容校验不符（sha256 不一致），请勿使用来源不明的包";
    }

    public static String sigMismatchError() {
        return "签名与容器内已装游戏不一致，可能不是官方包，已拒绝安装";
    }

    public static String sigUnavailableWarning() {
        return "无法读取已装游戏签名，本次跳过签名一致性校验";
    }

    /** 签名逐字节一致（null 与 null 视为一致——都读不到由调用方降级为警告）。 */
    public static boolean signatureMatches(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    // ---------- 真机校验入口 ----------

    /**
     * 全量校验。installedSignature 传 {@code VaultEngine.installedGameSignature()}
     * （可为 null → 签名项降级为警告）。
     */
    public static Result verify(Context ctx, File apk, GameClientUpdateConfig cfg,
                                long installedVersionCode, byte[] installedSignature) {
        Result r = new Result();
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            r.errors.add("安装包不存在或为空");
            return r;
        }
        // 1) size
        long expected = cfg == null ? 0 : cfg.sizeBytes;
        if (!sizeMatches(apk.length(), expected)) {
            r.errors.add(sizeError(apk.length(), expected));
        }
        // 2) 包信息（包名/versionCode/签名）
        String apkPath = apk.getAbsolutePath();
        PackageInfo pi = null;
        try {
            pi = ctx.getPackageManager().getPackageArchiveInfo(apkPath,
                    PackageManager.GET_SIGNATURES);
        } catch (Throwable ignored) {
        }
        if (pi == null || pi.applicationInfo == null) {
            r.errors.add("无法读取安装包信息（文件损坏或不是合法 APK）");
            return r;
        }
        String pkg = pi.applicationInfo.packageName;
        if (!GAME_PKG.equals(pkg)) {
            r.errors.add(pkgError(pkg));
        }
        if (!versionImproved(pi.versionCode, installedVersionCode)) {
            r.errors.add(versionError(pi.versionCode, installedVersionCode));
        }
        // 3) 签名一致性（读不到 → 降级警告）
        byte[] apkSig = signaturesOf(pi);
        if (installedSignature == null || apkSig == null) {
            r.warnings.add(sigUnavailableWarning());
        } else if (!signatureMatches(installedSignature, apkSig)) {
            r.errors.add(sigMismatchError());
        }
        // 4) 可选 sha256
        String expectSha = cfg == null ? null : cfg.sha256;
        if (expectSha != null && !expectSha.trim().isEmpty()) {
            try {
                String actual = sha256Hex(apk);
                if (!actual.equalsIgnoreCase(expectSha.trim())) {
                    r.errors.add(shaError(actual, expectSha));
                }
            } catch (IOException e) {
                r.errors.add("sha256 校验失败：" + e.getMessage());
            }
        }
        return r;
    }

    /** PackageInfo 的首签名合并为可比字节串；读不到返回 null。 */
    static byte[] signaturesOf(PackageInfo pi) {
        try {
            if (pi.signatures == null || pi.signatures.length == 0) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            for (android.content.pm.Signature s : pi.signatures) {
                byte[] b = s.toByteArray();
                bos.write((b.length >> 24) & 0xFF);
                bos.write((b.length >> 16) & 0xFF);
                bos.write((b.length >> 8) & 0xFF);
                bos.write(b.length & 0xFF);
                bos.write(b);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
