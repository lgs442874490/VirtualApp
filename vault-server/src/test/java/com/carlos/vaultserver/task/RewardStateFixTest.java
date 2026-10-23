package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次4 回归（2026-10-23）：R7 登录好礼状态化 / R8 背包解析 / C3 nextWakeAt 接线。
 *
 * <p>对照：qa/audit-20261023/reward-gaps.md §2#7、§4（notify 形态）、§5.3（背包根因）、§6（唤醒）；
 * 抓包 tools/bundle_re/proto_log.txt:36（背包 init）、:68（fourteen_sign）、:146（sevensign）、
 * audit_1014/proto_log_dev_old.txt（sign_21day / backpack_update）。
 *
 * <p>只测纯函数/状态判定：不依赖真协议、真会话；不覆盖任何新增钻石路径（本批零付费路径）。
 */
public class RewardStateFixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------- helpers ----------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
    }

    // =======================================================================
    // R7 登录好礼状态化（DailyLoginRewardState.java:96-136 / Snapshot:20-42）
    // =======================================================================

    @Test
    public void fourteenSign_capturedShape_notClaimable() {
        // 抓包 proto_log.txt:68：sign_days=4，free_award_record=[1,2,3,4]（该日奖励已领）
        Map<String, Object> n = map(
                "server_index", 5004001L,
                "last_sign_ts", 1791820800L,
                "sign_days", 4L,
                "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L),
                "buy_award_record", Collections.emptyList(),
                "has_pay", "false");
        ServerTasks.SignTrackState s = ServerTasks.parseFourteenSign(n);
        assertTrue(s.ready);
        assertEquals(4, s.signDays);
        assertEquals(7, s.totalDays);
        assertEquals(0, s.pendingFree());
        assertEquals(0, s.pendingPaid());
        assertFalse(s.claimable());
        assertFalse(s.completed());
        assertEquals(3, s.remainingDays());
        String text = s.modText(false);
        assertTrue("mod 需含已连签天数: " + text, text.contains("已连签 4/7 天"));
        assertTrue("mod 需含无可领: " + text, text.contains("无可领"));
        assertTrue("mod 需含周期剩余: " + text, text.contains("周期剩 3 天"));
    }

    @Test
    public void fourteenSign_newDayClaimable_includingEpicTrack() {
        Map<String, Object> n = map(
                "sign_days", 5L,
                "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L),
                "buy_award_record", Arrays.asList((Object) 1L, 2L, 3L),
                "has_pay", "true");   // 抓包为字符串 "true"/"false"
        ServerTasks.SignTrackState s = ServerTasks.parseFourteenSign(n);
        assertEquals(1, s.pendingFree());
        assertEquals(2, s.pendingPaid());
        assertTrue(s.claimable());
        assertTrue(s.modText(false).contains("免费档待领 1 天"));
        assertTrue(s.modText(false).contains("付费档待领 2 天"));
        assertTrue(s.modText(true).contains("今日已领"));
    }

    @Test
    public void fourteenSign_missingNotify_notReady() {
        ServerTasks.SignTrackState s = ServerTasks.parseFourteenSign(null);
        assertFalse(s.ready);
        assertFalse(s.claimable());
        assertEquals("十四天双轨状态未同步", s.modText(false));
        assertEquals(0, s.pendingFree());
    }

    @Test
    public void sevenSign_completeAndInProgress() {
        // 抓包 proto_log.txt:146：sign_days=7，get_award_info=[1..7]
        ServerTasks.SignTrackState done = ServerTasks.parseSevenSign(map(
                "sign_days", 7L,
                "get_award_info", Arrays.asList((Object) 1L, 2L, 3L, 4L, 5L, 6L, 7L),
                "lastsign_time", 1752693418L));
        assertTrue(done.ready);
        assertTrue(done.completed());
        assertFalse(done.claimable());
        assertEquals(0, done.remainingDays());
        assertTrue(done.modText(true).contains("7/7 天"));

        ServerTasks.SignTrackState inProgress = ServerTasks.parseSevenSign(map(
                "sign_days", 3L,
                "get_award_info", Arrays.asList((Object) 1L, 2L)));
        assertEquals(1, inProgress.pendingFree());
        assertTrue(inProgress.claimable());
        assertEquals(4, inProgress.remainingDays());
    }

    @Test
    public void day21Sign_freeTrackClaimableAndPaidLocked() {
        // 抓包 audit_1014/proto_log_dev_old.txt：sign_days=9，free_award_record=[1..9]
        ServerTasks.SignTrackState done = ServerTasks.parse21DaySign(map(
                "sign_days", 9L,
                "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
                "buy_award_record", Collections.emptyList(),
                "has_pay", "false",
                "start_ts", 1791043200L));
        assertTrue(done.ready);
        assertEquals(21, done.totalDays);
        assertFalse(done.claimable());
        assertEquals(12, done.remainingDays());

        ServerTasks.SignTrackState pending = ServerTasks.parse21DaySign(map(
                "sign_days", 10L,
                "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
                "has_pay", "false"));
        assertEquals(1, pending.pendingFree());
        assertTrue(pending.claimable());
    }

    @Test
    public void receivedSignDays_acceptsAllNotifyShapesAndFiltersJunk() {
        // 数组数字
        assertEquals(Arrays.asList(1, 2, 3),
                new java.util.ArrayList<>(ServerTasks.receivedSignDays(Arrays.asList(
                        (Object) 1L, 2L, 3L))));
        // 数组内对象 {id:1}；重复去重；非法值过滤
        assertEquals(Arrays.asList(1, 4),
                new java.util.ArrayList<>(ServerTasks.receivedSignDays(Arrays.asList(
                        (Object) map("id", 1L), map("id", 1L), map("id", 4L),
                        map("id", 0L), "bad"))));
        // Map 形态 {"1.0":true,"2.0":{id:2}}（网页版 normalizeDays 兼容 Map）
        assertEquals(Arrays.asList(1, 2),
                new java.util.ArrayList<>(ServerTasks.receivedSignDays(map(
                        "1.0", Boolean.TRUE, "2.0", map("id", 2L)))));
        assertTrue(ServerTasks.receivedSignDays(null).isEmpty());
    }

    @Test
    public void signRewardDetail_matchesPerTrackEvidence() {
        com.carlos.vault.wjdr.protocol.GameMessage fourteen =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        fourteen.getContent().putAll(map("rewards",
                Arrays.asList((Object) map("k", 100003L, "v", 5L))));
        assertEquals("钻石×5", ServerTasks.signRewardDetail(fourteen, "fourteen"));

        com.carlos.vault.wjdr.protocol.GameMessage seven =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        seven.getContent().putAll(map("award_success_list",
                Arrays.asList((Object) 1L, 2L)));
        assertEquals("×2", ServerTasks.signRewardDetail(seven, "seven"));
        // 空列表 = 无到账证据（不得伪造 CLAIMED）
        com.carlos.vault.wjdr.protocol.GameMessage empty =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        empty.getContent().putAll(map("award_success_list", Collections.emptyList()));
        assertEquals("", ServerTasks.signRewardDetail(empty, "seven"));
        assertEquals("", ServerTasks.signRewardDetail(null, "day21"));
    }

    @Test
    public void signEvidence_keepsLegacyStringRewardShape() {
        // 旧版 classifySimple / CaptureTasksTest.FakeEngine 形态：rewards 是字符串（RewardText 解析不出明细）
        com.carlos.vault.wjdr.protocol.GameMessage legacy =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        legacy.getContent().putAll(map("code", 0, "rewards", "diamond:10"));
        assertTrue("14 天轨：含奖励 key 即到账证据（旧 classifySimple 口径）",
                ServerTasks.hasSignRewardEvidence(legacy, "fourteen"));
        assertEquals("展示明细解析不出时为空，但不影响 CLAIMED 判定",
                "", ServerTasks.signRewardDetail(legacy, "fourteen"));
        // 七天/21 天保持旧实现“非空才叫到账”的严格口径
        assertFalse(ServerTasks.hasSignRewardEvidence(legacy, "seven"));
        assertTrue(ServerTasks.hasSignRewardEvidence(legacy, "day21"));
        assertFalse(ServerTasks.hasSignRewardEvidence(null, "fourteen"));
    }

    @Test
    public void signClaimedToday_prefersLedgerThenState() {
        TaskContext tc = context();
        ServerTasks.SignTrackState doneState = ServerTasks.parseFourteenSign(map(
                "sign_days", 4L,
                "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L)));
        assertTrue("状态显示已签名天数全部领完 → 视为今日已领",
                ServerTasks.signClaimedToday(tc, "14", doneState));
        assertFalse("状态未同步时不臆测今日已领",
                ServerTasks.signClaimedToday(tc, "14", ServerTasks.parseFourteenSign(null)));
        ClaimLedger.mark(tc, "login_rewards", "7");
        assertTrue(ServerTasks.signClaimedToday(tc, "7", ServerTasks.parseSevenSign(null)));
    }

    @Test
    public void claimSignTrack_ledgerAndStateGates_withoutProtocol() {
        // engine=null：若闸门未被拦住会 NPE，据此验证“台账/状态先行、可领才发请求”。
        TaskContext tc = context();
        ClaimLedger.mark(tc, "login_rewards", "14");
        StringBuilder msg = new StringBuilder();
        int[] byLedger = ServerTasks.claimSignTrack(tc, "14", "登录好礼(14天双轨)",
                "req_fourteen_sign_get_all_reward",
                ServerTasks.parseFourteenSign(map("sign_days", 5L,
                        "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L))),
                "fourteen", true, msg, new StringBuilder());
        assertEquals(0, byLedger[0]);
        assertEquals(1, byLedger[1]);   // SKIP：今日已领（台账）
        assertTrue(msg.toString().contains("今日已领"));

        // 状态已全领（无可领档）→ 不请求
        StringBuilder msg2 = new StringBuilder();
        int[] byState = ServerTasks.claimSignTrack(tc, "21", "21天签到",
                "req_sign_21day_get_all_reward",
                ServerTasks.parse21DaySign(map("sign_days", 9L,
                        "free_award_record", Arrays.asList((Object) 1L, 2L, 3L, 4L, 5L, 6L,
                                7L, 8L, 9L))),
                "day21", false, msg2, new StringBuilder());
        assertEquals(1, byState[1]);
        assertTrue(msg2.toString().contains("已全领") || msg2.toString().contains("暂无可领"));
    }

    // =======================================================================
    // R8 背包解析（抓包 proto_log.txt:36；旧 root cause=kvPairs 只认 {k,v}）
    // =======================================================================

    @Test
    public void backpackInit_listShape_parsed() {
        Map<String, Object> init = map("data", Arrays.asList(
                (Object) map("id", 510000L, "count", 14L),
                map("id", 510001L, "count", 164L),
                map("id", 100111L, "count", 2L),
                "not-a-map",
                map("id", 0L, "count", 9L)));      // 非法 id 过滤
        Map<Long, Long> items = ServerShopLeagueTasks.parseBackpackItems(init);
        assertEquals(3, items.size());
        assertEquals(Long.valueOf(14L), items.get(510000L));
        assertEquals(Long.valueOf(164L), items.get(510001L));
        assertEquals("资源包 100111 可被自动使用逻辑识别",
                Long.valueOf(2L), items.get(100111L));
        assertFalse(items.containsKey(0L));
    }

    @Test
    public void backpackItems_alternateShapesAndCountFields() {
        // items 键 + num/amount/item_id 兼容
        Map<Long, Long> a = ServerShopLeagueTasks.parseBackpackItems(map("items", Arrays.asList(
                (Object) map("item_id", 100112L, "num", 3L),
                map("id", 100113L, "amount", 7L))));
        assertEquals(Long.valueOf(3L), a.get(100112L));
        assertEquals(Long.valueOf(7L), a.get(100113L));
        // Map 形态 {"100111.0":{"count":5}}
        Map<Long, Long> b = ServerShopLeagueTasks.parseBackpackItems(map(
                "data", map("100111.0", map("count", 5L))));
        assertEquals(Long.valueOf(5L), b.get(100111L));
        // 旧 {k,v} 形状兜底
        Map<Long, Long> c = ServerShopLeagueTasks.parseBackpackItems(map("data", Arrays.asList(
                (Object) map("k", 100114L, "v", 4L))));
        assertEquals(Long.valueOf(4L), c.get(100114L));
        // 空/缺字段
        assertTrue(ServerShopLeagueTasks.parseBackpackItems(null).isEmpty());
        assertTrue(ServerShopLeagueTasks.parseBackpackItems(map("other", 1L)).isEmpty());
    }

    @Test
    public void backpackUpdate_addAccumulates_delSubtracts_unknownIgnored() {
        Map<Long, Long> items = new LinkedHashMap<>();
        items.put(600022L, 10L);
        ServerShopLeagueTasks.applyBackpackUpdate(items,
                map("type", "add", "data", Arrays.asList((Object) map("id", 600022L, "count", 5L),
                        map("id", 100111L, "count", 1L))));
        assertEquals(Long.valueOf(15L), items.get(600022L));
        assertEquals(Long.valueOf(1L), items.get(100111L));

        ServerShopLeagueTasks.applyBackpackUpdate(items,
                map("type", "del", "data", Arrays.asList((Object) map("id", 600022L, "count", 100L))));
        assertEquals("扣减不为负", Long.valueOf(0L), items.get(600022L));

        ServerShopLeagueTasks.applyBackpackUpdate(items,
                map("type", "mystery", "data", Arrays.asList((Object) map("id", 100111L, "count", 99L))));
        assertEquals("未知 type 不猜测", Long.valueOf(1L), items.get(100111L));
        ServerShopLeagueTasks.applyBackpackUpdate(null, map("type", "add"));
        ServerShopLeagueTasks.applyBackpackUpdate(items, null);
    }

    @Test
    public void backpackCount_fieldPriority() {
        assertEquals(3L, ServerShopLeagueTasks.backpackCount(map("count", 3L, "num", 9L)));
        assertEquals(9L, ServerShopLeagueTasks.backpackCount(map("num", 9L)));
        assertEquals(7L, ServerShopLeagueTasks.backpackCount(map("amount", 7L)));
        assertEquals(0L, ServerShopLeagueTasks.backpackCount(null));
        assertEquals(0L, ServerShopLeagueTasks.backpackCount(map("count", 0L)));
    }

    // =======================================================================
    // C3 nextWakeAt（TaskOutcome.java:26-35；RoundRunner.applyNextWake:190-208）
    // =======================================================================

    @Test
    public void adventureNextWakeAtMillis_geometry() {
        long now = 1_800_000_000L;
        // 刚领取（hang_start_time=now，累积 0）→ 600s 后可领
        assertEquals((now + 600L) * 1000L,
                ServerTasks.adventureNextWakeAtMillis(1L, now, now));
        // 已累积 500s → 100s 后
        assertEquals((now + 100L) * 1000L,
                ServerTasks.adventureNextWakeAtMillis(129L, now - 500L, now));
        // 已累积 >=600s（现在可领）→ 0（不覆盖节奏，当前轮直接领）
        assertEquals(0L, ServerTasks.adventureNextWakeAtMillis(129L, now - 700L, now));
        // 未解锁 → 0
        assertEquals(0L, ServerTasks.adventureNextWakeAtMillis(0L, 0L, now));
    }

    @Test
    public void supplyNextWakeAtMillis_readsCaptureState() {
        JSONObject mod = new JSONObject();
        mod.put("online_ts", 1792688377L);   // CaptureTasks 写入的是 epoch 秒
        assertEquals(1792688377L * 1000L, ServerTasks.supplyNextWakeAtMillis(mod));
        assertEquals(0L, ServerTasks.supplyNextWakeAtMillis(new JSONObject()));
        assertEquals(0L, ServerTasks.supplyNextWakeAtMillis(null));
    }

    @Test
    public void computedWakeAt_passesRunnerBoundary() {
        long nowMillis = System.currentTimeMillis();
        long nowSec = nowMillis / 1000L;
        long advWake = ServerTasks.adventureNextWakeAtMillis(1L, nowSec, nowSec);
        assertTrue("挂机按 600s 计算必须落在 Runner 边窗内",
                RoundRunner.acceptNextWakeAt(nowMillis, advWake));
        JSONObject mod = new JSONObject();
        mod.put("online_ts", nowSec + 60L);
        assertTrue("补给 next_calc_ts 必须落在 Runner 边窗内",
                RoundRunner.acceptNextWakeAt(nowMillis, ServerTasks.supplyNextWakeAtMillis(mod)));
        // TaskOutcome 契约：wakeAt(0)=不覆盖
        TaskOutcome out = new TaskOutcome("supply", "仓库补给", TaskOutcome.Category.SKIPPED, "x");
        out.wakeAt(0L);
        assertEquals(0L, out.nextWakeAtMillis);
        out.wakeAt(advWake);
        assertEquals(advWake, out.nextWakeAtMillis);
        assertNotNull(out);
    }
}
