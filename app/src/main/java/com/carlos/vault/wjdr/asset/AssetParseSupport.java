package com.carlos.vault.wjdr.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产库解析器共用工具：域对象构造 / 形状兼容 / 变更比较 / 增量合并（包私有）。
 *
 * <p>口径见 {@code design/research-20261024/核心资产库协议与架构盘点.md}：
 * <ul>
 *   <li>sproto 解码后 map 形集合是 {@code {"<key>.0": {...}}}、list 形是 {@code [{...}]}，
 *       两种形状统一在这里归一；</li>
 *   <li>update/add 是 PATCH 语义：仅覆盖「出现的字段」，缺省保留旧值（{@code notify_world_npc_update}
 *       已是 base=-1 的部分帧）；</li>
 *   <li>比较函数用于「无实际变化不标 dirty」；</li>
 *   <li>纯 java.util，Java 8 兼容，不做 IO、不发请求。</li>
 * </ul>
 */
final class AssetParseSupport {

    private AssetParseSupport() {
    }

    // ===================== 通用 =====================

    static boolean has(Map<String, Object> m, String... keys) {
        if (m == null) return false;
        for (String k : keys) {
            if (m.containsKey(k)) return true;
        }
        return false;
    }

    static long num(Map<String, Object> m, String... keys) {
        return AssetState.firstNum(m, 0L, keys);
    }

    /** 嵌套 map；非 Map 返回空 map（不抛）。 */
    static Map<String, Object> child(Map<String, Object> m, String key) {
        return AssetState.child(m, key);
    }

    /** 元素本身是 map 时取自身，否则取 key 子 map（兼容 content 直接就是对象的口径）。 */
    static Map<String, Object> selfOrChild(Map<String, Object> c, String key) {
        Map<String, Object> inner = child(c, key);
        if (!inner.isEmpty()) return inner;
        return c == null ? new LinkedHashMap<String, Object>() : c;
    }

    /** 宽松 id 列表：List/Collection 数字、Map 形 "id.0"→{id}、{id:N} 单对象、嵌套 member_list。 */
    static List<Long> idsOf(Object raw) {
        List<Long> out = new ArrayList<Long>();
        if (raw == null) return out;
        if (raw instanceof Map) {
            Map<String, Object> m = AssetState.mapOf(raw);
            long direct = AssetState.firstNum(m, 0L, "id", "march_id", "uid");
            if (direct > 0) {
                out.add(direct);
                return out;
            }
            for (Object v : m.values()) {
                addId(out, v);
            }
        } else if (raw instanceof List) {
            for (Object v : (List<?>) raw) {
                addId(out, v);
            }
        } else {
            long n = AssetState.valueOf(raw);
            if (n > 0) out.add(n);
        }
        return out;
    }

    private static void addId(List<Long> out, Object v) {
        if (v instanceof Map) {
            long id = AssetState.firstNum(AssetState.mapOf(v), 0L, "id", "march_id", "uid");
            if (id > 0) out.add(id);
        } else {
            long n = AssetState.valueOf(v);
            if (n > 0) out.add(n);
        }
    }

    // ===================== world_march / player_situation =====================

    static AssetSnapshot.QueueMarch marchOf(Map<String, Object> m) {
        if (m == null) m = new LinkedHashMap<String, Object>();
        Map<String, Object> sp = child(m, "startpoint");
        Map<String, Object> ep = child(m, "endpoint");
        Map<String, Object> ms = child(m, "mass_slave");
        Map<String, Object> cr = child(m, "collect_resource");
        return new AssetSnapshot.QueueMarch(
                num(m, "id", "march_id"),
                num(m, "owner"),
                num(m, "type"),
                num(m, "state"),
                num(m, "starttime", "start_time"),
                num(m, "endtime", "end_time", "end_ts"),
                num(m, "target_type"),
                num(m, "targetid", "target_id"),
                num(m, "aid"),
                num(sp, "x"), num(sp, "y"),
                num(ep, "x"), num(ep, "y"),
                num(ms, "master_marchid", "master_march_id"),
                num(cr, "refresh_id"));
    }

    static boolean sameMarch(AssetSnapshot.QueueMarch a, AssetSnapshot.QueueMarch b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.id == b.id && a.owner == b.owner && a.type == b.type && a.state == b.state
                && a.startTime == b.startTime && a.endTime == b.endTime
                && a.targetType == b.targetType && a.targetId == b.targetId && a.aid == b.aid
                && a.startX == b.startX && a.startY == b.startY
                && a.endX == b.endX && a.endY == b.endY
                && a.masterMarchId == b.masterMarchId && a.refreshId == b.refreshId;
    }

    /**
     * world_march 集合归一：List 形直接取元素；Map 形取 value（元素自身即 world_march）。
     * key = march.id（缺失时用集合 key 兜底）。
     */
    static Map<Long, AssetSnapshot.QueueMarch> marchMap(Object raw) {
        Map<Long, AssetSnapshot.QueueMarch> out = new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                putMarch(out, AssetState.mapOf(e.getValue()), AssetState.valueOf(e.getKey()), false);
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) putMarch(out, AssetState.mapOf(o), 0L, false);
            }
        }
        return out;
    }

    /**
     * player_situation 集合归一（notify_world_situation_info{,_add,_up}）：
     * 元素 {@code {id, march{...}, lv, power}}；key = situation id（slot，del 的 id_arr 用它）。
     */
    static Map<Long, AssetSnapshot.QueueMarch> situationMap(Object raw) {
        Map<Long, AssetSnapshot.QueueMarch> out = new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            Map<String, Object> inner = child(e, "march");
            AssetSnapshot.QueueMarch qm = marchOf(inner.isEmpty() ? e : inner);
            // key = player_situation.id（slot）：notify_world_situation_info_del.id_arr 用它删除
            long id = num(e, "id");
            if (id <= 0) id = qm.id;
            if (id > 0) out.put(id, qm);
        }
        return out;
    }

    private static void putMarch(Map<Long, AssetSnapshot.QueueMarch> out, Map<String, Object> m,
                                 long collectionKey, boolean preferCollectionKey) {
        AssetSnapshot.QueueMarch qm = marchOf(m);
        long id;
        if (preferCollectionKey) {
            id = num(m, "id") > 0 ? num(m, "id") : collectionKey;
        } else {
            id = qm.id > 0 ? qm.id : collectionKey;
        }
        if (id > 0) out.put(id, qm);
    }

    static boolean sameMarches(Map<Long, AssetSnapshot.QueueMarch> before,
                               Map<Long, AssetSnapshot.QueueMarch> after) {
        if (before.size() != after.size()) return false;
        for (Map.Entry<Long, AssetSnapshot.QueueMarch> e : after.entrySet()) {
            AssetSnapshot.QueueMarch old = before.get(e.getKey());
            if (!sameMarch(old, e.getValue())) return false;
        }
        return true;
    }

    /** 全量替换（调用方已判定发生变化）。 */
    static void replaceMarches(Map<Long, AssetSnapshot.QueueMarch> dst,
                               Map<Long, AssetSnapshot.QueueMarch> src) {
        dst.clear();
        dst.putAll(src);
    }

    /** PATCH 合并：仅覆盖 change 中出现的字段（含 startpoint/endpoint/mass_slave/collect_resource）。 */
    static AssetSnapshot.QueueMarch mergeMarch(AssetSnapshot.QueueMarch old,
                                               Map<String, Object> change) {
        if (old == null) return marchOf(change);
        if (change == null || change.isEmpty()) return old;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", old.id > 0 ? old.id : num(change, "id"));
        m.put("owner", has(change, "owner") ? num(change, "owner") : old.owner);
        m.put("type", has(change, "type") ? num(change, "type") : old.type);
        m.put("state", has(change, "state") ? num(change, "state") : old.state);
        m.put("starttime", has(change, "starttime", "start_time")
                ? num(change, "starttime", "start_time") : old.startTime);
        m.put("endtime", has(change, "endtime", "end_time", "end_ts")
                ? num(change, "endtime", "end_time", "end_ts") : old.endTime);
        m.put("target_type", has(change, "target_type") ? num(change, "target_type") : old.targetType);
        m.put("targetid", has(change, "targetid", "target_id")
                ? num(change, "targetid", "target_id") : old.targetId);
        m.put("aid", has(change, "aid") ? num(change, "aid") : old.aid);

        Map<String, Object> sp = new LinkedHashMap<String, Object>();
        sp.put("x", old.startX);
        sp.put("y", old.startY);
        Map<String, Object> spNew = child(change, "startpoint");
        if (has(spNew, "x")) sp.put("x", num(spNew, "x"));
        if (has(spNew, "y")) sp.put("y", num(spNew, "y"));
        m.put("startpoint", sp);

        Map<String, Object> ep = new LinkedHashMap<String, Object>();
        ep.put("x", old.endX);
        ep.put("y", old.endY);
        Map<String, Object> epNew = child(change, "endpoint");
        if (has(epNew, "x")) ep.put("x", num(epNew, "x"));
        if (has(epNew, "y")) ep.put("y", num(epNew, "y"));
        m.put("endpoint", ep);

        Map<String, Object> ms = new LinkedHashMap<String, Object>();
        ms.put("master_marchid", old.masterMarchId);
        Map<String, Object> msNew = child(change, "mass_slave");
        if (has(msNew, "master_marchid", "master_march_id")) {
            ms.put("master_marchid", num(msNew, "master_marchid", "master_march_id"));
        }
        m.put("mass_slave", ms);

        Map<String, Object> cr = new LinkedHashMap<String, Object>();
        cr.put("refresh_id", old.refreshId);
        Map<String, Object> crNew = child(change, "collect_resource");
        if (has(crNew, "refresh_id")) cr.put("refresh_id", num(crNew, "refresh_id"));
        m.put("collect_resource", cr);
        return marchOf(m);
    }

    /** world_march_del_keys 语义：aid/mass_* 字段被清空（base=-1 协议）。 */
    static AssetSnapshot.QueueMarch applyDelKeys(AssetSnapshot.QueueMarch old,
                                                  Map<String, Object> del) {
        if (old == null || del == null || del.isEmpty()) return old;
        long aid = old.aid;
        long master = old.masterMarchId;
        if (del.containsKey("aid")) aid = 0L;
        for (String k : del.keySet()) {
            if (k != null && k.startsWith("mass_")) {
                master = 0L;
                break;
            }
        }
        if (aid == old.aid && master == old.masterMarchId) return old;
        return new AssetSnapshot.QueueMarch(old.id, old.owner, old.type, old.state,
                old.startTime, old.endTime, old.targetType, old.targetId, aid, old.startX,
                old.startY, old.endX, old.endY, master, old.refreshId);
    }

    /** 从 world_march / player_situation 集合里提取 owner→power 事实表（外层 power）。 */
    static void collectPowers(Object raw, Map<Long, Long> out) {
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            Map<String, Object> inner = child(e, "march");
            long owner = inner.isEmpty() ? num(e, "owner") : num(inner, "owner");
            if (owner <= 0) owner = num(e, "owner");
            long power = num(e, "power");
            if (owner > 0 && power > 0) out.put(owner, power);
        }
    }

    // ===================== 活动日历 =====================

    static Map<Long, List<AssetSnapshot.Schedule>> schedulesOf(Object raw) {
        Map<Long, List<AssetSnapshot.Schedule>> out =
                new LinkedHashMap<Long, List<AssetSnapshot.Schedule>>();
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            long id = num(e, "id");
            List<AssetSnapshot.Schedule> list = new ArrayList<AssetSnapshot.Schedule>();
            for (Map<String, Object> s : AssetState.mapsOf(e.get("schedule"))) {
                list.add(new AssetSnapshot.Schedule(num(s, "begin_ts", "begin_time"),
                        num(s, "end_ts", "end_time"), num(s, "group_id")));
            }
            if (id > 0 && !list.isEmpty()) out.put(id, list);
        }
        return out;
    }

    static boolean sameScheduleList(List<AssetSnapshot.Schedule> a,
                                    List<AssetSnapshot.Schedule> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            AssetSnapshot.Schedule x = a.get(i);
            AssetSnapshot.Schedule y = b.get(i);
            if (x == null || y == null) {
                if (x != y) return false;
                continue;
            }
            if (x.beginTs != y.beginTs || x.endTs != y.endTs || x.groupId != y.groupId) return false;
        }
        return true;
    }

    static boolean sameSchedules(Map<Long, List<AssetSnapshot.Schedule>> before,
                                 Map<Long, List<AssetSnapshot.Schedule>> after) {
        if (before.size() != after.size()) return false;
        for (Map.Entry<Long, List<AssetSnapshot.Schedule>> e : after.entrySet()) {
            List<AssetSnapshot.Schedule> old = before.get(e.getKey());
            if (old == null || !sameScheduleList(old, e.getValue())) return false;
        }
        return true;
    }

    // ===================== 情报事件 =====================

    static AssetSnapshot.IntelEvent intelEventOf(Map<String, Object> m) {
        if (m == null) m = new LinkedHashMap<String, Object>();
        return new AssetSnapshot.IntelEvent(
                num(m, "id"),
                num(m, "event_id", "eventid", "event_config_id"),
                num(m, "status"),
                num(m, "x"),
                num(m, "y"),
                num(m, "expire_ts", "expire_time"),
                AssetState.boolOf(m.get("played")));
    }

    static boolean sameEvent(AssetSnapshot.IntelEvent a, AssetSnapshot.IntelEvent b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.id == b.id && a.eventId == b.eventId && a.status == b.status
                && a.x == b.x && a.y == b.y && a.expireTs == b.expireTs && a.played == b.played;
    }

    static Map<Long, AssetSnapshot.IntelEvent> eventMap(Object raw) {
        Map<Long, AssetSnapshot.IntelEvent> out =
                new LinkedHashMap<Long, AssetSnapshot.IntelEvent>();
        List<Long> keys = raw instanceof Map
                ? new ArrayList<Long>(keysLong((Map<?, ?>) raw)) : new ArrayList<Long>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Map)) continue;
                AssetSnapshot.IntelEvent ev = intelEventOf(AssetState.mapOf(o));
                if (ev.id > 0) out.put(ev.id, ev);
            }
            return out;
        }
        if (raw instanceof Map) {
            int i = 0;
            for (Object v : ((Map<?, ?>) raw).values()) {
                if (!(v instanceof Map)) {
                    i++;
                    continue;
                }
                AssetSnapshot.IntelEvent ev = intelEventOf(AssetState.mapOf(v));
                long id = ev.id;
                if (id <= 0 && i < keys.size()) id = keys.get(i);
                if (id > 0) out.put(id, ev);
                i++;
            }
        }
        return out;
    }

    private static List<Long> keysLong(Map<?, ?> m) {
        List<Long> out = new ArrayList<Long>();
        for (Object k : m.keySet()) out.add(AssetState.valueOf(k));
        return out;
    }

    static boolean sameEvents(Map<Long, AssetSnapshot.IntelEvent> before,
                              Map<Long, AssetSnapshot.IntelEvent> after) {
        if (before.size() != after.size()) return false;
        for (Map.Entry<Long, AssetSnapshot.IntelEvent> e : after.entrySet()) {
            if (!sameEvent(before.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    // ===================== 可恢复资源 =====================

    static AssetSnapshot.RecoverItem recoverOf(Map<String, Object> m) {
        if (m == null) m = new LinkedHashMap<String, Object>();
        return new AssetSnapshot.RecoverItem(num(m, "id"), num(m, "count"),
                num(m, "max_count", "max"), num(m, "recover", "recover_per"),
                num(m, "next_calc_ts"), num(m, "interval", "interval_sec"));
    }

    /** PATCH：仅覆盖出现的字段（update 帧通常是全字段，但按 base=-1 口径仍保守合并）。 */
    static AssetSnapshot.RecoverItem mergeRecover(AssetSnapshot.RecoverItem old,
                                                  Map<String, Object> m) {
        if (old == null) return recoverOf(m);
        if (m == null || m.isEmpty()) return old;
        long id = has(m, "id") ? num(m, "id") : old.id;
        long count = has(m, "count") ? num(m, "count") : old.count;
        long max = has(m, "max_count", "max") ? num(m, "max_count", "max") : old.maxCount;
        long recover = has(m, "recover", "recover_per") ? num(m, "recover", "recover_per")
                : old.recoverPer;
        long next = has(m, "next_calc_ts") ? num(m, "next_calc_ts") : old.nextCalcTs;
        long interval = has(m, "interval", "interval_sec") ? num(m, "interval", "interval_sec")
                : old.intervalSec;
        return new AssetSnapshot.RecoverItem(id, count, max, recover, next, interval);
    }

    static boolean sameRecover(AssetSnapshot.RecoverItem a, AssetSnapshot.RecoverItem b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.id == b.id && a.count == b.count && a.maxCount == b.maxCount
                && a.recoverPer == b.recoverPer && a.nextCalcTs == b.nextCalcTs
                && a.intervalSec == b.intervalSec;
    }

    static Map<Long, AssetSnapshot.RecoverItem> recoverMap(Object raw) {
        Map<Long, AssetSnapshot.RecoverItem> out =
                new LinkedHashMap<Long, AssetSnapshot.RecoverItem>();
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            AssetSnapshot.RecoverItem item = recoverOf(e);
            if (item.id > 0) out.put(item.id, item);
        }
        return out;
    }

    // ===================== 巨兽每日次数 =====================

    /** "1.0"/"1" → 1（map 形集合的 key 统一取 "." 前整数部分）。 */
    static long keyId(Object key) {
        long k = AssetState.valueOf(key);
        if (k > 0 || key == null) return k;
        String s = String.valueOf(key).trim();
        int dot = s.indexOf('.');
        if (dot > 0) k = AssetState.valueOf(s.substring(0, dot));
        return k;
    }

    /** map_daily_count：Map 形 key "1.0"→{key,count} 或 "1.0"→count，List 形 {key,count}。 */
    static Map<Long, Long> countMap(Object raw) {
        Map<Long, Long> out = new LinkedHashMap<Long, Long>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                long key = keyId(entry.getKey());
                Object v = entry.getValue();
                long k = 0L;
                long count;
                if (v instanceof Map) {
                    Map<String, Object> inner = AssetState.mapOf(v);
                    k = num(inner, "key", "id");
                    count = num(inner, "count", "v");
                } else {
                    count = AssetState.valueOf(v);
                }
                long id = k > 0 ? k : key;
                if (id > 0) out.put(id, count);
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> inner = AssetState.mapOf(o);
                long id = num(inner, "key", "id");
                if (id > 0) out.put(id, num(inner, "count", "v"));
            }
        }
        return out;
    }

    // ===================== 盟友坐标 / 驻防 =====================

    static AssetSnapshot.Ally allyOf(Map<String, Object> m) {
        if (m == null) m = new LinkedHashMap<String, Object>();
        long uid = num(m, "uid", "id");
        boolean leader = AssetState.boolOf(m.get("is_leader")) || AssetState.boolOf(m.get("leader"));
        return new AssetSnapshot.Ally(uid, num(m, "x"), num(m, "y"), num(m, "kid"), leader, 0L);
    }

    static boolean sameAlly(AssetSnapshot.Ally a, AssetSnapshot.Ally b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.uid == b.uid && a.x == b.x && a.y == b.y && a.kid == b.kid
                && a.leader == b.leader;
    }

    static AssetSnapshot.Garrison garrisonOf(Map<String, Object> detail, long targetUid) {
        if (detail == null) detail = new LinkedHashMap<String, Object>();
        return new AssetSnapshot.Garrison(
                num(detail, "id", "march_id"),
                num(detail, "owner"),
                targetUid,
                num(detail, "state"),
                num(detail, "endtime", "end_time"),
                num(detail, "troop_limit", "troopLimit"));
    }

    static boolean sameGarrison(AssetSnapshot.Garrison a, AssetSnapshot.Garrison b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.marchId == b.marchId && a.owner == b.owner && a.targetUid == b.targetUid
                && a.state == b.state && a.endTime == b.endTime
                && a.troopLimit == b.troopLimit;
    }
}
