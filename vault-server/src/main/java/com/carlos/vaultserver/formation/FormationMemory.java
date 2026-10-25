package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vault.wjdr.protocol.GameProtocolException;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.Exec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编队记忆（契约 §3.5 / 服务端审计 P1-4）：把游戏内编队槽（{@code notify_soldier_init.prefab}）
 * 快照下来，之后逐槽 {@code req_soldier_set_prefab} 恢复。
 *
 * <p><b>state 键</b>：{@code srv:formation:memory:{fpid}:{sid}:{uid}:{DAILY|BATTLE}}，值：
 * <pre>{"version":N,"savedAt":ms,"slotCount":n,"kind":"DAILY",
 *  "slots":[{id,name,heroIds,captainHeroId,allocationMode,soldiers,ratio,prefab}]}</pre>
 * （{@code prefab} 额外携带原始 {@code hero_id/soldier|formation_rate/formation_name} 对，
 * 因为恢复要把原始 soldier id 发回游戏，展示用的三兵种汇总不够。）
 *
 * <p><b>语义</b>（旧版 {@code RoleFormationMemoryService} 1:1）：
 * <ul>
 *   <li>保存：从当前游戏编队快照取非空槽；overwrite=false 且已有记忆 → 3002；同槽同时有
 *       soldier 与 formation_rate → 3002「无法记忆」；</li>
 *   <li>恢复：逐槽发 {@code req_soldier_set_prefab}，槽间隔 500ms（可配
 *       {@code wjdr.daily.formation-restore-interval-millis}），响应 code!=0 → failures[]；
 *       成功槽回写本地目录缓存；</li>
 *   <li>DAILY / BATTLE 两套互不影响。</li>
 * </ul>
 */
public final class FormationMemory {

    public static final String KIND_DAILY = "DAILY";
    public static final String KIND_BATTLE = "BATTLE";

    /** state 键前缀（契约 §3.2）。 */
    public static final String STATE_KEY_PREFIX = "srv:formation:memory:";
    /** 巨熊绑定 state 键前缀（契约 §3.2；本类只读，供目录/动作展示）。 */
    public static final String BEAR_STATE_KEY_PREFIX = "srv:formation:bear:";

    /** 恢复协议（c2s schema tag=4051；payload {prefab:{id,prefab:{...}}}）。 */
    public static final String PREFAB_PROTOCOL = "req_soldier_set_prefab";

    /** 槽间隔默认 500ms（旧版 DEFAULT_RESTORE_INTERVAL_MILLIS）。 */
    public static final long DEFAULT_RESTORE_INTERVAL_MILLIS = 500L;
    /** 槽间隔配置项（旧版同 key）。 */
    public static final String INTERVAL_PROPERTY = "wjdr.daily.formation-restore-interval-millis";

    /** 业务错误（HTTP 层转 3002 + 中文文案；与 VaultHttpApi.BizError 对齐）。 */
    public static final class MemoryError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int code;

        public MemoryError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private FormationMemory() {
    }

    // ------------------------------------------------------------------
    // 键 / 状态
    // ------------------------------------------------------------------

    public static String stateKey(String fpid, String sid, String uid, String kind) {
        return STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + (sid == null ? "" : sid)
                + ":" + (uid == null ? "" : uid) + ":" + normalizeKind(kind);
    }

    /** 巨熊绑定 state 键。 */
    public static String bearStateKey(String fpid, String sid, String uid) {
        return BEAR_STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + (sid == null ? "" : sid)
                + ":" + (uid == null ? "" : uid);
    }

    /** kind 归一化（大小写/空白容忍；非法返回 DAILY）。 */
    public static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase();
        return KIND_BATTLE.equals(k) ? KIND_BATTLE : KIND_DAILY;
    }

    /** kind 中文名（错误文案用）。 */
    public static String kindLabel(String kind) {
        return KIND_BATTLE.equals(normalizeKind(kind)) ? "战斗" : "日常";
    }

    /** 状态：{@code {daily:{exists,savedAt,slotCount,version}, battle:{...}}}（契约 §3.2）。 */
    public static JSONObject status(StateStore stateStore, String fpid, String sid, String uid) {
        return new JSONObject(statusMap(stateStore, fpid, sid, uid));
    }

    /** 状态（Map 形态，供目录快照内嵌；避免 JSONObject 嵌套包装差异）。 */
    public static Map<String, Object> statusMap(StateStore stateStore, String fpid, String sid,
                                                String uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("daily", kindStatusMap(stateStore, stateKey(fpid, sid, uid, KIND_DAILY)));
        out.put("battle", kindStatusMap(stateStore, stateKey(fpid, sid, uid, KIND_BATTLE)));
        return out;
    }

    /** 单 kind 状态（无快照=exists:false,全 0）。 */
    public static JSONObject kindStatus(StateStore stateStore, String key) {
        return new JSONObject(kindStatusMap(stateStore, key));
    }

    static Map<String, Object> kindStatusMap(StateStore stateStore, String key) {
        JSONObject snap = readSnapshot(stateStore, key);
        Map<String, Object> o = new LinkedHashMap<>();
        if (snap == null) {
            o.put("exists", false);
            o.put("savedAt", 0L);
            o.put("slotCount", 0);
            o.put("version", 0L);
            return o;
        }
        o.put("exists", snap.optInt("slotCount", 0) > 0);
        o.put("savedAt", snap.optLong("savedAt", 0L));
        o.put("slotCount", snap.optInt("slotCount", 0));
        o.put("version", snap.optLong("version", 0L));
        return o;
    }

    /** 巨熊模板绑定（读 state；缺省=未应用，契约 §3.2）。 */
    public static JSONObject bearBinding(StateStore stateStore, String fpid, String sid, String uid) {
        return new JSONObject(bearBindingMap(stateStore, fpid, sid, uid));
    }

    /** 巨熊模板绑定（Map 形态，供目录快照内嵌）。 */
    public static Map<String, Object> bearBindingMap(StateStore stateStore, String fpid,
                                                     String sid, String uid) {
        if (stateStore != null) {
            String raw = stateStore.getString(bearStateKey(fpid, sid, uid));
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    JSONObject o = new JSONObject(raw.trim());
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (String k : o.keySet()) out.put(k, jsonPlain(o.opt(k)));
                    return out;
                } catch (Throwable ignored) {
                }
            }
        }
        return FormationCatalog.defaultBear();
    }

    /** JSON 值 → 纯 Java 值（Map/List/String/Number/Boolean；null 安全）。 */
    static Object jsonPlain(Object v) {
        if (v == null || JSONObject.NULL.equals(v)) return null;
        if (v instanceof JSONObject) {
            Map<String, Object> m = new LinkedHashMap<>();
            JSONObject o = (JSONObject) v;
            for (String k : o.keySet()) m.put(k, jsonPlain(o.opt(k)));
            return m;
        }
        if (v instanceof JSONArray) {
            List<Object> list = new ArrayList<>();
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) list.add(jsonPlain(arr.opt(i)));
            return list;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /**
     * 记忆当前游戏编队（overwrite 语义）。
     *
     * @return {@code {kind,slotCount,savedAt,version}}
     * @throws MemoryError 3002（无编队可记忆 / 已存在且未确认覆盖 / 同槽 soldiers+rate）
     */
    public static JSONObject save(StateStore stateStore, WjdrEngine engine, String fpid, String sid,
                                  String uid, String kind, boolean overwrite) {
        String k = normalizeKind(kind);
        String key = stateKey(fpid, sid, uid, k);
        JSONObject existing = readSnapshot(stateStore, key);
        if (existing != null && existing.optInt("slotCount", 0) > 0 && !overwrite) {
            throw new MemoryError(3002, "记忆已存在，请确认覆盖");
        }
        List<GameFormations.Slot> slots = FormationCatalog.gameSlots(engine);
        if (slots == null || slots.isEmpty()) {
            throw new MemoryError(3002, "当前没有已同步的游戏编队，无法记忆");
        }
        List<Map<String, Object>> saved = new ArrayList<>();
        for (GameFormations.Slot slot : slots) {
            if (slot == null || slot.isEmpty()) continue;
            if (slot.hasSoldiers && slot.hasRates) {
                throw new MemoryError(3002, "游戏编队 " + slot.id + " 同时包含士兵数量与比例，无法记忆");
            }
            saved.add(slot.toMemorySlot());
        }
        if (saved.isEmpty()) {
            throw new MemoryError(3002, "当前没有已同步的游戏编队，无法记忆");
        }
        long savedAt = System.currentTimeMillis();
        long version = (existing == null ? 0L : existing.optLong("version", 0L)) + 1L;
        JSONObject snap = new JSONObject();
        snap.put("version", version);
        snap.put("savedAt", savedAt);
        snap.put("slotCount", saved.size());
        snap.put("kind", k);
        snap.put("slots", new JSONArray(saved));
        if (stateStore != null) stateStore.putString(key, snap.toString());
        FormationCatalog.setMemoryStatus(engine, statusMap(stateStore, fpid, sid, uid));

        JSONObject data = new JSONObject();
        data.put("kind", k);
        data.put("slotCount", saved.size());
        data.put("savedAt", savedAt);
        data.put("version", version);
        return data;
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    /**
     * 恢复编队记忆：逐槽发 {@code req_soldier_set_prefab}（槽间隔 500ms）。
     *
     * @return {@code {total,restored,failures:[{id,code,message}]}}
     * @throws MemoryError 3002（无记忆 / 无槽 / 引擎不可用）
     */
    public static JSONObject restore(StateStore stateStore, WjdrEngine engine, String fpid, String sid,
                                     String uid, String kind) {
        String k = normalizeKind(kind);
        String key = stateKey(fpid, sid, uid, k);
        JSONObject snap = readSnapshot(stateStore, key);
        if (snap == null || snap.optInt("slotCount", 0) <= 0) {
            throw new MemoryError(3002, "还没有" + kindLabel(k) + "编队记忆，请先记忆当前编队");
        }
        if (engine == null) {
            throw new MemoryError(3002, "账号离线，无法恢复编队，请先上线");
        }
        JSONArray slots = snap.optJSONArray("slots");
        int total = slots == null ? 0 : slots.length();
        if (total <= 0) {
            throw new MemoryError(3002, "还没有" + kindLabel(k) + "编队记忆，请先记忆当前编队");
        }
        long interval = restoreIntervalMillis();
        int restored = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            JSONObject slotJson = slots.optJSONObject(i);
            if (slotJson == null) continue;
            long slotId = slotJson.optLong("id", 0L);
            if (slotId <= 0L) continue;
            if (i > 0) waitBeforeSlot(interval, i);
            Map<String, Object> inner = prefabOf(slotJson);
            Map<String, Object> payload = new LinkedHashMap<>();
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("id", slotId);
            pair.put("prefab", inner);
            payload.put("prefab", pair);
            try {
                GameMessage rsp = engine.request(PREFAB_PROTOCOL, payload);
                long code = responseCode(rsp);
                if (code != 0L) {
                    failures.add(failure(slotId, code,
                            "游戏拒绝保存编队 " + slotId + "（错误码 " + code + "）"));
                    continue;
                }
                restored++;
                FormationCatalog.applyRestoredPrefab(engine, (int) slotId, inner);
            } catch (GameProtocolException e) {
                failures.add(failure(slotId, e.code(),
                        "游戏拒绝保存编队 " + slotId + "：" + e.getMessage()));
            } catch (Throwable t) {
                failures.add(failure(slotId, -1L,
                        "恢复编队 " + slotId + " 失败：" + String.valueOf(t.getMessage())));
            }
        }
        JSONObject data = new JSONObject();
        data.put("total", total);
        data.put("restored", restored);
        data.put("failures", new JSONArray(failures));
        return data;
    }

    /** 槽间隔（毫秒；系统属性可配，默认 500）。 */
    public static long restoreIntervalMillis() {
        try {
            String v = System.getProperty(INTERVAL_PROPERTY);
            if (v != null && !v.trim().isEmpty()) {
                return Math.max(0L, Long.parseLong(v.trim()));
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_RESTORE_INTERVAL_MILLIS;
    }

    private static void waitBeforeSlot(long interval, int index) {
        if (interval <= 0L) return;
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemoryError(3002, "恢复编队被中断，已处理 " + index + " 支");
        }
    }

    private static Map<String, Object> failure(long id, long code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    private static long responseCode(GameMessage rsp) {
        if (rsp == null || rsp.getContent() == null) return -1L;
        Object code = rsp.getContent().get("code");
        return code == null ? 0L : Exec.longOf(code);
    }

    // ------------------------------------------------------------------
    // JSON ↔ payload
    // ------------------------------------------------------------------

    /**
     * 从快照槽取内层 prefab 的 wire payload：hero_id 对 + soldier|formation_rate 二选一 +
     * formation_name（非空才写）。formation_rate 补齐 k=1/2/3（旧版 completeFormationRates 同口径）。
     */
    static Map<String, Object> prefabOf(JSONObject slot) {
        JSONObject prefab = slot.optJSONObject("prefab");
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> heroes = new ArrayList<>();
        if (prefab != null) {
            heroes = pairs(prefab.optJSONArray("hero_id"));
        } else {
            JSONArray ids = slot.optJSONArray("heroIds");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    long id = ids.optLong(i, 0L);
                    if (id > 0L) heroes.add(Exec.kv(heroes.size() + 1L, id));
                }
            }
        }
        out.put("hero_id", heroes);

        if (prefab != null && prefab.has("soldier")) {
            List<Map<String, Object>> soldiers = pairs(prefab.optJSONArray("soldier"));
            if (!soldiers.isEmpty()) out.put("soldier", soldiers);
        } else if (prefab == null) {
            // 无 prefab 的老快照：只有 formation_rate 可复原（soldier id 无法还原，宁可不写）
            JSONObject ratio = slot.optJSONObject("ratio");
            if (ratio != null) {
                int inf = ratio.optInt("infantry", 0);
                int lanc = ratio.optInt("lancer", 0);
                int mark = ratio.optInt("marksman", 0);
                if (inf + lanc + mark > 0) out.put("formation_rate", rates(inf, lanc, mark));
            }
        }

        if (prefab != null && prefab.has("formation_rate")) {
            List<Map<String, Object>> raw = pairs(prefab.optJSONArray("formation_rate"));
            Map<Long, Long> byType = new LinkedHashMap<>();
            for (Map<String, Object> p : raw) {
                byType.put(Exec.longOf(p.get("k")), Exec.longOf(p.get("v")));
            }
            out.put("formation_rate", rates(
                    (int) (long) byType.getOrDefault(1L, 0L),
                    (int) (long) byType.getOrDefault(2L, 0L),
                    (int) (long) byType.getOrDefault(3L, 0L)));
        }

        String name = prefab == null ? "" : prefab.optString("formation_name", "").trim();
        if (name.isEmpty() && slot.has("name")) name = slot.optString("name", "").trim();
        // 回退生成的显示名（"编队 N"）不回写，避免覆盖游戏原名
        if (!name.isEmpty() && !name.startsWith(GameFormations.DEFAULT_NAME_PREFIX)) {
            out.put("formation_name", name);
        }
        return out;
    }

    /** k=1/2/3 的 formation_rate（缺失补 0；旧版 completeFormationRates）。 */
    static List<Map<String, Object>> rates(int infantry, int lancer, int marksman) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Exec.kv(1L, infantry));
        out.add(Exec.kv(2L, lancer));
        out.add(Exec.kv(3L, marksman));
        return out;
    }

    /** JSON 对数组 → {@code [{k,v}]}（值类型保持 long）。 */
    static List<Map<String, Object>> pairs(JSONArray arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.has("k")) continue;
            long k = Exec.longOf(o.opt("k"));
            long v = Exec.longOf(o.opt("v"));
            if (k > 0L) out.add(Exec.kv(k, v));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static JSONObject readSnapshot(StateStore stateStore, String key) {
        if (stateStore == null) return null;
        try {
            String raw = stateStore.getString(key);
            if (raw == null || raw.trim().isEmpty()) return null;
            return new JSONObject(raw.trim());
        } catch (Throwable t) {
            return null;
        }
    }
}
