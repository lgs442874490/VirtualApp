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
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProgressService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapSurvivorState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapWorkRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapWorkState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapWorkService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapWorkService.class);
    private static final int FURNITURE_FID_WRONG = 156;
    private static final int FURNITURE_CANT_START_WORK = 158;
    private static final long WORK_START_DENIED_COOLDOWN_MILLIS = 60000L;
    private final Map<Long, Long> workStartDeniedAt = new ConcurrentHashMap<Long, Long>();
    private final Set<Long> staleWorkSids = ConcurrentHashMap.newKeySet();
    private static final String WORK_START = "req_survivor_work_start";
    private static final String WORK_PAUSE = "req_survivor_work_pause";
    private static final String HUNTING_TOOL = "req_hunting_tool_obtain";
    private static final String SCHEDULE_INFO = "req_schedule_info";
    private static final long HUNTER_HUT_ID = 305L;
    private static final long HUNTING_STATION_TYPE = 18L;
    private static final String CLOCK_SERVER_SECOND = "serverEpochSecond";
    private static final String CLOCK_READY = "ready";
    private final BootstrapWorkRegistry workStates;
    private final BootstrapBuildingStateRegistry buildingStates;
    private final BootstrapSurvivorRegistry survivorStates;
    private final BootstrapProgressService progress;

    BootstrapWorkService(BootstrapWorkRegistry workStates, BootstrapBuildingStateRegistry buildingStates, BootstrapSurvivorRegistry survivorStates, BootstrapProgressService progress) {
        this.workStates = workStates;
        this.buildingStates = buildingStates;
        this.survivorStates = survivorStates;
        this.progress = progress;
    }

    Optional<Map<String, Object>> advance(long accountRoleId, GameAccountSessionService.AccountSession session) {
        Optional<String> started;
        BootstrapWorkState work = this.workStates.workState(accountRoleId);
        BootstrapBuildingState buildings = this.buildingStates.buildingState(accountRoleId);
        BootstrapSurvivorState survivors = this.survivorStates.survivorState(accountRoleId);
        if (!survivors.initialized() || !buildings.buildingsReady()) {
            return Optional.empty();
        }
        Optional<String> schedule = this.querySchedule(session, work);
        if (schedule.isPresent()) {
            return Optional.of(BootstrapWorkService.action(accountRoleId, schedule.get()));
        }
        Optional<String> paused = this.pauseOnScheduleChange(session, work, survivors);
        if (paused.isPresent()) {
            return Optional.of(BootstrapWorkService.action(accountRoleId, paused.get()));
        }
        if (this.workStartAllowed(accountRoleId) && (started = this.startPendingSurvivors(accountRoleId, session, work, buildings, survivors)).isPresent()) {
            return Optional.of(BootstrapWorkService.action(accountRoleId, started.get()));
        }
        Optional<String> hunted = this.requestHuntingTool(session, work, survivors, buildings);
        return hunted.map(value -> BootstrapWorkService.action(accountRoleId, value));
    }

    public Map<String, Object> view(long accountRoleId) {
        BootstrapWorkState work = this.workStates.workState(accountRoleId);
        BootstrapSurvivorState survivors = this.survivorStates.survivorState(accountRoleId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("scheduleReady", work.scheduleReady());
        result.put("scheduleSegmentId", work.scheduleSegmentId());
        result.put("scheduleServerTime", work.scheduleServerTime());
        result.put("pendingWorkStart", List.copyOf(survivors.pendingWorkStart()));
        result.put("working", survivors.survivors().stream().filter(BootstrapSurvivorState.Survivor::working).map(BootstrapSurvivorState.Survivor::sid).toList());
        result.put("kitchenFoodCount", work.kitchenFoodCount());
        result.put("productionFoodId", work.productionFoodId());
        return result;
    }

    private Optional<String> querySchedule(GameAccountSessionService.AccountSession session, BootstrapWorkState work) {
        if (work.scheduleReady() || work.scheduleQuerySent()) {
            return Optional.empty();
        }
        work.markScheduleQuerySent();
        try {
            GameMessage response = session.request(SCHEDULE_INFO, Map.of());
            work.applyScheduleResponse(BootstrapProtocolSupport.content(response));
            log.info("[\u8d77\u53f7] \u5df2\u67e5\u8be2\u5f53\u524d\u4f5c\u606f\u8868 segment={}", (Object)work.scheduleSegmentId());
            return Optional.of("\u67e5\u8be2\u5f53\u524d\u4f5c\u606f\u8868");
        }
        catch (GameProtocolException exception) {
            log.info("[\u8d77\u53f7] \u67e5\u8be2\u4f5c\u606f\u8868\u88ab\u62d2\u7edd code={}", (Object)exception.code());
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u67e5\u8be2\u4f5c\u606f\u8868\u5931\u8d25 message={}", (Object)exception.getMessage());
            return Optional.empty();
        }
    }

    /*
     * WARNING - void declaration
     */
    private Optional<String> pauseOnScheduleChange(GameAccountSessionService.AccountSession session, BootstrapWorkState work, BootstrapSurvivorState survivors) {
        if (!work.scheduleChanged()) {
            return Optional.empty();
        }
        ArrayList<Long> working = new ArrayList<Long>();
        for (BootstrapSurvivorState.Survivor survivor : survivors.survivors()) {
            if (!survivor.working()) continue;
            working.add(survivor.sid());
        }
        if (working.isEmpty()) {
            work.clearScheduleChanged();
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sid_list", working);
        payload.put("time", BootstrapWorkService.serverSecond(session));
        try {
            void var6_9;
            session.request(WORK_PAUSE, payload);
            Iterator iterator = working.iterator();
            while (iterator.hasNext()) {
                long sid = (Long)iterator.next();
                work.markWorkPaused(sid);
            }
            work.clearScheduleChanged();
            boolean bl = false;
            for (BootstrapSurvivorState.Survivor survivor2 : survivors.survivors()) {
                if (survivor2.fidWork() <= 0L) continue;
                survivors.markAssigned(survivor2.sid(), survivor2.fidWork());
                ++var6_9;
            }
            log.info("[\u8d77\u53f7] \u4f5c\u606f\u5207\u6bb5 {}\uff0c\u5df2\u6682\u505c {} \u540d\u3001\u5e76\u91cd\u65b0\u5b89\u6392 {} \u540d\u5e78\u5b58\u8005\u4e0a\u73ed", new Object[]{work.scheduleSegmentId(), working.size(), (int)var6_9});
            return Optional.of("\u4f5c\u606f\u5207\u6bb5\uff1a\u6682\u505c " + working.size() + " \u540d\u5e76\u91cd\u65b0\u5b89\u6392 " + (int)var6_9 + " \u540d\u4e0a\u73ed");
        }
        catch (GameProtocolException gameProtocolException) {
            work.clearScheduleChanged();
            log.info("[\u8d77\u53f7] \u6682\u505c\u5e78\u5b58\u8005\u5de5\u4f5c\u88ab\u62d2\u7edd code={}", (Object)gameProtocolException.code());
            return Optional.empty();
        }
        catch (RuntimeException runtimeException) {
            work.clearScheduleChanged();
            log.info("[\u8d77\u53f7] \u6682\u505c\u5e78\u5b58\u8005\u5de5\u4f5c\u5931\u8d25 message={}", (Object)runtimeException.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> startPendingSurvivors(long accountRoleId, GameAccountSessionService.AccountSession session, BootstrapWorkState work, BootstrapBuildingState buildings, BootstrapSurvivorState survivors) {
        for (long sid : survivors.pendingWorkStart()) {
            if (!work.needsWorkStart(sid)) {
                survivors.markWorkStarted(sid);
                continue;
            }
            if (this.staleWorkSids.contains(sid)) {
                survivors.markWorkStarted(sid);
                continue;
            }
            if (this.isStationBuildingBusy(buildings, survivors, sid)) {
                survivors.markWorkStarted(sid);
                continue;
            }
            try {
                session.request(WORK_START, Map.of("sid", sid));
                work.markWorkStarted(sid);
                survivors.markWorkStarted(sid);
                log.info("[\u8d77\u53f7] \u5df2\u901a\u77e5\u5e78\u5b58\u8005 {} \u5f00\u59cb\u5de5\u4f5c", (Object)sid);
                return Optional.of("\u901a\u77e5\u5e78\u5b58\u8005 " + sid + " \u5f00\u59cb\u5de5\u4f5c");
            }
            catch (GameProtocolException exception) {
                survivors.markWorkStarted(sid);
                work.markWorkStarted(sid);
                if (exception.code() == 156) {
                    this.staleWorkSids.add(sid);
                } else if (exception.code() == 158) {
                    this.workStartDeniedAt.put(accountRoleId, System.currentTimeMillis());
                    for (long other : survivors.pendingWorkStart()) {
                        survivors.markWorkStarted(other);
                        work.markWorkStarted(other);
                    }
                    log.info("[\u8d77\u53f7] \u5de5\u4f4d\u6682\u4e0d\u53ef\u5f00\u5de5\uff08158\uff09\uff0c\u6682\u505c\u672c\u8f6e\u5f00\u5de5\u5c1d\u8bd5 60 \u79d2");
                    this.progress.note(accountRoleId, "info", "\u5f53\u524d\u662f\u4f11\u606f\u65f6\u6bb5\u6216\u5de5\u4f4d\u6682\u4e0d\u53ef\u7528\uff0c\u751f\u4ea7\u5de5\u4f4d\u7a0d\u540e\u81ea\u52a8\u6062\u590d");
                    break;
                }
                log.info("[\u8d77\u53f7] \u5e78\u5b58\u8005 {} \u5f00\u59cb\u5de5\u4f5c\u88ab\u62d2\u7edd code={}", (Object)sid, (Object)exception.code());
            }
            catch (RuntimeException exception) {
                survivors.markWorkStarted(sid);
                work.markWorkStarted(sid);
                log.info("[\u8d77\u53f7] \u5e78\u5b58\u8005 {} \u5f00\u59cb\u5de5\u4f5c\u5931\u8d25 message={}", (Object)sid, (Object)exception.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<String> requestHuntingTool(GameAccountSessionService.AccountSession session, BootstrapWorkState work, BootstrapSurvivorState survivors, BootstrapBuildingState buildings) {
        long now = System.currentTimeMillis();
        if (buildings.level(305L) <= 0L) {
            return Optional.empty();
        }
        for (Map.Entry<Long, List<Long>> entry : buildings.furnitureByType(305L, 18L).entrySet()) {
            long stationFid = buildings.furnitureInstanceId(305L, entry.getKey());
            LinkedHashSet<Long> candidates = new LinkedHashSet<Long>((Collection)entry.getValue());
            for (BootstrapSurvivorState.Survivor survivor : survivors.assignedToWork(stationFid)) {
                candidates.add(survivor.sid());
            }
            Iterator<BootstrapSurvivorState.Survivor> iterator = candidates.iterator();
            while (iterator.hasNext()) {
                long sid = (Long)((Object)iterator.next());
                if (!work.canHunt(sid, now) || survivors.survivor(sid).isEmpty()) continue;
                try {
                    GameMessage response = session.request(HUNTING_TOOL, Map.of("sid", sid));
                    long endTime = BootstrapProtocolSupport.number(BootstrapProtocolSupport.content(response).get("work_endtime"));
                    long until = endTime > 0L ? endTime * 1000L : now + 180000L;
                    work.markHunted(sid, until);
                    log.info("[\u8d77\u53f7] \u730e\u4eba {} \u5df2\u9886\u53d6\u72e9\u730e\u751f\u8089\uff0c\u51b7\u5374\u5230 {}", (Object)sid, (Object)until);
                    return Optional.of("\u730e\u4eba " + sid + " \u9886\u53d6\u72e9\u730e\u751f\u8089");
                }
                catch (GameProtocolException exception) {
                    work.markHunted(sid, now + 180000L);
                    log.info("[\u8d77\u53f7] \u730e\u4eba {} \u9886\u53d6\u72e9\u730e\u751f\u8089\u88ab\u62d2\u7edd code={}", (Object)sid, (Object)exception.code());
                }
                catch (RuntimeException exception) {
                    work.markHunted(sid, now + 180000L);
                    log.info("[\u8d77\u53f7] \u730e\u4eba {} \u9886\u53d6\u72e9\u730e\u751f\u8089\u5931\u8d25 message={}", (Object)sid, (Object)exception.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    private static long serverSecond(GameAccountSessionService.AccountSession session) {
        long second;
        Map<String, Object> snapshot = session.gameServerClockSnapshot();
        if (Boolean.TRUE.equals(snapshot.get(CLOCK_READY)) && (second = BootstrapProtocolSupport.number(snapshot.get(CLOCK_SERVER_SECOND))) > 0L) {
            return second;
        }
        return System.currentTimeMillis() / 1000L;
    }

    private boolean workStartAllowed(long accountRoleId) {
        Long deniedAt = this.workStartDeniedAt.get(accountRoleId);
        if (deniedAt == null) {
            return true;
        }
        if (System.currentTimeMillis() - deniedAt < 60000L) {
            return false;
        }
        this.workStartDeniedAt.remove(accountRoleId);
        return true;
    }

    private boolean isStationBuildingBusy(BootstrapBuildingState buildings, BootstrapSurvivorState survivors, long sid) {
        long fidWork = 0L;
        for (BootstrapSurvivorState.Survivor survivor : survivors.survivors()) {
            if (survivor.sid() != sid) continue;
            fidWork = survivor.fidWork();
            break;
        }
        if (fidWork <= 0L) {
            return false;
        }
        long buildingId = fidWork / 1000000L;
        return buildingId > 0L && buildings.busy(buildingId);
    }

    private static Map<String, Object> action(long accountRoleId, String message) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountRoleId", accountRoleId);
        result.put("status", "STARTED");
        result.put("message", "\u8d77\u53f7\u4e0a\u5c97\u5df2\u63a8\u8fdb\uff1a" + message);
        result.put("workAction", message);
        return result;
    }
}

