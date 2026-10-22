package com.carlos.vault.daily.guardian;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P4 预驻防单测：触发条件（H3 极限惯犯/H2 多路并发）、门控去重与反应式互斥、
 * 冷却账本、默认关闭、FnDetail 参数 chips 与默认值。
 */
public class GuardianPreGarrisonTest {

    private static final String FP = "fp-pre-test";
    private static final long KID = 990002L;
    private static final long TARGET = 987654321L;
    private static final long T0 = 1_700_000_000_000L;

    // ------------------------------------------------------------------ 触发判定

    @Test
    public void trigger_extremeRecent_anyMarchTowardAlly() {
        // H3：EXTREME 惯犯近期活跃 → 侦查也算（侦查=寻的信号，落地<9s 场景反应式无解）
        assertTrue(GuardianPreGarrison.shouldTrigger(true, true, 0, false));
        assertTrue(GuardianPreGarrison.shouldTrigger(true, true, 0, true));
    }

    @Test
    public void trigger_extremeNotRecent_noTrigger() {
        // 锁存在但 30 分钟无信号 → 不预驻（阵地战意义存疑，且避免无限续驻）
        assertFalse(GuardianPreGarrison.shouldTrigger(true, false, 0, true));
        assertFalse(GuardianPreGarrison.shouldTrigger(true, false, 5, true));
    }

    @Test
    public void trigger_h2_multiProng_needsTwoQualifiedAttacks() {
        // H2：≥2 条 QUALIFIED 进攻并发（本条须为进攻）
        assertTrue(GuardianPreGarrison.shouldTrigger(false, false, 2, true));
        assertTrue(GuardianPreGarrison.shouldTrigger(false, false, 3, true));
        assertFalse("仅 1 路 → 反应式路径处理，不预驻",
                GuardianPreGarrison.shouldTrigger(false, false, 1, true));
        assertFalse("侦查不算多路并发",
                GuardianPreGarrison.shouldTrigger(false, false, 2, false));
    }

    // ------------------------------------------------------------------ 门控（与反应式发射互斥/仲裁）

    @Test
    public void gate_offByDefault() {
        assertEquals(GuardianPreGarrison.Gate.SKIP_OFF,
                GuardianPreGarrison.gate(false, false, false, false, 0, 2));
    }

    @Test
    public void gate_order_cooldownBeforeDedupBeforeReserve() {
        assertEquals(GuardianPreGarrison.Gate.SKIP_COOLDOWN,
                GuardianPreGarrison.gate(true, true, true, true, 5, 2));
        assertEquals(GuardianPreGarrison.Gate.SKIP_GARRISONED,
                GuardianPreGarrison.gate(true, false, true, true, 5, 2));
        assertEquals(GuardianPreGarrison.Gate.SKIP_REACTIVE_BUSY,
                GuardianPreGarrison.gate(true, false, false, true, 0, 2));
    }

    @Test
    public void gate_reserveKeptOneSlotForReactive() {
        // MAX_CONCURRENT=2：预驻防只许占 1 队，恒留 1 队给反应式（手册 §4.1 保留池）
        assertEquals(GuardianPreGarrison.Gate.ALLOW,
                GuardianPreGarrison.gate(true, false, false, false, 0, 2));
        assertEquals(GuardianPreGarrison.Gate.SKIP_RESERVE_FULL,
                GuardianPreGarrison.gate(true, false, false, false, 1, 2));
        assertEquals(GuardianPreGarrison.Gate.SKIP_RESERVE_FULL,
                GuardianPreGarrison.gate(true, false, false, false, 2, 2));
    }

    // ------------------------------------------------------------------ 冷却账本（去重）

    @Test
    public void cooldown_windowAndIsolation() {
        String fp = FP + "-cd";
        GuardianPreGarrison.markCooldown(fp, KID, TARGET, T0);
        assertTrue(GuardianPreGarrison.coolingDown(fp, KID, TARGET, T0 + 1));
        assertTrue(GuardianPreGarrison.coolingDown(fp, KID, TARGET,
                T0 + GuardianPreGarrison.COOLDOWN_MS - 1));
        assertFalse("超冷却窗可再次触发",
                GuardianPreGarrison.coolingDown(fp, KID, TARGET, T0 + GuardianPreGarrison.COOLDOWN_MS));
        assertFalse("同目标按账号隔离",
                GuardianPreGarrison.coolingDown(fp + "-x", KID, TARGET, T0 + 1));
        assertFalse("其他目标不受影响",
                GuardianPreGarrison.coolingDown(fp, KID, TARGET + 1, T0 + 1));
    }

    @Test
    public void constants_sane() {
        assertEquals("冷却 10 分钟", 600_000L, GuardianPreGarrison.COOLDOWN_MS);
        assertEquals("无威胁滞留 10 分钟后由清扫器撤回", 600L, GuardianPreGarrison.DWELL_SEC);
        assertEquals("惯犯近期活跃窗口 30 分钟", 1800_000L, GuardianPreGarrison.RECENT_ACTIVE_MS);
        assertEquals("恒留 1 队给反应式", 1, GuardianPreGarrison.REACTIVE_RESERVE);
    }

    // ------------------------------------------------------------------ 默认关闭不改变现有行为 + FnDetail chips

    @Test
    public void fnDetail_newSubsPresent_withDefaultsOff() {
        java.util.List<com.carlos.vault.daily.FnDetail.Sub> subs =
                com.carlos.vault.daily.FnDetail.of("guardian");
        com.carlos.vault.daily.FnDetail.Sub hold = sub(subs, "hold_garrison_min");
        assertNotNull("常驻戒备子项存在", hold);
        assertFalse("常驻戒备默认关", hold.defOn);
        assertEquals("策略首档=关闭（默认 0 不撤防路径不变）", "0", hold.strategies[0][0]);
        assertEquals(5, hold.strategies.length);

        com.carlos.vault.daily.FnDetail.Sub pre = sub(subs, "pre_garrison");
        assertNotNull("预驻防子项存在", pre);
        assertFalse("预驻防默认关", pre.defOn);
        assertEquals("策略首档=关闭", "0", pre.strategies[0][0]);
        assertEquals(2, pre.strategies.length);

        com.carlos.vault.daily.FnDetail.Sub prio = sub(subs, "pre_garrison_targets");
        assertNotNull("目标优先级子项存在", prio);
        assertEquals("默认受威胁优先", "threat", prio.strategies[0][0]);
        assertEquals(2, prio.strategies.length);
    }

    @Test
    public void fnDetail_existingSubsUntouched() {
        java.util.List<com.carlos.vault.daily.FnDetail.Sub> subs =
                com.carlos.vault.daily.FnDetail.of("guardian");
        // 原有子项顺序与 id 不变（增量改动，不破坏既有配置/页面）
        assertEquals("watch", subs.get(0).id);
        assertEquals("intercept", subs.get(1).id);
        assertEquals("0", sub(subs, "power_min").strategies[0][0]);
        assertEquals("300", sub(subs, "eta_max").strategies[0][0]);
        assertEquals("1000", sub(subs, "delta_ms").strategies[0][0]);
    }

    // ------------------------------------------------------------------ 合成预驻防 Track（威胁表注册）

    @Test
    public void preTrack_negativeId_neverCollidesWithRealMarches() {
        GuardianEngine.Track cand = new GuardianEngine.Track(777L, KID, FP, 35605979L, 2L,
                TARGET, 100, 200, 5000L, System.currentTimeMillis() / 1000L + 60);
        GuardianEngine.Track pre = GuardianEngine.newPreTrack(FP, KID, cand);
        assertNotNull(pre);
        try {
            assertTrue("合成 id 必须为负（0 递减，不与真实 march id 冲突）", pre.marchId < 0);
            assertTrue(pre.preGarrison);
            assertEquals(KID, pre.kid);
            assertEquals(TARGET, pre.targetUid);
            long dwellEta = pre.endServerSec - System.currentTimeMillis() / 1000L;
            assertTrue("滞留窗 ≈ DWELL_SEC", Math.abs(dwellEta - GuardianPreGarrison.DWELL_SEC) <= 5);
            assertNotNull("已注册进威胁表（2878 重试与清扫器语义依赖）",
                    GuardianEngine.trackOf(KID, pre.marchId));
        } finally {
            GuardianEngine.dropTrack(KID, pre.marchId);
        }
        assertNull("摘除后不再可见", GuardianEngine.trackOf(KID, pre.marchId));
    }

    @Test
    public void preTrack_uniqueIds_andInvalidCandidateRejected() {
        GuardianEngine.Track cand = new GuardianEngine.Track(778L, KID, FP, 35605979L, 2L,
                TARGET, 5, 6, 1L, System.currentTimeMillis() / 1000L + 60);
        GuardianEngine.Track a = GuardianEngine.newPreTrack(FP, KID, cand);
        GuardianEngine.Track b = GuardianEngine.newPreTrack(FP, KID, cand);
        try {
            assertNotNull(a);
            assertNotNull(b);
            assertNotEquals(a.marchId, b.marchId);
        } finally {
            if (a != null) GuardianEngine.dropTrack(KID, a.marchId);
            if (b != null) GuardianEngine.dropTrack(KID, b.marchId);
        }
        // 缺坐标（无法派驻）→ 拒绝
        GuardianEngine.Track noxy = new GuardianEngine.Track(779L, KID, FP, 35605979L, 2L,
                TARGET, 0, 0, 1L, System.currentTimeMillis() / 1000L + 60);
        assertNull(GuardianEngine.newPreTrack(FP, KID, noxy));
    }

    private static com.carlos.vault.daily.FnDetail.Sub sub(
            java.util.List<com.carlos.vault.daily.FnDetail.Sub> subs, String id) {
        for (com.carlos.vault.daily.FnDetail.Sub s : subs) if (s.id.equals(id)) return s;
        return null;
    }
}
