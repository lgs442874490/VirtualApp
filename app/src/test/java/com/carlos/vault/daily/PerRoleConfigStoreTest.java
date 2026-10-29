package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R2 Phase 2：任务开关/参数按角色 uid 隔离（App 侧）。
 *
 * <p>覆盖：{@code acc:<fpid>:<sid>:<uid>} / {@code p:<fpid>:<sid>:<uid>:<fn>} /
 * {@code mod:<fpid>:<sid>:<uid>:<fn>} 三种存储的「uid 隔离 + 旧键读回退 + 惰性迁移」，
 * 以及切换当前角色（RoleStore.activeRoleUid）后读写落到各自角色。
 *
 * <p>不依赖真机：Context/SharedPreferences 用 Mockito 内存桩。
 */
public class PerRoleConfigStoreTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;

    /** 内存 SharedPreferences 桩（getString/putString/remove/apply 落到 Map）。 */
    private static final class MemPrefs {
        final Map<String, String> data = new LinkedHashMap<>();
        final SharedPreferences sp;

        MemPrefs() {
            sp = mock(SharedPreferences.class);
            SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
            when(sp.edit()).thenReturn(ed);
            when(sp.getString(anyString(), nullable(String.class)))
                    .thenAnswer(inv -> data.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
            when(ed.putString(anyString(), anyString())).thenAnswer(inv -> {
                data.put(inv.getArgument(0), inv.getArgument(1));
                return ed;
            });
            when(ed.remove(anyString())).thenAnswer(inv -> {
                data.remove(inv.getArgument(0));
                return ed;
            });
            when(ed.commit()).thenReturn(true);
        }
    }

    private final Map<String, MemPrefs> prefs = new HashMap<>();
    private Context ctx;

    private MemPrefs prefs(String name) {
        return prefs.computeIfAbsent(name, k -> new MemPrefs());
    }

    @Before
    public void setUp() {
        ctx = mock(Context.class);
        when(ctx.getSharedPreferences(anyString(), anyInt()))
                .thenAnswer(inv -> prefs(inv.getArgument(0)).sp);
    }

    private void activeRole(long uid) {
        prefs("daily_roles").data.put("active2:" + FPID, SID + ":" + uid);
    }

    // ==================== FnParams：参数按 uid ====================

    @Test
    public void fnParams_isolatedPerRole_andMigratesLegacyKey() throws Exception {
        activeRole(UID_A);
        FnParams.saveParams(ctx, FPID, SID, "mail", new JSONObject().put("x", 1));
        assertTrue("参数写入 uid 键",
                prefs("daily_params").data.containsKey("p:" + FPID + ":" + SID + ":" + UID_A + ":mail"));

        activeRole(UID_B);
        assertEquals("兄弟角色读不到 A 的参数",
                0, FnParams.params(ctx, FPID, SID, "mail").length());
        FnParams.saveParams(ctx, FPID, SID, "mail", new JSONObject().put("y", 2));

        activeRole(UID_A);
        assertEquals("A 的参数不受 B 影响",
                1, FnParams.params(ctx, FPID, SID, "mail").getInt("x"));

        // 旧键回退 + 惰性迁移：升级前只有 p:fpid:sid:fn
        String legacyKey = "p:" + FPID + ":" + SID + ":gather";
        prefs("daily_params").data.put(legacyKey, "{\"old\":true}");
        activeRole(UID_B);
        assertTrue("旧键回退可读", FnParams.params(ctx, FPID, SID, "gather").getBoolean("old"));
        assertTrue("读取后惰性迁移到 uid 键",
                prefs("daily_params").data.containsKey("p:" + FPID + ":" + SID + ":" + UID_B + ":gather"));
        assertTrue("旧键保留（回滚/旧版可读）", prefs("daily_params").data.containsKey(legacyKey));
    }

    @Test
    public void modState_isolatedPerRole_withLegacyFallback() throws Exception {
        activeRole(UID_A);
        FnParams.saveModText(ctx, FPID, SID, "alliance_help", "help_all", "A 已响应");
        assertTrue(prefs("daily_state").data.containsKey(
                "mod:" + FPID + ":" + SID + ":" + UID_A + ":alliance_help"));

        activeRole(UID_B);
        assertEquals("B 不继承 A 的模块状态（当无旧键时）",
                "", FnParams.modState(ctx, FPID, SID, "alliance_help").optString("help_all", ""));
        // 服务端 /state 会给 active 角色投影旧键：旧键存在时作为回退
        prefs("daily_state").data.put("mod:" + FPID + ":" + SID + ":alliance_help",
                "{\"help_all\":\"旧键投影\"}");
        assertEquals("旧键回退可读", "旧键投影",
                FnParams.modState(ctx, FPID, SID, "alliance_help").optString("help_all"));
    }

    // ==================== DailyConfigStore：开关按 uid ====================

    @Test
    public void dailyConfig_isolatedPerRole_andMigratesLegacySidKey() {
        DailyConfigStore store = new DailyConfigStore(ctx);
        activeRole(UID_A);
        Map<String, Boolean> a = new LinkedHashMap<>();
        a.put("gather", true);
        a.put("mail", true);
        store.saveRoleConfig(FPID, SID, a);
        assertTrue("开关写入 uid 键", prefs("daily_config").data.containsKey(
                "acc:" + FPID + ":" + SID + ":" + UID_A));
        assertFalse("不得写旧 sid 键（避免兄弟角色继承串写）",
                prefs("daily_config").data.containsKey("acc:" + FPID + ":" + SID));

        activeRole(UID_B);
        assertNull("B 未配置 → 无 meta/legacy 时返回 null（回落目录默认）",
                store.roleConfig(FPID, SID));
        Map<String, Boolean> b = new LinkedHashMap<>();
        b.put("gather", false);
        store.saveRoleConfig(FPID, SID, b);

        activeRole(UID_A);
        assertTrue(store.roleConfig(FPID, SID).get("gather"));
        assertEquals(2, store.roleConfig(FPID, SID).size());
        activeRole(UID_B);
        assertFalse(store.roleConfig(FPID, SID).get("gather"));
        assertEquals(1, store.roleConfig(FPID, SID).size());
    }

    @Test
    public void dailyConfig_legacySidKeyFallbackAndLazyMigration() {
        // 升级前：旧 sid 键（同服双角色共享）与整号键
        prefs("daily_config").data.put("acc:" + FPID + ":" + SID, "{\"gather\":true}");
        DailyConfigStore store = new DailyConfigStore(ctx);
        activeRole(UID_A);
        assertTrue("旧 sid 键回退可读", store.roleConfig(FPID, SID).get("gather"));
        assertTrue("惰性迁移到 uid 键", prefs("daily_config").data.containsKey(
                "acc:" + FPID + ":" + SID + ":" + UID_A));
        assertTrue("旧 sid 键保留（回滚/旧版可读）",
                prefs("daily_config").data.containsKey("acc:" + FPID + ":" + SID));
    }

    @Test
    public void dailyConfig_keyFormat() {
        assertEquals("acc:1:888:35371154", DailyConfigStore.roleConfigKey("1", 888L, 35371154L));
        assertEquals("acc:1:888", DailyConfigStore.roleConfigKey("1", 888L, 0L));
        assertEquals("p:1:888:35371154:mail", FnParams.key("1", 888L, 35371154L, "mail"));
        assertEquals("p:1:888:mail", FnParams.key("1", 888L, 0L, "mail"));
        assertEquals("mod:1:888:35371154:mail", FnParams.modKey("1", 888L, 35371154L, "mail"));
    }
}
