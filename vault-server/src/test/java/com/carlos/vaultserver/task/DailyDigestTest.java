package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.WeComNotifier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次5：每日自动化日报（企微推送）单测（2026-10-23）。
 *
 * <p>覆盖验收口径：触发时刻 {@link DailyDigest#nextDigestAt}/{@link DailyDigest#catchUpDue}、
 * 环境变量（VAULT_DIGEST=off / VAULT_DIGEST_TIME）、近 24h 聚合（{@link DailyDigest#collect}：
 * 领取/跳过/异常/奖励摘要/跳过原因 TOP/节奏键/最近整轮/通知历史/窗口过滤）、
 * 文案 {@link DailyDigest#format}、幂等 {@code srv:digest:{date}}、企微 webhook 复用 watch 配置（本地 mock）、
 * 无 webhook 时只落日志 + state。
 */
public class DailyDigestTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpServer server;
    private final AtomicInteger webhookAttempts = new AtomicInteger();
    private final AtomicReference<String> webhookBody = new AtomicReference<>("");

    @Before
    public void setUp() {
        ServerNotifyHistory.clearAll();
        System.clearProperty(DailyDigest.ENV_ENABLED);
        System.clearProperty(DailyDigest.ENV_TIME);
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        server = null;
        System.clearProperty(DailyDigest.ENV_ENABLED);
        System.clearProperty(DailyDigest.ENV_TIME);
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private static long bj(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.of("GMT+8")).toInstant().toEpochMilli();
    }

    private static TaskOutcome outcome(String taskId, TaskOutcome.Category cat, String message) {
        return new TaskOutcome(taskId, taskId, cat, message);
    }

    private static void seedLogs(StateStore store) {
        TaskOutcome claimed = outcome("login_rewards", TaskOutcome.Category.CLAIMED, "领取1/跳过0");
        claimed.rewards = "钻石×10";
        claimed.subResults.put("welfare_card", "ok:月卡每日奖励：钻石×5");
        RoundRunner.applyResult(store, FPID, SID, "login_rewards", claimed, "定时");

        RoundRunner.applyResult(store, FPID, SID, "alliance_chest",
                outcome("alliance_chest", TaskOutcome.Category.SKIPPED, "活动未开启"), "定时");
        RoundRunner.applyResult(store, FPID, SID, "activity_rewards",
                outcome("activity_rewards", TaskOutcome.Category.SKIPPED, "活动未开启"), "定时");
        RoundRunner.applyResult(store, FPID, SID, "arena",
                outcome("arena", TaskOutcome.Category.ERROR, "协议错误"), "定时");

        TaskOutcome mail = outcome("mail", TaskOutcome.Category.CLAIMED, "领取1/跳过0");
        mail.rewards = "邮件：钻石×2";
        RoundRunner.applyResult(store, "57259770", 724L, "mail", mail, "定时");
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String startMockWebhook() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            webhookAttempts.incrementAndGet();
            webhookBody.set(readAll(ex.getRequestBody()));
            respond(ex, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    // ===================================================================
    // 1) 触发时刻（北京时间定点）
    // ===================================================================

    @Test
    public void nextDigestAt_sameDayThenNextDay() {
        // 08:00 → 当天 09:00
        assertEquals(bj(2026, 10, 23, 9, 0),
                DailyDigest.nextDigestAt(bj(2026, 10, 23, 8, 0), "09:00"));
        // 10:00 → 次日 09:00
        assertEquals(bj(2026, 10, 24, 9, 0),
                DailyDigest.nextDigestAt(bj(2026, 10, 23, 10, 0), "09:00"));
        // 恰好 09:00 → 严格未来，次日 09:00
        assertEquals(bj(2026, 10, 24, 9, 0),
                DailyDigest.nextDigestAt(bj(2026, 10, 23, 9, 0), "09:00"));
        // 自定义时刻 23:30
        assertEquals(bj(2026, 10, 23, 23, 30),
                DailyDigest.nextDigestAt(bj(2026, 10, 23, 22, 0), "23:30"));
        // 非法时刻回落 09:00
        assertEquals(bj(2026, 10, 24, 9, 0),
                DailyDigest.nextDigestAt(bj(2026, 10, 23, 22, 0), "25:99"));
    }

    @Test
    public void catchUpDue_onlyWithinTwelveHoursAfterTrigger() {
        // 09:30 当天 → 补发
        assertTrue(DailyDigest.catchUpDue(bj(2026, 10, 23, 9, 30), "09:00"));
        // 08:59 → 未到点
        assertFalse(DailyDigest.catchUpDue(bj(2026, 10, 23, 8, 59), "09:00"));
        // 21:00 = 09:00+12h 边界内
        assertTrue(DailyDigest.catchUpDue(bj(2026, 10, 23, 21, 0), "09:00"));
        // 21:01 → 超过 12h，不补发（避免深夜扰民）
        assertFalse(DailyDigest.catchUpDue(bj(2026, 10, 23, 21, 1), "09:00"));
    }

    @Test
    public void envDigestTimeAndSwitch() {
        // 缺省：日报关闭（2026-10-27 起企微只发告警）
        assertEquals("09:00", DailyDigest.digestTime());
        assertFalse(DailyDigest.enabled());
        // 显式开启
        System.setProperty(DailyDigest.ENV_ENABLED, "on");
        assertTrue(DailyDigest.enabled());
        // VAULT_DIGEST_TIME 覆盖
        System.setProperty(DailyDigest.ENV_TIME, "07:15");
        assertEquals("07:15", DailyDigest.digestTime());
        // 非法回落 09:00
        System.setProperty(DailyDigest.ENV_TIME, "25:99");
        assertEquals("09:00", DailyDigest.digestTime());
        // VAULT_DIGEST=off 关闭
        System.setProperty(DailyDigest.ENV_ENABLED, "off");
        assertFalse(DailyDigest.enabled());
        System.setProperty(DailyDigest.ENV_ENABLED, "false");
        assertFalse(DailyDigest.enabled());
        System.setProperty(DailyDigest.ENV_ENABLED, "0");
        assertFalse(DailyDigest.enabled());
        System.setProperty(DailyDigest.ENV_ENABLED, "");
        assertFalse(DailyDigest.enabled());
        System.setProperty(DailyDigest.ENV_ENABLED, "1");
        assertTrue(DailyDigest.enabled());
    }

    // ===================================================================
    // 2) 聚合（近 24h、多账号-角色、窗口过滤、节奏/通知历史统计）
    // ===================================================================

    @Test
    public void collect_aggregatesLast24hByRole() {
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLogs(store);
        long now = System.currentTimeMillis();
        // 窗口外（25h 前）旧日志：不能计入
        store.putString("log:OLDFP:1:foo", "[{\"ts\":" + (now - 25L * 3600_000L)
                + ",\"cat\":\"CLAIMED\",\"reason\":\"旧\",\"detail\":\"旧奖励\"}]");
        // 节奏键 / 最近整轮
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", now + 60_000L);
        store.putLong("srv:cad:" + FPID + ":" + SID + ":arena", now + 60_000L);
        store.putLong("srv:cad:57259770:724:mail", now + 60_000L);
        store.putLong("srv:lastrun:" + FPID, now - 3600_000L);

        DailyDigest.DigestData d = DailyDigest.collect(store, now, DailyDigest.WINDOW_MILLIS, 2);

        assertEquals(2, d.roleCount);
        assertEquals(2, d.onlineAccounts);
        assertEquals(5, d.totalRuns);      // A 4 个任务 + B 1 个任务
        assertEquals(2, d.totalClaimed);   // A login_rewards + B mail
        assertEquals(2, d.totalSkipped);   // A alliance_chest + activity_rewards
        assertEquals(1, d.totalFailed);    // A arena ERROR
        assertEquals(3, d.cadenceKeys);
        assertEquals(1, d.lastRuns.size());
        assertTrue(d.lastRuns.containsKey(FPID));
        assertTrue(d.latestLastRun() > 0L);
        assertEquals(0, d.notifyEntries);  // setUp 已清空历史

        // 角色 A：奖励摘要含顶层 detail 与子项 CLAIMED 摘要；跳过原因归并
        DailyDigest.RoleDigest a = d.roles.get(0);
        assertEquals(FPID + ":" + SID, a.label());
        assertEquals(4, a.runs);
        assertEquals(1, a.claimed);
        assertEquals(2, a.skipped);
        assertEquals(1, a.failed);
        assertTrue(a.rewards.contains("钻石×10"));
        assertTrue(a.rewards.contains("月卡每日奖励：钻石×5"));
        assertEquals(Integer.valueOf(2), a.skipReasons.get("活动未开启"));
        assertEquals("活动未开启", a.topSkipReasons(5).get(0).getKey());

        // 窗口外旧角色不出现
        for (DailyDigest.RoleDigest r : d.roles) {
            assertFalse("OLDFP".equals(r.fpid));
        }
        // 角色 B
        assertEquals("57259770:724", d.roles.get(1).label());
        assertEquals(1, d.roles.get(1).runs);
        assertTrue(d.roles.get(1).rewards.contains("邮件：钻石×2"));
    }

    @Test
    public void collect_emptyStore_isSafe() {
        DailyDigest.DigestData d = DailyDigest.collect(null, System.currentTimeMillis(),
                DailyDigest.WINDOW_MILLIS, -1);
        assertEquals(0, d.roleCount);
        assertEquals(-1, d.onlineAccounts);
        assertEquals(0, d.totalRuns);
        assertNotNull(DailyDigest.format(d));
    }

    // ===================================================================
    // 3) 文案格式化
    // ===================================================================

    @Test
    public void format_containsSummaryAndTopLists() {
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLogs(store);
        long now = System.currentTimeMillis();
        String text = DailyDigest.format(DailyDigest.collect(store, now,
                DailyDigest.WINDOW_MILLIS, 2));
        assertTrue(text.startsWith("【自动化日报】" + DailyDigest.dayKey(now)));
        assertTrue(text.contains("在线账号 2"));
        assertTrue(text.contains("角色 2"));
        assertTrue(text.contains("任务 5"));
        assertTrue(text.contains("领取 2"));
        assertTrue(text.contains("跳过 2"));
        assertTrue(text.contains("异常 1"));
        assertTrue(text.contains(FPID + ":" + SID));
        assertTrue(text.contains("领取："));
        assertTrue(text.contains("跳过TOP："));
        assertTrue(text.contains("活动未开启×2"));
    }

    @Test
    public void format_truncatesToWeComSafeLength() {
        DailyDigest.DigestData d = new DailyDigest.DigestData();
        d.nowMillis = System.currentTimeMillis();
        d.day = DailyDigest.dayKey(d.nowMillis);
        for (int i = 0; i < 30; i++) {
            DailyDigest.RoleDigest r = new DailyDigest.RoleDigest(String.valueOf(100000 + i), "9" + i);
            r.runs = 10;
            r.claimed = 5;
            r.rewards.add("奖励摘要很长".repeat(6));
            r.skipReasons.put("活动未开启", 9);
            d.roles.add(r);
        }
        d.roleCount = d.roles.size();
        String text = DailyDigest.format(d);
        assertTrue("文案必须压缩到企微安全长度", text.length() <= DailyDigest.MAX_TEXT_CHARS);
        assertTrue(text.contains("另有"));
    }

    // ===================================================================
    // 4) 幂等 + 无 webhook 只落 state
    // ===================================================================

    @Test
    public void runOnce_writesStateAndIsIdempotentPerDay() {
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLogs(store);
        VaultStore vault = new VaultStore(tmp.getRoot().toPath());   // 默认 watch 未启用/无 webhook
        long now = System.currentTimeMillis();
        assertTrue(DailyDigest.shouldSend(store, now));

        DailyDigest digest = new DailyDigest(vault, store, new WeComNotifier(), () -> 1);
        assertFalse("无 webhook → 仅落日志/state", digest.runOnce(now));
        String text = store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now));
        assertNotNull(text);
        assertTrue(text.startsWith("【自动化日报】"));
        assertTrue(text.contains("在线账号 1"));
        assertFalse("同日第二次必须跳过（幂等）", DailyDigest.shouldSend(store, now));
        assertFalse(digest.runOnce(now));
        assertEquals(text, store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now)));
    }

    @Test
    public void runOnce_offByEnv_noStateWritten() {
        System.setProperty(DailyDigest.ENV_ENABLED, "off");
        StateStore store = new StateStore(tmp.getRoot().toPath());
        DailyDigest digest = new DailyDigest(null, store, null, () -> 0);
        assertFalse(digest.runOnce(System.currentTimeMillis()));
        assertNull(store.getString(DailyDigest.STATE_PREFIX
                + DailyDigest.dayKey(System.currentTimeMillis())));
        digest.start();   // off 时 start 只打日志，不起线程
        digest.stop();
    }

    // ===================================================================
    // 5) 企微推送（复用 vault.json watch 配置；本地 mock）
    // ===================================================================

    @Test
    public void runOnce_sendsViaWatchWebhookOncePerDay() throws Exception {
        String hookUrl = startMockWebhook();
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLogs(store);
        VaultStore vault = new VaultStore(tmp.getRoot().toPath());
        vault.updateWatchConfig(true, true, hookUrl, 300, -1, 8);

        long now = System.currentTimeMillis();
        DailyDigest digest = new DailyDigest(vault, store, new WeComNotifier(), () -> 2);
        assertTrue("watch 配置齐全时应推送成功", digest.runOnce(now));
        assertEquals(1, webhookAttempts.get());
        String body = webhookBody.get();
        assertTrue(body.contains("\"msgtype\":\"text\""));
        assertTrue(body.contains("自动化日报"));
        String stateText = store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now));
        assertNotNull(stateText);
        assertTrue(stateText.contains("自动化日报"));
        // 同日再跑：幂等，不再推送
        assertFalse(digest.runOnce(now));
        assertEquals(1, webhookAttempts.get());
    }

    @Test
    public void runOnce_wecomDisabled_fallsBackToStateOnly() throws Exception {
        String hookUrl = startMockWebhook();
        StateStore store = new StateStore(tmp.getRoot().toPath());
        VaultStore vault = new VaultStore(tmp.getRoot().toPath());
        vault.updateWatchConfig(true, false, hookUrl, 300, -1, 8);   // wecomEnabled=false
        long now = System.currentTimeMillis();
        DailyDigest digest = new DailyDigest(vault, store, new WeComNotifier(), () -> 0);
        assertFalse(digest.runOnce(now));
        assertEquals(0, webhookAttempts.get());
        assertNotNull(store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now)));
    }

    @Test
    public void runOnce_webhookFailure_stillWritesState() throws Exception {
        // 400 响应：WeComNotifier 重试 1 次后返回 false，日报仍必须落 state（当天只尝试一次）
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            webhookAttempts.incrementAndGet();
            respond(ex, 400, "{}");
        });
        server.start();
        String hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        StateStore store = new StateStore(tmp.getRoot().toPath());
        VaultStore vault = new VaultStore(tmp.getRoot().toPath());
        vault.updateWatchConfig(true, true, hookUrl, 300, -1, 8);
        long now = System.currentTimeMillis();
        DailyDigest digest = new DailyDigest(vault, store, new WeComNotifier(), () -> 0);
        assertFalse(digest.runOnce(now));
        assertEquals(2, webhookAttempts.get());   // 首次 + 重试 1 次
        assertNotNull(store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now)));
    }

    // ===================================================================
    // 6) watch 配置读取（不推送时也不允许异常）
    // ===================================================================

    @Test
    public void trySend_noVaultOrWebhook_returnsFalseWithoutThrowing() {
        long now = System.currentTimeMillis();
        // 无 VaultStore / 无 notifier：只落 state + 日志，不抛异常
        StateStore store = new StateStore(tmp.getRoot().toPath());
        assertFalse(new DailyDigest(null, store, new WeComNotifier(), () -> 0).runOnce(now));
        assertNotNull(store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now)));
        // VaultStore 空配置（watch.enabled=false / webhook 空）：同样降级为 state
        VaultStore vault = new VaultStore(tmp.getRoot().toPath());
        StateStore store2 = new StateStore(tmp.getRoot().toPath());
        assertFalse(new DailyDigest(vault, store2, new WeComNotifier(), () -> 0)
                .runOnce(now));
        assertNotNull(store2.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now)));
    }

    @Test
    public void digestStateKeyShape() {
        long now = bj(2026, 10, 23, 9, 30);
        assertEquals("2026-10-23", DailyDigest.dayKey(now));
        assertEquals("srv:digest:", DailyDigest.STATE_PREFIX);
        // shouldSend：存在当日键 → false
        StateStore store = new StateStore(tmp.getRoot().toPath());
        assertTrue(DailyDigest.shouldSend(store, now));
        store.putString(DailyDigest.STATE_PREFIX + "2026-10-23", "日报");
        assertFalse(DailyDigest.shouldSend(store, now));
        assertTrue("次日应重新发送", DailyDigest.shouldSend(store, bj(2026, 10, 24, 9, 30)));
    }

    @Test
    public void collect_roleScopedLogKeysAggregatePerAccountRole() {
        StateStore store = new StateStore(tmp.getRoot().toPath());
        long now = System.currentTimeMillis();
        org.json.JSONArray a = new org.json.JSONArray();
        org.json.JSONObject e1 = new org.json.JSONObject();
        e1.put("ts", now - 60_000L);
        e1.put("cat", "CLAIMED");
        e1.put("reason", "角色A领取");
        e1.put("detail", "钻石×10");
        a.put(e1);
        store.putString("log:" + FPID + ":" + SID + ":35371154:login_rewards", a.toString());
        org.json.JSONArray b = new org.json.JSONArray();
        org.json.JSONObject e2 = new org.json.JSONObject();
        e2.put("ts", now - 30_000L);
        e2.put("cat", "CLAIMED");
        e2.put("reason", "角色B领取");
        e2.put("detail", "邮件×2");
        b.put(e2);
        store.putString("log:" + FPID + ":" + SID + ":88280792:mail", b.toString());

        DailyDigest.DigestData d = DailyDigest.collect(store, now, DailyDigest.WINDOW_MILLIS, 1);
        assertEquals("两个角色各 1 次运行 → 账号内区服口径合计", 2, d.totalRuns);
        assertEquals(2, d.totalClaimed);
        assertEquals(1, d.roleCount);
        DailyDigest.RoleDigest rd = d.roles.get(0);
        assertEquals(FPID + ":" + SID, rd.label());
        assertEquals(2, rd.runs);
        assertTrue(rd.rewards.contains("钻石×10"));
        assertTrue(rd.rewards.contains("邮件×2"));
    }
}
