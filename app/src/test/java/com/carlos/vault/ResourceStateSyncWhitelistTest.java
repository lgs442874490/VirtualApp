package com.carlos.vault;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TC（P1-4）：资源状态同步的白名单口径。
 * 只允许清单指纹与资源版本号键进入 canonical——token/session/lastUserId/登录步骤
 * 等账号私有键绝不能被同步（复制会串号）。纯函数，JVM 可跑。
 */
public class ResourceStateSyncWhitelistTest {

    @Test
    public void manifestFingerprints_match() {
        assertTrue(ResourceStateSync.isWhitelistKey("__KEY_MD5_PATCH__"));
        assertTrue(ResourceStateSync.isWhitelistKey("__KEY_MD5_SPLIT__"));
    }

    @Test
    public void resVersionKeys_match() {
        // 真机实测键形态：__KEY_RES_VERSION_1784206943__ / __KEY_RES_VERSION_1788966990__
        assertTrue(ResourceStateSync.isWhitelistKey("__KEY_RES_VERSION_1784206943__"));
        assertTrue(ResourceStateSync.isWhitelistKey("__KEY_RES_VERSION_1788966990__"));
    }

    @Test
    public void accountPrivateKeys_neverMatch() {
        // 账号凭据与流程状态：一个都不许进白名单
        assertFalse(ResourceStateSync.isWhitelistKey("com.cg.sdk_session"));
        assertFalse(ResourceStateSync.isWhitelistKey("lastUserId"));
        assertFalse(ResourceStateSync.isWhitelistKey("login_step_first_CheckHotFix"));
        assertFalse(ResourceStateSync.isWhitelistKey("login_step_first_DownloadSplitAsset"));
        assertFalse(ResourceStateSync.isWhitelistKey("NEED_CHECK_SPLIT_ASSET"));
        assertFalse(ResourceStateSync.isWhitelistKey("USER_SETTING_20778222"));
    }

    @Test
    public void degenerateForms_neverMatch() {
        // 前缀相同但不是完整 RES_VERSION 键形态（无尾 __ / 空 id）不命中
        assertFalse(ResourceStateSync.isWhitelistKey("__KEY_RES_VERSION_"));
        assertFalse(ResourceStateSync.isWhitelistKey("__KEY_RES_VERSION_123"));
        assertFalse(ResourceStateSync.isWhitelistKey("__KEY_MD5_PATCH"));
        assertFalse(ResourceStateSync.isWhitelistKey("__KEY_MD5_PATCH__EXTRA"));
        assertFalse(ResourceStateSync.isWhitelistKey(""));
        assertFalse(ResourceStateSync.isWhitelistKey(null));
    }
}
