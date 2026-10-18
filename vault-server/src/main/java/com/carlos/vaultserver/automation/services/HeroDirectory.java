package com.carlos.vaultserver.automation.services;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 英雄目录：把“吉娜 / 波根 / 当前最强盾兵”这类业务选择器解析成英雄 id。
 *
 * <p>英雄 id 与名称/兵种来自游戏英雄目录（配置或运行时快照），不在代码里硬编码。
 * 解析不到时返回 0，由 FormationPlanner 产出可读失败原因。
 */
public interface HeroDirectory {

    long findByName(String name);

    long strongestShield(Collection<Long> ownedHeroIds);

    default long strongestByPower(Collection<Long> ownedHeroIds) {
        long best = 0;
        long bestPower = -1;
        for (long id : ownedHeroIds) {
            long p = powerOf(id);
            if (p > bestPower) {
                bestPower = p;
                best = id;
            }
        }
        return best;
    }

    /** 0 = 目录未提供该英雄战力。 */
    default long powerOf(long heroId) {
        return 0;
    }

    static HeroDirectory empty() {
        return new HeroDirectory() {
            @Override public long findByName(String name) { return 0; }
            @Override public long strongestShield(Collection<Long> ownedHeroIds) { return 0; }
        };
    }

    /** 配置化目录：id → (name, role, power)。role=shield/spear/shooter。 */
    final class ConfigHeroDirectory implements HeroDirectory {
        private final Map<Long, String> names = new LinkedHashMap<>();
        private final Map<Long, String> roles = new LinkedHashMap<>();
        private final Map<Long, Long> power = new LinkedHashMap<>();

        public ConfigHeroDirectory put(long id, String name, String role, long power) {
            names.put(id, name == null ? "" : name);
            roles.put(id, role == null ? "" : role);
            this.power.put(id, power);
            return this;
        }

        @Override
        public long findByName(String name) {
            if (name == null || name.isEmpty()) return 0;
            for (Map.Entry<Long, String> e : names.entrySet()) {
                if (name.equals(e.getValue())) return e.getKey();
            }
            return 0;
        }

        @Override
        public long strongestShield(Collection<Long> ownedHeroIds) {
            long best = 0;
            long bestPower = -1;
            for (long id : ownedHeroIds) {
                if (!"shield".equalsIgnoreCase(roles.get(id))) continue;
                long p = power.getOrDefault(id, 0L);
                if (p > bestPower) {
                    bestPower = p;
                    best = id;
                }
            }
            return best;
        }

        @Override
        public long powerOf(long heroId) {
            return power.getOrDefault(heroId, 0L);
        }

        public Map<Long, String> roles() {
            return Collections.unmodifiableMap(roles);
        }
    }
}
