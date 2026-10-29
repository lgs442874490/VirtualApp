package com.carlos.vaultserver;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R2 Phase 2B：{@link StateStore#removeRoleKeys(String, long, long)}（角色删除清理）。
 *
 * <p>语义：只删“白名单 family + 完整角色段 {@code :fpid:sid:uid}”的键（含 {@code st:<day>:…} 按日键、
 * {@code res:} 等无尾段精确键）；uid&lt;=0 / 空 fpid 返回 0 且**绝不删任何键**；兄弟角色、
 * 旧（无 uid）键、账号级键、全局键必须保留。
 */
public class StateStoreRemoveRoleKeysTest {

    private static final String FPID = "48460403";
    private static final String OTHER_FPID = "57259770";
    private static final long SID = 888L;
    private static final long UID = 35371154L;
    private static final long SIBLING_UID = 88280792L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private final List<String> targetKeys = new ArrayList<>();
    private final List<String> keepKeys = new ArrayList<>();

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        String r = FPID + ":" + SID + ":" + UID;   // 角色段
        targetKeys.clear();
        targetKeys.add("mod:" + r + ":guardian");
        targetKeys.add("log:" + r + ":mail");
        targetKeys.add("log:" + r + ":medic:heal");                       // 子项日志
        targetKeys.add("st:2026-10-29:" + r + ":mail");                   // 按日键
        targetKeys.add("st:2026-10-28:" + r + ":mail");                   // 跨天也要清
        targetKeys.add("res:" + r);                                       // 无尾段精确键
        targetKeys.add("todo:" + r);
        targetKeys.add("questts:" + r);
        targetKeys.add("ledger:" + r + ":activity_rewards");
        targetKeys.add("summary:" + r);
        targetKeys.add("srv:cad:" + r + ":mail");
        targetKeys.add("srv:cadlast:" + r);
        targetKeys.add("srv:lastrun:" + r);
        targetKeys.add("srv:supplyfire:" + r);
        targetKeys.add("srv:guardian:" + r + ":ledger");
        targetKeys.add("srv:alliance_science:" + r);
        targetKeys.add("srv:alliance_help:" + r);
        targetKeys.add("war:" + r);
        targetKeys.add("srv:asset:" + r);
        targetKeys.add("srv:formation:catalog:" + r);
        for (String k : targetKeys) state.putString(k, "x");

        // 兄弟角色（同 fpid+sid 另一 uid）：必须保留
        String sib = FPID + ":" + SID + ":" + SIBLING_UID;
        keepKeys.add("mod:" + sib + ":guardian");
        keepKeys.add("log:" + sib + ":mail");
        keepKeys.add("res:" + sib);
        keepKeys.add("srv:guardian:" + sib + ":ledger");
        // 旧（无 uid）键：必须保留
        keepKeys.add("mod:" + FPID + ":" + SID + ":alliance_help");
        keepKeys.add("res:" + FPID + ":" + SID);
        keepKeys.add("srv:guardian:" + FPID + ":" + SID + ":ledger");
        keepKeys.add("st:2026-10-29:" + FPID + ":" + SID + ":mail");
        keepKeys.add("log:" + FPID + ":" + SID + ":mail");
        // 账号级 / 告警 / 全局：必须保留
        keepKeys.add("summary:" + FPID);
        keepKeys.add("srv:lastrun:" + FPID);
        keepKeys.add("srv:cadlast:" + FPID);
        keepKeys.add("srv:cad:stats:2026-10-29");
        keepKeys.add("srv:digest:2026-10-29");
        keepKeys.add("srv:alert:offline:" + FPID + ":2026-10-29");
        keepKeys.add("srv:alliance_help_settled:" + FPID + ":" + SID);
        // 其他账号（同 sid/uid 数字巧合）也必须保留
        keepKeys.add("mod:" + OTHER_FPID + ":" + SID + ":" + UID + ":guardian");
        for (String k : keepKeys) state.putString(k, "keep-me");
    }

    @Test
    public void removeRoleKeys_deletesOnlyTargetRoleAndReturnsCount() {
        int removed = state.removeRoleKeys(FPID, SID, UID);
        assertEquals("删除条数=目标角色键数", targetKeys.size(), removed);
        for (String k : targetKeys) {
            assertFalse("目标角色键应删除: " + k, state.snapshot().containsKey(k));
        }
        for (String k : keepKeys) {
            assertTrue("兄弟/旧/账号/全局键必须保留: " + k, state.snapshot().containsKey(k));
        }
        assertEquals("幂等：再删一次返回 0", 0, state.removeRoleKeys(FPID, SID, UID));
    }

    @Test
    public void removeRoleKeys_uidNonPositive_deletesNothing() {
        Map<String, Object> before = state.snapshot();
        assertEquals(0, state.removeRoleKeys(FPID, SID, 0L));
        assertEquals(0, state.removeRoleKeys(FPID, SID, -7L));
        assertEquals("uid<=0 不得删除任何键", before, state.snapshot());
    }

    @Test
    public void removeRoleKeys_blankFpid_deletesNothing() {
        Map<String, Object> before = state.snapshot();
        assertEquals(0, state.removeRoleKeys(null, SID, UID));
        assertEquals(0, state.removeRoleKeys("", SID, UID));
        assertEquals(0, state.removeRoleKeys("   ", SID, UID));
        assertEquals("空 fpid 不得删除任何键（绝不整表清理）", before, state.snapshot());
    }

    @Test
    public void removeRoleKeys_unknownRole_noop() {
        Map<String, Object> before = state.snapshot();
        assertEquals(0, state.removeRoleKeys(FPID, 999L, 123L));
        assertEquals(before, state.snapshot());
    }
}
