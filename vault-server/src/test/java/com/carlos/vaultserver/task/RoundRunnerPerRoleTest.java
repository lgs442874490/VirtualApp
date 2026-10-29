package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R2 Phase 2：整轮执行按角色隔离（RoundRunner / ModularRoundRunner）。
 *
 * <p>锁定契约：
 * <ul>
 *   <li>双角色的 {@code log:/st:/summary:/srv:lastrun:} 键互不覆盖；</li>
 *   <li>旧键（App 显示链路）只由 active 角色镜像写，非 active 角色不写旧键；</li>
 *   <li>uid=0 旧签名行为与 Phase 2 之前 100% 一致（只写旧键）；</li>
 *   <li>启用配置优先 {@code roleConfigByUid[sid:uid]}，缺省回退 {@code roleConfig[sid]}。</li>
 * </ul>
 */
public class RoundRunnerPerRoleTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private VaultStore.Account acc;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        acc = new VaultStore.Account("111");
        acc.token = "tk";
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }

    /** 只让 login_rewards 成功，其余任务走默认（缺响应 → DEGRADED/ERROR，仅用于落键测试）。 */
    private static CaptureTasksTest.FakeEngine engine() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("rewards", "diamond:10");
        e.responses.put("req_fourteen_sign_get_all_reward", msg(ok));
        return e;
    }


    /** 除 login_rewards 外全部关闭：整轮只真正执行 1 个任务，测试快且不依赖网络替身。 */
    private static Map<String, Boolean> onlyLoginRewards() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (ServerTasks.TaskDef d : ServerTasks.all()) m.put(d.id, false);
        m.put("login_rewards", true);
        return m;
    }

    /** 全部关闭（login_rewards 也不执行）：用于回退默认配置分支。 */
    private static Map<String, Boolean> allOff() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (ServerTasks.TaskDef d : ServerTasks.all()) m.put(d.id, false);
        return m;
    }

    private static String day() {
        return com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd").format(new java.util.Date());
    }

    private long num(String key) {
        Object v = store.snapshot().get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1L;
    }

    // ---------------- RoundRunner ----------------

    @Test
    public void fullRound_twoRoles_isolatedAndNoLegacyWrites() {
        CaptureTasksTest.FakeEngine e = engine();
        // 该区服旧默认：login_rewards 关；按 uid 覆盖：1001 开、1002 无（回退旧默认关）
        acc.roleConfig.put("913", allOff());
        acc.roleConfigByUid.put("913:1001", onlyLoginRewards());

        RoundRunner.runRound(e, acc, 913L, store, null, null, "定时", 1001L, false);
        RoundRunner.runRound(e, acc, 913L, store, null, null, "定时", 1002L, false);

        // 角色日志互不覆盖：1001 用 byUid 覆盖（开），1002 回退 sid 默认（关）
        JSONArray log1 = new JSONArray(store.getString("log:111:913:1001:login_rewards"));
        assertEquals("CLAIMED", log1.getJSONObject(0).getString("cat"));
        JSONArray log2 = new JSONArray(store.getString("log:111:913:1002:login_rewards"));
        assertEquals("配置未启用", log2.getJSONObject(0).getString("reason"));

        // 当日状态机按角色键
        assertEquals(2L, num("st:" + day() + ":111:913:1001:login_rewards"));
        assertEquals(4L, num("st:" + day() + ":111:913:1002:login_rewards"));

        // 摘要/整轮标记按角色键
        assertNotNull(store.getString("summary:111:913:1001"));
        assertNotNull(store.getString("summary:111:913:1002"));
        assertNotNull(store.snapshot().get("srv:lastrun:111:913:1001"));
        assertNotNull(store.snapshot().get("srv:lastrun:111:913:1002"));

        // 非 active 角色不写旧键（App 显示链路留待 active 镜像，避免双角色互相覆盖）
        assertNull(store.getString("log:111:913:login_rewards"));
        assertNull(store.getString("summary:111"));
        assertNull(store.snapshot().get("srv:lastrun:111"));
        assertNull(store.snapshot().get("st:" + day() + ":111:913:login_rewards"));
    }

    @Test
    public void fullRound_activeRoleMirrorsLegacyKeys() {
        CaptureTasksTest.FakeEngine e = engine();
        acc.roleConfig.put("913", onlyLoginRewards());

        RoundRunner.runRound(e, acc, 913L, store, null, null, "定时", 1003L, true);

        // 角色键 + 旧键同时存在且内容一致（App 仍读 summary:<fpid> / log:<fpid>:<sid>:*）
        assertEquals(store.getString("summary:111:913:1003"), store.getString("summary:111"));
        assertNotNull(store.getString("log:111:913:login_rewards"));
        assertEquals(2L, num("st:" + day() + ":111:913:login_rewards"));
        assertNotNull(store.snapshot().get("srv:lastrun:111"));
        assertNotNull(store.snapshot().get("srv:lastrun:111:913:1003"));
        assertEquals(num("srv:lastrun:111:913:1003"), num("srv:lastrun:111"));
    }

    @Test
    public void legacySignature_uid0_writesLegacyKeysOnly() {
        CaptureTasksTest.FakeEngine e = engine();
        acc.roleConfig.put("913", onlyLoginRewards());

        RoundRunner.runRound(e, acc, 913L, store, null, null, "定时");

        // 旧键（现网 App 链路）全在
        assertNotNull(store.getString("summary:111"));
        assertNotNull(store.getString("log:111:913:login_rewards"));
        assertEquals(2L, num("st:" + day() + ":111:913:login_rewards"));
        assertNotNull(store.snapshot().get("srv:lastrun:111"));
        // 角色键不存在（uid=0 不分角色）
        assertNull(store.getString("log:111:913:0:login_rewards"));
        assertNull(store.getString("summary:111:913"));
        assertNull(store.snapshot().get("srv:lastrun:111:913"));
    }

    // ---------------- ModularRoundRunner ----------------

    @Test
    public void modularRunner_singleTask_roleScopedKeys() {
        CaptureTasksTest.FakeEngine e = engine();

        ModularRoundRunner.runRound(e, acc, 913L, store, null, "login_rewards", "手动", 1001L, false);

        assertNotNull(store.getString("log:111:913:1001:login_rewards"));
        assertNull(store.getString("log:111:913:login_rewards"));
        assertNull(store.getString("summary:111"));
        // 单任务不消耗整轮
        assertNull(store.snapshot().get("srv:lastrun:111:913:1001"));
    }

    @Test
    public void modularRunner_fullRound_activeMirrorsSummaryAndLastRun() {
        CaptureTasksTest.FakeEngine e = engine();
        acc.roleConfig.put("913", onlyLoginRewards());

        ModularRoundRunner.runRound(e, acc, 913L, store, null, null, "定时", 1004L, true);

        assertEquals(store.getString("summary:111:913:1004"), store.getString("summary:111"));
        assertNotNull(store.snapshot().get("srv:lastrun:111:913:1004"));
        assertEquals(num("srv:lastrun:111:913:1004"), num("srv:lastrun:111"));
        assertNotNull(store.getString("log:111:913:login_rewards"));
    }
}
