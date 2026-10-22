package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import org.json.JSONObject;

/**
 * 战争通知 App 侧纯逻辑单测（2026-10-22）：手机号校验、发送门控
 * （开关/手机号/权限/eventId 去重/5 分钟节流）、事件文本格式化。
 * SmsManager 触达与权限申请属 android 桩，不在纯 JVM 单测范围。
 */
public class WarNotifyGateTest {

    // ---------------- 手机号校验 ----------------

    @Test
    public void phoneValid_acceptsDomesticAndInternational() {
        assertTrue(WarNotifyGate.phoneValid("13812345678"));
        assertTrue(WarNotifyGate.phoneValid(" 13812345678 "));   // trim
        assertTrue(WarNotifyGate.phoneValid("12345"));           // 5 位下限
        assertTrue(WarNotifyGate.phoneValid("008613812345678")); // 国际前缀
    }

    @Test
    public void phoneValid_rejectsBadInput() {
        assertFalse(WarNotifyGate.phoneValid(null));
        assertFalse(WarNotifyGate.phoneValid(""));
        assertFalse(WarNotifyGate.phoneValid("1234"));            // 过短
        assertFalse(WarNotifyGate.phoneValid("12345678901234567890123456")); // 过长
        assertFalse(WarNotifyGate.phoneValid("1381234567a"));
        assertFalse(WarNotifyGate.phoneValid("138 1234 5678"));   // 空格在中间
        assertFalse(WarNotifyGate.phoneValid("+8613812345678"));  // + 非数字
    }

    // ---------------- 发送门控 ----------------

    private Set<String> ids(String... values) {
        Set<String> s = new HashSet<>();
        for (String v : values) s.add(v);
        return s;
    }

    @Test
    public void gate_passesWhenAllConditionsMet() {
        assertNull(WarNotifyGate.gate(true, "13812345678", true, 0,
                1_000_000L, "w1", ids()));
    }

    @Test
    public void gate_rejectsDisabledAndMissingPhone() {
        assertEquals("短信通知未开启", WarNotifyGate.gate(false, "13812345678", true,
                0, 0, "w1", ids()));
        assertEquals("手机号未配置", WarNotifyGate.gate(true, "", true, 0, 0, "w1", ids()));
        assertEquals("手机号未配置", WarNotifyGate.gate(true, "abc", true, 0, 0, "w1", ids()));
    }

    @Test
    public void gate_rejectsMissingPermission() {
        assertEquals("缺 SEND_SMS 权限", WarNotifyGate.gate(true, "13812345678", false,
                0, 0, "w1", ids()));
    }

    @Test
    public void gate_dedupesByEventId() {
        assertEquals("事件已发过", WarNotifyGate.gate(true, "13812345678", true,
                0, 0, "w1", ids("w1")));
        // eventId 为空不过去重（异常事件兜底仍可发）
        assertNull(WarNotifyGate.gate(true, "13812345678", true, 0, 0, "", ids("w1")));
    }

    @Test
    public void gate_throttlesFiveMinutes() {
        long now = 10 * 60_000L;
        assertEquals("5 分钟节流中", WarNotifyGate.gate(true, "13812345678", true,
                now - 1, now, "w2", ids()));
        assertNull(WarNotifyGate.gate(true, "13812345678", true,
                now - WarNotifyGate.SMS_INTERVAL_MS, now, "w2", ids()));
        // 无历史发送（0）不节流
        assertNull(WarNotifyGate.gate(true, "13812345678", true, 0, now, "w3", ids()));
    }

    // ---------------- 文本格式化 ----------------

    @Test
    public void formatSms_fullFields() {
        String s = WarNotifyGate.formatSms("宿敌", "进攻", true, 3, 4,
                2_000_000L, 1_999_900L, 888L);
        assertEquals("[号库]敌情·进攻 宿敌(3,4) 100s后到 k888", s);
    }

    @Test
    public void formatSms_missingFieldsDegrade() {
        String s = WarNotifyGate.formatSms(null, null, false, 0, 0, 0, 0, 0);
        assertEquals("[号库]敌情·动静 ?", s);
    }

    @Test
    public void formatSms_recallAndArriveCarryNoEta() {
        String recall = WarNotifyGate.formatSms("宿敌", "撤回", false, 0, 0,
                9_999_999L, 1_000L, 888L);
        assertFalse(recall.contains("后到"));
        assertTrue(recall.contains("撤回"));
        String arrive = WarNotifyGate.formatSms("宿敌", "到达", false, 0, 0,
                1_000L, 2_000L, 0L);
        assertFalse(arrive.contains("后到"));
        assertFalse(arrive.contains("k0"));
    }

    @Test
    public void formatSms_pastEndtimeCarriesNoEta() {
        String s = WarNotifyGate.formatSms("宿敌", "进攻", false, 0, 0,
                500L, 1_000L, 0L);
        assertFalse(s.contains("后到"));
    }

    @Test
    public void formatEventRow_containsTimeActionTarget() {
        // 固定时间戳：2026-10-22 12:00:00 北京时间 = 1792632000s
        long ts = 1792632000_000L;
        String row = WarNotifyGate.formatEventRow(ts, "进攻", "宿敌", true, 3, 4, 888L);
        assertTrue(row.contains("进攻"));
        assertTrue(row.contains("宿敌"));
        assertTrue(row.contains("(3,4)"));
        assertTrue(row.contains("@k888"));
        assertTrue(row.matches("\\d{2}-\\d{2} \\d{2}:\\d{2} .*"));
    }

    // ---------------- v4.1.8：事件级短信（昵称/坐标/行为/兵力/ETA/结算） ----------------

    private JSONObject jobj(String json) throws Exception {
        return new JSONObject(json);
    }

    @Test
    public void formatSmsEvent_actionCarriesNewFields() throws Exception {
        JSONObject e = jobj("{\"eventId\":\"w1\",\"sid\":888,\"targetUid\":57347368,"
                + "\"attackerName\":\"张三\",\"targetName\":\"宿敌\",\"action\":\"进攻\","
                + "\"behavior\":\"进攻城堡\",\"troops\":\"盾T10×1200（合计1200）\","
                + "\"startPoint\":{\"x\":5,\"y\":6},\"endpoint\":{\"x\":3,\"y\":4},"
                + "\"endtime\":2000000,\"etaSeconds\":100,\"phase\":\"action\"}");
        String s = WarNotifyGate.formatSms(e, 1999900L);
        assertEquals("[号库]敌情·进攻城堡 张三(5,6)→(3,4) 盾T10×1200（合计1200） 100s后到 k888", s);
    }

    @Test
    public void formatSmsEvent_settleCarriesOutcomeAndBreach() throws Exception {
        JSONObject e = jobj("{\"eventId\":\"w2\",\"sid\":888,\"attackerName\":\"张三\","
                + "\"phase\":\"settle\",\"outcome\":\"已到达\",\"action\":\"到达\","
                + "\"durationSeconds\":330,\"breach\":\"是\",\"battleCodes\":\"30/6\"}");
        String s = WarNotifyGate.formatSms(e, 0L);
        assertEquals("[号库]结算·已到达 张三 耗时5分30秒 破城:是(30/6) k888", s);

        JSONObject recall = jobj("{\"phase\":\"settle\",\"outcome\":\"撤回\","
                + "\"attackerName\":\"张三\",\"durationSeconds\":45}");
        String r = WarNotifyGate.formatSms(recall, 0L);
        assertTrue(r.contains("结算·撤回"));
        assertTrue(r.contains("耗时0分45秒"));
        assertFalse(r.contains("破城"));   // 撤回无破城
    }

    @Test
    public void formatSmsEvent_trimsTroopsDetailKeepsTotal() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 55; i++) name.append('敌');
        StringBuilder troops = new StringBuilder();
        for (int t = 0; t < 6; t++) troops.append("盾T10×1200 ");
        troops.append("（合计250000）");
        JSONObject e = jobj("{\"attackerName\":\"" + name + "\",\"behavior\":\"进攻城堡\","
                + "\"troops\":\"" + troops + "\",\"sid\":888,\"phase\":\"action\"}");
        String s = WarNotifyGate.formatSms(e, 0L);
        assertTrue("超预算应裁明细留合计: " + s, s.contains("合计250000"));
        assertFalse(s.contains("盾T10×1200"));
        assertTrue(s.length() <= WarNotifyGate.SMS_BUDGET_CHARS);

        // 极端长名：连合计版都超 → 硬裁到预算
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 140; i++) huge.append('敌');
        JSONObject e2 = jobj("{\"attackerName\":\"" + huge + "\",\"behavior\":\"进攻城堡\"}");
        assertTrue(WarNotifyGate.formatSms(e2, 0L).length() <= WarNotifyGate.SMS_BUDGET_CHARS);
    }

    @Test
    public void formatSmsEvent_degradesOnMissingFields() throws Exception {
        assertEquals("[号库]敌情", WarNotifyGate.formatSms(null, 0L));
        String s = WarNotifyGate.formatSms(new JSONObject(), 0L);
        assertTrue(s.contains("[号库]敌情·动静"));
    }

    @Test
    public void briefTroops_extractsTotalOnly() {
        assertEquals("合计2000", WarNotifyGate.briefTroops("盾T10×1200 矛T9×800（合计2000）"));
        assertEquals("合计1200", WarNotifyGate.briefTroops("（合计1200）"));
        assertEquals("", WarNotifyGate.briefTroops("未知"));
        assertEquals("", WarNotifyGate.briefTroops("盾T10×1200"));
        assertEquals("", WarNotifyGate.briefTroops(null));
    }

    @Test
    public void formatEventSub_actionAndSettle() throws Exception {
        JSONObject e = jobj("{\"behavior\":\"进攻城堡\",\"troops\":\"盾T10×1200（合计1200）\","
                + "\"etaSeconds\":100,\"phase\":\"action\",\"action\":\"进攻\"}");
        assertEquals("进攻城堡 · 盾T10×1200（合计1200） · 100s后到", WarNotifyGate.formatEventSub(e));

        JSONObject s = jobj("{\"phase\":\"settle\",\"outcome\":\"已到达\",\"durationSeconds\":330,"
                + "\"breach\":\"是\",\"battleCodes\":\"30/6\"}");
        assertEquals("结算 已到达 · 耗时5分30秒 · 破城:是(30/6)", WarNotifyGate.formatEventSub(s));
    }

    @Test
    public void formatEventDetail_containsAllSections() throws Exception {
        JSONObject e = jobj("{\"eventId\":\"w1\",\"detectedAt\":1792632000000,\"sid\":888,"
                + "\"targetUid\":57347368,\"attackerName\":\"张三\",\"action\":\"进攻\","
                + "\"behavior\":\"进攻城堡\",\"troops\":\"盾T10×1200（合计1200）\","
                + "\"startPoint\":{\"x\":5,\"y\":6},\"endpoint\":{\"x\":3,\"y\":4},"
                + "\"endtime\":1792632300,\"etaSeconds\":300,\"phase\":\"action\","
                + "\"rawProto\":\"notify_player_march_add\"}");
        String d = WarNotifyGate.formatEventDetail(e);
        assertTrue(d.contains("行为：进攻城堡"));
        assertTrue(d.contains("发起人：张三（uid 57347368）"));
        assertTrue(d.contains("发起人坐标：(5,6)"));
        assertTrue(d.contains("目标坐标：(3,4)"));
        assertTrue(d.contains("兵力：盾T10×1200（合计1200）"));
        assertTrue(d.contains("到达："));
        assertTrue(d.contains("（300s 后）"));
        assertTrue(d.contains("来源：notify_player_march_add"));
        assertTrue(d.contains("会话：k888"));
        assertTrue(d.contains("事件：w1"));

        JSONObject st = jobj("{\"eventId\":\"w2\",\"detectedAt\":1792632000000,\"phase\":\"settle\","
                + "\"attackerName\":\"张三\",\"outcome\":\"已到达\",\"durationSeconds\":330,"
                + "\"breach\":\"未知\",\"battleCodes\":\"30/1\"}");
        String d2 = WarNotifyGate.formatEventDetail(st);
        assertTrue(d2.contains("结算：已到达 · 耗时5分30秒 · 破城:未知（战报 30/1）"));
        assertFalse(d2.contains("到达："));   // 结算无到达行
    }
}
