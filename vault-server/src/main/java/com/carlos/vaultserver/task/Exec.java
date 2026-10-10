package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 执行器公共工具（App 端 daily/ExecUtil 逐行移植，改动须两侧同步）。
 */
public final class Exec {

    private Exec() {
    }

    public static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static int intOf(Object v) {
        return (int) longOf(v);
    }

    public static int codeOf(GameMessage msg) {
        if (msg == null || msg.getContent() == null) return -1;
        Object c = msg.getContent().get("code");
        return c == null ? 0 : intOf(c);
    }

    public static boolean ok(GameMessage msg) {
        return msg != null && msg.getContent() != null && codeOf(msg) == 0
                && !ServerTasks.isDegraded(msg);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapOf(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** 逐层下钻取 map：nest(content, "info", "base")。 */
    public static Map<String, Object> nest(Map<String, Object> m, String... keys) {
        Map<String, Object> cur = m;
        for (String k : keys) {
            if (cur == null) return null;
            cur = mapOf(cur.get(k));
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listOf(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    public static List<Map<String, Object>> mapsOf(Object v) {
        List<Object> l = listOf(v);
        if (l == null) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    /** {k,v} 对列表 → 有序 map（游戏通用背包/兵力形状）。 */
    public static Map<Long, Long> kvPairs(Object v) {
        Map<Long, Long> out = new TreeMap<>();
        for (Map<String, Object> m : mapsOf(v)) {
            long k = longOf(m.get("k"));
            if (k > 0) out.put(k, longOf(m.get("v")));
        }
        return out;
    }

    public static Map<String, Object> kv(long k, long v) {
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

    public static Long idOf(Map<String, Object> content) {
        return WjdrEngine.extractId(content);
    }

    // ---------- 出征编队（与 App 端一致） ----------

    public static Map<Long, List<Long>> clientSquads(WjdrEngine engine) {
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

    public static List<Long> allHeroes(WjdrEngine engine) {
        List<Long> out = new ArrayList<>();
        Map<String, Object> hi = engine.notifyData("notify_hero_init");
        if (hi == null) return out;
        for (Map<String, Object> m : mapsOf(hi.get("data"))) {
            long id = longOf(m.get("id"));
            if (id > 0) out.add(id);
        }
        out.sort((a, b) -> Long.compare(b, a));
        return out;
    }

    public static Map<Long, Long> soldiers(WjdrEngine engine) {
        Map<String, Object> si = engine.notifyData("notify_soldier_init");
        Map<Long, Long> out = new TreeMap<>();
        if (si == null) return out;
        Object soldier = si.get("soldier");
        out.putAll(kvPairs(soldier));
        if (out.isEmpty()) {
            if (soldier instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) soldier).entrySet()) {
                    try {
                        Object k = e.getKey();
                        long id = k instanceof Number ? ((Number) k).longValue()
                                : (long) Double.parseDouble(String.valueOf(k).trim());
                        out.put(id, longOf(e.getValue()));
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

    public static long[] baseSoldiers(WjdrEngine engine) {
        long[] out = new long[3];
        for (Map.Entry<Long, Long> e : soldiers(engine).entrySet()) {
            long tier = e.getKey() / 10000;
            long idx = (e.getKey() % 10000) / 100;
            if (idx != 1) continue;
            if (tier >= 1 && tier <= 3) out[(int) (tier - 1)] += e.getValue();
        }
        return out;
    }

    /** 构造出征编队：优先用客户端第 N 队布阵英雄，兵种按 40/30/30 比例。 */
    public static Map<String, Object> formation(WjdrEngine engine, int squadIndex) {
        List<Long> heroes = clientSquads(engine).get((long) squadIndex);
        if (heroes == null || heroes.isEmpty()) {
            List<Long> all = allHeroes(engine);
            heroes = all.subList(0, Math.min(3, all.size()));
        }
        if (heroes.isEmpty()) return null;
        long[] base = baseSoldiers(engine);
        long total = base[0] + base[1] + base[2];
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        if (total > 0) {
            long[] want = new long[]{(long) (total * 0.4), (long) (total * 0.3), 0};
            want[2] = total - want[0] - want[1];
            long[] tier1Ids = new long[]{10001, 20001, 30001};
            for (int i = 0; i < 3; i++) {
                if (want[i] > 0) soldierPairs.add(kv(tier1Ids[i], want[i]));
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

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 多步骤结果聚合（与 App ExecUtil.Multi 一致）。 */
    public static final class Multi {
        public final StringBuilder sb = new StringBuilder();
        public int ok, skip, degraded, fail;
        public final java.util.Map<String, String> subResults = new java.util.LinkedHashMap<>();
        /** 领取明细累计（与 App ExecUtil.Multi 一致；随 TaskOutcome.rewards 落盘）。 */
        private final StringBuilder rw = new StringBuilder();

        /** 领取成功时从响应提取物品明细并累计（与 App ExecUtil.Multi.rewards 一致）。 */
        public void rewards(String name, GameMessage rsp) {
            try {
                if (rw.length() > 600) return;
                String d = com.carlos.vault.daily.RewardText.fromContent(
                        rsp == null ? null : rsp.getContent());
                if (!d.isEmpty()) rw.append(name).append("：").append(d).append("；");
            } catch (Throwable ignored) {
            }
        }

        /** 累计的领取明细（去尾分隔符；空=未捕获到明细）。 */
        public String rewardsText() {
            String s = rw.toString();
            return s.endsWith("；") ? s.substring(0, s.length() - 1) : s;
        }

        public void subOk(String subId, String note) {
            subResults.put(subId, "ok:" + note);
        }

        public void subSkip(String subId, String note) {
            subResults.put(subId, "skip:" + note);
        }

        public void subFail(String subId, String note) {
            subResults.put(subId, "fail:" + note);
        }

        public void claimed(String s) {
            ok++;
            sb.append(s).append("✓ ");
        }

        public void skip(String s) {
            skip++;
            sb.append(s).append("· ");
        }

        public void degraded(String s) {
            degraded++;
            sb.append(s).append("⚠ ");
        }

        public void fail(String s) {
            fail++;
            sb.append(s).append("✗ ");
        }

        public String note() {
            return "领取" + ok + "/跳过" + skip
                    + (degraded > 0 ? "/协议异常" + degraded : "")
                    + (fail > 0 ? "/失败" + fail : "");
        }

        public TaskOutcome.Category cat() {
            if (degraded > 0) return TaskOutcome.Category.DEGRADED;
            if (fail > 0) return TaskOutcome.Category.ERROR;
            return ok > 0 ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED;
        }
    }

    /** Multi → TaskOutcome 收口（所有移植执行器统一用）。 */
    public static TaskOutcome result(Exec.Multi m, String id, String name) {
        TaskOutcome r = new TaskOutcome(id, name, m.cat(), m.note() + " " + m.sb);
        r.rewards = m.rewardsText();
        r.subResults.putAll(m.subResults);
        return r;
    }
}
