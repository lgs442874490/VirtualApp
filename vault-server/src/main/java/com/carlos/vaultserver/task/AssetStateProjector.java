package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.asset.AssetSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * 核心资产库 → StateStore 投影（只读纯函数；见 design 总体规划 W1/W4）。
 *
 * <p>服务端每角色资产库在版本变化后节流（≤1 次/秒，节流逻辑在
 * {@code SessionKeeper}）写入 {@code srv:asset:{fpid}:{sid}}，经既有
 * {@code /v1/vault/state} 通道回 App；采集设置页/角色卡据此展示王国天数、
 * 可采资源等级上限、队列占用、资源与体力摘要。
 *
 * <p>JSON 契约（字段名固定，画面/其它结构化消费方按此解析；数值未知用 0、missing 为空数组）：
 * <pre>
 * {
 *   "fpid": "48460403", "sid": 913, "uid": 28181058,
 *   "version": 12, "ready": true, "readyAt": 169...ms, "updatedAt": 169...ms,
 *   "kingdomDay": 5, "maxResourceLevel": 8, "serverNow": 169...s,
 *   "queue": {"capacity": 4, "rawCapacity": 4, "occupied": 2, "idle": 2,
 *             "pending": 0, "limitReached": false,
 *             "specialByType": {"411": 1}, "byType": {"4": 2}},
 *   "resources": {"diamond": 1, "meat": 2, "wood": 3, "coal": 4, "iron": 5,
 *                 "steel": 0, "allianceCoin": 0},
 *   "stamina": {"current": 1035, "max": 200, "nextRecalcAt": 169...s,
 *               "nextRecoverAt": 169...s, "recoverPer": 1, "intervalSec": 300,
 *               "currentAt": 1035},
 *   "city": {"x": 585, "y": 694},
 *   "missing": ["notify_alliance_init", ...]
 * }
 * </pre>
 *
 * <p>回滚开关：{@code VAULT_ASSET_LIBRARY=off}（环境变量）时，本类
 * {@link #enabled()} 返回 false，各读侧（ServerTasks/ServerGuardianEngine）全部走
 * 旧逻辑（attach 仍可做，仅读侧回退）。
 */
public final class AssetStateProjector {

    /** StateStore 键前缀：{@code srv:asset:{fpid}:{sid}}。 */
    public static final String STATE_KEY_PREFIX = "srv:asset:";

    private AssetStateProjector() {
    }

    /** 资产库读侧总开关（默认 on；{@code VAULT_ASSET_LIBRARY=off} 回滚旧读取逻辑）。 */
    public static boolean enabled() {
        try {
            String v = System.getenv("VAULT_ASSET_LIBRARY");
            return v == null || !"off".equalsIgnoreCase(v.trim());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 投影 StateStore 键（旧契约：fpid:sid，仅活跃角色写入；同服多角色会互相覆盖，新读侧用 uid 键）。 */
    public static String stateKey(String fpid, long sid) {
        return STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + sid;
    }

    /** 投影 StateStore 键（按角色 uid 区分，同服多角色各自独立）。 */
    public static String stateKey(String fpid, long sid, long uid) {
        return STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + sid + ":" + uid;
    }

    /** 纯函数：快照 → JSON 文本（永不返回 null；异常时返回最小可用对象）。 */
    public static String toJson(AssetSnapshot s, String fpid, long sid) {
        JSONObject o = new JSONObject();
        try {
            o.put("fpid", fpid == null ? "" : fpid);
            o.put("sid", sid);
            o.put("uid", s == null || s.key == null ? 0L : s.key.uid);
            if (s == null) {
                o.put("version", 0L);
                o.put("ready", false);
                o.put("readyAt", 0L);
                o.put("updatedAt", 0L);
                o.put("kingdomDay", 0);
                o.put("maxResourceLevel", 0);
                o.put("serverNow", System.currentTimeMillis() / 1000L);
                o.put("queue", emptyQueue());
                o.put("resources", emptyResources());
                o.put("stamina", emptyStamina());
                o.put("city", emptyCity());
                o.put("meta", emptyMeta());
                o.put("intel", emptyIntel());
                o.put("behemoth", emptyBehemoth());
                o.put("bear", emptyBear());
                o.put("war", emptyWar());
                o.put("guardian", emptyGuardian());
                o.put("calendar", emptyCalendar());
                o.put("backpack", emptyBackpack());
                o.put("autoJoin", emptyAutoJoin());
                o.put("missing", new JSONArray());
                return o.toString();
            }
            o.put("version", s.version);
            o.put("ready", s.ready);
            o.put("readyAt", s.readyAtMs);
            o.put("updatedAt", s.updatedAtMs);
            o.put("kingdomDay", s.clock == null ? 0 : s.clock.kingdomDay);
            o.put("maxResourceLevel", s.clock == null ? 0 : s.clock.maxResourceLevel);
            long nowSec = s.clock == null ? System.currentTimeMillis() / 1000L : s.clock.serverNowSec();
            o.put("serverNow", nowSec);
            o.put("queue", queueJson(s.queue));
            o.put("resources", resourcesJson(s.resources));
            o.put("stamina", staminaJson(s.stamina, nowSec));
            o.put("city", cityJson(s.meta));
            o.put("meta", metaJson(s.meta));
            o.put("intel", intelJson(s.intel, nowSec));
            o.put("behemoth", behemothJson(s.behemoth));
            o.put("bear", bearJson(s.bear));
            o.put("war", warJson(s.war));
            o.put("guardian", guardianJson(s.guardian));
            o.put("calendar", calendarJson(s.calendar, nowSec));
            o.put("backpack", backpackJson(s.backpack));
            o.put("autoJoin", autoJoinJson(s.autoJoin));
            o.put("missing", new JSONArray(s.missingProtocols == null
                    ? java.util.Collections.<String>emptySet() : s.missingProtocols));
            return o.toString();
        } catch (Throwable t) {
            // 投影失败绝不影响引擎/任务线程：返回能表达「未知」的最小对象
            try {
                JSONObject fallback = new JSONObject();
                fallback.put("fpid", fpid == null ? "" : fpid);
                fallback.put("sid", sid);
                fallback.put("ready", false);
                fallback.put("version", 0L);
                fallback.put("updatedAt", System.currentTimeMillis());
                fallback.put("error", String.valueOf(t.getMessage()));
                return fallback.toString();
            } catch (Throwable ignored) {
                return "{}";
            }
        }
    }

    private static JSONObject queueJson(AssetSnapshot.Queue q) {
        if (q == null) return emptyQueue();
        JSONObject o = new JSONObject();
        o.put("capacity", q.capacity);
        o.put("rawCapacity", q.rawCapacity);
        o.put("occupied", q.occupied);
        o.put("idle", q.idle);
        o.put("pending", q.pending);
        o.put("limitReached", q.limitReached);
        o.put("specialByType", countMap(q.specialByType));
        o.put("byType", countMap(q.byType));
        o.put("source", q.source == null ? "" : q.source);
        o.put("updatedAt", q.updatedAtMs);
        return o;
    }

    private static JSONObject emptyQueue() {
        JSONObject o = new JSONObject();
        o.put("capacity", 0);
        o.put("rawCapacity", 0);
        o.put("occupied", 0);
        o.put("idle", 0);
        o.put("pending", 0);
        o.put("limitReached", false);
        o.put("specialByType", new JSONObject());
        o.put("byType", new JSONObject());
        o.put("source", "none");
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject resourcesJson(AssetSnapshot.Resources r) {
        if (r == null) return emptyResources();
        JSONObject o = new JSONObject();
        o.put("diamond", r.diamond());
        o.put("meat", r.meat());
        o.put("wood", r.wood());
        o.put("coal", r.coal());
        o.put("iron", r.iron());
        o.put("steel", r.steel());
        o.put("allianceCoin", r.allianceCoin());
        o.put("updatedAt", r.updatedAtMs);
        return o;
    }

    private static JSONObject emptyResources() {
        JSONObject o = new JSONObject();
        o.put("diamond", 0L);
        o.put("meat", 0L);
        o.put("wood", 0L);
        o.put("coal", 0L);
        o.put("iron", 0L);
        o.put("steel", 0L);
        o.put("allianceCoin", 0L);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject staminaJson(AssetSnapshot.Stamina st, long nowSec) {
        if (st == null) return emptyStamina();
        JSONObject o = new JSONObject();
        o.put("current", st.current);
        o.put("max", st.max);
        o.put("nextRecalcAt", st.nextCalcTs);
        // 兼容旧设计文档字段名（nextRecoverAt 与 nextRecalcAt 同值）
        o.put("nextRecoverAt", st.nextCalcTs);
        o.put("recoverPer", st.recoverPer);
        o.put("intervalSec", st.intervalSec);
        o.put("currentAt", st.staminaAt(nowSec));
        return o;
    }

    private static JSONObject emptyStamina() {
        JSONObject o = new JSONObject();
        o.put("current", 0L);
        o.put("max", 0L);
        o.put("nextRecalcAt", 0L);
        o.put("nextRecoverAt", 0L);
        o.put("recoverPer", 0L);
        o.put("intervalSec", 0L);
        o.put("currentAt", 0L);
        return o;
    }

    /** 城市坐标投影：空快照/未知为 {x:0,y:0}（巨兽/巨熊/守护任务距离判断用）。 */
    private static JSONObject cityJson(AssetSnapshot.Meta meta) {
        if (meta == null) return emptyCity();
        JSONObject o = new JSONObject();
        o.put("x", meta.cityX);
        o.put("y", meta.cityY);
        return o;
    }

    private static JSONObject emptyCity() {
        JSONObject o = new JSONObject();
        o.put("x", 0L);
        o.put("y", 0L);
        return o;
    }

    private static JSONObject countMap(Map<Long, Integer> in) {
        JSONObject o = new JSONObject();
        if (in == null) return o;
        for (Map.Entry<Long, Integer> e : in.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            try {
                o.put(String.valueOf(e.getKey().longValue()), e.getValue().intValue());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    // ======================= 扩展域（W9/资产页：只读投影） =======================

    private static JSONObject emptyMeta() {
        JSONObject o = new JSONObject();
        o.put("nickName", "");
        o.put("level", 0L);
        o.put("stoveLv", 0L);
        o.put("power", 0L);
        o.put("allianceId", 0L);
        o.put("allianceName", "");
        o.put("allianceAbbr", "");
        o.put("cityX", 0L);
        o.put("cityY", 0L);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject metaJson(AssetSnapshot.Meta m) {
        if (m == null) return emptyMeta();
        JSONObject o = new JSONObject();
        try {
            o.put("nickName", m.nickName == null ? "" : m.nickName);
            o.put("level", m.level);
            o.put("stoveLv", m.stoveLv);
            o.put("power", m.power);
            o.put("allianceId", m.allianceId);
            o.put("allianceName", m.allianceName == null ? "" : m.allianceName);
            o.put("allianceAbbr", m.allianceAbbr == null ? "" : m.allianceAbbr);
            o.put("cityX", m.cityX);
            o.put("cityY", m.cityY);
            o.put("updatedAt", m.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyIntel() {
        JSONObject o = new JSONObject();
        o.put("radarLevel", 0L);
        o.put("radarExp", 0L);
        o.put("powerLevel", 0L);
        o.put("nextEventTime", 0L);
        o.put("total", 0);
        o.put("dispatchable", 0);
        o.put("claimable", 0);
        o.put("fighting", 0);
        o.put("events", new JSONArray());
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject intelJson(AssetSnapshot.Intel i, long nowSec) {
        if (i == null) return emptyIntel();
        JSONObject o = new JSONObject();
        try {
            int dispatchable = 0;
            int claimable = 0;
            int fighting = 0;
            JSONArray events = new JSONArray();
            for (AssetSnapshot.IntelEvent e : i.events.values()) {
                if (e == null) continue;
                boolean expired = e.expireTs > 0 && e.expireTs <= nowSec;
                if (e.status == 1L && !expired) dispatchable++;
                if (e.status == 2L) claimable++;
                if (e.status == 3L) fighting++;
                if (events.length() < 20) {
                    JSONObject ev = new JSONObject();
                    ev.put("id", e.id);
                    ev.put("eventId", e.eventId);
                    ev.put("status", e.status);
                    ev.put("x", e.x);
                    ev.put("y", e.y);
                    ev.put("expireTs", e.expireTs);
                    ev.put("expired", expired);
                    events.put(ev);
                }
            }
            o.put("radarLevel", i.radarLevel);
            o.put("radarExp", i.radarExp);
            o.put("powerLevel", i.powerLevel);
            o.put("nextEventTime", i.nextEventTime);
            o.put("total", i.events.size());
            o.put("dispatchable", dispatchable);
            o.put("claimable", claimable);
            o.put("fighting", fighting);
            o.put("events", events);
            o.put("updatedAt", i.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyBehemoth() {
        JSONObject o = new JSONObject();
        o.put("monsterMaxLv", 0L);
        o.put("lastDailyTime", 0L);
        o.put("expertRewardDailyCount", 0L);
        o.put("joinCount", 0);
        o.put("masterCount", 0);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject behemothJson(AssetSnapshot.Behemoth b) {
        if (b == null) return emptyBehemoth();
        JSONObject o = new JSONObject();
        try {
            o.put("monsterMaxLv", b.monsterMaxLv);
            o.put("lastDailyTime", b.lastDailyTime);
            o.put("expertRewardDailyCount", b.expertRewardDailyCount);
            o.put("joinCount", b.joinDailyCounts == null ? 0 : b.joinDailyCounts.size());
            o.put("masterCount", b.masterDailyCounts == null ? 0 : b.masterDailyCounts.size());
            o.put("updatedAt", b.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyBear() {
        JSONObject o = new JSONObject();
        o.put("reserveTime", 0L);
        o.put("massStartTime", 0L);
        o.put("massEndTime", 0L);
        o.put("coolEndTime", 0L);
        o.put("autoReserve", false);
        o.put("reserveKnown", false);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject bearJson(AssetSnapshot.Bear b) {
        if (b == null) return emptyBear();
        JSONObject o = new JSONObject();
        try {
            o.put("reserveTime", b.reserveTime);
            o.put("massStartTime", b.massStartTime);
            o.put("massEndTime", b.massEndTime);
            o.put("coolEndTime", b.coolEndTime);
            o.put("autoReserve", b.autoReserve);
            o.put("reserveKnown", b.reserveKnown);
            o.put("updatedAt", b.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyWar() {
        JSONObject o = new JSONObject();
        o.put("allianceMarches", 0);
        o.put("worldMarches", 0);
        o.put("powers", 0);
        o.put("lastMailFirstType", 0L);
        o.put("lastMailSecondType", 0L);
        o.put("lastMailAt", 0L);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject warJson(AssetSnapshot.War w) {
        if (w == null) return emptyWar();
        JSONObject o = new JSONObject();
        try {
            o.put("allianceMarches", w.allianceMarches == null ? 0 : w.allianceMarches.size());
            o.put("worldMarches", w.worldMarches == null ? 0 : w.worldMarches.size());
            o.put("powers", w.powers == null ? 0 : w.powers.size());
            o.put("lastMailFirstType", w.lastMailFirstType);
            o.put("lastMailSecondType", w.lastMailSecondType);
            o.put("lastMailAt", w.lastMailAtMs);
            o.put("updatedAt", w.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyGuardian() {
        JSONObject o = new JSONObject();
        o.put("allies", 0);
        o.put("garrisons", 0);
        o.put("holds", 0);
        o.put("garrisonList", new JSONArray());
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject guardianJson(AssetSnapshot.Guardian g) {
        if (g == null) return emptyGuardian();
        JSONObject o = new JSONObject();
        try {
            int allies = g.allies == null ? 0 : g.allies.size();
            int garrisons = g.garrisons == null ? 0 : g.garrisons.size();
            JSONArray list = new JSONArray();
            if (g.garrisons != null) {
                for (AssetSnapshot.Garrison gr : g.garrisons.values()) {
                    if (gr == null || list.length() >= 10) continue;
                    JSONObject item = new JSONObject();
                    item.put("marchId", gr.marchId);
                    item.put("owner", gr.owner);
                    item.put("targetUid", gr.targetUid);
                    item.put("state", gr.state);
                    item.put("endTime", gr.endTime);
                    item.put("troopLimit", gr.troopLimit);
                    list.put(item);
                }
            }
            o.put("allies", allies);
            o.put("garrisons", garrisons);
            o.put("holds", g.holds == null ? 0 : g.holds.size());
            o.put("garrisonList", list);
            o.put("updatedAt", g.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyCalendar() {
        JSONObject o = new JSONObject();
        o.put("schedules", 0);
        o.put("active", 0);
        o.put("nextEnd", 0L);
        return o;
    }

    private static JSONObject calendarJson(AssetSnapshot.Calendar c, long nowSec) {
        JSONObject o = emptyCalendar();
        if (c == null || c.schedules == null) return o;
        try {
            int active = 0;
            long nextEnd = 0L;
            int schedules = 0;
            for (Map.Entry<Long, java.util.List<AssetSnapshot.Schedule>> e : c.schedules.entrySet()) {
                java.util.List<AssetSnapshot.Schedule> list = e.getValue();
                if (list == null || list.isEmpty()) continue;
                schedules += list.size();
                boolean isActive = false;
                for (AssetSnapshot.Schedule sc : list) {
                    if (sc.beginTs <= nowSec && (sc.endTs <= 0 || nowSec < sc.endTs)) {
                        isActive = true;
                        if (sc.endTs > nowSec && (nextEnd == 0 || sc.endTs < nextEnd)) nextEnd = sc.endTs;
                    }
                }
                if (isActive) active++;
            }
            o.put("schedules", schedules);
            o.put("active", active);
            o.put("nextEnd", nextEnd);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyBackpack() {
        JSONObject o = new JSONObject();
        o.put("items", 0);
        o.put("stamina610002", 0L);
        o.put("speedup302000", 0L);
        o.put("speedup302001", 0L);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject backpackJson(AssetSnapshot.Backpack b) {
        if (b == null || b.items == null) return emptyBackpack();
        JSONObject o = new JSONObject();
        try {
            o.put("items", b.items.size());
            o.put("stamina610002", b.get(610002L));
            o.put("speedup302000", b.get(302000L));
            o.put("speedup302001", b.get(302001L));
            o.put("updatedAt", b.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject emptyAutoJoin() {
        JSONObject o = new JSONObject();
        o.put("endTime", 0L);
        o.put("limit", 0L);
        o.put("justSoldier", false);
        o.put("offline", false);
        o.put("selected", 0);
        o.put("updatedAt", 0L);
        return o;
    }

    private static JSONObject autoJoinJson(AssetSnapshot.AutoJoin a) {
        if (a == null) return emptyAutoJoin();
        JSONObject o = new JSONObject();
        try {
            o.put("endTime", a.endTime);
            o.put("limit", a.limit);
            o.put("justSoldier", a.justSoldier);
            o.put("offline", a.offline);
            o.put("selected", a.selectedIds == null ? 0 : a.selectedIds.size());
            o.put("updatedAt", a.updatedAtMs);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
