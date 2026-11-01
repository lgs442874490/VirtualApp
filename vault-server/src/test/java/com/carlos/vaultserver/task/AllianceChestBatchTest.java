package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联盟宝箱批量领取（L1）：批量候选计划与请求体纯函数回归。
 *
 * <p>背景（2026-11-01）：旧实现逐箱 {@code req_alliance_receive_chest{id,type}}，每轮 N 次请求；
 * 网页版 DailyAllianceChestService:136-170 用 {@code req_alliance_receive_chest_by_ids{ids}} 一次领完。
 * 批量前必须剔除「已领/已过期/本地判失效/战利品超 500 上限」的箱子，否则整批可能被服务端拒。
 */
public class AllianceChestBatchTest {

    private static Map<String, Object> chest(long id, long cid, String name,
                                             boolean received, long endTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id > 0L) m.put("id", id);
        m.put("cid", cid);
        m.put("name", name);
        m.put("receive", received);
        if (endTime > 0L) m.put("endtime", endTime);
        return m;
    }

    @SafeVarargs
    private static Map<Long, Map<String, Object>> chests(Map<String, Object>... items) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> c : items) {
            out.put((long) out.size(), c);
        }
        return out;
    }

    @Test
    public void planSkipsReceivedExpiredInvalidAndZeroId() {
        long now = 1000L;
        Map<Long, Long> invalid = new LinkedHashMap<>();
        invalid.put(4L, 999L);
        Map<Long, Map<String, Object>> map = chests(
                chest(1L, 90100L, "已领", true, 0L),
                chest(2L, 90100L, "可领", false, 0L),
                chest(3L, 90100L, "过期", false, now - 1L),
                chest(4L, 90100L, "失效", false, 0L),
                chest(0L, 90100L, "无id", false, 0L),
                chest(5L, 90200L, "战利品", false, 0L));

        ServerTasks.ChestPlan plan = ServerTasks.planChestClaims(map, now, invalid, Long.MAX_VALUE);

        assertEquals(Arrays.asList(2L, 5L), plan.ids);
        assertEquals(Integer.valueOf(1), plan.types.get(2L));
        assertEquals(Integer.valueOf(2), plan.types.get(5L));
        assertEquals("可领", plan.names.get(2L));
        assertEquals("战利品", plan.names.get(5L));
        assertFalse(plan.trophyLimitHit);
    }

    @Test
    public void trophyBudgetTruncatesAndFlagsLimit() {
        long now = 1000L;
        Map<Long, Map<String, Object>> map = chests(
                chest(11L, 90201L, "战利品A", false, 0L),
                chest(12L, 90202L, "战利品B", false, 0L),
                chest(13L, 90203L, "战利品C", false, 0L),
                chest(14L, 90101L, "盟友赠礼", false, 0L));

        ServerTasks.ChestPlan plan = ServerTasks.planChestClaims(map, now, null, 2L);

        // 战利品按顺序消耗 2 个配额，第 3 个被截断；普通宝箱不受影响
        assertEquals(Arrays.asList(11L, 12L, 14L), plan.ids);
        assertTrue(plan.trophyLimitHit);
    }

    @Test
    public void zeroBudgetSkipsAllTrophyButKeepsNormal() {
        Map<Long, Map<String, Object>> map = chests(
                chest(21L, 90201L, "战利品", false, 0L),
                chest(22L, 90301L, "辉煌", false, 0L));

        ServerTasks.ChestPlan plan = ServerTasks.planChestClaims(map, 1000L, null, 0L);

        assertEquals(Arrays.asList(22L), plan.ids);
        assertTrue(plan.trophyLimitHit);
    }

    @Test
    public void missingNameFallsBackToHashId() {
        Map<Long, Map<String, Object>> map = chests(
                chest(41L, 90100L, null, false, 0L));

        ServerTasks.ChestPlan plan = ServerTasks.planChestClaims(map, 1000L, null, Long.MAX_VALUE);

        assertEquals("#41", plan.names.get(41L));
    }

    @Test
    public void emptyAndNullInputsAreSafe() {
        assertTrue(ServerTasks.planChestClaims(null, 0L, null, 0L).ids.isEmpty());
        assertTrue(ServerTasks.planChestClaims(new LinkedHashMap<>(), 0L, null, 0L).ids.isEmpty());
        assertTrue(ServerTasks.chestBatchArgs(null).isEmpty());
        assertTrue(ServerTasks.chestBatchArgs(new ArrayList<Long>()).isEmpty());
    }

    @Test
    public void batchArgsCarryIdArray() {
        List<Long> ids = Arrays.asList(31L, 32L);
        Map<String, Object> args = ServerTasks.chestBatchArgs(ids);

        assertEquals(1, args.size());
        @SuppressWarnings("unchecked")
        List<Long> sent = (List<Long>) args.get("ids");
        assertEquals(ids, sent);
        // 防御性拷贝：调用方后续修改不应影响请求体
        ids.set(0, 99L);
        assertEquals(31L, sent.get(0).longValue());
    }
}
