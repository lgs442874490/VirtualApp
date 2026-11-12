/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class BootstrapWorkState {
    private static final String SCHEDULE_UPDATE = "notify_schedule_update";
    private static final String SCHEDULE_INFO = "req_schedule_info";
    private static final String SHELTER_INFO = "notify_shelter_info";
    private static final long KITCHEN_ID = 306L;
    static final long HUNTING_COOLDOWN_SECONDS = 180L;
    private final Set<Long> startedSurvivors = new LinkedHashSet<Long>();
    private final Map<Long, Long> huntCooldownUntil = new LinkedHashMap<Long, Long>();
    private long scheduleSegmentId = -1L;
    private long scheduleServerTime;
    private boolean scheduleChanged;
    private long kitchenFoodCount = -1L;
    private long productionFoodId;
    private boolean scheduleReady;
    private boolean scheduleQuerySent;

    BootstrapWorkState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        Map<String, Object> data = message.getContent();
        switch (message.getProto()) {
            case "notify_schedule_update": 
            case "req_schedule_info": {
                this.applySchedule(data);
                break;
            }
            case "notify_shelter_info": {
                this.applyKitchen(data.get("building_info"));
                break;
            }
        }
    }

    void applyScheduleResponse(Map<String, Object> data) {
        this.applySchedule(data);
    }

    private void applySchedule(Map<String, Object> data) {
        long segmentId = BootstrapProtocolSupport.number(data.get("qtime_id"));
        this.scheduleServerTime = BootstrapProtocolSupport.number(data.get("server_time"));
        if (this.scheduleSegmentId >= 0L && segmentId != this.scheduleSegmentId) {
            this.scheduleChanged = true;
            this.startedSurvivors.clear();
        }
        this.scheduleSegmentId = segmentId;
        this.scheduleReady = true;
    }

    private void applyKitchen(Object raw) {
        Map<String, Object> building = BootstrapProtocolSupport.map(BootstrapProtocolSupport.map(raw).get(String.valueOf(306L)));
        if (building.isEmpty()) {
            return;
        }
        long productionId = BootstrapProtocolSupport.number(building.get("production_food_id"));
        if (productionId > 0L) {
            this.productionFoodId = productionId;
        }
        long count = 0L;
        for (Object ignored : BootstrapProtocolSupport.iterable(building.get("foods"))) {
            ++count;
        }
        this.kitchenFoodCount = count;
    }

    boolean scheduleReady() {
        return this.scheduleReady;
    }

    boolean scheduleQuerySent() {
        return this.scheduleQuerySent;
    }

    void markScheduleQuerySent() {
        this.scheduleQuerySent = true;
    }

    long scheduleSegmentId() {
        return this.scheduleSegmentId;
    }

    long scheduleServerTime() {
        return this.scheduleServerTime;
    }

    boolean scheduleChanged() {
        return this.scheduleChanged;
    }

    void clearScheduleChanged() {
        this.scheduleChanged = false;
    }

    boolean needsWorkStart(long sid) {
        return sid > 0L && !this.startedSurvivors.contains(sid);
    }

    void markWorkStarted(long sid) {
        if (sid > 0L) {
            this.startedSurvivors.add(sid);
        }
    }

    void markWorkPaused(long sid) {
        this.startedSurvivors.remove(sid);
    }

    boolean canHunt(long sid, long nowMillis) {
        return nowMillis >= this.huntCooldownUntil.getOrDefault(sid, 0L);
    }

    void markHunted(long sid, long untilMillis) {
        if (sid > 0L) {
            this.huntCooldownUntil.put(sid, Math.max(0L, untilMillis));
        }
    }

    long kitchenFoodCount() {
        return this.kitchenFoodCount;
    }

    long productionFoodId() {
        return this.productionFoodId;
    }
}

