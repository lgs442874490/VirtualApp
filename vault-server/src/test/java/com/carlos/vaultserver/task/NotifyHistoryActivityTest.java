package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * R2 通知历史 + 多实例活动 + C5 活动日历门控单测（批次3 2026-10-23）。
 *
 * <p>覆盖需求验收口径：
 * <ul>
 *   <li>历史环形：allowlist 过滤、每 proto ≤8 条淘汰最旧、同帧去重、不可变深拷贝、按天/跨账号隔离；</li>
 *   <li>内存红线（生产 -Xmx256m）：总条数 ≤2000、估算字符预算 ≤4MB、账号数 ≤16；</li>
 *   <li>多实例活动 id 提取：曙光市集 7 档 + 曙光智慧从历史全部取出、逐 id claimOne、台账二次去重；</li>
 *   <li>活动日历门控：Map/List 两种 data 形状、family=id/1000、只拦“能确认未开放/已结束”，
 *       日历缺失/过旧/未列出该活动一律不拦；</li>
 *   <li>每轮请求上限 40：预算尽时不发请求且不写“已尝试”状态。</li>
 * </ul>
 *
 * <p>对照：wjdr-review/src-tool/com/wjdr/tool/modules/daily/service/DailyActivityCalendarState.java:37-140
 * （日历 parse/family）、service/DailyActivityRewardsService.java:398-411（claimOne 逐实例领取）、
 * engine/TaskEngine.java:446-483（每条 notify 一次信号）。
 */
public class NotifyHistoryActivityTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        ServerNotifyHistory.clearAll();
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static GameMessage msg(Map<String, Object> content) {
        GameMessage g = new GameMessage();
        g.getContent().putAll(content);
        return g;
    }

    private TaskContext context(WjdrEngine engine) {
        return new TaskContext(engine, FPID, SID,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 最小引擎替身：记录请求协议/参数，按协议返回预置响应（缺省 code=0 成功）。 */
    static final class HistoryTestEngine extends WjdrEngine {
        final Map<String, GameMessage> responses = new HashMap<>();
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final List<String> requestedProtos = new ArrayList<>();
        final List<Map<String, Object>> requestedArgs = new ArrayList<>();

        HistoryTestEngine() {
            super(null);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requestedProtos.add(proto);
            requestedArgs.add(args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args));
            GameMessage preset = responses.get(proto);
            return preset != null ? preset : msg(new LinkedHashMap<>());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }
    }

    // ===================================================================
    // 1) allowlist + 环形 + 去重 + 不可变副本
    // ===================================================================

    @Test
    public void record_onlyAllowlistedProtocols() {
        assertTrue(ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_info",
                map("ogp_info", map("id", 2508001L))));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_new_mail_syn", map("mid", 1L)));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_mail_type_info_syn", map("x", 1L)));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_alliance_chest_add", map("id", 1L)));
        // 非 allowlist：零副作用（心跳/初始化/战斗帧不入历史，防内存膨胀）
        assertFalse(ServerNotifyHistory.record(FPID, "notify_game_init", map("x", 1L)));
        assertFalse(ServerNotifyHistory.record(FPID, "req_heartbeat", map("x", 1L)));
        assertFalse(ServerNotifyHistory.record(FPID, "notify_world_npc_init", map("x", 1L)));
        // 非法账号/空内容
        assertFalse(ServerNotifyHistory.record("", "notify_vip_info", map("vip_lv", 1L)));
        assertFalse(ServerNotifyHistory.record(FPID, "notify_vip_info", null));
        assertFalse(ServerNotifyHistory.record(FPID, "notify_vip_info", new LinkedHashMap<>()));
        assertEquals(4, ServerNotifyHistory.entryCount(FPID));
        // allowlist 纯函数自检
        assertTrue(ServerNotifyHistory.isAllowlisted("notify_player_march_update"));
        assertTrue(ServerNotifyHistory.isAllowlisted("notify_recover_resource_init"));
        assertFalse(ServerNotifyHistory.isAllowlisted("notify_user_login"));
    }

    @Test
    public void perProtoRing_keepsLatestEightInArrivalOrder() {
        for (int i = 0; i < 12; i++) {
            assertTrue(ServerNotifyHistory.record(FPID, "notify_lost_treasure_info",
                    map("id", 2706001L, "arrival", i)));
        }
        List<ServerNotifyHistory.Entry> h =
                ServerNotifyHistory.history(FPID, "notify_lost_treasure_info");
        assertEquals(ServerNotifyHistory.MAX_PER_PROTO, h.size());
        // 0..3 被淘汰，剩 4..11，旧→新
        assertEquals(4L, Exec.longOf(h.get(0).content.get("arrival")));
        assertEquals(11L, Exec.longOf(h.get(h.size() - 1).content.get("arrival")));
        assertTrue(h.get(0).seq < h.get(h.size() - 1).seq);
        // 其它协议不受影响
        assertTrue(ServerNotifyHistory.record(FPID, "notify_vip_info", map("vip_lv", 9L)));
        assertEquals(9, ServerNotifyHistory.entryCount(FPID));
    }

    @Test
    public void record_dedupesIdenticalFrameAndStoresImmutableDeepCopy() {
        Map<String, Object> src = map("ogp_info",
                map("id", 2508001L, "free_box_get_time", 0L,
                        "items", Arrays.asList(1L, 2L)));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_info", src));
        assertTrue(ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_info", src));
        List<ServerNotifyHistory.Entry> h =
                ServerNotifyHistory.history(FPID, "notify_optional_gift_pack_info");
        assertEquals("同帧重复推送不占名额", 1, h.size());
        // 改源 Map/List 不影响历史（深拷贝）
        src.put("ogp_info", map("id", 999L));
        assertEquals(2508001L, Exec.longOf(
                Exec.mapOf(h.get(0).content.get("ogp_info")).get("id")));
        // 返回值/历史均为不可变结构
        try {
            h.get(0).content.put("hack", 1L);
            fail("历史内容必须不可变");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        assertFalse(ServerNotifyHistory.history(FPID).isEmpty());
    }

    @Test
    public void history_matchesProtocolFamilyAndIsolatesAccounts() {
        ServerNotifyHistory.record(FPID, "notify_lost_treasure_info", map("id", 2706001L));
        ServerNotifyHistory.record(FPID, "notify_lost_treasure_update", map("id", 2706002L));
        ServerNotifyHistory.record("other-fpid", "notify_lost_treasure_info", map("id", 2706001L));
        // 传族前缀 → 全族；传完整名 → 精确（+其变体前缀）
        assertEquals(2, ServerNotifyHistory.history(FPID, "notify_lost_treasure").size());
        assertEquals(1, ServerNotifyHistory.history(FPID, "notify_lost_treasure_info").size());
        assertEquals(0, ServerNotifyHistory.history(FPID, "notify_optional_gift_pack").size());
        // 按 fpid 隔离（sid 不作键）：同账号多角色共一份，其它账号互不可见
        assertEquals(2, ServerNotifyHistory.entryCount(FPID));
        assertEquals(1, ServerNotifyHistory.entryCount("other-fpid"));
        assertEquals(3, ServerNotifyHistory.totalEntries());
        assertEquals(2706002L, Exec.longOf(ServerNotifyHistory
                .latestContent(FPID, "notify_lost_treasure").get("id")));
        assertTrue(ServerNotifyHistory.latestRecordedAt(FPID, "notify_lost_treasure") > 0L);
        // clear 只清指定账号
        ServerNotifyHistory.clear(FPID);
        assertEquals(0, ServerNotifyHistory.entryCount(FPID));
        assertEquals(1, ServerNotifyHistory.entryCount("other-fpid"));
    }

    // ===================================================================
    // 2) 内存红线：总条数 ≤2000 / 字符预算 / 账号数上限
    // ===================================================================

    @Test
    public void totalEntryCap_evictsOldestAcrossAccounts() {
        // 每账号 28 个 allowlist 协议 × 8 条 = 224；10 账号 = 2240 > 2000 全局上限
        List<String> protos = new ArrayList<>();
        for (String prefix : ServerNotifyHistory.ALLOWLIST) {
            protos.add(prefix.endsWith("_") ? prefix + "test" : prefix + "_test");
        }
        int recorded = 0;
        for (int acct = 0; acct < 10; acct++) {
            String fpid = "acct-" + acct;
            for (String proto : protos) {
                for (int i = 0; i < ServerNotifyHistory.MAX_PER_PROTO; i++) {
                    assertTrue(ServerNotifyHistory.record(fpid, proto, map("id", acct * 1000 + i)));
                    recorded++;
                }
            }
        }
        assertEquals(10 * protos.size() * ServerNotifyHistory.MAX_PER_PROTO, recorded);
        assertTrue("总条数必须被硬上限约束", ServerNotifyHistory.totalEntries()
                <= ServerNotifyHistory.MAX_TOTAL_ENTRIES);
        assertTrue(ServerNotifyHistory.accountCount() <= ServerNotifyHistory.MAX_FPIDS);
        assertTrue(ServerNotifyHistory.totalChars() <= ServerNotifyHistory.MAX_TOTAL_CHARS);
        // 最旧账号（acct-0）的条目应最先被淘汰
        assertTrue(ServerNotifyHistory.entryCount("acct-0")
                < ServerNotifyHistory.entryCount("acct-9"));
    }

    @Test
    public void totalCharBudget_boundsMemoryOnHugeFrames() {
        // 单帧 ~2.9KB × 2000 条 ≈ 5.8MB > 4MB 预算 → 必须按字节预算淘汰（-Xmx256m 红线）
        List<String> protos = new ArrayList<>();
        for (String prefix : ServerNotifyHistory.ALLOWLIST) {
            protos.add(prefix.endsWith("_") ? prefix + "big" : prefix + "_big");
        }
        String padding = "x".repeat(2900);
        for (int acct = 0; acct < 10; acct++) {
            String fpid = "big-" + acct;
            for (String proto : protos) {
                for (int i = 0; i < ServerNotifyHistory.MAX_PER_PROTO; i++) {
                    ServerNotifyHistory.record(fpid, proto,
                            map("id", acct * 1000 + i, "pad", padding + i));
                }
            }
        }
        assertTrue(ServerNotifyHistory.totalChars() <= ServerNotifyHistory.MAX_TOTAL_CHARS);
        assertTrue("字符预算应先于条数上限触发", ServerNotifyHistory.totalEntries()
                < ServerNotifyHistory.MAX_TOTAL_ENTRIES);
    }

    @Test
    public void overBudgetFrame_isRejected() {
        String huge = "y".repeat(ServerNotifyHistory.MAX_ENTRY_CHARS + 10);
        assertFalse(ServerNotifyHistory.record(FPID, "notify_alliance_chest_init",
                map("blob", huge)));
        assertEquals(0, ServerNotifyHistory.entryCount(FPID));
    }

    // ===================================================================
    // 3) 多实例活动 id 提取（历史优先 + 引擎单条兜底）
    // ===================================================================

    @Test
    public void activityInfos_collectsAllHistoricalInstancesThenEngineFallback() {
        HistoryTestEngine engine = new HistoryTestEngine();
        TaskContext tc = context(engine);
        long now = nowSec();
        // 曙光市集 7 档 + 曙光智慧 1 帧，各占一次推送（引擎只留最后一帧）
        for (long id : ServerTasks.ACT_DAWN_MARKET_IDS) {
            ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_info",
                    map("ogp_info", map("id", id, "free_box_get_time", 0L,
                            "start_ts", now - 3600L, "end_ts", now + 86400L)));
        }
        ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_init",
                map("ogp_info", map("id", ServerTasks.ACT_DAWN_WISDOM_ID,
                        "free_box_get_time", 0L,
                        "start_ts", now - 3600L, "end_ts", now + 86400L)));
        List<Map<String, Object>> infos = ServerTasks.activityInfos(tc, "ogp_info",
                "notify_optional_gift_pack_info", "notify_optional_gift_pack_init");
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> info : infos) ids.add(Exec.longOf(info.get("id")));
        assertEquals(8, infos.size());
        assertTrue(ids.contains(ServerTasks.ACT_DAWN_WISDOM_ID));
        for (long id : ServerTasks.ACT_DAWN_MARKET_IDS) assertTrue("missing " + id, ids.contains(id));
        // 引擎单条兜底：历史清空后仍能取到 engine.notifyContent（旧行为不回归）
        ServerNotifyHistory.clearAll();
        engine.notifies.put("notify_lost_treasure_info",
                map("info", map("id", 2706001L, "daily_reward_get_ts", 0L)));
        List<Map<String, Object>> fallback = ServerTasks.activityInfos(tc, "info",
                "notify_lost_treasure_info");
        assertEquals(1, fallback.size());
        assertEquals(2706001L, Exec.longOf(fallback.get(0).get("id")));
    }

    @Test
    public void dedupeLatestActivityInfos_laterFrameWinsPerId() {
        List<Map<String, Object>> raw = Arrays.asList(
                map("id", 2508001L, "free_box_get_time", 0L),
                map("id", 2508002L, "free_box_get_time", 0L),
                map("id", 2508001L, "free_box_get_time", 1234L),   // 同 id 后到：更新
                map("note", "no-id"));
        List<Map<String, Object>> out = ServerTasks.dedupeLatestActivityInfos(raw);
        assertEquals(3, out.size());
        assertEquals(1234L, Exec.longOf(out.get(0).get("free_box_get_time")));
    }

    @Test
    public void claimOptionalGiftFamily_claimsEveryHistoricalInstanceOnce() {
        long now = nowSec();
        HistoryTestEngine engine = new HistoryTestEngine();
        TaskContext tc = context(engine);
        List<Long> expected = new ArrayList<>();
        for (long id : ServerTasks.ACT_DAWN_MARKET_IDS) {
            expected.add(id);
            ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_info",
                    map("ogp_info", map("id", id, "free_box_get_time", 0L,
                            "start_ts", now - 3600L, "end_ts", now + 86400L)));
        }
        expected.add(ServerTasks.ACT_DAWN_WISDOM_ID);
        ServerNotifyHistory.record(FPID, "notify_optional_gift_pack_init",
                map("ogp_info", map("id", ServerTasks.ACT_DAWN_WISDOM_ID,
                        "free_box_get_time", 0L,
                        "start_ts", now - 3600L, "end_ts", now + 86400L)));

        ServerTasks.ClaimStat st = new ServerTasks.ClaimStat();
        ServerTasks.claimOptionalGiftFamily(tc, st, now);

        assertEquals(8, engine.requestedProtos.size());
        for (String proto : engine.requestedProtos) {
            assertEquals(ServerTasks.PROTO_OPTIONAL_GIFT_FREE_BOX, proto);
        }
        Set<Long> claimedIds = new LinkedHashSet<>();
        for (Map<String, Object> args : engine.requestedArgs) {
            claimedIds.add(Exec.longOf(args.get("id")));
        }
        assertEquals(new HashSet<>(expected), claimedIds);
        assertEquals(8, st.ok);

        // 台账按天去重：第二轮不再重复请求
        ServerTasks.ClaimStat second = new ServerTasks.ClaimStat();
        ServerTasks.claimOptionalGiftFamily(tc, second, now);
        assertEquals("ClaimLedger 当日去重", 8, engine.requestedProtos.size());
    }

    @Test
    public void activityClaim_respectsRoundRequestBudget() {
        assertEquals(40, ServerTasks.ACTIVITY_MAX_REQUESTS_PER_ROUND);
        HistoryTestEngine engine = new HistoryTestEngine();
        TaskContext tc = context(engine);
        ServerTasks.ClaimStat st = new ServerTasks.ClaimStat();
        st.requests = ServerTasks.ACTIVITY_MAX_REQUESTS_PER_ROUND;
        ServerTasks.activityClaim(tc, st, "daily_special", "每日特惠礼包",
                ServerTasks.PROTO_DAILY_SPECIAL, null, "daily_special");
        assertEquals("预算尽不得发请求", 0, engine.requestedProtos.size());
        assertTrue(st.subs.get("daily_special").startsWith("skip:"));
        // 有余量：请求一次并占用预算
        st.requests = 39;
        ServerTasks.activityClaim(tc, st, "daily_special", "每日特惠礼包",
                ServerTasks.PROTO_DAILY_SPECIAL, null, "daily_special");
        assertEquals(1, engine.requestedProtos.size());
        assertEquals(40, st.requests);
        assertEquals(1, st.ok);
    }

    // ===================================================================
    // 4) 活动日历门控（C5）
    // ===================================================================

    private static Map<String, Object> calendarContent(long id, long begin, long end) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("schedule", Collections.singletonList(map("begin_ts", begin, "end_ts", end)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(String.valueOf(id), entry);
        return map("data", data);
    }

    @Test
    public void parseActivityCalendar_mapAndListShapes() {
        long begin = 1_700_000_000L;
        long end = begin + 7200L;
        // Map 形状（data = {key: {id, schedule[]}}；id 缺失时由 key 回填，网页版同款兼容）
        Map<Long, List<long[]>> fromMap = ServerTasks.parseActivityCalendar(
                calendarContent(2508001L, begin, end));
        assertEquals(1, fromMap.size());
        assertEquals(1, fromMap.get(2508001L).size());
        assertEquals(begin, fromMap.get(2508001L).get(0)[0]);
        assertEquals(end, fromMap.get(2508001L).get(0)[1]);
        // List 形状（data = [{id, schedule[]}]）
        Map<Long, List<long[]>> fromList = ServerTasks.parseActivityCalendar(map("data",
                Collections.singletonList(map("id", 2508008L,
                        "schedule", Arrays.asList(map("begin_ts", begin, "end_ts", end),
                                map("begin_ts", end + 10L, "end_ts", end + 20L))))));
        assertEquals(1, fromList.size());
        assertEquals(2, fromList.get(2508008L).size());
        // 非法/空：返回空 map（调用方“不拦”）
        assertTrue(ServerTasks.parseActivityCalendar(null).isEmpty());
        assertTrue(ServerTasks.parseActivityCalendar(map("data", "oops")).isEmpty());
        // begin/end 非法窗口被过滤 → 无窗口活动不收录
        assertTrue(ServerTasks.parseActivityCalendar(map("data", Collections.singletonList(
                map("id", 2508001L, "schedule", Collections.singletonList(
                        map("begin_ts", end, "end_ts", begin)))))).isEmpty());
    }

    @Test
    public void activityCalendarGate_blocksOnlyConfirmedClosedActivity() {
        long now = nowSec();
        HistoryTestEngine engine = new HistoryTestEngine();
        TaskContext tc = context(engine);
        // 未收到日历 → 不拦（解析不出不拦）
        assertNull(ServerTasks.activityCalendarBlock(tc, 2508001L, now));
        // 同 family（2508）已结束：2508001 未单独列出也应按 family 拦
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2508008L, now - 7200L, now - 3600L), System.currentTimeMillis());
        String block = ServerTasks.activityCalendarBlock(tc, 2508001L, now);
        assertNotNull("family 已结束应拦截", block);
        assertTrue(block.contains("日历"));
        // 日历列出但正在开放 → 放行
        ServerNotifyHistory.clearAll();
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2508001L, now - 600L, now + 600L), System.currentTimeMillis());
        assertNull(ServerTasks.activityCalendarBlock(tc, 2508001L, now));
        // 未列出该活动（也无同 family）→ 不拦
        ServerNotifyHistory.clearAll();
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2401001L, now - 7200L, now - 3600L), System.currentTimeMillis());
        assertNull(ServerTasks.activityCalendarBlock(tc, 2508001L, now));
        // 未开始（下一窗口在未来）→ 拦，文案含开始时间
        ServerNotifyHistory.clearAll();
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2508001L, now + 3600L, now + 7200L), System.currentTimeMillis());
        String future = ServerTasks.activityCalendarBlock(tc, 2508001L, now);
        assertNotNull(future);
        assertTrue(future.contains("尚未开启"));
        // 日历过旧（>24h）→ 无法确认，不拦
        ServerNotifyHistory.clearAll();
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2508001L, now - 7200L, now - 3600L),
                System.currentTimeMillis() - ServerTasks.ACTIVITY_CALENDAR_FRESH_MILLIS - 60_000L);
        assertNull("过期日历不能确认，不拦", ServerTasks.activityCalendarBlock(tc, 2508001L, now));
        // 窗口久远结束（超过 recentlyEndedFamily 守卫 12h）：周期活动可能重开 → 不拦
        ServerNotifyHistory.clearAll();
        long longAgo = now - 30L * 3600L;
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2508001L, longAgo - 7200L, longAgo), System.currentTimeMillis());
        assertNull("久远结束不能确认未开放，不拦",
                ServerTasks.activityCalendarBlock(tc, 2508001L, now));
        // 过期日历之后即使活动仍关闭也不请求（该用例只验证不拦；请求侧由 claim 层台账兜底）
    }

    @Test
    public void claimLostTreasure_skipsWhenCalendarConfirmsClosed() {
        long now = nowSec();
        HistoryTestEngine engine = new HistoryTestEngine();
        TaskContext tc = context(engine);
        ServerNotifyHistory.record(FPID, "notify_lost_treasure_info",
                map("info", map("id", 2706001L, "start_ts", now - 86400L,
                        "end_ts", now + 86400L, "daily_reward_get_ts", 0L)));
        ServerNotifyHistory.recordAt(FPID, "notify_activity_calendar_data",
                calendarContent(2706001L, now - 7200L, now - 3600L), System.currentTimeMillis());
        ServerTasks.ClaimStat st = new ServerTasks.ClaimStat();
        ServerTasks.claimLostTreasure(tc, st, now);
        assertEquals("日历已结束 → 不请求", 0, engine.requestedProtos.size());
        assertTrue(st.subs.get("lost_treasure").startsWith("skip:"));
    }
}
