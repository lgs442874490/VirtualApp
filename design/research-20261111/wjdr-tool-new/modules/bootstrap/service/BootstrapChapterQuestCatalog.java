/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BootstrapChapterQuestCatalog {
    private static final Map<Long, List<Long>> CHAPTER_QUESTS = new LinkedHashMap<Long, List<Long>>();
    private static final Map<Long, Long> TARGETS = new HashMap<Long, Long>();
    private static final Map<Long, Objective> OBJECTIVES;
    private static final long FURNITURE_TYPE_BUNK_BED = 1L;
    private static final long FURNITURE_TYPE_DESK = 2L;
    private static final long FURNITURE_TYPE_HUNT_STATION = 18L;
    private static final long FURNITURE_TYPE_WORKBENCH = 24L;
    private static final long FURNACE_ID = 301L;

    private BootstrapChapterQuestCatalog() {
    }

    public static boolean isChapterQuest(long questId) {
        return questId >= 1340001L && questId <= 1340999L;
    }

    public static long targetNum(long questId) {
        return TARGETS.getOrDefault(questId, 1L);
    }

    public static long chapterId(long questId) {
        if (!BootstrapChapterQuestCatalog.isChapterQuest(questId)) {
            return 0L;
        }
        long idx = (questId - 1340000L) / 100L;
        long ch = 1001L + idx;
        return CHAPTER_QUESTS.containsKey(ch) ? ch : 0L;
    }

    public static List<Long> questGroup(long chapterId) {
        return CHAPTER_QUESTS.getOrDefault(chapterId, List.of());
    }

    public static long nextChapter(long chapterId) {
        long next = chapterId + 1L;
        return CHAPTER_QUESTS.containsKey(next) ? next : 0L;
    }

    public static boolean isChapterFinished(long chapterId, Set<Long> claimedQuests) {
        List<Long> group = CHAPTER_QUESTS.get(chapterId);
        if (group == null || group.isEmpty()) {
            return false;
        }
        return claimedQuests.containsAll(group);
    }

    private static Map<Long, Objective> buildObjectives() {
        LinkedHashMap<Long, Objective> map = new LinkedHashMap<Long, Objective>();
        BootstrapChapterQuestCatalog.building(map, 1340002L, 306L, 1L, "\u5efa\u9020\u53a8\u623f");
        BootstrapChapterQuestCatalog.building(map, 1340003L, 302L, 1L, "\u5efa\u9020\u4f10\u6728\u573a");
        BootstrapChapterQuestCatalog.furniture(map, 1340004L, 302L, 24L, List.of(Long.valueOf(401L)), 1L, 1L, "\u4f10\u6728\u573a\u7684 1 \u4e2a\u5de5\u4f5c\u53f0\u5347\u5230 1 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340006L, 305L, 1L, "\u5efa\u9020\u730e\u4eba\u4e4b\u5bb6");
        BootstrapChapterQuestCatalog.furniture(map, 1340007L, 305L, 18L, List.of(Long.valueOf(301L)), 2L, 1L, "\u730e\u4eba\u4e4b\u5bb6\u7684 1 \u4e2a\u6355\u730e\u5de5\u4f4d\u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.assign(map, 1340005L, 302L, 2L, "\u59d4\u4efb 2 \u4f4d\u5e78\u5b58\u8005\u5230\u4f10\u6728\u573a\u5de5\u4f5c");
        BootstrapChapterQuestCatalog.assign(map, 1340008L, 305L, 2L, "\u59d4\u4efb 2 \u4f4d\u5e78\u5b58\u8005\u5230\u730e\u4eba\u4e4b\u5bb6\u5de5\u4f5c");
        BootstrapChapterQuestCatalog.building(map, 1340009L, 301L, 2L, "\u5927\u7194\u7089\u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.furniture(map, 1340101L, 308L, 1L, List.of(Long.valueOf(101L)), 2L, 1L, "\u6c11\u5c451 \u7684 1 \u4e2a\u53cc\u5c42\u5e8a\u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.furniture(map, 1340102L, 308L, 2L, List.of(Long.valueOf(103L)), 2L, 1L, "\u6c11\u5c451 \u7684 1 \u4e2a\u4e66\u684c\u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340103L, 308L, 2L, "\u6c11\u5c451 \u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.furniture(map, 1340104L, 308L, 1L, List.of(Long.valueOf(101L), Long.valueOf(102L)), 4L, 2L, "\u6c11\u5c451 \u7684 2 \u4e2a\u53cc\u5c42\u5e8a\u5347\u5230 4 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340105L, 302L, 2L, "\u4f10\u6728\u573a\u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340106L, 301L, 3L, "\u5927\u7194\u7089\u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340201L, 303L, 1L, "\u5efa\u9020\u7164\u77ff\u5382");
        BootstrapChapterQuestCatalog.building(map, 1340202L, 303L, 3L, "\u7164\u77ff\u5382\u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.assign(map, 1340203L, 303L, 2L, "\u59d4\u4efb 2 \u4f4d\u5e78\u5b58\u8005\u5230\u7164\u77ff\u5382\u5de5\u4f5c");
        BootstrapChapterQuestCatalog.building(map, 1340204L, 309L, 3L, "\u6c11\u5c452 \u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.furniture(map, 1340205L, 309L, 1L, List.of(Long.valueOf(101L), Long.valueOf(102L)), 4L, 2L, "\u6c11\u5c452 \u7684 2 \u4e2a\u53cc\u5c42\u5e8a\u5347\u5230 4 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340206L, 306L, 3L, "\u53a8\u623f\u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340207L, 301L, 4L, "\u5927\u7194\u7089\u5347\u5230 4 \u7ea7");
        BootstrapChapterQuestCatalog.adventure(map, 1340301L, 1L, "\u901a\u5173\u63a2\u9669\u7b2c 1 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340302L, 307L, 1L, "\u5efa\u9020\u533b\u52a1\u5ba4");
        BootstrapChapterQuestCatalog.assign(map, 1340303L, 307L, 1L, "\u59d4\u4efb 1 \u4f4d\u5e78\u5b58\u8005\u5230\u533b\u52a1\u5ba4\u5de5\u4f5c");
        BootstrapChapterQuestCatalog.building(map, 1340304L, 318L, 1L, "\u5efa\u9020\u82f1\u96c4\u5927\u5385");
        BootstrapChapterQuestCatalog.building(map, 1340307L, 302L, 4L, "\u4f10\u6728\u573a\u5347\u5230 4 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340308L, 310L, 3L, "\u6c11\u5c453 \u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340401L, 301L, 5L, "\u5927\u7194\u7089\u5347\u5230 5 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340402L, 304L, 1L, "\u5efa\u9020\u94c1\u77ff\u5382");
        BootstrapChapterQuestCatalog.building(map, 1340403L, 304L, 5L, "\u94c1\u77ff\u5382\u5347\u5230 5 \u7ea7");
        BootstrapChapterQuestCatalog.assign(map, 1340404L, 304L, 3L, "\u59d4\u4efb 3 \u4f4d\u5e78\u5b58\u8005\u5230\u94c1\u77ff\u5382\u5de5\u4f5c");
        BootstrapChapterQuestCatalog.building(map, 1340405L, 311L, 1L, "\u5efa\u9020\u6c11\u5c454");
        BootstrapChapterQuestCatalog.building(map, 1340406L, 311L, 3L, "\u6c11\u5c454 \u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.adventure(map, 1340407L, 10L, "\u901a\u5173\u63a2\u9669\u7b2c 10 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340501L, 301L, 6L, "\u5927\u7194\u7089\u5347\u5230 6 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340502L, 319L, 1L, "\u5efa\u9020\u5f8b\u4ee4\u6240");
        BootstrapChapterQuestCatalog.building(map, 1340503L, 307L, 5L, "\u533b\u52a1\u5ba4\u5347\u5230 5 \u7ea7");
        BootstrapChapterQuestCatalog.furniture(map, 1340504L, 305L, 18L, List.of(Long.valueOf(301L)), 10L, 1L, "\u730e\u4eba\u4e4b\u5bb6\u7684 1 \u4e2a\u6355\u730e\u5de5\u4f4d\u5347\u5230 10 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340505L, 312L, 3L, "\u6c11\u5c455 \u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.adventure(map, 1340506L, 20L, "\u901a\u5173\u63a2\u9669\u7b2c 20 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340508L, 301L, 7L, "\u5927\u7194\u7089\u5347\u5230 7 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340601L, 328L, 1L, "\u5efa\u9020\u706f\u5854");
        BootstrapChapterQuestCatalog.building(map, 1340602L, 313L, 3L, "\u6c11\u5c456 \u5347\u5230 3 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340603L, 321L, 1L, "\u5efa\u9020\u76fe\u5175\u8425");
        BootstrapChapterQuestCatalog.building(map, 1340607L, 332L, 2L, "\u57ce\u5899\u5347\u5230 2 \u7ea7");
        BootstrapChapterQuestCatalog.adventure(map, 1340611L, 50L, "\u901a\u5173\u63a2\u9669\u7b2c 50 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340701L, 301L, 8L, "\u5927\u7194\u7089\u5347\u5230 8 \u7ea7");
        BootstrapChapterQuestCatalog.building(map, 1340702L, 326L, 1L, "\u5efa\u9020\u7ade\u6280\u573a");
        BootstrapChapterQuestCatalog.building(map, 1340708L, 323L, 1L, "\u5efa\u9020\u5c04\u624b\u8425");
        BootstrapChapterQuestCatalog.building(map, 1340711L, 324L, 1L, "\u5efa\u9020\u519b\u533b\u6240");
        BootstrapChapterQuestCatalog.building(map, 1340712L, 325L, 1L, "\u5efa\u9020\u4f7f\u9986");
        BootstrapChapterQuestCatalog.building(map, 1340713L, 335L, 1L, "\u5efa\u9020\u91cc\u7a0b\u7891");
        BootstrapChapterQuestCatalog.adventure(map, 1340714L, 80L, "\u901a\u5173\u63a2\u9669\u7b2c 80 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340801L, 301L, 9L, "\u5927\u7194\u7089\u5347\u5230 9 \u7ea7");
        BootstrapChapterQuestCatalog.adventure(map, 1340804L, 100L, "\u901a\u5173\u63a2\u9669\u7b2c 100 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340810L, 329L, 1L, "\u5efa\u9020\u79d1\u7814\u6240");
        BootstrapChapterQuestCatalog.building(map, 1340812L, 322L, 1L, "\u5efa\u9020\u77db\u5175\u8425");
        BootstrapChapterQuestCatalog.building(map, 1340814L, 327L, 1L, "\u5efa\u9020\u4ed3\u5e93");
        BootstrapChapterQuestCatalog.building(map, 1340901L, 301L, 10L, "\u5927\u7194\u7089\u5347\u5230 10 \u7ea7");
        BootstrapChapterQuestCatalog.adventure(map, 1340904L, 150L, "\u901a\u5173\u63a2\u9669\u7b2c 150 \u5173");
        BootstrapChapterQuestCatalog.building(map, 1340908L, 330L, 1L, "\u5efa\u9020\u52df\u5175\u6240");
        BootstrapChapterQuestCatalog.building(map, 1340909L, 331L, 1L, "\u5efa\u9020\u6307\u6325\u90e8");
        return Collections.unmodifiableMap(map);
    }

    private static void assign(Map<Long, Objective> map, long questId, long buildingId, long count, String label) {
        long chapter = BootstrapChapterQuestCatalog.chapterId(questId);
        map.put(questId, new Objective(questId, chapter, ObjectiveKind.ASSIGN, buildingId, 0L, List.of(), 0L, count, label));
    }

    private static void adventure(Map<Long, Objective> map, long questId, long checkpoint, String label) {
        long chapter = BootstrapChapterQuestCatalog.chapterId(questId);
        map.put(questId, new Objective(questId, chapter, ObjectiveKind.ADVENTURE, 0L, 0L, List.of(), checkpoint, 1L, label));
    }

    private static void building(Map<Long, Objective> map, long questId, long buildingId, long targetLevel, String label) {
        long chapter = BootstrapChapterQuestCatalog.chapterId(questId);
        map.put(questId, new Objective(questId, chapter, ObjectiveKind.BUILDING, buildingId, 0L, List.of(), targetLevel, 1L, label));
    }

    private static void furniture(Map<Long, Objective> map, long questId, long buildingId, long furnitureType, List<Long> furnitureIds, long targetLevel, long count, String label) {
        long chapter = BootstrapChapterQuestCatalog.chapterId(questId);
        map.put(questId, new Objective(questId, chapter, ObjectiveKind.FURNITURE, buildingId, furnitureType, furnitureIds, targetLevel, count, label));
    }

    public static Optional<Objective> objective(long questId) {
        return Optional.ofNullable(OBJECTIVES.get(questId));
    }

    public static List<Objective> objectivesUpToChapter(long chapterId) {
        if (chapterId <= 0L) {
            return List.of();
        }
        return OBJECTIVES.values().stream().filter(objective -> objective.chapterId() > 0L && objective.chapterId() <= chapterId).toList();
    }

    public static long chapterForFurnaceLevel(long furnaceLevel) {
        for (Objective objective : OBJECTIVES.values()) {
            if (objective.kind() != ObjectiveKind.BUILDING || objective.buildingId() != 301L || objective.targetLevel() != furnaceLevel) continue;
            return objective.chapterId();
        }
        return 0L;
    }

    static {
        CHAPTER_QUESTS.put(1001L, List.of(Long.valueOf(1340001L), Long.valueOf(1340002L), Long.valueOf(1340003L), Long.valueOf(1340004L), Long.valueOf(1340005L), Long.valueOf(1340006L), Long.valueOf(1340007L), Long.valueOf(1340008L), Long.valueOf(1340009L)));
        CHAPTER_QUESTS.put(1002L, List.of(Long.valueOf(1340101L), Long.valueOf(1340102L), Long.valueOf(1340103L), Long.valueOf(1340104L), Long.valueOf(1340105L), Long.valueOf(1340106L)));
        CHAPTER_QUESTS.put(1003L, List.of(Long.valueOf(1340201L), Long.valueOf(1340204L), Long.valueOf(1340205L), Long.valueOf(1340202L), Long.valueOf(1340203L), Long.valueOf(1340206L), Long.valueOf(1340207L)));
        CHAPTER_QUESTS.put(1004L, List.of(Long.valueOf(1340301L), Long.valueOf(1340302L), Long.valueOf(1340303L), Long.valueOf(1340304L), Long.valueOf(1340305L), Long.valueOf(1340306L), Long.valueOf(1340307L), Long.valueOf(1340308L)));
        CHAPTER_QUESTS.put(1005L, List.of(Long.valueOf(1340401L), Long.valueOf(1340402L), Long.valueOf(1340403L), Long.valueOf(1340404L), Long.valueOf(1340405L), Long.valueOf(1340406L), Long.valueOf(1340407L)));
        CHAPTER_QUESTS.put(1006L, List.of(Long.valueOf(1340501L), Long.valueOf(1340502L), Long.valueOf(1340503L), Long.valueOf(1340504L), Long.valueOf(1340505L), Long.valueOf(1340506L), Long.valueOf(1340507L), Long.valueOf(1340508L)));
        CHAPTER_QUESTS.put(1007L, List.of(Long.valueOf(1340601L), Long.valueOf(1340602L), Long.valueOf(1340603L), Long.valueOf(1340604L), Long.valueOf(1340607L), Long.valueOf(1340609L), Long.valueOf(1340610L), Long.valueOf(1340611L)));
        CHAPTER_QUESTS.put(1008L, List.of(Long.valueOf(1340701L), Long.valueOf(1340702L), Long.valueOf(1340703L), Long.valueOf(1340708L), Long.valueOf(1340709L), Long.valueOf(1340710L), Long.valueOf(1340711L), Long.valueOf(1340712L), Long.valueOf(1340713L), Long.valueOf(1340714L)));
        CHAPTER_QUESTS.put(1009L, List.of(1340801L, 1340810L, 1340811L, 1340812L, 1340813L, 1340802L, 1340803L, 1340804L, 1340805L, 1340806L, 1340807L, 1340814L));
        CHAPTER_QUESTS.put(1010L, List.of(Long.valueOf(1340901L), Long.valueOf(1340909L), Long.valueOf(1340903L), Long.valueOf(1340904L), Long.valueOf(1340905L), Long.valueOf(1340906L), Long.valueOf(1340907L), Long.valueOf(1340908L), Long.valueOf(1340902L)));
        TARGETS.put(1340007L, 2L);
        TARGETS.put(1340009L, 2L);
        TARGETS.put(1340101L, 2L);
        TARGETS.put(1340102L, 2L);
        TARGETS.put(1340103L, 2L);
        TARGETS.put(1340104L, 2L);
        TARGETS.put(1340105L, 2L);
        TARGETS.put(1340106L, 3L);
        TARGETS.put(1340202L, 3L);
        TARGETS.put(1340203L, 2L);
        TARGETS.put(1340204L, 3L);
        TARGETS.put(1340205L, 2L);
        TARGETS.put(1340206L, 3L);
        TARGETS.put(1340207L, 4L);
        TARGETS.put(1340306L, 5L);
        TARGETS.put(1340307L, 4L);
        TARGETS.put(1340308L, 3L);
        TARGETS.put(1340401L, 5L);
        TARGETS.put(1340403L, 5L);
        TARGETS.put(1340404L, 3L);
        TARGETS.put(1340406L, 3L);
        TARGETS.put(1340501L, 6L);
        TARGETS.put(1340503L, 5L);
        TARGETS.put(1340504L, 10L);
        TARGETS.put(1340505L, 3L);
        TARGETS.put(1340507L, 6L);
        TARGETS.put(1340508L, 7L);
        TARGETS.put(1340602L, 3L);
        TARGETS.put(1340604L, 10L);
        TARGETS.put(1340607L, 2L);
        TARGETS.put(1340610L, 3L);
        TARGETS.put(1340701L, 8L);
        TARGETS.put(1340703L, 2L);
        TARGETS.put(1340709L, 10L);
        TARGETS.put(1340801L, 9L);
        TARGETS.put(1340803L, 2L);
        TARGETS.put(1340806L, 10L);
        TARGETS.put(1340813L, 10L);
        TARGETS.put(1340901L, 10L);
        TARGETS.put(1340905L, 5L);
        TARGETS.put(1340906L, 2L);
        TARGETS.put(1340907L, 3000L);
        OBJECTIVES = BootstrapChapterQuestCatalog.buildObjectives();
    }

    public record Objective(long questId, long chapterId, ObjectiveKind kind, long buildingId, long furnitureType, List<Long> furnitureIds, long targetLevel, long count, String label) {
        private final List<Long> furnitureIds;

        public Objective {
            furnitureIds = furnitureIds == null ? List.of() : List.copyOf(furnitureIds);
        }

        public List<Long> furnitureIds() {
            return List.copyOf(this.furnitureIds);
        }
    }

    public static enum ObjectiveKind {
        BUILDING,
        FURNITURE,
        ADVENTURE,
        ASSIGN;

    }
}

