package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.formation.FormationCatalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巨兽「发起队 + 上车编队 1..N」服务端单测（task-3，2026-11-03）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link ServerTasks.BehemothConfig#of} 解析 {@code join_formations}（复用
 *       {@link ServerBearTasks#parseJoinFormations}）；queue_count 默认 5（用户要求与 App 一致）；</li>
 *   <li>按 {@code state.joinCount}（第 N 次上车）选中第 N 个启用槽；disabled 槽跳过、越界返回 null；</li>
 *   <li>ROLE 槽用槽 params 走 FormationResolver 命中库内编队；AUTOMATIC 槽生成自动编队；</li>
 *   <li>槽不可用 → 回退 chooseBehemothFormation 且文案带槽标签；无配置 → 与旧行为完全一致。</li>
 * </ul>
 *
 * <p>不连真协议：引擎替身只回放 hero/soldier 推送；时间/状态用 TemporaryFolder。
 */
public class BehemothJoinFormationsTest {

    /** 编队库：ROLE 槽 #12 = 50041/50039/50022 + 显式兵量（与 notify_soldier_init 库存匹配）。 */
    private static final String LIB_JSON = "{\"formations\":["
            + "{\"id\":12,\"name\":\"车头上车队\",\"hero_ids\":[50041,50039,50022],"
            + "\"soldiers\":{\"infantry\":20000,\"lancer\":1549,\"marksman\":28451},"
            + "\"ratio\":{\"infantry\":40,\"lancer\":30,\"marksman\":30},\"total\":0}]}";

    /** 最小引擎替身：只回放 notifyData（不连网）。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();

        TestEngine() {
            super(null);
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
        public long lastUid() {
            return 88280792L;
        }

        @Override
        public long lastServerId() {
            return 888L;
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TestEngine engine;
    private StateStore state;

    @Before
    public void setUp() throws Exception {
        engine = new TestEngine();
        state = new StateStore(tmp.getRoot().toPath());
        FormationCatalog.reset(engine);
        presetCatalog();
        FormationCatalog.capture(engine);
    }

    // ---------------- 夹具 ----------------

    private void presetCatalog() {
        Map<String, Object> heroes = new LinkedHashMap<>();
        heroes.put("50041.0", hero(50041L, 80L, 30L, 9000L));
        heroes.put("50039.0", hero(50039L, 75L, 22L, 5000L));
        heroes.put("50022.0", hero(50022L, 70L, 12L, 1000L));
        engine.notifies.put("notify_hero_init", heroes);

        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", mapOf("id", 11000L, "count", 20000L));
        soldier.put("21000.0", mapOf("id", 21000L, "count", 1549L));
        soldier.put("31000.0", mapOf("id", 31000L, "count", 28451L));
        engine.notifies.put("notify_soldier_init", mapOf("soldier", soldier));
    }

    private static Map<String, Object> hero(long id, long lv, long star, long power) {
        return mapOf("id", id, "lv", lv, "star", star, "power", power);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static JSONObject slot(int id, boolean enabled, String mode, long formationId) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("enabled", enabled);
        o.put("mode", mode);
        if (formationId > 0L) o.put("formation_id", formationId);
        return o;
    }

    /** 默认上线槽：1=AUTO、2=disabled ROLE#10、3=ROLE#12（带 soldiers 覆盖）、9=越界丢弃。 */
    private static JSONObject defaultParams() {
        JSONObject p = new JSONObject();
        p.put("queue_count", 3);
        JSONArray arr = new JSONArray();
        arr.put(slot(1, true, "AUTOMATIC", 0L));
        arr.put(slot(2, false, "ROLE", 10L));
        JSONObject s3 = slot(3, true, "ROLE", 12L);
        s3.put("soldiers", new JSONObject().put("infantry", 100));
        arr.put(s3);
        arr.put(slot(9, true, "ROLE", 12L));   // id 越界(1..6) → 丢弃
        p.put("join_formations", arr);
        p.put("formation_library", LIB_JSON);
        return p;
    }

    private TaskContext tcFor(JSONObject behemoth) {
        Map<String, JSONObject> params = new LinkedHashMap<>();
        params.put("behemoth", behemoth);
        return new TaskContext(engine, "111", 913L, state, params, "test");
    }

    // ==================== 1. 配置解析 ====================

    @Test
    public void config_parsesJoinFormations_andKeepsQueueCount() {
        JSONObject p = defaultParams();
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        assertEquals("queue_count 保留 3", 3, cfg.queueCount);
        assertEquals("join_formations 解析（越界 id 丢弃）", 3, cfg.joinFormations.size());
        ServerBearTasks.JoinFormationConfig s1 = cfg.joinFormations.get(0);
        ServerBearTasks.JoinFormationConfig s2 = cfg.joinFormations.get(1);
        ServerBearTasks.JoinFormationConfig s3 = cfg.joinFormations.get(2);
        assertEquals(1, s1.id);
        assertTrue(s1.enabled);
        assertEquals("AUTOMATIC", s1.mode);
        assertEquals(0L, s1.formationId);
        assertEquals(2, s2.id);
        assertFalse("disabled 槽保留但标记 false", s2.enabled);
        assertEquals(10L, s2.formationId);
        assertEquals(3, s3.id);
        assertEquals(12L, s3.formationId);
        assertEquals("soldiers 字段进入槽（仅解析，不改库）", 100L, s3.infantry);
    }

    @Test
    public void config_queueCountDefaultFiveAndClamped() {
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(new JSONObject());
        assertEquals("用户 2026-11-03：默认按游戏编队槽数 5", 5, cfg.queueCount);
        JSONObject high = new JSONObject().put("queue_count", 9);
        assertEquals("clamp 1..6", 6, ServerTasks.BehemothConfig.of(high).queueCount);
        JSONObject low = new JSONObject().put("queue_count", 0);
        assertEquals(1, ServerTasks.BehemothConfig.of(low).queueCount);
        assertTrue("无 join_formations → 空列表（旧行为）",
                cfg.joinFormations.isEmpty());
    }

    // ==================== 2. 第 N 个启用槽（joinCount 下标） ====================

    @Test
    public void joinFormationAt_usesNthEnabledSlot_skipsDisabled() {
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(defaultParams());
        List<ServerBearTasks.JoinFormationConfig> slots = cfg.joinFormations;
        assertEquals("第 1 次上车 → 槽1", 1, ServerTasks.behemothJoinSlot(cfg, 0).id);
        assertEquals("第 2 次上车跳过 disabled 槽2 → 槽3", 3,
                ServerTasks.behemothJoinSlot(cfg, 1).id);
        assertNull("启用槽不足 → 回退旧逻辑", ServerTasks.behemothJoinSlot(cfg, 2));
        assertNull(ServerTasks.behemothJoinSlot(cfg, -1));
        assertNull(ServerTasks.behemothJoinSlot(null, 0));

        // BearConfig 委托同一实现（行为不变）
        ServerBearTasks.BearConfig bear = ServerBearTasks.BearConfig.of(defaultParams());
        assertEquals(1, ServerBearTasks.joinFormationAt(bear, 0).id);
        assertEquals(3, ServerBearTasks.joinFormationAt(bear, 1).id);
        assertNull(ServerBearTasks.joinFormationAt(bear, 2));
        assertNull(ServerBearTasks.joinFormationAt((List<ServerBearTasks.JoinFormationConfig>) null, 0));
    }

    @Test
    public void labels_includeSlotAndResolvedMode() {
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(defaultParams());
        assertEquals("上车编队1[AUTO]",
                ServerTasks.behemothJoinSlotLabel(cfg.joinFormations.get(0)));
        assertEquals("上车编队3[ROLE#12]",
                ServerTasks.behemothJoinSlotLabel(cfg.joinFormations.get(2)));
        assertEquals("上车编队", ServerTasks.behemothJoinSlotLabel(null));
    }

    // ==================== 3. ROLE / AUTOMATIC / 回退 ====================

    @Test
    public void roleSlot_resolvesFormationFromLibrary() {
        JSONObject p = defaultParams();
        TaskContext tc = tcFor(p);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        // 第 2 次上车 = 槽3（ROLE#12）；槽内 soldiers 只覆盖盾=100，其余沿用库内兵量
        ServerTasks.BehemothFormationChoice choice =
                ServerTasks.chooseBehemothJoinFormation(tc, cfg, 1);
        assertNotNull("ROLE 槽应解析成功", choice.payload);
        assertTrue("标签带槽号+模式+编队 id: " + choice.label,
                choice.label.startsWith("上车编队3[ROLE#12]"));
        assertFalse("成功文案不应带回退", choice.label.contains("回退"));
        assertEquals("命中库内编队英雄", Arrays.asList(50041L, 50039L, 50022L),
                payloadHeroIds(choice.payload));
    }

    @Test
    public void automaticSlot_buildsAutoFormationFromRatioTotal() {
        JSONObject p = new JSONObject();
        p.put("formation_library", LIB_JSON);
        p.put("ratio", new JSONObject().put("infantry", 50)
                .put("lancer", 20).put("marksman", 30));
        p.put("total", 30000);
        JSONArray arr = new JSONArray();
        arr.put(slot(1, true, "AUTOMATIC", 0L));
        p.put("join_formations", arr);
        TaskContext tc = tcFor(p);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        ServerTasks.BehemothFormationChoice choice =
                ServerTasks.chooseBehemothJoinFormation(tc, cfg, 0);
        assertNotNull("AUTOMATIC 槽应生成自动编队", choice.payload);
        assertTrue("标签带槽号: " + choice.label, choice.label.startsWith("上车编队1[AUTO]"));
        assertFalse(choice.label.contains("回退"));
        assertEquals("自动编队上阵英雄", 3, payloadHeroIds(choice.payload).size());
        assertTrue("自动编队兵力 >0", payloadSoldierTotal(choice.payload) > 0L);
    }

    @Test
    public void brokenSlot_fallsBackToLegacyWithSlotLabel() {
        JSONObject p = new JSONObject();
        p.put("formation_library", LIB_JSON);
        JSONArray arr = new JSONArray();
        arr.put(slot(2, true, "ROLE", 999L));   // 库内不存在
        p.put("join_formations", arr);
        TaskContext tc = tcFor(p);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        ServerTasks.BehemothFormationChoice choice =
                ServerTasks.chooseBehemothJoinFormation(tc, cfg, 0);
        assertNotNull("回退旧编队（fake 引擎可用英雄）", choice.payload);
        assertTrue("文案带槽标签+回退: " + choice.label,
                choice.label.startsWith("上车编队2[ROLE#999]→回退"));
        assertTrue("原因说明槽不可用: " + choice.reason,
                choice.reason.contains("槽编队不可用"));
        assertTrue("原因写明编队不存在: " + choice.reason,
                choice.reason.contains("999"));
    }

    @Test
    public void noSlotConfig_behavesExactlyLikeLegacyChoice() {
        JSONObject p = new JSONObject();
        p.put("formation_library", LIB_JSON);
        TaskContext tc = tcFor(p);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        ServerTasks.BehemothFormationChoice legacy =
                ServerTasks.chooseBehemothFormation(tc, cfg);
        ServerTasks.BehemothFormationChoice viaJoin =
                ServerTasks.chooseBehemothJoinFormation(tc, cfg, 3);
        assertEquals("无 join_formations → 与旧行为同一 label", legacy.label, viaJoin.label);
        assertEquals("无 join_formations → 与旧行为 payload 一致性",
                legacy.payload == null, viaJoin.payload == null);
        assertFalse("不应带槽标签", viaJoin.label.contains("上车编队"));
    }

    @Test
    public void allSlotsDisabled_fallsBackToLegacy() {
        JSONObject p = new JSONObject();
        p.put("formation_library", LIB_JSON);
        JSONArray arr = new JSONArray();
        arr.put(slot(1, false, "ROLE", 12L));
        arr.put(slot(2, false, "AUTOMATIC", 0L));
        p.put("join_formations", arr);
        TaskContext tc = tcFor(p);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        assertNull("全部 disabled → 无启用槽", ServerTasks.behemothJoinSlot(cfg, 0));
        ServerTasks.BehemothFormationChoice choice =
                ServerTasks.chooseBehemothJoinFormation(tc, cfg, 0);
        ServerTasks.BehemothFormationChoice legacy =
                ServerTasks.chooseBehemothFormation(tc, cfg);
        assertEquals(legacy.label, choice.label);
        assertEquals(legacy.payload == null, choice.payload == null);
    }

    // ---------------- payload 提取 ----------------

    private static List<Long> payloadHeroIds(Map<String, Object> payload) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> pair : Exec.mapsOf(payload == null ? null : payload.get("hero_id"))) {
            out.add(Exec.longOf(pair.get("v")));
        }
        return out;
    }

    private static long payloadSoldierTotal(Map<String, Object> payload) {
        long total = 0L;
        for (Map<String, Object> pair : Exec.mapsOf(payload == null ? null : payload.get("soldier"))) {
            total += Exec.longOf(pair.get("v"));
        }
        return total;
    }
}
