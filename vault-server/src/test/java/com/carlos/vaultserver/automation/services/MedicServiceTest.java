package com.carlos.vaultserver.automation.services;

import com.carlos.vaultserver.task.Exec;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 锁定军医所伤兵统计 BUG：init 与 wound push 必须合并取 max，不能只看 init。 */
public class MedicServiceTest {

    @Test
    public void parsesListShapeAndKeepsLargeWoundedCount() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Exec.kv(10101, 800));
        list.add(Exec.kv(20101, 400));
        Map<Long, Long> m = MedicService.parseWounded(list);
        assertEquals(Long.valueOf(800), m.get(10101L));
        assertEquals(Long.valueOf(400), m.get(20101L));
    }

    @Test
    public void parsesNestedSoldiersShape() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Exec.kv(30101, 55));
        nested.put("soldiers", list);
        Map<Long, Long> m = MedicService.parseWounded(nested);
        assertEquals(Long.valueOf(55), m.get(30101L));
    }

    @Test
    public void mergeAcrossInitAndPushTakesMaxPerType() {
        Map<Long, Long> init = new LinkedHashMap<>();
        init.put(10101L, 100L);
        Map<Long, Long> push = new LinkedHashMap<>();
        push.put(10101L, 900L);   // 推送才是实际大量伤兵
        push.put(20101L, 300L);
        MedicService.mergeMax(init, push);
        assertEquals(Long.valueOf(900), init.get(10101L));
        assertEquals(Long.valueOf(300), init.get(20101L));
        assertEquals(1200, init.values().stream().mapToLong(Long::longValue).sum());
    }
}
