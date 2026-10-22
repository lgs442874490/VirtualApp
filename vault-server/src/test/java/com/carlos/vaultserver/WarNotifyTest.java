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
        // 不同动作（侦查）不受该 key 冷却影响
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(113, ENEMY_UID, 1, nowS, nowS + 100, 1, 1));
        assertEquals(2, events.size());
        assertEquals(WarNotify.ACT_RECON, events.after(0).get(1).optString("action"));
        // 已跟踪行军的 update 只刷新跟踪表，不产生新事件
        war.onPush(FPID, 888L, "notify_player_march_update",
                playerMarchAdd(111, ENEMY_UID, 2, nowS, nowS + 10, 1, 1));
        assertEquals(2, events.size());
    }

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

        // 到达：endtime 已过（del 用 122 自己的 id）
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(122, ENEMY_UID, 1, nowS - 100, nowS - 5, 7, 8));
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

    @Test
    public void onPush_massMarchIsRally() {
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_mass_march_add",
                playerMarchAdd(131, ENEMY_UID, 501, nowS, nowS + 600, 9, 9));
        assertEquals(1, events.size());
        assertEquals(WarNotify.ACT_RALLY, events.after(0).get(0).optString("action"));
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
        assertTrue(text.contains("notify_player_march_add"));

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
        war.onPush(FPID, 888L, "notify_mass_march_add",
                playerMarchAdd(161, ENEMY_UID, 501, 1, 2, 1, 1));
        assertFalse(Files.isRegularFile(tmp.getRoot().toPath().resolve("events.json.bak")));
        war.onPush(FPID, 888L, "notify_player_march_add",
                playerMarchAdd(162, ENEMY_UID, 2, 3, 4, 1, 1));
        assertTrue(Files.isRegularFile(tmp.getRoot().toPath().resolve("events.json.bak")));
    }
}
