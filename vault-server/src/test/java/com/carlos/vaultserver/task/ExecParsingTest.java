package com.carlos.vaultserver.task;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 真机推送形状解析：英雄 map、兵力 {id:{count}}、最高档士兵。 */
public class ExecParsingTest {

    @Test
    public void heroesFrom_keyedMap() {
        Map<String, Object> hi = new LinkedHashMap<>();
        Map<String, Object> h1 = new LinkedHashMap<>();
        h1.put("id", 50042.0);
        hi.put("50042.0", h1);
        Map<String, Object> h2 = new LinkedHashMap<>();
        h2.put("id", 50040.0);
        hi.put("50040.0", h2);
        assertEquals(List.of(50042L, 50040L), Exec.heroesFrom(hi));
    }

    @Test
    public void heroesFrom_dataArray() {
        Map<String, Object> hi = new LinkedHashMap<>();
        List<Object> data = new ArrayList<>();
        Map<String, Object> h1 = new LinkedHashMap<>();
        h1.put("id", 1.0);
        Map<String, Object> h2 = new LinkedHashMap<>();
        h2.put("id", 2.0);
        data.add(h1);
        data.add(h2);
        hi.put("data", data);
        assertEquals(List.of(2L, 1L), Exec.heroesFrom(hi));
    }

    @Test
    public void soldiersFrom_keyedObjectMap() {
        Map<String, Object> si = new LinkedHashMap<>();
        Map<String, Object> soldier = new LinkedHashMap<>();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", 21000.0);
        v.put("count", 211004.0);
        soldier.put("21000.0", v);
        si.put("soldier", soldier);
        assertEquals(Long.valueOf(211004L), Exec.soldiersFrom(si).get(21000L));
    }

    @Test
    public void soldiersFrom_listShape() {
        Map<String, Object> si = new LinkedHashMap<>();
        List<Object> soldier = new ArrayList<>();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", 11000.0);
        v.put("count", 310771.0);
        soldier.add(v);
        si.put("soldier", soldier);
        assertEquals(Long.valueOf(310771L), Exec.soldiersFrom(si).get(11000L));
    }

    @Test
    public void bestSoldierIds_picksHighestTier() {
        Map<Long, Long> sol = new LinkedHashMap<>();
        sol.put(11000L, 100L);   // 盾 T10
        sol.put(11100L, 50L);    // 盾 T11 优先
        sol.put(21000L, 80L);
        sol.put(31000L, 60L);
        long[] ids = Exec.bestSoldierIds(sol);
        assertEquals(11100L, ids[0]);
        assertEquals(21000L, ids[1]);
        assertEquals(31000L, ids[2]);
    }
}
