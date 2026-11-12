package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.passport.DeviceProfile;
import com.carlos.vaultserver.passport.PassportClient;
import com.carlos.vaultserver.passport.PassportException;
import com.carlos.vaultserver.passport.PassportIds;
import com.carlos.vaultserver.sms.SmsException;
import com.carlos.vaultserver.sms.SmsProvider;
import com.carlos.vaultserver.sms.SmsProviders;

import org.json.JSONObject;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 起号注册编排服务（任务书 M3 §2.1）：游客注册 / 实名 / 自包含凭据包 / 手机号单号全链路。
 *
 * <p>全部依赖注入可测：passport 走 {@link PassportFacade}（生产 {@code PassportFacade.of(client)}，
 * 单测 fake 回放）；接码平台走 {@link SmsProviderFactory}（生产包 {@link SmsProviders#create}）；
 * 轮询等待走 {@link Sleeper}（单测注入跳时）。异常消息只含上游错误文本，绝无敏感值
 * （sdkToken / 验证码 / 身份证号不出现在任何异常、日志与步骤轨迹里）。
 */
public final class RegistrationService {

    /** 收码轮询间隔（毫秒，任务书 5s）。 */
    public static final long DEFAULT_INTERVAL_MILLIS = 5_000L;
    /** 收码默认超时（秒，任务书 120s）。 */
    public static final int DEFAULT_SMS_WAIT_SECONDS = 120;

    private final PassportFacade passport;
    private final SmsProviderFactory smsFactory;
    private final Sleeper sleeper;

    /** 生产装配：真实 passport 客户端 + SmsProviders 工厂 + 真实睡眠。 */
    public static RegistrationService production(PassportFacade facade) {
        return new RegistrationService(facade,
                (type, cfg) -> SmsProviders.create(type, cfg,
                        new com.carlos.vaultserver.sms.D1SmsProvider.HttpUrlConnectionTransport()),
                REAL_SLEEPER);
    }

    public RegistrationService(PassportFacade passport, SmsProviderFactory smsFactory, Sleeper sleeper) {
        this.passport = passport;
        this.smsFactory = smsFactory;
        this.sleeper = sleeper == null ? REAL_SLEEPER : sleeper;
    }

    // ---------------- 游客注册 / 实名 ----------------

    /**
     * 游客快速注册：随机设备指纹 → passport.guestRegister → fpid/session_key 非空即成功。
     *
     * @return {@link RegistrationOutcome}（含自包含加密串 encrypted）
     * @throws IllegalStateException 上游失败 / 凭据缺失（消息含 errorText，无敏感值）
     */
    public RegistrationOutcome guestRegister() {
        DeviceProfile profile = DeviceProfile.random();
        PassportClient.PassportResult r;
        try {
            r = passport.guestRegister(profile);
        } catch (PassportException e) {
            throw new IllegalStateException("游客注册失败: " + e.getMessage(), e);
        }
        String fpid = r.fpid();
        String sk = r.sessionKey();
        if (fpid.isEmpty() || sk.isEmpty()) {
            throw new IllegalStateException("游客注册失败: " + (r.errorText().isEmpty()
                    ? "上游未返回凭据" : r.errorText()));
        }
        JSONObject deviceJson = new JSONObject(profile.toLoginProfile());
        deviceJson.put("account", fpid);
        String deviceJsonText = deviceJson.toString();
        RegistrationOutcome out = new RegistrationOutcome();
        out.fpid = fpid;
        out.sdkToken = sk;
        out.deviceJson = deviceJsonText;
        out.encrypted = buildEncrypted(fpid, sk, deviceJsonText);
        out.createdAt = System.currentTimeMillis();
        FileLog.info("游客注册成功 fpid={} token={}", fpid, PassportIds.mask(sk));
        // 游客号同样存档（无手机号，但 token 可换设备登录）
        AccountArchive archive = AccountArchive.get();
        if (archive != null) {
            archive.recordRegistration(fpid, "", "guest", sk);
        }
        return out;
    }

    /**
     * 实名认证：透传 {@code PassportFacade.realName}（本地校验在其内），返回布尔结论
     * （{@code realNameVerified}/{@code realNameMessage}），不抛上游业务失败。
     *
     * @throws IllegalArgumentException 姓名/身份证格式本地不合法（由 facade 透传的客户端校验）
     */
    public RegistrationOutcome realName(String fpid, String sdkToken, String name, String idCard) {
        RegistrationOutcome out = new RegistrationOutcome();
        out.fpid = fpid == null ? "" : fpid;
        out.sdkToken = sdkToken == null ? "" : sdkToken;
        out.createdAt = System.currentTimeMillis();
        PassportClient.PassportResult r;
        try {
            r = passport.realName(fpid, sdkToken, name, idCard);
        } catch (IllegalArgumentException e) {
            out.realNameVerified = false;
            out.realNameMessage = e.getMessage() == null ? "实名信息格式不正确" : e.getMessage();
            return out;
        } catch (PassportException e) {
            out.realNameVerified = false;
            out.realNameMessage = "实名认证请求失败: " + e.getMessage();
            return out;
        }
        String err = r.errorText();
        out.realNameVerified = err.isEmpty();
        out.realNameMessage = err.isEmpty() ? "实名认证已提交" : err;
        FileLog.info("实名认证 fpid={} verified={}", out.fpid, out.realNameVerified);
        return out;
    }

    // ---------------- 自包含凭据包（encrypted） ----------------

    /**
     * 构建自包含凭据包：{@code base64url( fpid + "\n" + sdkToken + "\n" + base64(deviceJson) )}。
     * encrypted 本身就是交付产物（用户自己的数据），可持久化、可回读。
     */
    public static String buildEncrypted(String fpid, String sdkToken, String deviceJson) {
        if (fpid == null || fpid.isEmpty() || sdkToken == null || sdkToken.isEmpty()
                || deviceJson == null || deviceJson.isEmpty()) {
            throw new IllegalArgumentException("加密串格式不正确");
        }
        String inner = Base64.getEncoder().encodeToString(
                deviceJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // fpid / sdkToken 是字母数字与符号、不含换行；deviceJson 已 base64 化——三段定界成立
        String joined = fpid + "\n" + sdkToken + "\n" + inner;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 解析自包含凭据包（{@link #buildEncrypted} 的互逆操作）。
     *
     * @return {@code [fpid, sdkToken, deviceJson]} 三元素
     * @throws IllegalArgumentException 非 base64url / 分段数不对 / 段为空（统一消息「加密串格式不正确」）
     */
    public static String[] parseEncrypted(String encrypted) {
        String invalid = "加密串格式不正确";
        if (encrypted == null || encrypted.trim().isEmpty()) {
            throw new IllegalArgumentException(invalid);
        }
        byte[] joined;
        try {
            joined = Base64.getUrlDecoder().decode(encrypted.trim());
        } catch (Throwable t) {
            throw new IllegalArgumentException(invalid);
        }
        String text = new String(joined, java.nio.charset.StandardCharsets.UTF_8);
        int first = text.indexOf('\n');
        int second = first < 0 ? -1 : text.indexOf('\n', first + 1);
        if (first <= 0 || second <= first + 1 || second == text.length() - 1) {
            throw new IllegalArgumentException(invalid);
        }
        String fpid = text.substring(0, first);
        String sdkToken = text.substring(first + 1, second);
        String inner = text.substring(second + 1);
        String deviceJson;
        try {
            deviceJson = new String(Base64.getDecoder().decode(inner),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new IllegalArgumentException(invalid);
        }
        if (deviceJson.isEmpty()) {
            throw new IllegalArgumentException(invalid);
        }
        return new String[]{fpid, sdkToken, deviceJson};
    }

    // ---------------- 手机号单号全链路 ----------------

    /**
     * 单号全链路：取号 → 发码 → 轮询收码 → 验证码登录 →（可选）实名 → 释放/拉黑。
     *
     * <p>失败分支语义（任务书）：取号失败 / 发码失败 / 登录失败 / 实名拒绝分别带
     * {@code step + error}；<b>超时未到码拉黑</b>（block），其余失败与成功路径尽力
     * {@code release} 归还号码。每一步记录 {@link StepLog} 轨迹。
     *
     * @param cfg  批次配置（provider / keyword / 超时 / 可选实名）
     * @param creds 凭据箱：登录成功后 seal 存入（表键 fpid）
     */
    public PhoneResult registerOnePhone(PhoneBatchConfig cfg, CredentialBox creds) {
        PhoneResult r = new PhoneResult();
        List<StepLog> steps = r.steps;

        SmsProvider provider;
        try {
            provider = smsFactory.create(cfg.providerType, cfg.providerConfig);
        } catch (RuntimeException e) {
            return fail(r, null, null, "ACQUIRE", "取号失败: " + safeMsg(e), false);
        }

        // ACQUIRE：取号
        SmsProvider.PhoneOrder order;
        try {
            order = provider.acquireNumber(cfg.keyword);
        } catch (SmsException e) {
            return fail(r, provider, null, "ACQUIRE", "取号失败: " + safeMsg(e), false);
        }
        r.phone = order.phone;
        addStep(steps, "ACQUIRE", true, "取号成功");

        DeviceProfile profile = DeviceProfile.random();

        // 旧短信基线（2026-11-10 真实轮教训）：接码平台号池会复用号码，池中可能残留
        // 上一个使用者的历史短信。取号后先快照当前可见验证码，收码时只认与之不同的「新码」，
        // 否则会把旧码当本次验证码提交 → 上游「验证码验证失败」。快照失败不阻断（视为无旧码）。
        String baselineCode = "";
        try {
            SmsProvider.SmsMessage base = provider.fetchSms(order, cfg.keyword);
            if (base != null && !base.waiting && base.code != null && !base.code.trim().isEmpty()) {
                baselineCode = base.code.trim();
            }
        } catch (RuntimeException ignored) {
            // 基线快照失败按「无旧码」处理
        }

        // SEND_SMS：发码（60s 冷却由 PassportClient 维护）
        try {
            PassportClient.PassportResult sr = passport.sendSms(order.phone, "86", profile);
            if (!sr.errorText().isEmpty()) {
                return fail(r, provider, order, "SEND_SMS", "发送验证码失败: " + sr.errorText(), false);
            }
        } catch (RuntimeException e) {
            return fail(r, provider, order, "SEND_SMS", "发送验证码失败: " + safeMsg(e), false);
        }
        addStep(steps, "SEND_SMS", true, "验证码已发送");

        // WAIT_SMS：轮询收码（间隔 intervalMillis，总超时 smsWaitSeconds；超时 → block）
        String code = null;
        long waited = 0;
        long budget = cfg.smsWaitSeconds * 1000L;
        while (true) {
            SmsProvider.SmsMessage msg;
            try {
                msg = provider.fetchSms(order, cfg.keyword);
            } catch (SmsException e) {
                return fail(r, provider, order, "WAIT_SMS", "收取验证码失败: " + safeMsg(e), false);
            }
            String candidate = msg.code == null ? "" : msg.code.trim();
            if (!msg.waiting && !candidate.isEmpty() && !candidate.equals(baselineCode)) {
                code = candidate;
                break;
            }
            if (waited + cfg.intervalMillis >= budget) {
                break; // 预算耗尽：不再等待，按超时处理
            }
            try {
                sleeper.sleep(cfg.intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail(r, provider, order, "WAIT_SMS", "等待验证码被中断", true);
            }
            waited += cfg.intervalMillis;
        }
        if (code == null) {
            String extra = baselineCode.isEmpty() ? "" : "（仅见旧验证码，未收到新码）";
            return fail(r, provider, order, "WAIT_SMS", "等待验证码超时未收到" + extra, true);
        }
        addStep(steps, "WAIT_SMS", true, "已收到验证码");

        // SMS_LOGIN：验证码登录
        String fpid;
        String sk;
        try {
            PassportClient.PassportResult lr = passport.smsLogin(order.phone, code, profile);
            fpid = lr.fpid();
            sk = lr.sessionKey();
            if (fpid.isEmpty() || sk.isEmpty()) {
                return fail(r, provider, order, "SMS_LOGIN", "登录失败: " + (lr.errorText().isEmpty()
                        ? "上游未返回凭据" : lr.errorText()), false);
            }
        } catch (RuntimeException e) {
            return fail(r, provider, order, "SMS_LOGIN", "登录失败: " + safeMsg(e), false);
        }
        addStep(steps, "SMS_LOGIN", true, "登录成功");

        // REAL_NAME（可选）：实名配置非空时执行；本地拒绝 / 上游拒绝 → 整号失败
        if (cfg.realName != null) {
            RegistrationOutcome rn = realName(fpid, sk, cfg.realName.name, cfg.realName.idCard);
            if (!rn.realNameVerified) {
                return fail(r, provider, order, "REAL_NAME",
                        "实名校验未通过: " + rn.realNameMessage, false);
            }
            addStep(steps, "REAL_NAME", true, rn.realNameMessage);
        }

        // 凭据入箱（批次期加密）+ 自包含加密串（产物）
        if (creds != null) {
            Map<String, String> credsMap = new LinkedHashMap<>();
            credsMap.put("fpid", fpid);
            credsMap.put("sdkToken", sk);
            credsMap.put("phone", order.phone);
            creds.seal(credsMap);
        }
        r.fpid = fpid;
        r.sdkToken = sk;
        // 账号存档（2026-11-11 用户要求）：记录 fpid ↔ 注册手机号 ↔ 接码平台，
        // 掉登录时据此用原手机号重新取码登录（不存 sdkToken，它会变）。
        AccountArchive archive = AccountArchive.get();
        if (archive != null) {
            archive.recordRegistration(fpid, order.phone, cfg.providerType, sk);
        }
        JSONObject deviceJson = new JSONObject(profile.toLoginProfile());
        deviceJson.put("account", fpid);
        r.encrypted = buildEncrypted(fpid, sk, deviceJson.toString());

        // RELEASE：成功路径归还号码（releaseQuietly 已记步骤轨迹）
        releaseQuietly(provider, order, steps);
        r.ok = true;
        // 手机号是账号找回凭证，日志一律掩码
        FileLog.info("手机号注册成功 phone={} fpid={}", AccountArchive.mask(order.phone), fpid);
        return r;
    }

    // ---------------- 结果类型 ----------------

    /** 注册产物（游客 / 单号共用载体）：fpid + sdkToken + 加密串 + 设备快照。 */
    public static final class RegistrationOutcome {
        public String fpid = "";
        public String sdkToken = "";
        public String encrypted = "";
        public String deviceJson = "";
        public long createdAt;
        /** 实名结论（仅 {@link #realName} 填充）。 */
        public boolean realNameVerified;
        public String realNameMessage = "";
    }

    /** 步骤轨迹：step ∈ ACQUIRE/SEND_SMS/WAIT_SMS/SMS_LOGIN/REAL_NAME/RELEASE/BLOCK。 */
    public static final class StepLog {
        public final String step;
        public final boolean ok;
        public final String message;
        public final long at;

        public StepLog(String step, boolean ok, String message) {
            this.step = step == null ? "" : step;
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.at = System.currentTimeMillis();
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("step", step);
            o.put("ok", ok);
            o.put("message", message);
            o.put("at", at);
            return o;
        }

        static StepLog fromJson(JSONObject o) {
            return new StepLog(o.optString("step", ""), o.optBoolean("ok", false),
                    o.optString("message", ""));
        }
    }

    /** 单号结果：成功带 fpid/sdkToken/encrypted/phone；失败带 step + error（轨迹全量保留）。 */
    public static final class PhoneResult {
        public boolean ok;
        public String phone = "";
        public String fpid = "";
        public String sdkToken = "";
        public String encrypted = "";
        public String step = "";
        public String error = "";
        /** 步骤轨迹（COW：批次线程 add 与 HTTP 线程 toPersistentView 迭代并发安全）。 */
        public final List<StepLog> steps = new CopyOnWriteArrayList<>();

        /**
         * 可持久化视图（任务书 §4）：凭据只出 masked（fpid 掩码），encrypted 是产物允许携带，
         * 明文 sdkToken / 验证码 / 身份证 / 密码一律不出现。
         */
        public JSONObject toPersistentView(String state) {
            JSONObject o = new JSONObject();
            o.put("phone", phone);
            o.put("state", state == null ? (ok ? "SUCCESS" : "FAILED") : state);
            o.put("error", error);
            o.put("fpidMasked", PassportIds.mask(fpid));
            o.put("encrypted", ok ? encrypted : JSONObject.NULL);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (StepLog s : steps) arr.put(s.toJson());
            o.put("steps", arr);
            return o;
        }
    }

    /** 批次配置（单号流程 + 批次 runner 共用）；{@code gamePassword} 只在内存，绝不落盘。 */
    public static final class PhoneBatchConfig {
        public String providerType = "";
        public Map<String, String> providerConfig = Collections.emptyMap();
        public String keyword = "";
        public int maxNumbers;
        public int goalCount;
        public int smsWaitSeconds = DEFAULT_SMS_WAIT_SECONDS;
        public long intervalMillis = DEFAULT_INTERVAL_MILLIS;
        /** 可空：非空时登录成功后执行实名。 */
        public RealNameInfo realName;
        /** 可空：游戏密码（不落盘、不进任何视图与日志）。 */
        public String gamePassword = "";

        /** 实名信息（内存期载体；持久化只允许出 realNameEnabled 布尔）。 */
        public static final class RealNameInfo {
            public final String name;
            public final String idCard;

            public RealNameInfo(String name, String idCard) {
                this.name = name == null ? "" : name.trim();
                this.idCard = idCard == null ? "" : idCard.trim();
            }
        }
    }

    // ---------------- 注入点 ----------------

    /** 接码平台工厂：生产包 {@link SmsProviders#create}，单测返回 fake provider。 */
    public interface SmsProviderFactory {
        SmsProvider create(String type, Map<String, String> config);
    }

    /** 可注入睡眠（轮询间隔 / 批次号间隔）：单测注入空实现跳时。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 真实睡眠（生产默认）。 */
    public static final Sleeper REAL_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    };

    // ---------------- 工具 ----------------

    private static PhoneResult fail(PhoneResult r, SmsProvider provider,
            SmsProvider.PhoneOrder order, String step, String error, boolean blockInstead) {
        r.ok = false;
        r.step = step;
        r.error = error;
        if (order != null && r.phone.isEmpty()) r.phone = order.phone;
        // 失败结果一律不带凭据（含 REAL_NAME 前登录已成功的场景）
        r.fpid = "";
        r.sdkToken = "";
        addStep(r.steps, step, false, error);
        if (order != null) {
            if (blockInstead) blockQuietly(provider, order, r.steps);
            else releaseQuietly(provider, order, r.steps);
        }
        FileLog.warn("手机号注册失败 phone={} step={} error={}",
                AccountArchive.mask(r.phone), step, error);
        return r;
    }

    private static void addStep(List<StepLog> steps, String step, boolean ok, String message) {
        steps.add(new StepLog(step, ok, message));
    }

    /** 尽力释放号码：失败只记一步 RELEASE ok=false，不影响主结论。 */
    private static void releaseQuietly(SmsProvider provider, SmsProvider.PhoneOrder order,
            List<StepLog> steps) {
        try {
            provider.release(order);
            addStep(steps, "RELEASE", true, "号码已释放");
        } catch (RuntimeException e) {
            addStep(steps, "RELEASE", false, "释放失败: " + safeMsg(e));
        }
    }

    /** 尽力拉黑号码：失败只记一步 BLOCK ok=false，不影响主结论。 */
    private static void blockQuietly(SmsProvider provider, SmsProvider.PhoneOrder order,
            List<StepLog> steps) {
        try {
            provider.block(order);
            addStep(steps, "BLOCK", true, "号码已拉黑");
        } catch (RuntimeException e) {
            addStep(steps, "BLOCK", false, "拉黑失败: " + safeMsg(e));
        }
    }

    /** 异常安全文本（null → 类名），用于失败分支消息。 */
    private static String safeMsg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
