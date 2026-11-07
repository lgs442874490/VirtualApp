package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.sms.D1SmsProvider;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RegistrationApi} 七 action 分发单测（任务书 §2.3）：fake service（脚本回放 passport +
 * 全内存接码）注入，批次经真实 executor 异步跑完（goal=1 快速路径，无真实 sleep）；
 * 参数校验中文错误；provider-test 注入假 D1 transport 防真实网络。
 *
 * <p>防串测：{@code RegistrationStore.shutdown()}/{@code RegistrationApi.uninstall()} 在
 * @Before/@After 调用（进程级单例），express 内存表每例清空，transport 供给恢复默认。
 */
public class RegistrationApiTest {

    private static final String OK_SENT = "{\"data\":{\"sent\":1}}";
    private static final String OK_GUEST =
            "{\"data\":{\"fpid\":\"FPAPI00001\",\"session_key\":\"SK-SECRET-0001\"}}";
    private static final String OK_LOGIN =
            "{\"data\":{\"fpid\":\"FPAPI10001\",\"session_key\":\"SK-SECRET-1001\"}}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        RegistrationStore.shutdown();
        RegistrationApi.uninstall();
        RegistrationApi.clearExpressBox();
        RegistrationApi.TRANSPORT_SUPPLIER = D1SmsProvider.HttpUrlConnectionTransport::new;
        RegistrationApi.BATCH_SLEEPER = Fakes.NO_SLEEP;
        RegistrationStore.install(tmp.getRoot().toPath());
    }

    @After
    public void tearDown() {
        RegistrationStore.shutdown();
        RegistrationApi.uninstall();
        RegistrationApi.clearExpressBox();
        RegistrationApi.TRANSPORT_SUPPLIER = D1SmsProvider.HttpUrlConnectionTransport::new;
        RegistrationApi.BATCH_SLEEPER = RegistrationService.REAL_SLEEPER;
    }

    /** 全成功 fake service：游客注册与单号登录都产出凭据（express 用脚本第 1 段）。 */
    private void installHappyService() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_GUEST, OK_SENT, OK_LOGIN),
                Fakes.factory(Fakes.FakeSms.single("13811112222", "6666")),
                Fakes.NO_SLEEP);
        RegistrationApi.install(svc);
    }

    /** 批次专用 fake service：脚本只含发码+登录（批次流程不经过 express）。 */
    private void installBatchService() {
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, OK_LOGIN),
                Fakes.factory(Fakes.FakeSms.single("13811112222", "6666")),
                Fakes.NO_SLEEP);
        RegistrationApi.install(svc);
    }

    private static JSONObject handle(Map<String, Object> fields) {
        return RegistrationApi.handle(fields);
    }

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------------- express / real-name ----------------

    @Test
    public void express_returnsCredentialOnceAndSealsInMemory() {
        installHappyService();
        JSONObject out = handle(f("action", "express"));
        assertFalse("不应有 error: " + out, out.has("error"));
        assertEquals("FPAPI00001", out.getString("fpid"));
        assertEquals("SK-SECRET-0001", out.getString("sdkToken"));
        assertFalse(out.getString("encrypted").isEmpty());
        // 凭据已入内存表：real-name 可用
        JSONObject rn = handle(f("action", "real-name", "fpid", "FPAPI00001",
                "name", "张三", "id_card", "110101199001011230"));
        assertFalse("实名应可用: " + rn, rn.has("error"));
        assertTrue(rn.optBoolean("realNameVerified"));
    }

    @Test
    public void realName_withoutCredential_hintsExpressFirst() {
        installHappyService();
        JSONObject rn = handle(f("action", "real-name", "fpid", "no-such-fpid",
                "name", "张三", "id_card", "110101199001011230"));
        assertTrue(rn.has("error"));
        assertTrue("错误应提示先 express/批次: " + rn.getString("error"),
                rn.getString("error").contains("express"));
    }

    @Test
    public void realName_badIdCard_chineseValidation() {
        installHappyService();
        JSONObject rn = handle(f("action", "real-name", "fpid", "x",
                "name", "张三", "id_card", "12345"));
        assertTrue(rn.has("error"));
        assertTrue(rn.getString("error").contains("18 位"));
    }

    // ---------------- provider-test ----------------

    @Test
    public void providerTest_okWithFakeTransport() {
        installHappyService();
        RegistrationApi.TRANSPORT_SUPPLIER = () -> url -> "9.99";   // 假 D1 余额响应
        JSONObject out = handle(f("action", "provider-test",
                "providerType", "d1", "providerConfig", "{\"token\":\"fake-tok\"}"));
        assertEquals("不应有 error: " + out, "", out.optString("error"));
        assertTrue(out.optBoolean("ok"));
        assertEquals("9.99", out.optString("balanceText"));
        assertEquals("fak***tok", out.optString("credentialMask"));
    }

    @Test
    public void providerTest_unknownType_chineseError() {
        installHappyService();
        JSONObject out = handle(f("action", "provider-test",
                "providerType", "foo", "providerConfig", "{\"token\":\"t\"}"));
        assertTrue(out.has("error"));
        assertTrue(out.getString("error").contains("d1 或 haozhuma"));
    }

    // ---------------- batch-start / status / cancel / list ----------------

    @Test
    public void batchStart_thenStatus_completesWithEncryptedOnSuccessEntry() throws Exception {
        installBatchService();
        JSONObject start = handle(f("action", "batch-start",
                "providerType", "d1",
                "providerConfig", "{\"token\":\"fake-tok\"}",
                "keyword", "测试", "goalCount", 1, "maxNumbers", 2, "smsWaitSeconds", 30));
        assertFalse("batch-start 不应有 error: " + start, start.has("error"));
        String batchId = start.getString("id");
        // HTTP 立即返回批次概要；executor 已开跑时可能是 RUNNING（两种都合法）
        assertTrue("state 应为 WAITING/RUNNING: " + start.optString("state"),
                "WAITING".equals(start.optString("state"))
                        || "RUNNING".equals(start.optString("state")));
        assertEquals(1, start.optInt("goalCount"));

        JSONObject status = awaitTerminal(batchId);
        assertEquals("COMPLETED", status.optString("state"));
        assertEquals(1, status.optInt("successCount"));
        JSONObject phone = status.optJSONArray("phones").getJSONObject(0);
        assertEquals("SUCCESS", phone.optString("state"));
        assertFalse("SUCCESS 条目应带 encrypted 产物", phone.optString("encrypted").isEmpty());
        assertTrue("凭据应 masked（含 ***）", phone.optString("fpidMasked").contains("***"));

        // 批次结束 → runner 移除、再 cancel 给「已结束」文案
        JSONObject cancel = handle(f("action", "batch-cancel", "batchId", batchId));
        assertFalse(cancel.optBoolean("ok"));
        assertEquals("批次已结束，无需取消", cancel.optString("message"));

        // list 摘要
        JSONObject list = handle(f("action", "batch-list"));
        assertTrue(list.optInt("count") >= 1);
        JSONObject summary = list.optJSONArray("batches").getJSONObject(0);
        assertEquals(batchId, summary.optString("id"));
        assertEquals("COMPLETED", summary.optString("state"));
        assertFalse("列表摘要不含 phones", summary.has("phones"));
    }

    @Test
    public void batchCancel_runningBatch_requestsCancel() throws Exception {
        Fakes.FakeSms slow = Fakes.FakeSms.single("13833334444", "6666");
        slow.fetchReached = new java.util.concurrent.CountDownLatch(1);
        slow.fetchGate = new java.util.concurrent.CountDownLatch(1);
        // 慢 provider 的 service：批次用慢号（收码挂在 gate 上等测试 cancel）
        RegistrationService svc = new RegistrationService(
                Fakes.scriptedPassport(OK_SENT, OK_LOGIN), Fakes.factory(slow), Fakes.NO_SLEEP);
        RegistrationApi.install(svc);

        JSONObject start = handle(f("action", "batch-start",
                "providerType", "d1",
                "providerConfig", "{\"token\":\"fake-tok\"}",
                "keyword", "测试", "goalCount", 1, "maxNumbers", 1, "smsWaitSeconds", 30));
        String batchId = start.getString("id");
        assertTrue("应等到批次进入收码", slow.fetchReached.await(5,
                java.util.concurrent.TimeUnit.SECONDS));

        JSONObject cancel = handle(f("action", "batch-cancel", "batchId", batchId));
        assertTrue("运行中取消应受理: " + cancel, cancel.optBoolean("ok"));
        slow.fetchGate.countDown();   // 放行当前号自然结束

        JSONObject status = awaitTerminal(batchId);
        assertEquals("CANCELLED", status.optString("state"));
    }

    @Test
    public void batchStatusAndCancel_unknownId_chineseError() {
        installHappyService();
        JSONObject status = handle(f("action", "batch-status", "batchId", "ghost"));
        assertTrue(status.has("error"));
        assertTrue(status.getString("error").contains("批次不存在"));
        JSONObject cancel = handle(f("action", "batch-cancel", "batchId", "ghost"));
        assertTrue(cancel.has("error"));
    }

    // ---------------- 参数校验（batch-start） ----------------

    @Test
    public void batchStart_validationErrors_allChinese() {
        installHappyService();
        String[][] bad = {
                // {字段覆盖, 期望错误关键词}
                {"goalCount", "0"},
                {"goalCount", "51"},
        };
        for (String[] b : bad) {
            Map<String, Object> fields = baseBatchFields();
            fields.put(b[0], b[1]);
            JSONObject out = handle(fields);
            assertTrue("goalCount 校验应报错: " + out, out.has("error"));
        }
        // maxNumbers < goalCount
        Map<String, Object> m1 = baseBatchFields();
        m1.put("maxNumbers", 1);
        assertTrue(handle(m1).has("error"));
        // maxNumbers > 100
        Map<String, Object> m2 = baseBatchFields();
        m2.put("maxNumbers", 101);
        assertTrue(handle(m2).has("error"));
        // realName.idCard 非 18 位
        Map<String, Object> m3 = baseBatchFields();
        m3.put("realName", "{\"name\":\"张三\",\"idCard\":\"123\"}");
        JSONObject out3 = handle(m3);
        assertTrue(out3.has("error"));
        assertTrue(out3.getString("error").contains("18 位"));
        // gamePassword 过短
        Map<String, Object> m4 = baseBatchFields();
        m4.put("gamePassword", "abc");
        assertTrue(handle(m4).optString("error").contains("6 位"));
        // providerConfig 非法 JSON
        Map<String, Object> m5 = baseBatchFields();
        m5.put("providerConfig", "not-json");
        assertTrue(handle(m5).has("error"));
        // 缺 providerType
        Map<String, Object> m6 = baseBatchFields();
        m6.remove("providerType");
        assertTrue(handle(m6).has("error"));
    }

    @Test
    public void unknownAction_andNoService_chineseErrors() {
        installHappyService();
        JSONObject unknown = handle(f("action", "bogus"));
        assertTrue(unknown.has("error"));
        assertTrue(unknown.getString("error").contains("未知 action"));

        RegistrationApi.uninstall();   // 清掉服务：业务动作报「未安装」
        JSONObject noSvc = handle(f("action", "express"));
        assertTrue(noSvc.has("error"));
        assertTrue(noSvc.getString("error").contains("未安装"));
    }

    // ---------------- 工具 ----------------

    private static Map<String, Object> baseBatchFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "batch-start");
        m.put("providerType", "d1");
        m.put("providerConfig", "{\"token\":\"fake-tok\"}");
        m.put("keyword", "测试");
        m.put("goalCount", 2);
        m.put("maxNumbers", 5);
        return m;
    }

    /** 轮询 batch-status 直到非 WAITING/RUNNING（≤5s）。 */
    private static JSONObject awaitTerminal(String batchId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        JSONObject status = null;
        while (System.currentTimeMillis() < deadline) {
            status = handle(f("action", "batch-status", "batchId", batchId));
            String st = status.optString("state", "");
            if (!"WAITING".equals(st) && !"RUNNING".equals(st)) return status;
            Thread.sleep(20);
        }
        assertNotNull("批次应在 5s 内到达终态", status);
        return status;
    }
}
