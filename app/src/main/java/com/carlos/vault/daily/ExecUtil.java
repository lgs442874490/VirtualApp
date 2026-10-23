package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 日常执行器公共工具：响应判定 / 通知解析 / 出征编队（移植自网页版 BehemothAccountState 的解析口径）。 */
public final class ExecUtil {

    private ExecUtil() {
    }

    // ---------- 基础取值 ----------

    public static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static int intOf(Object v) {
        return (int) longOf(v);
    }

    public static int codeOf(GameMessage msg) {
        if (msg == null || msg.getContent() == null) return -1;
        Object c = msg.getContent().get("code");
        return c == null ? 0 : intOf(c);
    }

    public static boolean ok(GameMessage msg) {
        return msg != null && msg.getContent() != null && codeOf(msg) == 0
                && !DailyTasks.isDegraded(msg);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapOf(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** 逐层下钻取 map：nest(content, "info", "base")。 */
    static Map<String, Object> nest(Map<String, Object> m, String... keys) {
        Map<String, Object> cur = m;
        for (String k : keys) {
            if (cur == null) return null;
            cur = mapOf(cur.get(k));
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    static List<Object> listOf(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    /**
     * 形状兼容的列表取出（与服务端 task/Exec.mapsOf 同源）：游戏推送同一字段在不同版本/场景
     * 可能既是 List 又是 Map——
     * - List：逐项转 map，空 map 丢弃；
     * - Map：逐项取 value 转 map，并把 key 回填为 {@code id}（如 sciences: {科技id: {...}}、
     *   chests: {箱id: {...}}），保证上游按 {@code id} 取值不丢；
     * - 其它：空列表。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapsOf(Object v) {
        if (v instanceof Map) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object e : ((Map<Object, Object>) v).entrySet()) {
                Map.Entry<Object, Object> entry = (Map.Entry<Object, Object>) e;
                Map<String, Object> inner = mapOf(entry.getValue());
                if (inner == null || inner.isEmpty()) continue;
                Map<String, Object> item = new java.util.LinkedHashMap<>(inner);
                item.putIfAbsent("id", entry.getKey());
                out.add(item);
            }
            return out;
        }
        if (!(v instanceof java.util.Collection)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (java.util.Collection<Object>) v) {
            Map<String, Object> item = mapOf(o);
            if (item != null && !item.isEmpty()) out.add(item);
        }
        return out;
    }

    /** {k,v} 对列表 → 有序 map（游戏通用背包/兵力形状）。 */
    static Map<Long, Long> kvPairs(Object v) {
        Map<Long, Long> out = new TreeMap<>();
        for (Map<String, Object> m : mapsOf(v)) {
            long k = longOf(m.get("k"));
            if (k > 0) out.put(k, longOf(m.get("v")));
        }
        return out;
    }

    static Map<String, Object> kv(long k, long v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("v", v);
        return m;
    }

    public static Map<String, Object> point(long x, long y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    public static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 发请求并把响应内容里的常用 id 提出来。 */
    static Long idOf(Map<String, Object> content) {
        return WjdrEngine.extractId(content);
    }

    // ---------- 出征编队（来自 notify_hero_init / notify_soldier_init / 客户端布阵） ----------

    /** 客户端布阵小队（玩家自己配的五队）：source → 英雄ID列表。 */
    static Map<Long, List<Long>> clientSquads(WjdrEngine engine) {
        Map<Long, List<Long>> out = new TreeMap<>();
        Map<String, Object> arrange = engine.notifyData("notify_battle_hero_arrange_data");
        if (arrange == null) return out;
        long idx = 1;
        for (Map<String, Object> m : mapsOf(arrange.get("hero_arranges"))) {
            List<Long> heroes = new ArrayList<>();
            for (Object h : listOf(m.get("fight_heros")) != null ? listOf(m.get("fight_heros")) : new ArrayList<Object>()) {
                long id = longOf(h);
                if (id > 0) heroes.add(id);
            }
            if (!heroes.isEmpty()) out.put(idx, heroes);
            idx++;
        }
        return out;
    }

    /**
     * 全部英雄 id，按 id 降序（近似高品级优先）。
     * 兼容两种推送形状：schema 的 data[{id}]，以及真机实证的 {"50022.0":{hero}}。
     */
    static List<Long> allHeroes(WjdrEngine engine) {
        List<Long> out = new ArrayList<>();
        Map<String, Object> hi = engine.notifyData("notify_hero_init");
        if (hi == null) return out;
        Object data = hi.get("data");
        if (data == null) data = hi.get("heros");
        if (data != null) {
            for (Map<String, Object> m : mapsOf(data)) {
                long id = longOf(m.get("id"));
                if (id > 0) out.add(id);
            }
            if (out.isEmpty() && data instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) data).entrySet()) {
                    long id = idFromKeyOrValue(e);
                    if (id > 0) out.add(id);
                }
            }
        }
        if (out.isEmpty()) {
            for (Map.Entry<String, Object> e : hi.entrySet()) {
                long id = idFromKeyOrValue(e);
                if (id > 0) out.add(id);
            }
        }
        out.sort((a, b) -> Long.compare(b, a));
        return out;
    }

    /** map 的 key 是 "50022.0" 或 value 里带 id 时取英雄 id。 */
    private static long idFromKeyOrValue(Map.Entry<?, ?> e) {
        Object v = e.getValue();
        if (v instanceof Map) {
            long id = longOf(((Map<?, ?>) v).get("id"));
            if (id > 0) return id;
        }
        try {
            return (long) Double.parseDouble(String.valueOf(e.getKey()).trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 兵力库存：soldierId → 数量（notify_soldier_init.soldier=[{k,v}]）。 */
    static Map<Long, Long> soldiers(WjdrEngine engine) {
        Map<String, Object> si = engine.notifyData("notify_soldier_init");
        Map<Long, Long> out = new TreeMap<>();
        if (si == null) return out;
        Object soldier = si.get("soldier");
        out.putAll(kvPairs(soldier));                      // [{k,v}] 列表形状
        if (out.isEmpty()) {
            // 原版形状：{士兵id: 数量} 直读（integerLongMap；键可能是String或Double对象）
            if (soldier instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) soldier).entrySet()) {
                    try {
                        Object k = e.getKey();
                        long id = k instanceof Number ? ((Number) k).longValue()
                                : (long) Double.parseDouble(String.valueOf(k).trim());
                        // 真机形状：{ "11000.0": {id,count} }（value 是对象，不是数量）。
                        Object v = e.getValue();
                        long count = v instanceof Map
                                ? longOf(((Map<?, ?>) v).get("count")) : longOf(v);
                        out.put(id, count);
                    } catch (Throwable ignored) {
                    }
                }
            } else if (soldier instanceof List) {
                for (Object o : (List<?>) soldier) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    long id = 0;
                    for (Object k : new Object[]{"id", "soldier_id", "key"}) {
                        if (m.get(k) instanceof Number) {
                            id = ((Number) m.get(k)).longValue();
                            break;
                        }
                    }
                    if (id > 0) out.put(id, longOf(m.get("count") != null ? m.get("count") : m.get("value")));
                }
            }
        }
        return out;
    }

    /** 各兵种最高档士兵的可用量：[盾, 矛, 弓]。 */
    static long[] baseSoldiers(WjdrEngine engine) {
        Map<Long, Long> sol = soldiers(engine);
        long[] ids = bestSoldierIds(sol);
        long[] out = new long[3];
        for (Map.Entry<Long, Long> e : sol.entrySet()) {
            int line = (int) (e.getKey() / 10000);      // 1=盾 2=矛 3=弓
            if (line < 1 || line > 3) continue;
            if (e.getKey() == ids[line - 1]) out[line - 1] += e.getValue();
        }
        return out;
    }

    /** 各兵种当前最高档士兵 id（兵种*10000 + 等级*100，实证 11000=盾T10）。 */
    static long[] bestSoldierIds(Map<Long, Long> sol) {
        long[] ids = new long[]{11000L, 21000L, 31000L};
        int[] best = new int[]{-1, -1, -1};
        for (Map.Entry<Long, Long> e : sol.entrySet()) {
            int line = (int) (e.getKey() / 10000);
            int tier = (int) ((e.getKey() % 10000) / 100);
            if (line < 1 || line > 3) continue;
            if (e.getValue() != null && e.getValue() > 0 && tier > best[line - 1]) {
                best[line - 1] = tier;
                ids[line - 1] = e.getKey();
            }
        }
        return ids;
    }

    /**
     * 构造出征编队：优先用客户端第 N 队布阵英雄，兵种按 40/30/30 比例（不足按存量）。
     * 返回 formation map，无英雄时返回 null。
     */
    static Map<String, Object> formation(WjdrEngine engine, int squadIndex) {
        List<Long> heroes = clientSquads(engine).get((long) squadIndex);
        if (heroes == null || heroes.isEmpty()) {
            List<Long> all = allHeroes(engine);
            heroes = all.subList(0, Math.min(3, all.size()));
        }
        if (heroes.isEmpty()) return null;
        Map<Long, Long> sol = soldiers(engine);
        long[] base = baseSoldiers(engine);
        long stock = base[0] + base[1] + base[2];
        // 单队上限：全库存（几十万）会被服务端以参数错误拒绝（code=2）。
        long total = Math.min(stock, 50000L);
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        if (total > 0) {
            // 按各兵种实际库存夹取，否则 40/30/30 可能超出盾/矛存量（服务端回 code=2）。
            long wantShield = Math.min(base[0], Math.round(total * 0.4));
            long wantSpear = Math.min(base[1], Math.round(total * 0.3));
            long wantBow = Math.min(base[2], Math.max(0, total - wantShield - wantSpear));
            long[] want = new long[]{wantShield, wantSpear, wantBow};
            long[] ids = bestSoldierIds(sol);
            for (int i = 0; i < 3; i++) {
                if (want[i] > 0) soldierPairs.add(kv(ids[i], want[i]));
            }
        }
        List<Map<String, Object>> heroPairs = new ArrayList<>();
        for (long h : heroes) heroPairs.add(kv(h, 1));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", heroPairs);
        f.put("soldier", soldierPairs);
        return f;
    }

    /** 行军是否成功（响应 march_info.id>0）。 */
    public static long marchId(GameMessage rsp) {
        Map<String, Object> info = nest(rsp == null ? null : rsp.getContent(), "march_info");
        return info == null ? 0 : longOf(info.get("id"));
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 多步骤结果聚合。 */
    static class Multi {
        final StringBuilder sb = new StringBuilder();
        int ok, skip, degraded, fail;
        /** B表子项级：subId → "ok:文案"/"skip:文案"/"fail:文案"（随 DailyResult 落盘） */
        final java.util.Map<String, String> subResults = new java.util.LinkedHashMap<>();
        /** 领取明细累计（"名称：物品×数；"），随 DailyResult.rewards 落盘供「领取内容」展示 */
        private final StringBuilder rw = new StringBuilder();

        /** 领取成功时从响应提取物品明细并累计（无明细字段则不计）。 */
        void rewards(String name, GameMessage rsp) {
            try {
                if (rw.length() > 600) return;
                String d = RewardText.fromContent(rsp == null ? null : rsp.getContent());
                if (!d.isEmpty()) rw.append(name).append("：").append(d).append("；");
            } catch (Throwable ignored) {
            }
        }

        /** 累计的领取明细（去尾分隔符；空=未捕获到明细）。 */
        String rewardsText() {
            String s = rw.toString();
            return s.endsWith("；") ? s.substring(0, s.length() - 1) : s;
        }

        void subOk(String subId, String note) {
            subResults.put(subId, "ok:" + note);
        }

        void subSkip(String subId, String note) {
            subResults.put(subId, "skip:" + note);
        }

        void subFail(String subId, String note) {
            subResults.put(subId, "fail:" + note);
        }

        void claimed(String s) {
            ok++;
            sb.append(s).append("✓ ");
        }

        void skip(String s) {
            skip++;
            sb.append(s).append("· ");
        }

        void degraded(String s) {
            degraded++;
            sb.append(s).append("⚠ ");
        }

        void fail(String s) {
            fail++;
            sb.append(s).append("✗ ");
        }

        String note() {
            return "领取" + ok + "/跳过" + skip
                    + (degraded > 0 ? "/协议异常" + degraded : "")
                    + (fail > 0 ? "/失败" + fail : "");
        }

        DailyResult.Category cat() {
            if (degraded > 0) return DailyResult.Category.DEGRADED;
            if (fail > 0) return DailyResult.Category.ERROR;
            return ok > 0 ? DailyResult.Category.CLAIMED : DailyResult.Category.SKIPPED;
        }
    }
}
