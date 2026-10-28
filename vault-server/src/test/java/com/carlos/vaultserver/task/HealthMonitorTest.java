package com.carlos.vaultserver.task;

import com.carlos.vaultserver.SessionControl;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.WeComNotifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次6：自动化健康告警单测（2026-10-23）。
 *
 * <p>覆盖验收口径：5 类检查项阈值（会话离线 15min / 节奏停滞 30min / 零领取 2h·SKIPPED&gt;50 /
 * 错误率 1h·样本≥20·&gt;30% / 节奏键缺失）、离线计时快照、纯函数判定、文本格式、同类 6h 节流
 * （含跨零点 state 键）、{@code VAULT_ALERTS=off} 关闭、无 webhook 降级为仅日志、
 * 只读状态（不修改 log: 键、0 游戏协议）。
 */
public class HealthMonitorTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 计数型企微替身：不发网络，只统计调用（watch 配置解析仍走真实 VaultStore）。 */
    static final class CountingNotifier extends WeComNotifier {
        int sends;
        String lastText = "";

        @Override
        public boolean send(String webhookUrl, String content) {
            sends++;
            lastText = content;
            return true;
        }
    }

    @Before
    public void setUp() {
        System.clearProperty(HealthMonitor.ENV_ENABLED);
        System.clearProperty("VAULT_TASK_CADENCE");
    }

    @After
    public void tearDown() {
        System.clearProperty(HealthMonitor.ENV_ENABLED);
        System.clearProperty("VAULT_TASK_CADENCE");
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private static long bj(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.of("GMT+8")).toInstant().toEpochMilli();
    }

    private static HealthMonitor.AccountHealth health(String fpid, boolean online,
                                                      boolean keep, boolean auto) {
        HealthMonitor.AccountHealth h = new HealthMonitor.AccountHealth();
        h.fpid = fpid;
        h.sid = SID;
        h.label = "小鹿";
        h.online = online;
        h.keep = keep;
        h.auto = auto;
        return h;
    }

    private VaultStore vault(VaultStore.Account... accounts) {
        VaultStore v = new VaultStore(tmp.getRoot().toPath());
        v.replaceAll(new ArrayList<>(Arrays.asList(accounts)));
        return v;
    }

    private static VaultStore.Account account(String fpid, boolean automation, String label) {
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.label = label;
        a.keep = automation;
        a.auto = automation;
        a.activeSid = SID;
        return a;
    }

    private static List<SessionControl.SessionStatus> statuses(String fpid, String state) {
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = fpid;
        s.sid = SID;
        s.state = state;
        return Collections.singletonList(s);
    }

    /** 造一条历史日志（可指定 ts；cat 顶层要求 fn 不含冒号）。 */
    private static void seedLog(StateStore store, String fpid, String sid, String fn,
                                long ts, String cat, String reason) {
        String key = "log:" + fpid + ":" + sid + ":" + fn;
        String old = store.getString(key);
        org.json.JSONArray arr = new org.json.JSONArray();
        org.json.JSONObject e = new org.json.JSONObject();
        e.put("ts", ts);
        e.put("cat", cat);
        e.put("reason", reason);
        e.put("detail", "");
        arr.put(e);
        if (old != null) {
            try {
                org.json.JSONArray prev = new org.json.JSONArray(old);
                for (int i = 0; i < prev.length(); i++) arr.put(prev.get(i));
            } catch (Throwable ignored) {
            }
        }
        store.putString(key, arr.toString());
    }

    // ===================================================================
    // 1) 开关 / 配置
    // ===================================================================

    @Test
    public void envSwitch_enabledByDefaultAndOffValues() {
        assertTrue(HealthMonitor.enabled());
        for (String off : new String[]{"off", "false", "0", "OFF"}) {
            System.setProperty(HealthMonitor.ENV_ENABLED, off);
            assertFalse(off, HealthMonitor.enabled());
        }
        System.setProperty(HealthMonitor.ENV_ENABLED, "");
        assertTrue(HealthMonitor.enabled());
    }

    @Test
    public void cadenceSchedulerSwitch_offSkipsCadenceCheck() {
        assertTrue(HealthMonitor.cadenceSchedulerEnabled());
        System.setProperty("VAULT_TASK_CADENCE", "off");
        assertFalse(HealthMonitor.cadenceSchedulerEnabled());
        HealthMonitor.AccountHealth h = health(FPID, true, true, true);
        h.cadenceKeys = 0;
        assertNull(HealthMonitor.checkCadence(h, System.currentTimeMillis(), false));
    }

    // ===================================================================
    // 2) 各检查项阈值（纯函数）
    // ===================================================================

    @Test
    public void checkOffline_thresholdAndGuards() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, false, true, true);
        // 首次观察（offlineSince=0）不告警
        assertNull(HealthMonitor.checkOffline(h, now));
        // 离线 14 分钟 → 不告警
        h.offlineSinceMillis = now - 14L * 60_000L;
        assertNull(HealthMonitor.checkOffline(h, now));
        // 离线 15 分钟 → 告警
        h.offlineSinceMillis = now - 15L * 60_000L;
        HealthMonitor.Alert a = HealthMonitor.checkOffline(h, now);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_OFFLINE, a.kind);
        assertTrue(a.metric.contains("15 分钟"));
        assertTrue(a.metric.contains("阈值"));
        // 在线 / keep=false → 不告警
        h.offlineSinceMillis = now - 60L * 60_000L;
        h.online = true;
        assertNull(HealthMonitor.checkOffline(h, now));
        h.online = false;
        h.keep = false;
        assertNull(HealthMonitor.checkOffline(h, now));
    }

    @Test
    public void snapshot_offlineTimerCountsFromFirstObservation() {
        long t0 = bj(2026, 10, 23, 12, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        VaultStore vault = vault(account(FPID, true, "小鹿"));
        Map<String, Long> offlineSince = new LinkedHashMap<>();

        List<HealthMonitor.AccountHealth> first = HealthMonitor.snapshot(vault, store,
                () -> statuses(FPID, "offline"), t0, offlineSince);
        assertEquals(1, first.size());
        assertFalse(first.get(0).online);
        assertEquals(Long.valueOf(t0), offlineSince.get(FPID));
        assertNull("首次观察不告警", HealthMonitor.checkOffline(first.get(0), t0));

        List<HealthMonitor.AccountHealth> second = HealthMonitor.snapshot(vault, store,
                () -> statuses(FPID, "offline"), t0 + 16L * 60_000L, offlineSince);
        assertNotNull("≈3 次连续评估后告警", HealthMonitor.checkOffline(second.get(0),
                t0 + 16L * 60_000L));

        // 恢复在线 → 计时清除
        List<HealthMonitor.AccountHealth> third = HealthMonitor.snapshot(vault, store,
                () -> statuses(FPID, "online"), t0 + 20L * 60_000L, offlineSince);
        assertTrue(third.get(0).online);
        assertFalse(offlineSince.containsKey(FPID));
        assertNull(HealthMonitor.checkOffline(third.get(0), t0 + 20L * 60_000L));
    }

    @Test
    public void checkStagnant_thresholds() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, true, true, true);
        h.lastTaskLogMillis = now - 29L * 60_000L;
        assertNull("29 分钟不告警", HealthMonitor.checkStagnant(h, now));
        h.lastTaskLogMillis = now - 31L * 60_000L;
        HealthMonitor.Alert a = HealthMonitor.checkStagnant(h, now);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_STAGNANT, a.kind);
        assertTrue(a.metric.contains("31 分钟"));
        // 无任何日志也算停滞
        h.lastTaskLogMillis = 0L;
        assertNotNull(HealthMonitor.checkStagnant(h, now));
        // 非 auto / 离线 → 不告警
        h.lastTaskLogMillis = now - 60L * 60_000L;
        h.auto = false;
        assertNull(HealthMonitor.checkStagnant(h, now));
        h.auto = true;
        h.online = false;
        assertNull(HealthMonitor.checkStagnant(h, now));
    }

    @Test
    public void checkZeroClaim_thresholds() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, true, true, true);
        h.claimed2h = 0;
        h.skipped2h = 50;
        assertNull("SKIPPED=50 不告警（严格 >50）", HealthMonitor.checkZeroClaim(h, now));
        h.skipped2h = 51;
        HealthMonitor.Alert a = HealthMonitor.checkZeroClaim(h, now);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_ZERO_CLAIM, a.kind);
        assertTrue(a.metric.contains("SKIPPED=51"));
        h.claimed2h = 1;
        assertNull("有领取不告警", HealthMonitor.checkZeroClaim(h, now));
        h.claimed2h = 0;
        h.online = false;
        assertNull(HealthMonitor.checkZeroClaim(h, now));
    }

    @Test
    public void checkErrorRate_sampleAndThreshold() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, true, true, true);
        // 样本 20、错误 7 → 35% → 告警
        h.claimed1h = 5;
        h.skipped1h = 8;
        h.error1h = 7;
        HealthMonitor.Alert a = HealthMonitor.checkErrorRate(h, now);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_ERROR_RATE, a.kind);
        assertTrue(a.metric.contains("35%"));
        // 错误 6/20 = 30%（不 >30%）→ 不告警
        h.claimed1h = 7;
        h.error1h = 6;
        assertNull(HealthMonitor.checkErrorRate(h, now));
        // 样本 19 → 不告警
        h.claimed1h = 6;
        h.skipped1h = 8;
        h.error1h = 5;
        assertNull(HealthMonitor.checkErrorRate(h, now));
        // 离线 → 不告警
        h.claimed1h = 5;
        h.skipped1h = 8;
        h.error1h = 7;
        h.online = false;
        assertNull(HealthMonitor.checkErrorRate(h, now));
    }

    @Test
    public void checkCadence_missingKeysOnlyWhenOnline() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, true, true, true);
        h.cadenceKeys = 1;
        assertNull(HealthMonitor.checkCadence(h, now, true));
        h.cadenceKeys = 0;
        HealthMonitor.Alert a = HealthMonitor.checkCadence(h, now, true);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_CADENCE, a.kind);
        h.online = false;
        assertNull(HealthMonitor.checkCadence(h, now, true));
    }

    @Test
    public void evaluate_aggregatesAllHitKindsPerAccount() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, true, true, true);
        h.lastTaskLogMillis = now - 40L * 60_000L;
        h.claimed2h = 0;
        h.skipped2h = 60;
        h.claimed1h = 5;
        h.skipped1h = 8;
        h.error1h = 7;
        h.cadenceKeys = 0;
        List<HealthMonitor.Alert> alerts = HealthMonitor.evaluate(
                Collections.singletonList(h), now, true);
        // 在线 → 无 offline；命中 stagnant / zero_claim / error_rate / cadence 共 4 类
        assertEquals(4, alerts.size());
        List<String> kinds = new ArrayList<>();
        for (HealthMonitor.Alert a : alerts) kinds.add(a.kind);
        assertTrue(kinds.contains(HealthMonitor.KIND_STAGNANT));
        assertTrue(kinds.contains(HealthMonitor.KIND_ZERO_CLAIM));
        assertTrue(kinds.contains(HealthMonitor.KIND_ERROR_RATE));
        assertTrue(kinds.contains(HealthMonitor.KIND_CADENCE));
    }

    // ===================================================================
    // 3) 聚合快照（log: / srv:cad: / 会话状态）
    // ===================================================================

    @Test
    public void snapshot_aggregatesLogsAndCadenceKeys() {
        long now = System.currentTimeMillis();
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLog(store, FPID, SID, "login_rewards", now - 60_000L, "CLAIMED", "领取");
        seedLog(store, FPID, SID, "alliance_chest", now - 120_000L, "SKIPPED", "活动未开启");
        seedLog(store, FPID, SID, "arena", now - 180_000L, "ERROR", "协议错误");
        seedLog(store, FPID, SID, "old_task", now - 3L * 3600_000L, "CLAIMED", "旧");
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", now + 60_000L);
        store.putLong("srv:cad:" + FPID + ":" + SID + ":gather", now + 60_000L);
        VaultStore vault = vault(account(FPID, true, "小鹿"));

        List<HealthMonitor.AccountHealth> hs = HealthMonitor.snapshot(vault, store,
                () -> statuses(FPID, "online"), now, new LinkedHashMap<>());
        assertEquals(1, hs.size());
        HealthMonitor.AccountHealth h = hs.get(0);
        assertTrue(h.online);
        assertTrue(h.keep);
        assertTrue(h.auto);
        assertEquals("小鹿", h.label);
        assertTrue(h.lastTaskLogMillis >= now - 180_000L);
        assertEquals(1, h.claimed2h);   // 3h 前的旧任务不计入
        assertEquals(1, h.skipped2h);
        assertEquals(1, h.claimed1h);
        assertEquals(1, h.skipped1h);
        assertEquals(1, h.error1h);
        assertEquals(2, h.cadenceKeys);
        // 该账号健康（无告警）
        assertTrue(HealthMonitor.evaluate(hs, now, true).isEmpty());
    }

    @Test
    public void snapshot_missingStatusTreatedOfflineForKeepAccount() {
        long now = bj(2026, 10, 23, 12, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        VaultStore vault = vault(account(FPID, true, "小鹿"));
        Map<String, Long> offlineSince = new LinkedHashMap<>();
        List<HealthMonitor.AccountHealth> hs = HealthMonitor.snapshot(vault, store,
                Collections::emptyList, now, offlineSince);
        assertFalse(hs.get(0).online);
        assertEquals(Long.valueOf(now), offlineSince.get(FPID));
        // 16 分钟后 → 会话离线告警
        List<HealthMonitor.AccountHealth> later = HealthMonitor.snapshot(vault, store,
                Collections::emptyList, now + 16L * 60_000L, offlineSince);
        HealthMonitor.Alert a = HealthMonitor.checkOffline(later.get(0), now + 16L * 60_000L);
        assertNotNull(a);
        assertTrue(a.account.contains(FPID));
        assertTrue(a.account.contains("小鹿"));
    }

    // ===================================================================
    // 4) 文本格式
    // ===================================================================

    @Test
    public void format_alertTextShape() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.AccountHealth h = health(FPID, false, true, true);
        h.offlineSinceMillis = now - 20L * 60_000L;
        HealthMonitor.Alert a = HealthMonitor.checkOffline(h, now);
        assertNotNull(a);
        String text = HealthMonitor.format(a);
        assertTrue(text.startsWith("【自动化告警】会话离线"));
        assertTrue(text.contains("账号："));
        assertTrue(text.contains(FPID));
        assertTrue(text.contains("指标："));
        assertTrue(text.contains("建议："));
        assertTrue(text.contains("时间：10-23 12:00"));
        assertTrue(text.length() <= HealthMonitor.MAX_TEXT_CHARS);
        assertEquals("", HealthMonitor.format(null));
    }

    // ===================================================================
    // 5) 节流（同类 6h，跨零点 state 键）
    // ===================================================================

    @Test
    public void throttle_sixHoursAndCrossMidnight() {
        long t = bj(2026, 10, 23, 9, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        assertFalse(HealthMonitor.throttled(store, null, null,
                HealthMonitor.KIND_OFFLINE, FPID, t));
        HealthMonitor.markAlert(store, null, HealthMonitor.KIND_OFFLINE, FPID, t);
        // 节流键形态：srv:alert:{kind}:{fpid}:{date}
        assertEquals("srv:alert:offline:" + FPID + ":2026-10-23",
                HealthMonitor.throttleKey(HealthMonitor.KIND_OFFLINE, FPID, t));
        assertNotNull(store.snapshot().get("srv:alert:offline:" + FPID + ":2026-10-23"));
        assertTrue("6h 内节流", HealthMonitor.throttled(store, null, null,
                HealthMonitor.KIND_OFFLINE, FPID, t + 60L * 60_000L));
        assertFalse("满 6h 放行", HealthMonitor.throttled(store, null, null,
                HealthMonitor.KIND_OFFLINE, FPID, t + HealthMonitor.THROTTLE_MILLIS));
        // 跨零点：23:50 告警，次日 00:10 仍应节流（看今天+昨天键）
        long late = bj(2026, 10, 23, 23, 50);
        HealthMonitor.markAlert(store, null, HealthMonitor.KIND_STAGNANT, FPID, late);
        assertTrue(HealthMonitor.throttled(store, null, null,
                HealthMonitor.KIND_STAGNANT, FPID, bj(2026, 10, 24, 0, 10)));
        // 不同 kind / 不同 fpid 不互相节流
        assertFalse(HealthMonitor.throttled(store, null, null,
                HealthMonitor.KIND_OFFLINE, "999", late));
    }

    // ===================================================================
    // 6) runOnce 端到端：无 webhook 降级 / 有 webhook 推送 / 关闭开关
    // ===================================================================

    @Test
    public void runOnce_noWebhook_logsAndThrottlesWithoutGameRequests() {
        long t0 = bj(2026, 10, 23, 12, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLog(store, FPID, SID, "login_rewards", t0 - 60_000L, "CLAIMED", "领取");
        Map<String, Object> before = store.snapshot();
        VaultStore vault = vault(account(FPID, true, "小鹿"));   // 默认 watch.enabled=false
        CountingNotifier notifier = new CountingNotifier();
        HealthMonitor monitor = new HealthMonitor(vault, store, notifier,
                () -> statuses(FPID, "offline"));

        // 第 1 拍：记录离线起点（无告警）
        assertEquals(0, monitor.runOnce(t0));
        // 第 2 拍（+16min）：会话离线告警 → 无 webhook 仅日志，但节流键落盘
        assertEquals(1, monitor.runOnce(t0 + 16L * 60_000L));
        assertEquals(0, notifier.sends);
        assertNotNull(store.snapshot().get("srv:alert:offline:" + FPID + ":2026-10-23"));
        // 第 3 拍（+17min）：同类 6h 节流 → 0
        assertEquals(0, monitor.runOnce(t0 + 17L * 60_000L));
        // 第 4 拍（+6h17min）：节流窗口过 → 再次告警
        assertEquals(1, monitor.runOnce(t0 + 16L * 60_000L + HealthMonitor.THROTTLE_MILLIS + 1000L));
        // 只读：log 键内容未被修改
        assertEquals(before.get("log:" + FPID + ":" + SID + ":login_rewards"),
                store.snapshot().get("log:" + FPID + ":" + SID + ":login_rewards"));
    }

    @Test
    public void runOnce_withWebhook_sendsAlertText() {
        long t0 = bj(2026, 10, 23, 12, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        VaultStore vault = vault(account(FPID, true, "小鹿"));
        vault.updateWatchConfig(true, true, "http://127.0.0.1:1/hook", 300, -1, 8);
        CountingNotifier notifier = new CountingNotifier();
        HealthMonitor monitor = new HealthMonitor(vault, store, notifier,
                () -> statuses(FPID, "offline"));
        assertEquals(0, monitor.runOnce(t0));
        assertEquals(1, monitor.runOnce(t0 + 16L * 60_000L));
        assertEquals(1, notifier.sends);
        assertTrue(notifier.lastText.startsWith("【自动化告警】会话离线"));
        assertTrue(notifier.lastText.contains(FPID));
    }

    @Test
    public void runOnce_disabledByEnv_noEvaluationNoState() {
        System.setProperty(HealthMonitor.ENV_ENABLED, "off");
        long t0 = bj(2026, 10, 23, 12, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        VaultStore vault = vault(account(FPID, true, "小鹿"));
        CountingNotifier notifier = new CountingNotifier();
        HealthMonitor monitor = new HealthMonitor(vault, store, notifier,
                () -> statuses(FPID, "offline"));
        assertEquals(0, monitor.runOnce(t0));
        assertEquals(0, monitor.runOnce(t0 + 60L * 60_000L));
        assertEquals(0, notifier.sends);
        assertEquals(0, store.snapshot().size());
        monitor.start();   // off 时 start 只打日志、不起线程
        monitor.stop();
    }

    @Test
    public void runOnce_cadenceMissingAlertsWhenOnline() {
        long t0 = bj(2026, 10, 23, 12, 0);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        seedLog(store, FPID, SID, "login_rewards", t0 - 60_000L, "CLAIMED", "领取");
        VaultStore vault = vault(account(FPID, true, "小鹿"));
        CountingNotifier notifier = new CountingNotifier();
        HealthMonitor monitor = new HealthMonitor(vault, store, notifier,
                () -> statuses(FPID, "online"));
        // 在线、有近期日志、无 srv:cad 键 → 仅 cadence 告警
        assertEquals(1, monitor.runOnce(t0));
        assertNotNull(store.snapshot().get("srv:alert:cadence:" + FPID + ":2026-10-23"));
        // 补上节奏键后下一轮不再告警（同类已节流也会 suppress，故换账号验证判定）
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", t0 + 60_000L);
        assertEquals(0, monitor.runOnce(t0 + 5L * 60_000L));
    }

    // ===================================================================
    // 7) 2026-10-28：同轮 stagnant + cadence_stall 合并为一条推送
    // ===================================================================

    @Test
    public void planPushTexts_mergesStagnantAndCadenceStallIntoSinglePush() {
        long now = bj(2026, 10, 28, 8, 5);
        HealthMonitor.Alert stag = new HealthMonitor.Alert(
                HealthMonitor.KIND_STAGNANT, FPID, "节奏停滞", "小鹿(48460403)",
                "最近 31 分钟无任务完成（阈值 30 分钟）",
                "检查任务开关/节奏调度是否开启", now);
        HealthMonitor.Alert stall = HealthMonitor.cadenceStallAlert(
                8207L, "当日跳过 Top: min_gap 21141 / wartime 4578；当前战时让位: 48460403|888(33min)", now);
        List<HealthMonitor.Alert> due = new ArrayList<>(List.of(stag, stall));

        List<String> texts = HealthMonitor.planPushTexts(due);

        assertEquals("同轮两条同源告警必须合并为 1 条推送", 1, texts.size());
        String text = texts.get(0);
        assertTrue("合并标题，实际=" + text, text.contains("派单停滞+节奏停滞"));
        assertTrue("保留全局派单信息", text.contains("30 分钟无派单") && text.contains("当日累计 8207"));
        assertTrue("保留跳过 Top/战时让位", text.contains("min_gap 21141") && text.contains("战时让位"));
        assertTrue("追加账号停滞摘要", text.contains("账号停滞") && text.contains(FPID));
        assertTrue("账号停滞指标可见", text.contains("最近 31 分钟无任务完成"));
    }

    @Test
    public void planPushTexts_singleKindUnchanged() {
        long now = bj(2026, 10, 28, 8, 5);
        HealthMonitor.Alert stag = new HealthMonitor.Alert(
                HealthMonitor.KIND_STAGNANT, FPID, "节奏停滞", "小鹿(48460403)",
                "最近 31 分钟无任务完成", "检查任务开关", now);
        List<String> onlyStag = HealthMonitor.planPushTexts(new ArrayList<>(List.of(stag)));
        assertEquals(1, onlyStag.size());
        assertTrue("只命中 stagnant 时行为不变", onlyStag.get(0).contains("【自动化告警】节奏停滞"));

        HealthMonitor.Alert stall = HealthMonitor.cadenceStallAlert(8207L, "", now);
        List<String> onlyStall = HealthMonitor.planPushTexts(new ArrayList<>(List.of(stall)));
        assertEquals(1, onlyStall.size());
        assertTrue("只命中 cadence_stall 时行为不变", onlyStall.get(0).contains("【自动化告警】派单停滞"));
    }

    @Test
    public void planPushTexts_otherKindsNotMerged() {
        long now = bj(2026, 10, 28, 8, 5);
        HealthMonitor.Alert offline = new HealthMonitor.Alert(
                HealthMonitor.KIND_OFFLINE, "999", "会话离线", "另一个账号",
                "已离线 20 分钟", "检查网络", now);
        HealthMonitor.Alert stag = new HealthMonitor.Alert(
                HealthMonitor.KIND_STAGNANT, FPID, "节奏停滞", "小鹿(48460403)",
                "最近 31 分钟无任务完成", "检查任务开关", now);
        HealthMonitor.Alert stall = HealthMonitor.cadenceStallAlert(8207L, "", now);

        List<String> texts = HealthMonitor.planPushTexts(
                new ArrayList<>(List.of(offline, stag, stall)));

        assertEquals("离线 + 合并后的停滞 = 2 条", 2, texts.size());
        assertTrue(texts.get(0).contains("【自动化告警】会话离线"));
        assertTrue(texts.get(1).contains("派单停滞+节奏停滞"));
    }

    @Test
    public void runOnce_mergesStagnantAndCadenceStallIntoOneWecomPush() {
        long t0 = bj(2026, 10, 28, 8, 5);
        StateStore store = new StateStore(tmp.getRoot().toPath());
        // 有节奏键 → 不触发“节奏键缺失(cadence)”告警
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", t0 + 60_000L);
        // 当日派单统计：dispatch=0 且起点已超 30 分钟 → cadence_stall 命中
        String day = com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd")
                .format(new java.util.Date(t0));
        org.json.JSONObject stats = new org.json.JSONObject();
        stats.put("dispatch", 0);
        stats.put("firstAt", t0 - 31L * 60_000L);
        stats.put("globalMax", 20);
        stats.put("minuteDispatch", 0);
        stats.put("skip", new org.json.JSONObject().put("wartime", 30L));
        store.putString(HealthMonitor.CADENCE_STATS_PREFIX + day, stats.toString());

        VaultStore vault = vault(account(FPID, true, "小鹿"));
        vault.updateWatchConfig(true, true, "http://127.0.0.1:1/hook", 300, -1, 8);
        CountingNotifier notifier = new CountingNotifier();
        HealthMonitor monitor = new HealthMonitor(vault, store, notifier,
                () -> statuses(FPID, "online"));

        // 在线、auto、无任务日志 → stagnant；当日 dispatch=0 超 30min → cadence_stall；两者同轮命中
        assertEquals("同轮同源告警只推 1 条", 1, monitor.runOnce(t0));
        assertEquals("企微只收到 1 条", 1, notifier.sends);
        assertTrue("合并标题，实际=" + notifier.lastText,
                notifier.lastText.contains("派单停滞+节奏停滞"));
        assertTrue("保留账号停滞摘要", notifier.lastText.contains(FPID));

        // state 节流键保持各自独立（不影响后续节流语义）
        assertNotNull("stagnant 节流键独立落盘",
                store.snapshot().get("srv:alert:stagnant:" + FPID + ":2026-10-28"));
        assertNotNull("cadence_stall 节流键独立落盘",
                store.snapshot().get("srv:alert:cadence_stall:global:2026-10-28"));

        // 下一拍：两类都在 6h 节流窗 → 0 条、不再推送
        assertEquals(0, monitor.runOnce(t0 + 5L * 60_000L));
        assertEquals(1, notifier.sends);
    }
}
