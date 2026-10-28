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

/**
 * 联盟互助自动响应（移植自 App 端 daily/HelpResponder，判定字段照抄原版 HelpRequest）：
 * 推送到达（钩子线程）只做解析+置脏；真正的请求动作由会话线程 tick 驱动（秒级响应，且与心跳/采集天然串行）。
 * 状态写 StateStore 的 mod:fpid:sid:alliance_help（与 App 端展示链路同键）。
 *
 * <p>P0 2026-10-28 加固（生产实证 vault-20261028.log）：
 * <ul>
 *   <li>响应码语义：req_alliance_help_all 返回非 0 时旧实现仍按成功写“已响应 N 位”，
 *       1536（今日互助次数用完）也没有 settled，导致每 30s 反复请求且状态误导。
 *       现按码分流：0 成功 / 1536 今日结算（持久化到 srv:alliance_help_settled，跨天自动恢复）/
 *       75、2 无可帮 / 1510、1511 联盟身份变化 / 其它未知码 warn + 退避；</li>
 *   <li>进度字段：notify_alliance_help_init 的 help_num/day_glory 在 alliance_help 内层
 *       （proto 实证：{"alliance_help":{"help_list":[...],"day_glory":1700,"help_num":0}}），
 *       旧实现只找 data/info 包装 → App 今日互助次数永远不显示；</li>
 *   <li>形状兼容：owner 字段兼容 uid/user_id/id（真实样本是 uid，旧包/部分协议可能不同），
 *       helpers 兼容 List/Map，help_list 兼容 {alliance_help:{...}} 与裸 help_list 两种形状。</li>
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
    /** 未知码退避：同上，保持 15s 以免 30s 节奏反复打。 */
    static final long HELP_UNKNOWN_BLOCK_MILLIS = 15_000L;
    /** 联盟身份变化（1510/1511）退避：等联盟推送重新同步。 */
    static final long HELP_ALLIANCE_BLOCK_MILLIS = 60_000L;
    /** 无联盟/不可用（16873）退避：低频探测即可。 */
    static final long HELP_NO_ALLIANCE_BLOCK_MILLIS = 300_000L;
    static final long POLL_INTERVAL_MILLIS = 30_000L;

    /** req_alliance_help_all 响应码 1536：今日互助次数已用完（原版 handleHelpError 同码）。 */
    static final int HELP_CODE_DAILY_LIMIT = 1536;
    /** 无请求/不可帮（原版按 SKIPPED 处理的码）。 */
    static final int HELP_CODE_NO_REQUEST = 75;
    static final int HELP_CODE_PARAM_ILLEGAL = 2;
    /** 联盟身份变化。 */
    static final int HELP_CODE_ALLIANCE_CHANGED = 1510;
    static final int HELP_CODE_ALLIANCE_CHANGED_2 = 1511;
    /** 未加入联盟。 */
    static final int HELP_CODE_NO_ALLIANCE = 16873;

    /** 响应码分类：成功。 */
    static final int HELP_RSP_OK = 0;
    /** 响应码分类：今日互助次数已用完（需 settled）。 */
    static final int HELP_RSP_DAILY_LIMIT = 1;
    /** 响应码分类：当前无可帮请求（不算失败，不清空请求表）。 */
    static final int HELP_RSP_NO_REQUEST = 2;
    /** 响应码分类：联盟身份变化/无联盟（等重新同步）。 */
    static final int HELP_RSP_ALLIANCE = 3;
    /** 响应码分类：未知错误码（warn + 退避）。 */
    static final int HELP_RSP_UNKNOWN = 4;

    /** 一条互助请求（字段名与判定照抄原版 DailyAllianceDonationStateHelpRequest）。 */
    static final class HelpReq {
        final long owner;
        final long qid;
        final long endtime;
        final long max;
        final Set<Long> helpers = ConcurrentHashMap.newKeySet();

        HelpReq(long owner, long qid, long endtime, long max) {
            this.owner = owner;
            this.qid = qid;
            this.endtime = endtime;
            this.max = max;
        }

        String key() {
            return owner + ":" + qid;
        }

        boolean availableFor(long ownUid, long nowSec) {
            return ownUid > 0 && owner != ownUid && max > helpers.size()
                    && !helpers.contains(ownUid) && (endtime <= 0 || endtime > nowSec);
        }
    }

    private final String fpid;
    private final long sid;
    private final StateStore store;
    private final Map<String, HelpReq> helpRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicLong blockedUntil = new AtomicLong(0);
    private final AtomicLong lastPollAt = new AtomicLong(0);
    volatile int helpNumToday;
    volatile long dayGlory;

    ServerHelpResponder(String fpid, long sid, StateStore store) {
        this.fpid = fpid;
        this.sid = sid;
        this.store = store;
    }

    /** 挂到引擎消息钩子（推送线程调用：只解析+置脏，不发请求）。 */
    void attach(WjdrEngine engine) {
        engine.addMessageHook((proto, content) -> {
            if (!HELP_PROTOS.contains(proto)) return;
            try {
                applyPush(proto, content);
                dirty.set(true);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 会话线程 tick 驱动：脏标记或 30s 兜底轮询时尝试响应。返回 true 表示本轮有动作。 */
    boolean tick(WjdrEngine engine) {
        long now = System.currentTimeMillis();
        boolean due = dirty.compareAndSet(true, false)
                || now - lastPollAt.get() >= POLL_INTERVAL_MILLIS;
        if (!due) return false;
        lastPollAt.set(now);
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return false;
        // 兜底：用最近一次 help 推送内容重算（推送丢失时的保险）
        for (String p : new String[]{"notify_alliance_help_update_all", "notify_alliance_help_init"}) {
            Map<String, Object> c = engine.notifyContent(p);
            if (c != null) {
                applyPush(p, c);
                break;
            }
        }
        try {
            maybeHelp(engine, now);
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ---------------- 推送解析（与 App HelpResponder.applyPush 一致 + P0 形状加固） ----------------

    private void applyPush(String proto, Map<String, Object> content) {
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
                for (HelpReq r : parseHelpRequests(data)) helpRequests.remove(r.key());
            } else if (type == 1 || type == 2) {
                for (HelpReq r : parseHelpRequests(data)) helpRequests.put(r.key(), r);
            }
            pruneExpired(System.currentTimeMillis() / 1000L);
            return;
        }
        Object list = deepGet(content, 3, "help_list");
        if (list != null) {
            helpRequests.clear();
            for (HelpReq r : parseHelpRequests(list)) helpRequests.put(r.key(), r);
        }
        pruneExpired(System.currentTimeMillis() / 1000L);
    }

    /** 过期请求清理（endtime 已过/已达上限的请求不再保留，避免内存缓慢增长与误判）。 */
    private void pruneExpired(long nowSec) {
        try {
            helpRequests.values().removeIf(r -> r.endtime > 0 && r.endtime <= nowSec);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 纯解析函数（包内可见，便于单测；形状按真实协议 + 兼容旧包） ----------------

    /**
     * 解析互助请求列表（纯函数）：兼容 List/Map 容器、单条 {uid,qid}、以及
     * helpers 为 List/Map 两种形状；owner 字段优先 uid，其次 user_id/userId/id。
     */
    static List<HelpReq> parseHelpRequests(Object raw) {
        List<HelpReq> out = new ArrayList<>();
        for (Map<String, Object> m : helpEntries(raw)) {
            long owner = helpOwnerOf(m);
            long qid = helpQidOf(m);
            if (owner <= 0 || qid <= 0) continue;
            HelpReq r = new HelpReq(owner, qid,
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

    /** 请求发起人 uid（真实样本是 uid；兼容 user_id/userId/id/owner/owner_uid）。 */
    static long helpOwnerOf(Map<String, Object> m) {
        return number(firstOf(m, "uid", "user_id", "userId", "owner_uid", "owner", "id"));
    }

    /** 请求队列 id（真实样本是 qid；兼容 queue_id/queueId/help_id）。 */
    static long helpQidOf(Map<String, Object> m) {
        return number(firstOf(m, "qid", "queue_id", "queueId"));
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
        if (code == HELP_CODE_DAILY_LIMIT) return HELP_RSP_DAILY_LIMIT;
        if (code == HELP_CODE_NO_REQUEST || code == HELP_CODE_PARAM_ILLEGAL) return HELP_RSP_NO_REQUEST;
        if (code == HELP_CODE_ALLIANCE_CHANGED || code == HELP_CODE_ALLIANCE_CHANGED_2
                || code == HELP_CODE_NO_ALLIANCE) return HELP_RSP_ALLIANCE;
        return HELP_RSP_UNKNOWN;
    }

    /** 按响应分类给退避时长（毫秒，0=不额外退避）。 */
    static long helpBlockMillis(int kind) {
        switch (kind) {
            case HELP_RSP_DAILY_LIMIT:
            case HELP_RSP_NO_REQUEST:
                return HELP_BLOCK_MILLIS;
            case HELP_RSP_ALLIANCE:
                return HELP_ALLIANCE_BLOCK_MILLIS;
            case HELP_RSP_UNKNOWN:
                return HELP_UNKNOWN_BLOCK_MILLIS;
            default:
                return 0L;
        }
    }

    /** 今日互助结算键（1536 记一天，跨天自动恢复；持久化到 StateStore）。 */
    static String helpSettledKey(String fpid, long sid) {
        return "srv:alliance_help_settled:" + fpid + ":" + sid;
    }

    /** 北京时间自然日（统一走 Beijing 时区，避免服务器 UTC 跨日错位）。 */
    static String beijingDay(long nowMillis) {
        return Beijing.fmt("yyyy-MM-dd").format(new java.util.Date(nowMillis));
    }

    /** 今日是否已结算（纯函数）：存的是北京自然日，跨天自动失效。 */
    static boolean helpSettledToday(String storedDay, long nowMillis) {
        return storedDay != null && !storedDay.isEmpty()
                && storedDay.equals(beijingDay(nowMillis));
    }

    /** 任务层兜底路径：今日互助 1536 结算写盘（与常驻响应器同键，跨天自动恢复）。 */
    public static void markHelpSettled(StateStore store, String fpid, long sid, long nowMillis) {
        if (store == null) return;
        try {
            store.putString(helpSettledKey(fpid, sid), beijingDay(nowMillis));
        } catch (Throwable ignored) {
        }
    }

    /** 任务层兜底路径：读取今日互助是否已结算（无记录/跨天=false）。 */
    public static boolean helpSettled(StateStore store, String fpid, long sid, long nowMillis) {
        if (store == null) return false;
        try {
            return helpSettledToday(store.getString(helpSettledKey(fpid, sid)), nowMillis);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 响应（会话线程内执行） ----------------

    private void maybeHelp(WjdrEngine engine, long now) {
        if (now < blockedUntil.get()) return;
        // 1536 已结算：今日不再请求（避免 30s 反复打），跨天自动恢复；状态始终保持可读
        // （放在“无请求”判断之前，避免下次巡检把 settled 文案覆盖成“当前没有盟友需要互助”）。
        if (helpSettledToday(readSettledDay(), now)) {
            saveState(false, 0, "推送", "今日联盟互助次数已用完（码" + HELP_CODE_DAILY_LIMIT + "），明天自动继续");
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
        if (!busy.compareAndSet(false, true)) return;
        try {
            GameMessage rsp = engine.request("req_alliance_help_all",
                    new java.util.LinkedHashMap<String, Object>());
            int code = responseCode(rsp);
            int kind = helpResponseKind(code);
            if (kind == HELP_RSP_OK) {
                long done = Math.max(available.size(), responseHelpNum(rsp));
                // 乐观更新：把本次可帮请求都视为已帮（原版 markHelpAllHandled 同款）
                for (HelpReq r : available) r.helpers.add(ownUid);
                if (done > 0 && helpNumToday <= 0) helpNumToday = (int) Math.min(done, Integer.MAX_VALUE);
                saveState(true, done, "推送", "已响应 " + done + " 位盟友互助");
                FileLog.info("联盟互助 fpid={} sid={}：已响应 {} 位", fpid, String.valueOf(sid),
                        String.valueOf(done));
            } else if (kind == HELP_RSP_DAILY_LIMIT) {
                // 1536=今日互助次数用完：结算到今天，清掉过期可帮标记（原版 markHelpAllHandled）。
                storeSettledDay(now);
                for (HelpReq r : available) r.helpers.add(ownUid);
                saveState(false, 0, "推送", "今日联盟互助次数已用完（码" + code + "），明天自动继续");
                FileLog.info("联盟互助 fpid={} sid={}：今日次数已用完（1536）", fpid, String.valueOf(sid));
            } else if (kind == HELP_RSP_NO_REQUEST) {
                saveState(false, 0, "推送", "当前没有可互助请求（码" + code + "）");
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
            if (block > 0 && kind != HELP_RSP_NO_REQUEST) {
                blockedUntil.set(System.currentTimeMillis() + block);
            }
        } catch (Throwable t) {
            blockedUntil.set(System.currentTimeMillis() + HELP_BLOCK_MILLIS);
            saveState(false, 0, "推送", "联盟互助暂不可用，稍后重试");
            FileLog.warn("联盟互助 fpid={} sid={}：请求异常 {}", fpid, String.valueOf(sid),
                    String.valueOf(t.getMessage()));
        } finally {
            busy.set(false);
        }
    }

    /** 读今日结算日（空=未结算/无记录）。 */
    private String readSettledDay() {
        try {
            return store.getString(helpSettledKey(fpid, sid));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 记今日结算（北京自然日）。 */
    private void storeSettledDay(long nowMillis) {
        try {
            store.putString(helpSettledKey(fpid, sid), beijingDay(nowMillis));
        } catch (Throwable ignored) {
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

    /** 写互助状态行（与 App FnParams.saveModText 同构：文案 + _ts + _trg + 进度字段）。 */
    private void saveState(boolean acted, long done, String trigger, String text) {
        try {
            String key = "mod:" + fpid + ":" + sid + ":alliance_help";
            JSONObject mod = new JSONObject();
            String raw = store.getString(key);
            if (raw != null) mod = new JSONObject(raw);
            mod.put("help_all", text);
            mod.put("help_all_ts", System.currentTimeMillis());
            mod.put("help_all_trg", trigger);
            if (helpNumToday > 0) mod.put("help_num", helpNumToday);
            if (dayGlory > 0) mod.put("day_glory", dayGlory);
            store.putString(key, mod.toString());
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
