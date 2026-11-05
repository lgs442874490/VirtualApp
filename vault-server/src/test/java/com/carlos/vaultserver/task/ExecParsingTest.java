package com.carlos.vaultserver.task;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 真机推送形状解析：英雄 map、兵力 {id:{count}}、最高档士兵。 */
public class ExecParsingTest {

    @Test
    public void clampHeroes_marchPayloadAtMostThree() {
        // 生产 11-02/11-04 根因：客户端布阵（hero_arranges）带 5 名英雄，411 原样下发被 403/415 拒绝
        assertEquals(List.of(1L, 2L, 3L),
                Exec.clampHeroes(List.of(1L, 2L, 3L, 4L, 5L), Exec.MAX_MARCH_HEROES));
        assertEquals("≤3 原样返回", List.of(1L, 2L, 3L),
                Exec.clampHeroes(List.of(1L, 2L, 3L), Exec.MAX_MARCH_HEROES));
        assertEquals(List.of(1L), Exec.clampHeroes(List.of(1L), Exec.MAX_MARCH_HEROES));
        assertNull("null 保持 null（调用方走 allHeroes 兜底）", Exec.clampHeroes(null, 3));
        assertEquals(List.of(), Exec.clampHeroes(List.of(), 3));
    }

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

    // ---------- P1-A 二轮：Exec.Multi 聚合路径的会话中断升级（ZCode 复验 §6：8/9 失败不经 classifyException） ----------

    @Test
    public void multi_sessionLost_upgradesToSessionClosed() {
        // 形态 B：任务级 catch 走 m.fail(brief(message))，文案即断线原文
        Exec.Multi m = new Exec.Multi();
        m.skip("每日签到·冷却中");
        m.fail("连接游戏服务器失败");
        assertEquals("聚合中出现会话中断文案必须升级 SESSION_CLOSED（优先于 ERROR）",
                TaskOutcome.Category.SESSION_CLOSED, m.cat());
        assertTrue(m.sessionLost());
        assertEquals("note() 展示口径不变", "领取0/跳过1/失败1", m.note());
    }

    @Test
    public void multi_businessFailure_staysError() {
        // 形态 d（聚合侧）：业务失败文案仍判 ERROR，不得被误升级
        Exec.Multi m = new Exec.Multi();
        m.skip("每日签到·冷却中");
        m.fail("结算码6158");
        assertEquals(TaskOutcome.Category.ERROR, m.cat());
        assertFalse(m.sessionLost());
    }

    @Test
    public void multi_sessionLostBeatsDegraded() {
        // 会话中断优先于 DEGRADED：断线轮必须可重试，不能烧掉当天整轮
        Exec.Multi m = new Exec.Multi();
        m.degraded("协议异常重试");
        m.fail("Connection refused");
        assertEquals(TaskOutcome.Category.SESSION_CLOSED, m.cat());
    }

    @Test
    public void multi_resultCarriesSessionClosedCategory() {
        // 收口路径：Exec.result 把 Multi 的分类带进 TaskOutcome（RoundRunner 据此截断整轮）
        Exec.Multi m = new Exec.Multi();
        m.subFail("train_1", "训练码6158");
        m.fail("连接游戏服务器失败");
        TaskOutcome r = Exec.result(m, "t1", "城镇任务");
        assertEquals(TaskOutcome.Category.SESSION_CLOSED, r.category);
        assertTrue(r.message.contains("失败1"));
        assertEquals("fail:训练码6158", r.subResults.get("train_1"));
    }
}
