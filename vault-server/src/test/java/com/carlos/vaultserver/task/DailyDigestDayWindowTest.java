package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次6：日报自然日窗口 + 在线数竞态 + digest 历史清理 + StateStore 删除 API 单测（2026-10-23）。
 *
 * <p>验收口径：
 * <ul>
 *   <li>自然日窗口「昨日 00:00-24:00（北京时间）」纯函数 {@link DailyDigest#naturalDayWindow(long)}
 *       与聚合 {@link DailyDigest#collectNaturalDay(StateStore, long, int)}（右开区间、跨零点/跨月边界）；</li>
 *   <li>在线数注入 {@link DailyDigest#sampleOnlineAccounts(DailyDigest.Waiter)}：0 值等待 30s 重采一次、
 *       仍 0 时文案标注采样时点；公开 runOnce 不等待（快速返回）；</li>
 *   <li>digest 历史清理 {@link DailyDigest#expiredDigestKeys}/{@link DailyDigest#purgeOldDigests}：
 *       保留最近 30 个自然日、非法键不误删；</li>
 *   <li>{@link StateStore#remove(String)}/{@link StateStore#removePrefix(String)} 线程安全删除；</li>
 *   <li>文本上限 1500 字符保持（自然日窗口行/采样标注不破上限）。</li>
 * </ul>
 */
public class DailyDigestDayWindowTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        System.clearProperty(DailyDigest.ENV_ENABLED);
        System.clearProperty(DailyDigest.ENV_TIME);
        ServerNotifyHistory.clearAll();
    }

    @After
    public void tearDown() {
        System.clearProperty(DailyDigest.ENV_ENABLED);
        System.clearProperty(DailyDigest.ENV_TIME);
        ServerNotifyHistory.clearAll();
    }

    // ---------------- helpers ----------------

    private static long bj(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.of("GMT+8")).toInstant().toEpochMilli();
    }

    private static String log(String fpid, long sid, String fn) {
        return "log:" + fpid + ":" + sid + ":" + fn;
    }

    private StateStore store() {
        return new StateStore(tmp.getRoot().toPath());
    }

    /** 计数器等待器（不真实 sleep，避免拖慢测试）。 */
    private static final class CountingWaiter implements DailyDigest.Waiter {
        int calls;
        long lastMillis;

        @Override
        public void await(long millis) {
            calls++;
            lastMillis = millis;
        }
    }

    private static DailyDigest digest(StateStore store, com.carlos.vaultserver.WeComNotifier notifier,
                                      java.util.function.IntSupplier online) {
        return new DailyDigest(null, store, notifier, online);
    }

    // ===================================================================
    // 1) 自然日窗口（跨零点/跨月）纯函数
    // ===================================================================

    @Test
    public void naturalDayWindow_0910_isYesterdayFullDay() {
        long now = bj(2026, 10, 23, 9, 10);
        long[] win = DailyDigest.naturalDayWindow(now);
        assertEquals("窗口起点=昨日 00:00", bj(2026, 10, 22, 0, 0), win[0]);
        assertEquals("窗口终点=今日 00:00（右开区间）", bj(2026, 10, 23, 0, 0), win[1]);
        assertEquals(24L * 3600_000L, win[1] - win[0]);
        assertEquals("dayStartMillis 与窗口终点一致", win[1], DailyDigest.dayStartMillis(now));
    }

    @Test
    public void naturalDayWindow_sameWindowAllDayAndAcrossMidnight() {
        long expected0 = bj(2026, 10, 22, 0, 0);
        long expected1 = bj(2026, 10, 23, 0, 0);
        // 当天 00:00:00 整点：仍统计昨日
        long[] atMidnight = DailyDigest.naturalDayWindow(bj(2026, 10, 23, 0, 0));
        assertEquals(expected0, atMidnight[0]);
        assertEquals(expected1, atMidnight[1]);
        // 当天最后一分钟
        long nearEnd = LocalDateTime.of(2026, 10, 23, 23, 59, 59)
                .atZone(ZoneId.of("GMT+8")).toInstant().toEpochMilli();
        long[] atEnd = DailyDigest.naturalDayWindow(nearEnd);
        assertEquals(expected0, atEnd[0]);
        assertEquals(expected1, atEnd[1]);
        // 跨月：11-01 09:00 → 10-31 全天
        long[] acrossMonth = DailyDigest.naturalDayWindow(bj(2026, 11, 1, 9, 0));
        assertEquals(bj(2026, 10, 31, 0, 0), acrossMonth[0]);
        assertEquals(bj(2026, 11, 1, 0, 0), acrossMonth[1]);
    }

    // ===================================================================
    // 2) 自然日聚合（右开区间、边界条目）
    // ===================================================================

    @Test
    public void collectNaturalDay_includesOnlyYesterday() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        long twoDaysAgo = bj(2026, 10, 21, 12, 0);
        long yesterdayStart = bj(2026, 10, 22, 0, 0);
        long yesterdayNoon = bj(2026, 10, 22, 12, 30);
        long yesterdayLastMs = bj(2026, 10, 23, 0, 0) - 1L;
        long todayStart = bj(2026, 10, 23, 0, 0);
        long todayMorning = bj(2026, 10, 23, 8, 0);
        String entries = "["
                + "{\"ts\":" + twoDaysAgo + ",\"cat\":\"CLAIMED\",\"reason\":\"前天\",\"detail\":\"前天奖励\"},"
                + "{\"ts\":" + yesterdayStart + ",\"cat\":\"CLAIMED\",\"reason\":\"昨日0点\",\"detail\":\"边界奖励\"},"
                + "{\"ts\":" + yesterdayNoon + ",\"cat\":\"SKIPPED\",\"reason\":\"活动未开启\",\"detail\":\"\"},"
                + "{\"ts\":" + yesterdayLastMs + ",\"cat\":\"ERROR\",\"reason\":\"码42\",\"detail\":\"\"},"
                + "{\"ts\":" + todayStart + ",\"cat\":\"CLAIMED\",\"reason\":\"今日0点\",\"detail\":\"应排除\"},"
                + "{\"ts\":" + todayMorning + ",\"cat\":\"CLAIMED\",\"reason\":\"今日早\",\"detail\":\"应排除\"}"
                + "]";
        store.putString(log(FPID, SID, "mail"), entries);
        store.putLong("srv:cad:" + FPID + ":" + SID + ":mail", now);

        DailyDigest.DigestData d = DailyDigest.collectNaturalDay(store, now, 2);
        assertEquals(1, d.roleCount);
        DailyDigest.RoleDigest r = d.roles.get(0);
        assertEquals("只统计昨日 3 条（00:00 含、24:00 前 1ms 含）", 3, r.runs);
        assertEquals(1, r.claimed);
        assertEquals(1, r.skipped);
        assertEquals(1, r.failed);
        assertTrue(r.rewards.contains("边界奖励"));
        assertEquals(Integer.valueOf(1), r.skipReasons.get("活动未开启"));
        // 窗口字段供文案展示
        assertEquals(yesterdayStart, d.windowStartMillis);
        assertEquals(todayStart, d.windowEndMillis);
        assertEquals("2026-10-23", d.day);
    }

    @Test
    public void collect_rollingWindowStillCompatible() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        String entries = "["
                + "{\"ts\":" + (now - 2L * 3600_000L) + ",\"cat\":\"CLAIMED\",\"reason\":\"2h前\",\"detail\":\"今晨\"},"
                + "{\"ts\":" + (now - 25L * 3600_000L) + ",\"cat\":\"CLAIMED\",\"reason\":\"25h前\",\"detail\":\"窗口外\"}"
                + "]";
        store.putString(log(FPID, SID, "supply"), entries);
        // 批次5 滚动 24h 口径保留（兼容既有调用/测试）
        DailyDigest.DigestData rolling = DailyDigest.collect(store, now,
                DailyDigest.WINDOW_MILLIS, -1);
        assertEquals(1, rolling.roles.get(0).runs);
        // 自然日窗口（昨日 00:00-24:00）只含 25h 前那条（落在昨日 08:00），今日 07:00 那条不含
        DailyDigest.DigestData natural = DailyDigest.collectNaturalDay(store, now, -1);
        assertEquals(1, natural.roles.size());
        assertEquals(1, natural.roles.get(0).runs);
    }

    // ===================================================================
    // 3) 文案：窗口行 + 0 在线采样标注 + 上限
    // ===================================================================

    @Test
    public void format_showsNaturalDayWindowAndZeroOnlineSampleNote() {
        DailyDigest.DigestData d = new DailyDigest.DigestData();
        d.nowMillis = bj(2026, 10, 23, 9, 0);
        d.day = "2026-10-23";
        d.windowStartMillis = bj(2026, 10, 22, 0, 0);
        d.windowEndMillis = bj(2026, 10, 23, 0, 0);
        d.onlineAccounts = 0;
        d.onlineSampledAtMillis = bj(2026, 10, 23, 9, 0);
        String text = DailyDigest.format(d);
        assertTrue("需明示自然日统计窗口: " + text,
                text.contains("统计窗口 10-22 00:00 — 10-23 00:00（北京时间）"));
        assertTrue("0 在线需标注采样时点: " + text, text.contains("在线账号 0（采样 09:00"));
        assertTrue("无数据提示改成窗口口径", text.contains("统计窗口内无任务日志"));
        assertTrue(text.length() <= DailyDigest.MAX_TEXT_CHARS);

        // 在线 >0：不标注采样
        d.onlineAccounts = 2;
        String text2 = DailyDigest.format(d);
        assertTrue(text2.contains("在线账号 2 ·"));
        assertFalse(text2.contains("（采样"));

        // 兼容批次5 直接构造的 DigestData（无窗口字段）：不输出“统计窗口 …（北京时间）”区间行
        DailyDigest.DigestData legacy = new DailyDigest.DigestData();
        legacy.nowMillis = d.nowMillis;
        legacy.day = d.day;
        String text3 = DailyDigest.format(legacy);
        assertFalse(text3.contains("（北京时间）"));
    }

    @Test
    public void format_textCapHoldsWithWindowLine() {
        DailyDigest.DigestData d = new DailyDigest.DigestData();
        d.nowMillis = bj(2026, 10, 23, 9, 0);
        d.day = "2026-10-23";
        d.windowStartMillis = bj(2026, 10, 22, 0, 0);
        d.windowEndMillis = bj(2026, 10, 23, 0, 0);
        d.onlineAccounts = 0;
        d.onlineSampledAtMillis = d.nowMillis;
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
        assertTrue("增加窗口行/采样标注后仍必须 ≤1500",
                text.length() <= DailyDigest.MAX_TEXT_CHARS);
    }

    // ===================================================================
    // 4) 在线数注入与 0 值重采（竞态）
    // ===================================================================

    @Test
    public void sampleOnlineAccounts_positiveFirst_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        CountingWaiter waiter = new CountingWaiter();
        DailyDigest digest = digest(store(), null, () -> {
            calls.incrementAndGet();
            return 2;
        });
        assertEquals(2, digest.sampleOnlineAccounts(waiter));
        assertEquals(1, calls.get());
        assertEquals("首次>0 不等待", 0, waiter.calls);
    }

    @Test
    public void sampleOnlineAccounts_zeroThenPositive_retriesOnceWith30s() {
        AtomicInteger calls = new AtomicInteger();
        CountingWaiter waiter = new CountingWaiter();
        DailyDigest digest = digest(store(), null, () -> calls.getAndIncrement() == 0 ? 0 : 3);
        assertEquals("0 值重采一次后取新值", 3, digest.sampleOnlineAccounts(waiter));
        assertEquals(1, waiter.calls);
        assertEquals(30_000L, waiter.lastMillis);
        assertEquals(2, calls.get());
    }

    @Test
    public void sampleOnlineAccounts_unknownSupplierAndNoWaiter() {
        CountingWaiter waiter = new CountingWaiter();
        assertEquals("未注入 Supplier → -1 未知", -1,
                digest(store(), null, null).sampleOnlineAccounts(waiter));
        assertEquals(0, waiter.calls);

        AtomicInteger calls = new AtomicInteger();
        DailyDigest digest = digest(store(), null, () -> {
            calls.incrementAndGet();
            return 0;
        });
        assertEquals("waiter=null 不等待，仍为 0", 0, digest.sampleOnlineAccounts(null));
        assertEquals("只采一次", 1, calls.get());
    }

    @Test
    public void runOnce_zeroOnline_retriesAndWritesSampleNote() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        AtomicInteger calls = new AtomicInteger();
        CountingWaiter waiter = new CountingWaiter();
        DailyDigest digest = digest(store, null, () -> calls.getAndIncrement() == 0 ? 0 : 2);
        assertFalse("无 webhook → 仅落 state", digest.runOnce(now, waiter));
        assertEquals("生成日报时执行一次 0 值重采", 1, waiter.calls);
        String text = store.getString(DailyDigest.STATE_PREFIX + "2026-10-23");
        assertNotNull(text);
        assertTrue("重采后用新在线数: " + text, text.contains("在线账号 2"));
        assertTrue("自然日窗口行存在", text.contains("统计窗口 10-22 00:00 — 10-23 00:00"));
    }

    @Test
    public void runOnce_publicEntry_doesNotBlockOnZeroOnline() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        DailyDigest digest = digest(store, null, () -> 0);
        long t0 = System.currentTimeMillis();
        assertFalse(digest.runOnce(now));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("公开入口不得真等 30s（测试/手动触发快速返回），实际 " + elapsed + "ms", elapsed < 5_000L);
        String text = store.getString(DailyDigest.STATE_PREFIX + "2026-10-23");
        assertNotNull(text);
        assertTrue("0 在线标注采样时点", text.contains("（采样 "));
    }

    // ===================================================================
    // 5) digest 历史清理（保留最近 30 份）
    // ===================================================================

    @Test
    public void expiredDigestKeys_keepsRecent30Days_andIgnoresInvalidKeys() {
        long now = bj(2026, 10, 23, 9, 0);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            keys.add(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now - i * 24L * 3600_000L));
        }
        keys.add(DailyDigest.STATE_PREFIX + "bad");
        keys.add("other:key");
        keys.add(null);
        List<String> expired = DailyDigest.expiredDigestKeys(keys, now);
        // 保留 today-0..today-29 共 30 份；today-40..today-30 共 11 份需清理
        assertEquals(11, expired.size());
        assertTrue(expired.contains(DailyDigest.STATE_PREFIX
                + DailyDigest.dayKey(now - 30L * 24L * 3600_000L)));
        assertFalse("最近 30 天内不得删除", expired.contains(DailyDigest.STATE_PREFIX
                + DailyDigest.dayKey(now - 29L * 24L * 3600_000L)));
        assertFalse("非法键不误删", expired.contains(DailyDigest.STATE_PREFIX + "bad"));
        assertFalse(expired.contains("other:key"));
    }

    @Test
    public void purgeOldDigests_keepsLast30Entries() {
        StateStore store = store();
        long now = bj(2026, 10, 23, 9, 0);
        for (int i = 0; i <= 35; i++) {
            store.putString(DailyDigest.STATE_PREFIX
                    + DailyDigest.dayKey(now - i * 24L * 3600_000L), "日报" + i);
        }
        store.putString("log:" + FPID + ":" + SID + ":mail", "[]");
        store.putString("srv:digest:not-a-date", "保留");
        int removed = DailyDigest.purgeOldDigests(store, now);
        assertEquals("35+1 份里删 6 份（保留含今天 30 个自然日）", 6, removed);
        Map<String, Object> snap = store.snapshot();
        int kept = 0;
        for (String k : snap.keySet()) {
            if (k.startsWith(DailyDigest.STATE_PREFIX)
                    && k.substring(DailyDigest.STATE_PREFIX.length()).matches("\\d{4}-\\d{2}-\\d{2}")) {
                kept++;
            }
        }
        assertEquals(30, kept);
        assertNull(store.getString(DailyDigest.STATE_PREFIX
                + DailyDigest.dayKey(now - 35L * 24L * 3600_000L)));
        assertNotNull(store.getString(DailyDigest.STATE_PREFIX + DailyDigest.dayKey(now)));
        assertNotNull("非 digest 键不受影响", store.getString("log:" + FPID + ":" + SID + ":mail"));
        assertNotNull("非法日期 digest 键不误删", store.getString("srv:digest:not-a-date"));
        assertEquals(0, DailyDigest.purgeOldDigests(null, now));
    }

    // ===================================================================
    // 6) StateStore remove / removePrefix（批次6 新增小 API）
    // ===================================================================

    @Test
    public void stateStore_removeAndRemovePrefix() {
        StateStore store = store();
        store.putString("srv:digest:2026-10-20", "a");
        store.putString("srv:digest:2026-10-21", "b");
        store.putString("srv:digest:bad", "c");
        store.putString("log:1:2:mail", "[]");

        store.remove("srv:digest:2026-10-20");
        assertNull(store.getString("srv:digest:2026-10-20"));
        assertNotNull(store.getString("srv:digest:2026-10-21"));
        store.remove("not-exists");            // 不存在：静默
        store.remove(null);                    // null：静默

        assertEquals("删除全部 srv:digest: 前缀（含非法日期键）", 2,
                store.removePrefix("srv:digest:"));
        assertNull(store.getString("srv:digest:2026-10-21"));
        assertNull(store.getString("srv:digest:bad"));
        assertNotNull("其它前缀不受影响", store.getString("log:1:2:mail"));
        assertEquals("空/null 前缀防误删，返回 0", 0, store.removePrefix(""));
        assertEquals(0, store.removePrefix(null));
        assertNotNull(store.getString("log:1:2:mail"));
        assertTrue("删除后 snapshot 仍可读", store.snapshot().containsKey("log:1:2:mail"));
    }

    @Test
    public void stateStore_removePrefix_emptyResultAndDirtyFlushSafe() throws Exception {
        StateStore store = store();
        assertEquals(0, store.removePrefix("srv:digest:"));
        store.putString("srv:digest:2026-10-22", "x");
        assertEquals(1, store.removePrefix("srv:digest:"));
        // 删除后 flush 不抛异常（标脏路径）
        store.flush();
        assertNotNull(Arrays.asList(store.snapshot()));
        assertTrue(new VaultStore(tmp.getRoot().toPath()).watchConfig() == null
                || true);   // 仅确保依赖类可加载，不涉及推送
    }
}
