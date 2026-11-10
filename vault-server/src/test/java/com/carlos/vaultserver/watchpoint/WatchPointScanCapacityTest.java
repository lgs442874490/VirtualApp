package com.carlos.vaultserver.watchpoint;

import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 观察点多会话容量回归（2026-11-10）：
 *
 * <ul>
 *   <li>扫描预算随在线会话数放大（每会话 4 块/tick，与改造前单会话密度一致），单 tick 上限 32 块；</li>
 *   <li>令牌桶按会话 uid 独立（每会话 maxReqPerSec、容量 10、初始 5），会话增减/乱序不串桶；</li>
 *   <li>扫描期间监视/保护不再停摆（{@link WatchPointEngine#tick} 先 poll 再 scan）；</li>
 *   <li>某会话令牌耗尽只跳过它负责的目标，不拖累其它会话；</li>
 *   <li>单会话行为与此前一致（扫描 4 块/tick、全图 484 块完整发出）。</li>
 * </ul>
 */
public class WatchPointScanCapacityTest {

    private static final long KID = 888L;
    private static final long UID_BASE = 9000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 只记录发送的假引擎（同 WatchPointMultiAccountTest 写法）。 */
    private static final class FakeEngine extends WjdrEngine {
        final long uid;
        final List<Integer> sessions = new ArrayList<>();

        FakeEngine(long uid) {
            super(null);
            this.uid = uid;
        }

        @Override public String sessionState() { return STATE_ONLINE; }
        @Override public String lastFpid() { return "f1"; }
        @Override public long lastServerId() { return KID; }
        @Override public long lastUid() { return uid; }

        @Override public void sendFireAndForget(String proto, Map<String, Object> args, int session) {
            sessions.add(session);
        }
    }

    private WatchPointStore store(int monitorTargets) {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.enabled = true;
        s.kid = KID;
        s.fpid = "";
        s.allAccounts = true;
        s.multiRole = true;
        s.wecomEnabled = false;
        s.monitorTargets = new ArrayList<>();
        s.protectRects = new ArrayList<>();
        for (int i = 0; i < monitorTargets; i++) {
            s.monitorTargets.add(new WatchPointStore.Target(7000L + i, "t" + i, 100 + i, 100 + i));
        }
        return s;
    }

    private static List<WjdrEngine> engines(int n) {
        List<WjdrEngine> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new FakeEngine(UID_BASE + i));
        return out;
    }

    private static int totalSends(List<WjdrEngine> es) {
        int total = 0;
        for (WjdrEngine e : es) total += ((FakeEngine) e).sessions.size();
        return total;
    }

    /** A：2 个会话时单 tick 扫 8 块（每会话 4 块），而不是固定 4 块。 */
    @Test
    public void scanBudgetScalesWithSessions() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        List<WjdrEngine> es = engines(2);
        engine.startScanForTest("capacity");
        engine.scanTickForTest(es);
        assertEquals("2 会话单 tick 应发 8 块", 8, totalSends(es));
        assertEquals("会话0 分摊 4 块", 4, ((FakeEngine) es.get(0)).sessions.size());
        assertEquals("会话1 分摊 4 块", 4, ((FakeEngine) es.get(1)).sessions.size());
    }

    /** B：会话很多时单 tick 受 32 块上限保护（10 会话 × 4 = 40 → 32）。 */
    @Test
    public void scanBudgetCappedAt32() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        List<WjdrEngine> es = engines(10);
        engine.startScanForTest("cap");
        engine.scanTickForTest(es);
        assertEquals("单 tick 扫描块上限 32", 32, totalSends(es));
    }

    /** C：令牌桶按 uid 独立——初始各 5 个，总量 10（全局单桶时只有 5）。 */
    @Test
    public void tokenBucketsPerSessionIndependent() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        long now = System.currentTimeMillis();
        int taken0 = 0, taken1 = 0;
        for (int i = 0; i < 10; i++) {
            if (engine.takeTokenForTest(UID_BASE, now)) taken0++;
            if (engine.takeTokenForTest(UID_BASE + 1, now)) taken1++;
        }
        assertEquals("会话0 初始 5 令牌", 5, taken0);
        assertEquals("会话1 初始 5 令牌", 5, taken1);
        assertEquals("两个独立桶", 2, engine.tokenBucketCountForTest());
    }

    /** C2：一个会话耗尽不影响另一个；每会话按 maxReqPerSec 独立恢复。 */
    @Test
    public void tokenRefillIsPerSession() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        long now = System.currentTimeMillis();
        while (engine.takeTokenForTest(UID_BASE, now)) {
            // 耗尽会话0 的初始 5 个
        }
        assertFalse("会话0 已耗尽", engine.takeTokenForTest(UID_BASE, now));
        assertTrue("会话1 不受影响", engine.takeTokenForTest(UID_BASE + 1, now));
        long later = now + 1000L;
        assertTrue("1 秒后会话0 恢复第 1 个", engine.takeTokenForTest(UID_BASE, later));
        assertTrue("1 秒后会话0 恢复第 2 个", engine.takeTokenForTest(UID_BASE, later));
        assertFalse("1 秒只恢复 maxReqPerSec=2.5 个，第 3 个取不到", engine.takeTokenForTest(UID_BASE, later));
    }

    /** C3：会话下线后 60s prune 清理其桶，在线会话桶保留（按 uid 绑定，不会串桶）。 */
    @Test
    public void pruneDropsOfflineBuckets() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        long now = System.currentTimeMillis();
        engine.takeTokenForTest(UID_BASE, now);
        engine.takeTokenForTest(UID_BASE + 7, now);
        assertEquals(2, engine.tokenBucketCountForTest());
        engine.pruneTokenBucketsForTest(engines(1), now);
        assertEquals("只保留在线会话 9000", 1, engine.tokenBucketCountForTest());
    }

    /** D1：多会话下监视目标按 i % n 落到各自会话。 */
    @Test
    public void pollDistributesTargetsBySession() {
        WatchPointEngine engine = new WatchPointEngine(null, store(2), null, null);
        List<WjdrEngine> es = engines(2);
        engine.pollTickForTest(es);
        assertEquals("目标0 走会话0", 1, ((FakeEngine) es.get(0)).sessions.size());
        assertEquals("目标1 走会话1", 1, ((FakeEngine) es.get(1)).sessions.size());
    }

    /** D2：某会话令牌耗尽只跳过它负责的目标，不拖累其它会话（continue 语义）。 */
    @Test
    public void exhaustedSessionDoesNotBlockOthers() {
        WatchPointEngine engine = new WatchPointEngine(null, store(2), null, null);
        List<WjdrEngine> es = engines(2);
        long now = System.currentTimeMillis();
        while (engine.takeTokenForTest(UID_BASE, now)) {
            // 耗尽会话0
        }
        engine.pollTickForTest(es);
        assertEquals("会话0 令牌耗尽：目标0 不发", 0, ((FakeEngine) es.get(0)).sessions.size());
        assertEquals("会话1 不受影响：目标1 照发", 1, ((FakeEngine) es.get(1)).sessions.size());
    }

    /** D3：扫描期间监视 + 保护并行发送，不再停摆。 */
    @Test
    public void pollAndScanBothRunWhenScanning() {
        WatchPointStore s = store(1);
        s.protectRects.add(new WatchPointStore.Rect("p", 100, 100, 200, 200));
        WatchPointEngine engine = new WatchPointEngine(null, s, null, null);
        List<WjdrEngine> es = engines(1);
        engine.startScanForTest("capacity");
        assertTrue(engine.scanActiveForTest());
        engine.tick(es);
        FakeEngine fe = (FakeEngine) es.get(0);
        // 1 监视 + 1 保护 + 4 扫描
        assertEquals("扫描期间监视/保护仍发送", 6, fe.sessions.size());
        assertTrue("包含监视 session", fe.sessions.contains(100_000_000));
        assertTrue("包含保护 session", fe.sessions.contains(100_000_100));
        assertTrue("包含扫描第 0 块", fe.sessions.contains(100_500_000));
    }

    /** E：单会话行为与改造前一致（扫描 4 块/tick）。 */
    @Test
    public void singleSessionScanUnchanged() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        List<WjdrEngine> es = engines(1);
        engine.startScanForTest("single");
        engine.scanTickForTest(es);
        assertEquals("单会话仍 4 块/tick", 4, ((FakeEngine) es.get(0)).sessions.size());
    }

    /** F：单会话全图一轮 484 块完整发出（预算放大后边界不丢块/不重复）。 */
    @Test
    public void fullScanSendsAllTilesSingleSession() {
        WatchPointEngine engine = new WatchPointEngine(null, store(0), null, null);
        List<WjdrEngine> es = engines(1);
        engine.startScanForTest("full");
        for (int i = 0; i < 200; i++) engine.scanTickForTest(es);
        assertEquals("全图 484 块应全部发出", 484, ((FakeEngine) es.get(0)).sessions.size());
    }
}
