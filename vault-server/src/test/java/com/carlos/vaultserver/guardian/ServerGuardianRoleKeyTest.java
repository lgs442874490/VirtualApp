package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * R2 Phase 2B：守护盟友账本/状态键按角色 uid 隔离（ServerGuardianEngine）。
 *
 * <p>键规则：uid&gt;0 → {@code srv:guardian:<fpid>:<sid>:<uid>:ledger} /
 * {@code mod:<fpid>:<sid>:<uid>:guardian}；active 角色双写旧键（App 展示不变）；
 * 兄弟角色只写角色键；读优先角色键、缺失回退旧键；旧构造器（uid=0）行为不变。
 */
public class ServerGuardianRoleKeyTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
    }

    private ServerGuardianEngine engine(long uid, AtomicBoolean active) {
        return new ServerGuardianEngine(FPID, SID, uid, state, null,
                active == null ? null : active::get);
    }

    private static JSONObject entry(String note) {
        JSONObject e = new JSONObject();
        e.put("note", note);
        return e;
    }

    private JSONArray ledger(String key) {
        String raw = state.getString(key);
        return raw == null ? new JSONArray() : new JSONArray(raw);
    }

    private String legacyLedgerKey() {
        return ServerGuardianEngine.ledgerKey(FPID, SID);
    }

    private String legacyModKey() {
        return ServerGuardianEngine.modKey(FPID, SID);
    }

    private String roleLedgerKey(long uid) {
        return "srv:guardian:" + FPID + ":" + SID + ":" + uid + ":ledger";
    }

    private String roleModKey(long uid) {
        return "mod:" + FPID + ":" + SID + ":" + uid + ":guardian";
    }

    // ==================== 键型 ====================

    @Test
    public void keyFormats_roleAndLegacy() {
        ServerGuardianEngine a = engine(UID_A, null);
        assertEquals(roleLedgerKey(UID_A), a.ledgerKey());
        assertEquals(roleModKey(UID_A), a.modKey());

        ServerGuardianEngine legacy = new ServerGuardianEngine(FPID, SID, state, null);
        assertEquals(legacyLedgerKey(), legacy.ledgerKey());
        assertEquals(legacyModKey(), legacy.modKey());
    }

    // ==================== 双角色独立 ====================

    @Test
    public void siblingRoles_writeTheirOwnKeys_andNeverTouchLegacyKey() {
        AtomicBoolean inactive = new AtomicBoolean(false);
        ServerGuardianEngine a = engine(UID_A, inactive);
        ServerGuardianEngine b = engine(UID_B, inactive);

        a.writeMod("A 状态");
        b.writeMod("B 状态");
        a.prependLedger(entry("A 账本"));
        b.prependLedger(entry("B 账本"));

        assertEquals("A 状态", new JSONObject(state.getString(roleModKey(UID_A))).optString("last"));
        assertEquals("B 状态", new JSONObject(state.getString(roleModKey(UID_B))).optString("last"));
        assertEquals(1, ledger(roleLedgerKey(UID_A)).length());
        assertEquals("A 账本", ledger(roleLedgerKey(UID_A)).getJSONObject(0).optString("note"));
        assertEquals("B 账本", ledger(roleLedgerKey(UID_B)).getJSONObject(0).optString("note"));
        assertNull("两个都是兄弟角色：不写旧键", state.getString(legacyModKey()));
        assertNull(state.getString(legacyLedgerKey()));
    }

    // ==================== 主控镜像（active 双写） ====================

    @Test
    public void activeRole_mirrorsLegacyKey_siblingDoesNot() {
        ServerGuardianEngine a = engine(UID_A, new AtomicBoolean(true));
        a.writeMod("A 状态");
        a.prependLedger(entry("A 账本"));
        assertNotNull("主控角色双写旧 mod 键", state.getString(legacyModKey()));
        assertNotNull("主控角色双写旧 ledger 键", state.getString(legacyLedgerKey()));
        assertEquals("A 状态", new JSONObject(state.getString(legacyModKey())).optString("last"));
        assertEquals("A 账本", ledger(legacyLedgerKey()).getJSONObject(0).optString("note"));

        // 兄弟角色（非 active）只写自己的角色键，旧键保持主控内容
        ServerGuardianEngine b = engine(UID_B, new AtomicBoolean(false));
        b.writeMod("B 状态");
        b.prependLedger(entry("B 账本"));
        assertEquals("兄弟角色不得覆盖旧 mod 键", "A 状态",
                new JSONObject(state.getString(legacyModKey())).optString("last"));
        assertEquals("B 状态", new JSONObject(state.getString(roleModKey(UID_B))).optString("last"));
        assertEquals("B 账本", ledger(roleLedgerKey(UID_B)).getJSONObject(0).optString("note"));
    }

    @Test
    public void activeSupplierSwitch_togglesLegacyMirror() {
        AtomicBoolean active = new AtomicBoolean(false);
        ServerGuardianEngine e = engine(UID_A, active);
        e.writeMod("兄弟");
        assertNull("非 active 不写旧键", state.getString(legacyModKey()));

        active.set(true);
        e.writeMod("主控");
        assertNotNull("切成 active 后开始双写旧键", state.getString(legacyModKey()));
        assertEquals("主控", new JSONObject(state.getString(legacyModKey())).optString("last"));
        assertEquals("主控", new JSONObject(state.getString(roleModKey(UID_A))).optString("last"));
    }

    @Test
    public void nullActiveSupplier_treatedAsInactive() {
        ServerGuardianEngine e = engine(UID_A, null);
        e.writeMod("兄弟");
        assertNull("supplier=null 按非 active：不写旧键", state.getString(legacyModKey()));
        assertNotNull(state.getString(roleModKey(UID_A)));
    }

    // ==================== 读回退（旧键 → 角色键） ====================

    @Test
    public void readFallsBackToLegacy_thenWritesRoleKey() {
        // 升级前只有旧键（主控角色写的历史账本）
        state.putString(legacyLedgerKey(), new JSONArray().put(entry("旧账本")).toString());
        state.putString(legacyModKey(), new JSONObject().put("last", "旧状态").toString());

        ServerGuardianEngine a = engine(UID_A, new AtomicBoolean(false));
        a.writeMod("新状态");
        a.prependLedger(entry("新账本"));

        JSONObject mod = new JSONObject(state.getString(roleModKey(UID_A)));
        assertEquals("新状态", mod.optString("last"));
        JSONArray arr = ledger(roleLedgerKey(UID_A));
        assertEquals("旧键账本作为基底被继承", 2, arr.length());
        assertEquals("新账本", arr.getJSONObject(0).optString("note"));
        assertEquals("旧账本", arr.getJSONObject(1).optString("note"));
        assertEquals("兄弟角色不覆盖旧键", "旧状态",
                new JSONObject(state.getString(legacyModKey())).optString("last"));
    }

    // ==================== 旧构造器回归 ====================

    @Test
    public void legacyConstructor_writesOnlyLegacyKeys() {
        ServerGuardianEngine legacy = new ServerGuardianEngine(FPID, SID, state, null);
        legacy.writeMod("旧状态");
        legacy.prependLedger(entry("旧账本"));

        assertEquals("旧状态", new JSONObject(state.getString(legacyModKey())).optString("last"));
        assertEquals(1, ledger(legacyLedgerKey()).length());
        int guardianKeys = 0;
        for (Map.Entry<String, Object> e : state.snapshot().entrySet()) {
            if (e.getKey().contains(":guardian")) guardianKeys++;
        }
        assertEquals("旧构造器不得产生角色键（只有 legacy mod+ledger 两个）", 2, guardianKeys);
        assertFalse(state.snapshot().containsKey(roleModKey(UID_A)));
    }
}
