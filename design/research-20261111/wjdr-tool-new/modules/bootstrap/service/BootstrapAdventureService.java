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
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialState;
import com.wjdr.tool.modules.daily.service.IntelligenceQuestSupport;
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
public final class BootstrapAdventureService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdventureService.class);
    static final String ADVENTURE_BEGIN = "req_begin_adventure_pve";
    static final String ADVENTURE_END = "req_end_adventure_pve";
    private static final long BATTLE_VERSION = 26042101L;
    private static final long CLIENT_SQUAD_ID = 1L;
    private static final int SQUAD_SLOT_COUNT = 5;
    private static final long FAIL_RETRY_MILLIS = 600000L;
    private static final long ERROR_RETRY_MILLIS = 600000L;
    private final GameAccountSessionService sessions;
    private final BootstrapTutorialRegistry tutorialStates;
    private final BootstrapBuildingStateRegistry buildingStates;

    BootstrapAdventureService(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessions, BootstrapTutorialRegistry tutorialStates, BootstrapBuildingStateRegistry buildingStates) {
        this.sessions = sessions;
        this.tutorialStates = tutorialStates;
        this.buildingStates = buildingStates;
    }

    Optional<Map<String, Object>> advance(long accountRoleId, long targetCheckpoint) {
        long now;
        if (!this.sessions.hasSession(accountRoleId) || targetCheckpoint <= 0L) {
            return Optional.empty();
        }
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        if (!tutorial.adventureReady() || tutorial.adventurePassed() >= targetCheckpoint) {
            return Optional.empty();
        }
        long checkpoint = tutorial.adventurePassed() + 1L;
        String retryKey = "adventure:checkpoint:" + checkpoint;
        if (!tutorial.retryAllowed(retryKey, now = System.currentTimeMillis())) {
            return Optional.empty();
        }
        IntelligenceQuestSupport.ClientSquad squad = IntelligenceQuestSupport.clientSquad(this.buildingStates.state(accountRoleId));
        if (squad == null || !squad.ready()) {
            return Optional.empty();
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        LinkedHashMap<String, Object> beginPayload = new LinkedHashMap<String, Object>();
        beginPayload.put("checkpoint", checkpoint);
        beginPayload.put("fight_heros_data", Map.of("source", 1L, "fight_heros", BootstrapAdventureService.padSquad(squad.heroes())));
        try {
            GameMessage begin = session.request(ADVENTURE_BEGIN, beginPayload);
            Map<String, Object> beginContent = BootstrapProtocolSupport.content(begin);
            if (BootstrapProtocolSupport.booleanValue(beginContent.get("is_fast"))) {
                tutorial.markAdventurePassed(checkpoint);
                tutorial.clearRetry(retryKey);
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u5feb\u901f\u63a2\u9669\u901a\u5173\u7b2c {} \u5173", (Object)accountRoleId, (Object)checkpoint);
                return Optional.of(BootstrapAdventureService.result(accountRoleId, "ADVENTURE_FAST", "\u5feb\u901f\u63a2\u9669\u901a\u5173\u7b2c " + checkpoint + " \u5173", checkpoint, true));
            }
            LinkedHashMap<String, Object> endPayload = new LinkedHashMap<String, Object>();
            endPayload.put("checkpoint", checkpoint);
            endPayload.put("operatedata", List.of());
            endPayload.put("enddata", List.of());
            endPayload.put("version", 26042101L);
            GameMessage end = session.request(ADVENTURE_END, endPayload);
            Map<String, Object> endContent = BootstrapProtocolSupport.content(end);
            if (BootstrapProtocolSupport.booleanValue(endContent.get("is_win"))) {
                tutorial.markAdventurePassed(checkpoint);
                tutorial.clearRetry(retryKey);
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u901a\u5173\u63a2\u9669\u7b2c {} \u5173", (Object)accountRoleId, (Object)checkpoint);
                return Optional.of(BootstrapAdventureService.result(accountRoleId, "ADVENTURE_CLEAR", "\u901a\u5173\u63a2\u9669\u7b2c " + checkpoint + " \u5173", checkpoint, true));
            }
            tutorial.markRetry(retryKey, now + 600000L);
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u63a2\u9669\u7b2c {} \u5173\u6218\u529b\u4e0d\u8db3\uff0c30 \u5206\u949f\u540e\u518d\u8bd5", (Object)accountRoleId, (Object)checkpoint);
            return Optional.of(BootstrapAdventureService.result(accountRoleId, "ADVENTURE_FAILED", "\u63a2\u9669\u7b2c " + checkpoint + " \u5173\u6218\u529b\u4e0d\u8db3\uff0c\u7b49\u82f1\u96c4\u5347\u7ea7\u540e\u518d\u8bd5", checkpoint, false));
        }
        catch (GameProtocolException exception) {
            tutorial.markRetry(retryKey, now + 600000L);
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u63a2\u9669\u7b2c {} \u5173\u88ab\u62d2\u7edd code={}", new Object[]{accountRoleId, checkpoint, exception.code()});
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            tutorial.markRetry(retryKey, now + 600000L);
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u63a2\u9669\u7b2c {} \u5173\u5931\u8d25 message={}", new Object[]{accountRoleId, checkpoint, exception.getMessage()});
            return Optional.empty();
        }
    }

    Map<String, Object> view(long accountRoleId) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ready", tutorial.adventureReady());
        result.put("passCheckpoint", tutorial.adventurePassed());
        return result;
    }

    private static List<Long> padSquad(List<Long> heroes) {
        ArrayList<Long> padded = new ArrayList<Long>(5);
        for (int index = 0; index < 5; ++index) {
            long heroId = heroes != null && index < heroes.size() && heroes.get(index) != null ? heroes.get(index) : 0L;
            padded.add(Math.max(0L, heroId));
        }
        return List.copyOf(padded);
    }

    private static Map<String, Object> result(long accountRoleId, String kind, String message, long checkpoint, boolean passed) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("status", passed ? "STARTED" : "WAITING");
        result.put("message", message);
        result.put("adventureAction", kind);
        result.put("checkpoint", checkpoint);
        return result;
    }
}

