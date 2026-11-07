package com.carlos.vault.bootstrap;

import android.content.Context;

import com.carlos.vault.AccountStore;
import com.carlos.vault.VaultSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号回流（M5，2026-11-07）：把 vault-server 起号 SUCCESS 后写进
 * {@code bootstrap_referral.json} 的账号拉回 App 号库（AccountStore）。
 *
 * <p>服务端语义（M4b）：起号成功的号写入待回流清单（pulled=false），App 拉取清单 →
 * 并入 AccountStore → {@code referral-pull} 标记 → 随下一次全量 sync 双向合并。
 * <b>本类不自动触发全量 sync</b>（不调 {@link VaultSync#onAccountsChanged}）——回流路径
 * 的账号由用户在账号页手动同步或现有机制触发，避免打断既有 sync 时序（任务书 §3.3）。
 *
 * <p>结构：{@link #mergeReferrals} / {@link #parseReferralList} / {@link #displayLabel}
 * 为纯函数（JVM 单测直跑）；{@link #pullAndMerge} 是 Android 同步壳（全异步，回调在
 * OkHttp 线程）。凭据纪律：sdkToken 只进 AccountStore 落盘（号库本体），不进日志。
 */
public final class BootstrapReferralSync {

    /** 新账号归入的分组名（任务书 §3.3）。 */
    public static final String GROUP = "起号";

    private BootstrapReferralSync() {
    }

    /** 待回流条目（referral-list 的 entries[] 元素投影）。 */
    public static final class ReferralEntry {
        public final String fpid;
        public final String sdkToken;
        /** 加密串（fpid|token 两段式导入的条目缺失，为空串）。 */
        public final String encrypted;
        /** 服务端展示标签（手机号或 fpid；缺省空串）。 */
        public final String label;
        public final long serverId;
        public final long furnaceLevel;

        public ReferralEntry(String fpid, String sdkToken, String encrypted,
                             String label, long serverId, long furnaceLevel) {
            this.fpid = fpid == null ? "" : fpid.trim();
            this.sdkToken = sdkToken == null ? "" : sdkToken.trim();
            this.encrypted = encrypted == null ? "" : encrypted.trim();
            this.label = label == null ? "" : label.trim();
            this.serverId = serverId;
            this.furnaceLevel = furnaceLevel;
        }

        /** 解析 referral-list 的单个 entry（缺 fpid/sdkToken 返回 null，调用方跳过）。 */
        public static ReferralEntry fromJson(JSONObject o) {
            if (o == null) return null;
            String fpid = o.optString("fpid", "").trim();
            String sdkToken = o.optString("sdkToken", "").trim();
            if (fpid.isEmpty() || sdkToken.isEmpty()) return null;
            return new ReferralEntry(fpid, sdkToken, o.optString("encrypted", ""),
                    o.optString("label", ""), o.optLong("serverId", 0L),
                    o.optLong("furnaceLevel", 0L));
        }
    }

    /** 合并结果：added=新增账号（调用方入库）；skippedFpids=号库已有被跳过；toPull=需标记已拉取。 */
    public static final class MergeResult {
        public final List<AccountStore.Account> added = new ArrayList<>();
        public final List<String> skippedFpids = new ArrayList<>();
        public final List<String> toPull = new ArrayList<>();

        public int addedCount() {
            return added.size();
        }

        public int skippedCount() {
            return skippedFpids.size();
        }
    }

    /**
     * 合并待回流清单进现有号库（纯函数）：按 fpid 去重——
     * <ul>
     *   <li>号库已有该 fpid → 跳过（计入 {@code skippedFpids}）；</li>
     *   <li>新号 → {@code Account{id=fpid, label=展示标签, group="起号", token=sdkToken}}；
     *       不动设备指纹（settings 留空，登录时由引擎生成并回写）；</li>
     *   <li>{@code toPull} 收全部清单 fpid（新增与跳过都要标记 pulled——否则已存在的号
     *       会永远留在服务端待回流清单里反复出现）。</li>
     * </ul>
     * 入参 null 安全：existing/pending 任一为 null 按空清单处理；不修改入参列表。
     */
    public static MergeResult mergeReferrals(List<AccountStore.Account> existing,
                                             List<ReferralEntry> pending) {
        MergeResult out = new MergeResult();
        if (pending == null || pending.isEmpty()) return out;
        java.util.Set<String> known = new java.util.HashSet<>();
        if (existing != null) {
            for (AccountStore.Account a : existing) {
                if (a != null && a.fpid != null && !a.fpid.isEmpty()) known.add(a.fpid);
            }
        }
        for (ReferralEntry e : pending) {
            if (e == null || e.fpid.isEmpty() || e.sdkToken.isEmpty()) continue;
            out.toPull.add(e.fpid);
            if (known.contains(e.fpid)) {
                out.skippedFpids.add(e.fpid);
                continue;
            }
            known.add(e.fpid);
            AccountStore.Account a = new AccountStore.Account();
            a.id = e.fpid;
            a.fpid = e.fpid;
            a.token = e.sdkToken;
            a.label = displayLabel(e);
            a.group = GROUP;
            a.lastLogin = 0L;
            a.settings = "";
            out.added.add(a);
        }
        return out;
    }

    /**
     * 解析 referral-list 响应 data（{@code {count, entries[]}}，纯函数）。data 为 null /
     * 无 entries / 单条缺 fpid|sdkToken 的都跳过；顺序保持服务端插入序。
     */
    public static List<ReferralEntry> parseReferralList(JSONObject data) {
        List<ReferralEntry> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("entries");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            ReferralEntry e = ReferralEntry.fromJson(arr.optJSONObject(i));
            if (e != null) out.add(e);
        }
        return out;
    }

    /**
     * 展示标签（纯函数）：服务端 label 非空且不是裸 fpid → 原样；否则「游客+短 id」
     * （fpid 尾 4 位，短于 4 位用全串）。与任务书 §3.3 的「手机号 / 游客+短id」口径一致。
     */
    public static String displayLabel(ReferralEntry e) {
        if (e == null) return "";
        if (!e.label.isEmpty() && !e.label.equals(e.fpid)) return e.label;
        String id = e.fpid.isEmpty() ? e.label : e.fpid;
        if (id.isEmpty()) return "游客";
        return "游客" + (id.length() <= 4 ? id : id.substring(id.length() - 4));
    }

    // ================= Android 同步壳（全异步；勿在纯逻辑单测中触达） =================

    /**
     * 拉取待回流清单 → {@link #mergeReferrals} → AccountStore 入库 → referral-pull 标记。
     *
     * <p>两段串行（清单响应后再入库、入库后再标记），任一段失败都以服务端信封码回调。
     * 成功回调 data 附加 {@code added/skipped/marked} 计数；<b>不触发全量 sync</b>，
     * 由调用方在 UI 提示「已并入号库，稍后请在账号页手动同步」。回调在 OkHttp 线程。
     */
    public static void pullAndMerge(Context ctx, final VaultSync.Callback done) {
        final Context app = ctx.getApplicationContext();
        VaultSync.bootstrapCall(app, VaultSync.bootstrapReferralListFields(),
                (code, message, data) -> {
                    if (code != 0) {
                        if (done != null) done.onResult(code, message, data);
                        return;
                    }
                    mergeAndMark(app, parseReferralList(data), data, done);
                });
    }

    /**
     * 合并 + 标记内核（壳复用点，「拉取服务器待回流号→勾选导入」同样走这里）：
     * 给定条目按 {@link #mergeReferrals} 去重入库（仅新增落盘）并把全部 fpid 标记
     * 已拉取（已存在的号也要标记，否则永远留在服务端待回流清单反复出现）。
     * pending 为空时直接以 {@code "暂无待回流账号"} 成功回调。回调在 OkHttp 线程。
     *
     * @param listData referral-list 原始 data（计数附加其上；可为 null，自动兜新对象）
     */
    public static void mergeAndMark(final Context app, final List<ReferralEntry> pending,
                                    final JSONObject listData, final VaultSync.Callback done) {
        if (pending == null || pending.isEmpty()) {
            if (done != null) done.onResult(0, "暂无待回流账号", listData);
            return;
        }
        final List<AccountStore.Account> existing = new AccountStore(app).all();
        final MergeResult result = mergeReferrals(existing, pending);
        if (!result.added.isEmpty()) {
            List<AccountStore.Account> merged = new ArrayList<>(existing);
            merged.addAll(result.added);
            new AccountStore(app).saveSync(merged);
        }
        VaultSync.bootstrapCall(app, VaultSync.bootstrapReferralPullFields(result.toPull),
                (code, message, data) -> {
                    JSONObject out = listData == null ? new JSONObject() : listData;
                    try {
                        out.put("added", result.addedCount());
                        out.put("skipped", result.skippedCount());
                    } catch (Throwable ignored) {
                    }
                    if (done != null) done.onResult(code, message, out);
                });
    }
}
