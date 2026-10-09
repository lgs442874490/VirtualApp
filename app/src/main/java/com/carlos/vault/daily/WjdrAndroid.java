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

    static {
        WjdrLog.setSink((level, tag, message) -> {
            switch (level) {
                case 'W': android.util.Log.w(tag, message); break;
                case 'E': android.util.Log.e(tag, message); break;
                case 'D': android.util.Log.d(tag, message); break;
                default:  android.util.Log.i(tag, message); break;
            }
        });
    }

    private WjdrAndroid() {
    }

    public static WjdrEngine newEngine(Context context) {
        final Context app = context.getApplicationContext();
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
