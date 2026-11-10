/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.modules.behemoth.model.BehemothAccountStateFormationSlot;
import com.wjdr.tool.modules.behemoth.service.BehemothAccountState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapIntelligenceRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialState;
import com.wjdr.tool.modules.daily.service.DailyIntelligenceState;
import com.wjdr.tool.modules.daily.service.IntelligenceQuestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapWorldService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapWorldService.class);
    private static final String KINGDOM_INFO = "req_world_kingdom_info";
    private static final String MAP_FOG = "req_world_map_fog";
    private static final String MARCH_RETRY_KEY_PREFIX = "world:slg:";
    private static final String WORLD_MARCH = "req_world_march";
    private static final String DEL_WATCH = "req_world_del_watch";
    private static final long WORLD_UNLOCK_FURNACE_LEVEL = 7L;
    private static final long MARCH_RETRY_INTERVAL_MILLIS = 600000L;
    private final BootstrapTutorialRegistry tutorialStates;
    private final BootstrapBuildingStateRegistry buildingStates;
    private final BootstrapIntelligenceRegistry intelligenceStates;

    BootstrapWorldService(BootstrapTutorialRegistry tutorialStates, BootstrapBuildingStateRegistry buildingStates, BootstrapIntelligenceRegistry intelligenceStates) {
        this.tutorialStates = tutorialStates;
        this.buildingStates = buildingStates;
        this.intelligenceStates = intelligenceStates;
    }

    Optional<Map<String, Object>> advance(long accountRoleId, GameAccountSessionService.AccountSession session) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        BootstrapBuildingState buildings = this.buildingStates.buildingState(accountRoleId);
        if (buildings.level(301L) < 7L) {
            return Optional.empty();
        }
        DailyIntelligenceState.Snapshot snapshot = this.intelligenceStates.intelligenceState(accountRoleId).snapshot();
        if (!snapshot.initialized()) {
            return Optional.empty();
        }
        if (!tutorial.worldKingdomInfoSent()) {
            return this.send(accountRoleId, session, tutorial, "worldKingdomInfo", KINGDOM_INFO, Map.of(), BootstrapTutorialState::markWorldKingdomInfoSent);
        }
        if (!tutorial.worldFogSent() && tutorial.kingdomId() > 0L) {
            return this.send(accountRoleId, session, tutorial, "worldFog", MAP_FOG, Map.of("kid", tutorial.kingdomId()), BootstrapTutorialState::markWorldFogSent);
        }
        Optional<Map<String, Object>> rescue = this.rescueEvent(accountRoleId, session, tutorial, snapshot);
        if (rescue.isPresent()) {
            return rescue;
        }
        Optional<Map<String, Object>> monster = this.marchSlgMonster(accountRoleId, session, tutorial, snapshot);
        if (monster.isPresent()) {
            return monster;
        }
        if (!tutorial.worldDelWatchSent() && snapshot.events().isEmpty()) {
            return this.send(accountRoleId, session, tutorial, "worldDelWatch", DEL_WATCH, Map.of(), BootstrapTutorialState::markWorldDelWatchSent);
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> rescueEvent(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, DailyIntelligenceState.Snapshot snapshot) {
        for (DailyIntelligenceState.IntelligenceEvent event : snapshot.events()) {
            if (!IntelligenceQuestSupport.isRescueEvent(event.type()) || tutorial.rescued(event.id()) || event.x() <= 0L || event.y() <= 0L) continue;
            Map<String, Object> payload = IntelligenceQuestSupport.rescueMarchPayload(event.id(), event.x(), event.y());
            try {
                session.request(WORLD_MARCH, payload);
                tutorial.markRescueMarched(event.id());
                log.info("[\u8d77\u53f7] \u5df2\u5bf9\u60c5\u62a5\u4e8b\u4ef6 {} \u53d1\u8d77\u8425\u6551\u884c\u519b\uff0c\u7ec8\u70b9 {}:{}", new Object[]{event.id(), event.x(), event.y()});
                return Optional.of(BootstrapWorldService.action(accountRoleId, "worldRescue", "\u5df2\u5bf9\u60c5\u62a5\u4e8b\u4ef6 " + event.id() + " \u53d1\u8d77\u8425\u6551\u884c\u519b"));
            }
            catch (GameProtocolException exception) {
                tutorial.markRescueMarched(event.id());
                log.info("[\u8d77\u53f7] \u8425\u6551\u884c\u519b\u88ab\u62d2\u7edd eventId={} code={}", (Object)event.id(), (Object)exception.code());
                return Optional.empty();
            }
            catch (RuntimeException exception) {
                tutorial.markRescueMarched(event.id());
                log.info("[\u8d77\u53f7] \u8425\u6551\u884c\u519b\u5931\u8d25 eventId={} message={}", (Object)event.id(), (Object)exception.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> marchSlgMonster(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, DailyIntelligenceState.Snapshot snapshot) {
        BehemothAccountState accountState = this.buildingStates.state(accountRoleId);
        long now = System.currentTimeMillis();
        for (DailyIntelligenceState.IntelligenceEvent event : snapshot.events()) {
            String retryKey = MARCH_RETRY_KEY_PREFIX + event.id();
            if (!IntelligenceQuestSupport.isSlgMonsterEvent(event.type()) || tutorial.slgMarched(event.id()) || event.x() <= 0L || event.y() <= 0L || !tutorial.retryAllowed(retryKey, now)) continue;
            Formation formation = BootstrapWorldService.resolveFormation(accountState);
            if (formation == null) {
                tutorial.markRetry(retryKey, now + 600000L);
                log.info("[\u8d77\u53f7] \u60c5\u62a5\u602a\u7269 {} \u6ca1\u6709\u53ef\u7528\u7f16\u961f\uff08\u82f1\u96c4\u6216\u58eb\u5175\u4e0d\u8db3\uff09\uff0c\u6682\u4e0d\u51fa\u5f81", (Object)event.id());
                return Optional.empty();
            }
            Map<String, Object> payload = IntelligenceQuestSupport.marchPayload(event.id(), event.x(), event.y(), formation.heroes(), formation.soldiers(), false);
            try {
                session.request(WORLD_MARCH, payload);
                tutorial.markSlgMarched(event.id());
                log.info("[\u8d77\u53f7] \u5df2\u5bf9\u60c5\u62a5\u602a\u7269 {} \u53d1\u8d77\u4e16\u754c\u884c\u519b\uff0c\u7ec8\u70b9 {}:{}", new Object[]{event.id(), event.x(), event.y()});
                return Optional.of(BootstrapWorldService.action(accountRoleId, "worldSlgMarch", "\u5df2\u5bf9\u60c5\u62a5\u602a\u7269 " + event.id() + " \u53d1\u8d77\u4e16\u754c\u884c\u519b"));
            }
            catch (GameProtocolException exception) {
                tutorial.markRetry(retryKey, now + 600000L);
                log.info("[\u8d77\u53f7] \u60c5\u62a5\u602a\u7269 {} \u51fa\u5f81\u88ab\u62d2\u7edd code={}", (Object)event.id(), (Object)exception.code());
                return Optional.empty();
            }
            catch (RuntimeException exception) {
                tutorial.markRetry(retryKey, now + 600000L);
                log.info("[\u8d77\u53f7] \u60c5\u62a5\u602a\u7269 {} \u51fa\u5f81\u5931\u8d25 message={}", (Object)event.id(), (Object)exception.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Formation resolveFormation(BehemothAccountState accountState) {
        if (accountState == null) {
            return null;
        }
        for (BehemothAccountStateFormationSlot slot : accountState.gameFormationPrefabs()) {
            List<Map<String, Object>> soldiers;
            if (slot.heroPairs().isEmpty() || (soldiers = slot.soldierPairs().isEmpty() ? BehemothAccountStateFormationSlot.resolveRatioSoldiers(slot.soldierRates(), accountState.availableSoldierCounts(), accountState.normalMarchCapacity(BootstrapWorldService.heroIds(slot.heroPairs()))) : slot.soldierPairs()).isEmpty()) continue;
            return new Formation(slot.heroPairs(), soldiers);
        }
        List<Map<String, Object>> heroes = accountState.idleHeroFormation(Set.of());
        if (heroes.isEmpty()) {
            return null;
        }
        long available = accountState.availableSoldierTotal(Map.of());
        long capacity = accountState.normalMarchCapacity(BootstrapWorldService.heroIds(heroes));
        long limit = capacity > 0L ? Math.min(capacity, available) : available;
        List<Map<String, Object>> soldiers = accountState.evenlyDistributedSoldiers(limit, 1, Map.of());
        return soldiers.isEmpty() ? null : new Formation(heroes, soldiers);
    }

    private static List<Long> heroIds(List<Map<String, Object>> heroPairs) {
        return heroPairs.stream().map(pair -> BootstrapProtocolSupport.number(pair.get("v"))).filter(heroId -> heroId > 0L).toList();
    }

    private Optional<Map<String, Object>> send(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, String kind, String proto, Map<String, Object> payload, Consumer<BootstrapTutorialState> marker) {
        try {
            session.request(proto, payload);
            marker.accept(tutorial);
            return Optional.of(BootstrapWorldService.action(accountRoleId, kind, "\u5df2\u53d1\u9001 " + proto));
        }
        catch (GameProtocolException exception) {
            log.info("[\u8d77\u53f7] {} \u88ab\u62d2\u7edd code={}", (Object)proto, (Object)exception.code());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] {} \u5931\u8d25 message={}", (Object)proto, (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> action(long accountRoleId, String kind, String message) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("status", "STARTED");
        result.put("message", "\u8d77\u53f7\u4e16\u754c\u5730\u56fe\u5df2\u63a8\u8fdb\uff1a" + message);
        result.put("worldAction", kind);
        return result;
    }

    public Map<String, Object> view(long accountRoleId) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("kingdomId", tutorial.kingdomId());
        result.put("kingdomInfoSent", tutorial.worldKingdomInfoSent());
        result.put("fogSent", tutorial.worldFogSent());
        result.put("delWatchSent", tutorial.worldDelWatchSent());
        result.put("rescuedEvents", tutorial.rescueMarchedCount());
        result.put("slgMarchedEvents", tutorial.slgMarchedCount());
        return result;
    }

    private record Formation(List<Map<String, Object>> heroes, List<Map<String, Object>> soldiers) {
    }
}

