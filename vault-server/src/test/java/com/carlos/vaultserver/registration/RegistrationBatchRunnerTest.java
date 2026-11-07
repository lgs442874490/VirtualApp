package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.registration.RegistrationService.PhoneBatchConfig;
import com.carlos.vaultserver.registration.RegistrationService.PhoneResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RegistrationBatchRunner} 批次状态机单测（任务书 §2.3）：goal 达成即停、全失败 → FAILED、
 * cancel 当前号自然结束后置 CANCELLED、终态凭据箱 cleared、持久化视图不含明文。
 */
public class RegistrationBatchRunnerTest {

    private static final String OK_SENT = "{\"data\":{\"sent\":1}}";
    private static final String SECRET_TOKEN = "SECRET-BATCH-TOKEN-XYZ";
    private static final String SECRET_ID_CARD = "11010119900101123X";
    private static final String SECRET_PASSWORD = "plain-pass-9";

    private static String okLogin(String fpid) {
        return "{\"data\":{\"fpid\":\"" + fpid + "\",\"session_key\":\"" + SECRET_TOKEN + "\"}}";
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RegistrationStore newStore() {
        return new RegistrationStore(tmp.getRoot().toPath());
    }

    /** goal=2 / max=3 全成功：2 成功即停（尝试 2），第 3 槽位保持 PENDING，终态箱已清。 */
    @Test
    public void goalReachedStopsEarly() {
        Fakes.FakeSms sms = new Fakes.FakeSms(
                List.of("13800010001", "13800010002", "13800010003"),
                List.of("1111", "2222", "3333"));
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, okLogin("FP01"),
                        OK_SENT, okLogin("FP02")),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneBatchConfig cfg = Fakes.cfg(2, 3);
        CredentialBox box = new CredentialBox();
        RegistrationStore store = newStore();
        RegistrationBatchRunner runner = new RegistrationBatchRunner(cfg, svc, store, box,
                Fakes.NO_SLEEP);
        runner.run();

        assertEquals("COMPLETED", runner.state());
        assertEquals(2, runner.successCount());
        assertEquals(0, runner.failCount());
        assertEquals(2, runner.attemptCount());
        assertEquals("SUCCESS", runner.entry(0).state.name());
        assertEquals("SUCCESS", runner.entry(1).state.name());
        assertEquals("PENDING", runner.entry(2).state.name());
        assertEquals("1 次释放/号", List.of("13800010001", "13800010002"), sms.released);
        assertTrue("终态凭据箱应清空", box.size() == 0);
        // 落盘视图可查
        assertEquals("COMPLETED", store.find(runner.id).optString("state"));
    }

    /** 全失败（取号即抛）→ FAILED（0 成功、尝试 = maxNumbers）。 */
    @Test
    public void allFail_fails() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800020001", "1111");
        sms.acquireError = new com.carlos.vaultserver.sms.SmsException("号码池已空");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(), Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneBatchConfig cfg = Fakes.cfg(1, 2);
        RegistrationBatchRunner runner = new RegistrationBatchRunner(cfg, svc, newStore(),
                new CredentialBox(), Fakes.NO_SLEEP);
        runner.run();

        assertEquals("FAILED", runner.state());
        assertEquals(0, runner.successCount());
        assertEquals(2, runner.failCount());
        assertEquals(2, runner.attemptCount());
        assertEquals("FAILED", storeState(runner));
    }

    /** 号码用尽部分成功 → COMPLETED（部分成功语义）。 */
    @Test
    public void numbersExhaustedWithPartialSuccess_completes() {
        Fakes.FakeSms sms = new Fakes.FakeSms(
                List.of("13800030001", "13800030002"), List.of("1111", "2222"));
        // 第 2 号登录失败（脚本耗尽返回 {} → 无凭据）
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, okLogin("FP03"), OK_SENT),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        RegistrationBatchRunner runner = new RegistrationBatchRunner(Fakes.cfg(2, 2), svc,
                newStore(), new CredentialBox(), Fakes.NO_SLEEP);
        runner.run();

        assertEquals("COMPLETED", runner.state());
        assertEquals(1, runner.successCount());
        assertEquals(1, runner.failCount());
        assertEquals("FAILED", runner.entry(1).state.name());
    }

    /**
     * cancel：第一次 fetchSms 到达时（当前号进行中）发出取消 → 当前号自然结束（成功）后停，
     * 置 CANCELLED，不再开下一号。
     */
    @Test
    public void cancel_stopsAfterCurrentPhone() throws Exception {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800040001", "1111");
        sms.fetchReached = new CountDownLatch(1);
        sms.fetchGate = new CountDownLatch(1);
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, okLogin("FP04")),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        RegistrationBatchRunner runner = new RegistrationBatchRunner(Fakes.cfg(2, 3), svc,
                newStore(), new CredentialBox(), Fakes.NO_SLEEP);

        Thread t = new Thread(runner::run, "runner-cancel-test");
        t.start();
        assertTrue("应等到第一号进入收码轮询", sms.fetchReached.await(5, TimeUnit.SECONDS));
        runner.cancel();                       // 当前号进行中请求取消
        sms.fetchGate.countDown();             // 放行 → 当前号自然走完（成功）
        t.join(5_000);
        assertTrue("runner 应已结束", !t.isAlive());

        assertEquals("CANCELLED", runner.state());
        assertEquals("当前号自然完成后停：尝试 1", 1, runner.attemptCount());
        assertEquals(1, runner.successCount());
        assertEquals("SUCCESS", runner.entry(0).state.name());
        assertEquals("第二号保持 PENDING", "PENDING", runner.entry(1).state.name());
        assertTrue("终态箱清空", runner.box().size() == 0);
    }

    /** 持久化视图与落盘文件全文不含明文凭据（token / 身份证 / 密码）。 */
    @Test
    public void persistentView_containsNoPlaintext() throws Exception {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800050001", "778899");
        String fpid = "FP055000123";   // 掩码形态 FP0***123
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, okLogin(fpid)),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        PhoneBatchConfig cfg = Fakes.cfg(1, 2);
        cfg.realName = new PhoneBatchConfig.RealNameInfo("张三", SECRET_ID_CARD);
        cfg.gamePassword = SECRET_PASSWORD;
        RegistrationStore store = newStore();
        RegistrationBatchRunner runner = new RegistrationBatchRunner(cfg, svc, store,
                new CredentialBox(), Fakes.NO_SLEEP);
        runner.run();

        String viewJson = runner.view().toString();
        for (String secret : new String[]{SECRET_TOKEN, SECRET_ID_CARD, SECRET_PASSWORD, "778899"}) {
            assertTrue("视图不应含明文 " + secret + " -> " + viewJson, !viewJson.contains(secret));
        }
        assertTrue("视图含 fpid 掩码形态", viewJson.contains("FP0***123"));

        Path file = tmp.getRoot().toPath().resolve("registration_batches.json");
        assertTrue("落盘文件应存在", Files.isRegularFile(file));
        String fileText = Files.readString(file, StandardCharsets.UTF_8);
        for (String secret : new String[]{SECRET_TOKEN, SECRET_ID_CARD, SECRET_PASSWORD, "778899"}) {
            assertTrue("落盘文件不应含明文 " + secret, !fileText.contains(secret));
        }
    }

    /** WAITING 未起跑即 cancel → 直接 CANCELLED（0 尝试）。 */
    @Test
    public void cancelBeforeRun_cancelledWithoutAttempt() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800060001", "1111");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, okLogin("FP06")),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        RegistrationBatchRunner runner = new RegistrationBatchRunner(Fakes.cfg(1, 1), svc,
                newStore(), new CredentialBox(), Fakes.NO_SLEEP);
        runner.cancel();
        runner.run();
        assertEquals("CANCELLED", runner.state());
        assertEquals(0, runner.attemptCount());
        assertEquals(0, sms.acquired.size());
    }

    /** runner.run 幂等防重入：终态后再 run 不变。 */
    @Test
    public void runIsIdempotentAfterTerminal() {
        Fakes.FakeSms sms = Fakes.FakeSms.single("13800070001", "1111");
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, okLogin("FP07")),
                Fakes.factory(sms), Fakes.NO_SLEEP);
        RegistrationBatchRunner runner = new RegistrationBatchRunner(Fakes.cfg(1, 1), svc,
                newStore(), new CredentialBox(), Fakes.NO_SLEEP);
        runner.run();
        String id = runner.id;
        runner.run();
        assertEquals("COMPLETED", runner.state());
        assertEquals("批次 id 不变", id, runner.id);
        assertEquals("尝试数不因重跑增加", 1, runner.attemptCount());
    }

    private String storeState(RegistrationBatchRunner runner) {
        // 独立 store 实例的批次视图已在 append/update 落内存 + 文件；从文件回读断言
        try {
            Path file = tmp.getRoot().toPath().resolve("registration_batches.json");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(text);
            org.json.JSONArray arr = root.optJSONArray("batches");
            for (int i = 0; i < arr.length(); i++) {
                if (runner.id.equals(arr.getJSONObject(i).optString("id"))) {
                    return arr.getJSONObject(i).optString("state");
                }
            }
            fail("落盘应含本批次: " + runner.id);
            return "";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
