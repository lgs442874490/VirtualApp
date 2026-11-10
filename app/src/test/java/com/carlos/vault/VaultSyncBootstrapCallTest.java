package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * M5 起号注册/起号批量通道单测（2026-11-07，参照 {@link MonitorRequestFieldsTest} 口径）：
 * <ul>
 *   <li>两通道（{@code /v1/vault/registration} + {@code /v1/vault/bootstrap}）请求字段
 *       纯函数的字段名/取值/可选键裁剪（HMAC 扁平标量约定：providerConfig/realName/accounts/
 *       lines/fpids 一律 JSON 数组/对象字符串）；</li>
 *   <li>{@code data.error} 业务错误归一化透传（服务端 handler 把错误兜在成功信封的 data 里，
 *       且 provider-test 成功响应也带空串 error 键 → 只有非空 error 才判错）。</li>
 * </ul>
 */
public class VaultSyncBootstrapCallTest {

    // ================= registration 字段 =================

    @Test
    public void registrationExpress_onlyAction() {
        Map<String, Object> f = VaultSync.registrationExpressFields();
        assertEquals("express", f.get("action"));
        assertEquals(1, f.size());
    }

    @Test
    public void registrationRealName_fields() {
        Map<String, Object> f = VaultSync.registrationRealNameFields(
                " 48460403 ", " 张三 ", " 110101199001011234 ");
        assertEquals("real-name", f.get("action"));
        assertEquals(" 48460403 ", f.get("fpid"));   // 通道纯函数不做 trim（UI 层已 trim）
        assertEquals(" 张三 ", f.get("name"));
        assertEquals(" 110101199001011234 ", f.get("id_card"));   // 服务端键是 id_card
        assertEquals(4, f.size());
    }

    @Test
    public void registrationProviderTest_fields() {
        Map<String, Object> f = VaultSync.registrationProviderTestFields(
                "d1", "{\"token\":\"abc\"}");
        assertEquals("provider-test", f.get("action"));
        assertEquals("d1", f.get("providerType"));
        assertEquals("{\"token\":\"abc\"}", f.get("providerConfig"));   // JSON 对象字符串
        assertEquals(3, f.size());
    }

    @Test
    public void registrationBatchStart_optionalKeysOmitted() {
        Map<String, Object> full = VaultSync.registrationBatchStartFields(
                "haozhuma", "{\"user\":\"u\",\"password\":\"p\",\"sid\":\"1\"}",
                "点点互动", 2, 10, 180,
                "{\"name\":\"张三\",\"idCard\":\"110101199001011234\"}", "ab12cd34");
        assertEquals("batch-start", full.get("action"));
        assertEquals("haozhuma", full.get("providerType"));
        assertEquals("点点互动", full.get("keyword"));
        assertEquals(2, full.get("goalCount"));
        assertEquals(10, full.get("maxNumbers"));
        assertEquals(180, full.get("smsWaitSeconds"));
        assertEquals("{\"name\":\"张三\",\"idCard\":\"110101199001011234\"}", full.get("realName"));
        assertEquals("ab12cd34", full.get("gamePassword"));

        // 最小批次：smsWaitSeconds/realName/gamePassword 全部不带（服务端用默认值）
        Map<String, Object> min = VaultSync.registrationBatchStartFields(
                "d1", "{\"token\":\"t\"}", "", 1, 1, null, "", "");
        assertEquals("batch-start", min.get("action"));
        assertFalse(min.containsKey("smsWaitSeconds"));
        assertFalse(min.containsKey("realName"));
        assertFalse(min.containsKey("gamePassword"));
        assertEquals("", min.get("keyword"));
        assertEquals(6, min.size());
    }

    @Test
    public void registrationBatchStatusAndCancel() {
        assertEquals("batch-status", VaultSync.registrationBatchStatusFields("b123").get("action"));
        assertEquals("b123", VaultSync.registrationBatchStatusFields("b123").get("batchId"));
        assertEquals("batch-cancel", VaultSync.registrationBatchCancelFields("b123").get("action"));
        assertEquals("b123", VaultSync.registrationBatchCancelFields("b123").get("batchId"));
        assertEquals("", VaultSync.registrationBatchStatusFields(null).get("batchId"));
    }

    @Test
    public void batchListFields_sharedByBothPaths() {
        Map<String, Object> f = VaultSync.batchListFields();
        assertEquals("batch-list", f.get("action"));
        assertEquals(1, f.size());   // limit 不带=服务端默认 30
    }

    // ================= bootstrap 字段 =================

    @Test
    public void bootstrapImport_linesAsJsonArrayString() throws Exception {
        List<String> lines = Arrays.asList("enc-line-1", "fpid|token", "");
        Map<String, Object> f = VaultSync.bootstrapImportFields(lines);
        assertEquals("import", f.get("action"));
        JSONArray arr = new JSONArray((String) f.get("lines"));
        assertEquals(3, arr.length());
        assertEquals("enc-line-1", arr.optString(0));
        assertEquals("", arr.optString(2));   // null 元素兜空串
    }

    @Test
    public void bootstrapBatchStart_fields() throws Exception {
        String accountsJson = "[\"enc-line\",{\"fpid\":\"1\",\"sdkToken\":\"t\",\"label\":\"游客1\"}]";
        Map<String, Object> f = VaultSync.bootstrapBatchStartFields(
                152L, 9, "测试盟", "角色甲", true, true, false, accountsJson);
        assertEquals("batch-start", f.get("action"));
        assertEquals(152L, f.get("serverId"));
        assertEquals(9, f.get("targetFurnaceLevel"));
        assertEquals("测试盟", f.get("allianceName"));
        assertEquals("角色甲", f.get("roleName"));
        assertEquals(Boolean.TRUE, f.get("instantDiamond"));
        // 真实轮（2026-11-10）：补资源两开关必须随请求下发，否则服务端无法领奖/买资源
        assertEquals(Boolean.TRUE, f.get("claimRewards"));
        assertEquals(Boolean.FALSE, f.get("buyShopResources"));
        assertEquals(accountsJson, f.get("accounts"));
        assertEquals(9, f.size());
    }

    @Test
    public void bootstrapReferralFields() throws Exception {
        Map<String, Object> list = VaultSync.bootstrapReferralListFields();
        assertEquals("referral-list", list.get("action"));
        assertEquals(1, list.size());

        Map<String, Object> pull = VaultSync.bootstrapReferralPullFields(
                Arrays.asList("48460403", "11111111"));
        assertEquals("referral-pull", pull.get("action"));
        JSONArray fpids = new JSONArray((String) pull.get("fpids"));
        assertEquals(2, fpids.length());
        assertEquals("48460403", fpids.optString(0));
        // 空清单：fpids="[]"（服务端 stringList 解析为空表并报「缺少 fpids」——调用方应先判空）
        assertEquals("[]", VaultSync.bootstrapReferralPullFields(null).get("fpids"));
        assertEquals("[]", VaultSync.bootstrapReferralPullFields(Collections.emptyList()).get("fpids"));
    }

    // ================= data.error 归一化（错误透传契约） =================

    @Test
    public void actionError_dataErrorNormalizedToActionCode() throws Exception {
        // 服务端 handler 业务错误：信封 code=0 + data.error 非空 → 归一化 CODE_ACTION_ERROR
        JSONObject err = new JSONObject("{\"error\":\"goalCount 必须在 1–50 之间\"}");
        assertEquals(VaultSync.CODE_ACTION_ERROR, VaultSync.actionResultCode(0, err));
        assertEquals("goalCount 必须在 1–50 之间",
                VaultSync.actionResultMessage(0, "ok", err));
    }

    @Test
    public void actionError_emptyErrorIsNotFailure() throws Exception {
        // provider-test 成功响应固定带空串 error 键：不算失败
        JSONObject ok = new JSONObject("{\"ok\":true,\"balanceText\":\"¥12.3\",\"error\":\"\"}");
        assertEquals(0, VaultSync.actionResultCode(0, ok));
        assertEquals("ok", VaultSync.actionResultMessage(0, "ok", ok));
    }

    @Test
    public void actionError_envelopeErrorPassesThrough() {
        // 信封 code 非 0（验签失败 2001 / 旧服务端 404 归一 -1 等）：原样透传
        assertEquals(2001, VaultSync.actionResultCode(2001, null));
        assertEquals("sign error", VaultSync.actionResultMessage(2001, "sign error", null));
        assertEquals(-1, VaultSync.actionResultCode(-1, null));
        // data 为 null（网络层失败/契约外）：无业务错误，code=0 原样
        assertEquals(0, VaultSync.actionResultCode(0, null));
        assertEquals("", VaultSync.actionResultMessage(0, "", null));
    }
}
