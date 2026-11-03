package com.carlos.vault.daily;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code home:current_role} 键纯逻辑单测。
 *
 * <p>覆盖 2026-11-03 切号 bug 的口径：账号是主键，sid=0 表示「该账号暂无角色数据」，
 * 键必须仍能解析出 fpid（据此把今日/日常页落到新账号上）。
 */
public class CurrentRoleKeyTest {

    // ---------- 组装 ----------

    @Test
    public void of_withRoleAndUid_keepsThreeParts() {
        assertEquals("48460403:888:35371154", CurrentRoleKey.of("48460403", 888, 35371154));
    }

    @Test
    public void of_withRoleNoUid_keepsTwoParts() {
        assertEquals("48460403:888", CurrentRoleKey.of("48460403", 888, 0));
    }

    @Test
    public void of_noRole_writesZeroSidAndDropsUid() {
        // 无角色账号：键仍指向账号（fpid:0），这是「切号必须落到新账号」的落点
        assertEquals("107365823:0", CurrentRoleKey.of("107365823", 0, 0));
        assertEquals("107365823:0", CurrentRoleKey.of("107365823", 0, 12345));
    }

    @Test
    public void of_negativeSid_clampedToZero() {
        assertEquals("107365823:0", CurrentRoleKey.of("107365823", -1, 0));
    }

    @Test
    public void of_blankFpid_returnsEmpty() {
        assertEquals("", CurrentRoleKey.of(null, 888, 1));
        assertEquals("", CurrentRoleKey.of("   ", 888, 1));
    }

    @Test
    public void of_trimsFpid() {
        assertEquals("48460403:888", CurrentRoleKey.of(" 48460403 ", 888, 0));
    }

    // ---------- 拆解 ----------

    @Test
    public void parse_threeParts() {
        CurrentRoleKey.Parts p = CurrentRoleKey.parse("48460403:888:35371154");
        assertEquals("48460403", p.fpid);
        assertEquals(888L, p.sid);
        assertEquals(35371154L, p.uid);
        assertTrue(p.hasRole());
    }

    @Test
    public void parse_twoParts_uidDefaultsZero() {
        CurrentRoleKey.Parts p = CurrentRoleKey.parse("48460403:888");
        assertEquals("48460403", p.fpid);
        assertEquals(888L, p.sid);
        assertEquals(0L, p.uid);
        assertTrue(p.hasRole());
    }

    @Test
    public void parse_noRoleAccount_isValidAndPointsAtAccount() {
        CurrentRoleKey.Parts p = CurrentRoleKey.parse("107365823:0");
        assertEquals("107365823", p.fpid);
        assertEquals(0L, p.sid);
        assertFalse(p.hasRole());
        // 关键：仍然解析出账号，页面才能按新账号渲染（而不是继续用旧账号）
        assertTrue(CurrentRoleKey.belongsTo("107365823:0", "107365823"));
    }

    @Test
    public void parse_blankIgnored() {
        assertNull(CurrentRoleKey.parse(null));
        assertNull(CurrentRoleKey.parse(""));
        assertNull(CurrentRoleKey.parse("   "));
        assertNull(CurrentRoleKey.parse("48460403"));
        assertNull(CurrentRoleKey.parse(":888"));
        assertNull(CurrentRoleKey.parse("48460403:abc"));
        assertNull(CurrentRoleKey.parse("48460403:-1"));
    }

    @Test
    public void parse_brokenUidFallsBackToZero() {
        CurrentRoleKey.Parts p = CurrentRoleKey.parse("48460403:888:oops");
        assertEquals(888L, p.sid);
        assertEquals(0L, p.uid);
    }

    // ---------- 归属判定（切号防串台的核心） ----------

    @Test
    public void belongsTo_matchesOnlySameAccount() {
        assertTrue(CurrentRoleKey.belongsTo("48460403:888:35371154", "48460403"));
        assertFalse(CurrentRoleKey.belongsTo("48460403:888:35371154", "107365823"));
        assertFalse(CurrentRoleKey.belongsTo(null, "107365823"));
        assertFalse(CurrentRoleKey.belongsTo("107365823:0", null));
    }

    @Test
    public void roundTrip_parseThenOf_isStable() {
        for (String raw : new String[]{"48460403:888", "48460403:888:35371154", "107365823:0"}) {
            CurrentRoleKey.Parts p = CurrentRoleKey.parse(raw);
            assertEquals(raw, CurrentRoleKey.of(p.fpid, p.sid, p.uid));
        }
    }
}
