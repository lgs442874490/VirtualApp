package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.protocol.ProtocolValueSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 起号建筑/家具/队列状态（M4a 任务书 §2.3，语义对齐原版 BootstrapBuildingState）。
 *
 * <p>由 loaddata 全量内容一次性构建（{@link #fromLoadData}）：建筑等级表、家具等级表、
 * 升级队列忙碌集合、背包资源、角色 uid/名称，全部<b>防御式深找</b>
 * （风格参照 {@code ServerTownTasks.parseBarrackBuildingLevels}：多键候选 + 条目字段候选 +
 * base 包裹层兼容）。执行动作后用 {@link #markedUpgraded}/{@link #markedBusy}/
 * {@link #markedFurniture} 本地乐观推进，等待下一轮 loaddata 校准
 * （对齐原版 markBuildingInstantUpgrade/markBuildingStarted/markFurnitureLevel）。
 */
public final class BootstrapState {

    /** 建筑/队列/家具状态常量（对齐原版：建筑 status 0=空闲 1=升级中；队列 status 1=空闲）。 */
    private static final long BUILDING_UPGRADING_STATUS = 1L;
    private static final long QUEUE_IDLE_STATUS = 1L;
    private static final long BUILDING_QUEUE_TYPE = 1L;

    /** 建筑等级表（键 = 建筑 id）。 */
    private final Map<Long, Long> levels = new LinkedHashMap<>();
    /** 建筑状态表（0 = 空闲，非 0 = 升级中）。 */
    private final Map<Long, Long> statuses = new LinkedHashMap<>();
    /** 建筑占用队列（建筑 id → qid）。 */
    private final Map<Long, Long> queues = new LinkedHashMap<>();
    /** 已知建筑队列 id。 */
    private final Set<Long> knownQueues = new LinkedHashSet<>();
    /** 忙碌队列 id。 */
    private final Set<Long> busyQueues = new LinkedHashSet<>();
    /** 家具等级表（建筑 id → 家具 id → 等级）。 */
    private final Map<Long, Map<Long, Long>> furniture = new LinkedHashMap<>();
    /** 背包资源/道具数量（id → 数量）。 */
    private final Map<Long, Long> resources = new LinkedHashMap<>();
    /** 角色 uid（loaddata 顶层深找；缺失 0）。 */
    private long roleUid;
    /** 角色名称（loaddata 顶层深找；缺失空串）。 */
    private String roleName = "";
    /** 建筑全量是否已同步（loaddata 命中建筑字段）。 */
    private boolean buildingsReady;
    /** 队列全量是否已同步（loaddata 命中队列字段）。 */
    private boolean queuesReady;
    /** 背包物品是否已同步（loaddata 顶层命中背包键，对齐原版 backpackInitialized）。 */
    private boolean backpackReady;

    private BootstrapState() {
    }

    /** 空状态（全未同步），供未注入 loaddata 时兜底。 */
    public static BootstrapState empty() {
        return new BootstrapState();
    }

    /**
     * 从 loaddata 全量内容构建状态。
     *
     * <p>深找键候选：建筑 {@code buildings}/{@code building_info}；家具 {@code furnitures}/
     * {@code furniture}；队列 {@code queues}/{@code build_queue}/{@code queue_map}；
     * 资源 {@code res}/{@code resources}/{@code bag}/{@code backpack}；
     * 角色 uid {@code uid}/{@code fid}/{@code role_uid}；名称 {@code name}/{@code game_name}/
     * {@code display_name}。命中建筑字段即 buildingsReady、命中队列字段即 queuesReady。
     */
    public static BootstrapState fromLoadData(Map<String, Object> loadContent) {
        BootstrapState s = new BootstrapState();
        if (loadContent == null || loadContent.isEmpty()) {
            return s;
        }
        s.applyBuildings(firstOf(loadContent, "buildings", "building_info"));
        s.applyFurniture(firstOf(loadContent, "furnitures", "furniture"));
        s.applyQueues(firstOf(loadContent, "queues", "build_queue", "queue_map"));
        // 资源与物品是两张来源（res=资源、bag/backpack/items=背包物品），全部并入同一张
        // 计数表（原版 resourceCount/itemCount 同源口径）——只取 firstOf 会漏掉背包物品，
        // usePacks 的 itemCount 恒为 0 导致资源包永远不开。
        s.applyResources(firstOf(loadContent, "res", "resources"));
        s.applyResources(firstOf(loadContent, "bag", "backpack"));
        s.applyResources(firstOf(loadContent, "items", "item"));
        s.backpackReady = loadContent.containsKey("bag") || loadContent.containsKey("backpack")
                || loadContent.containsKey("items") || loadContent.containsKey("item");
        s.roleUid = deepPositiveNumber(loadContent, "uid", "fid", "role_uid");
        s.roleName = deepText(loadContent, "name", "game_name", "display_name");
        return s;
    }

    // ================= 查询 =================

    /** 建筑当前等级；未知返回 0。 */
    public long level(long buildingId) {
        return levels.getOrDefault(buildingId, 0L);
    }

    /** 大熔炉（301）当前等级。 */
    public long furnaceLevel() {
        return level(301L);
    }

    /** 建筑是否忙碌（升级中或占用队列）。 */
    public boolean busy(long buildingId) {
        return statuses.getOrDefault(buildingId, 0L) != 0L || queues.getOrDefault(buildingId, 0L) > 0L;
    }

    /** 忙碌建筑集合（升级中或占用队列）。 */
    public Set<Long> busyBuildings() {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Map.Entry<Long, Long> e : statuses.entrySet()) {
            if (e.getValue() != null && e.getValue() != 0L) result.add(e.getKey());
        }
        for (Map.Entry<Long, Long> e : queues.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0L) result.add(e.getKey());
        }
        return Set.copyOf(result);
    }

    /** 家具等级表快照（建筑 id → 家具 id → 等级）。 */
    public Map<Long, Map<Long, Long>> furnitureLevels() {
        LinkedHashMap<Long, Map<Long, Long>> result = new LinkedHashMap<>();
        furniture.forEach((buildingId, levels) -> result.put(buildingId, Map.copyOf(levels)));
        return Map.copyOf(result);
    }

    /** 建筑等级表快照。 */
    public Map<Long, Long> buildingLevels() {
        return Map.copyOf(levels);
    }

    /** 角色 uid（未同步 0）。 */
    public long roleUid() {
        return roleUid;
    }

    /** 角色名称（未同步空串）。 */
    public String roleName() {
        return roleName == null ? "" : roleName;
    }

    /** 背包资源/道具数量；未知 id 返回 0。 */
    public long resourceCount(long id) {
        return resources.getOrDefault(id, 0L);
    }

    /** 背包物品数量（与资源同表：loaddata 背包条目 id → 数量，语义同 {@link #resourceCount}）。 */
    public long itemCount(long itemId) {
        return resourceCount(itemId);
    }

    /** 背包物品是否已同步（原版 backpackInitialized 语义：资源包使用的前置门）。 */
    public boolean backpackInitialized() {
        return backpackReady;
    }

    /** 资源表快照。 */
    public Map<Long, Long> resourceMap() {
        return Map.copyOf(resources);
    }

    /** 建筑全量是否已同步。 */
    public boolean buildingsReady() {
        return buildingsReady;
    }

    /** 队列全量是否已同步。 */
    public boolean queuesReady() {
        return queuesReady;
    }

    /** 起号状态是否就绪（建筑与队列都已同步，对齐原版 stateReady）。 */
    public boolean stateReady() {
        return buildingsReady && queuesReady;
    }

    /**
     * 空闲建筑队列 id：优先取已同步队列中的空闲者；队列未同步时回退候选 1/2
     * （对齐原版 idleQueueId：instantDiamond=false 且无空闲 → 调用方返回 WAITING）。
     */
    public long idleQueueId() {
        for (long candidate : knownQueues) {
            if (!busyQueues.contains(candidate)) return candidate;
        }
        if (knownQueues.isEmpty()) {
            for (long candidate = 1L; candidate <= 2L; ++candidate) {
                if (!busyQueues.contains(candidate)) return candidate;
            }
        }
        return 0L;
    }

    // ================= 乐观更新（执行动作后本地推进，等待下轮 loaddata 校准） =================

    /**
     * 标记建筑已即时升到 {@code toLevel}（对齐原版 markBuildingInstantUpgrade）：
     * 等级推进、状态回空闲、释放占用队列。
     */
    public void markedUpgraded(long buildingId, long toLevel) {
        if (buildingId <= 0L) return;
        levels.put(buildingId, Math.max(0L, toLevel));
        statuses.put(buildingId, 0L);
        releaseQueueOf(buildingId);
    }

    /**
     * 标记建筑已开工（排队升级中，对齐原版 markBuildingStarted）：状态置忙碌，
     * 并把一个空闲队列标记为占用（无空闲队列则只标建筑）。
     *
     * <p>注意：占用的队列若来自「未同步时的候选回退」（1/2），不写入已同步集合
     * knownQueues——否则会把回退通道堵死（候选耗尽前始终可继续回退）。
     */
    public void markedBusy(long buildingId) {
        if (buildingId <= 0L) return;
        statuses.put(buildingId, BUILDING_UPGRADING_STATUS);
        long qid = idleQueueId();
        if (qid > 0L) {
            queues.put(buildingId, qid);
            busyQueues.add(qid);
        }
    }

    /** 标记家具等级（对齐原版 markFurnitureLevel）。 */
    public void markedFurniture(long buildingId, long furnitureId, long level) {
        if (buildingId <= 0L || furnitureId <= 0L) return;
        furniture.computeIfAbsent(buildingId, ignored -> new LinkedHashMap<>())
                .put(furnitureId, Math.max(0L, level));
    }

    /**
     * 标记背包物品已被消耗（资源包 {@code req_use_item} 成功后的本地乐观扣减，
     * 等待下一轮 loaddata 校准）。
     */
    public void markedItemUsed(long itemId, long count) {
        if (itemId <= 0L || count <= 0L) return;
        resources.computeIfPresent(itemId, (ignored, have) -> Math.max(0L, have - count));
    }

    /** 标记资源入账（资源包开出的资源本地乐观累加，等待下一轮 loaddata 校准）。 */
    public void markedResourceGained(long resourceId, long amount) {
        if (resourceId <= 0L || amount <= 0L) return;
        resources.merge(resourceId, amount, Long::sum);
    }

    // ================= loaddata 深找解析 =================

    private void applyBuildings(Object raw) {
        if (raw == null) return;
        boolean any = false;
        for (Map<String, Object> entry : entries(raw)) {
            long id = positive(number(entry.get("bid")), number(entry.get("id")));
            if (id <= 0L) continue;
            Map<String, Object> base = mapOf(entry.get("base"));
            long lv = positive(number(base.get("lv")), number(entry.get("lv")));
            // 兼容 level 键（任务书：条目内 bid/id + lv/level）
            if (lv <= 0L) lv = number(entry.get("level"));
            long status = positive(number(base.get("status")), number(entry.get("status")));
            long qid = positive(number(entry.get("qid")), number(base.get("qid")));
            levels.put(id, Math.max(0L, lv));
            statuses.put(id, Math.max(0L, status));
            if (qid > 0L) {
                queues.put(id, qid);
                knownQueues.add(qid);
                busyQueues.add(qid);
            }
            any = true;
        }
        if (any) buildingsReady = true;
    }

    private void applyFurniture(Object raw) {
        if (raw == null) return;
        for (Map<String, Object> entry : entries(raw)) {
            Map<String, Object> base = mapOf(entry.get("base"));
            long bid = positive(number(entry.get("bid")), number(entry.get("building_id")));
            long fid = positive(number(base.get("conf_fid")),
                    positive(number(entry.get("conf_fid")), number(entry.get("fid"))));
            long lv = positive(number(base.get("lv")), number(entry.get("lv")));
            if (bid <= 0L || fid <= 0L) continue;
            furniture.computeIfAbsent(bid, ignored -> new LinkedHashMap<>()).put(fid, Math.max(0L, lv));
        }
    }

    private void applyQueues(Object raw) {
        if (raw == null) return;
        knownQueues.clear();
        busyQueues.clear();
        for (Map<String, Object> entry : entries(raw)) {
            Map<String, Object> base = mapOf(entry.get("base"));
            long qid = positive(number(entry.get("qid")), number(base.get("qid")));
            long bid = positive(number(entry.get("bid")), number(base.get("bid")));
            // 非建筑队列跳过（对齐原版 qtype 判定）
            long qtype = number(base.get("qtype")) > 0L ? number(base.get("qtype"))
                    : number(entry.get("qtype"));
            if (qtype > 0L && qtype != BUILDING_QUEUE_TYPE) continue;
            // 未完成判定：status 非 1（队列 1=空闲，对齐原版）；无 status 字段但有归属即视为占用中
            long status = positive(number(entry.get("status")), number(base.get("status")));
            boolean busy = status != QUEUE_IDLE_STATUS;
            if (qid > 0L) {
                knownQueues.add(qid);
                if (busy) busyQueues.add(qid);
            }
            if (bid > 0L && busy && qid > 0L) {
                queues.put(bid, qid);
                statuses.put(bid, BUILDING_UPGRADING_STATUS);
            }
        }
        queuesReady = true;
    }

    private void applyResources(Object raw) {
        if (raw == null) return;
        if (raw instanceof Map) {
            for (Map.Entry<String, Object> e : mapOf(raw).entrySet()) {
                long id = number(e.getKey());
                if (id <= 0L) continue;
                Map<String, Object> nested = mapOf(e.getValue());
                long amount = nested.isEmpty()
                        ? number(e.getValue())
                        : positive(number(nested.get("count")),
                                positive(number(nested.get("num")), number(nested.get("amount"))));
                if (amount > 0L) resources.merge(id, amount, Long::sum);
            }
            return;
        }
        for (Map<String, Object> entry : entries(raw)) {
            long id = positive(number(entry.get("id")), number(entry.get("res_id")));
            long amount = positive(number(entry.get("count")),
                    positive(number(entry.get("num")), number(entry.get("amount"))));
            if (id > 0L && amount > 0L) resources.merge(id, amount, Long::sum);
        }
    }

    // ================= 防御式解析工具 =================

    /** 释放建筑占用的队列（建筑回空闲时调用，对齐原版 applyFinish/markBuildingInstantUpgrade）。 */
    private void releaseQueueOf(long buildingId) {
        Long qid = queues.remove(buildingId);
        if (qid != null && qid > 0L) busyQueues.remove(qid);
    }

    /** 键候选逐个命中：返回第一个非 null 值。 */
    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }

    /** 条目流：dict（值/键均收）与 list 统一为 Map 列表，单条建筑本体也兼容。 */
    private static List<Map<String, Object>> entries(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof Map) {
            for (Object v : ((Map<?, ?>) raw).values()) {
                Map<String, Object> m = mapOf(v);
                if (!m.isEmpty()) out.add(m);
            }
        } else if (raw instanceof Iterable) {
            for (Object v : (Iterable<?>) raw) {
                Map<String, Object> m = mapOf(v);
                if (!m.isEmpty()) out.add(m);
            }
        } else {
            Map<String, Object> single = mapOf(raw);
            if (!single.isEmpty()) out.add(single);
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map)) return Map.of();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static long number(Object v) {
        return ProtocolValueSupport.number(v);
    }

    private static long positive(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    /** 顶层优先的多键数值深找（首层命中即返回，含 0 值）。 */
    private static long deepPositiveNumber(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) return Math.max(0L, number(m.get(k)));
        }
        return 0L;
    }

    /** 顶层优先的多键文本深找（trim 后非空才命中）。 */
    private static String deepText(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) continue;
            String text = String.valueOf(v).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }
}
