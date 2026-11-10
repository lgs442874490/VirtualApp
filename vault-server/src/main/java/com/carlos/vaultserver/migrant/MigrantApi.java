package com.carlos.vaultserver.migrant;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionKeeper;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.task.RoleKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 移民 14 个 action 的 HTTP 路由层（纯参数解析 / 权限校验 / JSON 组装，业务在
 * {@link MigrantEngine}）。
 *
 * <p>请求统一走 vault-http 信封：{@code POST /v1/vault/migrant}，body =
 * commonFields({@code fpid/sid/uid}) + {@code action} + 业务字段。响应
 * {@code {code, message, data, serverTime}} 由 {@code VaultHttpApi.guard} 组装。
 *
 * <p>action 列表（与网页版 {@code /api/migrant/*} 一一对应）：
 * {@code accounts / overview / precheck / test-early / create / bulk / jobs / job /
 * cancel / recheck / marches / recall / attempts / login}。
 */
public final class MigrantApi {

    /** 业务错误（VaultHttpApi 捕获后转成信封 code）。 */
    public static final class BizError extends RuntimeException {
        public final int code;

        public BizError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final SessionKeeper keeper;
    private final VaultStore store;
    private final StateStore stateStore;
    private final MigrantJobStore jobs;
    private final MigrantEngine engine;

    public MigrantApi(SessionKeeper keeper, VaultStore store, StateStore stateStore,
                      MigrantJobStore jobs, MigrantEngine engine) {
        this.keeper = keeper;
        this.store = store;
        this.stateStore = stateStore;
        this.jobs = jobs;
        this.engine = engine;
    }

    /** 14 个 action 分发入口。 */
    public JSONObject handle(Map<String, Object> fields) {
        try {
            return dispatch(fields);
        } catch (BizError e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new BizError(3002, messageOf(e));
        } catch (RuntimeException e) {
            FileLog.warn("移民 action 执行异常：{}", String.valueOf(e));
            throw new BizError(3001, "移民模块异常：" + messageOf(e));
        }
    }

    private JSONObject dispatch(Map<String, Object> fields) {
        if (fields == null) throw new BizError(3002, "请求体为空");
        String action = normalizeAction(str(fields, "action"));
        if (action.isEmpty()) throw new BizError(3002, "缺少 action");
        String fpid = requireFpid(fields);

        if ("accounts".equals(action)) return accounts(fpid);
        if ("jobs".equals(action)) return engine.jobsJson(fpid);
        if ("job".equals(action)) return jobJson(fields, fpid);
        if ("cancel".equals(action)) return cancel(fields, fpid);
        if ("recheck".equals(action)) return recheck(fields, fpid);
        if ("marches".equals(action)) return marches(fields, fpid);
        if ("recall".equals(action)) return recall(fields, fpid);
        if ("attempts".equals(action)) return attempts(fields, fpid);
        if ("overview".equals(action)) return overview(fields, fpid);
        if ("precheck".equals(action)) return precheck(fields, fpid);
        if ("test-early".equals(action)) return testEarly(fields, fpid);
        if ("create".equals(action)) return create(fields, fpid);
        if ("bulk".equals(action)) return bulk(fields, fpid);
        if ("login".equals(action)) return login(fields, fpid);
        if ("logout".equals(action)) return logout(fields, fpid);
        throw new BizError(3002, "未知的移民 action：" + action);
    }

    // =====================================================================
    // 账号 / 任务大盘
    // =====================================================================

    /** 账号列表：只返回请求 fpid 的角色（批量弹窗据此选人，P2-5）。 */
    private JSONObject accounts(String fpid) {
        JSONArray arr = new JSONArray();
        List<VaultStore.Account> accounts = new ArrayList<>(store.snapshot());
        for (VaultStore.Account account : accounts) {
            if (!fpid.equals(account.fpid)) continue;
            List<VaultStore.RoleEntry> roles = store.roles(account.fpid);
            for (VaultStore.RoleEntry role : roles) {
                if (role == null || role.uid == null || role.uid.isEmpty()) continue;
                long uid;
                long sid;
                try {
                    uid = Long.parseLong(role.uid);
                } catch (RuntimeException e) {
                    continue;
                }
                try {
                    sid = Long.parseLong(role.sid);
                } catch (RuntimeException e) {
                    continue;
                }
                if (uid <= 0L || sid <= 0L) continue;
                JSONObject item = new JSONObject();
                item.put("accountRoleId", uid);
                item.put("game_name", role.name == null || role.name.trim().isEmpty()
                        ? (account.label == null || account.label.trim().isEmpty()
                        ? account.fpid : account.label) : role.name);
                item.put("account", account.label == null || account.label.trim().isEmpty()
                        ? account.fpid : account.label);
                item.put("fpid", account.fpid);
                item.put("server_id", sid);
                item.put("kid", sid);
                boolean online = keeper != null
                        && keeper.roleRuntime(account.fpid, role.sid, role.uid).online;
                item.put("online", online);
                arr.put(item);
            }
        }
        JSONObject out = new JSONObject();
        out.put("accounts", arr);
        return out;
    }

    private JSONObject jobJson(Map<String, Object> fields, String fpid) {
        MigrantJob job = requireJob(fields, fpid);
        return job.toJson();
    }

    private JSONObject cancel(Map<String, Object> fields, String fpid) {
        MigrantJob job = requireJob(fields, fpid);
        return engine.cancel(job.jobId);
    }

    private JSONObject recheck(Map<String, Object> fields, String fpid) {
        MigrantJob job = requireJob(fields, fpid);
        return engine.recheck(job);
    }

    private JSONObject marches(Map<String, Object> fields, String fpid) {
        MigrantJob job = requireJob(fields, fpid);
        return engine.marches(job);
    }

    private JSONObject recall(Map<String, Object> fields, String fpid) {
        MigrantJob job = requireJob(fields, fpid);
        long marchId = longValue(fields.get("march_id"), 0L);
        String mode = str(fields, "mode");
        if (mode == null || mode.trim().isEmpty()) mode = "item";
        if (!"item".equalsIgnoreCase(mode) && !"diamond".equalsIgnoreCase(mode)) {
            throw new BizError(3002, "召回方式只能是 item 或 diamond");
        }
        return engine.recall(job, marchId, mode.toLowerCase());
    }

    private JSONObject attempts(Map<String, Object> fields, String fpid) {
        MigrantJob job = requireJob(fields, fpid);
        return engine.attempts(job);
    }

    private MigrantJob requireJob(Map<String, Object> fields, String fpid) {
        String jobId = str(fields, "job_id");
        if (jobId == null || jobId.isEmpty()) jobId = str(fields, "jobId");
        if (jobId == null || jobId.isEmpty()) throw new BizError(3002, "缺少 job_id");
        MigrantJob job = engine.findJob(jobId);
        if (job == null) throw new BizError(1004, "移民任务不存在");
        if (!job.fpid.equals(fpid)) throw new BizError(1004, "角色不存在");
        return job;
    }

    // =====================================================================
    // 角色级 action
    // =====================================================================

    private JSONObject overview(Map<String, Object> fields, String fpid) {
        RoleTarget target = resolveRole(fields, fpid, false);
        JSONObject out = engine.overview(target.toRoleKey());
        // 离线：引擎返回 null，但 VaultHttpApi.envelope 会把 null 兜成 {}，
        // App 无法区分「离线」与「空数据」→ 显式给 online=false（2026-11-06 裁决）。
        if (out == null) {
            JSONObject offline = new JSONObject();
            offline.put("online", false);
            offline.put("message", "角色未上线：上线后可读取移民资格与王国列表");
            return offline;
        }
        if (!out.has("online")) out.put("online", true);
        return out;
    }

    private JSONObject precheck(Map<String, Object> fields, String fpid) {
        RoleTarget target = resolveRole(fields, fpid, true);
        long targetKid = requireTargetKid(fields);
        boolean autoExit = boolValue(fields.get("auto_exit_alliance"), true);
        boolean recallBeforeOpen = boolValue(fields.get("recall_before_open"), true);
        boolean recallSpeedup = boolValue(fields.get("recall_speedup"), true);
        boolean recallDiamondSpeedup = boolValue(fields.get("recall_diamond_speedup"), true);
        int advance = MigrantJob.clampAdvance((int) longValue(fields.get("advance_millis"), 0L));
        return engine.precheck(target.toRoleKey(), targetKid, autoExit, recallBeforeOpen,
                recallSpeedup, recallDiamondSpeedup, advance);
    }

    private JSONObject testEarly(Map<String, Object> fields, String fpid) {
        RoleTarget target = resolveRole(fields, fpid, true);
        long targetKid = requireTargetKid(fields);
        return engine.testEarly(target.toRoleKey(), targetKid);
    }

    private JSONObject create(Map<String, Object> fields, String fpid) {
        RoleTarget target = resolveRole(fields, fpid, true);
        requireTargetKid(fields);
        MigrantJob job = engine.create(target.toRoleKey(), fields);
        return job.toJson();
    }

    /** 批量创建：逐角色独立，失败隔离。 */
    private JSONObject bulk(Map<String, Object> fields, String fpid) {
        requireTargetKid(fields);
        List<Long> roleIds = roleIdsOf(fields);
        if (roleIds.isEmpty()) throw new BizError(3002, "请选择移民账号");
        JSONArray created = new JSONArray();
        JSONArray failed = new JSONArray();
        for (Long roleId : roleIds) {
            try {
                RoleTarget target = resolveRoleByUid(fpid, roleId, true);
                MigrantJob job = engine.create(target.toRoleKey(), fields);
                created.put(job.jobId);
            } catch (RuntimeException e) {
                JSONObject item = new JSONObject();
                item.put("account_role_id", roleId);
                item.put("message", messageOf(e));
                failed.put(item);
            }
        }
        FileLog.info("移民危险动作 bulk fpid={} target_kid={} requested={} created={} failed={}",
                fpid, String.valueOf(longValue(fields.get("target_kid"), 0L)),
                String.valueOf(roleIds.size()), String.valueOf(created.length()),
                String.valueOf(failed.length()));
        JSONObject out = new JSONObject();
        out.put("requested", roleIds.size());
        out.put("created", created);
        out.put("failed", failed);
        return out;
    }

    private JSONObject login(Map<String, Object> fields, String fpid) {
        RoleTarget target = resolveRole(fields, fpid, false);
        return engine.login(target.toRoleKey());
    }

    /** 角色下线（网页版工作区头部「账号上线/下线」开关的在线态动作，2026-11-06）。 */
    private JSONObject logout(Map<String, Object> fields, String fpid) {
        RoleTarget target = resolveRole(fields, fpid, false);
        return engine.logout(target.toRoleKey());
    }

    // =====================================================================
    // 身份解析 / 权限
    // =====================================================================

    private String requireFpid(Map<String, Object> fields) {
        String fpid = str(fields, "fpid");
        if (fpid == null || fpid.trim().isEmpty()) throw new BizError(3002, "缺少 fpid");
        if (store.find(fpid) == null) throw new BizError(1004, "账号不存在：" + fpid);
        return fpid;
    }

    /** 角色寻址：payload account_role_id → 账号库角色（越权/不存在 1004）。 */
    private RoleTarget resolveRole(Map<String, Object> fields, String fpid, boolean requireOnline) {
        long roleUid = longValue(fields.get("account_role_id"), 0L);
        if (roleUid <= 0L) roleUid = longValue(fields.get("accountRoleId"), 0L);
        if (roleUid <= 0L) roleUid = longValue(fields.get("uid"), 0L);
        if (roleUid <= 0L) throw new BizError(3002, "请选择移民账号");
        return resolveRoleByUid(fpid, roleUid, requireOnline);
    }

    private RoleTarget resolveRoleByUid(String fpid, long roleUid, boolean requireOnline) {
        VaultStore.RoleEntry matched = null;
        for (VaultStore.RoleEntry entry : store.roles(fpid)) {
            if (entry == null || entry.uid == null || entry.uid.isEmpty()) continue;
            long uid;
            try {
                uid = Long.parseLong(entry.uid);
            } catch (RuntimeException e) {
                continue;
            }
            if (uid == roleUid) {
                matched = entry;
                break;
            }
        }
        if (matched == null) throw new BizError(1004, "角色不存在");
        long sid;
        try {
            sid = Long.parseLong(matched.sid);
        } catch (RuntimeException e) {
            throw new BizError(1004, "角色不存在");
        }
        if (sid <= 0L) throw new BizError(1004, "角色不存在");
        RoleKey role = new RoleKey(fpid, sid, roleUid);
        if (requireOnline && (keeper == null || !keeper.roleOnline(role))) {
            throw new BizError(1004, "角色未上线，请先上线该角色");
        }
        return new RoleTarget(role, matched.name);
    }

    private List<Long> roleIdsOf(Map<String, Object> fields) {
        List<Long> out = new ArrayList<>();
        Object raw = fields.get("account_role_ids");
        if (raw == null) raw = fields.get("accountRoleIds");
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) {
                long id = longValue(arr.opt(i), 0L);
                if (id > 0L && !out.contains(id)) out.add(id);
            }
        } else if (raw instanceof Iterable) {
            for (Object item : (Iterable<?>) raw) {
                long id = longValue(item, 0L);
                if (id > 0L && !out.contains(id)) out.add(id);
            }
        } else if (raw != null) {
            long id = longValue(raw, 0L);
            if (id > 0L) out.add(id);
        }
        return out;
    }

    private long requireTargetKid(Map<String, Object> fields) {
        long targetKid = longValue(fields.get("target_kid"), 0L);
        if (targetKid <= 0L) targetKid = longValue(fields.get("targetKid"), 0L);
        if (targetKid <= 0L) throw new BizError(3002, "请选择目标王国");
        if (targetKid > Integer.MAX_VALUE) {
            throw new BizError(3002, "目标王国 id 超出范围");
        }
        return targetKid;
    }

    // =====================================================================
    // 基础工具
    // =====================================================================

    private static String normalizeAction(String raw) {
        if (raw == null) return "";
        String action = raw.trim().toLowerCase().replace('_', '-').replace(' ', '-');
        if ("testearly".equals(action)) return "test-early";
        return action;
    }

    static String str(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static long longValue(Object value, long fallback) {
        return MigrantEngine.longValue(value, fallback);
    }

    static boolean boolValue(Object value, boolean fallback) {
        return MigrantEngine.boolValue(value, fallback);
    }

    private static String messageOf(Throwable e) {
        return MigrantEngine.normalizedMessage(e);
    }

    /** 角色寻址结果。 */
    private static final class RoleTarget {
        final RoleKey role;
        final String name;

        RoleTarget(RoleKey role, String name) {
            this.role = role;
            this.name = name == null ? "" : name;
        }

        RoleKey toRoleKey() {
            return role;
        }
    }
}
