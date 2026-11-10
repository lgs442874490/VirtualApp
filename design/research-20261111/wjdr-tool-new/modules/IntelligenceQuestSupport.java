/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.daily.service;

import com.wjdr.tool.modules.behemoth.service.BehemothAccountState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IntelligenceQuestSupport {
    public static final String START_BATTLE_PROTOCOL = "req_intelligence_start_battle";
    public static final String END_BATTLE_PROTOCOL = "req_intelligence_end_battle";
    public static final String RECEIVE_REWARD_PROTOCOL = "req_intelligence_receive";
    public static final int EVENT_NOT_FOUND_CODE = 2501;
    public static final long CLIENT_ADVENTURE_SQUAD_ID = 1L;
    public static final int CLIENT_SQUAD_SLOT_COUNT = 5;
    public static final int MARCH_TRANSACTION_RESCUE = 301;
    public static final int MARCH_TRANSACTION_SLG = 302;

    private IntelligenceQuestSupport() {
    }

    public static ClientSquad clientSquad(BehemothAccountState accountState) {
        if (accountState == null) {
            return null;
        }
        List<Long> saved = accountState.battleHeroArrange(1L);
        if (IntelligenceQuestSupport.positiveHeroCount(saved) >= 5) {
            return new ClientSquad(saved, "\u5ba2\u6237\u7aef\u9ed8\u8ba4\u5c0f\u961f");
        }
        List<Long> topFive = accountState.clientOneClickTopFiveSquad();
        return IntelligenceQuestSupport.positiveHeroCount(topFive) > 0 ? new ClientSquad(topFive, "\u5ba2\u6237\u7aef\u4e00\u952e\u4e0a\u9635\u5c0f\u961f") : null;
    }

    public static Map<String, Object> startBattlePayload(long eventId, ClientSquad squad) {
        ArrayList<Long> heroIds = new ArrayList<Long>();
        for (int index = 0; index < squad.heroes().size(); ++index) {
            heroIds.add(Math.max(0L, squad.heroes().get(index)));
        }
        return Map.of("id", eventId, "fight_heros_data", Map.of("source", 1L, "fight_heros", List.copyOf(heroIds)));
    }

    public static Map<String, Object> endBattlePayload(long eventId) {
        return Map.of("id", eventId);
    }

    public static Map<String, Object> receiveRewardPayload(long eventId) {
        return Map.of("id", eventId);
    }

    public static boolean supportsQuickBattle(int eventType) {
        return eventType == 3;
    }

    public static boolean isRescueEvent(int eventType) {
        return eventType == 2;
    }

    public static boolean isSlgMonsterEvent(int eventType) {
        return eventType == 1;
    }

    public static Map<String, Object> rescueMarchPayload(long eventId, long x, long y) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", 301);
        result.put("endpoint", Map.of("x", x, "y", y));
        result.put("extra", Map.of("event_id", eventId));
        return result;
    }

    public static Map<String, Object> marchPayload(long eventId, long x, long y, List<Map<String, Object>> heroes, List<Map<String, Object>> soldiers, boolean rescue) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", rescue ? 301 : 302);
        result.put("endpoint", Map.of("x", x, "y", y));
        result.put("formation", Map.of("hero_id", heroes == null ? List.of() : List.copyOf(heroes), "soldier", soldiers == null ? List.of() : List.copyOf(soldiers)));
        result.put("extra", Map.of("event_id", eventId));
        return result;
    }

    private static int positiveHeroCount(List<Long> heroes) {
        if (heroes == null) {
            return 0;
        }
        return (int)heroes.stream().filter(heroId -> heroId != null && heroId > 0L).count();
    }

    public record ClientSquad(List<Long> heroes, String label) {
        public ClientSquad {
            heroes = heroes == null ? List.of() : List.copyOf(heroes);
        }

        public boolean ready() {
            return this.heroes.stream().anyMatch(heroId -> heroId != null && heroId > 0L);
        }
    }
}

