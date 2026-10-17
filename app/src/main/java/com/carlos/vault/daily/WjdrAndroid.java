package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.WjdrLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * App 侧引擎工厂：把 Android 环境（assets / 顶号策略 prefs / logcat）适配进平台中立的 WjdrEngine。
 * daily/ 全部引擎创建应走此处，保持 wjdr 包零 Android 依赖（vault-server 共用同一份源码）。
 */
public final class WjdrAndroid {

    private static final java.util.concurrent.atomic.AtomicReference<Context> appRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    static {
        WjdrLog.setSink((level, tag, message) -> {
            switch (level) {
                case 'W': android.util.Log.w(tag, message); break;
                case 'E': android.util.Log.e(tag, message); break;
                case 'D': android.util.Log.d(tag, message); break;
                default:  android.util.Log.i(tag, message); break;
            }
            // 引擎日志落盘（files/wjdr_log.txt，512KB 轮转）——vivo 出厂关 logcat，文件是唯一可靠通道
            try {
                Context app = appRef.get();
                if (app == null) return;
                java.io.File f = new java.io.File(app.getFilesDir(), "wjdr_log.txt");
                if (f.length() > 512 * 1024) f.delete();
                try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                    w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA)
                            .format(new java.util.Date()) + " " + tag + "/" + level + ": " + message + "\n");
                }
            } catch (Throwable ignored) {
            }
        });
        // P0 服务器时钟：单调钟注入 elapsedRealtime（含深睡计时，用户改系统时间/NTP 跳变不影响）
        com.carlos.vault.wjdr.GameServerClock.setMono(() -> android.os.SystemClock.elapsedRealtime());
    }

    private WjdrAndroid() {
    }

    public static WjdrEngine newEngine(Context context) {
        final Context app = context.getApplicationContext();
        appRef.set(app);
        AssetSource assets = name -> {
            InputStream in = app.getAssets().open(name);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                try { in.close(); } catch (Exception ignored) { }
            }
        };
        WjdrEngine.setTakeoverPolicySource(() -> {
            try {
                String v = app.getSharedPreferences("daily_sched", Context.MODE_PRIVATE)
                        .getString("takeover_policy", "human_first");
                return v == null ? "human_first" : v;
            } catch (Throwable t) {
                return "human_first";
            }
        });
        WjdrEngine engine = new WjdrEngine(assets);
        engine.setAppContext(app);
        return engine;
    }
}
