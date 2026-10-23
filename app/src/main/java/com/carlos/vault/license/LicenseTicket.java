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
    /**
     * 授权等级（批次7 三级权限）：1=普通 / 2=会员 / 3=高级会员。
     *
     * <p>服务端为权威。旧票据（无该字段）反序列化时默认 {@link LicenseConfig#TIER_DEFAULT}=3，
     * 避免存量用户降权；HMAC 兼容策略见 {@link #signableFields()}。
     */
    public int tier = LicenseConfig.TIER_DEFAULT;
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
     *
     * <p><b>tier 与旧票据 HMAC 兼容（批次7）</b>：只有当 {@code tier != TIER_DEFAULT(3)} 时才把
     * {@code tier} 放进签名串。原因：
     * <ul>
     *   <li>旧票据（2026-10 批次7 之前签发）没有 tier 字段、签名串里也没有 tier —— 旧票据
     *       反序列化后 tier 默认为 3，规范化串与旧版逐字节一致，**旧签名继续通过、不失效**；
     *   <li>新票据里 tier=3（高级会员，默认档）同样走旧串，签名口径稳定；
     *   <li>tier=1/2（真实降档）才纳入签名，任何把 1/2 改成 3 的手工篡改都会改变签名串 →
     *       校验失败 → TAMPERED；反过来把默认 3 改成 1/2 同样 TAMPERED。
     *       把任意票据“改成 3”在签名上等价于缺省（已是最高档），不产生权限提升。
     * </ul>
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
        // 仅非默认档参与签名：保证旧票据（无 tier）签名串字节不变（详见方法注释）。
        if (LicenseConfig.normalizeTier(tier) != LicenseConfig.TIER_DEFAULT) {
            m.put("tier", LicenseConfig.normalizeTier(tier));
        }
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
            o.put("tier", LicenseConfig.normalizeTier(tier));
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

    /** 从落盘 JSON 反序列化；解析失败返回 null。缺 tier 的旧票据按 {@link LicenseConfig#TIER_DEFAULT}=3。 */
    public static LicenseTicket fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            LicenseTicket t = new LicenseTicket();
            t.code = o.optString("code", "");
            t.ticketId = o.optString("ticketId", "");
            t.type = o.optString("type", LicenseConfig.TYPE_MONTH);
            // 旧票据无 tier 字段 → optInt 取默认 3（存量用户保持高级会员，不降权）
            t.tier = LicenseConfig.normalizeTier(o.optInt("tier", LicenseConfig.TIER_DEFAULT));
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
        t.tier = tier;
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
     *
     * <p>tier：服务端下发才覆盖（旧版服务端无该字段时保持原值，旧票据默认 3）。
     */
    public void applyServerTicket(JSONObject ticket) {
        if (ticket == null) return;
        code = ticket.optString("code", code);
        ticketId = ticket.optString("ticketId", ticketId);
        type = ticket.optString("type", type);
        if (ticket.has("tier")) {
            tier = LicenseConfig.normalizeTier(ticket.optInt("tier", LicenseConfig.TIER_DEFAULT));
        }
        activatedAt = ticket.optLong("activatedAt", activatedAt);
        expireAt = ticket.optLong("expireAt", expireAt);
        serverStatus = ticket.optString("serverStatus", serverStatus);
    }

    @Override
    public String toString() {
        return "LicenseTicket{type=" + type + ", tier=" + tier + ", expireAt=" + expireAt
                + ", serverStatus=" + serverStatus + ", lastVerifiedAt=" + lastVerifiedAt + "}";
    }
}
