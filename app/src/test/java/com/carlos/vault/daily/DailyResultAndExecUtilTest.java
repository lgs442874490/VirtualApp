package com.carlos.vault.daily;

import com.carlos.vault.wjdr.protocol.GameMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * TC-S17 / 通用判定：DailyResult 五级分类 + ExecUtil 纯函数解析口径
 * （移植自网页版 BehemothAccountState 的解析口径）。
 */
public class DailyResultAndExecUtilTest {

    // ---------- DailyResult ----------

    @Test
    public void categorySemantics() {
        DailyResult r = new DailyResult("mail", "邮件奖励", DailyResult.Category.CLAIMED, "ok");
        assertEquals("✅", r.symbol());
        assertFalse(r.retryable());

        assertEquals("⏭", new DailyResult("x", "x", DailyResult.Category.SKIPPED, "").symbol());
        assertEquals("⚠️", new DailyResult("x", "x", DailyResult.Category.DEGRADED, "").symbol());
        assertEquals("🔌", new DailyResult("x", "x", DailyResult.Category.SESSION_CLOSED, "").symbol());
        assertEquals("❌", new DailyResult("x", "x", DailyResult.Category.ERROR, "").symbol());
        // 仅会话中断可重试（对标原版 failureCategory=SESSION_CLOSED）
        assertTrue(new DailyResult("x", "x", DailyResult.Category.SESSION_CLOSED, "").retryable());
        assertFalse(new DailyResult("x", "x", DailyResult.Category.ERROR, "").retryable());
    }

    // ---------- ExecUtil 纯函数 ----------

    @Test
    public void longOf_intOf_defensive() {
        assertEquals(0L, ExecUtil.longOf(null));
        assertEquals(42L, ExecUtil.longOf("42"));
        assertEquals(3L, ExecUtil.longOf(3.9));
        assertEquals(0, ExecUtil.intOf(null));
        assertEquals(7, ExecUtil.intOf(7.2));
    }

    private static GameMessage msg(Map<String, Object> content) {
        GameMessage m = new GameMessage();
        m.setContent(content);
        return m;
    }

    @Test
    public void codeOf_nullSafety() {
        assertEquals(-1, ExecUtil.codeOf(null));
        GameMessage noCode = msg(new HashMap<>());
        assertEquals(0, ExecUtil.codeOf(noCode));
        GameMessage code5 = msg(new HashMap<String, Object>() {{ put("code", 5); }});
        assertEquals(5, ExecUtil.codeOf(code5));
    }

    @Test
    public void ok_resultAssertion() {
        assertFalse(ExecUtil.ok(null));
        // GameMessage.content 默认是空 map（非 null）——ok() 对「code 缺失」按 0 处理，
        // 因此空响应 ok()=true；这正是任务层要求证据字段 + DEGRADED 兜底的原因（差异点 D2）。
        GameMessage noContent = new GameMessage();
        noContent.setContent(null);
        assertFalse(ExecUtil.ok(noContent));
        GameMessage badCode = msg(new HashMap<String, Object>() {{ put("code", 6151); }});
        assertFalse(ExecUtil.ok(badCode));
        // 未知协议兜底内容必须判 not-ok（原版会假成功，本作明确 DEGRADED——差异点 D2）
        GameMessage unknown = msg(new HashMap<String, Object>() {{
            put("code", 0); put("_unknown_proto_tag", 123);
        }});
        assertFalse(ExecUtil.ok(unknown));
        GameMessage good = msg(new HashMap<String, Object>() {{
            put("code", 0); put("rewards", Arrays.asList("x"));
        }});
        assertTrue(ExecUtil.ok(good));
    }

    @Test
    public void kvPairs_gameShapes() {
        // 游戏通用背包/兵力形状：[{k,v}] 列表
        List<Map<String, Object>> l = Arrays.asList(
                kv(1L, 2L), kv(3L, 4L));
        Map<Long, Long> m = ExecUtil.kvPairs(l);
        assertEquals((Long) 2L, m.get(1L));
        assertEquals((Long) 4L, m.get(3L));
        assertEquals(2, m.size());
        // 空容错
        assertNotNull(ExecUtil.kvPairs(null));
        assertTrue(ExecUtil.kvPairs(null).isEmpty());
    }

    private static Map<String, Object> kv(long k, long v) {
        Map<String, Object> m = new HashMap<>();
        m.put("k", k);
        m.put("v", v);
        return m;
    }

    @Test
    public void args_skipsNullValues() {
        Map<String, Object> a = ExecUtil.args("x", 1L, "y", null, "z", 3L);
        assertEquals(2, a.size());
        assertTrue(a.containsKey("x") && a.containsKey("z"));
        assertFalse(a.containsKey("y"));
        assertEquals((Long) 1L, a.get("x"));
    }

    @Test
    public void point_shape() {
        Map<String, Object> p = ExecUtil.point(11, 22);
        assertEquals((Long) 11L, p.get("x"));
        assertEquals((Long) 22L, p.get("y"));
    }

    @Test
    public void nest_deepDive() {
        Map<String, Object> base = new HashMap<>();
        Map<String, Object> info = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("id", 42L);
        info.put("base", inner);
        base.put("info", info);
        Map<String, Object> got = ExecUtil.nest(base, "info", "base");
        assertEquals((Long) 42L, got.get("id"));
        // 断链返回 null（调用方按可空处理）
        assertNull(ExecUtil.nest(base, "a", "b", "c"));
    }

    @Test
    public void marchId_fromMarchInfo() {
        GameMessage withMarch = msg(new HashMap<String, Object>() {{
            put("march_info", new HashMap<String, Object>() {{ put("id", 777L); }});
        }});
        assertEquals(777L, ExecUtil.marchId(withMarch));
        assertEquals(0L, ExecUtil.marchId(msg(new HashMap<>())));
    }

    // ---------- Multi 聚合器 ----------

    @Test
    public void multiAggregator_priority() {
        ExecUtil.Multi m = new ExecUtil.Multi();
        m.claimed("领A");
        m.skip("跳B");
        assertEquals(DailyResult.Category.CLAIMED, m.cat());
        assertEquals("领取1/跳过1", m.note());

        ExecUtil.Multi m2 = new ExecUtil.Multi();
        m2.skip("跳B");
        assertEquals(DailyResult.Category.SKIPPED, m2.cat());
        assertEquals("领取0/跳过1", m2.note());

        // DEGRADED > ERROR > CLAIMED > SKIPPED
        ExecUtil.Multi m3 = new ExecUtil.Multi();
        m3.claimed("领A"); m3.fail("挂B");
        assertEquals(DailyResult.Category.ERROR, m3.cat());
        m3.degraded("异常C");
        assertEquals(DailyResult.Category.DEGRADED, m3.cat());
        assertTrue(m3.note().contains("协议异常1"));
        assertTrue(m3.note().contains("失败1"));

        // 子项结果前缀约定（LogStore.applySubResults 依赖）
        ExecUtil.Multi m4 = new ExecUtil.Multi();
        m4.subOk("s1", "成功");
        m4.subSkip("s2", "等待");
        m4.subFail("s3", "失败");
        assertEquals("ok:成功", m4.subResults.get("s1"));
        assertEquals("skip:等待", m4.subResults.get("s2"));
        assertEquals("fail:失败", m4.subResults.get("s3"));
    }
}
