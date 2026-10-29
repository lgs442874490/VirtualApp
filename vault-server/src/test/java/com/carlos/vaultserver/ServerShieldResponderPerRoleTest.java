package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R2 Phase 2B：{@link ServerShieldResponder} 状态键按角色 uid 隔离。
 *
 * <p>覆盖：两个 roleUid 的 responder 各自状态键独立；active 角色镜像旧键、兄弟角色不写旧键；
 * 旧构造器行为不变（只写旧键）；读取优先角色键、缺失回退旧键；p:battle_shield 参数按
 * roleParamsByUid 生效（force_online 按角色）。
 */
public class ServerShieldResponderPerRoleTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;
    private static final long OWN_UID = 1001L;
    private static final long OWNER = 49422287L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

    /** 最小引擎替身：在线态/uid 可切（状态键测试不触发游戏请求）。 */
    private static final class TestEngine extends WjdrEngine {
        boolean online = true;
        final long uid = OWN_UID;

        TestEngine() {
            super(null);
        }

        @Override
        public String sessionState() {
            return online ? WjdrEngine.STATE_ONLINE : WjdrEngine.STATE_TAKEOVER;
        }

        @Override
        public long lastUid() {
            return uid;
        }
    }

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
    }

    // ---------------- 夹具 ----------------

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 进攻自己城（WAR）推送（实证形状：to_marchs[].atk_city.targetid）。 */
    private static Map<String, Object> warPush(long marchId) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", OWN_UID);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", OWNER);
        march.put("type", 2L);
        march.put("target_type", 1L);
        march.put("endtime", nowS() + 600);
        march.put("atk_city", atk);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private ServerShieldResponder responder(long roleUid, boolean active) {
        return new ServerShieldResponder(FPID, SID, roleUid, state, vault, () -> active);
    }

    private ServerShieldResponder legacyResponder() {
        return new ServerShieldResponder(FPID, SID, state, vault);
    }

    private String roleKey(long uid) {
        return "mod:" + FPID + ":" + SID + ":" + uid + ":battle_shield";
    }

    private String legacyKey() {
        return "mod:" + FPID + ":" + SID + ":battle_shield";
    }

    private JSONObject json(String key) {
        String raw = state.getString(key);
        assertNotNull("缺少状态键 " + key, raw);
        return new JSONObject(raw);
    }

    /** 无账号场景触发状态留痕（decide: acc==null → saveState(null, "自动化未开启…")，不发请求）。 */
    private void triggerNoAccountState(ServerShieldResponder r, long marchId) {
        TestEngine engine = new TestEngine();
        r.onPush(warPush(marchId), OWN_UID, engine);
        assertTrue("应处理风险并留痕", r.tick(engine));
    }

    // ---------------- 状态键隔离 ----------------

    @Test
    public void twoRoles_haveIndependentStateKeys_andOnlyActiveMirrorsLegacy() {
        ServerShieldResponder a = responder(UID_A, true);    // 主控：镜像旧键
        ServerShieldResponder b = responder(UID_B, false);   // 兄弟：只写角色键
        triggerNoAccountState(a, 1L);
        triggerNoAccountState(b, 2L);

        assertNotNull("A 角色键", state.getString(roleKey(UID_A)));
        assertNotNull("B 角色键", state.getString(roleKey(UID_B)));
        String legacy = state.getString(legacyKey());
        assertNotNull("active 角色镜像旧键", legacy);

        String roleA = state.getString(roleKey(UID_A));
        assertEquals("旧键内容 = active 角色状态（App 显示链路不变）", roleA, legacy);
        assertFalse("兄弟角色不得写旧键", state.getString(roleKey(UID_B)).equals(legacy));
        assertTrue("两个角色状态文案独立可读",
                json(roleKey(UID_A)).getString("reactive").length() > 0
                        && json(roleKey(UID_B)).getString("reactive").length() > 0);
    }

    @Test
    public void siblingStateDoesNotOverwriteLegacyAfterActiveWrote() {
        ServerShieldResponder a = responder(UID_A, true);
        triggerNoAccountState(a, 11L);
        String legacyAfterActive = state.getString(legacyKey());

        ServerShieldResponder b = responder(UID_B, false);
        triggerNoAccountState(b, 12L);
        assertEquals("兄弟角色状态写入不覆盖旧键", legacyAfterActive, state.getString(legacyKey()));
        assertNotNull("兄弟角色自己的角色键已写入", state.getString(roleKey(UID_B)));
    }

    @Test
    public void legacyConstructor_writesOnlyLegacyKey() {
        ServerShieldResponder legacy = legacyResponder();
        triggerNoAccountState(legacy, 21L);

        assertNotNull("旧构造器写旧键", state.getString(legacyKey()));
        assertNull("旧构造器不写 A 角色键", state.getString(roleKey(UID_A)));
        assertNull("旧构造器不写 B 角色键", state.getString(roleKey(UID_B)));
    }

    @Test
    public void readPrefersRoleKey_fallsBackToLegacyOnce() {
        // 旧键里已有历史子项（gather）：角色键缺失时应回退读取并保留其它子项
        JSONObject old = new JSONObject();
        old.put("reactive", "旧文案");
        old.put("gather", "旧采集告警");
        state.putString(legacyKey(), old.toString());

        ServerShieldResponder b = responder(UID_B, false);
        triggerNoAccountState(b, 31L);

        JSONObject roleB = json(roleKey(UID_B));
        assertTrue("角色键应写入新 reactive", roleB.getString("reactive").length() > 0);
        assertEquals("回退旧键后保留其它子项", "旧采集告警", roleB.getString("gather"));
        assertEquals("旧键不被兄弟角色改写", "旧文案", json(legacyKey()).getString("reactive"));

        // 角色键存在时优先角色键（不再用旧键覆盖已更新的子项）
        triggerNoAccountState(b, 32L);
        assertTrue(json(roleKey(UID_B)).getString("reactive").length() > 0);
    }

    @Test
    public void activeSupplierNull_doesNotMirrorLegacy() {
        // roleUid>0 但未提供 activeSupplier → 视为非 active，只写角色键（安全默认）
        ServerShieldResponder r = new ServerShieldResponder(FPID, SID, UID_A, state, vault, null);
        triggerNoAccountState(r, 41L);
        assertNotNull(state.getString(roleKey(UID_A)));
        assertNull("无 activeSupplier 不镜像旧键", state.getString(legacyKey()));
    }

    // ---------------- p:battle_shield 参数按角色 ----------------

    @Test
    public void paramsPerRole_forceOnlineUsesRoleParamsByUidThenSidFallback() {
        VaultStore.Account acc = new VaultStore.Account(FPID);
        acc.token = "tk";
        acc.auto = true;
        acc.activeSid = String.valueOf(SID);
        Map<String, String> sidParams = new LinkedHashMap<>();
        sidParams.put("battle_shield", "{\"sub\":{\"force_online\":false}}");
        acc.roleParams.put(String.valueOf(SID), sidParams);
        Map<String, String> uidParams = new LinkedHashMap<>();
        uidParams.put("battle_shield", "{\"sub\":{\"force_online\":true}}");
        acc.roleParamsByUid.put(VaultStore.roleUidConfigKey(SID, UID_A), uidParams);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(acc);
        vault.replaceAll(in);

        ServerShieldResponder a = responder(UID_A, true);
        ServerShieldResponder b = responder(UID_B, false);
        a.onPush(warPush(51L), OWN_UID, new TestEngine());
        b.onPush(warPush(52L), OWN_UID, new TestEngine());

        assertTrue("A（uid 配置 force_online=true）应请求强制上线", a.forceOnlineWanted());
        assertFalse("B（回退 sid 配置 force_online=false）不请求", b.forceOnlineWanted());
        assertFalse("旧构造器（uid=0 且无风险）不请求", legacyResponder().forceOnlineWanted());
    }
}
