package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 重装后 token 恢复客户端单测（2026-10-25，{@link VaultSync#restoreTokenFromServer} 配套）：
 * <ul>
 *   <li>{@link VaultSync#parseRestoredToken}：真实 {@code /v1/vault/token/get} 成功/失败信封
 *       的 data 解析（trim、缺键、空串、null）——纯 JVM，org.json 用 testImplementation 覆盖
 *       android.jar 桩；</li>
 *   <li>入参守卫：fpid null/空/纯空白时不发请求、直接回调 -1（ctx 为 null 也不 NPE）；</li>
 *   <li>本地码契约：{@link VaultSync#CODE_TOKEN_NOT_RETURNED} 与网络失败 -1、成功 0 区分，
 *       Lead 接 UI 时据此提示「服务器未返回 token」而不是当网络故障重试。</li>
 * </ul>
 * 不测真实网络与落库（本方法不写账号库，写库由调用方负责；真实端点走真机联调）。
 */
public class VaultTokenRestoreTest {

    // ---------- data 解析 ----------

    @Test
    public void parse_successEnvelope_returnsToken() throws Exception {
        // 服务端契约 fixture：{code:0, message:"ok", data:{fpid,token,hasToken}, serverTime}
        JSONObject envelope = new JSONObject("{\"code\":0,\"message\":\"ok\","
                + "\"data\":{\"fpid\":\"80971192\",\"token\":\"tk-restored-A\",\"hasToken\":true},"
                + "\"serverTime\":1761360000000}");
        assertEquals(0, envelope.getInt("code"));
        JSONObject data = envelope.getJSONObject("data");
        assertEquals("80971192", data.getString("fpid"));
        assertTrue(data.getBoolean("hasToken"));
        assertEquals("tk-restored-A", VaultSync.parseRestoredToken(data));
    }

    @Test
    public void parse_tokenTrimmed() {
        assertEquals("tk-A", VaultSync.parseRestoredToken(
                json("{\"token\":\"  tk-A \\n\"}")));
    }

    @Test
    public void parse_missingOrBlank_returnsNull() {
        assertNull(VaultSync.parseRestoredToken(null));
        assertNull(VaultSync.parseRestoredToken(json("{}")));
        assertNull(VaultSync.parseRestoredToken(json("{\"fpid\":\"80971192\"}")));
        assertNull(VaultSync.parseRestoredToken(json("{\"token\":\"\"}")));
        assertNull(VaultSync.parseRestoredToken(json("{\"token\":\"   \"}")));
        // 旧服务端/异常数据：token 为 JSON null → optString 不炸，按缺失处理
        assertNull(VaultSync.parseRestoredToken(json("{\"token\":null}")));
    }

    @Test
    public void parse_errorEnvelopeEmptyData_returnsNull() throws Exception {
        // 1004 账号不存在 / 1005 无 token：信封 data 为空对象（服务端 BizError 口径）
        JSONObject notFound = new JSONObject("{\"code\":1004,\"message\":\"账号不存在: 80971192\","
                + "\"data\":{},\"serverTime\":1}");
        JSONObject noToken = new JSONObject("{\"code\":1005,\"message\":\"账号没有 token: 80971192\","
                + "\"data\":{},\"serverTime\":1}");
        assertEquals(1004, notFound.getInt("code"));
        assertEquals(1005, noToken.getInt("code"));
        assertNull(VaultSync.parseRestoredToken(notFound.getJSONObject("data")));
        assertNull(VaultSync.parseRestoredToken(noToken.getJSONObject("data")));
        // 错误 message 不含 token 明文（只有 fpid）
        assertTrue(notFound.getString("message").contains("80971192"));
        assertTrue(noToken.getString("message").contains("80971192"));
    }

    // ---------- 入参守卫（不发请求路径） ----------

    @Test
    public void restore_blankFpid_callbackMinusOne_withoutContext() {
        final AtomicReference<int[]> codes = new AtomicReference<>();
        final AtomicReference<String> messages = new AtomicReference<>();
        VaultSync.Callback cb = (code, message, data) -> {
            codes.set(new int[]{code});
            messages.set(message);
            assertNull("失败回调不得带 data", data);
        };
        // ctx 传 null：守卫必须在 getApplicationContext() 之前返回，证明未发请求
        VaultSync.restoreTokenFromServer(null, null, cb);
        assertEquals(-1, codes.get()[0]);
        assertTrue(messages.get().contains("fpid"));

        VaultSync.restoreTokenFromServer(null, "", cb);
        assertEquals(-1, codes.get()[0]);

        VaultSync.restoreTokenFromServer(null, "   ", cb);
        assertEquals(-1, codes.get()[0]);
    }

    @Test
    public void restore_callbackNull_doesNotThrow() {
        // done 允许 null（与 pushToken 等现有接口口径一致，调用方可 fire-and-forget）
        VaultSync.restoreTokenFromServer(null, "", null);
    }

    // ---------- 本地码契约 ----------

    @Test
    public void localCode_distinctFromNetworkAndSuccess() {
        assertEquals(-2, VaultSync.CODE_TOKEN_NOT_RETURNED);
        assertTrue(VaultSync.CODE_TOKEN_NOT_RETURNED != 0);
        assertTrue(VaultSync.CODE_TOKEN_NOT_RETURNED != -1);
    }

    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
