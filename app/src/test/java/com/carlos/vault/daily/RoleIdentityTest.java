package com.carlos.vault.daily;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.RoleIdentity.Identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 角色身份（serverid, uid）纯逻辑单测——同服多角色修复的核心语义：
 * 同 sid 不同 uid 是两个角色、旧 uid=0 记录原地升级、开关/查找按复合身份、
 * active 复合键解析与旧键迁移。RoleStore（Android SharedPreferences）只是这些
 * 纯函数的薄壳，逻辑正确性由本类保证。
 */
public class RoleIdentityTest {

    /** 测试用角色行（纯数据，不碰 Android）。 */
    private static final class Row implements Identity {
        final long sid;
        final long uid;
        boolean enabled;

        Row(long sid, long uid, boolean enabled) {
            this.sid = sid;
            this.uid = uid;
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

    private static Row row(long sid, long uid, boolean enabled) {
        return new Row(sid, uid, enabled);
    }

    // ---------- 复合键 ----------

    @Test
    public void keyAndParse_roundTrip() {
        assertEquals("888:88280792", RoleIdentity.key(888, 88280792));
        assertEquals("888:0", RoleIdentity.key(888, 0));
        assertArrayEquals(new long[]{888, 88280792}, RoleIdentity.parseKey("888:88280792"));
        assertArrayEquals(new long[]{888, 0}, RoleIdentity.parseKey("888:0"));
        assertNull(RoleIdentity.parseKey(null));
        assertNull(RoleIdentity.parseKey(""));
        assertNull(RoleIdentity.parseKey("888"));
        assertNull(RoleIdentity.parseKey(":12"));
        assertNull(RoleIdentity.parseKey("888:"));
        assertNull(RoleIdentity.parseKey("abc:12"));
        assertNull(RoleIdentity.parseKey("888:-1"));
    }

    // ---------- 身份匹配 ----------

    @Test
    public void sameIdentity_requiresBothUidsKnown() {
        assertTrue(RoleIdentity.sameIdentity(888, 111, 888, 111));
        assertFalse(RoleIdentity.sameIdentity(888, 111, 888, 222));
        assertFalse(RoleIdentity.sameIdentity(888, 0, 888, 0));     // 两侧未知不算同一角色
        assertFalse(RoleIdentity.sameIdentity(888, 111, 999, 111));
        assertFalse(RoleIdentity.sameIdentity(0, 111, 0, 111));
    }

    @Test
    public void matches_upgradesLegacyZeroUidButKeepsSameSidDistinct() {
        // 旧记录 uid=0：新 uid 已知 → 宽松命中（原地升级）
        assertTrue(RoleIdentity.matches(888, 0, 888, 88280792));
        assertTrue(RoleIdentity.matches(888, 88280792, 888, 0));
        // 同 sid 两个已知 uid → 不命中（不同角色）
        assertFalse(RoleIdentity.matches(888, 35371154, 888, 88280792));
        // 不同 sid 永不命中
        assertFalse(RoleIdentity.matches(888, 35371154, 999, 35371154));
        assertFalse(RoleIdentity.matches(0, 0, 0, 0));
    }

    // ---------- 列表定位（同服双角色不覆盖、开关互不影响） ----------

    @Test
    public void indexOfLoose_sameServerTwoRolesRegisterAsTwoRows() {
        List<Row> list = new ArrayList<>(Arrays.asList(row(888, 35371154, false)));
        // 第二个同服角色：宽松匹配不命中 → 调用方（RoleStore.registerRole）新建行
        assertEquals(-1, RoleIdentity.indexOfLoose(list, 888, 88280792));
        list.add(row(888, 88280792, false));
        assertEquals(2, list.size());
        // 旧记录 uid=0 时的升级语义
        List<Row> legacy = new ArrayList<>(Arrays.asList(row(888, 0, false)));
        assertEquals(0, RoleIdentity.indexOfLoose(legacy, 888, 88280792));
    }

    @Test
    public void indexOf_strictByUid_legacyFallbackBySid() {
        List<Row> list = new ArrayList<>(Arrays.asList(
                row(888, 35371154, true), row(888, 88280792, false)));
        // 开关/查询按 (sid,uid) 精确命中，互不影响
        assertEquals(0, RoleIdentity.indexOf(list, 888, 35371154));
        assertEquals(1, RoleIdentity.indexOf(list, 888, 88280792));
        assertEquals(-1, RoleIdentity.indexOf(list, 888, 999));   // 不存在的 uid
        // uid 未知（旧口径调用）：退化该 sid 第一行
        assertEquals(0, RoleIdentity.indexOf(list, 888, 0));
    }

    @Test
    public void ordinalOf_sameServerNumbering() {
        List<Row> list = new ArrayList<>(Arrays.asList(
                row(888, 35371154, false), row(913, 555, false), row(888, 88280792, false)));
        assertEquals(1, RoleIdentity.ordinalOf(list, 888, 35371154));
        assertEquals(2, RoleIdentity.ordinalOf(list, 888, 88280792));
        assertEquals(1, RoleIdentity.ordinalOf(list, 913, 555));
        assertEquals(0, RoleIdentity.ordinalOf(list, 888, 777));   // 未找到
        assertEquals(2, RoleIdentity.countOfServer(list, 888));
        assertEquals(1, RoleIdentity.countOfServer(list, 913));
    }

    // ---------- active 复合键迁移 ----------

    @Test
    public void resolveUidForSid_legacyActiveMigrationPrefersWorkspaceRole() {
        List<Row> list = new ArrayList<>(Arrays.asList(
                row(888, 35371154, false), row(888, 88280792, true)));
        // 旧 active:<fpid> 只有 sid=888 → 补 uid 时优先工作区内角色
        assertEquals(88280792, RoleIdentity.resolveUidForSid(list, 888, ENABLED));
        // 无启用角色 → 第一个
        List<Row> list2 = new ArrayList<>(Arrays.asList(
                row(888, 35371154, false), row(888, 88280792, false)));
        assertEquals(35371154, RoleIdentity.resolveUidForSid(list2, 888, ENABLED));
        // 该 sid 无任何行 → 0（保持未知，不瞎猜）
        assertEquals(0, RoleIdentity.resolveUidForSid(list, 999, ENABLED));
    }

    @Test
    public void activeValue_parseAndKey_compatibleWithRoleStoreFormat() {
        // RoleStore.active2:<fpid> 的值为 "sid:uid"；旧设备无此键时回退 active:<fpid>(long)
        long[] active = RoleIdentity.parseKey("888:88280792");
        assertArrayEquals(new long[]{888, 88280792}, active);
        // 写入口径：setActiveRole(fpid, sid, uid) → key(sid, uid)
        assertEquals("888:88280792", RoleIdentity.key(888, 88280792));
        // uid 未知（旧数据/未同步）：序列化为 0，读取方按 0 处理（sid 口径）
        assertArrayEquals(new long[]{888, 0}, RoleIdentity.parseKey(RoleIdentity.key(888, 0)));
    }

    // ---------- 展示 ----------

    @Test
    public void serverLabel_distinguishesSameServerRoles() {
        List<Row> single = new ArrayList<>(Arrays.asList(row(888, 88280792, false)));
        assertEquals("888服", RoleIdentity.serverLabel(single, 888, 88280792));
        List<Row> multi = new ArrayList<>(Arrays.asList(
                row(888, 35371154, false), row(888, 88280792, false)));
        assertEquals("888服 · 同服第1角色", RoleIdentity.serverLabel(multi, 888, 35371154));
        assertEquals("888服 · 同服第2角色", RoleIdentity.serverLabel(multi, 888, 88280792));
    }

    // ---------- 服务器角色回写门控（防回滚） ----------

    /** 真机案例常量：888 服隆科多 uid=35371154 / 小鹿矿工5号 uid=88280792。 */
    private static final long SID = 888;
    private static final long UID_A = 35371154;   // 隆科多
    private static final long UID_B = 88280792;   // 小鹿矿工5号

    @Test
    public void shouldLearn_noLocalChange_learnsServerRole() {
        // 从未记录过激活时间（localTs=0）：服务端说什么就学什么（首次/旧数据）
        assertTrue(RoleIdentity.shouldLearnActiveRole(-1, 0, 0, SID, UID_A, 1_000L));
        assertTrue(RoleIdentity.shouldLearnActiveRole(0, 0, 0, SID, UID_A, 1_000L));
    }

    @Test
    public void shouldLearn_localSwitchedAfterRequest_skipsToPreventRollback() {
        // 防回滚核心：请求发起(T0)后用户才切到小鹿(T1> T0)，回传是切换前旧快照 → 跳过
        long requestTs = 1_000L;
        long localTs = 1_500L;
        assertFalse(RoleIdentity.shouldLearnActiveRole(SID, UID_B, localTs, SID, UID_A, requestTs));
        // 跨服同理：本地已切到 913，888 的旧回传不采纳
        assertFalse(RoleIdentity.shouldLearnActiveRole(913, 555, localTs, SID, UID_A, requestTs));
    }

    @Test
    public void shouldLearn_sameIdentity_noOp() {
        // 本地与服务端同角色 → 无需回写
        assertFalse(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 500L, SID, UID_B, 1_000L));
    }

    @Test
    public void shouldLearn_legacyLocalZeroUid_upgradesFromServer() {
        // 旧数据本地 uid=0（sid 口径）：服务端给了 uid → 放行学习（原地升级）
        assertTrue(RoleIdentity.shouldLearnActiveRole(SID, 0, 500L, SID, UID_B, 1_000L));
        // 两侧都 uid=0（旧客户端 ↔ 旧服务器）：保持旧行为可学习
        assertTrue(RoleIdentity.shouldLearnActiveRole(SID, 0, 500L, SID, 0, 1_000L));
    }

    @Test
    public void shouldLearn_knownLocalUid_neverDowngradedByOldServer() {
        // 本地已知 uid、服务端 uid 缺失（旧服务器无 activeUid）：不降级，
        // 否则 setActiveRole(sid,0) 会按 sid 解析落到同服另一个角色
        assertFalse(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 500L, SID, 0, 1_000L));
    }

    @Test
    public void shouldLearn_invalidServerSid_skips() {
        assertFalse(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 500L, 0, UID_B, 1_000L));
        assertFalse(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 500L, -1, UID_B, 1_000L));
    }

    @Test
    public void shouldLearn_equalTimestamps_skipsConservatively() {
        // 边界：本地切换与请求发起同一毫秒——按跳过处理（误跳下次同步自愈，误采纳则回滚）
        assertFalse(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 1_000L, SID, UID_A, 1_000L));
    }

    @Test
    public void shouldLearn_localOlderThanRequest_learns() {
        // 本地切换早于请求发起：回传至少和本地一样新 → 正常学习（含跨服切换场景）
        assertTrue(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 500L, SID, UID_A, 1_000L));
        assertTrue(RoleIdentity.shouldLearnActiveRole(SID, UID_B, 500L, 913, 555, 1_000L));
    }

    // ---------- 服务器元数据学习门控（P1-2 防同服串行） ----------

    @Test
    public void shouldLearnServerMeta_knownUid_learnsByIdentity() {
        // uid>0（新服务器下发 activeUid 的正常路径）：身份完整，按 (sid,uid) 精确学习
        List<Row> multi = new ArrayList<>(Arrays.asList(
                row(SID, UID_A, false), row(SID, UID_B, true)));
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, UID_A, multi));
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, UID_B, multi));
        // 表里没有该 uid → 新建行学习，仍放行
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, 777, multi));
        // 空表（单角色账号首登）
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, UID_A, new ArrayList<>()));
    }

    @Test
    public void shouldLearnServerMeta_zeroUidWithKnownRows_skipsToPreventPollution() {
        // P1-2 核心：旧服务器 uid=0，宽松匹配必落到同服第一行 → 跳过，不污染
        List<Row> multi = new ArrayList<>(Arrays.asList(
                row(SID, UID_B, true), row(SID, UID_A, false)));
        assertFalse(RoleIdentity.shouldLearnServerMeta(SID, 0, multi));
        // 该 sid 无行、但别的 sid 有已知行：判定只看该 sid，仍允许首次引导
        List<Row> other = new ArrayList<>(Arrays.asList(row(913, 555, true)));
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, 0, other));
    }

    @Test
    public void shouldLearnServerMeta_zeroUidNoKnownRows_allowsFirstBootstrap() {
        // 单角色账号首次引导：空表 / 仅有 uid=0 旧行 → 允许 uid=0 登记（随后由登录升级）
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, 0, new ArrayList<>()));
        List<Row> legacy = new ArrayList<>(Arrays.asList(row(SID, 0, false)));
        assertTrue(RoleIdentity.shouldLearnServerMeta(SID, 0, legacy));
        // 防御：sid 无效不产生 sid=0 行
        assertFalse(RoleIdentity.shouldLearnServerMeta(0, 0, new ArrayList<>()));
        assertFalse(RoleIdentity.shouldLearnServerMeta(-1, 555, new ArrayList<>()));
    }

    // ---------- 发现角色元数据清洗判定（P3） ----------

    @Test
    public void shouldClearStaleMeta_nameCorrected_clearsResidualMeta() {
        // 名字被修正（旧名字非空且不同=旧名字是错的，如被串行污染）→ 清零残留元数据
        assertTrue(RoleIdentity.shouldClearStaleMeta("隆科多", "小鹿矿工5号"));
    }

    @Test
    public void shouldClearStaleMeta_nameUnchangedOrFillIn_keepsMeta() {
        assertFalse(RoleIdentity.shouldClearStaleMeta("小鹿矿工5号", "小鹿矿工5号"));  // 未变不动
        assertFalse(RoleIdentity.shouldClearStaleMeta("", "小鹿矿工5号"));             // 补全非修正
        assertFalse(RoleIdentity.shouldClearStaleMeta("小鹿矿工5号", ""));             // 空名不覆盖=未变
        assertFalse(RoleIdentity.shouldClearStaleMeta("", ""));                       // 两侧都空
        assertFalse(RoleIdentity.shouldClearStaleMeta(null, "小鹿矿工5号"));
        assertFalse(RoleIdentity.shouldClearStaleMeta("小鹿矿工5号", null));
    }

    // ---------- pickNewerRoleSource（2026-10-20 启动双源对齐） ----------

    @Test
    public void pickNewerRoleSource_activeNewer_useActive() {
        assertEquals(RoleIdentity.SOURCE_ACTIVE,
                RoleIdentity.pickNewerRoleSource(888, 35371154, 2000, 888, 88280792, 1000));
    }

    @Test
    public void pickNewerRoleSource_currentNewer_useCurrent() {
        assertEquals(RoleIdentity.SOURCE_CURRENT,
                RoleIdentity.pickNewerRoleSource(888, 35371154, 1000, 888, 88280792, 2000));
    }

    @Test
    public void pickNewerRoleSource_equalTimestamps_keepCurrentBehavior() {
        assertEquals(RoleIdentity.SOURCE_CURRENT,
                RoleIdentity.pickNewerRoleSource(888, 35371154, 1000, 888, 88280792, 1000));
    }

    @Test
    public void pickNewerRoleSource_currentInvalid_useActive() {
        assertEquals(RoleIdentity.SOURCE_ACTIVE,
                RoleIdentity.pickNewerRoleSource(888, 35371154, 1000, 0, 0, 0));
    }

    @Test
    public void pickNewerRoleSource_activeInvalid_useCurrent() {
        assertEquals(RoleIdentity.SOURCE_CURRENT,
                RoleIdentity.pickNewerRoleSource(0, 0, 0, 888, 88280792, 1000));
    }

    @Test
    public void pickNewerRoleSource_missingTimestamps_legacyCurrentWins() {
        assertEquals(RoleIdentity.SOURCE_CURRENT,
                RoleIdentity.pickNewerRoleSource(888, 35371154, 0, 888, 88280792, 0));
    }
}
