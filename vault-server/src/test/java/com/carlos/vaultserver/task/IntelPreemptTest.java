package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 情报「30 分钟消失 → 召回 1 个采集队列」单测（2026-10-31 用户规则 3）。
 *
 * <p>锁死：只有狩猎快过期且普通队列被采集占满才触发；只选 type=4 采集；至少 2 队采集才召回
 * （绝不全部召回）；保护类（驻防/集结/上车）一律不碰；冷却/窗口上限；真召回只发 1 次请求。
 */
public class IntelPreemptTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void cleanup() {
        CoreAssetRegistry.global().clearAll();
    }

    private static AssetSnapshot.QueueMarch march(long id, long type, long state,
                                                  long endSec, long owner) {
        return new AssetSnapshot.QueueMarch(id, owner, type, state, 0L, endSec,
                0L, 0L, 0L, 0, 0, 0, 0, 0L, 0L);
    }

    // ==================== 触发条件 ====================

    @Test
    public void needsPreempt_onlyWhenExpiringAndQueueFullByOthers() {
        long now = 10_000L;
        long soon = now + 1200L;      // 20 分钟后消失
        long far = now + 3600L;       // 1 小时后消失
        // 普通队列满（idle=0）且狩猎快过期 → 触发
        assertTrue(ServerFieldTasks.intelNeedsQueuePreempt(
                new ServerFieldTasks.IntelQueueSnapshot(3, 0, 0, true, 0), 1, 0, soon, now));
        // 还有空闲队列 → 不需要召回
        assertFalse(ServerFieldTasks.intelNeedsQueuePreempt(
                new ServerFieldTasks.IntelQueueSnapshot(3, 0, 0, true, 1), 1, 0, soon, now));
        // 情报自己占满普通队列（activeNormal>=allowed）→ 召回采集也没用
        assertFalse(ServerFieldTasks.intelNeedsQueuePreempt(
                new ServerFieldTasks.IntelQueueSnapshot(3, 1, 0, true, 0), 1, 1, soon, now));
        // 未进入 30 分钟窗口 → 不召回
        assertFalse(ServerFieldTasks.intelNeedsQueuePreempt(
                new ServerFieldTasks.IntelQueueSnapshot(3, 0, 0, true, 0), 1, 0, far, now));
        // 没有狩猎事件/队列空闲未知 → 不召回
        assertFalse(ServerFieldTasks.intelNeedsQueuePreempt(
                new ServerFieldTasks.IntelQueueSnapshot(3, 0, 0, true, 0), 1, 0, 0L, now));
        assertFalse(ServerFieldTasks.intelNeedsQueuePreempt(
                new ServerFieldTasks.IntelQueueSnapshot(3, 0, 0, false, 0), 1, 0, soon, now));
        assertFalse(ServerFieldTasks.intelNeedsQueuePreempt(null, 1, 0, soon, now));
    }

    // ==================== 选队 ====================

    @Test
    public void chooseGather_onlyType4InFlightOrArrived_earliestEnd() {
        long own = 7L;
        AssetSnapshot.QueueMarch garrison = march(1L, 5L, 3L, 100L, own);    // 驻防：绝不召回
        AssetSnapshot.QueueMarch rally = march(2L, 102L, 2L, 200L, own);     // 集结：绝不召回
        AssetSnapshot.QueueMarch gatherLong = march(3L, 4L, 3L, 5000L, own);
        AssetSnapshot.QueueMarch gatherSoon = march(4L, 4L, 2L, 900L, own);  // 在途且最早结束
        AssetSnapshot.QueueMarch otherOwner = march(5L, 4L, 2L, 100L, 99L);
        List<AssetSnapshot.QueueMarch> list = new ArrayList<>();
        list.add(garrison);
        list.add(rally);
        list.add(gatherLong);
        list.add(gatherSoon);
        list.add(otherOwner);
        assertSame(gatherSoon, ServerFieldTasks.intelChooseGatherToRecall(list, own));
        assertEquals(2, ServerFieldTasks.intelCountGatherMarches(list, own));
    }

    @Test
    public void chooseGather_prefersInFlightOverArrivedEvenIfArrivedEndsEarlier() {
        long own = 7L;
        AssetSnapshot.QueueMarch arrived = march(3L, 4L, 3L, 100L, own);
        AssetSnapshot.QueueMarch inFlight = march(4L, 4L, 2L, 9000L, own);
        List<AssetSnapshot.QueueMarch> list = new ArrayList<>();
        list.add(arrived);
        list.add(inFlight);
        assertSame(inFlight, ServerFieldTasks.intelChooseGatherToRecall(list, own));
    }

    @Test
    public void chooseGather_noneWhenOnlyProtectedOrNoGather() {
        long own = 7L;
        List<AssetSnapshot.QueueMarch> list = new ArrayList<>();
        list.add(march(1L, 5L, 3L, 100L, own));
        list.add(march(2L, 99L, 2L, 100L, own));
        assertNull(ServerFieldTasks.intelChooseGatherToRecall(list, own));
        assertEquals(0, ServerFieldTasks.intelCountGatherMarches(list, own));
        assertNull(ServerFieldTasks.intelChooseGatherToRecall(null, own));
    }

    // ==================== 冷却 / 上限 ====================

    @Test
    public void budget_cooldownAndWindowCap() {
        long now = 1_000_000L;
        // 刚召回（<5min）→ 冷却中
        JSONObject recent = new JSONObject();
        recent.put("last", now - 60_000L);
        assertFalse(ServerFieldTasks.intelPreemptBudgetAllows(recent, now));
        assertTrue(ServerFieldTasks.intelPreemptBudgetAllows(new JSONObject(), now));
        // 超过 5min 冷却，窗口内仅 1 次 → 允许
        JSONObject one = new JSONObject();
        one.put("last", now - 400_000L);
        JSONArray arr = new JSONArray();
        arr.put(now - 400_000L);
        one.put("recent", arr);
        assertTrue(ServerFieldTasks.intelPreemptBudgetAllows(one, now));
        // 10 分钟窗口内已有 2 次 → 拒绝（绝不把采集召回光）
        JSONObject two = new JSONObject();
        two.put("last", now - 400_000L);
        JSONArray arr2 = new JSONArray();
        arr2.put(now - 400_000L);
        arr2.put(now - 500_000L);
        two.put("recent", arr2);
        assertFalse(ServerFieldTasks.intelPreemptBudgetAllows(two, now));
        // 窗口外的旧记录不计入
        JSONObject old = new JSONObject();
        old.put("last", now - 700_000L);
        JSONArray arr3 = new JSONArray();
        arr3.put(now - 700_000L);
        arr3.put(now - 800_000L);
        old.put("recent", arr3);
        assertTrue(ServerFieldTasks.intelPreemptBudgetAllows(old, now));
    }

    @Test
    public void preemptKey_isRoleScoped() {
        TaskContext tc = new TaskContext(null, "fpid", 888L, null,
                new java.util.HashMap<String, JSONObject>(), "test", null, 35371154L);
        assertEquals("srv:intel:preempt:fpid:888:35371154", ServerFieldTasks.intelPreemptKey(tc));
        // 旧路径 roleUid=0 保持旧键形态
        TaskContext legacy = new TaskContext(null, "fpid", 888L, null,
                new java.util.HashMap<String, JSONObject>(), "test");
        assertEquals("srv:intel:preempt:fpid:888:0", ServerFieldTasks.intelPreemptKey(legacy));
    }

    // ==================== 真召回：只发 1 次请求 + 冷却 ====================

    @Test
    public void tryPreempt_recallsExactlyOneGather_andHonorsCooldown() throws Exception {
        FakeEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib);
        long nowSec = lib.clock().serverNowSec();
        // 3 队采集在途/驻扎，容量 3 → 普通队列 idle=0（被采集占满）
        lib.accept("notify_world_player_init", map(
                "march_info", list(
                        map("id", 1L, "type", 4L, "state", 3L, "endtime", nowSec + 900L),
                        map("id", 2L, "type", 4L, "state", 2L, "endtime", nowSec + 500L),
                        map("id", 3L, "type", 4L, "state", 3L, "endtime", nowSec + 1200L)),
                "world_extra_march", map("value", 0L)));
        assertEquals("3 队采集应占满容量 3", 0, lib.queue().idle);

        StateStore store = new StateStore(tmp.getRoot().toPath());
        TaskContext tc = new TaskContext(engine, "fpid", 888L, store,
                new LinkedHashMap<String, JSONObject>(), "test");

        String msg = ServerFieldTasks.tryPreemptGatherForIntel(
                tc, System.currentTimeMillis(), nowSec);
        assertNotNull("队列满+采集≥2 队 → 应召回 1 队", msg);
        assertTrue(msg.contains("不会全部召回"));
        assertEquals("只允许发 1 次召回请求", 1, engine.recallRequests);
        assertEquals("对在途(state=2)采集用 recall", "req_world_march_recall",
                engine.lastRecallProto);

        // 5 分钟冷却内再次触发 → 不召回、不再发请求（绝不把采集全部召回）
        assertNull(ServerFieldTasks.tryPreemptGatherForIntel(
                tc, System.currentTimeMillis() + 1_000L, nowSec));
        assertEquals("冷却内不得重复召回", 1, engine.recallRequests);
    }

    // ==================== fixtures ====================

    /** 假引擎：只记录召回请求，返回 code=0。 */
    private static final class FakeEngine extends WjdrEngine {
        int recallRequests = 0;
        String lastRecallProto = "";

        FakeEngine() {
            super(null);
        }

        @Override
        public long lastUid() {
            return 123L;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            if ("req_world_march_recall".equals(proto)
                    || "req_world_march_off_work".equals(proto)) {
                recallRequests++;
                lastRecallProto = proto;
            }
            GameMessage msg = new GameMessage();
            msg.setProto(proto);
            msg.setContent(map("code", 0L));
            return msg;
        }
    }

    /** 最小资产库 READY 夹具（与 IntelAssetReadTest 同源；容量=3）。 */
    private static void ready(CoreAssetLibrary lib) {
        lib.accept("notify_game_init", map("project_start_ts", 1_610_294_400L,
                "open_time", 1_725_182_701L, "servertime", 1_791_905_936L));
        lib.accept("notify_world_player_init", map("march_info", new ArrayList<>(),
                "world_extra_march", map("value", 0L)));
        lib.accept("notify_resource_init", map("data", new ArrayList<>()));
        lib.accept("notify_recover_resource_init", map("data", new LinkedHashMap<>()));
        lib.accept("notify_all_research", map("researched",
                map("1020.0", map("tid", 1020L, "lev", 1L))));
        lib.accept("notify_vip_info", map("vip_lv", 6L, "vip_active_endtime", 9_999_999_999L));
        lib.accept("notify_soldier_init", map("data", new ArrayList<>()));
        lib.accept("notify_hero_init", map("data", new ArrayList<>()));
        lib.accept("notify_shelter_info", map("building_info", new LinkedHashMap<>()));
        lib.accept("notify_lords_equipment_info", map("data", new ArrayList<>()));
        lib.accept("notify_backpack_init", map("data", new ArrayList<>()));
        lib.accept("notify_intelligence_info", map("events", new ArrayList<>()));
        lib.accept("notify_buff_show", map("map", new ArrayList<>()));
        assertTrue("资产库应 READY", lib.ready());
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<>();
        for (Object o : items) out.add(o);
        return out;
    }
}
