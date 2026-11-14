package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.passport.PassportIds;
import com.carlos.vaultserver.registration.RegistrationService.PhoneBatchConfig;
import com.carlos.vaultserver.registration.RegistrationService.PhoneResult;
import com.carlos.vaultserver.registration.RegistrationService.RegistrationOutcome;
import com.carlos.vaultserver.sms.SmsException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RegistrationService} 编排单测（任务书 §2.3）：fake PassportFacade（脚本回放）+
 * fake SmsProvider + 可注入 Sleeper 跳时——零真实网络。
 *
 * <p>覆盖：guestRegister 成功/失败（errorText 透出）、realName 本地校验 + 透传、
 * registerOnePhone 全链路成功（轨迹完整 + release）、超时未到码（block + 跳时 + 「未收到」）、
 * smsLogin 失败（release）、取号失败。
 */
public class RegistrationServiceTest {

    private static final String OK_GUEST = "{\"data\":{\"fpid\":\"FP888\",\"session_key\":\"SK888\"}}";
    private static final String OK_SENT = "{\"data\":{\"sent\":1}}";
    private static final String OK_LOGIN = "{\"data\":{\"fpid\":\"FP900\",\"session_key\":\"SK900\"}}";

    // ---------------- guestRegister ----------------

    @Test
    public void guestRegister_success() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_GUEST), Fakes.factory(Fakes.FakeSms.single("1", "2")),
                Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.guestRegister();
        assertEquals("FP888", o.fpid);
        assertEquals("SK888", o.sdkToken);
        assertFalse(o.encrypted.isEmpty());
        String[] parts = RegistrationService.parseEncrypted(o.encrypted);
        assertEquals("FP888", parts[0]);
        assertEquals("SK888", parts[1]);
        assertTrue("deviceJson 应含 account=fpid", parts[2].contains("\"account\":\"FP888\""));
        assertTrue(o.createdAt > 0);
    }

    @Test
    public void guestRegister_upstreamError_surfacesErrorText() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"error\":\"风控拒绝\"}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        try {
            svc.guestRegister();
            fail("上游业务失败应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue("错误消息应透出 errorText: " + e.getMessage(),
                    e.getMessage().contains("风控拒绝"));
            assertFalse("错误消息不含凭据", e.getMessage().contains("SK888"));
        }
    }

    @Test
    public void guestRegister_missingCredentials_fails() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{}"),   // 上游返回空 → fpid/sk 缺失
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        try {
            svc.guestRegister();
            fail("凭据缺失应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("游客注册失败"));
        }
    }

    @Test
    public void guestRegister_networkFailure_wrapsAsIllegalState() {
        RegistrationService svc = new RegistrationService(
                Fakes.brokenPassport(), Fakes.factory(Fakes.FakeSms.single("1", "2")),
                Fakes.NO_SLEEP);
        try {
            svc.guestRegister();
            fail("网络失败应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("网络失败"));
        }
    }

    // ---------------- realName ----------------

    @Test
    public void realName_localReject_doesNotCallUpstream() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(),       // 脚本空：若发起网络调用会返回 {}（断言不会走到）
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "12345");
        assertFalse(o.realNameVerified);
        assertTrue("本地校验消息: " + o.realNameMessage,
                o.realNameMessage.contains("身份证号格式不正确"));
    }

    @Test
    public void realName_passthroughSuccessAndFailure() {
        RegistrationService ok = new RegistrationService(
                Fakes.scriptedPassport("{\"data\":{\"result\":1}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome good = ok.realName("FP", "SK", "张三",
                "110101199001011230");
        assertTrue(good.realNameVerified);

        RegistrationService bad = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":1,\"data\":{\"code\":3,\"msg\":\"身份证号与姓名不匹配\"}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome reject = bad.realName("FP", "SK", "张三",
                "110101199001011230");
        assertFalse(reject.realNameVerified);
        assertEquals("身份证号与姓名不匹配", reject.realNameMessage);
    }

    // P1-1（2026-11-13 实跑）：上游口径是「外层 status + data.code」，不是 data.status / data.error。
    // 网页版 PhoneRegistrationPassportGateway.realNameOutcome：status=1&code=1 通过；
    // status=1&code=2 / code=2406 / msg 含「已实名…」按已实名通过；其余失败。

    @Test
    public void realName_status1Code1_verified() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":1,\"data\":{\"code\":1,\"msg\":\"实名认证成功\"}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "110101199001011230");
        assertTrue(o.realNameVerified);
        assertEquals("实名认证成功", o.realNameMessage);
    }

    /** 实跑原样响应：认证中（code=2）必须视为通过，否则同一身份第二次使用整号被判失败。 */
    @Test
    public void realName_status1Code2_authenticating_passes() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":1,\"data\":{\"code\":2,"
                        + "\"msg\":\"实名认证中，您可继续体验游戏!\"}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "110101199001011230");
        assertTrue("code=2 认证中必须通过: " + o.realNameMessage, o.realNameVerified);
        assertEquals("实名认证中，您可继续体验游戏!", o.realNameMessage);
    }

    @Test
    public void realName_code2406_alreadyVerified_passes() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":1,\"data\":{\"code\":2406}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "110101199001011230");
        assertTrue("code=2406 已实名必须通过", o.realNameVerified);
    }

    @Test
    public void realName_msgAlreadyVerified_passes() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":1,\"data\":{\"code\":9,"
                        + "\"msg\":\"该账号已通过实名认证，不能重复认证\"}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "110101199001011230");
        assertTrue("msg 含已实名必须通过: " + o.realNameMessage, o.realNameVerified);
    }

    @Test
    public void realName_status1Code3_realFailure_fails() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":1,\"data\":{\"code\":3,\"msg\":\"实名认证失败\"}}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "110101199001011230");
        assertFalse("真失败必须判失败", o.realNameVerified);
        assertEquals("实名认证失败", o.realNameMessage);
    }

    @Test
    public void realName_outerStatus0_fails() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport("{\"status\":0,\"error\":\"风控拒绝\"}"),
                Fakes.factory(Fakes.FakeSms.single("1", "2")), Fakes.NO_SLEEP);
        RegistrationOutcome o = svc.realName("FP", "SK", "张三", "110101199001011230");
        assertFalse(o.realNameVerified);
        assertTrue("失败消息应透出上游 error: " + o.realNameMessage,
                o.realNameMessage.contains("风控拒绝"));
    }

    // ---------------- registerOnePhone ----------------

    /** 全链路成功（脚本序：sendSms → smsLogin）。 */
    @Test
    public void registerOnePhone_fullChainSuccess() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800001111", "4321");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, OK_LOGIN),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        CredentialBox box = new CredentialBox();
        PhoneResult r = svc.registerOnePhone(Fakes.cfg(1, 1), box);

        assertTrue("应成功: " + r.error, r.ok);
        assertEquals("13800001111", r.phone);
        assertEquals("FP900", r.fpid);
        assertEquals("SK900", r.sdkToken);
        assertFalse(r.encrypted.isEmpty());
        // 步骤轨迹完整且全 ok
        List<String> names = new ArrayList<>();
        for (RegistrationService.StepLog s : r.steps) {
            names.add(s.step + (s.ok ? "+" : "-"));
        }
        assertEquals("[ACQUIRE+, SEND_SMS+, WAIT_SMS+, SMS_LOGIN+, RELEASE+]",
                names.toString());
        // 成功路径 release（不 block）
        assertEquals(List.of("13800001111"), sms.released);
        assertTrue(sms.blocked.isEmpty());
        // 凭据入箱
        assertTrue(box.has("FP900"));
        assertEquals("SK900", box.take("FP900").get("sdkToken"));
    }

    @Test
    public void registerOnePhone_smsTimeout_blocksAndReportsNotReceived() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800002222", "4321");
        sms.waitingRounds = Integer.MAX_VALUE;   // 永不到码
        Fakes.RecordingSleeper sleeper = new Fakes.RecordingSleeper();
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT), // smsLogin 不会走到
                Fakes.factory(sms), sleeper);
        PhoneBatchConfig cfg = Fakes.cfg(1, 1);
        cfg.smsWaitSeconds = 12;
        cfg.intervalMillis = 5_000L;
        PhoneResult r = svc.registerOnePhone(cfg, new CredentialBox());

        assertFalse(r.ok);
        assertEquals("WAIT_SMS", r.step);
        assertTrue("失败 error 应含「未收到」: " + r.error, r.error.contains("未收到"));
        assertEquals("超时应拉黑", List.of("13800002222"), sms.blocked);
        assertTrue("超时不应 release", sms.released.isEmpty());
        // 跳时注入生效：预算 12s / 间隔 5s → 2 次间隔等待，真实耗时为 0
        assertEquals(List.of(5_000L, 5_000L), sleeper.sleeps);
        // BLOCK 步骤记录在案
        assertTrue(r.steps.stream().anyMatch(s -> "BLOCK".equals(s.step) && s.ok));
        assertTrue("失败结果不带凭据", r.fpid.isEmpty());
    }

    /**
     * 真实轮回归（2026-11-10）：接码平台号池复用号码会残留旧短信，实现必须跳过旧码只认新码，
     * 否则会把旧码提交给游戏 → 上游「验证码验证失败」。
     */
    @Test
    public void staleCode_isSkipped_newCodeIsUsed() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("19331521464", "2222");
        sms.staleCode = "1111";   // 号池残留旧码
        Fakes.RecordingSleeper sleeper = new Fakes.RecordingSleeper();
        List<Map<String, String>> forms = new ArrayList<>();
        RegistrationService svc = new RegistrationService(
                Fakes.capturingPassport(forms, OK_SENT, OK_LOGIN), Fakes.factory(sms), sleeper);

        PhoneResult r = svc.registerOnePhone(Fakes.cfg(1, 1), new CredentialBox());

        assertTrue("应成功: " + r.error, r.ok);
        assertEquals("FP900", r.fpid);
        // 第 1 个表单是发码、第 2 个是登录；登录提交的必须是新码 2222，绝不能是旧码 1111
        assertEquals("应发码+登录两次请求", 2, forms.size());
        assertEquals("2222", forms.get(1).get("code"));
    }

    /** 只有旧码、始终没有新码 → 应超时失败且提示「仅见旧验证码」，不得把旧码当本次验证码。 */
    @Test
    public void onlyStaleCode_timesOutWithHint() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("19331521464", "2222");
        sms.staleCode = "1111";
        sms.waitingRounds = Integer.MAX_VALUE;
        Fakes.RecordingSleeper sleeper = new Fakes.RecordingSleeper();
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT), Fakes.factory(sms), sleeper);
        PhoneBatchConfig cfg = Fakes.cfg(1, 1);
        cfg.smsWaitSeconds = 12;
        cfg.intervalMillis = 5_000L;

        PhoneResult r = svc.registerOnePhone(cfg, new CredentialBox());

        assertFalse(r.ok);
        assertEquals("WAIT_SMS", r.step);
        assertTrue("应提示仅见旧验证码: " + r.error, r.error.contains("旧验证码"));
    }

    @Test
    public void registerOnePhone_smsLoginFails_releases() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800003333", "4321");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, "{\"error\":\"验证码错误\"}"),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneResult r = svc.registerOnePhone(Fakes.cfg(1, 1), new CredentialBox());

        assertFalse(r.ok);
        assertEquals("SMS_LOGIN", r.step);
        assertTrue(r.error.contains("验证码错误"));
        assertEquals("登录失败应 release", List.of("13800003333"), sms.released);
        assertTrue(sms.blocked.isEmpty());
        assertFalse("失败结果不得带凭据", r.steps.stream()
                .anyMatch(s -> "SMS_LOGIN".equals(s.step) && s.ok));
        assertTrue(r.fpid.isEmpty() && r.sdkToken.isEmpty());
    }

    @Test
    public void registerOnePhone_acquireFails_noReleaseNoBlock() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800004444", "4321");
        sms.acquireError = new SmsException("余额不足");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(), Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneResult r = svc.registerOnePhone(Fakes.cfg(1, 1), new CredentialBox());

        assertFalse(r.ok);
        assertEquals("ACQUIRE", r.step);
        assertTrue(r.error.contains("取号失败"));
        assertTrue(r.error.contains("余额不足"));
        assertTrue(sms.released.isEmpty() && sms.blocked.isEmpty());
    }

    @Test
    public void registerOnePhone_realNameLocalReject_failsWithRelease() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800005555", "4321");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, OK_LOGIN),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneBatchConfig cfg = Fakes.cfg(1, 1);
        cfg.realName = new PhoneBatchConfig.RealNameInfo("张三", "bad-id-card");
        PhoneResult r = svc.registerOnePhone(cfg, new CredentialBox());

        assertFalse(r.ok);
        assertEquals("REAL_NAME", r.step);
        assertTrue("实名校验本地拒绝: " + r.error, r.error.contains("实名校验未通过"));
        assertTrue(r.fpid.isEmpty() && r.sdkToken.isEmpty());
        assertEquals("号码归还", List.of("13800005555"), sms.released);
        // 轨迹里 SMS_LOGIN 曾成功，但凭据不外带
        assertTrue(r.steps.stream().anyMatch(s -> "SMS_LOGIN".equals(s.step) && s.ok));
    }

    @Test
    public void registerOnePhone_maskedErrorInSteps_neverPlaintextCode() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800006666", "998877");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, OK_LOGIN),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneResult r = svc.registerOnePhone(Fakes.cfg(1, 1), new CredentialBox());
        assertTrue(r.ok);
        String dumped = String.valueOf(r.toPersistentView("SUCCESS"));
        assertFalse("持久化视图绝不含验证码明文", dumped.contains("998877"));
        assertFalse("持久化视图绝不含 sdkToken 明文", dumped.contains("SK900"));
        assertTrue("视图含 fpid 掩码", dumped.contains(PassportIds.mask("FP900")));
    }

    // ---------------- 并发视图安全（P1-1 回归） ----------------

    /** 批次线程 addStep 与 HTTP 线程 toPersistentView（steps 迭代）并发：迭代期 add 不抛 CME。 */
    @Test
    public void toPersistentView_safeUnderConcurrentStepsAdd() throws Exception {
        PhoneResult r = new PhoneResult();
        java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                r.steps.add(new RegistrationService.StepLog("WAIT_SMS", true, "第 " + i + " 轮"));
            }
            done.set(true);
        });
        writer.start();
        int reads = 0;
        while (!done.get()) {
            // 写入窗口内反复迭代建视图：不抛 CME 即通过（初始窗口 steps 可为空，不校验内容）
            r.toPersistentView("RUNNING");
            reads++;
        }
        writer.join();
        assertEquals(20_000, r.steps.size());
        assertTrue("写入窗口内完成多次视图迭代", reads > 0);
        assertEquals("全部写完后视图含完整步骤", 20_000,
                r.toPersistentView("SUCCESS").optJSONArray("steps").length());
    }
}
