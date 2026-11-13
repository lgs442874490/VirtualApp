package com.carlos.vault;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 入口 ↔ live 会话 归属判定（P0-1/P0-2 回归）。
 *
 * <p>锁定 2026-11-13 真机串号问题的三条硬约束：
 * <ol>
 *   <li>live 归属不是本入口 ⇒ 拒绝导出（否则复制的是别人的 token）；</li>
 *   <li>入口尚未绑定号 ⇒ 只复制、不自动写入口（不能一次导出就改掉入口身份）；</li>
 *   <li>入口已有号且与新号不同 ⇒ 设置 token 必须走「换号」二次确认。</li>
 * </ol>
 */
public class TokenOwnershipTest {

    private static final String ENTRY = "e-sky";

    // ---------- decideExport ----------

    @Test
    public void export_liveOwnerMismatch_rejected() {
        assertEquals(TokenOwnership.ExportDecision.REJECT_OWNER_MISMATCH,
                TokenOwnership.decideExport(ENTRY, "74232981", "e-shuji", "74232981"));
    }

    @Test
    public void export_liveOwnerEmpty_rejected() {
        assertEquals(TokenOwnership.ExportDecision.REJECT_OWNER_MISMATCH,
                TokenOwnership.decideExport(ENTRY, "74232981", null, "74232981"));
        assertEquals(TokenOwnership.ExportDecision.REJECT_OWNER_MISMATCH,
                TokenOwnership.decideExport(ENTRY, "74232981", "", "74232981"));
    }

    @Test
    public void export_entryIdEmpty_rejected() {
        assertEquals(TokenOwnership.ExportDecision.REJECT_OWNER_MISMATCH,
                TokenOwnership.decideExport(null, "74232981", "e-sky", "74232981"));
        assertEquals(TokenOwnership.ExportDecision.REJECT_OWNER_MISMATCH,
                TokenOwnership.decideExport("", "74232981", "e-sky", "74232981"));
    }

    @Test
    public void export_noSession_rejected() {
        assertEquals(TokenOwnership.ExportDecision.REJECT_NO_SESSION,
                TokenOwnership.decideExport(ENTRY, "74232981", ENTRY, null));
        assertEquals(TokenOwnership.ExportDecision.REJECT_NO_SESSION,
                TokenOwnership.decideExport(ENTRY, "74232981", ENTRY, "  "));
    }

    @Test
    public void export_nativeEntry_copyOnly_neverBinds() {
        // 原生登录型入口（fpid 空）：即使 live 归属本入口，也不能自动把 live 里的号写进入口。
        assertEquals(TokenOwnership.ExportDecision.COPY_ONLY,
                TokenOwnership.decideExport(ENTRY, "", ENTRY, "74232981"));
        assertEquals(TokenOwnership.ExportDecision.COPY_ONLY,
                TokenOwnership.decideExport(ENTRY, null, ENTRY, "74232981"));
    }

    @Test
    public void export_sameFpid_binds() {
        assertEquals(TokenOwnership.ExportDecision.BIND,
                TokenOwnership.decideExport(ENTRY, "74232981", ENTRY, "74232981"));
    }

    @Test
    public void export_sameFpidWithWhitespace_binds() {
        assertEquals(TokenOwnership.ExportDecision.BIND,
                TokenOwnership.decideExport(ENTRY, " 74232981 ", ENTRY, "74232981"));
    }

    @Test
    public void export_differentFpid_rejected() {
        assertEquals(TokenOwnership.ExportDecision.REJECT_FPID_MISMATCH,
                TokenOwnership.decideExport(ENTRY, "74232981", ENTRY, "80971192"));
    }

    // ---------- requiresChangeAccountConfirm / isFirstBind ----------

    @Test
    public void setToken_differentFpid_requiresConfirm() {
        assertTrue(TokenOwnership.requiresChangeAccountConfirm("74232981", "80971192"));
    }

    @Test
    public void setToken_sameFpid_noConfirm() {
        assertFalse(TokenOwnership.requiresChangeAccountConfirm("74232981", "74232981"));
        assertFalse(TokenOwnership.requiresChangeAccountConfirm(" 74232981 ", "74232981"));
    }

    @Test
    public void setToken_nativeEntry_firstBind_noConfirm() {
        assertFalse(TokenOwnership.requiresChangeAccountConfirm("", "74232981"));
        assertFalse(TokenOwnership.requiresChangeAccountConfirm(null, "74232981"));
        assertTrue(TokenOwnership.isFirstBind(""));
        assertTrue(TokenOwnership.isFirstBind(null));
        assertFalse(TokenOwnership.isFirstBind("74232981"));
    }

    @Test
    public void setToken_emptyNewFpid_noConfirm() {
        // 空号 ID 由上游 validate 拦截，这里只保证不会误弹换号确认。
        assertFalse(TokenOwnership.requiresChangeAccountConfirm("74232981", ""));
        assertFalse(TokenOwnership.requiresChangeAccountConfirm("74232981", null));
    }
}
