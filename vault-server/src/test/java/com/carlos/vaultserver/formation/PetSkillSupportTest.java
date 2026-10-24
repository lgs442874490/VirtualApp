package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 宠物技能状态（重物搬运解锁判定）单元测试。 */
public class PetSkillSupportTest {

    @Test
    public void heavyLifting_lockedWhenBreakLevelLow() {
        WjdrEngine e = engine(petInfo(55003L, 1L, 1L, true));
        assertTrue("拥有但突破1级 → 未解锁", !PetSkillSupport.heavyLiftingUnlocked(e));
        assertTrue(PetSkillSupport.heavyLiftingLockReason(e).contains("突破1级"));
    }

    @Test
    public void heavyLifting_unlockedAtBreak2() {
        WjdrEngine e = engine(petInfo(55003L, 2L, 2L, true));
        assertTrue(PetSkillSupport.heavyLiftingUnlocked(e));
        assertEquals("", PetSkillSupport.heavyLiftingLockReason(e));
    }

    @Test
    public void heavyLifting_notOwnedOrMissing() {
        WjdrEngine owned = engine(petInfo(55003L, 1L, 0L, false));
        assertFalse(PetSkillSupport.heavyLiftingUnlocked(owned));
        WjdrEngine missing = engine(null);
        assertNull(PetSkillSupport.pet(missing, 55003L));
        assertTrue(PetSkillSupport.heavyLiftingLockReason(missing).contains("未同步"));
    }

    @Test
    public void petUpdateOverridesInit() {
        Map<String, Object> init = petInfo(55003L, 1L, 1L, true);
        Map<String, Object> update = mapOf("info", new ArrayList<Object>(Arrays.asList(
                mapOf("id", 55003L, "lv", 10L, "break_lv", 2L, "is_unlock", "true"))));
        Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        notifies.put("notify_pet_info", init);
        notifies.put("notify_pet_update", update);
        WjdrEngine e = new WjdrEngine(new AssetSource() {
            @Override
            public byte[] read(String name) {
                return new byte[0];
            }
        }) {
            @Override
            public Map<String, Object> notifyData(String proto) {
                return notifies.get(proto);
            }
        };
        assertTrue(PetSkillSupport.heavyLiftingUnlocked(e));
    }

    // ---------- fixtures ----------

    private static Map<String, Object> petInfo(long id, long lv, long breakLv, boolean unlock) {
        List<Object> list = new ArrayList<>();
        list.add(mapOf("id", id, "lv", lv, "break_lv", breakLv, "is_unlock", String.valueOf(unlock)));
        return mapOf("info", list);
    }

    private static WjdrEngine engine(Map<String, Object> petInfo) {
        return new WjdrEngine(new AssetSource() {
            @Override
            public byte[] read(String name) {
                return new byte[0];
            }
        }) {
            @Override
            public Map<String, Object> notifyData(String proto) {
                return "notify_pet_info".equals(proto) ? petInfo : null;
            }
        };
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
