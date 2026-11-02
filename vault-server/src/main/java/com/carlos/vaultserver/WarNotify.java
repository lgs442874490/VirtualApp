package com.carlos.vaultserver;

import com.carlos.vault.daily.ScoutPushQuota;
import com.carlos.vaultserver.task.Exec;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 战争通知中枢（2026-10-22 v4.1.8 增强）：敌人监视 + 行军动作检测 + 完成结算 + 企微/事件双出口。
 *
 * <p>数据源（design/守护盟友-自动驻防系统设计-20261015.md §2.1 实证字段 + App EnemyWatch 同口径）：
 * <ul>
 *   <li>{@code notify_player_march_add/_update/_del}：march{id, owner, startpoint/endpoint{x,y},
 *       starttime, endtime(秒), squad_list{soldier}, state, type, target_type, aid, kid}；
 *       del 只带 {id, type, kid}；</li>
 *   <li>{@code notify_mass_march_add/_update/_del}：集结行军（type=501 实证）；</li>
 *   <li>{@code notify_new_mail_syn}：战斗邮件推送（v4.1.8 起接收，解析 mail_cover_info
 *       关联到达结算做破城判定）；</li>
 *   <li>世界态势系列 {@code notify_world_situation_info_add/_up}、
 *       {@code notify_world_alliance_situation_add/_update/_del}（观察哨/目标系列，App 端
 *       EnemyWatch 同源）。</li>
 * </ul>
 *
 * <p>解析为<b>健壮递归查找</b>：不锁定单一形状，凡含 owner+id 的嵌套 map 都按行军候选处理
 * （协议字段改名/包装层变化时只降级不崩溃）；del 推送按 id 反查跟踪表。
 *
 * <p>流程：推送线程解析 → owner 命中账号监视名单 → 冷却节流（初始动作与完成结算分别冷却，
 * 结算 key 带 {@code |done} 后缀）→ 事件入 {@link WatchEventStore}（环形 200 条持久化）→
 * 企微 webhook 异步推送（单线程排队，静默时段只抑制企微）。
 *
 * <p>v4.1.8 事件增强（用户实测反馈 6 项）：
 * <ul>
 *   <li>初始事件：{@code attackerName}（昵称，异步解析 + 10min 缓存，失败回退监视备注/uid）、
 *       {@code startPoint}（发起人坐标）、{@code behavior}（侦查/进攻城堡/集结城堡/驻防/出征）、
 *       {@code troops}（squad_list 兵力汇总）、{@code etaSeconds}/{@code dueAt}（到达倒计时）；</li>
 *   <li>完成结算事件（{@code phase=settle}）：del 时输出 {@code durationSeconds}（从发起到完成
 *       耗时）、{@code outcome}（撤回/已到达）、{@code breach}（破城：是/否/未知/非本账号防守）、
 *       {@code battleCodes}（战报 first/second 原码）；</li>
 *   <li>破城判定：本会话到达前后 {@value #MAIL_ASSOC_WINDOW_MS}（毫秒，±120s）内的战斗邮件
 *       （first_type=30 防守类）按 second_type 4/5=防守胜（未破城）、6/7/8=防守败（破城）映射；
 *       到达结算默认等待 {@value #BATTLE_GRACE_DEFAULT_MS}（毫秒）让战报先落地，无战报则判
 *       「非本账号防守，结果未知」（防守语义未知风险见 GuardReportCodes）。</li>
 * </ul>
 *
 * <p><b>侦查类推送限流（用户 2026-11-01 硬规则）</b>：{@code action=侦查}（march type=1）不走
 * 通用「同目标同动作冷却」，改按<b>滑动 5 分钟窗口内每个敌人只推送前 3 次</b>计数——
 * 实现为 {@link ScoutPushQuota}（App 本地模式 EnemyWatch 与服务端同源共用，口径只改一处）。
 * 超限的侦查行军照常跟踪但不产生事件、不发企微；其后续「撤回/到达」结算一并静默，避免出现
 * 无上下文的孤立结算。进攻/集结/出征仍按 {@code cooldownSeconds} 冷却。
 *
 * <p><b>线程纪律</b>：昵称解析走 engine.request（阻塞等响应、响应经推送线程投递）——
 * 严禁在推送线程调用，否则自等自死锁。本类只在 {@code name-wecom} 线程（企微文案构建前）
 * 与 {@code war-name} 线程（缓存回填）调用 {@link NameResolver}；asyncWecom=false 的单测
 * 同步路径下 resolver 为测试 mock，不触网、无死锁。
 */
public final class WarNotify {

    /** del 时 endtime 与当前差值超过该秒数判为"撤回"，否则"到达"（对齐 App EnemyWatch）。 */
    static final long RECALL_MIN_S = 2L;
    /** 跟踪表 TTL：endtime 后 30 分钟未 del（推送丢失）则静默清除。 */
    static final long TRACK_TTL_MS = 30 * 60_000L;
    /** 递归解析的最大深度与候选上限（防异常 payload 打爆推送线程）。 */
    private static final int MAX_DEPTH = 6;
    private static final int MAX_CANDIDATES = 24;
    /** 战斗邮件关联窗口：与到达时刻相差不超过 120s 才参与破城判定（best-effort）。 */
    static final long MAIL_ASSOC_WINDOW_MS = 120_000L;
    /** 邮件记录 TTL（超出窗口 1 倍即可清理）。 */
    private static final long MAIL_TTL_MS = 2 * MAIL_ASSOC_WINDOW_MS;
    /** 每会话邮件记录上限（防刷爆）。 */
    private static final int MAIL_CAP = 20;
    /** 到达结算默认宽限：等战斗邮件落地后再结算破城；撤回结算不等待。 */
    static final long BATTLE_GRACE_DEFAULT_MS = 30_000L;
    /** 昵称缓存 TTL。 */
    static final long NAME_CACHE_TTL_MS = 10 * 60_000L;

    /** 动作枚举（事件 action 字段口径，v4.1.7 兼容不变）。 */
    static final String ACT_RECON = "侦查";
    static final String ACT_ATTACK = "进攻";
    static final String ACT_RALLY = "集结";
    static final String ACT_MARCH = "出征";
    static final String ACT_RECALL = "撤回";
    static final String ACT_ARRIVE = "到达";

    /** 行为枚举（事件 behavior 字段口径，v4.1.8）：比 action 更贴近用户语汇。 */
    static final String BEH_RECON = "侦查";
    static final String BEH_ATTACK = "进攻城堡";
    static final String BEH_RALLY = "集结城堡";
    static final String BEH_GARRISON = "驻防";
    static final String BEH_MARCH = "出征";

    /** 破城判定口径（事件 breach 字段）。 */
    static final String BREACH_YES = "是";
    static final String BREACH_NO = "否";
    static final String BREACH_UNKNOWN = "未知";
    static final String BREACH_NOT_OWN = "非本账号防守，结果未知";
    /** 防守类战斗邮件 first_type（P1 实证守城级联=30，同 App GuardReportCodes）。 */
    static final long DEF_FIRST_BATTLE = 30L;
    /** 兵力未知文案。 */
    static final String TROOPS_UNKNOWN = "未知";

    /** 结算事件 phase 值（初始动作事件 phase=action）。 */
    static final String PHASE_SETTLE = "settle";
    static final String PHASE_ACTION = "action";

    /** 诊断：situation 系列日志节流（每 60s 一条，输出候选 owner，定位「有推送但无命中」）。 */
    private final ConcurrentHashMap<String, Long> diagAt = new ConcurrentHashMap<>();
    private static final long DIAG_INTERVAL_MS = 60_000L;

    /** 昵称解析器（SessionKeeper 提供；实现里 engine.request 只准非推送线程调用）。 */
    public interface NameResolver {
        String resolve(String fpid, long sid, long uid);
    }

    private final VaultStore store;
    private final WatchEventStore events;
    private final WeComNotifier notifier;
    /** false = 企微在调用线程同步发送（单测确定性）；生产 true。 */
    private final boolean asyncWecom;
    private final ExecutorService wecomExec;
    /** 昵称异步解析（缓存回填；严禁推送线程做这件事）。 */
    private final ExecutorService nameExec;
    /** 到达结算宽限计时（等战斗邮件落地）。 */
    private final ScheduledExecutorService settleExec;
    /** 冷却表：fpid|targetUid|action[|done] → 最近通知 millis。 */
    private final ConcurrentHashMap<String, Long> cooldown = new ConcurrentHashMap<>();
    /**
     * 侦查推送配额：滑动 5 分钟窗口内每个敌人只推前 3 次（{@link ScoutPushQuota} 同源口径）。
     * 只作用于侦查（march type=1）的初始动作事件；不按 fpid 分账——marchId 已全局去重，
     * 「每个敌人」即敌人 uid 维度。
     */
    private final ScoutPushQuota scoutQuota = new ScoutPushQuota();
    /** 行军跟踪表：marchId → track（del 判撤回/到达；跨推送保持）。 */
    private final ConcurrentHashMap<Long, Track> tracked = new ConcurrentHashMap<>();
    /** 昵称缓存：fpid|uid → {name, at}（10min，失败也缓存回退名防反复请求）。 */
    private final ConcurrentHashMap<String, NameEntry> nameCache = new ConcurrentHashMap<>();
    /** 最近战斗邮件：fpid|sid → 队列（cap {@value #MAIL_CAP}，TTL {@value #MAIL_TTL_MS}）。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<MailRec>> recentMails =
            new ConcurrentHashMap<>();
    /** 待结算：marchId → settle（del 建立，宽限期后 finalize；幂等 CAS 移除）。 */
    private final ConcurrentHashMap<Long, Settle> pending = new ConcurrentHashMap<>();
    private volatile NameResolver nameResolver;
    /** 到达结算宽限（单测可注入 0=同步即时结算）。 */
    private volatile long battleGraceMs = BATTLE_GRACE_DEFAULT_MS;

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
        this.nameExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "war-name");
            t.setDaemon(true);
            return t;
        });
        this.settleExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "war-settle");
            t.setDaemon(true);
            return t;
        });
        if (!asyncWecom) this.battleGraceMs = 0;   // 单测同步模式：结算即时（不依赖 executor 时序）
    }

    /** 注入昵称解析器（Main 接线：SessionKeeper::resolvePlayerName）。 */
    public void setNameResolver(NameResolver resolver) {
        this.nameResolver = resolver;
    }

    /** 单测注入结算宽限（毫秒；0=del 推送线程同步结算）。 */
    void setBattleGraceMs(long ms) {
        this.battleGraceMs = Math.max(0, ms);
    }

    // ---------------------------------------------------------------- 引擎钩子入口

    /**
     * 引擎消息钩子入口（SessionKeeper 每个在线角色会话挂一个；推送线程调用）。
     * 所有异常就地吞掉——通知模块任何故障不得影响会话。
     */
    public void onPush(String fpid, long sid, String proto, Map<String, Object> content) {
        onPush(fpid, sid, 0L, proto, content);
    }

    /** 带角色 uid 的入口（2026-11-03）：用于「名单生效角色」范围过滤。 */
    public void onPush(String fpid, long sid, long selfUid, String proto,
                       Map<String, Object> content) {
        try {
            VaultStore.WatchConfig cfg = store.watchConfig();
            if (selfUid > 0L && cfg.roleUidScope != null && !cfg.roleUidScope.isEmpty()
                    && !cfg.roleUidScope.contains(selfUid)) {
                return;   // 该角色不在名单生效范围内
            }
            handlePush(fpid, sid, proto, content);
        } catch (Throwable t) {
            FileLog.warn("战争通知解析异常 {} {}：{}", proto == null ? "?" : proto,
                    fpid, String.valueOf(t));
        }
    }

    private void handlePush(String fpid, long sid, String proto, Map<String, Object> content) {
        if (proto != null && proto.contains("situation") && diagAllowed(proto)) {
            FileLog.info("战争诊断 {} contentNull={} contentEmpty={} keys={}",
                    proto, content == null, content != null && content.isEmpty(),
                    content == null ? "null" : content.keySet());
        }
        if (proto == null || content == null || content.isEmpty()) return;
        VaultStore.WatchConfig cfg = store.watchConfig();
        if (!cfg.enabled) return;
        sweep(System.currentTimeMillis());
        if (proto.contains("new_mail")) {
            recordMail(fpid, sid, content);
            return;   // 邮件内容不参与行军候选解析
        }
        boolean del = proto.endsWith("_del");
        if (del) {
            for (Map<String, Object> m : idCandidates(content)) {
                long id = Exec.longOf(m.get("id"));
                if (id > 0) onMarchDel(fpid, id);
            }
            return;
        }
        List<Map<String, Object>> candidates = marchCandidates(content);
        if (proto.contains("situation") && diagAllowed(proto + "|owners")) {
            List<Long> owners = new ArrayList<>();
            List<Long> watchedOwners = new ArrayList<>();
            for (Map<String, Object> m : candidates) {
                long owner = Exec.longOf(m.get("owner"));
                if (owner <= 0L) continue;
                if (store.matchWatched(fpid, owner) != null && !watchedOwners.contains(owner)) {
                    watchedOwners.add(owner);
                }
                if (!owners.contains(owner) && owners.size() < 6) owners.add(owner);
            }
            FileLog.info("战争诊断候选 {} candidates={} owners={} watched={}",
                    proto, candidates.size(), owners, watchedOwners);
        }
        for (Map<String, Object> m : candidates) {
            handleMarch(fpid, sid, proto, cfg, m);
        }
    }

    private boolean diagAllowed(String proto) {
        long now = System.currentTimeMillis();
        Long at = diagAt.get(proto);
        if (at != null && now - at < DIAG_INTERVAL_MS) return false;
        diagAt.put(proto, now);
        return true;
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
        long state = Exec.longOf(m.get("state"));
        long startSec = Exec.longOf(m.get("starttime"));
        long endSec = Exec.longOf(m.get("endtime"));
        int[] xy = endpointOf(m);
        int[] sp = startPointOf(m);
        // 2026-11-02 误报修复：只推敌情行军（侦查/进攻/集结）；采集/驻防/建设援助/狩猎一律不推。
        // 典型误报：监视目标派队列去建联盟总部，返程被当成「出征」推送。
        if (state >= 3L) return;                       // 已到达/回程
        if (!isPvpMarch(type) && (proto == null || !proto.contains("mass_march"))) {
            if (diagAllowed("typeskip:" + proto)) {
                FileLog.info("战争通知跳过（非敌情行军）proto={} owner={} type={} state={}",
                        proto, String.valueOf(owner), String.valueOf(type), String.valueOf(state));
            }
            return;
        }
        // 回自己城（援助/建设返程、撤军）不是打谁
        if (xy != null && sp != null && xy[0] == sp[0] && xy[1] == sp[1]) return;
        if (xy != null) {
            com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo self =
                    com.carlos.vaultserver.watchpoint.WatchPointEngine.cityAt(xy[0], xy[1]);
            if (self != null && self.uid == owner) return;
        }
        String action = actionOf(proto, type, false, 0);
        String behavior = behaviorOf(proto, type);
        String troops = troopsOf(m);

        Track t = tracked.get(marchId);
        if (t == null) {
            if (endSec <= 0) return;
            t = new Track(marchId, fpid, sid, owner, displayOf(watched), action, endSec, xy, proto);
            t.startServerSec = startSec;
            t.behavior = behavior;
            t.troops = troops;
            t.marchType = type;
            if (sp != null) {
                t.sx = sp[0];
                t.sy = sp[1];
                t.hasStart = true;
            }
            tracked.put(marchId, t);
            applyCachedName(t);   // 推送线程只读内存缓存，绝不在这里触网解析
        } else {
            // 已跟踪：endtime 变化（加速/重估）只更新跟踪，不产生新事件（冷却亦会抑制）
            if (endSec > 0) t.endServerSec = endSec;
            return;
        }
        emitAction(fpid, cfg, t, endSec);
    }

    /** del 命中：按跟踪表判撤回/到达；撤回即时结算，到达等宽限期（让战斗邮件先落地）。 */
    private void onMarchDel(String fpid, long marchId) {
        Track t = tracked.remove(marchId);
        if (t == null) return;   // 未跟踪（非监视目标/启动前已在途）：静默
        long nowMillis = System.currentTimeMillis();
        long nowS = nowMillis / 1000L;
        boolean recall = t.endServerSec - nowS > RECALL_MIN_S;
        t.action = recall ? ACT_RECALL : ACT_ARRIVE;
        Settle s = new Settle(t, nowS, nowMillis);
        if (pending.putIfAbsent(marchId, s) != null) return;   // 已有在途结算
        long delay = recall ? 0 : battleGraceMs;
        if (delay <= 0) {
            finalizeSettle(t.fpid, store.watchConfig(), marchId, s);
        } else {
            settleExec.schedule(() -> {
                try {
                    finalizeSettle(t.fpid, store.watchConfig(), marchId, s);
                } catch (Throwable tt) {
                    FileLog.warn("战争结算异常 march{}：{}", marchId, String.valueOf(tt));
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 结算落定（settleExec/推送线程）：破城判定 → 冷却（|done key）→ 结算事件 → 企微。
     * 幂等：pending CAS 移除，重复调用只生效一次。
     */
    private void finalizeSettle(String fpid, VaultStore.WatchConfig cfg, long marchId, Settle s) {
        if (!pending.remove(marchId, s)) return;
        Track t = s.t;
        if (t.muted) {
            // 超限被静默的侦查行军：初始事件没推，结算也不推（否则是无上下文的孤立通知）
            FileLog.info("侦查结算静默 uid={} 行军{}（该侦查已超限未推送）", t.targetUid, t.marchId);
            return;
        }
        long now = System.currentTimeMillis();
        if (s.recall) {
            s.breach = "";   // 撤回无破城概念
        } else {
            MailRec mail = nearestMail(t.fpid, t.sid, s.delAtMillis);
            if (mail == null) {
                s.breach = BREACH_NOT_OWN;   // 本会话无战报：大概率打的是视野内别人
            } else {
                s.breach = breachOf(mail.first, mail.second);
                s.battleCodes = mail.first + "/" + mail.second;
            }
        }
        long duration = 0;
        if (t.startServerSec > 0) {
            duration = s.delAtS - t.startServerSec;
            if (duration <= 0) duration = Math.max(0, t.endServerSec - t.startServerSec);
        } else {
            duration = Math.max(0, t.endServerSec - t.startServerSec);
        }
        String key = fpid + "|" + t.targetUid + "|" + t.action + "|done";
        if (throttled(key, cfg.cooldownSeconds * 1000L, now)) {
            FileLog.info("战争结算节流 {} uid={} {}（冷却中，丢弃）", fpid, t.targetUid, t.action);
            return;
        }
        JSONObject e = baseEvent(t, now);
        e.put("phase", PHASE_SETTLE);
        e.put("durationSeconds", duration);
        e.put("outcome", s.recall ? "撤回" : "已到达");
        if (!s.breach.isEmpty()) e.put("breach", s.breach);
        if (!s.battleCodes.isEmpty()) e.put("battleCodes", s.battleCodes);
        JSONObject stored = events.append(e);
        FileLog.info("战争结算 {} uid={} {} 行军{} 耗时{}s 破城{}（事件 {}）",
                fpid, t.targetUid, t.action, t.marchId, duration, s.breach,
                stored.optString("eventId"));
        if (cfg.wecomEnabled && !cfg.webhookUrl.isEmpty() && !inQuietHours(cfg, now)) {
            if (asyncWecom) wecomExec.execute(() -> sendWecom(cfg, t, stored));
            else sendWecom(cfg, t, stored);
        }
    }

    // ---------------------------------------------------------------- 事件发射

    /** 初始动作事件：侦查按「5 分钟窗口内每个敌人前 3 次」配额，其余按冷却 → 事件入库 → 企微 → 昵称异步回填。 */
    private void emitAction(String fpid, VaultStore.WatchConfig cfg, Track t, long endSec) {
        long now = System.currentTimeMillis();
        if (ACT_RECON.equals(t.action)) {
            // 用户 2026-11-01 硬规则：侦查类推送五分钟内每个敌人只推前三次（计数口径，取代通用冷却）
            if (!scoutQuota.allow(t.targetUid, now)) {
                t.muted = true;   // 后续「撤回/到达」结算一并静默
                FileLog.info("侦查推送超限 {} uid={} 行军{}（5 分钟内已推 {} 次，本次静默）",
                        fpid, t.targetUid, t.marchId, ScoutPushQuota.MAX_PUSHES);
                return;
            }
        } else if (throttled(fpid + "|" + t.targetUid + "|" + t.action,
                cfg.cooldownSeconds * 1000L, now)) {
            FileLog.info("战争通知节流 {} uid={} {}（冷却中，丢弃）",
                    fpid, t.targetUid, t.action);
            return;
        }
        JSONObject e = baseEvent(t, now);
        e.put("phase", PHASE_ACTION);
        e.put("etaSeconds", Math.max(0, endSec - now / 1000L));
        JSONObject stored = events.append(e);
        FileLog.info("战争通知命中 {} uid={} {} type={} 行军{}（事件 {}）",
                fpid, t.targetUid, t.action, String.valueOf(t.marchType), t.marchId,
                stored.optString("eventId"));
        if (cfg.wecomEnabled && !cfg.webhookUrl.isEmpty() && !inQuietHours(cfg, now)) {
            if (asyncWecom) wecomExec.execute(() -> sendWecom(cfg, t, stored));
            else sendWecom(cfg, t, stored);
        }
        resolveNameAsync(t);
    }

    /** 事件公共字段（初始与结算共用；targetName 保留供 v4.1.7 旧 App 兼容）。 */
    private JSONObject baseEvent(Track t, long nowMillis) {
        JSONObject e = new JSONObject();
        e.put("fpid", t.fpid);
        e.put("sid", t.sid);
        e.put("targetUid", t.targetUid);
        e.put("attackerName", t.attackerName);
        e.put("targetName", t.targetName);
        e.put("action", t.action);
        e.put("behavior", t.behavior);
        e.put("marchType", t.marchType);
        e.put("troops", t.troops);
        if (t.hasXY) {
            e.put("endpoint", new JSONObject().put("x", t.ex).put("y", t.ey));
        }
        if (t.hasStart) {
            e.put("startPoint", new JSONObject().put("x", t.sx).put("y", t.sy));
        }
        if (t.startServerSec > 0) e.put("starttime", t.startServerSec);
        if (t.endServerSec > 0) {
            e.put("endtime", t.endServerSec);
            e.put("dueAt", t.endServerSec * 1000L);
        }
        e.put("marchId", t.marchId);
        e.put("detectedAt", nowMillis);
        e.put("rawProto", t.rawProto);
        return e;
    }

    // ---------------------------------------------------------------- 昵称解析（异步）

    /** 异步回填昵称（nameExec；结果进缓存并更新 track，供后续事件与结算使用）。 */
    private void resolveNameAsync(Track t) {
        if (nameResolver == null) return;
        nameExec.execute(() -> {
            try {
                ensureNameResolved(t);
            } catch (Throwable tt) {
                FileLog.info("昵称异步解析失败 uid={}：{}", t.targetUid, String.valueOf(tt));
            }
        });
    }

    /**
     * 缓存优先 → 未命中调 resolver（只准在 nameExec/wecomExec 线程调用！）。
     * 失败也缓存回退名（10min 内不反复请求），后续展示回退到监视备注/uid。
     */
    private void ensureNameResolved(Track t) {
        String key = t.fpid + "|" + t.targetUid;
        long now = System.currentTimeMillis();
        NameEntry en = nameCache.get(key);
        if (en != null && now - en.at <= NAME_CACHE_TTL_MS) {
            t.attackerName = en.name;
            return;
        }
        NameResolver r = nameResolver;
        if (r == null) return;
        String nick = "";
        try {
            nick = r.resolve(t.fpid, t.sid, t.targetUid);
        } catch (Throwable tt) {
            FileLog.info("昵称解析异常 uid={}：{}", t.targetUid, String.valueOf(tt));
        }
        if (nick == null || nick.isEmpty()) {
            nameCache.put(key, new NameEntry(t.attackerName, now));
            return;
        }
        nameCache.put(key, new NameEntry(nick, now));
        t.attackerName = nick;
    }

    /** 缓存命中（未过期）则立即回填 track 昵称；推送线程安全（纯内存）。 */
    private void applyCachedName(Track t) {
        NameEntry en = nameCache.get(t.fpid + "|" + t.targetUid);
        if (en != null && System.currentTimeMillis() - en.at <= NAME_CACHE_TTL_MS) {
            t.attackerName = en.name;
        }
    }

    // ---------------------------------------------------------------- 战斗邮件关联

    /** 记录本会话战斗邮件（mail_cover_info 的 first/second_type；cap+TTL）。 */
    private void recordMail(String fpid, long sid, Map<String, Object> content) {
        try {
            Map<String, Object> cover = Exec.mapOf(content.get("mail_cover_info"));
            if (cover == null) return;
            long first = Exec.longOf(cover.get("first_type"));
            long second = Exec.longOf(cover.get("second_type"));
            if (first <= 0 && second <= 0) return;
            ConcurrentLinkedDeque<MailRec> dq =
                    recentMails.computeIfAbsent(fpid + "|" + sid,
                            k -> new ConcurrentLinkedDeque<>());
            dq.addLast(new MailRec(System.currentTimeMillis(), first, second));
            while (dq.size() > MAIL_CAP) dq.pollFirst();
        } catch (Throwable t) {
            FileLog.warn("战报邮件解析异常 {}：{}", fpid, String.valueOf(t));
        }
    }

    /** 距 anchor 前后 {@value #MAIL_ASSOC_WINDOW_MS} 内最近的邮件；无则 null。 */
    private MailRec nearestMail(String fpid, long sid, long anchorMillis) {
        ConcurrentLinkedDeque<MailRec> dq = recentMails.get(fpid + "|" + sid);
        if (dq == null) return null;
        MailRec best = null;
        long bestDiff = Long.MAX_VALUE;
        for (MailRec m : dq) {
            if (Math.abs(m.at - anchorMillis) > MAIL_ASSOC_WINDOW_MS) continue;
            long diff = Math.abs(m.at - anchorMillis);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = m;
            }
        }
        return best;
    }

    /**
     * 破城映射（口径与 App GuardReportCodes 一致，按防守方语义换算）：
     * first_type=30 防守类 → second 4/5=防守胜（未破城）、6/7/8=防守败（破城）、其它=未知；
     * 非 30 的邮件语义未校准 → 未知（battleCodes 照附供人工判读）。
     */
    static String breachOf(long first, long second) {
        if (first != DEF_FIRST_BATTLE) return BREACH_UNKNOWN;
        if (second == 4L || second == 5L) return BREACH_NO;
        if (second >= 6L && second <= 8L) return BREACH_YES;
        return BREACH_UNKNOWN;
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
        if (isRallyType(type)) return ACT_RALLY;
        return ACT_MARCH;
    }

    /** 集结相关类型：10/12 集结中、99 集结随从、100 集结、102 集结主体。 */
    static boolean isRallyType(long type) {
        return type == 10L || type == 12L || type == 99L || type == 100L || type == 102L;
    }

    /**
     * 敌情行军类型（2026-11-02）：1 侦查 / 2 进攻 / 10、12 集结中 / 99 集结随从 /
     * 100 集结 / 102 集结主体。采集(4)、驻防(5)、狩猎(3)、打野(302)、建设援助等一律不推。
     */
    static boolean isPvpMarch(long type) {
        return type == 1L || type == 2L || type == 10L || type == 12L
                || type == 99L || type == 100L || type == 102L;
    }

    /** 行为映射（v4.1.8）：集结协议=集结城堡；type 1=侦查 2=进攻城堡 5=驻防；余=出征。 */
    static String behaviorOf(String proto, long type) {
        if (proto != null && proto.contains("mass_march")) return BEH_RALLY;
        if (type == 1L) return BEH_RECON;
        if (type == 2L) return BEH_ATTACK;
        if (type == 5L) return BEH_GARRISON;
        if (isRallyType(type)) return BEH_RALLY;
        return BEH_MARCH;
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

    /** 发起人坐标：march.startpoint{x,y}；取不到 null。 */
    static int[] startPointOf(Map<String, Object> m) {
        Map<String, Object> p = Exec.mapOf(m.get("startpoint"));
        if (p == null) return null;
        int x = (int) Exec.longOf(p.get("x"));
        int y = (int) Exec.longOf(p.get("y"));
        if (x == 0 && y == 0) return null;
        return new int[]{x, y};
    }

    /**
     * 兵力汇总（squad_list.soldier，形状宽容）：map 键=id 值=数量（或 {count:n}）、
     * list 元素={id,count}；输出「盾T10×1200 矛T9×800（合计2000）」，无数据=未知。
     * 士兵 id=兵种*10000+等级*100（1盾2矛3弓）。
     */
    static String troopsOf(Map<String, Object> m) {
        Map<String, Object> squad = Exec.mapOf(m.get("squad_list"));
        Object soldier = squad == null ? null : squad.get("soldier");
        TreeMap<Long, Long> counts = new TreeMap<>();
        if (soldier instanceof Map) {
            for (Map.Entry<?, ?> en : ((Map<?, ?>) soldier).entrySet()) {
                long id = Exec.longOf(en.getKey());
                Object v = en.getValue();
                long cnt = 0;
                if (v instanceof Number) {
                    cnt = ((Number) v).longValue();
                } else {
                    Map<String, Object> vm = Exec.mapOf(v);
                    if (vm != null) {
                        cnt = Exec.longOf(vm.get("count"));
                        if (id <= 0) id = Exec.longOf(vm.get("id"));
                    }
                }
                if (id > 0 && cnt > 0) counts.merge(id, cnt, Long::sum);
            }
        } else if (soldier instanceof List) {
            for (Object o : (List<?>) soldier) {
                Map<String, Object> vm = Exec.mapOf(o);
                if (vm == null) continue;
                long id = Exec.longOf(vm.get("id"));
                long cnt = Exec.longOf(vm.get("count"));
                if (id > 0 && cnt > 0) counts.merge(id, cnt, Long::sum);
            }
        }
        if (counts.isEmpty()) return TROOPS_UNKNOWN;
        StringBuilder sb = new StringBuilder();
        long total = 0;
        for (Map.Entry<Long, Long> en : counts.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(soldierLabel(en.getKey())).append('×').append(en.getValue());
            total += en.getValue();
        }
        sb.append("（合计").append(total).append('）');
        return sb.toString();
    }

    /** 士兵 id → 显示名：1盾2矛3弓 + T等级（id%10000/100）；未知兵种=兵{id/10000}。 */
    static String soldierLabel(long id) {
        long kind = id / 10000L;
        long tier = (id % 10000L) / 100L;
        String k;
        switch ((int) kind) {
            case 1: k = "盾"; break;
            case 2: k = "矛"; break;
            case 3: k = "弓"; break;
            default: k = "兵" + kind;
        }
        return tier > 0 ? k + "T" + tier : k;
    }

    // ---------------------------------------------------------------- 企微文案与静默

    /**
     * 目标城显示名（2026-11-02）：整图城缓存里的昵称（地图昵称一般已带 {@code [SYD]} 前缀），
     * 缓存没有昵称时用玩家目录补，最后按 aid 反查联盟简称拼 {@code [abbr]}；都没有返回空串。
     */
    static String targetLabelAt(int x, int y) {
        if (x <= 0 || y <= 0) return "";
        com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo c =
                com.carlos.vaultserver.watchpoint.WatchPointEngine.cityAt(x, y);
        if (c == null) return "";
        String nick = c.nick == null ? "" : c.nick.trim();
        if (!nick.isEmpty() && nick.startsWith("[")) return nick;   // 已带联盟前缀，直接用
        String abbr = "";
        com.carlos.vaultserver.watchpoint.PlayerDirectory dir =
                com.carlos.vaultserver.watchpoint.PlayerDirectory.shared();
        if (dir != null) {
            if (nick.isEmpty()) {
                com.carlos.vaultserver.watchpoint.PlayerDirectory.Entry e = dir.byUid(c.uid);
                if (e != null && e.nick != null) nick = e.nick.trim();
            }
            abbr = dir.abbrOfAid(c.aid);
        }
        if (nick.isEmpty()) return abbr.isEmpty() ? "" : "[" + abbr + "]";
        if (abbr.isEmpty() || nick.startsWith("[")) return nick;
        return "[" + abbr + "]" + nick;
    }

    /**
     * 企微文本（v4.1.8 按用户 6 项重写）：行为/发起人/发起人坐标/目标/兵力/到达 + 来源 + 会话；
     * 结算事件（phase=settle）以「🏁 进攻结算」行替代到达倒计时。
     */
    String wecomText(String fpid, JSONObject event) {
        String behavior = event.optString("behavior", "");
        if (behavior.isEmpty()) behavior = event.optString("action", "");
        StringBuilder sb = new StringBuilder("⚔️ 战争警报·").append(behavior.isEmpty() ? "动静" : behavior);
        // 2026-11-02 用户要求：通知里不带 uid/aid 等编号，只留昵称与坐标
        String attackerName = event.optString("attackerName", "");
        sb.append("\n发起人：").append(attackerName.isEmpty() ? "未知名玩家" : attackerName);
        sb.append("\n发起人坐标：");
        Object sp = event.optJSONObject("startPoint");
        if (sp instanceof JSONObject) {
            sb.append('(').append(((JSONObject) sp).optInt("x"))
                    .append(',').append(((JSONObject) sp).optInt("y")).append(')');
        } else {
            sb.append("未知");
        }
        sb.append("\n目标：");
        Object ep = event.optJSONObject("endpoint");
        if (ep instanceof JSONObject) {
            int tx = ((JSONObject) ep).optInt("x");
            int ty = ((JSONObject) ep).optInt("y");
            // 2026-11-02：目标行带上被攻击人的昵称 + 联盟简称（地图昵称通常自带 [SYD] 前缀）
            String label = targetLabelAt(tx, ty);
            if (!label.isEmpty()) sb.append(label);
            sb.append('(').append(tx).append(',').append(ty).append(')');
        } else {
            sb.append("未知");
        }
        String troops = event.optString("troops", "");
        sb.append("\n兵力：").append(troops.isEmpty() ? TROOPS_UNKNOWN : troops);
        boolean settle = PHASE_SETTLE.equals(event.optString("phase", ""));
        long endSec = event.optLong("endtime", 0);
        if (!settle && endSec > 0) {
            long nowS = System.currentTimeMillis() / 1000L;
            sb.append("\n到达：").append(Beijing.fmt("HH:mm:ss").format(new java.util.Date(endSec * 1000L)));
            if (endSec > nowS) {
                long eta = endSec - nowS;
                sb.append("（约 ");
                if (eta >= 60) sb.append(eta / 60).append(" 分 ");
                sb.append(eta % 60).append(" 秒后）");
            }
        }
        if (settle) {
            sb.append("\n🏁 进攻结算：").append(displayOutcome(event.optString("outcome", "")))
                    .append("，从发起到完成耗时").append(fmtDuration(event.optLong("durationSeconds", 0)));
            String breach = event.optString("breach", "");
            if (!breach.isEmpty()) {
                sb.append("；破城：").append(breach);
                String codes = event.optString("battleCodes", "");
                if (!codes.isEmpty()) sb.append("（战报 ").append(codes).append('）');
            }
        }
        return sb.toString();
    }

    /** outcome 事件值 → 文案：撤回→已撤回、已到达→已到达（余原样）。 */
    static String displayOutcome(String outcome) {
        if (ACT_RECALL.equals(outcome)) return "已撤回";
        return outcome == null || outcome.isEmpty() ? "已到达" : outcome;
    }

    /** 秒 → 「X 分 Y 秒」（&lt;60s 只显示秒；负数按 0）。 */
    static String fmtDuration(long sec) {
        long s = Math.max(0, sec);
        if (s < 60) return s + " 秒";
        return (s / 60) + " 分 " + (s % 60) + " 秒";
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

    // ---------------------------------------------------------------- 内部

    /** 企微发送（wecomExec/调用线程）：先补齐昵称再渲染文案。 */
    private void sendWecom(VaultStore.WatchConfig cfg, Track t, JSONObject stored) {
        try {
            ensureNameResolved(t);
        } catch (Throwable tt) {
            FileLog.info("企微昵称补齐失败 uid={}：{}", t.targetUid, String.valueOf(tt));
        }
        if (!t.attackerName.equals(stored.optString("attackerName", ""))) {
            stored.put("attackerName", t.attackerName);   // 只影响本次文案；库内快照不变
        }
        notifier.send(cfg.webhookUrl, wecomText(t.fpid, stored));
    }

    /** 冷却节流（compute 原子；命中返回 true）。 */
    private boolean throttled(String key, long cooldownMs, long now) {
        long[] prev = new long[1];
        cooldown.compute(key, (k, v) -> {
            if (v != null && now - v < cooldownMs) {
                prev[0] = v;
                return v;
            }
            return now;
        });
        return prev[0] > 0;
    }

    /** 周期回收：跟踪表 TTL、战斗邮件 TTL、侦查配额窗口外记录（防长期运行无界增长）。 */
    private void sweep(long now) {
        for (Map.Entry<Long, Track> e : tracked.entrySet()) {
            if (e.getValue().endServerSec * 1000L + TRACK_TTL_MS < now) tracked.remove(e.getKey());
        }
        for (ConcurrentLinkedDeque<MailRec> dq : recentMails.values()) {
            dq.removeIf(m -> now - m.at > MAIL_TTL_MS);
        }
        scoutQuota.sweep(now);   // 侦查配额表回收窗口外记录，防长期运行无界增长
    }

    /** 监视目标显示名：备注 &gt; 名称 &gt; 「未知名玩家」（通知里不带 uid）。 */
    private static String displayOf(VaultStore.WatchTarget t) {
        if (t.note != null && !t.note.isEmpty()) return t.note;
        if (t.name != null && !t.name.isEmpty()) return t.name;
        return "未知名玩家";
    }

    /** 昵称缓存条目。 */
    private static final class NameEntry {
        final String name;
        final long at;

        NameEntry(String name, long at) {
            this.name = name;
            this.at = at;
        }
    }

    /** 一条战斗邮件推送的最小记录。 */
    static final class MailRec {
        final long at;
        final long first;
        final long second;

        MailRec(long at, long first, long second) {
            this.at = at;
            this.first = first;
            this.second = second;
        }
    }

    /** 待结算记录（del 建立 → 宽限后 finalize；breach 在 finalize 期判定）。 */
    private static final class Settle {
        final Track t;
        final long delAtS;
        /** del 毫秒时刻（战报最近邻锚点；不能用 秒*1000——截断会让 ±1s 内的战报排序反转）。 */
        final long delAtMillis;
        final boolean recall;
        String breach = "";
        String battleCodes = "";

        Settle(Track t, long delAtS, long delAtMillis) {
            this.t = t;
            this.delAtS = delAtS;
            this.delAtMillis = delAtMillis;
            this.recall = ACT_RECALL.equals(t.action);
        }
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
        /** 发起时间（starttime 秒；0=未知）。 */
        volatile long startServerSec;
        /** 发起人坐标。 */
        int sx;
        int sy;
        boolean hasStart;
        /** 行为（侦查/进攻城堡/集结城堡/驻防/出征）。 */
        String behavior = BEH_MARCH;
        /** 兵力汇总文案（未知=无 squad 数据）。 */
        String troops = TROOPS_UNKNOWN;
        /** 侦查推送超限被静默：初始事件未推送，其撤回/到达结算也不再推送。 */
        volatile boolean muted;
        /** 发起人显示名（昵称解析成功后异步升级；初值=监视备注/名称/uid）。 */
        volatile String attackerName;
        long marchType;

        Track(long marchId, String fpid, long sid, long targetUid, String targetName,
              String action, long endServerSec, int[] xy, String rawProto) {
            this.marchId = marchId;
            this.fpid = fpid;
            this.sid = sid;
            this.targetUid = targetUid;
            this.targetName = targetName;
            this.attackerName = targetName;   // 初值与监视显示名一致，解析成功后升级
            this.action = action;
            this.endServerSec = endServerSec;
            this.ex = xy == null ? 0 : xy[0];
            this.ey = xy == null ? 0 : xy[1];
            this.hasXY = xy != null;
            this.rawProto = rawProto == null ? "" : rawProto;
        }
    }

    // ---------------------------------------------------------------- 测试钩子

    /** 已跟踪行军数（测试/诊断）。 */
    int trackedCount() {
        return tracked.size();
    }

    /** 待结算数（测试）。 */
    int pendingCount() {
        return pending.size();
    }

    /** 指定行军当前的发起人显示名（测试异步回填用）。 */
    String trackedNameForTest(long marchId) {
        Track t = tracked.get(marchId);
        return t == null ? "" : t.attackerName;
    }

    /** 指定 uid 昵称缓存是否已有条目（测试异步回填同步点用）。 */
    boolean nameCachedForTest(String fpid, long uid) {
        return nameCache.containsKey(fpid + "|" + uid);
    }

    /** 冷却表剩余条数（测试）。 */
    int cooldownCount() {
        return cooldown.size();
    }

    /** 测试用：清空冷却表。 */
    void resetCooldownForTest() {
        cooldown.clear();
    }

    /** 侦查配额表剩余敌人数（测试）。 */
    int scoutQuotaCount() {
        return scoutQuota.trackedEnemies();
    }

    /** 指定行军是否被侦查限流静默（测试）。 */
    boolean mutedForTest(long marchId) {
        Track t = tracked.get(marchId);
        return t != null && t.muted;
    }
}
