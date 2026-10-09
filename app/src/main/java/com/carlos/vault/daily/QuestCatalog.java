package com.carlos.vault.daily;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 游戏每日任务目录：22 组 45 档，questId 与游戏 notify_dailyquests 一一对应。
 * 数据来自原网页版工具 DailyMissionCatalog（1320000-1320211）。
 */
public final class QuestCatalog {

    /** 一档任务：questId + 达成目标 + 活跃度点数。 */
    public static class Tier {
        public final long questId;
        public final int target;
        public final int points;

        Tier(long questId, int target, int points) {
            this.questId = questId;
            this.target = target;
            this.points = points;
        }
    }

    /** 一组任务（同一行为的多档，如 联盟互助 1/5/20）。 */
    public static class Group {
        public final String key;
        public final String name;
        /** 分类标签：城镇 / 野外 / 联盟 */
        public final String tag;
        /** 该组各档相同的奖励文案 */
        public final String reward;
        public final Tier[] tiers;

        Group(String key, String name, String tag, String reward, int[][] tiers) {
            this.key = key;
            this.name = name;
            this.tag = tag;
            this.reward = reward;
            this.tiers = new Tier[tiers.length];
            for (int i = 0; i < tiers.length; i++) {
                this.tiers[i] = new Tier(tiers[i][0], tiers[i][1], tiers[i][2]);
            }
        }

        public int totalPoints() {
            int s = 0;
            for (Tier t : tiers) s += t.points;
            return s;
        }
    }

    private static final List<Group> GROUPS = new ArrayList<>();

    static {
        add("daily_login", "每日登录", "城镇", "肉 4,000；煤 800；铁 200",
                new int[][]{{1320000, 1, 10}});
        add("alliance_help", "联盟互助", "联盟", "肉 4,000",
                new int[][]{{1320010, 1, 5}, {1320011, 5, 10}, {1320012, 20, 15}});
        add("alliance_donation", "联盟捐献", "联盟", "木 4,000",
                new int[][]{{1320020, 5, 5}, {1320021, 20, 10}, {1320022, 40, 15}});
        add("hero_recruit", "英雄招募", "城镇", "煤 800；铁 200",
                new int[][]{{1320030, 1, 10}, {1320031, 3, 15}});
        add("train_infantry", "训练盾兵", "城镇", "肉 4,000；木 4,000",
                new int[][]{{1320040, 10, 10}, {1320041, 30, 10}});
        add("train_lancer", "训练矛兵", "城镇", "肉 4,000；木 4,000",
                new int[][]{{1320050, 10, 10}, {1320051, 30, 10}});
        add("train_marksman", "训练射手", "城镇", "肉 4,000；木 4,000",
                new int[][]{{1320060, 10, 10}, {1320061, 30, 10}});
        add("intelligence", "处理情报线索", "野外", "煤 800；铁 200",
                new int[][]{{1320070, 1, 10}, {1320071, 5, 15}, {1320072, 10, 30}});
        add("warehouse_supply", "仓库领取补给", "城镇", "煤 800；铁 200",
                new int[][]{{1320081, 1, 5}, {1320082, 3, 5}, {1320083, 5, 10}});
        add("heal_soldier", "治疗伤兵", "城镇", "肉 4,000；铁 200",
                new int[][]{{1320090, 10, 10}});
        add("gather_meat", "采集生肉", "野外", "肉 4,000",
                new int[][]{{1320100, 50000, 5}, {1320101, 250000, 5}});
        add("gather_wood", "采集木材", "野外", "木 4,000",
                new int[][]{{1320110, 50000, 5}, {1320111, 250000, 5}});
        add("gather_coal", "采集煤炭", "野外", "煤 800",
                new int[][]{{1320120, 10000, 5}, {1320121, 50000, 5}});
        add("gather_iron", "采集铁", "野外", "铁 200",
                new int[][]{{1320130, 3000, 5}, {1320131, 15000, 5}});
        add("upgrade_building", "升级建筑", "城镇", "煤 800；铁 200",
                new int[][]{{1320140, 1, 30}});
        add("research_technology", "研究科技", "城镇", "煤 800；铁 200",
                new int[][]{{1320150, 1, 30}});
        add("complete_gather", "完成采集", "野外", "肉 4,000；木 4,000",
                new int[][]{{1320160, 1, 5}, {1320161, 2, 5}, {1320162, 4, 15}});
        add("defeat_behemoth", "击败冰原巨兽", "野外", "煤 800",
                new int[][]{{1320170, 1, 10}, {1320171, 2, 10}, {1320172, 3, 10}});
        add("arena_challenge", "竞技场挑战", "野外", "木 4,000；铁 200",
                new int[][]{{1320190, 1, 10}, {1320191, 5, 25}});
        add("successful_share", "成功分享", "城镇", "肉 4,000；木 4,000",
                new int[][]{{1320192, 1, 10}});
        add("underground_challenge", "地心探险挑战", "城镇", "煤 800；铁 200",
                new int[][]{{1320200, 1, 15}});
        add("pet_adventure", "派遣宠物寻宝", "城镇", "肉 4,000；木 4,000",
                new int[][]{{1320210, 1, 5}, {1320211, 4, 10}});
    }

    private static void add(String key, String name, String tag, String reward, int[][] tiers) {
        GROUPS.add(new Group(key, name, tag, reward, tiers));
    }

    public static List<Group> all() {
        return Collections.unmodifiableList(GROUPS);
    }
}
