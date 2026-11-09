package com.carlos.vault.patch;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.security.MessageDigest;

import dalvik.system.DexClassLoader;

/**
 * 补丁加载器：把 {@code code.dex} 反射插入宿主 {@code PathClassLoader} 的
 * {@code dexElements} 最前面，使补丁类优先于宿主类被加载。
 *
 * <p>核心机制已在本项目真机验证（Android 14 / vivo / VirtualApp / 15 dex）：
 * 插入后 {@code Class.forName} 命中补丁类，且容器内应用正常启动。
 *
 * <p>调用时机：{@link PatchApp#attachBaseContext(Context)} 中、{@code super} 之前，
 * 保证 VirtualApp {@code VirtualCore.startup} 与业务类加载前补丁已就位。
 *
 * <p>安全边界：
 * <ul>
 *   <li>版本锚定：补丁记录 baseApkMd5，与当前 APK 不符则丢弃（防旧补丁打新包）</li>
 *   <li>失败回滚：加载异常计数，连续 2 次失败自动清除补丁</li>
 * </ul>
 */
public final class PatchLoader {

    private static final String TAG = "DexPatch";
    /** 连续失败达到该次数即清除补丁（防崩溃循环）。 */
    private static final int MAX_FAIL_COUNT = 2;

    private PatchLoader() {
    }

    /** 启动时应用补丁（幂等；无补丁/校验失败/已禁用时安全返回）。 */
    public static void applyAtStartup(Context base) {
        try {
            File dex = PatchStore.patchFile(base);
            if (!dex.isFile() || dex.length() == 0) {
                return;
            }

            PatchStore.State st = PatchStore.load(base);
            if (st.failCount >= MAX_FAIL_COUNT) {
                Log.w(TAG, "patch disabled after " + st.failCount + " failures, clearing");
                PatchStore.clear(base);
                return;
            }

            // 版本锚定：补丁只能用于生成它的那个基线 APK
            String actual = apkMd5(base.getPackageCodePath());
            if (st.baseApkMd5 != null && !st.baseApkMd5.isEmpty() && !st.baseApkMd5.equals(actual)) {
                Log.w(TAG, "base apk mismatch: expect=" + st.baseApkMd5 + " actual=" + actual);
                PatchStore.clear(base);
                return;
            }

            // 磁盘篡改检测：落地时记录的 sha256 必须与当前文件一致
            if (st.sha256 != null && !st.sha256.isEmpty()) {
                String actualSha = sha256(readFile(dex));
                if (!actualSha.equalsIgnoreCase(st.sha256)) {
                    Log.w(TAG, "patch file sha256 mismatch, drop patch");
                    PatchStore.clear(base);
                    return;
                }
            }

            insertDex(dex);
            st.applied = true;
            PatchStore.save(base, st);
            Log.i(TAG, "patch applied version=" + st.version);
        } catch (Throwable t) {
            Log.e(TAG, "apply patch failed", t);
            try {
                PatchStore.State st = PatchStore.load(base);
                st.failCount++;
                PatchStore.save(base, st);
                if (st.failCount >= MAX_FAIL_COUNT) {
                    PatchStore.clear(base);
                    Log.w(TAG, "fail count reached " + MAX_FAIL_COUNT + ", patch cleared");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 保存补丁（下次冷启动生效）。
     *
     * @param bytes      补丁 dex 字节
     * @param baseApkMd5 基线 APK md5；为空时取当前 APK 的 md5
     * @return 是否保存成功
     */
    public static boolean install(Context ctx, byte[] bytes, String baseApkMd5) {
        return install(ctx, bytes, baseApkMd5, 0);
    }

    /**
     * 保存补丁（下次冷启动生效）。
     *
     * @param bytes      补丁 dex 字节
     * @param baseApkMd5 基线 APK md5；为空时取当前 APK 的 md5
     * @param version    补丁版本；<=0 时用当前时间戳
     * @return 是否保存成功
     */
    public static boolean install(Context ctx, byte[] bytes, String baseApkMd5, long version) {
        try {
            if (bytes == null || bytes.length == 0) {
                Log.w(TAG, "install: empty bytes");
                return false;
            }
            File dex = PatchStore.patchFile(ctx);
            try (FileOutputStream out = new FileOutputStream(dex)) {
                out.write(bytes);
            }
            PatchStore.State st = PatchStore.load(ctx);
            st.version = version > 0 ? version : System.currentTimeMillis();
            st.applied = false;
            st.failCount = 0;
            st.baseApkMd5 = (baseApkMd5 == null || baseApkMd5.isEmpty())
                    ? apkMd5(ctx.getPackageCodePath()) : baseApkMd5;
            st.sha256 = sha256(bytes);
            PatchStore.save(ctx, st);
            Log.i(TAG, "patch installed size=" + bytes.length + ", will apply on next startup");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            return false;
        }
    }

    /** 把补丁 dex 插入宿主 ClassLoader 的 dexElements 最前面。 */
    static void insertDex(File dexFile) throws Exception {
        // 预校验 dex 有效性：DexClassLoader 是懒加载，坏 dex 会静默接受（元素数为 0 或无类），
        // 必须先用 DexFile 真正解析一次，失败即抛，使其计入 failCount 触发回滚。
        dalvik.system.DexFile probe = dalvik.system.DexFile.loadDex(dexFile.getAbsolutePath(), null, 0);
        if (probe == null) {
            throw new IllegalStateException("invalid patch dex (null DexFile)");
        }
        probe.close();

        ClassLoader hostLoader = PatchLoader.class.getClassLoader();
        Object hostPathList = getPathList(hostLoader);
        Object[] hostElements = getDexElements(hostPathList);
        Log.i(TAG, "host dexElements size=" + hostElements.length);

        DexClassLoader patchLoader = new DexClassLoader(
                dexFile.getAbsolutePath(), null, null, hostLoader);
        Object patchPathList = getPathList(patchLoader);
        Object[] patchElements = getDexElements(patchPathList);
        Log.i(TAG, "patch dexElements size=" + patchElements.length);

        Object[] merged = (Object[]) Array.newInstance(
                hostElements.getClass().getComponentType(),
                patchElements.length + hostElements.length);
        System.arraycopy(patchElements, 0, merged, 0, patchElements.length);
        System.arraycopy(hostElements, 0, merged, patchElements.length, hostElements.length);

        Field field = hostPathList.getClass().getDeclaredField("dexElements");
        field.setAccessible(true);
        field.set(hostPathList, merged);
        Log.i(TAG, "dexElements replaced, new size=" + merged.length);
    }

    private static Object getPathList(ClassLoader cl) throws Exception {
        Field f = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
        f.setAccessible(true);
        return f.get(cl);
    }

    private static Object[] getDexElements(Object pathList) throws Exception {
        Field f = pathList.getClass().getDeclaredField("dexElements");
        f.setAccessible(true);
        return (Object[]) f.get(pathList);
    }

    /** 计算 bytes 的 sha256（hex），失败返回空串。 */
    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] readFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 计算文件 md5（hex），失败返回空串。 */
    static String apkMd5(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
