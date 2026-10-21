package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 账号池缓存解析辅助单测（M2c，M4 真机验证修复回归）：
 * <ul>
 *   <li>{@link AccountPoolCache#isRoleOnline}：在线在 {@code roles[].state.online}
 *       （M1 §2.1 真实 /accounts shape fixture），顶层 {@code online} 仅旧 shape 兼容
 *       ——M4 §3.1 D-1 P0：直接读顶层恒 false，在线行会被误判离线；</li>
 *   <li>同账号多角色并行在线（M2d 语义变更）：逐角色独立判定，多个角色可同时
 *       为 true，互不影响；</li>
 *   <li>{@link AccountPoolCache#isUnsupportedReply}：旧服务端没有池接口
 *       （code=3002/not found/HTTP 404，生产 v4.1.4 常态）≠ 网络失败（stale）；</li>
 *   <li>stale/unsupported 落盘标记互斥（一次请求只有一种失败口径）。</li>
 * </ul>
 * 全部纯 JVM：JSON 用真实 org.json（testImplementation 覆盖 android.jar 桩），
 * 落盘标记用 mockito 桩 SharedPreferences。
 */
public class AccountPoolCacheTest {

    // ---------- 测试 fixture（M4 真机实测的 /accounts 真实 shape） ----------

    @Test
    public void isRoleOnline_realShape_stateOnlineTrue() throws Exception {
        // M4 D-1 复现 fixture：小鹿 state.online=true（顶层无 online 字段）
        JSONObject xiaolu = new JSONObject("{\"sid\":\"888\",\"uid\":\"88280792\","
                + "\"name\":\"小鹿矿工5号\",\"enabled\":true,"
                + "\"state\":{\"online\":true,\"runState\":\"RUNNING\","
                + "\"recoveryState\":\"AVAILABLE\",\"statusMessage\":\"\","
                + "\"takeoverRecoveryActive\":false,\"nextRecoveryAt\":0,"
                + "\"startupInProgress\":false,\"loginInProgress\":false,"
                + "\"runScheduleState\":\"ACTIVE\"}}");
        assertTrue(AccountPoolCache.isRoleOnline(xiaolu));
    }

    @Test
    public void isRoleOnline_realShape_stateOnlineFalse() throws Exception {
        JSONObject longkeduo = new JSONObject("{\"sid\":\"888\",\"uid\":\"35371154\","
                + "\"name\":\"隆科多\",\"enabled\":true,"
                + "\"state\":{\"online\":false,\"runState\":\"RUNNING\","
                + "\"recoveryState\":\"WAITING_RETRY\"}}");
        assertFalse(AccountPoolCache.isRoleOnline(longkeduo));
    }

    @Test
    public void isRoleOnline_stateMissing_fallsBackToLegacyTopLevel() throws Exception {
        // 旧 shape（无 state 对象）：顶层 online 兼容回退
        assertTrue(AccountPoolCache.isRoleOnline(
                new JSONObject("{\"sid\":\"888\",\"uid\":\"1\",\"online\":true}")));
        assertFalse(AccountPoolCache.isRoleOnline(
                new JSONObject("{\"sid\":\"888\",\"uid\":\"1\",\"online\":false}")));
        // 无 state 且无顶层字段 → false（不误报在线）
        assertFalse(AccountPoolCache.isRoleOnline(
                new JSONObject("{\"sid\":\"888\",\"uid\":\"1\"}")));
    }

    @Test
    public void isRoleOnline_stateWins_overTopLevel() throws Exception {
        // state 对象存在时以其为准（顶层遗留脏字段不干扰）
        assertFalse(AccountPoolCache.isRoleOnline(new JSONObject(
                "{\"online\":true,\"state\":{\"online\":false}}")));
        assertTrue(AccountPoolCache.isRoleOnline(new JSONObject(
                "{\"online\":false,\"state\":{\"online\":true}}")));
    }

    @Test
    public void isRoleOnline_nullAndEmptySafe() {
        assertFalse(AccountPoolCache.isRoleOnline(null));
        assertFalse(AccountPoolCache.isRoleOnline(new JSONObject()));
    }

    @Test
    public void isRoleOnline_overRolesArray_multiRoleParallelOnline() throws Exception {
        // M2d 语义变更（验收 §四.1）：同账号两角色（隆科多 35371154 + 小鹿 88280792，
        // 均 888 服）可同时 online=true——isRoleOnline 逐角色独立判定，互不影响；
        // 第三个离线角色不受在线角色影响，仍为 false
        JSONArray roles = new JSONArray("["
                + "{\"sid\":\"888\",\"uid\":\"35371154\",\"name\":\"隆科多\","
                + "\"state\":{\"online\":true,\"runState\":\"RUNNING\"}},"
                + "{\"sid\":\"888\",\"uid\":\"88280792\",\"name\":\"小鹿矿工5号\","
                + "\"state\":{\"online\":true,\"runState\":\"RUNNING\"}},"
                + "{\"sid\":\"888\",\"uid\":\"11001\",\"name\":\"第三角色\","
                + "\"state\":{\"online\":false,\"runState\":\"STOPPED\"}}]");
        int onlineHits = 0;
        for (int i = 0; i < roles.length(); i++) {
            if (AccountPoolCache.isRoleOnline(roles.getJSONObject(i))) onlineHits++;
        }
        assertEquals(2, onlineHits);
        assertTrue(AccountPoolCache.isRoleOnline(roles.getJSONObject(0)));
        assertTrue(AccountPoolCache.isRoleOnline(roles.getJSONObject(1)));
        assertFalse(AccountPoolCache.isRoleOnline(roles.getJSONObject(2)));
    }

    // ---------- unsupported 与 stale 区分（M4 §B-13） ----------

    @Test
    public void unsupportedReply_code3002AndNotFound() {
        // 生产 v4.1.4 实测回复：业务码 3002 + message not found
        assertTrue(AccountPoolCache.isUnsupportedReply(3002, "not found"));
        assertTrue(AccountPoolCache.isUnsupportedReply(3002, ""));
        assertTrue(AccountPoolCache.isUnsupportedReply(-1, "not found"));
        assertTrue(AccountPoolCache.isUnsupportedReply(-1, "Not Found"));   // 大小写不敏感
    }

    @Test
    public void unsupportedReply_http404FromNonJsonResponse() {
        // post() 对非 JSON 响应把 HTTP 码带进 message（HTTP 404 ...）
        assertTrue(AccountPoolCache.isUnsupportedReply(-1,
                "HTTP 404 End of input at character 0"));
        assertFalse(AccountPoolCache.isUnsupportedReply(-1,
                "HTTP 500 Internal Server Error"));
    }

    @Test
    public void staleReply_networkAndOtherBusinessErrors() {
        // 网络失败（超时/连不上）与其它业务错 ≠ unsupported → stale
        assertFalse(AccountPoolCache.isUnsupportedReply(-1, "timeout"));
        assertFalse(AccountPoolCache.isUnsupportedReply(-1, "Failed to connect to server"));
        assertFalse(AccountPoolCache.isUnsupportedReply(-1, null));
        assertFalse(AccountPoolCache.isUnsupportedReply(1402, "角色未在线，无法暂停"));
        assertFalse(AccountPoolCache.isUnsupportedReply(0, "ok"));
    }

    // ---------- stale/unsupported 落盘标记互斥（桩 SharedPreferences 验写入） ----------

    private static Context prefsStub(SharedPreferences prefs, SharedPreferences.Editor ed) {
        Context app = mock(Context.class);
        when(app.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(ed);
        when(ed.putBoolean(anyString(), anyBoolean())).thenReturn(ed);
        when(ed.putString(anyString(), anyString())).thenReturn(ed);
        when(ed.putLong(anyString(), anyLong())).thenReturn(ed);
        return app;
    }

    private static android.content.SharedPreferences.Editor editor() {
        return mock(SharedPreferences.Editor.class);
    }

    @Test
    public void markStale_setsStale_clearsUnsupported() {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = editor();
        Context app = prefsStub(prefs, ed);

        AccountPoolCache.markStale(app);

        verify(ed).putBoolean("pool_stale", true);
        verify(ed).putBoolean("pool_unsupported", false);
    }

    @Test
    public void markUnsupported_setsUnsupported_clearsStale() {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = editor();
        Context app = prefsStub(prefs, ed);

        AccountPoolCache.markUnsupported(app);

        verify(ed).putBoolean("pool_unsupported", true);
        verify(ed).putBoolean("pool_stale", false);
    }

    @Test
    public void save_clearsBothFlags_andWritesCache() throws Exception {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = editor();
        Context app = prefsStub(prefs, ed);

        AccountPoolCache.save(app, new JSONObject("{\"accounts\":[]}"));

        verify(ed).putString("pool_cache", "{\"accounts\":[]}");
        verify(ed).putBoolean("pool_stale", false);
        verify(ed).putBoolean("pool_unsupported", false);
    }

    @Test
    public void save_nullData_noWrite() {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = editor();
        Context app = prefsStub(prefs, ed);

        AccountPoolCache.save(app, null);

        verifyNoInteractions(prefs, ed);
    }

    @Test
    public void flagReaders_reflectPrefs() {
        SharedPreferences prefs = mock(SharedPreferences.class);
        Context app = mock(Context.class);
        when(app.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getBoolean("pool_stale", false)).thenReturn(true);
        when(prefs.getBoolean("pool_unsupported", false)).thenReturn(false);

        assertTrue(AccountPoolCache.isStale(app));
        assertFalse(AccountPoolCache.isUnsupported(app));
    }
}
