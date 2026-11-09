package com.carlos.vault.patch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.carlos.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 补丁调试入口（广播触发，仅 debug 构建生效）。
 *
 * <pre>
 * 应用补丁（下次冷启动生效）：
 *   adb push manual.dex /sdcard/Android/data/com.carlos.multiapp/files/dexpatch/manual.dex
 *   adb shell am broadcast -a com.carlos.dexpatch.APPLY \
 *       -n com.carlos.multiapp/com.carlos.vault.patch.PatchDebugReceiver
 * 查询状态：
 *   adb shell am broadcast -a com.carlos.dexpatch.STATUS -n com.carlos.multiapp/com.carlos.vault.patch.PatchDebugReceiver
 * 清除补丁：
 *   adb shell am broadcast -a com.carlos.dexpatch.CLEAR -n com.carlos.multiapp/com.carlos.vault.patch.PatchDebugReceiver
 * </pre>
 */
public class PatchDebugReceiver extends BroadcastReceiver {

    public static final String ACTION_APPLY = "com.carlos.dexpatch.APPLY";
    public static final String ACTION_STATUS = "com.carlos.dexpatch.STATUS";
    public static final String ACTION_CLEAR = "com.carlos.dexpatch.CLEAR";

    private static final String TAG = "DexPatch";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "non-debug build, ignore");
            return;
        }
        String action = intent == null ? "" : intent.getAction();
        Log.i(TAG, "onReceive action=" + action);

        if (ACTION_APPLY.equals(action)) {
            apply(context);
        } else if (ACTION_STATUS.equals(action)) {
            status(context);
        } else if (ACTION_CLEAR.equals(action)) {
            PatchStore.clear(context);
            toast(context, "补丁已清除");
        }
    }

    private void apply(Context context) {
        try {
            File src = new File(context.getExternalFilesDir(null), "dexpatch/manual.dex");
            if (!src.isFile()) {
                toast(context, "补丁不存在: " + src.getAbsolutePath());
                return;
            }
            byte[] bytes = readAll(src);
            boolean ok = PatchLoader.install(context, bytes, null);
            toast(context, ok
                    ? "补丁已保存（" + bytes.length + "B），重启 App 生效"
                    : "补丁保存失败");
        } catch (Throwable t) {
            Log.e(TAG, "apply failed", t);
            toast(context, "失败: " + t);
        }
    }

    private void status(Context context) {
        PatchStore.State st = PatchStore.load(context);
        String msg = "patch version=" + st.version + " applied=" + st.applied
                + " failCount=" + st.failCount;
        Log.i(TAG, msg);
        toast(context, msg);
    }

    private byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void toast(Context context, String msg) {
        Log.i(TAG, msg);
        try {
            Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
