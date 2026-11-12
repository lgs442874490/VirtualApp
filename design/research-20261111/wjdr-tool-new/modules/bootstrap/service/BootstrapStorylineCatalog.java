/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import java.util.List;

final class BootstrapStorylineCatalog {
    static final long SAWMILL_ID = 302L;
    static final long KITCHEN_ID = 306L;
    static final long KITCHEN_STOVE_ID = 201L;
    static final long HUNTER_HUT_ID = 305L;
    static final long HUNTER_STATION_ID = 301L;
    static final long DORM_ONE_ID = 308L;
    static final long DORM_BED_ID = 101L;
    static final long SAWMILL_WORKBENCH_ID = 401L;
    private static final List<Step> STEPS = List.of(new Step(StepKind.BUILDING, 302L, 0L, 1L, "\u5efa\u9020\u4f10\u6728\u573a", "Guide_Construct_Sawmill_1"), new Step(StepKind.FURNITURE, 302L, 401L, 1L, "\u5efa\u9020\u4f10\u6728\u573a\u5de5\u4f5c\u53f0", "\u5bb6\u5177 401 \u5de5\u4f5c\u53f0"), new Step(StepKind.SURVIVOR, 302L, 401L, 1L, "\u59d4\u4efb\u4f10\u6728\u573a\u5de5\u4eba", "\u524d\u671f\u4efb\u52a1\uff1a\u59d4\u6d3e 1 \u4f4d\u5e78\u5b58\u8005\u5230\u4f10\u6728\u573a\u5de5\u4f5c"), new Step(StepKind.BUILDING, 306L, 0L, 1L, "\u5efa\u9020\u53a8\u623f", "Guide_Plot_StoryLine_3"), new Step(StepKind.FURNITURE, 306L, 201L, 1L, "\u5efa\u9020\u53a8\u623f\u7076\u53f0", "Guide_Plot_StoryLine_4"), new Step(StepKind.FURNITURE, 308L, 101L, 1L, "\u5efa\u9020\u6c11\u5c45\u5e8a\u4f4d", "Guide_Survivors_Join_1 \u524d\u7f6e"), new Step(StepKind.SURVIVOR, 306L, 201L, 1L, "\u59d4\u4efb\u53a8\u623f\u53a8\u5e08", "Guide_Plot_StoryLine_5"), new Step(StepKind.BUILDING, 305L, 0L, 1L, "\u5efa\u9020\u730e\u4eba\u4e4b\u5bb6", "\u751f\u8089\u4f9b\u7ed9\u5efa\u7b51"), new Step(StepKind.FURNITURE, 305L, 301L, 1L, "\u5efa\u9020\u6355\u730e\u5de5\u4f4d", "\u5bb6\u5177 301 \u6355\u730e\u5de5\u4f4d"), new Step(StepKind.SURVIVOR, 305L, 301L, 1L, "\u59d4\u4efb\u730e\u4eba", "\u6355\u730e\u5de5\u4f4d\u6d3e\u9063"));

    private BootstrapStorylineCatalog() {
    }

    static List<Step> steps() {
        return STEPS;
    }

    record Step(StepKind kind, long buildingId, long furnitureId, long targetLevel, String label, String source) {
    }

    static enum StepKind {
        BUILDING,
        FURNITURE,
        SURVIVOR;

    }
}

