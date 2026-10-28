package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import org.json.JSONObject;

import java.util.Date;
import java.util.Iterator;

/**
 * 按天领取台账：解决“游戏通知仍显示可领/服务端重复回 success”导致的重复领取、
 * 重复弹窗和状态不更新。
 *
 * <p>键：ledger:&lt;fpid&gt;:&lt;sid&gt;:&lt;scope&gt; → {itemKey: "yyyy-MM-dd"}（Phase 1 起非主控角色为
 * ledger:&lt;fpid&gt;:&lt;sid&gt;:&lt;uid&gt;:&lt;scope&gt;，按角色隔离）。
 * 只保留当天键，跨天自动失效；状态随 StateStore 一起回传 App。
 */
final class ClaimLedger {

    private ClaimLedger() {
    }

    /**
     * 台账键（Phase 1 按角色隔离）：roleUid&gt;0 → ledger:&lt;fpid&gt;:&lt;sid&gt;:&lt;uid&gt;:&lt;scope&gt;；
     * roleUid&lt;=0 → 旧键 ledger:&lt;fpid&gt;:&lt;sid&gt;:&lt;scope&gt;。
     *
     * <p>非主控角色读取时<b>不做</b>跨角色回退：把主控角色的“今日已领”当成自己的会漏领，
     * 宁可本角色首次多请求一次（服务端已有已领码兜底）。
     */
    private static String storeKey(TaskContext tc, String scope) {
        return tc.roleUid > 0L
                ? "ledger:" + tc.fpid + ":" + tc.sid + ":" + tc.roleUid + ":" + scope
                : "ledger:" + tc.fpid + ":" + tc.sid + ":" + scope;
    }

    private static String today() {
        return Beijing.fmt("yyyy-MM-dd").format(new Date());
    }

    private static JSONObject load(TaskContext tc, String scope) {
        try {
            String raw = tc.store.getString(storeKey(tc, scope));
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static boolean isClaimed(TaskContext tc, String scope, String itemKey) {
        if (itemKey == null || itemKey.isEmpty()) return false;
        JSONObject ledger = load(tc, scope);
        return today().equals(ledger.optString(itemKey, ""));
    }

    static void mark(TaskContext tc, String scope, String itemKey) {
        if (itemKey == null || itemKey.isEmpty()) return;
        JSONObject ledger = load(tc, scope);
        // 顺手清掉非今天的旧键，避免无限增长。
        String day = today();
        try {
            Iterator<String> it = ledger.keys();
            java.util.List<String> stale = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (!day.equals(ledger.optString(k, ""))) stale.add(k);
            }
            for (String k : stale) ledger.remove(k);
            ledger.put(itemKey, day);
            tc.store.putString(storeKey(tc, scope), ledger.toString());
        } catch (Throwable ignored) {
        }
    }

    static int claimedToday(TaskContext tc, String scope) {
        JSONObject ledger = load(tc, scope);
        String day = today();
        int n = 0;
        try {
            Iterator<String> it = ledger.keys();
            while (it.hasNext()) {
                if (day.equals(ledger.optString(it.next(), ""))) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }
}
