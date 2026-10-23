package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次5 回归（2026-10-23）：奖励历史补全 —— 联盟宝箱多帧 add 合并 / 邮件类型历史并集 / 历史观测。
 *
 * <p>背景：引擎 {@code WjdrEngine.notifyHook} 每 proto 只留最后一帧，而网页版对每条 inbound
 * 都作为独立信号处理；批次3 已落地 {@link ServerNotifyHistory}（每账号每 proto 环形 8 条），
 * 本批让任务侧真正消费它（旧实现仍只读 engine 单帧）。
 *
 * <p>对照：服务端主线 {@link ServerTasks#allianceChest}/{@link ServerTasks#mailRewards}；
 * 抓包 tools/bundle_re/proto_log.txt:36（背包/宝箱族形状）、:150 左右邮件中转；
 * qa/audit-20261023/reward-gaps.md §5.3/§6。
 */
public class RewardHistoryUseTest {

    private static final String FPID = "test-reward-history-48460403";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void cleanup() {
        ServerNotifyHistory.clear(FPID);
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static List<Map<String, Object>> chests(Object... chestMaps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : chestMaps) out.add(Exec.mapOf(o));
        return out;
    }

    private static Map<String, Object> chestFrame(Object trophy, Object... chestMaps) {
        Map<String, Object> frame = new LinkedHashMap<>();
        if (trophy != null) frame.put("trophy_received_count", trophy);
        frame.put("chests", chests(chestMaps));
        return frame;
    }

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
    }

    // =======================================================================
    // 1) 联盟宝箱：多帧 add 合并（id 去重、后帧覆盖、空历史回退）
    // =======================================================================

    @Test
    public void mergeChestContent_singleFrameOverrideAndReceiveFlag() {
        Map<Long, Map<String, Object>> view = new LinkedHashMap<>();
        long trophy = ServerTasks.mergeChestContent(view,
                chestFrame(100L, map("id", 1L, "cid", 90101L, "name", "盟友赠礼"),
                        map("id", 2L, "cid", 90200L)), false, -1L);
        assertEquals(100L, trophy);
        assertEquals(2, view.size());
        assertEquals("盟友赠礼", view.get(1L).get("name"));

        // update 帧：同 id 后帧覆盖 name，帧内箱子一律标 receive=true
        trophy = ServerTasks.mergeChestContent(view,
                chestFrame(150L, map("id", 1L, "name", "盟友赠礼(已收)"), map("id", 2L)),
                true, trophy);
        assertEquals(150L, trophy);
        assertEquals("盟友赠礼(已收)", view.get(1L).get("name"));
        assertEquals(Boolean.TRUE, view.get(1L).get("receive"));
        assertEquals(Boolean.TRUE, view.get(2L).get("receive"));

        // null 帧不改变（历史缺失帧防御）
        assertEquals(150L, ServerTasks.mergeChestContent(view, null, false, trophy));
        assertEquals(2, view.size());
    }

    @Test
    public void mergeChestFrames_multipleAddFrames_dedupLaterWins() {
        Map<Long, Map<String, Object>> view = new LinkedHashMap<>();
        List<Map<String, Object>> frames = Arrays.asList(
                chestFrame(null,
                        map("id", 11L, "cid", 90100L, "name", "旧名"),
                        map("id", 12L, "cid", 90101L)),
                // 同一 id 后到的 add 覆盖字段（网页版按到达顺序维护状态机）
                chestFrame(7L,
                        map("id", 11L, "cid", 90100L, "name", "新名"),
                        map("id", 13L, "cid", 90300L, "name", "辉煌")));
        long trophy = ServerTasks.mergeChestFrames(view, frames, false, -1L);
        assertEquals("最后帧的 trophy_received_count 生效", 7L, trophy);
        assertEquals(3, view.size());
        assertEquals("同 id 后帧覆盖", "新名", view.get(11L).get("name"));
        assertEquals("不同 id 并集", 90300L, ((Number) view.get(13L).get("cid")).longValue());
    }

    @Test
    public void mergeChestFrames_emptyHistoryKeepsSingleFrameView() {
        Map<Long, Map<String, Object>> view = new LinkedHashMap<>();
        long trophy = ServerTasks.mergeChestFrames(view, Collections.emptyList(), false, -1L);
        assertEquals(-1L, trophy);
        assertTrue("空历史不改变视图（调用方回退单帧）", view.isEmpty());
        assertEquals(-1L, ServerTasks.mergeChestFrames(view, null, false, -1L));

        // 单帧合并等价旧 mergeChestNotify 行为
        view.clear();
        long t = ServerTasks.mergeChestFrames(view, Collections.singletonList(
                chestFrame(3L, map("id", 21L, "name", "单帧"))), false, -1L);
        assertEquals(3L, t);
        assertEquals(1, view.size());
    }

    // =======================================================================
    // 2) 通知历史读取：ServerNotifyHistory.history → chestHistoryContents
    // =======================================================================

    @Test
    public void chestHistoryContents_multiFrameFromNotifyHistory() {
        assertTrue(ServerNotifyHistory.record(FPID, "notify_alliance_chest_add",
                chestFrame(null, map("id", 31L, "cid", 90200L, "name", "战利品A"))));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_alliance_chest_add",
                chestFrame(null, map("id", 32L, "cid", 90201L, "name", "战利品B"))));

        List<Map<String, Object>> frames = ServerTasks.chestHistoryContents(
                FPID, null, "notify_alliance_chest_add");
        assertEquals("两帧 add 都来自历史（不再只拿最后一帧）", 2, frames.size());
        assertEquals("历史按旧→新排序", 31L,
                ((Number) Exec.mapsOf(frames.get(0).get("chests")).get(0).get("id")).longValue());
        assertEquals(32L,
                ((Number) Exec.mapsOf(frames.get(1).get("chests")).get(0).get("id")).longValue());

        // 合并后应同时出现两个箱子（修复“连续新增只领最后一个”）
        Map<Long, Map<String, Object>> view = new LinkedHashMap<>();
        ServerTasks.mergeChestFrames(view, frames, false, -1L);
        assertEquals(2, view.size());
        assertTrue(view.containsKey(31L));
        assertTrue(view.containsKey(32L));
    }

    @Test
    public void chestHistoryContents_sinceInit_filtersPreviousSessionAdds() {
        long t0 = 1_790_000_000_000L;
        // 上一会话残留的 add（旧），随后本会话 init 快照，再本会话 add（新）
        ServerNotifyHistory.recordAt(FPID, "notify_alliance_chest_add",
                chestFrame(null, map("id", 51L, "name", "上一会话旧箱")), t0);
        ServerNotifyHistory.recordAt(FPID, "notify_alliance_chest_init",
                chestFrame(0L), t0 + 1_000L);
        ServerNotifyHistory.recordAt(FPID, "notify_alliance_chest_add",
                chestFrame(null, map("id", 52L, "name", "本会话新箱")), t0 + 2_000L);

        long initAt = ServerNotifyHistory.latestRecordedAt(FPID, "notify_alliance_chest_init");
        assertTrue(initAt > 0L);
        List<Map<String, Object>> scoped = ServerTasks.chestHistoryContents(
                FPID, null, "notify_alliance_chest_add", initAt);
        assertEquals("只合并 init 之后的 add，旧会话箱不复活", 1, scoped.size());
        assertEquals(52L, ((Number) Exec.mapsOf(scoped.get(0).get("chests"))
                .get(0).get("id")).longValue());
        // 不传 since 时仍能拿到全部历史（兼容旧行为/测试）
        assertEquals(2, ServerTasks.chestHistoryContents(
                FPID, null, "notify_alliance_chest_add").size());
    }

    @Test
    public void chestHistoryContents_emptyHistoryAndNoEngine_returnsEmpty() {
        assertTrue("无历史且无引擎（单测）→ 空，调用方保持既有行为",
                ServerTasks.chestHistoryContents(FPID, null, "notify_alliance_chest_add").isEmpty());
        // 其它协议族互不串味
        ServerNotifyHistory.record(FPID, "notify_alliance_chest_update", chestFrame(9L));
        assertTrue(ServerTasks.chestHistoryContents(FPID, null, "notify_alliance_chest_add").isEmpty());
    }

    @Test
    public void notifyPayload_unwrapsOneLayerLikeNotifyData() {
        // 抓包形态：多数协议顶层就是业务字段
        Map<String, Object> flat = chestFrame(1L, map("id", 1L));
        assertEquals(flat, ServerTasks.notifyPayload(flat));
        // 少数包装层 {data:{...}} / {info:{...}} / {content:{...}}：与 WjdrEngine.notifyData 同口径
        Map<String, Object> inner = chestFrame(2L, map("id", 2L));
        assertEquals(inner, ServerTasks.notifyPayload(map("data", inner)));
        assertEquals(inner, ServerTasks.notifyPayload(map("info", inner)));
        assertEquals(inner, ServerTasks.notifyPayload(map("content", inner)));
        assertNull(ServerTasks.notifyPayload(null));
    }

    @Test
    public void historyDedupe_identicalFrameRecordedOnce() {
        Map<String, Object> frame = chestFrame(null, map("id", 41L, "name", "重复帧"));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_alliance_chest_add", frame));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_alliance_chest_add", frame));
        assertEquals("同内容重复推送不多占历史帧名额",
                1, ServerTasks.chestHistoryContents(FPID, null, "notify_alliance_chest_add").size());
    }

    // =======================================================================
    // 3) 邮件：notify_new_mail_syn / notify_mail_type_info_syn 多帧类型并集
    // =======================================================================

    private static Map<String, Object> newMailSyn(long firstType, String mailId) {
        return map("mail_cover_info", map(
                "isread", 0L, "first_type", firstType, "second_type", firstType,
                "attach_statue", 1L, "mailid_new", mailId));
    }

    private static Map<String, Object> mailTypeInfo(Object... rows) {
        return map("infolist", new ArrayList<>(Arrays.asList(rows)));
    }

    @Test
    public void mailHistoryTypes_unionOfNewMailAndActionableTypeInfo() {
        // 抓包 proto_log 邮件族：新邮件单帧 first_type=40/80；类型统计 infolist 只有部分板块可领
        assertTrue(ServerNotifyHistory.record(FPID, "notify_new_mail_syn", newMailSyn(40L, "m1")));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_new_mail_syn", newMailSyn(80L, "m2")));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_mail_type_info_syn",
                mailTypeInfo(
                        map("type", 20L, "num", 24L, "un_readnum", 8L, "can_get_attach", 1L),
                        map("type", 110L, "num", 200L, "un_readnum", 0L, "can_get_attach", 0L),
                        map("type", 30L, "num", 1L, "un_readnum", 0L, "can_get_attach", 0L),
                        map("type", 50L, "num", 38L, "un_readnum", 1L, "can_get_attach", 0L))));

        Set<Integer> types = ServerTasks.mailHistoryTypes(FPID);
        assertEquals("新邮件 first_type 40/80 + 可领/未读板块 20/50；已读无附件类型不并入",
                new java.util.TreeSet<>(Arrays.asList(20, 40, 50, 80)), types);
        assertTrue(ServerTasks.mailHistoryFrameCount(FPID) >= 3);
    }

    @Test
    public void mailHistoryTypes_emptyAndInvalidData() {
        assertTrue(ServerTasks.mailHistoryTypes(FPID).isEmpty());
        assertTrue(ServerTasks.mailHistoryTypes(null).isEmpty());
        assertTrue(ServerTasks.mailHistoryTypes("").isEmpty());
        // 非法 type（0/负数/超上限）不入并集；无 cover_info 的帧不崩
        ServerNotifyHistory.record(FPID, "notify_new_mail_syn", map("mail_cover_info",
                map("first_type", 0L, "mailid_new", "bad")));
        ServerNotifyHistory.record(FPID, "notify_mail_type_info_syn",
                mailTypeInfo(map("type", 5000L, "un_readnum", 1L),
                        map("type", -1L, "un_readnum", 1L)));
        ServerNotifyHistory.record(FPID, "notify_new_mail_syn", map("nothing", 1L));
        assertTrue(ServerTasks.mailHistoryTypes(FPID).isEmpty());
    }

    @Test
    public void mergeMailTypes_unionOrderDedupeAndCap() {
        int[] configured = {10, 20, 40};
        Set<Integer> history = new java.util.TreeSet<>(Arrays.asList(20, 110, 220));
        // 配置顺序优先 + 历史升序追加 + 去重
        assertArrayEquals(new int[]{10, 20, 40, 110, 220},
                ServerTasks.mergeMailTypes(configured, history));
        // 无历史 → 等价配置
        assertArrayEquals(configured, ServerTasks.mergeMailTypes(configured, null));
        assertArrayEquals(configured, ServerTasks.mergeMailTypes(configured,
                Collections.emptySet()));
        // 未知/历史为空 → 配置照抄
        assertArrayEquals(new int[]{10}, ServerTasks.mergeMailTypes(new int[]{10}, null));

        // 上限 40 防异常历史把 cover_list 请求打爆
        Set<Integer> huge = new java.util.TreeSet<>();
        for (int i = 1; i <= 100; i++) huge.add(1000 - i);
        int[] capped = ServerTasks.mergeMailTypes(configured, huge);
        assertEquals(40, capped.length);
        assertEquals(10, capped[0]);
        assertEquals(20, capped[1]);
        assertEquals(40, capped[2]);
    }

    // =======================================================================
    // 4) C3 唤醒边窗（批次5 追加修复：生产 supply 越界 6s/条 WARN 噪声）
    // =======================================================================

    @Test
    public void safeNextWakeAtMillis_dropsPastAndBeyond24h() {
        long now = 1_800_000_000_000L;
        long h24 = 24L * 60L * 60L * 1000L;
        assertEquals("未来 1h：设置", now + 3_600_000L,
                ServerTasks.safeNextWakeAtMillis(now, now + 3_600_000L));
        assertEquals("恰好 24h 边界：仍设置（与 Runner delta<=24h 口径一致）",
                now + h24, ServerTasks.safeNextWakeAtMillis(now, now + h24));
        assertEquals("超过 24h：不设置（生产 WARN 根因）", 0L,
                ServerTasks.safeNextWakeAtMillis(now, now + h24 + 1L));
        assertEquals("过去时刻：不设置", 0L,
                ServerTasks.safeNextWakeAtMillis(now, now - 1L));
        assertEquals("等于 now：不设置（避免紧循环）", 0L,
                ServerTasks.safeNextWakeAtMillis(now, now));
        assertEquals("0/负：不设置", 0L, ServerTasks.safeNextWakeAtMillis(now, 0L));
        assertEquals("非正 now 也不崩", 0L, ServerTasks.safeNextWakeAtMillis(0L, 0L));

        // 生产形态链路：supply.online_ts 是 24h+ 后 → safe 归一为 0，回落 TaskCadence 间隔
        JSONObject mod = new JSONObject();
        mod.put("online_ts", (now + h24 + 60_000L) / 1000L);
        assertEquals(0L, ServerTasks.safeNextWakeAtMillis(now,
                ServerTasks.supplyNextWakeAtMillis(mod)));
        // ≤24h 的正常补给倒计时仍会设置
        JSONObject soon = new JSONObject();
        soon.put("online_ts", (now + 180_000L) / 1000L);
        assertTrue(ServerTasks.safeNextWakeAtMillis(now,
                ServerTasks.supplyNextWakeAtMillis(soon)) > now);
    }

    @Test
    public void logHistoryMerge_throttleSmoke() {
        // 纯 smoke：≤1 帧不写；>1 帧限流触发不抛异常（真实 FileLog 落盘由生产/其他测试覆盖）
        TaskContext tc = context();
        ServerTasks.logHistoryMerge(tc, "mail", 1);
        ServerTasks.logHistoryMerge(tc, "alliance_chest_add", 2);
        ServerTasks.logHistoryMerge(tc, "alliance_chest_add", 3);   // 10 分钟内限流
        ServerTasks.logHistoryMerge(null, "mail", 5);
    }
}
