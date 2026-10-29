package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 练兵档位口径修复 + 2106 自动降档单测（2026-10-28 生产 bug 回归）。
 *
 * <p>生产实证：旧 {@code barrackUnlockTiers} 用大熔炉(301)等级<b>覆盖</b>三个兵营等级，
 * 熔炉 13 级账号被推导成 T5(10500) 去训练，被游戏服 2106「该档未解锁」拒绝 → 采集后无训练队列，
 * App 显示"空闲（无训练队列数据）"。
 *
 * <p>修复基线（网页版 1:1）：
 * {@code DailyBarrackState.availableOptions:357-368}（每营用自己 321/322/323 的建筑等级）
 * + {@code DailyBarrackCatalog.minimumBarrackLevel}（BARRACK_LEVELS={1,4,7,11,13,16,19,22,26,30}）。
 *
 * <p>覆盖：①熔炉13/兵营11→T4；②三营不同等级各自推导；③兵营缺失回退熔炉并标 furnace-fallback；
 * ④2106 降档序列（≤3 次）；⑤cap 持久化生效 / 兵营升级后重置；⑥回放式集成（2106→降档成功→状态行/请求序列）。
 */
public class BarrackTierFixTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
    }

    // ==================== ① 熔炉 13 / 兵营 11 → T4（不是 T5） ====================

    @Test
    public void furnace13Barrack11_derivesT4_notT5() {
        // 生产现场：日志旧口径 "熔炉lv13 兵营[13,13,13] 口径=furnace" 实际是熔炉覆盖后的假值
        long[] raw = ServerTownTasks.parseBarrackBuildingLevels(map(
                FURNACE, map("bid", 301, "base", map("lv", 13)),
                SHIELD, map("bid", 321, "base", map("lv", 11)),
                SPEAR, map("bid", 322, "base", map("lv", 11)),
                BOW, map("bid", 323, "base", map("lv", 11))));
        assertEquals("解析出真实三营等级", 11L, raw[1]);
        assertEquals("解析出熔炉等级", 13L, raw[0]);

        String[] src = new String[1];
        int[] tiers = ServerTownTasks.barrackTiersFromLevels(raw, null, null, src);
        assertNotNull(tiers);
        assertEquals("兵营 11 级 → T4（BARRACK_LEVELS[3]=11）", 4, tiers[1]);
        assertEquals(4, tiers[2]);
        assertEquals(4, tiers[3]);
        assertFalse("绝不能再按熔炉 13 级推导成 T5", tiers[1] == 5);
        assertEquals("口径必须标 barrack（不是 furnace）", "barrack", src[0]);
    }

    @Test
    public void computeUnlockTiers_usesEachBarracksOwnLevel() {
        // 纯函数层再锁一次：等级 11 → T4；等级 13 → T5；等级 7 → T3；等级 1 → T1
        int[] tiers = ServerTownTasks.computeUnlockTiers(new long[]{0, 11, 13, 7}, null, null);
        assertEquals(4, tiers[1]);
        assertEquals(5, tiers[2]);
        assertEquals(3, tiers[3]);
        assertEquals(1, ServerTownTasks.computeUnlockTiers(new long[]{0, 1, 1, 1}, null, null)[1]);
        assertEquals("T10 需兵营 30 级", 10,
                ServerTownTasks.computeUnlockTiers(new long[]{0, 30, 30, 30}, null, null)[1]);
        assertEquals("等级不足 → 0（调用方写可读 SKIP）", 0,
                ServerTownTasks.computeUnlockTiers(new long[]{0, 0, 0, 0}, null, null)[1]);
    }

    // ==================== ② 三营不同等级各自推导 ====================

    @Test
    public void threeBarracksDifferentLevels_deriveIndependently() {
        long[] raw = ServerTownTasks.parseBarrackBuildingLevels(map(
                FURNACE, map("bid", 301, "base", map("lv", 13)),
                SHIELD, map("bid", 321, "base", map("lv", 13)),   // T5
                SPEAR, map("bid", 322, "base", map("lv", 11)),    // T4
                BOW, map("bid", 323, "base", map("lv", 7))));      // T3
        String[] src = new String[1];
        int[] tiers = ServerTownTasks.barrackTiersFromLevels(raw, null, null, src);
        assertNotNull(tiers);
        assertEquals(5, tiers[1]);
        assertEquals(4, tiers[2]);
        assertEquals(3, tiers[3]);
        assertEquals("barrack", src[0]);
    }

    // ==================== ③ 兵营等级缺失才回退熔炉 ====================

    @Test
    public void missingBarrackLevels_fallBackToFurnace() {
        long[] raw = ServerTownTasks.parseBarrackBuildingLevels(map(
                FURNACE, map("bid", 301, "base", map("lv", 13)),
                BOW, map("bid", 323, "base", map("lv", 11))));    // 只有弓营有数据
        assertEquals(0L, raw[1]);
        assertEquals(13L, raw[0]);

        String[] src = new String[1];
        int[] tiers = ServerTownTasks.barrackTiersFromLevels(raw, null, null, src);
        assertNotNull(tiers);
        assertEquals("盾营缺失 → 回退熔炉 13 → T5", 5, tiers[1]);
        assertEquals("矛营缺失 → 回退熔炉 13 → T5", 5, tiers[2]);
        assertEquals("弓营有真实数据 11 → T4（不被熔炉覆盖）", 4, tiers[3]);
        assertEquals("furnace-fallback", src[0]);
    }

    @Test
    public void noBuildingData_returnsNullSoCallerFallsBackToOwnedSoldiers() {
        assertNull(ServerTownTasks.barrackTiersFromLevels(new long[4], null, null, null));
        assertNull(ServerTownTasks.barrackTiersFromLevels(null, null, null, null));
        assertEquals(0L, ServerTownTasks.parseBarrackBuildingLevels(null)[0]);
    }

    @Test
    public void parseBuildingLevels_shapesCompatible() {
        // List 形状 + 顶层 lv（无 base）
        List<Object> list = new ArrayList<>();
        list.add(map("bid", 321, "lv", 11));
        list.add(map("id", 322, "base", map("lv", 13)));   // id 键兼容
        list.add(map("bid", 301, "base", map("lv", 13)));
        long[] raw = ServerTownTasks.parseBarrackBuildingLevels(list);
        assertEquals(13L, raw[0]);
        assertEquals(11L, raw[1]);
        assertEquals(13L, raw[2]);
        assertEquals(0L, raw[3]);
        // 其它建筑不影响
        long[] onlyOther = ServerTownTasks.parseBarrackBuildingLevels(
                map("999", map("bid", 999, "base", map("lv", 30))));
        assertEquals(0L, onlyOther[0]);
        assertEquals(0L, onlyOther[1]);
    }

    // ==================== ④ 2106 降档序列（≤3 次） ====================

    @Test
    public void tierAttempts_downgradeSequence() {
        assertEquals("T5 → 5,4,3", "[5, 4, 3]",
                java.util.Arrays.toString(ServerTownTasks.barrackTierAttempts(5)));
        assertEquals("T12 → 12,11,10", "[12, 11, 10]",
                java.util.Arrays.toString(ServerTownTasks.barrackTierAttempts(12)));
        assertEquals("T2 → 2,1", "[2, 1]",
                java.util.Arrays.toString(ServerTownTasks.barrackTierAttempts(2)));
        assertEquals("T1 → 只有 1 次", "[1]",
                java.util.Arrays.toString(ServerTownTasks.barrackTierAttempts(1)));
        assertEquals("非法/未知 → 至少尝试 T1", "[1]",
                java.util.Arrays.toString(ServerTownTasks.barrackTierAttempts(0)));
        assertEquals("单营单轮最多 3 次降档尝试", 3,
                ServerTownTasks.barrackTierAttempts(12).length);
    }

    // ==================== ⑤ cap 持久化生效 / 升级后重置 ====================

    @Test
    public void effectiveTrainTier_honoursCapUntilBarrackUpgraded() {
        assertEquals("cap 未探测 → 用推导档", 5,
                ServerTownTasks.effectiveTrainTier(5, 0, 0, 13));
        assertEquals("cap=3（探测到 T3 可用）→ min(5,3)=3", 3,
                ServerTownTasks.effectiveTrainTier(5, 3, 13, 13));
        assertEquals("推导档低于 cap → 用推导档", 2,
                ServerTownTasks.effectiveTrainTier(2, 5, 13, 13));
        assertEquals("兵营升级（16>13）→ cap 失效重新探测", 6,
                ServerTownTasks.effectiveTrainTier(6, 3, 13, 16));
        assertEquals("兵营未升级（等级相同）→ cap 仍生效", 3,
                ServerTownTasks.effectiveTrainTier(5, 3, 13, 13));
        assertEquals("推导档未知但有 cap → 用 cap 继续练", 3,
                ServerTownTasks.effectiveTrainTier(0, 3, 13, 13));
        assertEquals("cap_lv 未知(0) → 不清 cap", 3,
                ServerTownTasks.effectiveTrainTier(5, 3, 0, 13));
    }

    // ==================== ⑥ 回放式集成：2106 → 降档成功 → cap → 下一轮直接用 cap ====================

    @Test
    public void barracks_2106downgradesAndPersistsCap() throws Exception {
        FakeEngine engine = new FakeEngine(shelter(13));
        // 收兵：三营都无可收
        engine.responds(2104, 2104, 2104);
        // 训练：首选 T5(10500) → 2106 → 降一档 T4(10400) 成功；三营各一组
        engine.responds(2106, 0, 2106, 0, 2106, 0);

        TaskOutcome r = ServerTownTasks.barracks(context(engine));
        assertTrue("训练应成功（旧实现永远 2106）", r.message.contains("训练"));
        List<Long> trainIds = engine.trainSoldierIds();
        assertEquals("每营先撞 T5 再降档到 T4", 6, trainIds.size());
        // 每营独立：盾 10500→10400、矛 20500→20400、弓 30500→30400
        assertEquals(10500L, (long) trainIds.get(0));
        assertEquals(10400L, (long) trainIds.get(1));
        assertEquals(20500L, (long) trainIds.get(2));
        assertEquals(20400L, (long) trainIds.get(3));
        assertEquals(30500L, (long) trainIds.get(4));
        assertEquals(30400L, (long) trainIds.get(5));

        JSONObject mod = modState();
        assertEquals("max_* 是按兵营等级推导值（11→T4 场景用 13）", 5, mod.optInt("max_shield"));
        assertEquals("caps 记录成功档", 4, mod.optInt("cap_shield"));
        assertEquals(4, mod.optInt("cap_spear"));
        assertEquals(4, mod.optInt("cap_bow"));
        assertEquals("cap_lv 记录发现时兵营等级", 13, mod.optInt("cap_lv_shield"));
        assertEquals("真实兵营等级入状态（排查用）", 13, mod.optInt("lv_shield"));
        assertEquals("口径标 barrack", "barrack", mod.optString("tier_src"));
        assertTrue("训练成功后状态行应刷新为训练中",
                mod.optString("collect_shield", "").startsWith("训练中"));
    }

    @Test
    public void barracks_nextRoundStartsFromCap_noRepeated2106() throws Exception {
        FakeEngine first = new FakeEngine(shelter(13));
        first.responds(2104, 2104, 2104);
        first.responds(2106, 0, 2106, 0, 2106, 0);
        ServerTownTasks.barracks(context(first));
        assertEquals(4, modState().optInt("cap_shield"));

        // 第二轮：cap=4 生效 → 直接打 T4，不再浪费一次 2106；三营各 1 次成功
        FakeEngine second = new FakeEngine(shelter(13));
        second.responds(2104, 2104, 2104);
        second.responds(0, 0, 0);
        ServerTownTasks.barracks(context(second));

        List<Long> ids = second.trainSoldierIds();
        assertEquals("三营各 1 次成功请求", 3, ids.size());
        assertEquals(10400L, (long) ids.get(0));   // 盾 T4
        assertEquals(20400L, (long) ids.get(1));   // 矛 T4
        assertEquals(30400L, (long) ids.get(2));   // 弓 T4
        assertEquals("第二轮无 2106（cap 生效）", 0, second.countCode(2106));
    }

    @Test
    public void barracks_upgradeClearsCapAndReprobes() throws Exception {
        FakeEngine first = new FakeEngine(shelter(13));
        first.responds(2104, 2104, 2104);
        first.responds(2106, 0, 2106, 0, 2106, 0);
        ServerTownTasks.barracks(context(first));
        assertEquals(4, modState().optInt("cap_shield"));

        // 兵营升级到 16（>cap_lv=13）→ cap 失效，重新从推导档 T6 起探
        FakeEngine upgraded = new FakeEngine(shelter(16));
        upgraded.responds(2104, 2104, 2104);
        upgraded.responds(0, 0, 0);
        ServerTownTasks.barracks(context(upgraded));

        List<Long> ids = upgraded.trainSoldierIds();
        assertEquals(10600L, (long) ids.get(0));
        assertEquals(6, modState().optInt("max_shield"));
        assertEquals(6, modState().optInt("cap_shield"));
        assertEquals(16, modState().optInt("cap_lv_shield"));
    }

    @Test
    public void barracks_allAttemptsLocked_lowersProbeForNextRound() throws Exception {
        FakeEngine engine = new FakeEngine(shelter(13));   // 推导 T5
        engine.responds(2104, 2104, 2104);
        // 三营 × 三个档位全部 2106（5/4/3）
        engine.responds(2106, 2106, 2106, 2106, 2106, 2106, 2106, 2106, 2106);

        TaskOutcome r = ServerTownTasks.barracks(context(engine));

        assertFalse("全档位未解锁 → 不应算训练成功", r.message.contains("训练T"));
        assertEquals("单营最多 3 次降档尝试", 9, engine.trainSoldierIds().size());
        assertEquals("下轮探测起点压到 T2", 2, modState().optInt("cap_shield"));
    }


    @Test
    public void trainResponseQueue_refreshesStatusRow_notOverwrittenByStaleNotify() throws Exception {
        FakeEngine engine = new FakeEngine(shelter(13));
        // 旧 notify_barrack_init 是"无队列"快照 → 旧实现本轮末尾 captureBarrackState 会写成"空闲"
        engine.notifies.put("notify_barrack_init", new LinkedHashMap<>());
        engine.responds(2104, 2104, 2104);
        long finish = System.currentTimeMillis() / 1000L + 7200L;
        for (int bid : new int[]{321, 322, 323}) {
            engine.responds(2106);   // 首选 T5 被 2106 → 降档 T4 成功
            engine.respondContent(map("code", 0, "barrack", map(
                    "bid", bid, "soldier_id", 10800, "soldier_num", 200,
                    "finish_ts", finish, "total_time", 7200, "qid", 30001)));
        }

        TaskOutcome r = ServerTownTasks.barracks(context(engine));

        assertTrue(r.message.contains("训练"));
        JSONObject mod = modState();
        for (String key : new String[]{"collect_shield", "collect_spear", "collect_bow"}) {
            String row = mod.optString(key, "");
            assertTrue(key + " 应立即显示训练中（old code 会被旧 notify 覆盖回空闲），实际=" + row,
                    row.startsWith("训练中 · "));
            assertTrue(key + " 文案应以可收结尾，实际=" + row, row.endsWith("可收"));
        }
        assertEquals("每营先 T5 2106 再 T4 成功", 6, engine.trainSoldierIds().size());
    }

    // ==================== 测试基础设施 ====================


    private static final String FURNACE = "301";
    private static final String SHIELD = "321";
    private static final String SPEAR = "322";
    private static final String BOW = "323";

    private TaskContext context(FakeEngine engine) {
        return new TaskContext(engine, FPID, SID, store, Collections.emptyMap(), "手动");
    }

    private JSONObject modState() {
        String raw = store.getString("mod:" + FPID + ":" + SID + ":barracks");
        return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    /** 三营同等级的建筑推送（building_info Map 形状，含熔炉 301）。 */
    private static Map<String, Object> shelter(int barrackLv) {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put(FURNACE, map("bid", 301, "base", map("lv", barrackLv)));
        buildings.put(SHIELD, map("bid", 321, "base", map("lv", barrackLv)));
        buildings.put(SPEAR, map("bid", 322, "base", map("lv", barrackLv)));
        buildings.put(BOW, map("bid", 323, "base", map("lv", barrackLv)));
        return map("building_info", buildings);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 只回放推送/响应、不连网的引擎替身。 */
    static final class FakeEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        final List<String> requestProtos = new ArrayList<>();
        final List<Map<String, Object>> requestArgs = new ArrayList<>();
        final Deque<Map<String, Object>> responses = new ArrayDeque<>();
        final List<Integer> responseCodes = new ArrayList<>();

        FakeEngine(Map<String, Object> shelter) {
            super(null);
            if (shelter != null) notifies.put("notify_shelter_info", shelter);
            long now = System.currentTimeMillis() / 1000L;
            notifies.put("notify_barrack_init", map("barracks", List.of(
                    map("bid", 321, "finish_ts", now + 600),
                    map("bid", 322, "finish_ts", now + 600),
                    map("bid", 323, "finish_ts", now + 600))));
        }

        /** 预置完整响应体（按请求顺序回放）。 */
        void respondContent(Map<String, Object> content) {
            responses.add(content);
        }

        /** 预置响应码（按请求顺序回放；耗尽后默认 code=0 成功）。 */
        void responds(int... codes) {
            for (int code : codes) {
                responseCodes.add(code);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", code);
                responses.add(m);
            }
        }

        int countCode(int code) {
            int n = 0;
            for (int c : responseCodes) if (c == code) n++;
            return n;
        }

        List<Long> trainSoldierIds() {
            List<Long> out = new ArrayList<>();
            for (int i = 0; i < requestProtos.size(); i++) {
                if (!"req_barrack_train_soldier".equals(requestProtos.get(i))) continue;
                out.add(Exec.longOf(requestArgs.get(i).get("soldier_id")));
            }
            return out;
        }

        @Override
        public void collectNotifies(long millis) {
            // 单测不等待
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requestProtos.add(proto);
            requestArgs.add(args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args));
            GameMessage msg = new GameMessage();
            msg.setProto(proto + ".response");
            msg.setContent(responses.isEmpty() ? map("code", 0) : responses.poll());
            return msg;
        }
    }
}
