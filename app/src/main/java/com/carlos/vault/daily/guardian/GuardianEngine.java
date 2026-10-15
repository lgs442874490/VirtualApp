package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.FnParams;
import com.carlos.vault.daily.RoleSessionKeeper;
import com.carlos.vault.wjdr.WjdrEngine;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守护盟友引擎（P2：检测层 dry_run）。
 *
 * 职责（design/守护盟友-自动驻防系统设计-20261015.md §3/§4，P2 里程碑）：
 *   - 挂全部在线会话，解析世界态势推送（P1 实证：敌对盟友的行军走
 *     notify_world_alliance_situation_*（to_marchs），一般世界行军走 notify_world_situation_info_*）
 *   - 识别"指向本盟盟友城的侦查/进攻行军" → Threat 登记（owner/type/坐标/兵力/ETA/state）
 *   - 规则评估（R1）：攻击力阈值 + ETA 上限 + 目标须为盟友城
 *   - dry_run：只更新状态行 + 落 files/guardian_log.txt，不发出任何游戏指令
 *
 * state 语义（P1 实证）：2=在途出征 3=已到达（战斗/采集）4=回程；endtime>now 且 state∈{2,4} 才可加速/在途。
 * 行军 type：1=侦查 2=进攻 3=狩猎 4=采集 5=驻防(增援)。
 *
 * P3 接入点：LaunchScheduler（intercept 子开关开启后，QUALIFIED 威胁触发卡秒驻防）。
 */
public final class GuardianEngine {

    public static final String FN_ID = "guardian";

    private static final ConcurrentHashMap<String, Boolean> ATTACHED = new ConcurrentHashMap<>();
    /** 在途敌情：kid:marchId → Track。 */
    private static final ConcurrentHashMap<String, Track> THREATS = new ConcurrentHashMap<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();

    /** 一条威胁行军的跟踪记录。 */
    public static final class Track {
        public final long marchId;
        public final long kid;
        public final long owner;
        public final long type;          // 1=侦查 2=进攻
        public final long targetUid;     // 被打/被侦查的盟友 uid（0=未知）
        public final int x, y;
        public final long power;         // 攻方战力
        public final long firstSeenMs;
        public volatile long endServerSec;   // 当前预计到达（服务器秒）
        public volatile String stateText = "在途";
        public volatile long landedAtSec = 0;
        public volatile boolean forceQualified = false;   // 加速来袭已触发过驻防（防重复）

        Track(long marchId, long kid, long owner, long type, long targetUid, int x, int y,
              long power, long endServerSec) {
            this.marchId = marchId;
            this.kid = kid;
            this.owner = owner;
            this.type = type;
            this.targetUid = targetUid;
            this.x = x;
            this.y = y;
            this.power = power;
            this.endServerSec = endServerSec;
            this.firstSeenMs = System.currentTimeMillis();
        }

        public String kind() {
            return type == 1 ? "侦查" : type == 2 ? "进攻" : "行军#" + type;
        }
    }

    private GuardianEngine() {
    }

    // ------------------------------------------------------------------ 配置（FnParams p:guardian.* 子开关/策略）

    public static boolean watchOn(Context ctx, String fpid, long sid) {
        return FnParams.subOn(ctx, fpid, sid, FN_ID, "watch", false);
    }

    public static boolean interceptOn(Context ctx, String fpid, long sid) {
        return FnParams.subOn(ctx, fpid, sid, FN_ID, "intercept", false);
    }

    /** 攻方战力阈值（策略值即数字字符串；"0"=不限）。 */
    public static long powerMin(Context ctx, String fpid, long sid) {
        try {
            return Long.parseLong(FnParams.strategy(ctx, fpid, sid, FN_ID, "power_min", "0"));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 守护开启时为卡秒驻防预留的队列数（采集等日常不得占用）。 */
    public static int reserveQueues(Context ctx, String fpid, long sid) {
        return (interceptOn(ctx, fpid, sid) || watchOn(ctx, fpid, sid)) ? 2 : 0;
    }

    /** ETA 上限（秒；超过不构成即时威胁）。 */
    public static long etaMaxSec(Context ctx, String fpid, long sid) {
        try {
            return Long.parseLong(FnParams.strategy(ctx, fpid, sid, FN_ID, "eta_max", "1800"));
        } catch (Throwable t) {
            return 1800L;
        }
    }

    // ------------------------------------------------------------------ 挂载

    /** 会话登录后调用（RoleSessionKeeper，模式同 ProtoLogger/EnemyWatch）。幂等。 */
    public static void attach(Context ctx, RoleSessionKeeper.LiveSession fls) {
        try {
            String key = fls.fpid + ":" + fls.sid;
            if (ATTACHED.putIfAbsent(key, Boolean.TRUE) != null) return;
            final Context app = ctx.getApplicationContext();
            final long kid = fls.sid;
            final String fpid = fls.fpid;
            fls.engine.addMessageHook((proto, content) -> {
                try {
                    if (proto != null && (proto.startsWith("notify_world_alliance_situation")
                            || proto.startsWith("notify_world_situation"))) {
                        glog("hook收到 " + fpid + ":" + kid + " " + proto);
                    }
                    handle(app, fpid, kid, proto, content);
                } catch (Throwable t) {
                    glog("handle异常 " + proto + ": " + t);
                }
            });
            glog("已挂载 " + key);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 推送处理

    /** 供发射器实时读取敌 ETA（kid:marchId 定位）。 */
    public static Track trackOf(long kid, long marchId) {
        return THREATS.get(kid + ":" + marchId);
    }

    /** 供发射器感知威胁消失（敌召回/落地）。 */
    public static void dropTrack(long kid, long marchId) {
        THREATS.remove(kid + ":" + marchId);
    }

    private static void handle(Context app, String fpid, long kid, String proto, Map<String, Object> content) {
        if (content == null) return;
        if (!watchOn(app, fpid, kid) && !interceptOn(app, fpid, kid)) return;

        // 盟友坐标表喂食（任意会话收到的联盟成员位置推送都入表）
        if ("notify_alliance_member_pos_info".equals(proto)) {
            Map<String, Object> ml = asMap(content.get("member_list"));
            if (ml != null) {
                for (Object v : ml.values()) {
                    Map<String, Object> p = asMap(v);
                    AllyMap.put(kid, num(p.get("uid")), num(p.get("x")), num(p.get("y")));
                }
            }
            return;
        }
        if ("notify_alliance_member_pos_update".equals(proto)) {
            Map<String, Object> p = asMap(content.get("member_pos"));
            if (p != null) AllyMap.put(kid, num(p.get("uid")), num(p.get("x")), num(p.get("y")));
            return;
        }

        boolean watch = watchOn(app, fpid, kid);
        switch (proto) {
            case "notify_world_alliance_situation_add":
            case "notify_world_alliance_situation_update":
                for (String key : new String[]{"to_marchs", "marchs"}) {
                    Object arr = content.get(key);
                    if (arr instanceof List) {
                        for (Object v : (List<?>) arr) {
                            if (v instanceof Map) handleMarch(app, fpid, kid, asMap(v), true, num(((Map<?, ?>) v).get("power")));
                        }
                    }
                }
                break;
            case "notify_world_situation_info_add":
            case "notify_world_situation_info_up": {
                Object marches = content.get("marches");
                if (marches instanceof Map) {
                    for (Object v : ((Map<?, ?>) marches).values()) {
                        if (!(v instanceof Map)) continue;
                        Map<String, Object> entry = asMap(v);
                        Object m = entry.get("march");
                        if (m instanceof Map) handleMarch(app, fpid, kid, asMap(m), false, num(entry.get("power")));
                    }
                } else if (marches instanceof List) {
                    for (Object v : (List<?>) marches) {
                        if (v instanceof Map) handleMarch(app, fpid, kid, asMap(v), false, num(((Map<?, ?>) v).get("power")));
                    }
                }
                break;
            }
            case "notify_world_alliance_situation_del": {
                for (String key : new String[]{"to_marchs", "marchs"}) {
                    Object arr = content.get(key);
                    if (arr instanceof List) {
                        for (Object v : (List<?>) arr) {
                            if (v instanceof Map) handleDel(app, fpid, kid, asMap(v));
                        }
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private static void handleMarch(Context app, String fpid, long kid, Map<?, ?> m,
                                    boolean allyTargeted, long power) {
        if (!watchOn(app, fpid, kid) && !interceptOn(app, fpid, kid)) return;
        long id = num(m.get("id"));
        long owner = num(m.get("owner"));
        long type = num(m.get("type"));
        if (id <= 0 || owner <= 0) return;
        if (type != 1 && type != 2) return;   // P2 只关注侦查/进攻

        String tkey = kid + ":" + id;
        long end = num(m.get("endtime"));
        long nowS = System.currentTimeMillis() / 1000L;
        int[] xy = endpoint(m);
        long state = num(m.get("state"));
        long targetUid = targetUid(m);

        // 盟友目标判定：优先 atk_city.targetid/point 命中盟友表；world_situation 通用源用坐标匹配
        boolean allyHit = allyTargeted || (targetUid > 0 && AllyMap.isAlly(kid, targetUid))
                || (targetUid <= 0 && xy != null && AllyMap.match(kid, xy[0], xy[1]) > 0);
        if (!allyHit) return;

        Track t = THREATS.get(tkey);
        if (t == null) {
            if (end <= nowS) return;   // 已成历史
            t = new Track(id, kid, owner, type, targetUid, xy == null ? 0 : xy[0], xy == null ? 0 : xy[1],
                    power, end);
            THREATS.put(tkey, t);
            boolean intercept = interceptOn(app, fpid, kid);
            long pMin = powerMin(app, fpid, kid);
            long etaMax = etaMaxSec(app, fpid, kid);
            long etaSec = end - nowS;
            boolean qualified = intercept && etaSec <= etaMax && (pMin <= 0 || power >= pMin);
            String verdict = evaluate(app, fpid, kid, power, end - nowS);
            event(app, fpid, kid, "🚨检测[" + t.kind() + "] uid" + owner + " → 盟友城(" + t.x + "," + t.y
                    + (targetUid > 0 ? " uid" + targetUid : "") + ") ETA " + (end - nowS) + "s（"
                    + hhmmss(end) + "）power≈" + power + " → " + verdict);
            if (qualified) {
                com.carlos.vault.daily.guardian.GuardianLaunch.onThreatQualified(app, fpid, kid, t);
            }
        } else {
            long prev = t.endServerSec;
            t.endServerSec = end;
            if (end < prev) {
                event(app, fpid, kid, "⚡加速来袭[" + t.kind() + "] uid" + owner + " 行军" + id
                        + " 剩余 " + (prev - nowS) + "s → " + (end - nowS) + "s（烧道具了）");
                // 加速来袭 = 敌方真实投入，绕过战力阈值直接触发驻防（公理：防御先于一切）
                if (interceptOn(app, fpid, kid) && !t.forceQualified) {
                    t.forceQualified = true;
                    com.carlos.vault.daily.guardian.GuardianLaunch.onThreatQualified(app, fpid, kid, t);
                }
                // R3 动态同步：我方驻防已在途时，敌方再加速 → 加速我方驻防保持先至
                if (interceptOn(app, fpid, kid)) {
                    com.carlos.vault.daily.guardian.GuardianLaunch.raceAccelerate(app, fpid, kid, t.targetUid, id, end);
                }
            }
        }
    }

    private static void handleDel(Context app, String fpid, long kid, Map<?, ?> m) {
        if (!watchOn(app, fpid, kid) && !interceptOn(app, fpid, kid)) return;
        long id = num(m.get("id"));
        String tkey = kid + ":" + id;
        Track t = THREATS.remove(tkey);
        if (t == null) return;
        // 敌情解除 → 自动撤回我方驻防（打完即撤，释放盟友城驻防位与队列）
        if (interceptOn(app, fpid, kid) && t.targetUid > 0) {
            try {
                com.carlos.vault.daily.guardian.GuardianLaunch.repatriate(app, fpid, kid, t.targetUid);
            } catch (Throwable ignored) {
            }
        }
        long nowS = System.currentTimeMillis() / 1000L;
        long remain = t.endServerSec - nowS;
        String how = remain > 2 ? "召回解除（距落地 " + remain + "s）" : "已落地";
        event(app, fpid, kid, "✔敌情解除[" + t.kind() + "] uid" + t.owner + " 行军" + id + " " + how);
    }

    // ------------------------------------------------------------------ 规则评估（R1）

    private static String evaluate(Context app, String fpid, long kid, long power, long etaSec) {
        boolean intercept = interceptOn(app, fpid, kid);
        long pMin = powerMin(app, fpid, kid);
        long etaMax = etaMaxSec(app, fpid, kid);
        if (etaSec > etaMax) return "观察（ETA 超窗口）";
        if (pMin > 0 && power < pMin) return "观察（战力低于阈值）";
        return intercept ? " QUALIFIED（P3 接线后自动卡秒驻防）" : "dry_run（拦截未开启，仅告警）";
    }

    // ------------------------------------------------------------------ 状态与日志

    private static void event(Context app, String fpid, long kid, String line) {
        glog(fpid + ":" + kid + " " + line);
        try {
            // 状态行（功能卡片展示）；同时保留最近事件在文本里
            FnParams.saveModText(app, fpid, kid, FN_ID, "watch", line);
        } catch (Throwable ignored) {
        }
        synchronized (RECENT) {
            RECENT.addFirst(stamp() + " " + line);
            while (RECENT.size() > 30) RECENT.removeLast();
        }
    }

    /** 功能卡片状态行（含汇总）。 */
    public static String statusLine(Context ctx, String fpid, long kid) {
        int n = 0;
        for (Track t : THREATS.values()) if (t.kid == kid) n++;
        String last = null;
        synchronized (RECENT) {
            for (String s : RECENT) {
                if (s.contains(":" + kid + " ")) { last = s; break; }
            }
        }
        return "监测中 · 当前威胁 " + n + " · 盟友坐标 " + AllyMap.size(kid) + " 位"
                + (last == null ? "" : "\n" + last);
    }

    /** 日常轮的状态刷新任务输出（无游戏动作）。 */
    public static String summary(Context ctx, String fpid, long kid) {
        return statusLine(ctx, fpid, kid);
    }

    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    private static void glog(String line) {
        try {
            Context c = APP;
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "guardian_log.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(stamp() + " " + line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile Context APP;

    /** App 上下文注入（RoleSessionKeeper 附挂时）。 */
    public static void init(Context ctx) {
        if (APP == null && ctx != null) APP = ctx.getApplicationContext();
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private static String hhmmss(long serverSec) {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(serverSec * 1000L));
    }

    // ------------------------------------------------------------------ 解析工具（双形状自适应，P1 实证）

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (Throwable t) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static int[] endpoint(Map<?, ?> m) {
        try {
            Object p = m.get("endpoint");
            if (p instanceof Map) {
                return new int[]{(int) num(((Map<?, ?>) p).get("x")), (int) num(((Map<?, ?>) p).get("y"))};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 行军目标 uid：to_marchs 形状优先 atk_city.targetid，其次 target.map_city.uid。 */
    private static long targetUid(Map<?, ?> m) {
        try {
            Object atk = m.get("atk_city");
            if (atk instanceof Map) return num(((Map<?, ?>) atk).get("targetid"));
            Object target = m.get("target");
            if (target instanceof Map) {
                Object city = ((Map<?, ?>) target).get("map_city");
                if (city instanceof Map) return num(((Map<?, ?>) city).get("uid"));
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
