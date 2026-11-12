/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BootstrapTutorialState {
    private static final String GUIDE_INIT = "notify_guide_init";
    private static final String UNLOCK_INFO = "notify_unlock_module_info";
    private static final String PRODUCED_INIT = "notify_produced_resource_init";
    private static final String PRODUCED_UPDATE = "notify_produced_resource_update";
    private static final String HERO_INIT = "notify_hero_init";
    private static final String HERO_UPDATE = "notify_hero_update";
    private static final String HERO_SEARCH = "notify_hero_search_data";
    private static final String ADVENTURE_INFO = "notify_adventure_pve_info";
    private static final String INTELLIGENCE_INFO = "notify_intelligence_info";
    private static final String INTELLIGENCE_EVENTS = "notify_intelligence_events";
    private static final String INTELLIGENCE_EVENTS_DEL = "notify_intelligence_events_del";
    private static final String WORLD_PLAYER_INIT = "notify_world_player_init";
    private static final String SNOW_GUIDE_INFO = "notify_snow_guide_info";
    private static final String SNOW_GUIDE_TRIGGER = "notify_snow_guide_trigger";
    private final Map<Long, Long> guideSteps = new LinkedHashMap<Long, Long>();
    private final Set<Long> unlockedModules = new LinkedHashSet<Long>();
    private final Map<Long, Long> producedResources = new LinkedHashMap<Long, Long>();
    private final Map<Long, Long> heroLevels = new LinkedHashMap<Long, Long>();
    private final Map<Long, Long> heroFragments = new LinkedHashMap<Long, Long>();
    private final Set<Long> intelligenceReceived = new LinkedHashSet<Long>();
    private final Set<Long> rescuedEventIds = new LinkedHashSet<Long>();
    private final Set<Long> slgMarchedEventIds = new LinkedHashSet<Long>();
    private boolean guideReady;
    private boolean unlockReady;
    private boolean producedReady;
    private boolean heroReady;
    private boolean heroSearchReady;
    private boolean epicSearched;
    private boolean epicSearchRequested;
    private long heroUpgradeSuccess;
    private long adventurePassed = -1L;
    private boolean intelligenceReady;
    private long snowStormId;
    private long kingdomId;
    private boolean worldKingdomInfoSent;
    private boolean worldFogSent;
    private boolean worldDelWatchSent;
    private long lastProducedRefreshMillis;
    private String lastUnlockSignature = "";
    private final Map<String, Long> retryAtMillis = new LinkedHashMap<String, Long>();
    private long lastElderRequestMillis;
    private boolean elderRequested;
    private final Set<Long> bypassedGuides = new LinkedHashSet<Long>();

    BootstrapTutorialState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        Map<String, Object> data = message.getContent();
        switch (message.getProto()) {
            case "notify_guide_init": {
                this.applyGuideInit(data);
                break;
            }
            case "notify_unlock_module_info": {
                this.applyUnlockInfo(data);
                break;
            }
            case "notify_produced_resource_init": {
                this.producedResources.clear();
                this.applyProduced(data.get("data"));
                this.producedReady = true;
                break;
            }
            case "notify_produced_resource_update": {
                this.applyProduced(data.get("data"));
                this.producedReady = true;
                break;
            }
            case "notify_hero_init": {
                this.heroLevels.clear();
                this.applyHeroes(data.get("data"));
                this.heroReady = true;
                break;
            }
            case "notify_hero_update": {
                this.applyHero(data.get("data"));
                this.applyHeroFragments(data.get("data"), data.get("fragment_num"));
                this.heroReady = true;
                break;
            }
            case "notify_hero_search_data": {
                this.epicSearched = BootstrapProtocolSupport.booleanValue(data.get("epic_first_rearch")) || this.epicSearched;
                this.heroSearchReady = true;
                break;
            }
            case "notify_adventure_pve_info": {
                this.applyAdventure(data.get("adventure_pve_info"));
                break;
            }
            case "notify_intelligence_info": 
            case "notify_intelligence_events": {
                this.intelligenceReady = true;
                break;
            }
            case "notify_intelligence_events_del": {
                for (Object raw : BootstrapProtocolSupport.iterable(data.get("ids"))) {
                    long id = BootstrapProtocolSupport.number(raw);
                    if (id <= 0L) continue;
                    this.intelligenceReceived.add(id);
                }
                break;
            }
            case "notify_world_player_init": {
                this.applyKingdom(data);
                break;
            }
            case "notify_snow_guide_info": 
            case "notify_snow_guide_trigger": {
                this.applySnowGuide(data.get("snow_guide"));
                break;
            }
        }
    }

    private void applyGuideInit(Map<String, Object> data) {
        this.guideSteps.clear();
        for (Map<String, Object> entry : ProtocolValueSupport.keyedMapList(data.get("data"))) {
            long id = BootstrapProtocolSupport.number(entry.get("id"));
            if (id <= 0L) continue;
            this.guideSteps.put(id, BootstrapProtocolSupport.number(entry.get("step")));
        }
        this.guideReady = true;
    }

    private void applyUnlockInfo(Map<String, Object> data) {
        this.unlockedModules.clear();
        for (Object raw : BootstrapProtocolSupport.iterable(data.get("module_unlocked"))) {
            long module = BootstrapProtocolSupport.number(raw);
            if (module <= 0L) continue;
            this.unlockedModules.add(module);
        }
        this.unlockReady = true;
    }

    private void applyProduced(Object raw) {
        for (Map<String, Object> entry : ProtocolValueSupport.keyedMapList(raw)) {
            long id = BootstrapProtocolSupport.number(entry.get("id"));
            if (id <= 0L) continue;
            this.producedResources.put(id, BootstrapProtocolSupport.number(entry.get("count")));
        }
    }

    private void applyHeroes(Object raw) {
        ProtocolValueSupport.keyedMapList(raw).forEach(this::applyHero);
    }

    private void applyHero(Object raw) {
        Map<String, Object> hero = BootstrapProtocolSupport.map(raw);
        long id = BootstrapProtocolSupport.number(hero.get("id"));
        if (id <= 0L) {
            return;
        }
        this.heroLevels.put(id, Math.max(0L, BootstrapProtocolSupport.number(hero.get("lev"))));
    }

    private void applyHeroFragments(Object raw, Object rawFragments) {
        Map<String, Object> hero = BootstrapProtocolSupport.map(raw);
        long id = BootstrapProtocolSupport.number(hero.get("id"));
        if (id <= 0L) {
            return;
        }
        this.heroFragments.put(id, Math.max(0L, BootstrapProtocolSupport.number(rawFragments)));
    }

    private void applyAdventure(Object raw) {
        Map<String, Object> info = BootstrapProtocolSupport.map(raw);
        if (info.isEmpty()) {
            return;
        }
        this.adventurePassed = Math.max(0L, BootstrapProtocolSupport.number(info.get("pass_checkpoint")));
    }

    private void applyKingdom(Map<String, Object> data) {
        for (String key : List.of("kid", "kingdom_id", "kingdom_count", "world_kid")) {
            long value = BootstrapProtocolSupport.number(data.get(key));
            if (value <= 0L) continue;
            this.kingdomId = value;
            return;
        }
    }

    private void applySnowGuide(Object raw) {
        Map<String, Object> guide = BootstrapProtocolSupport.map(raw);
        long id = BootstrapProtocolSupport.number(guide.get("id"));
        if (id > 0L) {
            this.snowStormId = Math.max(this.snowStormId, id);
        }
    }

    boolean guideReady() {
        return this.guideReady;
    }

    long guideStep(long guideId) {
        return this.guideSteps.getOrDefault(guideId, 0L);
    }

    void markGuideStep(long guideId, long step) {
        if (guideId <= 0L || step <= 0L) {
            return;
        }
        this.guideSteps.merge(guideId, step, Math::max);
    }

    boolean unlockReady() {
        return this.unlockReady;
    }

    Set<Long> unlockedModules() {
        return Set.copyOf(this.unlockedModules);
    }

    void markModuleUnlocked(long module) {
        if (module > 0L) {
            this.unlockedModules.add(module);
        }
    }

    Map<Long, Long> producedResources() {
        return Map.copyOf(this.producedResources);
    }

    boolean producedReady() {
        return this.producedReady;
    }

    long lastProducedRefreshMillis() {
        return this.lastProducedRefreshMillis;
    }

    void markProducedRefreshed(long nowMillis) {
        this.lastProducedRefreshMillis = nowMillis;
    }

    String lastUnlockSignature() {
        return this.lastUnlockSignature;
    }

    void markUnlockAttempted(String signature) {
        this.lastUnlockSignature = signature == null ? "" : signature;
    }

    boolean heroReady() {
        return this.heroReady;
    }

    boolean heroSearchReady() {
        return this.heroSearchReady;
    }

    boolean epicSearched() {
        return this.epicSearched;
    }

    boolean epicSearchRequested() {
        return this.epicSearchRequested;
    }

    void markEpicSearchRequested() {
        this.epicSearchRequested = true;
    }

    void markEpicSearched() {
        this.epicSearched = true;
    }

    Map<Long, Long> heroLevels() {
        return Map.copyOf(this.heroLevels);
    }

    long heroUpgradeSuccess() {
        return this.heroUpgradeSuccess;
    }

    void markHeroUpgrade(boolean success) {
        if (success) {
            ++this.heroUpgradeSuccess;
        }
    }

    boolean adventureReady() {
        return this.adventurePassed >= 0L;
    }

    long adventurePassed() {
        return Math.max(0L, this.adventurePassed);
    }

    void markAdventurePassed(long checkpoint) {
        this.adventurePassed = Math.max(this.adventurePassed, checkpoint);
    }

    boolean intelligenceReady() {
        return this.intelligenceReady;
    }

    List<Long> heroesWithFragments(long minimum) {
        return this.heroFragments.entrySet().stream().filter(entry -> (Long)entry.getValue() >= minimum).sorted((left, right) -> Long.compare((Long)right.getValue(), (Long)left.getValue())).map(Map.Entry::getKey).toList();
    }

    long highestLevelHero() {
        long best = 0L;
        long bestLevel = Long.MIN_VALUE;
        for (Map.Entry<Long, Long> entry : this.heroLevels.entrySet()) {
            if (best != 0L && entry.getValue() <= bestLevel) continue;
            bestLevel = entry.getValue();
            best = entry.getKey();
        }
        return best;
    }

    long kingdomId() {
        return this.kingdomId;
    }

    boolean worldKingdomInfoSent() {
        return this.worldKingdomInfoSent;
    }

    void markWorldKingdomInfoSent() {
        this.worldKingdomInfoSent = true;
    }

    boolean worldFogSent() {
        return this.worldFogSent;
    }

    void markWorldFogSent() {
        this.worldFogSent = true;
    }

    boolean worldDelWatchSent() {
        return this.worldDelWatchSent;
    }

    void markWorldDelWatchSent() {
        this.worldDelWatchSent = true;
    }

    boolean rescued(long eventId) {
        return this.rescuedEventIds.contains(eventId);
    }

    void markRescueMarched(long eventId) {
        if (eventId > 0L) {
            this.rescuedEventIds.add(eventId);
        }
    }

    long rescueMarchedCount() {
        return this.rescuedEventIds.size();
    }

    boolean slgMarched(long eventId) {
        return this.slgMarchedEventIds.contains(eventId);
    }

    void markSlgMarched(long eventId) {
        if (eventId > 0L) {
            this.slgMarchedEventIds.add(eventId);
        }
    }

    long slgMarchedCount() {
        return this.slgMarchedEventIds.size();
    }

    long receivedIntelligenceCount() {
        return this.intelligenceReceived.size();
    }

    long snowStormId() {
        return this.snowStormId;
    }

    Set<Long> bypassedGuides() {
        return Set.copyOf(this.bypassedGuides);
    }

    void markGuideBypassed(long guideId) {
        this.bypassedGuides.add(guideId);
    }

    boolean retryAllowed(String key, long nowMillis) {
        return nowMillis >= this.retryAtMillis.getOrDefault(key, 0L);
    }

    void markRetry(String key, long untilMillis) {
        this.retryAtMillis.put(key, Math.max(0L, untilMillis));
    }

    void clearRetry(String key) {
        this.retryAtMillis.remove(key);
    }

    boolean elderRequested() {
        return this.elderRequested;
    }

    long lastElderRequestMillis() {
        return this.lastElderRequestMillis;
    }

    void markElderRequested(long nowMillis, boolean success) {
        this.lastElderRequestMillis = nowMillis;
        this.elderRequested = this.elderRequested || success;
    }
}

