package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领取明细链路（服务器侧）：RewardText 单一源（App com.carlos.vault.daily 同类直编）可用、
 * Exec.Multi 明细累计经 Exec.result 落到 TaskOutcome.rewards。
 */
public class RewardTextServerTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage g =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        g.getContent().putAll(content);
        return g;
    }

    @Test
    public void rewardText_singleSourceUsable() {
        // 游戏通用 [{k:id,v:count}] 形状 → 中文明细（与 App 端同表同命名）
        List<Object> l = Arrays.asList(
                (Object) map("k", 100003L, "v", 30L),
                map("k", 102.0, "v", 1000.0));
        assertEquals("钻石×30、生肉×1000",
                com.carlos.vault.daily.RewardText.describe(l));
        assertEquals("钻石", com.carlos.vault.daily.RewardText.name(100003));
        assertEquals("钻石×5", com.carlos.vault.daily.RewardText.fromContent(
                map("code", 0, "rewards", Arrays.asList((Object) map("k", 100003L, "v", 5L)))));
    }

    @Test
    public void multiRewardsAccumulateViaResult() {
        Exec.Multi m = new Exec.Multi();
        // 无奖励字段的响应不计入
        m.rewards("普通补给", null);
        m.rewards("普通补给", msg(map("code", 6158)));
        assertEquals("", m.rewardsText());

        m.rewards("普通补给", msg(map("code", 0,
                "online_reward", Arrays.asList((Object) map("k", 100003L, "v", 30L)))));
        m.rewards("体力补给", msg(map("code", 0,
                "spec_reward", Arrays.asList((Object) map("k", 200103L, "v", 1L)))));
        assertEquals("普通补给：钻石×30；体力补给：1小时加速", m.rewardsText());

        TaskOutcome r = Exec.result(m, "supply", "仓库补给");
        assertEquals("普通补给：钻石×30；体力补给：1小时加速", r.rewards);
        assertTrue(r.subResults.isEmpty());
    }
}
