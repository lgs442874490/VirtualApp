package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R2 Phase 2：/state 旧键投影单测（vault-server/src/main/java/com/carlos/vaultserver/VaultHttpApi.java）。
 *
 * <p>背景：Phase 2 起任务状态键按角色 uid 隔离（{@code family:fpid:sid:uid:rest}），
 * 但 App 契约保持不变——服务端在 /state 里把 active 角色（App 当前选中角色）的角色键投影成旧键
 * （{@code family:fpid:sid:rest}）下发，非 active 角色的任务状态不下发。
 */
public class VaultHttpApiRoleProjectionTest {

    private static Map<String, Long> uids(String fpid, long uid) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(fpid, uid);
        return m;
    }

    @Test
    public void activeRoleKeyProjectsToLegacyShape() {
        Map<String, Long> active = uids("48460403", 88280792L);
        assertEquals("mod:48460403:888:alliance_help",
                VaultHttpApi.projectRoleStateKey("mod:48460403:888:88280792:alliance_help", active));
        assertEquals("log:48460403:888:alliance_donate",
                VaultHttpApi.projectRoleStateKey("log:48460403:888:88280792:alliance_donate", active));
        assertEquals("res:48460403:888",
                VaultHttpApi.projectRoleStateKey("res:48460403:888:88280792", active));
        assertEquals("questprog:48460403:888",
                VaultHttpApi.projectRoleStateKey("questprog:48460403:888:88280792", active));
        // st:<day>:<fpid>:<sid>:<uid>:<fn>
        assertEquals("st:2026-10-29:48460403:888:alliance_help",
                VaultHttpApi.projectRoleStateKey("st:2026-10-29:48460403:888:88280792:alliance_help", active));
        // fn 内含冒号（pet:alliance_chest）保持完整
        assertEquals("log:48460403:888:pet:alliance_chest",
                VaultHttpApi.projectRoleStateKey("log:48460403:888:88280792:pet:alliance_chest", active));
    }

    @Test
    public void nonActiveRoleKeyIsDropped() {
        Map<String, Long> active = uids("48460403", 88280792L);
        assertEquals("", VaultHttpApi.projectRoleStateKey("mod:48460403:888:35371154:alliance_help", active));
        assertEquals("", VaultHttpApi.projectRoleStateKey("log:48460403:888:35371154:alliance_donate", active));
    }

    @Test
    public void legacyAndOtherKeysPassThrough() {
        Map<String, Long> active = uids("48460403", 88280792L);
        // 旧键（无 uid 段）原样下发
        assertNull(VaultHttpApi.projectRoleStateKey("mod:48460403:888:alliance_help", active));
        assertNull(VaultHttpApi.projectRoleStateKey("res:48460403:888", active));
        // srv:* 新契约键（asset/formation 已按 uid）不投影，原样下发
        assertNull(VaultHttpApi.projectRoleStateKey("srv:asset:48460403:888:88280792", active));
        assertNull(VaultHttpApi.projectRoleStateKey("srv:cad:48460403:888:88280792:alliance_help", active));
        // 无 active 信息：不投影（安全：保持旧行为）
        assertNull(VaultHttpApi.projectRoleStateKey("mod:48460403:888:88280792:alliance_help",
                new LinkedHashMap<>()));
    }
}
