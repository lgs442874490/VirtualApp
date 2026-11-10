/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class BootstrapSurvivorState {
    private static final long FURNITURE_SLOT_BASE = 1000L;
    private static final long BUILDING_BASE = 1000000L;
    private final Map<Long, Survivor> survivors = new LinkedHashMap<Long, Survivor>();
    private final Set<Long> tempSurvivorIds = new LinkedHashSet<Long>();
    private final Set<Long> pendingWorkStart = new LinkedHashSet<Long>();
    private boolean initialized;
    private boolean guideSelected;
    private boolean guideRequested;

    BootstrapSurvivorState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        Map<String, Object> data = message.getContent();
        switch (message.getProto()) {
            case "notify_survivor_info": {
                this.survivors.clear();
                this.applySurvivors(data.get("survivor_list"));
                this.guideSelected = BootstrapProtocolSupport.booleanValue(data.get("guide_selected"));
                this.initialized = true;
                break;
            }
            case "notify_survivor_add": {
                this.applySurvivors(data.get("survivor_list"));
                break;
            }
            case "notify_temp_survivor": {
                this.tempSurvivorIds.addAll(BootstrapSurvivorState.survivorIds(data.get("survivor_list")));
                break;
            }
            case "notify_survivor_del": {
                for (long sid : BootstrapSurvivorState.survivorIds(data.get("survivor_list"))) {
                    this.survivors.remove(sid);
                }
                long sid = BootstrapProtocolSupport.number(data.get("sid"));
                if (sid <= 0L) break;
                this.survivors.remove(sid);
                break;
            }
        }
    }

    void reset() {
        this.survivors.clear();
        this.tempSurvivorIds.clear();
        this.pendingWorkStart.clear();
        this.initialized = false;
        this.guideSelected = false;
        this.guideRequested = false;
    }

    private void applySurvivors(Object raw) {
        for (Map<String, Object> entry : BootstrapProtocolSupport.maps(raw)) {
            long sid = BootstrapProtocolSupport.number(entry.get("sid"));
            if (sid <= 0L) continue;
            this.survivors.put(sid, new Survivor(sid, BootstrapProtocolSupport.number(entry.get("status")), BootstrapProtocolSupport.number(entry.get("fid_work")), BootstrapProtocolSupport.number(entry.get("fid_source")), BootstrapProtocolSupport.number(entry.get("name_id")), BootstrapProtocolSupport.number(entry.get("img_id")), BootstrapProtocolSupport.booleanValue(entry.get("is_sick"))));
        }
    }

    private static List<Long> survivorIds(Object raw) {
        ArrayList<Long> result = new ArrayList<Long>();
        for (Map<String, Object> entry : BootstrapProtocolSupport.maps(raw)) {
            long sid = BootstrapProtocolSupport.number(entry.get("sid"));
            if (sid <= 0L) continue;
            result.add(sid);
        }
        return result;
    }

    boolean initialized() {
        return this.initialized;
    }

    boolean guideSelected() {
        return this.guideSelected;
    }

    boolean guideRequested() {
        return this.guideRequested;
    }

    void markGuideRequested() {
        this.guideRequested = true;
    }

    List<Survivor> survivors() {
        return List.copyOf(this.survivors.values());
    }

    List<Long> tempSurvivorIds() {
        return List.copyOf(this.tempSurvivorIds);
    }

    void clearTempSurvivors() {
        this.tempSurvivorIds.clear();
    }

    Optional<Survivor> idleSurvivor() {
        return this.survivors.values().stream().filter(Survivor::idle).findFirst();
    }

    Optional<Survivor> survivor(long sid) {
        return Optional.ofNullable(this.survivors.get(sid));
    }

    long assignedCountToBuilding(long buildingId) {
        if (buildingId <= 0L) {
            return 0L;
        }
        long count = 0L;
        for (Survivor survivor : this.survivors.values()) {
            if (survivor.fidWork() <= 0L || survivor.fidWork() / 1000000L != buildingId) continue;
            ++count;
        }
        return count;
    }

    List<Survivor> assignedToWork(long fidWork) {
        if (fidWork <= 0L) {
            return List.of();
        }
        return this.survivors.values().stream().filter(survivor -> survivor.fidWork() == fidWork).toList();
    }

    boolean assignedTo(long furnitureInstanceId) {
        return !this.assignedToWork(furnitureInstanceId).isEmpty();
    }

    long matchOnBed(long buildingId, long confFid, long nameId, long imgId) {
        for (Survivor survivor : this.onBed(buildingId, confFid)) {
            if (survivor.nameId() != nameId || survivor.imgId() != imgId) continue;
            return survivor.sid();
        }
        return 0L;
    }

    List<Survivor> onBed(long buildingId, long confFid) {
        return this.survivors.values().stream().filter(survivor -> survivor.bedBuildingId() == buildingId && survivor.bedConfFid() == confFid).toList();
    }

    void markAssigned(long sid, long furnitureInstanceId) {
        Survivor survivor = this.survivors.get(sid);
        if (survivor == null) {
            this.survivors.put(sid, new Survivor(sid, 0L, furnitureInstanceId, 0L, 0L, 0L, false));
        } else {
            this.survivors.put(sid, survivor.withFidWork(furnitureInstanceId));
        }
        this.pendingWorkStart.add(sid);
    }

    void removeSurvivor(long sid) {
        if (sid > 0L) {
            this.survivors.remove(sid);
            this.tempSurvivorIds.remove(sid);
            this.pendingWorkStart.remove(sid);
        }
    }

    List<Long> pendingWorkStart() {
        return List.copyOf(this.pendingWorkStart);
    }

    void markWorkStarted(long sid) {
        this.pendingWorkStart.remove(sid);
    }

    record Survivor(long sid, long status, long fidWork, long fidSource, long nameId, long imgId, boolean sick) {
        boolean idle() {
            return this.fidWork <= 0L && !this.sick;
        }

        boolean working() {
            return this.fidWork > 0L;
        }

        long bedBuildingId() {
            return this.fidSource > 0L ? this.fidSource / 1000000L : 0L;
        }

        long bedConfFid() {
            return this.fidSource > 0L ? this.fidSource % 1000L : 0L;
        }

        Survivor withFidWork(long newFidWork) {
            return new Survivor(this.sid, this.status, newFidWork, this.fidSource, this.nameId, this.imgId, this.sick);
        }
    }
}

