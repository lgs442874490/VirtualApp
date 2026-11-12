/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PostConstruct
 *  jakarta.annotation.PreDestroy
 *  java.lang.MatchException
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.common.concurrency.VirtualThreadSupport;
import com.wjdr.tool.core.common.error.GameErrorDescriptor;
import com.wjdr.tool.core.model.GameModule;
import com.wjdr.tool.core.notification.NotificationEventType;
import com.wjdr.tool.core.notification.NotificationPublisher;
import com.wjdr.tool.core.persistence.GameAccountRoleModuleConfigStore;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.core.time.GameServerClock;
import com.wjdr.tool.modules.behemoth.service.BehemothAccountState;
import com.wjdr.tool.modules.bootstrap.model.BootstrapConfig;
import com.wjdr.tool.modules.bootstrap.service.BootstrapAdventureService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapChapterQuestCatalog;
import com.wjdr.tool.modules.bootstrap.service.BootstrapHeroService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapIntelligenceRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapMailService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapMilestoneService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProgressService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapRewardService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapShopService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapStorylineCatalog;
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialCatalog;
import com.wjdr.tool.modules.bootstrap.service.BootstrapTutorialService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapWorkService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapWorldService;
import com.wjdr.tool.modules.daily.service.BuildingUpgradePlanner;
import com.wjdr.tool.modules.daily.service.DailyBackpackResourceSupport;
import com.wjdr.tool.modules.daily.service.DailyIntelligenceState;
import com.wjdr.tool.modules.daily.service.DailyTownDevelopmentCatalog;
import com.wjdr.tool.modules.daily.service.IntelligenceQuestSupport;
import com.wjdr.tool.modules.daily.service.SpecialBuildingCatalog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.invoke.CallSite;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);
    private static final long FURNACE_ID = 301L;
    private static final String UPGRADE_BUILDING = "req_upgrade_building";
    private static final String CREATE_FURNITURE = "req_create_furniture";
    private static final String UPGRADE_FURNITURE = "req_upgrade_furniture";
    private static final int CONDITION_BUILDING_LEVEL_NOT_ENOUGH = 806;
    private static final int BUILDING_EXP_NOT_ENOUGH = 119;
    private static final int FURNITURE_EXIST = 154;
    private static final int FURNITURE_NOT_EXIST = 155;
    private static final int FURNITURE_MAX_LEVEL = 153;
    private static final int QUEUE_BUSY = 73;
    private static final int QUEUE_UNLOCKED_PURCHASE = 77;
    private static final long QUEUE_BUSY_BACKOFF_MILLIS = 60000L;
    private static final long UPGRADE_REJECT_BACKOFF_MILLIS = 30000L;
    private static final int BUILDING_QUEUE_BUSY = 118;
    private static final int BUILDING_IS_UPGRADING = 121;
    private static final int ITEM_NOT_ENOUGH = 51;
    private static final long RESOURCE_STOP_FURNACE_LEVEL = 9L;
    private static final int RESOURCE_EXHAUSTED_LIMIT = 3;
    private static final long RESOURCE_STALL_FURNACE_LEVEL = 7L;
    private static final int RESOURCE_STALL_LIMIT = 10;
    private static final int CONDITION_STALL_LIMIT = 15;
    private static final long CONDITION_STALL_WINDOW_MILLIS = 90000L;
    private static final int BUILDING_HAS_NOT_FURNITURE = 120;
    private static final int MAX_RUN_UNTIL_STEPS = 300;
    private static final long STEP_PACING_MIN_MILLIS = 50L;
    private static final long STEP_PACING_JITTER_MILLIS = 100L;
    private static final int QUEUE_FINISHED = 71;
    private static final List<Long> SPEEDUP_ITEM_IDS = List.of(Long.valueOf(200201L), Long.valueOf(200202L), Long.valueOf(200203L), Long.valueOf(200204L), Long.valueOf(200205L), Long.valueOf(200206L));
    private static final String ADVENTURE_BEGIN = "req_begin_adventure_pve";
    private static final long ADVENTURE_STARTER_HERO = 50006L;
    private static final long CDKEY_MIN_FURNACE_LEVEL = 3L;
    private static final long RESOURCE_WAIT_BUDGET_MILLIS = 90000L;
    private static final long RESOURCE_WAIT_INTERVAL_MILLIS = 3000L;
    private static final String QUEUE_SPEED = "req_building_queue_speed";
    private static final String STOVE_COMBUSTION_SWITCH = "req_stove_combustion_switch";
    private static final long STOVE_SWITCH_ON = 1L;
    private static final long STOVE_OVERDRIVE_OFF = 0L;
    private static final String ALLIANCE_SEARCH = "req_alliance_search_by_key";
    private static final String ALLIANCE_JOIN = "req_alliance_join";
    private static final String ALLIANCE_APPLY_JOIN = "req_alliance_apply_join";
    private static final int ALLIANCE_ALREADY_JOINED_CODE = 1501;
    private static final int ALLIANCE_NEED_APPLY_CODE = 1512;
    private static final int ALLIANCE_APPLY_DUPLICATED_CODE = 1515;
    private static final long ALLIANCE_TRANSIENT_RETRY_MILLIS = 30000L;
    private static final String MODIFY_NAME_PROTOCOL = "req_player_modify_name";
    private static final String PLAYER_BASE_INFO_PROTOCOL = "req_players_base_info";
    private static final String SURVIVOR_SEND_OR_FIRED = "req_survivor_send_or_fired";
    private static final String TEMP_SURVIVOR_CONFIRM = "req_temp_survivor_confirm";
    private static final String GUIDE_COME_SURVIVOR = "req_guide_come_survivor";
    private static final long SURVIVOR_ASSIGN = 1L;
    private static final long WORLD_MAP_UNLOCK_FURNACE_LEVEL = 7L;
    private static final long MILESTONE_MIN_FURNACE_LEVEL = 7L;
    private static final List<Long> PRODUCTION_STATION_TYPES = List.of(Long.valueOf(9L), Long.valueOf(24L), Long.valueOf(30L), Long.valueOf(42L));
    private static final long STATION_RETRY_INTERVAL_MILLIS = 60000L;
    private static final List<Long> SPECIAL_BUILDING_IDS = List.of(Long.valueOf(318L), Long.valueOf(319L), Long.valueOf(326L), Long.valueOf(328L), Long.valueOf(330L), Long.valueOf(335L));
    private static final int NAME_SAME_AS_BEFORE = 205;
    private final GameAccountSessionService sessions;
    private final GameAccountRoleModuleConfigStore configStore;
    private final DailyTownDevelopmentCatalog catalog;
    private final BuildingUpgradePlanner planner;
    private final BootstrapBuildingStateRegistry buildingStates;
    private final BootstrapIntelligenceRegistry intelligenceStates;
    private final BootstrapSurvivorRegistry survivorStates;
    private final BootstrapRewardService rewards;
    private final BootstrapShopService shops;
    private final BootstrapTutorialService tutorial;
    private final BootstrapWorkService work;
    private final BootstrapHeroService heroes;
    private final BootstrapMailService mails;
    private final BootstrapWorldService world;
    private final BootstrapMilestoneService milestones;
    private final BootstrapAdventureService adventures;
    private final BootstrapProgressService progress;
    private final NotificationPublisher notificationPublisher;
    private final Map<Long, String> runStateSnapshots = new ConcurrentHashMap<Long, String>();
    private final Map<Long, AllianceFollowUp> allianceFollowUps = new ConcurrentHashMap<Long, AllianceFollowUp>();
    private final Map<Long, Long> allianceRetryAt = new ConcurrentHashMap<Long, Long>();
    private final Set<Long> allianceFailureNotifiedRoles = ConcurrentHashMap.newKeySet();
    private final Set<Long> endedNotifiedRoles = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService autoScheduler = VirtualThreadSupport.singleScheduler("bootstrap-auto-run-scheduler-");
    private final ExecutorService autoWorkers = VirtualThreadSupport.perTaskExecutor("bootstrap-auto-run-worker-");
    private final Semaphore autoWorkerPermits = new Semaphore(4, true);
    private final Map<Long, Long> nextActionAtMillis = new ConcurrentHashMap<Long, Long>();
    private final PriorityQueue<BootstrapDueRole> dueRoles = new PriorityQueue();
    private AutoCloseable sessionOnlineRegistration;
    private final Set<Long> autoRunningRoles = ConcurrentHashMap.newKeySet();
    private static final long AUTO_RUN_INTERVAL_MILLIS = 1000L;
    private static final long AUTO_RUN_JITTER_MILLIS = 500L;
    private static final int AUTO_RUN_MAX_STEPS = 20;
    private static final long FINISH_PASS_MIN_INTERVAL_MILLIS = 14400000L;
    private static final int FINISH_PASS_MAIL_ROUNDS = 4;
    private final AtomicLong lastDispatchLogMillis = new AtomicLong();
    private static final long DISPATCH_LOG_IDLE_INTERVAL_MILLIS = 300000L;
    private final Map<Long, Long> finishPassMillis = new ConcurrentHashMap<Long, Long>();
    private final Map<Long, String> finishPassDays = new ConcurrentHashMap<Long, String>();
    private final Set<Long> finishRunningRoles = ConcurrentHashMap.newKeySet();
    private final Set<Long> resourceEndedRoles = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> resourceExhaustedAttempts = new ConcurrentHashMap<Long, Integer>();
    private static final long CONFIG_CACHE_MILLIS = 5000L;
    private final Map<Long, Long> lastBusySpeedUpMillis = new ConcurrentHashMap<Long, Long>();
    private static final long BUSY_SPEEDUP_RETRY_MILLIS = 30000L;
    private final Map<Long, Long> upgradeBackoffUntil = new ConcurrentHashMap<Long, Long>();
    private final Map<Long, CachedConfig> configCache = new ConcurrentHashMap<Long, CachedConfig>();
    private final Map<Long, Long> roleFids = new ConcurrentHashMap<Long, Long>();
    private static final long INTELLIGENCE_RETRY_INTERVAL_MILLIS = 600000L;
    private final Map<String, Long> intelligenceRetryAt = new ConcurrentHashMap<String, Long>();
    private final Map<Long, long[]> conditionStall = new ConcurrentHashMap<Long, long[]>();
    private final Map<Long, String> renamedRoles = new ConcurrentHashMap<Long, String>();
    private final Map<Long, String> renameErrors = new ConcurrentHashMap<Long, String>();
    private final AutoCloseable sessionStoppedRegistration;

    private static String intelligenceRetryKey(long accountRoleId, long eventId) {
        return accountRoleId + ":" + eventId;
    }

    private boolean intelligenceRetryAllowed(long accountRoleId, long eventId, long now) {
        Long until = this.intelligenceRetryAt.get(BootstrapService.intelligenceRetryKey(accountRoleId, eventId));
        return until == null || now >= until;
    }

    BootstrapService(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessions, GameAccountRoleModuleConfigStore configStore, DailyTownDevelopmentCatalog catalog, BuildingUpgradePlanner planner, BootstrapBuildingStateRegistry buildingStates, BootstrapIntelligenceRegistry intelligenceStates, BootstrapSurvivorRegistry survivorStates, BootstrapRewardService rewards, BootstrapShopService shops, BootstrapTutorialService tutorial, BootstrapWorkService work, BootstrapHeroService heroes, BootstrapMailService mails, BootstrapWorldService world, BootstrapMilestoneService milestones, BootstrapAdventureService adventures, BootstrapProgressService progress) {
        this(sessions, configStore, catalog, planner, buildingStates, intelligenceStates, survivorStates, rewards, shops, tutorial, work, heroes, mails, world, milestones, adventures, progress, null);
    }

    @Autowired
    BootstrapService(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessions, GameAccountRoleModuleConfigStore configStore, DailyTownDevelopmentCatalog catalog, BuildingUpgradePlanner planner, BootstrapBuildingStateRegistry buildingStates, BootstrapIntelligenceRegistry intelligenceStates, BootstrapSurvivorRegistry survivorStates, BootstrapRewardService rewards, BootstrapShopService shops, BootstrapTutorialService tutorial, BootstrapWorkService work, BootstrapHeroService heroes, BootstrapMailService mails, BootstrapWorldService world, BootstrapMilestoneService milestones, BootstrapAdventureService adventures, BootstrapProgressService progress, NotificationPublisher notificationPublisher) {
        this.sessions = sessions;
        this.configStore = configStore;
        this.catalog = catalog;
        this.planner = planner;
        this.buildingStates = buildingStates;
        this.intelligenceStates = intelligenceStates;
        this.survivorStates = survivorStates;
        this.rewards = rewards;
        this.shops = shops;
        this.tutorial = tutorial;
        this.work = work;
        this.heroes = heroes;
        this.mails = mails;
        this.world = world;
        this.milestones = milestones;
        this.adventures = adventures;
        this.progress = progress;
        this.notificationPublisher = notificationPublisher;
        this.sessionStoppedRegistration = sessions.addSessionStoppedListener(session -> {
            long accountRoleId = session.accountRoleId();
            if (sessions.findOnlineSession(accountRoleId).isPresent()) {
                return;
            }
            this.clearSessionRuntimeState(accountRoleId);
        });
    }

    private void clearSessionRuntimeState(long accountRoleId) {
        this.clearScheduledRole(accountRoleId);
        this.finishPassMillis.remove(accountRoleId);
        this.finishPassDays.remove(accountRoleId);
        this.resourceEndedRoles.remove(accountRoleId);
        this.resourceExhaustedAttempts.remove(accountRoleId);
        this.conditionStall.remove(accountRoleId);
        this.upgradeBackoffUntil.remove(accountRoleId);
        this.lastBusySpeedUpMillis.remove(accountRoleId);
        this.renamedRoles.remove(accountRoleId);
        this.renameErrors.remove(accountRoleId);
        this.allianceFollowUps.remove(accountRoleId);
        this.allianceRetryAt.remove(accountRoleId);
        this.allianceFailureNotifiedRoles.remove(accountRoleId);
        this.roleFids.remove(accountRoleId);
        this.intelligenceRetryAt.keySet().removeIf(key -> key.startsWith(accountRoleId + ":"));
        this.runStateSnapshots.remove(accountRoleId);
        this.endedNotifiedRoles.remove(accountRoleId);
        this.configCache.remove(accountRoleId);
    }

    @PostConstruct
    void startAutoRunner() {
        this.sessionOnlineRegistration = this.sessions.addSessionOnlineListener(session -> this.scheduleRole(session.accountRoleId(), BootstrapService.nextDelayMillis()));
        this.sessions.onlineSessions().forEach(session -> this.scheduleRole(session.accountRoleId(), BootstrapService.nextDelayMillis()));
        this.scheduleNextAutoRun(1000L);
    }

    private void scheduleNextAutoRun(long delayMillis) {
        this.autoScheduler.schedule(() -> {
            try {
                this.dispatchAutoRun();
            }
            finally {
                this.scheduleNextAutoRun(1000L + ThreadLocalRandom.current().nextLong(501L));
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @PreDestroy
    void stopAutoRunner() {
        VirtualThreadSupport.shutdownNow(this.autoScheduler);
        VirtualThreadSupport.shutdownNow(this.autoWorkers);
        BootstrapService.closeQuietly(this.sessionOnlineRegistration);
        BootstrapService.closeQuietly(this.sessionStoppedRegistration);
        PriorityQueue<BootstrapDueRole> priorityQueue = this.dueRoles;
        synchronized (priorityQueue) {
            this.dueRoles.clear();
        }
        this.nextActionAtMillis.clear();
    }

    private boolean submitAutoWorker(Runnable action) {
        if (!this.autoWorkerPermits.tryAcquire()) {
            return false;
        }
        try {
            this.autoWorkers.execute(() -> {
                try {
                    action.run();
                }
                finally {
                    this.autoWorkerPermits.release();
                }
            });
            return true;
        }
        catch (RuntimeException failure) {
            this.autoWorkerPermits.release();
            throw failure;
        }
    }

    private static void closeQuietly(AutoCloseable registration) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private static long nextDelayMillis() {
        return 1000L + ThreadLocalRandom.current().nextLong(501L);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void scheduleRole(long accountRoleId, long delayMillis) {
        long dueAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
        this.nextActionAtMillis.put(accountRoleId, dueAt);
        PriorityQueue<BootstrapDueRole> priorityQueue = this.dueRoles;
        synchronized (priorityQueue) {
            this.dueRoles.offer(new BootstrapDueRole(accountRoleId, dueAt));
        }
    }

    private void clearScheduledRole(long accountRoleId) {
        this.nextActionAtMillis.remove(accountRoleId);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private List<Long> drainDueRoles() {
        long now = System.currentTimeMillis();
        ArrayList<Long> result = new ArrayList<Long>();
        PriorityQueue<BootstrapDueRole> priorityQueue = this.dueRoles;
        synchronized (priorityQueue) {
            while (result.size() < 64 && !this.dueRoles.isEmpty()) {
                BootstrapDueRole due = this.dueRoles.poll();
                if (due.dueAtMillis() > now) {
                    this.dueRoles.offer(due);
                    break;
                }
                Long scheduledAt = this.nextActionAtMillis.get(due.accountRoleId());
                if (scheduledAt == null || scheduledAt.longValue() != due.dueAtMillis()) continue;
                this.nextActionAtMillis.remove(due.accountRoleId(), due.dueAtMillis());
                result.add(due.accountRoleId());
            }
        }
        return List.copyOf(result);
    }

    public int scheduledRoleCount() {
        return this.nextActionAtMillis.size();
    }

    private void dispatchAutoRun() {
        int online = 0;
        int submitted = 0;
        int running = 0;
        int disabled = 0;
        int finished = 0;
        int finishing = 0;
        int allianceBlocked = 0;
        try {
            for (long accountRoleId : this.drainDueRoles()) {
                ++online;
                if (this.sessions.findOnlineSession(accountRoleId).isEmpty()) {
                    this.clearScheduledRole(accountRoleId);
                    continue;
                }
                this.scheduleRole(accountRoleId, BootstrapService.nextDelayMillis());
                if (this.autoRunningRoles.contains(accountRoleId)) {
                    ++running;
                    continue;
                }
                BootstrapConfig config = this.config(accountRoleId);
                if (!config.enabled()) {
                    ++disabled;
                    this.clearScheduledRole(accountRoleId);
                    continue;
                }
                BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
                if (state.level(301L) >= 7L && this.finishPassDue(accountRoleId) && this.finishRunningRoles.add(accountRoleId)) {
                    boolean accepted = this.submitAutoWorker(() -> {
                        try {
                            this.runFinishPass(accountRoleId);
                        }
                        catch (RuntimeException exception) {
                            log.info("[\u8d77\u53f7] \u6536\u5c3e\u6d41\u7a0b\u5931\u8d25 accountRoleId={} message={}", (Object)accountRoleId, (Object)exception.getMessage());
                        }
                        finally {
                            this.finishRunningRoles.remove(accountRoleId);
                        }
                    });
                    if (!accepted) {
                        this.finishRunningRoles.remove(accountRoleId);
                        continue;
                    }
                    ++finishing;
                    continue;
                }
                String stateNow = this.runState(accountRoleId, config, state);
                if ("COMPLETED".equals(stateNow)) {
                    ++finished;
                    this.clearScheduledRole(accountRoleId);
                    this.progress.markFinished(accountRoleId, this.completionMessage(accountRoleId, config));
                    continue;
                }
                if ("ALLIANCE_BLOCKED".equals(stateNow)) {
                    ++allianceBlocked;
                    this.scheduleRole(accountRoleId, 60000L + ThreadLocalRandom.current().nextLong(501L));
                    continue;
                }
                this.autoRunningRoles.add(accountRoleId);
                boolean accepted = this.submitAutoWorker(() -> {
                    try {
                        this.runUntil(accountRoleId, 20);
                    }
                    catch (RuntimeException exception) {
                        log.info("[\u8d77\u53f7] \u81ea\u52a8\u63a8\u8fdb\u5931\u8d25 accountRoleId={} message={}", (Object)accountRoleId, (Object)exception.getMessage());
                    }
                    finally {
                        this.autoRunningRoles.remove(accountRoleId);
                    }
                });
                if (!accepted) {
                    this.autoRunningRoles.remove(accountRoleId);
                    continue;
                }
                ++submitted;
            }
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u81ea\u52a8\u63a8\u8fdb\u8c03\u5ea6\u5931\u8d25 message={}", (Object)exception.getMessage());
        }
        boolean meaningful = submitted > 0 || finishing > 0 || running > 0;
        long now = System.currentTimeMillis();
        long lastLog = this.lastDispatchLogMillis.get();
        if (meaningful || now - lastLog >= 300000L) {
            this.lastDispatchLogMillis.set(now);
            log.info("[\u8d77\u53f7] \u81ea\u52a8\u63a8\u8fdb\u8f6e\u6b21\uff1a\u5728\u7ebf {}\uff0c\u63d0\u4ea4 {}\uff0c\u8fdb\u884c\u4e2d {}\uff0c\u672a\u5f00\u542f {}\uff0c\u5df2\u8fbe\u6807 {}\uff0c\u5165\u76df\u5f85\u5904\u7406 {}\uff0c\u6536\u5c3e {}", new Object[]{online, submitted, running, disabled, finished, allianceBlocked, finishing});
        }
    }

    private boolean levelingGoalSatisfied(long accountRoleId, BootstrapConfig config, BootstrapBuildingState state) {
        if (this.resourceEndedRoles.contains(accountRoleId)) {
            return true;
        }
        if (state.level(301L) < (long)config.targetFurnaceLevel()) {
            return false;
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return false;
        }
        if (!this.planNext(config, state, 1).complete()) {
            return false;
        }
        for (long questId : BootstrapService.chapterObjectiveQuestIds(config, state)) {
            BootstrapChapterQuestCatalog.Objective objective = BootstrapChapterQuestCatalog.objective(questId).orElse(null);
            if (objective == null || this.chapterObjectiveSatisfied(accountRoleId, objective, state)) continue;
            return false;
        }
        return true;
    }

    private boolean finishPassDue(long accountRoleId) {
        Long last = this.finishPassMillis.get(accountRoleId);
        if (last == null) {
            return true;
        }
        String today = LocalDate.now().toString();
        if (today.equals(this.finishPassDays.get(accountRoleId))) {
            return false;
        }
        return System.currentTimeMillis() - last >= 14400000L;
    }

    private void runFinishPass(long accountRoleId) {
        int backpackUsed;
        Object mail;
        this.requireEnabled(accountRoleId);
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        LinkedHashMap<String, Object> summary = new LinkedHashMap<String, Object>();
        int mailRounds = 0;
        for (int round = 0; round < 4 && !((Optional)(mail = this.mails.advance(accountRoleId, session))).isEmpty(); ++round) {
            ++mailRounds;
            summary.put("mail", ((Optional)mail).get().get("message"));
            BootstrapService.sleepQuietly(50L + ThreadLocalRandom.current().nextLong(100L));
        }
        if (mailRounds > 0) {
            this.progress.note(accountRoleId, "info", "\u5df2\u5904\u7406\u90ae\u7bb1\uff1a\u5171\u9886\u53d6 " + mailRounds + " \u6279\u90ae\u4ef6\u5956\u52b1");
        }
        try {
            summary.put("rewards", this.rewards.claimAll(accountRoleId));
        }
        catch (RuntimeException exception) {
            summary.put("rewards", Map.of("message", "\u9886\u5956\u5931\u8d25\uff1a" + exception.getMessage()));
        }
        this.milestones.refreshInfo(accountRoleId);
        List<Long> milestonesClaimed = this.milestones.claimMilestones(accountRoleId);
        if (!milestonesClaimed.isEmpty()) {
            summary.put("milestones", milestonesClaimed);
            mail = milestonesClaimed.iterator();
            while (mail.hasNext()) {
                long milestoneId = (Long)mail.next();
                this.progress.note(accountRoleId, "success", "\u5df2\u9886\u53d6\u91cc\u7a0b\u7891\u5956\u52b1\uff08\u7f16\u53f7 " + milestoneId + "\uff09");
            }
        }
        if ((backpackUsed = this.rewards.claimBackpackDiamonds(accountRoleId)) > 0) {
            summary.put("backpackDiamonds", backpackUsed);
            this.progress.note(accountRoleId, "success", "\u5df2\u6253\u5f00\u80cc\u5305\u91cc\u7684\u94bb\u77f3\u9053\u5177 " + backpackUsed + " \u4ef6");
        }
        summary.put("world", this.world.advance(accountRoleId, session).map(result -> result.get("message")).orElse(""));
        this.finishPassMillis.put(accountRoleId, System.currentTimeMillis());
        this.finishPassDays.put(accountRoleId, LocalDate.now().toString());
        this.progress.note(accountRoleId, "info", "\u6536\u5c3e\u5b8c\u6210\uff1a\u90ae\u7bb1\u3001\u7b7e\u5230\u3001\u91cc\u7a0b\u7891\u4e0e\u80cc\u5305\u5956\u52b1\u5df2\u5904\u7406");
        log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u6536\u5c3e\u6d41\u7a0b\u5b8c\u6210\uff1a{}", (Object)accountRoleId, summary);
    }

    public Map<String, Object> status(long accountRoleId) {
        BootstrapConfig config = this.config(accountRoleId);
        boolean online = this.sessions.hasSession(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("module", GameModule.BOOTSTRAP.key());
        result.put("online", online);
        result.put("config", config.toMap());
        result.put("serverTime", Instant.now().getEpochSecond());
        if (!online) {
            result.put("stateReady", false);
            result.put("message", "\u8d26\u53f7\u672a\u4e0a\u7ebf\uff0c\u8d77\u53f7\u6d41\u7a0b\u4e0d\u4f1a\u6267\u884c");
            return result;
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
        LinkedHashMap<Long, Long> levels = new LinkedHashMap<Long, Long>();
        for (DailyTownDevelopmentCatalog.BuildingDefinition definition : this.catalog.buildings()) {
            levels.put(definition.id(), state.level(definition.id()));
        }
        Iterator<Object> iterator = SPECIAL_BUILDING_IDS.iterator();
        while (iterator.hasNext()) {
            long specialId = (Long)iterator.next();
            levels.put(specialId, state.level(specialId));
        }
        result.put("roleUid", session.roleUid());
        result.put("fid", this.resolveRoleFid(accountRoleId, session));
        result.put("intelligence", this.intelligenceView(accountRoleId));
        result.put("roleName", BootstrapService.currentRoleName(session));
        result.put("targetRoleName", config.roleName());
        result.put("renamePending", this.renamePending(accountRoleId, config, session));
        result.put("renameMessage", this.renameErrors.getOrDefault(accountRoleId, ""));
        result.put("stateReady", state.buildingsReady() && state.queuesReady());
        result.put("sync", Map.of("buildings", state.buildingsReady(), "queues", state.queuesReady()));
        result.put("furnaceLevel", state.level(301L));
        result.put("targetFurnaceLevel", config.targetFurnaceLevel());
        result.put("buildings", this.buildingView(levels, state));
        result.put("resources", this.resourceView(accountRoleId));
        BuildingUpgradePlanner.Plan plan = this.planner.plan(new BuildingUpgradePlanner.Request(levels, state.furnitureLevels(), this.targetLevels(config, state), state.busyBuildings()));
        result.put("remainingSteps", plan.actions().stream().map(this::stepView).toList());
        BootstrapSurvivorState survivors = this.survivorStates.survivorState(accountRoleId);
        result.put("storyline", Map.of("steps", this.storylineView(state, survivors)));
        result.put("tutorial", this.tutorial.view(accountRoleId));
        result.put("work", this.work.view(accountRoleId));
        result.put("heroes", this.heroes.view(accountRoleId));
        result.put("mails", this.mails.view(accountRoleId));
        result.put("milestones", this.milestones.view(accountRoleId));
        result.put("chapterObjectives", this.chapterObjectiveView(accountRoleId, config, state));
        result.put("adventure", this.adventures.view(accountRoleId));
        result.put("world", this.world.view(accountRoleId));
        result.put("survivors", this.survivorView(survivors));
        result.put("blockedReason", plan.blockedReason());
        result.put("allianceFollowUp", this.allianceFollowUpView(accountRoleId, config));
        String currentRunState = this.runState(accountRoleId, config, state);
        result.put("completed", "COMPLETED".equals(currentRunState));
        result.put("runState", currentRunState);
        result.put("message", this.runMessage(accountRoleId, config, state));
        result.put("progressLastSeq", this.progress.lastSeq(accountRoleId));
        result.put("finished", this.progress.finished(accountRoleId));
        result.put("running", this.autoRunningRoles.contains(accountRoleId));
        result.put("autoRunning", this.autoRunningRoles.contains(accountRoleId));
        result.put("finishSummary", this.progress.finished(accountRoleId) ? this.lastMessage(accountRoleId) : "");
        return result;
    }

    public Map<String, Object> progressView(long accountRoleId, long afterSeq) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<String, Object>(this.progress.snapshot(accountRoleId, afterSeq));
        snapshot.put("running", this.autoRunningRoles.contains(accountRoleId));
        boolean configEnabled = this.config(accountRoleId).enabled();
        snapshot.put("enabled", configEnabled);
        BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
        snapshot.put("furnaceLevel", state.level(301L));
        snapshot.put("targetFurnaceLevel", this.config(accountRoleId).targetFurnaceLevel());
        return snapshot;
    }

    public Map<String, Object> startRun(long accountRoleId) {
        BootstrapConfig config = this.config(accountRoleId);
        if (!config.enabled()) {
            throw new IllegalStateException("\u8bf7\u5148\u5f00\u542f\u8d77\u53f7\u4e3b\u5f00\u5173\u518d\u5f00\u59cb\u8d77\u53f7");
        }
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        if (session == null) {
            throw new IllegalStateException("\u8d26\u53f7\u672a\u4e0a\u7ebf\uff0c\u8d77\u53f7\u6d41\u7a0b\u4e0d\u4f1a\u6267\u884c");
        }
        boolean submitted = this.autoRunningRoles.add(accountRoleId);
        if (submitted) {
            this.progress.note(accountRoleId, "info", "\u5f00\u59cb\u8d77\u53f7\uff1a\u76ee\u6807\u5927\u7194\u7089 " + config.targetFurnaceLevel() + " \u7ea7");
            submitted = this.submitAutoWorker(() -> {
                try {
                    this.runUntil(accountRoleId, 20);
                }
                catch (RuntimeException exception) {
                    log.info("[\u8d77\u53f7] \u624b\u52a8\u5f00\u59cb\u63a8\u8fdb\u5931\u8d25 accountRoleId={} message={}", (Object)accountRoleId, (Object)exception.getMessage());
                }
                finally {
                    this.autoRunningRoles.remove(accountRoleId);
                }
            });
            if (!submitted) {
                this.autoRunningRoles.remove(accountRoleId);
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", submitted ? "STARTED" : "RUNNING");
        result.put("message", submitted ? "\u5df2\u5f00\u59cb\u8d77\u53f7" : "\u8d77\u53f7\u6b63\u5728\u8fdb\u884c\u4e2d");
        result.put("state", this.status(accountRoleId));
        return result;
    }

    public Map<String, Object> stopRun(long accountRoleId) {
        BootstrapConfig current = this.config(accountRoleId);
        BootstrapConfig disabled = new BootstrapConfig(false, current.targetServerId(), current.targetFurnaceLevel(), current.allianceName(), current.autoApplyWhenPrivate(), current.roleName());
        this.configStore.save(GameModule.BOOTSTRAP, accountRoleId, "settings", disabled.toMap());
        this.configCache.put(accountRoleId, new CachedConfig(System.currentTimeMillis(), disabled));
        this.progress.note(accountRoleId, "warning", "\u5df2\u6682\u505c\u8d77\u53f7\uff08\u4e3b\u5f00\u5173\u5df2\u5173\u95ed\uff09");
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "STOPPED");
        result.put("message", "\u5df2\u6682\u505c\u8d77\u53f7");
        result.put("state", this.status(accountRoleId));
        return result;
    }

    private String lastMessage(long accountRoleId) {
        Map item;
        Object last;
        List list;
        Map<String, Object> snapshot = this.progress.snapshot(accountRoleId, 0L);
        Object entries = snapshot.get("entries");
        if (entries instanceof List && !(list = (List)entries).isEmpty() && (last = list.getLast()) instanceof Map && (item = (Map)last).get("message") != null) {
            return String.valueOf(item.get("message"));
        }
        return "";
    }

    public Map<String, Object> saveSettings(long accountRoleId, Map<String, Object> raw) {
        BootstrapConfig config = BootstrapConfig.fromMap(raw);
        this.configStore.save(GameModule.BOOTSTRAP, accountRoleId, "settings", config.toMap());
        this.configCache.put(accountRoleId, new CachedConfig(System.currentTimeMillis(), config));
        this.resourceEndedRoles.remove(accountRoleId);
        this.resourceExhaustedAttempts.remove(accountRoleId);
        this.conditionStall.remove(accountRoleId);
        this.runStateSnapshots.remove(accountRoleId);
        this.allianceFollowUps.remove(accountRoleId);
        this.allianceRetryAt.remove(accountRoleId);
        this.allianceFailureNotifiedRoles.remove(accountRoleId);
        this.endedNotifiedRoles.remove(accountRoleId);
        this.progress.clearFinished(accountRoleId);
        if (config.enabled()) {
            this.scheduleRole(accountRoleId, 0L);
        } else {
            this.clearScheduledRole(accountRoleId);
        }
        return this.status(accountRoleId);
    }

    public Map<String, Object> runNextStep(long accountRoleId) {
        Map<String, Object> result = this.runNextStepInternal(accountRoleId);
        this.recordStep(accountRoleId, result);
        return result;
    }

    private void recordStep(long accountRoleId, Map<String, Object> result) {
        Object purchaseMessage;
        Map purchase;
        Object purchased;
        Number count;
        Object backpackDiamonds;
        Object intelligenceFought;
        Object intelligenceClaimed;
        if (result == null || accountRoleId <= 0L) {
            return;
        }
        String status = BootstrapProtocolSupport.text(result.get("status"));
        String message = BootstrapProtocolSupport.text(result.get("message"));
        switch (status) {
            case "STARTED": {
                this.progress.note(accountRoleId, "success", message);
                break;
            }
            case "COMPLETED": {
                this.progress.markFinished(accountRoleId, message.isEmpty() ? "\u8d77\u53f7\u5b8c\u6bd5" : message);
                break;
            }
            case "ENDED": {
                this.progress.markFinished(accountRoleId, message.isEmpty() ? "\u8d77\u53f7\u5df2\u7ed3\u675f" : message);
                this.publishBootstrapEnded(accountRoleId, message);
                break;
            }
            case "WAITING": 
            case "RESYNC_REQUIRED": 
            case "EXP_BLOCKED": 
            case "MANUAL": {
                this.progress.note(accountRoleId, "warning", message);
                break;
            }
            default: {
                this.progress.note(accountRoleId, "info", message);
            }
        }
        Object claimed = result.get("milestoneClaimed");
        if (claimed instanceof List) {
            List ids = (List)claimed;
            for (Object id : ids) {
                this.progress.note(accountRoleId, "success", "\u5df2\u9886\u53d6\u91cc\u7a0b\u7891\u5956\u52b1\uff08\u7f16\u53f7 " + String.valueOf(id) + "\uff09");
            }
        }
        if ((intelligenceClaimed = result.get("intelligenceClaimed")) instanceof List) {
            List ids = (List)intelligenceClaimed;
            for (Object id : ids) {
                this.progress.note(accountRoleId, "success", "\u5df2\u9886\u53d6\u60c5\u62a5\u4efb\u52a1\u5956\u52b1\uff08\u7f16\u53f7 " + String.valueOf(id) + "\uff09");
            }
        }
        if ((intelligenceFought = result.get("intelligenceFought")) instanceof Number) {
            Object id;
            id = (Number)intelligenceFought;
            this.progress.note(accountRoleId, "success", "\u5df2\u5feb\u901f\u5b8c\u6210\u60c5\u62a5\u6218\u6597\uff08\u7f16\u53f7 " + String.valueOf(id) + "\uff09");
        }
        if ((backpackDiamonds = result.get("backpackDiamonds")) instanceof Number && (count = (Number)backpackDiamonds).longValue() > 0L) {
            this.progress.note(accountRoleId, "success", "\u5df2\u6253\u5f00\u80cc\u5305\u91cc\u7684\u94bb\u77f3\u9053\u5177 " + count.longValue() + " \u4ef6");
        }
        if ((purchased = result.get("purchase")) instanceof Map && !(purchase = (Map)purchased).isEmpty() && (purchaseMessage = purchase.get("message")) != null && !String.valueOf(purchaseMessage).isBlank()) {
            this.progress.note(accountRoleId, "info", String.valueOf(purchaseMessage));
        }
        this.notifyRunStateTransition(accountRoleId);
    }

    private void publishBootstrapEnded(long accountRoleId, String message) {
        if (this.notificationPublisher == null || !this.endedNotifiedRoles.add(accountRoleId)) {
            return;
        }
        try {
            LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("taskLabel", "\u8d77\u53f7");
            data.put("status", "FAILED");
            data.put("message", message == null || message.isBlank() ? "\u8d77\u53f7\u63d0\u524d\u7ed3\u675f" : message);
            this.notificationPublisher.publish(NotificationEventType.TASK_BOOTSTRAP_RESULT, accountRoleId, "bootstrap:ended", data);
        }
        catch (RuntimeException runtimeException) {
            // empty catch block
        }
    }

    private void notifyRunStateTransition(long accountRoleId) {
        if (this.notificationPublisher == null || accountRoleId <= 0L) {
            return;
        }
        try {
            BootstrapConfig config = this.config(accountRoleId);
            BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
            String stateNow = this.runState(accountRoleId, config, state);
            String previous = this.runStateSnapshots.put(accountRoleId, stateNow);
            if ("COMPLETED".equals(stateNow) && !"COMPLETED".equals(previous)) {
                LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("taskLabel", "\u8d77\u53f7");
                data.put("status", "SUCCESS");
                data.put("message", this.completionMessage(accountRoleId, config));
                this.notificationPublisher.publish(NotificationEventType.TASK_BOOTSTRAP_RESULT, accountRoleId, "bootstrap:completed", data);
            }
        }
        catch (RuntimeException runtimeException) {
            // empty catch block
        }
    }

    private Map<String, Object> runNextStepInternal(long accountRoleId) {
        int cdkeyClaimed;
        Optional<Map<String, Object>> tutorialResult;
        GameAccountSessionService.AccountSession session;
        BootstrapConfig config = this.requireEnabled(accountRoleId);
        Optional<Map<String, Object>> rename = this.renameIfNeeded(accountRoleId, config, session = this.requireOnline(accountRoleId));
        if (rename.isPresent()) {
            return rename.get();
        }
        BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
        if (state.level(301L) >= 7L) {
            Optional<Map<String, Object>> worldResult = this.world.advance(accountRoleId, session);
            if (worldResult.isPresent()) {
                return worldResult.get();
            }
            Optional<Map<String, Object>> intelligence = this.claimIntelligenceRewards(accountRoleId, session);
            if (intelligence.isPresent()) {
                return intelligence.get();
            }
            if (this.finishPassDue(accountRoleId)) {
                try {
                    this.runFinishPass(accountRoleId);
                }
                catch (RuntimeException exception) {
                    log.info("[\u8d77\u53f7] 7\u7ea7\u9886\u5956\u6d41\u7a0b\u5f02\u5e38 message={}", (Object)exception.getMessage());
                }
            }
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return this.actionResult(accountRoleId, "WAITING", "\u7b49\u5f85\u5efa\u7b51\u4e0e\u961f\u5217\u5168\u91cf\u72b6\u6001\u540c\u6b65");
        }
        long nowMillis = System.currentTimeMillis();
        Long lastBusySpeedUp = this.lastBusySpeedUpMillis.get(accountRoleId);
        if (lastBusySpeedUp == null || nowMillis - lastBusySpeedUp >= 30000L) {
            this.lastBusySpeedUpMillis.put(accountRoleId, nowMillis);
            Optional<Map<String, Object>> spedUp = this.finishBusyQueue(accountRoleId, session, state, this.planNext(config, state, 1).actions().stream().findFirst().orElse(null));
            if (spedUp.isPresent()) {
                return spedUp.get();
            }
        }
        if ((tutorialResult = this.tutorial.advance(accountRoleId, session)).isPresent()) {
            return tutorialResult.get();
        }
        Optional<Map<String, Object>> mailResult = this.mails.advance(accountRoleId, session);
        if (mailResult.isPresent()) {
            return mailResult.get();
        }
        Optional<Map<String, Object>> milestoneResult = this.advanceMilestones(accountRoleId, state);
        if (milestoneResult.isPresent()) {
            return milestoneResult.get();
        }
        if (state.level(301L) >= 3L && (cdkeyClaimed = this.rewards.claimCdkeys(accountRoleId)) > 0) {
            Map<String, Object> cdkeyResult = this.actionResult(accountRoleId, "STARTED", "\u5df2\u5151\u6362 " + cdkeyClaimed + " \u6761\u5151\u6362\u7801\u5e76\u6253\u5f00\u80cc\u5305\u94bb\u77f3");
            cdkeyResult.put("rewardClaimed", true);
            return cdkeyResult;
        }
        if (this.rewards.claimPendingQuests(accountRoleId) > 0) {
            Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u9886\u53d6\u672c\u8f6e\u65b0\u5b8c\u6210\u7684\u7ae0\u8282\u4efb\u52a1\u5956\u52b1\u4e0e\u7ae0\u8282\u5b9d\u7bb1");
            result.put("rewardClaimed", true);
            return result;
        }
        Optional<Map<String, Object>> heroResult = this.heroes.advance(accountRoleId, session);
        if (heroResult.isPresent()) {
            return heroResult.get();
        }
        Optional<Map<String, Object>> workResult = this.work.advance(accountRoleId, session);
        if (workResult.isPresent()) {
            return workResult.get();
        }
        Optional<BootstrapStorylineCatalog.Step> storyline = this.pendingStorylineStep(state, this.survivorStates.survivorState(accountRoleId));
        if (storyline.isPresent() && storyline.get().kind() != BootstrapStorylineCatalog.StepKind.BUILDING) {
            return this.executeStorylineStep(accountRoleId, session, state, storyline.get());
        }
        Optional<Map<String, Object>> assignment = this.assignIdleSurvivorToProduction(accountRoleId, session, state);
        if (assignment.isPresent()) {
            return assignment.get();
        }
        BuildingUpgradePlanner.Plan plan = this.planNext(config, state, 8);
        Optional<Map<String, Object>> adventureStep = this.advanceChapterAdventure(accountRoleId, config, state);
        if (adventureStep.isPresent()) {
            return adventureStep.get();
        }
        Optional<BuildingUpgradePlanner.Action> chapterFurniture = this.chapterFurnitureAction(config, state);
        if (chapterFurniture.isPresent()) {
            BuildingUpgradePlanner.Plan furniturePlan = new BuildingUpgradePlanner.Plan(List.of(chapterFurniture.get()), "", true);
            Map<String, Object> result = this.executeFirstAction(accountRoleId, session, state, furniturePlan);
            result.put("chapterObjective", chapterFurniture.get().label());
            return result;
        }
        if (plan.actions().isEmpty()) {
            if (plan.complete()) {
                Optional<Map<String, Object>> allianceResult;
                if (state.level(301L) >= 7L && this.finishPassDue(accountRoleId)) {
                    try {
                        this.runFinishPass(accountRoleId);
                    }
                    catch (RuntimeException exception) {
                        log.info("[\u8d77\u53f7] runNextStep \u6536\u5c3e\u6d41\u7a0b\u5f02\u5e38 message={}", (Object)exception.getMessage());
                    }
                }
                if ((allianceResult = this.attemptAutomaticAllianceJoin(accountRoleId, config, session)).isPresent()) {
                    return allianceResult.get();
                }
                Map<String, Object> result = this.actionResult(accountRoleId, "COMPLETED", this.completionMessage(accountRoleId, config));
                result.put("levelingCompleted", true);
                return result;
            }
            Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", plan.blockedReason().isEmpty() ? "\u5f53\u524d\u6ca1\u6709\u53ef\u6267\u884c\u52a8\u4f5c\uff0c\u7b49\u5f85\u72b6\u6001\u540c\u6b65" : plan.blockedReason());
            result.put("blockedReason", plan.blockedReason());
            return result;
        }
        return this.executeFirstAction(accountRoleId, session, state, plan);
    }

    public Map<String, Object> probeAdventureCheckpoint(long accountRoleId) {
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("checkpoint", 1L);
        payload.put("fight_heros_data", Map.of("source", 1L, "fight_heros", List.of(Long.valueOf(50006L), Long.valueOf(0L), Long.valueOf(0L), Long.valueOf(0L), Long.valueOf(0L))));
        Map<String, Object> content = session.request(ADVENTURE_BEGIN, payload).getContent();
        log.info("[\u8d77\u53f7] \u63a2\u9669\u63a2\u9488 checkpoint=1 \u54cd\u5e94\uff08\u6293\u5305\u57fa\u51c6 seed=187072760 hero atk85/def90/hp540\uff09\uff1a{}", content);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("checkpoint", 1L);
        result.put("heroId", 50006L);
        result.put("baselineSeed", 187072760L);
        result.put("battleTroopsInfo", content.get("battle_troops_info"));
        result.put("message", "\u5df2\u53d6\u56de checkpoint 1 \u6218\u6597\u4fe1\u606f\uff0c\u8bf7\u4e0e\u6293\u5305\u57fa\u51c6\u6bd4\u5bf9");
        return result;
    }

    private Optional<Map<String, Object>> assignIdleSurvivorToProduction(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapBuildingState state) {
        BootstrapSurvivorState survivors = this.survivorStates.survivorState(accountRoleId);
        if (!survivors.initialized() || !state.buildingsReady()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        for (long buildingId : BootstrapTutorialCatalog.PRODUCTION_BUILDINGS) {
            if (state.level(buildingId) <= 0L) continue;
            for (long type : PRODUCTION_STATION_TYPES) {
                for (Map.Entry<Long, List<Long>> entry : state.furnitureByType(buildingId, type).entrySet()) {
                    String retryKey;
                    if (!entry.getValue().isEmpty() || !this.tutorial.retryAllowed(accountRoleId, retryKey = "station:" + buildingId + ":" + String.valueOf(entry.getKey()))) continue;
                    Optional<BootstrapSurvivorState.Survivor> idle = survivors.idleSurvivor();
                    if (idle.isEmpty()) {
                        return Optional.empty();
                    }
                    long fidWork = state.furnitureInstanceId(buildingId, entry.getKey());
                    if (fidWork <= 0L || survivors.assignedTo(fidWork)) continue;
                    long sid = idle.get().sid();
                    try {
                        session.request(SURVIVOR_SEND_OR_FIRED, Map.of("sid", sid, "fid_work", fidWork, "type", 1L));
                        survivors.markAssigned(sid, fidWork);
                        log.info("[\u8d77\u53f7] \u5df2\u628a\u5e78\u5b58\u8005 {} \u6d3e\u5230 {} \u7684\u5de5\u4f4d {}", new Object[]{sid, this.catalog.buildingName(buildingId), fidWork});
                        Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u59d4\u4efb\u5e78\u5b58\u8005 " + sid + " \u5230 " + this.catalog.buildingName(buildingId) + " \u5de5\u4f4d");
                        result.put("stationBuildingId", buildingId);
                        return Optional.of(result);
                    }
                    catch (GameProtocolException exception) {
                        this.tutorial.markRetry(accountRoleId, retryKey, now + 60000L);
                        log.info("[\u8d77\u53f7] \u5de5\u4f4d {} \u6d3e\u9063\u88ab\u62d2\u7edd code={}", (Object)fidWork, (Object)exception.code());
                        return Optional.empty();
                    }
                    catch (RuntimeException exception) {
                        this.tutorial.markRetry(accountRoleId, retryKey, now + 60000L);
                        log.info("[\u8d77\u53f7] \u5de5\u4f4d {} \u6d3e\u9063\u5931\u8d25 message={}", (Object)fidWork, (Object)exception.getMessage());
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> advanceMilestones(long accountRoleId, BootstrapBuildingState state) {
        if (state.level(301L) < 7L) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> info = this.milestones.refreshInfo(accountRoleId);
        if (info.isPresent()) {
            return info;
        }
        List<Long> claimed = this.milestones.claimMilestones(accountRoleId);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        int backpackDiamonds = this.rewards.claimBackpackDiamonds(accountRoleId);
        Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u9886\u53d6 " + claimed.size() + " \u9879\u91cc\u7a0b\u7891\u8fbe\u6210\u5956\u52b1" + (String)(backpackDiamonds > 0 ? "\uff0c\u5e76\u6253\u5f00\u80cc\u5305\u94bb\u77f3\u9053\u5177 x" + backpackDiamonds : ""));
        result.put("milestoneClaimed", claimed);
        if (backpackDiamonds > 0) {
            result.put("backpackDiamonds", backpackDiamonds);
        }
        result.put("rewardClaimed", true);
        return Optional.of(result);
    }

    private Optional<Map<String, Object>> claimIntelligenceRewards(long accountRoleId, GameAccountSessionService.AccountSession session) {
        DailyIntelligenceState intelligence = this.intelligenceStates.intelligenceState(accountRoleId);
        DailyIntelligenceState.Snapshot snapshot = intelligence.snapshot();
        if (!snapshot.initialized()) {
            return Optional.empty();
        }
        ArrayList<Long> claimedIds = new ArrayList<Long>();
        for (DailyIntelligenceState.IntelligenceEvent event : snapshot.events()) {
            if (event.status() != 2) continue;
            try {
                session.request("req_intelligence_receive", IntelligenceQuestSupport.receiveRewardPayload(event.id()));
                intelligence.remove(event.id());
                claimedIds.add(event.id());
            }
            catch (GameProtocolException exception) {
                if (exception.code() == 2501) {
                    intelligence.remove(event.id());
                    continue;
                }
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u9886\u53d6\u60c5\u62a5\u5956\u52b1\u88ab\u62d2\u7edd id={} code={}", new Object[]{accountRoleId, event.id(), exception.code()});
                break;
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u9886\u53d6\u60c5\u62a5\u5956\u52b1\u5931\u8d25 id={} message={}", new Object[]{accountRoleId, event.id(), exception.getMessage()});
                break;
            }
        }
        if (claimedIds.isEmpty()) {
            return this.fightIdleIntelligence(accountRoleId, session, intelligence, snapshot);
        }
        Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u9886\u53d6 " + claimedIds.size() + " \u4e2a\u60c5\u62a5\u4efb\u52a1\u5956\u52b1");
        result.put("intelligenceClaimed", List.copyOf(claimedIds));
        return Optional.of(result);
    }

    private Optional<Map<String, Object>> fightIdleIntelligence(long accountRoleId, GameAccountSessionService.AccountSession session, DailyIntelligenceState intelligence, DailyIntelligenceState.Snapshot snapshot) {
        long now = System.currentTimeMillis();
        DailyIntelligenceState.IntelligenceEvent event = snapshot.events().stream().filter(value -> value.status() == 1).filter(value -> IntelligenceQuestSupport.supportsQuickBattle(value.type())).filter(value -> this.intelligenceRetryAllowed(accountRoleId, value.id(), now)).findFirst().orElse(null);
        if (event == null) {
            return Optional.empty();
        }
        IntelligenceQuestSupport.ClientSquad squad = IntelligenceQuestSupport.clientSquad(this.buildingStates.state(accountRoleId));
        if (squad == null || !squad.ready()) {
            return Optional.empty();
        }
        try {
            session.request("req_intelligence_start_battle", IntelligenceQuestSupport.startBattlePayload(event.id(), squad));
            session.request("req_intelligence_end_battle", IntelligenceQuestSupport.endBattlePayload(event.id()));
            intelligence.markCompleted(event.id());
        }
        catch (GameProtocolException exception) {
            this.intelligenceRetryAt.put(BootstrapService.intelligenceRetryKey(accountRoleId, event.id()), System.currentTimeMillis() + 600000L);
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u53d1\u8d77\u60c5\u62a5\u6218\u6597\u88ab\u62d2\u7edd id={} code={}\uff0c10 \u5206\u949f\u540e\u518d\u8bd5", new Object[]{accountRoleId, event.id(), exception.code()});
            this.progress.note(accountRoleId, "warning", "\u60c5\u62a5\u4efb\u52a1 " + event.id() + " \u6682\u65f6\u65e0\u6cd5\u5feb\u901f\u5b8c\u6210\uff0c\u7a0d\u540e\u81ea\u52a8\u91cd\u8bd5");
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u53d1\u8d77\u60c5\u62a5\u6218\u6597\u5931\u8d25 id={} message={}", new Object[]{accountRoleId, event.id(), exception.getMessage()});
            return Optional.empty();
        }
        Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u5feb\u901f\u5b8c\u6210 1 \u4e2a\u82f1\u96c4\u4e4b\u65c5\u60c5\u62a5\uff08" + squad.label() + "\uff09");
        result.put("intelligenceFought", event.id());
        return Optional.of(result);
    }

    private Map<String, Object> intelligenceView(long accountRoleId) {
        DailyIntelligenceState.Snapshot snapshot = this.intelligenceStates.intelligenceState(accountRoleId).snapshot();
        long idle = snapshot.events().stream().filter(event -> event.status() == 1).count();
        long fighting = snapshot.events().stream().filter(event -> event.status() == 3).count();
        long completed = snapshot.events().stream().filter(event -> event.status() == 2).count();
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("initialized", snapshot.initialized());
        result.put("radarLevel", snapshot.radarLevel());
        result.put("eventCount", snapshot.events().size());
        result.put("idle", idle);
        result.put("fighting", fighting);
        result.put("completed", completed);
        return result;
    }

    private Optional<Map<String, Object>> renameIfNeeded(long accountRoleId, BootstrapConfig config, GameAccountSessionService.AccountSession session) {
        String target;
        String string = target = config.roleName() == null ? "" : config.roleName().trim();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        String current = BootstrapService.currentRoleName(session);
        if (target.equals(current) || target.equals(this.renamedRoles.get(accountRoleId))) {
            this.renameErrors.remove(accountRoleId);
            return Optional.empty();
        }
        try {
            this.requestModifyName(session, target, false);
        }
        catch (GameProtocolException exception) {
            if (exception.code() == 51) {
                try {
                    this.requestModifyName(session, target, true);
                }
                catch (GameProtocolException retry) {
                    this.renameErrors.put(accountRoleId, BootstrapService.renameRejectionMessage(target, retry));
                    return Optional.empty();
                }
            }
            if (exception.code() == 205) {
                this.renamedRoles.put(accountRoleId, target);
                this.renameErrors.remove(accountRoleId);
                return Optional.empty();
            }
            this.renameErrors.put(accountRoleId, BootstrapService.renameRejectionMessage(target, exception));
            return Optional.empty();
        }
        this.renamedRoles.put(accountRoleId, target);
        this.renameErrors.remove(accountRoleId);
        Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u4fee\u6539\u89d2\u8272\u540d\u79f0\u4e3a " + target);
        result.put("rename", Map.of("from", current, "to", target));
        return Optional.of(result);
    }

    private void requestModifyName(GameAccountSessionService.AccountSession session, String name, boolean useDiamond) {
        session.request(MODIFY_NAME_PROTOCOL, Map.of("name", name, "use_diamond", useDiamond));
    }

    private static String renameRejectionMessage(String target, GameProtocolException exception) {
        GameErrorDescriptor descriptor = exception.descriptor();
        String detail = descriptor == null || descriptor.chineseDescription() == null || descriptor.chineseDescription().isBlank() ? exception.getMessage() : descriptor.chineseDescription();
        return "\u65e0\u6cd5\u628a\u89d2\u8272\u540d\u79f0\u6539\u4e3a\u300c" + target + "\u300d\uff1a\u9519\u8bef\u7801 " + exception.code() + "\uff1a" + detail;
    }

    private boolean renamePending(long accountRoleId, BootstrapConfig config, GameAccountSessionService.AccountSession session) {
        String target;
        String string = target = config.roleName() == null ? "" : config.roleName().trim();
        if (target.isEmpty()) {
            return false;
        }
        return !target.equals(BootstrapService.currentRoleName(session)) && !target.equals(this.renamedRoles.get(accountRoleId));
    }

    private static String currentRoleName(GameAccountSessionService.AccountSession session) {
        Map<String, Object> status = session.status();
        String gameName = BootstrapProtocolSupport.text(status.get("game_name"));
        return gameName.isBlank() ? BootstrapProtocolSupport.text(status.get("display_name")) : gameName;
    }

    private long resolveRoleFid(long accountRoleId, GameAccountSessionService.AccountSession session) {
        Long cached = this.roleFids.get(accountRoleId);
        if (cached != null && cached > 0L) {
            return cached;
        }
        try {
            GameMessage response = session.request(PLAYER_BASE_INFO_PROTOCOL, Map.of("uids", List.of(Long.valueOf(session.roleUid()))));
            for (Map<String, Object> player : BootstrapProtocolSupport.maps(BootstrapProtocolSupport.content(response).get("result"))) {
                long fid;
                if (BootstrapProtocolSupport.number(player.get("uid")) != session.roleUid() || (fid = BootstrapProtocolSupport.number(player.get("fid"))) <= 0L) continue;
                this.roleFids.put(accountRoleId, fid);
                return fid;
            }
        }
        catch (RuntimeException exception) {
            log.debug("[\u8d77\u53f7] FID \u8bfb\u53d6\u6682\u672a\u5b8c\u6210 accountRoleId={} message={}", (Object)accountRoleId, (Object)exception.getMessage());
        }
        return 0L;
    }

    private Map<String, Object> executeFirstAction(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapBuildingState state, BuildingUpgradePlanner.Plan plan) {
        long queueId;
        BuildingUpgradePlanner.Action action = (BuildingUpgradePlanner.Action)plan.actions().getFirst();
        Map<String, Object> preparation = this.prepareResources(accountRoleId, action);
        Long backoffUntil = this.upgradeBackoffUntil.get(accountRoleId);
        if (backoffUntil != null && System.currentTimeMillis() < backoffUntil) {
            return this.actionResult(accountRoleId, "WAITING", "\u5efa\u7b51\u961f\u5217\u6b63\u5728\u65bd\u5de5\uff0c\u7a0d\u540e\u518d\u7ee7\u7eed\u5347\u7ea7");
        }
        long l = queueId = action.usesQueue() ? state.idleQueueId(this.serverNowSeconds(accountRoleId)) : 0L;
        if (action.usesQueue() && queueId <= 0L) {
            Optional<Map<String, Object>> finished = this.finishBusyQueue(accountRoleId, session, state, action);
            return finished.orElseGet(() -> this.actionResult(accountRoleId, "WAITING", "\u5f53\u524d\u6ca1\u6709\u7a7a\u95f2\u5efa\u7b51\u961f\u5217\uff0c\u52a0\u901f\u9053\u5177\u4e0e\u94bb\u77f3\u90fd\u4e0d\u8db3\u4ee5\u79d2\u5b8c\u5728\u65bd\u5de5\u7684\u961f\u5217"));
        }
        GameMessage response = null;
        BootstrapService.igniteFurnaceIfNeeded(session, action);
        for (int attempt = 0; attempt < 3; ++attempt) {
            try {
                response = session.request(BootstrapService.protocol(action), BootstrapService.payload(action, queueId));
                break;
            }
            catch (GameProtocolException exception) {
                int code = exception.code();
                if (code == 51 && attempt == 0) {
                    preparation.putAll(this.topUpResources(accountRoleId, action));
                    continue;
                }
                if (code == 154 && action.kind() == BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE) {
                    state.markFurnitureLevel(action.buildingId(), action.furnitureId(), 1L);
                    state.clearFurnitureInstance(action.buildingId(), action.furnitureId());
                    Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", action.buildingName() + " \u7684\u5bb6\u5177 " + action.furnitureId() + " \u670d\u52a1\u7aef\u5df2\u5b58\u5728\uff0c\u6309 1 \u7ea7\u7ee7\u7eed");
                    result.put("step", this.stepView(action));
                    result.put("responseCode", 154);
                    result.put("preparation", Map.copyOf(preparation));
                    return result;
                }
                if (code == 77) {
                    state.markQueueLocked(queueId);
                    Map<String, Object> waiting = this.actionResult(accountRoleId, "WAITING", "\u5efa\u9020\u961f\u5217\u5c1a\u672a\u89e3\u9501\uff0c\u5df2\u6539\u7528\u5176\u5b83\u7a7a\u95f2\u961f\u5217");
                    waiting.put("step", this.stepView(action));
                    waiting.put("errorCode", code);
                    return waiting;
                }
                if (code == 73 || code == 118 || code == 121) {
                    this.upgradeBackoffUntil.put(accountRoleId, System.currentTimeMillis() + 60000L);
                    state.markQueueBusy(queueId);
                    Map<String, Object> waiting = this.actionResult(accountRoleId, "WAITING", "\u5efa\u7b51\u961f\u5217\u6b63\u5728\u65bd\u5de5\uff0c\u7a0d\u540e\u81ea\u52a8\u7ee7\u7eed");
                    waiting.put("step", this.stepView(action));
                    waiting.put("errorCode", code);
                    return waiting;
                }
                if (code == 155 && action.kind() == BuildingUpgradePlanner.ActionKind.UPGRADE_FURNITURE) {
                    session.request(CREATE_FURNITURE, Map.of("bid", action.buildingId(), "conf_fid", action.furnitureId()));
                    continue;
                }
                return this.upgradeRejected(accountRoleId, action, exception, preparation);
            }
        }
        if (response == null) {
            return this.actionResult(accountRoleId, "WAITING", "\u8fde\u7eed\u591a\u6b21\u8bf7\u6c42\u672a\u53d6\u5f97\u7ed3\u679c\uff0c\u7b49\u5f85\u72b6\u6001\u540c\u6b65\u540e\u91cd\u8bd5\uff1a" + action.label());
        }
        if (action.kind() == BuildingUpgradePlanner.ActionKind.UPGRADE_BUILDING) {
            this.upgradeBackoffUntil.remove(accountRoleId);
            boolean spedUp = this.speedUpQueue(accountRoleId, session, queueId, action);
            if (spedUp) {
                state.markBuildingInstantUpgrade(action.buildingId(), action.toLevel());
            } else {
                state.markBuildingStarted(action.buildingId(), queueId);
            }
        } else {
            long targetLevel;
            long appliedLevel = ProtocolValueSupport.number(BootstrapProtocolSupport.content(response).get("lv"));
            long appliedFurnitureId = BootstrapService.furnitureInstanceId(response);
            long l2 = targetLevel = appliedLevel > 0L ? appliedLevel : action.toLevel();
            if (action.kind() == BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE) {
                long type = BootstrapBuildingState.catalogFurnitureType(action.furnitureId());
                if (type > 0L) {
                    state.markFurnitureCreated(action.buildingId(), action.furnitureId(), appliedFurnitureId, targetLevel, type);
                } else {
                    state.markFurnitureCreated(action.buildingId(), action.furnitureId(), appliedFurnitureId, targetLevel);
                }
            } else {
                state.markFurnitureLevel(action.buildingId(), action.furnitureId(), targetLevel);
            }
        }
        log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u5df2\u8bf7\u6c42{}", (Object)accountRoleId, (Object)action.label());
        Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u8bf7\u6c42" + action.label() + BootstrapService.backpackUseSummary(preparation));
        result.put("step", this.stepView(action));
        result.put("responseCode", this.responseCode(response));
        result.put("preparation", preparation);
        return result;
    }

    private boolean speedUpQueue(long accountRoleId, GameAccountSessionService.AccountSession session, long queueId, BuildingUpgradePlanner.Action action) {
        if (queueId <= 0L) {
            return false;
        }
        String label = BootstrapService.actionLabel(action);
        if (this.speedUpWithItems(accountRoleId, session, queueId, action)) {
            return true;
        }
        try {
            session.request(QUEUE_SPEED, Map.of("qid", queueId, "use_diamond", true));
            log.info("[\u8d77\u53f7] \u5df2\u7528\u94bb\u77f3\u79d2\u5b8c {} \u7684\u961f\u5217 {}", (Object)label, (Object)queueId);
            return true;
        }
        catch (GameProtocolException exception) {
            if (exception.code() == 71) {
                BootstrapService.logQueueFinished(queueId, action);
                return true;
            }
            log.info("[\u8d77\u53f7] \u79d2\u5b8c\u961f\u5217 {} \u5931\u8d25\uff08\u6309\u6392\u961f\u7b49\u5f85\u5904\u7406\uff09code={} message={}", new Object[]{queueId, exception.code(), exception.getMessage()});
            return false;
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u79d2\u5b8c\u961f\u5217 {} \u5931\u8d25\uff08\u6309\u6392\u961f\u7b49\u5f85\u5904\u7406\uff09message={}", (Object)queueId, (Object)exception.getMessage());
            return false;
        }
    }

    private boolean speedUpWithItems(long accountRoleId, GameAccountSessionService.AccountSession session, long queueId, BuildingUpgradePlanner.Action action) {
        BehemothAccountState inventory = this.buildingStates.state(accountRoleId);
        for (long itemId : SPEEDUP_ITEM_IDS) {
            long count = inventory.itemCount(itemId);
            if (count <= 0L) continue;
            try {
                session.request(QUEUE_SPEED, Map.of("qid", queueId, "use_diamond", false, "items", List.of(Map.of("id", itemId, "count", count))));
                log.info("[\u8d77\u53f7] \u5df2\u7528\u52a0\u901f\u9053\u5177 {} x{} \u79d2\u5b8c {} \u7684\u961f\u5217 {}", new Object[]{itemId, count, BootstrapService.actionLabel(action), queueId});
                return true;
            }
            catch (GameProtocolException exception) {
                if (exception.code() == 71) {
                    BootstrapService.logQueueFinished(queueId, action);
                    return true;
                }
                log.info("[\u8d77\u53f7] \u52a0\u901f\u9053\u5177 {} \u79d2\u961f\u5217\u5931\u8d25 code={}", (Object)itemId, (Object)exception.code());
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] \u52a0\u901f\u9053\u5177 {} \u79d2\u961f\u5217\u5931\u8d25 message={}", (Object)itemId, (Object)exception.getMessage());
            }
        }
        return false;
    }

    private static String actionLabel(BuildingUpgradePlanner.Action action) {
        return action == null ? "\u6b63\u5728\u65bd\u5de5\u7684\u5efa\u7b51" : action.label();
    }

    private static void logQueueFinished(long queueId, BuildingUpgradePlanner.Action action) {
        log.info("[\u8d77\u53f7] \u961f\u5217 {} \u5df2\u7ed3\u675f\uff0c\u89c6\u4e3a {} \u5347\u7ea7\u5b8c\u6210", (Object)queueId, (Object)BootstrapService.actionLabel(action));
    }

    private Optional<Map<String, Object>> finishBusyQueue(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapBuildingState state, BuildingUpgradePlanner.Action action) {
        long busyQueueId = state.busyQueueId();
        if (busyQueueId <= 0L) {
            return Optional.empty();
        }
        if (!this.speedUpQueue(accountRoleId, session, busyQueueId, action)) {
            return Optional.empty();
        }
        this.lastBusySpeedUpMillis.put(accountRoleId, System.currentTimeMillis());
        long busyBuildingId = state.buildingOnQueue(busyQueueId);
        if (busyBuildingId > 0L) {
            state.markBuildingInstantUpgrade(busyBuildingId, state.level(busyBuildingId) + 1L);
        }
        return Optional.of(this.actionResult(accountRoleId, "STARTED", "\u5df2\u79d2\u5b8c\u6b63\u5728\u65bd\u5de5\u7684\u5efa\u9020\u961f\u5217 " + busyQueueId + "\uff0c\u7ee7\u7eed\u5347\u7ea7"));
    }

    private static void igniteFurnaceIfNeeded(GameAccountSessionService.AccountSession session, BuildingUpgradePlanner.Action action) {
        if (action.kind() != BuildingUpgradePlanner.ActionKind.UPGRADE_BUILDING || action.buildingId() != 301L || action.fromLevel() > 0L) {
            return;
        }
        try {
            session.request(STOVE_COMBUSTION_SWITCH, Map.of("switch", 1L, "overdrive", 0L));
            log.info("[\u8d77\u53f7] \u5df2\u70b9\u706b\u5927\u7194\u7089\uff08switch=1, overdrive=0\uff09\uff0c\u968f\u540e\u5f00\u59cb\u9996\u6b21\u5347\u7ea7");
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u70b9\u706b\u5927\u7194\u7089\u5931\u8d25 message={}", (Object)exception.getMessage());
        }
    }

    public Map<String, Object> runUntil(long accountRoleId, int maxSteps) {
        BootstrapBuildingState finalState;
        this.requireEnabled(accountRoleId);
        int limit = Math.clamp((long)maxSteps, (int)1, (int)300);
        ArrayList<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        int started = 0;
        Object stopMessage = "";
        long waitBudget = 90000L;
        int index = 0;
        while (index < limit) {
            Map<String, Object> result = this.runNextStep(accountRoleId);
            String status = BootstrapProtocolSupport.text(result.get("status"));
            if (!"STARTED".equals(status)) {
                if (BootstrapService.isResourceEnough(result) || waitBudget <= 0L) {
                    results.add(result);
                    stopMessage = BootstrapProtocolSupport.text(result.get("message"));
                    break;
                }
                BootstrapService.sleepQuietly(3000L);
                waitBudget -= 3000L;
                continue;
            }
            results.add(result);
            ++started;
            BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
            if (++index >= limit) {
                stopMessage = "\u672c\u8f6e\u5df2\u8fbe\u5230\u5355\u6b21\u6267\u884c\u4e0a\u9650 " + limit + " \u6b65\uff0c\u53ef\u518d\u6b21\u70b9\u51fb\u7ee7\u7eed";
                break;
            }
            if (!state.buildingsReady() || !state.queuesReady()) break;
            BootstrapService.sleepQuietly(50L + ThreadLocalRandom.current().nextLong(100L));
        }
        if ((finalState = this.buildingStates.buildingState(accountRoleId)).level(301L) >= 7L && this.finishPassDue(accountRoleId)) {
            try {
                this.runFinishPass(accountRoleId);
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] runUntil \u6536\u5c3e\u6d41\u7a0b\u5f02\u5e38 message={}", (Object)exception.getMessage());
            }
        }
        LinkedHashMap<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("started", started);
        summary.put("results", List.copyOf(results));
        summary.put("state", this.status(accountRoleId));
        summary.put("message", BootstrapService.stopSummary(started, (String)stopMessage));
        return summary;
    }

    private static String stopSummary(int started, String stopMessage) {
        String detail;
        String string = detail = stopMessage == null ? "" : stopMessage.trim();
        if (started == 0) {
            return detail.isEmpty() ? "\u6ca1\u6709\u6267\u884c\u4efb\u4f55\u5347\u7ea7\uff0c\u8bf7\u68c0\u67e5\u72b6\u6001" : detail;
        }
        return detail.isEmpty() ? "\u5df2\u8fde\u7eed\u6267\u884c " + started + " \u6b65" : "\u5df2\u8fde\u7eed\u6267\u884c " + started + " \u6b65\uff1b" + detail;
    }

    private Map<String, Object> upgradeRejected(long accountRoleId, BuildingUpgradePlanner.Action action, GameProtocolException exception, Map<String, Object> preparation) {
        int code = exception.code();
        if (code == 806) {
            BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
            long now = System.currentTimeMillis();
            long[] stall = this.conditionStall.merge(accountRoleId, new long[]{now, 1L}, (value, incoming) -> {
                long[] lArray;
                if (now - value[0] <= 90000L) {
                    long[] lArray2 = new long[2];
                    lArray2[0] = now;
                    lArray = lArray2;
                    lArray2[1] = value[1] + 1L;
                } else {
                    lArray = incoming;
                }
                return lArray;
            });
            if (stall[1] >= 15L) {
                this.conditionStall.remove(accountRoleId);
                this.resourceEndedRoles.add(accountRoleId);
                Map<String, Object> ended = this.actionResult(accountRoleId, "ENDED", "\u670d\u52a1\u7aef\u6301\u7eed\u5224\u5b9a\u524d\u7f6e\u5efa\u7b51\u7b49\u7ea7\u4e0d\u8db3\uff08\u672c\u5730\u8bb0\u5f55\u4e0e\u670d\u52a1\u7aef\u76f8\u5dee 1 \u7ea7\uff09\uff0c\u8d77\u53f7\u7ed3\u675f\uff1b\u91cd\u65b0\u5f00\u542f\u8d77\u53f7\u4f1a\u81ea\u52a8\u91cd\u65b0\u540c\u6b65\u5efa\u7b51\u7b49\u7ea7");
                ended.put("step", this.stepView(action));
                ended.put("errorCode", code);
                ended.put("resourceEnded", true);
                return ended;
            }
            Map<String, Object> result = this.actionResult(accountRoleId, "RESYNC_REQUIRED", "\u670d\u52a1\u7aef\u5224\u5b9a\u524d\u7f6e\u5efa\u7b51\u7b49\u7ea7\u4e0d\u8db3\uff0c\u672c\u5730\u5efa\u7b51\u7b49\u7ea7\u53ef\u80fd\u5c1a\u672a\u540c\u6b65\uff0c\u8bf7\u7b49\u5f85\u5168\u91cf\u540c\u6b65\u540e\u91cd\u8bd5");
            result.put("step", this.stepView(action));
            result.put("errorCode", code);
            result.put("sync", Map.of("buildings", state.buildingsReady(), "queues", state.queuesReady()));
            return result;
        }
        if (code == 119) {
            Map<String, Object> result = this.actionResult(accountRoleId, "EXP_BLOCKED", action.buildingName() + " \u7684\u5bb6\u5177\u7ecf\u9a8c\u4e0d\u8db3\uff0c\u5df2\u6539\u4e3a\u5148\u8865\u9f50\u5bb6\u5177\u518d\u5347\u7ea7\u5efa\u7b51");
            result.put("step", this.stepView(action));
            result.put("errorCode", code);
            result.put("blockedBuildingId", action.buildingId());
            return result;
        }
        if (code == 51) {
            int stall;
            Optional<Map<String, Object>> retryDiamond;
            this.rewards.claimBackpackDiamonds(accountRoleId);
            Map<String, Object> topUp = this.topUpResources(accountRoleId, action);
            BootstrapBuildingState bState = this.buildingStates.buildingState(accountRoleId);
            if (bState.level(301L) >= 9L) {
                int attempts;
                LinkedHashMap<String, Object> combinedPrep = new LinkedHashMap<String, Object>();
                if (preparation != null) {
                    combinedPrep.putAll(preparation);
                }
                combinedPrep.putAll(topUp);
                Map<String, Object> purchaseView = BootstrapProtocolSupport.map(topUp.get("purchase"));
                Object coverableFlag = purchaseView.get("coverable");
                boolean coverable = coverableFlag == null || Boolean.TRUE.equals(coverableFlag);
                int n = attempts = coverable ? 0 : this.resourceExhaustedAttempts.merge(accountRoleId, 1, Integer::sum);
                if (!coverable && (this.resourceEndedRoles.contains(accountRoleId) || attempts >= 3)) {
                    this.resourceEndedRoles.add(accountRoleId);
                    Map<String, Object> result = this.actionResult(accountRoleId, "ENDED", "\u5927\u7194\u7089\u5df2\u5230 9 \u7ea7\uff0c\u8d44\u6e90\u5df2\u5c3d\u529b\u8865\u5145\u4ecd\u4e0d\u8db3\uff08" + BootstrapService.unsuppliedSummary(topUp) + "\uff09\uff0c\u8d77\u53f7\u7ed3\u675f");
                    result.put("step", this.stepView(action));
                    result.put("errorCode", code);
                    result.put("preparation", combinedPrep);
                    result.put("resourceEnded", true);
                    return result;
                }
                Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", "\u8d44\u6e90\u6216\u9053\u5177\u4e0d\u8db3\uff0c\u7b49\u5f85\u8865\u5145\u540e\u91cd\u8bd5\uff1a" + action.label() + BootstrapService.preparationSummary(combinedPrep) + (String)(coverable ? "" : "\uff08" + BootstrapService.unsuppliedSummary(topUp) + "\uff09"));
                result.put("step", this.stepView(action));
                result.put("errorCode", code);
                result.put("preparation", combinedPrep);
                return result;
            }
            Optional<Map<String, Object>> diamondResult = this.upgradeWithDiamond(accountRoleId, action);
            if (diamondResult.isPresent()) {
                this.resourceExhaustedAttempts.remove(accountRoleId);
                return diamondResult.get();
            }
            if (bState.level(301L) >= 5L && (retryDiamond = this.upgradeWithDiamond(accountRoleId, action)).isPresent()) {
                this.resourceExhaustedAttempts.remove(accountRoleId);
                return retryDiamond.get();
            }
            LinkedHashMap<String, Object> combinedPrep = new LinkedHashMap<String, Object>();
            if (preparation != null) {
                combinedPrep.putAll(preparation);
            }
            combinedPrep.putAll(topUp);
            Object coverableFlag = BootstrapProtocolSupport.map(topUp.get("purchase")).get("coverable");
            boolean coverable = coverableFlag == null || Boolean.TRUE.equals(coverableFlag);
            int n = stall = coverable ? 0 : this.resourceExhaustedAttempts.merge(accountRoleId, 1, Integer::sum);
            if (!coverable && bState.level(301L) >= 7L && stall >= 10) {
                this.resourceEndedRoles.add(accountRoleId);
                Map<String, Object> result = this.actionResult(accountRoleId, "ENDED", "\u5927\u7194\u7089\u5df2\u5230 " + bState.level(301L) + " \u7ea7\uff0c\u8d44\u6e90\u5df2\u5c3d\u529b\u8865\u5145\u4ecd\u4e0d\u8db3\uff08" + BootstrapService.unsuppliedSummary(topUp) + "\uff09\uff0c\u8d77\u53f7\u7ed3\u675f");
                result.put("step", this.stepView(action));
                result.put("errorCode", code);
                result.put("preparation", combinedPrep);
                result.put("resourceEnded", true);
                return result;
            }
            Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", "\u8d44\u6e90\u6216\u9053\u5177\u4e0d\u8db3\uff0c\u7b49\u5f85\u8865\u5145\u540e\u91cd\u8bd5\uff1a" + action.label() + BootstrapService.preparationSummary(combinedPrep));
            result.put("step", this.stepView(action));
            result.put("errorCode", code);
            result.put("preparation", combinedPrep);
            return result;
        }
        if (code == 120) {
            Map<String, Object> result = this.actionResult(accountRoleId, "RESYNC_REQUIRED", "\u670d\u52a1\u7aef\u5224\u5b9a\u5efa\u7b51\u7f3a\u5c11\u5bb6\u5177\uff0c\u7b49\u5f85\u5bb6\u5177\u6570\u636e\u540c\u6b65\u540e\u91cd\u8bd5");
            result.put("step", this.stepView(action));
            result.put("errorCode", code);
            return result;
        }
        if (code == 118 || code == 121) {
            Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", "\u670d\u52a1\u7aef\u5224\u5b9a\u5efa\u7b51\u961f\u5217\u5fd9\u6216\u5efa\u7b51\u6b63\u5728\u5347\u7ea7\uff0c\u7b49\u5f85\u5b8c\u6210\u901a\u77e5\u540e\u7ee7\u7eed");
            result.put("step", this.stepView(action));
            result.put("errorCode", code);
            return result;
        }
        if (code == 153) {
            long currentBuildingLevel;
            long cap = currentBuildingLevel = this.buildingStates.buildingState(accountRoleId).level(action.buildingId());
            for (DailyTownDevelopmentCatalog.Cost req : this.catalog.furnitureRequirement(action.buildingId(), currentBuildingLevel)) {
                if (req.id() != action.furnitureId()) continue;
                cap = Math.max(cap, req.amount());
            }
            if (cap > 0L) {
                this.buildingStates.buildingState(accountRoleId).markFurnitureLevel(action.buildingId(), action.furnitureId(), cap);
            }
            Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", action.buildingName() + " \u7684\u5bb6\u5177 " + action.furnitureId() + " \u5df2\u8fbe\u5f53\u524d\u5efa\u7b51\u7b49\u7ea7\u4e0a\u9650\uff0c\u7b49\u5f85\u5347\u7ea7 " + action.buildingName() + " \u89e3\u9501\u66f4\u9ad8\u7b49\u7ea7");
            result.put("step", this.stepView(action));
            result.put("errorCode", code);
            return result;
        }
        this.upgradeBackoffUntil.put(accountRoleId, System.currentTimeMillis() + 30000L);
        Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", "\u670d\u52a1\u7aef\u6682\u65f6\u62d2\u7edd\u4e86\u672c\u6b21\u5347\u7ea7\uff08\u9519\u8bef\u7801 " + code + "\uff09\uff0c\u7a0d\u540e\u81ea\u52a8\u91cd\u8bd5");
        result.put("step", this.stepView(action));
        result.put("errorCode", code);
        return result;
    }

    private static String unsuppliedSummary(Map<String, Object> topUp) {
        List entries;
        Map<String, Object> purchase = BootstrapProtocolSupport.map(topUp.get("purchase"));
        Object object = purchase.get("unsupplied");
        if (!(object instanceof List) || (entries = (List)object).isEmpty()) {
            return "\u5546\u5e97\u65e0\u53ef\u8d2d\u4e70\u8d44\u6e90\u6216\u94bb\u77f3\u4e0d\u8db3";
        }
        ArrayList<CallSite> parts = new ArrayList<CallSite>();
        for (Object entry : entries) {
            Map<String, Object> map = BootstrapProtocolSupport.map(entry);
            parts.add((CallSite)((Object)(BootstrapProtocolSupport.text(map.get("name")) + " \u7f3a " + BootstrapProtocolSupport.number(map.get("missing")))));
        }
        return String.join((CharSequence)"\uff0c", parts);
    }

    private static String preparationSummary(Map<String, Object> preparation) {
        List<?> used;
        if (preparation == null || preparation.isEmpty()) {
            return "";
        }
        ArrayList<Object> parts = new ArrayList<Object>();
        if (preparation.containsKey("rewards")) {
            parts.add("\u5df2\u9886\u53d6\u4efb\u52a1\u5956\u52b1");
        }
        if (!(used = BootstrapService.backpackUsedItems(preparation)).isEmpty()) {
            parts.add("\u5df2\u5f00\u542f\u80cc\u5305\u8d44\u6e90\u5305 " + used.size() + " \u9879");
        }
        if (preparation.containsKey("purchase")) {
            parts.add("\u5df2\u5c1d\u8bd5\u7edf\u5e05\u5546\u5e97\u8865\u8d44\u6e90");
        }
        return parts.isEmpty() ? "" : "\uff08" + String.join((CharSequence)"\uff1b", parts) + "\uff09";
    }

    private static String backpackUseSummary(Map<String, Object> preparation) {
        List<?> used = BootstrapService.backpackUsedItems(preparation);
        return used.isEmpty() ? "" : "\uff08\u5df2\u5f00\u542f\u80cc\u5305\u8d44\u6e90\u5305 " + used.size() + " \u9879\uff09";
    }

    private static List<?> backpackUsedItems(Map<String, Object> preparation) {
        List list;
        Object object;
        if (preparation == null || !((object = preparation.get("backpack")) instanceof Map)) {
            return List.of();
        }
        Map map = (Map)object;
        Object used = map.get("used");
        return used instanceof List ? (list = (List)used) : List.of();
    }

    public Map<String, Object> levelBuilding(long accountRoleId, long buildingId) {
        this.requireEnabled(accountRoleId);
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        BootstrapBuildingState state = this.buildingStates.buildingState(accountRoleId);
        if (!state.buildingsReady() || !state.queuesReady()) {
            return this.actionResult(accountRoleId, "WAITING", "\u7b49\u5f85\u5efa\u7b51\u4e0e\u961f\u5217\u5168\u91cf\u72b6\u6001\u540c\u6b65");
        }
        if (!this.catalog.supports(buildingId)) {
            throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684\u5efa\u7b51\u7f16\u53f7: " + buildingId);
        }
        long current = state.level(buildingId);
        if (current >= this.catalog.maxLevel(buildingId)) {
            return this.actionResult(accountRoleId, "COMPLETED", "\u8be5\u5efa\u7b51\u5df2\u6ee1\u7ea7\u6216\u4e0d\u53ef\u5347\u7ea7");
        }
        BuildingUpgradePlanner.Plan plan = this.planner.plan(new BuildingUpgradePlanner.Request(this.currentLevels(state), state.furnitureLevels(), Map.of(buildingId, current + 1L), state.busyBuildings(), BuildingUpgradePlanner.ResourceAvailability.ALWAYS, 8));
        if (plan.actions().isEmpty()) {
            Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", plan.blockedReason().isEmpty() ? "\u8be5\u5efa\u7b51\u5f53\u524d\u6ca1\u6709\u53ef\u6267\u884c\u52a8\u4f5c" : plan.blockedReason());
            result.put("blockedReason", plan.blockedReason());
            return result;
        }
        return this.executeFirstAction(accountRoleId, session, state, plan);
    }

    Map<String, Object> prepareResources(long accountRoleId, BuildingUpgradePlanner.Action action) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            result.put("rewards", this.rewards.claimAll(accountRoleId));
        }
        catch (RuntimeException exception) {
            result.put("rewards", Map.of("message", "\u9886\u5956\u5931\u8d25\uff1a" + exception.getMessage()));
        }
        result.putAll(this.topUpResources(accountRoleId, action));
        return result;
    }

    private Map<String, Object> topUpResources(long accountRoleId, BuildingUpgradePlanner.Action action) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        List<DailyTownDevelopmentCatalog.Cost> costs = action.costs();
        if (costs.isEmpty() || action.buildingId() == 318L) {
            return result;
        }
        result.put("backpack", this.useBackpackResourcePacks(accountRoleId, costs));
        BehemothAccountState inventory = this.buildingStates.state(accountRoleId);
        long missingMeat = 0L;
        long missingWood = 0L;
        long missingCoal = 0L;
        long missingIron = 0L;
        for (DailyTownDevelopmentCatalog.Cost cost : costs) {
            long missing = Math.max(0L, cost.amount() - DailyBackpackResourceSupport.resourceCount(inventory, cost.id()));
            if (cost.id() == 102L) {
                missingMeat = missing;
                continue;
            }
            if (cost.id() == 103L) {
                missingWood = missing;
                continue;
            }
            if (cost.id() == 104L) {
                missingCoal = missing;
                continue;
            }
            if (cost.id() != 105L) continue;
            missingIron = missing;
        }
        if (missingMeat == 0L && missingWood == 0L && missingCoal == 0L && missingIron == 0L) {
            return result;
        }
        try {
            if (missingMeat > 0L) {
                result.put("purchase", this.shops.buyMissingResources(accountRoleId, missingMeat, missingWood, missingCoal, missingIron, inventory.resourceCount(101L)));
            } else {
                result.put("purchase", this.shops.buyMissingResources(accountRoleId, missingWood, missingCoal, missingIron, inventory.resourceCount(101L)));
            }
        }
        catch (RuntimeException exception) {
            result.put("purchase", Map.of("message", "\u8865\u8d44\u6e90\u5931\u8d25\uff1a" + exception.getMessage()));
        }
        return result;
    }

    private Map<String, Object> useBackpackResourcePacks(long accountRoleId, List<DailyTownDevelopmentCatalog.Cost> costs) {
        return DailyBackpackResourceSupport.usePacks(this.sessions.onlineSession(accountRoleId), this.buildingStates.state(accountRoleId), costs, "[\u8d77\u53f7]");
    }

    public List<Map<String, Object>> shopResourcePacks(long accountRoleId) {
        return this.shops.resourcePacks(accountRoleId);
    }

    public int pendingRewardCount(long accountRoleId) {
        return this.rewards.pendingCount(accountRoleId);
    }

    private long serverNowSeconds(long accountRoleId) {
        return this.sessions.gameServerClock(accountRoleId).map(GameServerClock::epochSecond).orElseGet(() -> System.currentTimeMillis() / 1000L);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> resolveAlliance(long accountRoleId, String keyword) {
        String key;
        String string = key = keyword == null ? "" : keyword.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("\u8bf7\u586b\u5199\u8054\u76df\u540d\u79f0\u6216\u7b80\u79f0");
        }
        if (key.length() > 16) {
            throw new IllegalArgumentException("\u8054\u76df\u540d\u79f0\u6216\u7b80\u79f0\u6700\u957f 16 \u4e2a\u5b57\u7b26");
        }
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        GameMessage response = session.request(ALLIANCE_SEARCH, Map.of("key", key));
        ArrayList matches = new ArrayList();
        for (Map<String, Object> alliance : BootstrapProtocolSupport.maps(BootstrapProtocolSupport.content(response).get("alliances"))) {
            String name = BootstrapProtocolSupport.text(alliance.get("name"));
            String abbr = BootstrapProtocolSupport.text(alliance.get("abbr"));
            if (!name.equals(key) && !abbr.equals(key)) continue;
            matches.add(this.allianceView(alliance));
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("keyword", key);
        result.put("matches", matches);
        result.put("count", matches.size());
        if (matches.size() == 1) {
            result.put("allianceId", ((Map)matches.getFirst()).get("id"));
            result.put("unique", true);
        } else {
            result.put("allianceId", 0L);
            result.put("unique", false);
        }
        return result;
    }

    public Map<String, Object> joinAlliance(long accountRoleId) {
        BootstrapConfig config = this.requireEnabled(accountRoleId);
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        return this.joinAllianceInternal(accountRoleId, config, session, false);
    }

    private Map<String, Object> joinAllianceInternal(long accountRoleId, BootstrapConfig config, GameAccountSessionService.AccountSession session, boolean automatic) {
        String keyword;
        String string = keyword = config.allianceName() == null ? "" : config.allianceName().trim();
        if (keyword.isEmpty()) {
            throw new IllegalStateException("\u5c1a\u672a\u914d\u7f6e\u8054\u76df\u540d\u79f0\u6216\u7b80\u79f0");
        }
        try {
            GameMessage response;
            Map<String, Object> resolved;
            try {
                resolved = this.resolveAlliance(accountRoleId, keyword);
            }
            catch (GameProtocolException exception) {
                throw new AllianceJoinTerminalException("\u67e5\u8be2\u76ee\u6807\u8054\u76df\u300c" + keyword + "\u300d\u5931\u8d25\uff1a" + exception.rejectionText(), exception);
            }
            if (!Boolean.TRUE.equals(resolved.get("unique"))) {
                throw new AllianceJoinTerminalException("\u76ee\u6807\u8054\u76df\u300c" + keyword + "\u300d\u5f53\u524d\u672a\u5f00\u653e\u6216\u672a\u627e\u5230\uff0c\u8bf7\u786e\u8ba4\u540d\u79f0/\u7b80\u79f0");
            }
            long allianceId = ProtocolValueSupport.number(resolved.get("allianceId"));
            Map alliance = (Map)BootstrapProtocolSupport.maps(resolved.get("matches")).getFirst();
            String allianceName = BootstrapProtocolSupport.text(alliance.get("name"));
            boolean publicRecruit = BootstrapService.booleanValue(alliance.get("publicRecruit"));
            if (!publicRecruit && !config.autoApplyWhenPrivate()) {
                throw new AllianceJoinTerminalException("\u76ee\u6807\u8054\u76df\u300c" + keyword + "\u300d\u4e3a\u975e\u516c\u5f00\u62db\u52df\uff0c\u672a\u5f00\u542f\u81ea\u52a8\u7533\u8bf7\uff0c\u65e0\u6cd5\u81ea\u52a8\u52a0\u5165");
            }
            String protocol = publicRecruit ? ALLIANCE_JOIN : ALLIANCE_APPLY_JOIN;
            while (true) {
                try {
                    response = session.request(protocol, Map.of("aid", allianceId));
                }
                catch (GameProtocolException exception) {
                    int code = exception.code();
                    if (code == 1501) {
                        return this.completeAllianceMembership(accountRoleId, AllianceFollowUpState.JOINED, allianceId, allianceName.isEmpty() ? keyword : allianceName, alliance, protocol, code, "\u5f53\u524d\u89d2\u8272\u5df2\u5728\u8054\u76df\u4e2d\uff0c\u65e0\u9700\u91cd\u590d\u52a0\u5165");
                    }
                    if (code == 1515) {
                        return this.completeAllianceMembership(accountRoleId, AllianceFollowUpState.APPLIED, allianceId, allianceName.isEmpty() ? keyword : allianceName, alliance, ALLIANCE_APPLY_JOIN, code, "\u5df2\u63d0\u4ea4\u8fc7\u8054\u76df\u300c" + keyword + "\u300d\u7533\u8bf7\uff0c\u7b49\u5f85\u5ba1\u6279");
                    }
                    if (code == 1512 && config.autoApplyWhenPrivate() && ALLIANCE_JOIN.equals(protocol)) {
                        protocol = ALLIANCE_APPLY_JOIN;
                        continue;
                    }
                    throw new AllianceJoinTerminalException("\u8054\u76df\u300c" + keyword + "\u300d\u5f53\u524d\u65e0\u6cd5\u52a0\u5165\uff1a" + exception.rejectionText(), exception);
                }
                break;
            }
            if (response == null) {
                throw new AllianceJoinTerminalException("\u8054\u76df\u300c" + keyword + "\u300d\u5f53\u524d\u65e0\u6cd5\u52a0\u5165\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
            }
            boolean applied = ALLIANCE_APPLY_JOIN.equals(protocol);
            AllianceFollowUpState followState = applied ? AllianceFollowUpState.APPLIED : AllianceFollowUpState.JOINED;
            String message = applied ? "\u5df2\u63d0\u4ea4\u8054\u76df\u300c" + keyword + "\u300d\u7533\u8bf7\uff0c\u7b49\u5f85\u5ba1\u6279" : "\u5df2\u52a0\u5165\u8054\u76df\u300c" + keyword + "\u300d";
            return this.completeAllianceMembership(accountRoleId, followState, allianceId, allianceName.isEmpty() ? keyword : allianceName, alliance, protocol, this.responseCode(response), message);
        }
        catch (AllianceJoinTerminalException exception) {
            if (automatic) {
                this.allianceFollowUps.put(accountRoleId, new AllianceFollowUp(AllianceFollowUpState.FAILED, 0L, keyword, exception.getMessage()));
                this.allianceRetryAt.remove(accountRoleId);
                this.publishAllianceJoinFailure(accountRoleId, keyword, exception.getMessage());
            }
            throw exception;
        }
    }

    private Map<String, Object> completeAllianceMembership(long accountRoleId, AllianceFollowUpState state, long allianceId, String allianceName, Map<String, Object> alliance, String protocol, long responseCode, String message) {
        this.allianceFollowUps.put(accountRoleId, new AllianceFollowUp(state, allianceId, allianceName, message));
        this.allianceRetryAt.remove(accountRoleId);
        this.allianceFailureNotifiedRoles.remove(accountRoleId);
        Map<String, Object> result = this.actionResult(accountRoleId, state == AllianceFollowUpState.APPLIED ? "APPLIED" : "JOINED", message);
        result.put("allianceId", allianceId);
        result.put("alliance", alliance);
        result.put("allianceName", allianceName);
        result.put("allianceFollowUp", state.name());
        result.put("protocol", protocol);
        result.put("responseCode", responseCode);
        result.put("demandApply", state == AllianceFollowUpState.APPLIED);
        return result;
    }

    private Optional<Map<String, Object>> attemptAutomaticAllianceJoin(long accountRoleId, BootstrapConfig config, GameAccountSessionService.AccountSession session) {
        if (BootstrapService.allianceNotRequired(config)) {
            return Optional.empty();
        }
        AllianceFollowUp existing = this.allianceFollowUps.get(accountRoleId);
        if (existing != null) {
            if (existing.state() == AllianceFollowUpState.FAILED) {
                return Optional.of(this.actionResult(accountRoleId, "MANUAL", existing.message()));
            }
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        Long retryAt = this.allianceRetryAt.get(accountRoleId);
        if (retryAt != null && now < retryAt) {
            return Optional.of(this.actionResult(accountRoleId, "WAITING", "\u5347\u7ea7\u5df2\u5b8c\u6210\uff0c\u7b49\u5f85\u81ea\u52a8\u91cd\u8bd5\u52a0\u5165\u8054\u76df\u300c" + config.allianceName().trim() + "\u300d"));
        }
        try {
            Map<String, Object> joined = this.joinAllianceInternal(accountRoleId, config, session, true);
            String message = BootstrapProtocolSupport.text(joined.get("message"));
            joined.put("status", "STARTED");
            joined.put("message", "\u5347\u7ea7\u5df2\u5b8c\u6210\uff0c" + message);
            return Optional.of(joined);
        }
        catch (AllianceJoinTerminalException exception) {
            Map<String, Object> result = this.actionResult(accountRoleId, "MANUAL", "\u5347\u7ea7\u5df2\u5b8c\u6210\uff0c\u4f46\u81ea\u52a8\u5165\u76df\u672a\u5b8c\u6210\uff1a" + exception.getMessage());
            result.put("allianceFailure", true);
            return Optional.of(result);
        }
        catch (RuntimeException exception) {
            this.allianceRetryAt.put(accountRoleId, now + 30000L);
            String message = "\u5347\u7ea7\u5df2\u5b8c\u6210\uff0c\u81ea\u52a8\u52a0\u5165\u8054\u76df\u6682\u672a\u6210\u529f\uff1a" + BootstrapService.failureText(exception) + "\uff0c\u7a0d\u540e\u81ea\u52a8\u91cd\u8bd5";
            return Optional.of(this.actionResult(accountRoleId, "WAITING", message));
        }
    }

    private static boolean allianceNotRequired(BootstrapConfig config) {
        return config.allianceName() == null || config.allianceName().isBlank();
    }

    private Map<String, Object> allianceFollowUpView(long accountRoleId, BootstrapConfig config) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        String configuredName = config.allianceName() == null ? "" : config.allianceName().trim();
        result.put("configuredName", configuredName);
        if (configuredName.isEmpty()) {
            result.put("state", "NOT_CONFIGURED");
            result.put("allianceId", 0L);
            result.put("allianceName", "");
            result.put("message", "\u672a\u914d\u7f6e\u8054\u76df\uff0c\u5347\u7ea7\u5b8c\u6210\u540e\u4e0d\u4f1a\u6267\u884c\u5165\u76df");
            return result;
        }
        AllianceFollowUp followUp = this.allianceFollowUps.get(accountRoleId);
        if (followUp == null) {
            result.put("state", "PENDING");
            result.put("allianceId", 0L);
            result.put("allianceName", configuredName);
            result.put("message", "\u5347\u7ea7\u5b8c\u6210\u540e\u5c06\u81ea\u52a8\u52a0\u5165\u8054\u76df\u300c" + configuredName + "\u300d\uff0c\u4e0d\u4f1a\u521b\u5efa\u65b0\u8054\u76df");
            return result;
        }
        result.put("state", followUp.state().name());
        result.put("allianceId", followUp.allianceId());
        result.put("allianceName", followUp.allianceName());
        result.put("message", followUp.message());
        return result;
    }

    private void publishAllianceJoinFailure(long accountRoleId, String allianceName, String reason) {
        if (this.notificationPublisher == null || !this.allianceFailureNotifiedRoles.add(accountRoleId)) {
            return;
        }
        try {
            LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("taskLabel", "\u8d77\u53f7");
            data.put("status", "ACTION_REQUIRED");
            data.put("detail", "\u5347\u7ea7\u5df2\u5b8c\u6210\uff0c\u4f46\u81ea\u52a8\u52a0\u5165\u8054\u76df\u5931\u8d25");
            data.put("message", "\u8d77\u53f7\u5347\u7ea7\u5df2\u5b8c\u6210\uff0c\u4f46\u8054\u76df\u300c" + allianceName + "\u300d\u5f53\u524d\u672a\u5f00\u653e\u6216\u65e0\u6cd5\u52a0\u5165\uff1a" + reason + "\u3002\u7cfb\u7edf\u4e0d\u4f1a\u81ea\u52a8\u521b\u5efa\u8054\u76df\uff0c\u8bf7\u68c0\u67e5\u8054\u76df\u72b6\u6001\u540e\u5728\u8d77\u53f7\u9875\u624b\u52a8\u91cd\u8bd5\u3002");
            this.notificationPublisher.publish(NotificationEventType.TASK_BOOTSTRAP_RESULT, accountRoleId, "bootstrap:alliance-failed", data);
        }
        catch (RuntimeException runtimeException) {
            // empty catch block
        }
    }

    private static String failureText(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public BootstrapConfig config(long accountRoleId) {
        long now = System.currentTimeMillis();
        CachedConfig cached = this.configCache.get(accountRoleId);
        if (cached != null && now - cached.at() < 5000L) {
            return cached.config();
        }
        BootstrapConfig loaded = this.configStore.find(GameModule.BOOTSTRAP, accountRoleId, "settings").map(BootstrapConfig::fromMap).orElseGet(BootstrapConfig::defaults);
        this.configCache.put(accountRoleId, new CachedConfig(now, loaded));
        return loaded;
    }

    private BootstrapConfig requireEnabled(long accountRoleId) {
        BootstrapConfig config = this.config(accountRoleId);
        if (!config.enabled()) {
            throw new IllegalStateException("\u8d77\u53f7\u6a21\u5757\u4e3b\u5f00\u5173\u672a\u5f00\u542f");
        }
        return config;
    }

    private GameAccountSessionService.AccountSession requireOnline(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            throw new IllegalStateException("\u8d77\u53f7\u8d26\u53f7\u672a\u4e0a\u7ebf");
        }
        return this.sessions.onlineSession(accountRoleId);
    }

    private Map<Long, Long> currentLevels(BootstrapBuildingState state) {
        LinkedHashMap<Long, Long> levels = new LinkedHashMap<Long, Long>();
        for (DailyTownDevelopmentCatalog.BuildingDefinition definition : this.catalog.buildings()) {
            levels.put(definition.id(), state.level(definition.id()));
        }
        Iterator<Object> iterator = SPECIAL_BUILDING_IDS.iterator();
        while (iterator.hasNext()) {
            long specialId = (Long)iterator.next();
            levels.put(specialId, state.level(specialId));
        }
        return levels;
    }

    private Map<Long, Long> targetLevels(BootstrapConfig config, BootstrapBuildingState state) {
        LinkedHashMap<Long, Long> targets = new LinkedHashMap<Long, Long>();
        for (BootstrapStorylineCatalog.Step step : BootstrapStorylineCatalog.steps()) {
            if (step.kind() != BootstrapStorylineCatalog.StepKind.BUILDING) continue;
            targets.merge(step.buildingId(), step.targetLevel(), Math::max);
        }
        Iterator<Object> iterator = BootstrapService.chapterObjectiveQuestIds(config, state).iterator();
        while (iterator.hasNext()) {
            long buildingLevel;
            long questId = (Long)iterator.next();
            BootstrapChapterQuestCatalog.Objective objective = BootstrapChapterQuestCatalog.objective(questId).orElse(null);
            if (objective == null) continue;
            if (objective.kind() == BootstrapChapterQuestCatalog.ObjectiveKind.BUILDING) {
                if (objective.buildingId() == 301L) continue;
                targets.merge(objective.buildingId(), objective.targetLevel(), Math::max);
                continue;
            }
            if (objective.kind() != BootstrapChapterQuestCatalog.ObjectiveKind.FURNITURE || (buildingLevel = this.chapterFurnitureRequiredBuildingLevel(objective)) <= 0L) continue;
            targets.merge(objective.buildingId(), buildingLevel, Math::max);
        }
        targets.put(301L, Long.valueOf(config.targetFurnaceLevel()));
        return targets;
    }

    private static List<Long> chapterObjectiveQuestIds(BootstrapConfig config, BootstrapBuildingState state) {
        if (BootstrapService.storylineBuildingsPending(state)) {
            return List.of();
        }
        long chapter = BootstrapChapterQuestCatalog.chapterForFurnaceLevel(config.targetFurnaceLevel());
        if (chapter <= 0L) {
            return List.of();
        }
        return BootstrapChapterQuestCatalog.objectivesUpToChapter(chapter).stream().map(BootstrapChapterQuestCatalog.Objective::questId).toList();
    }

    private long chapterFurnitureRequiredBuildingLevel(BootstrapChapterQuestCatalog.Objective objective) {
        long maxLevel = this.catalog.maxLevel(objective.buildingId());
        for (long level = 1L; level <= maxLevel; ++level) {
            LinkedHashMap<Long, Long> caps = new LinkedHashMap<Long, Long>();
            for (DailyTownDevelopmentCatalog.Cost requirement : this.catalog.furnitureRequirement(objective.buildingId(), level)) {
                caps.putIfAbsent(requirement.id(), requirement.amount());
            }
            int available = 0;
            boolean levelsEnough = true;
            for (long furnitureId : objective.furnitureIds()) {
                Long cap = (Long)caps.get(furnitureId);
                if (cap == null) continue;
                if (cap < objective.targetLevel()) {
                    levelsEnough = false;
                    break;
                }
                ++available;
            }
            if (!levelsEnough || (long)available < objective.count()) continue;
            return level;
        }
        return 0L;
    }

    private Optional<BuildingUpgradePlanner.Action> chapterFurnitureAction(BootstrapConfig config, BootstrapBuildingState state) {
        for (long questId : BootstrapService.chapterObjectiveQuestIds(config, state)) {
            long requiredLevel;
            BootstrapChapterQuestCatalog.Objective objective = BootstrapChapterQuestCatalog.objective(questId).orElse(null);
            if (objective == null || objective.kind() != BootstrapChapterQuestCatalog.ObjectiveKind.FURNITURE || BootstrapService.chapterFurnitureSatisfied(objective, state) || (requiredLevel = this.chapterFurnitureRequiredBuildingLevel(objective)) > 0L && state.level(objective.buildingId()) < requiredLevel) continue;
            for (long furnitureId : objective.furnitureIds()) {
                long current = state.furnitureLevels().getOrDefault(objective.buildingId(), Map.of()).getOrDefault(furnitureId, 0L);
                String buildingName = this.catalog.buildingName(objective.buildingId());
                if (current <= 0L) {
                    return Optional.of(new BuildingUpgradePlanner.Action(BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE, objective.buildingId(), buildingName, furnitureId, 0L, 1L, this.catalog.furnitureCosts(furnitureId, 1L)));
                }
                if (current >= objective.targetLevel()) continue;
                return Optional.of(new BuildingUpgradePlanner.Action(BuildingUpgradePlanner.ActionKind.UPGRADE_FURNITURE, objective.buildingId(), buildingName, furnitureId, current, objective.targetLevel(), this.catalog.furnitureUpgradeCosts(furnitureId, current, objective.targetLevel())));
            }
        }
        return Optional.empty();
    }

    private static boolean chapterFurnitureSatisfied(BootstrapChapterQuestCatalog.Objective objective, BootstrapBuildingState state) {
        long satisfied = state.furnitureCountAtLeast(objective.buildingId(), objective.furnitureType(), objective.targetLevel());
        return satisfied >= objective.count();
    }

    private boolean chapterObjectiveSatisfied(long accountRoleId, BootstrapChapterQuestCatalog.Objective objective, BootstrapBuildingState state) {
        return switch (objective.kind()) {
            default -> throw new MatchException(null, null);
            case BootstrapChapterQuestCatalog.ObjectiveKind.BUILDING -> {
                if (state.level(objective.buildingId()) >= objective.targetLevel()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapChapterQuestCatalog.ObjectiveKind.FURNITURE -> BootstrapService.chapterFurnitureSatisfied(objective, state);
            case BootstrapChapterQuestCatalog.ObjectiveKind.ADVENTURE -> {
                if (this.tutorial.adventurePassed(accountRoleId) >= objective.targetLevel()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapChapterQuestCatalog.ObjectiveKind.ASSIGN -> this.survivorStates.survivorState(accountRoleId).assignedCountToBuilding(objective.buildingId()) >= objective.count();
        };
    }

    private Optional<Map<String, Object>> advanceChapterAdventure(long accountRoleId, BootstrapConfig config, BootstrapBuildingState state) {
        for (long questId : BootstrapService.chapterObjectiveQuestIds(config, state)) {
            Optional<Map<String, Object>> advanced;
            BootstrapChapterQuestCatalog.Objective objective = BootstrapChapterQuestCatalog.objective(questId).orElse(null);
            if (objective == null || objective.kind() != BootstrapChapterQuestCatalog.ObjectiveKind.ADVENTURE || this.chapterObjectiveSatisfied(accountRoleId, objective, state) || !(advanced = this.adventures.advance(accountRoleId, objective.targetLevel())).isPresent()) continue;
            return advanced;
        }
        return Optional.empty();
    }

    private static boolean storylineBuildingsPending(BootstrapBuildingState state) {
        for (BootstrapStorylineCatalog.Step step : BootstrapStorylineCatalog.steps()) {
            if (step.kind() != BootstrapStorylineCatalog.StepKind.BUILDING || state.level(step.buildingId()) >= step.targetLevel()) continue;
            return true;
        }
        return false;
    }

    private Map<String, Object> chapterObjectiveView(long accountRoleId, BootstrapConfig config, BootstrapBuildingState state) {
        long chapter = BootstrapChapterQuestCatalog.chapterForFurnaceLevel(config.targetFurnaceLevel());
        ArrayList pending = new ArrayList();
        int total = 0;
        int done = 0;
        if (chapter > 0L) {
            for (BootstrapChapterQuestCatalog.Objective objective : BootstrapChapterQuestCatalog.objectivesUpToChapter(chapter)) {
                ++total;
                if (this.chapterObjectiveSatisfied(accountRoleId, objective, state)) {
                    ++done;
                    continue;
                }
                LinkedHashMap<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("questId", objective.questId());
                entry.put("chapterId", objective.chapterId());
                entry.put("kind", objective.kind().name());
                entry.put("buildingId", objective.buildingId());
                entry.put("buildingName", this.catalog.buildingName(objective.buildingId()));
                entry.put("targetLevel", objective.targetLevel());
                entry.put("label", objective.label());
                pending.add(entry);
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targetChapter", chapter);
        result.put("total", total);
        result.put("done", done);
        result.put("pending", List.copyOf(pending));
        return result;
    }

    private BuildingUpgradePlanner.Plan planNext(BootstrapConfig config, BootstrapBuildingState state, int maxActions) {
        return this.planner.plan(new BuildingUpgradePlanner.Request(this.currentLevels(state), state.furnitureLevels(), this.targetLevels(config, state), state.busyBuildings(), BuildingUpgradePlanner.ResourceAvailability.ALWAYS, maxActions));
    }

    private Optional<BootstrapStorylineCatalog.Step> pendingStorylineStep(BootstrapBuildingState state, BootstrapSurvivorState survivors) {
        return BootstrapStorylineCatalog.steps().stream().filter(step -> !this.storylineStepCompleted(state, survivors, (BootstrapStorylineCatalog.Step)step)).filter(step -> step.kind() != BootstrapStorylineCatalog.StepKind.SURVIVOR || survivors.initialized()).filter(step -> this.furnitureUnlocked(state, (BootstrapStorylineCatalog.Step)step)).findFirst();
    }

    private boolean furnitureUnlocked(BootstrapBuildingState state, BootstrapStorylineCatalog.Step step) {
        if (step.kind() != BootstrapStorylineCatalog.StepKind.FURNITURE) {
            return true;
        }
        long requiredLevel = this.catalog.furnitureRequiredBuildingLevel(step.buildingId(), step.furnitureId());
        return requiredLevel <= 0L || state.level(step.buildingId()) >= requiredLevel;
    }

    private boolean storylineStepCompleted(BootstrapBuildingState state, BootstrapSurvivorState survivors, BootstrapStorylineCatalog.Step step) {
        return switch (step.kind()) {
            default -> throw new MatchException(null, null);
            case BootstrapStorylineCatalog.StepKind.BUILDING -> {
                if (state.level(step.buildingId()) >= step.targetLevel()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapStorylineCatalog.StepKind.FURNITURE -> {
                if (step.buildingId() == 308L && (step.furnitureId() == 101L || step.furnitureId() == 102L)) {
                    if (Math.max(BootstrapService.furnitureLevel(state, 308L, 101L), BootstrapService.furnitureLevel(state, 308L, 102L)) >= step.targetLevel()) {
                        yield true;
                    }
                    yield false;
                }
                if (BootstrapService.furnitureLevel(state, step.buildingId(), step.furnitureId()) >= step.targetLevel()) {
                    yield true;
                }
                yield false;
            }
            case BootstrapStorylineCatalog.StepKind.SURVIVOR -> {
                long instanceId = state.furnitureInstanceId(step.buildingId(), step.furnitureId());
                if (instanceId > 0L && survivors.assignedTo(instanceId)) {
                    yield true;
                }
                yield false;
            }
        };
    }

    private Map<String, Object> executeStorylineStep(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapBuildingState state, BootstrapStorylineCatalog.Step step) {
        if (step.kind() == BootstrapStorylineCatalog.StepKind.FURNITURE) {
            return this.executeStorylineFurniture(accountRoleId, session, state, step);
        }
        return this.executeStorylineSurvivor(accountRoleId, session, state, this.survivorStates.survivorState(accountRoleId), step);
    }

    private Map<String, Object> executeStorylineFurniture(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapBuildingState state, BootstrapStorylineCatalog.Step step) {
        String buildingName = this.catalog.buildingName(step.buildingId());
        if (state.level(step.buildingId()) <= 0L) {
            return this.actionResult(accountRoleId, "WAITING", "\u7b49\u5f85" + buildingName + " \u5efa\u9020\u5b8c\u6210\u540e\u7ee7\u7eed");
        }
        long requiredLevel = this.catalog.furnitureRequiredBuildingLevel(step.buildingId(), step.furnitureId());
        if (requiredLevel > state.level(step.buildingId())) {
            return this.actionResult(accountRoleId, "WAITING", buildingName + " \u9700\u8981\u5347\u5230 " + requiredLevel + " \u7ea7\u624d\u89e3\u9501\u8be5\u5bb6\u5177\uff0c\u4ea4\u7ed9\u5efa\u7b51\u5347\u7ea7\u8ba1\u5212\u63a8\u8fdb");
        }
        long current = BootstrapService.furnitureLevel(state, step.buildingId(), step.furnitureId());
        BuildingUpgradePlanner.Action action = this.storylineFurnitureAction(buildingName, step, current);
        BuildingUpgradePlanner.Plan plan = new BuildingUpgradePlanner.Plan(List.of(action), "", true);
        Map<String, Object> result = this.executeFirstAction(accountRoleId, session, state, plan);
        result.put("storylineStep", this.storylineStepView(step));
        return result;
    }

    private BuildingUpgradePlanner.Action storylineFurnitureAction(String buildingName, BootstrapStorylineCatalog.Step step, long current) {
        if (current <= 0L) {
            return new BuildingUpgradePlanner.Action(BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE, step.buildingId(), buildingName, step.furnitureId(), 0L, step.targetLevel(), this.catalog.furnitureCosts(step.furnitureId(), 1L));
        }
        return new BuildingUpgradePlanner.Action(BuildingUpgradePlanner.ActionKind.UPGRADE_FURNITURE, step.buildingId(), buildingName, step.furnitureId(), current, step.targetLevel(), this.catalog.furnitureUpgradeCosts(step.furnitureId(), current, step.targetLevel()));
    }

    private Map<String, Object> executeStorylineSurvivor(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapBuildingState state, BootstrapSurvivorState survivors, BootstrapStorylineCatalog.Step step) {
        if (!survivors.initialized()) {
            return this.actionResult(accountRoleId, "WAITING", "\u7b49\u5f85\u5e78\u5b58\u8005\u6570\u636e\u540c\u6b65\u540e\u59d4\u4efb");
        }
        if (BootstrapService.furnitureLevel(state, step.buildingId(), step.furnitureId()) <= 0L) {
            return this.actionResult(accountRoleId, "WAITING", "\u7b49\u5f85" + step.label() + " \u7684\u5de5\u4f4d\u5bb6\u5177\u5efa\u6210\u540e\u59d4\u4efb");
        }
        long instanceId = state.furnitureInstanceId(step.buildingId(), step.furnitureId());
        if (instanceId <= 0L) {
            return this.actionResult(accountRoleId, "WAITING", "\u7b49\u5f85" + step.label() + " \u7684\u5de5\u4f4d\u5bb6\u5177\u7f16\u53f7\u5c31\u7eea");
        }
        if (survivors.assignedTo(instanceId)) {
            Map<String, Object> done = this.actionResult(accountRoleId, "COMPLETED", step.label() + "\u5df2\u5b8c\u6210");
            done.put("storylineStep", this.storylineStepView(step));
            return done;
        }
        try {
            List<Long> tempIds = survivors.tempSurvivorIds();
            if (!tempIds.isEmpty()) {
                session.request(TEMP_SURVIVOR_CONFIRM, Map.of("sid_list", tempIds));
                survivors.clearTempSurvivors();
                Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u63a5\u6536 " + tempIds.size() + " \u540d\u5e78\u5b58\u8005");
                result.put("storylineStep", this.storylineStepView(step));
                return result;
            }
            Optional<BootstrapSurvivorState.Survivor> idle = survivors.idleSurvivor();
            if (idle.isEmpty()) {
                if (!survivors.guideSelected() && !survivors.guideRequested()) {
                    session.request(GUIDE_COME_SURVIVOR, Map.of("option", 1));
                    survivors.markGuideRequested();
                    Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u8bf7\u6c42\u65b0\u624b\u5e78\u5b58\u8005\u52a0\u5165");
                    result.put("storylineStep", this.storylineStepView(step));
                    return result;
                }
                Map<String, Object> waiting = this.actionResult(accountRoleId, "WAITING", "\u5f53\u524d\u6ca1\u6709\u53ef\u6d3e\u9063\u7684\u5e78\u5b58\u8005\uff0c\u7b49\u5f85\u5e78\u5b58\u8005\u52a0\u5165\u540e\u59d4\u4efb");
                waiting.put("storylineStep", this.storylineStepView(step));
                return waiting;
            }
            long sid = idle.get().sid();
            session.request(SURVIVOR_SEND_OR_FIRED, Map.of("sid", sid, "fid_work", instanceId, "type", 1L));
            survivors.markAssigned(sid, instanceId);
            log.info("[\u8d77\u53f7] \u8d26\u53f7 {} \u5df2\u59d4\u4efb\u5e78\u5b58\u8005 {} \u5230 {}", new Object[]{accountRoleId, sid, step.label()});
            Map<String, Object> result = this.actionResult(accountRoleId, "STARTED", "\u5df2\u59d4\u4efb\u5e78\u5b58\u8005 " + sid + " \u5230 " + step.label());
            result.put("storylineStep", this.storylineStepView(step));
            return result;
        }
        catch (GameProtocolException exception) {
            Map<String, Object> result = this.actionResult(accountRoleId, "WAITING", step.label() + "\u88ab\u670d\u52a1\u7aef\u62d2\u7edd\uff0c\u7b49\u5f85\u72b6\u6001\u540c\u6b65\u540e\u91cd\u8bd5");
            result.put("storylineStep", this.storylineStepView(step));
            result.put("errorCode", exception.code());
            return result;
        }
    }

    private static long furnitureLevel(BootstrapBuildingState state, long buildingId, long furnitureId) {
        return state.furnitureLevels().getOrDefault(buildingId, Map.of()).getOrDefault(furnitureId, 0L);
    }

    private List<Map<String, Object>> storylineView(BootstrapBuildingState state, BootstrapSurvivorState survivors) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (BootstrapStorylineCatalog.Step step : BootstrapStorylineCatalog.steps()) {
            LinkedHashMap<String, Object> view = new LinkedHashMap<String, Object>(this.storylineStepView(step));
            view.put("completed", this.storylineStepCompleted(state, survivors, step));
            result.add(view);
        }
        return result;
    }

    private Map<String, Object> storylineStepView(BootstrapStorylineCatalog.Step step) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("kind", step.kind().name());
        result.put("buildingId", step.buildingId());
        result.put("buildingName", this.catalog.buildingName(step.buildingId()));
        result.put("furnitureId", step.furnitureId());
        result.put("targetLevel", step.targetLevel());
        result.put("label", step.label());
        result.put("source", step.source());
        return result;
    }

    private Map<String, Object> survivorView(BootstrapSurvivorState survivors) {
        List<BootstrapSurvivorState.Survivor> list = survivors.survivors();
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("initialized", survivors.initialized());
        result.put("guideSelected", survivors.guideSelected());
        result.put("total", list.size());
        result.put("idle", list.stream().filter(BootstrapSurvivorState.Survivor::idle).count());
        result.put("working", list.stream().filter(survivor -> survivor.fidWork() > 0L).count());
        result.put("sick", list.stream().filter(BootstrapSurvivorState.Survivor::sick).count());
        result.put("tempPending", survivors.tempSurvivorIds().size());
        return result;
    }

    private static String protocol(BuildingUpgradePlanner.Action action) {
        return switch (action.kind()) {
            default -> throw new MatchException(null, null);
            case BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE -> CREATE_FURNITURE;
            case BuildingUpgradePlanner.ActionKind.UPGRADE_FURNITURE -> UPGRADE_FURNITURE;
            case BuildingUpgradePlanner.ActionKind.UPGRADE_BUILDING -> UPGRADE_BUILDING;
        };
    }

    private static Map<String, Object> payload(BuildingUpgradePlanner.Action action, long queueId) {
        return switch (action.kind()) {
            default -> throw new MatchException(null, null);
            case BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE -> Map.of("bid", action.buildingId(), "conf_fid", action.furnitureId());
            case BuildingUpgradePlanner.ActionKind.UPGRADE_FURNITURE -> Map.of("bid", action.buildingId(), "conf_fid", action.furnitureId(), "add_lv", Math.max(1L, action.toLevel() - action.fromLevel()));
            case BuildingUpgradePlanner.ActionKind.UPGRADE_BUILDING -> Map.of("bid", action.buildingId(), "use_diamond", false, "qid", queueId);
        };
    }

    private static boolean isResourceEnough(Map<String, Object> result) {
        return BootstrapProtocolSupport.number(result.get("errorCode")) != 51L;
    }

    private Optional<Map<String, Object>> upgradeWithDiamond(long accountRoleId, BuildingUpgradePlanner.Action action) {
        GameAccountSessionService.AccountSession session;
        if (action.kind() != BuildingUpgradePlanner.ActionKind.UPGRADE_BUILDING) {
            return Optional.empty();
        }
        try {
            session = this.sessions.onlineSession(accountRoleId);
        }
        catch (RuntimeException exception) {
            return Optional.empty();
        }
        try {
            session.request(UPGRADE_BUILDING, Map.of("bid", action.buildingId(), "use_diamond", true, "qid", 0L));
            this.buildingStates.buildingState(accountRoleId).markBuildingInstantUpgrade(action.buildingId(), action.toLevel());
            log.info("[\u8d77\u53f7] \u8d44\u6e90\u4e0d\u8db3\uff0c\u5df2\u6539\u7528\u94bb\u77f3\u5b8c\u6210 {} \u7684\u5347\u7ea7", (Object)action.label());
            return Optional.of(this.actionResult(accountRoleId, "STARTED", "\u8d44\u6e90\u4e0d\u8db3\uff0c\u5df2\u6539\u7528\u94bb\u77f3\u5b8c\u6210 " + action.label()));
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u6539\u7528\u94bb\u77f3\u5347\u7ea7 {} \u5931\u8d25 message={}", (Object)action.label(), (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> actionResult(long accountRoleId, String status, String message) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> stepView(BuildingUpgradePlanner.Action action) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("buildingId", action.buildingId());
        result.put("buildingName", action.buildingName());
        result.put("furnitureId", action.furnitureId());
        result.put("fromLevel", action.fromLevel());
        result.put("toLevel", action.toLevel());
        result.put("kind", action.kind().name());
        result.put("action", switch (action.kind()) {
            default -> throw new MatchException(null, null);
            case BuildingUpgradePlanner.ActionKind.CREATE_FURNITURE -> "CREATE_FURNITURE";
            case BuildingUpgradePlanner.ActionKind.UPGRADE_FURNITURE -> "UPGRADE_FURNITURE";
            case BuildingUpgradePlanner.ActionKind.UPGRADE_BUILDING -> action.fromLevel() <= 0L ? "BUILD" : "UPGRADE";
        });
        result.put("costs", action.costs().stream().map(cost -> Map.of("id", cost.id(), "amount", cost.amount())).toList());
        return result;
    }

    private List<Map<String, Object>> buildingView(Map<Long, Long> levels, BootstrapBuildingState state) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (DailyTownDevelopmentCatalog.BuildingDefinition definition : this.catalog.buildings()) {
            long level = levels.getOrDefault(definition.id(), 0L);
            if (level <= 0L && !definition.name().contains("\u7194\u7089")) continue;
            result.add(this.buildingEntry(definition.id(), definition.name(), level, state));
        }
        Iterator<Object> iterator = SPECIAL_BUILDING_IDS.iterator();
        while (iterator.hasNext()) {
            SpecialBuildingCatalog.Definition special;
            long specialId = (Long)iterator.next();
            long level = levels.getOrDefault(specialId, 0L);
            if (level <= 0L || (special = SpecialBuildingCatalog.find(specialId)) == null) continue;
            result.add(this.buildingEntry(special.id(), special.name(), level, state));
        }
        return result;
    }

    private Map<String, Object> buildingEntry(long id, String name, long level, BootstrapBuildingState state) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("name", name);
        result.put("level", level);
        result.put("busy", state.busy(id));
        return result;
    }

    private Map<String, Object> resourceView(long accountRoleId) {
        BehemothAccountState inventory = this.buildingStates.state(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("wood", DailyBackpackResourceSupport.resourceCount(inventory, 103L));
        result.put("coal", DailyBackpackResourceSupport.resourceCount(inventory, 104L));
        result.put("iron", DailyBackpackResourceSupport.resourceCount(inventory, 105L));
        result.put("diamond", inventory.resourceCount(101L));
        return result;
    }

    private Map<String, Object> allianceView(Map<String, Object> alliance) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", ProtocolValueSupport.number(alliance.get("aid")));
        result.put("name", BootstrapProtocolSupport.text(alliance.get("name")));
        result.put("abbr", BootstrapProtocolSupport.text(alliance.get("abbr")));
        result.put("publicRecruit", BootstrapService.booleanValue(alliance.get("public")));
        result.put("memberCount", ProtocolValueSupport.number(alliance.get("count")));
        result.put("memberMax", ProtocolValueSupport.number(alliance.get("member_max")));
        result.put("level", ProtocolValueSupport.number(alliance.get("lv")));
        Map<String, Object> condition = BootstrapProtocolSupport.map(alliance.get("condition"));
        result.put("requiredLevel", ProtocolValueSupport.number(condition.get("level")));
        result.put("requiredPower", ProtocolValueSupport.number(condition.get("power")));
        return result;
    }

    private String runState(long accountRoleId, BootstrapConfig config, BootstrapBuildingState state) {
        if (!config.enabled()) {
            return "DISABLED";
        }
        if (this.resourceEndedRoles.contains(accountRoleId)) {
            return "COMPLETED";
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return "WAITING_SYNC";
        }
        if (!this.levelingGoalSatisfied(accountRoleId, config, state)) {
            return state.level(301L) >= (long)config.targetFurnaceLevel() ? "CHAPTER_IN_PROGRESS" : "READY";
        }
        if (BootstrapService.allianceNotRequired(config)) {
            return "COMPLETED";
        }
        AllianceFollowUp followUp = this.allianceFollowUps.get(accountRoleId);
        if (followUp == null) {
            return "JOINING_ALLIANCE";
        }
        return followUp.state() == AllianceFollowUpState.FAILED ? "ALLIANCE_BLOCKED" : "COMPLETED";
    }

    private String runMessage(long accountRoleId, BootstrapConfig config, BootstrapBuildingState state) {
        if (!config.enabled()) {
            return "\u4e3b\u5f00\u5173\u672a\u5f00\u542f\uff0c\u8d77\u53f7\u6d41\u7a0b\u4e0d\u4f1a\u6267\u884c";
        }
        if (this.resourceEndedRoles.contains(accountRoleId)) {
            return "\u5927\u7194\u7089\u5df2\u5230 9 \u7ea7\uff0c\u8d44\u6e90\u5df2\u5c3d\u529b\u8865\u5145\u4ecd\u4e0d\u8db3\uff0c\u8d77\u53f7\u5df2\u7ed3\u675f\uff08\u4fdd\u5b58\u914d\u7f6e\u540e\u53ef\u91cd\u65b0\u5f00\u59cb\uff09";
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return "\u7b49\u5f85\u5efa\u7b51\u4e0e\u961f\u5217\u5168\u91cf\u72b6\u6001\u540c\u6b65";
        }
        if (this.levelingGoalSatisfied(accountRoleId, config, state)) {
            if (BootstrapService.allianceNotRequired(config)) {
                return "\u5927\u7194\u7089\u5df2\u5230\u8fbe\u76ee\u6807\u7b49\u7ea7\uff0c\u7ae0\u8282\u4efb\u52a1\u76ee\u6807\u5df2\u5168\u90e8\u8fbe\u6210";
            }
            AllianceFollowUp followUp = this.allianceFollowUps.get(accountRoleId);
            if (followUp == null) {
                return "\u5347\u7ea7\u4e0e\u7ae0\u8282\u76ee\u6807\u5df2\u5b8c\u6210\uff0c\u6b63\u5728\u81ea\u52a8\u52a0\u5165\u8054\u76df\u300c" + config.allianceName().trim() + "\u300d";
            }
            return followUp.message();
        }
        if (state.level(301L) >= (long)config.targetFurnaceLevel()) {
            return "\u5927\u7194\u7089\u5df2\u5230\u8fbe\u76ee\u6807\u7b49\u7ea7\uff0c\u6b63\u5728\u8865\u5b8c\u7ae0\u8282\u4efb\u52a1\u76ee\u6807";
        }
        return "\u53ef\u4ee5\u6267\u884c\u4e0b\u4e00\u6b65";
    }

    private String completionMessage(long accountRoleId, BootstrapConfig config) {
        if (this.resourceEndedRoles.contains(accountRoleId)) {
            return "\u5927\u7194\u7089\u5df2\u5230 9 \u7ea7\uff0c\u8d44\u6e90\u5df2\u5c3d\u529b\u8865\u5145\u4ecd\u4e0d\u8db3\uff0c\u8d77\u53f7\u5df2\u7ed3\u675f";
        }
        String base = "\u5927\u7194\u7089\u5df2\u5230\u8fbe\u76ee\u6807\u7b49\u7ea7 " + config.targetFurnaceLevel() + "\uff0c\u7ae0\u8282\u4efb\u52a1\u76ee\u6807\u5df2\u5168\u90e8\u8fbe\u6210";
        if (BootstrapService.allianceNotRequired(config)) {
            return base;
        }
        AllianceFollowUp followUp = this.allianceFollowUps.get(accountRoleId);
        if (followUp == null) {
            return base;
        }
        return switch (followUp.state().ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> base + "\uff0c\u5df2\u52a0\u5165\u8054\u76df\u300c" + followUp.allianceName() + "\u300d";
            case 1 -> base + "\uff0c\u5df2\u63d0\u4ea4\u8054\u76df\u300c" + followUp.allianceName() + "\u300d\u7533\u8bf7\uff0c\u7b49\u5f85\u5ba1\u6279";
            case 2 -> base + "\uff0c\u4f46\u81ea\u52a8\u5165\u76df\u672a\u5b8c\u6210\uff1a" + followUp.message();
        };
    }

    private long responseCode(GameMessage response) {
        return ProtocolValueSupport.number(BootstrapProtocolSupport.content(response).get("code"));
    }

    private static long furnitureInstanceId(GameMessage response) {
        Map<String, Object> content = BootstrapProtocolSupport.content(response);
        Map<String, Object> furniture = BootstrapProtocolSupport.map(content.get("furniture"));
        if (furniture.isEmpty()) {
            return 0L;
        }
        long instanceId = BootstrapProtocolSupport.number(furniture.get("fid"));
        if (instanceId > 0L) {
            return instanceId;
        }
        return BootstrapProtocolSupport.number(BootstrapProtocolSupport.map(furniture.get("base")).get("fid"));
    }

    private static boolean booleanValue(Object raw) {
        if (raw instanceof Boolean) {
            Boolean value = (Boolean)raw;
            return value;
        }
        String text = BootstrapProtocolSupport.text(raw);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private record BootstrapDueRole(long accountRoleId, long dueAtMillis) implements Comparable<BootstrapDueRole>
    {
        @Override
        public int compareTo(BootstrapDueRole other) {
            return Long.compare(this.dueAtMillis, other.dueAtMillis);
        }
    }

    private record CachedConfig(long at, BootstrapConfig config) {
    }

    private static final class AllianceJoinTerminalException
    extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private AllianceJoinTerminalException(String message) {
            super(message);
        }

        private AllianceJoinTerminalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static enum AllianceFollowUpState {
        JOINED,
        APPLIED,
        FAILED;

    }

    private record AllianceFollowUp(AllianceFollowUpState state, long allianceId, String allianceName, String message) {
    }
}

