package com.carlos.vault.daily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 功能子项目录（对照网页版配置页）：每个功能展开后的子开关/说明/策略选项/状态展示键。
 * 状态行文案由 DailyRunner 各采集方法写入 daily_state 的 "mod:fpid:sid:fnId" JSON（子id→文案）。
 */
public final class FnDetail {

    public static class Sub {
        public final String id, name, desc;
        public final boolean defOn;
        /** 策略选项（单选）：{值, 文案}；空=无策略 */
        public final String[][] strategies;

        public Sub(String id, String name, String desc, boolean defOn, String[][] strategies) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.defOn = defOn;
            this.strategies = strategies;
        }
    }

    private static final Map<String, List<Sub>> ALL = new LinkedHashMap<>();

    private static void fn(String fnId, Sub... subs) {
        List<Sub> l = new ArrayList<>();
        for (Sub s : subs) l.add(s);
        ALL.put(fnId, l);
    }

    static {
        // ===== 奖励 =====
        fn("login_rewards",
                new Sub("sign", "7天签到", "登录好礼的一键补领（错过日期可追溯）", true, null),
                new Sub("seven", "七天签到", "七日签到一键领取（未领天数自动补，协议盘点2026-10-12新增）", true, null),
                new Sub("day21", "21天签到", "21天签到免费档一键领取（协议盘点2026-10-12新增）", true, null));
        fn("mail",
                new Sub("read", "读信领附件", "自动读取所选板块邮件并批量领取附件（单批上限100封）", true,
                        new String[][]{{"all", "全部板块"}, {"sys", "仅系统"}, {"sys_ally", "系统+联盟"}, {"no_battle", "除战报"}}),
                new Sub("keep_unread", "仅领有附件邮件", "无附件的公告类邮件不动，避免标记已读", true, null));
        fn("supply",
                new Sub("online", "普通仓库补给", "服务端倒计时结束或刷新通知到达后立即领取", true,
                        new String[][]{{"now", "刷新即领取"}}),
                new Sub("spec", "限时体力补给", "午间/晚间限时窗口的体力补给，按所选策略在窗口内领取", true,
                        new String[][]{{"now", "刷新即领取"}, {"end30", "截止前30分钟"}, {"end60", "截止前60分钟"}, {"custom", "自定义(分钟)"}}));
        fn("daily_mission",
                new Sub("claim", "批量领奖", "把已达成的每日任务奖励一次性领取（含活跃度宝箱到档）", true, null));
        fn("chapter_quest",
                new Sub("chapter", "章节奖励", "当前章节（含上一章兜底）的章节奖励领取", true, null),
                new Sub("chapter_quest", "章节任务", "notify_chapterquests 中列表任务逐个领取", true, null),
                new Sub("growth_quest", "成长任务", "notify_growthquests 中列表任务逐个领取", true, null));
        fn("adventure",
                new Sub("hang_up", "挂机产量", "探险挂机奖励到期自动领取（约10分钟一领）", true,
                        new String[][]{{"0", "总是领取"}, {"300", "≥5分钟"}, {"600", "≥10分钟"}, {"1800", "≥30分钟"}}));
        fn("activity_rewards",
                new Sub("month_card", "月卡每日", "月卡每日奖励", true, null),
                new Sub("vip_exp", "VIP每日经验", "VIP等级每日经验", true, null),
                new Sub("bundle", "每日特惠礼包", "每日特惠免费礼包", true, null),
                new Sub("treasures", "宝藏/许愿/宝箱类", "失落宝藏·许愿屋·米娅·无限宝箱等免费奖", true, null),
                new Sub("battle_pass", "战令免费档", "英雄集结战令免费奖励批量领取", true, null));
        fn("commander_gift",
                new Sub("gift", "统帅每日礼包", "当前统帅等级的每日免费礼包", true, null));
        fn("act_endless",
                new Sub("milestone", "里程碑奖励", "无尽征程达到档位的一键领取", true, null));
        fn("achievement_claim",
                new Sub("claim", "批量领取", "已完成未领取的成就奖励批量领取（notify_achievement_info：complete且未received）", true, null));

        // ===== 城镇 =====
        fn("town_affairs",
                new Sub("recruit_free", "免费英雄招募", "普通招募免费次数用完为止（每天5次）", true, null),
                new Sub("recruit_epic", "史诗招募", "48小时一次的免费史诗招募", true, null),
                new Sub("expert_token", "罗慕路斯·忠诚令牌", "领取募兵所忠诚令牌（专家技能，每日一次）", true, null),
                new Sub("expert_soldier", "罗慕路斯·每日士兵", "领取每日兵营士兵（选择兵营：默认盾兵营）", true,
                        new String[][]{{"321", "盾兵营"}, {"322", "矛兵营"}, {"323", "弓兵营"}}));
        fn("medic",
                new Sub("heal", "治疗伤兵", "高等级优先批量治疗伤兵（联盟互助已收敛到联盟模块）", true, null));
        fn("barracks",
                new Sub("collect_shield", "盾兵营收兵", "收回盾兵营已训练完毕的士兵", true, null),
                new Sub("collect_spear", "矛兵营收兵", "收回矛兵营已训练完毕的士兵", true, null),
                new Sub("collect_bow", "弓兵营收兵", "收回弓兵营已训练完毕的士兵", true, null),
                new Sub("train_shield", "盾兵训练", "盾兵营续训；等级下拉只列该营已解锁档，默认最高", true, null),
                new Sub("train_spear", "矛兵训练", "矛兵营续训；等级下拉只列该营已解锁档，默认最高", true, null),
                new Sub("train_bow", "弓兵训练", "弓兵营续训；等级下拉只列该营已解锁档，默认最高", true, null));
        fn("research",
                new Sub("auto", "路线自动研究", "按路线（休闲/战狂）自动补前置并研究下一项", true, null),
                new Sub("help", "研究联盟求助", "开始研究后向联盟求助加速", true, null));
        fn("building",
                new Sub("auto", "前置最低自动升级", "沿前置链找最底层被依赖建筑先升，每轮上限可配", true, null),
                new Sub("help", "建造联盟求助", "开工后向联盟求助加速", true, null));
        fn("pet",
                new Sub("skills", "战斗技能", "批量开启可用宠物的战斗技能（冷却自动跳过）", true, null),
                new Sub("adventure", "寻宝领奖/派遣", "领取已完成区域并派遣空闲宠物", true, null),
                new Sub("alliance_chest", "联盟寻宝宝箱", "批量领取盟友分享的寻宝宝箱（每日5个）", true, null));
        fn("expert",
                new Sub("energy", "领取行动力", "游历行动力每日领取", true, null),
                new Sub("afk", "全额挂机游历", "把当前行动力全部挂机产出并确认", true,
                        new String[][]{{"0", "用全部"}, {"20", "保留20"}, {"60", "保留60"}, {"100", "保留100"}}),
                new Sub("buy", "钻石购体力", "行动力不足时补购（每次100钻，日限4次，默认关）", false, null));
        fn("homeland",
                new Sub("free", "晨曦回礼", "家园免费产量领取", true, null),
                new Sub("tree", "生命之树", "生命之树金币收取", true, null),
                new Sub("logging", "伐木小屋/加工厂", "按家园通知坐标收取木材产物", true, null));
        fn("fire_crystal",
                new Sub("purify", "普通提炼", "普通火晶提炼至每日上限", true, null),
                new Sub("super", "超级提炼", "开关打开后每轮超炼1次（可在参数 super_times 配次数，消耗火晶）", false, null),
                new Sub("quests", "悬赏任务", "火晶悬赏任务奖励批量领取（notify_firecrystal_quests）", true, null));
        fn("climbtower",
                new Sub("sweep", "每日扫荡", "按星期自动扫荡今日开放的地心之塔", true, null));
        fn("arena",
                new Sub("fight", "自动挑战", "从对手列表选战力最低者挑战", true,
                        new String[][]{{"3", "挑战3场"}, {"5", "挑战5场"}, {"8", "挑战8场"}, {"10", "挑战10场"}}),
                new Sub("daily", "每日奖励", "挑战次数达标后的每日奖励", true, null));

        // ===== 活动 =====
        fn("act_balloon",
                new Sub("sign", "签到奖励", "正中靶心活动签到补领", true, null),
                new Sub("exchange", "积分兑换", "按累计积分自动兑换达到的档位", true, null),
                new Sub("free", "免费局", "只用免费次数开局并顺序射12靶（不耗神枪券）", true, null));
        fn("act_pitchpot",
                new Sub("play", "开局+满分结算", "开局→15秒→提交满分结算（4650分）", true, null),
                new Sub("task", "任务奖励", "进度达标的投壶任务奖励", true, null));
        fn("act_snowclean",
                new Sub("task", "每日任务", "除雪小分队每日任务奖励", true, null),
                new Sub("fund", "冰雪基金", "冰雪基金奖励领取", true, null),
                new Sub("sweep", "极速除雪", "当前关连扫直到燃油耗尽/通关", true, null));
        fn("act_icebound",
                new Sub("training", "寻宝特训", "特训任务/档位/额外奖励", true, null),
                new Sub("chest", "宝藏积分与宝箱", "积分达档领奖+开启未开宝箱", true, null));
        fn("act_polar",
                new Sub("chest", "队列宝箱", "探索完成的队列立即领箱", true, null),
                new Sub("start", "派遣队列", "空闲队列自动派遣探索", true, null));
        fn("act_brawl",
                new Sub("achievement", "成就奖励", "大乱斗成就奖励领取", true, null));
        fn("act_fishing",
                new Sub("free", "免费礼/每日", "钓鱼免费礼物+每日奖励", true, null),
                new Sub("task", "任务/俱乐趣", "任务奖励与俱乐趣(冰钓俱乐部)奖励", true, null));
        fn("act_joy",
                new Sub("watch", "怪攻监测", "怪物攻城活动监测与提醒（自动驻防待盟友配置）", true, null));
        fn("act_feast",
                new Sub("eat", "自动赴宴", "开席阶段自动搜桌并发起赴宴行军", true, null));
        fn("act_mercenary",
                new Sub("difficulty", "选难度", "自动选择下一档难度（上限20）", true, null),
                new Sub("spawn", "生成佣兵", "生成个人佣兵怪并单刷出征", true, null));
        fn("act_truck",
                new Sub("take", "领送达奖励", "已送达货车的奖励自动领取", true, null),
                new Sub("send", "自动发车", "用一名英雄自动发出货车（劫掠他人默认关）", true, null));
        fn("act_icehunter",
                new Sub("reward", "进度奖励", "冰原猎人档位奖励一键领取", true, null),
                new Sub("boss", "生成并集结BOSS", "消耗零件生成BOSS并发起集结（需25体力）", true, null));
        fn("red_packet",
                new Sub("alliance", "联盟频道", "联盟频道红包刷新与抢领（每日配额内）", true, null),
                new Sub("world", "世界频道", "世界频道红包刷新与抢领", true, null));
        fn("bank",
                new Sub("withdraw", "到期取款", "存款到期自动取出", true, null),
                new Sub("redeposit", "原额续存", "取款后按原柜型原额续存（不新增钻石）", true, null));

        // ===== 野外 =====
        fn("gather",
                new Sub("send", "自动采集", "搜索资源点并派出空闲队列（资源/等级/队列数见下方策略）", true,
                        new String[][]{{"any", "任意存量"}, {"full", "只采满矿"}}));
        fn("adventure_sweep",
                new Sub("sweep", "速刷关数", "已通关卡用快速战斗逐关推进；新关卡需手动战斗解锁一次", true,
                        new String[][]{{"5", "最多5关"}, {"10", "最多10关"}, {"20", "最多20关"}, {"50", "最多50关"}}));
        fn("intel",
                new Sub("events", "处理情报", "待办情报事件自动出征/快速战并领奖（优先于采集队列）", true, null));
        fn("behemoth",
                new Sub("auto_join", "原生自动上车", "开启游戏自带的自动加入集结（巨兽/死神/巨熊）", true, null));
        fn("bear",
                new Sub("trap", "巨熊陷阱同步", "同步联盟巨熊陷阱预约状态（周五窗口发车由自动上车承接）", true, null));
        fn("hunt",
                new Sub("hunt", "自动狩猎", "按等级区间搜索野怪自动出征（体力不足自动停）", true, null));
        fn("battle_shield",
                new Sub("free", "每日免费护盾", "每日8小时免费护盾（被袭击时生效）", true, null),
                new Sub("reactive", "反应式开盾", "收到战争/侦查通知时立即登录开盾（需通知使用权）", true, null));

        // ===== 联盟 =====
        fn("guardian",
                new Sub("watch", "敌情监测（dry_run）", "监测指向本盟盟友城的侦查/进攻行军并告警（只看不发）", true, null),
                new Sub("intercept", "自动卡秒驻防", "检测到威胁后自动向盟友城卡秒驻防（P3 接线，当前标记）", false, null),
                new Sub("power_min", "攻方战力阈值", "低于该战力的进攻仅观察不告警", true,
                        new String[][]{{"0", "不限"}, {"30000", "3万"}, {"50000", "5万"}, {"100000", "10万"}}),
                new Sub("eta_max", "ETA 告警窗口", "到达时间超过该窗口的行军仅观察", true,
                        new String[][]{{"300", "5分钟"}, {"600", "10分钟"}, {"1800", "30分钟"}, {"3600", "1小时"}}));
        fn("alliance_chest",
                new Sub("chest", "联盟宝箱", "批量领取可领的联盟宝箱", true, null));
        fn("alliance_donate",
                new Sub("science", "免费科技捐献", "每日免费科技捐献", true, null),
                new Sub("dev", "发展/排名奖励", "每日发展+每周发展+联盟排名奖励", true, null));
        fn("alliance_mobilization",
                new Sub("reward", "任务奖励", "已完成的总动员任务自动领奖", true, null),
                new Sub("count", "免费接取次数", "领取每日免费接取次数", true, null),
                new Sub("milestone", "里程碑", "批量领取里程碑奖励", true, null));
        fn("alliance_showdown",
                new Sub("apply", "自动报名", "未报名时按配置编队报名（默认赛道1）", true, null));
        fn("alliance_help",
                new Sub("help_all", "一键帮助全盟", "定时兜底：常驻互助（今日页卡/服务器）接管时自动跳过", true, null));
        fn("homeland_visit",
                new Sub("assist", "拜访协助", "拉联盟活跃榜→逐个拜访活跃盟友晨曦岛并协助（今日<3次且过6小时冷却）", true, null));

        // ===== 商店/其他 =====
        fn("shop",
                new Sub("buy", "按清单购买", "先选店铺勾选商品，运行时按限购与币种余额自动购买", true, null),
                new Sub("refresh_diamond", "钻石刷新货架", "流动商人免费刷新(3次)用完后，继续用钻石刷新直到刷出勾选商品；策略=最多钻石刷新次数", false,
                        new String[][]{{"0", "关闭"}, {"5", "再刷5次"}, {"10", "再刷10次"}, {"20", "再刷20次"}}));
        fn("alliance_shop",
                new Sub("buy", "联盟日/周商店", "联盟币商品按勾选清单购买", true, null));
        fn("act_flamefangs",
                new Sub("buy", "兑换期抢购", "活动兑换阶段按勾选档位自动兑换", true, null));
        fn("act_frostfire",
                new Sub("apply", "按时段报名", "每日自动报名所选时段的燃霜矿区战场", true, null),
                new Sub("milestone", "里程碑奖励", "战场内里程碑奖励领取+积分上报", true, null));
        fn("act_snowtrade",
                new Sub("free", "免费兑换", "雪原贸易站免费档自动兑换（ve_info.is_free_exchange 且该档未兑时）", true, null),
                new Sub("buy", "按清单兑换", "勾选档位自动兑换（已兑部分自动扣减，超出限购由服务端拒绝；档位目录随最近快照自适应）", false, null));
        fn("backpack",
                new Sub("fixed", "固定资源包", "批量开启100/1k/1w/10w/100w档固定资源包", true, null),
                new Sub("random", "随机补给箱", "批量开启随机补给箱（默认关，收益随机）", false, null));
        fn("social_gift",
                new Sub("send", "按FID赠送", "把所选礼物按FID清单自动查询并赠送", true, null));
    }

    public static List<Sub> of(String fnId) {
        List<Sub> l = ALL.get(fnId);
        return l == null ? new ArrayList<>() : l;
    }

    private FnDetail() {
    }
}
