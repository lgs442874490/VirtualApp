package com.carlos.vaultserver.watchpoint;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 通知文案不含编号（2026-11-02 用户要求）：所有企微/短信通知里不要出现 uid / aid / march 这类编号，
 * 只保留昵称、联盟简称、坐标、行为、兵力、ETA、时间。
 */
public class NotificationNoIdsTest {

    private static final Map<Long, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(89700731L, "818");
    }

    @Test
    public void incomingTextHasNoUidOrAid() {
        String text = WatchPointEngine.incomingAlertText(
                "酒当歌", 99999999L, LABELS, 2L,
                "鲱鱼罐头", 89700731L, 278, 607, 66L, 85442L, "12:18:53");
        assertFalse("不能出现 uid: " + text, text.contains("uid"));
        assertFalse("不能出现 aid: " + text, text.contains("aid"));
        assertFalse("不能出现 march: " + text, text.toLowerCase().contains("march"));
        assertTrue("联盟简称仍要显示: " + text, text.contains("[818]"));
    }

    @Test
    public void outgoingTextHasNoUidOrAid() {
        String text = WatchPointEngine.outgoingAlertText(
                "未知名玩家", 0L, LABELS, 2L, null, 0L, 278, 607, 60L, 1200L, "12:18:53", 1000L);
        assertFalse("不能出现 uid: " + text, text.contains("uid"));
        assertFalse("不能出现 aid: " + text, text.contains("aid"));
        assertFalse("不能出现 march: " + text, text.toLowerCase().contains("march"));
    }

    @Test
    public void gatherTextHasNoUidOrAid() {
        String text = WatchPointEngine.gatherAlertText(
                "鲱鱼罐头", 89700731L, 278, 607, 2L, 512, 688, 120L, 5000L,
                "未知名玩家", 0L, "12:18:53", LABELS, 1000L);
        assertFalse("不能出现 uid: " + text, text.contains("uid"));
        assertFalse("不能出现 aid: " + text, text.contains("aid"));
        assertFalse("不能出现 march: " + text, text.toLowerCase().contains("march"));
    }

    @Test
    public void allianceSuffixNeverPrintsNumbers() {
        assertEquals("", WatchPointEngine.allySuffix(89700999L, LABELS, null));
        assertEquals(" [818]", WatchPointEngine.allySuffix(89700731L, LABELS, null));
    }

    @Test
    public void targetAlertsUseLabelNotUid() {
        // 目标昵称标签（App 里配置的名字），不能变成 (uid...)
        WatchPointStore.Target t = new WatchPointStore.Target(31374667L, "818 80级", 278, 607);
        WatchPointStore store = new WatchPointStore(java.nio.file.Paths.get("/tmp"));
        store.wecomEnabled = false;
        store.monitorTargets.clear();
        store.monitorTargets.add(t);
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.noteTargetCity(31374667L, 600, 700, "[818]鲱鱼罐头");
        e.setMoveAlertWindowMsForTest(0);
        e.flushPendingMoveAlertsForTest();
        String text = e.lastAlertTextForTest();
        assertTrue("要带昵称: " + text, text.contains("[818]鲱鱼罐头"));
        assertFalse("不能出现 uid: " + text, text.contains("uid"));
    }
}
