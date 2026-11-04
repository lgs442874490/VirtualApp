package com.carlos.vaultserver.watchpoint;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 采集点被攻击告警（2026-11-02 用户口径）：
 * 「跟侦查一样，每人 5 分钟只显示前 3 条；通知里要有攻击人坐标、攻击人出征信息、
 *  被攻击人资源点信息、被攻击资源点坐标」。
 */
public class GatherAttackAlertTest {

    private final Map<Long, String> labels = new LinkedHashMap<>();

    @Test
    public void textHasAttackerCoordsMarchInfoVictimAndPoint() {
        labels.put(89700731L, "818");
        labels.put(89700055L, "SYD");
        String text = WatchPointEngine.gatherAlertText(
                "[818]鲱鱼罐头", 89700731L, 278, 607, 2L,
                512, 688, 185, 197860L,
                "小淑女i", 89700055L,
                "22:30:00", labels, 1000L);
        assertTrue(text, text.contains("采集点被攻击"));
        assertTrue("攻击人坐标: " + text, text.contains("(278,607)"));
        assertTrue("出征信息: " + text, text.contains("进攻") && text.contains("ETA"));
        assertTrue("被攻击人: " + text, text.contains("小淑女i") && text.contains("[SYD]"));
        assertFalse("通知里不能带 uid/aid 编号: " + text,
                text.contains("uid") || text.contains("aid") || text.contains("march"));
        assertTrue("资源点坐标: " + text, text.contains("(512,688)"));
        assertTrue("兵力和 ETA: " + text, text.contains("威胁进攻") || text.contains("兵力约"));
    }

    @Test
    public void onlyAttacksOnOccupiedPointsCount() {
        WatchPointStore store = new WatchPointStore(Paths.get("/tmp"));
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.recordResource(512, 688, 19694427L, 89700055L, 12345L);
        assertTrue("别人来打这个采集点", e.isGatherAttack(31374667L, 512, 688, 2L));
        assertFalse("集结主体不可能打采集点（2026-11-04 用户口径）",
                e.isGatherAttack(31374667L, 512, 688, 102L));
        assertFalse("集结也不可能打采集点", e.isGatherAttack(31374667L, 512, 688, 100L));
        assertFalse("自己不能打自己", e.isGatherAttack(19694427L, 512, 688, 2L));
        assertFalse("侦查不算攻击", e.isGatherAttack(31374667L, 512, 688, 1L));
        assertFalse("没人的矿点不算", e.isGatherAttack(31374667L, 100, 100, 2L));
    }

    @Test
    public void onlyRelevantGatherAlerts() {
        WatchPointStore store = new WatchPointStore(Paths.get("/tmp"));
        store.monitorTargets.clear();
        store.monitorTargets.add(new WatchPointStore.Target(31374667L, "818 80级", 278, 607));
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.recordResource(512, 688, 19694427L, 89700352L, 12345L);   // yYz 的人
        // 攻击者是监视目标 → 相关
        assertTrue(e.gatherRelevant(31374667L, pointOf(e)));
        // 被打的是友盟（yYz=89700352 在默认友盟里）→ 相关
        store.friendAids.add(89700352L);
        assertTrue(e.gatherRelevant(70000000L, pointOf(e)));
        // 都不相关 → 不推（防全图采集点互殴刷屏）
        store.friendAids.remove(89700352L);
        assertFalse(e.gatherRelevant(70000000L, pointOf(e)));
    }

    private static WatchPointEngine.ResPoint pointOf(WatchPointEngine e) {
        return e.resByPointForTest().get("512,688");
    }

    @Test
    public void quotaThreePerFiveMinutesPerAttacker() {
        WatchPointStore store = new WatchPointStore(Paths.get("/tmp"));
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        long now = System.currentTimeMillis();
        long uid = 31374667L;
        // 同一条行军重放只算一次
        assertTrue(e.gatherCountOnce(uid, 1L, now));
        assertFalse(e.gatherCountOnce(uid, 1L, now + 4000L));
        assertTrue(e.gatherCountOnce(uid, 2L, now + 1000L));
        assertTrue(e.gatherCountOnce(uid, 3L, now + 2000L));
        // 第 4 条真实行军：配额已满
        Map<Long, java.util.ArrayDeque<Long>> q = new java.util.concurrent.ConcurrentHashMap<>();
        assertTrue(WatchPointEngine.scoutQuotaAllow(q, uid, now, 300_000L, 3));
        assertTrue(WatchPointEngine.scoutQuotaAllow(q, uid, now + 1000L, 300_000L, 3));
        assertTrue(WatchPointEngine.scoutQuotaAllow(q, uid, now + 2000L, 300_000L, 3));
        assertFalse(WatchPointEngine.scoutQuotaAllow(q, uid, now + 3000L, 300_000L, 3));
    }
}
