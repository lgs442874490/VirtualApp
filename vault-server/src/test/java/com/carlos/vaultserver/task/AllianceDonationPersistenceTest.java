package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 联盟科技捐献持久化/纯函数单测（P0 2026-10-28，bug 5「联盟科技捐献满了没有自动去捐」）。
 *
 * <p>生产实证（vault-20261027/28.log）：
 * <ul>
 *   <li>会话启动 17:13 → notify_alliance_science_init 17:50:45 才到，37 分钟“科技数据未同步”一次不捐；</li>
 *   <li>{@code 科技捐献失败 id=6023 level=1 code=1507} 883 次/日、{@code code=1584} 单目标码阻塞整轮。</li>
 * </ul>
 * 修复口径：sciences/recommend/recovery 持久化到 {@code srv:alliance_science:<fpid>:<sid>}，推送漏发/
 * 会话重启后仍可捐；免费次数「未知不盲捐」但可由持久化快照投影恢复；单目标不可捐→下一个目标。
 */
public class AllianceDonationPersistenceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "服务器");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static ServerTasks.AllianceScienceState sampleState() {
        ServerTasks.AllianceScienceState st = new ServerTasks.AllianceScienceState();
        st.sciences.put(6021L, new long[]{5L, 0L});
        st.sciences.put(6023L, new long[]{0L, 1_178_984_370L});
        st.recommend = 6023L;
        st.recovery = map("id", 205L, "count", 2L, "max_count", 5L,
                "next_calc_ts", 1_800_000_000L, "recover", 1L, "interval", 600L);
        st.noFreeUntilSec = 1_800_000_123L;
        st.targetCooldownUntilSec = 1_800_000_456L;
        return st;
    }

    // ==================== 持久化 round-trip ====================

    @Test
    public void encodeDecode_roundTrip() {
        ServerTasks.AllianceScienceState in = sampleState();
        ServerTasks.AllianceScienceState out =
                ServerTasks.decodeAllianceScience(ServerTasks.encodeAllianceScience(in));
        assertEquals("科技表条目数", in.sciences.size(), out.sciences.size());
        assertEquals("lv 保留", 5L, out.sciences.get(6021L)[0]);
        assertEquals("donate 保留", 1_178_984_370L, out.sciences.get(6023L)[1]);
        assertEquals("推荐科技", 6023L, out.recommend);
        assertNotNull(out.recovery);
        assertEquals(2L, Exec.longOf(out.recovery.get("count")));
        assertEquals(5L, Exec.longOf(out.recovery.get("max_count")));
        assertEquals(1_800_000_000L, Exec.longOf(out.recovery.get("next_calc_ts")));
        assertEquals(1_800_000_123L, out.noFreeUntilSec);
        assertEquals(1_800_000_456L, out.targetCooldownUntilSec);
        assertTrue(out.sciencesKnown());
        assertTrue(out.recoveryKnown());
    }

    @Test
    public void decode_defensiveOnEmptyAndGarbage() {
        ServerTasks.AllianceScienceState empty =
                ServerTasks.decodeAllianceScience(null);
        assertFalse(empty.sciencesKnown());
        assertFalse(empty.recoveryKnown());
        assertEquals(0L, empty.recommend);

        ServerTasks.AllianceScienceState broken = ServerTasks.decodeAllianceScience("{not-json");
        assertFalse(broken.sciencesKnown());
        assertFalse(broken.recoveryKnown());

        ServerTasks.AllianceScienceState blank = ServerTasks.decodeAllianceScience("   ");
        assertFalse(blank.sciencesKnown());
    }

    @Test
    public void persistence_roundTripThroughStateStore() {
        TaskContext tc = context();
        assertEquals("srv:alliance_science:48460403:888",
                ServerTasks.allianceScienceKey(tc.fpid, tc.sid));
        ServerTasks.saveAllianceScience(tc, sampleState());

        // 模拟会话重启/推送漏发：新 TaskContext 共享同一 StateStore 再读
        TaskContext restarted = new TaskContext(null, "48460403", 888L,
                tc.store, Collections.emptyMap(), "服务器");
        ServerTasks.AllianceScienceState loaded = ServerTasks.loadAllianceScience(restarted);
        assertEquals(2, loaded.sciences.size());
        assertEquals(5L, loaded.sciences.get(6021L)[0]);
        assertEquals(6023L, loaded.recommend);
        assertNotNull(loaded.recovery);
        assertEquals(1_800_000_123L, loaded.noFreeUntilSec);
    }

    @Test
    public void persistence_absentKeyIsEmptyState() {
        ServerTasks.AllianceScienceState st = ServerTasks.loadAllianceScience(context());
        assertFalse(st.sciencesKnown());
        assertFalse(st.recoveryKnown());
    }

    // ==================== 合并/免费次数合并 ====================

    @Test
    public void mergeSciences_freshOverridesButKeepsHigherLv() {
        Map<Long, long[]> into = new LinkedHashMap<>();
        into.put(1L, new long[]{2L, 10L});
        Map<Long, long[]> fresh = new LinkedHashMap<>();
        fresh.put(1L, new long[]{1L, 0L});    // 旧推送：lv 更低，保留更高 lv
        fresh.put(2L, new long[]{3L, 5L});    // 新增
        ServerTasks.mergeSciences(into, fresh);
        assertEquals(2L, into.get(1L)[0]);
        assertEquals(10L, into.get(1L)[1]);
        assertEquals(3L, into.get(2L)[0]);

        Map<Long, long[]> higher = new LinkedHashMap<>();
        higher.put(1L, new long[]{4L, 7L});
        ServerTasks.mergeSciences(into, higher);
        assertEquals("更高 lv 覆盖", 4L, into.get(1L)[0]);
        assertEquals(7L, into.get(1L)[1]);
    }

    @Test
    public void effectiveFreeCount_takesMinWhenBothKnown() {
        assertEquals("都未知 → 未知（不盲捐）", -1L, ServerTasks.effectiveFreeCount(-1L, -1L));
        assertEquals("仅引擎已知", 2L, ServerTasks.effectiveFreeCount(2L, -1L));
        assertEquals("仅持久化已知", 3L, ServerTasks.effectiveFreeCount(-1L, 3L));
        assertEquals("都已知取小（宁漏不花）", 2L, ServerTasks.effectiveFreeCount(2L, 5L));
        assertEquals(1L, ServerTasks.effectiveFreeCount(4L, 1L));
    }

    // ==================== 免费次数投影/归一化 ====================

    @Test
    public void projectedRecoveryCount_matchesWeb() {
        // 快照 2 次，未到 next_calc_ts → 2
        assertEquals(2L, ServerTasks.projectedRecoveryCount(
                map("count", 2, "max_count", 5, "next_calc_ts", 1000, "recover", 1, "interval", 600), 999));
        // 到点后按周期补齐：1 + (1600-1000)/600 = 2 个周期 → 2+2=4
        assertEquals(4L, ServerTasks.projectedRecoveryCount(
                map("count", 2, "max_count", 5, "next_calc_ts", 1000, "recover", 1, "interval", 600), 1600));
        // 上限 max_count 封顶
        assertEquals(5L, ServerTasks.projectedRecoveryCount(
                map("count", 4, "max_count", 5, "next_calc_ts", 1000, "recover", 2, "interval", 600), 9999));
        // max_count 未知 → 只返回快照值（不猜）
        assertEquals(3L, ServerTasks.projectedRecoveryCount(
                map("count", 3, "max_count", 0, "next_calc_ts", 1000, "recover", 1, "interval", 600), 9999));
        assertEquals(0L, ServerTasks.projectedRecoveryCount(null, 100));
    }

    @Test
    public void normalizeRecovery_deductsAndAdvancesNextCalc() {
        Map<String, Object> rec = map("count", 2, "max_count", 5,
                "next_calc_ts", 1000, "recover", 1, "interval", 600);
        Map<String, Object> out = ServerTasks.normalizeRecovery(rec, 1600, 3L);
        assertEquals("扣减后剩余次数", 3L, Exec.longOf(out.get("count")));
        assertTrue("next_calc_ts 推进到未来第一个恢复点，避免重复补算",
                Exec.longOf(out.get("next_calc_ts")) > 1600);
        assertEquals(1600L + 600L, Exec.longOf(out.get("next_calc_ts")));
        // 扣到 0 也不为负
        assertEquals(0L, Exec.longOf(ServerTasks.normalizeRecovery(rec, 1600, -5L).get("count")));
        // max_count 封顶
        assertEquals(5L, Exec.longOf(ServerTasks.normalizeRecovery(rec, 1600, 99L).get("count")));
        assertNull(ServerTasks.normalizeRecovery(null, 1600, 1L));
    }

    @Test
    public void localDeduction_notReAddedByProjection() {
        // 场景：引擎/持久化快照到点后投影为 3 次免费；捐 1 次后本地归一化 count=2、next 推到未来，
        // 下一次投影不能再把已消费周期补算回来（否则会重复超捐 → 每轮 1528）。
        Map<String, Object> rec = map("count", 2, "max_count", 5,
                "next_calc_ts", 1000, "recover", 1, "interval", 600);
        long now = 1000L;                                    // 到点：投影 2+1=3
        assertEquals(3L, ServerTasks.projectedRecoveryCount(rec, now));
        Map<String, Object> after = ServerTasks.normalizeRecovery(rec, now, 3L - 1L);
        assertEquals("扣减后剩余 2 次", 2L, Exec.longOf(after.get("count")));
        assertEquals("归一化后立即再投影仍是 2 次", 2L,
                ServerTasks.projectedRecoveryCount(after, now));
        // 下一个恢复点（1600）才 +1
        assertEquals(3L, ServerTasks.projectedRecoveryCount(after, 1600));
    }

    @Test
    public void donationNoFreeUntil_usesRecoveryOrFallback() {
        Map<String, Object> rec = map("count", 0, "max_count", 5,
                "next_calc_ts", 2000, "recover", 1, "interval", 600);
        assertEquals("next_calc_ts 在未来 → 就等它", 2000L,
                ServerTasks.donationNoFreeUntil(rec, 1500));
        assertEquals("next_calc_ts 已过 → 下一个周期", 2600L,
                ServerTasks.donationNoFreeUntil(rec, 2000));
        assertEquals("无恢复数据 → 至少 300s 兜底", 1800L,
                ServerTasks.donationNoFreeUntil(null, 1500));
        assertEquals("next_calc_ts/interval 都缺失 → 300s 兜底", 1800L,
                ServerTasks.donationNoFreeUntil(map("next_calc_ts", 0, "interval", 0), 1500));
    }

    // ==================== 目标排序 / 跳过码 ====================

    @Test
    public void orderedTargets_recommendedFirstThenAscending() {
        Map<Long, long[]> sciences = new LinkedHashMap<>();
        sciences.put(6023L, new long[]{0L, 99L});
        sciences.put(5006L, new long[]{5L, 0L});
        sciences.put(4008L, new long[]{4L, 0L});
        List<long[]> targets = ServerTasks.orderedScienceTargets(sciences, 6023L);
        assertEquals(3, targets.size());
        assertEquals("推荐科技优先", 6023L, targets.get(0)[0]);
        assertEquals(4008L, targets.get(1)[0]);
        assertEquals(5006L, targets.get(2)[0]);
        // 推荐 id 不在科技表里 → 纯 id 升序
        assertEquals(4008L, ServerTasks.orderedScienceTargets(sciences, 999L).get(0)[0]);
        assertTrue(ServerTasks.orderedScienceTargets(null, 1L).isEmpty());
    }

    @Test
    public void skipCodes_coverProductionStuckCodes() {
        // 网页版 SKIPPABLE_TARGET_CODES
        assertTrue(ServerTasks.isDonationTargetSkipCode(2));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1570));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1571));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1574));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1576));   // 研究中
        assertTrue(ServerTasks.isDonationTargetSkipCode(1577));   // 捐献已满
        assertTrue(ServerTasks.isDonationTargetSkipCode(1579));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1580));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1581));   // 条件不满足
        // 生产实证新增：1507（今日已捐献 883 次/日卡死）、1584（id=6023 目标不可捐）
        assertTrue("1507 只代表该科技已捐，不能阻塞整轮", ServerTasks.isDonationTargetSkipCode(1507));
        assertTrue("1584 目标级不可捐，继续下一个目标", ServerTasks.isDonationTargetSkipCode(1584));
        // 非跳过码：需要停轮处理
        assertFalse(ServerTasks.isDonationTargetSkipCode(1528));   // 免费次数用完
        assertFalse(ServerTasks.isDonationTargetSkipCode(1545));   // 资源不足
        assertFalse(ServerTasks.isDonationTargetSkipCode(1510));
        assertFalse(ServerTasks.isDonationTargetSkipCode(0));
    }

    @Test
    public void collectSciences_nullEngineIsEmpty() {
        assertTrue(ServerTasks.collectSciences(null).isEmpty());
        assertTrue(ServerTasks.recoveryOf(null) == null);
        assertEquals(0L, ServerTasks.recommendedScienceId(null));
    }

    @Test
    public void rememberScience_keepsHigherLevel() {
        Map<Long, long[]> sciences = new LinkedHashMap<>();
        ServerTasks.rememberScience(sciences, map("id", 6023, "lv", 0, "donate", 100));
        assertEquals(0L, sciences.get(6023L)[0]);
        assertEquals(100L, sciences.get(6023L)[1]);
        // 同级 lv 的新推送要更新 donate（exp_update 单条累积就是同级递增）
        ServerTasks.rememberScience(sciences, map("id", 6023, "lv", 0, "donate", 5));
        assertEquals(5L, sciences.get(6023L)[1]);
        // 更高 lv 覆盖
        ServerTasks.rememberScience(sciences, map("id", 6023, "lv", 1, "donate", 7));
        assertEquals(1L, sciences.get(6023L)[0]);
        assertEquals(7L, sciences.get(6023L)[1]);
        // 非法 id 忽略
        ServerTasks.rememberScience(sciences, map("id", 0, "lv", 5));
        assertEquals(1, sciences.size());
    }
}
