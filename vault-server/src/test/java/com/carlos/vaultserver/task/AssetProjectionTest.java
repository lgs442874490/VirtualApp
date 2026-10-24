package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.asset.AssetKey;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W1 资产库 → {@code srv:asset:*} state 投影（{@link AssetStateProjector}）验收：
 *
 * <p>用真实形状的最小登录帧（proto_log.txt 10-13 SNAPSHOT-LOGIN 样本简化）跑通
 * {@link CoreAssetLibrary}（accept → READY → AssetSnapshot）→ 投影 JSON 字段对拍：
 * <ul>
 *   <li>王国天数/可采资源等级上限（game_init 时间轴推导）；</li>
 *   <li>队列容量派生公式（科技 1020 + VIP2 → capacity 2；科技 3 + VIP6 + buff 10505 + extra
 *       → raw 7 → 只读层 clamp 6）；</li>
 *   <li>占用口径（普通 type=4 占 1；特殊 411 不占普通队列但进 specialByType）；</li>
 *   <li>城市坐标（world_player_init 的 city_point → meta.cityX/cityY → 投影 city.x/y）；</li>
 *   <li>资源（101 钻石/102 肉/103 木/104 煤/105 铁）、体力（recover id=201）。</li>
 * </ul>
 *
 * <p>依赖同仓 {@code com.carlos.vault.wjdr.asset.AssetParsers}（A2 解析器）的实现；
 * 帧形状与设计文档 design/research-20261024/核心资产库协议与架构盘点.md 一致。
 */
public class AssetProjectionTest {

    private static final String FPID = "48460403";
    private static final long SID = 913L;

    // game_init 时间轴：开服日定在 project_start_ts + 2×14 天对齐点，servertime 为开服第 6 天 +1h
    private static final long PROJECT_START_TS = 1600000000L;
    private static final long OPEN_TIME = PROJECT_START_TS + 2L * 1209600L;   // 1602419200
    private static final long SERVER_TIME = OPEN_TIME + 5L * 86400L + 3600L; // 开服第 6 天 01:00

    /** 推荐协议全集（登录批 13 项）：全部 accept 过一遍才能免 5s READY 宽限。 */
    private static final String[] RECOMMENDED_EMPTY = {
            "notify_soldier_init",
            "notify_hero_init",
            "notify_shelter_info",
            "notify_lords_equipment_info",
            "notify_backpack_init",
            "notify_intelligence_info"
    };

    // ================= 最小真实形状帧 =================

    private static Map<String, Object> gameInit() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("project_start_ts", PROJECT_START_TS);
        c.put("servertime", SERVER_TIME);
        c.put("utc_offset", 28800L);
        c.put("open_time", OPEN_TIME);
        c.put("zero_time", OPEN_TIME + 5L * 86400L);
        return c;
    }

    /** 世界玩家 init：1 条普通采集行军(type=4) + 1 条特殊巨熊集结(type=411)，extra_march 空。 */
    private static Map<String, Object> worldPlayerInit() {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("x", 585L);
        city.put("y", 694L);
        c.put("city_point", city);
        List<Object> marches = new ArrayList<>();
        marches.add(march(19117L, 4L, 2L));
        marches.add(march(19001L, 411L, 2L));
        c.put("march_info", marches);
        c.put("mass_master_marchs", new ArrayList<Object>());
        c.put("world_extra_march", new LinkedHashMap<String, Object>());
        return c;
    }

    private static Map<String, Object> march(long id, long type, long state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", 28181058L);
        m.put("type", type);
        m.put("state", state);
        m.put("starttime", SERVER_TIME - 600L);
        m.put("endtime", SERVER_TIME + 1800L);
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("x", 585L);
        sp.put("y", 694L);
        m.put("startpoint", sp);
        m.put("endpoint", sp);
        return m;
    }

    private static Map<String, Object> resourceInit() {
        List<Object> rows = new ArrayList<>();
        rows.add(resource(101L, 221696L));   // 钻石
        rows.add(resource(102L, 1234L));     // 肉
        rows.add(resource(103L, 5678L));     // 木
        rows.add(resource(104L, 90L));       // 煤
        rows.add(resource(105L, 12L));       // 铁
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("data", rows);
        return c;
    }

    private static Map<String, Object> resource(long id, long count) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("count", count);
        return r;
    }

    /** 可恢复资源 init（map 形 "201.0"）：体力 count 可超 max（真实样本 1035/200）。 */
    private static Map<String, Object> recoverInit() {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("id", 201L);
        st.put("count", 1035L);
        st.put("max_count", 200L);
        st.put("recover", 1L);
        st.put("next_calc_ts", SERVER_TIME + 120L);
        st.put("interval", 300L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("201.0", st);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("data", data);
        return c;
    }

    /** 科技：1020=1（1 队）；用于容量样本（1020 + VIP2 → capacity 2）。 */
    private static Map<String, Object> research(Map<String, Long> tids) {
        Map<String, Object> researched = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : tids.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tid", Long.parseLong(e.getKey()));
            row.put("lev", e.getValue());
            researched.put(e.getKey() + ".0", row);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("researched", researched);
        return c;
    }

    private static Map<String, Object> vip(long lv, long activeEndtime) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("vip_lv", lv);
        c.put("vip_active_endtime", activeEndtime);
        return c;
    }

    /** buff_show：一个已过期 10505（不应计入）+ 一个 10418 非队列 buff。 */
    private static Map<String, Object> buffShow() {
        Map<String, Object> expired10505 = new LinkedHashMap<>();
        expired10505.put("show_id", 81010L);
        Map<String, Object> expiredMap = new LinkedHashMap<>();
        Map<String, Object> expiredAttr = new LinkedHashMap<>();
        expiredAttr.put("k", 10505L);
        expiredAttr.put("v", 1L);
        expiredMap.put("10505.0", expiredAttr);
        expired10505.put("buff_map", expiredMap);
        expired10505.put("expire_ts", SERVER_TIME - 3600L);   // 已过期 → 不计队列
        expired10505.put("total_duration", 28800L);

        Map<String, Object> active = new LinkedHashMap<>();
        active.put("show_id", 81001L);
        Map<String, Object> activeMap = new LinkedHashMap<>();
        Map<String, Object> activeAttr = new LinkedHashMap<>();
        activeAttr.put("k", 10418L);
        activeAttr.put("v", 10000L);
        activeMap.put("10418.0", activeAttr);
        active.put("buff_map", activeMap);
        active.put("expire_ts", SERVER_TIME + 3L * 86400L);
        active.put("total_duration", 28800L);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("81010.0", expired10505);
        map.put("81001.0", active);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("map", map);
        return c;
    }

    /** 组装并跑完一次登录批（推荐协议空帧先到，真实帧最后；最后一个 game_init 触发 READY 判定）。 */
    private static CoreAssetLibrary readyLibrary(Map<String, Long> researchTids, long vipLv,
                                                 long vipActiveEndtime, boolean activeQueueBuff,
                                                 long extraMarch) {
        CoreAssetLibrary lib = new CoreAssetLibrary(new AssetKey(FPID, SID, 28181058L, 1L));
        for (String proto : RECOMMENDED_EMPTY) {
            lib.accept(proto, new LinkedHashMap<String, Object>());
        }
        lib.accept("notify_resource_init", resourceInit());
        lib.accept("notify_recover_resource_init", recoverInit());
        lib.accept("notify_world_player_init", extraMarch > 0
                ? worldPlayerInitWithExtra(extraMarch) : worldPlayerInit());
        lib.accept("notify_all_research", research(researchTids));
        lib.accept("notify_vip_info", vip(vipLv, vipActiveEndtime));
        lib.accept("notify_buff_show", buffShow(activeQueueBuff));
        lib.accept("notify_game_init", gameInit());
        assertTrue("资产库应 READY（核心+推荐协议齐）", lib.awaitReady(6000L));
        assertTrue(lib.ready());
        assertNotNull(lib.snapshot());
        return lib;
    }

    private static Map<String, Object> worldPlayerInitWithExtra(long extra) {
        Map<String, Object> c = worldPlayerInit();
        Map<String, Object> worldExtra = new LinkedHashMap<>();
        worldExtra.put("value", extra);
        c.put("world_extra_march", worldExtra);
        return c;
    }

    /** activeQueueBuff=true 时追加一个生效中的 10505 临时队列 buff（+1 队）。 */
    private static Map<String, Object> buffShow(boolean activeQueueBuff) {
        Map<String, Object> c = buffShow();
        if (!activeQueueBuff) return c;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) c.get("map");
        Map<String, Object> buff = new LinkedHashMap<>();
        buff.put("show_id", 81002L);
        Map<String, Object> bm = new LinkedHashMap<>();
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("k", 10505L);
        attr.put("v", 1L);
        bm.put("10505.0", attr);
        buff.put("buff_map", bm);
        buff.put("expire_ts", SERVER_TIME + 600L);
        buff.put("total_duration", 28800L);
        map.put("81002.0", buff);
        return c;
    }

    // ================= 断言 =================

    @Test
    public void projectionExposesClockQueueResourcesStamina() {
        Map<String, Long> research = new LinkedHashMap<>();
        research.put("1020", 1L);
        CoreAssetLibrary lib = readyLibrary(research, 2L, SERVER_TIME + 86400L, false, 0L);

        String json = AssetStateProjector.toJson(lib.snapshot(), FPID, SID);
        assertNotNull(json);
        JSONObject o = new JSONObject(json);
        assertTrue("投影应带 ready=true", o.getBoolean("ready"));
        assertEquals(FPID, o.getString("fpid"));
        assertEquals(SID, o.getLong("sid"));
        assertTrue("version 应递增", o.getLong("version") > 0L);
        assertTrue("readyAt 应有值", o.getLong("readyAt") > 0L);
        assertTrue("updatedAt 应有值", o.getLong("updatedAt") > 0L);

        // ---- 时钟：开服第 6 天 → maxResourceLevel=8（<701 天）----
        assertEquals(6, o.getInt("kingdomDay"));
        assertEquals(8, o.getInt("maxResourceLevel"));

        // ---- 队列：科技 1020(1) + VIP2(0) → capacity 2；普通 type=4 占 1；特殊 411 不占 ----
        JSONObject q = o.getJSONObject("queue");
        assertEquals(2, q.getInt("capacity"));
        assertEquals(2, q.getInt("rawCapacity"));
        assertEquals(1, q.getInt("occupied"));
        assertEquals(1, q.getInt("idle"));
        assertFalse(q.getBoolean("limitReached"));
        assertEquals(1, q.getJSONObject("specialByType").getInt("411"));

        // ---- 资源 ----
        JSONObject r = o.getJSONObject("resources");
        assertEquals(221696L, r.getLong("diamond"));
        assertEquals(1234L, r.getLong("meat"));
        assertEquals(5678L, r.getLong("wood"));
        assertEquals(90L, r.getLong("coal"));
        assertEquals(12L, r.getLong("iron"));

        // ---- 城市坐标（world_player_init 的 city_point）----
        JSONObject city = o.getJSONObject("city");
        assertEquals(585L, city.getLong("x"));
        assertEquals(694L, city.getLong("y"));

        // ---- 体力（recover id=201；count 可超 max，不 clamp）----
        JSONObject st = o.getJSONObject("stamina");
        assertEquals(1035L, st.getLong("current"));
        assertEquals(200L, st.getLong("max"));
        assertEquals(SERVER_TIME + 120L, st.getLong("nextRecalcAt"));
        assertEquals(1L, st.getLong("recoverPer"));
        assertEquals(300L, st.getLong("intervalSec"));
        assertTrue("currentAt 不应低于快照值", st.getLong("currentAt") >= 1035L);

        // ---- 推荐协议全到 → missing 空 ----
        JSONArray missing = o.getJSONArray("missing");
        assertEquals(0, missing.length());
    }

    /** raw 超上限场景：科技 3 + VIP6 生效 + 临时 buff 10505(+1) + extra_march 1 → raw 7，clamp 6。 */
    @Test
    public void projectionClampsQueueCapacityToSix() {
        Map<String, Long> research = new LinkedHashMap<>();
        research.put("1007", 1L);
        research.put("1020", 1L);
        research.put("1027", 1L);
        CoreAssetLibrary lib = readyLibrary(research, 6L, SERVER_TIME + 86400L, true, 1L);

        AssetSnapshot.Queue q = lib.queue();
        assertEquals("公式原值 1+3科技+VIP+buff+extra", 7, q.rawCapacity);
        assertEquals("只读层 clamp 1..6", 6, q.capacity);

        JSONObject o = new JSONObject(AssetStateProjector.toJson(lib.snapshot(), FPID, SID));
        assertEquals(7, o.getJSONObject("queue").getInt("rawCapacity"));
        assertEquals(6, o.getJSONObject("queue").getInt("capacity"));
    }

    /** 空快照投影也不能抛：字段齐全、ready=false、queue 全 0（App 侧读取安全）。 */
    @Test
    public void emptySnapshotProjectsSafely() {
        CoreAssetLibrary lib = new CoreAssetLibrary(new AssetKey(FPID, SID, 0L, 1L));
        JSONObject o = new JSONObject(AssetStateProjector.toJson(lib.snapshot(), FPID, SID));
        assertFalse(o.getBoolean("ready"));
        assertEquals(0, o.getInt("kingdomDay"));
        assertEquals(0, o.getInt("maxResourceLevel"));
        assertEquals(0, o.getJSONObject("queue").getInt("capacity"));
        assertEquals(0, o.getJSONObject("resources").getLong("diamond"));
        assertEquals(0, o.getJSONObject("stamina").getLong("current"));
        // 空快照城市坐标必须为 {x:0,y:0}（不能缺键，App 读侧按契约解析）
        JSONObject emptyCity = o.getJSONObject("city");
        assertEquals(0L, emptyCity.getLong("x"));
        assertEquals(0L, emptyCity.getLong("y"));
        assertEquals(0, o.getJSONArray("missing").length());
        // 键契约
        assertEquals("srv:asset:48460403:913", AssetStateProjector.stateKey(FPID, SID));
    }
}
