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

    /** 投影 StateStore 键。 */
    public static String stateKey(String fpid, long sid) {
        return STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + sid;
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
}
