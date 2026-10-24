package com.carlos.vault.daily;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-24：反应式开盾 + 守护盟友的配置面回归（App 侧纯函数）。
 *
 * <ul>
 *   <li>FnDetail battle_shield 总开关/强制上线/兵力阈值/卡秒 ETA 子项存在且默认值正确
 *       （UI 由 SubCardUi 自动渲染，缺项会导致用户无法配置）；</li>
 *   <li>FnParams.GuardianCfg 解析/写回 force_online/troops_min：顶层/sub/strategy 三处兼容，
 *       且保存不冲掉统一编队字段（与服务器 ServerGuardianEngine.parseConfig 同口径）。</li>
 * </ul>
 */
public class ReactiveConfigAppTest {

    private static FnDetail.Sub subOf(String fnId, String subId) {
        List<FnDetail.Sub> subs = FnDetail.of(fnId);
        for (FnDetail.Sub s : subs) {
            if (s.id.equals(subId)) return s;
        }
        return null;
    }

    @Test
    public void battleShield_hasMasterSwitchAndTriggerStrategySubs() {
        assertNotNull("总开关子项缺失，App 无法关闭整个护盾模块", subOf("battle_shield", "enabled"));
        assertTrue(subOf("battle_shield", "enabled").defOn);
        assertNotNull("既有每日免费盾子项", subOf("battle_shield", "free"));
        assertNotNull("既有反应式子项", subOf("battle_shield", "reactive"));

        FnDetail.Sub force = subOf("battle_shield", "force_online");
        assertNotNull("强制上线子项缺失", force);
        assertFalse("强制上线默认关（会顶掉其他设备）", force.defOn);

        FnDetail.Sub troops = subOf("battle_shield", "troops_min");
        assertNotNull("兵力下限策略缺失", troops);
        assertNotNull(troops.strategies);
        assertTrue("兵力下限必须有 0=不限 档", hasValue(troops, "0"));

        FnDetail.Sub eta = subOf("battle_shield", "eta_sec");
        assertNotNull("卡秒 ETA 策略缺失", eta);
        assertNotNull(eta.strategies);
        assertTrue("ETA 必须有 0=收到即开 档", hasValue(eta, "0"));
        assertTrue("ETA 必须有 30 秒档", hasValue(eta, "30"));
    }

    @Test
    public void battleShield_hasGatherAttackSubs() {
        FnDetail.Sub notify = subOf("battle_shield", "gather_attack");
        assertNotNull("采集点遭袭提醒子项缺失（用户实证该事件与主城被攻击同有提示）", notify);
        assertTrue("采集点遭袭提醒默认开", notify.defOn);

        FnDetail.Sub recall = subOf("battle_shield", "gather_recall");
        assertNotNull("采集点遭袭自动撤矿子项缺失", recall);
        assertFalse("自动撤矿默认关（避免未经确认的撤矿副作用）", recall.defOn);
    }

    private static boolean hasValue(FnDetail.Sub sub, String value) {
        for (String[] s : sub.strategies) {
            if (s.length > 0 && value.equals(s[0])) return true;
        }
        return false;
    }

    @Test
    public void guardianCfg_defaults_newTriggersOffAndUnlimited() throws Exception {
        FnParams.GuardianCfg c = FnParams.guardianCfg(new JSONObject());
        assertFalse("默认关闭", c.enabled);
        assertTrue("默认演练", c.dryRun);
        assertFalse("强制上线默认关", c.forceOnline);
        assertEquals("兵力阈值默认 0=不限", 0L, c.troopsMin);
    }

    @Test
    public void guardianCfg_parsesForceOnlineAndTroopsMin_fromTopSubStrategy() throws Exception {
        JSONObject top = new JSONObject();
        top.put("force_online", true);
        top.put("troops_min", 50000);
        FnParams.GuardianCfg c = FnParams.guardianCfg(top);
        assertTrue(c.forceOnline);
        assertEquals(50000L, c.troopsMin);

        JSONObject sub = new JSONObject();
        JSONObject s = new JSONObject();
        s.put("force_online", true);
        s.put("troops_min", 10000);
        sub.put("sub", s);
        FnParams.GuardianCfg c2 = FnParams.guardianCfg(sub);
        assertTrue(c2.forceOnline);
        assertEquals(10000L, c2.troopsMin);

        JSONObject strat = new JSONObject();
        JSONObject st = new JSONObject();
        st.put("force_online", true);
        st.put("troops_min", 100000);
        strat.put("strategy", st);
        FnParams.GuardianCfg c3 = FnParams.guardianCfg(strat);
        assertTrue(c3.forceOnline);
        assertEquals(100000L, c3.troopsMin);
    }

    @Test
    public void guardianCfg_explicitEnabledIsAuthoritativeOverLegacyIntercept() throws Exception {
        // 与服务器 ServerGuardianEngine.parseConfig 同口径：显式 enabled 是总开关权威值
        JSONObject explicitOff = new JSONObject();
        explicitOff.put("enabled", false);
        JSONObject sub = new JSONObject();
        sub.put("intercept", true);
        explicitOff.put("sub", sub);
        assertFalse("显式 enabled=false 必须压过旧 intercept=true",
                FnParams.guardianCfg(explicitOff).enabled);

        JSONObject explicitOn = new JSONObject();
        explicitOn.put("enabled", true);
        assertTrue(FnParams.guardianCfg(explicitOn).enabled);

        // 旧数据无 enabled 键：intercept 兼容仍生效
        JSONObject legacy = new JSONObject();
        JSONObject legacySub = new JSONObject();
        legacySub.put("intercept", true);
        legacy.put("sub", legacySub);
        assertTrue(FnParams.guardianCfg(legacy).enabled);
    }

    @Test
    public void guardianCfg_applyRoundTrip_preservesFormationFields() throws Exception {
        JSONObject prev = new JSONObject();
        prev.put("formation_mode", "ROLE");
        prev.put("formation_id", 7);
        prev.put("soldiers", "1:100,2:200");
        prev.put("sub", new JSONObject().put("watch", true));

        FnParams.GuardianCfg c = new FnParams.GuardianCfg();
        c.enabled = true;
        c.dryRun = false;
        c.forceOnline = true;
        c.troopsMin = 30000L;
        JSONObject out = FnParams.applyGuardianCfg(prev, c);
        assertEquals("ROLE", out.optString("formation_mode"));
        assertEquals(7, out.optInt("formation_id"));
        assertEquals("1:100,2:200", out.optString("soldiers"));
        assertTrue(out.optBoolean("enabled"));
        assertFalse(out.optBoolean("dry_run"));
        assertTrue(out.optBoolean("force_online"));
        assertEquals(30000L, out.optLong("troops_min"));

        // 二次解析回读一致
        FnParams.GuardianCfg again = FnParams.guardianCfg(out);
        assertTrue(again.enabled);
        assertFalse(again.dryRun);
        assertTrue(again.forceOnline);
        assertEquals(30000L, again.troopsMin);
    }
}
