package com.carlos.vaultserver.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * R2 Phase 2：观测键解析（HealthMonitor / DailyDigest）。
 *
 * <p>旧键 {@code log:fpid:sid:fn}（fn 可含冒号，如子项 {@code pet:alliance_chest}）
 * 与新键 {@code log:fpid:sid:uid:fn} 必须都能正确拆出 fpid/sid/uid/fn，避免
 * 新键下 fn 被当成 "uid:fn" 而误判为子项 → 领取/异常统计归零（假告警）。
 */
public class StateKeysObservedKeyTest {

    @Test
    public void splitRoleKey_legacyLogKey() {
        String[] p = StateKeys.splitRoleKey("log", "log:80971192:913:login_rewards");
        assertEquals("80971192", p[0]);
        assertEquals("913", p[1]);
        assertEquals("0", p[2]);
        assertEquals("login_rewards", p[3]);
    }

    @Test
    public void splitRoleKey_roleScopedLogKey() {
        String[] p = StateKeys.splitRoleKey("log", "log:80971192:913:35371154:login_rewards");
        assertEquals("80971192", p[0]);
        assertEquals("913", p[1]);
        assertEquals("35371154", p[2]);
        assertEquals("login_rewards", p[3]);
    }

    @Test
    public void splitRoleKey_subTaskWithColonBothShapes() {
        String[] legacy = StateKeys.splitRoleKey("log", "log:80971192:913:pet:alliance_chest");
        assertEquals("0", legacy[2]);
        assertEquals("pet:alliance_chest", legacy[3]);
        String[] scoped = StateKeys.splitRoleKey("log", "log:80971192:913:88280792:pet:alliance_chest");
        assertEquals("88280792", scoped[2]);
        assertEquals("pet:alliance_chest", scoped[3]);
    }

    @Test
    public void splitRoleKey_modAndOtherFamilies() {
        String[] legacy = StateKeys.splitRoleKey("mod", "mod:48460403:888:act_brawl");
        assertEquals("act_brawl", legacy[3]);
        String[] scoped = StateKeys.splitRoleKey("mod", "mod:48460403:888:88280792:alliance_help");
        assertEquals("88280792", scoped[2]);
        assertEquals("alliance_help", scoped[3]);
    }

    @Test
    public void splitRoleKey_rejectsBadShapes() {
        assertNull(StateKeys.splitRoleKey("log", null));
        assertNull("族不匹配", StateKeys.splitRoleKey("log", "res:1:2:3"));
        assertNull("段数不足", StateKeys.splitRoleKey("log", "log:1:2"));
        assertNull("空 fpid", StateKeys.splitRoleKey("log", "log::2:fn"));
        // 4 段且第 4 段是数字：长度不足 uid 键（>=5）→ 按旧键处理，fn="777"（兼容优先，不丢数据）
        String[] numericLegacy = StateKeys.splitRoleKey("log", "log:1:2:777");
        assertEquals("0", numericLegacy[2]);
        assertEquals("777", numericLegacy[3]);
    }

    @Test
    public void splitDayKey_legacyAndRoleScoped() {
        String[] legacy = StateKeys.splitDayKey("st:2026-10-29:48460403:888:login_rewards");
        assertEquals("2026-10-29", legacy[0]);
        assertEquals("48460403", legacy[1]);
        assertEquals("888", legacy[2]);
        assertEquals("0", legacy[3]);
        assertEquals("login_rewards", legacy[4]);

        String[] scoped = StateKeys.splitDayKey("st:2026-10-29:48460403:888:88280792:pet:alliance_chest");
        assertEquals("2026-10-29", scoped[0]);
        assertEquals("88280792", scoped[3]);
        assertEquals("pet:alliance_chest", scoped[4]);
    }

    @Test
    public void splitDayKey_rejectsBadShapes() {
        assertNull(StateKeys.splitDayKey(null));
        assertNull(StateKeys.splitDayKey("log:2026-10-29:1:2:fn"));
        assertNull(StateKeys.splitDayKey("st:2026-10-29:1:2"));
        String[] numericLegacy = StateKeys.splitDayKey("st:2026-10-29:1:2:777");
        assertEquals("0", numericLegacy[3]);
        assertEquals("777", numericLegacy[4]);
    }

    @Test
    public void roleKeyFormats_phase2() {
        RoleKey role = new RoleKey("48460403", 888L, 88280792L);
        assertEquals("log:48460403:888:88280792:mail", StateKeys.taskLog(role, "mail"));
        assertEquals("log:48460403:888:mail", StateKeys.taskLogLegacy(role, "mail"));
        assertEquals("st:2026-10-29:48460403:888:88280792:mail",
                StateKeys.dayState("2026-10-29", role, "mail"));
        // uid<=0 → 旧键形状（兼容路径）
        RoleKey legacy = new RoleKey("48460403", 888L, 0L);
        assertEquals("log:48460403:888:mail", StateKeys.taskLog(legacy, "mail"));
        assertEquals("48460403:888:0", legacy.suffix());
    }
}
