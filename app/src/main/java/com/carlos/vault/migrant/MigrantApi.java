package com.carlos.vault.migrant;

import android.content.Context;

import com.carlos.vault.VaultSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


/**
 * 移民模块客户端：唯一通道 {@code POST /v1/vault/migrant}（op {@code vaultMigrant}）。
 *
 * <p><b>为什么不复刻网页版 REST</b>：网页版走 {@code /api/migrant/*}；本项目 App↔vault-server
 * 统一走 vault-http 信封 + ApiSign（契约 §1）。因此本类只按 action 发信封请求，
 * 14 个 action 由服务端 {@code MigrantApi.handle} 分发。
 *
 * <p><b>身份映射</b>（契约 §1，两端必须一致）：每个请求体都带
 * {@code fpid}（账号 token 维度）、{@code sid}（当前服）、{@code uid}（角色主键，long）。
 * 服务端据此做越权校验（非本账号角色 → {@code BizError(1004,"角色不存在")}）与
 * {@code SessionKeeper} 会话定位。
 *
 * <p><b>签名</b>：复用 {@code VaultSync.postSigned}（全 App 唯一一份密钥/格式，
 * 不得在本类重新复制 SECRET_OBF——2026-11-06 收敛）。
 *
 * <p>线程语义与 {@link VaultSync} 一致：所有方法立即返回，回调在 OkHttp 工作线程触发，
 * 调用方需自行切主线程。
 */
public final class MigrantApi {

    /** 契约 §1：唯一路径与 op。 */
    private static final String PATH = "/v1/vault/migrant";
    private static final String OP = "vaultMigrant";


    /** action 常量（契约 §3，逐字照抄）。 */
    public static final String ACT_ACCOUNTS = "accounts";
    public static final String ACT_OVERVIEW = "overview";
    public static final String ACT_PRECHECK = "precheck";
    public static final String ACT_TEST_EARLY = "test-early";
    public static final String ACT_CREATE = "create";
    public static final String ACT_BULK = "bulk";
    public static final String ACT_JOBS = "jobs";
    public static final String ACT_CANCEL = "cancel";
    public static final String ACT_RECHECK = "recheck";
    public static final String ACT_MARCHES = "marches";
    public static final String ACT_RECALL = "recall";
    public static final String ACT_ATTEMPTS = "attempts";
    public static final String ACT_LOGIN = "login";
    public static final String ACT_LOGOUT = "logout";

    /** 召回方式（契约 §3.11）。 */
    public static final String RECALL_MODE_ITEM = "item";
    public static final String RECALL_MODE_DIAMOND = "diamond";

    private MigrantApi() {
    }

    /**
     * 请求身份（契约 §1）：{@code fpid} 账号 + {@code sid} 当前服 + {@code uid} 角色主键。
     * 不可变，便于跨线程传递。
     */
    public static final class Identity {
        public final String fpid;
        public final long sid;
        public final long uid;

        public Identity(String fpid, long sid, long uid) {
            this.fpid = fpid == null ? "" : fpid;
            this.sid = Math.max(0L, sid);
            this.uid = Math.max(0L, uid);
        }

        public boolean valid() {
            return !fpid.isEmpty() && sid > 0;
        }

        public String key() {
            return fpid + ":" + sid + ":" + uid;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    /** 回调（OkHttp 线程）。 */
    public interface Callback {
        void onResult(int code, String message, JSONObject data);
    }

    // ==================== 13 个 action ====================

    /**
     * {@code accounts}：账号池角色列表 {@code {accounts:[{accountRoleId, game_name, server_id, online}]}}。
     * 批量创建弹窗的账号多选数据源。
     */
    public static void accounts(Context ctx, Identity who, Callback cb) {
        post(ctx, who, ACT_ACCOUNTS, null, cb, "vault-migrant-accounts");
    }

    /**
     * {@code overview}：活动总览 + 王国列表（契约 §3.2）。
     * 离线时服务端返回 {@code {online:false,message}}（2026-11-06 裁决；页面显示未上线并禁用表单）。
     */
    public static void overview(Context ctx, Identity who, Callback cb) {
        post(ctx, who, ACT_OVERVIEW, null, cb, "vault-migrant-overview");
    }

    /** {@code logout}：角色下线（网页版工作区头部「账号上线/下线」开关的在线态动作）。 */
    public static void logout(Context ctx, Identity who, Callback cb) {
        post(ctx, who, ACT_LOGOUT, null, cb, "vault-migrant-logout");
    }

    /** {@code precheck}：资格预检；出参含 checks/warnings。 */
    public static void precheck(Context ctx, Identity who, long targetKid,
                                boolean autoExitAlliance, boolean recallBeforeOpen,
                                boolean recallSpeedup, boolean recallDiamondSpeedup,
                                int advanceMillis, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("target_kid", targetKid);
        f.put("auto_exit_alliance", autoExitAlliance);
        f.put("recall_before_open", recallBeforeOpen);
        f.put("recall_speedup", recallSpeedup);
        f.put("recall_diamond_speedup", recallDiamondSpeedup);
        f.put("advance_millis", MigrantModels.clampAdvance(advanceMillis));
        post(ctx, who, ACT_PRECHECK, f, cb, "vault-migrant-precheck");
    }

    /**
     * {@code test-early}：真实发送一次 {@code req_migrant_migrate}（危险语义保留）。
     * 调用前必须由 UI 二次确认（网页版同样 confirm）。
     */
    public static void testEarly(Context ctx, Identity who, long targetKid, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("target_kid", targetKid);
        post(ctx, who, ACT_TEST_EARLY, f, cb, "vault-migrant-test-early");
    }

    /** {@code create}：创建单个边界任务；同角色已有 active 任务时服务端 BizError（中文原因）。 */
    public static void create(Context ctx, Identity who, long targetKid,
                              int advanceMillis, boolean autoExitAlliance,
                              boolean recallBeforeOpen, boolean recallSpeedup,
                              boolean recallDiamondSpeedup, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("target_kid", targetKid);
        f.put("advance_millis", MigrantModels.clampAdvance(advanceMillis));
        f.put("auto_exit_alliance", autoExitAlliance);
        f.put("recall_before_open", recallBeforeOpen);
        f.put("recall_speedup", recallSpeedup);
        f.put("recall_diamond_speedup", recallDiamondSpeedup);
        post(ctx, who, ACT_CREATE, f, cb, "vault-migrant-create");
    }

    /**
     * {@code bulk}：批量创建；出参 {@code {requested, created:[job_id], failed:[{account_role_id,message}]}}。
     * 逐角色独立，失败隔离。
     */
    public static void bulk(Context ctx, Identity who, long targetKid,
                            List<Long> accountRoleIds, boolean autoExitAlliance,
                            boolean recallBeforeOpen, boolean recallSpeedup,
                            boolean recallDiamondSpeedup, int advanceMillis, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("target_kid", targetKid);
        JSONArray ids = new JSONArray();
        if (accountRoleIds != null) {
            for (Long id : accountRoleIds) {
                if (id != null && id.longValue() > 0) ids.put(id.longValue());
            }
        }
        f.put("account_role_ids", ids);
        f.put("auto_exit_alliance", autoExitAlliance);
        f.put("recall_before_open", recallBeforeOpen);
        f.put("recall_speedup", recallSpeedup);
        f.put("recall_diamond_speedup", recallDiamondSpeedup);
        f.put("advance_millis", MigrantModels.clampAdvance(advanceMillis));
        post(ctx, who, ACT_BULK, f, cb, "vault-migrant-bulk");
    }

    /** {@code jobs}：{@code {active:[job], finished:[job]}}（finished 已在服务端倒序截断 50）。 */
    public static void jobs(Context ctx, Identity who, Callback cb) {
        post(ctx, who, ACT_JOBS, null, cb, "vault-migrant-jobs");
    }

    /** {@code cancel}：仅 {@code cancelable=true} 可取消，否则服务端 BizError。 */
    public static void cancel(Context ctx, Identity who, String jobId, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("job_id", jobId == null ? "" : jobId);
        post(ctx, who, ACT_CANCEL, f, cb, "vault-migrant-cancel");
    }

    /** {@code recheck}：重登读当前王国对账（终态任务的「重新核验」）。 */
    public static void recheck(Context ctx, Identity who, String jobId, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("job_id", jobId == null ? "" : jobId);
        post(ctx, who, ACT_RECHECK, f, cb, "vault-migrant-recheck");
    }

    /** {@code marches}：撤退详情（行军列表 + 手动召回许可）。 */
    public static void marches(Context ctx, Identity who, String jobId, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("job_id", jobId == null ? "" : jobId);
        post(ctx, who, ACT_MARCHES, f, cb, "vault-migrant-marches");
    }

    /** {@code recall}：单支（{@code marchId>0}）或全部（{@code marchId=0}）召回。 */
    public static void recall(Context ctx, Identity who, String jobId, long marchId,
                              String mode, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("job_id", jobId == null ? "" : jobId);
        f.put("march_id", marchId);
        f.put("mode", mode == null || mode.isEmpty() ? RECALL_MODE_ITEM : mode);
        post(ctx, who, ACT_RECALL, f, cb, "vault-migrant-recall");
    }

    /** {@code attempts}：请求审计（时间轴 + 逐次请求明细）。 */
    public static void attempts(Context ctx, Identity who, String jobId, Callback cb) {
        Map<String, Object> f = new TreeMap<>();
        f.put("job_id", jobId == null ? "" : jobId);
        post(ctx, who, ACT_ATTEMPTS, f, cb, "vault-migrant-attempts");
    }

    /** {@code login}：拉起角色会话（撤退详情「上线并同步部队」）。 */
    public static void login(Context ctx, Identity who, Callback cb) {
        post(ctx, who, ACT_LOGIN, null, cb, "vault-migrant-login");
    }

    /** 批量召回全部：{@code march_id=0}。 */
    public static void recallAll(Context ctx, Identity who, String jobId, String mode, Callback cb) {
        recall(ctx, who, jobId, 0L, mode, cb);
    }

    // ==================== 传输层（复用 VaultSync 统一签名通道）====================

    /**
     * 发送一次信封请求。
     *
     * <p><b>2026-11-06 改造</b>：不再本类复制 `SECRET_OBF`/XOR key/签名格式，改调
     * {@link VaultSync#postSigned} 与 {@link VaultSync#signedCommonFields}——签名与信封
     * 全 App 唯一一份（复制点的密钥轮换会静默失效，属安全敏感重复）。
     */
    private static void post(final Context ctx, final Identity who, final String action,
                             final Map<String, Object> extra, final Callback cb,
                             String threadName) {
        final Context app = ctx == null ? null : ctx.getApplicationContext();
        if (app == null) {
            if (cb != null) cb.onResult(-1, "应用上下文不可用", null);
            return;
        }
        if (who == null || !who.valid()) {
            if (cb != null) cb.onResult(-1, "缺少账号或角色信息，请重新选择角色", null);
            return;
        }
        Map<String, Object> f = VaultSync.signedCommonFields(app);
        // 契约 §1 身份映射：fpid / sid / uid；
        // account_role_id 是服务端角色寻址主键（各 action 入参名），与 uid 同值双写，
        // 避免两端对「角色主键字段名」的解读差异。
        f.put("fpid", who.fpid);
        f.put("sid", who.sid);
        f.put("uid", who.uid);
        f.put("account_role_id", who.uid);
        if (extra != null) f.putAll(extra);
        f.put("action", action);
        // VaultSync.Callback 与本类 Callback 结构一致（单方法），用适配器桥接
        VaultSync.postSigned(app, PATH, OP, f,
                threadName == null ? "vault-migrant" : threadName,
                (code, message, data) -> {
                    if (cb != null) cb.onResult(code, message, data);
                });
    }

    // ==================== 便捷组合 ====================

    /**
     * 并行拉取 {@code overview + jobs}（页面刷新入口；两者都完成后回调一次）。
     * 任一失败即按失败回调（与网页版 {@code Promise.all} 语义一致）。
     * 成功时 data = {@code {overview:..., jobs:...}}。
     */
    public static void refresh(Context ctx, Identity who, Callback cb) {
        final JSONObject[] ovBox = new JSONObject[1];
        final JSONObject[] jobsBox = new JSONObject[1];
        final int[] pending = {2};
        final boolean[] failed = {false};
        final String[] errMessage = {""};
        final int[] errCode = {0};

        Callback done = (code, message, data) -> {
            synchronized (pending) {
                if (failed[0]) return;
                if (code != 0) {
                    failed[0] = true;
                    errCode[0] = code;
                    errMessage[0] = message;
                }
                pending[0]--;
                if (pending[0] > 0) return;
                if (failed[0] || cb == null) {
                    if (cb != null) cb.onResult(errCode[0], errMessage[0], null);
                    return;
                }
                JSONObject merged = new JSONObject();
                try {
                    if (ovBox[0] != null) merged.put("overview", ovBox[0]);
                    if (jobsBox[0] != null) merged.put("jobs", jobsBox[0]);
                } catch (Throwable ignored) {
                }
                cb.onResult(0, "", merged);
            }
        };

        overview(ctx, who, (code, message, data) -> {
            ovBox[0] = data;
            done.onResult(code, message, data);
        });
        jobs(ctx, who, (code, message, data) -> {
            jobsBox[0] = data;
            done.onResult(code, message, data);
        });
    }

    /** 角色 id 列表（批量弹窗提交用，纯函数）。 */
    public static List<Long> roleIdsOf(List<MigrantModels.AccountItem> items) {
        List<Long> out = new ArrayList<>();
        if (items == null) return out;
        for (MigrantModels.AccountItem a : items) {
            if (a != null && a.accountRoleId > 0) out.add(a.accountRoleId);
        }
        return out;
    }
}
