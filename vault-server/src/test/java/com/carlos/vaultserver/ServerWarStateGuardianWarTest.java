package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次 J（2026-10-31 用户长期规则第 3 条）：守护战争态（ServerWarState 侧）。
 *
 * <p>锁定契约：
 * <ul>
 *   <li>守护实盘开启后，侦查（type=1）即进入战争态（war_on_scout 默认 true）；</li>
 *   <li>战争态占队任务清单显式暂停（war_pause_queue_tasks，默认 true）；关闭时回退既有 allowlist 总闸；</li>
 *   <li>无任何在途敌方行军持续 war_exit_idle_sec（默认 60s）→ 自动退出战争态；</li>
 *   <li>无侦查不触发；VAULT_GUARDIAN_WAR=off 回旧行为（仅 p:war 判定，无守护战争态动作）。</li>
 * </ul>
 */
public class ServerWarStateGuardianWarTest {

    private static final long SID = 913L;
    private static final long UID = 1001L;
    private static final long ENEMY = 49422287L;

    private final List<String> created = new ArrayList<>();
    private String fpid = "gw";

    @After
    public void clean() {
        for (String f : created) {
            ServerWarState.unregister(f, SID, UID);
        }
        created.clear();
        System.clearProperty("VAULT_GUARDIAN_WAR");
    }

    // ---------------- 夹具 ----------------

    private static String liveGuardian(String extra) {
        String base = "\"sub\":{\"enabled\":true,\"dry_run\":false}";
        return "{" + base + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private VaultStore.Account account(String guardianJson, String warJson) {
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.token = "tk";
        Map<String, String> params = new LinkedHashMap<>();
        if (guardianJson != null) params.put("guardian", guardianJson);
        if (warJson != null) params.put("war", warJson);
        if (!params.isEmpty()) a.roleParams.put(String.valueOf(SID), params);
        return a;
    }

    private ServerWarState mount(VaultStore.Account acc) {
        ServerWarState w = ServerWarState.register(fpid, SID, UID);
        created.add(fpid);
        ServerWarState.configure(acc, SID, UID);
        return w;
    }

    private static Map<String, Object> scoutPush(long marchId, long endSec) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", ENEMY);
        march.put("type", 1L);
        march.put("endtime", endSec);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private static Map<String, Object> delPush(long marchId) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private void scout(ServerWarState w, long marchId, long endSec) {
        w.onPush(ServerGuardianEngineProto.ALLIANCE_ADD, scoutPush(marchId, endSec), UID);
    }

    /** 协议名常量（避免跨包依赖 ServerWarState 的 package-private 常量）。 */
    private static final class ServerGuardianEngineProto {
        static final String ALLIANCE_ADD = "notify_world_alliance_situation_add";
        static final String ALLIANCE_DEL = "notify_world_alliance_situation_del";
    }

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    // ---------------- 1. 侦查触发 ----------------

    @Test
    public void scout_entersGuardianWar_andGatesQueueTasks() {
        ServerWarState w = mount(account(liveGuardian(null), null));

        assertTrue("守护实盘 → 守护战争态配置生效",
                ServerWarState.guardianWarEnabled(fpid, SID, UID));
        assertFalse("未侦查不触发", ServerWarState.guardianWarActive(fpid, SID, UID));

        scout(w, 9001L, nowS() + 120L);

        assertTrue("侦查(type=1)即入战争态", ServerWarState.guardianWarActive(fpid, SID, UID));
        assertTrue("战争态让位自动整轮", ServerWarState.wartimeYield(fpid, SID, UID));
        assertTrue("触发原因标记 H0", ServerWarState.guardianWarTrigger(fpid, SID, UID).contains("H0"));
        // 占队任务清单全部显式暂停（即使加入 war.allowlist 也应停）
        for (String fn : new String[]{"gather", "hunt", "intel", "behemoth", "bear",
                "adventure_sweep", "act_mercenary", "act_truck", "act_feast",
                "act_icehunter", "act_joy"}) {
            String note = ServerWarState.gateNote(fpid, SID, UID, fn);
            assertNotNull("占队任务应暂停：" + fn, note);
            assertTrue("应标注占队/驻防：" + note, note.contains("占队任务"));
        }
        // 非占队任务交给既有 allowlist 总闸（login_rewards 不在默认 allowlist → 仍停）
        assertNotNull(ServerWarState.gateNote(fpid, SID, UID, "login_rewards"));
        assertNull("battle_shield 在 allowlist 内 → 放行",
                ServerWarState.gateNote(fpid, SID, UID, "battle_shield"));
    }

    @Test
    public void noScout_noGuardianWar() {
        ServerWarState w = mount(account(liveGuardian(null), null));
        long now = System.currentTimeMillis();
        w.tick(now);
        w.tick(now + 120_000L);
        assertFalse("无侦查/无进攻 → 不进战争态", ServerWarState.guardianWarActive(fpid, SID, UID));
        assertFalse(ServerWarState.wartimeYield(fpid, SID, UID));
    }

    @Test
    public void warOnScoutFalse_scoutDoesNotEnterWartime() {
        ServerWarState w = mount(account(liveGuardian("\"strategy\":{\"war_on_scout\":false}"), null));
        scout(w, 9002L, nowS() + 120L);
        assertFalse("war_on_scout=false：侦查只降级预警", ServerWarState.guardianWarActive(fpid, SID, UID));
    }

    // ---------------- 2. war_pause_queue_tasks 开关 ----------------

    @Test
    public void pauseQueueTasksFalse_allowsAllowlistedQueueTask() {
        // 显式把 gather 加进既有 war.allowlist：pause=true 时仍要停；pause=false 时按 allowlist 放行
        String warJson = "{\"strategy\":{\"allowlist\":\"battle_shield,medic,gather\"}}";
        String guardianOn = liveGuardian(null);
        String guardianOff = liveGuardian("\"strategy\":{\"war_pause_queue_tasks\":false}");

        fpid = "gw-pause-on";
        ServerWarState w1 = mount(account(guardianOn, warJson));
        scout(w1, 9101L, nowS() + 120L);
        assertNotNull("pause=true 时 allowlist 也拦占队任务："
                + ServerWarState.gateNote(fpid, SID, UID, "gather"),
                ServerWarState.gateNote(fpid, SID, UID, "gather"));
        assertNull("允许清单内非占队任务放行",
                ServerWarState.gateNote(fpid, SID, UID, "battle_shield"));

        fpid = "gw-pause-off";
        ServerWarState w2 = mount(account(guardianOff, warJson));
        scout(w2, 9102L, nowS() + 120L);
        assertNull("pause=false 时 allowlist 内占队任务放行",
                ServerWarState.gateNote(fpid, SID, UID, "gather"));
        assertNotNull("allowlist 外占队任务仍停（既有总闸）",
                ServerWarState.gateNote(fpid, SID, UID, "hunt"));
    }

    // ---------------- 3. 无在途威胁自动退出 ----------------

    @Test
    public void noInFlightThreat_60s_exitsWartime() {
        ServerWarState w = mount(account(liveGuardian(null), null));
        long now = System.currentTimeMillis();
        scout(w, 9201L, now / 1000L + 120L);
        assertTrue(ServerWarState.guardianWarActive(fpid, SID, UID));

        w.tick(now);
        assertTrue("仍有在途侦查：不退出", ServerWarState.guardianWarActive(fpid, SID, UID));

        // 侦查消失（敌召回/落地）→ 无在途敌方行军
        w.onPush(ServerGuardianEngineProto.ALLIANCE_DEL, delPush(9201L), UID);
        w.tick(now);
        assertTrue("刚开始无威胁：未满 60s 不退出",
                ServerWarState.guardianWarActive(fpid, SID, UID));
        w.tick(now + 61_000L);
        assertFalse("无在途威胁 ≥60s → 退出战争态",
                ServerWarState.guardianWarActive(fpid, SID, UID));
        assertFalse(ServerWarState.wartimeYield(fpid, SID, UID));
    }

    @Test
    public void warExitIdleSec_customValue() {
        ServerWarState w = mount(account(
                liveGuardian("\"strategy\":{\"war_exit_idle_sec\":10}"), null));
        long now = System.currentTimeMillis();
        scout(w, 9301L, now / 1000L + 120L);
        w.onPush(ServerGuardianEngineProto.ALLIANCE_DEL, delPush(9301L), UID);
        w.tick(now);
        w.tick(now + 9_000L);
        assertTrue("未满 10s 不退出", ServerWarState.guardianWarActive(fpid, SID, UID));
        w.tick(now + 11_000L);
        assertFalse("满 10s 退出", ServerWarState.guardianWarActive(fpid, SID, UID));
    }

    // ---------------- 4. env 回滚 ----------------

    @Test
    public void envRollback_off_keepsLegacyWarOnly() {
        System.setProperty("VAULT_GUARDIAN_WAR", "off");
        ServerWarState w = mount(account(liveGuardian(null), null));

        assertFalse("VAULT_GUARDIAN_WAR=off → 守护战争态关闭",
                ServerWarState.guardianWarEnabled(fpid, SID, UID));
        scout(w, 9401L, nowS() + 120L);
        // 旧行为：p:war 默认 auto/scout_hard=true → 仍会进入 WARTIME（批次 J 之前的口径）
        assertTrue("env 回滚后仍按旧 war 判定入战时",
                ServerWarState.wartimeYield(fpid, SID, UID));
        assertFalse("但不启用守护战争态动作/显式占队清单",
                ServerWarState.guardianWarActive(fpid, SID, UID));
        assertFalse("占队清单不再显式标注",
                String.valueOf(ServerWarState.gateNote(fpid, SID, UID, "gather"))
                        .contains("占队任务"));
    }

    @Test
    public void nonGuardianAccount_guardianWarNeverEnabled() {
        // guardian 未开（无参数）：即使 p:war 默认入战时，也不启用守护战争态
        ServerWarState w = mount(account(null, null));
        assertFalse(ServerWarState.guardianWarEnabled(fpid, SID, UID));
        scout(w, 9501L, nowS() + 120L);
        assertFalse(ServerWarState.guardianWarActive(fpid, SID, UID));
    }
}
