package com.carlos.vaultserver.task;

import com.carlos.vaultserver.SessionControl;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;
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
 * 批次10：日报「自动派单 / 守护盟友」段 + 健康告警「派单停滞 / 全局预算打满」单测（2026-10-23）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>日报段解析/渲染：{@code srv:cad:stats:{date}} 契约字段（dispatch/byAccount/skip/globalMax/
 *       minuteDispatch/firstAt）、skip/账号 Top 排序（同值按键名稳定）、只取统计窗口那一天、
 *       数据缺失省略；守护盟友 {@code srv:guardian:*:ledger} 与 {@code mod:*:guardian} 兜底、
 *       dry_run/实发/失败/撤防计数与最近原因；1500 字符上限；</li>
 *   <li>健康告警纯函数：派单停滞 30 分钟窗口/首拍基线/无在线不判，预算打满 ≥95% 阈值与 15 分钟持续；
 *       runOnce 端到端（停滞告警 6h 节流、state 键、无在线不误报）。</li>
 * </ul>
 *
 * <p>不依赖网络：企微 notifier 用计数替身；不写任何游戏协议。
 */
public class DigestStatsTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 计数型企微替身：不发网络。 */
    static final class CountingNotifier extends com.carlos.vaultserver.WeComNotifier {
        int sends = 0;

        @Override
        public boolean send(String webhookUrl, String content) {
            sends++;
            return true;
        }
    }

    @Before
    public void setUp() {
        System.clearProperty(HealthMonitor.ENV_ENABLED);
        System.clearProperty("VAULT_TASK_CADENCE");
        System.clearProperty(DailyDigest.ENV_ENABLED);
        System.clearProperty(DailyDigest.ENV_TIME);
    }

    // ---------------- helpers ----------------

    private StateStore store() {
        return new StateStore(tmp.getRoot().toPath());
    }

    private static long bj(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("GMT+8"))
                .toInstant().toEpochMilli();
    }

    private static String day(long millis) {
        return DailyDigest.dayKey(millis);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String cadenceStatsJson(long dispatch, Map<String, Object> byAccount,
                                           Map<String, Object> skip, int globalMax,
                                           int minuteDispatch, long firstAt) {
        JSONObject o = new JSONObject();
        o.put("date", "2026-10-22");
        o.put("dispatch", dispatch);
        o.put("byAccount", new JSONObject(byAccount));
        o.put("skip", new JSONObject(skip));
        o.put("globalMax", globalMax);
        o.put("minuteDispatch", minuteDispatch);
        o.put("firstAt", firstAt);
        return o.toString();
    }

    private static String ledgerJson(Object... entries) {
        JSONArray arr = new JSONArray();
        for (Object e : entries) arr.put(e);
        return arr.toString();
    }

    private static JSONObject ledgerEntry(long ts, String ev, String note) {
        JSONObject e = new JSONObject();
        e.put("ts", ts);
        e.put("ev", ev);
        e.put("note", note);
        return e;
    }

    private VaultStore vault(String fpid) {
        VaultStore v = new VaultStore(tmp.getRoot().toPath());
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.label = "小鹿";
        a.keep = true;
        a.auto = true;
        a.activeSid = SID;
        v.replaceAll(new ArrayList<>(Collections.singletonList(a)));
        return v;
    }

    private static List<SessionControl.SessionStatus> status(String fpid, String state) {
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = fpid;
        s.sid = SID;
        s.state = state;
        return Collections.singletonList(s);
    }

    // ===================================================================
    // 1) 日报「自动派单」段
    // ===================================================================

    @Test
    public void parseCadenceStats_contractFieldsAndTopSorting() {
        String raw = cadenceStatsJson(123L,
                map(FPID, 80L, "57259770", 43L),
                map("min_gap", 900L, "no_due", 12L, "busy", 3L, "offline", 1L),
                20, 7, 1_792_000_000_000L);
        DailyDigest.CadenceDigest c = DailyDigest.parseCadenceStats(raw, "2026-10-22");
        assertNotNull(c);
        assertEquals("2026-10-22", c.day);
        assertEquals(123L, c.dispatch);
        assertEquals(20, c.globalMax);
        assertEquals(2, c.accounts);
        assertEquals(Long.valueOf(80L), c.byAccount.get(FPID));

        // skip Top3：次数降序（min_gap 900 > no_due 12 > busy 3），第 4 名不展示
        List<Map.Entry<String, Long>> skips = c.topSkips(3);
        assertEquals(3, skips.size());
        assertEquals("min_gap", skips.get(0).getKey());
        assertEquals("no_due", skips.get(1).getKey());
        assertEquals("busy", skips.get(2).getKey());
        // 每账号 Top：次数降序
        List<Map.Entry<String, Long>> accounts = c.topAccounts(2);
        assertEquals(FPID, accounts.get(0).getKey());
        assertEquals("57259770", accounts.get(1).getKey());
    }

    @Test
    public void parseCadenceStats_tieBreakStableAndBadJsonSafe() {
        DailyDigest.CadenceDigest c = DailyDigest.parseCadenceStats(
                cadenceStatsJson(0L, map(), map("busy", 5L, "alpha", 5L), 0, 0, 0L), "d");
        List<Map.Entry<String, Long>> skips = c.topSkips(3);
        assertEquals("同次数按原因名升序（确定性）", "alpha", skips.get(0).getKey());
        assertEquals("busy", skips.get(1).getKey());
        assertNull("坏 JSON → null（段落省略）",
                DailyDigest.parseCadenceStats("{not-json", "d"));
        assertNull(DailyDigest.parseCadenceStats(null, "d"));
        // JSON 存在但全 0：视为“有数据”（当日 0 派单也要展示）
        DailyDigest.CadenceDigest zero = DailyDigest.parseCadenceStats("{}", "d");
        assertNotNull(zero);
        assertEquals(0L, zero.dispatch);
        assertEquals(0, zero.accounts);
    }

    @Test
    public void collectNaturalDay_cadenceUsesWindowDayOnly() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        String yesterday = day(now - 24L * 3600_000L);
        store.putString("srv:cad:stats:" + yesterday, cadenceStatsJson(321L,
                map(FPID, 200L), map("no_due", 5L), 20, 3, now - 30L * 3600_000L));
        store.putString("srv:cad:stats:" + day(now), cadenceStatsJson(9L, map(FPID, 9L),
                map("busy", 1L), 20, 1, now));
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", now);

        DailyDigest.DigestData d = DailyDigest.collectNaturalDay(store, now, 1);
        assertNotNull("统计窗口那一天（昨日）的 stats 必须被采用", d.cadence);
        assertEquals(yesterday, d.cadence.day);
        assertEquals("不混入今天的 stats", 321L, d.cadence.dispatch);
        assertEquals(1, d.cadence.accounts);
        assertEquals("stats 键不计入节奏键数", 1, d.cadenceKeys);
    }

    @Test
    public void collectNaturalDay_missingStats_cadenceNull() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        DailyDigest.DigestData d = DailyDigest.collectNaturalDay(store, now, 0);
        assertNull("缺 stats → cadence 段省略", d.cadence);
        assertNull(d.guardian);
    }

    @Test
    public void format_rendersCadenceSection() {
        DailyDigest.DigestData d = new DigestData0().build();
        DailyDigest.CadenceDigest c = DailyDigest.parseCadenceStats(
                cadenceStatsJson(123L, map(FPID, 80L, "57259770", 43L),
                        map("min_gap", 900L, "no_due", 12L, "busy", 3L),
                        20, 7, 0L), "2026-10-22");
        d.cadence = c;
        String text = DailyDigest.format(d);
        assertTrue("总派单/账号数/全局预算: " + text,
                text.contains("自动派单 2026-10-22：派单 123 · 账号 2 · 全局预算 20/min"));
        assertTrue("跳过原因 Top3", text.contains("跳过TOP min_gap×900；no_due×12；busy×3"));
        assertTrue("每账号派单数", text.contains("每账号：" + FPID + "×80；57259770×43"));
        assertTrue("仍在 1500 内", text.length() <= DailyDigest.MAX_TEXT_CHARS);
    }

    @Test
    public void format_omitsSectionsWhenNoData() {
        DailyDigest.DigestData d = new DigestData0().build();
        String text = DailyDigest.format(d);
        assertFalse("无 cadence 数据 → 段省略", text.contains("自动派单"));
        assertFalse("无 guardian 数据 → 段省略", text.contains("守护盟友"));
    }

    @Test
    public void format_withBothSections_textCap() {
        DailyDigest.DigestData d = new DigestData0().build();
        d.cadence = DailyDigest.parseCadenceStats(cadenceStatsJson(999L,
                map("100001", 500L, "100002", 400L, "100003", 300L, "100004", 200L,
                        "100005", 100L),
                map("min_gap", 999L, "no_due", 99L, "busy", 9L), 20, 19, 0L), "2026-10-22");
        DailyDigest.GuardianDigest g = new DailyDigest.GuardianDigest();
        g.accounts = 3;
        g.dryRun = 2;
        g.launched = 1;
        g.launchFailed = 1;
        g.recalled = 1;
        g.lastTs = 0L;
        g.lastEvent = "dry_run";
        g.lastNote = "敌召回 → 将撤防（dry_run 不发请求）";
        d.guardian = g;
        for (int i = 0; i < 20; i++) {
            DailyDigest.RoleDigest r = new DailyDigest.RoleDigest(String.valueOf(100000 + i), "9" + i);
            r.runs = 10;
            r.claimed = 5;
            r.rewards.add("很长的奖励摘要".repeat(5));
            d.roles.add(r);
        }
        d.roleCount = d.roles.size();
        String text = DailyDigest.format(d);
        assertTrue(text.length() <= DailyDigest.MAX_TEXT_CHARS);
        assertTrue(text.contains("自动派单"));
        assertTrue(text.contains("守护盟友"));
    }

    /** 便捷构造基准 DigestData（自然日窗口 + 在线 2）。 */
    private static final class DigestData0 {
        DailyDigest.DigestData build() {
            DailyDigest.DigestData d = new DailyDigest.DigestData();
            d.nowMillis = bj(2026, 10, 23, 9, 0);
            d.day = "2026-10-23";
            d.windowStartMillis = bj(2026, 10, 22, 0, 0);
            d.windowEndMillis = bj(2026, 10, 23, 0, 0);
            d.onlineAccounts = 2;
            return d;
        }
    }

    // ===================================================================
    // 2) 日报「守护盟友」段
    // ===================================================================

    @Test
    public void parseGuardianLedger_countsAndLatestReason() {
        String raw = ledgerJson(
                ledgerEntry(100L, "dry_run", "将撤防"),
                ledgerEntry(200L, "launched", "已驻防"),
                ledgerEntry(300L, "launch_failed", "码42"),
                ledgerEntry(400L, "recalled", "敌方主力撤防"),
                ledgerEntry(500L, "launch_failed", "队列无空位"));
        DailyDigest.GuardianDigest g = DailyDigest.parseGuardianLedger(raw);
        assertNotNull(g);
        assertEquals(5, g.ledgerEntries);
        assertEquals(1, g.dryRun);
        assertEquals(1, g.launched);
        assertEquals(2, g.launchFailed);
        assertEquals(1, g.recalled);
        assertEquals("最近一条按 ts 最大", 500L, g.lastTs);
        assertEquals("launch_failed", g.lastEvent);
        assertEquals("队列无空位", g.lastNote);

        assertNull(DailyDigest.parseGuardianLedger(null));
        assertNull("空数组 → null 段省略", DailyDigest.parseGuardianLedger("[]"));
        assertNull("坏 JSON → null", DailyDigest.parseGuardianLedger("{bad"));
    }

    @Test
    public void parseGuardianMod_fallbackAndAccountKey() {
        DailyDigest.GuardianDigest g = DailyDigest.parseGuardianMod(
                new JSONObject().put("last", "[DRY] 敌召回").put("last_ts", 1234L).toString());
        assertNotNull(g);
        assertEquals(1234L, g.lastTs);
        assertEquals("state", g.lastEvent);
        assertEquals("[DRY] 敌召回", g.lastNote);
        assertNull(DailyDigest.parseGuardianMod("{}"));
        assertNull(DailyDigest.parseGuardianMod(null));

        assertEquals(FPID + ":888",
                DailyDigest.guardianAccountKey("srv:guardian:" + FPID + ":888:ledger"));
        assertEquals(FPID + ":888",
                DailyDigest.guardianAccountKey("mod:" + FPID + ":888:guardian"));
        assertNull(DailyDigest.guardianAccountKey("other:key"));
    }

    @Test
    public void collectNaturalDay_guardianLedgerAndModFallback() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        store.putString("srv:guardian:" + FPID + ":" + SID + ":ledger",
                ledgerJson(ledgerEntry(1000L, "dry_run", "A"), ledgerEntry(2000L, "launched", "B")));
        // 同账号也有 mod：不应重复计数（ledger 优先）
        store.putString("mod:" + FPID + ":" + SID + ":guardian",
                new JSONObject().put("last", "旧状态").put("last_ts", 3000L).toString());
        // 另一个账号只有 mod → 兜底计入
        store.putString("mod:57259770:724:guardian",
                new JSONObject().put("last", "[DRY] 敌召回 → 将撤防").put("last_ts", 5000L).toString());

        DailyDigest.DigestData d = DailyDigest.collectNaturalDay(store, now, 1);
        assertNotNull(d.guardian);
        assertEquals("两个账号（fpid:sid 去重）", 2, d.guardian.accounts);
        assertEquals(1, d.guardian.dryRun);
        assertEquals(1, d.guardian.launched);
        assertEquals("最近原因取最晚 ts（mod 兜底 B 账号）", 5000L, d.guardian.lastTs);
        assertEquals("state", d.guardian.lastEvent);
        assertTrue(d.guardian.lastNote.contains("将撤防"));
    }

    @Test
    public void format_rendersGuardianSection() {
        DailyDigest.DigestData d = new DigestData0().build();
        DailyDigest.GuardianDigest g = new DailyDigest.GuardianDigest();
        g.accounts = 2;
        g.dryRun = 2;
        g.launched = 1;
        g.launchFailed = 1;
        g.recalled = 1;
        g.lastEvent = "dry_run";
        g.lastNote = "敌召回 → 将撤防（dry_run 不发请求）";
        d.guardian = g;
        String text = DailyDigest.format(d);
        assertTrue("dry_run/实发/失败/撤防: " + text,
                text.contains("守护盟友：dry_run 2 · 实发 1（失败 1） · 撤防 1"));
        assertTrue("最近一次原因", text.contains("最近 dry_run：敌召回"));
        assertTrue(text.length() <= DailyDigest.MAX_TEXT_CHARS);
    }

    // ===================================================================
    // 3) 健康告警：派单停滞（30 分钟窗口）
    // ===================================================================

    @Test
    public void dispatchStalled_windowsAndBaseline() {
        long now = 1_800_000_000_000L;
        long m30 = HealthMonitor.CADENCE_STALL_MILLIS;
        // 无在线账号：一律不判
        assertFalse(HealthMonitor.dispatchStalled(false, 0L, now - m30, -1L, 0L, now));
        // 当日 0 派单：统计起点已 ≥30 分钟 → 停滞
        assertTrue(HealthMonitor.dispatchStalled(true, 0L, now - m30, -1L, 0L, now));
        // 29 分钟 → 未到阈值
        assertFalse(HealthMonitor.dispatchStalled(true, 0L, now - m30 + 1L, -1L, 0L, now));
        // firstAt 未知：退化为首次观测时刻
        assertTrue(HealthMonitor.dispatchStalled(true, 0L, 0L, -1L, now - m30 - 1L, now));
        assertFalse(HealthMonitor.dispatchStalled(true, 0L, 0L, -1L, 0L, now));
        // 有派单：首拍只建基线，不告警
        assertFalse(HealthMonitor.dispatchStalled(true, 5L, 0L, -1L, now - m30, now));
        // 基线后 dispatch 未增长且 ≥30 分钟 → 停滞
        assertTrue(HealthMonitor.dispatchStalled(true, 5L, 0L, 5L, now - m30, now));
        assertFalse(HealthMonitor.dispatchStalled(true, 5L, 0L, 5L, now - m30 + 1L, now));
        // dispatch 有增长 → 不判
        assertFalse(HealthMonitor.dispatchStalled(true, 6L, 0L, 5L, now - m30 - 1L, now));
        // 增长时刻未知 → 不判
        assertFalse(HealthMonitor.dispatchStalled(true, 5L, 0L, 5L, 0L, now));
    }

    @Test
    public void budgetSaturated_percentBoundaries() {
        // globalMax=20 → 95% = 19（整数比较：19*100 >= 20*95）
        assertTrue(HealthMonitor.budgetSaturated(19, 20));
        assertFalse(HealthMonitor.budgetSaturated(18, 20));
        // globalMax=7 → 6.65 → 7 才达标（6 不达标）
        assertTrue(HealthMonitor.budgetSaturated(7, 7));
        assertFalse(HealthMonitor.budgetSaturated(6, 7));
        // 0=不限 / 非法
        assertFalse(HealthMonitor.budgetSaturated(100, 0));
        assertFalse(HealthMonitor.budgetSaturated(-1, 20));
        // 超过上限也算打满
        assertTrue(HealthMonitor.budgetSaturated(25, 20));
    }

    @Test
    public void budgetSustained_fifteenMinutes() {
        long now = 1_800_000_000_000L;
        long m15 = HealthMonitor.BUDGET_SATURATED_MILLIS;
        assertTrue("持续满 15 分钟", HealthMonitor.budgetSustained(now - m15, now));
        assertFalse("未满 15 分钟", HealthMonitor.budgetSustained(now - m15 + 1L, now));
        assertFalse("无起始点", HealthMonitor.budgetSustained(0L, now));
        assertFalse("未来时刻（时钟回拨）", HealthMonitor.budgetSustained(now + 1L, now));
    }

    @Test
    public void anyOnline_andReadCadenceStat() {
        assertTrue(HealthMonitor.anyOnline(() -> status(FPID, "online")));
        assertFalse(HealthMonitor.anyOnline(() -> status(FPID, "offline")));
        assertFalse(HealthMonitor.anyOnline(() -> new ArrayList<>()));
        assertFalse(HealthMonitor.anyOnline(null));

        StateStore store = store();
        long now = bj(2026, 10, 23, 12, 0);
        assertNull("无 stats 键 → null", HealthMonitor.readCadenceStat(store, now));
        store.putString("srv:cad:stats:" + day(now),
                cadenceStatsJson(42L, map(FPID, 42L), map(), 20, 19, now - 3600_000L));
        HealthMonitor.CadenceStat stat = HealthMonitor.readCadenceStat(store, now);
        assertNotNull(stat);
        assertEquals(42L, stat.dispatch);
        assertEquals(20, stat.globalMax);
        assertEquals(19, stat.minuteDispatch);
        assertNull(HealthMonitor.parseCadenceStat("{bad"));
        assertNull(HealthMonitor.parseCadenceStat(null));
    }

    @Test
    public void cadenceStallAlert_shapeAndThrottleKind() {
        long now = bj(2026, 10, 23, 12, 0);
        HealthMonitor.Alert a = HealthMonitor.cadenceStallAlert(0L, now);
        assertEquals(HealthMonitor.KIND_CADENCE_STALL, a.kind);
        assertEquals("全局", a.account);
        assertTrue(a.title.contains("派单停滞"));
        assertTrue(a.metric.contains("30 分钟无派单"));
        String text = HealthMonitor.format(a);
        assertTrue(text.startsWith("【自动化告警】派单停滞"));
        assertEquals("srv:alert:cadence_stall:global:2026-10-23",
                HealthMonitor.throttleKey(HealthMonitor.KIND_CADENCE_STALL, "global", now));
    }

    // ===================================================================
    // 4) runOnce 端到端（停滞告警 + 6h 节流；无在线不误报）
    // ===================================================================

    @Test
    public void runOnce_stallAlertFiresOncePerThrottleWindow() {
        long now = bj(2026, 10, 23, 12, 0);
        StateStore store = store();
        // 在线 + 近 1min 有 CLAIMED + 有节奏键 → 只可能命中“派单停滞”
        store.putString("log:" + FPID + ":" + SID + ":login_rewards",
                "[{\"ts\":" + (now - 60_000L) + ",\"cat\":\"CLAIMED\",\"reason\":\"领取\",\"detail\":\"钻石×1\"}]");
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", now + 60_000L);
        // 当日 0 派单，统计起点 40 分钟前 → 停滞
        store.putString("srv:cad:stats:" + day(now), cadenceStatsJson(0L, map(),
                map("offline", 5L), 20, 0, now - 40L * 60_000L));

        CountingNotifier notifier = new CountingNotifier();
        HealthMonitor monitor = new HealthMonitor(vault(FPID), store, notifier,
                () -> status(FPID, "online"));
        assertEquals("派单停滞告警", 1, monitor.runOnce(now));
        assertNotNull(store.snapshot().get("srv:alert:cadence_stall:global:2026-10-23"));
        // 6h 内同类节流 → 0
        assertEquals(0, monitor.runOnce(now + 5L * 60_000L));
        assertEquals("无 webhook 不推送", 0, notifier.sends);
    }

    @Test
    public void runOnce_noStallAlertWhenNoOnlineAccount() {
        long now = bj(2026, 10, 23, 12, 0);
        StateStore store = store();
        store.putString("srv:cad:stats:" + day(now), cadenceStatsJson(0L, map(),
                map("offline", 5L), 20, 0, now - 40L * 60_000L));
        HealthMonitor monitor = new HealthMonitor(vault(FPID), store, new CountingNotifier(),
                () -> status(FPID, "offline"));
        // 首拍只记录离线起点；无在线账号 → 不产生派单停滞告警
        assertEquals(0, monitor.runOnce(now));
        assertNull(store.snapshot().get("srv:alert:cadence_stall:global:2026-10-23"));
    }
}
