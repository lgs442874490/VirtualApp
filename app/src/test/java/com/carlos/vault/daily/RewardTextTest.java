package com.carlos.vault.daily;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 领取明细解析与展示口径：形状防御（kv 对/id+数量/数量映射/嵌套）、物品命名、
 * 「领取内容」行整理（明细优先、时间不参与）。对应功能页领取内容改造。
 */
public class RewardTextTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ---------- describe：各协议奖励形状 ----------

    @Test
    public void describe_kvPairList() {
        // 游戏通用形状：[{k:id, v:count}...]
        List<Object> l = Arrays.asList(
                (Object) map("k", 100003L, "v", 30L),
                map("k", 102.0, "v", 1000.0));
        assertEquals("钻石×30、生肉×1000", RewardText.describe(l));
    }

    @Test
    public void describe_idCountList() {
        List<Object> l = Arrays.asList(
                (Object) map("id", 200103L, "count", 2L),
                map("id", 500220L, "num", 1.0));
        assertEquals("1小时加速×2、传说英雄信物", RewardText.describe(l));
    }

    @Test
    public void describe_numericKeyMap() {
        // 数量映射：键可能是字符串或 Long/Double 对象（协议解码的 integerLongMap 形状）
        Map<String, Object> strKeys = new LinkedHashMap<>();
        strKeys.put("100003", 30.0);
        assertEquals("钻石×30", RewardText.describe(strKeys));

        Map<Object, Object> objKeys = new LinkedHashMap<>();
        objKeys.put(100003L, 30);
        objKeys.put(100004L, 1L);
        assertEquals("钻石×30、1000肉", RewardText.describe(objKeys));
    }

    @Test
    public void describe_nestedContainerAndMerge() {
        Map<String, Object> v = map(
                "resource", map("100003", 10.0),
                "items", Arrays.asList((Object) map("k", 100003L, "v", 20L)));
        assertEquals("钻石×30", RewardText.describe(v));
    }

    @Test
    public void describe_defensive() {
        assertEquals("", RewardText.describe(null));
        assertEquals("", RewardText.describe("纯文本"));
        assertEquals("", RewardText.describe(Arrays.asList(1, 2, 3)));   // 裸数字形状不明，不猜
        // 识别不了的 map 形状：不抛异常即可
        assertNotNull(RewardText.describe(map("foo", "bar")));
    }

    @Test
    public void fromContent_scansRewardKeys() {
        Map<String, Object> content = map(
                "code", 0,
                "rewards", Arrays.asList((Object) map("k", 100003L, "v", 5L)));
        assertEquals("钻石×5", RewardText.fromContent(content));
        assertEquals("", RewardText.fromContent(null));
        assertEquals("", RewardText.fromContent(map("code", 0)));
    }

    // ---------- 物品命名 ----------

    @Test
    public void name_knownAndUnknown() {
        assertEquals("钻石", RewardText.name(100003));
        assertEquals("传说装备自选箱", RewardText.name(100081));
        assertEquals("生肉", RewardText.name(102));
        assertEquals("物品999999", RewardText.name(999999));
    }

    // ---------- 「领取内容」行整理 ----------

    @Test
    public void claimLine_detailFirst_multiSegment() {
        String line = RewardText.claimLine("✅ 仓库补给：普通补给已领取钻石×30; 体力补给已领取体力×100",
                "普通补给：钻石×30；体力补给：体力×100");
        assertEquals("普通补给：钻石×30\n体力补给：体力×100", line);
    }

    @Test
    public void claimLine_fallbackCleansReason() {
        assertEquals("领取邮件 12 封（候选 12）",
                RewardText.claimLine("✅ 邮件奖励：领取邮件 12 封（候选 12）", ""));
        // 冒号前过长视为正文自带冒号，不裁
        assertEquals("第3天：钻石×1",
                RewardText.claimLine("✅ x：第3天：钻石×1", ""));
        assertEquals("", RewardText.claimLine(null, ""));
    }

    // ---------- 登录好礼目录（详情页网格与明细共用） ----------

    @Test
    public void loginCatalogs_shape() {
        for (int round : new int[]{5004001, 5004002, 5004003}) {
            assertEquals(7, RewardText.loginRewardsFree(round).length);
            assertEquals(7, RewardText.loginRewardsEpic(round).length);
            for (String[] day : RewardText.loginRewardsFree(round)) assertFalse(day.length == 0);
            for (String[] day : RewardText.loginRewardsEpic(round)) assertFalse(day.length == 0);
        }
        assertEquals("钻石×1", RewardText.join(RewardText.loginRewardsFree(5004001)[0]));
        // 第三轮史诗轨 D2 传说装备箱×7
        assertTrue(RewardText.join(RewardText.loginRewardsEpic(5004003)[1]).contains("传说装备自选箱×7"));
    }
}
