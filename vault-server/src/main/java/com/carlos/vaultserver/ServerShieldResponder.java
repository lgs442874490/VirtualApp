package com.carlos.vaultserver;

import com.carlos.vault.wjdr.GameServerClock;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 反应式开盾（P1-2 服务器补位，承接 App DailySchedulerService.onGameEvent 改 no-op 后的缺口）：
 * 服务器模式"被攻击/侦查自动开免费盾"的常驻实现，行为对齐原版 WAR/RECON 分支。
 *
 * <ul>
 *   <li>事件源 = 世界行军推送（协议实证见 design/守护盟友-协议实证-20261015.md）：
 *     notify_world_situation_info_add/_up（观察哨系列）与 notify_world_alliance_situation_add/_update
 *     （目标系列）。type=1=侦查(RECON)、type=2=进攻(WAR)（type=5 驻防不触发），target_type=1=攻玩家城；
 *     目标 uid（atk_city.targetid 或 target.map_city.uid）必须等于本角色 uid——只防自己城，
 *     不为邻城 attack 浪费每日免费盾；</li>
 *   <li>推送线程只解析+置脏（同 ServerHelpResponder 模式），开盾动作由会话线程 tick 驱动，
 *     与心跳/采集天然串行（engine.requestLock 串行，无并发互踢）；</li>
 *   <li>门控（2026-10-24 扩展）：总开关 battle_shield.enabled（缺省 true，关=定时免费盾与反应式都停）
 *     + 账号自动化开启（auto/keep 统一开关）+ 子开关 battle_shield.reactive != false（默认 true，
 *     App FnDetail 同款语义）；</li>
 *   <li>触发策略（strategy 子项，可独立配置）：
 *     <ul>
 *       <li>troops_min：来袭兵力下限（march.squad_list.soldier 求和，与 WarNotify.troopsOf 同口径）；
 *         已知且低于阈值→跳过留痕；兵力未知→按保护优先照常触发并留痕；</li>
 *       <li>eta_sec：卡秒——&gt;0 时记录敌方到达服务器秒（endtime），剩余 &gt; N 秒则挂起等待
 *         （保持 pending，不用 10 分钟 EVENT_TTL 判死）；剩余 ≤ N 秒才开盾；0 保持"收到即开"旧行为；</li>
 *     </ul></li>
 *   <li>force_online：{@link #forceOnlineWanted()} 供 SessionKeeper 在非 online 分支查询——
 *     存在新鲜风险（未过期/未到达）且总开关+reactive+force_online 均开时返回 true，由会话管理器
 *     强制回连；本类只回答"要不要回连"，不自己登录；</li>
 *   <li>幂等：开盾成功（8 小时护盾）后 8 小时内不重复开；其余结果（今日已用/协议异常/错误码）
 *     5 分钟冷却，防推送风暴；eta_sec=0 时事件超过 10 分钟才被处理则丢弃——human_first 让位/掉线期
 *     积压的事件自然过期，回连后不为旧攻击补盾（eta_sec&gt;0 的挂起风险不受此限）；</li>
 *   <li>让位保护：tick 只在会话 online 时执行（LiveSession 亦只在 online 分支调用），人玩期间
 *     服务器不发任何请求，不互踢；</li>
 *   <li>留痕：FileLog + mod:fpid:sid:battle_shield（reactive 子项，App FnDetail/今日页同键展示，
 *     文案区分 总开关关闭/兵力不足/等待卡秒/已触发）。</li>
 * </ul>
 */
public final class ServerShieldResponder {

    // ---------------- 协议常量（实证口径，勿凭猜测改） ----------------

    /** 行军类型：1=侦查 2=进攻 100=集结进攻（5=驻防/增援不触发护盾）。 */
    private static final long MARCH_RECON = 1L;
    private static final long MARCH_WAR = 2L;
    /** 集结进攻（飞艇/集结）同样攻玩家城：按 WAR 触发护盾（对齐网页版 RALLY_CITY_ATTACK）。 */
    private static final long MARCH_RALLY = 100L;
    /** 目标类型：1=玩家城（打野/情报类 target_type 非 1，不触发）。 */
    private static final long TARGET_CITY = 1L;
    /** 目标类型：2=地图资源点（采集点遭袭识别用，对齐网页版 targetsOwnResource）。 */
    private static final long TARGET_RESOURCE = 2L;
    /** 目标采集行军类型：4=个人采集；110=联盟矿采集（仅识别，不自动撤联盟矿）。 */
    private static final long MARCH_GATHER = 4L;
    private static final long MARCH_ALLIANCE_GATHER = 110L;
    /** 单条采集行军撤矿的最小重试间隔（同 marchId）。 */
    static final long GATHER_RECALL_COOLDOWN_MILLIS = 10 * 60_000L;

    /** WAR/RECON 行军推送源。 */
    static final Set<String> SITUATION_PROTOS = Set.of(
            "notify_world_situation_info_add",
            "notify_world_situation_info_up",
            "notify_world_alliance_situation_add",
            "notify_world_alliance_situation_update");

    /** 开盾成功后的幂等窗：8 小时免费护盾时长内不重复开。 */
    static final long SHIELD_ACTIVE_MILLIS = 8 * 3600_000L;
    /** 非成功结果的重试冷却：防推送风暴把协议打爆。 */
    static final long RETRY_COOLDOWN_MILLIS = 5 * 60_000L;
    /** 事件保鲜期：推送到达超过该时长才处理则丢弃（仅 eta_sec=0 的旧行为路径）。 */
    static final long EVENT_TTL_MILLIS = 10 * 60_000L;
    /** 事件保鲜期（秒，卡秒路径判断"威胁早已到达"用）。 */
    static final long EVENT_TTL_SEC = EVENT_TTL_MILLIS / 1000L;

    // ---------------- 风险状态 ----------------

    /** 一条推送命中"自己城被侦查/进攻"后的决策输入（兵力/到达时刻用于阈值与卡秒）。 */
    private static final class Risk {
        /** WAR / RECON（严重度优先）。 */
        final String kind;
        /** 敌方到达服务器秒（多条命中取最早）；0=未知。 */
        final long endtimeSec;
        /** squad_list.soldier 求和；-1=未知。 */
        final long troops;

        Risk(String kind, long endtimeSec, long troops) {
            this.kind = kind;
            this.endtimeSec = endtimeSec;
            this.troops = troops;
        }
    }

    /** 自己正在采集的行军（来源：核心资产库 QUEUE；type=4 且 state=3 才算“采集驻留”）。 */
    static final class GatherMarch {
        final long marchId;
        final int x;
        final int y;

        GatherMarch(long marchId, int x, int y) {
            this.marchId = marchId;
            this.x = x;
            this.y = y;
        }
    }

    /** 自己采集行军来源（生产读核心资产库；单测可注入，避免依赖资产库 READY）。 */
    interface MarchSource {
        List<GatherMarch> ownGatheringMarches(WjdrEngine engine);
    }

    /** 一次采集点遭袭告警（钩子线程只入账，撤矿动作交给 tick 线程）。 */
    private static final class GatherAlert {
        final long enemyMarchId;
        final long endtimeSec;
        final int x;
        final int y;
        /** 命中的自己采集行军（可能为空：只有资源点归属能判、资产库未 READY）。 */
        final List<GatherMarch> ownMarches;
        /** 是否由 target.map_resource.uid==本人 直接判定（而非仅坐标匹配）。 */
        final boolean ownResource;

        GatherAlert(long enemyMarchId, long endtimeSec, int x, int y,
                    List<GatherMarch> ownMarches, boolean ownResource) {
            this.enemyMarchId = enemyMarchId;
            this.endtimeSec = endtimeSec;
            this.x = x;
            this.y = y;
            this.ownMarches = ownMarches == null ? Collections.<GatherMarch>emptyList() : ownMarches;
            this.ownResource = ownResource;
        }
    }

    // ---------------- 状态 ----------------

    private final String fpid;
    private final long sid;
    private final StateStore store;
    private final VaultStore vault;

    /** 本机毫秒时间源（生产 System.currentTimeMillis；单测注入以便驱动卡秒）。 */
    private final LongSupplier nowMs;
    /** 服务器秒时间源（单测注入；null=生产走 engine.serverClock()，未校准退化本机秒）。 */
    private final LongSupplier serverNowSecSource;
    /** attach 时记录的引擎：forceOnlineWanted() 无引擎入参，取服务器时钟用。 */
    private volatile WjdrEngine engineRef;
    /** 自己采集行军来源（默认读核心资产库；测试可注入）。 */
    private volatile MarchSource marchSource = this::ownGatheringFromAssetLibrary;
    /** 最近一次命中的新鲜风险（未处理/挂起中）；null=无风险。 */
    private volatile Risk risk;
    /** 最近一次采集点遭袭告警（钩子线程写入，tick 消费）。 */
    private volatile GatherAlert gatherAlert;
    /** 采集告警脏标记（与 city risk 的 dirty 分离：采集撤矿与开盾是两条独立反应链）。 */
    private final AtomicBoolean gatherDirty = new AtomicBoolean(false);
    /** 已撤矿 marchId → 最近一次撤矿时刻（防同威胁重复请求；10min 冷却）。 */
    private final Map<Long, Long> gatherRecalledAt = new ConcurrentHashMap<>();
    /** 风险写入/清除互斥（推送线程 vs 会话线程），保证"处理旧风险时新推送不丢"。 */
    private final Object riskLock = new Object();
    /** 卡秒挂起下一次判定时刻（本机毫秒；0=无挂起）。 */
    volatile long etaCheckAtMs;
    /** 服务器时钟未校准告警只打一次（每 responder 实例）。 */
    private final AtomicBoolean clockWarned = new AtomicBoolean(false);

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** 冷却截止时刻（包可见：单测断言幂等窗/重试窗）。 */
    final AtomicLong nextAttemptAt = new AtomicLong(0);
    /** 最近一次 WAR/RECON 推送时刻（包可见：单测模拟陈旧事件）。 */
    volatile long lastEventAt;
    /** 最近一次事件类型 WAR/RECON（包可见：单测断言）。 */
    volatile String lastEventKind = "";

    ServerShieldResponder(String fpid, long sid, StateStore store, VaultStore vault) {
        this(fpid, sid, store, vault, System::currentTimeMillis, null);
    }

    /**
     * 单测构造重载：注入本机毫秒与服务器秒时间源（生产用上一个构造，等价于
     * nowMs=System.currentTimeMillis、服务器秒优先 engine.serverClock()）。
     */
    ServerShieldResponder(String fpid, long sid, StateStore store, VaultStore vault,
                          LongSupplier nowMs, LongSupplier serverNowSec) {
        this.fpid = fpid;
        this.sid = sid;
        this.store = store;
        this.vault = vault;
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
        this.serverNowSecSource = serverNowSec;
    }

    // ---------------- 挂载与 tick ----------------

    /** 挂到引擎消息钩子（推送线程调用：只解析+置脏，不发请求）。 */
    void attach(WjdrEngine engine) {
        engineRef = engine;
        engine.addMessageHook((proto, content) -> {
            if (!SITUATION_PROTOS.contains(proto)) return;
            try {
                onPush(content, engine.lastUid(), engine);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 推送入口（钩子线程）：命中"自己城被侦查/进攻"记入开盾风险；
     * 命中"自己的采集点遭袭"记入采集告警（两条反应链独立）。
     * 包可见供单测直接驱动（不经过引擎钩子分发）。
     */
    void onPush(Map<String, Object> content, long ownUid) {
        onPush(content, ownUid, engineRef);
    }

    /** 推送入口（带引擎：采集行军来源需要读核心资产库；engine 可 null=测试无资产库）。 */
    void onPush(Map<String, Object> content, long ownUid, WjdrEngine engine) {
        Risk r = riskOf(content, ownUid);
        if (r != null) {
            synchronized (riskLock) {
                risk = r;
                lastEventKind = r.kind;
                lastEventAt = nowMs.getAsLong();
                etaCheckAtMs = 0;
                dirty.set(true);
            }
        }
        GatherAlert g = gatherAlertOf(content, ownUid, engine);
        if (g != null) {
            gatherAlert = g;
            gatherDirty.set(true);
        }
    }

    /**
     * 会话线程 tick 驱动：先处理采集点遭袭（提醒/可选撤矿），再处理开盾风险。
     * 返回 true 表示本轮有动作（发请求/跳过留痕），false=无动作/挂起/冷却/陈旧。
     */
    boolean tick(WjdrEngine engine) {
        // 让位/恢复/登录中：不发请求（人玩优先，防互踢）；风险保留，回连如仍新鲜才补
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return false;
        long now = nowMs.getAsLong();
        boolean acted = false;
        GatherAlert g = gatherAlert;
        if (g != null && gatherDirty.compareAndSet(true, false)) {
            acted = handleGather(engine, g, now);
        }
        boolean fresh = dirty.compareAndSet(true, false);   // 先消费脏标记再读风险：避免与推送线程竞态丢事件
        Risk r = risk;
        if (r == null) return acted;
        if (!fresh) {
            // 无新推送：仅"卡秒挂起"到点才继续推进（等待期间保持 pending，不受 10min 判死）
            if (etaCheckAtMs <= 0 || now < etaCheckAtMs) return acted;
        }
        return decide(engine, r, now) || acted;
    }

    // ---------------- 决策（会话线程内执行） ----------------

    private boolean decide(WjdrEngine engine, Risk r, long now) {
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：账号自动化未开启", fpid);
            saveState(null, "自动化未开启，未反应式开盾");
            return true;
        }
        TaskContext tc = new TaskContext(engine, fpid, sid, store, paramsOf(acc, sid), "反应式");
        // 门控链（设计 §2.2）：总开关 → reactive → 账号自动化
        if (!tc.subOn("battle_shield", "enabled", true)) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：总开关 battle_shield.enabled 已关闭", fpid);
            saveState(tc, "总开关已关闭（App 设置页可开）");
            return true;
        }
        if (!tc.subOn("battle_shield", "reactive", true)) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：子开关 battle_shield.reactive 已关闭", fpid);
            saveState(tc, "反应式开盾已关闭（App 设置页可开）");
            return true;
        }
        if (!(acc.auto || acc.keep)) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：账号自动化未开启", fpid);
            saveState(tc, "自动化未开启，未反应式开盾");
            return true;
        }
        String prefix = "WAR".equals(r.kind) ? "被进攻" : "被侦查";
        // 卡秒 ETA：eta_sec=0 保持"收到即开"旧行为；>0 时等到"到达前 eta_sec 秒"再开
        long etaSec = numOf(tc.strategy("battle_shield", "eta_sec", "0"));
        if (etaSec > 0) {
            if (r.endtimeSec > 0) {
                long remain = r.endtimeSec - serverNowSec(engine);
                if (remain > etaSec) {
                    // 挂起：保持 pending，本拍不发请求；到点由 tick 的 etaCheckAtMs 继续推进
                    // （与 onPush/clearRisk 的写入同一把锁，避免旧值覆盖新推送的清零）
                    synchronized (riskLock) {
                        etaCheckAtMs = now + Math.max(200L, (remain - etaSec) * 1000L);
                    }
                    saveState(tc, prefix + " → 等待卡秒：约" + remain + "秒后到达，到达前"
                            + etaSec + "秒开盾");
                    FileLog.info("反应式开盾挂起 fpid={} 事件={}：约{}秒后到达（卡秒{}秒）",
                            fpid, r.kind, String.valueOf(remain), String.valueOf(etaSec));
                    return false;
                }
                if (remain < -EVENT_TTL_SEC) {
                    // 到达已超过保鲜期：为旧攻击补盾无意义（卡秒路径唯一的时间上界）
                    clearRisk(r);
                    FileLog.info("反应式开盾丢弃 fpid={}：威胁已到达超过{}秒",
                            fpid, String.valueOf(EVENT_TTL_SEC));
                    return false;
                }
            } else {
                // 推送未带 endtime：无法卡秒，按保护优先立即开盾并留痕
                FileLog.info("反应式开盾 fpid={} 命中{}风险但缺 endtime，按保护优先立即开盾",
                        fpid, r.kind);
            }
        } else if (now - lastEventAt > EVENT_TTL_MILLIS) {
            // 陈旧事件（让位期积压）：静默丢弃，不为旧攻击补盾（与 master 行为一致）
            clearRisk(r);
            return false;
        }
        // 兵力阈值：已知且低于 → 跳过留痕；未知 → 保护优先照常触发并留痕
        long troopsMin = numOf(tc.strategy("battle_shield", "troops_min", "0"));
        if (troopsMin > 0) {
            if (r.troops < 0) {
                FileLog.info("反应式开盾 fpid={} 兵力未知，按保护优先照常触发（阈值{}）",
                        fpid, String.valueOf(troopsMin));
            } else if (r.troops < troopsMin) {
                clearRisk(r);
                saveState(tc, prefix + " → 来袭兵力" + r.troops + "低于阈值" + troopsMin + "，未开盾");
                FileLog.info("反应式开盾跳过 fpid={}：来袭兵力{}低于阈值{}",
                        fpid, String.valueOf(r.troops), String.valueOf(troopsMin));
                return true;
            }
        }
        if (now < nextAttemptAt.get()) {   // 幂等（8h 护盾窗）/防风暴冷却（5min）
            clearRisk(r);
            return false;
        }
        if (!busy.compareAndSet(false, true)) return false;
        try {
            openShield(tc, now);
        } finally {
            busy.set(false);
        }
        clearRisk(r);
        return true;
    }

    /** 真正下发免费盾请求（复用整轮执行器：free_id 动态取，码表归 SKIPPED）。 */
    private void openShield(TaskContext tc, long now) {
        // 执行器内部已捕获 Exception 并分类，此处再兜一层 Throwable，防止 tick 链被打断
        TaskOutcome r;
        try {
            r = ServerTasks.freeShield(tc);
        } catch (Throwable t) {
            r = ServerTasks.classifyException(t, "战斗护盾");
        }
        nextAttemptAt.set(now + (r.category == TaskOutcome.Category.CLAIMED
                ? SHIELD_ACTIVE_MILLIS : RETRY_COOLDOWN_MILLIS));
        String text = ("WAR".equals(lastEventKind) ? "被进攻" : "被侦查") + " → " + r.message;
        saveState(tc, text);
        FileLog.info("反应式开盾 fpid={} 事件={} 结果={}：{}", fpid, lastEventKind,
                String.valueOf(r.category), r.message);
    }

    // ---------------- 采集点遭袭（2026-10-24：对齐网页版 GATHERING_ATTACK） ----------------

    /**
     * 处理一次采集点遭袭：写 App 状态提醒；`sub.gather_recall=true` 时对命中的自己采集行军
     * 发 {@code req_world_march_off_work} 撤矿（同 marchId 10 分钟冷却，防重复请求）。
     *
     * <p>语义来源（网页版 wjdr-review，1:1 对齐）：
     * {@code DailyBattleState.appendThreats} 对 type=4/110 且 {@code target.map_resource.uid==ownUid}
     * 的来袭行军，用 endpoint 匹配自己 type=4、state=3 的采集行军；命中后
     * {@code DailyBattleService.handleGatheringThreat} 发 {@code req_world_march_off_work {id}}。
     */
    private boolean handleGather(WjdrEngine engine, GatherAlert g, long now) {
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null || !(acc.auto || acc.keep)) {
            saveStateText(null, "gather", "自动化未开启，未处理采集告警");
            return true;
        }
        TaskContext tc = new TaskContext(engine, fpid, sid, store, paramsOf(acc, sid), "反应式");
        if (!tc.subOn("battle_shield", "enabled", true)) {
            saveStateText(tc, "gather", "总开关已关闭（App 设置页可开）");
            return true;
        }
        if (!tc.subOn("battle_shield", "gather_attack", true)) {
            FileLog.info("采集点遭袭跳过 fpid={}：子开关 battle_shield.gather_attack 已关闭", fpid);
            saveStateText(tc, "gather", "采集点遭袭提醒已关闭");
            return true;
        }
        StringBuilder text = new StringBuilder("采集点遭袭");
        if (g.ownResource) text.append("（资源点归属本人）");
        if (g.x > 0 || g.y > 0) text.append(" @(").append(g.x).append(",").append(g.y).append(")");
        if (g.enemyMarchId > 0) text.append(" 敌行军#").append(g.enemyMarchId);
        if (g.ownMarches.isEmpty()) {
            text.append(" · 未定位到在采队伍");
        } else {
            text.append(" · 在采队伍");
            for (GatherMarch m : g.ownMarches) text.append(" #").append(m.marchId);
        }
        boolean recall = tc.subOn("battle_shield", "gather_recall", false);
        String result = "";
        if (recall && !g.ownMarches.isEmpty()) {
            List<String> done = new ArrayList<>();
            for (GatherMarch m : g.ownMarches) {
                Long last = gatherRecalledAt.get(m.marchId);
                if (last != null && now - last < GATHER_RECALL_COOLDOWN_MILLIS) {
                    done.add("#" + m.marchId + "（冷却中）");
                    continue;
                }
                gatherRecalledAt.put(m.marchId, now);
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("id", m.marchId);
                    com.carlos.vault.wjdr.protocol.GameMessage rsp =
                            engine.request("req_world_march_off_work", args);
                    int code = Exec.codeOf(rsp);
                    if (code == 0) {
                        done.add("#" + m.marchId + " 已撤");
                        FileLog.info("采集点遭袭撤矿成功 fpid={} marchId={}", fpid, String.valueOf(m.marchId));
                    } else {
                        done.add("#" + m.marchId + " 失败code=" + code);
                        FileLog.warn("采集点遭袭撤矿失败 fpid={} marchId={} code={}",
                                fpid, String.valueOf(m.marchId), String.valueOf(code));
                    }
                } catch (Throwable t) {
                    done.add("#" + m.marchId + " 异常");
                    FileLog.warn("采集点遭袭撤矿异常 fpid={} marchId={}：{}",
                            fpid, String.valueOf(m.marchId), String.valueOf(t));
                }
            }
            if (!done.isEmpty()) result = " · 自动撤矿 " + String.join("、", done);
        } else if (recall) {
            result = " · 自动撤矿已开但未定位队伍";
        }
        String line = text + result;
        saveStateText(tc, "gather", line);
        FileLog.info("采集点遭袭 fpid={}：{}", fpid, line);
        return true;
    }

    /** 自己正在采集（type=4、state=3）的行军：生产读核心资产库 QUEUE。 */
    private List<GatherMarch> ownGatheringFromAssetLibrary(WjdrEngine engine) {
        try {
            if (engine == null) return Collections.emptyList();
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            if (lib == null || !lib.ready() || lib.queue() == null) return Collections.emptyList();
            List<GatherMarch> out = new ArrayList<>();
            for (AssetSnapshot.QueueMarch m : lib.queue().marches) {
                if (m == null) continue;
                if (m.type != MARCH_GATHER || m.state != 3L) continue;   // 4=采集、3=已到达/采集中
                if (m.endX <= 0 || m.endY <= 0) continue;
                out.add(new GatherMarch(m.id, (int) m.endX, (int) m.endY));
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** 单测注入采集行军来源（避免依赖资产库 READY）。 */
    void setMarchSourceForTest(MarchSource source) {
        this.marchSource = source;
    }

    /**
     * 从一条 situation 推送中识别「自己的采集点遭袭」。
     * 判定（网页版 appendThreats 同口径）：来袭 type=4/110、目标 {@code target.type=2} 且
     * {@code target.map_resource.uid==本人}；同时用 endpoint 匹配自己正在采集的 type=4/state=3 行军。
     * 仅坐标匹配也接受（资源点 uid 推送缺失时的兜底）。
     */
    GatherAlert gatherAlertOf(Map<String, Object> content, long ownUid, WjdrEngine engine) {
        if (content == null || ownUid <= 0) return null;
        List<GatherMarch> own = marchSource == null
                ? Collections.<GatherMarch>emptyList() : marchSource.ownGatheringMarches(engine);
        for (Map<String, Object> m : marchesOf(content)) {
            long type = Exec.longOf(m.get("type"));
            if (type != MARCH_GATHER && type != MARCH_ALLIANCE_GATHER) continue;
            Map<String, Object> target = Exec.mapOf(m.get("target"));
            boolean ownResource = false;
            if (target != null) {
                long targetType = Exec.longOf(target.get("type"));
                Map<String, Object> resource = Exec.mapOf(target.get("map_resource"));
                if (resource != null) {
                    long uid = Exec.longOf(resource.get("uid"));
                    if (uid == ownUid) ownResource = true;
                    if (targetType == 0L && uid > 0) targetType = TARGET_RESOURCE;
                }
                if (targetType == TARGET_RESOURCE && !ownResource) {
                    // 目标类型是资源点但归属未知：仅靠坐标匹配兜底（不因此直接判定本人）
                }
            }
            Map<String, Object> endpoint = firstPoint(m);
            int x = endpoint == null ? 0 : (int) Exec.longOf(endpoint.get("x"));
            int y = endpoint == null ? 0 : (int) Exec.longOf(endpoint.get("y"));
            List<GatherMarch> hit = new ArrayList<>();
            for (GatherMarch gm : own) {
                if (gm.x == x && gm.y == y) hit.add(gm);
            }
            if (!ownResource && hit.isEmpty()) continue;   // 既非本人资源点、也不匹配在采队伍：不是我的采集点
            long endSec = Exec.longOf(m.get("endtime"));
            return new GatherAlert(Exec.longOf(m.get("id")), endSec, x, y, hit, ownResource);
        }
        return null;
    }

    /** 行军 endpoint{x,y} → target.map_city{x,y}（网页版 endpointOf 同口径）。 */
    private static Map<String, Object> firstPoint(Map<String, Object> m) {
        Map<String, Object> p = Exec.mapOf(m.get("endpoint"));
        if (p != null && (Exec.longOf(p.get("x")) != 0 || Exec.longOf(p.get("y")) != 0)) return p;
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null && (Exec.longOf(city.get("x")) != 0 || Exec.longOf(city.get("y")) != 0)) {
                return city;
            }
        }
        return null;
    }

    // ---------------- 强制上线查询（SessionKeeper 非 online 分支用） ----------------

    /**
     * 是否请求 SessionKeeper 强制上线（battle_shield.sub.force_online）：
     * 存在"仍新鲜"的风险（未过期/未到达）且总开关、reactive、force_online 三者均开时 true。
     * 本方法不要求会话 online——它正是给"非 online"分支用的；无风险时恒 false。
     */
    public boolean forceOnlineWanted() {
        Risk r = risk;
        if (r == null) return false;
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null) return false;
        // 只读配置：engine 传 null 安全（TaskContext 的开关/策略读取不触碰 engine）
        TaskContext tc = new TaskContext(null, fpid, sid, store, paramsOf(acc, sid), "反应式");
        if (!tc.subOn("battle_shield", "enabled", true)) return false;
        if (!tc.subOn("battle_shield", "reactive", true)) return false;
        if (!tc.subOn("battle_shield", "force_online", false)) return false;
        long etaSec = numOf(tc.strategy("battle_shield", "eta_sec", "0"));
        if (r.endtimeSec > 0) {
            long remain = r.endtimeSec - serverNowSec(engineRef);
            // 卡秒模式只对"尚未到达"的风险强制上线；eta=0 模式到达后保鲜窗内仍算新鲜
            return etaSec > 0 ? remain > 0 : remain > -EVENT_TTL_SEC;
        }
        return nowMs.getAsLong() - lastEventAt <= EVENT_TTL_MILLIS;
    }

    /** 清除当前风险（仅当仍是 snapshot 时，避免覆盖同时到达的新推送）。 */
    private void clearRisk(Risk expected) {
        synchronized (riskLock) {
            if (risk == expected) {
                risk = null;
                etaCheckAtMs = 0;
            }
        }
    }

    // ---------------- 时间与配置读取 ----------------

    /** 当前服务器秒：单测注入优先；生产 engine.serverClock() 已校准则用，未校准退化本机秒。 */
    private long serverNowSec(WjdrEngine engine) {
        if (serverNowSecSource != null) return serverNowSecSource.getAsLong();
        WjdrEngine e = engine != null ? engine : engineRef;
        if (e != null) {
            try {
                GameServerClock ck = e.serverClock();
                if (ck != null && ck.locked()) return ck.nowServerSec();
            } catch (Throwable ignored) {
            }
        }
        if (clockWarned.compareAndSet(false, true)) {
            // 未校准：ETA/卡秒按本机秒推算，本机与游戏服务器时钟偏差会直接变成卡秒偏差
            FileLog.warn("反应式开盾 fpid={}：服务器时钟未校准，ETA 卡秒退化为本机秒（偏差≈本机时钟偏差）", fpid);
        }
        return nowMs.getAsLong() / 1000L;
    }

    /** strategy 数值（如 "60000"/"60000.0"）→ long；无法解析 → 0（=不限/不卡秒）。 */
    private static long numOf(String s) {
        if (s == null) return 0L;
        String t = s.trim();
        if (t.isEmpty()) return 0L;
        int dot = t.indexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        try {
            return Long.parseLong(t);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 写 mod:fpid:sid:battle_shield 的 reactive 子项（App FnDetail/今日页同键展示）。 */
    private void saveState(TaskContext tc, String text) {
        saveStateText(tc, "reactive", text);
    }

    /** 写 mod:fpid:sid:battle_shield 的指定子项（reactive=开盾 / gather=采集点遭袭）。 */
    private void saveStateText(TaskContext tc, String subId, String text) {
        try {
            if (tc != null) {
                tc.saveModText("battle_shield", subId, text);
            } else {
                JSONObject mod = new JSONObject();
                String raw = store.getString("mod:" + fpid + ":" + sid + ":battle_shield");
                if (raw != null) mod = new JSONObject(raw);
                mod.put(subId, text);
                mod.put(subId + "_ts", System.currentTimeMillis());
                mod.put(subId + "_trg", "反应式");
                store.putString("mod:" + fpid + ":" + sid + ":battle_shield", mod.toString());
            }
            store.flush();
        } catch (Throwable ignored) {
        }
    }

    /** 角色任务参数（App FnParams p:fpid:sid:fn 的同步副本）→ TaskContext 形状。 */
    private static Map<String, JSONObject> paramsOf(VaultStore.Account acc, long sid) {
        Map<String, JSONObject> params = new LinkedHashMap<>();
        Map<String, String> raw = acc.roleParams.get(String.valueOf(sid));
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                try {
                    params.put(e.getKey(), new JSONObject(e.getValue()));
                } catch (Throwable ignored) {
                }
            }
        }
        return params;
    }

    // ---------------- 推送解析（与 App EnemyWatch.handleMarch 同口径） ----------------

    /**
     * 判定推送是否含"自己城被侦查/进攻"。
     * 返回 "WAR"（有进攻）/ "RECON"（仅有侦查）/ null（不触发）。
     */
    static String classify(Map<String, Object> content, long ownUid) {
        Risk r = riskOf(content, ownUid);
        return r == null ? null : r.kind;
    }

    /**
     * 汇总推送中命中"自己城被侦查/进攻"的行军：kind 严重度优先 WAR；
     * endtime 取多条命中的最早到达（服务器秒，0=未知）；troops 求和（任一未知则整体未知）。
     */
    private static Risk riskOf(Map<String, Object> content, long ownUid) {
        if (content == null || ownUid <= 0) return null;
        boolean hit = false, war = false, recon = false, unknownTroops = false;
        long endSec = 0, troops = 0;
        for (Map<String, Object> m : marchesOf(content)) {
            long type = Exec.longOf(m.get("type"));
            // 1=侦查、2=进攻、100=集结进攻（网页版 RALLY_CITY_ATTACK 同按护盾处理）
            if (type != MARCH_RECON && type != MARCH_WAR && type != MARCH_RALLY) continue;
            Object targetType = m.get("target_type");
            if (targetType != null && Exec.longOf(targetType) != TARGET_CITY) continue;
            if (targetUidOf(m) != ownUid) continue;
            hit = true;
            if (type == MARCH_WAR || type == MARCH_RALLY) war = true;
            else recon = true;
            long e = Exec.longOf(m.get("endtime"));
            if (e > 0 && (endSec == 0 || e < endSec)) endSec = e;
            long t = troopsOf(m);
            if (t < 0) unknownTroops = true;
            else troops += t;
        }
        if (!hit) return null;
        return new Risk(war ? "WAR" : "RECON", endSec, unknownTroops ? -1L : troops);
    }

    /**
     * 单条行军兵力汇总（squad_list.soldier，形状宽容，与 WarNotify.troopsOf 同口径）：
     * map 键=id 值=数量（或 {count:n}）、list 元素={id,count}；未知（无 squad/全 0）返回 -1。
     * 包可见：单测断言阈值口径。
     */
    static long troopsOf(Map<String, Object> m) {
        Map<String, Object> squad = Exec.mapOf(m.get("squad_list"));
        Object soldier = squad == null ? null : squad.get("soldier");
        long total = 0;
        boolean known = false;
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
                if (id > 0 && cnt > 0) {
                    total += cnt;
                    known = true;
                }
            }
        } else if (soldier instanceof List) {
            for (Object o : (List<?>) soldier) {
                Map<String, Object> vm = Exec.mapOf(o);
                if (vm == null) continue;
                long id = Exec.longOf(vm.get("id"));
                long cnt = Exec.longOf(vm.get("count"));
                if (id > 0 && cnt > 0) {
                    total += cnt;
                    known = true;
                }
            }
        }
        return known ? total : -1L;
    }

    /**
     * 收集推送中的行军对象，兼容三种实证形状：
     * 观察哨 {"marches":{"9.0":{"march":{...},"power":0}}} / 列表直挂；
     * 目标系列 {"to_marchs":[{...}]} / {"marchs":[{...}]}；以及 {data:{...}} 包装。
     * 包可见：P7 ServerWarState 复用同一解析（单一事实源）。
     */
    static List<Map<String, Object>> marchesOf(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectMarches(content, out);
        for (String wrap : new String[]{"data", "info"}) {
            Object w = content.get(wrap);
            if (w instanceof Map) collectMarches((Map<String, Object>) w, out);
        }
        return out;
    }

    private static void collectMarches(Map<String, Object> c, List<Map<String, Object>> out) {
        Object marches = c.get("marches");
        if (marches instanceof Map) {
            for (Object v : ((Map<?, ?>) marches).values()) {
                Map<String, Object> e = Exec.mapOf(v);
                if (e == null) continue;
                Map<String, Object> m = Exec.mapOf(e.get("march"));
                out.add(m != null ? m : e);
            }
        } else if (marches instanceof List) {
            out.addAll(Exec.mapsOf(marches));
        }
        for (String key : new String[]{"to_marchs", "marchs"}) {
            Object arr = c.get(key);
            if (arr instanceof List) out.addAll(Exec.mapsOf(arr));
        }
    }

    /** 守方 uid：atk_city.targetid 优先，退化 target.map_city.uid；取不到返回 0（不触发）。
     *  包可见：P7 ServerWarState 复用同一解析。 */
    static long targetUidOf(Map<String, Object> m) {
        Map<String, Object> atk = Exec.mapOf(m.get("atk_city"));
        if (atk != null) {
            long t = Exec.longOf(atk.get("targetid"));
            if (t > 0) return t;
        }
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null) return Exec.longOf(city.get("uid"));
        }
        return 0;
    }
}
