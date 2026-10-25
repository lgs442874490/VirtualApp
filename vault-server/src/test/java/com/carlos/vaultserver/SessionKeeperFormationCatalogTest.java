package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 编队目录 v2 / 编队动作的服务端集成（契约 §3.1/§3.2/§3.5；T2 2026-10-25）：
 * <ul>
 *   <li>{@code formation.catalog-refresh} → state 新键带 uid + 旧键双写、v2 字段、空快照保护；</li>
 *   <li>{@code formation.memory-save} / {@code memory-restore} / {@code bear-binding} 动作；</li>
 *   <li>参数校验（缺 kind / 未知 action / 缺角色）。</li>
 * </ul>
 */
public class SessionKeeperFormationCatalogTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID = "35371154";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private SessionKeeper keeper;
    private final List<FakeEngine> engines = new ArrayList<>();

    /** 合成引擎：登录即在线，不连网；回放推送集。 */
    private final class FakeEngine extends WjdrEngine {
        boolean online;
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        final List<String> requestProtos = new ArrayList<>();
        final List<Map<String, Object>> requestArgs = new ArrayList<>();

        FakeEngine() {
            super(null);
            engines.add(this);
        }

        @Override
        public void initialize() {
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid) {
            online = true;
            return result(serverid == null ? 888L : serverid, Long.parseLong(UID));
        }

        @Override
        public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) {
            online = true;
            return result(serverid == null ? 888L : serverid, roleUid);
        }

        @Override
        public Long discoverRoleUid(long kid) {
            return Long.parseLong(UID);
        }

        @Override
        public List<RoleInfo> discoverRoles() {
            return Arrays.asList(role(888L, Long.parseLong(UID), "小鹿矿工5号"));
        }

        @Override
        public String sessionState() {
            return online ? STATE_ONLINE : STATE_OFFLINE;
        }

        @Override
        public void heartbeatTick() {
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requestProtos.add(proto);
            requestArgs.add(args == null ? new LinkedHashMap<>() : args);
            GameMessage msg = new GameMessage();
            msg.setProto(proto + ".response");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", 0L);
            msg.setContent(content);
            return msg;
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
        public String identityJson() {
            return "";
        }

        @Override
        public void applyIdentity(String settingsJson) {
        }

        @Override
        public String lastFailure() {
            return null;
        }

        @Override
        public long lastServerId() {
            return 888L;
        }

        @Override
        public void close() {
            online = false;
        }

        private LoginResult result(long sid, long uid) {
            LoginResult r = new LoginResult();
            r.serverId = sid;
            r.uid = uid;
            r.gameName = "小鹿矿工5号";
            r.level = 70;
            return r;
        }

        private RoleInfo role(long sid, long uid, String name) {
            RoleInfo ri = new RoleInfo();
            ri.serverid = sid;
            ri.uid = uid;
            ri.name = name;
            return ri;
        }
    }

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        keeper = new SessionKeeper(store, config(), null, stateStore, FakeEngine::new, true);
        syncAccount();
        // 登录并驱动一拍（测试模式同步登录）
        assertEquals(0, keeper.roleAction(FPID, SID, UID, "login").code);
        keeper.tickForTest();
        assertTrue("前置：角色在线", keeper.roleRuntime(FPID, SID, UID).online);
    }

    private VaultConfig config() {
        return new VaultConfig("127.0.0.1:0", tmp.getRoot().toPath(), "s", null, "human_first", 8);
    }

    private void syncAccount() {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk-" + FPID;
        a.activeSid = SID;
        a.activeUid = UID;
        List<VaultStore.Account> in = new ArrayList<>(store.snapshot());
        in.removeIf(x -> x.fpid.equals(FPID));
        in.add(a);
        store.replaceAll(in);
        store.ensureRole(FPID, SID, UID);
    }

    /** 生产形状推送：hero_init / soldier_init(prefab) / shelter(容量) / world(队列)。 */
    private void presetPushes() {
        FakeEngine e = engines.get(0);
        e.notifies.put("notify_hero_init", mapOf(
                "50025.0", mapOf("id", 50025L, "lev", 80L, "star", 26L),
                "50019.0", mapOf("id", 50019L, "lev", 56L, "star", 5L)));
        e.notifies.put("notify_soldier_init", mapOf(
                "soldier", List.of(
                        mapOf("id", 11000L, "count", 20000L),
                        mapOf("id", 21000L, "count", 1549L),
                        mapOf("id", 31000L, "count", 28451L)),
                "prefab", List.of(
                        mapOf("id", 1L, "prefab", mapOf(
                                "hero_id", List.of(pair(1, 50025)),
                                "soldier", List.of(pair(10300, 500L)),
                                "formation_name", "赫克托")),
                        mapOf("id", 2L, "prefab", mapOf(
                                "hero_id", List.of(pair(1, 50019)),
                                "formation_rate", List.of(pair(1, 10), pair(2, 10), pair(3, 80)))))));
        e.notifies.put("notify_shelter_info", mapOf(
                "building_info", mapOf("331", mapOf("base", mapOf("lv", 30L)))));
        e.notifies.put("notify_world_player_init", mapOf("world", mapOf("capacity", 5L)));
    }

    // ------------------------------------------------------------------

    @Test
    public void catalogRefresh_writesUidKeyAndLegacyKey_withV2Json() {
        presetPushes();
        SessionKeeper.FormationActionResult r =
                keeper.formationAction(FPID, SID, UID, "formation.catalog-refresh", null);
        assertEquals(0, r.code);
        assertTrue(r.data.getBoolean("ok"));
        assertTrue(r.data.getLong("capturedAt") > 0L);

        String newKey = "srv:formation:catalog:" + FPID + ":" + SID + ":" + UID;
        String legacyKey = "srv:formation:catalog:" + FPID + ":" + SID;
        String newJson = stateStore.getString(newKey);
        String legacyJson = stateStore.getString(legacyKey);
        assertNotNull("新键带 uid 必须写入", newJson);
        assertNotNull("旧键兼容双写", legacyJson);
        assertEquals("新旧键同内容", newJson, legacyJson);

        JSONObject o = new JSONObject(newJson);
        assertEquals(2, o.getInt("version"));
        assertEquals(FPID, o.getString("fpid"));
        assertEquals(888L, o.getLong("sid"));
        assertEquals(UID, o.getString("uid"));
        assertTrue(o.getBoolean("configured"));
        assertEquals(2, o.getJSONArray("heroes").length());
        assertEquals("诺拉", o.getJSONArray("heroes").getJSONObject(0).getString("name"));
        assertFalse("hero 名不得带引号",
                o.getJSONArray("heroes").getJSONObject(0).getString("name").contains("\""));
        JSONObject mc = o.getJSONObject("marchCapacity");
        assertTrue("容量必须初始化且非 0（审计 P0-4）", mc.getBoolean("initialized"));
        assertTrue(mc.getLong("fixedCapacity") > 0L);
        assertTrue(mc.getJSONObject("limits").getLong("threeHeroLimit") > 0L);
        assertEquals(2, o.getJSONArray("gameFormations").length());
        assertEquals(-1L, o.getJSONArray("gameFormations").getJSONObject(0).getLong("id"));
        assertEquals("GAME", o.getJSONArray("gameFormations").getJSONObject(0).getString("source"));
        assertEquals(50, o.getJSONObject("automaticSettings").getInt("infantryRatio"));
        assertFalse(o.getJSONObject("bear").getBoolean("applied"));
        assertFalse(o.getJSONObject("memory").getJSONObject("daily").getBoolean("exists"));
        // 自动比例 state 键（契约 §3.2）也落盘（默认 50/20/30）
        JSONObject auto = new JSONObject(stateStore.getString(
                "srv:formation:autosettings:" + FPID + ":" + SID + ":" + UID));
        assertEquals(50, auto.getInt("infantryRatio"));
        assertEquals(20, auto.getInt("lancerRatio"));
        assertEquals(30, auto.getInt("marksmanRatio"));
    }

    @Test
    public void catalogRefresh_readsAutomaticSettingsFromRoleParams() {
        // App 上传的编队库参数（伪功能 formation/formation_library）
        VaultStore.Account acc = store.find(FPID);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("formation_library",
                "{\"automatic_settings\":{\"infantry_ratio\":40,\"lancer_ratio\":30,"
                        + "\"marksman_ratio\":30}}");
        acc.roleParams.put(SID, params);
        presetPushes();
        assertEquals(0, keeper.formationAction(FPID, SID, UID,
                "formation.catalog-refresh", null).code);
        JSONObject auto = new JSONObject(stateStore.getString(
                "srv:formation:autosettings:" + FPID + ":" + SID + ":" + UID));
        assertEquals(40, auto.getInt("infantryRatio"));
        assertEquals(30, auto.getInt("lancerRatio"));
        JSONObject catalog = new JSONObject(stateStore.getString(
                "srv:formation:catalog:" + FPID + ":" + SID + ":" + UID));
        assertEquals(40, catalog.getJSONObject("automaticSettings").getInt("infantryRatio"));
    }

    @Test
    public void catalogRefresh_emptySnapshot_keepsGoodState() {
        presetPushes();
        assertEquals(0, keeper.formationAction(FPID, SID, UID,
                "formation.catalog-refresh", null).code);
        String newKey = "srv:formation:catalog:" + FPID + ":" + SID + ":" + UID;
        String before = stateStore.getString(newKey);
        assertNotNull(before);

        // 模拟进程重启后冷缓存 + 推送全部丢失：空快照不得覆盖 state 里既有的好目录
        engines.get(0).notifies.clear();
        com.carlos.vaultserver.formation.FormationCatalog.reset(engines.get(0));
        assertEquals(0, keeper.formationAction(FPID, SID, UID,
                "formation.catalog-refresh", null).code);
        assertEquals("空快照不覆盖好目录（审计 P0-6）", before, stateStore.getString(newKey));
        JSONObject o = new JSONObject(stateStore.getString(newKey));
        assertEquals(2, o.getJSONArray("heroes").length());
    }

    @Test
    public void memorySaveAndRestore_viaActions() {
        presetPushes();
        assertEquals(0, keeper.formationAction(FPID, SID, UID,
                "formation.catalog-refresh", null).code);

        Map<String, Object> saveFields = new LinkedHashMap<>();
        saveFields.put("kind", "DAILY");
        saveFields.put("overwrite", false);
        SessionKeeper.FormationActionResult saved = keeper.formationAction(FPID, SID, UID,
                "formation.memory-save", saveFields);
        assertEquals(0, saved.code);
        assertEquals("DAILY", saved.data.getString("kind"));
        assertEquals(2, saved.data.getInt("slotCount"));
        assertEquals(1L, saved.data.getLong("version"));

        JSONObject memoryJson = new JSONObject(stateStore.getString(
                "srv:formation:memory:" + FPID + ":" + SID + ":" + UID + ":DAILY"));
        assertEquals(2, memoryJson.getInt("slotCount"));

        // 已存在且未确认覆盖 → 3002
        SessionKeeper.FormationActionResult again = keeper.formationAction(FPID, SID, UID,
                "formation.memory-save", saveFields);
        assertEquals(3002, again.code);
        assertEquals("记忆已存在，请确认覆盖", again.message);

        // 恢复：逐槽 req_soldier_set_prefab（测试把间隔设为 0）
        System.setProperty("wjdr.daily.formation-restore-interval-millis", "0");
        try {
            Map<String, Object> restoreFields = new LinkedHashMap<>();
            restoreFields.put("kind", "DAILY");
            SessionKeeper.FormationActionResult restored = keeper.formationAction(FPID, SID, UID,
                    "formation.memory-restore", restoreFields);
            assertEquals(0, restored.code);
            assertEquals(2, restored.data.getInt("total"));
            assertEquals(2, restored.data.getInt("restored"));
            assertEquals(0, restored.data.getJSONArray("failures").length());
            assertEquals("逐槽发 req_soldier_set_prefab（登录期其它请求不计）", 2,
                    countPrefabRequests());
        } finally {
            System.clearProperty("wjdr.daily.formation-restore-interval-millis");
        }

        // 记忆状态已进目录（App 即时可见）
        JSONObject catalog = new JSONObject(stateStore.getString(
                "srv:formation:catalog:" + FPID + ":" + SID + ":" + UID));
        assertTrue(catalog.getJSONObject("memory").getJSONObject("daily").getBoolean("exists"));
    }

    @Test
    public void memorySave_requiresKind_andValidAction() {
        SessionKeeper.FormationActionResult noKind = keeper.formationAction(FPID, SID, UID,
                "formation.memory-save", new LinkedHashMap<>());
        assertEquals(3002, noKind.code);
        assertEquals("kind 必须是 DAILY 或 BATTLE", noKind.message);

        SessionKeeper.FormationActionResult badKind = keeper.formationAction(FPID, SID, UID,
                "formation.memory-save", mapOf("kind", "WEEKLY"));
        assertEquals(3002, badKind.code);

        SessionKeeper.FormationActionResult unknown = keeper.formationAction(FPID, SID, UID,
                "formation.unknown", null);
        assertEquals(3002, unknown.code);
        assertTrue(unknown.message.contains("未知 action"));

        // 缺 uid/sid → 3002
        assertEquals(3002, keeper.formationAction(FPID, null, UID,
                "formation.catalog-refresh", null).code);
        assertEquals(3002, keeper.formationAction(FPID, SID, null,
                "formation.catalog-refresh", null).code);
        // 账号不存在 → 1004
        assertEquals(1004, keeper.formationAction("nope", SID, UID,
                "formation.catalog-refresh", null).code);
    }

    @Test
    public void bearBinding_returnsDefaultJson() {
        SessionKeeper.FormationActionResult r = keeper.formationAction(FPID, SID, UID,
                "formation.bear-binding", null);
        assertEquals(0, r.code);
        assertFalse(r.data.getBoolean("applied"));
        assertEquals("", r.data.getString("templateId"));
        assertTrue(r.data.getJSONArray("joinFormationIds").isEmpty());
    }

    /** 统计 req_soldier_set_prefab（登录流程本身也会发 req_players_base_info/req_role_list）。 */
    private int countPrefabRequests() {
        int n = 0;
        for (String proto : engines.get(0).requestProtos) {
            if ("req_soldier_set_prefab".equals(proto)) n++;
        }
        return n;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> pair(long k, long v) {
        return mapOf("k", k, "v", v);
    }
}
