package com.carlos.vaultserver;

import com.carlos.vaultserver.task.Exec;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 战争通知中枢（2026-10-22）：敌人监视 + 行军动作检测 + 企微/事件双出口。
 *
 * <p>数据源（design/守护盟友-自动驻防系统设计-20261015.md §2.1 实证字段 + App EnemyWatch 同口径）：
 * <ul>
 *   <li>{@code notify_player_march_add/_update/_del}：march{id, owner, startpoint/endpoint{x,y},
 *       starttime, endtime(秒), state, type, target_type, aid, kid}；del 只带 {id, type, kid}；</li>
 *   <li>{@code notify_mass_march_add/_update/_del}：集结行军（type=501 实证）；</li>
 *   <li>世界态势系列 {@code notify_world_situation_info_add/_up}、
 *       {@code notify_world_alliance_situation_add/_update/_del}（观察哨/目标系列，App 端
 *       EnemyWatch 同源）。</li>
 * </ul>
 *
 * <p>解析为<b>健壮递归查找</b>：不锁定单一形状，凡含 owner+id 的嵌套 map 都按行军候选处理
 * （协议字段改名/包装层变化时只降级不崩溃）；del 推送按 id 反查跟踪表。
 *
 * <p>流程：推送线程解析 → owner 命中账号监视名单 → 冷却节流（同 target+action 默认 300s，
 * 可配 60..3600）→ 事件入 {@link WatchEventStore}（环形 200 条持久化）→ 企微 webhook
 * 异步推送（单线程排队，静默时段只抑制企微；重试/超时由 {@link WeComNotifier} 负责，
 * 失败仅日志）。
 */
public final class WarNotify {

    /** del 时 endtime 与当前差值超过该秒数判为"撤回"，否则"到达"（对齐 App EnemyWatch）。 */
    static final long RECALL_MIN_S = 2L;
    /** 跟踪表 TTL：endtime 后 30 分钟未 del（推送丢失）则静默清除。 */
    static final long TRACK_TTL_MS = 30 * 60_000L;
    /** 递归解析的最大深度与候选上限（防异常 payload 打爆推送线程）。 */
    private static final int MAX_DEPTH = 6;
    private static final int MAX_CANDIDATES = 24;

    /** 动作枚举（事件 action 字段口径）。 */
    static final String ACT_RECON = "侦查";
    static final String ACT_ATTACK = "进攻";
    static final String ACT_RALLY = "集结";
    static final String ACT_MARCH = "出征";
    static final String ACT_RECALL = "撤回";
    static final String ACT_ARRIVE = "到达";

    private final VaultStore store;
    private final WatchEventStore events;
    private final WeComNotifier notifier;
    /** false = 企微在调用线程同步发送（单测确定性）；生产 true。 */
    private final boolean asyncWecom;
    private final ExecutorService wecomExec;
    /** 冷却表：fpid|targetUid|action → 最近通知 millis。 */
    private final ConcurrentHashMap<String, Long> cooldown = new ConcurrentHashMap<>();
    /** 行军跟踪表：marchId → track（del 判撤回/到达；跨推送保持）。 */
    private final ConcurrentHashMap<Long, Track> tracked = new ConcurrentHashMap<>();

    public WarNotify(VaultStore store, WatchEventStore events, WeComNotifier notifier) {
        this(store, events, notifier, true);
    }

    WarNotify(VaultStore store, WatchEventStore events, WeComNotifier notifier, boolean asyncWecom) {
        this.store = store;
        this.events = events;
        this.notifier = notifier;
        this.asyncWecom = asyncWecom;
        this.wecomExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "war-wecom");
            t.setDaemon(true);
            return t;
        });
    }

    // ---------------------------------------------------------------- 引擎钩子入口

    /**
     * 引擎消息钩子入口（SessionKeeper 每个在线角色会话挂一个；推送线程调用）。
     * 所有异常就地吞掉——通知模块任何故障不得影响会话。
     */
    public void onPush(String fpid, long sid, String proto, Map<String, Object> content) {
        try {
            handlePush(fpid, sid, proto, content);
        } catch (Throwable t) {
            FileLog.warn("战争通知解析异常 {} {}：{}", proto == null ? "?" : proto,
                    fpid, String.valueOf(t));
        }
    }

    private void handlePush(String fpid, long sid, String proto, Map<String, Object> content) {
        if (proto == null || content == null || content.isEmpty()) return;
        VaultStore.WatchConfig cfg = store.watchConfig();
        if (!cfg.enabled) return;
        sweepTracked(System.currentTimeMillis());
        boolean del = proto.endsWith("_del");
        if (del) {
            for (Map<String, Object> m : idCandidates(content)) {
                long id = Exec.longOf(m.get("id"));
                if (id > 0) onMarchDel(fpid, cfg, id);
            }
            return;
        }
        for (Map<String, Object> m : marchCandidates(content)) {
            handleMarch(fpid, sid, proto, cfg, m);
        }
    }

    /** 一条行军候选命中处理：owner 命中监视名单 → 事件（冷却内丢弃）。 */
    private void handleMarch(String fpid, long sid, String proto, VaultStore.WatchConfig cfg,
                             Map<String, Object> m) {
        long marchId = Exec.longOf(m.get("id"));
        long owner = Exec.longOf(m.get("owner"));
        if (marchId <= 0 || owner <= 0) return;
        VaultStore.WatchTarget watched = store.matchWatched(fpid, owner);
        if (watched == null) return;

        long type = Exec.longOf(m.get("type"));
        long startSec = Exec.longOf(m.get("starttime"));
        long endSec = Exec.longOf(m.get("endtime"));
        int[] xy = endpointOf(m);
        String action = actionOf(proto, type, false, 0);

        Track t = tracked.get(marchId);
        if (t == null) {
            if (endSec <= 0) return;
            t = new Track(marchId, fpid, sid, owner, displayOf(watched), action, endSec, xy, proto);
            tracked.put(marchId, t);
        } else {
            // 已跟踪：endtime 变化（加速/重估）只更新跟踪，不产生新事件（冷却亦会抑制）
            if (endSec > 0) t.endServerSec = endSec;
            return;
        }
        emit(fpid, cfg, t, startSec, endSec);
    }

    /** del 命中：按跟踪表判撤回/到达。 */
    private void onMarchDel(String fpid, VaultStore.WatchConfig cfg, long marchId) {
        Track t = tracked.remove(marchId);
        if (t == null) return;   // 未跟踪（非监视目标/启动前已在途）：静默
        long nowS = System.currentTimeMillis() / 1000L;
        long remain = t.endServerSec - nowS;
        t.action = remain > RECALL_MIN_S ? ACT_RECALL : ACT_ARRIVE;
        emit(fpid, cfg, t, 0, t.endServerSec);
    }

    /** 冷却节流 → 事件入库 → 企微推送。 */
    private void emit(String fpid, VaultStore.WatchConfig cfg, Track t,
                      long startSec, long endSec) {
        long now = System.currentTimeMillis();
        String key = fpid + "|" + t.targetUid + "|" + t.action;
        long cooldownMs = cfg.cooldownSeconds * 1000L;
        long[] prev = new long[1];
        cooldown.compute(key, (k, v) -> {
            if (v != null && now - v < cooldownMs) {
                prev[0] = v;
                return v;
            }
            return now;
        });
        if (prev[0] > 0) {
            FileLog.info("战争通知节流 {} uid={} {}（冷却中，丢弃）",
                    fpid, t.targetUid, t.action);
            return;
        }
        JSONObject e = new JSONObject();
        e.put("fpid", fpid);
        e.put("sid", t.sid);
        e.put("targetUid", t.targetUid);
        e.put("targetName", t.targetName);
        e.put("action", t.action);
        if (t.hasXY) {
            e.put("endpoint", new JSONObject().put("x", t.ex).put("y", t.ey));
        }
        if (startSec > 0) e.put("starttime", startSec);
        if (endSec > 0) e.put("endtime", endSec);
        e.put("detectedAt", now);
        e.put("rawProto", t.rawProto);
        JSONObject stored = events.append(e);
        FileLog.info("战争通知命中 {} uid={} {} 行军{}（事件 {}）",
                fpid, t.targetUid, t.action, t.marchId, stored.optString("eventId"));
        if (cfg.wecomEnabled && !cfg.webhookUrl.isEmpty() && !inQuietHours(cfg, now)) {
            String text = wecomText(fpid, stored, endSec);
            if (asyncWecom) wecomExec.execute(() -> notifier.send(cfg.webhookUrl, text));
            else notifier.send(cfg.webhookUrl, text);
        }
    }

    // ---------------------------------------------------------------- 事件存储委托（API 用）

    /** cursor 之后的新事件（最旧在前，分页上限内）。 */
    public List<JSONObject> eventsAfter(long cursor) {
        return events.after(cursor);
    }

    /** 当前最大事件 seq（游标推进用）。 */
    public long latestSeq() {
        return events.latestSeq();
    }

    // ---------------------------------------------------------------- 测试通知

    /** 配置测试通知（API op=test 同步调用）；返回是否确认送达。与配置保存共用同一 webhook 校验。 */
    public boolean sendTest() {
        VaultStore.WatchConfig cfg = store.watchConfig();
        if (cfg.webhookUrl.isEmpty()) {
            FileLog.info("测试通知跳过：未配置 webhook");
            return false;
        }
        String invalid = VaultStore.validateWatchConfig(cfg.webhookUrl,
                cfg.cooldownSeconds, cfg.quietStart, cfg.quietEnd);
        if (invalid != null) {
            FileLog.info("测试通知跳过：{}", invalid);
            return false;
        }
        return notifier.send(cfg.webhookUrl,
                "🧪战争通知测试推送：通道打通即部署成功。\n冷却 " + cfg.cooldownSeconds
                        + "s · 企微" + (cfg.wecomEnabled ? "开" : "关")
                        + " · 监视" + (cfg.enabled ? "开" : "关"));
    }

    // ---------------------------------------------------------------- 推送解析（静态可测）

    /**
     * 递归收集行军候选：凡含 owner(&gt;0)+id 的嵌套 map（命中后不再下钻）。
     * 兼容 {march:{…}} / marches:{槽位:{march:{…}}} / to_marchs:[…] / {data:{…}} 等实证形状。
     */
    static List<Map<String, Object>> marchCandidates(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        walk(content, out, new int[]{0}, true, 0);
        return out;
    }

    /** 递归收集 del 推送的 id 候选（含 id&gt;0 的 map；形状扁平，宽容一些）。 */
    static List<Map<String, Object>> idCandidates(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        walk(content, out, new int[]{0}, false, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object node, List<Map<String, Object>> out, int[] count,
                             boolean needOwner, int depth) {
        if (count[0] >= MAX_CANDIDATES || depth > MAX_DEPTH) return;
        if (!(node instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) node;
        if (m.get("id") != null && Exec.longOf(m.get("id")) > 0
                && (!needOwner || Exec.longOf(m.get("owner")) > 0)) {
            out.add(m);
            count[0]++;
            return;   // 命中即不再下钻（行军内部不嵌行军）
        }
        for (Object v : m.values()) {
            if (v instanceof Map) {
                walk(v, out, count, needOwner, depth + 1);
            } else if (v instanceof List) {
                for (Object e : (List<Object>) v) {
                    if (e instanceof Map) walk(e, out, count, needOwner, depth + 1);
                }
            }
        }
    }

    /** 动作映射：del 按剩余时间判撤回/到达；集结协议判集结；type 1=侦查 2=进攻；余为出征。 */
    static String actionOf(String proto, long type, boolean del, long remainSec) {
        if (del) return remainSec > RECALL_MIN_S ? ACT_RECALL : ACT_ARRIVE;
        if (proto != null && proto.contains("mass_march")) return ACT_RALLY;
        if (type == 1L) return ACT_RECON;
        if (type == 2L) return ACT_ATTACK;
        return ACT_MARCH;
    }

    /** endpoint 坐标：march.endpoint{x,y} 优先，退化 march.target{x,y}；取不到 null。 */
    static int[] endpointOf(Map<String, Object> m) {
        for (String key : new String[]{"endpoint", "target"}) {
            Map<String, Object> p = Exec.mapOf(m.get(key));
            if (p == null) continue;
            int x = (int) Exec.longOf(p.get("x"));
            int y = (int) Exec.longOf(p.get("y"));
            if (x != 0 || y != 0) return new int[]{x, y};
        }
        return null;
    }

    // ---------------------------------------------------------------- 企微文案与静默

    /** 企微文本（含角色/敌人/动作/坐标/时间）。 */
    String wecomText(String fpid, JSONObject event, long endSec) {
        StringBuilder sb = new StringBuilder("⚔️ 战争警报·").append(event.optString("action", ""))
                .append('\n').append("敌人：").append(event.optString("targetName", ""));
        Object ep = event.optJSONObject("endpoint");
        if (ep instanceof JSONObject) {
            sb.append(" (").append(((JSONObject) ep).optInt("x"))
                    .append(',').append(((JSONObject) ep).optInt("y")).append(')');
        }
        if (endSec > 0) {
            long nowS = System.currentTimeMillis() / 1000L;
            sb.append("\n预计 ").append(Beijing.fmt("HH:mm:ss").format(new java.util.Date(endSec * 1000L)));
            if (endSec > nowS) sb.append("（约 ").append(endSec - nowS).append("s 后）");
        }
        String label = "";
        VaultStore.Account acc = store.find(fpid);
        if (acc != null && acc.label != null && !acc.label.isEmpty()) label = acc.label;
        sb.append("\n监视会话：").append(label.isEmpty() ? fpid : label)
                .append("@k").append(event.optLong("sid"));
        String proto = event.optString("rawProto", "");
        if (!proto.isEmpty()) sb.append("\n来源：").append(proto);
        return sb.toString();
    }

    /** 静默时段判定（只抑制企微推送）；start==end 视为未配置。 */
    static boolean inQuietHours(VaultStore.WatchConfig cfg, long nowMillis) {
        if (cfg.quietStart < 0 || cfg.quietStart == cfg.quietEnd) return false;
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.setTimeInMillis(nowMillis);
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (cfg.quietStart < cfg.quietEnd) return h >= cfg.quietStart && h < cfg.quietEnd;
        return h >= cfg.quietStart || h < cfg.quietEnd;   // 跨夜（如 23→8）
    }

    // ---------------------------------------------------------------- 跟踪表维护

    private void sweepTracked(long now) {
        for (Map.Entry<Long, Track> e : tracked.entrySet()) {
            if (e.getValue().endServerSec * 1000L + TRACK_TTL_MS < now) tracked.remove(e.getKey());
        }
    }

    /** 已跟踪行军数（测试/诊断）。 */
    int trackedCount() {
        return tracked.size();
    }

    /** 监视目标显示名：备注 &gt; 名称 &gt; uid。 */
    private static String displayOf(VaultStore.WatchTarget t) {
        if (t.note != null && !t.note.isEmpty()) return t.note;
        if (t.name != null && !t.name.isEmpty()) return t.name;
        return "uid " + t.uid;
    }

    /** 一条被监视行军的跟踪记录。 */
    static final class Track {
        final long marchId;
        final String fpid;
        final long sid;
        final long targetUid;
        final String targetName;
        volatile String action;
        volatile long endServerSec;
        final int ex;
        final int ey;
        final boolean hasXY;
        final String rawProto;

        Track(long marchId, String fpid, long sid, long targetUid, String targetName,
              String action, long endServerSec, int[] xy, String rawProto) {
            this.marchId = marchId;
            this.fpid = fpid;
            this.sid = sid;
            this.targetUid = targetUid;
            this.targetName = targetName;
            this.action = action;
            this.endServerSec = endServerSec;
            this.ex = xy == null ? 0 : xy[0];
            this.ey = xy == null ? 0 : xy[1];
            this.hasXY = xy != null;
            this.rawProto = rawProto == null ? "" : rawProto;
        }
    }

    /** 冷却表剩余条数（测试）。 */
    int cooldownCount() {
        return cooldown.size();
    }

    /** 测试用：清空冷却表。 */
    void resetCooldownForTest() {
        cooldown.clear();
    }
}
