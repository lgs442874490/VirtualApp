package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.automation.ModuleRegistry;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * R2 Phase 2A：TaskCadenceScheduler 按角色（{@link RoleKey}）调度 + 20/分公平分配。
 *
 * <p>覆盖：
 * <ul>
 *   <li>灰度开关解析（VAULT_PER_ROLE / VAULT_CADENCE_ACCOUNT_MAX）；</li>
 *   <li>off（默认）模式 = 现网单 active 角色行为：只写旧键 {@code srv:cad:<fpid>:<sid>:<fn>}、
 *       {@code srv:cadlast:<fpid>}，不产生 uid 键；</li>
 *   <li>on 模式：双角色各自 cadence/min_gap 独立；active 角色旧键镜像；两个角色公平轮转不饿死；</li>
 *   <li>角色级闸门：init grace / busy / wartime 只影响对应角色；</li>
 *   <li>账号级预算 {@code VAULT_CADENCE_ACCOUNT_MAX}：超限本 tick 不派且不写 cad 键；</li>
 *   <li>迁移读：角色键缺失回退旧 cadence / 旧 st 键一次；roleConfigByUid 优先、旧 sid 配置回退；</li>
 *   <li>统计 {@code srv:cad:stats} 增加 byRole/skipByRole，保留 byAccount。</li>
 * </ul>
 */
public class PerRoleCadenceSchedulerTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_MAIN = 35371154L;
    private static final long UID_ALT = 88280792L;
    private static final RoleKey MAIN = new RoleKey(FPID, SID, UID_MAIN);
    private static final RoleKey ALT = new RoleKey(FPID, SID, UID_ALT);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private FakeKeeper keeper;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        keeper = new FakeKeeper();
    }

    private static ModuleRegistry registry() {
        ModuleRegistry r = Modules.registry();
        r.validate();
        return r;
    }

    /** 完整注入构造（perRole / accountMax 可控）。 */
    private TaskCadenceScheduler scheduler(boolean perRole, int globalMax, int accountMax) {
        return new TaskCadenceScheduler(store, stateStore, keeper, registry(),
                false, new Random(42L), globalMax, perRole, accountMax);
    }

    private VaultStore.Account seed(boolean withAlt, Map<String, Boolean> sidConfig) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        a.activeUid = String.valueOf(UID_MAIN);
        if (sidConfig != null) a.roleConfig.put(String.valueOf(SID), sidConfig);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        keeper.addRole(MAIN);
        keeper.addRole(ALT);
        keeper.online.add(MAIN.key());
        keeper.online.add(ALT.key());
        if (!withAlt) keeper.online.remove(ALT.key());
        keeper.active.put(FPID, MAIN);
        return store.find(FPID);
    }

    private static long bj(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String day(long epochMillis) {
        return Beijing.fmt("yyyy-MM-dd").format(new java.util.Date(epochMillis));
    }

    private JSONObject stats(long now) {
        String raw = stateStore.getString(TaskCadenceScheduler.STATS_KEY_PREFIX + day(now));
        assertNotNull("缺少统计键", raw);
        return new JSONObject(raw);
    }

    private Object cad(Object role, String fn) {
        return stateStore.snapshot().get(StateKeys.cadence((RoleKey) role, fn));
    }

    private Set<String> cadKeys() {
        Set<String> out = new HashSet<>();
        for (String k : stateStore.snapshot().keySet()) {
            if (k.startsWith("srv:cad:") && !k.startsWith(TaskCadenceScheduler.STATS_KEY_PREFIX)) out.add(k);
        }
        return out;
    }

    // ======================= 1. 配置解析 =======================

    @Test
    public void parsePerRoleAndAccountMax() {
        assertFalse("默认 off", TaskCadenceScheduler.parsePerRole(null));
        assertFalse(TaskCadenceScheduler.parsePerRole("off"));
        assertFalse(TaskCadenceScheduler.parsePerRole("false"));
        assertFalse(TaskCadenceScheduler.parsePerRole("abc"));
        assertTrue(TaskCadenceScheduler.parsePerRole("on"));
        assertTrue(TaskCadenceScheduler.parsePerRole("ON"));
        assertTrue(TaskCadenceScheduler.parsePerRole("1"));
        assertTrue(TaskCadenceScheduler.parsePerRole("true"));

        assertEquals("账号预算默认 24", 24, TaskCadenceScheduler.parseAccountMax(null));
        assertEquals(24, TaskCadenceScheduler.parseAccountMax(""));
        assertEquals(24, TaskCadenceScheduler.parseAccountMax("abc"));
        assertEquals("off=不限", 0, TaskCadenceScheduler.parseAccountMax("off"));
        assertEquals("0=不限", 0, TaskCadenceScheduler.parseAccountMax("0"));
        assertEquals("负数=不限", 0, TaskCadenceScheduler.parseAccountMax("-1"));
        assertEquals(24, TaskCadenceScheduler.parseAccountMax("24"));
        assertEquals(8, TaskCadenceScheduler.parseAccountMax("8"));

        TaskCadenceScheduler on = scheduler(true, 20, 24);
        assertTrue(on.perRoleEnabled());
        assertEquals(24, on.accountMaxPerMinute());
        TaskCadenceScheduler off = scheduler(false, 20, 0);
        assertFalse(off.perRoleEnabled());
        assertEquals(0, off.accountMaxPerMinute());
    }

    @Test
    public void pickNextRole_pureRoundRobin() {
        assertNull(TaskCadenceScheduler.pickNextRole(null, "x"));
        assertNull(TaskCadenceScheduler.pickNextRole(new ArrayList<>(), "x"));
        String main = "48460403|888|35371154";
        String alt = "48460403|888|88280792";
        List<String> two = Arrays.asList(alt, main);
        assertEquals("首次选字典序最小", main, TaskCadenceScheduler.pickNextRole(two, null));
        assertEquals(alt, TaskCadenceScheduler.pickNextRole(two, main));
        assertEquals(main, TaskCadenceScheduler.pickNextRole(two, alt));
        assertEquals("游标不在候选 → 字典序最小", main,
                TaskCadenceScheduler.pickNextRole(two, "other|888|1"));
        assertEquals("单角色恒选自己", main,
                TaskCadenceScheduler.pickNextRole(Arrays.asList(main), main));
    }

    // ======================= 2. off 模式 = 现网行为 =======================

    @Test
    public void offMode_onlyActiveLegacyRoleAndLegacyKeys() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(false, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        scheduler.tick(now);

        assertEquals("off 模式每账号只派一个角色", 1, keeper.submittedRoles.size());
        RoleKey submitted = keeper.submittedRoles.get(0);
        assertEquals("off 模式使用旧角色槽（uid=0）", 0L, submitted.uid);
        assertEquals(SID, submitted.sid);

        String fn = keeper.submittedFns.get(0);
        Object legacyNext = stateStore.snapshot().get("srv:cad:" + FPID + ":" + SID + ":" + fn);
        assertNotNull("off 模式写旧 cad 键", legacyNext);
        // 唤醒兼容镜像：off 模式把 active 角色 uid 键同步为同一值（Phase 2 wake 钩子按 uid 写 0，
        // 若不刷新会反复到期；只读回退 + 同值刷新，不改变旧键主导的调度语义）
        Object uidNext = stateStore.snapshot().get(StateKeys.cadence(MAIN, fn));
        if (uidNext != null) {
            assertEquals("off 模式 uid 键只作唤醒兼容镜像（同值）",
                    ((Number) legacyNext).longValue(), ((Number) uidNext).longValue());
        }
        assertEquals("off 模式写账号级 cadlast",
                now, ((Number) stateStore.snapshot().get("srv:cadlast:" + FPID)).longValue());
    }

    @Test
    public void offMode_rereadsWakeUidKeyAndDoesNotLoop() {
        VaultStore.Account acc = seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(false, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        List<String> enabled = scheduler.enabledTaskIds(acc, SID, 0L);
        assertFalse(enabled.isEmpty());
        // 所有任务旧键都未到期；只有 active 角色 uid 键被推送唤醒写成 0 → 应回退读取并派单
        for (String fn : enabled) {
            stateStore.putLong("srv:cad:" + FPID + ":" + SID + ":" + fn, now + 3_600_000L);
        }
        String wakeFn = enabled.get(0);
        stateStore.putLong(StateKeys.cadence(MAIN, wakeFn), 0L);

        scheduler.tick(now);
        assertEquals(1, keeper.submittedRoles.size());
        assertEquals("off 模式应读 active 角色唤醒键", wakeFn, keeper.submittedFns.get(0));
        // 派单后旧键与唤醒 uid 键同步推进 → 下一 tick 不再被 0 反复唤醒
        scheduler.tick(now + 7_000L);
        assertEquals("唤醒键 0 必须被刷新，不能每 tick 重复派单", 1, keeper.submittedRoles.size());
    }

    // ======================= 3. on 模式：双角色独立键 =======================

    @Test
    public void onMode_bothRolesDispatchWithIndependentKeys() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);

        scheduler.tick(now);
        scheduler.tick(now + 7_000L);   // 两角色各自 min_gap 6s：同 tick 后再过 7s 另一角色可派
        assertEquals("两角色各派 1 单", 2, keeper.submittedRoles.size());
        assertNotEquals("两单来自不同角色",
                keeper.submittedRoles.get(0).key(), keeper.submittedRoles.get(1).key());

        for (int i = 0; i < 2; i++) {
            RoleKey role = keeper.submittedRoles.get(i);
            String fn = keeper.submittedFns.get(i);
            assertNotNull("角色 " + role.key() + " 必须写角色 cad 键", cad(role, fn));
            assertNotNull("角色 " + role.key() + " 必须写角色 cadlast",
                    stateStore.snapshot().get(StateKeys.cadenceLast(role)));
        }
        JSONObject st = stats(now);
        JSONObject byRole = st.getJSONObject("byRole");
        assertTrue("统计 byRole 覆盖两个角色: " + byRole,
                byRole.has(keeper.submittedRoles.get(0).key())
                        && byRole.has(keeper.submittedRoles.get(1).key()));
        assertEquals("byAccount 保留", 2L, st.getJSONObject("byAccount").getLong(FPID));
    }

    @Test
    public void onMode_perRoleMinGapAllowsSiblingDispatch() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);

        scheduler.tick(now);
        assertEquals(1, keeper.submittedRoles.size());
        // 1s 后：刚派单角色被角色级 min_gap 拦住，另一角色仍可派（旧账号级 6s 间隔会一起拦住）
        scheduler.tick(now + 1_000L);
        assertEquals("兄弟角色不应被对方的 min_gap 拦住", 2, keeper.submittedRoles.size());
        assertNotEquals(keeper.submittedRoles.get(0).key(), keeper.submittedRoles.get(1).key());
    }

    @Test
    public void onMode_activeRoleMirrorsLegacyKeys_onlyActive() {
        VaultStore.Account acc = seed(false, null);   // 先只有主控在线
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        scheduler.tick(now);
        assertEquals(1, keeper.submittedRoles.size());
        assertEquals("只有主控在线时派主控", MAIN.key(), keeper.submittedRoles.get(0).key());
        String fn = keeper.submittedFns.get(0);
        long roleNext = ((Number) cad(MAIN, fn)).longValue();
        assertEquals("主控（active）镜像写旧 cad 键",
                roleNext, ((Number) stateStore.snapshot()
                        .get("srv:cad:" + FPID + ":" + SID + ":" + fn)).longValue());
        assertEquals("主控镜像写账号级 cadlast",
                now, ((Number) stateStore.snapshot().get("srv:cadlast:" + FPID)).longValue());

        // 主控下线、兄弟上线（active 仍是主控）→ 兄弟派单不覆盖旧键
        keeper.online.remove(MAIN.key());
        keeper.online.add(ALT.key());
        long now2 = now + 7_000L;
        scheduler.tick(now2);
        assertEquals(2, keeper.submittedRoles.size());
        assertEquals("兄弟角色派单", ALT.key(), keeper.submittedRoles.get(1).key());
        String altFn = keeper.submittedFns.get(1);
        assertNotNull("兄弟角色写自己的 cad 键", cad(ALT, altFn));
        assertEquals("兄弟角色不得把旧账号级 cadlast 改写",
                now, ((Number) stateStore.snapshot().get("srv:cadlast:" + FPID)).longValue());
        // acc 字段校验（activeUid 匹配）
        assertTrue(scheduler.isActiveRole(acc, MAIN));
        assertFalse(scheduler.isActiveRole(acc, ALT));
    }

    // ======================= 4. 公平：双角色不饿死 =======================

    @Test
    public void onMode_fairness_twoRolesAlternateWithinBudget() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        for (int i = 0; i < 12; i++) scheduler.tick(now + i * 7_000L);

        int main = 0, alt = 0;
        for (RoleKey r : keeper.submittedRoles) {
            if (r.key().equals(MAIN.key())) main++;
            if (r.key().equals(ALT.key())) alt++;
        }
        assertEquals("单 tick 最多派 1 单", 12, keeper.submittedRoles.size());
        assertTrue("20/分预算内两角色都应拿到派单：main=" + main + " alt=" + alt, main >= 4 && alt >= 4);
        assertTrue("公平：两角色数量接近（差值≤2）", Math.abs(main - alt) <= 2);
        assertTrue("预算窗口不超过 20/分", scheduler.minuteDispatchCount() <= 20);
    }

    // ======================= 5. 角色级闸门 =======================

    @Test
    public void onMode_initGracePerRole() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        keeper.onlineSince.put(MAIN.key(), now - 5_000L);                       // 主控刚上线 5s
        keeper.onlineSince.put(ALT.key(), now - TaskCadenceScheduler.INIT_GRACE_MILLIS - 1_000L);

        scheduler.tick(now);
        assertEquals("主控 init grace 内不派，兄弟正常派", 1, keeper.submittedRoles.size());
        assertEquals(ALT.key(), keeper.submittedRoles.get(0).key());
        scheduler.publishStats(now);
        assertTrue("统计记录 init_grace", stats(now).getJSONObject("skip")
                .optLong("init_grace", 0L) >= 1L);
    }

    @Test
    public void onMode_busyPerRole() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        keeper.busyRoles.add(MAIN.key());

        scheduler.tick(now);
        assertEquals("主控忙只影响主控，兄弟仍派", 1, keeper.submittedRoles.size());
        assertEquals(ALT.key(), keeper.submittedRoles.get(0).key());
        scheduler.publishStats(now);
        assertTrue("统计记录 busy", stats(now).getJSONObject("skip")
                .optLong("busy", 0L) >= 1L);
    }

    @Test
    public void onMode_wartimePerRole() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        keeper.warRoles.add(MAIN.key());

        scheduler.tick(now);
        assertEquals("主控战时让位，兄弟仍派", 1, keeper.submittedRoles.size());
        assertEquals(ALT.key(), keeper.submittedRoles.get(0).key());
        scheduler.publishStats(now);
        assertTrue("统计记录 wartime", stats(now).getJSONObject("skip")
                .optLong("wartime", 0L) >= 1L);
    }

    // ======================= 6. 账号级预算 =======================

    @Test
    public void onMode_accountBudgetCapsAndDoesNotWriteCadKeys() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 0, 2);   // 全局不限，账号 2/分
        long now = bj(2026, 10, 29, 6, 0);

        scheduler.tick(now);
        scheduler.tick(now + 7_000L);
        assertEquals("账号预算 2/分：前两单受理", 2, keeper.submittedRoles.size());

        Set<String> before = cadKeys();
        scheduler.tick(now + 14_000L);
        assertEquals("账号预算用尽本 tick 不派", 2, keeper.submittedRoles.size());
        assertEquals("账号预算用尽不写任何 cad 键", before, cadKeys());
        scheduler.publishStats(now);
        assertTrue("统计记录 account_budget", stats(now).getJSONObject("skip")
                .optLong("account_budget", 0L) >= 1L);

        // 60s 窗口滑过后恢复
        scheduler.tick(now + 62_000L);
        assertEquals("账号预算窗口滑过后恢复派单", 3, keeper.submittedRoles.size());
    }

    // ======================= 7. 迁移读 + 配置优先级 =======================

    @Test
    public void onMode_roleConfigByUid_precedesSidFallback() {
        VaultStore.Account acc = seed(true, null);
        Map<String, Boolean> sidCfg = new HashMap<>();
        sidCfg.put("mail", false);
        sidCfg.put("gather", false);
        acc.roleConfig.put(String.valueOf(SID), sidCfg);
        Map<String, Boolean> uidCfg = new HashMap<>();
        uidCfg.put("mail", true);
        uidCfg.put("gather", true);
        acc.roleConfigByUid.put(SID + ":" + UID_MAIN, uidCfg);

        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        List<String> forMain = scheduler.enabledTaskIds(acc, SID, UID_MAIN);
        assertTrue("uid 配置优先：mail 开", forMain.contains("mail"));
        assertTrue("uid 配置优先：gather 开", forMain.contains("gather"));
        List<String> forAlt = scheduler.enabledTaskIds(acc, SID, UID_ALT);
        assertFalse("无 uid 配置回退 sid 配置：mail 关", forAlt.contains("mail"));
        assertFalse("无 uid 配置回退 sid 配置：gather 关", forAlt.contains("gather"));
        assertTrue("未配置项用目录默认（supply 默认开）", forAlt.contains("supply"));
        // 旧签名（uid=0）行为不变
        assertFalse(scheduler.enabledTaskIds(acc, SID).contains("mail"));
    }

    @Test
    public void onMode_legacyStFallbackForBackoff() {
        VaultStore.Account acc = seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        String day = day(System.currentTimeMillis());
        String fn = "login_rewards";
        // 只有旧 st 键（Phase 1 格式），无角色 st 键 → 回退读取
        stateStore.putLong("st:" + day + ":" + FPID + ":" + SID + ":" + fn, 4L);
        long interval = TaskCadence.intervalSeconds(fn) * 1000L;
        long next = TaskCadenceScheduler.nextIntervalForResult(acc, MAIN, fn,
                interval, stateStore.snapshot());
        assertTrue("旧 st 键应作为回退：无可领退避 ≥180s", next >= TaskCadenceScheduler.SKIP_BACKOFF_MIN_MILLIS);
        // 角色 st 键存在时优先角色键
        stateStore.putLong(StateKeys.dayState(day, MAIN, fn), 2L);
        assertEquals("角色 st 键优先（已领取=正常间隔）", interval,
                TaskCadenceScheduler.nextIntervalForResult(acc, MAIN, fn,
                        interval, stateStore.snapshot()));
    }

    @Test
    public void onMode_dayStateKeysIndependentPerRole() {
        VaultStore.Account acc = seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        String day = day(System.currentTimeMillis());
        String fn = "login_rewards";
        stateStore.putLong(StateKeys.dayState(day, MAIN, fn), 4L);   // 主控：无可领 → 退避
        stateStore.putLong(StateKeys.dayState(day, ALT, fn), 2L);    // 兄弟：已领取 → 正常间隔
        long interval = TaskCadence.intervalSeconds(fn) * 1000L;
        long mainNext = TaskCadenceScheduler.nextIntervalForResult(acc, MAIN, fn,
                interval, stateStore.snapshot());
        long altNext = TaskCadenceScheduler.nextIntervalForResult(acc, ALT, fn,
                interval, stateStore.snapshot());
        assertTrue("主控 st=4 退避", mainNext >= TaskCadenceScheduler.SKIP_BACKOFF_MIN_MILLIS);
        assertEquals("兄弟 st=2 正常间隔", interval, altNext);
        assertNotEquals("两角色 st 键互不影响", mainNext, altNext);
    }

    @Test
    public void onMode_legacyCadenceFallbackInCandidate() {
        VaultStore.Account acc = seed(false, null);   // 只主控在线，避免兄弟干扰
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 29, 6, 0);
        // 给所有已启用任务写"未到期"的角色键，再把其中一个任务的旧键写成到期（0）
        List<String> enabled = scheduler.enabledTaskIds(acc, SID, UID_MAIN);
        assertFalse(enabled.isEmpty());
        String legacyFn = enabled.get(0);
        for (String fn : enabled) {
            if (fn.equals(legacyFn)) continue;   // 该任务故意只写旧键，验证迁移回退
            stateStore.putLong(StateKeys.cadence(MAIN, fn), now + 3_600_000L);
        }
        stateStore.putLong("srv:cad:" + FPID + ":" + SID + ":" + legacyFn, 0L);

        scheduler.tick(now);
        assertEquals("旧 cad 键回退命中", 1, keeper.submittedRoles.size());
        assertEquals("应派回退命中的任务", legacyFn, keeper.submittedFns.get(0));
        assertEquals(MAIN.key(), keeper.submittedRoles.get(0).key());
    }

    // ======================= 8. 统计 schema =======================

    @Test
    public void stats_roleRetainsLegacyFieldsAndSkipByRole() {
        seed(true, null);
        TaskCadenceScheduler scheduler = scheduler(true, 20, 24);
        long now = bj(2026, 10, 29, 6, 0);
        keeper.busyRoles.add(MAIN.key());   // 制造角色级 skip
        scheduler.tick(now);
        assertEquals(1, keeper.submittedRoles.size());
        scheduler.publishStats(now);

        JSONObject st = stats(now);
        assertEquals("date", day(now), st.getString("date"));
        assertEquals("dispatch", 1L, st.getLong("dispatch"));
        assertEquals("globalMax", 20, st.getInt("globalMax"));
        assertEquals("accountMax", 24, st.getInt("accountMax"));
        assertNotNull("byAccount 保留", st.getJSONObject("byAccount"));
        assertEquals("byAccount 计数", 1L, st.getJSONObject("byAccount").getLong(FPID));
        JSONObject byRole = st.getJSONObject("byRole");
        assertEquals("byRole 计数", 1L, byRole.getLong(keeper.submittedRoles.get(0).key()));
        JSONObject skipByRole = st.getJSONObject("skipByRole");
        assertTrue("skipByRole 记录主控 busy", skipByRole.has(MAIN.key())
                && skipByRole.getJSONObject(MAIN.key()).optLong("busy", 0L) >= 1L);
    }

    // ======================= 9. 竞技场独占窗（批次 B） =======================

    @Test
    public void onMode_arenaGateBlocksGatedRoleOnly() {
        VaultStore.Account acc = seed(true, null);
        // 关掉白名单任务，确保主控在独占窗内没有任何可派任务
        acc.roleConfigByUid.put(SID + ":" + UID_MAIN,
                new LinkedHashMap<>(Map.of("battle_shield", false, "medic", false)));
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 30, 12, 0);
        ArenaGate.enterActive(MAIN, now);
        try {
            scheduler.tick(now);
            assertEquals("独占中的主控不应派单", 1, keeper.submittedRoles.size());
            assertEquals("兄弟角色不受影响", ALT.key(), keeper.submittedRoles.get(0).key());
            scheduler.publishStats(now);
            assertTrue("统计记录 arena_exclusive",
                    stats(now).getJSONObject("skip").optLong("arena_exclusive", 0L) >= 1L);
            for (String k : cadKeys()) {
                assertFalse("被阻塞角色不得写 cad 键: " + k, k.contains("|" + UID_MAIN + "|"));
            }
        } finally {
            ArenaGate.clear(MAIN);
        }
    }

    @Test
    public void onMode_arenaGateDoesNotBlockSafetyWhitelist() {
        VaultStore.Account acc = seed(false, null);   // 仅主控在线
        acc.roleConfigByUid.put(SID + ":" + UID_MAIN,
                new LinkedHashMap<>(Map.of("battle_shield", true, "medic", true)));
        TaskCadenceScheduler scheduler = scheduler(true, 20, 0);
        long now = bj(2026, 10, 30, 12, 0);
        ArenaGate.enterActive(MAIN, now);
        try {
            scheduler.tick(now);
            assertEquals("独占窗内白名单任务仍可派", 1, keeper.submittedRoles.size());
            assertEquals(MAIN.key(), keeper.submittedRoles.get(0).key());
            String fn = keeper.submittedFns.get(0);
            assertTrue("独占窗内只放行安全白名单: " + fn,
                    "battle_shield".equals(fn) || "medic".equals(fn));
        } finally {
            ArenaGate.clear(MAIN);
        }
    }

    // ======================= 假实现 =======================

    private static final class FakeKeeper implements RoundScheduler.KeeperFacade {
        final Set<String> online = new HashSet<>();
        final Set<String> busyRoles = new HashSet<>();
        final Set<String> warRoles = new HashSet<>();
        final Map<String, Long> onlineSince = new HashMap<>();
        final Map<String, RoleKey> active = new HashMap<>();
        final Set<String> registered = new HashSet<>();
        final List<RoleKey> submittedRoles = new ArrayList<>();
        final List<String> submittedFns = new ArrayList<>();

        void addRole(RoleKey role) {
            registered.add(role.key());
        }

        @Override
        public List<RoleKey> rolesOf(String fpid) {
            List<RoleKey> out = new ArrayList<>();
            for (String k : registered) {
                if (k.startsWith(fpid + "|")) {
                    String[] p = k.split("\\|");
                    out.add(new RoleKey(fpid, Long.parseLong(p[1]), Long.parseLong(p[2])));
                }
            }
            return out;
        }

        @Override
        public RoleKey activeRole(String fpid) {
            return active.get(fpid);
        }

        @Override
        public boolean submitRound(RoleKey role, String fn, String trigger) {
            submittedRoles.add(role);
            submittedFns.add(fn);
            return true;
        }

        @Override
        public boolean submitRound(String fpid, String fn, String trigger) {
            RoleKey role = activeRole(fpid);
            if (role == null) return false;
            submittedRoles.add(role);
            submittedFns.add(fn);
            return true;
        }

        @Override
        public long sessionSid(String fpid) {
            RoleKey role = activeRole(fpid);
            return role == null ? 0L : role.sid;
        }

        @Override
        public boolean sessionOnline(String fpid) {
            for (String k : online) if (k.startsWith(fpid + "|")) return true;
            return false;
        }

        @Override
        public boolean sessionOnline(RoleKey role) {
            if (role == null) return false;
            if (role.uid <= 0L) return sessionOnline(role.fpid);   // 旧角色槽回落账号维度
            return online.contains(role.key());
        }

        @Override
        public long sessionOnlineSince(RoleKey role) {
            return role == null ? 0L : onlineSince.getOrDefault(role.key(), 0L);
        }

        @Override
        public boolean executorBusy(RoleKey role) {
            return role != null && busyRoles.contains(role.key());
        }

        @Override
        public boolean wartimeYield(RoleKey role) {
            return role != null && warRoles.contains(role.key());
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }
    }
}
