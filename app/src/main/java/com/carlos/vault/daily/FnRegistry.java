package com.carlos.vault.daily;

import java.util.ArrayList;
import java.util.List;

/**
 * 功能总表：七大功能栏（与原网页版归属一致）。
 * implemented=false 的为规划中（下一批搬），总揽页置灰显示"待开放"。
 */
public final class FnRegistry {

    public static final int CAT_REWARD = 0;     // 奖励：仅领取
    public static final int CAT_TOWN = 1;       // 城镇
    public static final int CAT_ACTIVITY = 2;   // 活动
    public static final int CAT_FIELD = 3;      // 野外
    public static final int CAT_ALLIANCE = 4;   // 联盟
    public static final int CAT_SHOP = 5;       // 商店
    public static final int CAT_OTHER = 6;      // 其他

    public static final String[] CAT_NAMES = {"奖励", "城镇", "活动", "野外", "联盟", "商店", "其他"};

    public static class Fn {
        public final String id;
        public final String name;
        public final int cat;
        public final boolean implemented;
        public final String desc;

        public Fn(String id, String name, int cat, boolean implemented, String desc) {
            this.id = id;
            this.name = name;
            this.cat = cat;
            this.implemented = implemented;
            this.desc = desc;
        }
    }

    public static List<Fn> all() {
        List<Fn> l = new ArrayList<>();
        // ===== 奖励（仅领取类） =====
        l.add(new Fn("login_rewards", "登录好礼", CAT_REWARD, true, "十四天签到奖励自动领取"));
        l.add(new Fn("mail", "邮件奖励", CAT_REWARD, true, "自动读邮件并批量领取附件"));
        l.add(new Fn("supply", "仓库补给", CAT_REWARD, true, "普通仓库补给+限时体力补给（策略可选）"));
        l.add(new Fn("daily_mission", "每日任务", CAT_REWARD, true, "每日任务与活跃度奖励"));
        l.add(new Fn("chapter_quest", "章节任务", CAT_REWARD, true, "章节奖励+章节任务+成长任务一键领取"));
        l.add(new Fn("adventure", "挂机奖励", CAT_REWARD, true, "挂机产量到期自动领取"));
        l.add(new Fn("activity_rewards", "活动奖励", CAT_REWARD, true, "月卡经验/战令/转盘等17种免费奖励"));
        l.add(new Fn("commander_gift", "统帅礼包", CAT_REWARD, true, "每日免费统帅礼包"));
        l.add(new Fn("act_endless", "无尽征程", CAT_REWARD, true, "无尽征程里程碑奖励一键领取"));

        // ===== 城镇 =====
        l.add(new Fn("town_affairs", "城镇管理", CAT_TOWN, true, "免费招募+收兵+专家技能"));
        l.add(new Fn("medic", "军医所", CAT_TOWN, true, "自动治疗伤兵+联盟求助"));
        l.add(new Fn("barracks", "兵营训练", CAT_TOWN, true, "收兵+按现有最高兵种续训一批"));
        l.add(new Fn("research", "科技研究", CAT_TOWN, true, "按路线自动研究（休闲/战狂可选，设置页切换）"));
        l.add(new Fn("building", "建筑升级", CAT_TOWN, true, "按前置最低自动升级（每轮上限可配）"));
        l.add(new Fn("pet", "宠物", CAT_TOWN, true, "宠物技能+寻宝领奖/派遣+联盟宝箱"));
        l.add(new Fn("expert", "专家游历", CAT_TOWN, true, "领行动力+全额挂机游历"));
        l.add(new Fn("homeland", "晨曦岛", CAT_TOWN, true, "回礼/生命之树/伐木小屋/加工厂"));
        l.add(new Fn("fire_crystal", "火晶提炼", CAT_TOWN, true, "普通提炼至每日上限"));
        l.add(new Fn("climbtower", "地心探索", CAT_TOWN, true, "按星期扫荡今日开放塔"));
        l.add(new Fn("arena", "竞技场", CAT_TOWN, true, "挑战最弱对手×5+每日奖励"));

        // ===== 活动 =====
        l.add(new Fn("act_balloon", "正中靶心", CAT_ACTIVITY, true, "签到/兑换/免费局自动射12靶"));
        l.add(new Fn("act_pitchpot", "壶纳千祥", CAT_ACTIVITY, true, "开局→满分结算→任务奖励"));
        l.add(new Fn("act_snowclean", "除雪", CAT_ACTIVITY, true, "任务/基金奖励+极速除雪"));
        l.add(new Fn("act_icebound", "冰封宝藏", CAT_ACTIVITY, true, "特训奖励+积分档位+开宝箱"));
        l.add(new Fn("act_polar", "逐光之旅", CAT_ACTIVITY, true, "队列派遣与宝箱领取"));
        l.add(new Fn("act_flamefangs", "烈焰獠牙", CAT_ACTIVITY, true, "兑换期按勾选清单自动兑换"));
        l.add(new Fn("act_brawl", "欢乐乱斗", CAT_ACTIVITY, true, "成就奖励领取（战斗部分待开放）"));
        l.add(new Fn("act_frostfire", "燃霜矿区", CAT_ACTIVITY, true, "按时段自动报名+里程碑领取（占矿战斗下一版）"));
        l.add(new Fn("act_fishing", "钓鱼", CAT_ACTIVITY, true, "免费礼/日常/任务/俱乐趣奖励"));
        l.add(new Fn("act_joy", "怪攻守城", CAT_ACTIVITY, true, "怪攻守城监测（自动驻防待盟友配置）"));
        l.add(new Fn("act_feast", "至尊盛宴", CAT_ACTIVITY, true, "开席自动搜桌赴宴"));
        l.add(new Fn("act_mercenary", "佣兵荣耀", CAT_ACTIVITY, true, "选难度+生成佣兵+出征"));
        l.add(new Fn("act_truck", "运宝车队", CAT_ACTIVITY, true, "领送达奖励+自动发车"));
        l.add(new Fn("act_icehunter", "冰原猎人", CAT_ACTIVITY, true, "进度奖励+生成BOSS+集结"));

        // ===== 野外 =====
        l.add(new Fn("gather", "野外采集", CAT_FIELD, true, "搜索资源点派出空闲队列"));
        l.add(new Fn("intel", "情报任务", CAT_FIELD, true, "处理待办情报事件并领奖"));
        l.add(new Fn("behemoth", "巨兽狩猎", CAT_FIELD, true, "开启原生自动上车(巨兽/死神/巨熊)"));
        l.add(new Fn("bear", "巨熊行动", CAT_FIELD, true, "陷阱预约同步（上车由自动上车承接）"));
        l.add(new Fn("hunt", "自动狩猎", CAT_FIELD, true, "搜索野怪自动出征×8"));
        l.add(new Fn("battle_shield", "战斗护盾", CAT_FIELD, true, "每日免费护盾+反应式开盾"));

        // ===== 联盟 =====
        l.add(new Fn("alliance_chest", "联盟宝箱", CAT_ALLIANCE, true, "批量领取联盟宝箱"));
        l.add(new Fn("alliance_donate", "联盟捐献", CAT_ALLIANCE, true, "科技捐献+发展/排名奖励"));
        l.add(new Fn("alliance_mobilization", "联盟动员", CAT_ALLIANCE, true, "任务奖励+免费次数+里程碑"));
        l.add(new Fn("alliance_showdown", "争霸赛", CAT_ALLIANCE, true, "未报名时自动报名赛道1"));
        l.add(new Fn("alliance_help", "联盟互助", CAT_ALLIANCE, true, "一键帮助全盟（随每次运行执行）"));

        // ===== 商店 =====
        l.add(new Fn("shop", "商店购买", CAT_SHOP, true, "勾选商品后按限购自动购买（默认不勾）"));
        l.add(new Fn("alliance_shop", "联盟商店", CAT_SHOP, true, "联盟日/周商店勾选购买"));

        // ===== 其他 =====
        l.add(new Fn("red_packet", "游戏红包", CAT_OTHER, true, "联盟/世界频道红包刷新与抢领"));
        l.add(new Fn("bank", "钻石银行", CAT_OTHER, true, "到期自动取款+原额续存"));
        l.add(new Fn("backpack", "背包道具", CAT_OTHER, true, "批量开启固定资源包"));
        l.add(new Fn("social_gift", "社交礼物", CAT_OTHER, true, "按FID清单自动赠送所选礼物"));
        return l;
    }

    public static List<Fn> byCategory(int cat) {
        List<Fn> out = new ArrayList<>();
        for (Fn f : all()) {
            if (f.cat == cat) out.add(f);
        }
        return out;
    }

    public static Fn byId(String id) {
        for (Fn f : all()) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }

    private FnRegistry() {
    }
}
