package com.carlos.vault.patch;

import android.content.Context;
import android.util.Log;

import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.HotConfig;
import com.carlos.vault.license.LicenseConfig;

import org.json.JSONObject;

import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 补丁同步：读 hotconfig → 下载 → 校验（size/sha256/HMAC）→ 落地（下次冷启动生效）。
 *
 * <p>HMAC 密钥复用 {@link LicenseConfig#appSecret()}（与服务端 LICENSE_SECRET 同值），
 * 保证补丁不能被中间人替换。
 *
 * <p>需在后台线程调用。
 */
public final class PatchManager {

    private static final String TAG = "DexPatch";
    private static final String HMAC_ALG = "HmacSHA256";

    private PatchManager() {
    }

    /** 检查并下载补丁；任何异常都静默降级（不影响 App 启动）。 */
    public static synchronized void sync(Context ctx) {
        try {
            HotConfig.init(ctx);
            JSONObject code = HotConfig.codePatch();
            if (code == null) {
                Log.i(TAG, "no code patch config");
                return;
            }
            PatchMeta meta = PatchMeta.fromJson(code);
            if (meta == null || meta.url.isEmpty()) {
                Log.w(TAG, "bad code patch config");
                return;
            }

            PatchStore.State st = PatchStore.load(ctx);
            if (st.version >= meta.version) {
                Log.i(TAG, "patch up to date v=" + st.version);
                return;
            }

            byte[] bytes = VaultSync.fetchBytes(ctx, meta.url);
            if (bytes == null || bytes.length == 0) {
                Log.w(TAG, "patch download failed");
                return;
            }
            if (!verify(bytes, meta)) {
                return;
            }

            boolean ok = PatchLoader.install(ctx, bytes, meta.baseApkMd5, meta.version);
            Log.i(TAG, "sync done version=" + meta.version
                    + " size=" + bytes.length + " ok=" + ok);
        } catch (Throwable t) {
            Log.e(TAG, "sync failed", t);
        }
    }

    /** 校验 size / sha256 / HMAC；任一不符返回 false。 */
    static boolean verify(byte[] bytes, PatchMeta meta) {
        if (meta.sizeBytes > 0 && bytes.length != meta.sizeBytes) {
            Log.w(TAG, "size mismatch expect=" + meta.sizeBytes + " actual=" + bytes.length);
            return false;
        }
        if (!meta.sha256.isEmpty()) {
            String actual = sha256(bytes);
            if (!actual.equalsIgnoreCase(meta.sha256)) {
                Log.w(TAG, "sha256 mismatch expect=" + meta.sha256 + " actual=" + actual);
                return false;
            }
        }
        if (!meta.hmac.isEmpty()) {
            String actual = hmacSha256(bytes);
            if (!actual.equalsIgnoreCase(meta.hmac)) {
                Log.w(TAG, "hmac mismatch");
                return false;
            }
        }
        return true;
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Throwable t) {
            return "";
        }
    }

    static String hmacSha256(byte[] data) {
        try {
            String secret = LicenseConfig.appSecret();
            if (secret == null || secret.isEmpty()) {
                return "";
            }
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), HMAC_ALG));
            return hex(mac.doFinal(data));
        } catch (Throwable t) {
            return "";
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
}
