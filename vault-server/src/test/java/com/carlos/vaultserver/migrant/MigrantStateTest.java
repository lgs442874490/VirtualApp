package com.carlos.vaultserver.migrant;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 状态快照解析回归（参考原版 {@code MigrantStateTracker}）：
 * 登录/基础/背包/联盟/移民活动/王国列表/邀请/首登标记。
 */
public class MigrantStateTest {

    private static final long START_SEC = 1_700_000_000L;
    private static final long NOW_SEC =
            START_SEC + MigrantRules.FREEDOM_START_OFFSET_SECONDS + 600L;

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> item(long id, long count) {
        return map("id", id, "count", count);
    }

    private MigrantState feedLoggedInState() {
        MigrantState state = new MigrantState();
        Map<String, Object> role = map("kid", 888L);
        List<Map<String, Object>> roles = new ArrayList<>();
        roles.add(role);
        state.handle("loginauth", map("uid", 1001L, "serverid", 888L, "role_list", roles));
        state.handle("notify_base_info", map("uid", 1001L, "kid", 888L,
                "stove_lv", 30, "office", 0, "register_time", 100L));
        state.handle("notify_alliance_init", map("player", map("aid", 0L)));
        List<Map<String, Object>> bag = new ArrayList<>();
        bag.add(item(MigrantRules.MIGRANT_PERMIT_ITEM_ID, 20L));
        bag.add(item(MigrantRules.RECALL_ITEM_ID, 1L));
        state.handle("notify_backpack_init", map("data", bag));
        Map<String, Object> actInfo = map("id", MigrantRules.SUPPORTED_ACTIVITY_ID,
                "start_ts", START_SEC,
                "end_ts", START_SEC + MigrantRules.ACTIVITY_DURATION_SECONDS,
                "serial_no", 77L);
        Map<Object, Object> kidSet = new LinkedHashMap<>();
        kidSet.put(888L, START_SEC);
        kidSet.put(1001L, START_SEC);
        Map<String, Object> invitation = map("kid", 1001L, "status", 0);
        List<Map<String, Object>> invitations = new ArrayList<>();
        invitations.add(invitation);
        Map<String, Object> info = map("act_info", actInfo, "gid", 1L,
                "standard_points", 1000L, "personal_points", 500L,
                "kid_set", kidSet, "civilian_data", map("invitation_list", invitations));
        state.handle("notify_migrant_init", map("info", info));
        List<Map<String, Object>> kingdomList = new ArrayList<>();
        kingdomList.add(map("kid", 1001L, "state", 0, "migrant_num", 0L,
                "normal_migrant_num", 0L, "power_limit", 1, "name", "K1001"));
        state.recordResponse(MigrantRules.PROTO_KINGDOM_LIST, map("list", kingdomList));
        return state;
    }

    @Test
    public void parsesLoggedInStateAndRulesPass() {
        MigrantState state = feedLoggedInState();
        MigrantState.Snapshot snap = state.snapshot(NOW_SEC * 1000L, true);

        assertTrue("初始化数据完整", snap.complete());
        assertEquals(1001L, snap.uid);
        assertEquals(888L, snap.currentKid);
        assertEquals(30, snap.stoveLevel);
        assertEquals(0L, snap.allianceId);
        assertEquals(MigrantRules.SUPPORTED_ACTIVITY_ID, snap.activityId);
        assertEquals(START_SEC, snap.activityStartSeconds);
        assertEquals(77L, snap.activitySerialNo);
        assertEquals(1L, snap.competitionAreaId);
        assertEquals(20L, snap.permitCount);
        assertEquals(2, snap.kidOpenTimes.size());
        assertEquals(1, snap.invitationKids.size());
        assertEquals(1001L, (long) snap.invitationKids.get(0));
        assertEquals("K1001", snap.kingdoms.get(1001L).name);

        MigrantRules.Evaluation evaluation = MigrantRules.evaluate(snap, 1001L);
        assertTrue("解析后的快照应通过资格：" + MigrantRules.firstFailure(evaluation),
                evaluation.ready);
        assertEquals(12, evaluation.permitsRequired);
        assertEquals(20L, evaluation.permitsOwned);
        assertTrue(evaluation.remainingQuota > 0L);
    }

    @Test
    public void backpackUpdateAddsAndDeletes() {
        MigrantState state = feedLoggedInState();
        state.handle("notify_backpack_update", map("type", "add",
                "data", Arrays.asList(item(MigrantRules.RECALL_ITEM_ID, 2L))));
        assertEquals(3L, state.itemCount(MigrantRules.RECALL_ITEM_ID));
        state.handle("notify_backpack_update", map("type", "del",
                "data", Arrays.asList(item(MigrantRules.RECALL_ITEM_ID, 2L))));
        assertEquals(1L, state.itemCount(MigrantRules.RECALL_ITEM_ID));
        assertTrue(state.backpackSynced());
    }

    @Test
    public void allianceQuitAndFirstLoginFlag() {
        MigrantState state = feedLoggedInState();
        assertFalse(state.firstLoginNotified());
        state.handle("notify_migrant_first_login", map());
        assertTrue(state.firstLoginNotified());
        state.clearFirstLoginFlag();
        assertFalse(state.firstLoginNotified());

        Map<String, Object> members = new LinkedHashMap<>();
        members.put("1001", map("uid", 1001L, "del", true));
        state.handle("notify_alliance_update", map("members", members));
        MigrantState.Snapshot snap = state.snapshot(NOW_SEC * 1000L, true);
        assertEquals(0L, snap.allianceId);

        state.markAllianceLeft();
        assertEquals(0L, state.snapshot(NOW_SEC * 1000L, true).allianceId);
    }

    @Test
    public void missingActivityKeepsEvaluationBlockedNotCrash() {
        MigrantState state = new MigrantState();
        MigrantState.Snapshot snap = state.snapshot(NOW_SEC * 1000L, false);
        assertFalse(snap.complete());
        MigrantRules.Evaluation evaluation = MigrantRules.evaluate(snap, 1001L);
        assertFalse(evaluation.ready);
        assertFalse(evaluation.checkOf("state").passed);
        assertTrue(evaluation.checkOf("state").detail.contains("尚未同步"));
    }

    @Test
    public void resetForLoginClearsEverything() {
        MigrantState state = feedLoggedInState();
        state.resetForLogin();
        MigrantState.Snapshot snap = state.snapshot(NOW_SEC * 1000L, true);
        assertFalse(snap.complete());
        assertEquals(0L, snap.uid);
        assertEquals(0L, snap.activityId);
        assertEquals(0L, snap.permitCount);
        assertTrue(snap.kingdoms.isEmpty());
        assertEquals(0L, state.itemCount(MigrantRules.RECALL_ITEM_ID));
    }
}
