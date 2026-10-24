package com.carlos.vault.wjdr.asset;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产库协议解析分发（单写者线程内调用；纯函数式：只读写 {@link AssetState} 与 dirty 集）。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>{@code apply(...)} 返回 true=本帧属于资产库且已处理；false=不关心（不影响 version）；</li>
 *   <li>解析器只在有实际变化时才往 {@code dirty} 加 {@link AssetDomain}；无变化返回 true 但不加 dirty；</li>
 *   <li>init 类协议做「全量替换」；update/add 类做「增量/upsert」；del 类做删除；</li>
 *   <li>形状兼容统一用 {@link AssetState#mapOf/mapsOf/listOf/longMap} 等工具（Map 形 "201.0"、List 形、
 *       去包装 data/info/content）；</li>
 *   <li>严禁在解析器内调用 {@code engine.request}（inbound 线程发请求会死锁）；</li>
 *   <li>异常一律向上抛由 {@link CoreAssetLibrary} 捕获记 parseErrors，不要吞掉当成功。</li>
 * </ul>
 *
 * <p><b>域-协议映射</b>（详见 design/research-20261024/核心资产库协议与架构盘点.md §1）：
 * <pre>
 * META:      notify_base_info, notify_player_power_update, notify_player_level_up,
 *            notify_alliance_init(player 段), notify_alliance_update
 * CLOCK:     notify_game_init, notify_server_time, notify_schedule_update
 * CALENDAR:  notify_activity_calendar_data
 * RESOURCE:  notify_resource_init, notify_resource_update
 * RECOVER:   notify_recover_resource_init, notify_recover_resource_update
 * BACKPACK:  notify_backpack_init, notify_backpack_update
 * QUEUE:     notify_world_player_init（其 city_point 归 META 域）, notify_player_march_add/update/del,
 *            notify_mass_march_add/update/del, notify_all_research, notify_research_complete,
 *            notify_vip_info, notify_vip_update, notify_buff_show, notify_buff_show_update,
 *            notify_buff_show_delete, notify_world_add_extra_march,
 *            req_world_march(response)
 * INTEL:     notify_intelligence_info, notify_intelligence_events, notify_intelligence_events_del,
 *            notify_intelligence_battle_result, notify_intelligence_power_info
 * BEHEMOTH:  notify_world_npc_init, notify_world_npc_update（auto_join 状态也挂 BEHEMOTH 域）
 * AUTOJOIN:  req_alliance_activity_auto_join_info(response), req_alliance_activity_auto_join_open(response)
 * BEAR:      notify_alliance_trap_update, req_alliance_trap_info(response)
 * WAR:       notify_world_alliance_situation_init/add/update/del/activity*,
 *            notify_world_situation_info/_add/_up/_del, notify_new_mail_syn
 * GUARDIAN:  notify_alliance_member_pos_info/add/update/del, notify_embassy_world_march_add/del
 * </pre>
 *
 * <p>Java 8 兼容，纯 java.util。
 */
final class AssetParsers {

    static boolean apply(String proto, Map<String, Object> rawContent, AssetState st,
                         Set<AssetDomain> dirty) {
        if (proto == null || proto.isEmpty()) return false;
        Map<String, Object> content = rawContent == null
                ? new LinkedHashMap<String, Object>() : rawContent;

        // ===== 时钟/元数据 =====
        if ("notify_game_init".equals(proto)) return gameInit(content, st, dirty);
        if ("notify_server_time".equals(proto)) return serverTime(content, st, dirty);
        if ("notify_schedule_update".equals(proto)) return scheduleUpdate(content, st, dirty);
        if ("notify_base_info".equals(proto)) return baseInfo(content, st, dirty);
        if ("notify_player_power_update".equals(proto)) return powerUpdate(content, st, dirty);
        if ("notify_player_level_up".equals(proto)) return levelUp(content, st, dirty);
        if ("notify_alliance_init".equals(proto) || "notify_alliance_update".equals(proto)) {
            return alliance(content, st, dirty);
        }
        if ("notify_activity_calendar_data".equals(proto)) return calendar(content, st, dirty);

        // ===== 资源/体力/背包 =====
        if ("notify_resource_init".equals(proto)) return resourceInit(content, st, dirty);
        if ("notify_resource_update".equals(proto)) return resourceUpdate(content, st, dirty);
        if ("notify_recover_resource_init".equals(proto)) return recoverInit(content, st, dirty);
        if ("notify_recover_resource_update".equals(proto)) return recoverUpdate(content, st, dirty);
        if ("notify_backpack_init".equals(proto)) return backpackInit(content, st, dirty);
        if ("notify_backpack_update".equals(proto)) return backpackUpdate(content, st, dirty);

        // ===== 队列 =====
        if ("notify_world_player_init".equals(proto)) return worldPlayerInit(content, st, dirty);
        if ("notify_player_march_add".equals(proto)) return playerMarchAdd(content, st, dirty);
        if ("notify_player_march_update".equals(proto)) return playerMarchUpdate(content, st, dirty);
        if ("notify_player_march_del".equals(proto)) return playerMarchDel(content, st, dirty);
        if ("notify_mass_march_add".equals(proto)) return massMarchAdd(content, st, dirty);
        if ("notify_mass_march_update".equals(proto)) return massMarchUpdate(content, st, dirty);
        if ("notify_mass_march_del".equals(proto)) return massMarchDel(content, st, dirty);
        if ("notify_all_research".equals(proto)) return researchInit(content, st, dirty);
        if ("notify_research_complete".equals(proto)) return researchComplete(content, st, dirty);
        if ("notify_vip_info".equals(proto) || "notify_vip_update".equals(proto)) {
            return vip(content, st, dirty);
        }
        if ("notify_buff_show".equals(proto)) return buffShow(content, st, dirty);
        if ("notify_buff_show_update".equals(proto)) return buffShowUpdate(content, st, dirty);
        if ("notify_buff_show_delete".equals(proto)) return buffShowDelete(content, st, dirty);
        if ("notify_world_add_extra_march".equals(proto)) return extraMarch(content, st, dirty);
        if ("req_world_march".equals(proto)) return marchResponse(content, st, dirty);

        // ===== 情报 =====
        if ("notify_intelligence_info".equals(proto)) return intelInfo(content, st, dirty);
        if ("notify_intelligence_events".equals(proto)) return intelEvents(content, st, dirty);
        if ("notify_intelligence_events_del".equals(proto)) return intelEventsDel(content, st, dirty);
        if ("notify_intelligence_battle_result".equals(proto)) {
            return intelBattleResult(content, st, dirty);
        }
        if ("notify_intelligence_power_info".equals(proto)) return intelPowerInfo(content, st, dirty);

        // ===== 巨兽/巨熊 =====
        if ("notify_world_npc_init".equals(proto)) return npcInit(content, st, dirty);
        if ("notify_world_npc_update".equals(proto)) return npcUpdate(content, st, dirty);
        if ("req_alliance_activity_auto_join_info".equals(proto)
                || "req_alliance_activity_auto_join_open".equals(proto)) {
            return autoJoinResponse(content, st, dirty);
        }
        if ("notify_alliance_trap_update".equals(proto)) return bearTrap(content, st, dirty);
        if ("req_alliance_trap_info".equals(proto)) return bearTrapInfoResponse(content, st, dirty);

        // ===== 战争事实 =====
        if (proto.startsWith("notify_world_alliance_situation")) {
            return allianceSituation(proto, content, st, dirty);
        }
        if (proto.startsWith("notify_world_situation_")) {
            return worldSituation(proto, content, st, dirty);
        }
        if ("notify_new_mail_syn".equals(proto)) return newMail(content, st, dirty);

        // ===== 驻防队友 =====
        if (proto.startsWith("notify_alliance_member_pos")) return memberPos(proto, content, st, dirty);
        if ("notify_embassy_world_march_add".equals(proto)) return embassyAdd(content, st, dirty);
        if ("notify_embassy_world_march_del".equals(proto)) return embassyDel(content, st, dirty);

        return false;
    }

    // ================= 通用小工具 =================

    private static void mark(AssetState st, Set<AssetDomain> dirty, AssetDomain... domains) {
        for (AssetDomain d : domains) {
            dirty.add(d);
            st.markFresh(d);
        }
    }

    /** 协议载荷：优先取 data 包装（兼容 engine.notifyData 口径），否则用 content 本身。 */
    private static Object payload(Map<String, Object> c) {
        Object v = c.get("data");
        return v != null ? v : c;
    }

    /** 单对象载荷：去 data/info/content 包装。 */
    private static Map<String, Object> body(Map<String, Object> c) {
        return AssetState.unwrap(c);
    }

    private static boolean has(Map<String, Object> m, String... keys) {
        if (m == null) return false;
        for (String k : keys) {
            if (m.containsKey(k)) return true;
        }
        return false;
    }

    private static long num(Map<String, Object> m, String... keys) {
        return AssetState.firstNum(m, 0L, keys);
    }

    private static String str(Map<String, Object> m, String... keys) {
        return AssetState.firstStr(m, keys);
    }

    private static boolean sameRecoverMaps(Map<Long, AssetSnapshot.RecoverItem> a,
                                           Map<Long, AssetSnapshot.RecoverItem> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<Long, AssetSnapshot.RecoverItem> e : b.entrySet()) {
            if (!AssetParseSupport.sameRecover(a.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    private static boolean sameAllies(Map<Long, AssetSnapshot.Ally> a,
                                      Map<Long, AssetSnapshot.Ally> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<Long, AssetSnapshot.Ally> e : b.entrySet()) {
            if (!AssetParseSupport.sameAlly(a.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    private static boolean sameGarrisons(Map<Long, AssetSnapshot.Garrison> a,
                                         Map<Long, AssetSnapshot.Garrison> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<Long, AssetSnapshot.Garrison> e : b.entrySet()) {
            if (!AssetParseSupport.sameGarrison(a.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    /** upsert 一条 march；有实际差异返回 true。 */
    private static boolean upsertMarch(Map<Long, AssetSnapshot.QueueMarch> dst,
                                       AssetSnapshot.QueueMarch qm) {
        if (qm == null || qm.id <= 0) return false;
        if (AssetParseSupport.sameMarch(dst.get(qm.id), qm)) return false;
        dst.put(qm.id, qm);
        return true;
    }

    /**
     * PATCH 合并集合：元素为 world_march（alliance 域）或 player_situation（world 域，
     * 内层 march + 外层 id/power）；update 类帧可能是部分字段（sproto base=-1），
     * 仅覆盖出现字段、缺省保留旧值。
     */
    private static boolean mergeMarches(Map<Long, AssetSnapshot.QueueMarch> dst, Object raw,
                                        boolean playerSituation) {
        boolean changed = false;
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            Map<String, Object> march = playerSituation
                    ? AssetParseSupport.child(e, "march") : e;
            if (march.isEmpty()) continue;
            long key = AssetState.firstNum(playerSituation ? e : march, 0L, "id");
            if (key <= 0) key = AssetState.firstNum(march, 0L, "id");
            if (key <= 0) continue;
            AssetSnapshot.QueueMarch old = dst.get(key);
            AssetSnapshot.QueueMarch merged = old == null
                    ? AssetParseSupport.marchOf(march)
                    : AssetParseSupport.mergeMarch(old, march);
            if (AssetParseSupport.sameMarch(old, merged)) continue;
            dst.put(key, merged);
            changed = true;
        }
        return changed;
    }

    private static boolean removeMarches(Map<Long, AssetSnapshot.QueueMarch> dst, Object raw) {
        boolean changed = false;
        for (Long id : AssetParseSupport.idsOf(raw)) {
            if (dst.remove(id) != null) changed = true;
        }
        return changed;
    }

    // ================= CLOCK / META / CALENDAR =================

    private static boolean gameInit(Map<String, Object> c, AssetState st, Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "project_start_ts", "project_start_time")) {
            long v = num(m, "project_start_ts", "project_start_time");
            if (v != st.projectStartTs) {
                st.projectStartTs = v;
                changed = true;
            }
        }
        if (has(m, "servertime", "server_time", "time")) {
            long v = num(m, "servertime", "server_time", "time");
            if (v > 0 && (v != st.serverTimeSec || st.serverTimeRecvAtMs <= 0)) {
                st.serverTimeSec = v;
                st.serverTimeRecvAtMs = now;
                changed = true;
            }
        }
        if (has(m, "utc_offset")) {
            long v = num(m, "utc_offset");
            if (v != st.utcOffsetSec) {
                st.utcOffsetSec = v;
                changed = true;
            }
        }
        if (has(m, "open_time")) {
            long v = num(m, "open_time");
            if (v != st.openTime) {
                st.openTime = v;
                changed = true;
            }
        }
        if (has(m, "zero_time")) {
            long v = num(m, "zero_time");
            if (v != st.zeroTime) {
                st.zeroTime = v;
                changed = true;
            }
        }
        if (changed) {
            st.clockAtMs = now;
            mark(st, dirty, AssetDomain.CLOCK);
        }
        return true;
    }

    private static boolean serverTime(Map<String, Object> c, AssetState st,
                                      Set<AssetDomain> dirty) {
        long now = st.now();
        long t = num(body(c), "time", "servertime", "server_time");
        if (t > 0 && (t != st.serverTimeSec || st.serverTimeRecvAtMs <= 0)) {
            st.serverTimeSec = t;
            st.serverTimeRecvAtMs = now;
            st.clockAtMs = now;
            mark(st, dirty, AssetDomain.CLOCK);
        }
        return true;
    }

    private static boolean scheduleUpdate(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        long now = st.now();
        long t = num(body(c), "server_time", "time", "servertime");
        if (t > 0 && (t != st.serverTimeSec || st.serverTimeRecvAtMs <= 0)) {
            st.serverTimeSec = t;
            st.serverTimeRecvAtMs = now;
            st.clockAtMs = now;
            mark(st, dirty, AssetDomain.CLOCK);
        }
        return true;
    }

    private static boolean baseInfo(Map<String, Object> c, AssetState st,
                                    Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "nickName", "nickname")) {
            String v = str(m, "nickName", "nickname");
            if (!v.equals(st.nickName)) {
                st.nickName = v;
                changed = true;
            }
        }
        if (has(m, "lv", "level")) {
            long v = num(m, "lv", "level");
            if (v != st.level) {
                st.level = v;
                changed = true;
            }
        }
        if (has(m, "uid")) {
            long v = num(m, "uid");
            if (v != st.uid) {
                st.uid = v;
                changed = true;
            }
        }
        if (has(m, "kid")) {
            long v = num(m, "kid");
            if (v != st.kid) {
                st.kid = v;
                changed = true;
            }
        }
        if (has(m, "fid")) {
            long v = num(m, "fid");
            if (v != st.fid) {
                st.fid = v;
                changed = true;
            }
        }
        if (has(m, "power")) {
            long v = num(m, "power");
            if (v != st.power) {
                st.power = v;
                changed = true;
            }
        }
        if (has(m, "register_time")) {
            long v = num(m, "register_time");
            if (v != st.registerTime) {
                st.registerTime = v;
                changed = true;
            }
        }
        if (has(m, "stove_lv")) {
            long v = num(m, "stove_lv");
            if (v != st.stoveLv) {
                st.stoveLv = v;
                changed = true;
            }
        }
        if (changed) {
            st.metaAtMs = now;
            mark(st, dirty, AssetDomain.META);
        }
        return true;
    }

    private static boolean powerUpdate(Map<String, Object> c, AssetState st,
                                       Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "power")) {
            long v = num(m, "power");
            if (v != st.power) {
                st.power = v;
                changed = true;
            }
        }
        if (changed) {
            st.metaAtMs = now;
            mark(st, dirty, AssetDomain.META);
        }
        return true;
    }

    private static boolean levelUp(Map<String, Object> c, AssetState st, Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "lv", "level")) {
            long v = num(m, "lv", "level");
            if (v != st.level) {
                st.level = v;
                changed = true;
            }
        }
        if (changed) {
            st.metaAtMs = now;
            mark(st, dirty, AssetDomain.META);
        }
        return true;
    }

    private static boolean alliance(Map<String, Object> c, AssetState st, Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        Map<String, Object> a = AssetParseSupport.child(m, "alliance");
        Map<String, Object> p = AssetParseSupport.child(m, "player");
        boolean metaChanged = false;
        boolean bearChanged = false;
        if (!a.isEmpty()) {
            if (has(a, "aid")) {
                long v = num(a, "aid");
                if (v > 0 && v != st.allianceId) {
                    st.allianceId = v;
                    metaChanged = true;
                }
            }
            if (has(a, "name")) {
                String v = str(a, "name");
                if (!v.equals(st.allianceName)) {
                    st.allianceName = v;
                    metaChanged = true;
                }
            }
            if (has(a, "abbr")) {
                String v = str(a, "abbr");
                if (!v.equals(st.allianceAbbr)) {
                    st.allianceAbbr = v;
                    metaChanged = true;
                }
            }
        }
        if (!p.isEmpty()) {
            if (has(p, "aid")) {
                long v = num(p, "aid");
                if (v > 0 && v != st.allianceId) {
                    st.allianceId = v;
                    metaChanged = true;
                }
            }
            if (has(p, "trap_time")) {
                long v = num(p, "trap_time");
                if (v != st.bearTrapTime) {
                    st.bearTrapTime = v;
                    bearChanged = true;
                }
            }
            Map<String, Object> extra = AssetParseSupport.child(p, "trap_extra");
            if (has(extra, "aid")) {
                long v = num(extra, "aid");
                if (v != st.bearTrapExtraAid) {
                    st.bearTrapExtraAid = v;
                    bearChanged = true;
                }
            }
        }
        if (metaChanged) {
            st.metaAtMs = now;
            mark(st, dirty, AssetDomain.META);
        }
        if (bearChanged) {
            st.bearAtMs = now;
            mark(st, dirty, AssetDomain.BEAR);
        }
        return true;
    }

    private static boolean calendar(Map<String, Object> c, AssetState st,
                                    Set<AssetDomain> dirty) {
        Object raw = payload(c);
        Map<Long, List<AssetSnapshot.Schedule>> next = AssetParseSupport.schedulesOf(raw);
        if (AssetParseSupport.sameSchedules(st.schedules, next)) return true;
        st.schedules.clear();
        st.schedules.putAll(next);
        st.calendarAtMs = st.now();
        mark(st, dirty, AssetDomain.CALENDAR);
        return true;
    }

    // ================= RESOURCE / RECOVER / BACKPACK =================

    private static boolean resourceInit(Map<String, Object> c, AssetState st,
                                        Set<AssetDomain> dirty) {
        Map<Long, Long> next = AssetState.longMap(payload(c));
        if (AssetState.changedLong(st.resources, next)) {
            AssetState.copyInto(st.resources, next);
            st.resourceAtMs = st.now();
            mark(st, dirty, AssetDomain.RESOURCE);
        }
        return true;
    }

    private static boolean resourceUpdate(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        String type = str(c, "type", "op");
        boolean del = "del".equalsIgnoreCase(type) || "delete".equalsIgnoreCase(type)
                || "sub".equalsIgnoreCase(type) || "reduce".equalsIgnoreCase(type);
        Map<Long, Long> data = AssetState.longMap(payload(c));
        boolean changed = false;
        for (Map.Entry<Long, Long> e : data.entrySet()) {
            long id = e.getKey().longValue();
            long count = e.getValue() == null ? 0L : e.getValue().longValue();
            Long oldObj = st.resources.get(id);
            long base = oldObj == null ? 0L : oldObj.longValue();
            long next = del ? Math.max(0L, base - count) : base + count;
            if (oldObj == null && next == 0L) continue;
            if (oldObj == null || oldObj.longValue() != next) {
                st.resources.put(id, next);
                changed = true;
            }
        }
        if (changed) {
            st.resourceAtMs = st.now();
            mark(st, dirty, AssetDomain.RESOURCE);
        }
        return true;
    }

    private static boolean recoverInit(Map<String, Object> c, AssetState st,
                                       Set<AssetDomain> dirty) {
        Map<Long, AssetSnapshot.RecoverItem> next = AssetParseSupport.recoverMap(payload(c));
        if (sameRecoverMaps(st.recover, next)) return true;
        boolean staminaChanged = !AssetParseSupport.sameRecover(st.recover.get(201L),
                next.get(201L));
        st.recover.clear();
        st.recover.putAll(next);
        st.recoverAtMs = st.now();
        mark(st, dirty, AssetDomain.RECOVER);
        if (staminaChanged) mark(st, dirty, AssetDomain.STAMINA);
        return true;
    }

    private static boolean recoverUpdate(Map<String, Object> c, AssetState st,
                                         Set<AssetDomain> dirty) {
        boolean changed = false;
        boolean staminaChanged = false;
        for (Map<String, Object> e : AssetState.mapsOf(payload(c))) {
            AssetSnapshot.RecoverItem next = AssetParseSupport.recoverOf(e);
            if (next.id <= 0) continue;
            AssetSnapshot.RecoverItem old = st.recover.get(next.id);
            AssetSnapshot.RecoverItem merged = AssetParseSupport.mergeRecover(old, e);
            if (AssetParseSupport.sameRecover(old, merged)) continue;
            st.recover.put(merged.id, merged);
            changed = true;
            if (merged.id == 201L) staminaChanged = true;
        }
        if (changed) {
            st.recoverAtMs = st.now();
            mark(st, dirty, AssetDomain.RECOVER);
            if (staminaChanged) mark(st, dirty, AssetDomain.STAMINA);
        }
        return true;
    }

    private static boolean backpackInit(Map<String, Object> c, AssetState st,
                                        Set<AssetDomain> dirty) {
        Map<Long, Long> next = AssetState.longMap(payload(c));
        if (AssetState.changedLong(st.backpack, next)) {
            AssetState.copyInto(st.backpack, next);
            st.backpackAtMs = st.now();
            mark(st, dirty, AssetDomain.BACKPACK);
        }
        return true;
    }

    private static boolean backpackUpdate(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        String type = str(c, "type", "op");
        boolean del = "del".equalsIgnoreCase(type) || "delete".equalsIgnoreCase(type)
                || "sub".equalsIgnoreCase(type) || "reduce".equalsIgnoreCase(type);
        Map<Long, Long> data = AssetState.longMap(payload(c));
        boolean changed = false;
        for (Map.Entry<Long, Long> e : data.entrySet()) {
            long id = e.getKey().longValue();
            long count = e.getValue() == null ? 0L : e.getValue().longValue();
            Long oldObj = st.backpack.get(id);
            long base = oldObj == null ? 0L : oldObj.longValue();
            long next = del ? Math.max(0L, base - count) : base + count;
            if (oldObj == null && next == 0L) continue;
            if (oldObj == null || oldObj.longValue() != next) {
                st.backpack.put(id, next);
                changed = true;
            }
        }
        if (changed) {
            st.backpackAtMs = st.now();
            mark(st, dirty, AssetDomain.BACKPACK);
        }
        return true;
    }

    // ================= QUEUE =================

    private static boolean worldPlayerInit(Map<String, Object> c, AssetState st,
                                           Set<AssetDomain> dirty) {
        long now = st.now();
        boolean changed = false;
        Map<Long, AssetSnapshot.QueueMarch> marches =
                AssetParseSupport.marchMap(c.get("march_info"));
        if (!AssetParseSupport.sameMarches(st.marches, marches)) {
            AssetParseSupport.replaceMarches(st.marches, marches);
            changed = true;
        }
        Map<Long, AssetSnapshot.QueueMarch> masters =
                AssetParseSupport.marchMap(c.get("mass_master_marchs"));
        if (!AssetParseSupport.sameMarches(st.massMasters, masters)) {
            AssetParseSupport.replaceMarches(st.massMasters, masters);
            changed = true;
        }
        if (c.containsKey("world_extra_march")) {
            long v = num(AssetParseSupport.child(c, "world_extra_march"), "value");
            if (v < 0L) v = 0L;
            if (v != st.extraMarch) {
                st.extraMarch = v;
                changed = true;
            }
        }
        // city_point{x,y}：城市坐标（巨兽/巨熊/守护任务做距离/驻防判断）。
        // 该字段随 notify_world_player_init 全量帧下发，但语义上是玩家元数据而非队列口径；
        // 为避免坐标变化触发无关的 QUEUE 重建/队列时间戳抖动，这里选择归入 dirty 的 META 域
        // （二选一取 META），与 base_info 等元数据变更共用 metaAtMs。
        boolean cityChanged = false;
        if (c.containsKey("city_point")) {
            Map<String, Object> city = AssetParseSupport.child(c, "city_point");
            if (!city.isEmpty()) {
                long x = num(city, "x");
                long y = num(city, "y");
                if (x > 0L && x != st.cityX) {
                    st.cityX = x;
                    cityChanged = true;
                }
                if (y > 0L && y != st.cityY) {
                    st.cityY = y;
                    cityChanged = true;
                }
            }
        }
        if (changed) {
            st.queueAtMs = now;
            st.queueSource = "init";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        if (cityChanged) {
            st.metaAtMs = now;
            mark(st, dirty, AssetDomain.META);
        }
        return true;
    }

    private static boolean playerMarchAdd(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        Map<String, Object> march = AssetParseSupport.selfOrChild(c, "march");
        AssetSnapshot.QueueMarch qm = AssetParseSupport.marchOf(march);
        boolean changed = upsertMarch(st.marches, qm);
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean playerMarchUpdate(Map<String, Object> c, AssetState st,
                                             Set<AssetDomain> dirty) {
        Map<Long, AssetSnapshot.QueueMarch> dst = st.marches;
        long id = num(c, "id");
        if (!st.marches.containsKey(id) && st.massMasters.containsKey(id)) {
            dst = st.massMasters;
        }
        AssetSnapshot.QueueMarch old = dst.get(id);
        Map<String, Object> patch = new LinkedHashMap<String, Object>(
                AssetParseSupport.child(c, "change"));
        if (!patch.containsKey("type") && c.containsKey("type")) patch.put("type", c.get("type"));
        if (!patch.containsKey("id")) patch.put("id", id);
        AssetSnapshot.QueueMarch merged = AssetParseSupport.mergeMarch(old, patch);
        merged = AssetParseSupport.applyDelKeys(merged, AssetParseSupport.child(c, "del"));
        if (merged == null || merged.id <= 0) return true;
        boolean changed = !AssetParseSupport.sameMarch(old, merged);
        if (changed) {
            dst.put(merged.id, merged);
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean playerMarchDel(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        boolean changed = removeMarches(st.marches, c.get("id"));
        if (st.marchLimitReached) {
            st.marchLimitReached = false;
            changed = true;
        }
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean massMarchAdd(Map<String, Object> c, AssetState st,
                                        Set<AssetDomain> dirty) {
        Map<String, Object> march = AssetParseSupport.selfOrChild(c, "march");
        boolean changed = upsertMarch(st.massMasters, AssetParseSupport.marchOf(march));
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean massMarchUpdate(Map<String, Object> c, AssetState st,
                                           Set<AssetDomain> dirty) {
        long id = num(c, "id");
        AssetSnapshot.QueueMarch old = st.massMasters.get(id);
        Map<String, Object> patch = new LinkedHashMap<String, Object>(
                AssetParseSupport.child(c, "change"));
        if (!patch.containsKey("type") && c.containsKey("type")) patch.put("type", c.get("type"));
        if (!patch.containsKey("id")) patch.put("id", id);
        AssetSnapshot.QueueMarch merged = AssetParseSupport.mergeMarch(old, patch);
        merged = AssetParseSupport.applyDelKeys(merged, AssetParseSupport.child(c, "del"));
        if (merged == null || merged.id <= 0) return true;
        boolean changed = !AssetParseSupport.sameMarch(old, merged);
        if (changed) {
            st.massMasters.put(merged.id, merged);
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean massMarchDel(Map<String, Object> c, AssetState st,
                                        Set<AssetDomain> dirty) {
        boolean changed = removeMarches(st.massMasters, c.get("id"));
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean researchInit(Map<String, Object> c, AssetState st,
                                        Set<AssetDomain> dirty) {
        Object raw = c.get("researched");
        if (raw == null) {
            // 兼容已去包装的内容（直接是 "tid.0" → {tid,lev} 的 map）；带 researching 时不做猜测。
            raw = c.containsKey("researching") || c.containsKey("queue") ? null : c;
        }
        Map<Long, Long> next = new LinkedHashMap<Long, Long>();
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            long tid = num(e, "tid", "id");
            if (tid > 0) next.put(tid, num(e, "lev", "level", "count"));
        }
        if (AssetState.changedLong(st.research, next)) {
            AssetState.copyInto(st.research, next);
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean researchComplete(Map<String, Object> c, AssetState st,
                                            Set<AssetDomain> dirty) {
        boolean changed = false;
        Map<String, Object> m = body(c);
        if (has(m, "tid", "tid_lev")) {
            long tid;
            long lev;
            long tidLev = num(m, "tid_lev", "tidLev");
            if (m.containsKey("tid") && m.containsKey("lev")) {
                tid = num(m, "tid");
                lev = num(m, "lev");
            } else if (tidLev > 10L) {
                tid = tidLev / 10L;
                lev = tidLev % 10L;
            } else {
                tid = tidLev;
                lev = 0L;
            }
            if (tid > 0) {
                Long old = st.research.get(tid);
                if (old == null || old.longValue() != lev) {
                    st.research.put(tid, lev);
                    changed = true;
                }
            }
        }
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean vip(Map<String, Object> c, AssetState st, Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        boolean changed = false;
        if (has(m, "vip_lv", "vip_level")) {
            long v = num(m, "vip_lv", "vip_level");
            if (v != st.vipLevel) {
                st.vipLevel = v;
                changed = true;
            }
        }
        if (has(m, "vip_active_endtime", "vip_endtime")) {
            long v = num(m, "vip_active_endtime", "vip_endtime");
            if (v != st.vipActiveEndtime) {
                st.vipActiveEndtime = v;
                changed = true;
            }
        }
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    /** 单个 buff_show 元素：解析出 buff_map 中 k=10505（临时队列数）；无 10505 视为无效清理。 */
    private static void readQueueBuff(Map<String, Object> e, long[] out) {
        out[0] = 0L;
        out[1] = 0L;
        out[2] = 0L; // [showId, queueCount, expireTs]
        long showId = num(e, "show_id", "id");
        long expire = num(e, "expire_ts", "expire");
        long queues = 0L;
        boolean found = false;
        for (Map<String, Object> b : AssetState.mapsOf(e.get("buff_map"))) {
            long k = num(b, "k", "id", "key");
            long v = num(b, "v", "value", "count");
            if (k == 10505L) {
                queues += v;
                found = true;
            }
        }
        out[0] = showId;
        out[1] = found ? queues : 0L;
        out[2] = expire;
        out[3] = found ? 1L : 0L;
    }

    private static boolean buffShow(Map<String, Object> c, AssetState st,
                                    Set<AssetDomain> dirty) {
        Object raw = c.get("map");
        if (raw == null) raw = payload(c);
        Map<Long, Long> queues = new LinkedHashMap<Long, Long>();
        Map<Long, Long> expires = new LinkedHashMap<Long, Long>();
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            long[] parsed = new long[4];
            readQueueBuff(e, parsed);
            if (parsed[0] > 0 && parsed[3] == 1L) {
                queues.put(parsed[0], parsed[1]);
                expires.put(parsed[0], parsed[2]);
            }
        }
        boolean changed = AssetState.changedLong(st.buffQueues, queues)
                || AssetState.changedLong(st.buffExpire, expires);
        if (!changed) return true;
        AssetState.copyInto(st.buffQueues, queues);
        AssetState.copyInto(st.buffExpire, expires);
        st.queueAtMs = st.now();
        st.queueSource = "push";
        mark(st, dirty, AssetDomain.QUEUE);
        return true;
    }

    private static boolean buffShowUpdate(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        long[] parsed = new long[4];
        readQueueBuff(body(c), parsed);
        long showId = parsed[0];
        if (showId <= 0) return true;
        boolean changed;
        if (parsed[3] == 1L) {
            Long oldQ = st.buffQueues.get(showId);
            Long oldE = st.buffExpire.get(showId);
            changed = oldQ == null || oldQ.longValue() != parsed[1]
                    || oldE == null || oldE.longValue() != parsed[2];
            if (changed) {
                st.buffQueues.put(showId, parsed[1]);
                st.buffExpire.put(showId, parsed[2]);
            }
        } else {
            changed = st.buffQueues.remove(showId) != null | st.buffExpire.remove(showId) != null;
        }
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean buffShowDelete(Map<String, Object> c, AssetState st,
                                           Set<AssetDomain> dirty) {
        long showId = num(body(c), "show_id", "id");
        if (showId <= 0) return true;
        boolean existed = st.buffQueues.remove(showId) != null;
        existed = st.buffExpire.remove(showId) != null || existed;
        if (existed) {
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean extraMarch(Map<String, Object> c, AssetState st,
                                      Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        Map<String, Object> wem = AssetParseSupport.child(m, "world_extra_march");
        long v = wem.isEmpty() ? num(m, "value") : num(wem, "value");
        if (v < 0L) v = 0L;
        if (v != st.extraMarch) {
            st.extraMarch = v;
            st.queueAtMs = st.now();
            st.queueSource = "push";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    private static boolean marchResponse(Map<String, Object> c, AssetState st,
                                         Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        Object raw = m.get("march_info");
        if (raw == null) raw = c.get("march_info");
        boolean changed = false;
        if (raw instanceof Map) {
            Map<String, Object> single = AssetState.mapOf(raw);
            if (!single.isEmpty()) changed = upsertMarch(st.marches,
                    AssetParseSupport.marchOf(single));
        } else if (raw instanceof List) {
            for (Map.Entry<Long, AssetSnapshot.QueueMarch> e
                    : AssetParseSupport.marchMap(raw).entrySet()) {
                if (upsertMarch(st.marches, e.getValue())) changed = true;
            }
        } else if (num(m, "code") == 2878L) {
            // 队列已满：置位直到 notify_player_march_del（网页 BehemothAccountState:915-924）
            if (!st.marchLimitReached) {
                st.marchLimitReached = true;
                changed = true;
            }
        }
        if (changed) {
            st.queueAtMs = st.now();
            st.queueSource = "response";
            mark(st, dirty, AssetDomain.QUEUE);
        }
        return true;
    }

    // ================= INTEL =================

    private static boolean intelInfo(Map<String, Object> c, AssetState st,
                                     Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        long radarLevel = num(m, "radar_level");
        long radarExp = num(m, "radar_exp");
        long powerLevel = num(m, "power_level", "power_lv");
        long nextEventTime = num(m, "next_event_time");
        if (has(m, "radar_level") && radarLevel != st.radarLevel) {
            st.radarLevel = radarLevel;
            changed = true;
        }
        if (has(m, "radar_exp") && radarExp != st.radarExp) {
            st.radarExp = radarExp;
            changed = true;
        }
        if (has(m, "power_level", "power_lv") && powerLevel != st.powerLevel) {
            st.powerLevel = powerLevel;
            changed = true;
        }
        if (has(m, "next_event_time") && nextEventTime != st.nextEventTime) {
            st.nextEventTime = nextEventTime;
            changed = true;
        }
        Map<Long, AssetSnapshot.IntelEvent> next =
                AssetParseSupport.eventMap(m.get("events"));
        if (!AssetParseSupport.sameEvents(st.intelEvents, next)) {
            st.intelEvents.clear();
            st.intelEvents.putAll(next);
            changed = true;
        }
        if (changed) {
            st.intelAtMs = now;
            mark(st, dirty, AssetDomain.INTEL);
        }
        return true;
    }

    private static boolean intelEvents(Map<String, Object> c, AssetState st,
                                       Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        for (Map.Entry<Long, AssetSnapshot.IntelEvent> e
                : AssetParseSupport.eventMap(m.get("events")).entrySet()) {
            if (!AssetParseSupport.sameEvent(st.intelEvents.get(e.getKey()), e.getValue())) {
                st.intelEvents.put(e.getKey(), e.getValue());
                changed = true;
            }
        }
        if (has(m, "next_event_time")) {
            long v = num(m, "next_event_time");
            if (v != st.nextEventTime) {
                st.nextEventTime = v;
                changed = true;
            }
        }
        if (changed) {
            st.intelAtMs = now;
            mark(st, dirty, AssetDomain.INTEL);
        }
        return true;
    }

    private static boolean intelEventsDel(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        boolean changed = false;
        for (Long id : AssetParseSupport.idsOf(c.get("ids"))) {
            if (st.intelEvents.remove(id) != null) changed = true;
        }
        if (changed) {
            st.intelAtMs = st.now();
            mark(st, dirty, AssetDomain.INTEL);
        }
        return true;
    }

    private static boolean intelBattleResult(Map<String, Object> c, AssetState st,
                                             Set<AssetDomain> dirty) {
        // 资产库不保存战斗结算字段（IntelEvent 无 success/battleresult），维持事件事实不变；
        // 判定与战报归 WarState/业务层（只做事实表，不做判定）。
        return true;
    }

    private static boolean intelPowerInfo(Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "power_lv", "power_level")) {
            long v = num(m, "power_lv", "power_level");
            if (v != st.powerLevel) {
                st.powerLevel = v;
                changed = true;
            }
        }
        if (has(m, "radar_level")) {
            long v = num(m, "radar_level");
            if (v != st.radarLevel) {
                st.radarLevel = v;
                changed = true;
            }
        }
        if (has(m, "radar_exp")) {
            long v = num(m, "radar_exp");
            if (v != st.radarExp) {
                st.radarExp = v;
                changed = true;
            }
        }
        if (changed) {
            st.intelAtMs = now;
            mark(st, dirty, AssetDomain.INTEL);
        }
        return true;
    }

    // ================= BEHEMOTH / AUTOJOIN / BEAR =================

    private static boolean npcInit(Map<String, Object> c, AssetState st,
                                   Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "monster_max_lv") && num(m, "monster_max_lv") != st.monsterMaxLv) {
            st.monsterMaxLv = num(m, "monster_max_lv");
            changed = true;
        }
        if (has(m, "last_daily_time") && num(m, "last_daily_time") != st.lastDailyTime) {
            st.lastDailyTime = num(m, "last_daily_time");
            changed = true;
        }
        if (has(m, "expert_reward_daily_count")
                && num(m, "expert_reward_daily_count") != st.expertRewardDailyCount) {
            st.expertRewardDailyCount = num(m, "expert_reward_daily_count");
            changed = true;
        }
        if (has(m, "join_daily_count")) {
            Map<Long, Long> next = AssetParseSupport.countMap(m.get("join_daily_count"));
            if (AssetState.changedLong(st.joinDailyCounts, next)) {
                AssetState.copyInto(st.joinDailyCounts, next);
                changed = true;
            }
        }
        if (has(m, "master_daily_count")) {
            Map<Long, Long> next = AssetParseSupport.countMap(m.get("master_daily_count"));
            if (AssetState.changedLong(st.masterDailyCounts, next)) {
                AssetState.copyInto(st.masterDailyCounts, next);
                changed = true;
            }
        }
        if (changed) {
            st.behemothAtMs = now;
            mark(st, dirty, AssetDomain.BEHEMOTH);
        }
        return true;
    }

    private static boolean npcUpdate(Map<String, Object> c, AssetState st,
                                     Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "monster_max_lv") && num(m, "monster_max_lv") != st.monsterMaxLv) {
            st.monsterMaxLv = num(m, "monster_max_lv");
            changed = true;
        }
        if (has(m, "last_daily_time") && num(m, "last_daily_time") != st.lastDailyTime) {
            st.lastDailyTime = num(m, "last_daily_time");
            changed = true;
        }
        if (has(m, "expert_reward_daily_count")
                && num(m, "expert_reward_daily_count") != st.expertRewardDailyCount) {
            st.expertRewardDailyCount = num(m, "expert_reward_daily_count");
            changed = true;
        }
        if (has(m, "join_daily_count")) {
            Map<Long, Long> next = AssetParseSupport.countMap(m.get("join_daily_count"));
            if (AssetState.changedLong(st.joinDailyCounts, next)) {
                AssetState.copyInto(st.joinDailyCounts, next);
                changed = true;
            }
        }
        if (has(m, "master_daily_count")) {
            Map<Long, Long> next = AssetParseSupport.countMap(m.get("master_daily_count"));
            if (AssetState.changedLong(st.masterDailyCounts, next)) {
                AssetState.copyInto(st.masterDailyCounts, next);
                changed = true;
            }
        }
        if (changed) {
            st.behemothAtMs = now;
            mark(st, dirty, AssetDomain.BEHEMOTH);
        }
        return true;
    }

    private static boolean autoJoinResponse(Map<String, Object> c, AssetState st,
                                            Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        long now = st.now();
        boolean changed = false;
        if (has(m, "end_time")) {
            long v = num(m, "end_time");
            if (v != st.autoJoinEndTime) {
                st.autoJoinEndTime = v;
                changed = true;
            }
        }
        if (has(m, "limit")) {
            long v = num(m, "limit");
            if (v != st.autoJoinLimit) {
                st.autoJoinLimit = v;
                changed = true;
            }
        }
        if (has(m, "just_soldier")) {
            boolean v = AssetState.boolOf(m.get("just_soldier"));
            if (v != st.autoJoinJustSoldier) {
                st.autoJoinJustSoldier = v;
                changed = true;
            }
        }
        if (has(m, "offline")) {
            boolean v = AssetState.boolOf(m.get("offline"));
            if (v != st.autoJoinOffline) {
                st.autoJoinOffline = v;
                changed = true;
            }
        }
        if (m.containsKey("auto_mass_list")) {
            Set<Long> next = new LinkedHashSet<Long>();
            for (Map<String, Object> e : AssetState.mapsOf(m.get("auto_mass_list"))) {
                if (AssetState.boolOf(e.get("selected"))) {
                    long id = num(e, "id", "k");
                    if (id > 0) next.add(id);
                }
            }
            if (!next.equals(st.autoJoinSelected)) {
                st.autoJoinSelected.clear();
                st.autoJoinSelected.addAll(next);
                changed = true;
            }
        } else if (m.containsKey("auto_mass_id_list")) {
            Set<Long> next = new LinkedHashSet<Long>();
            for (Long id : AssetParseSupport.idsOf(m.get("auto_mass_id_list"))) next.add(id);
            if (!next.equals(st.autoJoinSelected)) {
                st.autoJoinSelected.clear();
                st.autoJoinSelected.addAll(next);
                changed = true;
            }
        }
        if (changed) {
            st.autoJoinAtMs = now;
            mark(st, dirty, AssetDomain.BEHEMOTH);
        }
        return true;
    }

    private static boolean bearTrap(Map<String, Object> c, AssetState st,
                                    Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        boolean changed = applyBearFields(m, st);
        if (changed) {
            st.bearAtMs = st.now();
            mark(st, dirty, AssetDomain.BEAR);
        }
        return true;
    }

    private static boolean bearTrapInfoResponse(Map<String, Object> c, AssetState st,
                                                Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        boolean changed = applyBearFields(m, st);
        // req_alliance_trap_info 响应是 reserve_time 的权威来源（推送可能缺该字段）
        if (!st.bearReserveKnown) {
            st.bearReserveKnown = true;
            changed = true;
        }
        if (changed) {
            st.bearAtMs = st.now();
            mark(st, dirty, AssetDomain.BEAR);
        }
        return true;
    }

    private static boolean applyBearFields(Map<String, Object> m, AssetState st) {
        boolean changed = false;
        if (has(m, "mass_start_time") && num(m, "mass_start_time") != st.bearMassStart) {
            st.bearMassStart = num(m, "mass_start_time");
            changed = true;
        }
        if (has(m, "mass_end_time") && num(m, "mass_end_time") != st.bearMassEnd) {
            st.bearMassEnd = num(m, "mass_end_time");
            changed = true;
        }
        if (has(m, "cool_end_time") && num(m, "cool_end_time") != st.bearCoolEnd) {
            st.bearCoolEnd = num(m, "cool_end_time");
            changed = true;
        }
        if (has(m, "donate") && num(m, "donate") != st.bearDonate) {
            st.bearDonate = num(m, "donate");
            changed = true;
        }
        if (has(m, "total_score") && num(m, "total_score") != st.bearTotalScore) {
            st.bearTotalScore = num(m, "total_score");
            changed = true;
        }
        if (has(m, "reserve_time") && num(m, "reserve_time") != st.bearReserveTime) {
            st.bearReserveTime = num(m, "reserve_time");
            changed = true;
        }
        if (has(m, "auto_reserve")) {
            boolean v = AssetState.boolOf(m.get("auto_reserve"));
            if (v != st.bearAutoReserve) {
                st.bearAutoReserve = v;
                changed = true;
            }
        }
        return changed;
    }

    // ================= WAR =================

    private static boolean allianceSituation(String proto, Map<String, Object> c, AssetState st,
                                             Set<AssetDomain> dirty) {
        String tail = proto.substring("notify_world_alliance_situation".length());
        long now = st.now();
        boolean changed = false;
        if (tail.startsWith("_activity")) {
            if ("_activity".equals(tail)) {
                changed = replaceAllianceMarches(c, st);
            } else if (tail.endsWith("_update")) {
                changed = updateAllianceMarches(c, st);
            } else if (tail.endsWith("_add")) {
                changed = upsertAllianceMarches(c, st);
            } else if (tail.endsWith("_del")) {
                changed = deleteAllianceMarches(c, st);
            } else {
                return true; // activity_count 等：无事实可写
            }
        } else if ("_init".equals(tail)) {
            changed = replaceAllianceMarches(c, st);
        } else if ("_update".equals(tail)) {
            changed = updateAllianceMarches(c, st);
        } else if ("_add".equals(tail)) {
            changed = upsertAllianceMarches(c, st);
        } else if ("_del".equals(tail)) {
            changed = deleteAllianceMarches(c, st);
        } else if ("_slave_moving_update".equals(tail)) {
            changed = slaveMovingUpdate(c, st);
        } else {
            return true; // _point_update/_activity_count 等：不改变行军事实表
        }
        if (changed) {
            st.warAtMs = now;
            mark(st, dirty, AssetDomain.WAR);
        }
        return true;
    }

    private static boolean replaceAllianceMarches(Map<String, Object> c, AssetState st) {
        Map<Long, AssetSnapshot.QueueMarch> next =
                new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
        next.putAll(AssetParseSupport.marchMap(c.get("marchs")));
        next.putAll(AssetParseSupport.marchMap(c.get("to_marchs")));
        boolean changed = !AssetParseSupport.sameMarches(st.allianceMarches, next);
        if (changed) AssetParseSupport.replaceMarches(st.allianceMarches, next);
        AssetParseSupport.collectPowers(c.get("marchs"), st.warPowers);
        AssetParseSupport.collectPowers(c.get("to_marchs"), st.warPowers);
        return changed;
    }

    private static boolean upsertAllianceMarches(Map<String, Object> c, AssetState st) {
        boolean changed = false;
        for (Map.Entry<Long, AssetSnapshot.QueueMarch> e
                : AssetParseSupport.marchMap(c.get("marchs")).entrySet()) {
            if (upsertMarch(st.allianceMarches, e.getValue())) changed = true;
        }
        for (Map.Entry<Long, AssetSnapshot.QueueMarch> e
                : AssetParseSupport.marchMap(c.get("to_marchs")).entrySet()) {
            if (upsertMarch(st.allianceMarches, e.getValue())) changed = true;
        }
        AssetParseSupport.collectPowers(c.get("marchs"), st.warPowers);
        AssetParseSupport.collectPowers(c.get("to_marchs"), st.warPowers);
        return changed;
    }

    private static boolean updateAllianceMarches(Map<String, Object> c, AssetState st) {
        if (c.containsKey("marchs") || c.containsKey("to_marchs")) {
            boolean changed = mergeMarches(st.allianceMarches, c.get("marchs"), false);
            changed = mergeMarches(st.allianceMarches, c.get("to_marchs"), false) || changed;
            AssetParseSupport.collectPowers(c.get("marchs"), st.warPowers);
            AssetParseSupport.collectPowers(c.get("to_marchs"), st.warPowers);
            return changed;
        }
        // 兼容 {id, change, del} 单条形态（world_march 的部分帧口径）
        long id = num(c, "id", "march_id");
        AssetSnapshot.QueueMarch old = st.allianceMarches.get(id);
        AssetSnapshot.QueueMarch merged = AssetParseSupport.mergeMarch(old,
                AssetParseSupport.child(c, "change"));
        merged = AssetParseSupport.applyDelKeys(merged, AssetParseSupport.child(c, "del"));
        if (merged == null || id <= 0 || AssetParseSupport.sameMarch(old, merged)) return false;
        st.allianceMarches.put(id, merged);
        return true;
    }

    private static boolean deleteAllianceMarches(Map<String, Object> c, AssetState st) {
        boolean changed = removeMarches(st.allianceMarches, c.get("marchs"));
        changed = removeMarches(st.allianceMarches, c.get("to_marchs")) || changed;
        return changed;
    }

    private static boolean slaveMovingUpdate(Map<String, Object> c, AssetState st) {
        long id = num(c, "id");
        if (id <= 0) return false;
        AssetSnapshot.QueueMarch old = st.allianceMarches.get(id);
        if (old == null) return false;
        Map<String, Object> change = AssetParseSupport.child(c, "change");
        AssetSnapshot.QueueMarch merged = AssetParseSupport.mergeMarch(old, change);
        if (AssetParseSupport.sameMarch(old, merged)) return false;
        st.allianceMarches.put(id, merged);
        return true;
    }

    /** player_situation 的 power 归属：优先内层 march.owner，其次用 slot 回查已合并行军。 */
    private static void collectSituationPowers(Object raw,
                                               Map<Long, AssetSnapshot.QueueMarch> marches,
                                               Map<Long, Long> powers) {
        for (Map<String, Object> e : AssetState.mapsOf(raw)) {
            long power = num(e, "power");
            if (power <= 0) continue;
            long owner = num(AssetParseSupport.child(e, "march"), "owner");
            if (owner <= 0) {
                long slot = num(e, "id");
                AssetSnapshot.QueueMarch m = slot > 0 ? marches.get(slot) : null;
                if (m != null) owner = m.owner;
            }
            if (owner <= 0) owner = num(e, "owner");
            if (owner > 0) powers.put(owner, power);
        }
    }

    private static boolean worldSituation(String proto, Map<String, Object> c, AssetState st,
                                          Set<AssetDomain> dirty) {
        long now = st.now();
        boolean changed = false;
        if ("notify_world_situation_info".equals(proto)) {
            Map<Long, AssetSnapshot.QueueMarch> next =
                    AssetParseSupport.situationMap(c.get("situation_info"));
            changed = !AssetParseSupport.sameMarches(st.worldMarches, next);
            if (changed) AssetParseSupport.replaceMarches(st.worldMarches, next);
            collectSituationPowers(c.get("situation_info"), st.worldMarches, st.warPowers);
        } else if ("notify_world_situation_info_up".equals(proto)) {
            changed = mergeMarches(st.worldMarches, c.get("marches"), true);
            collectSituationPowers(c.get("marches"), st.worldMarches, st.warPowers);
        } else if ("notify_world_situation_info_add".equals(proto)) {
            // key = player_situation.id（slot），不能用 march.id（del 的 id_arr 是 slot）
            for (Map.Entry<Long, AssetSnapshot.QueueMarch> e
                    : AssetParseSupport.situationMap(c.get("marches")).entrySet()) {
                if (AssetParseSupport.sameMarch(st.worldMarches.get(e.getKey()), e.getValue())) {
                    continue;
                }
                st.worldMarches.put(e.getKey(), e.getValue());
                changed = true;
            }
            collectSituationPowers(c.get("marches"), st.worldMarches, st.warPowers);
        } else if ("notify_world_situation_info_del".equals(proto)) {
            changed = removeMarches(st.worldMarches, c.get("id_arr"));
        } else {
            return true;
        }
        if (changed) {
            st.warAtMs = now;
            mark(st, dirty, AssetDomain.WAR);
        }
        return true;
    }

    private static boolean newMail(Map<String, Object> c, AssetState st,
                                   Set<AssetDomain> dirty) {
        Map<String, Object> cover = AssetParseSupport.child(c, "mail_cover_info");
        if (cover.isEmpty()) cover = body(c);
        boolean changed = false;
        if (has(cover, "first_type")) {
            long v = num(cover, "first_type");
            if (v != st.lastMailFirstType) {
                st.lastMailFirstType = v;
                changed = true;
            }
        }
        if (has(cover, "second_type")) {
            long v = num(cover, "second_type");
            if (v != st.lastMailSecondType) {
                st.lastMailSecondType = v;
                changed = true;
            }
        }
        if (changed) {
            st.lastMailAtMs = st.now();
            st.warAtMs = st.lastMailAtMs;
            mark(st, dirty, AssetDomain.WAR);
        }
        return true;
    }

    // ================= GUARDIAN =================

    private static boolean memberPos(String proto, Map<String, Object> c, AssetState st,
                                     Set<AssetDomain> dirty) {
        long now = st.now();
        boolean changed = false;
        if (proto.endsWith("_info")) {
            Object raw = c.get("member_list");
            if (raw == null) raw = c;
            Map<Long, AssetSnapshot.Ally> next = new LinkedHashMap<Long, AssetSnapshot.Ally>();
            for (Map<String, Object> e : AssetState.mapsOf(raw)) {
                AssetSnapshot.Ally ally = AssetParseSupport.allyOf(e);
                if (ally.uid > 0) next.put(ally.uid, ally);
            }
            changed = !sameAllies(st.allies, next);
            if (changed) {
                st.allies.clear();
                st.allies.putAll(next);
            }
        } else if (proto.endsWith("_add") || proto.endsWith("_update")) {
            Map<String, Object> e = AssetParseSupport.selfOrChild(c, "member_pos");
            AssetSnapshot.Ally ally = AssetParseSupport.allyOf(e);
            if (ally.uid > 0 && !AssetParseSupport.sameAlly(st.allies.get(ally.uid), ally)) {
                st.allies.put(ally.uid, ally);
                changed = true;
            }
        } else if (proto.endsWith("_del")) {
            boolean any = false;
            for (Long uid : AssetParseSupport.idsOf(c.get("uid"))) {
                if (st.allies.remove(uid) != null) any = true;
            }
            for (Long uid : AssetParseSupport.idsOf(c.get("member_list"))) {
                if (st.allies.remove(uid) != null) any = true;
            }
            changed = any;
        } else {
            return true;
        }
        if (changed) {
            st.guardianAtMs = now;
            mark(st, dirty, AssetDomain.GUARDIAN);
        }
        return true;
    }

    private static boolean embassyAdd(Map<String, Object> c, AssetState st,
                                      Set<AssetDomain> dirty) {
        Map<String, Object> m = body(c);
        Map<String, Object> detail = AssetParseSupport.child(m, "march_detail");
        if (detail.isEmpty()) detail = m;
        long targetUid = num(m, "target_uid", "uid");
        AssetSnapshot.Garrison garrison = AssetParseSupport.garrisonOf(detail, targetUid);
        if (garrison.marchId <= 0) return true;
        if (AssetParseSupport.sameGarrison(st.garrisons.get(garrison.marchId), garrison)) {
            return true;
        }
        st.garrisons.put(garrison.marchId, garrison);
        st.guardianAtMs = st.now();
        mark(st, dirty, AssetDomain.GUARDIAN);
        return true;
    }

    private static boolean embassyDel(Map<String, Object> c, AssetState st,
                                      Set<AssetDomain> dirty) {
        boolean changed = false;
        for (Long id : AssetParseSupport.idsOf(c.get("id"))) {
            if (st.garrisons.remove(id) != null) changed = true;
        }
        if (changed) {
            st.guardianAtMs = st.now();
            mark(st, dirty, AssetDomain.GUARDIAN);
        }
        return true;
    }

    private AssetParsers() {
    }
}
