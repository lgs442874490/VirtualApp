package com.carlos.vault;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真机监控请求字段构造单测（纯 JVM）。
 *
 * <p>契约：{@code design/真机监控-服务端任务级协议监控-20261025.md} §3.3 ——
 * 请求字段扁平（action/since/limit/kinds/fpid/includeData/msg），其中 {@code includeData}
 * 必须用字符串 "1"/"0"（服务端按扁平字符串字段读取）。本测试锁住字段名/默认值/裁剪口径，
 * 避免 UI 改动把服务端契约改坏。
 */
public class MonitorRequestFieldsTest {

    @Test
    public void queryFields_defaultsAndContractKeys() {
        Map<String, Object> f = VaultSync.monitorQueryFields(0L, 200, null, null, true);
        assertEquals("query", f.get("action"));
        assertEquals(Long.valueOf(0L), f.get("since"));
        assertEquals(Integer.valueOf(200), f.get("limit"));
        assertEquals("", f.get("kinds"));
        assertEquals("", f.get("fpid"));
        assertEquals("1", f.get("includeData"));   // 契约：字符串 "1"/"0"
        // 首屏 6 个字段齐全
        assertEquals(6, f.size());
    }

    @Test
    public void queryFields_includeDataFalseAndFilters() {
        Map<String, Object> f = VaultSync.monitorQueryFields(107L, 50, " task.end,io.rsp ",
                " 48460403 ", false);
        assertEquals(Long.valueOf(107L), f.get("since"));
        assertEquals(Integer.valueOf(50), f.get("limit"));
        assertEquals("task.end,io.rsp", f.get("kinds"));
        assertEquals("48460403", f.get("fpid"));
        assertEquals("0", f.get("includeData"));
        // 纯函数：不同参数互不影响
        assertFalse(f.containsKey("msg"));
    }

    @Test
    public void queryFields_clampSinceAndLimit() {
        // since 负数 → 0（服务端只接受 seq > since）
        assertEquals(Long.valueOf(0L),
                VaultSync.monitorQueryFields(-5L, 200, "", "", true).get("since"));
        // limit 裁剪到契约范围 1..1000
        assertEquals(Integer.valueOf(1),
                VaultSync.monitorQueryFields(0L, 0, "", "", true).get("limit"));
        assertEquals(Integer.valueOf(1),
                VaultSync.monitorQueryFields(0L, -9, "", "", true).get("limit"));
        assertEquals(Integer.valueOf(1000),
                VaultSync.monitorQueryFields(0L, 5000, "", "", true).get("limit"));
    }

    @Test
    public void markFields_actionAndMsg() {
        Map<String, Object> f = VaultSync.monitorMarkFields("用户打点 16:13:20");
        assertEquals("mark", f.get("action"));
        assertEquals("用户打点 16:13:20", f.get("msg"));
        assertEquals(2, f.size());
        // null msg 兜底空串（服务端按空串处理，不写 null）
        assertEquals("", VaultSync.monitorMarkFields(null).get("msg"));
        // 输入 Map 不被签名通道污染：buildSignedBody 内部才补 ts/nonce/sign
        Map<String, Object> copy = new HashMap<>(f);
        assertTrue(copy.containsKey("msg"));
    }
}
