package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Cost;
import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Prerequisite;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 建筑升级 DFS 规划器（M4a 任务书 §2.2，原版 wjdr-tool BuildingUpgradePlanner 移植）。
 *
 * <p>算法与 {@code blockedReason} 中文文案逐字对齐原版：前置递归（最大深度 40）、
 * 家具补齐（创建 + 逐级升级合并成本）、经验门槛、资源检查、maxActions 截断、
 * 环形依赖检测。纯函数可测，不依赖 Spring。
 *
 * <p>数据源经 {@link Catalog} 注入：生产装配 {@link BootstrapCatalog}
 * （建筑名称/成本/前置 = TownDevCatalog；needExp/家具 = BuildingGateCatalog），
 * 单测可注入 fake 构造环形依赖等场景。
 */
public final class BootstrapPlanner {

    /** 单轮规划默认动作上限（对齐原版 DEFAULT_MAX_ACTIONS）。 */
    public static final int DEFAULT_MAX_ACTIONS = 8;
    /** 前置递归最大深度（对齐原版 MAX_DEPTH）。 */
    private static final int MAX_DEPTH = 40;

    private final Catalog catalog;

    /** 生产装配：组合目录（TownDevCatalog + BuildingGateCatalog + 318 英雄大厅）。 */
    public BootstrapPlanner() {
        this(BootstrapCatalog.combined());
    }

    /** 注入目录（单测 fake 用）。 */
    public BootstrapPlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    /** 规划一份达成 targets 的动作序列。 */
    public Plan plan(Request request) {
        return new Planner(request).plan();
    }

    /** 规划器数据目录：方法集与原版 BuildingUpgradeCatalog 一致。 */
    public interface Catalog {
        /** 目录是否收录该建筑（门槛建筑/城镇建筑/特殊建筑任一）。 */
        boolean supports(long buildingId);

        /** 建筑最大等级；未知返回 0。 */
        long maxLevel(long buildingId);

        /** 建筑名称；未知返回「建筑 {id}」。 */
        String buildingName(long buildingId);

        /** 升到 {@code level} 级的前置建筑要求。 */
        List<Prerequisite> prerequisites(long buildingId, long level);

        /** 升到 {@code level} 级所需家具经验门槛。 */
        long needExp(long buildingId, long level);

        /** 升到 {@code level} 级的家具门槛（id → 需求等级）。 */
        List<Cost> furnitureRequirement(long buildingId, long level);

        /** 家具处于 {@code level} 级贡献的经验。 */
        long furnitureExp(long furnitureId, long level);

        /** 家具升到 {@code level} 级的成本。 */
        List<Cost> furnitureCosts(long furnitureId, long level);

        /** 家具最大等级。 */
        long furnitureMaxLevel(long furnitureId);

        /** 家具是否参与经验贡献。 */
        boolean furnitureContributesExp(long furnitureId);

        /** 建筑升到 {@code level} 级的资源成本。 */
        List<Cost> buildingCosts(long buildingId, long level);

        /** 城镇建筑清单（id + 名称，towndev 序）；仅供 status 视图遍历，fake 实现可返回空。 */
        default List<TownBuilding> townBuildings() {
            return List.of();
        }

        /** 城镇建筑摘要。 */
        record TownBuilding(long id, String name) {
        }
    }

    private final class Planner {
        private final Request request;
        private final Map<Long, Long> levels = new LinkedHashMap<>();
        private final Map<Long, Map<Long, Long>> furniture = new LinkedHashMap<>();
        private final Set<String> expanded = new LinkedHashSet<>();
        private final Deque<Long> path = new ArrayDeque<>();
        private final List<Action> actions = new ArrayList<>();
        private String blockedReason = "";
        private boolean limitReached;

        private Planner(Request request) {
            Objects.requireNonNull(request);
            this.request = request;
            request.buildingLevels().forEach((id, level) -> {
                if (id != null && id > 0L && level != null) {
                    levels.put(id, Math.max(0L, level));
                }
            });
            request.furnitureLevels().forEach((buildingId, values) -> {
                if (buildingId == null || buildingId <= 0L || values == null) return;
                LinkedHashMap<Long, Long> copy = new LinkedHashMap<>();
                values.forEach((fid, level) -> {
                    if (fid != null && fid > 0L && level != null && level > 0L) {
                        copy.put(fid, level);
                    }
                });
                furniture.put(buildingId, copy);
            });
        }

        private Plan plan() {
            boolean complete = true;
            for (Map.Entry<Long, Long> target : request.targets().entrySet()) {
                Long buildingId = target.getKey();
                Long targetLevel = target.getValue();
                if (buildingId == null || buildingId <= 0L || targetLevel == null || targetLevel <= 0L) {
                    continue;
                }
                if (limitReached) {
                    complete = false;
                    break;
                }
                if (currentLevel(buildingId) >= targetLevel || !isBlocked(buildingId, targetLevel, 0)) {
                    continue;
                }
                complete = false;
            }
            if (limitReached) {
                complete = false;
            }
            return new Plan(actions, blockedReason, complete);
        }

        /** 判定把建筑升到 targetLevel 是否被阻塞；不阻塞时把升级动作追加进 actions。 */
        private boolean isBlocked(long buildingId, long targetLevel, int depth) {
            if (depth > MAX_DEPTH) {
                block("建筑前置条件嵌套过深，请检查门槛配置");
                return true;
            }
            if (limitReached) {
                return true;
            }
            if (!catalog.supports(buildingId)) {
                if (path.isEmpty()) {
                    return false;
                }
                block("前置建筑 " + buildingId + " 不在门槛目录中，无法自动建造");
                return true;
            }
            if (path.contains(buildingId)) {
                block("建筑 " + catalog.buildingName(buildingId)
                        + " 的前置条件互相依赖，已停止自动升级");
                return true;
            }
            long current = currentLevel(buildingId);
            if (current >= targetLevel) {
                return false;
            }
            if (request.busyBuildings().contains(buildingId)) {
                block(catalog.buildingName(buildingId) + " 正在建造或升级，等待完成后再继续");
                return true;
            }
            path.push(buildingId);
            try {
                for (long level = current + 1L; level <= targetLevel; ++level) {
                    if (limitReached) {
                        return true;
                    }
                    String key = buildingId + "@" + level;
                    if (!expanded.add(key)) continue;
                    if (prerequisitesBlocked(buildingId, level, depth)) {
                        return true;
                    }
                    if (furnitureBlocked(buildingId, level)) {
                        return true;
                    }
                    if (experienceBlocked(buildingId, level)) {
                        return true;
                    }
                    List<Cost> costs = catalog.buildingCosts(buildingId, level);
                    if (resourcesMissing(costs)) {
                        block(catalog.buildingName(buildingId) + " 升级到 " + level
                                + " 级所需资源不足，等待资源补充");
                        return true;
                    }
                    if (actions.size() >= request.maxActions()) {
                        limitReached = true;
                        return true;
                    }
                    actions.add(new Action(ActionKind.UPGRADE_BUILDING, buildingId,
                            catalog.buildingName(buildingId), 0L, level - 1L, level, costs));
                    levels.put(buildingId, level);
                }
                return false;
            } finally {
                path.pop();
            }
        }

        private boolean prerequisitesBlocked(long buildingId, long level, int depth) {
            for (Prerequisite prerequisite : catalog.prerequisites(buildingId, level)) {
                if (currentLevel(prerequisite.id()) >= prerequisite.amount()
                        || !isBlocked(prerequisite.id(), prerequisite.amount(), depth + 1)) {
                    continue;
                }
                return true;
            }
            return false;
        }

        /** 升到 level 级需满足 level-1 级的家具门槛：不足则补创建 + 升级动作。 */
        private boolean furnitureBlocked(long buildingId, long level) {
            if (level <= 1L) {
                return false;
            }
            for (Cost requirement : catalog.furnitureRequirement(buildingId, level - 1L)) {
                long currentFurnitureLevel;
                long requiredLevel = requirement.amount();
                if (!catalog.furnitureContributesExp(requirement.id())
                        || (currentFurnitureLevel = furnitureLevel(buildingId, requirement.id())) >= requiredLevel) {
                    continue;
                }
                long maxLevel = catalog.furnitureMaxLevel(requirement.id());
                if (maxLevel <= 0L || requiredLevel > maxLevel) {
                    block(catalog.buildingName(buildingId) + " 的家具 " + requirement.id()
                            + " 缺少等级上限配置");
                    return true;
                }
                if (currentFurnitureLevel <= 0L) {
                    List<Cost> createCosts = catalog.furnitureCosts(requirement.id(), 1L);
                    if (resourcesMissing(createCosts)) {
                        block("建造 " + catalog.buildingName(buildingId) + " 的家具 "
                                + requirement.id() + " 所需资源不足，等待资源补充");
                        return true;
                    }
                    if (actions.size() >= request.maxActions()) {
                        limitReached = true;
                        return true;
                    }
                    actions.add(new Action(ActionKind.CREATE_FURNITURE, buildingId,
                            catalog.buildingName(buildingId), requirement.id(), 0L, 1L, createCosts));
                    putFurnitureLevel(buildingId, requirement.id(), 1L);
                    currentFurnitureLevel = 1L;
                }
                if (currentFurnitureLevel >= requiredLevel) continue;
                List<Cost> upgradeCosts = furnitureUpgradeCosts(requirement.id(),
                        currentFurnitureLevel + 1L, requiredLevel);
                if (resourcesMissing(upgradeCosts)) {
                    block("升级 " + catalog.buildingName(buildingId) + " 的家具 "
                            + requirement.id() + " 所需资源不足，等待资源补充");
                    return true;
                }
                if (actions.size() >= request.maxActions()) {
                    limitReached = true;
                    return true;
                }
                actions.add(new Action(ActionKind.UPGRADE_FURNITURE, buildingId,
                        catalog.buildingName(buildingId), requirement.id(),
                        currentFurnitureLevel, requiredLevel, upgradeCosts));
                putFurnitureLevel(buildingId, requirement.id(), requiredLevel);
            }
            return false;
        }

        private boolean experienceBlocked(long buildingId, long level) {
            long required = catalog.needExp(buildingId, level);
            if (required <= 0L) {
                return false;
            }
            long total = 0L;
            for (Map.Entry<Long, Long> entry : furniture.getOrDefault(buildingId, Map.of()).entrySet()) {
                total += catalog.furnitureExp(entry.getKey(), entry.getValue());
            }
            if (total < required) {
                block(catalog.buildingName(buildingId) + " 的家具经验不足（" + total + "/"
                        + required + "），等待家具升级数据同步");
                return true;
            }
            return false;
        }

        /** fromLevel+1..toLevel 各级家具成本合并（同道具求和，保持首见顺序）。 */
        private List<Cost> furnitureUpgradeCosts(long furnitureId, long fromLevel, long toLevel) {
            LinkedHashMap<Long, Long> merged = new LinkedHashMap<>();
            for (long level = fromLevel; level <= toLevel; ++level) {
                for (Cost cost : catalog.furnitureCosts(furnitureId, level)) {
                    merged.merge(cost.id(), cost.amount(), Long::sum);
                }
            }
            ArrayList<Cost> result = new ArrayList<>();
            merged.forEach((id, amount) -> result.add(new Cost(id, amount)));
            return List.copyOf(result);
        }

        private boolean resourcesMissing(List<Cost> costs) {
            for (Cost cost : costs) {
                if (request.resources().available(cost.id(), cost.amount())) continue;
                return true;
            }
            return false;
        }

        private long currentLevel(long buildingId) {
            return Math.max(0L, levels.getOrDefault(buildingId, 0L));
        }

        private long furnitureLevel(long buildingId, long furnitureId) {
            return Math.max(0L, furniture.getOrDefault(buildingId, Map.of()).getOrDefault(furnitureId, 0L));
        }

        private void putFurnitureLevel(long buildingId, long furnitureId, long level) {
            furniture.computeIfAbsent(buildingId, ignored -> new LinkedHashMap<Long, Long>())
                    .put(furnitureId, level);
        }

        private void block(String reason) {
            if (blockedReason == null || blockedReason.isEmpty()) {
                blockedReason = reason;
            }
        }
    }

    // ================= 载体类型（对齐原版 record 形状） =================

    /** 规划请求：当前等级表 / 家具等级表 / 目标等级表 / 忙碌建筑 / 资源可用性 / 动作上限。 */
    public record Request(Map<Long, Long> buildingLevels, Map<Long, Map<Long, Long>> furnitureLevels,
                          Map<Long, Long> targets, Set<Long> busyBuildings,
                          ResourceAvailability resources, int maxActions) {
        public Request {
            buildingLevels = buildingLevels == null ? Map.of() : Map.copyOf(buildingLevels);
            furnitureLevels = furnitureLevels == null ? Map.of() : Map.copyOf(furnitureLevels);
            targets = targets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(targets));
            busyBuildings = busyBuildings == null ? Set.of() : Set.copyOf(busyBuildings);
            resources = resources == null ? ResourceAvailability.ALWAYS : resources;
            maxActions = maxActions <= 0 ? DEFAULT_MAX_ACTIONS : Math.min(maxActions, 300);
        }

        /** 兼容便捷构造：资源恒可用 + 默认动作上限（对齐原版 4 参构造）。 */
        public Request(Map<Long, Long> buildingLevels, Map<Long, Map<Long, Long>> furnitureLevels,
                       Map<Long, Long> targets, Set<Long> busyBuildings) {
            this(buildingLevels, furnitureLevels, targets, busyBuildings,
                    ResourceAvailability.ALWAYS, DEFAULT_MAX_ACTIONS);
        }
    }

    /** 规划结果：动作序列 + 首个阻塞原因 + 目标是否全部达成。 */
    public record Plan(List<Action> actions, String blockedReason, boolean complete) {
        public Plan {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        public List<Action> actions() {
            return List.copyOf(this.actions);
        }

        public boolean hasActions() {
            return !this.actions.isEmpty();
        }
    }

    /** 单个待执行动作（建筑升级 / 家具创建 / 家具升级）。 */
    public record Action(ActionKind kind, long buildingId, String buildingName, long furnitureId,
                         long fromLevel, long toLevel, List<Cost> costs) {
        public Action {
            costs = costs == null ? List.of() : List.copyOf(costs);
        }

        public List<Cost> costs() {
            return List.copyOf(this.costs);
        }

        /** 建筑升级占用建筑队列；家具动作不占用。 */
        public boolean usesQueue() {
            return this.kind == ActionKind.UPGRADE_BUILDING;
        }

        /** 人类可读动作文案（逐字对齐原版 label）。 */
        public String label() {
            return switch (this.kind) {
                case CREATE_FURNITURE -> this.buildingName + " 家具 " + this.furnitureId + " 建造";
                case UPGRADE_FURNITURE -> this.buildingName + " 家具 " + this.furnitureId
                        + " 升级到 " + this.toLevel + " 级";
                case UPGRADE_BUILDING -> (this.fromLevel <= 0L ? "建造 " : "升级 ") + this.buildingName
                        + " 到 " + this.toLevel + " 级";
            };
        }
    }

    /** 动作类型（顺序对齐原版 ActionKind）。 */
    public enum ActionKind {
        CREATE_FURNITURE,
        UPGRADE_FURNITURE,
        UPGRADE_BUILDING
    }

    /** 资源可用性判定（生产侧 ALWAYS；单测注入不足场景）。 */
    @FunctionalInterface
    public interface ResourceAvailability {
        ResourceAvailability ALWAYS = (itemId, amount) -> true;

        boolean available(long itemId, long amount);
    }
}
