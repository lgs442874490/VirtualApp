package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 按天领取台账：同一子项当天只允许请求一次，跨 scope 隔离。 */
public class ClaimLedgerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
    }

    @Test
    public void mark_isIdempotentWithinDay() {
        TaskContext tc = context();
        assertFalse(ClaimLedger.isClaimed(tc, "expert_extra", "1320090"));
        ClaimLedger.mark(tc, "expert_extra", "1320090");
        assertTrue(ClaimLedger.isClaimed(tc, "expert_extra", "1320090"));
        assertEquals(1, ClaimLedger.claimedToday(tc, "expert_extra"));
        // 重复 mark 不增加条目：第二天轮次仍只跳过同一个任务。
        ClaimLedger.mark(tc, "expert_extra", "1320090");
        assertEquals(1, ClaimLedger.claimedToday(tc, "expert_extra"));
    }

    @Test
    public void scopes_areIndependent() {
        TaskContext tc = context();
        ClaimLedger.mark(tc, "expert_extra", "q1");
        assertTrue(ClaimLedger.isClaimed(tc, "expert_extra", "q1"));
        assertFalse(ClaimLedger.isClaimed(tc, "dawn_market", "q1"));
        ClaimLedger.mark(tc, "dawn_market", "quest:q1");
        assertTrue(ClaimLedger.isClaimed(tc, "dawn_market", "quest:q1"));
        assertFalse(ClaimLedger.isClaimed(tc, "dawn_market", "q1"));
    }

    @Test
    public void emptyKey_neverCounted() {
        TaskContext tc = context();
        assertFalse(ClaimLedger.isClaimed(tc, "expert_extra", ""));
        ClaimLedger.mark(tc, "expert_extra", "");
        assertEquals(0, ClaimLedger.claimedToday(tc, "expert_extra"));
    }

    @Test
    public void staleKeys_prunedOnNextMark() throws Exception {
        TaskContext tc = context();
        JSONObject old = new JSONObject();
        old.put("yesterday", "2020-01-01");
        tc.store.putString("ledger:48460403:888:expert_extra", old.toString());

        ClaimLedger.mark(tc, "expert_extra", "today");
        JSONObject ledger = new JSONObject(
                tc.store.getString("ledger:48460403:888:expert_extra"));
        assertFalse(ledger.has("yesterday"));
        assertEquals(1, ledger.length());
        assertEquals(1, ClaimLedger.claimedToday(tc, "expert_extra"));
    }
}
