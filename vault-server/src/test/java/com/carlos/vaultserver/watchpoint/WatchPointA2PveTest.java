package com.carlos.vaultserver.watchpoint;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A2 敌袭 PvE 过滤回归（2026-11-09 用户实测）：{@code type=102} 的「巨兽模板」集结被观察点
 * 保护矩形通道误报为「🚨 敌袭·集结出征」；A2 分支此前缺 PvE 判定（采集点分支才有）。
 *
 * <p>判定分三层：顶层 PvE 动作键 → target 怪物子对象 → mass 的 master_type（501/411/5004）。
 * 102 不列入 PvE 集合，保留「集结出征」能力，避免误杀真实打城集结。
 */
public class WatchPointA2PveTest {

    private static Map<String, Object> march(long type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 1L);
        m.put("owner", 100L);
        m.put("type", type);
        return m;
    }

    @Test
    public void topLevelPveKeyIsFiltered() {
        Map<String, Object> m = march(102);
        m.put("mass_boss", new LinkedHashMap<String, Object>());
        assertEquals("key:mass_boss", WatchPointEngine.pveMarchReason(m));
        assertTrue(WatchPointEngine.isPveMarchDeep(m));
    }

    @Test
    public void monsterTargetObjectIsFiltered() {
        Map<String, Object> m = march(102);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("map_monster", new LinkedHashMap<String, Object>());
        m.put("target", target);
        assertEquals("target:map_monster", WatchPointEngine.pveMarchReason(m));
        assertTrue(WatchPointEngine.isPveMarchDeep(m));
    }

    @Test
    public void pveMasterTypeIsFiltered() {
        Map<String, Object> icefield = march(102);
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("master_type", 501L);
        icefield.put("mass_master", mass);
        assertEquals("mass_master.master_type:501", WatchPointEngine.pveMarchReason(icefield));
        assertTrue(WatchPointEngine.isPveMarchDeep(icefield));

        Map<String, Object> bear = march(102);
        Map<String, Object> bearMass = new LinkedHashMap<>();
        bearMass.put("master_type", 411L);
        bear.put("mass_master", bearMass);
        assertTrue("巨熊集结（411）也是 PvE", WatchPointEngine.isPveMarchDeep(bear));
    }

    @Test
    public void normalCityRallyIsNotPve() {
        Map<String, Object> m = march(102);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("map_city", new LinkedHashMap<String, Object>());
        m.put("target", target);
        assertEquals("", WatchPointEngine.pveMarchReason(m));
        assertFalse("打城集结不能被误杀", WatchPointEngine.isPveMarchDeep(m));
    }

    @Test
    public void masterType102KeepsRallyAlertAbility() {
        Map<String, Object> m = march(102);
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("master_type", 102L);
        m.put("mass_master", mass);
        assertFalse("102 保留集结出征，避免误杀真实打城集结",
                WatchPointEngine.isPveMarchDeep(m));
    }
}
