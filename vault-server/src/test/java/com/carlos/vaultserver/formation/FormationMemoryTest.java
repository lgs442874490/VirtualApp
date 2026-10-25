package com.carlos.vaultserver.formation;

import com.carlos.vaultserver.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.carlos.vaultserver.formation.TestEngines.FakeEngine.mapOf;
import static com.carlos.vaultserver.formation.TestEngines.FakeEngine.pair;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 编队记忆（契约 §3.5 / 审计 P1-4）：DAILY/BATTLE 快照保存（overwrite 语义）、恢复逐槽
 * {@code req_soldier_set_prefab}（500ms 间隔可配 0）、失败逐槽返回、state 键、巨熊绑定读取。
 */
public class FormationMemoryTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID = "88280792";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore stateStore;
    private TestEngines.FakeEngine engine;

    @Before
    public void setUp() throws Exception {
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        engine = new TestEngines.FakeEngine();
        FormationCatalog.reset(engine);
        presetPrefabs(engine);
        FormationCatalog.capture(engine);
        assertEquals("前置：2 个非空游戏编队槽", 2, FormationCatalog.gameSlots(engine).size());
    }

    @After
    public void tearDown() {
        System.clearProperty(FormationMemory.INTERVAL_PROPERTY);
    }

    /** notify_soldier_init：soldier 库存 + 两个 prefab 槽（士兵数量 / 比例各一）。 */
    private static void presetPrefabs(TestEngines.FakeEngine e) {
        List<Map<String, Object>> prefab = new ArrayList<>();
        prefab.add(mapOf("id", 1L, "prefab", mapOf(
                "hero_id", List.of(pair(1, 50025), pair(2, 50019), pair(3, 50022)),
                "soldier", List.of(pair(10300, 88885), pair(20100, 3629), pair(30300, 88886)),
                "formation_name", "赫克托")));
        prefab.add(mapOf("id", 2L, "prefab", mapOf(
                "hero_id", List.of(pair(1, 50006)),
                "formation_rate", List.of(pair(1, 10), pair(2, 10), pair(3, 80)))));
        e.notifies.put("notify_soldier_init", mapOf(
                "soldier", List.of(
                        mapOf("id", 11000L, "count", 20000L),
                        mapOf("id", 21000L, "count", 1549L),
                        mapOf("id", 31000L, "count", 28451L)),
                "prefab", prefab));
    }

    @Test
    public void save_storesSnapshot_andReturnsContractFields() {
        JSONObject data = FormationMemory.save(stateStore, engine, FPID, SID, UID, "DAILY", false);
        assertEquals("DAILY", data.getString("kind"));
        assertEquals(2, data.getInt("slotCount"));
        assertTrue(data.getLong("savedAt") > 0L);
        assertEquals(1L, data.getLong("version"));

        String key = FormationMemory.stateKey(FPID, SID, UID, "DAILY");
        assertEquals("srv:formation:memory:" + FPID + ":" + SID + ":" + UID + ":DAILY", key);
        String raw = stateStore.getString(key);
        assertNotNull(raw);
        JSONObject snap = new JSONObject(raw);
        assertEquals(2, snap.getInt("slotCount"));
        assertEquals(1L, snap.getLong("version"));
        JSONArray slots = snap.getJSONArray("slots");
        JSONObject slot1 = slots.getJSONObject(0);
        assertEquals(1L, slot1.getLong("id"));
        assertEquals("赫克托", slot1.getString("name"));
        assertEquals(3, slot1.getJSONArray("heroIds").length());
        assertNotNull("记忆快照保留原始 prefab（恢复要回发 soldier id）",
                slot1.optJSONObject("prefab"));
        assertEquals(3, slot1.getJSONObject("prefab").getJSONArray("soldier").length());

        // 状态视图：daily 有、battle 无
        JSONObject status = FormationMemory.status(stateStore, FPID, SID, UID);
        assertTrue(status.getJSONObject("daily").getBoolean("exists"));
        assertEquals(2, status.getJSONObject("daily").getInt("slotCount"));
        assertFalse(status.getJSONObject("battle").getBoolean("exists"));
    }

    @Test
    public void save_withoutOverwrite_rejects_thenOverwriteBumpsVersion() {
        FormationMemory.save(stateStore, engine, FPID, SID, UID, "BATTLE", false);
        try {
            FormationMemory.save(stateStore, engine, FPID, SID, UID, "BATTLE", false);
            fail("已存在且 !overwrite 应报 3002");
        } catch (FormationMemory.MemoryError e) {
            assertEquals(3002, e.code);
            assertEquals("记忆已存在，请确认覆盖", e.getMessage());
        }
        JSONObject data = FormationMemory.save(stateStore, engine, FPID, SID, UID, "BATTLE", true);
        assertEquals(2L, data.getLong("version"));
    }

    @Test
    public void save_withNoPrefab_reportsChineseError() {
        TestEngines.FakeEngine empty = new TestEngines.FakeEngine();
        FormationCatalog.reset(empty);
        try {
            FormationMemory.save(stateStore, empty, FPID, SID, UID, "DAILY", true);
            fail("无游戏编队应报错");
        } catch (FormationMemory.MemoryError e) {
            assertEquals(3002, e.code);
            assertEquals("当前没有已同步的游戏编队，无法记忆", e.getMessage());
        }
    }

    @Test
    public void save_slotWithSoldiersAndRates_rejected() {
        TestEngines.FakeEngine bad = new TestEngines.FakeEngine();
        FormationCatalog.reset(bad);
        bad.notifies.put("notify_soldier_init", mapOf("prefab", List.of(
                mapOf("id", 1L, "prefab", mapOf(
                        "hero_id", List.of(pair(1, 50025)),
                        "soldier", List.of(pair(10300, 100)),
                        "formation_rate", List.of(pair(1, 10), pair(2, 10), pair(3, 80)))))));
        FormationCatalog.capture(bad);
        try {
            FormationMemory.save(stateStore, bad, FPID, SID, UID, "DAILY", true);
            fail("soldier 与 formation_rate 同时存在应拒绝记忆");
        } catch (FormationMemory.MemoryError e) {
            assertEquals(3002, e.code);
            assertTrue("实际文案：" + e.getMessage(), e.getMessage().contains("同时包含士兵数量与比例"));
        }
    }

    @Test
    public void restore_sendsPrefabPerSlot_andCollectsFailures() {
        FormationMemory.save(stateStore, engine, FPID, SID, UID, "DAILY", false);
        System.setProperty(FormationMemory.INTERVAL_PROPERTY, "0");
        engine.respond(0L);   // 槽 1 成功
        engine.respond(7L);   // 槽 2 被游戏拒绝（错误码 7）

        JSONObject r = FormationMemory.restore(stateStore, engine, FPID, SID, UID, "DAILY");
        assertEquals(2, r.getInt("total"));
        assertEquals(1, r.getInt("restored"));
        JSONArray failures = r.getJSONArray("failures");
        assertEquals(1, failures.length());
        JSONObject f = failures.getJSONObject(0);
        assertEquals(2L, f.getLong("id"));
        assertEquals(7L, f.getLong("code"));
        assertTrue(f.getString("message").contains("错误码 7"));

        assertEquals("逐槽发 req_soldier_set_prefab", 2, engine.requestProtos.size());
        assertEquals("req_soldier_set_prefab", engine.requestProtos.get(0));
        Map<String, Object> payload1 = engine.requestArgs.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> pair1 = (Map<String, Object>) payload1.get("prefab");
        assertEquals(1L, ((Number) pair1.get("id")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) pair1.get("prefab");
        assertEquals(3, ((List<?>) inner.get("hero_id")).size());
        assertEquals("赫克托", inner.get("formation_name"));
        assertEquals("soldier id 原样回发", true,
                hasPair(inner.get("soldier"), 10300L, 88885L));

        // 第二个槽：比例 payload 补齐 3 个兵种，不带 soldier/formation_name
        @SuppressWarnings("unchecked")
        Map<String, Object> pair2 = (Map<String, Object>)
                ((Map<String, Object>) engine.requestArgs.get(1)).get("prefab");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner2 = (Map<String, Object>) pair2.get("prefab");
        assertEquals(3, ((List<?>) inner2.get("formation_rate")).size());
        assertFalse(inner2.containsKey("soldier"));
        assertFalse(inner2.containsKey("formation_name"));
    }

    @Test
    public void restore_withoutMemory_reportsChineseError() {
        try {
            FormationMemory.restore(stateStore, engine, FPID, SID, UID, "DAILY");
            fail("无记忆应报错");
        } catch (FormationMemory.MemoryError e) {
            assertEquals(3002, e.code);
            assertTrue(e.getMessage().contains("还没有日常编队记忆"));
        }
        // BATTLE 文案区分
        try {
            FormationMemory.restore(stateStore, engine, FPID, SID, UID, "BATTLE");
            fail("无记忆应报错");
        } catch (FormationMemory.MemoryError e) {
            assertTrue(e.getMessage().contains("战斗"));
        }
    }

    @Test
    public void restore_engineNull_offlineError() {
        FormationMemory.save(stateStore, engine, FPID, SID, UID, "DAILY", false);
        try {
            FormationMemory.restore(stateStore, null, FPID, SID, UID, "DAILY");
            fail("离线应报错");
        } catch (FormationMemory.MemoryError e) {
            assertEquals(3002, e.code);
            assertEquals("账号离线，无法恢复编队，请先上线", e.getMessage());
        }
    }

    @Test
    public void bearBinding_defaultAndCustom() {
        JSONObject def = FormationMemory.bearBinding(stateStore, FPID, SID, UID);
        assertFalse(def.getBoolean("applied"));
        assertEquals("", def.getString("templateId"));
        assertTrue(def.getJSONArray("joinFormationIds").isEmpty());

        Map<String, Object> bear = new LinkedHashMap<>();
        bear.put("applied", true);
        bear.put("templateId", "g01-spender");
        bear.put("title", "一代·大氪");
        bear.put("generation", 1);
        bear.put("tier", "大氪");
        bear.put("launchFormationId", 719L);
        bear.put("joinFormationIds", List.of(720L, 721L));
        stateStore.putString(FormationMemory.bearStateKey(FPID, SID, UID),
                new JSONObject(bear).toString());
        JSONObject got = FormationMemory.bearBinding(stateStore, FPID, SID, UID);
        assertTrue(got.getBoolean("applied"));
        assertEquals("g01-spender", got.getString("templateId"));
        assertEquals(2, got.getJSONArray("joinFormationIds").length());
    }

    @Test
    public void kindAndKeyHelpers() {
        assertEquals("DAILY", FormationMemory.normalizeKind(null));
        assertEquals("BATTLE", FormationMemory.normalizeKind(" battle "));
        assertEquals("日常", FormationMemory.kindLabel("DAILY"));
        assertEquals("战斗", FormationMemory.kindLabel("BATTLE"));
        assertEquals("srv:formation:bear:" + FPID + ":" + SID + ":" + UID,
                FormationMemory.bearStateKey(FPID, SID, UID));
        assertEquals("srv:formation:memory:" + FPID + ":" + SID + ":" + UID + ":BATTLE",
                FormationMemory.stateKey(FPID, SID, UID, "battle"));
    }

    private static boolean hasPair(Object list, long k, long v) {
        if (!(list instanceof List)) return false;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            if (((Number) m.get("k")).longValue() == k
                    && ((Number) m.get("v")).longValue() == v) return true;
        }
        return false;
    }
}
