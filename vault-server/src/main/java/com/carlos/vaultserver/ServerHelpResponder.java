package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 联盟互助自动响应（移植自 App 端 daily/HelpResponder，判定字段照抄原版 HelpRequest）：
 * 推送到达（钩子线程）只做解析+置脏；真正的请求动作由会话线程 tick 驱动（秒级响应，且与心跳/采集天然串行）。
 * 状态写 StateStore 的 mod:fpid:sid:alliance_help（与 App 端展示链路同键）。
 *
 * <p>R2 Phase 1（2026-10-29）：按角色 uid 隔离（同账号多角色并行在线时各角色独立互助）：
 * <ul>
 *   <li>旧 3 参构造器（roleUid=0）：完全维持 P0 行为，状态只写旧键
 *       {@code mod:<fpid>:<sid>:alliance_help}；</li>
 *   <li>角色构造器（roleUid&gt;0）：状态写 {@code mod:<fpid>:<sid>:<roleUid>:alliance_help}；
 *       若 activeRole.get()==true（主控角色写入方）则同时双写旧键（App 今日页读旧键），
 *       兄弟角色只写角色键、不覆盖旧键；读取时优先角色键，缺失回退旧键（迁移保留进度字段）。</li>
 *   <li>历史 settled 清理：旧路径行为不变；角色路径下仅 active 角色清理旧
 *       {@code srv:alliance_help_settled:<fpid>:<sid>} 脏键（1536 已不落盘，无需按 uid 建 settled）。</li>
 * </ul>
 *
 * <p>P0 2026-10-28 二次加固（生产实证 vault-20261028.log / monitor-20261028.jsonl）：
 * <ul>
 *   <li><b>1536 语义纠正</b>：1536 不是“今日互助次数已用完”，而是“服务端此刻没有可响应的互助请求”
 *       （网页版参考实现 DailyAllianceDonationService: {@code NO_AVAILABLE_HELP_CODE = 1536}，
 *       handleHelpError 对 1536 只做 {@code markHelpAllHandled(ownUid, Map.of(), 0) + helpWaiting()}，
 *       不落盘、不跨天）。旧实现把 1536 当每日上限并持久化 {@code srv:alliance_help_settled} 一整天，
 *       生产 2026-10-28 04:23（北京）首次 1536 后当天 18.5 小时 0 次互助请求。
 *       现改为：1536 → 标记当前可帮条目已处理（markHelpAllHandled 同款）+ 5s 短退避 + 继续等待推送；
 *       不再写 settled，并幂等清理历史遗留键。</li>
 *   <li><b>tick 不再每轮重放缓存 init</b>：旧实现每轮用登录快照 {@code notify_alliance_help_init}
 *       clear+重建请求表，会抹掉推送增量与乐观“已帮”标记（新请求永远帮不到、30s 反复请求、日志假成功）。
 *       现改为 seed-once：只有真正收到 init/update_all 推送（钩子路径）才全量替换；兜底轮询仅在
 *       “从未 seed 过”时用 notifyContent 初始化一次。</li>
 *   <li><b>成功判定只认服务端数据</b>：code==0 且 count/help_num&gt;0 才写“已响应 N 位”；
 *       code==0 但 count==0 不得假成功。</li>
 *   <li><b>单条唯一键改为 extra.help_id</b>（真实协议同一 owner+qid 可并发多条），缺失时回退
 *       {@code owner:qid}；update type=3 优先按 help_id 精确删除，兼容旧形状。</li>
 *   <li>busy 抢占失败保留 dirty；两次发送最小间隔 5s，避免推送洪峰把请求打成风暴。</li>
 * </ul>
 */
public final class ServerHelpResponder {

    static final Set<String> HELP_PROTOS = Set.of(
            "notify_alliance_help_init",
            "notify_alliance_help_update_all",
            "notify_alliance_help_update",
            "notify_alliance_help_auto_help");

    /** 失败退避：原版 10~15s（DailyAllianceDonationService.handleHelpError）。 */
    static final long HELP_BLOCK_MILLIS = 15_000L;
    /**
     * 1536（当前无可响应请求）短退避：网页版 helpWaiting 语义 = 本轮没有可做的东西，
     * 等下一次推送/轮询再试；不落盘、不跨天。
     */
    static final long HELP_NO_AVAILABLE_BLOCK_MILLIS = 5_000L;
    /** 未知码退避：15s，避免 30s 节奏反复打。 */
    static final long HELP_UNKNOWN_BLOCK_MILLIS = 15_000L;
    /** 联盟身份变化（1510/1511）退避：等联盟推送重新同步。 */
    static final long HELP_ALLIANCE_BLOCK_MILLIS = 60_000L;
    /** 无联盟/不可用（16873）退避：低频探测即可。 */
    static final long HELP_NO_ALLIANCE_BLOCK_MILLIS = 300_000L;
    static final long POLL_INTERVAL_MILLIS = 30_000L;
    /** 两次 req_alliance_help_all 的最小间隔：推送洪峰下（抓包可见 ~1s/条）防止请求风暴。 */
    static final long SEND_MIN_INTERVAL_MILLIS = 5_000L;

    /**
     * req_alliance_help_all 响应码 1536：服务端当前没有可响应的互助请求（瞬时；网页版 NO_AVAILABLE_HELP_CODE）。
     * <b>不是</b>“今日互助次数已用完”。
     */
    static final int HELP_CODE_NO_AVAILABLE = 1536;
    /** 无请求/不可帮（网页版按 SKIPPED 处理的码）。 */
    static final int HELP_CODE_NO_REQUEST = 75;
    static final int HELP_CODE_PARAM_ILLEGAL = 2;
    /** 联盟身份变化。 */
    static final int HELP_CODE_ALLIANCE_CHANGED = 1510;
    static final int HELP_CODE_ALLIANCE_CHANGED_2 = 1511;
    /** 未加入联盟。 */
    static final int HELP_CODE_NO_ALLIANCE = 16873;

    /** 响应码分类：成功。 */
    static final int HELP_RSP_OK = 0;
    /** 响应码分类：1536 当前无可响应请求（瞬时；只做本地已处理标记 + 短退避）。 */
    static final int HELP_RSP_NO_AVAILABLE = 1;
    /** 响应码分类：75/2 当前什么也帮不了（不算失败，不清空请求表）。 */
    static final int HELP_RSP_NO_REQUEST = 2;
    /** 响应码分类：联盟身份变化/无联盟（等重新同步）。 */
    static final int HELP_RSP_ALLIANCE = 3;
    /** 响应码分类：未知错误码（warn + 退避）。 */
    static final int HELP_RSP_UNKNOWN = 4;

    /**
     * 历史版本（92ae8de 那一版）误写的“1536 当日结算”键前缀。
     * 只用于首次 tick 幂等清理：否则改完代码当天仍会被旧键卡住。
     */
    static final String LEGACY_SETTLED_PREFIX = "srv:alliance_help_settled:";

    /** 一条互助请求（字段名与判定照抄原版 DailyAllianceDonationStateHelpRequest）。 */
    static final class HelpReq {
        final long owner;
        final long qid;
        /** 真实协议单条唯一标识（extra.help_id；同一 owner+qid 可并发多条）。 */
        final long helpId;
        final long endtime;
        final long max;
        final Set<Long> helpers = ConcurrentHashMap.newKeySet();

        HelpReq(long owner, long qid, long helpId, long endtime, long max) {
            this.owner = owner;
            this.qid = qid;
            this.helpId = helpId;
            this.endtime = endtime;
            this.max = max;
        }

        String key() {
            return helpId > 0 ? owner + ":" + qid + ":" + helpId : owner + ":" + qid;
        }

        boolean availableFor(long ownUid, long nowSec) {
            return ownUid > 0 && owner != ownUid && max > helpers.size()
                    && !helpers.contains(ownUid) && (endtime <= 0 || endtime > nowSec);
        }
    }

    private final String fpid;
    private final long sid;
    /** 角色 uid（Phase 1）：>0=按角色隔离状态键；<=0=旧/主控兼容路径（只写旧键）。 */
    private final long roleUid;
    /** 当前会话是否 active 角色写入方（SessionKeeper 提供；roleUid<=0 时不用；异常按 false）。 */
    private final Supplier<Boolean> activeRole;
    private final StateStore store;
    private final Map<String, HelpReq> helpRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicLong blockedUntil = new AtomicLong(0);
    private final AtomicLong lastPollAt = new AtomicLong(0);
    /** 上次真正发出 req_alliance_help_all 的时刻（最小发送间隔用）。 */
    private final AtomicLong lastSendAt = new AtomicLong(0);
    /** 是否已用 init/update_all 快照播种过请求表（seed-once，详见 tick）。 */
    private final AtomicBoolean seeded = new AtomicBoolean(false);
    /** 历史 settled 脏键是否已清理（幂等）。 */
    private final AtomicBoolean legacySettledCleared = new AtomicBoolean(false);
    volatile int helpNumToday;
    volatile long dayGlory;

    /** 旧 3 参构造器（P0/兼容路径）：roleUid=0、activeRole=null，状态只写旧键，行为与 P0 完全一致。 */
    ServerHelpResponder(String fpid, long sid, StateStore store) {
        this(fpid, sid, 0L, store, null);
    }

    /**
     * 按角色隔离的构造器（R2 Phase 1）。
     *
     * <p>状态键规则：
     * <ul>
     *   <li>roleUid&lt;=0：旧键 {@code mod:<fpid>:<sid>:alliance_help}（行为同旧 3 参构造器）；</li>
     *   <li>roleUid&gt;0：角色键 {@code mod:<fpid>:<sid>:<roleUid>:alliance_help}；
     *       activeRole.get()==true 时同时双写旧键（App 今日页读取旧键），兄弟角色只写角色键。</li>
     * </ul>
     *
     * @param roleUid    角色 uid（真实会话 uid；测试/兼容可传 0）
     * @param activeRole 当前是否为 active 角色写入方；null 视为 false；get() 抛异常按 false
     */
    ServerHelpResponder(String fpid, long sid, long roleUid, StateStore store,
                        Supplier<Boolean> activeRole) {
        this.fpid = fpid;
        this.sid = sid;
        this.roleUid = roleUid;
        this.store = store;
        this.activeRole = activeRole;
    }

    /** 旧键（App 今日页/现有链路读取）：mod:<fpid>:<sid>:alliance_help。 */
    static String legacyStateKey(String fpid, long sid) {
        return "mod:" + fpid + ":" + sid + ":alliance_help";
    }

    /** 角色隔离键：mod:<fpid>:<sid>:<roleUid>:alliance_help（roleUid<=0 退化为旧键）。 */
    static String roleStateKey(String fpid, long sid, long roleUid) {
        return roleUid > 0 ? "mod:" + fpid + ":" + sid + ":" + roleUid + ":alliance_help"
                : legacyStateKey(fpid, sid);
    }

    /** 本实例负责写入的状态键（角色键或旧键）。 */
    private String stateKey() {
        return roleStateKey(fpid, sid, roleUid);
    }

    /** 当前会话是否 active 角色写入方（旧路径恒 true；supplier 异常按 false）。 */
    private boolean isActiveRole() {
        if (roleUid <= 0) return true;
        if (activeRole == null) return false;
        try {
            return Boolean.TRUE.equals(activeRole.get());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 挂到引擎消息钩子（推送线程调用：只解析+置脏，不发请求）。 */
    void attach(WjdrEngine engine) {
        engine.addMessageHook((proto, content) -> {
            if (!HELP_PROTOS.contains(proto)) return;
            try {
                // 真实推送：init/update_all 全量替换；update 增量合并；auto_help 只更新进度。
                applyPush(proto, content, true);
                dirty.set(true);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 会话线程 tick 驱动：脏标记或 30s 兜底轮询时尝试响应。返回 true 表示本轮有动作。 */
    boolean tick(WjdrEngine engine) {
        return tick(engine, System.currentTimeMillis());
    }

    /** 可注入时钟的 tick（单测走这条，避免真的 sleep 30s）。 */
    boolean tick(WjdrEngine engine, long now) {
        clearLegacySettledOnce();
        boolean due = dirty.compareAndSet(true, false)
                || now - lastPollAt.get() >= POLL_INTERVAL_MILLIS;
        if (!due) return false;
        lastPollAt.set(now);
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return false;
        // 兜底：仅在“从未 seed 过”时用最近一次 help 快照初始化一次；之后绝不重放，
        // 否则会把推送增量与乐观已帮标记清掉（旧实现 bug）。
        seedFromCacheOnce(engine);
        try {
            maybeHelp(engine, now);
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ---------------- 历史 settled 脏键清理（幂等） ----------------

    private void clearLegacySettledOnce() {
        if (legacySettledCleared.get()) return;
        // Phase 1：角色路径下只由 active 角色清理旧脏键（兄弟角色不动旧键，避免互相覆盖语义）。
        if (roleUid > 0 && !isActiveRole()) return;
        if (!legacySettledCleared.compareAndSet(false, true)) return;
        removeLegacySettled(store, fpid, sid);
    }

    /** 历史版本误写的 1536 当日结算键（清理失败忽略；键不存在静默返回）。 */
    static void removeLegacySettled(StateStore store, String fpid, long sid) {
        if (store == null) return;
        try {
            store.remove(LEGACY_SETTLED_PREFIX + fpid + ":" + sid);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 快照播种（seed-once）与推送解析 ----------------

    private void seedFromCacheOnce(WjdrEngine engine) {
        if (seeded.get()) return;
        for (String p : new String[]{"notify_alliance_help_update_all", "notify_alliance_help_init"}) {
            Map<String, Object> c = engine.notifyContent(p);
            if (c != null) {
                applyPush(p, c, true);
                return;
            }
        }
        // 登录快照还没到：保持未 seed，等真正的 init 推送（钩子路径）或下一轮 tick。
    }

    /**
     * 解析并写入请求表。
     *
     * @param replace true=全量替换（真实 init/update_all 推送、首次播种）；
     *                false=增量合并（不 clear、不删“已帮”标记）
     */
    private void applyPush(String proto, Map<String, Object> content, boolean replace) {
        if (content == null) return;
        // 进度字段（今日已帮次数/互助荣耀）：init 的字段在 alliance_help 内层，update_auto_help 在本体。
        long helpNum = helpNumOf(content);
        long glory = dayGloryOf(content);
        if (helpNum > 0) helpNumToday = (int) Math.min(helpNum, Integer.MAX_VALUE);
        if (glory > 0) dayGlory = glory;

        if ("notify_alliance_help_update".equals(proto)) {
            long type = number(content.get("type"));
            Object data = content.get("data");
            if (type == 3) {
                for (HelpReq r : parseHelpRequests(data)) removeHelpRequest(r);
            } else if (type == 1 || type == 2) {
                for (HelpReq r : parseHelpRequests(data)) helpRequests.put(r.key(), r);
            }
            pruneExpired(System.currentTimeMillis() / 1000L);
            return;
        }
        if ("notify_alliance_help_auto_help".equals(proto)) {
            // 只有进度（help_num/day_glory），不含请求列表。
            return;
        }
        Object list = deepGet(content, 3, "help_list");
        if (list == null) return;
        if (replace) {
            helpRequests.clear();
            for (HelpReq r : parseHelpRequests(list)) helpRequests.put(r.key(), r);
        } else {
            for (HelpReq r : parseHelpRequests(list)) helpRequests.putIfAbsent(r.key(), r);
        }
        seeded.set(true);
        pruneExpired(System.currentTimeMillis() / 1000L);
    }

    /** type=3 删除：优先按 help_id 精确删除；只有旧形状（无 help_id）才退化为 owner:qid。 */
    private void removeHelpRequest(HelpReq r) {
        if (r.helpId > 0) {
            if (helpRequests.remove(r.key()) != null) return;
            // 同一 owner/qid 的旧键（无 help_id 入表的历史条目）顺带清理，但不误删其它并发请求。
            helpRequests.values().removeIf(x -> x.helpId <= 0 && x.owner == r.owner && x.qid == r.qid);
            return;
        }
        // 兼容旧形状：type=3 数据里没有 extra.help_id 时按 owner:qid 删除。
        helpRequests.values().removeIf(x -> x.owner == r.owner && x.qid == r.qid);
    }

    /** 过期请求清理（endtime 已过的不再保留，避免内存缓慢增长与误判）。 */
    private void pruneExpired(long nowSec) {
        try {
            helpRequests.values().removeIf(r -> r.endtime > 0 && r.endtime <= nowSec);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 纯解析函数（包内可见，便于单测；形状按真实协议 + 兼容旧包） ----------------

    /**
     * 解析互助请求列表（纯函数）：兼容 List/Map 容器、单条 {uid,qid}、以及
     * helpers 为 List/Map 两种形状；owner 字段优先 uid，其次 user_id/userId/id；
     * 单条唯一标识取 extra.help_id。
     */
    static List<HelpReq> parseHelpRequests(Object raw) {
        List<HelpReq> out = new ArrayList<>();
        for (Map<String, Object> m : helpEntries(raw)) {
            long owner = helpOwnerOf(m);
            long qid = helpQidOf(m);
            if (owner <= 0 || qid <= 0) continue;
            HelpReq r = new HelpReq(owner, qid, helpIdOf(m),
                    Math.max(0, number(firstOf(m, "endtime", "end_time", "endTime"))),
                    Math.max(0, number(firstOf(m, "max", "max_count", "maxCount"))));
            collectHelpers(r, m.get("helpers"));
            out.add(r);
        }
        return out;
    }

    /** 互助条目归一化（纯函数）：List → 各条；Map → 单条（含 uid/qid）或容器展开一层。 */
    static List<Map<String, Object>> helpEntries(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) raw;
            if (helpOwnerOf(castMap(m)) > 0 && helpQidOf(castMap(m)) > 0) {
                out.add(castMap(m));
                return out;
            }
            for (Object v : m.values()) {
                if (v instanceof Map) out.addAll(helpEntries(v));
                else if (v instanceof List) out.addAll(helpEntries(v));
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) out.add(castMap((Map<?, ?>) o));
            }
        }
        return out;
    }

    /** 请求发起人 uid（真实样本是 uid；兼容 user_id/userId/owner_uid/owner/id）。 */
    static long helpOwnerOf(Map<String, Object> m) {
        return number(firstOf(m, "uid", "user_id", "userId", "owner_uid", "owner", "id"));
    }

    /** 请求队列 id（真实样本是 qid；兼容 queue_id/queueId）。 */
    static long helpQidOf(Map<String, Object> m) {
        return number(firstOf(m, "qid", "queue_id", "queueId"));
    }

    /** 单条唯一标识：真实协议在 extra.help_id（同一 owner+qid 可并发多条）；兼容 helpId。 */
    static long helpIdOf(Map<String, Object> m) {
        if (m == null) return 0L;
        Object extra = m.get("extra");
        if (extra instanceof Map) {
            long id = number(firstOf(castMap((Map<?, ?>) extra), "help_id", "helpId"));
            if (id > 0) return id;
        }
        return number(firstOf(m, "help_id", "helpId"));
    }

    /** 今日互助进度：init 在 {alliance_help:{help_num}} 内层，update_auto_help 在本体。 */
    static long helpNumOf(Map<String, Object> content) {
        Map<String, Object> body = helpBody(content);
        return number(firstOf(body, "help_num", "helpNum"));
    }

    /** 今日互助荣耀：同上。 */
    static long dayGloryOf(Map<String, Object> content) {
        Map<String, Object> body = helpBody(content);
        return number(firstOf(body, "day_glory", "dayGlory"));
    }

    /** 互助进度本体：{alliance_help:{...}} 内层优先，其次 data/info 包装，最后原始 content。 */
    static Map<String, Object> helpBody(Map<String, Object> content) {
        if (content == null) return new LinkedHashMap<>();
        Object nested = content.get("alliance_help");
        if (!(nested instanceof Map)) nested = content.get("allianceHelp");
        if (nested instanceof Map) return castMap((Map<?, ?>) nested);
        for (String wrap : new String[]{"data", "info", "content"}) {
            Object w = content.get(wrap);
            if (w instanceof Map) {
                Map<String, Object> inner = castMap((Map<?, ?>) w);
                Object again = inner.get("alliance_help");
                if (again instanceof Map) return castMap((Map<?, ?>) again);
                return inner;
            }
        }
        return content;
    }

    /** req_alliance_help_all 响应码分类（纯函数，1:1 网页版 handleHelpError）。 */
    static int helpResponseKind(int code) {
        if (code == 0) return HELP_RSP_OK;
        if (code == HELP_CODE_NO_AVAILABLE) return HELP_RSP_NO_AVAILABLE;
        if (code == HELP_CODE_NO_REQUEST || code == HELP_CODE_PARAM_ILLEGAL) return HELP_RSP_NO_REQUEST;
        if (code == HELP_CODE_ALLIANCE_CHANGED || code == HELP_CODE_ALLIANCE_CHANGED_2
                || code == HELP_CODE_NO_ALLIANCE) return HELP_RSP_ALLIANCE;
        return HELP_RSP_UNKNOWN;
    }

    /** 按响应分类给退避时长（毫秒，0=不额外退避）。 */
    static long helpBlockMillis(int kind) {
        switch (kind) {
            case HELP_RSP_NO_AVAILABLE:
                return HELP_NO_AVAILABLE_BLOCK_MILLIS;
            case HELP_RSP_NO_REQUEST:
            case HELP_RSP_UNKNOWN:
                return HELP_UNKNOWN_BLOCK_MILLIS;
            case HELP_RSP_ALLIANCE:
                return HELP_ALLIANCE_BLOCK_MILLIS;
            default:
                return 0L;
        }
    }

    // ---------------- 响应（会话线程内执行） ----------------

    private void maybeHelp(WjdrEngine engine, long now) {
        if (now < blockedUntil.get()) {
            // 退避中：保留脏标记，退避到期后的下一轮 tick 立即重试（不必等 30s 兜底）。
            dirty.set(true);
            return;
        }
        // 最小发送间隔：推送洪峰（抓包 ~1s/条）下避免每来一条推送就打一次协议。
        if (now - lastSendAt.get() < SEND_MIN_INTERVAL_MILLIS) {
            dirty.set(true);
            return;
        }
        long nowSec = now / 1000L;
        long ownUid = engine.lastUid();
        List<HelpReq> available = new ArrayList<>();
        for (HelpReq r : helpRequests.values()) {
            if (r.availableFor(ownUid, nowSec)) available.add(r);
        }
        if (available.isEmpty()) {
            saveState(false, 0, "推送", "当前没有盟友需要互助");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            // 抢不到锁（上一个请求还在飞）：保留唤醒，下个 tick 再来，不丢事件。
            dirty.set(true);
            return;
        }
        try {
            lastSendAt.set(now);
            GameMessage rsp = engine.request("req_alliance_help_all",
                    new LinkedHashMap<String, Object>());
            int code = responseCode(rsp);
            int kind = helpResponseKind(code);
            if (kind == HELP_RSP_OK) {
                // 只认服务端 count/help_num：code=0 但 count=0 说明其实没帮到人，不得写“已响应 N 位”。
                long done = responseHelpNum(rsp);
                // 乐观更新：把本次可帮请求都视为已帮（网页版 markHelpAllHandled 同款）。
                for (HelpReq r : available) r.helpers.add(ownUid);
                if (done > 0) {
                    if (helpNumToday <= 0) helpNumToday = (int) Math.min(done, Integer.MAX_VALUE);
                    saveState(true, done, "推送", "已响应 " + done + " 位盟友互助");
                    FileLog.info("联盟互助 fpid={} sid={}：已响应 {} 位", fpid, String.valueOf(sid),
                            String.valueOf(done));
                } else {
                    saveState(false, 0, "推送", "当前没有可互助请求（响应成功但无实际帮助），继续等待推送");
                    FileLog.info("联盟互助 fpid={} sid={}：响应成功但无实际帮助（count=0）", fpid,
                            String.valueOf(sid));
                }
            } else if (kind == HELP_RSP_NO_AVAILABLE) {
                // 1536＝服务端此刻没有可响应请求（瞬时）。网页版语义 = markHelpAllHandled + helpWaiting：
                // 标记现有可帮条目已处理，避免同一批反复打；不落盘、不跨天、不停摆。
                for (HelpReq r : available) r.helpers.add(ownUid);
                saveState(false, 0, "推送", "当前没有可互助请求（码" + code + "），继续等待推送");
                FileLog.info("联盟互助 fpid={} sid={}：当前无可响应请求（码1536），继续等待推送", fpid,
                        String.valueOf(sid));
            } else if (kind == HELP_RSP_NO_REQUEST) {
                saveState(false, 0, "推送", "当前没有可互助请求（码" + code + "），继续等待推送");
            } else if (kind == HELP_RSP_ALLIANCE) {
                saveState(false, 0, "推送", "联盟状态异常/未加入联盟（码" + code + "），等待重新同步");
                FileLog.warn("联盟互助 fpid={} sid={}：联盟状态异常 code={}", fpid,
                        String.valueOf(sid), String.valueOf(code));
            } else {
                saveState(false, 0, "推送", "联盟互助暂不可用（码" + code + "），稍后重试");
                FileLog.warn("联盟互助 fpid={} sid={}：未知响应码 {}", fpid, String.valueOf(sid),
                        String.valueOf(code));
            }
            long block = helpBlockMillis(kind);
            if (block > 0) blockedUntil.set(now + block);
        } catch (Throwable t) {
            blockedUntil.set(System.currentTimeMillis() + HELP_BLOCK_MILLIS);
            saveState(false, 0, "推送", "联盟互助暂不可用，稍后重试");
            FileLog.warn("联盟互助 fpid={} sid={}：请求异常 {}", fpid, String.valueOf(sid),
                    String.valueOf(t.getMessage()));
        } finally {
            busy.set(false);
        }
    }

    /** 响应码：无 content 视为 -1（未知），有 content 无 code 视为 0（协议成功形状）。 */
    static int responseCode(GameMessage rsp) {
        if (rsp == null || rsp.getContent() == null) return -1;
        Object c = rsp.getContent().get("code");
        return c == null ? 0 : (int) number(c);
    }

    /** 响应里的今日已帮次数（help_num/count，取不到=0）。 */
    static long responseHelpNum(GameMessage rsp) {
        if (rsp == null || rsp.getContent() == null) return 0L;
        Map<String, Object> body = helpBody(rsp.getContent());
        return Math.max(0L, number(firstOf(body, "help_num", "helpNum", "count")));
    }

    /** 当前请求表大小（包内可见：只给单测断言 seed/增量/删除行为，不影响生产语义）。 */
    int pendingHelpCount() {
        return helpRequests.size();
    }

    /** 写互助状态行（与 App FnParams.saveModText 同构：文案 + _ts + _trg + 进度字段）。 */
    private void saveState(boolean acted, long done, String trigger, String text) {
        try {
            String key = stateKey();
            String legacy = legacyStateKey(fpid, sid);
            JSONObject mod = new JSONObject();
            // 读取：优先角色键；角色键缺失时回退旧键（迁移/切换时保留旧进度字段）。
            String raw = store.getString(key);
            if (raw == null && roleUid > 0 && !key.equals(legacy)) raw = store.getString(legacy);
            if (raw != null) mod = new JSONObject(raw);
            mod.put("help_all", text);
            mod.put("help_all_ts", System.currentTimeMillis());
            mod.put("help_all_trg", trigger);
            if (helpNumToday > 0) mod.put("help_num", helpNumToday);
            if (dayGlory > 0) mod.put("day_glory", dayGlory);
            String json = mod.toString();
            store.putString(key, json);
            // Phase 1：主控角色双写旧键（App 今日页读旧键）；兄弟角色只写角色键。
            if (roleUid > 0 && isActiveRole()) store.putString(legacy, json);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 取值辅助（形状防御式，与 App HelpResponder 同风格） ----------------

    private static void collectHelpers(HelpReq r, Object helpers) {
        if (helpers instanceof List) {
            for (Object h : (List<?>) helpers) {
                long uid = number(h);
                if (uid > 0) r.helpers.add(uid);
            }
        } else if (helpers instanceof Map) {
            // 兼容 {uid: {...}} / {uid: uid} 两种 Map 形状
            for (Map.Entry<?, ?> en : ((Map<?, ?>) helpers).entrySet()) {
                long uid = number(en.getValue());
                if (uid <= 0) uid = number(en.getKey());
                if (uid > 0) r.helpers.add(uid);
            }
        }
    }

    private static long number(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(o).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 依次取首个数值字段（同层直取，不递归包装）。 */
    private static Object firstOf(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number) return v;
        }
        // 某些协议字段是字符串数字
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && number(v) > 0) return v;
        }
        return null;
    }

    private static Object deepGet(Map<String, Object> m, int depth, String key) {
        if (m == null || depth < 0) return null;
        Object v = m.get(key);
        if (v != null) return v;
        for (Object w : m.values()) {
            if (w instanceof Map) {
                Object r = deepGet(castMap((Map<?, ?>) w), depth - 1, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
