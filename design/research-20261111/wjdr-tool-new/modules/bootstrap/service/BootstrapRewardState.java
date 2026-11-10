/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import com.wjdr.tool.modules.bootstrap.service.BootstrapChapterQuestCatalog;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BootstrapRewardState {
    private final Set<Long> growthQuests = new LinkedHashSet<Long>();
    private final Set<Long> claimedGrowthQuests = new LinkedHashSet<Long>();
    private final Set<Long> chapterQuests = new LinkedHashSet<Long>();
    private final Set<Long> claimedQuests = new LinkedHashSet<Long>();
    private final Set<Long> chapterRewards = new LinkedHashSet<Long>();
    private final Set<Long> claimedChapterRewards = new LinkedHashSet<Long>();
    private long curChapter = 1001L;
    private final Set<String> attemptedCdkeys = new LinkedHashSet<String>();
    private boolean shareInfoReady;
    private final Set<Long> dailyQuests = new LinkedHashSet<Long>();
    private final Set<Long> sharedModules = new LinkedHashSet<Long>();
    private final Set<Long> receivedShareModules = new LinkedHashSet<Long>();
    private final Set<Long> claimedDownloadRewards = new LinkedHashSet<Long>();
    private boolean downloadRewardReady;

    BootstrapRewardState() {
    }

    void accept(GameMessage message) {
        if (!GameMessage.hasProtocolContent(message)) {
            return;
        }
        Map<String, Object> data = message.getContent();
        switch (message.getProto()) {
            case "notify_growthquests": {
                this.applyGrowthQuests(data);
                break;
            }
            case "notify_update_growthquest": {
                this.applyUpdateGrowthQuest(data.get("quest"));
                break;
            }
            case "notify_chapterquests": {
                this.applyChapterQuests(data);
                break;
            }
            case "notify_update_chapterquest": {
                this.applyUpdateChapterQuest(data.get("quest"));
                break;
            }
            case "notify_dailyquests": {
                this.applyDailyQuests(data);
                break;
            }
            case "notify_update_dailyquest": {
                this.applyUpdateDailyQuest(data.get("quest"));
                break;
            }
            case "notify_share_game_info": {
                this.applyShareInfo(data);
                break;
            }
            case "notify_download_reward_init": {
                this.applyDownloadRewards(data.get("data"));
                break;
            }
        }
    }

    private void applyChapterQuests(Map<String, Object> data) {
        long cur = BootstrapRewardState.id(data.get("cur_chapter"));
        if (cur > 0L) {
            this.curChapter = cur;
        }
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(data.get("finishs"))) {
            long qid = BootstrapRewardState.id(entry.get("id"));
            if (qid <= 0L) continue;
            this.claimedQuests.add(qid);
            this.chapterQuests.remove(qid);
        }
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(data.get("chapter_award"))) {
            boolean claimed;
            long cid = BootstrapRewardState.id(entry.get("id"));
            boolean bl = claimed = Boolean.TRUE.equals(entry.get("get_flag")) || "true".equalsIgnoreCase(String.valueOf(entry.get("get_flag")));
            if (cid <= 0L) continue;
            if (claimed) {
                this.claimedChapterRewards.add(cid);
                this.chapterRewards.remove(cid);
                continue;
            }
            this.chapterRewards.add(cid);
        }
        this.applyChapterQuestEntries(data.get("quests"));
        this.checkChapterAwards();
    }

    private void applyUpdateChapterQuest(Object raw) {
        this.applyChapterQuestEntries(raw);
        this.checkChapterAwards();
    }

    private void applyChapterQuestEntries(Object raw) {
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(raw)) {
            long progress;
            long qid = BootstrapRewardState.id(entry.get("id"));
            if (qid <= 0L || this.claimedQuests.contains(qid)) continue;
            long target = BootstrapChapterQuestCatalog.targetNum(qid);
            long l = progress = entry.containsKey("progress") ? BootstrapRewardState.id(entry.get("progress")) : target;
            if (progress >= target) {
                this.chapterQuests.add(qid);
                continue;
            }
            this.chapterQuests.remove(qid);
        }
    }

    void checkChapterAwards() {
        for (long ch = 1001L; ch <= Math.max(1001L, this.curChapter); ++ch) {
            if (this.claimedChapterRewards.contains(ch) || !BootstrapChapterQuestCatalog.isChapterFinished(ch, this.claimedQuests)) continue;
            this.chapterRewards.add(ch);
        }
    }

    private void applyGrowthQuests(Map<String, Object> data) {
        long qid;
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(data.get("finishs"))) {
            qid = BootstrapRewardState.id(entry.get("id"));
            if (qid <= 0L) continue;
            this.claimedGrowthQuests.add(qid);
            this.growthQuests.remove(qid);
        }
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(data.get("quests"))) {
            long progress;
            qid = BootstrapRewardState.id(entry.get("id"));
            if (qid <= 0L || this.claimedGrowthQuests.contains(qid) || (progress = entry.containsKey("progress") ? BootstrapRewardState.id(entry.get("progress")) : 1L) <= 0L) continue;
            this.growthQuests.add(qid);
        }
    }

    private void applyUpdateGrowthQuest(Object raw) {
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(raw)) {
            long progress;
            long qid = BootstrapRewardState.id(entry.get("id"));
            if (qid <= 0L || this.claimedGrowthQuests.contains(qid) || (progress = entry.containsKey("progress") ? BootstrapRewardState.id(entry.get("progress")) : 1L) <= 0L) continue;
            this.growthQuests.add(qid);
        }
    }

    private void applyDailyQuests(Map<String, Object> data) {
        LinkedHashSet<Long> finished = new LinkedHashSet<Long>();
        Object finishs = data.get("finishs");
        if (finishs instanceof Map) {
            Map map = (Map)finishs;
            for (Object k : map.keySet()) {
                fid = ProtocolValueSupport.number(k);
                if (fid <= 0L) continue;
                finished.add(fid);
            }
        } else {
            for (Object item : BootstrapProtocolSupport.iterable(finishs)) {
                fid = ProtocolValueSupport.number(item);
                if (fid <= 0L) continue;
                finished.add(fid);
            }
        }
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(data.get("quests"))) {
            long progress;
            long qid = BootstrapRewardState.id(entry.get("id"));
            long l = progress = entry.containsKey("progress") ? BootstrapRewardState.id(entry.get("progress")) : 1L;
            if (qid <= 0L || progress <= 0L || finished.contains(qid)) continue;
            this.dailyQuests.add(qid);
        }
    }

    private void applyUpdateDailyQuest(Object raw) {
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(raw)) {
            long progress;
            long qid = BootstrapRewardState.id(entry.get("id"));
            if (qid <= 0L || (progress = entry.containsKey("progress") ? BootstrapRewardState.id(entry.get("progress")) : 1L) <= 0L) continue;
            this.dailyQuests.add(qid);
        }
    }

    private void applyDownloadRewards(Object raw) {
        this.claimedDownloadRewards.clear();
        for (Map<String, Object> entry : BootstrapProtocolSupport.maps(raw)) {
            Object key = entry.get("k") != null ? entry.get("k") : entry.get("key");
            long version = BootstrapProtocolSupport.number(key);
            if (version <= 0L || !BootstrapProtocolSupport.booleanValue(entry.get("v") != null ? entry.get("v") : entry.get("value"))) continue;
            this.claimedDownloadRewards.add(version);
        }
        this.downloadRewardReady = true;
    }

    void reset() {
        this.growthQuests.clear();
        this.claimedGrowthQuests.clear();
        this.chapterQuests.clear();
        this.claimedQuests.clear();
        this.chapterRewards.clear();
        this.claimedChapterRewards.clear();
        this.curChapter = 1001L;
        this.dailyQuests.clear();
        this.sharedModules.clear();
        this.receivedShareModules.clear();
        this.attemptedCdkeys.clear();
        this.claimedDownloadRewards.clear();
        this.downloadRewardReady = false;
    }

    boolean downloadRewardReady() {
        return this.downloadRewardReady;
    }

    boolean downloadRewardClaimed(long version) {
        return this.claimedDownloadRewards.contains(version);
    }

    void markDownloadRewardClaimed(long version) {
        if (version > 0L) {
            this.claimedDownloadRewards.add(version);
        }
    }

    long curChapter() {
        return this.curChapter;
    }

    List<Long> growthQuestIds() {
        return List.copyOf(this.growthQuests);
    }

    List<Long> chapterQuestIds() {
        return List.copyOf(this.chapterQuests);
    }

    List<Long> chapterRewardIds() {
        return List.copyOf(this.chapterRewards);
    }

    void markGrowthClaimed(long questId) {
        this.growthQuests.remove(questId);
        this.claimedGrowthQuests.add(questId);
    }

    void removePendingGrowthQuest(long questId) {
        this.growthQuests.remove(questId);
    }

    void markChapterQuestClaimed(long questId) {
        this.chapterQuests.remove(questId);
        this.claimedQuests.add(questId);
        long ch = BootstrapChapterQuestCatalog.chapterId(questId);
        if (ch > 0L && !this.claimedChapterRewards.contains(ch) && BootstrapChapterQuestCatalog.isChapterFinished(ch, this.claimedQuests)) {
            this.chapterRewards.add(ch);
        }
    }

    void removePendingChapterQuest(long questId) {
        this.chapterQuests.remove(questId);
    }

    void markChapterRewardClaimed(long rewardId) {
        this.chapterRewards.remove(rewardId);
        this.claimedChapterRewards.add(rewardId);
        long next = BootstrapChapterQuestCatalog.nextChapter(rewardId);
        if (next > 0L && (this.curChapter <= rewardId || this.curChapter == 0L)) {
            this.curChapter = next;
        }
    }

    void removePendingChapterReward(long rewardId) {
        this.chapterRewards.remove(rewardId);
    }

    private void applyShareInfo(Map<String, Object> data) {
        BootstrapRewardState.flagModules(data.get("shared"), this.sharedModules);
        BootstrapRewardState.flagModules(data.get("received"), this.receivedShareModules);
        this.shareInfoReady = true;
    }

    private static void flagModules(Object raw, Set<Long> target) {
        for (Map<String, Object> entry : BootstrapRewardState.toMaps(raw)) {
            Object flag;
            long id = BootstrapRewardState.id(entry.get("id"));
            if (id <= 0L) continue;
            Object object = flag = entry.containsKey("boolean") ? entry.get("boolean") : entry.get("1");
            if (flag != null && !Boolean.parseBoolean(String.valueOf(flag))) continue;
            target.add(id);
        }
    }

    List<String> pendingCdkeys(List<String> all) {
        ArrayList<String> pending = new ArrayList<String>();
        for (String code : all) {
            if (this.attemptedCdkeys.contains(code)) continue;
            pending.add(code);
        }
        return List.copyOf(pending);
    }

    void markCdkeyAttempted(String code) {
        this.attemptedCdkeys.add(code);
    }

    List<Long> pendingShareRewards() {
        ArrayList<Long> pending = new ArrayList<Long>();
        for (Long id : this.sharedModules) {
            if (this.receivedShareModules.contains(id)) continue;
            pending.add(id);
        }
        return List.copyOf(pending);
    }

    void markShareRewardReceived(long id) {
        this.receivedShareModules.add(id);
    }

    boolean shareInfoReady() {
        return this.shareInfoReady;
    }

    void markShareInfoReady() {
        this.shareInfoReady = true;
    }

    List<Long> dailyQuestIds() {
        return List.copyOf(this.dailyQuests);
    }

    void markDailyQuestsClaimed(List<Long> questIds) {
        questIds.forEach(this.dailyQuests::remove);
    }

    int pendingCount() {
        return this.growthQuests.size() + this.chapterQuests.size() + this.chapterRewards.size();
    }

    static List<Map<String, Object>> toMaps(Object raw) {
        ArrayList<Map<String, Object>> result;
        block6: {
            block5: {
                result = new ArrayList<Map<String, Object>>();
                if (!(raw instanceof Map)) break block5;
                Map map = (Map)raw;
                if (map.containsKey("id") || map.containsKey("k") || map.containsKey("key")) {
                    result.add(BootstrapProtocolSupport.map(map));
                } else {
                    for (Map.Entry entry : map.entrySet()) {
                        Object v = entry.getValue();
                        if (!(v instanceof Map)) continue;
                        Map inner = (Map)v;
                        LinkedHashMap<String, Object> converted = new LinkedHashMap<String, Object>(BootstrapProtocolSupport.map(inner));
                        if (!converted.containsKey("id") && entry.getKey() != null) {
                            converted.put("id", entry.getKey());
                        }
                        result.add(converted);
                    }
                }
                break block6;
            }
            if (!(raw instanceof Iterable)) break block6;
            Iterable iterable = (Iterable)raw;
            for (Object item : iterable) {
                if (!(item instanceof Map)) continue;
                Map map = (Map)item;
                result.add(BootstrapProtocolSupport.map(map));
            }
        }
        return result;
    }

    private static long id(Object value) {
        return (long)Math.floor(Math.max(0.0, (double)ProtocolValueSupport.number(value)));
    }
}

