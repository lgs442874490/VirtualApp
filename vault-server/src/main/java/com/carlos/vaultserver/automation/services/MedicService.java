package com.carlos.vaultserver.automation.services;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 军医所服务：伤兵统计 + 可配置治疗量 + 自动援助。
 *
 * <p>修复的 BUG：旧实现只读 notify_hospital_init.wound_soldiers；
 * 实战大量伤兵来自 notify_hospital_wound_soldiers 推送，init 可能是空快照。
 * 本服务合并多个通知并按兵种取最大值，避免漏计/重复计。
 */
public final class MedicService {

    public static final class Snapshot {
        public final Map<Long, Long> wounded = new LinkedHashMap<>();
        public final Map<Long, Long> healing = new LinkedHashMap<>();
        public final String sources;

        Snapshot(String sources) {
            this.sources = sources;
        }

        public long woundedTotal() {
            long n = 0;
            for (long v : wounded.values()) n += v;
            return n;
        }

        public long healingTotal() {
            long n = 0;
            for (long v : healing.values()) n += v;
            return n;
        }
    }

    public Snapshot snapshot(TaskContext tc) {
        tc.engine.collectNotifies(1500);
        try {
            FileLog.info("medic notifies fpid={} protos={}", tc.fpid,
                    tc.engine.notifyProtos());
        } catch (Throwable ignored) {
        }
        Map<Long, Long> wounded = new LinkedHashMap<>();
        Map<Long, Long> healing = new LinkedHashMap<>();
        StringBuilder sources = new StringBuilder();

        readAndMerge(wounded, tc.engine.notifyData("notify_hospital_init"),
                "wound_soldiers", "hospital_init", sources);
        readAndMerge(wounded, tc.engine.notifyData("notify_hospital_wound_soldiers"),
                "wound_soldiers", "hospital_wound", sources);
        // 跨服/乱斗军医所：按配置可选择是否纳入；默认纳入，避免“主城无伤兵但活动有伤兵”。
        readAndMerge(wounded, tc.engine.notifyData("notify_brawl_war_hospital_init"),
                "wound_soldiers", "brawl_hospital_init", sources);
        readAndMerge(wounded, tc.engine.notifyData("notify_arsenal_war_hospital_init"),
                "wound_soldiers", "arsenal_war_hospital_init", sources);
        readAndMerge(wounded, tc.engine.notifyData("notify_arsenal_league_hospital_init"),
                "wound_soldiers", "arsenal_league_hospital_init", sources);

        // 正在治疗的伤兵：heal_data.soldiers
        readHealing(healing, tc.engine.notifyData("notify_hospital_init"),
                "heal_data", sources);
        return new Snapshot(sources.length() == 0 ? "无军医所通知" : sources.toString());
    }

    /**
     * 解析 wound_soldiers（支持 [{k,v}] 与 {soldiers:[{k,v}]} 两种形状）。
     * 包内可见，便于单测锁定“大量伤兵被漏计”的回归。
     */
    static Map<Long, Long> parseWounded(Object value) {
        Map<Long, Long> m = Exec.kvPairs(value);
        if (m.isEmpty() && value instanceof Map) {
            Object inner = ((Map<?, ?>) value).get("soldiers");
            m = Exec.kvPairs(inner);
        }
        return m;
    }

    /** 多来源合并取 max，避免 init + push 重复计数。 */
    static void mergeMax(Map<Long, Long> out, Map<Long, Long> in) {
        for (Map.Entry<Long, Long> e : in.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    private static void readAndMerge(Map<Long, Long> out, Map<String, Object> notify,
                                     String key, String source, StringBuilder sources) {
        if (notify == null) return;
        Map<Long, Long> m = parseWounded(notify.get(key));
        if (m.isEmpty()) return;
        sources.append(source).append('=').append(sum(m)).append("; ");
        mergeMax(out, m);
    }

    private static void readHealing(Map<Long, Long> out, Map<String, Object> notify,
                                    String key, StringBuilder sources) {
        if (notify == null) return;
        Map<String, Object> heal = Exec.mapOf(notify.get(key));
        if (heal == null) return;
        Map<Long, Long> m = Exec.kvPairs(heal.get("soldiers"));
        for (Map.Entry<Long, Long> e : m.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Math::max);
        }
        if (!m.isEmpty()) sources.append("healing=").append(sum(m)).append("; ");
    }

    private static long sum(Map<Long, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }

    /**
     * @param maxCount 0=全部；>0 最多治疗该数量（按伤兵类型顺序截取）
     * @param immediate 是否立即完成
     * @param requestHelp 是否在治疗后请求联盟援助
     */
    public TaskOutcome heal(TaskContext tc, int maxCount, boolean immediate,
                            boolean requestHelp) {
        TaskOutcome outcome;
        try {
            Snapshot s = snapshot(tc);
            long total = s.woundedTotal();
            if (total <= 0) {
                outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.SKIPPED,
                        "无伤兵（" + s.sources + "）");
                outcome.subResults.put("heal", "skip:无伤兵（" + s.sources + "）");
                return outcome;
            }
            long cap = maxCount <= 0 ? total : Math.min(total, maxCount);
            List<Map<String, Object>> batch = new ArrayList<>();
            long left = cap;
            for (Map.Entry<Long, Long> e : s.wounded.entrySet()) {
                if (left <= 0) break;
                long take = Math.min(left, e.getValue());
                if (take > 0) {
                    batch.add(Exec.kv(e.getKey(), take));
                    left -= take;
                }
            }
            if (batch.isEmpty()) {
                outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.SKIPPED,
                        "伤兵数据为空（" + s.sources + "）");
                return outcome;
            }
            long willHeal = cap - left;
            var rsp = tc.engine.request("req_hospital_heal_soldier",
                    Exec.args("soldiers", batch, "immediate", immediate));
            int code = Exec.codeOf(rsp);
            if (Exec.ok(rsp)) {
                outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.CLAIMED,
                        "治疗" + willHeal + "伤兵（伤兵总数" + total + "）");
                outcome.subResults.put("heal", "ok:治疗" + willHeal + "伤兵");
            } else if (code == 2301) {
                outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.SKIPPED, "上批仍在治疗中");
                outcome.subResults.put("heal", "skip:上批治疗中");
            } else if (code == 51) {
                outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.SKIPPED, "资源不足，未治疗");
                outcome.subResults.put("heal", "skip:资源不足");
            } else {
                outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.ERROR, "治疗码" + code);
                outcome.subResults.put("heal", "fail:码" + code);
            }

            if (requestHelp) {
                try {
                    var help = tc.engine.request("req_alliance_help_all", null);
                    if (Exec.ok(help)) {
                        outcome.subResults.put("help", "ok:已请求联盟援助");
                    } else {
                        outcome.subResults.put("help", "skip:援助码" + Exec.codeOf(help));
                    }
                } catch (Throwable t) {
                    outcome.subResults.put("help", "skip:援助异常");
                }
            }
            return outcome;
        } catch (Exception e) {
            if (com.carlos.vaultserver.task.ServerTownTasks.sessionLost(e)) {
                return com.carlos.vaultserver.task.ServerTasks.classifyException(e, "军医所");
            }
            return new TaskOutcome("medic", "军医所", TaskOutcome.Category.ERROR,
                    "治疗异常：" + e.getClass().getSimpleName());
        }
    }
}
