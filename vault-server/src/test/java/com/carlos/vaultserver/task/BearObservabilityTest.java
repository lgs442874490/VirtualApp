package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.StateStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 2026-11-12 审查修复（F1/F3/F4/F5）：巨熊关键路径日志、未知码文案、刷屏节流与时间可读。
 *
 * <p>纯函数/行为断言，不连真协议：FileLog 未 setup 时只写 stdout，用 System.out 捕获。
 */
public class BearObservabilityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static ServerBearTasks.BearState freshState() {
        return new ServerBearTasks.BearState();
    }

    private static String captureStdout(Runnable action) {
        PrintStream old = System.out;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(old);
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ======================= F3：5203 未归类码 =======================

    @Test
    public void bearCodeText_5203_isUnclassifiedButKeepsCode() {
        String text = ServerBearTasks.bearCodeText(5203);
        assertTrue("5203 必须有可读语义，实际=" + text, text.contains("5203"));
        assertTrue("5203 必须标注未归类，实际=" + text, text.contains("未归类"));
    }

    @Test
    public void bearCodeText_otherUnknownCode_staysEmpty() {
        assertEquals("", ServerBearTasks.bearCodeText(5999));
    }

    @Test
    public void launchFailureText_unknownCode_saysUnknownAndNoRetry() {
        String text = ServerBearTasks.launchFailureText(5999, null);
        assertTrue("未知码须保留原码，实际=" + text, text.contains("5999"));
        assertTrue("未知码须标注未知，实际=" + text, text.contains("未知"));
        assertTrue("未知码须说明不再重试，实际=" + text, text.contains("不再重试"));
    }

    @Test
    public void launchFailureText_5203_keepsCodeAndUnclassified() {
        String text = ServerBearTasks.launchFailureText(5203, null);
        assertTrue(text.contains("5203"));
        assertTrue(text.contains("未归类"));
    }

    // ======================= F1：静默分支文案 =======================

    @Test
    public void launchWaitText_hasWaitSecondsAndLastCode() {
        String text = ServerBearTasks.launchWaitText(59L, 401);
        assertTrue(text.contains("59s"));
        assertTrue(text.contains("401"));
        assertTrue(text.contains("等待重试"));
    }

    @Test
    public void launchTooLateText_hasRemainingAndPrepare() {
        String text = ServerBearTasks.launchTooLateText(120L, 2);
        assertTrue(text.contains("120s"));
        assertTrue(text.contains("准备档 2"));
    }

    @Test
    public void launchNoTargetText_hasReason() {
        String empty = ServerBearTasks.launchNoTargetText("");
        assertTrue(empty.contains("未找到联盟熊坑目标"));
        assertTrue(empty.contains("为空"));
        String why = ServerBearTasks.launchNoTargetText("联盟建筑未开放");
        assertTrue(why.contains("联盟建筑未开放"));
    }

    @Test
    public void launchNoFormationText_hasSourceAndReason() {
        String text = ServerBearTasks.launchNoFormationText("默认打熊队模板", "无英雄");
        assertTrue(text.contains("默认打熊队模板"));
        assertTrue(text.contains("无英雄"));
    }

    @Test
    public void joinSilentTexts_areReadable() {
        assertTrue(ServerBearTasks.joinNoCandidateText().contains("未发现可加入"));
        assertTrue(ServerBearTasks.joinNoFreeHeroText().contains("无空闲英雄"));
        assertTrue(ServerBearTasks.joinNoSoldierText().contains("无可用兵力"));
        assertTrue(ServerBearTasks.joinPayloadMissingText().contains("参数不完整"));
        assertTrue(ServerBearTasks.joinRequestExceptionText(116L).contains("116"));
        assertTrue(ServerBearTasks.joinPendingText(116L).contains("116"));
        assertTrue(ServerBearTasks.joinFailureText(5203).contains("5203"));
    }

    // ======================= F1：节流与输出 =======================

    @Test
    public void bearLogOnce_sameWindowSameKey_onlyFirst() {
        ServerBearTasks.BearState state = freshState();
        assertTrue(ServerBearTasks.bearLogOnce(state, 1000L, "launch-wait:401"));
        assertFalse(ServerBearTasks.bearLogOnce(state, 1000L, "launch-wait:401"));
        assertTrue("不同原因应各自记一次", ServerBearTasks.bearLogOnce(state, 1000L, "launch-wait:403"));
        assertTrue("新场次应重置", ServerBearTasks.bearLogOnce(state, 2000L, "launch-wait:401"));
    }

    @Test
    public void logOnceInfo_emitsOnceWithFields() {
        ServerBearTasks.BearState state = freshState();
        String text = "发车等待重试：还有 59s（上次码401）";
        String out = captureStdout(() -> {
            ServerBearTasks.logOnceInfo("48460403", 888L, state, 1794488400L, "launch-wait:401", text);
            ServerBearTasks.logOnceInfo("48460403", 888L, state, 1794488400L, "launch-wait:401", text);
        });
        assertEquals("同场次同原因只能打一条", 1, count(out, "发车等待重试"));
        assertTrue(out.contains("fpid=48460403"));
        assertTrue(out.contains("sid=888"));
        assertTrue(out.contains("场次="));
    }

    @Test
    public void logOnceWarn_usesWarnLevelAndThrottles() {
        ServerBearTasks.BearState state = freshState();
        String out = captureStdout(() -> {
            ServerBearTasks.logOnceWarn("1", 2L, state, 1794488400L, "no-target", "未找到联盟熊坑目标：x");
            ServerBearTasks.logOnceWarn("1", 2L, state, 1794488400L, "no-target", "未找到联盟熊坑目标：x");
        });
        assertTrue("必须走 warn 级别", out.contains("W/VaultSrv"));
        assertEquals(1, count(out, "未找到联盟熊坑目标"));
    }

    // ======================= F4：回退摘要与冲突去重 =======================

    @Test
    public void compactFallbackSummary_stripsLibraryAndFits300() {
        List<String> notes = Arrays.asList(
                "上车编队#1 跳过：编队候选均不可用：编队 #9100000010 没有英雄或 id 非法",
                "上车编队#2 跳过：编队候选均不可用：编队 #9100000011 不存在（编队库 [9100000001, 9100000002, 9100000003, 9100000004, 9100000005, 9100000006, 9100000007, 9100000008, 9100000009, 9100000010, 9100000011]）",
                "上车编队#3 跳过：编队候选均不可用：编队 #9100000012 不存在（编队库 [9100000001, 9100000002, 9100000003, 9100000004, 9100000005, 9100000006, 9100000007, 9100000008, 9100000009, 9100000010, 9100000011]）",
                "上车编队#4 跳过：编队候选均不可用：编队 #9100000013 不存在（编队库 [9100000001, 9100000002, 9100000003, 9100000004, 9100000005, 9100000006, 9100000007, 9100000008, 9100000009, 9100000010, 9100000011]）",
                "上车编队#5 跳过：编队候选均不可用：编队 #9100000014 不存在（编队库 [9100000001, 9100000002, 9100000003, 9100000004, 9100000005, 9100000006, 9100000007, 9100000008, 9100000009, 9100000010, 9100000011]）");
        String text = ServerBearTasks.compactFallbackSummary(notes);
        assertNotNull(text);
        assertTrue("须含回退语义，实际=" + text, text.contains("回退自动编队"));
        assertTrue("须含槽数，实际=" + text, text.contains("槽数 5"));
        assertTrue("单条必须 ≤300 字符，实际=" + text.length(), text.length() <= 300);
        assertFalse("不得再拼接完整编队库", text.contains("9100000001"));
    }

    @Test
    public void conflictTypeOf_ignoresDynamicHeroList() {
        String a = ServerBearTasks.conflictTypeOf(
                "上车编队#1 跳过：英雄已被发起队/前序上车占用 [50036, 50041, 50039]");
        String b = ServerBearTasks.conflictTypeOf(
                "上车编队#1 跳过：英雄已被发起队/前序上车占用 [50026, 50034, 50015]");
        assertEquals("同一冲突类型（英雄列表不同）应视为同一键", a, b);
        String other = ServerBearTasks.conflictTypeOf(
                "上车编队#2 跳过：英雄已被发起队/前序上车占用 [50036, 50041, 50039]");
        assertFalse("不同槽位不得互相吞日志", a.equals(other));
    }

    // ======================= F5：时间可读 =======================

    @Test
    public void clockText_formatsBeijingTime() {
        assertEquals("21:31:13", ServerBearTasks.clockText(1794490273L));
    }

    @Test
    public void compactFallbackSummary_emptyNotes_stillReadable() {
        String text = ServerBearTasks.compactFallbackSummary(Collections.emptyList());
        assertTrue(text.contains("回退自动编队"));
        assertTrue(text.length() <= 300);
    }

    @Test
    public void conflictKey_sameSlotSameType_logsOncePerWindow() {
        ServerBearTasks.BearState state = freshState();
        String a = "上车编队#1 跳过：英雄已被发起队/前序上车占用 [50036, 50041, 50039]";
        String b = "上车编队#1 跳过：英雄已被发起队/前序上车占用 [50026, 50034, 50015]";
        assertTrue(ServerBearTasks.bearLogOnce(state, 1000L,
                "join-conflict:" + ServerBearTasks.conflictTypeOf(a)));
        assertFalse("同槽同冲突第二轮必须静默", ServerBearTasks.bearLogOnce(state, 1000L,
                "join-conflict:" + ServerBearTasks.conflictTypeOf(b)));
    }

    @Test
    public void fallback_logsOncePerWindow() {
        ServerBearTasks.BearState state = freshState();
        assertTrue(ServerBearTasks.bearLogOnce(state, 1000L, "join-fallback"));
        assertFalse(ServerBearTasks.bearLogOnce(state, 1000L, "join-fallback"));
        assertTrue(ServerBearTasks.bearLogOnce(state, 2000L, "join-fallback"));
    }

    // ======================= F6：对账查询失败 =======================

    @Test
    public void reconcileQueryFailure_warnsOnceAndKeepsPending() {
        StateStore store;
        try {
            store = new StateStore(tmp.getRoot().toPath());
        } catch (Exception e) {
            throw new AssertionError("临时目录初始化失败", e);
        }
        TaskContext tc = new TaskContext(null, "48460403", 888L, store, null, "test");
        long start = 1794488400L;
        ServerBearTasks.BearState state = freshState();
        state.pendingJoinWindowStart = start;
        JSONArray arr = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("mid", 116L);
        o.put("owner", 1L);
        o.put("kid", 2L);
        o.put("heroes", "");
        o.put("tries", 0);
        arr.put(o);
        state.pendingJoins = arr.toString();
        ServerBearTasks.Window window = new ServerBearTasks.Window(
                ServerBearTasks.Window.ACTIVE, ServerBearTasks.Window.SRC_RESERVE,
                start - 600L, start, start + 1800L);

        String out = captureStdout(() -> {
            int[] first = ServerBearTasks.reconcilePendingJoins(tc, state, null, window, 999L);
            int[] second = ServerBearTasks.reconcilePendingJoins(tc, state, null, window, 999L);
            assertEquals(0, first[0]);
            assertEquals(0, first[1]);
            assertEquals(0, second[0]);
            assertEquals(0, second[1]);
        });

        assertTrue("查询失败必须打日志", out.contains("巨熊上车对账查询失败"));
        assertTrue(out.contains("masterMarchId=116"));
        assertTrue("必须按 warn 输出", out.contains("W/VaultSrv"));
        assertEquals("同场次同一 pending 只告警一次", 1, count(out, "巨熊上车对账查询失败"));
        JSONArray kept = new JSONArray(state.pendingJoins);
        assertEquals("查询失败不得释放 pending", 1, kept.length());
        assertEquals(2, kept.getJSONObject(0).optInt("tries"));
    }
}
