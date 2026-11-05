package com.carlos.vault.license;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 本地授权票据。
 *
 * 服务端为权威：{@link #activatedAt} / {@link #expireAt} / {@link #serverStatus}
 * 全部来自服务端响应，客户端只缓存与展示。
 *
 * 完整性：{@link #signableFields()} 的规范化 JSON 经 HMAC-SHA256 得到签名，
 * 落盘时同时保存 ticket_json 与 ticket_sig。任何字段被手工篡改（例如把
 * lastVerifiedAt 改大以延长离线宽限）都会导致签名不符 → TAMPERED。
 *
 * <p><b>角色与功能矩阵（2026-11-06）</b>：服务端 activate/verify 新增下发
 * {@code role}（1/2/3/4，4=GM）与 {@code features}（当前角色可见功能 key 列表）。
 * 两者用于覆盖 App 的硬编码 {@code FeatureGate} 默认矩阵；离线时沿用票据缓存。
 * 签名兼容策略：旧票据无 role/features 字段时规范化串**逐字节不变**（见 signableFields）。
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
    /**
     * 角色档位（2026-11-06）：1/2/3/4（4=GM）。缺省 3，兼容旧票据/旧服务端。
     * GM 的 tier 仍为 3（旧 App 按高级会员处理），role=4 只给支持矩阵的新 App。
     */
    public int role = LicenseConfig.TIER_DEFAULT;
    /**
     * 服务端是否已下发功能矩阵（区分"字段缺失 → 回退硬编码"与"空数组 → 明确无功能"）。
     * 旧票据为 false；新票据首次 verify/activate 后为 true。
     */
    public boolean featuresPresent = false;
    /** 当前角色可见功能 key（服务端权威；离线时使用该缓存）。 */
    public List<String> features = new ArrayList<>();
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

    /** 排序、去重、去空后的功能 key（签名与下发消费都以该顺序为准）。 */
    public List<String> normalizedFeatures() {
        TreeSet<String> set = new TreeSet<>();
        if (features != null) {
            for (String k : features) {
                if (k != null && !k.isEmpty()) set.add(k);
            }
        }
        return new ArrayList<>(set);
    }

    /** 功能 key 的稳定串（逗号连接；key 只含小写字母/下划线，不含逗号）。 */
    private String featuresSignatureValue() {
        return String.join(",", normalizedFeatures());
    }

    /**
     * 参与签名的字段集合。刻意排除 {@link #sig} 自身。
     * 返回 TreeMap 以保证键有序，配合 {@link LicenseCrypto#writeCanonical} 得到稳定串。
     *
     * <p><b>tier/role/features 与旧票据 HMAC 兼容（2026-11-06）</b>：
     * <ul>
     *   <li>旧票据（无 tier/role/features 字段）反序列化后 tier=3、role=3、featuresPresent=false，
     *       规范化串与旧版**逐字节一致**，旧签名继续通过、不失效；</li>
     *   <li>tier/role 仅在非默认值 3 时参与签名（与既有 tier 策略一致）；</li>
     *   <li>features 仅在 featuresPresent=true 时参与（空数组也会以 {@code "features":""} 入串，
     *       因此"删掉 features 字段并伪造 featuresPresent=false"会改变签名 → TAMPERED）；</li>
     *   <li>GM 的 role=4 入串；把 3 改成 4 会改变签名 → TAMPERED。</li>
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
        // 角色仅非默认（3）时参与签名：GM=4/T1=1/T2=2 入串；默认档保持旧串。
        if (LicenseConfig.normalizeRole(role) != LicenseConfig.TIER_DEFAULT) {
            m.put("role", LicenseConfig.normalizeRole(role));
        }
        // 功能矩阵仅在服务端下发过时参与签名（空数组也入串，见方法注释）。
        if (featuresPresent) {
            m.put("features", featuresSignatureValue());
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
            o.put("role", LicenseConfig.normalizeRole(role));
            o.put("featuresPresent", featuresPresent);
            JSONArray arr = new JSONArray();
            for (String k : normalizedFeatures()) arr.put(k);
            o.put("features", arr);
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

    /** 从落盘 JSON 反序列化；解析失败返回 null。缺 tier/role 的旧票据按默认 3。 */
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
            t.role = LicenseConfig.normalizeRole(o.optInt("role", LicenseConfig.TIER_DEFAULT));
            t.features = new ArrayList<>();
            JSONArray arr = o.optJSONArray("features");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String k = arr.optString(i, "");
                    if (!k.isEmpty()) t.features.add(k);
                }
            }
            // 兼容：缺 featuresPresent 但存在 features 字段时视为已下发。
            t.featuresPresent = o.optBoolean("featuresPresent", arr != null);
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
        t.role = role;
        t.featuresPresent = featuresPresent;
        t.features = new ArrayList<>(features == null ? new ArrayList<>() : features);
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
     * <p>tier/role/features：服务端下发才覆盖（旧版服务端无对应字段时保持原值）。
     */
    public void applyServerTicket(JSONObject ticket) {
        if (ticket == null) return;
        code = ticket.optString("code", code);
        ticketId = ticket.optString("ticketId", ticketId);
        type = ticket.optString("type", type);
        if (ticket.has("tier")) {
            tier = LicenseConfig.normalizeTier(ticket.optInt("tier", LicenseConfig.TIER_DEFAULT));
        }
        applyServerRoleFeatures(ticket);
        activatedAt = ticket.optLong("activatedAt", activatedAt);
        expireAt = ticket.optLong("expireAt", expireAt);
        serverStatus = ticket.optString("serverStatus", serverStatus);
    }

    /**
     * 解析服务端下发的 role/features（activate 的 ticket 对象与 verify 的 data 共用）。
     * 字段缺失时保持原值，避免旧服务端把已有矩阵清空。
     */
    public void applyServerRoleFeatures(JSONObject o) {
        if (o == null) return;
        if (o.has("role")) {
            role = LicenseConfig.normalizeRole(o.optInt("role", LicenseConfig.TIER_DEFAULT));
        }
        if (o.has("features")) {
            JSONArray arr = o.optJSONArray("features");
            List<String> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String k = arr.optString(i, "");
                    if (!k.isEmpty()) list.add(k);
                }
            }
            features = list;
            featuresPresent = true;
        }
    }

    @Override
    public String toString() {
        return "LicenseTicket{type=" + type + ", tier=" + tier + ", role=" + role
                + ", features=" + (featuresPresent ? normalizedFeatures().size() : -1)
                + ", expireAt=" + expireAt + ", serverStatus=" + serverStatus
                + ", lastVerifiedAt=" + lastVerifiedAt + "}";
    }
}
