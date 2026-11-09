package com.carlos.vault.daily;

import android.content.Context;
import android.util.Log;

import com.carlos.vault.VaultSync;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * 热更新管理器（Tier 1 配置/目录 + Tier 2 声明式领取任务）：
 * 解决"改个字段名/阈值/目录就要重出包+过 vivo 确认"的发版循环。
 *
 * <p><b>数据来源优先级</b>：① filesDir/hotconfig/config.json（adb push / run-as 放置，
 * 离线可用）；② 远端 URL（prefs hot_url 可配，默认自托管在 vault-server：
 * {@code VaultSync.baseUrl(ctx) + "/v1/vault/hotconfig"}，启动/每 6 小时刷新）；
 * ③ 兜底走 APK 内置 assets——热更失败时行为与旧版完全一致。
 *
 * <p><b>热更包格式</b>（JSON）：
 * <pre>{ "version": 2026101101,
 *   "nums":  { "arena.version_fallback": 26042102 },
 *   "catalogs": { "shops": [ ...条目同 assets/towndev/shops.json 单店数组... ] },
 *   "claims":[ {"id":"hot_fire_chest","name":"火晶宝箱",
 *               "proto":"req_get_firecrystal_chest_award","args":{}},
 *              {"id":"hot_x","name":"xx","proto":"req_y","args":{"k":"v"},
 *               "notify":"notify_z"} ],
 *   "disabled": ["act_joy"] }</pre>
 *
 * <p><b>整包更新（大版本）</b>：同一 JSON 可带 apk 块，用于热更覆盖不了的版本：
 * <pre>{ "version": 2026101801,
 *   "apk": { "versionCode": 420, "versionName": "4.2.0",
 *            "url": "https://example.com/download/app.apk",
 *            "notes": "本次更新内容…" } }</pre>
 * 检查时若 apk.versionCode 高于本机 versionCode，由界面引导到浏览器下载，
 * 不做静默安装；热更仍按 version 字段独立比对。
 *
 * <p><b>声明式领取语义</b>（Tier 2）：每日轮收尾按 claims 顺序执行——collectNotifies
 * 后发 {@code proto}（args 原样透传，空=无参），code==0 记 CLAIMED 否则按码记
 * SKIPPED。覆盖"无参/固定参/单步"领取类需求；复杂逻辑（阈值/循环/战斗）仍走正式发版。
 *
 * <p>安全边界：内容限 2MB；不做任何代码/Dex 加载。
 */
public final class HotConfig {

    public static final String TAG = "HotConfig";
    private static final long REFRESH_INTERVAL_MILLIS = 6 * 3600_000L;
    private static final String PREFS = "hotconfig";
    private static final String PREF_HOT_URL = "hot_url";
    /** 默认热更源路径（vault-server 自托管；nginx 反代 /v1/vault/ 前缀）。 */
    private static final String DEFAULT_HOT_PATH = "/v1/vault/hotconfig";

    private static volatile JSONObject config;      // 热更包全文（null=无热更）
    private static volatile long version;
    private static volatile long lastFetchAt;
    private static volatile boolean initialized;

    private HotConfig() {
    }

    /** app 启动时调用一次：读本地文件 + 到点拉远端。 */
    public static synchronized void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        loadLocal(ctx);
        maybeRefreshRemote(ctx);
    }

    private static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "hotconfig");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "config.json");
    }

    /**
     * 热更源 URL 解析（纯函数，便于 JVM 单测）：prefs hot_url 非空优先；
     * 否则回落服务器默认自托管端点 {@code baseUrl + "/v1/vault/hotconfig"}。
     *
     * @param prefUrl prefs 里显式配置的 hot_url（可 null/空白）
     * @param baseUrl 服务器基址（{@link VaultSync#baseUrl}，末尾斜杠会被归一）
     * @return 生效的更新源 URL；两边都为空时返回 ""（防御式，正常不会发生）
     */
    static String resolveHotUrl(String prefUrl, String baseUrl) {
        if (prefUrl != null) {
            String u = prefUrl.trim();
            if (!u.isEmpty()) return u;
        }
        if (baseUrl == null) return "";
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b.isEmpty() ? "" : b + DEFAULT_HOT_PATH;
    }

    /**
     * 当前生效的热更源：prefs hot_url（adb 广播 --es url 可设置，联调/回退用）优先，
     * 否则默认走 vault-server 自托管端点——App 无需配置即可检查更新。
     */
    public static String hotUrl(Context ctx) {
        String pref = "";
        try {
            pref = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_HOT_URL, "");
        } catch (Throwable ignored) {
            // prefs 不可用时仍回落默认端点
        }
        String base;
        try {
            base = VaultSync.baseUrl(ctx);
        } catch (Throwable t) {
            base = null;
        }
        return resolveHotUrl(pref, base);
    }

    private static void loadLocal(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.isFile() || f.length() == 0 || f.length() > 2_000_000L) return;
            JSONObject o = new JSONObject(readFile(f));
            apply(o);
            Log.i(TAG, "本地热更包已加载 version=" + version);
        } catch (Throwable t) {
            Log.w(TAG, "本地热更包解析失败: " + t.getMessage());
        }
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** 远端刷新（默认服务器自托管端点，prefs hot_url 显式配置时优先；成功写回本地文件）。限频 6 小时。 */
    public static synchronized void maybeRefreshRemote(Context ctx) {
        try {
            String url = hotUrl(ctx);
            if (url.isEmpty()) return;
            if (System.currentTimeMillis() - lastFetchAt < REFRESH_INTERVAL_MILLIS) return;
            fetchAndApply(ctx, url);
        } catch (Throwable t) {
            Log.w(TAG, "远端热更拉取失败: " + t.getMessage());
        }
    }

    /**
     * 手动检查更新接口（绕过 6 小时限频立即拉取）。
     *
     * @return 生效的热更包版本号；更新源不可用（base/prefs 均为空）返回 -1
     */
    public static synchronized long forceRefresh(Context ctx) {
        lastFetchAt = System.currentTimeMillis();
        try {
            String url = hotUrl(ctx);
            if (url.isEmpty()) {
                Log.i(TAG, "手动检查：更新源为空（默认端点不可用且未配置 hot_url）");
                return -1;
            }
            long before = version;
            fetchAndApply(ctx, url);
            Log.i(TAG, "手动检查完成：version " + before + " → " + version);
            return version;
        } catch (Throwable t) {
            Log.w(TAG, "手动检查失败: " + t.getMessage());
            return -1;
        }
    }

    private static void fetchAndApply(Context ctx, String url) throws Exception {
        // 复用 VaultSync 客户端选择：生产 https 自签端点走 pin 客户端，其它走系统 CA
        String text = VaultSync.fetchText(ctx, url);
        if (text == null || text.isEmpty() || text.length() > 2_000_000L) return;
        JSONObject o = new JSONObject(text);
        long remote = o.optLong("version", 0);
        if (remote > version) {
            FileOutputStream out = new FileOutputStream(file(ctx));
            out.write(text.getBytes("UTF-8"));
            out.close();
            apply(o);
            Log.i(TAG, "远端热更包已更新 version=" + remote);
        }
    }

    /** 服务器版本比对结果。 */
    public static final class CheckResult {
        public final int code;        // 0=已更新到服务器版 1=已是最新 2=更新源不可用（防御式，默认源恒非空） 3=拉取失败
        public final long serverVersion;
        public final long localVersion;
        // 整包（大版本）信息：服务端 JSON 未带 apk 块时 versionCode=0 / 其余为空
        public final long apkVersionCode;
        public final String apkVersionName;
        public final String apkUrl;
        public final String apkNotes;

        CheckResult(int code, long serverVersion, long localVersion) {
            this(code, serverVersion, localVersion, 0, "", "", "");
        }

        CheckResult(int code, long serverVersion, long localVersion,
                    long apkVersionCode, String apkVersionName, String apkUrl, String apkNotes) {
            this.code = code;
            this.serverVersion = serverVersion;
            this.localVersion = localVersion;
            this.apkVersionCode = apkVersionCode;
            this.apkVersionName = apkVersionName == null ? "" : apkVersionName;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.apkNotes = apkNotes == null ? "" : apkNotes;
        }
    }

    /**
     * 检查服务器版本：热更包按 version 比对并立即应用；apk 块原样带回给界面，
     * 由界面比较本机 versionCode 后决定是否引导整包下载。
     */
    public static synchronized CheckResult checkServer(Context ctx) {
        long local = version;
        try {
            String url = hotUrl(ctx);
            if (url.isEmpty()) {
                return new CheckResult(2, -1, local);
            }
            lastFetchAt = System.currentTimeMillis();
            // 与 fetchAndApply 同一条取数路径：生产 https 自签端点走 VaultSync 的 pin 客户端
            String text = VaultSync.fetchText(ctx, url);
            if (text == null || text.isEmpty() || text.length() > 2_000_000L) {
                return new CheckResult(3, -1, local);
            }
            JSONObject o = new JSONObject(text);
            long remote = o.optLong("version", 0);
            JSONObject apk = o.optJSONObject("apk");
            long apkCode = apk == null ? 0 : apk.optLong("versionCode", 0);
            String apkName = apk == null ? "" : apk.optString("versionName", "");
            String apkUrl = apk == null ? "" : apk.optString("url", "");
            String apkNotes = apk == null ? "" : apk.optString("notes", "");
            boolean hotUpdated = remote > local;
            if (hotUpdated) {
                try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                    out.write(text.getBytes("UTF-8"));
                }
                apply(o);
            }
            return new CheckResult(hotUpdated ? 0 : 1, remote, local,
                    apkCode, apkName, apkUrl, apkNotes);
        } catch (Throwable t) {
            Log.w(TAG, "检查更新失败: " + t.getMessage());
            return new CheckResult(3, -1, local);
        }
    }

    /** 手动放置/替换热更包后调用。 */
    public static synchronized void apply(JSONObject o) {
        if (o == null) return;
        config = o;
        version = o.optLong("version", 0);
    }

    // ---------- 消费接口（全部防御式：无热更包=回默认值） ----------

    /** 数值参数：nums.{key}，热更包未定义时回落 def。 */
    public static long num(String key, long def) {
        JSONObject o = config;
        if (o == null) return def;
        JSONObject nums = o.optJSONObject("nums");
        long v = nums == null ? def : nums.optLong(key, def);
        return v != 0 ? v : def;
    }

    /** 目录覆盖（map 形态，如 shops）：catalogs.{name}，否则 null。 */
    public static JSONObject catalogObj(String name) {
        JSONObject o = config;
        if (o == null) return null;
        JSONObject catalogs = o.optJSONObject("catalogs");
        return catalogs == null ? null : catalogs.optJSONObject(name);
    }

    /** 目录覆盖（数组形态，如 flamefangs）：catalogs.{name}，否则 null（调用方回落 assets）。 */
    public static JSONArray catalog(String name) {
        JSONObject o = config;
        if (o == null) return null;
        JSONObject catalogs = o.optJSONObject("catalogs");
        return catalogs == null ? null : catalogs.optJSONArray(name);
    }

    /** 声明式领取任务清单（Tier 2）；无热更包返回空数组。 */
    public static JSONArray claims() {
        JSONObject o = config;
        return o == null ? new JSONArray() : o.optJSONArray("claims");
    }

    /** 被热更禁用的任务 id，runTasks 据此强制跳过（远端一键止血用）。 */
    public static boolean isDisabled(String fnId) {
        JSONObject o = config;
        if (o == null) return false;
        JSONArray arr = o.optJSONArray("disabled");
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            if (fnId.equals(arr.optString(i))) return true;
        }
        return false;
    }

    public static long version() {
        return version;
    }

    /**
     * 代码补丁配置（{@code patches.code} 块）；未配置返回 null。
     *
     * <p>该块由 DexPatch 消费：{@code PatchManager.sync} 读取后下载 / 校验 / 落地。
     * 结构见 {@code PatchMeta}。保持只读，不影响原有 {@code nums/catalogs/claims} 语义。
     */
    public static JSONObject codePatch() {
        JSONObject o = config;
        if (o == null) {
            return null;
        }
        JSONObject patches = o.optJSONObject("patches");
        return patches == null ? null : patches.optJSONObject("code");
    }

}
