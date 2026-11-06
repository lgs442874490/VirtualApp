package com.carlos.vaultserver.migrant;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MigrantApi#handle} 参数校验 / 权限 / BizError 语义回归（契约 §3.14）：
 * 缺 action / 缺 fpid / 账号不存在 / 越权角色 / 任务不属于该账号 一律走 BizError。
 */
public class MigrantApiTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private VaultStore store;
    private MigrantJobStore jobs;
    private MigrantApi api;

    @Before
    public void setUp() throws Exception {
        Path dir = folder.newFolder("data").toPath();
        store = new VaultStore(dir);
        store.load();
        VaultStore.Account a1 = new VaultStore.Account("f1");
        a1.label = "账号一";
        a1.token = "t1";
        VaultStore.RoleEntry r1 = new VaultStore.RoleEntry("888", "1001");
        r1.name = "角色甲";
        a1.roles.put(VaultStore.roleKey("888", "1001"), r1);
        VaultStore.Account a2 = new VaultStore.Account("f2");
        a2.label = "账号二";
        a2.token = "t2";
        VaultStore.RoleEntry r2 = new VaultStore.RoleEntry("999", "2002");
        r2.name = "角色乙";
        a2.roles.put(VaultStore.roleKey("999", "2002"), r2);
        store.replaceAll(Arrays.asList(a1, a2));

        StateStore stateStore = new StateStore(dir);
        stateStore.load();
        jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, store, jobs, stateStore);
        api = new MigrantApi(null, store, stateStore, jobs, engine);
    }

    private Map<String, Object> fields(Object... kv) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            fields.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return fields;
    }

    private MigrantApi.BizError expectBiz(Map<String, Object> fields) {
        try {
            api.handle(fields);
            fail("应当抛 BizError");
            return null;
        } catch (MigrantApi.BizError e) {
            return e;
        }
    }

    @Test
    public void missingAction() {
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1"));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("action"));
    }

    @Test
    public void missingFpid() {
        MigrantApi.BizError e = expectBiz(fields("action", "jobs"));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("fpid"));
    }

    @Test
    public void unknownFpid() {
        MigrantApi.BizError e = expectBiz(fields("fpid", "nope", "action", "jobs"));
        assertEquals(1004, e.code);
        assertTrue(e.getMessage().contains("账号不存在"));
    }

    @Test
    public void unknownAction() {
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "explode"));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("未知的移民 action"));
    }

    @Test
    public void precheckRequiresRoleId() {
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "precheck",
                "target_kid", 1001));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("请选择移民账号"));
    }

    @Test
    public void precheckOfflineRoleIs1004() {
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "precheck",
                "account_role_id", 1001, "target_kid", 1001));
        assertEquals(1004, e.code);
        assertTrue(e.getMessage().contains("未上线"));
    }

    @Test
    public void roleOfOtherAccountIs1004() {
        // f1 账号下不存在 uid=2002 的角色
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "precheck",
                "account_role_id", 2002, "target_kid", 1001));
        assertEquals(1004, e.code);
        assertTrue(e.getMessage().contains("角色不存在"));
    }

    @Test
    public void accountsListsOnlyRequestedFpidRoles() {
        JSONObject out = api.handle(fields("fpid", "f1", "action", "accounts"));
        assertNotNull(out);
        org.json.JSONArray accounts = out.getJSONArray("accounts");
        assertEquals("P2-5：只返回本 fpid 的角色", 1, accounts.length());
        JSONObject first = accounts.getJSONObject(0);
        assertEquals(1001L, first.getLong("accountRoleId"));
        assertEquals("角色甲", first.getString("game_name"));
        assertEquals(888L, first.getLong("server_id"));
        assertFalse("未注入 SessionKeeper → offline", first.getBoolean("online"));

        JSONObject other = api.handle(fields("fpid", "f2", "action", "accounts"));
        assertEquals(1, other.getJSONArray("accounts").length());
        assertEquals(2002L, other.getJSONArray("accounts").getJSONObject(0).getLong("accountRoleId"));
    }

    @Test
    public void jobsAndJobDetailScopeByFpid() {
        MigrantJob job = MigrantTestSupport.job("job-1", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        jobs.put(job);

        JSONObject out = api.handle(fields("fpid", "f1", "action", "jobs"));
        assertEquals(1, out.getJSONArray("active").length());
        assertEquals(0, out.getJSONArray("finished").length());

        JSONObject detail = api.handle(fields("fpid", "f1", "action", "job", "job_id", "job-1"));
        assertEquals("job-1", detail.getString("job_id"));

        MigrantApi.BizError denied = expectBiz(fields("fpid", "f2", "action", "job",
                "job_id", "job-1"));
        assertEquals(1004, denied.code);
        assertTrue(denied.getMessage().contains("角色不存在"));
    }

    @Test
    public void missingJobIdAndUnknownJob() {
        MigrantApi.BizError missing = expectBiz(fields("fpid", "f1", "action", "cancel"));
        assertEquals(3002, missing.code);
        assertTrue(missing.getMessage().contains("job_id"));

        MigrantApi.BizError unknown = expectBiz(fields("fpid", "f1", "action", "attempts",
                "job_id", "nope"));
        assertEquals(1004, unknown.code);
        assertTrue(unknown.getMessage().contains("任务不存在"));
    }

    @Test
    public void cancelOnlyWaiting() {
        MigrantJob preparing = MigrantTestSupport.job("job-prep", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        preparing.status = MigrantJob.PREPARING;
        jobs.put(preparing);
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "cancel",
                "job_id", "job-prep"));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("不能取消"));

        MigrantJob waiting = MigrantTestSupport.job("job-wait", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        jobs.put(waiting);
        JSONObject out = api.handle(fields("fpid", "f1", "action", "cancel",
                "job_id", "job-wait"));
        assertEquals("移民任务已取消", out.getString("message"));
        assertEquals(MigrantJob.CANCELLED, waiting.status);
        assertTrue(MigrantJob.isTerminal(waiting.status));
    }

    @Test
    public void recallValidatesMode() {
        MigrantJob job = MigrantTestSupport.job("job-r", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        jobs.put(job);
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "recall",
                "job_id", "job-r", "march_id", 1, "mode", "laser"));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("item 或 diamond"));
    }

    @Test
    public void recallOfflineRoleFailsWithChineseMessage() {
        MigrantJob job = MigrantTestSupport.job("job-r2", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        jobs.put(job);
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "recall",
                "job_id", "job-r2", "march_id", 0, "mode", "item"));
        assertTrue("离线召回应给出可读原因", e.getMessage().contains("未就绪")
                || e.getMessage().contains("未上线"));
    }

    @Test
    public void marchesOfflineReturnsOnlineFalse() {
        MigrantJob job = MigrantTestSupport.job("job-m", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        jobs.put(job);
        JSONObject out = api.handle(fields("fpid", "f1", "action", "marches",
                "job_id", "job-m"));
        assertFalse(out.getBoolean("online"));
        assertFalse(out.getBoolean("manual_recall_allowed"));
        assertTrue(out.getString("manual_recall_block_reason").contains("未上线"));
        assertEquals(0, out.getJSONArray("marches").length());
    }

    @Test
    public void overviewOfflineReturnsOnlineFalse() {
        // 2026-11-06 裁决：信封会把 null 兜成 {}，App 无法区分离线/空数据 →
        // 离线统一显式返回 {online:false, message}。
        JSONObject out = api.handle(fields("fpid", "f1", "action", "overview",
                "account_role_id", 1001));
        assertNotNull(out);
        assertFalse("离线必须显式 online=false", out.getBoolean("online"));
        assertTrue("离线要带可读原因", out.getString("message").contains("未上线"));
    }

    @Test
    public void bulkValidatesTargetAndRoleIds() {
        MigrantApi.BizError noTarget = expectBiz(fields("fpid", "f1", "action", "bulk",
                "account_role_ids", java.util.Collections.singletonList(1001)));
        assertEquals(3002, noTarget.code);
        assertTrue(noTarget.getMessage().contains("请选择目标王国"));

        MigrantApi.BizError noRoles = expectBiz(fields("fpid", "f1", "action", "bulk",
                "target_kid", 1001));
        assertEquals(3002, noRoles.code);
        assertTrue(noRoles.getMessage().contains("请选择移民账号"));
    }

    @Test
    public void bulkIsolatesPerRoleFailures() {
        JSONObject out = api.handle(fields("fpid", "f1", "action", "bulk",
                "target_kid", 1001, "account_role_ids", java.util.Arrays.asList(1001, 9999)));
        assertEquals(2, out.getInt("requested"));
        assertEquals(0, out.getJSONArray("created").length());
        assertEquals(2, out.getJSONArray("failed").length());
        JSONObject first = out.getJSONArray("failed").getJSONObject(0);
        assertEquals(1001L, first.getLong("account_role_id"));
        assertTrue(first.getString("message").contains("未上线"));
    }

    @Test
    public void loginWithoutKeeperFailsGracefully() {
        MigrantApi.BizError e = expectBiz(fields("fpid", "f1", "action", "login",
                "account_role_id", 1001));
        assertEquals(3002, e.code);
        assertTrue(e.getMessage().contains("会话管理器未就绪"));
    }
}
