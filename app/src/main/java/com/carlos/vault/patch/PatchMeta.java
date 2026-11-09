package com.carlos.vault.patch;

import org.json.JSONObject;

/**
 * 补丁元数据，来自 {@code hotconfig.json} 的 {@code patches.code} 块。
 *
 * <pre>{ "patches": { "code": {
 *   "version": 2026110921,
 *   "baseApkMd5": "49376cab...",
 *   "baseVersionCode": 23574,
 *   "url": "{base}/v1/vault/patch/code.dex",
 *   "sha256": "…",
 *   "hmac": "…",
 *   "sizeBytes": 193008,
 *   "notes": "修复 xxx"
 * } } }</pre>
 */
public final class PatchMeta {

    public final long version;
    public final String baseApkMd5;
    public final int baseVersionCode;
    public final String url;
    public final String sha256;
    public final String hmac;
    public final long sizeBytes;
    public final String notes;

    private PatchMeta(long version, String baseApkMd5, int baseVersionCode, String url,
                      String sha256, String hmac, long sizeBytes, String notes) {
        this.version = version;
        this.baseApkMd5 = baseApkMd5 == null ? "" : baseApkMd5;
        this.baseVersionCode = baseVersionCode;
        this.url = url == null ? "" : url;
        this.sha256 = sha256 == null ? "" : sha256;
        this.hmac = hmac == null ? "" : hmac;
        this.sizeBytes = sizeBytes;
        this.notes = notes == null ? "" : notes;
    }

    /** 解析；字段缺失时给安全默认值，但 {@code url} 为空视为无效（由调用方判断）。 */
    public static PatchMeta fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        return new PatchMeta(
                o.optLong("version", 0),
                o.optString("baseApkMd5", ""),
                o.optInt("baseVersionCode", 0),
                o.optString("url", ""),
                o.optString("sha256", ""),
                o.optString("hmac", ""),
                o.optLong("sizeBytes", 0),
                o.optString("notes", ""));
    }
}
