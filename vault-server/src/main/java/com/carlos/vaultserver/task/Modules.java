package com.carlos.vaultserver.task;

import com.carlos.vaultserver.automation.ActionDefinition;
import com.carlos.vaultserver.automation.AutomationModule;
import com.carlos.vaultserver.automation.ModuleCategory;
import com.carlos.vaultserver.automation.ModuleRegistry;
import com.carlos.vaultserver.automation.services.MedicService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 领域模块装配。
 *
 * <p>当前策略：8 个域模块覆盖需求中的核心域；未纳入域任务由 LegacyModule 承接，
 * 保证 55 项任务不丢失。模块只做编排，协议实现继续复用 ServerTasks 系列，
 * 后续可逐个把执行器迁入模块内部而不改接口。
 *
 * <p>动作默认开关唯一事实源 = {@link ServerTasks} 任务目录（enabledByDefault）；
 * 域模块不硬编码 true/false，否则 roleConfig 中未出现的动作会改变旧 RoundRunner 的
 * 默认语义（G1：bank/gather/intel/hunt/shop/alliance_shop 目录默认 false 却被默认执行）。
 */
public final class Modules {

    private Modules() {
    }

    public static ModuleRegistry registry() {
        ModuleRegistry r = new ModuleRegistry();
        List<AutomationModule> domain = new ArrayList<>();
        domain.add(reward());
        domain.add(alliance());
        domain.add(bank());
        domain.add(gather());
        domain.add(pet());
        domain.add(shop());
        domain.add(medic());
        domain.add(wilderness());

        Set<String> assigned = new LinkedHashSet<>();
        for (AutomationModule m : domain) {
            for (ActionDefinition a : m.actions()) assigned.add(a.id);
            r.register(m);
        }
        r.register(legacy(assigned));
        return r;
    }

    public static AutomationModule reward() {
        return new SimpleModule("reward", "奖励领取", ModuleCategory.REWARD,
                actions(
                        action("login_rewards", "登录好礼", ServerTasks::loginRewards),
                        action("mail", "邮件奖励", ServerTasks::mailRewards),
                        action("supply", "仓库补给", ServerTasks::supplyRewards),
                        action("daily_mission", "每日任务", ServerTasks::dailyMission),
                        action("chapter_quest", "章节任务", ServerTasks::chapterQuest),
                        action("adventure", "挂机奖励", ServerTasks::adventureReward),
                        action("activity_rewards", "活动奖励", ServerTasks::activityRewards),
                        action("commander_gift", "统帅礼包", ServerTasks::commanderGift),
                        action("act_endless", "无尽征程", ServerActivityTasks::endlessJourney),
                        action("achievement_claim", "成就奖励", ServerTasks::achievementClaim)
                ));
    }

    public static AutomationModule alliance() {
        return new SimpleModule("alliance", "联盟系统", ModuleCategory.ALLIANCE,
                actions(
                        action("alliance_chest", "战利品宝箱", ServerTasks::allianceChest),
                        action("alliance_donate", "联盟科技捐献", ServerTasks::allianceDonate)
                ));
    }

    public static AutomationModule bank() {
        return new SimpleModule("bank", "钻石银行", ModuleCategory.BANK,
                actions(action("bank", "24小时续存", ServerActivityTasks::bank)));
    }

    public static AutomationModule gather() {
        return new SimpleModule("gather", "野外采集", ModuleCategory.GATHER,
                actions(action("gather", "持续采集", ServerFieldTasks::gather)));
    }

    public static AutomationModule pet() {
        return new SimpleModule("pet", "宠物系统", ModuleCategory.PET,
                actions(action("pet", "宠物技能/寻宝", ServerTownTasks::pet)));
    }

    public static AutomationModule shop() {
        return new SimpleModule("shop", "商店系统", ModuleCategory.SHOP,
                actions(
                        action("shop", "普通商店", ServerShopLeagueTasks::shopBuy),
                        action("alliance_shop", "联盟商店", ServerShopLeagueTasks::allianceShop)
                ));
    }

    public static AutomationModule medic() {
        return new SimpleModule("medic", "军医所", ModuleCategory.MEDIC,
                actions(action("medic", "伤兵统计/治疗/援助",
                        tc -> {
                            var p = tc.params("medic");
                            return new MedicService().heal(tc,
                                    p.optInt("heal_count", 0),
                                    p.optBoolean("immediate", false),
                                    p.optBoolean("request_help", true));
                        })));
    }

    public static AutomationModule wilderness() {
        return new SimpleModule("wilderness", "野外/情报", ModuleCategory.WILDERNESS,
                actions(
                        action("intel", "情报任务", ServerFieldTasks::intel),
                        action("behemoth", "巨兽狩猎", ServerTasks::behemoth),
                        action("bear", "巨熊行动", ServerTasks::bear),
                        action("hunt", "自动狩猎", ServerFieldTasks::hunt)
                ));
    }

    /** 未纳入域模块的任务：保持旧行为，避免覆盖缺口。 */
    public static AutomationModule legacy(Set<String> assignedActionIds) {
        Set<String> assigned = assignedActionIds == null
                ? java.util.Collections.emptySet() : assignedActionIds;
        List<ActionDefinition> left = new ArrayList<>();
        for (ServerTasks.TaskDef def : ServerTasks.all()) {
            if (assigned.contains(def.id)) continue;
            left.add(action(def.id, def.name, def.executor::run));
        }
        return new SimpleModule("legacy", "其他任务", ModuleCategory.LEGACY,
                left.toArray(new ActionDefinition[0]));
    }

    /**
     * 动作默认开关一律取 ServerTasks 任务目录（唯一事实源）。
     * 域模块不得再硬编码 true：否则 roleConfig 中未出现的动作会改变旧 RoundRunner 语义
     * （G1：bank/gather/intel/hunt/shop/alliance_shop 目录默认 false 却被默认执行）。
     */
    private static ActionDefinition action(String id, String name,
                                           ActionDefinition.Executor executor) {
        ServerTasks.TaskDef def = ServerTasks.byId(id);
        boolean enabledByDefault = def != null && def.enabledByDefault;
        return new ActionDefinition(id, name, enabledByDefault, executor);
    }

    private static ActionDefinition[] actions(ActionDefinition... actions) {
        return actions;
    }

    private static final class SimpleModule implements AutomationModule {
        private final String id;
        private final String name;
        private final ModuleCategory category;
        private final List<ActionDefinition> actions = new ArrayList<>();

        SimpleModule(String id, String name, ModuleCategory category,
                     ActionDefinition[] actions) {
            this.id = id;
            this.name = name;
            this.category = category;
            if (actions != null) {
                for (ActionDefinition a : actions) if (a != null) this.actions.add(a);
            }
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return name; }
        @Override public ModuleCategory category() { return category; }
        @Override public List<ActionDefinition> actions() { return actions; }
    }
}
