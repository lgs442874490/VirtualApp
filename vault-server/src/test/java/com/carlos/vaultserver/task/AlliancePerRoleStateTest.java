package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1（R2 第一期）回归：同账号同服多角色「联盟科技捐献」状态按 roleUid 隔离。
 *
 * <p>背景（2026-10-28 生产分析 R2）：{@code srv:alliance_science:<fpid>:<sid>}、{@code mod:…}、
 * {@code ledger:…} 都是 fpid:sid 键型，888 服两个角色（35371154 / 88280792）互相覆盖；205 免费次数
 * 是按角色的，非主控角色永远花不掉、主控角色的缓存又会被非主控角色读到。
 *
 * <p>Phase 1 口径：
 * <ul>
 *   <li>roleUid&lt;=0 = 旧/主控兼容路径，所有键与行为 100% 不变（App 显示链路不变）；</li>
 *   <li>roleUid&gt;0 才使用 uid 键，两个角色互不覆盖；角色键首次 miss 允许回退旧键作初始快照一次，
 *       读取不写旧键；台账读取<b>不</b>跨角色回退（宁多请求一次，避免漏领）。</li>
 * </ul>
 */
public class AlliancePerRoleStateTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_MAIN = 35371154L;
    private static final long UID_ALT = 88280792L;

    private static final String LEGACY_SCIENCE_KEY = "srv:alliance_science:48460403:888";
    private static final String MOD_FN = "alliance_donate";
    private static final String LEDGER_SCOPE = "alliance_donate";
    private static final String LEDGER_ITEM = "dev_daily";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore freshStore() {
        return new StateStore(tmp.getRoot().toPath());
    }

    /** roleUid=0 的旧构造（主控/旧测试路径）。 */
    private TaskContext legacyCtx(StateStore store) {
        return new TaskContext(null, FPID, SID, store, Collections.emptyMap(), "服务器");
    }

    /** Phase 1 新构造：带 roleUid。 */
    private TaskContext roleCtx(StateStore store, long roleUid) {
        return new TaskContext(null, FPID, SID, store, Collections.emptyMap(), "服务器",
                TaskContext.enabledPredicate(null), roleUid);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> recovery(long count, long max, long next, long interval) {
        return map("id", 205L, "count", count, "max_count", max,
                "next_calc_ts", next, "recover", 1L, "interval", interval);
    }

    private static ServerTasks.AllianceScienceState stateWith(long count, long spent) {
        ServerTasks.AllianceScienceState st = new ServerTasks.AllianceScienceState();
        st.sciences.put(6023L, new long[]{0L, 30_021_400L});
        st.recommend = 6023L;
        st.recovery = recovery(count, 25L, 1_900_000_000L, 600L);
        st.recoverySig = ServerTasks.recoverySignature(st.recovery);
        st.spentSinceSnapshot = spent;
        return st;
    }

    // ==================== 键规则 ====================

    @Test
    public void scienceKey_roleUidIsolatedAndZeroKeepsLegacyKey() {
        assertEquals(LEGACY_SCIENCE_KEY, ServerTasks.allianceScienceKey(FPID, SID));
        assertEquals("roleUid=0 必须走旧键（主控/App 链路不变）",
                LEGACY_SCIENCE_KEY, ServerTasks.allianceScienceKey(FPID, SID, 0L));
        assertEquals("srv:alliance_science:48460403:888:35371154",
                ServerTasks.allianceScienceKey(FPID, SID, UID_MAIN));
        assertEquals("srv:alliance_science:48460403:888:88280792",
                ServerTasks.allianceScienceKey(FPID, SID, UID_ALT));
        assertNotEquals(ServerTasks.allianceScienceKey(FPID, SID, UID_MAIN),
                ServerTasks.allianceScienceKey(FPID, SID, UID_ALT));
    }

    @Test
    public void modKey_roleUidIsolatedAndZeroKeepsLegacyKey() {
        assertEquals("mod:48460403:888:alliance_donate",
                TaskContext.modKey(FPID, SID, 0L, MOD_FN));
        assertEquals("mod:48460403:888:35371154:alliance_donate",
                TaskContext.modKey(FPID, SID, UID_MAIN, MOD_FN));
        assertEquals("mod:48460403:888:88280792:alliance_donate",
                TaskContext.modKey(FPID, SID, UID_ALT, MOD_FN));
    }

    /** roleUid=0：mod/srv/ledger 全部落在旧键，且不会写出 :0: 伪角色键。 */
    @Test
    public void roleUidZero_writesOnlyLegacyKeys() {
        StateStore store = freshStore();
        TaskContext tc = legacyCtx(store);
        assertEquals(0L, tc.roleUid);

        tc.saveModText(MOD_FN, "no_free_until", "0");
        assertNotNull(store.getString("mod:48460403:888:alliance_donate"));
        assertNull(store.getString("mod:48460403:888:0:alliance_donate"));

        ServerTasks.saveAllianceScience(tc, stateWith(24L, 0L));
        assertNotNull(store.getString(LEGACY_SCIENCE_KEY));
        assertNull(store.getString("srv:alliance_science:48460403:888:0"));

        ClaimLedger.mark(tc, LEDGER_SCOPE, LEDGER_ITEM);
        assertNotNull(store.getString("ledger:48460403:888:alliance_donate"));
        assertNull(store.getString("ledger:48460403:888:0:alliance_donate"));
        assertTrue("旧路径台账必须仍生效", ClaimLedger.isClaimed(tc, LEDGER_SCOPE, LEDGER_ITEM));
    }

    @Test
    public void roleUidConstructor_andPredicatePreserved() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("alliance_donate", true);
        TaskContext tc = new TaskContext(null, FPID, SID, freshStore(), Collections.emptyMap(),
                "服务器", TaskContext.enabledPredicate(cfg), UID_MAIN);
        assertEquals(UID_MAIN, tc.roleUid);
        assertTrue(tc.taskEnabled("alliance_donate"));
        assertFalse(tc.taskEnabled("no_such_task"));
        assertEquals(0L, legacyCtx(freshStore()).roleUid);
    }

    // ==================== 科技捐献快照按角色隔离 ====================

    @Test
    public void perRoleScienceState_doNotOverwriteEachOther() {
        StateStore store = freshStore();
        TaskContext main = roleCtx(store, UID_MAIN);
        TaskContext alt = roleCtx(store, UID_ALT);

        ServerTasks.saveAllianceScience(main, stateWith(24L, 24L));
        ServerTasks.saveAllianceScience(alt, stateWith(5L, 0L));

        ServerTasks.AllianceScienceState mainBack = ServerTasks.loadAllianceScience(main);
        ServerTasks.AllianceScienceState altBack = ServerTasks.loadAllianceScience(alt);
        assertEquals("主控角色快照独立", 24L, Exec.longOf(mainBack.recovery.get("count")));
        assertEquals(24L, mainBack.spentSinceSnapshot);
        assertEquals("非主控角色快照独立", 5L, Exec.longOf(altBack.recovery.get("count")));
        assertEquals(0L, altBack.spentSinceSnapshot);
        assertNull("两个角色都不写旧键（旧键仍归主控整轮角色使用）",
                store.getString(LEGACY_SCIENCE_KEY));
    }

    @Test
    public void perRoleRecoverySpent_notCrossDeducted() {
        // 角色 A 自己捐掉 24 次后，角色 B 的免费次数不受影响（各自 state 实例/键）
        Map<String, Object> fresh = recovery(24L, 25L, 1000L, 600L);

        ServerTasks.AllianceScienceState a = new ServerTasks.AllianceScienceState();
        assertEquals(24L, ServerTasks.resolveFreeCount(a, fresh, 999L));
        ServerTasks.recordDonated(a, 24L);
        assertEquals("A 同快照不再放行", 0L, ServerTasks.resolveFreeCount(a, fresh, 999L));

        ServerTasks.AllianceScienceState b = new ServerTasks.AllianceScienceState();
        assertEquals("B 自己的 24 次不被 A 的已消费扣减", 24L,
                ServerTasks.resolveFreeCount(b, fresh, 999L));
    }

    @Test
    public void roleUidZero_behavesLikeBeforeForScienceState() {
        StateStore store = freshStore();
        TaskContext legacy = legacyCtx(store);
        ServerTasks.saveAllianceScience(legacy, stateWith(24L, 3L));
        ServerTasks.AllianceScienceState back = ServerTasks.loadAllianceScience(legacy);
        assertEquals(24L, Exec.longOf(back.recovery.get("count")));
        assertEquals(3L, back.spentSinceSnapshot);
        assertNotNull(store.getString(LEGACY_SCIENCE_KEY));
    }

    // ==================== 迁移 / mod / 台账 ====================

    /** 非主控角色首次读角色键 miss → 回退旧键作初始快照；随后 save 只写角色键，旧键不被改写。 */
    @Test
    public void legacyMigration_readsOldKeyOnceThenWritesRoleKey() {
        StateStore store = freshStore();
        TaskContext legacy = legacyCtx(store);
        ServerTasks.saveAllianceScience(legacy, stateWith(24L, 0L));
        String legacyRaw = store.getString(LEGACY_SCIENCE_KEY);
        assertNotNull(legacyRaw);

        TaskContext alt = roleCtx(store, UID_ALT);
        ServerTasks.AllianceScienceState migrated = ServerTasks.loadAllianceScience(alt);
        assertTrue("角色键 miss 时允许回退旧键作初始快照", migrated.recoveryKnown());
        assertEquals(24L, Exec.longOf(migrated.recovery.get("count")));
        assertNull("读取不写角色键（只做迁移读取）",
                store.getString(ServerTasks.allianceScienceKey(FPID, SID, UID_ALT)));

        ServerTasks.saveAllianceScience(alt, migrated);
        assertNotNull("随后写角色键",
                store.getString(ServerTasks.allianceScienceKey(FPID, SID, UID_ALT)));
        assertEquals("旧键（主控角色）不能被非主控角色改写", legacyRaw,
                store.getString(LEGACY_SCIENCE_KEY));
        assertEquals(24L, Exec.longOf(ServerTasks.loadAllianceScience(legacy)
                .recovery.get("count")));
    }

    /** mod 状态：角色键优先；首次 miss 回退旧键一次；写只写角色键，主控旧键不变。 */
    @Test
    public void modState_roleKeyFirstThenLegacyFallbackOnce() {
        StateStore store = freshStore();
        TaskContext legacy = legacyCtx(store);
        legacy.saveModText(MOD_FN, "no_free_until", "1900000000");
        String legacyRaw = store.getString("mod:48460403:888:alliance_donate");

        TaskContext alt = roleCtx(store, UID_ALT);
        assertEquals("角色键 miss → 回退旧键一次",
                "1900000000", alt.modState(MOD_FN).optString("no_free_until", ""));

        alt.saveModText(MOD_FN, "no_free_until", "0");
        assertNotNull(store.getString("mod:48460403:888:88280792:alliance_donate"));
        assertEquals("旧键不被非主控角色改写", legacyRaw,
                store.getString("mod:48460403:888:alliance_donate"));
        assertEquals("主控读取自己的状态不变",
                "1900000000", legacy.modState(MOD_FN).optString("no_free_until", ""));

        TaskContext other = roleCtx(store, UID_MAIN);
        assertEquals("另一角色首次也只回退旧键（不会读到 alt 的角色键）",
                "1900000000", other.modState(MOD_FN).optString("no_free_until", ""));
    }

    /** 台账：按角色隔离，读取不做跨角色回退（避免把主控已领误当本角色已领 → 漏领）。 */
    @Test
    public void claimLedger_roleIsolatedAndNoCrossRoleFallback() {
        StateStore store = freshStore();
        TaskContext legacy = legacyCtx(store);
        ClaimLedger.mark(legacy, LEDGER_SCOPE, LEDGER_ITEM);
        assertTrue(ClaimLedger.isClaimed(legacy, LEDGER_SCOPE, LEDGER_ITEM));

        TaskContext alt = roleCtx(store, UID_ALT);
        assertFalse("非主控角色不能继承主控角色的已领台账（宁可多请求一次）",
                ClaimLedger.isClaimed(alt, LEDGER_SCOPE, LEDGER_ITEM));
        ClaimLedger.mark(alt, LEDGER_SCOPE, LEDGER_ITEM);
        assertTrue(ClaimLedger.isClaimed(alt, LEDGER_SCOPE, LEDGER_ITEM));
        assertNotNull(store.getString("ledger:48460403:888:88280792:alliance_donate"));
        assertTrue("主控旧台账仍在且仍生效",
                ClaimLedger.isClaimed(legacy, LEDGER_SCOPE, LEDGER_ITEM));

        TaskContext other = roleCtx(store, UID_MAIN);
        assertFalse("另一角色台账互不影响",
                ClaimLedger.isClaimed(other, LEDGER_SCOPE, LEDGER_ITEM));
    }

    // ==================== SessionKeeper 接线契约 ====================

    /** 公开入口固定签名（Lead 用它对非主控角色每 30s 调用）。 */
    @Test
    public void runAllianceDonate_publicStaticEntrySignature() throws Exception {
        Method m = ServerTasks.class.getMethod("runAllianceDonate", TaskContext.class);
        assertTrue("必须 public", Modifier.isPublic(m.getModifiers()));
        assertTrue("必须 static", Modifier.isStatic(m.getModifiers()));
        assertEquals(TaskOutcome.class, m.getReturnType());
        assertEquals(1, m.getParameterCount());
        assertEquals(TaskContext.class, m.getParameterTypes()[0]);
    }
}
