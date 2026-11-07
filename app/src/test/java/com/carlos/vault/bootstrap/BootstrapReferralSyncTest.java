package com.carlos.vault.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.AccountStore;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 账号回流合并单测（M5，任务书 §3.4）：
 * <ul>
 *   <li>{@link BootstrapReferralSync#mergeReferrals}：fpid 去重（已存在跳过并仍标记 pulled）、
 *       新增账号的 label/group/token 字段、空清单安全；</li>
 *   <li>{@link BootstrapReferralSync#parseReferralList}：referral-list data 解析（缺 fpid/sdkToken 跳过）；</li>
 *   <li>{@link BootstrapReferralSync#displayLabel}：服务端 label 优先 / 「游客+短id」兜底。</li>
 * </ul>
 * 全部纯 JVM：merge/parse/displayLabel 不触 Android 运行时（壳方法 pullAndMerge/mergeAndMark 不在本测）。
 */
public class BootstrapReferralSyncTest {

    private static BootstrapReferralSync.ReferralEntry entry(String fpid, String token,
                                                             String label, long furnace) {
        return new BootstrapReferralSync.ReferralEntry(fpid, token, "", label, 152L, furnace);
    }

    private static AccountStore.Account existing(String fpid) {
        AccountStore.Account a = new AccountStore.Account();
        a.id = fpid;
        a.fpid = fpid;
        a.token = "old-token";
        a.label = "老号";
        a.group = "";
        return a;
    }

    @Test
    public void merge_addsNewAccounts() {
        List<AccountStore.Account> existing = new ArrayList<>();
        existing.add(existing("48460403"));
        List<BootstrapReferralSync.ReferralEntry> pending = Arrays.asList(
                entry("48460403", "tok-old", "13800000001", 9L),
                entry("11111111", "tok-a", "13800000002", 9L),
                entry("22222222", "tok-b", "", 5L));
        BootstrapReferralSync.MergeResult r =
                BootstrapReferralSync.mergeReferrals(existing, pending);

        assertEquals(2, r.addedCount());
        assertEquals(1, r.skippedCount());
        // toPull 收全部清单 fpid（跳过的也要标记，否则永远留在服务端待回流清单）
        assertEquals(Arrays.asList("48460403", "11111111", "22222222"), r.toPull);

        AccountStore.Account a1 = r.added.get(0);
        assertEquals("11111111", a1.id);
        assertEquals("11111111", a1.fpid);
        assertEquals("tok-a", a1.token);
        assertEquals("13800000002", a1.label);   // 服务端 label 原样（手机号）
        assertEquals("起号", a1.group);

        AccountStore.Account a2 = r.added.get(1);
        assertEquals("游客2222", a2.label);       // label 缺省 → 「游客+短id」
        assertEquals("", a2.settings);            // 指纹留空，登录时引擎生成回写
        assertEquals(0L, a2.lastLogin);
    }

    @Test
    public void merge_doesNotMutateInputs() {
        List<AccountStore.Account> existing = new ArrayList<>();
        existing.add(existing("48460403"));
        List<BootstrapReferralSync.ReferralEntry> pending = Collections.singletonList(
                entry("99999999", "tok-x", "", 0L));
        BootstrapReferralSync.mergeReferrals(existing, pending);
        // 纯函数：入参列表不被修改（入库由调用方拿到 added 后自行 merge）
        assertEquals(1, existing.size());
        assertEquals("old-token", existing.get(0).token);
    }

    @Test
    public void merge_emptyAndNullSafe() {
        BootstrapReferralSync.MergeResult r1 =
                BootstrapReferralSync.mergeReferrals(new ArrayList<>(), null);
        assertEquals(0, r1.addedCount());
        assertEquals(0, r1.skippedCount());
        assertTrue(r1.toPull.isEmpty());
        BootstrapReferralSync.MergeResult r2 =
                BootstrapReferralSync.mergeReferrals(null, Collections.emptyList());
        assertEquals(0, r2.addedCount());
        // 条目缺 token 不进任何集合（服务端契约外数据）
        BootstrapReferralSync.MergeResult r3 = BootstrapReferralSync.mergeReferrals(
                new ArrayList<>(),
                Collections.singletonList(new BootstrapReferralSync.ReferralEntry(
                        "33333333", "", "", "", 0L, 0L)));
        assertEquals(0, r3.addedCount());
        assertTrue(r3.toPull.isEmpty());
    }

    @Test
    public void parseReferralList_readsEntriesArray() throws Exception {
        JSONObject data = new JSONObject("{\"count\":2,\"entries\":["
                + "{\"fpid\":\"48460403\",\"sdkToken\":\"tok\",\"encrypted\":\"enc\","
                + "\"label\":\"13800000001\",\"serverId\":152,\"furnaceLevel\":9},"
                + "{\"fpid\":\"bad\",\"sdkToken\":\"\"},"
                + "{\"fpid\":\"ok2\",\"sdkToken\":\"tok2\",\"label\":\"游客\",\"furnaceLevel\":7}"
                + "]}");
        List<BootstrapReferralSync.ReferralEntry> list =
                BootstrapReferralSync.parseReferralList(data);
        assertEquals(2, list.size());
        assertEquals("48460403", list.get(0).fpid);
        assertEquals("enc", list.get(0).encrypted);
        assertEquals(9L, list.get(0).furnaceLevel);
        assertEquals("ok2", list.get(1).fpid);
        // null / 无 entries 安全
        assertEquals(0, BootstrapReferralSync.parseReferralList(null).size());
        assertEquals(0, BootstrapReferralSync.parseReferralList(new JSONObject()).size());
    }

    @Test
    public void displayLabel_rules() {
        assertEquals("13800000001", BootstrapReferralSync.displayLabel(
                entry("48460403", "t", "13800000001", 0L)));
        assertEquals("游客0403", BootstrapReferralSync.displayLabel(
                entry("48460403", "t", "", 0L)));
        assertEquals("游客0403", BootstrapReferralSync.displayLabel(
                entry("48460403", "t", "48460403", 0L)));   // label=裸 fpid → 游客+短id
        assertEquals("游客ab", BootstrapReferralSync.displayLabel(
                entry("ab", "t", "", 0L)));                  // 短 id 全保留
        assertEquals("", BootstrapReferralSync.displayLabel(null));
    }
}
