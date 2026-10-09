package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONObject;

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
 */
public final class ServerHelpResponder {

    static final Set<String> HELP_PROTOS = Set.of(
            "notify_alliance_help_init",
            "notify_alliance_help_update_all",
            "notify_alliance_help_update",
            "notify_alliance_help_auto_help");

    private static final long HELP_BLOCK_MILLIS = 15_000L;
    private static final long POLL_INTERVAL_MILLIS = 30_000L;

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

    // ---------------- 推送解析（与 App HelpResponder.applyPush 一致） ----------------

    private void applyPush(String proto, Map<String, Object> content) {
        if (content == null) return;
        long helpNum = num(firstNum(content, 3, "help_num"));
        long dayGlory = num(firstNum(content, 3, "day_glory"));
        if (helpNum > 0) helpNumToday = (int) Math.min(helpNum, Integer.MAX_VALUE);
        if (dayGlory > 0) this.dayGlory = dayGlory;

        if ("notify_alliance_help_update".equals(proto)) {
            long type = num(content.get("type"));
            Object data = content.get("data");
            if (type == 3) {
                removeRequests(data);
            } else if (type == 1 || type == 2) {
                rememberRequests(data);
            }
            return;
        }
        Object list = deepGet(content, 3, "help_list");
        if (list != null) {
            helpRequests.clear();
            rememberRequests(list);
        }
    }

    private void rememberRequests(Object raw) {
        for (Map<String, Object> m : mapsOf(raw)) {
            long owner = num(m.get("uid"));
            long qid = num(m.get("qid"));
            if (owner <= 0 || qid <= 0) continue;
            HelpReq r = new HelpReq(owner, qid,
                    Math.max(0, num(m.get("endtime"))), Math.max(0, num(m.get("max"))));
            Object helpers = m.get("helpers");
            if (helpers instanceof List) {
                for (Object h : (List<?>) helpers) {
                    long uid = num(h);
                    if (uid > 0) r.helpers.add(uid);
                }
            } else if (helpers instanceof Map) {
                for (Object h : ((Map<?, ?>) helpers).values()) {
                    long uid = num(h);
                    if (uid > 0) r.helpers.add(uid);
                }
            }
            helpRequests.put(r.key(), r);
        }
    }

    private void removeRequests(Object raw) {
        for (Map<String, Object> m : mapsOf(raw)) {
            long owner = num(m.get("uid"));
            long qid = num(m.get("qid"));
            if (owner > 0 && qid > 0) helpRequests.remove(owner + ":" + qid);
        }
    }

    // ---------------- 响应（会话线程内执行） ----------------

    private void maybeHelp(WjdrEngine engine, long now) {
        if (now < blockedUntil.get()) return;
        long nowSec = now / 1000L;
        long ownUid = engine.lastUid();
        int count = 0;
        for (HelpReq r : helpRequests.values()) {
            if (r.availableFor(ownUid, nowSec)) count++;
        }
        if (count <= 0) {
            saveState(false, 0, "推送", "当前没有盟友需要互助");
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        try {
            GameMessage rsp = engine.request("req_alliance_help_all", new java.util.LinkedHashMap<String, Object>());
            long done = Math.max(count, num(firstNum(rsp.getContent(), 2, "help_num", "count")));
            // 乐观更新：把本次可帮请求都视为已帮（原版 markHelpAllHandled 同款）
            for (HelpReq r : helpRequests.values()) {
                if (r.availableFor(ownUid, nowSec)) r.helpers.add(ownUid);
            }
            saveState(true, done, "推送", "已响应 " + done + " 位盟友互助");
            FileLog.info("联盟互助 fpid={} sid={}：已响应 {} 位", fpid, String.valueOf(sid),
                    String.valueOf(done));
        } catch (Throwable t) {
            blockedUntil.set(System.currentTimeMillis() + HELP_BLOCK_MILLIS);
            saveState(false, 0, "推送", "联盟互助暂不可用，稍后重试");
        } finally {
            busy.set(false);
        }
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

    // ---------------- 取值辅助（与 App HelpResponder 一致） ----------------

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static Object firstNum(Map<String, Object> m, int depth, String... keys) {
        if (m == null || depth < 0) return null;
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number) return v;
        }
        for (String wrap : new String[]{"data", "info"}) {
            Object w = m.get(wrap);
            if (w instanceof Map) {
                Object r = firstNum((Map<String, Object>) w, depth - 1, keys);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Object deepGet(Map<String, Object> m, int depth, String key) {
        if (m == null || depth < 0) return null;
        Object v = m.get(key);
        if (v != null) return v;
        for (Object w : m.values()) {
            if (w instanceof Map) {
                Object r = deepGet((Map<String, Object>) w, depth - 1, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> mapsOf(Object raw) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) raw;
            if (m.containsKey("uid") || m.containsKey("qid")) {
                out.add((Map<String, Object>) m);
                return out;
            }
            for (Object v : m.values()) {
                if (v instanceof Map) out.add((Map<String, Object>) v);
                else if (v instanceof List) out.addAll(mapsOf(v));
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) out.add((Map<String, Object>) o);
            }
        }
        return out;
    }
}
