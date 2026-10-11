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
        HotConfig.forceRefresh(context);
    }
}
