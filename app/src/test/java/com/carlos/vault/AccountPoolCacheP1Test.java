package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * P1 /accounts 新增字段解析单测（契约 §二，纯 JVM）：
 * {@code account.recovery}、
 * {@code roles[].pause_resume_at}（ISO-8601/数值/缺字段容错）、
 * 池角色定位 {@link AccountPoolCache#findRole}。
 */
public class AccountPoolCacheP1Test {

    // ---------- pause_resume_at：ISO-8601 解析 ----------

    @Test
    public void pauseResumeAtOf_isoZulu() throws Exception {
        // 2026-10-21T12:00:00Z == 1790...（固定 epoch：2026-10-21 UTC 12:00）
        long epoch = AccountPoolCache.parseIsoMs("2026-10-21T12:00:00Z");
        java.util.GregorianCalendar expect =
                new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"));
        expect.clear();
        expect.set(2026, 9, 21, 12, 0, 0);
        assertEquals(expect.getTimeInMillis(), epoch);
        assertEquals(epoch, AccountPoolCache.pauseResumeAtOf(
                new JSONObject("{\"pause_resume_at\":\"2026-10-21T12:00:00Z\"}")));
    }

    @Test
    public void pauseResumeAtOf_positiveOffset() {
        // +08:00：本地 20:00 = UTC 12:00（epoch 与上一条同刻）
        long zulu = AccountPoolCache.parseIsoMs("2026-10-21T12:00:00Z");
        assertEquals(zulu, AccountPoolCache.parseIsoMs("2026-10-21T20:00:00+08:00"));
        // 无冒号偏移 +0800 同样支持
        assertEquals(zulu, AccountPoolCache.parseIsoMs("2026-10-21T20:00:00+0800"));
    }

    @Test
    public void pauseResumeAtOf_negativeOffset() {
        long zulu = AccountPoolCache.parseIsoMs("2026-10-21T12:00:00Z");
        // -05:00：本地 07:00 = UTC 12:00
        assertEquals(zulu, AccountPoolCache.parseIsoMs("2026-10-21T07:00:00-05:00"));
    }

    @Test
    public void pauseResumeAtOf_fractionSecondsAndNoOffset() {
        long zulu = AccountPoolCache.parseIsoMs("2026-10-21T12:00:00Z");
        // 无偏移按 UTC（服务端 DateTimeFormatter.ISO_LOCAL_DATE_TIME 容错）
        assertEquals(zulu, AccountPoolCache.parseIsoMs("2026-10-21T12:00:00"));
        assertEquals(zulu + 500, AccountPoolCache.parseIsoMs("2026-10-21T12:00:00.5Z"));
        assertEquals(zulu + 123, AccountPoolCache.parseIsoMs("2026-10-21T12:00:00.123456Z"));
    }

    @Test
    public void pauseResumeAtOf_numericMillisCompat() throws Exception {
        assertEquals(1790000000000L,
                AccountPoolCache.pauseResumeAtOf(new JSONObject("{\"pause_resume_at\":1790000000000}")));
        assertEquals(1790000000000L,
                AccountPoolCache.pauseResumeAtOf(new JSONObject("{\"pause_resume_at\":\"1790000000000\"}")));
        // 0/负数 → 无（0=无契约口径）
        assertEquals(0L, AccountPoolCache.pauseResumeAtOf(new JSONObject("{\"pause_resume_at\":0}")));
    }

    @Test
    public void pauseResumeAtOf_missingAndGarbage_returnZero() throws Exception {
        // 旧服务端缺字段 → 0；垃圾值不抛异常 → 0
        assertEquals(0L, AccountPoolCache.pauseResumeAtOf(new JSONObject("{\"sid\":\"888\"}")));
        assertEquals(0L, AccountPoolCache.pauseResumeAtOf(new JSONObject("{\"pause_resume_at\":\"\"}")));
        assertEquals(0L, AccountPoolCache.pauseResumeAtOf(
                new JSONObject("{\"pause_resume_at\":\"not-a-time\"}")));
        assertEquals(0L, AccountPoolCache.pauseResumeAtOf(
                new JSONObject("{\"pause_resume_at\":\"2026-13-99T99:99:99Z\"}")));
        assertEquals(0L, AccountPoolCache.pauseResumeAtOf(null));
    }

    // ---------- account.recovery getter ----------

    @Test
    public void recoveryOf_presentMissingNull() throws Exception {
        JSONObject rec = new JSONObject("{\"enabled\":true}");
        JSONObject acc = new JSONObject("{\"recovery\":" + rec + "}");
        // 用字符串比较（测试 classpath 的 JSONObject#equals 非深度比较）
        assertEquals(rec.toString(), AccountPoolCache.recoveryOf(acc).toString());
        assertNull(AccountPoolCache.recoveryOf(new JSONObject("{\"fpid\":\"1\"}")));
        assertNull(AccountPoolCache.recoveryOf(null));
    }

    // ---------- stateOfRole 桥接 pause_resume_at ----------

    @Test
    public void stateOfRole_mapsPauseResumeAt() throws Exception {
        JSONObject role = new JSONObject("{\"sid\":\"888\",\"uid\":\"1\",\"enabled\":true,"
                + "\"pause_resume_at\":\"2026-10-21T12:04:32Z\","
                + "\"state\":{\"online\":false,\"runState\":\"PAUSED\","
                + "\"recoveryState\":\"AVAILABLE\",\"statusMessage\":\"\"}}");
        com.carlos.vault.daily.RoleRuntimeState st = AccountPoolCache.stateOfRole(role);
        long epoch = AccountPoolCache.parseIsoMs("2026-10-21T12:04:32Z");
        assertEquals(epoch, st.pauseResumeAt);
        // PAUSED + pauseResumeAt>0 → 「已暂停·待恢复」+ 倒计时
        assertEquals("已暂停·待恢复 04:32", st.label(epoch - 272_000));
    }

    @Test
    public void stateOfRole_legacyRoleMissingPauseResumeAt_stillParses() throws Exception {
        // 旧服务端角色（无新字段）状态机照常构建，pauseResumeAt=0
        JSONObject role = new JSONObject("{\"sid\":\"888\",\"uid\":\"1\","
                + "\"state\":{\"online\":true,\"runState\":\"RUNNING\"}}");
        com.carlos.vault.daily.RoleRuntimeState st = AccountPoolCache.stateOfRole(role);
        assertEquals(0L, st.pauseResumeAt);
        assertTrue(st.online);
    }

    // ---------- findRole（HomeActivity 行匹配下沉版） ----------

    @Test
    public void findRole_exactSidUid_andSidFallback() throws Exception {
        JSONObject acc = new JSONObject("{\"roles\":["
                + "{\"sid\":\"888\",\"uid\":\"35371154\",\"name\":\"隆科多\"},"
                + "{\"sid\":\"888\",\"uid\":\"88280792\",\"name\":\"小鹿矿工5号\"}]}");
        assertEquals("隆科多", AccountPoolCache.findRole(acc, 888, 35371154).optString("name"));
        assertEquals("小鹿矿工5号", AccountPoolCache.findRole(acc, 888, 88280792).optString("name"));
        // uid 未知（0）→ 按 sid 兜底（M2 matchPoolRole 原口径：最后命中行胜出）
        assertEquals("小鹿矿工5号", AccountPoolCache.findRole(acc, 888, 0).optString("name"));
        assertNull(AccountPoolCache.findRole(acc, 999, 1));
        assertNull(AccountPoolCache.findRole(null, 888, 1));
    }
}
