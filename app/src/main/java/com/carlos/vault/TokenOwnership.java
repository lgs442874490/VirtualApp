package com.carlos.vault;

/**
 * 入口 ↔ live 会话 归属判定（纯逻辑，JVM 单测直接跑）。
 *
 * <p><b>为什么需要它</b>：容器只有<b>一份</b> live 会话目录，任何入口启动都会覆盖它。
 * 「导出 token」与「设置 token」如果只比较 fpid、不校验 live 归属，就会出现
 * 「在 A 入口里导出/绑定了 B 账号的 token」——用户随后注入即覆盖 B 的登录
 * （2026-11-13 真机：原生登录分身导出到别人的 token，注入后书记被顶）。
 *
 * <p>本类把判定从 Activity 里抽出来，UI 只按枚举值决定「是否复制」「是否写入口」，
 * 避免以后再出现两处口径不一致。规则：
 * <ul>
 *   <li>live 归属不是本入口 ⇒ <b>拒绝导出，连剪贴板都不碰</b>（复制即误导）；</li>
 *   <li>live 归属本入口、入口尚未绑定号（原生登录型）⇒ <b>只复制，不自动写入口</b>
 *       —— 入口身份不能被一次导出悄悄改写；</li>
 *   <li>live 归属本入口、入口已有号且一致 ⇒ 复制并刷新入口 token；</li>
 *   <li>live 归属本入口、入口已有号但不一致 ⇒ 拒绝导出（先确认/换号，不要拿别人的 token）。</li>
 * </ul>
 */
public final class TokenOwnership {

    private TokenOwnership() {
    }

    /** 导出 token 的处置结果。 */
    public enum ExportDecision {
        /** live 属于本入口且号一致：复制，并刷新入口 token。 */
        BIND,
        /** live 属于本入口、入口尚未绑定号：只复制，**不**自动写入口。 */
        COPY_ONLY,
        /** live 不属于本入口：拒绝导出（不复制、不写入口）。 */
        REJECT_OWNER_MISMATCH,
        /** live 属于本入口，但登录的号与入口记录的号不同：拒绝导出（不复制、不写入口）。 */
        REJECT_FPID_MISMATCH,
        /** 读不到有效会话：拒绝导出。 */
        REJECT_NO_SESSION
    }

    /**
     * 导出前判定。
     *
     * @param entryId    当前入口 id（null/空 ⇒ 无法确认归属）
     * @param entryFpid  入口记录的号 ID（原生登录型为空）
     * @param liveOwner  live 目录当前归属的入口 id（{@code AccountStore#getLiveEntryId()}）
     * @param liveFpid   当前 live 里实际登录的号 ID
     * @return 处置结果；调用方按结果决定复制/写入口/提示
     */
    public static ExportDecision decideExport(String entryId, String entryFpid,
                                              String liveOwner, String liveFpid) {
        if (trim(liveFpid).isEmpty()) return ExportDecision.REJECT_NO_SESSION;
        if (trim(entryId).isEmpty() || !trim(entryId).equals(trim(liveOwner))) {
            return ExportDecision.REJECT_OWNER_MISMATCH;
        }
        String cur = trim(entryFpid);
        if (cur.isEmpty()) return ExportDecision.COPY_ONLY;
        return cur.equals(trim(liveFpid))
                ? ExportDecision.BIND : ExportDecision.REJECT_FPID_MISMATCH;
    }

    /**
     * 「设置 token」是否需要二次确认「换号」。
     *
     * <p>入口已有号且与新号不同 ⇒ 这是换号，不只是刷新 token：保存后下次启动会
     * 摘掉 live 归属并重新注入，原账号的登录现场会被替换。必须让用户明确确认，
     * 避免把别处导出的 token 粘进来覆盖自己/他人的登录。
     */
    public static boolean requiresChangeAccountConfirm(String entryFpid, String newFpid) {
        String cur = trim(entryFpid);
        String next = trim(newFpid);
        return !cur.isEmpty() && !next.isEmpty() && !cur.equals(next);
    }

    /** 入口尚未绑定号（原生登录型）：首次设置 token 不触发「换号」确认。 */
    public static boolean isFirstBind(String entryFpid) {
        return trim(entryFpid).isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
