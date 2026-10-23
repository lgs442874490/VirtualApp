package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.formation.FormationLibrary;
import com.carlos.vaultserver.formation.FormationResolver;
import com.carlos.vaultserver.formation.ResolvedFormation;
import com.carlos.vaultserver.task.Exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 守护盟友 P0 发射器：把一次"驻防任务"翻译成游戏协议请求（批次9，2026-10-23）。
 *
 * <p><b>范围（P0）</b>：只做 type=5 驻防发起（A-2 三变体自发现）与撤防（在途召回 / 已驻扎撤回），
 * 不做卡秒等待、不做加速链（P1）；不发任何花钻石请求。协议口径全部来自实证文档：
 * <ul>
 *   <li>{@code design/守护盟友-协议实证-20261015.md:65-74}：驻防行军 type=5 / target_type=1；
 *       落地后城主收 {@code notify_embassy_world_march_add}（服务器侧 0 条，不可依赖）；</li>
 *   <li>{@code app/.../guardian/GuardianLaunch.java:664-687}：A-2 变体自发现
 *       {@code req_world_march{type:5, endpoint, formation, extra:{}}}
 *       （extra 空 / {@code target_uid} / {@code param1} 三选一，code=0 即锁定）；</li>
 *   <li>{@code design/守护盟友-会话交接-20261017.md:29-40}：撤防在途
 *       {@code req_world_march_recall{id}}、已驻扎 {@code req_world_march_off_work{id}}；
 *       {@code embassy_repatriation} 非此用途（实测空操作/2894）。</li>
 * </ul>
 *
 * <p><b>安全约束</b>：每个威胁最多一次尝试（变体最多 3 个请求，属于同一次 A-2 自发现，不是重试风暴）；
 * 任何失败只回传原始业务码由上层记账，本类不重试、不循环、不消耗钻石。
 */
public final class ServerGuardianLauncher {

    // ---------------- 协议常量（实证口径，勿凭猜测改） ----------------

    /** 驻防（增援）行军 type（A-1 实证：type=5, target_type=1）。 */
    static final long MARCH_TYPE_GARRISON = 5L;
    /** 世界行军发起协议（A-2；App GuardianLaunch 同款）。 */
    static final String PROTO_WORLD_MARCH = "req_world_march";
    /** 在途行军召回（A-11 撤防：state=2 在途）。 */
    static final String PROTO_MARCH_RECALL = "req_world_march_recall";
    /** 已驻扎行军撤回（A-11 撤防：state=3 驻扎，4/4 实测 code=0）。 */
    static final String PROTO_MARCH_OFF_WORK = "req_world_march_off_work";

    /** A-2 变体：extra 为空。 */
    static final int VARIANT_EMPTY = 0;
    /** A-2 变体：extra.target_uid = 盟友 uid。 */
    static final int VARIANT_TARGET_UID = 1;
    /** A-2 变体：extra.param1 = 盟友 uid。 */
    static final int VARIANT_PARAM1 = 2;
    /** 变体总数。 */
    static final int VARIANT_COUNT = 3;

    // ---------------- 业务码（实证/交接文档；原码透传记账） ----------------

    static final int CODE_OK = 0;
    /** 队列满（App：2878 → 仲裁腾位；服务器 P0 只记录不重试）。 */
    static final int CODE_QUEUE_FULL = 2878;
    /** 英雄不在城（随集结/驻防出征）。 */
    static final int CODE_HERO_AWAY = 301;
    /** 目标不同盟（help_must_same_alliance）。 */
    static final int CODE_NOT_SAME_ALLIANCE_2815 = 2815;
    /** 目标不同盟（not_in_same_alliance）。 */
    static final int CODE_NOT_SAME_ALLIANCE_1510 = 1510;
    /** 同城已有我方援助（弱驻防；P0 跳过不 replace）。 */
    static final int CODE_WEAK_GARRISON = 2814;
    /** 行军不存在（加速/召回时目标已消失）。 */
    static final int CODE_MARCH_NOT_FOUND = 2806;
    /** 请求异常/无响应（本地标记，非游戏码）。 */
    static final int CODE_LOCAL_ERROR = -1;

    /** 变体锁定（-1=未锁定；A-2 成功一次后进程内复用，避免每条威胁都试 3 次）。 */
    private volatile int lockedVariant = -1;

    /** 一次发射尝试的结果（成功或失败都回传原始码，供上层 ledger 记账）。 */
    public static final class LaunchResult {
        /** 是否 code=0 且拿到行军 id/endtime。 */
        public final boolean ok;
        /** 原始业务码（失败时保留原码；请求异常=-1）。 */
        public final int code;
        /** 我方行军 id（失败=0）。 */
        public final long marchId;
        /** 我方到达时间（服务器秒；取不到=0）。 */
        public final long endtimeSec;
        /** 本次命中的 A-2 变体下标（-1=未尝试）。 */
        public final int variant;
        /** 结果分类文案（日志/账本用）。 */
        public final String text;

        LaunchResult(boolean ok, int code, long marchId, long endtimeSec, int variant, String text) {
            this.ok = ok;
            this.code = code;
            this.marchId = marchId;
            this.endtimeSec = endtimeSec;
            this.variant = variant;
            this.text = text;
        }
    }

    /**
     * 发起一次 type=5 驻防（单次尝试：按变体顺序各发 1 个请求，code=0 即锁定变体）。
     *
     * @param x         目标盟友城坐标 x
     * @param y         目标盟友城坐标 y
     * @param formation 编队（{@code Exec.formation} 构造；null/空=失败）
     * @param targetUid 盟友 uid（0=未知，仍可仅按坐标驻防）
     * @return 结果（不重试；失败原码透传）
     */
    public LaunchResult launch(WjdrEngine engine, int x, int y,
                               Map<String, Object> formation, long targetUid) {
        if (engine == null || formation == null || formation.isEmpty() || x <= 0 || y <= 0) {
            return new LaunchResult(false, CODE_LOCAL_ERROR, 0L, 0L, -1, "参数不完整（坐标/编队缺失）");
        }
        int start = lockedVariant >= 0 ? lockedVariant : 0;
        int end = lockedVariant >= 0 ? lockedVariant : VARIANT_COUNT - 1;
        LaunchResult last = null;
        for (int vi = start; vi <= end; vi++) {
            Map<String, Object> args = buildLaunchArgs(x, y, formation, targetUid, vi);
            GameMessage rsp = null;
            int code;
            try {
                rsp = engine.request(PROTO_WORLD_MARCH, args);
                code = Exec.codeOf(rsp);
            } catch (Throwable t) {
                return new LaunchResult(false, CODE_LOCAL_ERROR, 0L, 0L, vi,
                        "请求异常：" + brief(t.getMessage()));
            }
            long marchId = marchIdOf(rsp);
            long endtime = endtimeOf(rsp);
            if (code == CODE_OK) {
                if (marchId <= 0) {
                    // code=0 但拿不到行军 id：按失败记账（宁可不发，绝不猜测成功）
                    return new LaunchResult(false, CODE_OK, 0L, endtime, vi, "响应缺 marchId");
                }
                lockedVariant = vi;
                return new LaunchResult(true, CODE_OK, marchId, endtime, vi,
                        "type=5 变体" + (vi + 1) + " code=0 marchId=" + marchId + " endtime=" + endtime);
            }
            last = new LaunchResult(false, code, marchId, endtime, vi, classifyCodeText(code));
            if (lockedVariant >= 0) break;   // 已锁定变体：只试该变体一次
        }
        return last != null ? last
                : new LaunchResult(false, CODE_LOCAL_ERROR, 0L, 0L, -1, "未尝试任何变体");
    }

    /**
     * 构造 type=5 驻防请求参数（A-2 三变体；纯函数便于单测）。
     *
     * <p>变体口径（App 实证 {@code GuardianLaunch.java:664-687}）：
     * <pre>
     *   variant=EMPTY       extra = {}
     *   variant=TARGET_UID  extra = {"target_uid": targetUid}（targetUid>0 时）
     *   variant=PARAM1      extra = {"param1": targetUid}（targetUid>0 时）
     * </pre>
     */
    static Map<String, Object> buildLaunchArgs(int x, int y, Map<String, Object> formation,
                                               long targetUid, int variant) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (variant == VARIANT_TARGET_UID && targetUid > 0) {
            extra.put("target_uid", targetUid);
        } else if (variant == VARIANT_PARAM1 && targetUid > 0) {
            extra.put("param1", targetUid);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", MARCH_TYPE_GARRISON);
        args.put("endpoint", Exec.point(x, y));
        args.put("formation", formation);
        args.put("extra", extra);
        return args;
    }

    /** 撤防参数：在途召回 / 已驻扎撤回（纯函数便于单测）。 */
    static Map<String, Object> buildMarchIdArgs(long marchId) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (marchId > 0) args.put("id", marchId);
        return args;
    }

    /**
     * 撤防协议选择（纯函数）：我方行军仍在途（服务器时间早于 endtime）→ 召回；否则已驻扎 → off_work。
     * 依据 {@code design/守护盟友-会话交接-20261017.md:29-40}（off_work 对 state=3 实测 code=0）。
     */
    static String recallProtoFor(boolean inFlight) {
        return inFlight ? PROTO_MARCH_RECALL : PROTO_MARCH_OFF_WORK;
    }

    /** 我方行军是否仍在途（服务器秒域）：nowSec < 到达时刻=在途；未知 endtime 按"已驻扎"处理（保守撤回）。 */
    static boolean inFlightAt(long endtimeSec, long nowSec) {
        return endtimeSec > 0 && nowSec < endtimeSec;
    }

    /** 从 {@code req_world_march} 响应取我方行军 id（{@code march_info.id}；防御式，不猜测）。 */
    static long marchIdOf(GameMessage rsp) {
        try {
            return Exec.marchId(rsp);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 从响应取我方到达时刻（服务器秒）：实证形状 `{"code":0,"march_info":{"id":..,"endtime":..,"state":2,"type":5}}`；
     * 兼容 `endtime/end_time/endTime` 与 `march`/`data` 包装层；取不到返回 0。
     */
    static long endtimeOf(GameMessage rsp) {
        if (rsp == null || rsp.getContent() == null) return 0L;
        return endtimeOfMap(rsp.getContent(), 0);
    }

    @SuppressWarnings("unchecked")
    static long endtimeOfMap(Map<String, Object> content, int depth) {
        if (content == null || depth > 3) return 0L;
        for (String k : new String[]{"endtime", "end_time", "endTime"}) {
            Object v = content.get(k);
            long n = Exec.longOf(v);
            if (n > 0) return n;
        }
        for (String k : new String[]{"march_info", "march", "data"}) {
            Object v = content.get(k);
            if (v instanceof Map) {
                long n = endtimeOfMap((Map<String, Object>) v, depth + 1);
                if (n > 0) return n;
            }
        }
        return 0L;
    }

    /** 原始业务码 → 可读分类（日志/账本；不影响判定）。 */
    static String classifyCodeText(int code) {
        switch (code) {
            case CODE_OK:
                return "成功";
            case CODE_QUEUE_FULL:
                return "队列已满";
            case CODE_HERO_AWAY:
                return "英雄不在城";
            case CODE_NOT_SAME_ALLIANCE_2815:
            case CODE_NOT_SAME_ALLIANCE_1510:
                return "目标不同盟";
            case CODE_WEAK_GARRISON:
                return "同城已有我方援助";
            case CODE_MARCH_NOT_FOUND:
                return "行军不存在";
            case CODE_LOCAL_ERROR:
                return "本地/请求异常";
            default:
                return "业务码" + code;
        }
    }

    /** 已锁定的 A-2 变体（-1=未锁定；诊断/单测用）。 */
    int lockedVariant() {
        return lockedVariant;
    }

    /** 单测用：重置变体锁定。 */
    void resetVariant() {
        lockedVariant = -1;
    }

    // =====================================================================
    // 批次10：统一编队接入（task-32）——type=5 驻防编队
    // 1:1 参考网页版 DailyMarchFormationResolver（AUTOMATIC/ROLE 两模式；库缺失/编队不存在
    // /英雄无效/兵量超库存 → SKIP，不静默回退）+ FormationResolver 冻结契约。
    // =====================================================================

    /** 编队解析结果：configured=false 表示无编队参数（调用方保持旧行为）。 */
    public static final class FormationResolution {
        public final boolean configured;
        public final Map<String, Object> payload;
        public final String label;
        public final String reason;

        FormationResolution(boolean configured, Map<String, Object> payload, String label, String reason) {
            this.configured = configured;
            this.payload = payload;
            this.label = label == null ? "" : label;
            this.reason = reason == null ? "" : reason;
        }

        public boolean ready() {
            return payload != null && !payload.isEmpty();
        }
    }

    /**
     * 解析 type=5 驻防编队（批次10）：
     * <ul>
     *   <li>无任何编队参数 → configured=false（调用方走旧 hero_ids/squad_index/队1，零行为变化）；</li>
     *   <li>显式编队参数 → FormationResolver 解析；失败 → configured=true + payload=null + 可读原因（SKIP）；</li>
     *   <li>保留 squad_index 兼容：由 FormationResolver 的 FALLBACK 分支覆盖（无参数时）。</li>
     * </ul>
     *
     * @param formationLibraryJson App 同步的 formation_library JSON（可为空=无库）
     */
    public static FormationResolution resolveFormation(WjdrEngine engine, Map<String, Object> fnParams,
                                                       String formationLibraryJson) {
        if (!FormationResolver.isConfigured(fnParams)) {
            return new FormationResolution(false, null, "旧编队", "无编队参数");
        }
        try {
            FormationLibrary lib = FormationLibrary.parse(formationLibraryJson);
            FormationResolver.ResolveOutcome out = FormationResolver.resolveOutcome(engine, lib, fnParams);
            if (!out.ready()) {
                return new FormationResolution(true, null, "统一编队", out.reason);
            }
            ResolvedFormation resolved = out.formation;
            Map<String, Object> payload = resolved.toFormationPayload();
            return new FormationResolution(true, payload, resolved.describe(),
                    payload == null ? "编队英雄/兵种为空" : "");
        } catch (Throwable t) {
            return new FormationResolution(true, null, "统一编队",
                    "编队解析异常：" + brief(String.valueOf(t.getMessage())));
        }
    }

    /** JSONObject → Map 深转换（Engine 配置解析用；FormationResolver 契约入参是 Map）。 */
    public static Map<String, Object> toParamMap(org.json.JSONObject p) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) return out;
        java.util.Iterator<String> it = p.keys();
        while (it.hasNext()) {
            String key = it.next();
            out.put(key, toJavaValue(p.opt(key)));
        }
        return out;
    }

    private static Object toJavaValue(Object v) {
        if (v instanceof org.json.JSONObject) return toParamMap((org.json.JSONObject) v);
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) v;
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(toJavaValue(arr.opt(i)));
            return list;
        }
        return v;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
