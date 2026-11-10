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

    /**
     * 是否 debug 构建。
     *
     * <p><b>不能用 {@code BuildConfig.DEBUG}</b>：本项目 release 也配置了
     * {@code debuggable true}（run-as 调试容器数据依赖它），而 AGP 的
     * {@code BuildConfig.DEBUG} 跟随 debuggable，导致生产包该值恒为 true，
     * 签名校验会被整段跳过（2026-11-10 实测：无签名广播也能清除补丁）。
     * 这里改用 BUILD_TYPE 精确区分构建类型。
     */
    private static final boolean IS_DEBUG_BUILD = "debug".equals(BuildConfig.BUILD_TYPE);

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        // release 包不再一律拒绝：改为 HMAC 签名校验（见 hasValidSignature）。
        // 这样生产环境可用带签名的广播清除/查询补丁，同时不给任意应用留后门。
        if (!IS_DEBUG_BUILD && !hasValidSignature(intent, action)) {
            Log.w(TAG, "unsigned broadcast rejected: " + action);
            return;
        }
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

    /**
     * release 包的广播签名校验。
     *
     * <p>签名串 = {@code HMAC-SHA256(action + "|" + ts, LicenseConfig.appSecret())}（hex），
     * 由 {@code ts}（毫秒时间戳）限定 5 分钟有效期，降低重放风险。
     *
     * <p>生成方式（本机）：
     * <pre>
     * TS=$(date +%s%3N); SIG=$(printf '%s|%s' "com.carlos.dexpatch.CLEAR" "$TS" | openssl dgst -sha256 -hmac "$APP_SECRET" -r | cut -d' ' -f1)
     * adb shell am broadcast -a com.carlos.dexpatch.CLEAR \
     *   --es ts "$TS" --es sig "$SIG" -n com.carlos.multiapp/com.carlos.vault.patch.PatchDebugReceiver
     * </pre>
     */
    private boolean hasValidSignature(Intent intent, String action) {
        if (intent == null) {
            return false;
        }
        String sig = intent.getStringExtra("sig");
        String ts = intent.getStringExtra("ts");
        if (sig == null || sig.isEmpty() || ts == null || ts.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long t;
        try {
            t = Long.parseLong(ts);
        } catch (Throwable e) {
            return false;
        }
        if (Math.abs(now - t) > 5 * 60 * 1000L) {
            Log.w(TAG, "signature expired");
            return false;
        }
        String expect = hmacSha256(action + "|" + ts);
        return expect != null && !expect.isEmpty() && expect.equalsIgnoreCase(sig);
    }

    private String hmacSha256(String data) {
        try {
            String secret = com.carlos.vault.license.LicenseConfig.appSecret();
            if (secret == null || secret.isEmpty()) {
                return "";
            }
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
            byte[] d = mac.doFinal(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
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
