package com.carlos.vault.license;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * 本地授权票据。
 *
 * 服务端为权威：{@link #activatedAt} / {@link #expireAt} / {@link #serverStatus}
 * 全部来自服务端响应，客户端只缓存与展示。
 *
 * 完整性：{@link #signableFields()} 的规范化 JSON 经 HMAC-SHA256 得到签名，
 * 落盘时同时保存 ticket_json 与 ticket_sig。任何字段被手工篡改（例如把
 * lastVerifiedAt 改大以延长离线宽限）都会导致签名不符 → TAMPERED。
 */
public class LicenseTicket {

    /** 兑换码（展示用；本地不作为校验依据）。 */
    public String code = "";
    /** 服务端票据 ID（追踪用）。 */
    public String ticketId = "";
    /** 码类型：month / permanent。 */
    public String type = LicenseConfig.TYPE_MONTH;
    /** 首激活时间（服务端权威，epoch ms）。 */
    public long activatedAt = 0L;
    /** 到期时间；permanent 为 0。 */
    public long expireAt = 0L;
    /** 服务端状态：active / expired / revoked / device_mismatch（原样落盘，由 LicenseManager 按 D4 映射）。 */
    public String serverStatus = LicenseConfig.STATUS_ACTIVE;
    /** 本机加盐指纹（verify 复用）。 */
    public String deviceHash = "";
    /** 激活时服务端下发的一次性盐（展示/排障用）。 */
    public String salt = "";
    /** 最后一次联网校验成功时间（本地时钟，D3 宽限起算点）。 */
    public long lastVerifiedAt = 0L;
    /** serverTime - localTimeAtResponse，用于修正本机时间。 */
    public long clockOffsetMs = 0L;
    /** 上述字段的 HMAC-SHA256。 */
    public String sig = "";

    /** 是否为永久码。 */
    public boolean isPermanent() {
        return LicenseConfig.TYPE_PERMANENT.equals(type);
    }

    /** 票面是否为空（从未激活）。 */
    public boolean isEmpty() {
        return code == null || code.isEmpty() || ticketId == null || ticketId.isEmpty();
    }

    /**
     * 参与签名的字段集合。刻意排除 {@link #sig} 自身。
     * 返回 TreeMap 以保证键有序，配合 {@link LicenseCrypto#writeCanonical} 得到稳定串。
     */
    public Map<String, Object> signableFields() {
        Map<String, Object> m = new TreeMap<>();
        m.put("activatedAt", activatedAt);
        m.put("clockOffsetMs", clockOffsetMs);
        m.put("code", code == null ? "" : code);
        m.put("deviceHash", deviceHash == null ? "" : deviceHash);
        m.put("expireAt", expireAt);
        m.put("lastVerifiedAt", lastVerifiedAt);
        m.put("salt", salt == null ? "" : salt);
        m.put("serverStatus", serverStatus == null ? "" : serverStatus);
        m.put("ticketId", ticketId == null ? "" : ticketId);
        m.put("type", type == null ? "" : type);
        return m;
    }

    /** 参与签名的规范化串（供 HMAC 使用）。 */
    public String canonicalForSign() {
        return LicenseCrypto.writeCanonical(signableFields());
    }

    /** 序列化为完整 JSON（含 sig），用于落盘。 */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("code", code == null ? "" : code);
            o.put("ticketId", ticketId == null ? "" : ticketId);
            o.put("type", type == null ? "" : type);
            o.put("activatedAt", activatedAt);
            o.put("expireAt", expireAt);
            o.put("serverStatus", serverStatus == null ? "" : serverStatus);
            o.put("deviceHash", deviceHash == null ? "" : deviceHash);
            o.put("salt", salt == null ? "" : salt);
            o.put("lastVerifiedAt", lastVerifiedAt);
            o.put("clockOffsetMs", clockOffsetMs);
            o.put("sig", sig == null ? "" : sig);
            return o.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从落盘 JSON 反序列化；解析失败返回 null。 */
    public static LicenseTicket fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            LicenseTicket t = new LicenseTicket();
            t.code = o.optString("code", "");
            t.ticketId = o.optString("ticketId", "");
            t.type = o.optString("type", LicenseConfig.TYPE_MONTH);
            t.activatedAt = o.optLong("activatedAt", 0L);
            t.expireAt = o.optLong("expireAt", 0L);
            t.serverStatus = o.optString("serverStatus", LicenseConfig.STATUS_ACTIVE);
            t.deviceHash = o.optString("deviceHash", "");
            t.salt = o.optString("salt", "");
            t.lastVerifiedAt = o.optLong("lastVerifiedAt", 0L);
            t.clockOffsetMs = o.optLong("clockOffsetMs", 0L);
            t.sig = o.optString("sig", "");
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 深拷贝，避免状态机判定时污染原对象。 */
    public LicenseTicket copy() {
        LicenseTicket t = new LicenseTicket();
        t.code = code;
        t.ticketId = ticketId;
        t.type = type;
        t.activatedAt = activatedAt;
        t.expireAt = expireAt;
        t.serverStatus = serverStatus;
        t.deviceHash = deviceHash;
        t.salt = salt;
        t.lastVerifiedAt = lastVerifiedAt;
        t.clockOffsetMs = clockOffsetMs;
        t.sig = sig;
        return t;
    }

    /**
     * 用服务端响应 data 中的 ticket 对象填充本票据。
     * 只覆盖服务端权威字段，本地字段（lastVerifiedAt/clockOffsetMs/deviceHash/salt）由调用方另行设置。
     */
    public void applyServerTicket(JSONObject ticket) {
        if (ticket == null) return;
        code = ticket.optString("code", code);
        ticketId = ticket.optString("ticketId", ticketId);
        type = ticket.optString("type", type);
        activatedAt = ticket.optLong("activatedAt", activatedAt);
        expireAt = ticket.optLong("expireAt", expireAt);
        serverStatus = ticket.optString("serverStatus", serverStatus);
    }

    @Override
    public String toString() {
        return "LicenseTicket{type=" + type + ", expireAt=" + expireAt
                + ", serverStatus=" + serverStatus + ", lastVerifiedAt=" + lastVerifiedAt + "}";
    }
}
