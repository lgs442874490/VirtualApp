/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PreDestroy
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.CdkeyFailureCatalog;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.GameProtocolException;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.behemoth.service.BehemothAccountState;
import com.wjdr.tool.modules.bootstrap.service.BootstrapBuildingStateRegistry;
import com.wjdr.tool.modules.bootstrap.service.BootstrapCdkeyCatalog;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProgressService;
import com.wjdr.tool.modules.bootstrap.service.BootstrapRewardState;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class BootstrapRewardService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapRewardService.class);
    private static final String GROWTH_AWARD = "req_get_growthquest_award";
    private static final String CHAPTER_QUEST_AWARD = "req_get_chapterquest_award";
    private static final String CHAPTER_AWARD = "req_get_chapter_award";
    private static final String SEVEN_SIGN_AWARD = "req_seven_sign_one_click_award";
    private static final String SIGN_21DAY_AWARD = "req_sign_21day_get_all_reward";
    private static final long QUEST_CLAIM_INTERVAL_MILLIS = 300L;
    private static final long SINGLE_CLAIM_INTERVAL_MILLIS = 150L;
    private static final String COMMUNITY_ENTER = "req_community_enter";
    private static final String COMMUNITY_ENTER_RECEIVE = "req_community_enter_receive";
    private static final String EXCHANGE_CDKEY = "req_exchange_cdkey";
    private static final long CDKEY_PLATFORM = 2L;
    private static final long CDKEY_INTERVAL_MILLIS = 200L;
    private static final int CDKEY_RETRY_LATER = 7208;
    private static final long CDKEY_RETRY_COOLDOWN_MILLIS = 300000L;
    private static final long CDKEY_CODE_RETRY_COOLDOWN_MILLIS = 300000L;
    private final Map<String, Long> cdkeyCodeRetryAt = new ConcurrentHashMap<String, Long>();
    private static final String SHARE_GAME_LINK = "req_share_game_link";
    private static final String SHARE_GAME_REWARD = "req_share_game_get_reward";
    private static final long SHARE_PROBE_MAX_ID = 8L;
    private static final String DAILY_QUEST_AWARD = "req_get_all_dailyquest_award";
    private static final String DOWNLOAD_REWARD = "req_download_reward_receive";
    private static final long DOWNLOAD_REWARD_VERSION = 1L;
    private static final List<Long> DIAMOND_ITEM_IDS = List.of(Long.valueOf(100001L), Long.valueOf(100002L), Long.valueOf(100003L), Long.valueOf(100004L), Long.valueOf(100005L));
    private final GameAccountSessionService sessions;
    private final BootstrapBuildingStateRegistry buildingStates;
    private final BootstrapProgressService progress;
    private final Map<Long, BootstrapRewardState> states = new ConcurrentHashMap<Long, BootstrapRewardState>();
    private final Map<Long, GameAccountSessionService.AccountSession> boundSessions = new ConcurrentHashMap<Long, GameAccountSessionService.AccountSession>();
    private final Set<Long> communityRewardClaimed = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastQuestClaimMillis = new ConcurrentHashMap<Long, Long>();
    private final AutoCloseable sessionCreatedRegistration;
    private final AutoCloseable sessionStoppedRegistration;

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String cdkeyKey(long accountRoleId, String code) {
        return accountRoleId + ":" + code;
    }

    private boolean cdkeyCodeCoolingDown(long accountRoleId, String code) {
        Long until = this.cdkeyCodeRetryAt.get(BootstrapRewardService.cdkeyKey(accountRoleId, code));
        return until != null && System.currentTimeMillis() < until;
    }

    static String cdkeyFailureReason(int code) {
        return CdkeyFailureCatalog.reason(code);
    }

    private void note(long accountRoleId, String level, String message) {
        if (this.progress != null) {
            this.progress.note(accountRoleId, level, message);
        }
    }

    private static boolean permanentCdkeyFailure(int code) {
        return CdkeyFailureCatalog.permanent(code);
    }

    BootstrapRewardService(GameAccountSessionService sessions, BootstrapBuildingStateRegistry buildingStates) {
        this(sessions, buildingStates, null);
    }

    @Autowired
    BootstrapRewardService(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessions, BootstrapBuildingStateRegistry buildingStates, BootstrapProgressService progress) {
        this.sessions = sessions;
        this.buildingStates = buildingStates;
        this.progress = progress;
        this.sessionCreatedRegistration = sessions.addSessionCreatedListener(session -> {
            BootstrapRewardState state = new BootstrapRewardState();
            this.boundSessions.put(session.accountRoleId(), (GameAccountSessionService.AccountSession)session);
            this.states.put(session.accountRoleId(), state);
            session.observeInbound(state::accept);
        });
        this.sessionStoppedRegistration = sessions.addSessionStoppedListener(session -> {
            long accountRoleId = session.accountRoleId();
            if (this.boundSessions.remove(accountRoleId, session)) {
                this.states.remove(accountRoleId);
            }
        });
    }

    public BootstrapRewardService(GameAccountSessionService sessions) {
        this(sessions, null, null);
    }

    @PreDestroy
    public void closeRegistrations() {
        BootstrapRewardService.closeQuietly(this.sessionCreatedRegistration);
        BootstrapRewardService.closeQuietly(this.sessionStoppedRegistration);
    }

    public int pendingCount(long accountRoleId) {
        return this.state(accountRoleId).pendingCount();
    }

    public int claimPendingQuests(long accountRoleId) {
        long code;
        long now = System.currentTimeMillis();
        Long previous = this.lastQuestClaimMillis.get(accountRoleId);
        if (previous != null && now - previous < 300L) {
            return 0;
        }
        this.lastQuestClaimMillis.put(accountRoleId, now);
        if (!this.sessions.hasSession(accountRoleId)) {
            return 0;
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        BootstrapRewardState state = this.state(accountRoleId);
        int claimed = 0;
        for (long questId : state.growthQuestIds()) {
            code = this.lastCode(session, GROWTH_AWARD, Map.of("questid", questId));
            if (code == 0L || code == 900L) {
                state.markGrowthClaimed(questId);
                if (code == 0L) {
                    ++claimed;
                }
            } else {
                state.removePendingGrowthQuest(questId);
            }
            BootstrapRewardService.sleepQuietly(150L);
        }
        for (long questId : state.chapterQuestIds()) {
            code = this.lastCode(session, CHAPTER_QUEST_AWARD, Map.of("questid", questId));
            if (code == 0L || code == 900L) {
                state.markChapterQuestClaimed(questId);
                if (code == 0L) {
                    ++claimed;
                }
            } else {
                state.removePendingChapterQuest(questId);
            }
            BootstrapRewardService.sleepQuietly(150L);
        }
        claimed += this.claimChapterRewards(session, state);
        state.checkChapterAwards();
        claimed += this.claimChapterRewards(session, state);
        claimed += this.claimCommunityReward(accountRoleId);
        claimed += this.claimShareRewards(accountRoleId);
        List<Long> dailyQuestIds = state.dailyQuestIds();
        if (!dailyQuestIds.isEmpty()) {
            long code2 = this.lastCode(session, DAILY_QUEST_AWARD, Map.of("quest_ids", dailyQuestIds));
            state.markDailyQuestsClaimed(dailyQuestIds);
            if (code2 == 0L) {
                ++claimed;
            }
        }
        this.claimBackpackDiamonds(accountRoleId);
        return claimed;
    }

    private int claimChapterRewards(GameAccountSessionService.AccountSession session, BootstrapRewardState state) {
        int claimed = 0;
        for (long rewardId : state.chapterRewardIds()) {
            long code = this.lastCode(session, CHAPTER_AWARD, Map.of("chapterid", rewardId));
            if (code == 0L || code == 4801L) {
                state.markChapterRewardClaimed(rewardId);
                if (code == 0L) {
                    ++claimed;
                }
            } else {
                state.removePendingChapterReward(rewardId);
            }
            BootstrapRewardService.sleepQuietly(150L);
        }
        return claimed;
    }

    public int claimCdkeys(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            return 0;
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        BootstrapRewardState state = this.state(accountRoleId);
        ArrayList<String> pending = new ArrayList<String>(state.pendingCdkeys(BootstrapCdkeyCatalog.codes()));
        Collections.reverse(pending);
        if (pending.isEmpty()) {
            return 0;
        }
        int claimed = 0;
        int failed = 0;
        for (String code : pending) {
            if (this.cdkeyCodeCoolingDown(accountRoleId, code)) continue;
            try {
                session.request(EXCHANGE_CDKEY, Map.of("cdkey", code, "platform", 2L));
                state.markCdkeyAttempted(code);
                ++claimed;
                log.info("[\u8d77\u53f7] \u5151\u6362\u7801 {} \u5151\u6362\u6210\u529f", (Object)code);
                this.note(accountRoleId, "success", "\u5151\u6362\u7801 " + code + " \u5151\u6362\u6210\u529f");
            }
            catch (GameProtocolException exception) {
                int failureCode = exception.code();
                String reason = BootstrapRewardService.cdkeyFailureReason(failureCode);
                if (failureCode == 7208) {
                    log.info("[\u8d77\u53f7] \u5151\u6362\u7801 {} {}\uff08code=7208\uff09\uff0c{} \u5206\u949f\u540e\u518d\u8bd5", new Object[]{code, reason, 5L});
                    break;
                }
                if (BootstrapRewardService.permanentCdkeyFailure(failureCode)) {
                    state.markCdkeyAttempted(code);
                    ++failed;
                    log.info("[\u8d77\u53f7] \u5151\u6362\u7801 {} \u5931\u8d25\uff1a{}\uff08code={}\uff09\uff0c\u4ee5\u540e\u4e0d\u518d\u5c1d\u8bd5", new Object[]{code, reason, failureCode});
                    this.note(accountRoleId, "warning", "\u5151\u6362\u7801 " + code + " " + reason + "\uff0c\u5df2\u8df3\u8fc7");
                } else {
                    this.cdkeyCodeRetryAt.put(BootstrapRewardService.cdkeyKey(accountRoleId, code), System.currentTimeMillis() + 300000L);
                    log.info("[\u8d77\u53f7] \u5151\u6362\u7801 {} \u6682\u4e0d\u53ef\u7528\uff1a{}\uff08code={}\uff09\uff0c{} \u5206\u949f\u540e\u518d\u8bd5\u4e00\u6b21", new Object[]{code, reason, failureCode, 5L});
                    this.note(accountRoleId, "info", "\u5151\u6362\u7801 " + code + " " + reason + "\uff0c\u7a0d\u540e\u81ea\u52a8\u91cd\u8bd5");
                }
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] \u5151\u6362\u7801 {} \u8bf7\u6c42\u5931\u8d25 message={}", (Object)code, (Object)exception.getMessage());
            }
            BootstrapRewardService.sleepQuietly(200L);
        }
        log.info("[\u8d77\u53f7] \u5151\u6362\u7801\u672c\u8f6e\u5c1d\u8bd5 {} \u6761\uff0c\u6210\u529f {} \u6761\uff0c\u5931\u6548 {} \u6761", new Object[]{pending.size(), claimed, failed});
        this.claimBackpackDiamonds(accountRoleId);
        return claimed;
    }

    public int claimBackpackDiamonds(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            return 0;
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        BehemothAccountState inventory = this.buildingStates != null ? this.buildingStates.state(accountRoleId) : null;
        int used = 0;
        for (long itemId : DIAMOND_ITEM_IDS) {
            long count = inventory != null ? inventory.itemCount(itemId) : 0L;
            if (count <= 0L) continue;
            try {
                session.request("req_use_item", Map.of("id", itemId, "count", (int)Math.min(count, 9999L), "extra", Map.of()));
                log.info("[\u8d77\u53f7] \u5df2\u81ea\u52a8\u4f7f\u7528\u80cc\u5305\u94bb\u77f3\u9053\u5177 itemId={} count={}", (Object)itemId, (Object)count);
                ++used;
                BootstrapRewardService.sleepQuietly(150L);
            }
            catch (RuntimeException exception) {
                log.info("[\u8d77\u53f7] \u4f7f\u7528\u80cc\u5305\u94bb\u77f3\u9053\u5177\u5f02\u5e38 itemId={} message={}", (Object)itemId, (Object)exception.getMessage());
            }
        }
        return used;
    }

    public int claimCommunityReward(long accountRoleId) {
        if (this.communityRewardClaimed.contains(accountRoleId) || !this.sessions.hasSession(accountRoleId)) {
            return 0;
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        if (!this.request(session, COMMUNITY_ENTER, Map.of())) {
            return 0;
        }
        if (!this.request(session, COMMUNITY_ENTER_RECEIVE, Map.of())) {
            return 0;
        }
        this.communityRewardClaimed.add(accountRoleId);
        log.info("[\u8d77\u53f7] \u5df2\u9886\u53d6\u8fdb\u5165\u793e\u533a\u5956\u52b1\uff08req_community_enter_receive\uff09");
        return 1;
    }

    public int claimShareRewards(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            return 0;
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        BootstrapRewardState state = this.state(accountRoleId);
        int claimed = 0;
        for (long moduleId : state.pendingShareRewards()) {
            if (!this.request(session, SHARE_GAME_REWARD, Map.of("id", moduleId))) continue;
            state.markShareRewardReceived(moduleId);
            ++claimed;
        }
        if (claimed > 0 || state.shareInfoReady()) {
            return claimed;
        }
        for (long moduleId = 1L; moduleId <= 8L; ++moduleId) {
            this.request(session, SHARE_GAME_LINK, Map.of("id", moduleId));
            BootstrapRewardService.sleepQuietly(150L);
        }
        state.markShareInfoReady();
        log.info("[\u8d77\u53f7] \u5df2\u63d0\u4ea4\u5206\u4eab\u6a21\u5757\u7f16\u53f7\u63a2\u6d4b 1~{}\uff0c\u7b49\u5f85 notify_share_game_info \u56de\u544a", (Object)8L);
        return claimed;
    }

    public Map<String, Object> claimAll(long accountRoleId) {
        Map<String, Object> payload;
        List<Long> dailyQuestIds;
        int shareClaimed;
        long code;
        if (!this.sessions.hasSession(accountRoleId)) {
            throw new IllegalStateException("\u8d77\u53f7\u8d26\u53f7\u672a\u4e0a\u7ebf");
        }
        GameAccountSessionService.AccountSession session = this.sessions.onlineSession(accountRoleId);
        BootstrapRewardState state = this.state(accountRoleId);
        ArrayList<Object> claimed = new ArrayList<Object>();
        ArrayList<Object> failed = new ArrayList<Object>();
        int attempted = 0;
        for (long questId : state.growthQuestIds()) {
            ++attempted;
            code = this.lastCode(session, GROWTH_AWARD, Map.of("questid", questId));
            if (code == 0L || code == 900L) {
                state.markGrowthClaimed(questId);
                claimed.add("\u6210\u957f\u4efb\u52a1 " + questId);
            } else {
                state.removePendingGrowthQuest(questId);
                failed.add("\u6210\u957f\u4efb\u52a1 " + questId + " \u9886\u53d6\u5931\u8d25\uff08\u9519\u8bef\u7801 " + code + BootstrapRewardService.errorText(code) + "\uff09");
            }
            BootstrapRewardService.sleepQuietly(150L);
        }
        for (long questId : state.chapterQuestIds()) {
            ++attempted;
            code = this.lastCode(session, CHAPTER_QUEST_AWARD, Map.of("questid", questId));
            if (code == 0L || code == 900L) {
                state.markChapterQuestClaimed(questId);
                claimed.add("\u7ae0\u8282\u4efb\u52a1 " + questId);
            } else {
                state.removePendingChapterQuest(questId);
                failed.add("\u7ae0\u8282\u4efb\u52a1 " + questId + " \u9886\u53d6\u5931\u8d25\uff08\u9519\u8bef\u7801 " + code + BootstrapRewardService.errorText(code) + "\uff09");
            }
            BootstrapRewardService.sleepQuietly(150L);
        }
        for (long rewardId : state.chapterRewardIds()) {
            ++attempted;
            code = this.lastCode(session, CHAPTER_AWARD, Map.of("chapterid", rewardId));
            if (code == 0L || code == 4801L) {
                state.markChapterRewardClaimed(rewardId);
                claimed.add("\u7ae0\u8282\u5b9d\u7bb1 " + rewardId);
            } else {
                state.removePendingChapterReward(rewardId);
                failed.add("\u7ae0\u8282\u5b9d\u7bb1 " + rewardId + " \u9886\u53d6\u5931\u8d25\uff08\u9519\u8bef\u7801 " + code + BootstrapRewardService.errorText(code) + "\uff09");
            }
            BootstrapRewardService.sleepQuietly(150L);
        }
        if (this.claimCommunityReward(accountRoleId) > 0) {
            claimed.add("\u8fdb\u5165\u793e\u533a\u5956\u52b1");
        }
        if ((shareClaimed = this.claimShareRewards(accountRoleId)) > 0) {
            claimed.add("\u5206\u4eab\u5956\u52b1 " + shareClaimed + " \u9879");
        }
        if (!(dailyQuestIds = state.dailyQuestIds()).isEmpty()) {
            ++attempted;
            payload = Map.of("quest_ids", dailyQuestIds);
            code = this.lastCode(session, DAILY_QUEST_AWARD, payload);
            state.markDailyQuestsClaimed(dailyQuestIds);
            if (code == 0L) {
                claimed.add("\u65e5\u5e38\u4efb\u52a1 " + dailyQuestIds.size() + " \u9879");
            } else {
                failed.add("\u65e5\u5e38\u4efb\u52a1\u4e00\u952e\u9886\u5956 \u9886\u53d6\u5931\u8d25\uff08\u9519\u8bef\u7801 " + code + BootstrapRewardService.errorText(code) + "\uff09");
            }
        }
        if (state.downloadRewardReady() && !state.downloadRewardClaimed(1L)) {
            ++attempted;
            payload = Map.of("version", 1L);
            if (this.request(session, DOWNLOAD_REWARD, payload)) {
                state.markDownloadRewardClaimed(1L);
                claimed.add("\u4e0b\u8f7d\u5956\u52b1");
            } else {
                failed.add(this.failure(session, payload));
                state.markDownloadRewardClaimed(1L);
            }
        }
        for (String protocol : List.of(SEVEN_SIGN_AWARD, SIGN_21DAY_AWARD)) {
            ++attempted;
            if (!this.request(session, protocol, Map.of())) continue;
            claimed.add(protocol);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("attempted", attempted);
        result.put("claimed", List.copyOf(claimed));
        result.put("failed", List.copyOf(failed));
        result.put("pending", state.pendingCount());
        result.put("message", claimed.isEmpty() ? "\u5f53\u524d\u6ca1\u6709\u53ef\u9886\u53d6\u7684\u4efb\u52a1\u5956\u52b1" : "\u5df2\u9886\u53d6 " + claimed.size() + " \u9879\u5956\u52b1");
        return result;
    }

    private boolean request(GameAccountSessionService.AccountSession session, String protocol, Map<String, Object> payload) {
        return this.lastCode(session, protocol, payload) == 0L;
    }

    private String failure(GameAccountSessionService.AccountSession session, Map<String, Object> payload) {
        try {
            GameMessage response = session.request(DOWNLOAD_REWARD, payload);
            Map content = response == null || response.getContent() == null ? Map.of() : response.getContent();
            long code = ProtocolValueSupport.number(content.get("code"));
            return "\u4e0b\u8f7d\u5956\u52b1 \u9886\u53d6\u5931\u8d25\uff08\u9519\u8bef\u7801 " + code + BootstrapRewardService.errorText(code) + "\uff09";
        }
        catch (RuntimeException exception) {
            return "\u4e0b\u8f7d\u5956\u52b1 \u9886\u53d6\u5931\u8d25\uff08" + exception.getMessage() + "\uff09";
        }
    }

    private long lastCode(GameAccountSessionService.AccountSession session, String protocol, Map<String, Object> payload) {
        try {
            GameMessage response = session.request(protocol, payload);
            Map content = response == null || response.getContent() == null ? Map.of() : response.getContent();
            long code = ProtocolValueSupport.number(content.get("code"));
            if (code != 0L) {
                log.debug("\u8d77\u53f7\u9886\u5956\u88ab\u62d2\u7edd proto={} code={}", (Object)protocol, (Object)code);
                return code;
            }
            return 0L;
        }
        catch (RuntimeException exception) {
            log.debug("\u8d77\u53f7\u9886\u5956\u5931\u8d25 proto={} message={}", (Object)protocol, (Object)exception.getMessage());
            return -1L;
        }
    }

    private static String errorText(long code) {
        if (code == 1180L) {
            return "\uff0c\u7edf\u5e05\u5546\u5e97\u672a\u5f00\u653e";
        }
        return "";
    }

    private BootstrapRewardState state(long accountRoleId) {
        return this.states.computeIfAbsent(accountRoleId, ignored -> new BootstrapRewardState());
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
}

