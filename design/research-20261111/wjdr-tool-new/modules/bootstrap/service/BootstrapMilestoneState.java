/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BootstrapMilestoneState {
    private final Map<Long, Milestone> milestones = new LinkedHashMap<Long, Milestone>();
    private boolean infoReceived;
    private long lastQueryMillis;

    BootstrapMilestoneState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        if ("notify_achievement_info".equals(message.getProto())) {
            this.applyInfos(message.getContent().get("infos"));
        }
    }

    void applyInfos(Object raw) {
        for (Map<String, Object> entry : BootstrapProtocolSupport.maps(raw)) {
            long id = ProtocolValueSupport.number(entry.get("id"));
            if (id <= 0L) continue;
            this.milestones.put(id, new Milestone(id, BootstrapProtocolSupport.booleanValue(entry.get("complete")), BootstrapProtocolSupport.booleanValue(entry.get("received")), ProtocolValueSupport.number(entry.get("start_ts")), ProtocolValueSupport.number(entry.get("end_ts"))));
        }
        this.infoReceived = true;
    }

    boolean infoReceived() {
        return this.infoReceived;
    }

    long lastQueryMillis() {
        return this.lastQueryMillis;
    }

    void markQueried(long nowMillis) {
        this.lastQueryMillis = nowMillis;
    }

    List<Milestone> claimable() {
        ArrayList result = new ArrayList();
        this.milestones.values().stream().filter(Milestone::claimable).sorted(Comparator.comparingLong(Milestone::id)).forEach(result::add);
        return List.copyOf(result);
    }

    void markClaimed(long id) {
        Milestone current = this.milestones.get(id);
        if (current != null) {
            this.milestones.put(id, new Milestone(id, current.complete(), true, current.startTs(), current.endTs()));
        }
    }

    int total() {
        return this.milestones.size();
    }

    long claimedCount() {
        return this.milestones.values().stream().filter(Milestone::received).count();
    }

    record Milestone(long id, boolean complete, boolean received, long startTs, long endTs) {
        boolean claimable() {
            return this.complete && !this.received;
        }
    }
}

