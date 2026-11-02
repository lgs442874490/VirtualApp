package com.carlos.vaultserver.watchpoint;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 侦查限流回归（2026-11-02 GLM-5.3 审查致命项）：
 * 监视轮询每 4s 重放同一条在途行军，旧实现每次重放都扣配额 → 一条侦查 12 秒就把
 * 「每人 5 分钟 3 条」刷光，真实侦查被静默。修复后同一条行军（uid+marchId）只记一次配额。
 */
public class WatchPointScoutReplayTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private WatchPointStore store() {
        return new WatchPointStore(Paths.get("/tmp"));
    }

    @Test
    public void sameMarchReplayedOnlyCountsOnce() {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        long now = System.currentTimeMillis();
        long uid = 31374667L;
        long marchId = 1234567L;
        // 第一次：需要做配额判定（true）
        assertTrue(e.scoutCountOnce(uid, marchId, now));
        // 后续 4 次都是同一行军的轮询重放：不再计配额
        for (int i = 1; i <= 4; i++) {
            assertFalse("第 " + i + " 次重放不应计配额", e.scoutCountOnce(uid, marchId, now + i * 4000L));
        }
        // 重新出现的同一 marchId（可能 id 复用）：超过窗口后再计
        assertTrue(e.scoutCountOnce(uid, marchId, now + WatchPointEngine.SCOUT_WINDOW_MS + 1L));
    }

    @Test
    public void differentMarchesEachCount() {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        long now = System.currentTimeMillis();
        long uid = 16253662L;
        assertTrue(e.scoutCountOnce(uid, 1L, now));
        assertTrue(e.scoutCountOnce(uid, 2L, now + 1000L));
        assertTrue(e.scoutCountOnce(uid, 3L, now + 2000L));
    }

    /** 纯函数口径：同一个人第 4 条真侦查被限流，前 3 条放行。 */
    @Test
    public void quotaAllowsThreePerWindow() {
        Map<Long, java.util.ArrayDeque<Long>> map = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        long uid = 29180054L;
        assertTrue(WatchPointEngine.scoutQuotaAllow(map, uid, now, WatchPointEngine.SCOUT_WINDOW_MS, 3));
        assertTrue(WatchPointEngine.scoutQuotaAllow(map, uid, now + 1000L, WatchPointEngine.SCOUT_WINDOW_MS, 3));
        assertTrue(WatchPointEngine.scoutQuotaAllow(map, uid, now + 2000L, WatchPointEngine.SCOUT_WINDOW_MS, 3));
        assertFalse(WatchPointEngine.scoutQuotaAllow(map, uid, now + 3000L, WatchPointEngine.SCOUT_WINDOW_MS, 3));
        // 窗口滑过后恢复
        assertTrue(WatchPointEngine.scoutQuotaAllow(map, uid,
                now + WatchPointEngine.SCOUT_WINDOW_MS + 5000L, WatchPointEngine.SCOUT_WINDOW_MS, 3));
        assertEquals(1, map.size());
    }
}
