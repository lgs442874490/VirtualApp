package com.carlos.vault.daily;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领取明细的解析与命名：把协议响应里的奖励字段转成"钻石×30、生肉×1000"式的中文明细，
 * 供日志 detail 落盘与功能页「领取内容」展示（领到了什么，而不是什么时间领的）。
 *
 * <p>形状防御式解析（各协议奖励字段形状不一，参照 ExecUtil.kvPairs/soldiers 的既有口径）：
 * <ul>
 *   <li>{@code [{k:id, v:count}...]} —— kv 对列表（游戏通用背包/资源形状）</li>
 *   <li>{@code [{id:.., count/num/amount:..}...]} —— id+数量列表</li>
 *   <li>{@code {id:count, ...}} —— 数量映射（键可能为字符串或 Double）</li>
 *   <li>嵌套容器逐层下钻（深度限 3），识别不了的形状原样截断，保证不因解析失败丢证据</li>
 * </ul>
 */
public final class RewardText {

    /** 单条明细渲染上限（避免批量领取把页面撑爆）。 */
    private static final int MAX_LEN = 200;

    /** 响应内容里的奖励字段候选键（领取类响应按命中率排序）。 */
    private static final String[] REWARD_KEYS = {
            "rewards", "reward", "awards", "award", "reward_list", "award_list", "item_list",
            "items", "online_reward", "spec_reward", "gains", "gain", "drop_list", "drops",
            "first_reward", "extra_reward", "reward_infos"};

    /** 物品命名表（奖励目录照原版 DailyLoginRewardCatalog；101~106 资源段照 towndev resourceNames）。 */
    public static String name(long id) {
        switch ((int) id) {
            case 100003: return "钻石";
            case 100004: return "1000肉";
            case 100072: return "5000英雄经验";
            case 100081: return "传说装备自选箱";
            case 200102: return "5分钟加速";
            case 200103: return "1小时加速";
            case 200202: return "建造加速5分钟";
            case 200203: return "建造加速1小时";
            case 200302: return "训练加速5分钟";
            case 200303: return "训练加速1小时";
            case 200402: return "研究加速5分钟";
            case 200403: return "研究加速1小时";
            case 500200: return "传说探险技能书";
            case 500210: return "传说远征技能书";
            case 500220: return "传说英雄信物";
            case 500221: return "史诗英雄信物";
            case 600021: return "强韧合金";
            case 600022: return "抛光液";
            case 600023: return "设计图纸";
            case 600024: return "宝石图纸";
            case 600025: return "精工图纸";
            case 100091: case 100092: case 100093: return "随机补给箱";
            case 100111: return "资源包(100档)";
            case 100112: return "资源包(1k档)";
            case 100113: return "资源包(1w档)";
            case 100114: return "资源包(10w档)";
            case 100115: return "资源包(100w档)";
            case 101: return "钻石";
            case 102: return "生肉";
            case 103: return "木材";
            case 104: return "煤矿";
            case 105: return "铁矿";
            case 106: return "钢材";
            case 100082: return "精炼火晶";
            case 100083: return "火晶微粒";
            default: return "物品" + id;
        }
    }

    /** 从响应内容里找奖励字段并解析明细；找不到返回 ""。 */
    public static String fromContent(Map<String, Object> content) {
        if (content == null) return "";
        for (String k : REWARD_KEYS) {
            if (!content.containsKey(k)) continue;
            String d = describe(content.get(k));
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    /** 任意奖励形状 → "名称×数量、名称×数量"；识别不了返回 ""。同 id 自动合并数量。 */
    public static String describe(Object v) {
        Map<Long, Long> agg = new LinkedHashMap<>();
        collect(v, agg, 0);
        if (agg.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Long, Long> e : agg.entrySet()) {
            if (out.length() > MAX_LEN) break;
            if (out.length() > 0) out.append('、');
            out.append(name(e.getKey()));
            if (e.getValue() > 1) out.append('×').append(e.getValue());
        }
        return out.length() > MAX_LEN + 16 ? out.substring(0, MAX_LEN) + "…" : out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object v, Map<Long, Long> agg, int depth) {
        if (v == null || depth > 3) return;
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            // 单个 {k:id, v:count} 对
            if (m.containsKey("k") && m.containsKey("v") && m.size() <= 2) {
                long id = idOf(m.get("k"));
                if (id > 0) {
                    add(agg, id, longOf(m.get("v")));
                    return;
                }
            }
            // 单个 {id:.., count/num/amount:..} 元素
            long id = 0;
            for (String k : new String[]{"id", "item_id", "itemId"}) {
                if (m.containsKey(k)) {
                    id = idOf(m.get(k));
                    break;
                }
            }
            if (id > 0 && m.size() <= 6) {
                long cnt = 1;
                for (String k : new String[]{"count", "num", "amount", "value", "v", "n"}) {
                    Object c = m.get(k);
                    if (c instanceof Number) {
                        cnt = ((Number) c).longValue();
                        break;
                    }
                }
                add(agg, id, cnt);
                return;
            }
            // 数量映射 {100003:30,...} 或嵌套容器
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object val = e.getValue();
                long kid = idOf(e.getKey());
                if (kid > 0 && val instanceof Number) add(agg, kid, ((Number) val).longValue());
                else collect(val, agg, depth + 1);
            }
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Number) continue;   // 裸数字列表形状不明，跳过防误读
                collect(o, agg, depth + 1);
            }
        }
    }

    private static void add(Map<Long, Long> agg, long id, long cnt) {
        if (id <= 0) return;
        Long old = agg.get(id);
        agg.put(id, (old == null ? 0 : old) + Math.max(0, cnt));
    }

    private static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long idOf(Object k) {
        if (k instanceof Number) return ((Number) k).longValue();
        if (k == null) return 0;
        try {
            return (long) Double.parseDouble(String.valueOf(k).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 日志行整理（功能页「领取内容」展示口径） ----------

    /** 去掉符号与"任务名："前缀，保留领取描述本身。 */
    public static String cleanReason(String reason) {
        if (reason == null) return "";
        String s = reason.replace("✅", "").replace("⏭", "").replace("⚠️", "").trim();
        int c = s.indexOf('：');
        // 首个冒号前是任务名（如"仓库补给："），长度有限；更长视为正文自带的冒号，不动
        if (c > 0 && c <= 14) s = s.substring(c + 1).trim();
        return s;
    }

    /** 「领取内容」一行：优先展示明细（多段拆行），无明细回退清理后的结果描述。 */
    public static String claimLine(String reason, String detail) {
        String d = detail == null ? "" : detail.trim();
        if (!d.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : d.split("；")) {
                p = p.trim();
                if (p.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(p);
            }
            if (sb.length() > 0) return sb.toString();
        }
        return cleanReason(reason);
    }

    // ---------- 登录好礼目录（原 DailyLoginRewardCatalog 三轮次；详情页网格与明细共用） ----------

    /** 目录条目拼接："钻石×1、5分钟加速×1"。 */
    public static String join(String[] items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('、');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String[] line(long id, long cnt) {
        return new String[]{name(id) + "×" + cnt};
    }

    /** 免费轨七日目录：[day][物品描述]。 */
    public static String[][] loginRewardsFree(int round) {
        if (round == 5004002 || round == 5004003) {
            return new String[][]{line(100003, 1), line(200102, 1), line(200202, 1),
                    line(600021, 50), line(200402, 1), line(200302, 1), line(600022, 1)};
        }
        return new String[][]{line(100003, 1), line(200102, 1), line(200202, 1),
                line(100072, 1), line(200402, 1), line(200302, 1), line(500221, 1)};
    }

    /** 史诗轨七日目录：[day][物品描述]。 */
    public static String[][] loginRewardsEpic(int round) {
        if (round == 5004001) {
            return new String[][]{
                    new String[]{"1000肉×1", "传说英雄信物×1"},
                    new String[]{"5分钟加速×12", "1小时加速×1"},
                    new String[]{"1小时加速×1", "建造加速1小时×1"},
                    new String[]{"传说探险技能书×5", "传说远征技能书×5"},
                    new String[]{"1小时加速×1", "研究加速1小时×1"},
                    new String[]{"1小时加速×1", "训练加速1小时×1"},
                    new String[]{"史诗英雄信物×1", "传说英雄信物×1"}};
        }
        int epic81 = round == 5004003 ? 7 : 5;   // 第三轮 D2 传说装备箱×7
        int epic81b = round == 5004003 ? 8 : 5;  // 第三轮 D6 传说装备箱×8
        return new String[][]{
                new String[]{"1000肉×1", "传说英雄信物×1"},
                new String[]{"1小时加速×1", "传说装备自选箱×" + epic81},
                new String[]{"建造加速1小时×1", "宝石图纸×2"},
                new String[]{"传说探险技能书×5", "传说远征技能书×5"},
                new String[]{"研究加速1小时×1", "精工图纸×2"},
                new String[]{"训练加速1小时×1", "传说装备自选箱×" + epic81b},
                new String[]{"设计图纸×2", "传说英雄信物×1"}};
    }

    private RewardText() {
    }
}
