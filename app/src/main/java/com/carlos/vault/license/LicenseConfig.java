package com.carlos.vault.license;

import android.util.Base64;

import java.nio.charset.Charset;

/**
 * 授权模块的编译期常量与文案表。
 *
 * 本类中的每个常量都与服务端 <code>license-server</code> 的
 * <code>model.go</code> / <code>config.go</code> 一一对应，
 * 任何一侧的改动都必须双向同步，否则签名或错误码会错位。
 */
public final class LicenseConfig {

    private LicenseConfig() {
    }

    // ---------- 服务端基址 ----------

    /**
     * 生产环境基址（不含 /v1 后缀）。
     *
     * 值来自构建期字段 <code>LICENSE_BASE_URL</code>（见 app/build.gradle），
     * 因此换域名只需改一处构建配置。调试期还可被
     * <code>filesDir/license_endpoint.txt</code> 覆盖，见 {@link LicenseApi#resolveBaseUrl}。
     */
    public static final String BASE_URL = com.carlos.BuildConfig.LICENSE_BASE_URL;

    /** 调试期端点覆盖文件名（放在 filesDir 下，内容为一行完整基址）。 */
    public static final String ENDPOINT_OVERRIDE_FILE = "license_endpoint.txt";

    // ---------- 接口路径与协议 ----------

    /** 签名协议版本前缀，与服务端 signVersion 一致。 */
    public static final String SIGN_VERSION = "v1";

    /** 接口 URL 路径，仅用于拼接请求地址（http://host + PATH）。 */
    public static final String PATH_ACTIVATE = "/v1/activate";
    public static final String PATH_VERIFY = "/v1/verify";
    public static final String PATH_REBIND = "/v1/rebind";

    /**
     * 签名槽位 3 的操作名（OP）。**不含 /v1 前缀**。
     *
     * 架构师冻结修订（2026-09-14）：签名第 3 槽用「逻辑操作名」而非真实 URL 路径，
     * 以免与 URL 版本升级 / Caddy 反代 strip_prefix 耦合。
     * 必须与服务端 sign.go 的 opActivate / opVerify / opRebind 逐字节一致。
     */
    public static final String OP_ACTIVATE = "activate";
    public static final String OP_VERIFY = "verify";
    public static final String OP_REBIND = "rebind";

    // ---------- 共享密钥 ----------

    /**
     * HMAC 共享密钥（与服务端环境变量 LICENSE_SECRET 同值）。
     *
     * 以 base64 + 循环 XOR 存放，避免明文直接出现在反编译结果里。
     * 说明：对称密钥内置于 APK 属「提高门槛」而非「绝对不可破」，
     * 对本项目的小范围分发场景可接受。
     */
    private static final String APP_SECRET_OBF =
            "FwcXDRVZDAZTAgoPUlBIRRNXRl0WXQhTVlFQUl1UQURABUFaEVgLUlNUAwYPUBgTT1JFWE0PWFcFVgcGCFBBFw==";

    /** 与 APP_SECRET_OBF 配对使用的 XOR 密钥（16 字节）。 */
    private static final byte[] SECRET_XOR_KEY =
            "vaultlic2026key!".getBytes(Charset.forName("UTF-8"));

    /** 还原出真实共享密钥；异常时返回空串（调用方按「服务端错误」处理）。 */
    public static String appSecret() {
        try {
            byte[] cipher = Base64.decode(APP_SECRET_OBF, Base64.DEFAULT);
            byte[] plain = new byte[cipher.length];
            for (int i = 0; i < cipher.length; i++) {
                plain[i] = (byte) (cipher[i] ^ SECRET_XOR_KEY[i % SECRET_XOR_KEY.length]);
            }
            return new String(plain, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------- 时间与节流 ----------

    /** 离线宽限天数（D3）：以最后一次联网校验成功为起点。 */
    public static final int GRACE_DAYS = 3;

    public static final long MS_PER_DAY = 24L * 60L * 60L * 1000L;

    /** onResume 触发联网校验的节流窗口，避免频繁请求。 */
    public static final long VERIFY_THROTTLE_MS = 10L * 60_000L;

    /** 与服务端一致的时间戳允许偏移窗口。 */
    public static final long SIGN_WINDOW_MS = 5L * 60_000L;

    /** HTTP 超时（毫秒）。 */
    public static final long HTTP_TIMEOUT_MS = 15_000L;

    // ---------- 本地存储 ----------

    public static final String SP_NAME = "license_vault";
    public static final String KEY_TICKET_JSON = "ticket_json";
    public static final String KEY_TICKET_SIG = "ticket_sig";
    public static final String KEY_DEVICE_UUID = "device_uuid";
    public static final String KEY_LAST_VERIFY_AT = "last_verify_at";

    /** 票据 HMAC 的常量后缀，与签名证书指纹、包名一起派生出 ticketKey。 */
    public static final String TICKET_KEY_SUFFIX = "vault-license-v1";

    public static final String LOG_TAG = "LicenseX";

    // ---------- 业务错误码（与服务器 model.go 保持一致） ----------

    public static final int CODE_OK = 0;
    public static final int CODE_NOT_FOUND = 1001;
    public static final int CODE_FORMAT_INVALID = 1002;
    public static final int CODE_ALREADY_BOUND = 1003;
    public static final int CODE_REVOKED = 1004;
    public static final int CODE_EXPIRED = 1005;
    public static final int CODE_DEVICE_MISMATCH = 1006;
    public static final int CODE_NOT_ACTIVATED = 1007;
    public static final int CODE_REBIND_LIMIT = 1008;
    public static final int CODE_CHALLENGE_INVALID = 1009;
    public static final int CODE_BAD_SIGNATURE = 2001;
    public static final int CODE_TIMESTAMP_SKEW = 2002;
    public static final int CODE_NONCE_REPLAY = 2003;
    public static final int CODE_BAD_REQUEST = 2004;
    public static final int CODE_SERVER_ERROR = 3001;

    // ---------- 服务端 status 字段取值 ----------

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_REVOKED = "revoked";
    public static final String STATUS_DEVICE_MISMATCH = "device_mismatch";
    /**
     * 历史/防御性取值：服务端自契约 v1.1 起不再下发该 status（未激活统一回 code=1007）。
     * 客户端保留该比较仅为兜底——万一收到此字符串，按"未激活"处理（fail-closed）。
     */
    public static final String STATUS_NOT_ACTIVATED = "not_activated";

    // ---------- 码类型 ----------

    public static final String TYPE_MONTH = "month";
    public static final String TYPE_PERMANENT = "permanent";

    // ---------- 授权等级（tier，批次7 三级权限 2026-10-23） ----------
    //
    // 与服务端 license-server 的 license_code.tier 一一对应（schema 默认 3）：
    //   TIER_BASIC   = 1 普通（只有分身）
    //   TIER_MEMBER  = 2 会员（日常脚本）
    //   TIER_PREMIUM = 3 高级会员（高阶功能）
    //
    // 兼容策略（务必与服务端一致）：
    //  - 存量兑换码：服务端 DB 迁移时 `tier INTEGER NOT NULL DEFAULT 3` → 一律高级会员；
    //  - 旧票据（无 tier 字段）：客户端按 TIER_DEFAULT=3 处理，且不改变票据 HMAC 规范化串
    //    （见 LicenseTicket#signableFields），旧签名继续通过、不失效；
    //  - 服务端响应缺 tier 字段（旧版服务端）：同样按 3 处理。
    public static final int TIER_BASIC = 1;
    public static final int TIER_MEMBER = 2;
    public static final int TIER_PREMIUM = 3;
    /** 默认等级 = 高级会员（存量兼容：旧码/旧票据/缺字段一律按此）。 */
    public static final int TIER_DEFAULT = TIER_PREMIUM;

    /**
     * 归一化 tier：任意 int → 1/2/3 合法集合。
     * 0/负数/超界/未知值一律按 {@link #TIER_DEFAULT}（=3，存量兼容，绝不降权老用户）。
     */
    public static int normalizeTier(int tier) {
        return (tier >= TIER_BASIC && tier <= TIER_PREMIUM) ? tier : TIER_DEFAULT;
    }

    /** 等级展示名（UI/日志用；未知值按高级会员兼容）。 */
    public static String tierName(int tier) {
        switch (normalizeTier(tier)) {
            case TIER_BASIC:
                return "普通";
            case TIER_MEMBER:
                return "会员";
            default:
                return "高级会员";
        }
    }

    /**
     * 把业务错误码映射为给用户看的提示文案。
     * 安全类错误（验签/重放/参数）统一收敛为「网络异常」，不向用户暴露协议细节。
     */
    public static String messageFor(int code) {
        switch (code) {
            case CODE_OK:
                return "成功";
            case CODE_NOT_FOUND:
            case CODE_FORMAT_INVALID:
                return "兑换码不存在或格式错误";
            case CODE_ALREADY_BOUND:
                return "该码已被使用（已绑定其他设备）";
            case CODE_REVOKED:
                return "该码已被停用";
            case CODE_EXPIRED:
                return "授权已到期";
            case CODE_DEVICE_MISMATCH:
                return "设备不匹配";
            case CODE_NOT_ACTIVATED:
                return "该码尚未激活";
            case CODE_REBIND_LIMIT:
                return "换绑次数已用尽，请联系客服";
            case CODE_TIMESTAMP_SKEW:
                return "本机时间异常，请校准后重试";
            case CODE_CHALLENGE_INVALID:
            case CODE_BAD_SIGNATURE:
            case CODE_NONCE_REPLAY:
            case CODE_BAD_REQUEST:
            case CODE_SERVER_ERROR:
            default:
                return "网络异常，请重试";
        }
    }
}
