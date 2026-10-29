package com.carlos.vaultserver;

import com.carlos.vault.daily.guardian.ThreatBoard;
import com.carlos.vault.daily.guardian.WarState;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;

import org.json.JSONObject;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器侧战争状态对等（P7，开发计划裁决 1：vault-server 对等=仅 WarState 判定+战时任务闸）。
 *
 * <p>判定机 {@link WarState} 与 App 同源（build.gradle 直编同一份文件，禁止复制漂移）；
 * 本类只做服务器特有的事：
 * <ul>
 *   <li><b>信号源</b>：挂引擎消息钩子解析世界/联盟行军推送（复用 {@link ServerShieldResponder}
 *       的三种实证形状解析；无 MarchCoordinator 线程，钩子线程只解析入账，全部动作由
 *       判定机内部完成）；联盟态势推送天然盟友域（allyTargeted），一般世界推送只认
 *       自己城（target==ownUid，服务器无盟友坐标表）；</li>
 *   <li><b>H 系映射</b>：H0 侦查/H2 并发（判定机内在途表）/H3 极限压缩（服务器侧
 *       独立 EXTREME 迷你锁存：同 owner 压缩≥3 次→24h）/H1 落地与战报——服务器不解析
 *       战报邮件，H1 以"敌攻落地"（del 且 remain≤2s，7.2 口径）为准；H4 佯攻潮
 *       （召回解除 remain&gt;2s）由判定机窗口计数；</li>
 *   <li><b>参数</b>：App 同步的 roleParams p:war.* JSON（sub/strategy 同形状）热载；</li>
 *   <li><b>战时任务闸（E1 对等）</b>：{@link #gateNote} 供 RoundRunner（逐任务）与
 *       ModularRoundRunner（整轮合成 SKIPPED、不烧 srv:lastrun → 退出后窗口补跑）
 *       查询；{@link #wartimeYield} 供 RoundScheduler 让位自动整轮；</li>
 *   <li><b>/status 摘要</b>：{@link #summaryJson} 供 VaultHttpApi 暴露 warState 概要。</li>
 * </ul>
 *
 * <p>实例键 = (fpid, sid, uid)（Phase 2B 2026-10-29）：同账号同服双角色各自独立战争态，
 * 互不覆盖；uid&lt;=0 为旧/兼容路径（旧签名委托 uid=0）。App 显示链路不变：active 角色由
 * SessionKeeper 用 {@link #summaryJson(String,long,long)} 读取，账号级聚合语义
 * （{@link #wartimeYield(String)}）保留供运维/旧调度。推送数据缺失（无推送/形状变化）
 * 自然降级——判定机收不到信号即停在 NORMAL，任务闸不拦截（不会因失明误伤日常）。
 */
public final class ServerWarState {

    /** (fpid|sid|uid) → 实例（uid=0 为旧/聚合兼容实例）。 */
    private static final ConcurrentHashMap<String, ServerWarState> REGISTRY = new ConcurrentHashMap<>();

    /** 服务器侧 ALERT 同步让位的可选出征类任务（与 App ALERT_FIELD_TASKS 同集）。 */
    static final Set<String> ALERT_FIELD_TASKS = Set.of(
            "gather", "hunt", "intel", "bear", "adventure_sweep",
            "act_mercenary", "act_truck", "act_feast", "act_icehunter");

    final String fpid;
    final long sid;
    /** Phase 2B：角色 uid（0=旧/兼容实例）。 */
    final long uid;
    final WarState core = new WarState();
    /** 在途敌情行军（侦查+进攻）：marchId → [type, owner, endServerSec]（del 分类依赖）。 */
    private final Map<String, long[]> tracked = new ConcurrentHashMap<>();
    /** 服务器侧 EXTREME 迷你锁存：owner → 锁存到期 ms（判定机 H3 的上游）。 */
    private final Map<Long, Long> extremeLock = new ConcurrentHashMap<>();
    /** 服务器侧压缩计数：owner → 次数（≥3 触发锁存）。 */
    private final Map<Long, Integer> compressCount = new ConcurrentHashMap<>();

    private ServerWarState(String fpid, long sid, long uid) {
        this.fpid = fpid;
        this.sid = sid;
        this.uid = uid;
    }

    // ---------------- 实例管理 ----------------

    /** 角色实例键：{@code fpid|sid|uid}（uid<=0 旧槽位）。 */
    static String key(String fpid, long sid, long uid) {
        return (fpid == null ? "" : fpid) + "|" + sid + "|" + uid;
    }

    /** 旧实例键（uid=0），保留供既有调用/测试。 */
    static String key(String fpid, long sid) {
        return key(fpid, sid, 0L);
    }

    /** 旧 get-or-create（uid=0 兼容实例）。 */
    public static ServerWarState forRole(String fpid, long sid) {
        return forRole(fpid, sid, 0L);
    }

    /** Phase 2B get-or-create：同账号同服双角色各自实例（uid 必填；SessionKeeper 挂载时调用）。 */
    public static ServerWarState forRole(String fpid, long sid, long uid) {
        String k = key(fpid, sid, uid);
        ServerWarState w = REGISTRY.get(k);
        if (w == null) {
            synchronized (REGISTRY) {
                w = REGISTRY.get(k);
                if (w == null) {
                    w = new ServerWarState(fpid, sid, uid);
                    w.core.setListener(loggingListener(fpid, sid, uid));
                    REGISTRY.put(k, w);
                }
            }
        }
        return w;
    }

    static ServerWarState register(String fpid, long sid) {
        return forRole(fpid, sid);
    }

    static ServerWarState register(String fpid, long sid, long uid) {
        return forRole(fpid, sid, uid);
    }

    /** 旧注销（uid=0 兼容实例）。 */
    public static void unregister(String fpid, long sid) {
        unregister(fpid, sid, 0L);
    }

    /** Phase 2B：只移除目标角色实例（兄弟角色实例保留）。 */
    public static void unregister(String fpid, long sid, long uid) {
        REGISTRY.remove(key(fpid, sid, uid));
    }

    // ---------------- 全局节拍 / 运维解卡 / 告警取数（2026-10-25 战时卡死修复） ----------------

    /** 全局节拍线程是否已启动（幂等）。 */
    private static volatile boolean tickerStarted;
    /** 全局节拍周期：30s（只做退出判定/上限解除，开销可忽略）。 */
    static final long TICKER_INTERVAL_MILLIS = 30_000L;
    /**
     * 服务器侧「战时上限」默认值（分钟）：<b>0=关闭（默认，按原有静默退出机制）</b>。
     *
     * <p>用户裁决 2026-10-25：战时状态按原有机制退出——**无侦查、无进攻满 30 分钟即退出战争态**
     * （{@code war.quiet_exit} 默认 30，由 {@code ThreatBoard.allQuiet} 判定）。
     * 本上限只是可选的运维兜底，需显式配置 {@code VAULT_WARTIME_MAX_MIN} 才生效，避免默认改变战斗语义。
     */
    static final long DEFAULT_MAX_WARTIME_MINUTES = 0L;

    /**
     * 启动战争态全局节拍：对 {@link #REGISTRY} 内<b>全部</b>角色实例驱动退出判定。
     *
     * <p>背景（2026-10-24 生产事故）：原实现只由「active 写入方角色会话」的 1s tick 驱动
     * {@code war.tick}；同号多角色时，非活跃角色实例在切成副角色后不再被 tick，其推送钩子一旦
     * 把状态推入 WARTIME 就永远无法退出；而 {@link #wartimeYield} 是账号级判定，于是整个账号的
     * 节奏派单/整轮被无限期拦截（实测 2h33m，只有重启才恢复）。全局节拍保证任何实例都会被
     * 判定退出（静默退出 / 开盾退出 / 战时上限）。
     */
    public static synchronized void startTicker() {
        if (tickerStarted) return;
        tickerStarted = true;
        Thread t = new Thread(() -> {
            long maxMin = maxWartimeMinutesByEnv();
            FileLog.info("战争态全局节拍已启动：{}ms（对全部已注册角色实例判退出/上限，含同服多角色 uid 实例），战时上限={}",
                    String.valueOf(TICKER_INTERVAL_MILLIS),
                    maxMin <= 0 ? "不限" : (maxMin + "min"));
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    tickAll(System.currentTimeMillis());
                } catch (Throwable e) {
                    FileLog.warn("战争态全局节拍异常：{}", String.valueOf(e));
                }
                try {
                    Thread.sleep(TICKER_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "war-state-ticker");
        t.setDaemon(true);
        t.start();
    }

    /** 单次节拍：tick 所有已注册实例（包可见供单测）。 */
    static void tickAll(long now) {
        for (Map.Entry<String, ServerWarState> e : REGISTRY.entrySet()) {
            ServerWarState w = e.getValue();
            if (w == null) continue;
            try {
                w.core.tick(now);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 运营解卡（不重启解除卡死的战时态）：清掉该账号所有角色的 WARTIME。
     *
     * @return 真正从 WARTIME 解除的实例数（0=本来就不在战时）
     */
    public static int resetWartime(String fpid) {
        if (fpid == null || fpid.isEmpty()) return 0;
        int n = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ServerWarState> e : REGISTRY.entrySet()) {
            if (!e.getKey().startsWith(fpid + "|")) continue;
            ServerWarState w = e.getValue();
            if (w == null) continue;
            if (w.core.wartime()) n++;
            w.core.forceWartime(false, "运营解卡（war-reset）", now);
        }
        return n;
    }

    /** 当前处于 WARTIME 的角色摘要（供告警/状态展示）："fpid|sid(152min) …"；无则空串。 */
    public static String activeWartimeSummary(long now) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ServerWarState> e : REGISTRY.entrySet()) {
            ServerWarState w = e.getValue();
            if (w == null || !w.core.wartime()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.fpid).append('|').append(w.sid);
            if (w.uid > 0L) sb.append('|').append(w.uid);
            sb.append('(').append(w.core.durationMs(now) / 60_000L).append("min)");
        }
        return sb.toString();
    }

    /** 服务器侧战时上限（分钟）：环境变量 {@code VAULT_WARTIME_MAX_MIN}（测试可设同名 System 属性）。 */
    static long maxWartimeMinutesByEnv() {
        return parseMaxWartimeMinutes(setting("VAULT_WARTIME_MAX_MIN"));
    }

    /** 解析战时上限：null/空/非法 → 默认 120；off/false/0/负数 → 0（不限）。 */
    static long parseMaxWartimeMinutes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_MAX_WARTIME_MINUTES;
        String v = raw.trim();
        if ("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return 0L;
        try {
            long n = Long.parseLong(v);
            return n <= 0L ? 0L : n;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_WARTIME_MINUTES;
        }
    }

    /** 取值：System 属性优先（单测注入），其次环境变量。 */
    private static String setting(String key) {
        try {
            String p = System.getProperty(key);
            if (p != null) return p;
        } catch (Throwable ignored) {
        }
        try {
            return System.getenv(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 进出日志监听：战争态进入/退出/提醒落 FileLog（原来无任何埋点，事故只能靠 skip 计数反推）。 */
    private static WarState.Listener loggingListener(final String fpid, final long sid, final long uid) {
        return new WarState.ListenerAdapter() {
            @Override
            public void onWarEnter(WarState.Trigger trigger, String detail) {
                FileLog.info("战争态进入 fpid={} sid={} uid={} 触发={} {}",
                        fpid, String.valueOf(sid), String.valueOf(uid),
                        trigger == null ? "?" : trigger.name(), detail);
            }

            @Override
            public void onWarExit(String reason, String detail) {
                FileLog.info("战争态退出 fpid={} sid={} uid={} 原因={} {}",
                        fpid, String.valueOf(sid), String.valueOf(uid), reason, detail);
            }

            @Override
            public void onNotice(String text) {
                FileLog.info("战争态提醒 fpid={} sid={} uid={} {}",
                        fpid, String.valueOf(sid), String.valueOf(uid), text);
            }
        };
    }

    /** 旧 p:war.* 参数热载（uid=0 兼容实例）。 */
    public static void configure(VaultStore.Account acc, long sid) {
        configure(acc, sid, 0L);
    }

    /** Phase 2B：按角色 uid 热载 p:war.*（roleParamsByUid 优先，回退旧 sid 参数）。 */
    public static void configure(VaultStore.Account acc, long sid, long uid) {
        ServerWarState w = REGISTRY.get(key(acc == null ? "" : acc.fpid, sid, uid));
        if (w == null) return;
        w.core.configure(configOf(acc, sid, uid));
    }

    /** 挂引擎消息钩子（推送线程只解析入账；幂等由引擎 hook 容器保证）。 */
    public void attach(WjdrEngine engine) {
        engine.addMessageHook((proto, content) -> {
            try {
                onPush(proto, content, engine.lastUid());
            } catch (Throwable ignored) {
            }
        });
    }

    // ---------------- 推送解析（P1 实证口径，与 GuardianEngine/ServerShieldResponder 同形） ----------------

    /** 联盟域推送（天然指向盟友）；一般世界推送只认自己城。 */
    private static final Set<String> ALLIANCE_PROTOS = Set.of(
            "notify_world_alliance_situation_add",
            "notify_world_alliance_situation_update",
            "notify_world_alliance_situation_init",
            "notify_world_alliance_situation_del");
    private static final Set<String> WORLD_PROTOS = Set.of(
            "notify_world_situation_info_add",
            "notify_world_situation_info_up");

    /** 钩子入口（包可见供单测直接驱动）。 */
    void onPush(String proto, Map<String, Object> content, long ownUidNow) {
        if (content == null || proto == null) return;
        boolean alliance = ALLIANCE_PROTOS.contains(proto);
        if (!alliance && !WORLD_PROTOS.contains(proto)) return;
        boolean isDel = proto.endsWith("_del");
        for (Map<String, Object> m : ServerShieldResponder.marchesOf(content)) {
            if (isDel) {
                handleDel(m);
            } else {
                handleMarch(m, alliance, ownUidNow);
            }
        }
    }

    private void handleMarch(Map<String, Object> m, boolean allianceScope, long ownUid) {
        long id = num(m.get("id"));
        long owner = num(m.get("owner"));
        long type = num(m.get("type"));
        if (id <= 0 || owner <= 0) return;
        if (type != 1 && type != 2) return;
        long end = num(m.get("endtime"));
        long nowS = System.currentTimeMillis() / 1000L;
        long targetUid = ServerShieldResponder.targetUidOf(m);
        // 盟友目标判定：联盟域推送天然算数；世界域只认自己城（服务器无盟友坐标表）
        boolean allyHit = allianceScope || (ownUid > 0 && targetUid == ownUid);
        if (!allyHit) return;
        if (core.isFriendlyUid(owner) || owner == ownUid) return;   // 白名单/自家（§7.31）

        String tkey = String.valueOf(id);
        long[] known = tracked.get(tkey);
        if (known == null) {
            if (end <= nowS) return;   // 已成历史
            tracked.put(tkey, new long[]{type, owner, end});
            if (type == 1) {
                core.onScout(id, owner, System.currentTimeMillis());   // H0 / 降级软 +15
            } else {
                // 服务器侧无战力/ETA 阈值：指向盟友的进攻一律按 qualified 入账（宁可错入）
                core.onAttackMarch(id, owner, end, true, System.currentTimeMillis());
                if (extremeLocked(owner)) {
                    core.onExtremeMarch(id, owner, System.currentTimeMillis());   // H3
                }
            }
        } else if (end < known[2]) {
            // 敌加减速（压缩）：刷新在途表 + 敌情板计时；EXTREME 锁存者压缩 → H3
            known[2] = end;
            core.onAttackUpdated(id, end);
            core.onSignal(WarState.Signal.soft("U:m" + id + ":" + end, 0, owner,
                    type == 1 ? ThreatBoard.Kind.SCOUT : ThreatBoard.Kind.ATTACK,
                    System.currentTimeMillis()));
            int n = compressCount.merge(owner, 1, Integer::sum);
            if (n >= 3 && !extremeLocked(owner)) {
                extremeLock.put(owner, System.currentTimeMillis() + 24 * 3600_000L);
            }
            if (extremeLocked(owner)) {
                core.onExtremeMarch(id, owner, System.currentTimeMillis());
            }
        } else if (end > known[2]) {
            known[2] = end;
            core.onAttackUpdated(id, end);
        }
    }

    /**
     * 资产库事实迁移（W9）：从 {@link CoreAssetLibrary.war()} 的联盟域行军事实补齐/对账威胁，
     * 作为原始推送之外的<b>第二事实源</b>（推送丢失/迟到时仍能判定；去重依赖 tracked id，幂等）。
     *
     * <p>只迁移联盟域（天然指向盟友，不依赖目标 uid 解析）；世界域仍需原始推送（需盟友坐标表）。
     * 已消失的行军 → 按 handleDel 结算（召回/到达语义与推送路径一致）。
     */
    public void syncFromAssets(CoreAssetLibrary lib, long ownUidNow) {
        if (lib == null || !lib.ready() || lib.war() == null) return;
        try {
            java.util.Set<Long> present = new java.util.HashSet<Long>();
            for (AssetSnapshot.QueueMarch march : lib.war().allianceMarches.values()) {
                if (march == null || march.id <= 0L) continue;
                present.add(march.id);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", march.id);
                m.put("owner", march.owner);
                m.put("type", march.type);
                m.put("endtime", march.endTime);
                Map<String, Object> target = new LinkedHashMap<String, Object>();
                target.put("uid", march.targetId);
                target.put("target", march.targetId);
                target.put("id", march.targetId);
                target.put("target_type", march.targetType);
                m.put("target", target);
                m.put("target_type", march.targetType);
                m.put("targetid", march.targetId);
                handleMarch(m, true, ownUidNow);
            }
            // 已消失的联盟行军 → 结算
            for (String id : new java.util.ArrayList<String>(tracked.keySet())) {
                long mid = num(id);
                if (mid <= 0L || present.contains(mid)) continue;
                Map<String, Object> del = new LinkedHashMap<String, Object>();
                del.put("id", mid);
                handleDel(del);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 旧 SessionKeeper 资产库钩子调用（uid=0 兼容实例）；不为未注册角色创建状态。 */
    public static void syncAssets(String fpid, long sid, CoreAssetLibrary lib, long ownUidNow) {
        syncAssets(fpid, sid, 0L, lib, ownUidNow);
    }

    /** Phase 2B：按角色 uid 同步资产库战争事实（未注册该角色则跳过）。 */
    public static void syncAssets(String fpid, long sid, long uid,
                                  CoreAssetLibrary lib, long ownUidNow) {
        ServerWarState w = REGISTRY.get(key(fpid, sid, uid));
        if (w == null) return;
        w.syncFromAssets(lib, ownUidNow);
    }

    private void handleDel(Map<String, Object> m) {
        long id = num(m.get("id"));
        if (id <= 0) return;
        long[] known = tracked.remove(String.valueOf(id));
        if (known == null) return;
        long type = known[0];
        long owner = known[1];
        long remain = known[2] - System.currentTimeMillis() / 1000L;
        // 7.2 口径：remain > 2s 判召回（佯攻）；≤2s 判到达（H1 落地）
        if (type == 2) {
            core.onAttackEnded(id, owner, 0, remain > 2, System.currentTimeMillis());
        } else {
            core.onScoutEnded(id);
        }
    }

    private boolean extremeLocked(long owner) {
        Long until = extremeLock.get(owner);
        return until != null && System.currentTimeMillis() < until;
    }

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---------------- 周期判定（SessionKeeper 会话节拍驱动） ----------------

    /** 退出判定/持续提醒（1s 节拍调用，内部有状态短路，开销可忽略）。 */
    public void tick(long now) {
        core.tick(now);
    }

    // ---------------- 战时任务闸（E1 对等） ----------------

    /**
     * 任务闸：null=放行；否则 SKIPPED 理由。WARTIME 看 war.allowlist；
     * ALERT 停可选出征类（与 App ALERT_FIELD_TASKS 同集）。实例不存在=null（未挂载不拦截）。
     */
    /** 旧任务闸（uid=0 兼容实例）。 */
    public static String gateNote(String fpid, long sid, String taskId) {
        return gateNote(fpid, sid, 0L, taskId);
    }

    /** Phase 2B：按角色 uid 的任务闸（各角色自己的战争态）。 */
    public static String gateNote(String fpid, long sid, long uid, String taskId) {
        ServerWarState w = REGISTRY.get(key(fpid, sid, uid));
        if (w == null) return null;
        WarState core = w.core;
        if (core.wartime()) {
            return core.dailyTaskGated(taskId)
                    ? "战时暂停（" + reasonText(core) + "；war.allowlist 外任务）" : null;
        }
        if (core.alert() && taskId != null && ALERT_FIELD_TASKS.contains(taskId)) {
            return "战争预警：可选出征暂停（采集限近郊）";
        }
        return null;
    }

    /** 整轮让位判定：null=正常跑；否则整轮合成 SKIPPED（不烧 srv:lastrun）。 */
    /** 旧整轮让位判定（uid=0 兼容实例）。 */
    public static String roundYieldNote(String fpid, long sid, String fnOnly) {
        return roundYieldNote(fpid, sid, 0L, fnOnly);
    }

    /** Phase 2B：按角色 uid 的整轮让位判定（各角色独立战争态）。 */
    public static String roundYieldNote(String fpid, long sid, long uid, String fnOnly) {
        ServerWarState w = REGISTRY.get(key(fpid, sid, uid));
        if (w == null || !w.core.wartime()) return null;
        if (fnOnly == null || fnOnly.isEmpty()) {
            return "战时整轮让位（" + reasonText(w.core) + "）";
        }
        // 单任务（补给补领/手动单功能）：allowlist 内放行
        return w.core.dailyTaskGated(fnOnly) ? "战时暂停（" + reasonText(w.core) + "）" : null;
    }

    /**
     * Phase 2B：按角色 uid 的让位判定（Phase2 调度应使用本方法，互不拖累）。
     */
    public static boolean wartimeYield(String fpid, long sid, long uid) {
        ServerWarState w = REGISTRY.get(key(fpid, sid, uid));
        return w != null && w.core.wartime();
    }

    /** 账号级聚合让位：该账号任一角色处于战争态 → true（运维/旧调度兼容，保留）。 */
    public static boolean wartimeYield(String fpid) {
        if (fpid == null) return false;
        for (Map.Entry<String, ServerWarState> e : REGISTRY.entrySet()) {
            if (e.getKey().startsWith(fpid + "|") && e.getValue().core.wartime()) return true;
        }
        return false;
    }

    private static String reasonText(WarState core) {
        return core.manualLocked() ? "人工锁定" : core.reason() == null ? "H 触发" : core.reason().text;
    }

    // ---------------- /status 摘要 ----------------

    /**
     * 旧 warState 摘要（uid=0；/status /accounts 旧调用）：sid 查不到时回退该 fpid 任一实例。
     */
    public static JSONObject summaryJson(String fpid, long sid) {
        ServerWarState w = REGISTRY.get(key(fpid, sid, 0L));
        if (w == null) {
            for (Map.Entry<String, ServerWarState> e : REGISTRY.entrySet()) {
                if (e.getKey().startsWith((fpid == null ? "" : fpid) + "|")) {
                    w = e.getValue();
                    break;
                }
            }
        }
        return w == null ? null : summaryOf(w);
    }

    /**
     * Phase 2B：按角色 uid 的摘要（/status 用 active 角色）。uid&gt;0 时只查精确角色实例、
     * 不做 fpid 兜底（避免把兄弟角色的战争态显示成本角色）；uid&lt;=0 走旧兜底语义。
     */
    public static JSONObject summaryJson(String fpid, long sid, long uid) {
        if (uid <= 0L) return summaryJson(fpid, sid);
        ServerWarState w = REGISTRY.get(key(fpid, sid, uid));
        return w == null ? null : summaryOf(w);
    }

    private static JSONObject summaryOf(ServerWarState w) {
        WarState core = w.core;
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        o.put("state", core.state().name());
        o.put("reason", core.reason() == null ? "" : core.reason().name());
        o.put("manualLock", core.manualLocked());
        o.put("enteredAt", core.enteredAt());
        o.put("durationMin", core.durationMs(now) / 60_000L);
        o.put("enemies", core.board().size());
        ThreatBoard.Enemy top = core.board().mostActive();
        o.put("topEnemyUid", top == null ? 0 : top.ownerUid);
        o.put("topEnemySignals", top == null ? 0 : top.signalCount);
        o.put("inFlightAttacks", core.inFlightAttacks());
        o.put("explain", core.explain(now));
        return o;
    }

    // ---------------- p:war.* 参数装配（与 App FnParams JSON 同形状） ----------------

    /**
     * roleParams 的 war JSON → 判定机 Config（sub/strategy 键与 App FnDetail war 条目一致）。
     *
     * <p><b>H0「侦查即入」保持默认开启（用户裁决 2026-10-25）</b>：SLG 里侦查完成后约 2 秒即可
     * 发起攻击，侦查=进攻前兆，必须立即进入 WARTIME 并触发驻防/开盾等战斗动作；配合「驻防盟友
     * 对等加速」（敌方一加速即对等加速、可无限消耗加速道具，只要保证驻防到达即可），不能为了
     * 少误报而砍掉 H0。因此本次应急修复<b>不降级</b> scout_hard，只修「战时态只进不出/
     * 无日志/副角色实例不再 tick」这些导致卡死 2h33m 的结构性缺陷。
     *
     * <p><b>退出语义</b>：按原有机制——无侦查、无进攻满 {@code war.quiet_exit}（默认 30min，
     * 由 {@code ThreatBoard.allQuiet} 判定）即退出；{@code maxWartimeMs} 默认 <b>0=关闭</b>，
     * 仅当显式配置 {@code VAULT_WARTIME_MAX_MIN}（分钟）时作为运维兜底生效。</p>
     */
    static WarState.Config configOf(VaultStore.Account acc, long sid) {
        return configOf(acc, sid, 0L);
    }

    /** Phase 2B：按角色 uid 装配 p:war.*（roleParamsByUid 优先 → 旧 sid 参数 → 默认）。 */
    static WarState.Config configOf(VaultStore.Account acc, long sid, long uid) {
        WarState.Config c = new WarState.Config();
        c.maxWartimeMs = maxWartimeMinutesByEnv() * 60_000L;             // 服务器侧兜底：战时上限
        try {
            if (acc == null) return c;
            Map<String, String> raw = VaultStore.roleParamsOf(acc, sid, uid);
            String s = raw == null ? null : raw.get("war");
            if (s == null || s.isEmpty()) return c;
            JSONObject p = new JSONObject(s);
            JSONObject sub = p.optJSONObject("sub");
            if (sub != null) {
                c.auto = sub.optBoolean("auto", true);
                c.scoutHard = sub.optBoolean("scout_hard", true);        // H0 默认开启；仅显式 false 才降级
                c.shieldExit = sub.optBoolean("shield_exit", true);
            }
            JSONObject st = p.optJSONObject("strategy");
            if (st != null) {
                c.quietExitMs = minutes(st.optString("quiet_exit", "30"), 30) * 60_000L;
                c.alertTh = (int) minutes(st.optString("alert_th", "25"), 25);
                c.feintBurstCount = (int) minutes(st.optString("feint_burst", "5"), 5);
                c.durationAlertMs = minutes(st.optString("duration_alert", "120"), 120) * 60_000L;
                c.allowlist = list(st.optString("allowlist", "battle_shield,medic"));
                c.friendlyUids = uids(st.optString("friendly_uids", ""));
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static long minutes(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static Set<String> list(String csv) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : (csv == null ? "" : csv).split("[,，]")) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out.isEmpty() ? WarState.Config.defaultAllowlist() : out;
    }

    private static Set<Long> uids(String csv) {
        Set<Long> out = new java.util.LinkedHashSet<>();
        for (String p : (csv == null ? "" : csv).split("[,，]")) {
            try {
                long u = Long.parseLong(p.trim());
                if (u > 0) out.add(u);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
