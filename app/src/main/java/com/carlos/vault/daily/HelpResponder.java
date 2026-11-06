package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联盟互助自动响应（对标原版 DailyAllianceDonationService 互助分支 + RecoveryAutomationExecutor.helpAll）。
 *
 * <p>触发链路与原版一致：
 * <ul>
 *   <li>推送驱动：notify_alliance_help_init / update_all / update / auto_help 到达即解析并响应（秒级）；</li>
 *   <li>兜底轮询：30s 一次（原版 alliance-donation 任务间隔），由 RoleSessionKeeper.scan 调 {@link #poll}；</li>
 *   <li>请求字段与判定照抄原版 HelpRequest：uid/qid/helpers/endtime/max，
 *       可帮 = 非自己发起 && 未满员 && 自己没帮过 && 未过期；</li>
 *   <li>动作 = req_alliance_help_all（一键与自动同一协议）；失败退避 15s（原版 10~15s）。</li>
 * </ul>
 *
 * <p>状态落 {@code mod:fpid:sid:alliance_help}（子项 help_all）：文案 + _ts + _trg=通知/定时 +
 * help_num/day_glory，沿用现有功能页/子项卡的展示链路；动作另写 LogStore（trigger=通知/定时）。
 */
final class HelpResponder {

    private static final Set<String> HELP_PROTOS = Set.of(
            "notify_alliance_help_init",
            "notify_alliance_help_update_all",
            "notify_alliance_help_update",
            "notify_alliance_help_auto_help");
    private static final long HELP_BLOCK_MILLIS = 15_000L;

    /** 一条互助请求（字段名与判定照抄原版 DailyAllianceDonationStateHelpRequest）。 */
    static final class HelpReq {
        final long owner;
        final long qid;
        final long endtime;
        final long max;
        final Set<Long> helpers = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    private HelpResponder() {
    }

    /** 在会话上挂互助钩子（每会话一次；重连后钩子随引擎 messageHooks 存续）。 */
    static void attach(Context ctx, RoleSessionKeeper.LiveSession ls) {
        if (!ls.helpAttached.compareAndSet(false, true)) return;
        ls.engine.addMessageHook((proto, content) -> {
            if (!HELP_PROTOS.contains(proto)) return;
            try {
                applyPush(ls, proto, content);
                maybeHelp(ctx, ls, "通知");
            } catch (Throwable ignored) {
            }
        });
    }

    /** 30s 兜底轮询：用最近一次 help 推送内容重算（推送丢失/漏发时的保险，原版任务间隔同款）。 */
    static void poll(Context ctx, RoleSessionKeeper.LiveSession ls) {
        try {
            for (String p : new String[]{"notify_alliance_help_update_all", "notify_alliance_help_init"}) {
                Map<String, Object> c = ls.engine.notifyContent(p);
                if (c != null) {
                    applyPush(ls, p, c);
                    break;
                }
            }
            maybeHelp(ctx, ls, "定时");
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 推送解析 ----------------

    /**
     * 维护请求表（对标原版 updateHelpRequests）：
     * init/update_all = help_list 全量替换；update = {type: 1/2 增改、3 删除, data: 单条或列表}。
     * 容器字段兼容 {alliance_help:{help_list}} 与裸 {help_list} 两种形状（含 data 包装）。
     */
    private static void applyPush(RoleSessionKeeper.LiveSession ls, String proto, Map<String, Object> content) {
        if (content == null) return;
        // 进度字段（今日已助次数/互助荣耀，原版 updateHelpProgress）
        long helpNum = num(firstNum(content, 3, "help_num"));
        long dayGlory = num(firstNum(content, 3, "day_glory"));
        if (helpNum > 0) ls.helpNumToday = (int) Math.min(helpNum, Integer.MAX_VALUE);
        if (dayGlory > 0) ls.dayGlory = dayGlory;

        if ("notify_alliance_help_update".equals(proto)) {
            long type = num(content.get("type"));
            Object data = content.get("data");
            if (type == 3) {
                removeRequests(ls, data);
            } else if (type == 1 || type == 2) {
                rememberRequests(ls, data);
            }
            return;
        }
        Object list = deepGet(content, 3, "help_list");
        if (list != null) {
            ls.helpRequests.clear();
            rememberRequests(ls, list);
        }
    }

    private static void rememberRequests(RoleSessionKeeper.LiveSession ls, Object raw) {
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
            ls.helpRequests.put(r.key(), r);
        }
    }

    private static void removeRequests(RoleSessionKeeper.LiveSession ls, Object raw) {
        for (Map<String, Object> m : mapsOf(raw)) {
            long owner = num(m.get("uid"));
            long qid = num(m.get("qid"));
            if (owner > 0 && qid > 0) ls.helpRequests.remove(owner + ":" + qid);
        }
    }

    // ---------------- 响应 ----------------

    private static int availableCount(RoleSessionKeeper.LiveSession ls) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long ownUid = ls.engine.lastUid();
        int count = 0;
        for (HelpReq r : ls.helpRequests.values()) {
            if (r.availableFor(ownUid, nowSec)) count++;
        }
        return count;
    }

    private static void maybeHelp(Context ctx, RoleSessionKeeper.LiveSession ls, String trigger) {
        if (!WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) return;
        long now = System.currentTimeMillis();
        if (now < ls.helpBlockedUntil) return;
        int count = availableCount(ls);
        if (count <= 0) {
            saveState(ctx, ls, false, 0, trigger, "当前没有盟友需要互助");
            return;
        }
        if (!ls.helpBusy.compareAndSet(false, true)) return;
        final int want = count;
        RoleSessionKeeper.get(ctx).execute(() -> {
            try {
                GameMessage rsp = ls.engine.request("req_alliance_help_all", new LinkedHashMap<String, Object>());
                long done = Math.max(want, num(firstNum(rsp.getContent(), 2, "help_num", "count")));
                // 乐观更新：把本次可帮请求都视为已帮（原版 markHelpAllHandled 同款）
                long ownUid = ls.engine.lastUid();
                long nowSec = System.currentTimeMillis() / 1000L;
                for (HelpReq r : ls.helpRequests.values()) {
                    if (r.availableFor(ownUid, nowSec)) r.helpers.add(ownUid);
                }
                saveState(ctx, ls, true, done, trigger, "已响应 " + done + " 位盟友互助");
                try {
                    new LogStore(ctx).append(ls.fpid, ls.sid, "alliance_help", true,
                            "✅ 联盟互助：已响应 " + done + " 位盟友", "", trigger, 0,
                            DailyResult.Category.CLAIMED.name(), 1);
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                ls.helpBlockedUntil = System.currentTimeMillis() + HELP_BLOCK_MILLIS;
                saveState(ctx, ls, false, 0, trigger, "联盟互助暂不可用，稍后重试");
                try {
                    new LogStore(ctx).append(ls.fpid, ls.sid, "alliance_help", false,
                            "⚠️ 联盟互助失败：" + t.getMessage(), "", trigger, 0,
                            DailyResult.Category.ERROR.name(), 1);
                } catch (Throwable ignored) {
                }
            } finally {
                ls.helpBusy.set(false);
            }
        });
    }

    /** 写互助状态行（与 FnParams.saveModText 同构：文案 + _ts(毫秒) + _trg + 进度字段）。 */
    private static void saveState(Context ctx, RoleSessionKeeper.LiveSession ls, boolean acted,
                                  long done, String trigger, String text) {
        try {
            JSONObject mod = FnParams.modState(ctx, ls.fpid, ls.sid, "alliance_help");
            mod.put("help_all", text);
            mod.put("help_all_ts", System.currentTimeMillis());
            mod.put("help_all_trg", trigger);
            if (ls.helpNumToday > 0) mod.put("help_num", ls.helpNumToday);
            if (ls.dayGlory > 0) mod.put("day_glory", ls.dayGlory);
            FnParams.saveModState(ctx, ls.fpid, ls.sid, "alliance_help", mod);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 取值辅助（形状防御式） ----------------

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** 深找首个数值字段（depth 限制；兼容 {data:{…}} 包装）。 */
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
            // 单条形状（含 uid/qid）直接收；否则按容器展开一层
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
