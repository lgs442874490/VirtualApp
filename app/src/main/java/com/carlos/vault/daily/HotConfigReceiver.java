package com.carlos.vault.daily;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 热更包手动检查更新接口：
 * <pre>adb shell am broadcast -a com.carlos.multiapp.HOT_REFRESH [--es url https://...]</pre>
 * 带 url 时先写入 hot_url（以后自动检查都用它），随后立即强制拉取（绕过 6h 限频），
 * 结果看 logcat 过滤 HotConfig。无 UI 依赖，锁定屏/后台均可触发。
 */
public class HotConfigReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.carlos.multiapp.HOT_REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !HotConfigReceiver.ACTION.equals(intent.getAction())) return;
        try {
            String url = intent.getStringExtra("url");
            if (url != null && !url.isEmpty()) {
                context.getSharedPreferences("hotconfig", Context.MODE_PRIVATE)
                        .edit().putString("hot_url", url).apply();
                Log.i(HotConfig.TAG, "hot_url 已更新: " + url);
            }
        } catch (Throwable ignored) {
        }
        // 广播回调在 Android 主线程：forceRefresh 内部是同步 OkHttp GET，主线程会直接抛
        // NetworkOnMainThreadException（真机 2026-10-25 实测 fetchText 返回 null、版本不变）。
        // 因此切到后台线程执行，结果仍看 logcat HotConfig；UI 手动检查本就在后台线程。
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                long v = HotConfig.forceRefresh(app);
                Log.i(HotConfig.TAG, "广播触发热更检查完成 version=" + v);
            } catch (Throwable t) {
                Log.w(HotConfig.TAG, "广播触发热更检查失败: " + t);
            }
        }, "hot-refresh").start();
    }
}
