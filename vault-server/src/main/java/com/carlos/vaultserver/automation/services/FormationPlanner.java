package com.carlos.vaultserver.automation.services;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编队规划服务：采集/打野兽/巨兽集结共用。
 *
 * <p>关键原则：
 * - 兵量按“采集目标 / 单兵载重 × 英雄加成 × 安全系数”动态计算，不再固定 40/30/30；
 * - 英雄按业务选择器（吉娜/波根/最强盾兵）解析，id 由 HeroDirectory 提供；
 * - 方案可配置（资源类型、目标量、缓冲、上限、是否满兵）。
 */
public interface FormationPlanner {

    enum Scheme { GATHER, BEAST, BEHEMOTH_RALLY }

    enum Resource { MEAT, WOOD, COAL, IRON }

    final class HeroSelectors {
        public String shooter = "吉娜";
        public String spearman = "波根";
        public String shield = "strongest_shield";
    }

    final class Request {
        public final Scheme scheme;
        public final Resource resource;
        public final long targetAmount;
        public final HeroSelectors heroes;
        public final boolean fullTroops;
        /** 当前账号已拥有的英雄 id；用于 strongest_shield 选择器。 */
        public final java.util.List<Long> ownedHeroIds;

        public Request(Scheme scheme, Resource resource, long targetAmount,
                       HeroSelectors heroes, boolean fullTroops) {
            this(scheme, resource, targetAmount, heroes, fullTroops,
                    java.util.Collections.emptyList());
        }

        public Request(Scheme scheme, Resource resource, long targetAmount,
                       HeroSelectors heroes, boolean fullTroops,
                       java.util.List<Long> ownedHeroIds) {
            this.scheme = scheme;
            this.resource = resource;
            this.targetAmount = targetAmount;
            this.heroes = heroes == null ? new HeroSelectors() : heroes;
            this.fullTroops = fullTroops;
            this.ownedHeroIds = ownedHeroIds == null
                    ? java.util.Collections.emptyList() : ownedHeroIds;
        }
    }

    /** 可用兵力：key=兵种 id（1xxx 盾 / 2xxx 矛 / 3xxx 弓），value=数量。 */
    final class TroopPool {
        public final Map<Long, Long> byType = new LinkedHashMap<>();

        public TroopPool put(long soldierId, long count) {
            if (soldierId > 0 && count > 0) byType.put(soldierId, count);
            return this;
        }

        public long total() {
            long n = 0;
            for (long v : byType.values()) n += v;
            return n;
        }

        public long byTier(int tier) {
            long n = 0;
            for (Map.Entry<Long, Long> e : byType.entrySet()) {
                if (e.getKey() / 10000 == tier) n += e.getValue();
            }
            return n;
        }
    }

    final class Config {
        public long carryPerTroop = 100;       // 单兵载重（游戏实际值可配置）
        public double heroCarryBonus = 0.30;   // 采集英雄加成
        public double bufferPct = 5.0;         // 安全系数 %
        public int minTroops = 1000;
        public int maxTroops = 300000;
        public double shieldRatio = 0.40;
        public double spearRatio = 0.30;
        public double bowRatio = 0.30;
        public Map<Resource, Long> targetAmounts = new LinkedHashMap<>();
    }

    final class Plan {
        public final Scheme scheme;
        public final Resource resource;
        public final List<Long> heroIds;
        public final Map<Long, Long> soldiers;
        public final long totalSoldiers;
        public final String note;

        public Plan(Scheme scheme, Resource resource, List<Long> heroIds,
                    Map<Long, Long> soldiers, long totalSoldiers, String note) {
            this.scheme = scheme;
            this.resource = resource;
            this.heroIds = heroIds;
            this.soldiers = soldiers;
            this.totalSoldiers = totalSoldiers;
            this.note = note;
        }

        public boolean valid() {
            return !heroIds.isEmpty() && totalSoldiers > 0;
        }
    }

    Plan plan(Request request, TroopPool pool, HeroDirectory heroes, Config config);
}
