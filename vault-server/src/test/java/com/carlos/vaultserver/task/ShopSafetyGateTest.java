package com.carlos.vaultserver.task;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商店/总动员/争霸赛「安全第一，宁漏不花」闸门单测（audit task-3 → task-4）。
 *
 * <p>不依赖真协议/真会话，只锁纯判定函数：
 * <ul>
 *   <li>商店：App 名单模式(buy_names)必须安全跳过；刷新只允许 801/601；</li>
 *   <li>总动员：购买接取次数在价目/状态机接入前一律不买（网页版首档 0 钻、其后 50/200/1000 钻）；</li>
 *   <li>争霸赛：无赛季/已报名/拉取超时给出明确 SKIP 文案。</li>
 * </ul>
 */
public class ShopSafetyGateTest {

    // ================= 商店：buy_names 名单模式安全闸 =================

    @Test
    public void nameMode_nullOrEmpty_notConfigured() {
        assertFalse("null 参数不得视为名单模式", ServerShopLeagueTasks.nameModeConfigured(null));
        assertFalse("空参数对象不得视为名单模式",
                ServerShopLeagueTasks.nameModeConfigured(new JSONObject()));
    }

    @Test
    public void nameMode_emptyBuyNames_notConfigured() {
        JSONObject p = new JSONObject();
        p.put("buy_names", new JSONObject());
        assertFalse("空 buy_names 对象等同未配置", ServerShopLeagueTasks.nameModeConfigured(p));
    }

    @Test
    public void nameMode_nonEmptyBuyNames_configured() {
        JSONObject names = new JSONObject();
        names.put("100点统帅经验", 1);
        JSONObject p = new JSONObject();
        p.put("buy_names", names);
        assertTrue("非空 buy_names = App 名单模式，必须安全跳过",
                ServerShopLeagueTasks.nameModeConfigured(p));
    }

    @Test
    public void legacyConfigIdBuys_notTreatedAsNameMode() {
        JSONObject p = new JSONObject();
        p.put("buys", new JSONObject().put("40110121", 1));
        assertFalse("旧 configId 勾选模式不受名单模式闸门影响",
                ServerShopLeagueTasks.nameModeConfigured(p));
    }

    // ================= 商店：刷新收敛到 801/601 =================

    @Test
    public void refreshableShops_onlyVendorAndMysterious() {
        long[] all = ServerShopLeagueTasks.SHOP_IDS_TOWN;
        long[] got = ServerShopLeagueTasks.refreshableShopIds(all);
        assertArrayEquals("12 店铺中只有 801 流动/601 神秘可刷新",
                new long[]{ServerShopLeagueTasks.SHOP_ID_VENDOR,
                        ServerShopLeagueTasks.SHOP_ID_MYSTERIOUS}, got);
    }

    @Test
    public void refreshableShops_allianceShops_neverRefresh() {
        assertArrayEquals("联盟商店(401/501)为每日/每周重置，不刷新",
                new long[0],
                ServerShopLeagueTasks.refreshableShopIds(ServerShopLeagueTasks.SHOP_IDS_ALLIANCE));
    }

    @Test
    public void refreshableShops_otherFixedShops_neverRefresh() {
        assertArrayEquals("固定店/钻石店不发 req_shop_refresh",
                new long[0],
                ServerShopLeagueTasks.refreshableShopIds(new long[]{701, 1001, 901, 1201, 301, 2001, 1101, 2401}));
    }

    @Test
    public void refreshableShops_nullSafe() {
        assertArrayEquals("null 店铺列表返回空，不得 NPE",
                new long[0], ServerShopLeagueTasks.refreshableShopIds(null));
    }

    // ================= 总动员：购买接取次数一律不买 =================

    @Test
    public void mobilizationBuy_neverAllowedWithoutStateMachine() {
        ServerShopLeagueTasks.MobilizationCountBuy d =
                ServerShopLeagueTasks.mobilizationCountBuyDecision(null);
        assertFalse("价目/状态机未接入前不得购买接取次数（防花钻石）", d.allowed());
        assertTrue("SKIP 文案需说明原因", d.reason().contains("跳过"));
    }

    @Test
    public void mobilizationBuy_allowPaidCountFlagStillBlocked() {
        JSONObject params = new JSONObject();
        params.put("allow_paid_count", true);
        ServerShopLeagueTasks.MobilizationCountBuy d =
                ServerShopLeagueTasks.mobilizationCountBuyDecision(params);
        assertFalse("allow_paid_count 预留开关在状态机接入前同样不生效", d.allowed());
        assertTrue("文案需说明 allow_paid_count 暂不生效", d.reason().contains("allow_paid_count"));
    }

    // ================= 争霸赛：最小状态 SKIP 文案 =================

    @Test
    public void showdown_skipWhenNoActivityData() {
        assertNotNull("无响应内容 → 无活动数据 SKIP",
                ServerShopLeagueTasks.showdownSkipReason(null));
        assertNotNull("空内容 → 无活动数据 SKIP",
                ServerShopLeagueTasks.showdownSkipReason(new LinkedHashMap<>()));
    }

    @Test
    public void showdown_skipWhenAlreadySigned() {
        Map<String, Object> apply1 = new LinkedHashMap<>();
        apply1.put("apply", 1L);
        assertNotNull("apply==1 → 已报名 SKIP", ServerShopLeagueTasks.showdownSkipReason(apply1));

        Map<String, Object> isApply = new LinkedHashMap<>();
        isApply.put("is_apply", "true");
        assertNotNull("is_apply=true → 已报名 SKIP", ServerShopLeagueTasks.showdownSkipReason(isApply));
    }

    @Test
    public void showdown_skipWhenSeasonMissing() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("season", 0L);
        String reason = ServerShopLeagueTasks.showdownSkipReason(info);
        assertNotNull("season<=0 → 无赛季 SKIP", reason);
        assertTrue("文案需说明无赛季", reason.contains("无赛季"));
    }

    @Test
    public void showdown_proceedWhenStateUnknown() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("season", 3L);
        info.put("apply", 0L);
        assertNull("赛季有效且未报名 → 保持原行为尝试报名（服务端判定）",
                ServerShopLeagueTasks.showdownSkipReason(info));

        Map<String, Object> noFields = new LinkedHashMap<>();
        noFields.put("activity", 1L);
        assertNull("字段缺失不臆测，交由服务端判定",
                ServerShopLeagueTasks.showdownSkipReason(noFields));
    }

    @Test
    public void showdown_failureNote_timeoutNotDisguisedAsClosed() {
        String timeout = ServerShopLeagueTasks.showdownInfoFailureNote(7705);
        assertTrue("7705 需明确超时重试", timeout.contains("超时"));
        String closed = ServerShopLeagueTasks.showdownInfoFailureNote(9001);
        assertTrue("其它码保持未开启文案", closed.contains("未开启"));
        assertEquals("未开启文案保留错误码", true, closed.contains("9001"));
    }
}
