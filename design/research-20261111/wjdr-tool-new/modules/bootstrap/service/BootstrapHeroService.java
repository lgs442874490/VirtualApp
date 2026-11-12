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
import com.wjdr.tool.modules.behemoth.service.BehemothAccountState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProgressService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialState;
import com.wjdr.tool.modules.daily.service.DailyHeroCatalog;
import java.lang.invoke.CallSite;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapHeroService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapHeroService.class);
    private static final String HERO_SEARCH = "req_hero_search_times";
    private static final String HERO_UPGRADE = "req_hero_upgrade";
    private static final String HERO_PROMOTE_STAR = "req_hero_promote_star";
    private static final long SEARCH_TYPE_ADVANCED = 1L;
    private static final long SEARCH_TYPE_EPIC = 2L;
    private static final long COST_TYPE_FREE = 1L;
    private static final long SEARCH_TIMES_ONE = 1L;
    private static final long UPGRADE_RETRY_INTERVAL_MILLIS = 300000L;
    private static final String UPGRADE_RETRY_KEY = "hero:upgrade";
    private static final String FREE_ADVANCED_RETRY_KEY = "hero:recruit:free:advanced";
    private static final String FREE_EPIC_RETRY_KEY = "hero:recruit:free:epic";
    private static final String ITEM_ADVANCED_RETRY_KEY = "hero:recruit:item:advanced";
    private static final String ITEM_EPIC_RETRY_KEY = "hero:recruit:item:epic";
    private static final String BOX_RETRY_KEY = "hero:recruit:box";
    private static final long COST_TYPE_ITEM = 2L;
    private static final long COST_TYPE_BOX = 3L;
    private static final long SEARCH_TYPE_BOX = 3L;
    private static final long RECRUIT_RETRY_INTERVAL_MILLIS = 1800000L;
    private static final long RECRUIT_SUCCESS_INTERVAL_MILLIS = 1000L;
    private static final long STAR_RETRY_INTERVAL_MILLIS = 1800000L;
    private static final long MIN_STAR_FRAGMENTS = 1L;
    private static final String STAR_RETRY_KEY_PREFIX = "hero:star:";
    private static final int HERO_ACTION_BURST = 10;
    private static final int YIELD_ONCE = -1;
    private static final List<RecruitStep> RECRUIT_LADDER = List.of(new RecruitStep("hero:recruit:free:advanced", 1L, 1L, "\u9ad8\u7ea7\u62db\u52df", "\u514d\u8d39"), new RecruitStep("hero:recruit:free:epic", 2L, 1L, "\u53f2\u8bd7\u62db\u52df", "\u514d\u8d39"), new RecruitStep("hero:recruit:item:advanced", 1L, 2L, "\u9ad8\u7ea7\u62db\u52df", "\u9053\u5177"), new RecruitStep("hero:recruit:item:epic", 2L, 2L, "\u53f2\u8bd7\u62db\u52df", "\u9053\u5177"), new RecruitStep("hero:recruit:box", 3L, 3L, "\u62db\u52df\u5b9d\u7bb1", "\u79ef\u5206"));
    private final BootstrapTutorialRegistry tutorialStates;
    private final BootstrapBuildingStateRegistry buildingStates;
    private final DailyHeroCatalog heroCatalog;
    private final BootstrapProgressService progress;
    private final Map<Long, Integer> actionBurst = new ConcurrentHashMap<Long, Integer>();

    BootstrapHeroService(BootstrapTutorialRegistry tutorialStates, BootstrapBuildingStateRegistry buildingStates, DailyHeroCatalog heroCatalog, BootstrapProgressService progress) {
        this.tutorialStates = tutorialStates;
        this.buildingStates = buildingStates;
        this.heroCatalog = heroCatalog;
        this.progress = progress;
    }

    Optional<Map<String, Object>> advance(long accountRoleId, GameAccountSessionService.AccountSession session) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        BootstrapBuildingState buildings = this.buildingStates.buildingState(accountRoleId);
        if (!tutorial.heroReady() || !tutorial.heroSearchReady() || buildings.level(318L) <= 0L) {
            return Optional.empty();
        }
        if (this.yieldTurn(accountRoleId)) {
            return Optional.empty();
        }
        Optional<String> searched = this.requestGuideEpicSearch(session, tutorial);
        if (searched.isPresent()) {
            return Optional.of(BootstrapHeroService.action(accountRoleId, "heroSearch", searched.get()));
        }
        Optional<String> recruited = this.requestFreeRecruit(session, tutorial);
        if (recruited.isPresent()) {
            return Optional.of(BootstrapHeroService.action(accountRoleId, "heroRecruit", recruited.get()));
        }
        Optional<String> promoted = this.requestStarPromotion(session, tutorial);
        return promoted.map(message -> BootstrapHeroService.action(accountRoleId, "heroPromoteStar", message)).or(() -> this.requestHeroUpgrade(accountRoleId, session, tutorial).map(message -> BootstrapHeroService.action(accountRoleId, "heroUpgrade", message)));
    }

    private boolean yieldTurn(long accountRoleId) {
        Integer burst = this.actionBurst.get(accountRoleId);
        if (burst != null && burst == -1) {
            this.actionBurst.remove(accountRoleId);
            return true;
        }
        int next = burst == null ? 0 : burst;
        this.actionBurst.put(accountRoleId, next >= 10 ? -1 : next + 1);
        return false;
    }

    private Optional<String> requestFreeRecruit(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial) {
        for (RecruitStep step : RECRUIT_LADDER) {
            Optional<String> done = this.requestRecruit(session, tutorial, step);
            if (!done.isPresent()) continue;
            return done;
        }
        return Optional.empty();
    }

    private Optional<String> requestRecruit(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial, RecruitStep step) {
        long now = System.currentTimeMillis();
        if (!tutorial.retryAllowed(step.retryKey(), now)) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("search_type", step.searchType());
        payload.put("cost_type", step.costType());
        payload.put("times", 1L);
        try {
            session.request(HERO_SEARCH, payload);
            tutorial.markRetry(step.retryKey(), now + 1000L);
            log.info("[\u8d77\u53f7] \u5df2\u8bf7\u6c42{}{} 1 \u6b21", (Object)step.costLabel(), (Object)step.label());
            return Optional.of(step.costLabel() + step.label() + " 1 \u6b21");
        }
        catch (GameProtocolException exception) {
            tutorial.markRetry(step.retryKey(), now + 1800000L);
            log.info("[\u8d77\u53f7] {}{}\u88ab\u62d2\u7edd code={}", new Object[]{step.costLabel(), step.label(), exception.code()});
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            tutorial.markRetry(step.retryKey(), now + 1800000L);
            log.info("[\u8d77\u53f7] {}{}\u5931\u8d25 message={}", new Object[]{step.costLabel(), step.label(), exception.getMessage()});
            return Optional.empty();
        }
    }

    private Optional<String> requestStarPromotion(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial) {
        for (long heroId : tutorial.heroesWithFragments(1L)) {
            long now;
            String retryKey = STAR_RETRY_KEY_PREFIX + heroId;
            if (!tutorial.retryAllowed(retryKey, now = System.currentTimeMillis())) continue;
            try {
                session.request(HERO_PROMOTE_STAR, Map.of("id", heroId));
                tutorial.clearRetry("adventure:checkpoint:" + (tutorial.adventurePassed() + 1L));
                log.info("[\u8d77\u53f7] \u5df2\u8bf7\u6c42\u82f1\u96c4 {} \u5347\u661f", (Object)heroId);
                return Optional.of("\u8bf7\u6c42\u82f1\u96c4 " + heroId + " \u5347\u661f");
            }
            catch (GameProtocolException exception) {
                tutorial.markRetry(retryKey, now + 1800000L);
                log.info("[\u8d77\u53f7] \u82f1\u96c4 {} \u5347\u661f\u88ab\u62d2\u7edd code={}", (Object)heroId, (Object)exception.code());
            }
            catch (RuntimeException exception) {
                tutorial.markRetry(retryKey, now + 1800000L);
                log.info("[\u8d77\u53f7] \u82f1\u96c4 {} \u5347\u661f\u5931\u8d25 message={}", (Object)heroId, (Object)exception.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<String> requestGuideEpicSearch(GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial) {
        if (tutorial.epicSearched() || tutorial.epicSearchRequested()) {
            return Optional.empty();
        }
        tutorial.markEpicSearchRequested();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("search_type", 2L);
        payload.put("cost_type", 1L);
        payload.put("times", 1L);
        try {
            session.request(HERO_SEARCH, payload);
            tutorial.markEpicSearched();
            log.info("[\u8d77\u53f7] \u5df2\u8bf7\u6c42\u65b0\u624b\u5f15\u5bfc\u7684\u9996\u6b21\u53f2\u8bd7\u62db\u52df\uff08\u514d\u8d39 1 \u6b21\uff09");
            return Optional.of("\u8bf7\u6c42\u9996\u6b21\u53f2\u8bd7\u62db\u52df");
        }
        catch (GameProtocolException exception) {
            tutorial.markEpicSearched();
            log.info("[\u8d77\u53f7] \u9996\u6b21\u53f2\u8bd7\u62db\u52df\u88ab\u62d2\u7edd code={}\uff0c\u6807\u8bb0\u4e3a\u5df2\u5904\u7406", (Object)exception.code());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u9996\u6b21\u53f2\u8bd7\u62db\u52df\u5931\u8d25 message={}", (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> requestHeroUpgrade(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapTutorialState tutorial) {
        long bestHeroId = tutorial.highestLevelHero();
        long now = System.currentTimeMillis();
        ArrayList<Long> candidates = new ArrayList<Long>();
        if (bestHeroId > 0L) {
            candidates.add(bestHeroId);
        }
        tutorial.heroLevels().entrySet().stream().filter(e -> (Long)e.getKey() != bestHeroId).sorted((e1, e2) -> {
            int cmp = Long.compare((Long)e2.getValue(), (Long)e1.getValue());
            return cmp != 0 ? cmp : Long.compare((Long)e1.getKey(), (Long)e2.getKey());
        }).map(Map.Entry::getKey).forEach(candidates::add);
        Iterator iterator = candidates.iterator();
        while (iterator.hasNext()) {
            long heroId = (Long)iterator.next();
            String retryKey = "hero:upgrade:" + heroId;
            if (heroId <= 0L || !tutorial.retryAllowed(retryKey, now)) continue;
            String lacking = this.missingUpgradeMaterials(accountRoleId, tutorial, heroId);
            if (lacking != null) {
                tutorial.markRetry(retryKey, now + 300000L);
                log.info("[\u8d77\u53f7] \u82f1\u96c4 {} \u5347\u7ea7\u6750\u6599\u4e0d\u8db3\uff08{}\uff09\uff0c\u6682\u7f13\u5347\u7ea7", (Object)heroId, (Object)lacking);
                this.progress.note(accountRoleId, "info", "\u82f1\u96c4 " + heroId + " \u5347\u7ea7\u6750\u6599\u4e0d\u8db3\uff08" + lacking + "\uff09\uff0c\u5148\u6512\u6750\u6599\u518d\u5347\u7ea7");
                continue;
            }
            try {
                session.request(HERO_UPGRADE, Map.of("id", heroId));
                tutorial.clearRetry(retryKey);
                tutorial.markHeroUpgrade(true);
                tutorial.clearRetry("adventure:checkpoint:" + (tutorial.adventurePassed() + 1L));
                log.info("[\u8d77\u53f7] \u5df2\u8bf7\u6c42\u82f1\u96c4 {} \u5347\u7ea7", (Object)heroId);
                return Optional.of("\u8bf7\u6c42\u82f1\u96c4 " + heroId + " \u5347\u7ea7");
            }
            catch (GameProtocolException exception) {
                tutorial.markRetry(retryKey, now + 300000L);
                tutorial.markHeroUpgrade(false);
                log.info("[\u8d77\u53f7] \u82f1\u96c4 {} \u5347\u7ea7\u88ab\u62d2\u7edd code={}", (Object)heroId, (Object)exception.code());
            }
            catch (RuntimeException exception) {
                tutorial.markRetry(retryKey, now + 300000L);
                tutorial.markHeroUpgrade(false);
                log.info("[\u8d77\u53f7] \u82f1\u96c4 {} \u5347\u7ea7\u5931\u8d25 message={}", (Object)heroId, (Object)exception.getMessage());
            }
        }
        return Optional.empty();
    }

    private String missingUpgradeMaterials(long accountRoleId, BootstrapTutorialState tutorial, long heroId) {
        BehemothAccountState inventory = this.buildingStates.state(accountRoleId);
        if (inventory == null || !inventory.backpackInitialized()) {
            return null;
        }
        long level = tutorial.heroLevels().getOrDefault(heroId, 0L);
        DailyHeroCatalog.LevelCost cost = this.heroCatalog.levelCost((int)level + 1);
        if (cost == null || cost.costs().isEmpty()) {
            return null;
        }
        ArrayList<CallSite> missing = new ArrayList<CallSite>();
        for (DailyHeroCatalog.CostItem item : cost.costs()) {
            long owned = inventory.itemCount(item.itemId());
            if (owned >= item.count()) continue;
            missing.add((CallSite)((Object)("\u9053\u5177 " + item.itemId() + " \u8fd8\u5dee " + (item.count() - owned) + " \u4e2a")));
        }
        return missing.isEmpty() ? null : String.join((CharSequence)"\u3001", missing);
    }

    public Map<String, Object> view(long accountRoleId) {
        BootstrapTutorialState tutorial = this.tutorialStates.tutorialState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("heroReady", tutorial.heroReady());
        result.put("heroSearchReady", tutorial.heroSearchReady());
        result.put("epicSearched", tutorial.epicSearched());
        result.put("highestLevelHero", tutorial.highestLevelHero());
        result.put("upgradeSuccess", tutorial.heroUpgradeSuccess());
        return result;
    }

    private static Map<String, Object> action(long accountRoleId, String kind, String message) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("status", "STARTED");
        result.put("message", "\u8d77\u53f7\u82f1\u96c4\u5df2\u63a8\u8fdb\uff1a" + message);
        result.put("heroAction", kind);
        return result;
    }

    private record RecruitStep(String retryKey, long searchType, long costType, String label, String costLabel) {
    }
}

