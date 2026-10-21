package com.carlos.vault.gameupdate;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * 分身游戏最新版本配置（M3）。
 *
 * <p>来源（按契约 §4.2 的可用性降级）：assets 内 {@code game_client.json} →
 * 内置缺省值（T1b 真机取证 2026-10-21：1.16.12 / versionCode 147 / 887,587,540B /
 * 短链 {@code https://wjdr-app.campfiregames.cn/download}）。
 *
 * <p><b>apkUrl 是 302 短链</b>：真实 CDN 地址路径含日期（会随包版本变化），
 * {@link GameApkDownloader} 必须在每次下载时现场解析 302，绝不允许把 CDN 路径写死进配置。
 *
 * <p>测试覆盖：Intent extra（{@code game_update_apk_url}）可临时覆盖 apkUrl，
 * 便于指向本地/自建源验证全链路，不必真下 888MB。
 *
 * <p>解析用手写的平铺 JSON 提取器（{@link #fromJson}）：字段全是标量、无嵌套，
 * 避免把 org.json 依赖带进 JVM 单测（android.jar 的 org.json 在单测里是桩）。
 */
public class GameClientUpdateConfig {

    /** assets 配置文件名 */
    public static final String ASSET_FILE = "game_client.json";
    /** Intent extra：覆盖 apkUrl（联调/测试用） */
    public static final String EXTRA_OVERRIDE_APK_URL = "game_update_apk_url";

    // ---- 内置缺省值（qa/evidence-20261021-account-pool/T1b 取证值；仅当 assets 缺失/损坏时兜底）----
    public static final String FALLBACK_LATEST_VERSION_NAME = "1.16.12";
    public static final long FALLBACK_LATEST_VERSION_CODE = 147L;
    public static final long FALLBACK_SIZE_BYTES = 887587540L;
    public static final String FALLBACK_APK_URL = "https://wjdr-app.campfiregames.cn/download";
    public static final String FALLBACK_SHORT_LINK = FALLBACK_APK_URL;

    /** 最新版本 versionName（展示用，不参与比较） */
    public String latestVersionName = FALLBACK_LATEST_VERSION_NAME;
    /** 最新版本 versionCode（版本比较的唯一依据） */
    public long latestVersionCode = FALLBACK_LATEST_VERSION_CODE;
    /** 官方下载短链（302），每次下载现场解析 */
    public String apkUrl = FALLBACK_APK_URL;
    /** 展示用短链（游戏内更新按钮跳的同一个地址） */
    public String shortLink = FALLBACK_SHORT_LINK;
    /** 预期包大小（字节）；<=0 表示未知，校验时跳过 size 比对 */
    public long sizeBytes = FALLBACK_SIZE_BYTES;
    /** 可选 sha256（小写 hex）；空 = 跳过该单项校验 */
    public String sha256 = "";
    /** 游戏服务端是否强制该版本（仅用于文案分级，判定一律以 versionCode 为准） */
    public boolean force = true;
    /** 最低可升级基线：容器内低于它时判定 UNSUPPORTED（建议重新克隆）；<=0 不启用 */
    public long minSupportedVersionCode = 0L;

    /** 读 assets 配置；任何失败（缺文件/坏 JSON）都回退内置缺省值，绝不抛出。 */
    public static GameClientUpdateConfig load(Context ctx) {
        return load(ctx, null);
    }

    /**
     * @param apkUrlOverride 非 null 时覆盖 apkUrl（Intent extra 传参，联调用）
     */
    public static GameClientUpdateConfig load(Context ctx, String apkUrlOverride) {
        GameClientUpdateConfig cfg = defaults();
        if (ctx != null) {
            try {
                InputStream in = ctx.getAssets().open(ASSET_FILE);
                String json = readAll(in);
                in.close();
                merge(cfg, fromJson(json));
            } catch (Throwable ignored) {
                // assets 未内置或读取失败 → 保持缺省值（取证内置基线）
            }
        }
        if (apkUrlOverride != null && !apkUrlOverride.trim().isEmpty()) {
            cfg.apkUrl = apkUrlOverride.trim();
        }
        return cfg;
    }

    public static GameClientUpdateConfig defaults() {
        return new GameClientUpdateConfig();
    }

    /**
     * 平铺 JSON → 配置（纯函数，JVM 可测）。认识字符串/整数/布尔标量；
     * 不认识的字段忽略，缺的字段保持调用方默认值。解析异常返回 null。
     */
    public static GameClientUpdateConfig fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        // 最小 JSON 合法性：必须是花括号包起来的对象（挡住整段错误内容回填默认值的误判）
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null;
        try {
            GameClientUpdateConfig cfg = new GameClientUpdateConfig();
            cfg.latestVersionName = stringValue(json, "latestVersionName", cfg.latestVersionName);
            cfg.latestVersionCode = longValue(json, "latestVersionCode", cfg.latestVersionCode);
            cfg.apkUrl = stringValue(json, "apkUrl", cfg.apkUrl);
            cfg.shortLink = stringValue(json, "shortLink", cfg.shortLink);
            cfg.sizeBytes = longValue(json, "sizeBytes", cfg.sizeBytes);
            cfg.sha256 = stringValue(json, "sha256", cfg.sha256);
            cfg.force = boolValue(json, "force", cfg.force);
            cfg.minSupportedVersionCode =
                    longValue(json, "minSupportedVersionCode", cfg.minSupportedVersionCode);
            return cfg;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用解析结果覆盖缺省值（仅覆盖 JSON 里明确给出的字段）。 */
    static void merge(GameClientUpdateConfig base, GameClientUpdateConfig parsed) {
        if (base == null || parsed == null) return;
        base.latestVersionName = parsed.latestVersionName;
        base.latestVersionCode = parsed.latestVersionCode;
        base.apkUrl = parsed.apkUrl;
        base.shortLink = parsed.shortLink;
        base.sizeBytes = parsed.sizeBytes;
        base.sha256 = parsed.sha256;
        base.force = parsed.force;
        base.minSupportedVersionCode = parsed.minSupportedVersionCode;
    }

    // ---- 平铺 JSON 标量提取（够用即可：字段名在双引号内唯一，值不含转义引号）----

    static String stringValue(String json, String key, String def) {
        String raw = rawValue(json, key);
        if (raw == null) return def;
        raw = raw.trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return def;
    }

    static long longValue(String json, String key, long def) {
        String raw = stripQuotes(rawValue(json, key));
        if (raw == null) return def;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean boolValue(String json, String key, boolean def) {
        String raw = stripQuotes(rawValue(json, key));
        if (raw == null) return def;
        raw = raw.trim();
        if ("true".equals(raw)) return true;
        if ("false".equals(raw)) return false;
        return def;
    }

    /** 宽容处理：`"147"` 这类字符串包裹的标量也剥掉引号再解析。 */
    private static String stripQuotes(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /** 取 key 对应的原始值串（到逗号/右花括号为止）；不存在返回 null。 */
    private static String rawValue(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int end = colon + 1;
        boolean inStr = false;
        for (; end < json.length(); end++) {
            char c = json.charAt(end);
            if (inStr) {
                if (c == '\\') {
                    end++;   // 跳过转义对
                    continue;
                }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == ',' || c == '}' || c == ']') break;
        }
        return json.substring(colon + 1, end).trim();
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        char[] buf = new char[2048];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    /** 诊断串（失败弹窗「复制诊断」用）：一行看全配置关键值。 */
    public String describe() {
        return "latest=" + latestVersionName + "(" + latestVersionCode + ")"
                + " size=" + sizeBytes
                + " sha256=" + (sha256 == null || sha256.isEmpty() ? "-" : sha256)
                + " force=" + force + " apkUrl=" + apkUrl;
    }
}
