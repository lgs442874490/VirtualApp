/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BootstrapBuildingState {
    private static final long NORMAL_STATUS = 0L;
    private static final long UPGRADING_STATUS = 1L;
    private static final long IDLE_QUEUE_STATUS = 1L;
    private static final long BUILDING_QUEUE_TYPE = 1L;
    private static final long FURNITURE_ID_BUILDING_BASE = 1000000L;
    private final Map<Long, Long> levels = new LinkedHashMap<Long, Long>();
    private final Map<Long, Long> statuses = new LinkedHashMap<Long, Long>();
    private final Map<Long, Long> queues = new LinkedHashMap<Long, Long>();
    private final Set<Long> knownQueues = new LinkedHashSet<Long>();
    private static final List<Long> FALLBACK_BUILDING_QUEUES = List.of(Long.valueOf(10001L), Long.valueOf(10002L));
    private final Set<Long> busyQueues = new LinkedHashSet<Long>();
    private final Map<Long, Map<Long, Long>> furniture = new LinkedHashMap<Long, Map<Long, Long>>();
    private final Map<Long, Map<Long, Long>> furnitureInstances = new LinkedHashMap<Long, Map<Long, Long>>();
    private final Map<Long, Map<Long, List<Long>>> furnitureOccupants = new LinkedHashMap<Long, Map<Long, List<Long>>>();
    private final Map<Long, Map<Long, Long>> furnitureTypes = new LinkedHashMap<Long, Map<Long, Long>>();
    private final Map<Long, Map<Long, Map<Long, Long>>> furnitureTypeLevels = new LinkedHashMap<Long, Map<Long, Map<Long, Long>>>();
    private boolean buildingsReady;
    private boolean queuesReady;
    private final Map<Long, Long> queuePurchasedEndtime = new LinkedHashMap<Long, Long>();
    private final Set<Long> lockedQueues = new LinkedHashSet<Long>();

    BootstrapBuildingState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        Map<String, Object> data = message.getContent();
        switch (message.getProto()) {
            case "notify_shelter_info": {
                this.applyBuildings(data);
                break;
            }
            case "notify_building_upgrade_finish": {
                this.applyFinish(data);
                break;
            }
            case "notify_queue_info": {
                this.applyQueues(data);
                break;
            }
            case "notify_queue_timeout": 
            case "notify_queue_cancel": {
                this.releaseQueue(data);
                break;
            }
        }
    }

    void reset() {
        this.clearBuildingProjection();
        this.knownQueues.clear();
        this.busyQueues.clear();
        this.queuePurchasedEndtime.clear();
        this.lockedQueues.clear();
        this.buildingsReady = false;
        this.queuesReady = false;
    }

    private void applyBuildings(Map<String, Object> data) {
        this.clearBuildingProjection();
        BootstrapBuildingState.forEachEntry(data.get("building_info"), (key, raw) -> {
            Map<String, Object> building = ProtocolValueSupport.map(raw);
            long id = BootstrapBuildingState.positive(BootstrapBuildingState.number(building.get("bid")), BootstrapBuildingState.number(key));
            if (id <= 0L) {
                return;
            }
            Map<String, Object> base = ProtocolValueSupport.map(building.get("base"));
            this.levels.put(id, Math.max(0L, BootstrapBuildingState.number(base.get("lv"))));
            this.statuses.put(id, Math.max(0L, BootstrapBuildingState.number(base.get("status"))));
            this.queues.put(id, Math.max(0L, BootstrapBuildingState.number(building.get("qid"))));
            this.applyFurniture(id, base.get("furniture"));
        });
        this.buildingsReady = true;
    }

    private void clearBuildingProjection() {
        this.levels.clear();
        this.statuses.clear();
        this.queues.clear();
        this.furniture.clear();
        this.furnitureInstances.clear();
        this.furnitureOccupants.clear();
        this.furnitureTypes.clear();
        this.furnitureTypeLevels.clear();
    }

    private void applyFinish(Map<String, Object> data) {
        long id = BootstrapBuildingState.number(data.get("bid"));
        if (id <= 0L) {
            return;
        }
        this.markBuildingLevel(id, BootstrapBuildingState.number(data.get("lv")));
        this.statuses.put(id, 0L);
        Long queueId = this.queues.remove(id);
        if (queueId != null && queueId > 0L) {
            this.busyQueues.remove(queueId);
        }
        this.applyFurniture(id, data.get("furniture_list"));
    }

    void markBuildingLevel(long buildingId, long level) {
        this.levels.put(buildingId, Math.max(0L, level));
    }

    private void applyFurniture(long buildingId, Object raw) {
        Map target = this.furniture.computeIfAbsent(buildingId, ignored -> new LinkedHashMap());
        Map instances = this.furnitureInstances.computeIfAbsent(buildingId, ignored -> new LinkedHashMap());
        Map occupants = this.furnitureOccupants.computeIfAbsent(buildingId, ignored -> new LinkedHashMap());
        Map types = this.furnitureTypes.computeIfAbsent(buildingId, ignored -> new LinkedHashMap());
        Map typeLevels = this.furnitureTypeLevels.computeIfAbsent(buildingId, ignored -> new LinkedHashMap());
        typeLevels.clear();
        BootstrapBuildingState.forEachEntry(raw, (key, value) -> {
            Map<String, Object> entry = ProtocolValueSupport.map(value);
            Map<String, Object> base = ProtocolValueSupport.map(entry.get("base"));
            long confFid = BootstrapBuildingState.positive(BootstrapBuildingState.number(base.get("conf_fid")), BootstrapBuildingState.number(entry.get("conf_fid")));
            long level = BootstrapBuildingState.number(base.get("lv"));
            if (confFid > 0L) {
                long type;
                target.put(confFid, Math.max(0L, level));
                long instanceId = BootstrapBuildingState.positive(BootstrapBuildingState.number(entry.get("fid")), BootstrapBuildingState.number(key));
                if (instanceId > 0L) {
                    instances.put(confFid, instanceId);
                }
                if ((type = BootstrapBuildingState.number(base.get("type"))) <= 0L) {
                    type = BootstrapBuildingState.catalogFurnitureType(confFid);
                }
                if (type > 0L) {
                    types.put(confFid, type);
                    typeLevels.computeIfAbsent(type, ignored -> new LinkedHashMap()).put(confFid, Math.max(0L, level));
                }
                List assigned = occupants.computeIfAbsent(confFid, ignored -> new ArrayList());
                assigned.clear();
                for (Object sid : BootstrapProtocolSupport.iterable(entry.get("sid_list"))) {
                    long survivorId = BootstrapProtocolSupport.number(sid);
                    if (survivorId <= 0L) continue;
                    assigned.add(survivorId);
                }
            }
        });
    }

    List<Long> furnitureLevelsOfType(long buildingId, long furnitureType) {
        ArrayList levels = new ArrayList(this.furnitureTypeLevels.getOrDefault(buildingId, Map.of()).getOrDefault(furnitureType, Map.of()).values());
        levels.sort(Comparator.reverseOrder());
        return List.copyOf(levels);
    }

    long furnitureCountAtLeast(long buildingId, long furnitureType, long level) {
        return this.furnitureLevelsOfType(buildingId, furnitureType).stream().filter(value -> value >= level).count();
    }

    List<Long> furnitureOccupants(long buildingId, long furnitureId) {
        return List.copyOf(this.furnitureOccupants.getOrDefault(buildingId, Map.of()).getOrDefault(furnitureId, List.of()));
    }

    static long catalogFurnitureType(long confFid) {
        if (confFid == 101L || confFid == 102L) {
            return 1L;
        }
        if (confFid == 103L) {
            return 2L;
        }
        if (confFid == 104L) {
            return 3L;
        }
        if (confFid == 105L) {
            return 4L;
        }
        if (confFid == 106L) {
            return 5L;
        }
        if (confFid == 107L) {
            return 6L;
        }
        if (confFid == 108L) {
            return 7L;
        }
        if (confFid == 109L) {
            return 8L;
        }
        if (confFid >= 201L && confFid <= 203L) {
            return 9L;
        }
        if (confFid == 204L) {
            return 10L;
        }
        if (confFid == 205L || confFid == 206L) {
            return 11L;
        }
        if (confFid == 208L) {
            return 12L;
        }
        if (confFid == 210L) {
            return 14L;
        }
        if (confFid == 211L) {
            return 15L;
        }
        if (confFid == 212L) {
            return 16L;
        }
        if (confFid == 213L) {
            return 17L;
        }
        if (confFid >= 301L && confFid <= 306L) {
            return 18L;
        }
        if (confFid == 307L) {
            return 19L;
        }
        if (confFid == 308L) {
            return 20L;
        }
        if (confFid == 309L) {
            return 21L;
        }
        if (confFid == 310L) {
            return 22L;
        }
        if (confFid == 311L) {
            return 23L;
        }
        if (confFid >= 401L && confFid <= 406L) {
            return 24L;
        }
        if (confFid == 407L) {
            return 25L;
        }
        if (confFid == 408L) {
            return 26L;
        }
        if (confFid == 409L) {
            return 27L;
        }
        if (confFid == 410L) {
            return 28L;
        }
        if (confFid == 411L) {
            return 29L;
        }
        if (confFid >= 501L && confFid <= 506L) {
            return 30L;
        }
        if (confFid == 507L) {
            return 31L;
        }
        if (confFid == 508L) {
            return 32L;
        }
        if (confFid == 509L) {
            return 33L;
        }
        if (confFid == 510L) {
            return 34L;
        }
        if (confFid == 511L) {
            return 35L;
        }
        if (confFid >= 601L && confFid <= 605L) {
            return 30L;
        }
        if (confFid == 606L) {
            return 36L;
        }
        if (confFid == 607L) {
            return 37L;
        }
        if (confFid == 608L) {
            return 38L;
        }
        if (confFid == 609L) {
            return 39L;
        }
        if (confFid == 610L) {
            return 40L;
        }
        if (confFid == 611L) {
            return 41L;
        }
        if (confFid == 701L) {
            return 42L;
        }
        if (confFid >= 702L && confFid <= 707L) {
            return 43L;
        }
        if (confFid == 708L) {
            return 44L;
        }
        if (confFid == 709L) {
            return 45L;
        }
        if (confFid == 710L) {
            return 46L;
        }
        if (confFid == 711L) {
            return 47L;
        }
        if (confFid == 712L) {
            return 48L;
        }
        return 0L;
    }

    long furnitureType(long buildingId, long furnitureId) {
        long synced = this.furnitureTypes.getOrDefault(buildingId, Map.of()).getOrDefault(furnitureId, 0L);
        if (synced > 0L) {
            return synced;
        }
        return BootstrapBuildingState.catalogFurnitureType(furnitureId);
    }

    Map<Long, List<Long>> furnitureByType(long buildingId, long type) {
        LinkedHashMap<Long, List<Long>> result = new LinkedHashMap<Long, List<Long>>();
        this.furnitureTypes.getOrDefault(buildingId, Map.of()).forEach((confFid, value) -> {
            if (value == type) {
                result.put((Long)confFid, this.furnitureOccupants(buildingId, (long)confFid));
            }
        });
        return result;
    }

    long furnitureInstanceId(long buildingId, long furnitureId) {
        long synced = this.furnitureInstances.getOrDefault(buildingId, Map.of()).getOrDefault(furnitureId, 0L);
        if (synced > 0L) {
            return synced;
        }
        if (buildingId <= 0L || furnitureId <= 0L) {
            return 0L;
        }
        return buildingId * 1000000L + furnitureId % 1000000L;
    }

    void clearFurnitureInstance(long buildingId, long furnitureId) {
        Map<Long, Long> instances = this.furnitureInstances.get(buildingId);
        if (instances != null) {
            instances.remove(furnitureId);
        }
    }

    void markFurnitureLevel(long buildingId, long furnitureId, long level) {
        if (buildingId <= 0L || furnitureId <= 0L) {
            return;
        }
        long applied = Math.max(0L, level);
        this.furniture.computeIfAbsent(buildingId, ignored -> new LinkedHashMap()).put(furnitureId, applied);
        long type = this.furnitureType(buildingId, furnitureId);
        if (type > 0L) {
            this.furnitureTypes.computeIfAbsent(buildingId, ignored -> new LinkedHashMap()).put(furnitureId, type);
            this.furnitureTypeLevels.computeIfAbsent(buildingId, ignored -> new LinkedHashMap()).computeIfAbsent(type, ignored -> new LinkedHashMap()).put(furnitureId, applied);
        }
    }

    void markFurnitureCreated(long buildingId, long furnitureId, long instanceId, long level) {
        this.markFurnitureCreated(buildingId, furnitureId, instanceId, level, 0L);
    }

    void markFurnitureCreated(long buildingId, long furnitureId, long instanceId, long level, long type) {
        if (type <= 0L) {
            type = BootstrapBuildingState.catalogFurnitureType(furnitureId);
        }
        if (type > 0L && buildingId > 0L && furnitureId > 0L) {
            this.furnitureTypes.computeIfAbsent(buildingId, ignored -> new LinkedHashMap()).put(furnitureId, type);
            this.furnitureTypeLevels.computeIfAbsent(buildingId, ignored -> new LinkedHashMap()).computeIfAbsent(type, ignored -> new LinkedHashMap()).put(furnitureId, Math.max(0L, level));
        }
        this.markFurnitureLevel(buildingId, furnitureId, level);
        if (buildingId <= 0L || furnitureId <= 0L || instanceId <= 0L) {
            return;
        }
        this.furnitureInstances.computeIfAbsent(buildingId, ignored -> new LinkedHashMap()).put(furnitureId, instanceId);
    }

    private void applyQueues(Map<String, Object> data) {
        this.knownQueues.clear();
        this.busyQueues.clear();
        this.queuePurchasedEndtime.clear();
        this.lockedQueues.clear();
        BootstrapBuildingState.forEachEntry(data.get("queue_map"), (key, raw) -> {
            Map<String, Object> queue = ProtocolValueSupport.map(raw);
            long queueId = BootstrapBuildingState.positive(BootstrapBuildingState.number(queue.get("qid")), BootstrapBuildingState.number(key));
            if (queueId <= 0L) {
                return;
            }
            Map<String, Object> base = ProtocolValueSupport.map(queue.get("base"));
            long queueType = BootstrapBuildingState.number(base.get("qtype"));
            if (queueType > 0L && queueType != 1L) {
                return;
            }
            Object rawPurchasedEndtime = base.get("purchased_endtime");
            this.queuePurchasedEndtime.put(queueId, rawPurchasedEndtime == null ? -1L : ProtocolValueSupport.number(rawPurchasedEndtime));
            this.knownQueues.add(queueId);
            if (BootstrapBuildingState.number(queue.get("status")) != 1L) {
                this.busyQueues.add(queueId);
            }
        });
        this.queuesReady = true;
    }

    long busyQueueId() {
        for (Map.Entry<Long, Long> entry : this.queues.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0L) continue;
            return entry.getValue();
        }
        Iterator<Object> iterator = this.knownQueues.iterator();
        while (iterator.hasNext()) {
            long candidate = (Long)iterator.next();
            if (!this.busyQueues.contains(candidate)) continue;
            return candidate;
        }
        iterator = FALLBACK_BUILDING_QUEUES.iterator();
        while (iterator.hasNext()) {
            long candidate = (Long)iterator.next();
            if (!this.busyQueues.contains(candidate)) continue;
            return candidate;
        }
        return 0L;
    }

    long buildingOnQueue(long queueId) {
        if (queueId <= 0L) {
            return 0L;
        }
        for (Map.Entry<Long, Long> entry : this.queues.entrySet()) {
            if (entry.getValue() == null || entry.getValue() != queueId) continue;
            return entry.getKey();
        }
        return 0L;
    }

    boolean queueUsable(long queueId, long serverNowSeconds) {
        if (queueId <= 0L || this.lockedQueues.contains(queueId)) {
            return false;
        }
        Long purchasedEndtime = this.queuePurchasedEndtime.get(queueId);
        if (purchasedEndtime == null) {
            return true;
        }
        return purchasedEndtime < 0L || purchasedEndtime > 0L && purchasedEndtime > serverNowSeconds;
    }

    void markQueueLocked(long queueId) {
        if (queueId > 0L) {
            this.lockedQueues.add(queueId);
        }
    }

    private void releaseQueue(Map<String, Object> data) {
        long queueId = BootstrapBuildingState.number(data.get("qid"));
        this.busyQueues.remove(queueId);
        for (Map.Entry<Long, Long> entry : this.queues.entrySet()) {
            if (entry.getValue() == null || entry.getValue() != queueId) continue;
            this.statuses.put(entry.getKey(), 0L);
            this.queues.remove(entry.getKey());
            break;
        }
    }

    void markBuildingStarted(long buildingId, long queueId) {
        if (buildingId <= 0L) {
            return;
        }
        this.statuses.put(buildingId, 1L);
        if (queueId > 0L) {
            this.queues.put(buildingId, queueId);
            this.knownQueues.add(queueId);
            this.busyQueues.add(queueId);
        }
    }

    void markQueueBusy(long queueId) {
        if (queueId > 0L) {
            this.knownQueues.add(queueId);
            this.busyQueues.add(queueId);
        }
    }

    void markBuildingInstantUpgrade(long buildingId, long level) {
        if (buildingId <= 0L) {
            return;
        }
        this.markBuildingLevel(buildingId, level);
        this.statuses.put(buildingId, 0L);
        Long queueId = this.queues.remove(buildingId);
        if (queueId != null && queueId > 0L) {
            this.busyQueues.remove(queueId);
        }
    }

    long level(long buildingId) {
        return this.levels.getOrDefault(buildingId, 0L);
    }

    long status(long buildingId) {
        return this.statuses.getOrDefault(buildingId, 0L);
    }

    boolean busy(long buildingId) {
        return this.status(buildingId) != 0L || this.queue(buildingId) > 0L;
    }

    Set<Long> busyBuildings() {
        LinkedHashSet<Long> result = new LinkedHashSet<Long>();
        for (Map.Entry<Long, Long> entry : this.statuses.entrySet()) {
            if (entry.getValue() == null || entry.getValue() == 0L) continue;
            result.add(entry.getKey());
        }
        for (Map.Entry<Long, Long> entry : this.queues.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0L) continue;
            result.add(entry.getKey());
        }
        return Set.copyOf(result);
    }

    long queue(long buildingId) {
        return this.queues.getOrDefault(buildingId, 0L);
    }

    Map<Long, Map<Long, Long>> furnitureLevels() {
        LinkedHashMap result = new LinkedHashMap();
        this.furniture.forEach((buildingId, levels) -> result.put(buildingId, Map.copyOf(levels)));
        return Map.copyOf(result);
    }

    boolean buildingsReady() {
        return this.buildingsReady;
    }

    boolean queuesReady() {
        return this.queuesReady;
    }

    long idleQueueId(long serverNowSeconds) {
        for (long candidate : this.knownQueues) {
            if (this.busyQueues.contains(candidate) || !this.queueUsable(candidate, serverNowSeconds)) continue;
            return candidate;
        }
        if (this.knownQueues.isEmpty()) {
            for (long candidate : FALLBACK_BUILDING_QUEUES) {
                if (this.busyQueues.contains(candidate) || !this.queueUsable(candidate, serverNowSeconds)) continue;
                return candidate;
            }
        }
        return 0L;
    }

    private static void forEachEntry(Object raw, EntryConsumer consumer) {
        if (raw instanceof Map) {
            Map map = (Map)raw;
            map.forEach((key, value) -> consumer.accept(key == null ? "" : String.valueOf(key), value));
            return;
        }
        if (raw instanceof Iterable) {
            Iterable iterable = (Iterable)raw;
            for (Object value2 : iterable) {
                consumer.accept("", value2);
            }
        }
    }

    private static long number(Object value) {
        return (long)Math.floor(Math.max(0.0, (double)ProtocolValueSupport.number(value)));
    }

    private static long positive(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    @FunctionalInterface
    private static interface EntryConsumer {
        public void accept(String var1, Object var2);
    }
}

