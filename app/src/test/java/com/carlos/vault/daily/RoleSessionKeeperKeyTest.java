package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * 本地常驻会话键（2026-11-08）：同账号同服多角色 sid 相同，键必须带 uid，
 * 否则两个 LiveSession 会互相覆盖（连接泄漏 + 结算误报）。
 */
public class RoleSessionKeeperKeyTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Test
    public void sameSidDifferentUid_distinctKeys() {
        String a = RoleSessionKeeper.key(FPID, SID, 35371154L);
        String b = RoleSessionKeeper.key(FPID, SID, 88280792L);
        assertNotEquals("同服双角色会话键必须不同", a, b);
        assertEquals("48460403:888:35371154", a);
        assertEquals("48460403:888:88280792", b);
    }

    @Test
    public void unknownUid_fallsBackToLegacyKey() {
        assertEquals("uid 未知保持旧键（兼容旧数据）",
                RoleSessionKeeper.key(FPID, SID), RoleSessionKeeper.key(FPID, SID, 0L));
        assertEquals("48460403:888", RoleSessionKeeper.key(FPID, SID, 0L));
    }
}
