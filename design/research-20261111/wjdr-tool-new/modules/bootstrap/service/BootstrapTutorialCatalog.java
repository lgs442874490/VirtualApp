/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import java.util.List;

final class BootstrapTutorialCatalog {
    static final long STORYLINE_GUIDE_ID = 100101L;
    static final long SURVIVORS_JOIN_GUIDE_ID = 200104L;
    static final long ANIMAL_ATTACKS_GUIDE_ID = 200106L;
    static final long BATTLE_CAST_XP_GUIDE_ID = 200109L;
    static final long FIRST_RECRUIT_HERO_GUIDE_ID = 200111L;
    static final long DO_SNOW_THAW_GUIDE_ID = 200130L;
    static final long SNOW_STORM_FIRST_GUIDE_ID = 200131L;
    static final long FURNACE_ACCELERATE_GUIDE_ID = 300012L;
    static final long FURNACE_ID = 301L;
    static final long KITCHEN_ID = 306L;
    static final long KITCHEN_STOVE_ID = 201L;
    static final long DORM_ONE_ID = 308L;
    static final long DORM_BED_ID = 102L;
    static final long LIGHTHOUSE_ID = 328L;
    static final long HERO_HALL_ID = 318L;
    static final long FURNACE_ACCELERATE_LEVEL = 5L;
    static final ElderBed ELDER_BED = new ElderBed(309L, 101L, 66L, 106L);
    private static final List<Guide> GUIDES = List.of(new Guide(100101L, "Plot_StoryLine", List.of(Step.furnace(1L, "\u662f\u5426\u5df2\u5347\u7ea7\u8fc7", "Guide_Plot_StoryLine_1"), Step.scripted("\u9996\u6b21\u663c\u591c\u4ea4\u66ff\u52a8\u753b\u89e6\u53d1", "Guide_Plot_StoryLine_2"), Step.building(306L, "\u5efa\u9020\u53a8\u623f\u89e6\u53d1", "Guide_Plot_StoryLine_3"), Step.furniture(), Step.survivor(), Step.scripted("\u66b4\u98ce\u96ea\u9884\u8b66\u89e6\u53d1", "Guide_Plot_StoryLine_6"), Step.scripted("\u66b4\u98ce\u96ea\u6765\u88ad\u89e6\u53d1", "Guide_Plot_StoryLine_7"), Step.elder(), Step.scripted("\u7ae0\u8282\u4efb\u52a1\u89e3\u9501\u89e6\u53d1", "Guide_Plot_StoryLine_9"))), new Guide(200104L, "Survivors_Join", List.of(Step.survivorJoin())), new Guide(200106L, "Animal_Attacks", List.of(Step.furnace(4L, "\u79ef\u96ea\u878d\u53164\u7ea7\u89e6\u53d1", "Guide_Animal_Attacks_1"), Step.scripted("\u52a8\u7269\u88ad\u51fb\u5267\u60c5\u89e6\u53d1", "Guide_Animal_Attacks_2"), Step.scripted("PVE \u5165\u53e3\u89e3\u9501", "Guide_Animal_Attacks_3"), Step.adventure("\u9996\u6b21\u63a2\u9669\u6218\u6597", "Guide_Animal_Attacks_4"))), new Guide(200109L, "Battle_Cast_Xp", List.of(Step.adventure("\u5f15\u5bfc\u70b9\u51fb\u91ca\u653e Xp", "Guide_Battle_Cast_Xp_1"))), new Guide(200111L, "First_Recruit_Hero", List.of(Step.heroEpicSearch(), Step.scripted("\u82f1\u96c4\u6309\u94ae\u89e3\u9501 UNLOCK_MENU;11", "Guide_First_Recruit_Hero_2"), Step.heroUpgraded(), Step.scripted("7 \u65e5\u7b7e\u5230\u89e3\u9501 UNLOCK_MENU;16", "Guide_First_Recruit_Hero_4"))), new Guide(200130L, "DoSnowThaw", List.of(Step.furnace(7L, "\u79ef\u96ea\u878d\u53167\u7ea7\u89e6\u53d1", "Guide_DoSnowThaw_1"), Step.scripted("\u8ff7\u96fe\u89e3\u9501", "Guide_DoSnowThaw_2"), Step.building(328L, "\u4fee\u590d\u706f\u5854", "Guide_DoSnowThaw_3"), Step.intelligenceQuest(), Step.intelligenceReward(), Step.slgMonster())), new Guide(200131L, "SnowStormFirst", List.of(Step.snowStormFirst())), new Guide(300012L, "Construct_Furnace_Accelerate", List.of(Step.furnace(5L, "\u7194\u7089\u52a0\u901f\u5f15\u5bfc", "GuideCtrl.OnConstructFinishCallback"))));
    private static final List<ModuleUnlock> MODULE_UNLOCKS = List.of(new ModuleUnlock(1L, ModuleCondition.furnace(1L)), new ModuleUnlock(5L, ModuleCondition.furnace(2L)), new ModuleUnlock(10L, ModuleCondition.guide(200106L, 1L)), new ModuleUnlock(9L, ModuleCondition.heroHall()), new ModuleUnlock(11L, ModuleCondition.guide(200111L, 2L)), new ModuleUnlock(16L, ModuleCondition.guide(200111L, 4L)), new ModuleUnlock(2L, ModuleCondition.furnace(5L)), new ModuleUnlock(12L, ModuleCondition.furnace(5L)), new ModuleUnlock(15L, ModuleCondition.furnace(5L)), new ModuleUnlock(19L, ModuleCondition.furnace(5L)), new ModuleUnlock(22L, ModuleCondition.furnace(5L)), new ModuleUnlock(24L, ModuleCondition.furnace(5L)), new ModuleUnlock(25L, ModuleCondition.furnace(5L)), new ModuleUnlock(26L, ModuleCondition.furnace(5L)), new ModuleUnlock(4L, ModuleCondition.furnace(7L)), new ModuleUnlock(13L, ModuleCondition.furnace(7L)), new ModuleUnlock(21L, ModuleCondition.furnace(7L)), new ModuleUnlock(27L, ModuleCondition.furnace(7L)), new ModuleUnlock(39L, ModuleCondition.furnace(7L)));
    static final List<Long> PRODUCTION_BUILDINGS = List.of(Long.valueOf(302L), Long.valueOf(303L), Long.valueOf(304L), Long.valueOf(305L), Long.valueOf(306L), Long.valueOf(307L));
    private static final List<PostGuide> POST_GUIDES = List.of(new PostGuide(11009L, "Barrack_Training", 321L, 1L), new PostGuide(11010L, "Barrack_Harvested", 321L, 1L), new PostGuide(200115L, "LawsOffice_Enact", 319L, 1L), new PostGuide(300009L, "First_Treatment_Soldier", 324L, 1L), new PostGuide(200118L, "Arena_Build", 326L, 3L));

    static List<PostGuide> postGuides() {
        return POST_GUIDES;
    }

    private BootstrapTutorialCatalog() {
    }

    static List<Guide> guides() {
        return GUIDES;
    }

    static List<ModuleUnlock> moduleUnlocks() {
        return MODULE_UNLOCKS;
    }

    record ElderBed(long buildingId, long furnitureId, long nameId, long imgId) {
    }

    record Guide(long id, String name, List<Step> steps) {
        long maxStep() {
            return this.steps.size();
        }
    }

    record Step(MilestoneKind kind, long buildingId, long furnitureId, long value, String node, String source) {
        static Step scripted(String node, String source) {
            return new Step(MilestoneKind.SCRIPTED, 0L, 0L, 0L, node, source);
        }

        static Step furnace(long level, String node, String source) {
            return new Step(MilestoneKind.FURNACE_LEVEL, 301L, 0L, level, node, source);
        }

        static Step building(long buildingId, String node, String source) {
            return new Step(MilestoneKind.BUILDING_CREATED, buildingId, 0L, 1L, node, source);
        }

        static Step furniture() {
            return new Step(MilestoneKind.FURNITURE_CREATED, 306L, 201L, 1L, "\u5efa\u9020\u7076\u53f0\u89e6\u53d1", "Guide_Plot_StoryLine_4");
        }

        static Step survivor() {
            return new Step(MilestoneKind.SURVIVOR_ASSIGNED, 306L, 201L, 1L, "\u59d4\u4efb\u53a8\u5e08\u89e6\u53d1", "Guide_Plot_StoryLine_5");
        }

        static Step survivorJoin() {
            return new Step(MilestoneKind.SURVIVOR_JOIN, 308L, 102L, 1L, "\u5e78\u5b58\u8005\u52a0\u5165\u89e6\u53d1", "Guide_Survivors_Join_1");
        }

        static Step elder() {
            return new Step(MilestoneKind.ELDER_DEATH, ELDER_BED.buildingId(), ELDER_BED.furnitureId(), 1L, "\u8001\u4eba\u53bb\u4e16\u5267\u60c5\u89e6\u53d1", "Guide_Plot_StoryLine_8");
        }

        static Step heroEpicSearch() {
            return new Step(MilestoneKind.HERO_EPIC_SEARCH, 0L, 0L, 1L, "\u9996\u6b21\u82f1\u96c4\u62db\u52df\u89e6\u53d1", "Guide_First_Recruit_Hero_1");
        }

        static Step snowStormFirst() {
            return new Step(MilestoneKind.SNOW_STORM_PENDING, 301L, 0L, 3L, "\u66b4\u98ce\u96ea\u6765\u88ad\u7b2c\u4e00\u6b21", "Guide_SnowStormFirst_1");
        }

        static Step heroUpgraded() {
            return new Step(MilestoneKind.HERO_UPGRADED, 0L, 0L, 2L, "\u82f1\u96c4\u5347\u7ea7\u89e6\u53d1", "Guide_First_Recruit_Hero_3");
        }

        static Step adventure(String node, String source) {
            return new Step(MilestoneKind.ADVENTURE_CLEAR, 0L, 0L, 1L, node, source);
        }

        static Step intelligenceQuest() {
            return new Step(MilestoneKind.INTELLIGENCE_QUEST, 0L, 0L, 1L, "\u8fdb\u5165 SLG \u60c5\u62a5\u7ad9", "Guide_DoSnowThaw_4");
        }

        static Step intelligenceReward() {
            return new Step(MilestoneKind.INTELLIGENCE_REWARD, 0L, 0L, 1L, "\u9886\u53d6\u5df2\u5b8c\u6210\u7684 SLG \u60c5\u62a5\u4efb\u52a1", "Guide_DoSnowThaw_5");
        }

        static Step slgMonster() {
            return new Step(MilestoneKind.SLG_MONSTER_QUEST, 0L, 0L, 1L, "\u5b8c\u6210 SLG \u602a\u7269\u60c5\u62a5", "Guide_DoSnowThaw_6");
        }
    }

    record ModuleUnlock(long module, ModuleCondition condition) {
    }

    record ModuleCondition(ModuleConditionKind kind, long first, long second) {
        static ModuleCondition furnace(long level) {
            return new ModuleCondition(ModuleConditionKind.FURNACE_LEVEL, level, 0L);
        }

        static ModuleCondition heroHall() {
            return new ModuleCondition(ModuleConditionKind.BUILDING_LEVEL, 318L, 1L);
        }

        static ModuleCondition guide(long guideId, long step) {
            return new ModuleCondition(ModuleConditionKind.GUIDE_STEP, guideId, step);
        }
    }

    record PostGuide(long id, String name, long buildingId, long maxStep) {
    }

    static enum ModuleConditionKind {
        FURNACE_LEVEL,
        BUILDING_LEVEL,
        GUIDE_STEP;

    }

    static enum MilestoneKind {
        SCRIPTED,
        FURNACE_LEVEL,
        SNOW_STORM_PENDING,
        BUILDING_CREATED,
        FURNITURE_CREATED,
        SURVIVOR_ASSIGNED,
        SURVIVOR_JOIN,
        ELDER_DEATH,
        HERO_EPIC_SEARCH,
        HERO_UPGRADED,
        ADVENTURE_CLEAR,
        INTELLIGENCE_QUEST,
        INTELLIGENCE_REWARD,
        SLG_MONSTER_QUEST;

    }
}

