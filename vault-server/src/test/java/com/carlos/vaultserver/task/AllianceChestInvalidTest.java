package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联盟宝箱 1540（已失效/不可领）本地抑制：解析/序列化纯函数回归。
 *
 * <p>背景（2026-10-29 生产实测）：两个角色每轮各对 25 个陈旧战利品宝箱发
 * {@code req_alliance_receive_chest}，服务端全部返回 1540；旧实现记 ERROR 且下轮继续重试，
 * 每分钟几十次无效请求。修复：1540 → 记角色级失效集合（TTL 6h），到期前不再请求。
 */
public class AllianceChestInvalidTest {

    @Test
    public void parseAndFormatRoundTrip() {
        Map<Long, Long> m = new LinkedHashMap<>();
        m.put(15195L, 1793211066L);
        m.put(15196L, 1793211067L);
        String raw = ServerTasks.formatInvalidChests(m);
        assertEquals("15195:1793211066,15196:1793211067", raw);
        Map<Long, Long> out = ServerTasks.parseInvalidChests(raw);
        assertEquals(2, out.size());
        assertEquals(Long.valueOf(1793211066L), out.get(15195L));
        assertEquals(Long.valueOf(1793211067L), out.get(15196L));
    }

    @Test
    public void malformedEntriesAreIgnored() {
        Map<Long, Long> out = ServerTasks.parseInvalidChests("15195:1,bad,0:5,-3:9,:,15196");
        // 15195 有效；15196 无时间戳按 ts=0（调用方会当作已过期清理）；0/-3/bad/空段忽略
        assertEquals(2, out.size());
        assertEquals(Long.valueOf(1L), out.get(15195L));
        assertEquals(Long.valueOf(0L), out.get(15196L));
        assertEquals("", ServerTasks.formatInvalidChests(new LinkedHashMap<>()));
        assertTrue(ServerTasks.parseInvalidChests(null).isEmpty());
        assertTrue(ServerTasks.parseInvalidChests("").isEmpty());
    }
}
