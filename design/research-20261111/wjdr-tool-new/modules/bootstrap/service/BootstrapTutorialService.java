/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  java.lang.MatchException
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialCatalog;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapTutorialService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapTutorialService.class);
    private static final String GUIDE_SET_STEP = "req_guide_set_step";
    private static final String UNLOCK_MODULE = "req_unlock_module";
    private static final String PRODUCED_NEED_UPDATE = "req_produced_resource_need_update";
    private static final String GUIDE_COME_SURVIVOR = "req_guide_come_survivor";
    private static final String GUIDE_DEAD_SURVIVOR = "req_guide_dead_survivor";
    private static final long SURVIVOR_JOIN_ACCEPT_ALL = 1L;
    private static final long PRODUCED_REFRESH_INTERVAL_MILLIS = 60000L;
    private static final long GUIDE_STEP_INTERVAL_MILLIS = 100L;
    private static final boolean IGNORE_GUIDE = false;
    private static final long GUIDE_STEP_JITTER_MILLIS = 50L;
    private final Map<Long, Long> lastGuideStepMillis = new ConcurrentHashMap<Long, Long>();
    private static final long ELDER_RETRY_INTERVAL_MILLIS = 1000L;
    private static final long GUIDE_STEP_RETRY_INTERVAL_MILLIS = 30000L;
    private final BootstrapTutorialRegistry tutorialStates;
    private final BootstrapBuildingStateRegistry buildingStates;
    private final BootstrapSurvivorRegistry survivorStates;
    private static final int GUIDE_STEP_ALREADY_ADVANCED = 2;

    BootstrapTutorialService(BootstrapTutorialRegistry tutorialStates, BootstrapBuildingStateRegistry buildingStates, BootstrapSurvivorRegistry survivorStates) {
        this.tutorialStates = tutorialStates;
        this.buildingStates = buildingStates;
        this.survivorStates = survivorStates;
    }

    Optional<Map<String, Object>> advance(long accountRoleId, GameAccountSessionService.AccountSession session) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        BootstrapBuildingState buildings = this.buildingStates.buildingState(accountRoleId);
        BootstrapSurvivorState survivors = this.survivorStates.survivorState(accountRoleId);
        ArrayList<Object> actions = new ArrayList<Object>();
        ArrayList<Long> reportedGuides = new ArrayList<Long>();
        ArrayList<Long> unlockedModules = new ArrayList<Long>();
        Optional<String> guideAction = this.advanceGuides(session, tutorial, buildings, survivors, reportedGuides);
        if (guideAction.isPresent()) {
            actions.add(guideAction.get());
        } else {
            Optional<String> bypass = this.bypassPostGuides(session, tutorial, buildings);
            if (bypass.isPresent()) {
                actions.add(bypass.get());
            } else {
                unlockedModules.addAll(this.unlockEligibleModules(session, tutorial, buildings));
                if (!unlockedModules.isEmpty()) {
                    actions.add("\u89e3\u9501\u6a21\u5757 " + String.valueOf(unlockedModules));
                }
                if (this.refreshProducedResources(session, tutorial, buildings)) {
                    actions.add("\u5237\u65b0\u751f\u4ea7\u8d44\u6e90");
                }
            }
        }
        if (actions.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("status", "STARTED");
        result.put("message", "\u8d77\u53f7\u6559\u7a0b\u5df2\u63a8\u8fdb\uff1a" + String.join((CharSequence)"\uff1b", actions));
        result.put("tutorialActions", List.copyOf(actions));
        if (!reportedGuides.isEmpty()) {
            result.put("guideSteps", List.copyOf(reportedGuides));
        }
        if (!unlockedModules.isEmpty()) {
            result.put("unlockedModules", List.copyOf(unlockedModules));
        }
        return Optional.of(result);
    }

    boolean retryAllowed(long accountRoleId, String key) {
        return this.tutorialStates.tutorialState(accountRoleId).retryAllowed(key, System.currentTimeMillis());
    }

    void markRetry(long accountRoleId, String key, long untilMillis) {
        this.tutorialStates.tutorialState(accountRoleId).markRetry(key, untilMillis);
    }

    long adventurePassed(long accountRoleId) {
        return this.tutorialStates.tutorialState(accountRoleId).adventurePassed();
    }

    public Map<String, Object> view(long accountRoleId) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("guideReady", tutorial.guideReady());
        result.put("unlockReady", tutorial.unlockReady());
        result.put("producedReady", tutorial.producedReady());
        result.put("heroReady", tutorial.heroReady());
        result.put("adventurePassed", tutorial.adventurePassed());
        result.put("intelligenceReady", tutorial.intelligenceReady());
        result.put("snowStormId", tutorial.snowStormId());
        result.put("elderRequested", tutorial.elderRequested());
        ArrayList guides = new ArrayList();
        for (BootstrapTutorialCatalog.Guide guide : BootstrapTutorialCatalog.guides()) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("id", guide.id());
            entry.put("name", guide.name());
            entry.put("step", tutorial.guideStep(guide.id()));
            entry.put("maxStep", guide.maxStep());
            entry.put("completed", tutorial.guideStep(guide.id()) >= guide.maxStep());
            guides.add(entry);
        }
        result.put("guides", guides);
        result.put("storylineStep", tutorial.guideStep(100101L));
        result.put("storylineMaxStep", ((BootstrapTutorialCatalog.Guide)BootstrapTutorialCatalog.guides().getFirst()).maxStep());
        result.put("unlockedModules", List.copyOf(tutorial.unlockedModules()));
        result.put("producedResources", tutorial.producedResources());
        return result;
    }

    private Optional<String> advanceGuides(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapSurvivorState survivors, List<Long> reportedGuides) {
        if (!tutorial.guideReady()) {
            return Optional.empty();
        }
        for (BootstrapTutorialCatalog.Guide guide : BootstrapTutorialCatalog.guides()) {
            Optional<String> action = this.advanceGuide(session, guide, tutorial, buildings, survivors, reportedGuides);
            if (!action.isPresent()) continue;
            return action;
        }
        return Optional.empty();
    }

    private Optional<String> advanceGuide(GameAccountSessionService.AccountSession session, BootstrapTutorialCatalog.Guide guide, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapSurvivorState survivors, List<Long> reportedGuides) {
        String retryKey;
        long current = tutorial.guideStep(guide.id());
        if (current >= guide.maxStep()) {
            return Optional.empty();
        }
        long target = this.reachableStep(guide, current, tutorial, buildings, survivors);
        if (target <= current) {
            return this.handleBlockedStep(session, guide, current, tutorial, buildings, survivors);
        }
        ArrayList reported = new ArrayList();
        for (long step = current + 1L; step <= target && tutorial.retryAllowed(retryKey = BootstrapTutorialService.guideRetryKey(guide.id(), step), System.currentTimeMillis()); ++step) {
            if (!this.requestGuideStep(session, guide.id(), step)) {
                tutorial.markRetry(retryKey, System.currentTimeMillis() + 30000L);
                break;
            }
            tutorial.clearRetry(retryKey);
            tutorial.markGuideStep(guide.id(), step);
            reported.add(step);
        }
        if (reported.isEmpty()) {
            return Optional.empty();
        }
        reportedGuides.addAll(reported);
        return Optional.of("\u4e0a\u62a5\u5f15\u5bfc " + guide.name() + "(" + guide.id() + ") \u7b2c " + String.valueOf(reported.getFirst()) + "-" + String.valueOf(reported.getLast()) + " \u6b65");
    }

    private long reachableStep(BootstrapTutorialCatalog.Guide guide, long current, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapSurvivorState survivors) {
        BootstrapTutorialCatalog.Step definition;
        long target = current;
        long step = current + 1L;
        while (step <= guide.maxStep() && !this.milestonePending(definition = guide.steps().get((int)step - 1), tutorial, buildings, survivors)) {
            target = step++;
        }
        return target;
    }

    private Optional<String> handleBlockedStep(GameAccountSessionService.AccountSession session, BootstrapTutorialCatalog.Guide guide, long current, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapSurvivorState survivors) {
        if (current + 1L > guide.maxStep()) {
            return Optional.empty();
        }
        BootstrapTutorialCatalog.Step definition = guide.steps().get((int)current);
        return switch (definition.kind()) {
            case BootstrapTutorialCatalog.MilestoneKind.SURVIVOR_JOIN -> this.requestGuideComeSurvivor(session, definition, buildings, survivors);
            case BootstrapTutorialCatalog.MilestoneKind.ELDER_DEATH -> this.requestElderDeath(session, tutorial, buildings, survivors);
            case BootstrapTutorialCatalog.MilestoneKind.ADVENTURE_CLEAR -> this.clearAdventureFirstCheckpoint(session, tutorial);
            case BootstrapTutorialCatalog.MilestoneKind.BUILDING_CREATED -> this.repairLighthouseIfBlocked(session, definition, buildings);
            default -> Optional.empty();
        };
    }

    private boolean milestonePending(BootstrapTutorialCatalog.Step definition, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapSurvivorState survivors) {
        return switch (definition.kind()) {
            default -> throw new MatchException(null, null);
            case BootstrapTutorialCatalog.MilestoneKind.SCRIPTED -> false;
            case BootstrapTutorialCatalog.MilestoneKind.FURNACE_LEVEL, BootstrapTutorialCatalog.MilestoneKind.SNOW_STORM_PENDING -> {
                if (buildings.level(301L) < definition.value()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.BUILDING_CREATED -> {
                if (buildings.level(definition.buildingId()) < 1L) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.FURNITURE_CREATED -> {
                if (BootstrapTutorialService.furnitureLevel(buildings, definition.buildingId(), definition.furnitureId()) < 1L) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.SURVIVOR_ASSIGNED -> {
                long instanceId = buildings.furnitureInstanceId(definition.buildingId(), definition.furnitureId());
                if (instanceId <= 0L || !survivors.assignedTo(instanceId)) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.SURVIVOR_JOIN -> {
                if (!(buildings.level(301L) >= 2L || BootstrapTutorialService.furnitureLevel(buildings, definition.buildingId(), definition.furnitureId()) >= 1L && survivors.guideSelected())) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.ELDER_DEATH -> {
                if (!tutorial.elderRequested()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.HERO_EPIC_SEARCH -> {
                if (!(tutorial.heroSearchReady() && tutorial.epicSearched() || buildings.level(301L) >= 5L)) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.HERO_UPGRADED -> {
                if ((!tutorial.heroReady() || tutorial.heroLevels().values().stream().noneMatch(level -> level >= definition.value())) && buildings.level(301L) < 5L) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.ADVENTURE_CLEAR -> {
                if (!(tutorial.adventureReady() && tutorial.adventurePassed() >= definition.value() || buildings.level(301L) >= 5L)) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.INTELLIGENCE_QUEST -> {
                if (tutorial.rescueMarchedCount() <= 0L && tutorial.receivedIntelligenceCount() <= 0L && buildings.level(328L) < 1L) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.INTELLIGENCE_REWARD -> {
                if (tutorial.receivedIntelligenceCount() <= 0L && buildings.level(328L) < 1L) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.MilestoneKind.SLG_MONSTER_QUEST -> tutorial.slgMarchedCount() <= 0L && buildings.level(328L) < 1L;
        };
    }

    private Optional<String> repairLighthouseIfBlocked(GameAccountSessionService.AccountSession session, BootstrapTutorialCatalog.Step definition, BootstrapBuildingState buildings) {
        if (definition.buildingId() != 328L || buildings.level(328L) >= 1L) {
            return Optional.empty();
        }
        try {
            session.request("req_upgrade_building", Map.of("bid", 328L, "use_diamond", true));
            buildings.markBuildingLevel(328L, 1L);
            log.info("[\u8d77\u53f7] \u81ea\u52a8\u4fee\u590d\u706f\u5854(bid=328)\u6210\u529f\uff0c\u5df2\u89e3\u9501\u91ce\u5916\u5730\u56fe\u4e0e\u5316\u96ea\u5f15\u5bfc");
            return Optional.of("\u81ea\u52a8\u4fee\u590d\u706f\u5854\u89e3\u9501\u91ce\u5916");
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u4fee\u590d\u706f\u5854\u5931\u8d25 message={}", (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> clearAdventureFirstCheckpoint(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial) {
        if (tutorial.adventurePassed() >= 1L) {
            return Optional.empty();
        }
        try {
            session.request("req_begin_adventure_pve", Map.of("checkpoint", 1L, "fight_heros_data", Map.of("source", 1L, "fight_heros", List.of(Long.valueOf(50006L), Long.valueOf(0L), Long.valueOf(0L), Long.valueOf(0L), Long.valueOf(0L)))));
            session.request("req_end_adventure_pve", Map.of("checkpoint", 1L, "operatedata", Map.of("101", Map.of("key", 101L, "value", Map.of("41", Map.of("key", 41L, "value", true)))), "enddata", List.of(Map.of("index", 101L, "hp", 425L), Map.of("index", 102L, "hp", 180L), Map.of("index", 103L, "hp", 180L)), "version", 26042101L));
            tutorial.markAdventurePassed(1L);
            log.info("[\u8d77\u53f7] \u81ea\u52a8\u901a\u5173\u63a2\u9669\u7b2c\u4e00\u5173\u6210\u529f");
            return Optional.of("\u901a\u5173\u63a2\u9669\u7b2c\u4e00\u5173");
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u81ea\u52a8\u901a\u5173\u63a2\u9669\u7b2c\u4e00\u5173\u5931\u8d25 message={}", (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private static String guideRetryKey(long guideId, long step) {
        return "guide:" + guideId + ":" + step;
    }

    private static long furnitureLevel(BootstrapBuildingState buildings, long buildingId, long furnitureId) {
        return buildings.furnitureLevels().getOrDefault(buildingId, Map.of()).getOrDefault(furnitureId, 0L);
    }

    private Optional<String> requestGuideComeSurvivor(GameAccountSessionService.AccountSession session, BootstrapTutorialCatalog.Step definition, BootstrapBuildingState buildings, BootstrapSurvivorState survivors) {
        boolean bedReady;
        boolean bl = bedReady = BootstrapTutorialService.furnitureLevel(buildings, definition.buildingId(), definition.furnitureId()) >= 1L;
        if (!bedReady || !survivors.initialized() || survivors.guideSelected() || survivors.guideRequested()) {
            return Optional.empty();
        }
        try {
            session.request(GUIDE_COME_SURVIVOR, Map.of("option", 1L));
            survivors.markGuideRequested();
            log.info("[\u8d77\u53f7] \u5df2\u8bf7\u6c42\u65b0\u624b\u5e78\u5b58\u8005\u6295\u9760 option={}", (Object)1L);
            return Optional.of("\u8bf7\u6c42\u65b0\u624b\u5e78\u5b58\u8005\u6295\u9760\uff08\u63a5\u7eb3\u5168\u90e8\uff09");
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u8bf7\u6c42\u65b0\u624b\u5e78\u5b58\u8005\u6295\u9760\u5931\u8d25 message={}", (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> requestElderDeath(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapSurvivorState survivors) {
        if (tutorial.elderRequested() || !survivors.initialized() || !buildings.buildingsReady()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (now - tutorial.lastElderRequestMillis() < 1000L) {
            return Optional.empty();
        }
        BootstrapTutorialCatalog.ElderBed bed = BootstrapTutorialCatalog.ELDER_BED;
        long sid = survivors.matchOnBed(bed.buildingId(), bed.furnitureId(), bed.nameId(), bed.imgId());
        if (sid <= 0L) {
            if (buildings.level(301L) >= 2L) {
                tutorial.markElderRequested(now, true);
                log.info("[\u8d77\u53f7] \u8001\u4eba\u53bb\u4e16\u4e8b\u4ef6\u5e8a\u4f4d\u672a\u5339\u914d\u5230\u4e14\u5927\u7194\u7089\u5df2\u8fbe {} \u7ea7\uff0c\u6807\u8bb0\u4e3a\u5df2\u5904\u7406\u4ee5\u7ee7\u7eed\u4e3b\u7ebf", (Object)buildings.level(301L));
                return Optional.of("\u8df3\u8fc7\u8001\u4eba\u53bb\u4e16\u5339\u914d\u76f4\u63a5\u63a8\u8fdb\u5267\u60c5");
            }
            tutorial.markElderRequested(now, false);
            log.info("[\u8d77\u53f7] \u8001\u4eba\u53bb\u4e16\u4e8b\u4ef6\u5728\u771f\u5b9e\u5e78\u5b58\u8005\u91cc\u5339\u914d\u4e0d\u5230\u5e8a\u4f4d {}-{} \u540d\u5b57 {} \u5f62\u8c61 {}\uff0c\u4fdd\u6301\u5267\u60c5\u7b2c 8 \u6b65\u7b49\u5f85\u670d\u52a1\u7aef\u6570\u636e", new Object[]{bed.buildingId(), bed.furnitureId(), bed.nameId(), bed.imgId()});
            return Optional.empty();
        }
        try {
            session.request(GUIDE_DEAD_SURVIVOR, Map.of("sid", sid));
            tutorial.markElderRequested(now, true);
            survivors.removeSurvivor(sid);
            log.info("[\u8d77\u53f7] \u5df2\u4e0a\u62a5\u8001\u4eba\u53bb\u4e16\u4e8b\u4ef6 sid={}\uff08\u5e8a\u4f4d {}-{} \u540d\u5b57 {} \u5f62\u8c61 {}\uff09", new Object[]{sid, bed.buildingId(), bed.furnitureId(), bed.nameId(), bed.imgId()});
            return Optional.of("\u4e0a\u62a5\u8001\u4eba\u53bb\u4e16\u4e8b\u4ef6 sid=" + sid);
        }
        catch (GameProtocolException exception) {
            tutorial.markElderRequested(now, true);
            log.info("[\u8d77\u53f7] \u8001\u4eba\u53bb\u4e16\u4e8b\u4ef6\u88ab\u62d2\u7edd code={}\uff0c\u6807\u8bb0\u4e3a\u5df2\u5904\u7406\u4ee5\u7ee7\u7eed\u4e3b\u7ebf", (Object)exception.code());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            tutorial.markElderRequested(now, false);
            log.info("[\u8d77\u53f7] \u8001\u4eba\u53bb\u4e16\u4e8b\u4ef6\u5931\u8d25 message={}", (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private void waitForGuideStepInterval(long accountRoleId) {
        Long last = this.lastGuideStepMillis.get(accountRoleId);
        if (last == null) {
            return;
        }
        long target = 100L + ThreadLocalRandom.current().nextLong(50L);
        long wait = target - (System.currentTimeMillis() - last);
        if (wait <= 0L) {
            return;
        }
        try {
            Thread.sleep(wait);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<String> bypassPostGuides(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, BootstrapBuildingState buildings) {
        for (BootstrapTutorialCatalog.PostGuide guide : BootstrapTutorialCatalog.postGuides()) {
            Optional<String> bypassed = this.bypassOnePostGuide(session, tutorial, buildings, guide);
            if (!bypassed.isPresent()) continue;
            return bypassed;
        }
        return Optional.empty();
    }

    private Optional<String> bypassOnePostGuide(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, BootstrapBuildingState buildings, BootstrapTutorialCatalog.PostGuide guide) {
        if (buildings.level(guide.buildingId()) < 1L || tutorial.bypassedGuides().contains(guide.id())) {
            return Optional.empty();
        }
        boolean done = true;
        for (long step = 1L; step <= guide.maxStep(); ++step) {
            this.waitForGuideStepInterval(session.accountRoleId());
            try {
                session.request(GUIDE_SET_STEP, Map.of("id", guide.id(), "step", step, "fakeEnd", false));
                this.lastGuideStepMillis.put(session.accountRoleId(), System.currentTimeMillis());
                log.info("[\u8d77\u53f7] \u5df2\u8df3\u8fc7\u5f15\u5bfc {}\uff08{}\uff09\u7b2c {} \u6b65", new Object[]{guide.name(), guide.id(), step});
                continue;
            }
            catch (GameProtocolException exception) {
                if (exception.code() == 2) continue;
                log.info("[\u8d77\u53f7] \u8df3\u8fc7\u5f15\u5bfc {} \u7b2c {} \u6b65\u88ab\u62d2\u7edd code={}", new Object[]{guide.id(), step, exception.code()});
                done = false;
                break;
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] \u8df3\u8fc7\u5f15\u5bfc {} \u7b2c {} \u6b65\u5931\u8d25 message={}", new Object[]{guide.id(), step, exception.getMessage()});
                done = false;
                break;
            }
        }
        if (!done) {
            return Optional.empty();
        }
        tutorial.markGuideBypassed(guide.id());
        return Optional.of("\u8df3\u8fc7\u5f15\u5bfc " + guide.name() + "(" + guide.id() + ")");
    }

    private boolean requestGuideStep(GameAccountSessionService.AccountSession session, long guideId, long step) {
        this.waitForGuideStepInterval(session.accountRoleId());
        try {
            session.request(GUIDE_SET_STEP, Map.of("id", guideId, "step", step));
            this.lastGuideStepMillis.put(session.accountRoleId(), System.currentTimeMillis());
            log.info("[\u8d77\u53f7] \u5df2\u4e0a\u62a5\u5f15\u5bfc {} \u7684\u7b2c {} \u6b65", (Object)guideId, (Object)step);
            return true;
        }
        catch (GameProtocolException exception) {
            log.info("[\u8d77\u53f7] \u4e0a\u62a5\u5f15\u5bfc {} \u7b2c {} \u6b65\u88ab\u62d2\u7edd code={}", new Object[]{guideId, step, exception.code()});
            return false;
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u4e0a\u62a5\u5f15\u5bfc {} \u7b2c {} \u6b65\u5931\u8d25 message={}", new Object[]{guideId, step, exception.getMessage()});
            return false;
        }
    }

    private List<Long> unlockEligibleModules(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, BootstrapBuildingState buildings) {
        if (!tutorial.unlockReady()) {
            return List.of();
        }
        String signature = BootstrapTutorialService.unlockSignature(tutorial, buildings);
        if (signature.equals(tutorial.lastUnlockSignature())) {
            return List.of();
        }
        tutorial.markUnlockAttempted(signature);
        ArrayList<Long> unlocked = new ArrayList<Long>();
        for (BootstrapTutorialCatalog.ModuleUnlock entry : BootstrapTutorialCatalog.moduleUnlocks()) {
            if (tutorial.unlockedModules().contains(entry.module()) || BootstrapTutorialService.unlockConditionPending(entry.condition(), tutorial, buildings)) continue;
            try {
                session.request(UNLOCK_MODULE, Map.of("module", entry.module()));
                tutorial.markModuleUnlocked(entry.module());
                unlocked.add(entry.module());
            }
            catch (GameProtocolException exception) {
                log.debug("[\u8d77\u53f7] \u6a21\u5757 {} \u5c1a\u672a\u6ee1\u8db3\u89e3\u9501\u6761\u4ef6 code={}", (Object)entry.module(), (Object)exception.code());
            }
            catch (RuntimeException exception) {
                log.debug("[\u8d77\u53f7] \u6a21\u5757 {} \u89e3\u9501\u5931\u8d25 message={}", (Object)entry.module(), (Object)exception.getMessage());
            }
        }
        return unlocked;
    }

    private static String unlockSignature(BootstrapTutorialState tutorial, BootstrapBuildingState buildings) {
        return buildings.level(301L) + "/" + buildings.level(318L) + "/" + tutorial.guideStep(200106L) + "/" + tutorial.guideStep(200111L);
    }

    private static boolean unlockConditionPending(BootstrapTutorialCatalog.ModuleCondition condition, BootstrapTutorialState tutorial, BootstrapBuildingState buildings) {
        return switch (condition.kind()) {
            default -> throw new MatchException(null, null);
            case BootstrapTutorialCatalog.ModuleConditionKind.FURNACE_LEVEL -> {
                if (buildings.level(301L) < condition.first()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.ModuleConditionKind.BUILDING_LEVEL -> {
                if (buildings.level(condition.first()) < condition.second()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapTutorialCatalog.ModuleConditionKind.GUIDE_STEP -> tutorial.guideStep(condition.first()) < condition.second();
        };
    }

    private boolean refreshProducedResources(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, BootstrapBuildingState buildings) {
        if (!tutorial.producedReady()) {
            return false;
        }
        boolean hasProducer = BootstrapTutorialCatalog.PRODUCTION_BUILDINGS.stream().anyMatch(buildingId -> buildings.level((long)buildingId) > 0L);
        if (!hasProducer) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - tutorial.lastProducedRefreshMillis() < 60000L) {
            return false;
        }
        try {
            session.request(PRODUCED_NEED_UPDATE, Map.of());
            tutorial.markProducedRefreshed(now);
            return true;
        }
        catch (RuntimeException exception) {
            log.debug("[\u8d77\u53f7] \u5237\u65b0\u751f\u4ea7\u8d44\u6e90\u5931\u8d25 message={}", (Object)exception.getMessage());
            return false;
        }
    }
}

