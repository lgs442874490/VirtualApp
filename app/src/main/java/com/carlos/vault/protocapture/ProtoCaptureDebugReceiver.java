package com.carlos.vault.protocapture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.carlos.BuildConfig;

import java.io.File;

/**
 * 调试包专用（release no-op）：adb 触发开始/停止/导出/打开抓包页，便于真机自测与自动化取证。
 * 例：adb shell am broadcast -p com.carlos.multiapp -a com.carlos.vault.PROTO_DEBUG_START \
 *        --es target com.gof.china
 */
public class ProtoCaptureDebugReceiver extends BroadcastReceiver {
    public static final String ACTION_START = "com.carlos.vault.PROTO_DEBUG_START";
    public static final String ACTION_STOP = "com.carlos.vault.PROTO_DEBUG_STOP";
    public static final String ACTION_EXPORT = "com.carlos.vault.PROTO_DEBUG_EXPORT";
    public static final String ACTION_OPEN = "com.carlos.vault.PROTO_DEBUG_OPEN";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!BuildConfig.DEBUG || intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        try {
            if (ACTION_START.equals(action)) {
                String target = intent.getStringExtra("target");
                Intent i = new Intent(ctx, ProtoCaptureService.class)
                        .setAction(ProtoCaptureService.ACTION_START)
                        .putExtra(ProtoCaptureService.EXTRA_TARGET_PACKAGE,
                                target == null ? "" : target);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
            } else if (ACTION_STOP.equals(action)) {
                ProtoCaptureService.stop(ctx);
            } else if (ACTION_EXPORT.equals(action)) {
                File f = ProtoCaptureLog.export(ctx);
                android.util.Log.i("ProtoCapture", "export=" + f.getAbsolutePath()
                        + " records=" + ProtoCaptureLog.size());
            } else if (ACTION_OPEN.equals(action)) {
                Intent i = new Intent(ctx, ProtoCaptureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Throwable t) {
            android.util.Log.w("ProtoCapture", "debug receiver: " + t);
        }
    }
}
