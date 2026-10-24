package com.carlos.vault.wjdr.asset;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
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

/**
 * 资产库各域解析器单测：真实字段构造 Map（proto_log 样本形状），覆盖
 * init 全量 / update 增量 / del / map 形 "201.0" / list 形 / 去包装 / 变更检测 / 不抛。
 */
public class AssetParsersTest {

    private AssetState st;
    private Set<AssetDomain> dirty;

    @Before
    public void setUp() {
        st = new AssetState(new AssetKey("fpid", 1L, 1001L, 1L));
        dirty = new LinkedHashSet<AssetDomain>();
    }

    // ---------------- 构造工具 ----------------

    private boolean apply(String proto, Map<String, Object> content) {
        return AssetParsers.apply(proto, content, st, dirty);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : items) out.add(o);
        return out;
    }

    private static Map<String, Object> item(long id, long count) {
        return map("id", id, "count", count);
    }

    private static Map<String, Object> march(long id, long owner, long type, long state,
                                             long start, long end) {
        return map("id", id, "owner", owner, "type", type, "state", state,
                "starttime", start, "endtime", end,
                "startpoint", map("x", 585L, "y", 694L),
                "endpoint", map("x", 609L, "y", 704L));
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    // ================= CLOCK / META / CALENDAR =================

    @Test
    public void gameInit_serverTime_scheduleUpdate() {
        assertTrue(apply("notify_game_init", map(
                "project_start_ts", 1610294400L, "servertime", 1791905936L,
                "utc_offset", 28800L, "open_time", 1725156900L, "zero_time", 1791820800L)));
        assertEquals(1610294400L, st.projectStartTs);
        assertEquals(1791905936L, st.serverTimeSec);
        assertEquals(28800L, st.utcOffsetSec);
        assertEquals(1725156900L, st.openTime);
        assertEquals(1791820800L, st.zeroTime);
        assertTrue(st.serverTimeRecvAtMs > 0);
        assertTrue(dirty.contains(AssetDomain.CLOCK));

        dirty.clear();
        assertTrue(apply("notify_server_time", map("time", 1791905936L)));
        assertTrue("时间未变化不应 dirty", dirty.isEmpty());
        assertTrue(apply("notify_server_time", map("time", 1791905999L)));
        assertEquals(1791905999L, st.serverTimeSec);
        assertTrue(dirty.contains(AssetDomain.CLOCK));

        dirty.clear();
        assertTrue(apply("notify_schedule_update",
                map("qtime_id", 18L, "start_time", 1L, "server_time", 1791906100L)));
        assertEquals(1791906100L, st.serverTimeSec);
        assertTrue(dirty.contains(AssetDomain.CLOCK));
    }

    @Test
    public void baseInfo_power_level_allianceTrap() {
        assertTrue(apply("notify_base_info", map(
                "nickName", "我是蛋炒饭啊", "lv", 25L, "uid", 28181058L, "kid", 913L,
                "fid", 109164553L, "power", 764414129L, "register_time", 1722415842L,
                "stove_lv", 80L)));
        assertEquals("我是蛋炒饭啊", st.nickName);
        assertEquals(25L, st.level);
        assertEquals(28181058L, st.uid);
        assertEquals(913L, st.kid);
        assertEquals(109164553L, st.fid);
        assertEquals(764414129L, st.power);
        assertEquals(1722415842L, st.registerTime);
        assertEquals(80L, st.stoveLv);
        assertTrue(dirty.contains(AssetDomain.META));

        dirty.clear();
        assertTrue(apply("notify_player_power_update", map("power", 770000000L)));
        assertEquals(770000000L, st.power);
        assertTrue(dirty.contains(AssetDomain.META));
        // 同一战力重复推送不 dirty
        dirty.clear();
        assertTrue(apply("notify_player_power_update", map("power", 770000000L)));
        assertTrue(dirty.isEmpty());

        dirty.clear();
        assertTrue(apply("notify_player_level_up", map("data", map("lv", 26L))));
        assertEquals(26L, st.level);
        assertTrue(dirty.contains(AssetDomain.META));

        dirty.clear();
        assertTrue(apply("notify_alliance_init", map(
                "alliance", map("aid", 92200060L, "name", "无忧小筑", "abbr", "TOP", "lv", 11L),
                "player", map("aid", 92200060L, "rank", 3L, "trap_time", 1791894911L,
                        "trap_extra", map("aid", 92200060L)))));
        assertEquals(92200060L, st.allianceId);
        assertEquals("无忧小筑", st.allianceName);
        assertEquals("TOP", st.allianceAbbr);
        assertEquals(1791894911L, st.bearTrapTime);
        assertEquals(92200060L, st.bearTrapExtraAid);
        assertTrue(dirty.contains(AssetDomain.META));
        assertTrue(dirty.contains(AssetDomain.BEAR));
    }

    @Test
    public void calendar_fullReplace() {
        assertTrue(apply("notify_activity_calendar_data", map("data", map(
                "2972001.0", map("id", 2972001L, "schedule",
                        list(map("begin_ts", 1791734400L, "end_ts", 1792339200L))),
                "2426002.0", map("id", 2426002L, "schedule",
                        list(map("begin_ts", 1791734400L, "end_ts", 1792267200L,
                                "group_id", 6L)))))));
        assertEquals(2, st.schedules.size());
        assertEquals(1, st.schedules.get(2426002L).size());
        assertEquals(6L, st.schedules.get(2426002L).get(0).groupId);
        assertTrue(dirty.contains(AssetDomain.CALENDAR));

        // 全量替换：旧活动消失
        dirty.clear();
        assertTrue(apply("notify_activity_calendar_data", map("data", map(
                "5.0", map("id", 5L, "schedule",
                        list(map("begin_ts", 1L, "end_ts", 2L)))))));
        assertEquals(1, st.schedules.size());
        assertNull(st.schedules.get(2972001L));
        assertTrue(dirty.contains(AssetDomain.CALENDAR));
    }

    // ================= RESOURCE / RECOVER / BACKPACK =================

    @Test
    public void resource_initUpdateDel() {
        assertTrue(apply("notify_resource_init", map("data", list(
                item(106L, 9717030L), item(101L, 1704304L), item(102L, 500L)))));
        assertEquals(9717030L, st.resources.get(106L).longValue());
        assertEquals(1704304L, st.resources.get(101L).longValue());
        assertTrue(dirty.contains(AssetDomain.RESOURCE));

        dirty.clear();
        assertTrue(apply("notify_resource_update", map("type", "add",
                "data", list(item(106L, 920L)))));
        assertEquals(9717950L, st.resources.get(106L).longValue());
        assertTrue(dirty.contains(AssetDomain.RESOURCE));

        dirty.clear();
        // del 减法，下限 0（网页 resourceUpdate: del 取负）
        assertTrue(apply("notify_resource_update", map("type", "del",
                "data", list(item(102L, 99999L)))));
        assertEquals(0L, st.resources.get(102L).longValue());
        assertTrue(dirty.contains(AssetDomain.RESOURCE));

        // 无变化不 dirty
        dirty.clear();
        assertTrue(apply("notify_resource_update", map("type", "add",
                "data", list(item(106L, 0L)))));
        assertTrue(dirty.isEmpty());

        // 全量替换：init 里的旧 id 消失
        dirty.clear();
        assertTrue(apply("notify_resource_init", map("data", list(item(101L, 1L)))));
        assertEquals(1, st.resources.size());
        assertTrue(dirty.contains(AssetDomain.RESOURCE));
    }

    @Test
    public void recover_mapShape_initUpdate_staminaDomain() {
        // map 形：key "201.0"
        assertTrue(apply("notify_recover_resource_init", map("data", map(
                "201.0", map("id", 201L, "count", 180L, "max_count", 200L, "recover", 1L,
                        "next_calc_ts", 1791916148L, "interval", 300L),
                "203.0", map("id", 203L, "count", 4L, "max_count", 5L, "recover", 1L,
                        "next_calc_ts", 1792490970L, "interval", 604800L)))));
        assertNotNull(st.recover.get(201L));
        assertEquals(180L, st.recover.get(201L).count);
        assertEquals(200L, st.recover.get(201L).maxCount);
        assertEquals(300L, st.recover.get(201L).intervalSec);
        assertTrue(dirty.contains(AssetDomain.RECOVER));
        assertTrue("201 变化应同时标 STAMINA", dirty.contains(AssetDomain.STAMINA));

        // update list 形：非 201 变化只标 RECOVER
        dirty.clear();
        assertTrue(apply("notify_recover_resource_update", map("data", list(
                map("id", 203L, "count", 3L, "next_calc_ts", 1792490929L, "recover", 1L,
                        "interval", 604800L, "max_count", 5L)))));
        assertEquals(3L, st.recover.get(203L).count);
        assertTrue(dirty.contains(AssetDomain.RECOVER));
        assertFalse(dirty.contains(AssetDomain.STAMINA));

        // 201 update：count 单独出现的 PATCH 合并（缺省字段保留）
        dirty.clear();
        assertTrue(apply("notify_recover_resource_update", map("data", map(
                "201.0", map("id", 201L, "count", 190L)))));
        assertEquals(190L, st.recover.get(201L).count);
        assertEquals(200L, st.recover.get(201L).maxCount);
        assertTrue(dirty.contains(AssetDomain.STAMINA));
    }

    @Test
    public void backpack_initUpdateDel() {
        assertTrue(apply("notify_backpack_init", map("data", list(
                item(610002L, 274L), item(100011L, 130L)))));
        assertEquals(274L, st.backpack.get(610002L).longValue());
        assertTrue(dirty.contains(AssetDomain.BACKPACK));

        dirty.clear();
        assertTrue(apply("notify_backpack_update", map("type", "add",
                "data", list(item(600022L, 1L)))));
        assertEquals(1L, st.backpack.get(600022L).longValue());
        assertTrue(dirty.contains(AssetDomain.BACKPACK));

        dirty.clear();
        assertTrue(apply("notify_backpack_update", map("type", "del",
                "data", list(item(610002L, 274L)))));
        assertEquals(0L, st.backpack.get(610002L).longValue());
        assertTrue(dirty.contains(AssetDomain.BACKPACK));
    }

    // ================= QUEUE =================

    @Test
    public void worldPlayerInit_fullSnapshot_capacityFactors() {
        Map<String, Object> m1 = map("id", 19117L, "owner", 28181058L);
        m1.putAll(march(19117L, 28181058L, 4L, 3L, 1791896523L, 1791922041L));
        m1.put("collect_resource", map("refresh_id", 1250308L));
        Map<String, Object> master = march(8597L, 20775796L, 501L, 5L, 1791917192L, 1791917372L);
        assertTrue(apply("notify_world_player_init", map(
                "city_point", map("x", 585L, "y", 694L),
                "march_info", list(m1),
                "mass_master_marchs", list(master),
                "world_extra_march", map("value", 1L))));
        assertEquals(1, st.marches.size());
        assertEquals(1250308L, st.marches.get(19117L).refreshId);
        assertEquals(1, st.massMasters.size());
        assertEquals(1L, st.extraMarch);
        assertEquals("init", st.queueSource);
        assertEquals(1, st.occupied());
        assertEquals(2, st.rawCapacity());
        assertEquals("city_point 应承载到 Meta", 585L, st.cityX);
        assertEquals(694L, st.cityY);
        assertTrue(dirty.contains(AssetDomain.QUEUE));
        assertTrue("city_point 变化应标 META dirty", dirty.contains(AssetDomain.META));

        // 同一快照重放：无变化不 dirty
        dirty.clear();
        assertTrue(apply("notify_world_player_init", map(
                "city_point", map("x", 585L, "y", 694L),
                "march_info", list(m1),
                "mass_master_marchs", list(master),
                "world_extra_march", map("value", 1L))));
        assertTrue(dirty.isEmpty());
    }

    @Test
    public void worldPlayerInit_cityPoint_metaProjection() {
        // 首帧带 city_point：state 与快照 Meta 均承载坐标（走 build() 验证不可变快照）
        assertTrue(apply("notify_world_player_init", map(
                "city_point", map("x", 609L, "y", 704L),
                "march_info", list(),
                "mass_master_marchs", list())));
        assertEquals(609L, st.cityX);
        assertEquals(704L, st.cityY);
        assertTrue(dirty.contains(AssetDomain.META));
        AssetSnapshot s = st.build(1L, false, new LinkedHashSet<String>());
        assertEquals(609L, s.meta.cityX);
        assertEquals(704L, s.meta.cityY);

        // 无 city_point 的帧：保留旧坐标，不触发 META/QUEUE dirty
        dirty.clear();
        assertTrue(apply("notify_world_player_init", map(
                "march_info", list(),
                "mass_master_marchs", list())));
        assertEquals("无 city_point 不清旧坐标", 609L, st.cityX);
        assertEquals(704L, st.cityY);
        assertTrue(dirty.isEmpty());

        // 坐标迁移（x/y 变化）：META dirty；非法/零值不覆盖
        dirty.clear();
        assertTrue(apply("notify_world_player_init", map(
                "city_point", map("x", 700L, "y", 800L),
                "march_info", list())));
        assertEquals(700L, st.cityX);
        assertEquals(800L, st.cityY);
        assertTrue(dirty.contains(AssetDomain.META));

        dirty.clear();
        assertTrue(apply("notify_world_player_init", map(
                "city_point", map("x", 0L, "y", 0L),
                "march_info", list())));
        assertEquals("x/y<=0 不覆盖", 700L, st.cityX);
        assertEquals(800L, st.cityY);
        assertTrue("零值坐标不算变化", dirty.isEmpty());

        // city_point 形状异常（非 Map / 空 map）不抛、不 dirty
        assertTrue(apply("notify_world_player_init", map("city_point", "bad")));
        assertTrue(apply("notify_world_player_init", map("city_point", map())));
        assertEquals(700L, st.cityX);
        assertEquals(800L, st.cityY);
    }

    @Test
    public void playerMarch_addUpdateDel() {
        assertTrue(apply("notify_player_march_add", map("kid", 913L, "march", map(
                "id", 19159L, "owner", 28181058L, "startpoint", map("x", 585L, "y", 694L),
                "endpoint", map("x", 581L, "y", 710L),
                "starttime", 1791917252L, "endtime", 1791917266L, "state", 2L, "type", 99L,
                "target_type", 1L, "aid", 92200060L,
                "mass_slave", map("master_uid", 20775796L, "master_marchid", 8597L,
                        "master_type", 501L)))));
        assertEquals(1, st.marches.size());
        assertEquals("push", st.queueSource);
        assertEquals("type=99 加入集结占普通队列", 1, st.occupied());
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        dirty.clear();
        assertTrue(apply("notify_player_march_update", map("id", 19159L, "type", 99L,
                "kid", 913L,
                "change", map("state", 3L,
                        "mass_slave", map("master_target_type", 101L, "master_targetid", 7200103L)))));
        assertEquals(3L, st.marches.get(19159L).state);
        assertEquals("change 未带 master_marchid 时保留旧值", 8597L,
                st.marches.get(19159L).masterMarchId);
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        dirty.clear();
        st.marchLimitReached = true;
        assertTrue(apply("notify_player_march_del", map("id", 19159L, "type", 99L, "kid", 913L)));
        assertFalse(st.marches.containsKey(19159L));
        assertFalse("march_del 需清 marchLimitReached", st.marchLimitReached);
        assertTrue(dirty.contains(AssetDomain.QUEUE));
    }

    @Test
    public void massMarch_addUpdateDel() {
        assertTrue(apply("notify_mass_march_add", map("kid", 913L,
                "march", march(8597L, 20775796L, 99L, 5L, 1791917192L, 1791917372L))));
        assertEquals(1, st.massMasters.size());
        assertEquals("massMasters 里 type=99 也占普通队列", 1, st.occupied());
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        dirty.clear();
        assertTrue(apply("notify_mass_march_update", map("id", 8597L, "type", 99L,
                "change", map("state", 4L, "endtime", 1791917400L))));
        assertEquals(4L, st.massMasters.get(8597L).state);
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        dirty.clear();
        assertTrue(apply("notify_mass_march_del", map("id", 8597L, "type", 99L)));
        assertTrue(st.massMasters.isEmpty());
        assertEquals(0, st.occupied());
        assertTrue(dirty.contains(AssetDomain.QUEUE));
    }

    @Test
    public void queueCapacity_researchVipBuffExtra() {
        // research_complete: tid_lev=10071 → tid 1007 lev 1（tid*10+lev 编码）
        assertTrue(apply("notify_research_complete", map("tid_lev", 10071L)));
        assertEquals(1L, st.research.get(1007L).longValue());
        assertEquals(1, st.techQueueCount());
        assertEquals(2, st.rawCapacity());

        dirty.clear();
        assertTrue(apply("notify_all_research", map("researched", map(
                "1007.0", map("tid", 1007L, "lev", 1L),
                "1020.0", map("tid", 1020L, "lev", 1L),
                "1027.0", map("tid", 1027L, "lev", 1L),
                "3048.0", map("tid", 3048L, "lev", 1L)))));
        assertEquals(3, st.techQueueCount());
        assertEquals(4, st.rawCapacity());
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        // VIP≥6 且未过期 +1
        dirty.clear();
        assertTrue(apply("notify_vip_info",
                map("vip_lv", 6L, "vip_active_endtime", nowSec() + 3600L)));
        assertTrue(st.vip6Active(st.serverNowSec()));
        assertEquals(5, st.rawCapacity());

        // VIP 过期：-1
        dirty.clear();
        assertTrue(apply("notify_vip_update",
                map("vip_lv", 6L, "vip_active_endtime", nowSec() - 10L)));
        assertFalse(st.vip6Active(st.serverNowSec()));
        assertEquals(4, st.rawCapacity());

        // 恢复有效 VIP + 临时队列 buff（buff_map[10505] 未过期）+1
        apply("notify_vip_info", map("vip_lv", 9L, "vip_active_endtime", nowSec() + 3600L));
        assertTrue(apply("notify_buff_show", map("map", map(
                "81001.0", map("show_id", 81001L,
                        "buff_map", map("10505.0", map("k", 10505L, "v", 1L)),
                        "expire_ts", nowSec() + 60L)))));
        assertEquals(1, st.activeBuffQueues(st.serverNowSec()));
        assertEquals(6, st.rawCapacity());

        // 过期 buff 不算（按 expire_ts 过滤，不只看存在）
        dirty.clear();
        assertTrue(apply("notify_buff_show", map("map", map(
                "81001.0", map("show_id", 81001L,
                        "buff_map", map("10505.0", map("k", 10505L, "v", 1L)),
                        "expire_ts", nowSec() - 10L)))));
        assertEquals(0, st.activeBuffQueues(st.serverNowSec()));
        assertEquals(5, st.rawCapacity());

        // buff_show_update 单条增量
        dirty.clear();
        assertTrue(apply("notify_buff_show_update", map("data", map(
                "show_id", 81002L,
                "buff_map", map("10505.0", map("k", 10505L, "v", 1L)),
                "expire_ts", nowSec() + 60L))));
        assertEquals(Long.valueOf(1L), st.buffQueues.get(81002L));
        assertEquals(1, st.activeBuffQueues(st.serverNowSec()));

        // buff_show_delete 清理
        dirty.clear();
        assertTrue(apply("notify_buff_show_delete", map("show_id", 81002L)));
        assertNull(st.buffQueues.get(81002L));
        assertNull(st.buffExpire.get(81002L));
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        // 额外队列 +1（raw=6，clamp 仍 6）
        dirty.clear();
        assertTrue(apply("notify_world_add_extra_march",
                map("world_extra_march", map("value", 1L))));
        assertEquals(1L, st.extraMarch);
        assertEquals(6, st.rawCapacity());
        assertEquals(QueueCapacity.MAX_QUEUE, QueueCapacity.clampCapacity(st.rawCapacity()));
        // 再 +2 → raw 8 / clamp 6（保留 raw 供诊断）
        apply("notify_world_add_extra_march", map("world_extra_march", map("value", 3L)));
        assertEquals(8, st.rawCapacity());
        assertEquals(6, QueueCapacity.clampCapacity(st.rawCapacity()));
    }

    @Test
    public void marchLimitReached_2878_andPlayerMarchDelClears() {
        dirty.clear();
        assertTrue(apply("req_world_march", map("code", 2878L)));
        assertTrue(st.marchLimitReached);
        assertTrue(dirty.contains(AssetDomain.QUEUE));

        dirty.clear();
        assertTrue(apply("notify_player_march_del", map("id", 12345L)));
        assertFalse(st.marchLimitReached);
        assertTrue(dirty.contains(AssetDomain.QUEUE));
    }

    @Test
    public void marchResponse_upsert() {
        assertTrue(apply("req_world_march", map("code", 0L, "march_info", map(
                "id", 19200L, "owner", 28181058L, "type", 4L, "state", 2L,
                "starttime", 10L, "endtime", 20L,
                "startpoint", map("x", 1L, "y", 2L), "endpoint", map("x", 3L, "y", 4L),
                "collect_resource", map("refresh_id", 999L)))));
        assertEquals(1, st.marches.size());
        assertEquals(999L, st.marches.get(19200L).refreshId);
        assertEquals("response", st.queueSource);
        assertTrue(dirty.contains(AssetDomain.QUEUE));
    }

    // ================= INTEL =================

    @Test
    public void intel_info_events_eventsDel_powerInfo() {
        assertTrue(apply("notify_intelligence_info", map(
                "radar_level", 17L, "radar_exp", 8L, "power_level", 7L,
                "next_event_time", 1791932400L,
                "events", map("1626.0", map("id", 1626L, "event_id", 1035L, "status", 1L,
                        "x", 772L, "y", 407L, "expire_ts", 1791932400L, "played", "false")))));
        assertEquals(17L, st.radarLevel);
        assertEquals(8L, st.radarExp);
        assertEquals(7L, st.powerLevel);
        assertEquals(1, st.intelEvents.size());
        assertEquals(1L, st.intelEvents.get(1626L).status);
        assertTrue(dirty.contains(AssetDomain.INTEL));

        // 增量 upsert：新增 1627，保留 1626
        dirty.clear();
        assertTrue(apply("notify_intelligence_events", map(
                "next_event_time", 1791932500L,
                "events", map("1627.0", map("id", 1627L, "event_id", 1034L, "status", 1L,
                        "x", 798L, "y", 400L, "expire_ts", 1791932400L)))));
        assertEquals(2, st.intelEvents.size());
        assertEquals(1791932500L, st.nextEventTime);
        assertTrue(dirty.contains(AssetDomain.INTEL));

        // del
        dirty.clear();
        assertTrue(apply("notify_intelligence_events_del", map("ids", list(1626L))));
        assertFalse(st.intelEvents.containsKey(1626L));
        assertTrue(st.intelEvents.containsKey(1627L));
        assertTrue(dirty.contains(AssetDomain.INTEL));

        // power_info 只推 power_lv（schema: notify_intelligence_power_info.power_lv）
        dirty.clear();
        assertTrue(apply("notify_intelligence_power_info", map("power_lv", 10L)));
        assertEquals(10L, st.powerLevel);
        assertTrue(dirty.contains(AssetDomain.INTEL));

        // battle_result 保持事件存在（资产库不存战斗结算字段）
        dirty.clear();
        assertTrue(apply("notify_intelligence_battle_result",
                map("id", 1627L, "success", "true")));
        assertTrue(st.intelEvents.containsKey(1627L));
    }

    // ================= BEHEMOTH / AUTOJOIN / BEAR =================

    @Test
    public void npc_initUpdate_partialPreserve() {
        assertTrue(apply("notify_world_npc_init", map(
                "monster_max_lv", 30L, "last_daily_time", 1791820800L,
                "join_daily_count", map("1.0", map("key", 1L, "count", 50L)),
                "master_daily_count", map("1.0", map("key", 1L, "count", 10L)),
                "expert_reward_daily_count", 0L)));
        assertEquals(30L, st.monsterMaxLv);
        assertEquals(1791820800L, st.lastDailyTime);
        assertEquals(50L, st.joinDailyCounts.get(1L).longValue());
        assertEquals(10L, st.masterDailyCounts.get(1L).longValue());
        assertTrue(dirty.contains(AssetDomain.BEHEMOTH));

        // update 是部分帧（schema: base=-1，字段 4/5/6/7）：只覆盖出现的字段
        dirty.clear();
        assertTrue(apply("notify_world_npc_update", map(
                "last_daily_time", 1791907200L,
                "join_daily_count", map("1.0", map("key", 1L, "count", 49L)))));
        assertEquals(1791907200L, st.lastDailyTime);
        assertEquals("未出现的 monster_max_lv 必须保留", 30L, st.monsterMaxLv);
        assertEquals(49L, st.joinDailyCounts.get(1L).longValue());
        assertEquals("未出现的 master_daily_count 必须保留", 10L,
                st.masterDailyCounts.get(1L).longValue());
        assertTrue(dirty.contains(AssetDomain.BEHEMOTH));

        // count map 的另一种形状：key "2.0" → 直接是 count 数字
        dirty.clear();
        assertTrue(apply("notify_world_npc_update", map(
                "join_daily_count", map("2.0", 7L))));
        assertEquals(7L, st.joinDailyCounts.get(2L).longValue());
        assertTrue(dirty.contains(AssetDomain.BEHEMOTH));
    }

    @Test
    public void autoJoin_infoAndOpen() {
        assertTrue(apply("req_alliance_activity_auto_join_info", map(
                "code", 0L, "end_time", 1791936563L, "pos", 54L, "just_soldier", "true",
                "auto_mass_list", map(
                        "1.0", map("id", 1L, "selected", "true"),
                        "3.0", map("id", 3L, "selected", "true"),
                        "4.0", map("id", 4L, "selected", "false")),
                "limit", 1L, "offline", "false")));
        assertEquals(1791936563L, st.autoJoinEndTime);
        assertEquals(1L, st.autoJoinLimit);
        assertTrue(st.autoJoinJustSoldier);
        assertFalse(st.autoJoinOffline);
        assertEquals(2, st.autoJoinSelected.size());
        assertTrue(st.autoJoinSelected.contains(1L));
        assertTrue(st.autoJoinSelected.contains(3L));
        assertTrue(dirty.contains(AssetDomain.BEHEMOTH));

        // open 响应只有 code/end_time/pos：其余字段保留
        dirty.clear();
        assertTrue(apply("req_alliance_activity_auto_join_open",
                map("code", 0L, "end_time", 1791944704L)));
        assertEquals(1791944704L, st.autoJoinEndTime);
        assertEquals("open 响应缺 limit 时必须保留", 1L, st.autoJoinLimit);
        assertTrue(dirty.contains(AssetDomain.BEHEMOTH));

        // auto_mass_id_list 变体
        dirty.clear();
        assertTrue(apply("req_alliance_activity_auto_join_info",
                map("auto_mass_id_list", list(1L, 4L))));
        assertEquals(2, st.autoJoinSelected.size());
        assertTrue(st.autoJoinSelected.contains(4L));
        assertTrue(dirty.contains(AssetDomain.BEHEMOTH));
    }

    @Test
    public void bear_pushAndAuthoritativeInfo() {
        assertTrue(apply("notify_alliance_trap_update", map(
                "mass_start_time", 1791894600L, "mass_end_time", 1791896400L,
                "cool_end_time", 1792065600L, "donate", 2195L,
                "total_score", 1005479285171L, "reserve_time", 1792067400L,
                "auto_reserve", "true")));
        assertEquals(1791894600L, st.bearMassStart);
        assertEquals(1791896400L, st.bearMassEnd);
        assertEquals(1792065600L, st.bearCoolEnd);
        assertEquals(2195L, st.bearDonate);
        assertEquals(1005479285171L, st.bearTotalScore);
        assertEquals(1792067400L, st.bearReserveTime);
        assertTrue(st.bearAutoReserve);
        assertFalse("推送不标 reserve 权威", st.bearReserveKnown);
        assertTrue(dirty.contains(AssetDomain.BEAR));

        // req 响应：reserve_time 权威来源
        dirty.clear();
        assertTrue(apply("req_alliance_trap_info", map(
                "mass_start_time", 1791894600L, "mass_end_time", 1791896400L,
                "cool_end_time", 1792065600L, "donate", 2195L,
                "total_score", 924393518105L, "reserve_time", 1792067400L)));
        assertEquals(924393518105L, st.bearTotalScore);
        assertTrue(st.bearReserveKnown);
        assertTrue(dirty.contains(AssetDomain.BEAR));
    }

    // ================= WAR =================

    private static Map<String, Object> situation(long id, long owner, long type, long state,
                                                  long endtime, long power) {
        return map("id", id, "owner", owner, "type", type, "state", state,
                "starttime", 1791905922L, "endtime", endtime,
                "startpoint", map("x", 779L, "y", 429L),
                "endpoint", map("x", 758L, "y", 435L),
                "target_type", 4L, "aid", 92500099L, "power", power);
    }

    @Test
    public void allianceSituation_initAddUpdateDelActivity() {
        assertTrue(apply("notify_world_alliance_situation_init", map(
                "marchs", list(situation(48714L, 36103809L, 102L, 2L, 1791905953L, 93931162L),
                        situation(48715L, 36103809L, 102L, 2L, 1791905956L, 89717562L)),
                "to_marchs", list(situation(48720L, 36515409L, 3L, 2L, 1791906000L, 5000000L)))));
        assertEquals(3, st.allianceMarches.size());
        assertEquals(89717562L, st.warPowers.get(36103809L).longValue());
        assertTrue(dirty.contains(AssetDomain.WAR));

        // add：增量 upsert
        dirty.clear();
        assertTrue(apply("notify_world_alliance_situation_add", map(
                "marchs", list(situation(48716L, 36103809L, 102L, 2L, 1791905960L, 100L)))));
        assertEquals(4, st.allianceMarches.size());
        assertTrue(dirty.contains(AssetDomain.WAR));

        // update：同 id 覆盖（状态变化）
        dirty.clear();
        assertTrue(apply("notify_world_alliance_situation_update", map(
                "marchs", list(situation(48714L, 36103809L, 102L, 5L, 1791906167L, 25382950L)))));
        assertEquals(5L, st.allianceMarches.get(48714L).state);
        assertEquals(4, st.allianceMarches.size());
        assertTrue(dirty.contains(AssetDomain.WAR));

        // update 部分帧（sproto base=-1）：缺省字段保留旧值，仅覆盖出现字段
        dirty.clear();
        assertEquals(36103809L, st.allianceMarches.get(48715L).owner);
        assertTrue(apply("notify_world_alliance_situation_update", map(
                "marchs", list(map("id", 48715L, "state", 4L)))));
        assertEquals(4L, st.allianceMarches.get(48715L).state);
        assertEquals("部分帧未带 owner 时必须保留", 36103809L,
                st.allianceMarches.get(48715L).owner);
        assertTrue(dirty.contains(AssetDomain.WAR));

        // del：{id,owner,type}
        dirty.clear();
        assertTrue(apply("notify_world_alliance_situation_del", map(
                "marchs", list(map("id", 48716L, "owner", 36103809L, "type", 102L)))));
        assertFalse(st.allianceMarches.containsKey(48716L));
        assertEquals(3, st.allianceMarches.size());
        assertTrue(dirty.contains(AssetDomain.WAR));

        // activity：全量替换
        dirty.clear();
        assertTrue(apply("notify_world_alliance_situation_activity", map(
                "marchs", list(situation(48800L, 111L, 1L, 2L, 1791906500L, 42L)),
                "to_marchs", list(), "is_end", "false")));
        assertEquals(1, st.allianceMarches.size());
        assertTrue(st.allianceMarches.containsKey(48800L));
        assertTrue(dirty.contains(AssetDomain.WAR));

        // activity_add：增量
        dirty.clear();
        assertTrue(apply("notify_world_alliance_situation_activity_add", map(
                "marchs", list(situation(48801L, 112L, 2L, 2L, 1791906600L, 43L)))));
        assertEquals(2, st.allianceMarches.size());
    }

    @Test
    public void worldSituation_infoAddDel() {
        Map<String, Object> ps = map("id", 9L,
                "march", march(9001L, 123L, 2L, 2L, 1L, 2L), "lv", 5L, "power", 1000L);
        assertTrue(apply("notify_world_situation_info", map(
                "situation_info", map("9.0", ps))));
        assertEquals(1, st.worldMarches.size());
        assertEquals(1000L, st.warPowers.get(123L).longValue());
        assertTrue(dirty.contains(AssetDomain.WAR));

        dirty.clear();
        Map<String, Object> ps2 = map("id", 10L,
                "march", march(9002L, 456L, 4L, 3L, 3L, 4L), "lv", 6L, "power", 2000L);
        assertTrue(apply("notify_world_situation_info_add", map(
                "marches", map("10.0", ps2))));
        assertEquals(2, st.worldMarches.size());
        assertTrue(dirty.contains(AssetDomain.WAR));

        dirty.clear();
        assertTrue(apply("notify_world_situation_info_del", map("id_arr", list(9L))));
        assertFalse(st.worldMarches.containsKey(9L));
        assertTrue(st.worldMarches.containsKey(10L));
        assertTrue(dirty.contains(AssetDomain.WAR));

        // info_up 合并：march 部分帧（只有 id/state）也必须保留旧字段
        dirty.clear();
        assertTrue(apply("notify_world_situation_info_up", map(
                "marches", map("10.0", map("id", 10L,
                        "march", map("id", 9002L, "state", 4L), "power", 2001L)))));
        assertEquals(4L, st.worldMarches.get(10L).state);
        assertEquals("部分帧未带 owner 时必须保留", 456L, st.worldMarches.get(10L).owner);
        assertEquals(2001L, st.warPowers.get(456L).longValue());
        assertTrue(dirty.contains(AssetDomain.WAR));
    }

    @Test
    public void newMail_factTable() {
        assertTrue(apply("notify_new_mail_syn", map("mail_cover_info", map(
                "first_type", 30L, "second_type", 5L, "send_time", 1791917307L))));
        assertEquals(30L, st.lastMailFirstType);
        assertEquals(5L, st.lastMailSecondType);
        assertTrue(st.lastMailAtMs > 0);
        assertTrue(dirty.contains(AssetDomain.WAR));
    }

    // ================= GUARDIAN =================

    @Test
    public void memberPos_infoUpdateAddDel() {
        assertTrue(apply("notify_alliance_member_pos_info", map("member_list", map(
                "36516817.0", map("uid", 36516817L, "x", 781L, "y", 419L, "kid", 916L,
                        "is_leader", "false"),
                "36515370.0", map("uid", 36515370L, "x", 775L, "y", 418L, "kid", 916L)))));
        assertEquals(2, st.allies.size());
        assertEquals(781L, st.allies.get(36516817L).x);
        assertFalse(st.allies.get(36516817L).leader);
        assertTrue(dirty.contains(AssetDomain.GUARDIAN));

        dirty.clear();
        assertTrue(apply("notify_alliance_member_pos_update", map(
                "member_pos", map("uid", 36516817L, "x", 782L, "y", 419L, "kid", 916L,
                        "is_leader", "true"))));
        assertEquals(782L, st.allies.get(36516817L).x);
        assertTrue(st.allies.get(36516817L).leader);
        assertTrue(dirty.contains(AssetDomain.GUARDIAN));

        dirty.clear();
        assertTrue(apply("notify_alliance_member_pos_add", map(
                "member_pos", map("uid", 40000L, "x", 1L, "y", 2L, "kid", 916L))));
        assertEquals(3, st.allies.size());

        dirty.clear();
        assertTrue(apply("notify_alliance_member_pos_del", map("uid", 40000L)));
        assertEquals(2, st.allies.size());
        assertTrue(dirty.contains(AssetDomain.GUARDIAN));
    }

    @Test
    public void embassy_addDel() {
        assertTrue(apply("notify_embassy_world_march_add", map("march_detail", map(
                "id", 5001L, "owner", 28181058L, "state", 3L,
                "starttime", 1791900000L, "endtime", 1791908000L, "aid", 92200060L,
                "persons_max", 4L, "troop_limit", 100000L))));
        assertEquals(1, st.garrisons.size());
        assertEquals(100000L, st.garrisons.get(5001L).troopLimit);
        assertEquals(3L, st.garrisons.get(5001L).state);
        assertTrue(dirty.contains(AssetDomain.GUARDIAN));

        dirty.clear();
        assertTrue(apply("notify_embassy_world_march_del",
                map("id", 5001L, "num", 1L, "owner", 28181058L)));
        assertTrue(st.garrisons.isEmpty());
        assertTrue(dirty.contains(AssetDomain.GUARDIAN));
    }

    // ================= 兼容/健壮性 =================

    @Test
    public void unknownProtocol_returnsFalse() {
        assertFalse(apply("notify_not_a_real_protocol", map()));
        assertFalse(apply("", map()));
        assertFalse(AssetParsers.apply(null, map(), st, dirty));
        assertTrue(dirty.isEmpty());
    }

    @Test
    public void wrapping_dataContent_andPreUnwrapped() {
        // 响应 {content:{...}} 包装（applyResponse 回灌形态）
        assertTrue(apply("req_alliance_activity_auto_join_info", map("content", map(
                "code", 0L, "end_time", 123L, "limit", 2L, "just_soldier", "false",
                "offline", "false"))));
        assertEquals(123L, st.autoJoinEndTime);
        assertEquals(2L, st.autoJoinLimit);

        // {data:{单对象}} 包装（engine.notifyData 口径）
        assertTrue(apply("notify_buff_show_update", map("data", map(
                "show_id", 81003L,
                "buff_map", map("10505.0", map("k", 10505L, "v", 2L)),
                "expire_ts", nowSec() + 60L))));
        assertEquals(Long.valueOf(2L), st.buffQueues.get(81003L));

        // notify_player_level_up 的 data=base_info 包装
        assertTrue(apply("notify_player_level_up", map("data", map("lv", 42L))));
        assertEquals(42L, st.level);

        // 已去包装的 recover init（content 直接是 "201.0" → item 的 map）
        assertTrue(apply("notify_recover_resource_init", map("201.0", map(
                "id", 201L, "count", 50L, "max_count", 100L, "recover", 1L,
                "next_calc_ts", 1L, "interval", 300L))));
        assertEquals(50L, st.recover.get(201L).count);
    }

    @Test
    public void malformedShapes_doNotThrow() {
        assertTrue(apply("notify_resource_init", map("data", "garbage")));
        assertTrue(apply("notify_recover_resource_init",
                map("data", list("x", 1L, map("no_id", 1L)))));
        assertTrue(apply("notify_base_info", map("nickName", 123L, "lv", "abc")));
        assertTrue(apply("notify_world_player_init",
                map("march_info", "bad", "mass_master_marchs", 42L,
                        "world_extra_march", "bad")));
        assertTrue(apply("notify_buff_show", map("map", list("bad", map("show_id", 1L)))));
        assertTrue(apply("notify_world_alliance_situation_init", map("marchs", "bad")));
        assertTrue(apply("notify_world_situation_info", map("situation_info", 7L)));
        assertTrue(apply("notify_intelligence_info", map("events", "bad")));
        assertTrue(apply("notify_all_research", map("researched", list("bad"))));
        assertTrue(apply("notify_alliance_member_pos_info", map("member_list", "bad")));
        assertTrue(apply("notify_embassy_world_march_add", map("march_detail", "bad")));
        assertTrue(apply("notify_player_march_update", map("id", 1L, "change", "bad")));
        assertTrue(apply("notify_mass_march_del", map()));
        assertTrue(AssetParsers.apply("notify_game_init", null, st, dirty));
    }
}
