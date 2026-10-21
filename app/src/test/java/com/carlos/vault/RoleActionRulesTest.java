package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P1 角色动作文案/规则单测（契约 §三，纯 JVM）：
 * 防封确认弹窗（标题/正文/按钮逐字）、移除确认（强调不删游戏内角色）、
 * 改名校验（3~12 字符、首尾空格、控制字符）、暂停成功 toast、消耗提示。
 */
public class RoleActionRulesTest {

    // ---------- 防封确认（§三.1 逐字） ----------

    @Test
    public void antibanConfirmTitle_exact() {
        assertEquals("开启防封保护确认", RoleActionRules.ANTIBAN_CONFIRM_TITLE);
        assertEquals("确认开启", RoleActionRules.ANTIBAN_CONFIRM_POSITIVE);
        assertEquals("取消", RoleActionRules.ANTIBAN_CONFIRM_NEGATIVE);
    }

    @Test
    public void antibanConfirmBody_exactWithRoleName() {
        assertEquals("确定为角色【小鹿矿工5号】开启防封保护吗？开启后，该角色在登录游戏时"
                + "将自动模拟真实客户端上报设备环境（req_settings_set_device），并在进入游戏"
                + " 10 秒后执行易盾安全校验（req_dun163_risk_check）。⚠ 注意事项："
                + "1. 采用官方受信任特征与动态 Nonce 掩码重封装机制；"
                + "2. 开关变更将在下一次角色登录/启动时生效；"
                + "3. 未开启时，角色仍按原来的极简协议链路登录启动。",
                RoleActionRules.antibanConfirmBody("小鹿矿工5号"));
    }

    @Test
    public void antibanConfirmBody_containsKeyPhrases_anyName() {
        String b = RoleActionRules.antibanConfirmBody("隆科多");
        assertTrue(b.startsWith("确定为角色【隆科多】开启防封保护吗？"));
        assertTrue(b.contains("req_settings_set_device"));
        assertTrue(b.contains("req_dun163_risk_check"));
        assertTrue(b.contains("开关变更将在下一次角色登录/启动时生效"));
    }

    @Test
    public void antibanConfirmBody_nullNameSafe() {
        assertTrue(RoleActionRules.antibanConfirmBody(null).startsWith("确定为角色【】"));
    }

    @Test
    public void antibanSuccessToasts_exact() {
        assertEquals("已开启防封风控安全保护", RoleActionRules.ANTIBAN_ON_TOAST);
        assertEquals("已关闭防封风控安全保护", RoleActionRules.ANTIBAN_OFF_TOAST);
    }

    // ---------- 暂停（§三.2 逐字） ----------

    @Test
    public void pauseOkToast_exact() {
        assertEquals("账号已暂停，将按顶号设置自动恢复", RoleActionRules.PAUSE_OK_TOAST);
    }

    // ---------- 移除角色（§三.4 逐字） ----------

    @Test
    public void removeConfirmBody_exactAndStressesNoGameDelete() {
        assertEquals("确定要移除角色【铁柱】吗？仅从工具工作区移除，不会删除游戏内角色。",
                RoleActionRules.removeConfirmBody("铁柱"));
        assertEquals("移除角色", RoleActionRules.REMOVE_CONFIRM_TITLE);
        assertEquals("确认移除", RoleActionRules.REMOVE_CONFIRM_POSITIVE);
    }

    // ---------- 改名校验（§三.4：3~12 字符、首尾空格、控制字符） ----------

    @Test
    public void validateRoleName_nullAndEmpty() {
        assertEquals(RoleActionRules.ERR_NAME_EMPTY, RoleActionRules.validateRoleName(null));
        assertEquals(RoleActionRules.ERR_NAME_EMPTY, RoleActionRules.validateRoleName(""));
    }

    @Test
    public void validateRoleName_leadingTrailingSpaces() {
        assertEquals(RoleActionRules.ERR_NAME_TRIM, RoleActionRules.validateRoleName(" abc"));
        assertEquals(RoleActionRules.ERR_NAME_TRIM, RoleActionRules.validateRoleName("abc "));
        assertEquals(RoleActionRules.ERR_NAME_TRIM, RoleActionRules.validateRoleName("a b "));
    }

    @Test
    public void validateRoleName_controlChars() {
        assertEquals(RoleActionRules.ERR_NAME_CONTROL, RoleActionRules.validateRoleName("ab\nc"));
        assertEquals(RoleActionRules.ERR_NAME_CONTROL, RoleActionRules.validateRoleName("ab\tc"));
        assertEquals(RoleActionRules.ERR_NAME_CONTROL,
                RoleActionRules.validateRoleName("ab\u0001c"));
        assertEquals(RoleActionRules.ERR_NAME_CONTROL,
                RoleActionRules.validateRoleName("abc\u007f"));
    }

    @Test
    public void validateRoleName_lengthBounds() {
        assertEquals(RoleActionRules.ERR_NAME_LENGTH, RoleActionRules.validateRoleName("ab"));
        assertEquals(RoleActionRules.ERR_NAME_LENGTH,
                RoleActionRules.validateRoleName("abcdefghijklm"));   // 13
        assertNull(RoleActionRules.validateRoleName("abc"));             // 3 OK
        assertNull(RoleActionRules.validateRoleName("abcdefghijkl"));    // 12 OK
    }

    @Test
    public void validateRoleName_chineseOk() {
        assertNull(RoleActionRules.validateRoleName("小鹿矿工5号"));
        assertNull(RoleActionRules.validateRoleName("小鹿矿工五号在冬天上线了"));   // 12 个汉字
    }

    // ---------- 改名消耗提示（§二.3 usedDiamond → 400 钻石 / 改名道具） ----------

    @Test
    public void renameCostText_byUsedDiamond() {
        assertEquals("本次消耗：400 钻石", RoleActionRules.renameCostText(true));
        assertEquals("本次消耗：改名道具", RoleActionRules.renameCostText(false));
    }

    @Test
    public void renameCostHint_dialogText() {
        assertEquals("本次消耗改名道具；不足时自动消耗 400 钻石",
                RoleActionRules.RENAME_COST_HINT);
    }

    // ---------- 启用/收纳（§三.4） ----------

    @Test
    public void shelveLabels_andBadge() {
        assertEquals("启用角色", RoleActionRules.ENABLE_LABEL);
        assertEquals("收纳角色", RoleActionRules.SHELVE_LABEL);
        assertEquals("已收纳", RoleActionRules.SHELVED_BADGE);
    }
}
