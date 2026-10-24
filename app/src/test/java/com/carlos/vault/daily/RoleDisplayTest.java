package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.RoleIdentity.Identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 角色展示纯逻辑单测（第二轮 UI 统一）：元数据行格式化（缺失段「未同步」）、
 * 服务器状态文案映射、服务器态与角色行匹配、工作区过滤。
 */
public class RoleDisplayTest {

    /** 测试用角色行（纯数据，不碰 Android）。 */
    private static final class Row implements Identity {
        final long sid;
        final long uid;
        final long level;
        final long power;
        final boolean enabled;

        Row(long sid, long uid, long level, long power, boolean enabled) {
            this.sid = sid;
            this.uid = uid;
            this.level = level;
            this.power = power;
            this.enabled = enabled;
        }

        @Override public long serverid() {
            return sid;
        }

        @Override public long uid() {
            return uid;
        }
    }

    private static final RoleIdentity.EnabledProbe ENABLED = r -> ((Row) r).enabled;

    /** 真机案例常量：888 服隆科多 uid=35371154 lv70 / 小鹿矿工5号 uid=88280792。 */
    private static final long SID = 888;
    private static final long UID_A = 35371154;
    private static final long UID_B = 88280792;

    private static Row row(long sid, long uid, long level, long power, boolean enabled) {
        return new Row(sid, uid, level, power, enabled);
    }

    // ---------- 元数据行 ----------

    @Test
    public void roleMetaLine_fullMeta_standardFormat() {
        assertEquals("K888 · UID 88280792 · 城堡 70 级 · 战力 20784万",
                RoleDisplay.roleMetaLine(SID, UID_B, 70, 207_840_000L));
        assertEquals("K888 · UID 35371154 · 城堡 1 级 · 战力 1234.6万",
                RoleDisplay.roleMetaLine(SID, UID_A, 1, 12_346_000L));
    }

    @Test
    public void roleMetaLine_missingSegments_showUnsynced() {
        // uid 未知 → UID 未同步
        assertEquals("K888 · UID 未同步 · 城堡 70 级 · 战力 20784万",
                RoleDisplay.roleMetaLine(SID, 0, 70, 207_840_000L));
        // 等级未知 → 城堡未同步
        assertEquals("K888 · UID 88280792 · 城堡未同步 · 战力 20784万",
                RoleDisplay.roleMetaLine(SID, UID_B, 0, 207_840_000L));
        // 战力未知 → 战力未同步
        assertEquals("K888 · UID 88280792 · 城堡 70 级 · 战力未同步",
                RoleDisplay.roleMetaLine(SID, UID_B, 70, 0));
        // 全部未知：只剩 K 前缀 + 三个未同步段
        assertEquals("K888 · UID 未同步 · 城堡未同步 · 战力未同步",
                RoleDisplay.roleMetaLine(SID, 0, 0, 0));
        // 负值同样按缺失处理（脏数据防御）
        assertEquals("K888 · UID 未同步 · 城堡未同步 · 战力未同步",
                RoleDisplay.roleMetaLine(SID, -1, -5, -100));
    }

    @Test
    public void roleName_empty_showsUnsynced() {
        assertEquals("小鹿矿工5号", RoleDisplay.roleName("小鹿矿工5号"));
        assertEquals("未同步", RoleDisplay.roleName(null));
        assertEquals("未同步", RoleDisplay.roleName(""));
    }

    @Test
    public void wan_integralDropsDecimal_fractionalKeepsOne() {
        assertEquals("20784万", RoleDisplay.wan(207_840_000L));   // 整数万去小数点
        assertEquals("1234.6万", RoleDisplay.wan(12_346_000L));   // 非整数保留一位
        assertEquals("9999", RoleDisplay.wan(9999));              // 小于 1 万原值
        assertEquals("0", RoleDisplay.wan(0));
    }

    // ---------- 服务器状态文案 ----------

    @Test
    public void serverStateLabel_allKnownStates() {
        assertEquals("在线", RoleDisplay.serverStateLabel("online"));
        assertEquals("重连中", RoleDisplay.serverStateLabel("recovering"));
        assertEquals("被顶号暂停", RoleDisplay.serverStateLabel("takeover"));
        assertEquals("需重导token", RoleDisplay.serverStateLabel("need_token"));
    }

    @Test
    public void serverStateLabel_unknownOrNull_offline() {
        assertEquals("离线", RoleDisplay.serverStateLabel(null));
        assertEquals("离线", RoleDisplay.serverStateLabel(""));
        assertEquals("离线", RoleDisplay.serverStateLabel("offline"));
        assertEquals("离线", RoleDisplay.serverStateLabel("something_new"));
    }

    @Test
    public void isServerOnline_onlyOnline() {
        assertTrue(RoleDisplay.isServerOnline("online"));
        assertFalse(RoleDisplay.isServerOnline("recovering"));
        assertFalse(RoleDisplay.isServerOnline(null));
    }

    // ---------- 服务器态与角色行匹配 ----------

    @Test
    public void roleMatchesServerStatus_newServer_exactUid() {
        // 新服务器带 activeUid：uid 均已知且相等才命中（同服两角色互不串）
        assertTrue(RoleDisplay.roleMatchesServerStatus(SID, UID_B, SID, UID_B, SID, UID_B));
        assertFalse(RoleDisplay.roleMatchesServerStatus(SID, UID_A, SID, UID_B, SID, UID_B));
        // 行 uid 未知（未同步）→ 无法确认，保守 false
        assertFalse(RoleDisplay.roleMatchesServerStatus(SID, 0, SID, UID_B, SID, UID_B));
        // 不同 sid 永不命中
        assertFalse(RoleDisplay.roleMatchesServerStatus(913, UID_B, SID, UID_B, SID, UID_B));
        // 状态无 sid（服务端未登录）
        assertFalse(RoleDisplay.roleMatchesServerStatus(SID, UID_B, 0, UID_B, SID, UID_B));
    }

    @Test
    public void roleMatchesServerStatus_oldServer_fallsBackToActiveRole() {
        // 旧服务器无 activeUid：退到激活角色口径
        assertTrue(RoleDisplay.roleMatchesServerStatus(SID, UID_A, SID, 0, SID, UID_A));
        assertFalse(RoleDisplay.roleMatchesServerStatus(SID, UID_B, SID, 0, SID, UID_A));
        // 激活 uid 也未知（旧数据）：sid 口径（已知限制：同服第二角色无法细分）
        assertTrue(RoleDisplay.roleMatchesServerStatus(SID, UID_B, SID, 0, SID, 0));
        assertFalse(RoleDisplay.roleMatchesServerStatus(SID, UID_B, SID, 0, 913, 0));
    }

    // ---------- 工作区过滤 ----------

    @Test
    public void workspaceRoles_onlyEnabled() {
        List<Row> list = new ArrayList<>(Arrays.asList(
                row(SID, UID_A, 70, 207_840_000L, true),
                row(SID, UID_B, 30, 5_000_000L, false),
                row(913, 555, 0, 0, true)));
        List<Row> ws = RoleDisplay.workspaceRoles(list, ENABLED);
        assertEquals(2, ws.size());
        assertEquals(UID_A, ws.get(0).uid());
        assertEquals(913, ws.get(1).serverid());
        // 不改原表
        assertEquals(3, list.size());
        // 空表/空入参安全
        assertTrue(RoleDisplay.workspaceRoles(new ArrayList<>(), ENABLED).isEmpty());
        assertTrue(RoleDisplay.workspaceRoles(null, ENABLED).isEmpty());
    }

    @Test
    public void firstWorkspaceRole_firstEnabledOrNull() {
        List<Row> list = new ArrayList<>(Arrays.asList(
                row(SID, UID_A, 70, 207_840_000L, false),
                row(SID, UID_B, 30, 5_000_000L, true)));
        Row first = RoleDisplay.firstWorkspaceRole(list, ENABLED);
        assertNotNull(first);
        assertEquals(UID_B, first.uid());
        // 全部未启用 → null（调用方提示去角色管理添加）
        List<Row> none = new ArrayList<>(Arrays.asList(
                row(SID, UID_A, 70, 207_840_000L, false)));
        assertNull(RoleDisplay.firstWorkspaceRole(none, ENABLED));
        assertNull(RoleDisplay.firstWorkspaceRole(new ArrayList<>(), ENABLED));
        assertNull(RoleDisplay.firstWorkspaceRole(null, ENABLED));
    }

    @Test
    public void roleListLabel_zoneAbbrName() {
        assertEquals("888-cch-小鹿矿工5号",
                RoleDisplay.roleListLabel(888L, "cch", "小鹿矿工5号"));
        assertEquals("888-YvY-隆科多", RoleDisplay.roleListLabel(888L, "YvY", "隆科多"));
        // 缺失段统一「未同步」，不编造
        assertEquals("未同步-未同步-未同步", RoleDisplay.roleListLabel(0L, "", ""));
        assertEquals("888-未同步-小鹿矿工5号", RoleDisplay.roleListLabel(888L, "", "小鹿矿工5号"));
        // 简称去空白
        assertEquals("888-cch-名字", RoleDisplay.roleListLabel(888L, " cch ", "名字"));
    }
}
