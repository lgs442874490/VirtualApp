package com.carlos.vaultserver.guardian;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次 R（2026-10-31 用户口径）：同网页“查询坐标”——目标点的出征队列携带兵数量，
 * 用于集结兵力判定（> rally_troops_min 即真）。
 */
public class GuardianTroopProbeTest {

    private static final int SESSION = 600_123_456;

    private static ServerGuardianEngine engine() {
        com.carlos.vaultserver.VaultStore vault =
                new com.carlos.vaultserver.VaultStore(
                        java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
        return new ServerGuardianEngine("111", 913L, null, vault);
    }

    private static Map<String, Object> march(long owner, int x, int y, long soldiers) {
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", (long) x);
        ep.put("y", (long) y);
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000", soldiers);
        Map<String, Object> squad = new LinkedHashMap<>();
        squad.put("soldier", soldier);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owner", owner);
        m.put("endpoint", ep);
        m.put("squad_list", squad);
        return m;
    }

    @Test
    public void aggregatesEnemyMarchTroopsAtTargetPoint() {
        ServerGuardianEngine g = engine();
        try {
            g.registerProbeForTest(SESSION, 537, 436);
            List<Object> list = new ArrayList<>();
            list.add(march(2L, 537, 436, 200_000L));
            list.add(march(3L, 537, 436, 150_000L));
            list.add(march(4L, 500, 400, 999_999L));   // 别的坐标不计
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("session", (long) SESSION);
            content.put("datalist", list);
            g.handleProbeMessage("notify_world_march_add", content);

            long[] r = g.probeResultForTest(SESSION);
            assertEquals("两支行军兵力合计 35 万", 350_000L, r[0]);
            assertEquals("命中 2 支", 2L, r[1]);
        } finally {
            g.shutdown();
        }
    }

    @Test
    public void endFlagCompletesProbe() {
        ServerGuardianEngine g = engine();
        try {
            g.registerProbeForTest(SESSION, 600, 700);
            List<Object> list = new ArrayList<>();
            list.add(march(9L, 600, 700, 320_000L));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("session", (long) SESSION);
            content.put("datalist", list);
            content.put("end_flag", Boolean.TRUE);
            g.handleProbeMessage("notify_world_marchs", content);
            long[] r = g.probeResultForTest(SESSION);
            assertEquals(320_000L, r[0]);
            assertEquals("end_flag 结束等待", 1L, r[2]);
        } finally {
            g.shutdown();
        }
    }

    @Test
    public void ignoresOtherSessionsAndOwnMarches() {
        ServerGuardianEngine g = engine();
        try {
            g.registerProbeForTest(SESSION, 700, 800);
            List<Object> list = new ArrayList<>();
            list.add(march(12345L, 700, 800, 111_111L));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("session", (long) (SESSION + 1));
            content.put("datalist", list);
            g.handleProbeMessage("notify_world_march_add", content);
            long[] r = g.probeResultForTest(SESSION);
            assertEquals("其它 session 不计", 0L, r[0]);
        } finally {
            g.shutdown();
        }
    }
}
