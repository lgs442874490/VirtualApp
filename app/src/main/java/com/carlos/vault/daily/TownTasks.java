package com.carlos.vault.daily;

import com.carlos.vault.daily.gather.GatherConfigV2;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.pet.PetSkillCatalog;
import com.carlos.vault.wjdr.pet.PetSupport;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 城镇类任务（协议流移植自网页版 DailyTownService/Hospital/Barrack/Pet/Expert/Homeland/
 * FireCrystal/Climbtower/Arena 系列服务，按一次性执行语义裁剪）。
 */
final class TownTasks {

    private TownTasks() {
    }
static double num(Object v, double def) {
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }
// ================= 兵营可训练档位（对齐原版 DailyBarrackCatalog/DailyBarrackState） =================

    /** T1..T10 解锁所需兵营建筑等级（原版 BARRACK_LEVELS）；T11/T12 需 30 级 + 科技。 */
    private static final int[] BARRACK_TIER_LEVELS = {1, 4, 7, 11, 13, 16, 19, 22, 26, 30};
    /** T11 所需 spar 科技（盾/矛/弓）；T12 所需 refine 科技。 */
    private static final long[] TIER11_TECH = {7007, 8007, 9007};
    private static final long[] TIER12_TECH = {10006, 11006, 12006};
/**
     * 纯函数：按原版规则算每营已解锁最高档（下标 1/2/3 = 盾/矛/弓）。
     * 规则：t<=10 只看兵营等级表；t=11 加 spar 科技门控；t=12 加 refine 科技门控。
     * 科技表为 null（notify 缺失）时按空表处理——保守拦截高档（对齐原版：科技查不到就不放行）。
     */
    static int[] computeUnlockTiers(long[] levels, Map<Long, Long> spar, Map<Long, Long> refine) {
        Map<Long, Long> sparSafe = spar != null ? spar : java.util.Collections.emptyMap();
        Map<Long, Long> refineSafe = refine != null ? refine : java.util.Collections.emptyMap();
        int[] tiers = new int[4];
        for (int line = 1; line <= 3; line++) {
            int best = 0;
            for (int t = 12; t >= 1; t--) {
                int need = t <= 10 ? BARRACK_TIER_LEVELS[t - 1] : 30;
                if (levels[line] < need) continue;
                if (t == 11 && sparSafe.getOrDefault(TIER11_TECH[line - 1], 0L) < 1) continue;
                if (t == 12 && refineSafe.getOrDefault(TIER12_TECH[line - 1], 0L) < 1) continue;
                best = t;
                break;
            }
            tiers[line] = best;
        }
        return tiers;
    }
private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('、');
            sb.append(s);
        }
        return sb.toString();
    }
/** 生命之树小时产量表（按建筑等级，index=等级，封顶 420/时）。 */
    private static final long[] LIFE_TREE_HOURLY = {0, 120, 150, 180, 210, 240, 270, 300, 330, 360, 420};
/** 递归收集家园建筑条目（含 bid 的 map）。 */
    static List<Map<String, Object>> homelandBuildings(Map<String, Object> node) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectBuildings(node, out, 0);
        return out;
    }

    private static void collectBuildings(Map<String, Object> node, List<Map<String, Object>> out, int depth) {
        if (node == null || depth > 4 || out.size() >= 32) return;
        if (node.containsKey("bid")) {
            out.add(node);
            return;
        }
        for (Object v : node.values()) {
            if (v instanceof Map) {
                collectBuildings((Map<String, Object>) v, out, depth + 1);
            } else if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    if (o instanceof Map) collectBuildings((Map<String, Object>) o, out, depth + 1);
                }
            }
        }
    }

    private static long homelandBuildingLevel(Map<String, Object> hi, long bid) {
        for (Map<String, Object> b : homelandBuildings(hi)) {
            if (numIn(b, "bid") == bid) return numIn(b, "level");
        }
        return 0;
    }

    /** 小屋/加工厂的 cur_value（字段可能直接挂建筑条目或内嵌 info）。 */
    private static long loggingCur(Map<String, Object> b) {
        long v = numIn(b, "cur_value");
        if (v > 0) return v;
        for (Object o : b.values()) {
            if (o instanceof Map) {
                v = numIn((Map<String, Object>) o, "cur_value");
                if (v > 0) return v;
            }
        }
        return 0;
    }

    /** 递归找第一个含指定键的子 map。 */
    private static Map<String, Object> deepMap(Map<String, Object> node, String key) {
        if (node == null) return null;
        Object direct = node.get(key);
        if (direct instanceof Map) return (Map<String, Object>) direct;
        for (Object v : node.values()) {
            if (v instanceof Map) {
                Map<String, Object> r = deepMap((Map<String, Object>) v, key);
                if (r != null) return r;
            }
        }
        return null;
    }
static String towerName(long tower) {
        switch ((int) tower) {
            case 1: return "英雄之地";
            case 2: return "群兽洞窟";
            case 3: return "宝石矿洞";
            case 4: return "地下科研所";
            case 5: return "装备锻炉";
            default: return "大地之心";
        }
    }
// ---------- 竞技场：挑战弱敌 + 每日奖励 ----------

    /** 每日免费刷新对手上限（1:1 网页版 DailyArenaConfig.freeOpponentRefreshLimit 默认 5）。 */
    private static final long ARENA_FREE_REFRESH_LIMIT = 5L;
/** 防御式数字提取：依次找 depth≤2 的同名键。 */
    static long numIn(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        if (v instanceof Number) return ExecUtil.longOf(v);
        for (Object o : map.values()) {
            if (o instanceof Map) {
                Object inner = ((Map<?, ?>) o).get(key);
                if (inner instanceof Number) return ExecUtil.longOf(inner);
            }
        }
        return 0;
    }
}