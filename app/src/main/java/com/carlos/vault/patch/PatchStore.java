package com.carlos.vault.patch;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * 补丁落盘与状态管理。
 *
 * <p>目录：{@code filesDir/dexpatch/}
 * <ul>
 *   <li>{@code code.dex} —— 补丁 dex</li>
 *   <li>{@code state.properties} —— version / applied / failCount / baseApkMd5</li>
 * </ul>
 *
 * <p>用 Properties 文件而非 SharedPreferences：{@code attachBaseContext} 早期
 * SharedPreferences 可能尚未加载完成，直接文件 IO 更可控。
 */
public final class PatchStore {

    private static final String DIR = "dexpatch";
    private static final String DEX = "code.dex";
    private static final String STATE = "state.properties";

    private PatchStore() {
    }

    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    static File patchFile(Context ctx) {
        return new File(dir(ctx), DEX);
    }

    private static File stateFile(Context ctx) {
        return new File(dir(ctx), STATE);
    }

    static State load(Context ctx) {
        State s = new State();
        try (FileInputStream in = new FileInputStream(stateFile(ctx))) {
            Properties p = new Properties();
            p.load(in);
            s.version = Long.parseLong(p.getProperty("version", "0"));
            s.applied = Boolean.parseBoolean(p.getProperty("applied", "false"));
            s.failCount = Integer.parseInt(p.getProperty("failCount", "0"));
            s.baseApkMd5 = p.getProperty("baseApkMd5", "");
            s.sha256 = p.getProperty("sha256", "");
        } catch (Throwable ignored) {
            // 首次运行无状态文件，返回默认值
        }
        return s;
    }

    static void save(Context ctx, State s) {
        try (FileOutputStream out = new FileOutputStream(stateFile(ctx))) {
            Properties p = new Properties();
            p.setProperty("version", String.valueOf(s.version));
            p.setProperty("applied", String.valueOf(s.applied));
            p.setProperty("failCount", String.valueOf(s.failCount));
            p.setProperty("baseApkMd5", s.baseApkMd5 == null ? "" : s.baseApkMd5);
            p.setProperty("sha256", s.sha256 == null ? "" : s.sha256);
            p.store(out, null);
        } catch (Throwable ignored) {
        }
    }

    /** 清除补丁与状态（失败回滚 / 手动清理）。 */
    static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        patchFile(ctx).delete();
        //noinspection ResultOfMethodCallIgnored
        stateFile(ctx).delete();
    }

    static final class State {
        long version;
        boolean applied;
        int failCount;
        String baseApkMd5 = "";
        /** 落地时记录的补丁 sha256，启动时用于发现磁盘篡改。 */
        String sha256 = "";
    }
}
