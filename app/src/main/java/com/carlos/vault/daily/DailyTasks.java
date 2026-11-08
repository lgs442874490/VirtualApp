package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首批日常领取任务（协议与判定移植自原版反编译，按失效根因分析加固）：
 * - 不依赖服务端推送状态，直接尝试领取并以响应为准（修"状态未初始化→永久无效"）
 * - 服务端错误码分级：合理跳过 vs 真异常（修"静默 SKIPPED"）
 * - 响应缺预期字段 → DEGRADED 而非成功（修"假成功无效果"）
 * - 会话中断关键字 → SESSION_CLOSED 可重试
 */
public final class DailyTasks {

    private static final String[] SESSION_CLOSED_MARKS = {
            "不在线", "会话已", "连接已关闭", "断开", "强制下线", "连接失败", "发送协议数据失败",
            "等待协议响应超时", "Socket", "socket", "EOF"};

    /** 任务定义 */
    public static class TaskDef {
        public final String id;
        public final String name;
        public final boolean enabledByDefault;

        public TaskDef(String id, String name, boolean enabledByDefault) {
            this.id = id;
            this.name = name;
            this.enabledByDefault = enabledByDefault;
        }
    }

    /** 已注册任务（首批 5 项；后续批次在此追加）。 */
    public static List<TaskDef> all() {
        List<TaskDef> list = new ArrayList<>();
        list.add(new TaskDef("login_rewards", "登录好礼", true));
        list.add(new TaskDef("mail", "邮件奖励", true));
        list.add(new TaskDef("supply", "仓库补给", true));
        list.add(new TaskDef("daily_mission", "每日任务", true));
        list.add(new TaskDef("chapter_quest", "章节任务", true));
        list.add(new TaskDef("adventure", "挂机奖励", true));
        list.add(new TaskDef("activity_rewards", "活动奖励", true));
        list.add(new TaskDef("alliance_chest", "联盟宝箱", true));
        list.add(new TaskDef("alliance_donate", "联盟捐献", true));
        list.add(new TaskDef("battle_shield", "战斗护盾", true));
        list.add(new TaskDef("guardian", "守护盟友", false));
        list.add(new TaskDef("war", "战争状态", true));
        list.add(new TaskDef("shop", "商店购买", false));
        list.add(new TaskDef("commander_gift", "统帅礼包", true));
        list.add(new TaskDef("achievement_claim", "成就奖励", true));
        // ===== 城镇 =====
        list.add(new TaskDef("research", "科技研究", true));
        list.add(new TaskDef("building", "建筑升级", false));
        list.add(new TaskDef("town_affairs", "城镇管理", true));
        list.add(new TaskDef("medic", "军医所", true));
        list.add(new TaskDef("barracks", "兵营训练", false));
        list.add(new TaskDef("pet", "宠物", true));
        list.add(new TaskDef("expert", "专家游历", true));
        list.add(new TaskDef("homeland", "晨曦岛", true));
        list.add(new TaskDef("fire_crystal", "火晶提炼", true));
        list.add(new TaskDef("climbtower", "地心探索", true));
        list.add(new TaskDef("arena", "竞技场", false));
        // ===== 活动 =====
        list.add(new TaskDef("act_balloon", "正中靶心", true));
        list.add(new TaskDef("act_pitchpot", "壶纳千祥", true));
        list.add(new TaskDef("act_snowclean", "除雪", true));
        list.add(new TaskDef("act_icebound", "冰封宝藏", true));
        list.add(new TaskDef("act_polar", "逐光之旅", true));
        list.add(new TaskDef("act_brawl", "欢乐乱斗", true));
        list.add(new TaskDef("act_fishing", "钓鱼", true));
        list.add(new TaskDef("act_endless", "无尽征程", true));
        list.add(new TaskDef("act_joy", "怪攻守城", false));
        list.add(new TaskDef("act_feast", "至尊盛宴", false));
        list.add(new TaskDef("act_mercenary", "佣兵荣耀", false));
        list.add(new TaskDef("act_truck", "雪原商路", false));
        list.add(new TaskDef("act_arsenal_league", "雪域兵器联赛", false));
        list.add(new TaskDef("act_slg_league", "冰火战歌", false));
        list.add(new TaskDef("act_cross_capital_revive", "最强王国复活", false));
        list.add(new TaskDef("ally_offline_guard", "离线联动", false));
        list.add(new TaskDef("act_icehunter", "冰原猎人", false));
        list.add(new TaskDef("red_packet", "游戏红包", true));
        list.add(new TaskDef("bank", "钻石银行", false));
        list.add(new TaskDef("act_flamefangs", "烈焰獠牙", false));
        list.add(new TaskDef("act_snowtrade", "雪原贸易站", false));
        list.add(new TaskDef("act_frostfire", "燃霜矿区", false));
        list.add(new TaskDef("social_gift", "社交礼物", false));
        // ===== 野外 =====
        list.add(new TaskDef("gather", "野外采集", false));
        list.add(new TaskDef("intel", "情报任务", false));
        list.add(new TaskDef("behemoth", "巨兽狩猎", true));
        list.add(new TaskDef("bear", "巨熊行动", true));
        list.add(new TaskDef("hunt", "自动狩猎", false));
        list.add(new TaskDef("adventure_sweep", "探险速刷", false));
        // ===== 联盟 =====
        list.add(new TaskDef("alliance_mobilization", "联盟动员", true));
        list.add(new TaskDef("alliance_showdown", "争霸赛", false));
        list.add(new TaskDef("alliance_help", "联盟互助", true));
        list.add(new TaskDef("homeland_visit", "晨曦岛拜访", true));
        // ===== 商店/其他 =====
        list.add(new TaskDef("alliance_shop", "联盟商店", false));
        // 2026-11-06 决策2：backpack 被 FeatureGate.BACKPACK_ITEMS 隐藏（产品未确认），
        // 默认值同步改 false——隐藏的功能不默认执行（上传侧另有 filterHiddenDailyFns 兜底）。
        list.add(new TaskDef("backpack", "背包道具", false));
        return list;
    }
// ---------- 4. 每日任务奖励 ----------

    /** 原版 DailyMissionCatalog 全量任务 ID（1320000~1320083 及后续段），缺失段由服务端忽略。 */
    static final int[] DAILY_QUEST_IDS = {
            1320000,
            1320010, 1320011, 1320012,
            1320020, 1320021, 1320022,
            1320030, 1320031,
            1320040, 1320041,
            1320050, 1320051,
            1320060, 1320061,
            1320070, 1320071, 1320072,
            1320081, 1320082, 1320083};
// ---------- 公共判定（根因修复核心） ----------

    /** 降级检测：未知协议 / 未映射会话 → 原版仅告警并假成功，这里明确降级。 */
    static boolean isDegraded(GameMessage msg) {
        if (msg == null || msg.getContent() == null) return true;
        return msg.getContent().containsKey("_unknown_proto_tag")
                || msg.getContent().containsKey("_unmapped_session");
    }

    /** 简单领取类通用分类：有 code 看 code，无 code 查证据字段；成功时顺带提取物品明细。 */
    static DailyResult classifySimple(GameMessage msg, String proto, String name, String[] evidenceKeys, String claimedMsg) {
        if (isDegraded(msg)) {
            return new DailyResult(proto, name, DailyResult.Category.DEGRADED, "响应异常（协议可能变更）");
        }
        Object code = msg.getContent().get("code");
        if (code != null && toInt(code) != 0) {
            return classifyServerCode(toInt(code), proto, name);
        }
        for (String k : evidenceKeys) {
            if (msg.getContent().containsKey(k)) {
                DailyResult r = new DailyResult(proto, name, DailyResult.Category.CLAIMED, claimedMsg);
                r.rewards = RewardText.fromContent(msg.getContent());
                return r;
            }
        }
        return new DailyResult(proto, name, DailyResult.Category.DEGRADED, "响应缺少预期字段，未确认到账");
    }
/**
     * 服务端业务错误码 → 合理跳过（带原因）或异常。
     *
     * <p>fnId 必须始终是功能注册表 id（例如 daily_mission），不能传显示名：
     * 否则日志/当日状态会落到中文键，功能详情按 id 查不到，显示成“今日未跑”。
     */
    static DailyResult classifyServerCode(int code, String fnId, String name) {
        switch (code) {
            case 2:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "尚未到可领时间（冷却中）");
            case 6151:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "当前不可领取，等待下一窗口");
            case 6158:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "本窗口已领过");
            case 6152:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "体力补给不可领取");
            case 7501:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "今日已领取或暂无可领奖励");
            case 4303:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "暂无可领取的任务奖励");
            case 4302:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED,
                        "任务奖励已领取或暂无可领（码4302）");
            case 4104:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED,
                        "科技目标已满级或当前不可研究（码4104）");
            case 4004:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "领取间隔未到");
            case 4003:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "功能尚未解锁");
            case 21:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "今日已领取或次数已用");
            case 7401: case 7404:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "活动未开启或已结束");
            case 40611: case 61:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "今日已领取");
            case 1201:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "邮件不存在（已被领）");
            case 1202:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "附件已领取");
            case 1204: case 1215:
                return new DailyResult(fnId, name, DailyResult.Category.SKIPPED, "邮件状态不允许领取");
            case 40271:
                return new DailyResult(fnId, name, DailyResult.Category.SESSION_CLOSED, "被服务端强制下线");
            default:
                return new DailyResult(fnId, name, DailyResult.Category.ERROR, "服务端返回错误码 " + code);
        }
    }

    /** 异常分类：会话中断 → 可重试；其余 → ERROR（带消息）。 */
    static DailyResult classifyException(Throwable e, String name) {
        String msg = String.valueOf(e.getMessage());
        for (String mark : SESSION_CLOSED_MARKS) {
            if (msg.contains(mark)) {
                return new DailyResult(name, name, DailyResult.Category.SESSION_CLOSED,
                        "会话中断（可重试）：" + brief(msg));
            }
        }
        return new DailyResult(name, name, DailyResult.Category.ERROR,
                brief(e.getClass().getSimpleName() + ": " + msg));
    }

    static String brief(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    static String descRewards(Object rewards) {
        return RewardText.describe(rewards);
    }

    static int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object v) {
        if (!(v instanceof List)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Object> listOfRaw(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    private DailyTasks() {
    }
}
