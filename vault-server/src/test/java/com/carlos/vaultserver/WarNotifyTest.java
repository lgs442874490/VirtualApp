package com.carlos.vaultserver;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 战争通知单测（2026-10-22）：推送解析（递归候选/动作映射/坐标）、uid 命中匹配、
 * 冷却节流、事件环形缓冲持久化、静默时段、watch 配置校验与跨 sync 保留。
 */
public class WarNotifyTest {

    private static final String FPID = "48460403";
    private static final long ENEMY_UID = 57347368L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private WatchEventStore events;
    private Rec notifier;
    private WarNotify war;

    /** 记录型 notifier（同步模式，断言无竞态）。 */
    static final class Rec extends WeComNotifier {
        final List<String> sent = new ArrayList<>();
        boolean ok = true;

        @Override
        public boolean send(String url, String content) {
            sent.add(content);
            return ok;
        }
    }

    @Before
    public void setUp() throws Exception {
        Path dataDir = tmp.getRoot().toPath();
        store = new VaultStore(dataDir);
        store.load();
        JSONObject a = new JSONObject();
        a.put("fpid", FPID);
        JSONArray accounts = new JSONArray().put(a);
        store.replaceAll(listOf(accounts));
        events = new WatchEventStore(dataDir);
        events.load();
        notifier = new Rec();
        war = new WarNotify(store, events, notifier, false);
        // 默认开启监视并加一个目标
        store.updateWatchConfig(true, true, "", 300, -1, 8);
        store.addWatchTarget(FPID, String.valueOf(ENEMY_UID), "仇人A", "888", "宿敌");
    }

    private static List<VaultStore.Account> listOf(JSONArray arr) throws Exception {
        List<VaultStore.Account> out = new ArrayList<>();
        JSONArray parsed = new JSONArray(arr.toString());
        for (int i = 0; i < parsed.length(); i++) {
            JSONObject o = parsed.getJSONObject(i);
            VaultStore.Account acc = new VaultStore.Account(o.optString("fpid"));
            acc.token = "tk";
            out.add(acc);
        }
        return out;
    }

    private Map<String, Object> playerMarchAdd(long marchId, long owner, long type,
                                               long start, long end, int x, int y) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", owner);
        march.put("type", type);
        march.put("starttime", start);
        march.put("endtime", end);
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", x);
        ep.put("y", y);
        march.put("endpoint", ep);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", march);
        return content;
    }

    // ---------------- 推送解析：递归候选 ----------------

    @Test
    public void marchCandidates_playerMarchShape() {
        Map<String, Object> c = playerMarchAdd(101, ENEMY_UID, 2, 1000, 2000, 12, 34);
        List<Map<String, Object>> list = WarNotify.marchCandidates(c);
        assertEquals(1, list.size());
        assertEquals(101L, com.carlos.vaultserver.task.Exec.longOf(list.get(0).get("id")));
        assertEquals(ENEMY_UID, com.carlos.vaultserver.task.Exec.longOf(list.get(0).get("owner")));
    }

    @Test
    public void marchCandidates_worldSituationSlotShape() {
        // 观察哨系列实证形状：marches:{槽位:{march:{...}, power}}
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 9);
        march.put("owner", 42L);
        march.put("type", 1);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        slot.put("power", 12345);
        Map<String, Object> marches = new LinkedHashMap<>();
        marches.put("1.0", slot);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marches", marches);
        List<Map<String, Object>> list = WarNotify.marchCandidates(content);
        assertEquals(1, list.size());
        assertEquals(42L, com.carlos.vaultserver.task.Exec.longOf(list.get(0).get("owner")));
    }

    @Test
    public void marchCandidates_toMarchsListAndDataWrapper() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 7);
        m.put("owner", 8L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("to_marchs", new ArrayList<>(java.util.Collections.singletonList(m)));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("data", data);
        assertEquals(1, WarNotify.marchCandidates(content).size());
    }

    @Test
    public void marchCandidates_ignoresMapsWithoutOwnerOrId() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("id", 5);   // 无 owner
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", inner);
        assertEquals(0, WarNotify.marchCandidates(content).size());

        Map<String, Object> inner2 = new LinkedHashMap<>();
        inner2.put("owner", 5L);   // 无 id
        Map<String, Object> content2 = new LinkedHashMap<>();
        content2.put("march", inner2);
        assertEquals(0, WarNotify.marchCandidates(content2).size());
    }

    @Test
    public void idCandidates_findsDelIds() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", 77);
        d.put("type", 2);
        d.put("kid", 888);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", d);
        assertEquals(1, WarNotify.idCandidates(content).size());
    }

    // ---------------- 动作映射与坐标 ----------------

    @Test
    public void actionOf_mapping() {
        assertEquals(WarNotify.ACT_RECON, WarNotify.actionOf("notify_player_march_add", 1, false, 0));
        assertEquals(WarNotify.ACT_ATTACK, WarNotify.actionOf("notify_player_march_add", 2, false, 0));
        assertEquals(WarNotify.ACT_RALLY, WarNotify.actionOf("notify_mass_march_add", 501, false, 0));
        assertEquals(WarNotify.ACT_MARCH, WarNotify.actionOf("notify_player_march_add", 5, false, 0));
        assertEquals(WarNotify.ACT_RECALL, WarNotify.actionOf("x", 2, true, 60));
        assertEquals(WarNotify.ACT_ARRIVE, WarNotify.actionOf("x", 2, true, 1));
    }

    @Test
    public void endpointOf_endpointThenTargetFallback() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", 12);
        ep.put("y", 34);
        m.put("endpoint", ep);
        assertEquals(12, WarNotify.endpointOf(m)[0]);

        Map<String, Object> m2 = new LinkedHashMap<>();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("x", 5);
        t.put("y", 6);
        m2.put("target", t);
        assertEquals(6, WarNotify.endpointOf(m2)[1]);

        assertNull(WarNotify.endpointOf(new LinkedHashMap<>()));
    }

    // ---------------- 端到端：命中/节流/事件字段 ----------------

    @Test
    public void onPush_watchedOwnerEmitsEvent() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(101, ENEMY_UID, 2, nowS, nowS + 300, 12, 34));
        assertEquals(1, events.size());
        JSONObject e = events.after(0).get(0);
        assertEquals(FPID, e.optString("fpid"));
        assertEquals(888L, e.optLong("sid"));
        assertEquals(ENEMY_UID, e.optLong("targetUid"));
        assertEquals("宿敌", e.optString("targetName"));   // 备注 > 名称 > uid
        assertEquals(WarNotify.ACT_ATTACK, e.optString("action"));
        assertEquals(12, e.optJSONObject("endpoint").optInt("x"));
        assertEquals(nowS + 300, e.optLong("endtime"));
        assertEquals("notify_player_march_add", e.optString("rawProto"));
        assertFalse(e.optString("eventId").isEmpty());
    }

    /** 2026-11-04：监视名单是用户级的——敌方行军帧从其它账号/非生效范围角色回来也必须推送。 */
    @Test
    public void onPush_watchedOwnerFromOtherAccountOutOfScopeStillEmits() {
        long nowS = System.currentTimeMillis() / 1000L;
        // 名单生效范围只选了主账号的某个角色
        store.updateWatchRoleScope(List.of(35371154L));
        // 帧从另一个账号（86885582）的会话回来，角色不在生效范围，但 owner 命中全局监视名单
        war.onPush("86885582", 888L, 23954889L, "notify_player_march_add",
                playerMarchAdd(121, ENEMY_UID, 2, nowS, nowS + 300, 12, 34));
        assertEquals("跨账号视野的监视目标也要推送", 1, events.size());
        assertEquals(ENEMY_UID, events.after(0).get(0).optLong("targetUid"));
        // 不在范围、也不是监视目标的行军仍然静默（保留范围过滤的降噪作用）
        war.onPush("86885582", 888L, 23954889L, "notify_player_march_add",
                playerMarchAdd(122, 999L, 2, nowS, nowS + 300, 1, 1));
        assertEquals(1, events.size());
    }

    @Test
    public void onPush_unwatchedOrDisabledEmitsNothing() {
        long nowS = System.currentTimeMillis() / 1000L;
        // 未监视 uid
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(102, 999L, 2, nowS, nowS + 300, 1, 1));
        // 总开关关
        store.updateWatchConfig(false, true, "", 300, -1, 8);
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(103, ENEMY_UID, 2, nowS, nowS + 300, 1, 1));
        // 目标被停用
        store.updateWatchConfig(true, true, "", 300, -1, 8);
        store.updateWatchTarget(FPID, String.valueOf(ENEMY_UID), null, null, null, false);
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(104, ENEMY_UID, 2, nowS, nowS + 300, 1, 1));
        assertEquals(0, events.size());
    }

    @Test
    public void onPush_cooldownThrottlesSameTargetAndAction() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(111, ENEMY_UID, 2, nowS, nowS + 300, 1, 1));
        // 同 target+action 冷却内：第二支行军被节流
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(112, ENEMY_UID, 2, nowS, nowS + 200, 1, 1));
        assertEquals(1, events.size());
        // 侦查不再由战争警报推送（交给观察点），所以这里不该新增事件
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(113, ENEMY_UID, 2, nowS, nowS + 100, 1, 1));
        assertEquals(1, events.size());
        // 已跟踪行军的 update 只刷新跟踪表，不产生新事件
        war.onPush(FPID, 888L, "notify_player_march_update",
                playerMarchAdd(111, ENEMY_UID, 2, nowS, nowS + 10, 1, 1));
        assertEquals(1, events.size());
    }

    // ---------- 侦查类推送限流（用户 2026-11-01：5 分钟内每个敌人只推前 3 次侦查） ----------
    // 纯逻辑（滑动窗口/独立计数/恢复）单测在 App 侧 ScoutPushQuotaTest（本类由两侧同源编译）。

    @Test
    public void onPush_delRecallAndArrive() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(121, ENEMY_UID, 2, nowS, nowS + 300, 7, 8));
        // endtime 距今 300s > 2s → 撤回
        Map<String, Object> del = new LinkedHashMap<>();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", 121);
        d.put("type", 2);
        d.put("kid", 888);
        del.put("march", d);
        war.onPush(FPID, 888L, "notify_player_march_del", del);
        assertEquals(WarNotify.ACT_RECALL, events.after(0).get(1).optString("action"));
        assertEquals(7, events.after(0).get(1).optJSONObject("endpoint").optInt("x"));

        // 到达：endtime 已过（del 用 122 自己的 id；换目标避开冷却）
        long second = addSecondTarget();
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(122, second, 2, nowS - 100, nowS - 5, 7, 8));
        Map<String, Object> d122 = new LinkedHashMap<>();
        d122.put("id", 122);
        d122.put("type", 1);
        d122.put("kid", 888);
        Map<String, Object> del122 = new LinkedHashMap<>();
        del122.put("march", d122);
        war.onPush(FPID, 888L, "notify_player_march_del", del122);
        assertEquals(WarNotify.ACT_ARRIVE, events.after(0).get(3).optString("action"));

        // 未跟踪 id 的 del：静默
        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("id", 99999);
        Map<String, Object> del2 = new LinkedHashMap<>();
        del2.put("march", d2);
        war.onPush(FPID, 888L, "notify_player_march_del", del2);
        assertEquals(4, events.size());
    }

    // ================= 2026-11-02：非敌情行军不推（建设援助/采集/驻防返程误报） =================

    @Test
    public void isPvpMarch_onlyAttackAndRallyLeader() {
        assertTrue(WarNotify.isPvpMarch(2L));
        assertFalse("发起集结归 A1 情报（观察点）", WarNotify.isPvpMarch(100L));
        assertTrue("集结出征", WarNotify.isPvpMarch(102L));
        assertFalse("侦查走观察点", WarNotify.isPvpMarch(1L));
        assertFalse("加入集结不推", WarNotify.isPvpMarch(99L));
        assertFalse("集结中不推", WarNotify.isPvpMarch(10L));
        assertFalse("集结中不推", WarNotify.isPvpMarch(12L));
        assertFalse("采集不推", WarNotify.isPvpMarch(4L));
        assertFalse("驻防/援助不推", WarNotify.isPvpMarch(5L));
        assertFalse("狩猎不推", WarNotify.isPvpMarch(3L));
        assertFalse("未知类型不推", WarNotify.isPvpMarch(0L));
    }

    @Test
    public void onPush_rallyFollowerIsSilent() {
        long nowS = System.currentTimeMillis() / 1000L;
        // type=99 随从 / 带 mass_slave 的加入集结帧都不推
        war.onPush(FPID, 888L, "notify_world_marchs",
                playerMarchAdd(920, ENEMY_UID, 99, nowS, nowS + 300, 12, 34));
        Map<String, Object> c = playerMarchAdd(921, ENEMY_UID, 501, nowS, nowS + 300, 12, 34);
        @SuppressWarnings("unchecked")
        Map<String, Object> march = (Map<String, Object>) c.get("march");
        Map<String, Object> slave = new LinkedHashMap<>();
        slave.put("id", 921);
        march.put("mass_slave", slave);
        war.onPush(FPID, 888L, "notify_mass_march_add", c);
        assertEquals("加入集结不推", 0, events.size());
    }

    @Test
    public void onPush_nonPvpMarchIsSilent() {
        long nowS = System.currentTimeMillis() / 1000L;
        // 采集(4) / 驻防援助(5) / 类型缺失(0) 都不该产生事件
        war.onPush(FPID, 888L, "notify_world_marchs",
                playerMarchAdd(901, ENEMY_UID, 4, nowS, nowS + 600, 9, 9));
        war.onPush(FPID, 888L, "notify_world_marchs",
                playerMarchAdd(902, ENEMY_UID, 5, nowS, nowS + 600, 9, 9));
        war.onPush(FPID, 888L, "notify_world_marchs",
                playerMarchAdd(903, ENEMY_UID, 0, nowS, nowS + 600, 9, 9));
        assertEquals("非敌情行军一律不推", 0, events.size());
    }

    @Test
    public void onPush_returnToOwnCityIsSilent() {
        long nowS = System.currentTimeMillis() / 1000L;
        // 监视目标自己的城在 (569,420)：从 (281,603) 回自己城（建设援助返程）不是敌情
        com.carlos.vaultserver.watchpoint.WatchPointStore wps =
                new com.carlos.vaultserver.watchpoint.WatchPointStore(
                        java.nio.file.Paths.get(tmp.getRoot().getAbsolutePath()));
        com.carlos.vaultserver.watchpoint.WatchPointEngine wpe =
                new com.carlos.vaultserver.watchpoint.WatchPointEngine(null, wps, null, null);
        wpe.seedCityForTest(ENEMY_UID, 89700731L, 569, 420, "[818]南音_");
        com.carlos.vaultserver.watchpoint.WatchPointEngine.install(wpe);
        try {
            Map<String, Object> c = playerMarchAdd(904, ENEMY_UID, 2, nowS, nowS + 600, 569, 420);
            @SuppressWarnings("unchecked")
            Map<String, Object> march = (Map<String, Object>) c.get("march");
            Map<String, Object> sp = new LinkedHashMap<>();
            sp.put("x", 281L);
            sp.put("y", 603L);
            march.put("startpoint", sp);
            war.onPush(FPID, 888L, "notify_world_marchs", c);
            assertEquals("回自己城的行军不是敌情", 0, events.size());
        } finally {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.install(null);
        }
    }

    @Test
    public void onPush_roleScopeFiltersNotifications() {
        long nowS = System.currentTimeMillis() / 1000L;
        // 只让 uid=42 这个角色生效
        store.updateWatchRoleScope(List.of(42L));
        // 不在生效角色范围、且不是监视目标 → 不推（保留范围过滤的降噪作用）
        war.onPush(FPID, 888L, 7L, "notify_world_marchs",
                playerMarchAdd(905, 999L, 2, nowS, nowS + 600, 12, 34));
        assertEquals("不在范围且非监视目标 → 不推", 0, events.size());
        // 2026-11-04：监视目标优先——即使从非生效角色会话回来也要推（跨账号视野漏报修复）
        war.onPush(FPID, 888L, 7L, "notify_world_marchs",
                playerMarchAdd(906, ENEMY_UID, 2, nowS, nowS + 600, 12, 34));
        assertEquals("监视目标不受角色范围限制 → 推", 1, events.size());
        // 清空范围 = 全部角色（换目标避开冷却）
        store.updateWatchRoleScope(List.of());
        long secondR = addSecondTarget();
        war.onPush(FPID, 888L, 7L, "notify_world_marchs",
                playerMarchAdd(907, secondR, 2, nowS, nowS + 600, 12, 35));
        assertEquals("空范围=全部角色", 2, events.size());
    }

    @Test
    public void onPush_attackMarchStillAlerts() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_world_marchs",
                playerMarchAdd(905, ENEMY_UID, 2, nowS, nowS + 600, 12, 34));
        assertEquals("真实进攻仍要推", 1, events.size());
    }

    @Test
    public void onPush_massMarchIsRally() {
        long nowS = System.currentTimeMillis() / 1000L;
        installCity(ENEMY_UID, 1001L, 1, 1);       // 发起人城
        installCity(9002L, 2002L, 9, 9);           // 敌方目标城（不同联盟）
        try {
            war.onPush(FPID, 888L, "notify_mass_march_add",
                    playerMarchAdd(131, ENEMY_UID, 501, nowS, nowS + 600, 9, 9));
            assertEquals(1, events.size());
            assertEquals(WarNotify.ACT_RALLY, events.after(0).get(0).optString("action"));
        } finally {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.install(null);
        }
    }

    /** 再加一个监视目标（不同 target+action 冷却键用）。 */
    private long addSecondTarget() {
        long uid = ENEMY_UID + 9000;
        store.addWatchTarget(FPID, String.valueOf(uid), "仇人B", "888", "宿敌B");
        return uid;
    }

    /** 装一份城的临时观测点缓存（cityAt 用）。 */
    private void installCity(long uid, long aid, int x, int y) {
        installCities(new long[][]{{uid, aid, x, y}});
    }

    private void installCities(long[][] cities) {
        com.carlos.vaultserver.watchpoint.WatchPointStore wps =
                new com.carlos.vaultserver.watchpoint.WatchPointStore(
                        java.nio.file.Paths.get(tmp.getRoot().getAbsolutePath()));
        com.carlos.vaultserver.watchpoint.WatchPointEngine wpe =
                new com.carlos.vaultserver.watchpoint.WatchPointEngine(null, wps, null, null);
        for (long[] c : cities) wpe.seedCityForTest(c[0], c[1], (int) c[2], (int) c[3], "[T]nick");
        com.carlos.vaultserver.watchpoint.WatchPointEngine.install(wpe);
    }

    @Test
    public void onPush_scoutIsNotDuplicatedByWarNotify() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_world_marchs",
                playerMarchAdd(908, ENEMY_UID, 1, nowS, nowS + 120, 12, 34));
        assertEquals("侦查交给观察点推送，战争警报不重复", 0, events.size());
    }

    @Test
    public void onPush_sameAllianceRallyIsSilent() {
        long nowS = System.currentTimeMillis() / 1000L;
        installCities(new long[][]{{ENEMY_UID, 777L, 100, 100}, {ENEMY_UID + 1, 777L, 120, 120}});
        try {
            Map<String, Object> c = playerMarchAdd(909, ENEMY_UID, 100, nowS, nowS + 600, 120, 120);
            @SuppressWarnings("unchecked")
            Map<String, Object> march = (Map<String, Object>) c.get("march");
            Map<String, Object> sp = new LinkedHashMap<>();
            sp.put("x", 100L);
            sp.put("y", 100L);
            march.put("startpoint", sp);
            war.onPush(FPID, 888L, "notify_world_marchs", c);
            assertEquals("同联盟集结自己人是误报", 0, events.size());
        } finally {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.install(null);
        }
    }

    @Test
    public void onPush_monsterRallyIsSilent() {
        long nowS = System.currentTimeMillis() / 1000L;
        // mass_march 打巨兽：终点没有城
        war.onPush(FPID, 888L, "notify_mass_march_add",
                playerMarchAdd(910, ENEMY_UID, 501, nowS, nowS + 600, 700, 700));
        assertEquals("巨兽/野外集结不推", 0, events.size());
    }

    @Test
    public void onPush_wecomDispatchAndQuietHoursGate() {
        store.updateWatchConfig(true, true,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8);
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(141, ENEMY_UID, 2, nowS, nowS + 300, 3, 4));
        assertEquals(1, notifier.sent.size());
        String text = notifier.sent.get(0);
        assertTrue(text.contains("进攻"));
        assertTrue(text.contains("宿敌"));
        assertTrue(text.contains("(3,4)"));
        assertFalse("不再附协议名: " + text, text.contains("notify_player_march_add"));

        // 静默时段只抑制企微，事件照记；本机当前小时不在窗口内则无法直接断言跳过，
        // 这里只验证 inQuietHours 与人工时刻一致（同钟计算，无 flake）
        VaultStore.WatchConfig c = store.watchConfig();
        c.quietStart = -1;
        assertFalse(WarNotify.inQuietHours(c, System.currentTimeMillis()));
        c.quietStart = 8;
        c.quietEnd = 8;   // start==end 视为未配置
        assertFalse(WarNotify.inQuietHours(c, System.currentTimeMillis()));
        c.quietStart = 0;
        c.quietEnd = 23;
        int h = Calendar.getInstance(Beijing.TZ).get(Calendar.HOUR_OF_DAY);
        boolean expect = h >= 0 && h < 23;
        assertEquals(expect, WarNotify.inQuietHours(c, System.currentTimeMillis()));
    }

    @Test
    public void onPush_wecomDisabledOrNoUrlSkips() {
        store.updateWatchConfig(true, false, "", 300, -1, 8);
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(151, ENEMY_UID, 2, nowS, nowS + 60, 1, 1));
        assertEquals(0, notifier.sent.size());
        assertEquals(1, events.size());   // 事件照记
    }

    // ---------------- 环形缓冲持久化与游标 ----------------

    @Test
    public void eventStore_ringCapAndReload() throws Exception {
        Path dataDir = tmp.getRoot().toPath();
        WatchEventStore s = new WatchEventStore(dataDir.resolve("sub"));
        s.load();
        for (int i = 0; i < WatchEventStore.CAP + 5; i++) {
            JSONObject e = new JSONObject();
            e.put("i", i);
            s.append(e);
        }
        assertEquals(WatchEventStore.CAP, s.size());
        assertEquals(WatchEventStore.CAP + 5, s.latestSeq());
        // 重启后 seq 不回退、事件保留
        WatchEventStore s2 = new WatchEventStore(dataDir.resolve("sub"));
        s2.load();
        assertEquals(WatchEventStore.CAP, s2.size());
        assertEquals(WatchEventStore.CAP + 5, s2.latestSeq());
        // 游标语义：after(latest) 为空；after(latest-3) 恰 3 条且最旧在前
        assertTrue(s2.after(s2.latestSeq()).isEmpty());
        List<JSONObject> tail = s2.after(s2.latestSeq() - 3);
        assertEquals(3, tail.size());
        assertTrue(tail.get(0).optLong("seq") < tail.get(1).optLong("seq"));
    }

    @Test
    public void eventStore_fetchLimitCapsPage() {
        WatchEventStore s = new WatchEventStore(tmp.getRoot().toPath().resolve("p2"));
        s.load();
        for (int i = 0; i < 150; i++) s.append(new JSONObject());
        assertEquals(WatchEventStore.FETCH_LIMIT, s.after(0).size());
    }

    // ---------------- watch 配置与名单持久化 ----------------

    @Test
    public void watchConfig_validation() {
        assertNull(VaultStore.validateWatchConfig("", 300, -1, 8));
        assertNull(VaultStore.validateWatchConfig(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 60, 23, 8));
        assertEquals("webhook 地址须为 https://qyapi.weixin.qq.com/ 开头的企业微信机器人地址",
                VaultStore.validateWatchConfig("https://evil.example.com/hook", 300, -1, 8));
        assertEquals("通知冷却须在 60 到 3600 秒之间",
                VaultStore.validateWatchConfig("", 10, -1, 8));
        // 本地 mock 默认拒绝（联调需设 VAULT_WATCH_WEBHOOK_ALLOW，见 watchConfig_webhookAllowEnv）
        assertEquals("webhook 地址须为 https://qyapi.weixin.qq.com/ 开头的企业微信机器人地址",
                VaultStore.validateWatchConfig("http://127.0.0.1:9999/hook", 300, -1, 8));
    }

    @Test
    public void watchConfig_webhookAllowEnv() {
        String msg = "webhook 地址须为 https://qyapi.weixin.qq.com/ 开头的企业微信机器人地址";
        // 默认白名单为空：内网/mock 一律拒绝
        assertEquals(msg, VaultStore.validateWatchConfig("http://192.168.1.5:8080/hook", 300, -1, 8));
        System.setProperty("VAULT_WATCH_WEBHOOK_ALLOW", "http://127.0.0.1, http://192.168.1.5:8080/");
        try {
            // 白名单前缀（逗号分隔、容忍空格）命中即放行；前缀外仍拒绝
            assertNull(VaultStore.validateWatchConfig("http://127.0.0.1:9999/hook", 300, -1, 8));
            assertNull(VaultStore.validateWatchConfig("http://192.168.1.5:8080/hook", 300, -1, 8));
            assertEquals(msg, VaultStore.validateWatchConfig("http://10.0.0.9/hook", 300, -1, 8));
            // 官方域名不受白名单影响，始终放行
            assertNull(VaultStore.validateWatchConfig(
                    "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8));
        } finally {
            System.clearProperty("VAULT_WATCH_WEBHOOK_ALLOW");
        }
    }

    @Test
    public void watchConfig_persistAndClamp() throws Exception {
        store.updateWatchConfig(true, false,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k", 30, 25, 9);
        Path dataDir = tmp.getRoot().toPath();
        VaultStore s2 = new VaultStore(dataDir);
        s2.load();
        VaultStore.WatchConfig c = s2.watchConfig();
        assertTrue(c.enabled);
        assertFalse(c.wecomEnabled);
        assertTrue(c.webhookUrl.contains("qyapi"));
        assertEquals(60, c.cooldownSeconds);   // 30 收敛到下限 60
        assertEquals(23, c.quietStart);        // 25 收敛到 23
    }

    @Test
    public void watchTargets_crudPersistAndSurviveSync() throws Exception {
        assertEquals(0, store.addWatchTarget(FPID, "123", "名", "888", "注"));
        assertEquals(3, store.addWatchTarget(FPID, "123", "", "", ""));   // 重复
        assertEquals(2, store.addWatchTarget(FPID, "abc", "", "", ""));   // 非法 uid
        assertEquals(1, store.addWatchTarget("no-such", "1", "", "", ""));
        assertEquals(0, store.addWatchTarget(FPID, "456", "", "", ""));

        assertTrue(store.updateWatchTarget(FPID, "456", null, "999", "改", false));
        VaultStore.WatchTarget t456 = store.matchWatched(FPID, 456L);
        assertNull(t456);   // enabled=false 不命中

        // 落盘重载
        Path dataDir = tmp.getRoot().toPath();
        VaultStore s2 = new VaultStore(dataDir);
        s2.load();
        assertEquals(3, s2.watchTargets(FPID).size());
        for (VaultStore.WatchTarget t : s2.watchTargets(FPID)) {
            if (t.uid.equals("456")) {
                assertEquals("999", t.kid);
                assertFalse(t.enabled);
            }
        }
        assertTrue(s2.removeWatchTarget(FPID, "456"));
        assertFalse(s2.removeWatchTarget(FPID, "456"));

        // App sync（不带 watchTargets）不冲掉服务器侧名单
        List<VaultStore.Account> incoming = new ArrayList<>();
        VaultStore.Account acc = new VaultStore.Account(FPID);
        acc.token = "tk2";
        incoming.add(acc);
        s2.replaceAll(incoming);
        assertEquals(2, s2.watchTargets(FPID).size());
    }

    @Test
    public void sendTest_requiresWebhook() {
        assertFalse(war.sendTest());   // 未配 webhook
        assertEquals(0, notifier.sent.size());
        store.updateWatchConfig(true, true,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8);
        assertTrue(war.sendTest());
        assertEquals(1, notifier.sent.size());
        assertTrue(notifier.sent.get(0).contains("测试推送"));
    }

    @Test
    public void onPush_garbagePayloadDoesNotThrow() {
        war.onPush(FPID, 888L, "notify_player_march_add", new LinkedHashMap<>());
        war.onPush(FPID, 888L, "notify_player_march_add", null);
        war.onPush(FPID, 888L, null, playerMarchAdd(1, ENEMY_UID, 2, 1, 2, 1, 1));
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("march", "not-a-map");
        weird.put("junk", java.util.Collections.singletonList(java.util.Collections
                .singletonMap("id", 5)));
        war.onPush(FPID, 888L, "notify_player_march_add", weird);
        // junk 里的 {id:5} 无 owner，不产生事件
        assertEquals(0, events.size());
    }

    @Test
    public void eventsStore_bakFileWritten() throws Exception {
        // 首次写只有主文件（.bak 是旧主文件轮转）；第二次写起 .bak 出现。
        // 两推送用不同动作（集结/进攻），避免撞同 target+action 的冷却节流
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(161, ENEMY_UID, 2, 3, 4, 1, 1));
        assertFalse(Files.isRegularFile(tmp.getRoot().toPath().resolve("events.json.bak")));
        long second = ENEMY_UID;
        for (int i = 0; i < 2; i++) { second = ENEMY_UID + 1000 + i; }
        store.addWatchTarget(FPID, String.valueOf(second), "仇人B", "888", "宿敌B");
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(162, second, 2, 3, 4, 5, 6));
        assertTrue(Files.isRegularFile(tmp.getRoot().toPath().resolve("events.json.bak")));
    }

    // ================= v4.1.8：行为/兵力/发起人坐标/昵称/ETA =================

    /** 计数型昵称解析 mock（不真实请求引擎）。 */
    static final class NameRes implements WarNotify.NameResolver {
        final Map<Long, String> names = new LinkedHashMap<>();
        final List<Long> calls = new ArrayList<>();

        @Override
        public String resolve(String fpid, long sid, long uid) {
            calls.add(uid);
            return names.getOrDefault(uid, "");
        }
    }

    /** 组装带 startpoint/squad_list 的 march map（不经 content 包装）。 */
    private Map<String, Object> fullMarch(long marchId, long owner, long type, long start, long end,
                                          int ex, int ey, int sx, int sy, Map<Long, Long> squad) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", owner);
        march.put("type", type);
        march.put("starttime", start);
        march.put("endtime", end);
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", ex);
        ep.put("y", ey);
        march.put("endpoint", ep);
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("x", sx);
        sp.put("y", sy);
        march.put("startpoint", sp);
        if (squad != null) {
            Map<String, Object> soldier = new LinkedHashMap<>();
            for (Map.Entry<Long, Long> en : squad.entrySet()) {
                soldier.put(String.valueOf(en.getKey()), en.getValue());
            }
            Map<String, Object> squadList = new LinkedHashMap<>();
            squadList.put("soldier", soldier);
            march.put("squad_list", squadList);
        }
        return march;
    }

    private Map<String, Object> content(Map<String, Object> march) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("march", march);
        return c;
    }

    @Test
    public void behaviorOf_mapping() {
        assertEquals(WarNotify.BEH_RECON, WarNotify.behaviorOf("notify_player_march_add", 1));
        assertEquals(WarNotify.BEH_ATTACK, WarNotify.behaviorOf("notify_player_march_add", 2));
        assertEquals(WarNotify.BEH_RALLY, WarNotify.behaviorOf("notify_mass_march_add", 501));
        assertEquals(WarNotify.BEH_GARRISON, WarNotify.behaviorOf("notify_player_march_add", 5));
        assertEquals(WarNotify.BEH_MARCH, WarNotify.behaviorOf("notify_player_march_add", 3));
    }

    @Test
    public void troopsOf_mapListAndMissingShapes() {
        // map：键=id 值=数量
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000", 1200);
        // 值={count:n} 形状
        Map<String, Object> cnt = new LinkedHashMap<>();
        cnt.put("count", 800);
        soldier.put("20900", cnt);
        Map<String, Object> squad = new LinkedHashMap<>();
        squad.put("soldier", soldier);
        m.put("squad_list", squad);
        assertEquals("盾T10×1200 矛T9×800（合计2000）", WarNotify.troopsOf(m));

        // list：元素={id,count}
        Map<String, Object> m2 = new LinkedHashMap<>();
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("id", 30800);
        e1.put("count", 500);
        Map<String, Object> squad2 = new LinkedHashMap<>();
        squad2.put("soldier", new ArrayList<>(java.util.Collections.singletonList(e1)));
        m2.put("squad_list", squad2);
        assertEquals("弓T8×500（合计500）", WarNotify.troopsOf(m2));

        // 无 squad 数据 = 未知
        assertEquals(WarNotify.TROOPS_UNKNOWN, WarNotify.troopsOf(new LinkedHashMap<>()));
    }

    @Test
    public void soldierLabel_kindsAndTiers() {
        assertEquals("盾T10", WarNotify.soldierLabel(11000));
        assertEquals("盾T11", WarNotify.soldierLabel(11100));
        assertEquals("矛T9", WarNotify.soldierLabel(20900));
        assertEquals("弓T8", WarNotify.soldierLabel(30800));
        assertEquals("兵9", WarNotify.soldierLabel(90000));
    }

    @Test
    public void startPointOf_parsesStartpoint() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("x", 5);
        sp.put("y", 6);
        m.put("startpoint", sp);
        assertEquals(5, WarNotify.startPointOf(m)[0]);
        assertEquals(6, WarNotify.startPointOf(m)[1]);
        assertNull(WarNotify.startPointOf(new LinkedHashMap<>()));
    }

    @Test
    public void onPush_initialEventCarriesEnrichedFields() {
        long nowS = System.currentTimeMillis() / 1000L;
        Map<Long, Long> squad = new LinkedHashMap<>();
        squad.put(11000L, 1200L);
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(201, ENEMY_UID, 2, nowS - 60, nowS + 300, 3, 4, 5, 6, squad)));
        assertEquals(1, events.size());
        JSONObject e = events.after(0).get(0);
        assertEquals("宿敌", e.optString("attackerName"));   // 解析前回退监视备注
        assertEquals("进攻城堡", e.optString("behavior"));
        assertEquals("盾T10×1200（合计1200）", e.optString("troops"));
        assertEquals(5, e.optJSONObject("startPoint").optInt("x"));
        assertEquals(6, e.optJSONObject("startPoint").optInt("y"));
        long eta = e.optLong("etaSeconds");
        assertTrue("eta 应在 296~300： " + eta, eta >= 296 && eta <= 300);
        assertEquals((nowS + 300) * 1000L, e.optLong("dueAt"));
        assertEquals(WarNotify.PHASE_ACTION, e.optString("phase"));
        assertEquals(201L, e.optLong("marchId"));
        assertEquals("宿敌", e.optString("targetName"));   // v4.1.7 兼容字段保留
    }

    @Test
    public void nameResolver_cachedAcrossEvents() throws Exception {
        NameRes res = new NameRes();
        res.names.put(ENEMY_UID, "张三");
        war.setNameResolver(res);
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(211, ENEMY_UID, 2, nowS, nowS + 300, 1, 1));
        // 异步解析回填（nameExec 线程）
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && !"张三".equals(war.trackedNameForTest(211))) {
            Thread.sleep(20);
        }
        assertEquals("张三", war.trackedNameForTest(211));
        assertEquals(1, res.calls.size());
        // 第二支行军（同 uid、不同动作=集结，避开冷却）：缓存命中直接带昵称，不再发解析请求
        installCity(9002L, 2002L, 5, 6);
        try {
            war.onPush(FPID, 888L, "notify_player_march_add",
                    playerMarchAdd(212, ENEMY_UID, 102, nowS, nowS + 100, 5, 6));
        } finally {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.install(null);
        }
        assertEquals("张三", events.after(0).get(1).optString("attackerName"));
        assertEquals(1, res.calls.size());
    }

    @Test
    public void nameResolver_failureFallsBackAndDoesNotRepeat() throws Exception {
        NameRes res = new NameRes();   // 空 names → 解析失败返回空串
        war.setNameResolver(res);
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(221, ENEMY_UID, 2, nowS, nowS + 300, 1, 1));
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !war.nameCachedForTest(FPID, ENEMY_UID)) {
            Thread.sleep(20);
        }
        assertEquals("宿敌", war.trackedNameForTest(221));   // 回退监视备注
        assertEquals(1, res.calls.size());
        // 失败也缓存（10min）：后续事件不再反复请求（换动作=集结，避开冷却）
        installCity(9002L, 2002L, 5, 6);
        try {
            war.onPush(FPID, 888L, "notify_player_march_add",
                    playerMarchAdd(222, ENEMY_UID, 102, nowS, nowS + 100, 5, 6));
        } finally {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.install(null);
        }
        Thread.sleep(200);
        assertEquals(1, res.calls.size());
        assertEquals("宿敌", events.after(0).get(1).optString("attackerName"));
    }

    @Test
    public void nameResolver_asyncBackfillReachesSettle() throws Exception {
        NameRes res = new NameRes();
        res.names.put(ENEMY_UID, "李四");
        war.setNameResolver(res);
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(231, ENEMY_UID, 2, nowS - 300, nowS - 10, 7, 8));
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !"李四".equals(war.trackedNameForTest(231))) {
            Thread.sleep(20);
        }
        // 到达结算（单测同步模式 grace=0）：结算事件昵称=已解析昵称
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(231));
        JSONObject settle = events.after(0).get(1);
        assertEquals(WarNotify.PHASE_SETTLE, settle.optString("phase"));
        assertEquals("李四", settle.optString("attackerName"));
    }

    // ================= v4.1.8：完成结算（duration/outcome/breach/battleCodes） =================

    private Map<String, Object> delContent(long marchId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", marchId);
        d.put("type", 2);
        d.put("kid", 888);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("march", d);
        return c;
    }

    private Map<String, Object> mailContent(long first, long second) {
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("first_type", first);
        cover.put("second_type", second);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("mail_cover_info", cover);
        return c;
    }

    @Test
    public void onPush_settleArrivalWithoutMailIsNotOwnDefense() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(241, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(241));
        assertEquals(2, events.size());
        JSONObject settle = events.after(0).get(1);
        assertEquals(WarNotify.ACT_ARRIVE, settle.optString("action"));
        assertEquals("已到达", settle.optString("outcome"));
        assertEquals(WarNotify.PHASE_SETTLE, settle.optString("phase"));
        long duration = settle.optLong("durationSeconds");
        assertTrue("耗时约 300s： " + duration, duration >= 295 && duration <= 310);
        assertEquals(WarNotify.BREACH_NOT_OWN, settle.optString("breach"));   // 本会话无战报
        assertTrue(settle.optString("battleCodes").isEmpty());
    }

    @Test
    public void onPush_settleRecallImmediateWithDuration() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(251, ENEMY_UID, 2, nowS - 100, nowS + 300, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(251));
        JSONObject settle = events.after(0).get(1);
        assertEquals(WarNotify.ACT_RECALL, settle.optString("action"));
        assertEquals("撤回", settle.optString("outcome"));
        long duration = settle.optLong("durationSeconds");
        assertTrue("耗时约 100s： " + duration, duration >= 95 && duration <= 110);
        assertFalse(settle.has("breach"));   // 撤回无破城概念
        assertEquals(2, events.size());
    }

    @Test
    public void onPush_settleBreachKnownCodes() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(261, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        // 防守败（second=6）→ 破城=是
        war.onPush(FPID, 888L, "notify_new_mail_syn", mailContent(30, 6));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(261));
        JSONObject settle = events.after(0).get(1);
        assertEquals(WarNotify.BREACH_YES, settle.optString("breach"));
        assertEquals("30/6", settle.optString("battleCodes"));

        // 防守胜（second=5）→ 未破城（新 march，冷却 key 区分：先清冷却）
        war.resetCooldownForTest();
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(262, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_new_mail_syn", mailContent(30, 5));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(262));
        JSONObject settle2 = events.after(0).get(3);
        assertEquals(WarNotify.BREACH_NO, settle2.optString("breach"));
        assertEquals("30/5", settle2.optString("battleCodes"));
    }

    @Test
    public void onPush_settleBreachUnknownCodesAndNonDefenseFirst() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(271, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        // 未收录码（second=1，实测防守样本）→ 未知 + battleCodes 原样附
        war.onPush(FPID, 888L, "notify_new_mail_syn", mailContent(30, 1));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(271));
        JSONObject settle = events.after(0).get(1);
        assertEquals(WarNotify.BREACH_UNKNOWN, settle.optString("breach"));
        assertEquals("30/1", settle.optString("battleCodes"));

        // 非 30 的 first 语义未校准 → 未知（battleCodes 照附）
        war.resetCooldownForTest();
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(272, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_new_mail_syn", mailContent(31, 6));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(272));
        JSONObject settle2 = events.after(0).get(3);
        assertEquals(WarNotify.BREACH_UNKNOWN, settle2.optString("breach"));
        assertEquals("31/6", settle2.optString("battleCodes"));
    }

    @Test
    public void breachOf_mapping() {
        assertEquals(WarNotify.BREACH_NO, WarNotify.breachOf(30, 4));
        assertEquals(WarNotify.BREACH_NO, WarNotify.breachOf(30, 5));
        assertEquals(WarNotify.BREACH_YES, WarNotify.breachOf(30, 6));
        assertEquals(WarNotify.BREACH_YES, WarNotify.breachOf(30, 8));
        assertEquals(WarNotify.BREACH_UNKNOWN, WarNotify.breachOf(30, 1));
        assertEquals(WarNotify.BREACH_UNKNOWN, WarNotify.breachOf(31, 6));
    }

    @Test
    public void onPush_settleAndInitCooldownIndependent() {
        long nowS = System.currentTimeMillis() / 1000L;
        // 第一轮：初始 + 结算各一条（冷却 key 带 |done 区分）
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(281, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(281));
        assertEquals(2, events.size());
        // 第二轮（冷却窗口内）：初始动作与完成结算分别被各自 key 节流
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(282, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(282));
        assertEquals(2, events.size());
    }

    @Test
    public void onPush_mailPushProducesNoMarchEvents() {
        war.onPush(FPID, 888L, "notify_new_mail_syn", mailContent(30, 6));
        // 邮件 content 即便混入 {id,owner} 形状也不产生行军事件
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("id", 9);
        weird.put("owner", ENEMY_UID);
        Map<String, Object> c = mailContent(30, 6);
        c.put("junk", weird);
        war.onPush(FPID, 888L, "notify_new_mail_syn", c);
        assertEquals(0, events.size());
    }

    @Test
    public void fmtDuration_andDisplayOutcome() {
        assertEquals("45 秒", WarNotify.fmtDuration(45));
        assertEquals("5 分 30 秒", WarNotify.fmtDuration(330));
        assertEquals("0 秒", WarNotify.fmtDuration(-3));
        assertEquals("已撤回", WarNotify.displayOutcome("撤回"));
        assertEquals("已到达", WarNotify.displayOutcome("已到达"));
    }

    // ================= v4.1.8：企微文案（用户 6 项 + 结算追加） =================

    @Test
    public void wecomText_containsSixItems() {
        long nowS = System.currentTimeMillis() / 1000L;
        store.updateWatchConfig(true, true,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8);
        Map<Long, Long> squad = new LinkedHashMap<>();
        squad.put(11000L, 1200L);
        squad.put(20900L, 800L);
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(301, ENEMY_UID, 2, nowS - 60, nowS + 300, 3, 4, 5, 6, squad)));
        assertEquals(1, notifier.sent.size());
        String text = notifier.sent.get(0);
        assertTrue("标题: " + text, text.contains("🚨 敌袭·进攻"));
        assertTrue("级别: " + text, text.contains("级别：🔴 威胁进攻"));
        assertTrue("攻击者: " + text, text.contains("攻击者：宿敌"));
        assertFalse("通知里不带 uid/aid 编号: " + text,
                text.contains("uid") || text.contains("aid"));
        assertTrue("兵力总数: " + text, text.contains("兵力：2000"));
        assertTrue("目标坐标: " + text, text.contains("(3,4)"));
        assertTrue("ETA: " + text, text.contains("ETA："));
        assertTrue("时间: " + text, text.contains("时间："));
        assertFalse("不再附来源协议名（技术噪声）: " + text, text.contains("来源："));
    }

    @Test
    public void wecomText_targetShowsDefenderNickAndAlliance() {
        long nowS = System.currentTimeMillis() / 1000L;
        store.updateWatchConfig(true, true,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8);
        // 整图城缓存里已经有 (3,4) 这座城：地图昵称自带联盟简称前缀
        com.carlos.vaultserver.watchpoint.WatchPointStore wps =
                new com.carlos.vaultserver.watchpoint.WatchPointStore(
                        java.nio.file.Paths.get(tmp.getRoot().getAbsolutePath()));
        com.carlos.vaultserver.watchpoint.WatchPointEngine wpe =
                new com.carlos.vaultserver.watchpoint.WatchPointEngine(null, wps, null, null);
        wpe.seedCityForTest(19694427L, 89700055L, 3, 4, "[SYD]小淑女");
        com.carlos.vaultserver.watchpoint.WatchPointEngine.install(wpe);
        try {
            war.onPush(FPID, 888L, "notify_player_march_add", content(
                    fullMarch(321, ENEMY_UID, 2, nowS - 60, nowS + 300, 3, 4, 5, 6, null)));
            assertEquals(1, notifier.sent.size());
            String text = notifier.sent.get(0);
            assertTrue("目标行要带被攻击人昵称+简称: " + text, text.contains("目标：[SYD]小淑女(3,4)"));
        } finally {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.install(null);
        }
    }

    @Test
    public void wecomText_settleAppendsOutcomeLine() {
        long nowS = System.currentTimeMillis() / 1000L;
        store.updateWatchConfig(true, true,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8);
        war.onPush(FPID, 888L, "notify_player_march_add", content(
                fullMarch(311, ENEMY_UID, 2, nowS - 300, nowS - 10, 3, 4, 5, 6, null)));
        war.onPush(FPID, 888L, "notify_new_mail_syn", mailContent(30, 6));
        war.onPush(FPID, 888L, "notify_player_march_del", delContent(311));
        // 2026-11-04：结算不再推企微（只推发起一条），但结算事件仍入库
        assertEquals("只推发起一条", 1, notifier.sent.size());
        String init = notifier.sent.get(0);
        assertTrue(init.contains("🚨 敌袭·进攻"));
        assertFalse("结算内容不进推送", init.contains("🏁 进攻结算"));
        assertEquals("结算事件仍入库供 App 看", 2, events.size());
    }

    @Test
    public void onPush_allianceSituationFrameMatchesWatchedOwner() {
        long nowS = System.currentTimeMillis() / 1000L;
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 48714L);
        march.put("owner", ENEMY_UID);
        march.put("type", 2L);
        march.put("starttime", nowS);
        march.put("endtime", nowS + 600L);
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("x", 779);
        sp.put("y", 429);
        march.put("startpoint", sp);
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", 758);
        ep.put("y", 435);
        march.put("endpoint", ep);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marchs", new ArrayList<>(java.util.Collections.singletonList(march)));
        content.put("to_marchs", new ArrayList<>());

        store.updateWatchConfig(true, true,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", 300, -1, 8);
        war.onPush(FPID, 888L, "notify_world_alliance_situation_add", content);
        assertEquals("联盟态势推送命中监视目标必须产生事件", 1, events.size());
        JSONObject e = events.after(0).get(0);
        assertEquals(ENEMY_UID, e.optLong("targetUid"));
        assertEquals(WarNotify.ACT_ATTACK, e.optString("action"));
        assertEquals(779, e.optJSONObject("startPoint").optInt("x"));
        assertEquals("企微也必须发出", 1, notifier.sent.size());
    }

    @Test
    public void onPush_allianceSituationToMarchsMatchesWatchedOwner() {
        long nowS = System.currentTimeMillis() / 1000L;
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 48715L);
        march.put("owner", ENEMY_UID);
        march.put("type", 2L);
        march.put("starttime", nowS);
        march.put("endtime", nowS + 300L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marchs", new ArrayList<>());
        content.put("to_marchs", new ArrayList<>(java.util.Collections.singletonList(march)));
        war.onPush(FPID, 888L, "notify_world_alliance_situation_update", content);
        assertEquals("to_marchs（敌方进攻盟友）也必须命中", 1, events.size());
        assertEquals(ENEMY_UID, events.after(0).get(0).optLong("targetUid"));
    }
}
