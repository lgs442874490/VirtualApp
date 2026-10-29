package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R2 Phase 2：采集按角色隔离（CaptureTasks）。
 *
 * <p>锁定契约：
 * <ul>
 *   <li>{@code res:/todo:/rallies:/questprog:/questdone:/questts:/mod:*} 写角色键，双角色互不覆盖；</li>
 *   <li>旧键只由 active 角色镜像写（App 显示链路不变）；</li>
 *   <li>旧 4 参签名（uid=0）行为与 Phase 2 之前 100% 一致：只写旧键。</li>
 * </ul>
 */
public class CaptureTasksPerRoleTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long UID_A = 1001L;
    private static final long UID_B = 2002L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }

    /** 日历响应：family = id/1000，schedule 在未来 1 小时开启。 */
    private static Map<String, Object> calendar(long id) {
        long now = System.currentTimeMillis() / 1000L;
        long begin = now + 3600;
        Map<String, Object> schedule = new HashMap<>();
        schedule.put("begin_ts", begin);
        schedule.put("end_ts", begin + 7200);
        Map<String, Object> act = new HashMap<>();
        act.put("id", id);
        act.put("schedule", java.util.Arrays.asList(schedule));
        Map<String, Object> entry = new HashMap<>();
        entry.put(String.valueOf(id), act);
        Map<String, Object> content = new HashMap<>();
        content.put("data", entry);
        return content;
    }

    private static Map<String, Object> resources(long count) {
        Map<String, Object> data = new HashMap<>();
        data.put("1", count);
        return data;
    }

    private static Map<String, Object> quests(long progress) {
        Map<String, Object> q = new HashMap<>();
        q.put("progress", progress);
        Map<String, Object> quests = new LinkedHashMap<>();
        quests.put("1320000", q);
        Map<String, Object> data = new HashMap<>();
        data.put("quests", quests);
        data.put("finishs", java.util.Arrays.asList(1320010L));
        data.put("integral", 270L);
        return data;
    }

    private void setCalendar(CaptureTasksTest.FakeEngine e, long id) {
        e.responses.put("req_activity_calendar_get_data", msg(calendar(id)));
    }

    private long num(String key) {
        Object v = store.snapshot().get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1L;
    }

    @Test
    public void twoRoles_resourceAndTodoAndModIsolated() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        e.notifies.put("notify_recover_resource_init", resources(100L));
        setCalendar(e, 2705001L);   // 烈焰与獠牙 / act_flamefangs

        CaptureTasks.captureAll(e, store, FPID, SID, UID_A, false);

        e.notifies.put("notify_recover_resource_init", resources(200L));
        setCalendar(e, 2436001L);   // 投壶高手 / act_pitchpot
        CaptureTasks.captureAll(e, store, FPID, SID, UID_B, false);

        // res 独立
        assertEquals(100L, new JSONObject(store.getString("res:111:913:1001")).optLong("1"));
        assertEquals(200L, new JSONObject(store.getString("res:111:913:2002")).optLong("1"));
        // todo 独立
        JSONArray todoA = new JSONArray(store.getString("todo:111:913:1001"));
        assertEquals("烈焰与獠牙", todoA.getJSONObject(0).getString("name"));
        JSONArray todoB = new JSONArray(store.getString("todo:111:913:2002"));
        assertEquals("投壶高手", todoB.getJSONObject(0).getString("name"));
        // 采集附属 mod 键独立
        assertNotNull(store.getString("mod:111:913:1001:act_flamefangs"));
        assertNotNull(store.getString("mod:111:913:2002:act_pitchpot"));

        // 非 active 角色不写旧键（避免双角色互相覆盖 App 显示）
        assertNull(store.getString("res:111:913"));
        assertNull(store.getString("todo:111:913"));
        assertNull(store.getString("mod:111:913:act_flamefangs"));
        assertNull(store.getString("mod:111:913:act_pitchpot"));
    }

    @Test
    public void twoRoles_questStateIsolated() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        e.notifies.put("notify_dailyquests", quests(3L));

        CaptureTasks.captureAll(e, store, FPID, SID, UID_A, false);

        e.notifies.put("notify_dailyquests", quests(7L));
        CaptureTasks.captureAll(e, store, FPID, SID, UID_B, false);

        assertEquals(3L, new JSONObject(store.getString("questprog:111:913:1001")).optLong("1320000"));
        assertEquals(7L, new JSONObject(store.getString("questprog:111:913:2002")).optLong("1320000"));
        // act/boxes/questts 也按角色键
        assertEquals(270L, num("act:111:913:1001"));
        assertEquals(270L, num("act:111:913:2002"));
        assertEquals(6L, num("boxes:111:913:1001"));
        assertNotNull(store.snapshot().get("questts:111:913:1001"));
        // 旧键不被非 active 角色写
        assertNull(store.getString("questprog:111:913"));
        assertNull(store.snapshot().get("act:111:913"));
    }

    @Test
    public void activeRole_mirrorsLegacyKeys() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        long nowSec = System.currentTimeMillis() / 1000L;
        Map<String, Object> ri = resources(50L);
        ri.put("online_next_ts", nowSec + 1800);
        e.notifies.put("notify_recover_resource_init", ri);
        e.notifies.put("notify_dailyquests", quests(5L));
        Map<String, Object> frostInfo = new HashMap<>();
        frostInfo.put("sign_up_index", 2L);
        Map<String, Object> frostContent = new HashMap<>();
        frostContent.put("act_info", frostInfo);
        e.notifies.put("notify_frostfire_war_info", frostContent);
        setCalendar(e, 2705001L);

        CaptureTasks.captureAll(e, store, FPID, SID, UID_A, true);

        // 角色键 + 旧键同时存在且一致（App 仍读旧键）
        assertEquals(store.getString("res:111:913:1001"), store.getString("res:111:913"));
        assertEquals(store.getString("todo:111:913:1001"), store.getString("todo:111:913"));
        assertEquals(store.getString("questprog:111:913:1001"), store.getString("questprog:111:913"));
        assertEquals(store.getString("mod:111:913:1001:supply"), store.getString("mod:111:913:supply"));
        assertEquals(store.getString("mod:111:913:1001:frostfire"),
                store.getString("mod:111:913:frostfire"));
        assertEquals(store.getString("mod:111:913:1001:act_flamefangs"),
                store.getString("mod:111:913:act_flamefangs"));
    }

    @Test
    public void legacySignature_writesLegacyKeysOnly() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        e.notifies.put("notify_recover_resource_init", resources(77L));
        e.notifies.put("notify_dailyquests", quests(4L));
        setCalendar(e, 2705001L);

        assertNull(CaptureTasks.captureAll(e, store, FPID, SID));

        // 旧键（现网 App 链路）全在
        assertEquals(77L, new JSONObject(store.getString("res:111:913")).optLong("1"));
        assertNotNull(store.getString("todo:111:913"));
        assertEquals(4L, new JSONObject(store.getString("questprog:111:913")).optLong("1320000"));
        assertEquals(270L, num("act:111:913"));
        // 角色键不存在（uid=0 不分角色）
        assertNull(store.getString("res:111:913:0"));
        assertNull(store.getString("todo:111:913:0"));
        assertNull(store.getString("questprog:111:913:0"));
    }

    // ---------------- Phase 2B：公共 6 参重载（bear / captureSupplyState） ----------------

    /** 巨熊响应：code=0 + reserve_time 属合法纪元秒 → 会写 mod:…:bear。 */
    private static Map<String, Object> trap() {
        Map<String, Object> trap = new HashMap<>();
        trap.put("code", 0);
        trap.put("reserve_time", System.currentTimeMillis() / 1000L + 3600L);
        return trap;
    }

    /** 未来合法纪元秒的补给时间字段 → 会写 mod:….supply。 */
    private static Map<String, Object> supplyNotify() {
        Map<String, Object> ri = new HashMap<>();
        ri.put("online_time", System.currentTimeMillis() / 1000L + 1800L);
        return ri;
    }

    @Test
    public void bearOverload_roleScopedAndActiveMirrorsLegacy() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        e.responses.put("req_alliance_trap_info", msg(trap()));

        CaptureTasks.bear(e, store, FPID, SID, UID_A, false);
        assertNotNull("兄弟角色写角色 mod:bear 键", store.getString("mod:111:913:1001:bear"));
        assertNull("非 active 角色不写旧 mod:bear", store.getString("mod:111:913:bear"));

        CaptureTasks.bear(e, store, FPID, SID, UID_B, true);
        assertNotNull(store.getString("mod:111:913:2002:bear"));
        assertEquals("active 角色角色键 + 旧键一致镜像",
                store.getString("mod:111:913:2002:bear"), store.getString("mod:111:913:bear"));
        assertNotNull("兄弟角色键不被 active 角色覆盖", store.getString("mod:111:913:1001:bear"));
    }

    @Test
    public void captureSupplyStateOverload_roleScopedAndActiveMirrorsLegacy() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        e.notifies.put("notify_recover_resource_init", supplyNotify());

        CaptureTasks.captureSupplyState(e, store, FPID, SID, UID_A, false);
        assertNotNull(store.getString("mod:111:913:1001:supply"));
        assertNull(store.getString("mod:111:913:supply"));

        CaptureTasks.captureSupplyState(e, store, FPID, SID, UID_B, true);
        assertNotNull(store.getString("mod:111:913:2002:supply"));
        assertEquals(store.getString("mod:111:913:2002:supply"),
                store.getString("mod:111:913:supply"));
        assertNotNull(store.getString("mod:111:913:1001:supply"));
    }

    @Test
    public void legacySignatures_bearAndSupply_writeLegacyKeysOnly() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        e.responses.put("req_alliance_trap_info", msg(trap()));
        e.notifies.put("notify_recover_resource_init", supplyNotify());

        CaptureTasks.bear(e, store, FPID, SID);
        CaptureTasks.captureSupplyState(e, store, FPID, SID);

        assertNotNull(store.getString("mod:111:913:bear"));
        assertNotNull(store.getString("mod:111:913:supply"));
        // uid=0 旧签名不产生角色键（App 现网链路 100% 不变）
        assertNull(store.getString("mod:111:913:0:bear"));
        assertNull(store.getString("mod:111:913:0:supply"));
    }
}
