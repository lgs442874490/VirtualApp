package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 状态保留（retention）回归（2026-10-30 批次 A）。
 *
 * <p>规则：{@code st:<day>:…} 保留最近 N 天（默认 7）；{@code srv:cad:stats:<day>} /
 * {@code srv:digest:<day>} 保留 30 天；只删可解析且早于截止日的键；其它键一律不动。
 */
public class StateRetentionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
    }

    /** 生成「北京自然日」相对 now 的日期串（-days 天）。 */
    private static String day(long now, int offsetDays) {
        return StateRetention.day(now + offsetDays * 86_400_000L);
    }

    @Test
    public void removesStaleDayStateAndStats_keepsFreshAndOthers() {
        long now = 1_793_400_000_000L; // 2026-10-30 附近
        String oldDay = day(now, -30);
        String edgeOld = day(now, -8);   // 超过 7 天保留期 → 应删
        String fresh = day(now, -1);
        store.putString("st:" + oldDay + ":48460403:888:35371154:mail", "2");
        store.putString("st:" + edgeOld + ":48460403:888:35371154:mail", "4");
        store.putString("st:" + fresh + ":48460403:888:35371154:mail", "4");
        String statsOld = day(now, -31);
        store.putString("srv:cad:stats:" + statsOld, "{}");
        store.putString("srv:cad:stats:" + fresh, "{}");
        store.putString("srv:digest:" + day(now, -31), "{}");
        // 非按天键：不能动
        store.putString("mod:48460403:888:35371154:mail", "x");
        store.putString("st:not-a-day:48460403:888:35371154:mail", "x");
        store.putString("srv:cad:48460403:888:35371154:mail", "0");

        int removed = StateRetention.runOnce(store, now);
        assertEquals("应删除 4 个过期键（st 30 天前、st 8 天前、stats 31 天前、digest 31 天前）", 4, removed);
        assertNull(store.getString("st:" + oldDay + ":48460403:888:35371154:mail"));
        assertNull(store.getString("st:" + edgeOld + ":48460403:888:35371154:mail"));
        assertNull(store.getString("srv:cad:stats:" + statsOld));
        assertNull(store.getString("srv:digest:" + day(now, -31)));
        assertEquals("4", store.getString("st:" + fresh + ":48460403:888:35371154:mail"));
        assertEquals("{}", store.getString("srv:cad:stats:" + fresh));
        assertEquals("x", store.getString("mod:48460403:888:35371154:mail"));
        assertEquals("x", store.getString("st:not-a-day:48460403:888:35371154:mail"));
        assertEquals("0", store.getString("srv:cad:48460403:888:35371154:mail"));
    }

    @Test
    public void idempotentAndEmptySafe() {
        long now = 1_793_400_000_000L;
        assertEquals(0, StateRetention.runOnce(store, now));
        assertEquals(0L, StateRetention.runOnce(null, now));
    }

    @Test
    public void dayParserIsStrict() {
        assertTrue(StateRetention.isBefore("2026-10-01", "2026-10-23"));
        assertFalse(StateRetention.isBefore("2026-10-23", "2026-10-23"));
        assertFalse("非法日期保守不删", StateRetention.isBefore("not-a-day", "2026-10-23"));
        assertFalse(StateRetention.isBefore("2026-1-1", "2026-10-23"));
        assertFalse(StateRetention.isBefore(null, "2026-10-23"));
    }

    @Test
    public void retentionConfigIsBounded() {
        String old = System.getProperty(StateRetention.ENV_DAYS);
        try {
            System.setProperty(StateRetention.ENV_DAYS, "3");
            assertEquals(3, StateRetention.days());
            System.setProperty(StateRetention.ENV_DAYS, "0");
            assertEquals("0/负数回默认", StateRetention.DEFAULT_DAYS, StateRetention.days());
            System.setProperty(StateRetention.ENV_DAYS, "9999");
            assertEquals("上限 365 天", 365, StateRetention.days());
        } finally {
            if (old == null) System.clearProperty(StateRetention.ENV_DAYS);
            else System.setProperty(StateRetention.ENV_DAYS, old);
        }
    }
}
