package com.carlos.vault.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 起号/注册表单纯逻辑单测（M5）：校验门槛对齐服务端（RegistrationApi/BootstrapApi）、
 * providerConfig/accounts JSON 组装（HMAC 扁平标量约定）、状态→文案映射（任务书 §2 语义）。
 */
public class BootstrapFormsTest {

    // ================= 接码平台 =================

    @Test
    public void providerConfig_d1RequiresToken() {
        Map<String, String> withToken = new HashMap<>();
        withToken.put("token", " abc123 ");
        assertEquals("{\"token\":\"abc123\"}",
                BootstrapForms.providerConfigJson("d1", withToken));
        assertNull("缺 token 返回 null（调用方提示）",
                BootstrapForms.providerConfigJson("d1", new HashMap<>()));
        assertNull(BootstrapForms.providerConfigJson("d1", null));
    }

    @Test
    public void providerConfig_haozhumaRequiresUserPasswordSid() throws Exception {
        Map<String, String> full = new HashMap<>();
        full.put("server", "https://api.hz.cn");
        full.put("user", "u1");
        full.put("password", "p1");
        full.put("sid", "123");
        JSONObject o = new JSONObject(BootstrapForms.providerConfigJson("haozhuma", full));
        assertEquals("u1", o.optString("user"));
        assertEquals("p1", o.optString("password"));
        assertEquals("123", o.optString("sid"));
        assertEquals("https://api.hz.cn", o.optString("server"));

        // server 可空：不拼键
        Map<String, String> noServer = new HashMap<>(full);
        noServer.remove("server");
        JSONObject o2 = new JSONObject(BootstrapForms.providerConfigJson("haozhuma", noServer));
        assertFalse(o2.has("server"));

        // 缺 sid 返回 null
        Map<String, String> noSid = new HashMap<>(full);
        noSid.remove("sid");
        assertNull(BootstrapForms.providerConfigJson("haozhuma", noSid));
    }

    // ================= 接码注册校验（对齐 RegistrationApi 门槛） =================

    @Test
    public void validatePhoneBatch_thresholds() {
        assertNull(BootstrapForms.validatePhoneBatch(1, 1, "", ""));
        assertNull(BootstrapForms.validatePhoneBatch(50, 100, "ab12cd", null));
        assertEquals("目标成功数必须在 1–50 之间",
                BootstrapForms.validatePhoneBatch(0, 1, "", ""));
        assertEquals("目标成功数必须在 1–50 之间",
                BootstrapForms.validatePhoneBatch(51, 60, "", ""));
        assertEquals("最多尝试号码数不能小于目标成功数",
                BootstrapForms.validatePhoneBatch(3, 2, "", ""));
        assertEquals("最多尝试号码数不能超过 100",
                BootstrapForms.validatePhoneBatch(1, 101, "", ""));
        assertEquals("游戏账号密码（至少 6 位）",
                BootstrapForms.validatePhoneBatch(1, 2, "abc12", ""));
        assertNull("空密码=不带（服务端允许）", BootstrapForms.validatePhoneBatch(1, 2, "", ""));
        assertEquals("身份证号必须为 18 位",
                BootstrapForms.validatePhoneBatch(1, 2, "", "110123"));
        assertNull(BootstrapForms.validatePhoneBatch(1, 2, "", "110101199001011234"));
    }

    @Test
    public void generateGamePassword_shape() {
        for (int i = 0; i < 20; i++) {
            String pwd = BootstrapForms.generateGamePassword();
            assertEquals(8, pwd.length());
            assertTrue("至少含一个大写字母", pwd.matches(".*[A-Z].*"));
            assertTrue("至少含一个数字", pwd.matches(".*[0-9].*"));
        }
    }

    // ================= 起号校验（对齐 BootstrapApi 门槛） =================

    @Test
    public void validateBootstrapBatch_thresholds() {
        assertNull(BootstrapForms.validateBootstrapBatch("152", 9, ""));
        assertNull(BootstrapForms.validateBootstrapBatch("152", 1, "小鹿"));
        assertNull("角色名可空", BootstrapForms.validateBootstrapBatch("152", 9, null));
        assertEquals("区服号必须为正整数",
                BootstrapForms.validateBootstrapBatch("0", 9, ""));
        assertEquals("区服号必须为正整数",
                BootstrapForms.validateBootstrapBatch("abc", 9, ""));
        assertEquals("目标大熔炉必须在 1–9 级",
                BootstrapForms.validateBootstrapBatch("152", 0, ""));
        assertEquals("目标大熔炉必须在 1–9 级",
                BootstrapForms.validateBootstrapBatch("152", 10, ""));
        assertEquals("角色名最多 16 个字",
                BootstrapForms.validateBootstrapBatch("152", 9, "一二三四五六七八九十一二三四五六七"));
    }

    @Test
    public void parseAccountLines_trimsAndDropsBlank() {
        List<String> lines = BootstrapForms.parseAccountLines("  aaa \n\n bbb \n  \nccc");
        assertEquals(Arrays.asList("aaa", "bbb", "ccc"), lines);
        assertTrue(BootstrapForms.parseAccountLines("").isEmpty());
        assertTrue(BootstrapForms.parseAccountLines(null).isEmpty());
    }

    @Test
    public void accountsJson_mixesLinesAndReferralObjects() throws Exception {
        List<BootstrapReferralSync.ReferralEntry> picked = Collections.singletonList(
                new BootstrapReferralSync.ReferralEntry("48460403", "tok", "enc", "", 152L, 9L));
        String json = BootstrapForms.accountsJson(Arrays.asList("enc-line", " fpid|token "),
                picked);
        JSONArray arr = new JSONArray(json);
        assertEquals(3, arr.length());
        assertEquals("enc-line", arr.optString(0));
        assertEquals("fpid|token", arr.optString(1));   // 行 trim
        JSONObject obj = arr.optJSONObject(2);
        assertEquals("48460403", obj.optString("fpid"));
        assertEquals("tok", obj.optString("sdkToken"));
        assertEquals("enc", obj.optString("encrypted"));
        assertEquals("游客0403", obj.optString("label"));   // label 缺省 → displayLabel（fpid 尾 4 位）

        assertEquals("", BootstrapForms.accountsJson(null, null));
        assertEquals("", BootstrapForms.accountsJson(Arrays.asList("  "), null));
    }

    // ================= 状态 → 文案（任务书 §2） =================

    @Test
    public void stateText_unifiedMap() {
        assertEquals("等待中", BootstrapForms.stateText("PENDING"));
        assertEquals("等待中", BootstrapForms.stateText("WAITING"));
        assertEquals("建号中", BootstrapForms.stateText("CREATING"));
        assertEquals("执行中", BootstrapForms.stateText("RUNNING"));
        assertEquals("成功", BootstrapForms.stateText("SUCCESS"));
        assertEquals("已完成", BootstrapForms.stateText("COMPLETED"));
        assertEquals("失败", BootstrapForms.stateText("FAILED"));
        assertEquals("已取消", BootstrapForms.stateText("CANCELLED"));
        assertEquals("等待中", BootstrapForms.stateText(null));
        assertEquals("等待中", BootstrapForms.stateText("???"));
    }

    @Test
    public void badgeType_colors() {
        assertEquals("ok", BootstrapForms.badgeType("SUCCESS"));
        assertEquals("ok", BootstrapForms.badgeType("COMPLETED"));
        assertEquals("run", BootstrapForms.badgeType("RUNNING"));
        assertEquals("wait", BootstrapForms.badgeType("CREATING"));
        assertEquals("err", BootstrapForms.badgeType("FAILED"));
        assertEquals("warn", BootstrapForms.badgeType("CANCELLED"));
        assertEquals("idle", BootstrapForms.badgeType("PENDING"));
    }

    @Test
    public void isTerminalBatch_threeTerminals() {
        assertTrue(BootstrapForms.isTerminalBatch("COMPLETED"));
        assertTrue(BootstrapForms.isTerminalBatch("FAILED"));
        assertTrue(BootstrapForms.isTerminalBatch("CANCELLED"));
        assertFalse(BootstrapForms.isTerminalBatch("RUNNING"));
        assertFalse(BootstrapForms.isTerminalBatch("WAITING"));
        assertFalse(BootstrapForms.isTerminalBatch(null));
    }

    /** R-06（P2-3）：ENDED 展示为「已结束（资源不足）」+ warn 色，四处映射一致。 */
    @Test
    public void endedState_mappedEverywhere() {
        assertEquals("已结束（资源不足）", BootstrapForms.stateText("ENDED"));
        assertEquals("warn", BootstrapForms.badgeType("ENDED"));
        assertEquals("已结束（资源不足）", BootstrapForms.accountStateText("ENDED"));
        assertEquals("已结束（资源不足）", BootstrapForms.batchStateText("ENDED"));
    }

    // ================= 其它 =================

    @Test
    public void batchShortId_andMask() {
        assertEquals("#4501", BootstrapForms.batchShortId("b2026110712344501"));
        assertEquals("#b1", BootstrapForms.batchShortId("b1"));
        assertEquals("#?", BootstrapForms.batchShortId(""));
        assertEquals("#?", BootstrapForms.batchShortId(null));

        // 敏感串掩码：短串全掩，长串首尾 4 位 + 长度（token/加密串 UI 展示用）
        assertEquals("****", BootstrapForms.maskCredential("abc"));
        assertEquals("abcd…wxyz（20 字符）", BootstrapForms.maskCredential("abcdefghijklmnopwxyz"));
        assertEquals("", BootstrapForms.maskCredential(null));
        assertEquals("", BootstrapForms.maskCredential("  "));
    }
}
