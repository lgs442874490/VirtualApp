/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.modules.bootstrap.service.BootstrapMilestoneRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapMilestoneState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapMilestoneService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapMilestoneService.class);
    static final String ACHIEVEMENT_INFO = "req_achievement_info";
    static final String ACHIEVEMENT_REWARD = "req_achievement_get_reward";
    private static final int ERR_UNFINISHED = 7901;
    private static final int ERR_ALREADY_GET = 7902;
    private static final int ERR_NOT_EXIST = 7903;
    private static final int ERR_TYPE_ERROR = 7904;
    private static final int ERR_CONF_NOT_EXIST = 7905;
    private static final long INFO_INTERVAL_MILLIS = 600000L;
    private static final long CLAIM_INTERVAL_MILLIS = 150L;
    private final GameAccountSessionService sessions;
    private final BootstrapMilestoneRegistry registry;

    BootstrapMilestoneService(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessions, BootstrapMilestoneRegistry registry) {
        this.sessions = sessions;
        this.registry = registry;
    }

    Optional<Map<String, Object>> refreshInfo(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            return Optional.empty();
        }
        BootstrapMilestoneState state = this.registry.milestoneState(accountRoleId);
        long now = System.currentTimeMillis();
        if (now - state.lastQueryMillis() < 600000L) {
            return Optional.empty();
        }
        state.markQueried(now);
        try {
            GameMessage response = this.sessions.onlineSession(accountRoleId).request(ACHIEVEMENT_INFO, Map.of());
            state.applyInfos(BootstrapProtocolSupport.content(response).get("infos"));
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("accountRoleId", accountRoleId);
            result.put("status", "STARTED");
            result.put("message", "\u5df2\u540c\u6b65\u91cc\u7a0b\u7891\u5217\u8868\uff08" + state.total() + " \u9879\uff09");
            result.put("milestoneAction", "INFO");
            return Optional.of(result);
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u67e5\u8be2\u91cc\u7a0b\u7891\u5217\u8868\u5931\u8d25 message={}", (Object)accountRoleId, (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    List<Long> claimMilestones(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            return List.of();
        }
        BootstrapMilestoneState state = this.registry.milestoneState(accountRoleId);
        List<BootstrapMilestoneState.Milestone> claimable = state.claimable();
        if (claimable.isEmpty()) {
            return List.of();
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        ArrayList<Long> claimed = new ArrayList<Long>();
        for (BootstrapMilestoneState.Milestone milestone : claimable) {
            try {
                session.request(ACHIEVEMENT_REWARD, Map.of("id", milestone.id()));
                state.markClaimed(milestone.id());
                claimed.add(milestone.id());
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u5df2\u9886\u53d6\u91cc\u7a0b\u7891\u5956\u52b1 id={}", (Object)accountRoleId, (Object)milestone.id());
            }
            catch (GameProtocolException exception) {
                int code = exception.code();
                if (code == 7902) {
                    state.markClaimed(milestone.id());
                    continue;
                }
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u9886\u53d6\u91cc\u7a0b\u7891 {} \u88ab\u62d2\u7edd code={}", new Object[]{accountRoleId, milestone.id(), code});
                if (code == 7901 || code == 7903 || code == 7904 || code == 7905) {
                    continue;
                }
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u9886\u53d6\u91cc\u7a0b\u7891 {} \u5931\u8d25 message={}", new Object[]{accountRoleId, milestone.id(), exception.getMessage()});
            }
            BootstrapMilestoneService.sleepClaimInterval();
        }
        return List.copyOf(claimed);
    }

    Map<String, Object> view(long accountRoleId) {
        BootstrapMilestoneState state = this.registry.milestoneState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("infoReceived", state.infoReceived());
        result.put("total", state.total());
        result.put("claimed", state.claimedCount());
        result.put("pending", state.claimable().stream().map(BootstrapMilestoneState.Milestone::id).toList());
        return result;
    }

    private static void sleepClaimInterval() {
        try {
            Thread.sleep(150L);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

