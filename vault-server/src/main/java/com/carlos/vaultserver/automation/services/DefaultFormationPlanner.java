package com.carlos.vaultserver.automation.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 默认编队算法：动态兵量 + 业务英雄选择器 + 方案差异。 */
public final class DefaultFormationPlanner implements FormationPlanner {

    @Override
    public Plan plan(Request request, TroopPool pool, HeroDirectory heroes, Config config) {
        Config cfg = config == null ? new Config() : config;
        TroopPool p = pool == null ? new TroopPool() : pool;
        HeroDirectory hd = heroes == null ? HeroDirectory.empty() : heroes;
        List<Long> heroIds = resolveHeroes(request, p, hd);

        if (heroIds.isEmpty()) {
            return new Plan(request.scheme, request.resource, heroIds,
                    new LinkedHashMap<>(), 0,
                    "英雄目录未解析出可用英雄（吉娜/波根/盾兵）");
        }
        long available = p.total();
        if (available <= 0) {
            return new Plan(request.scheme, request.resource, heroIds,
                    new LinkedHashMap<>(), 0, "无可用兵力");
        }

        long required = requiredTroops(request, available, cfg);
        Map<Long, Long> soldiers;
        String note;
        if (request.scheme == Scheme.BEAST && request.fullTroops) {
            soldiers = allSoldiers(p);
            note = "满兵方案（省体力）";
        } else if (request.scheme == Scheme.BEHEMOTH_RALLY) {
            // 集结方案：一个盾兵英雄带队，其余英雄与野兽方案相同；兵力以盾兵为主。
            soldiers = allocate(p, required, 1.0, 0.0, 0.0);
            note = "巨兽集结：盾兵带队";
        } else {
            soldiers = allocate(p, required,
                    cfg.shieldRatio, cfg.spearRatio, cfg.bowRatio);
            note = "动态兵量";
        }
        long total = sum(soldiers);
        String reason = note + " required=" + required + " total=" + total;
        return new Plan(request.scheme, request.resource, heroIds, soldiers, total, reason);
    }

    private static List<Long> resolveHeroes(Request req, TroopPool pool, HeroDirectory hd) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        long shooter = hd.findByName(req.heroes.shooter);
        long spearman = hd.findByName(req.heroes.spearman);
        long shield;
        if ("strongest_shield".equalsIgnoreCase(req.heroes.shield)) {
            // ownedHeroIds 必须是英雄 id，不能拿兵种 id 顶替。
            shield = hd.strongestShield(req.ownedHeroIds);
        } else {
            shield = hd.findByName(req.heroes.shield);
        }
        if (req.scheme == Scheme.BEHEMOTH_RALLY) {
            if (shield > 0) ids.add(shield);
            if (shooter > 0) ids.add(shooter);
            if (spearman > 0) ids.add(spearman);
        } else {
            if (shooter > 0) ids.add(shooter);
            if (spearman > 0) ids.add(spearman);
            if (shield > 0) ids.add(shield);
        }
        return new ArrayList<>(ids);
    }

    private static long requiredTroops(Request req, long available, Config cfg) {
        if (req.scheme == Scheme.BEAST && req.fullTroops) return available;
        long target = req.targetAmount;
        if (target <= 0) {
            Long configured = cfg.targetAmounts.get(req.resource);
            target = configured == null ? 1_000_000L : configured;
        }
        double carry = Math.max(1.0, cfg.carryPerTroop * (1.0 + cfg.heroCarryBonus));
        double raw = target / carry * (1.0 + cfg.bufferPct / 100.0);
        long required = (long) Math.ceil(raw);
        required = Math.max(cfg.minTroops, Math.min(cfg.maxTroops, required));
        return Math.min(available, Math.max(1, required));
    }

    /** 按盾/矛/弓比例把 required 分配到具体兵种 id；不足的份额留在池里。 */
    private static Map<Long, Long> allocate(TroopPool pool, long required,
                                            double shieldRatio, double spearRatio,
                                            double bowRatio) {
        Map<Long, Long> out = new LinkedHashMap<>();
        double[] ratio = {shieldRatio, spearRatio, bowRatio};
        for (int tier = 1; tier <= 3; tier++) {
            long tierAvailable = 0;
            for (Map.Entry<Long, Long> e : pool.byType.entrySet()) {
                if (e.getKey() / 10000 == tier) tierAvailable += e.getValue();
            }
            if (tierAvailable <= 0 || ratio[tier - 1] <= 0) continue;
            long tierWant = Math.min(tierAvailable,
                    Math.round(required * ratio[tier - 1]));
            if (tierWant <= 0) continue;
            // 同一兵种档位内按可用数量比例分配；优先派高等级 id。
            List<Map.Entry<Long, Long>> entries = new ArrayList<>();
            for (Map.Entry<Long, Long> e : pool.byType.entrySet()) {
                if (e.getKey() / 10000 == tier) entries.add(e);
            }
            entries.sort((a, b) -> Long.compare(b.getKey(), a.getKey()));
            long left = tierWant;
            long tierSum = 0;
            for (Map.Entry<Long, Long> e : entries) tierSum += e.getValue();
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<Long, Long> e = entries.get(i);
                long take = i == entries.size() - 1
                        ? left
                        : Math.min(left, Math.round((double) tierWant * e.getValue() / tierSum));
                if (take > 0) {
                    out.put(e.getKey(), take);
                    left -= take;
                }
                if (left <= 0) break;
            }
        }
        return out;
    }

    private static Map<Long, Long> allSoldiers(TroopPool pool) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : pool.byType.entrySet()) {
            if (e.getValue() > 0) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static long sum(Map<Long, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }
}
