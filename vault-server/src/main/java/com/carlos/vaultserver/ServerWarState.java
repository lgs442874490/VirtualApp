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
 * <p>实例键 = (fpid, sid)：与 App 一致按角色实例独立计分；推送数据缺失（无推送/形状变化）
 * 自然降级——判定机收不到信号即停在 NORMAL，任务闸不拦截（不会因失明误伤日常）。
 */
public final class ServerWarState {

    /** (fpid,sid) → 实例。 */
    private static final ConcurrentHashMap<String, ServerWarState> REGISTRY = new ConcurrentHashMap<>();

    /** 服务器侧 ALERT 同步让位的可选出征类任务（与 App ALERT_FIELD_TASKS 同集）。 */
    static final Set<String> ALERT_FIELD_TASKS = Set.of(
            "gather", "hunt", "intel", "bear", "adventure_sweep",
            "act_mercenary", "act_truck", "act_feast", "act_icehunter");

    final String fpid;
    final long sid;
    final WarState core = new WarState();
    /** 在途敌情行军（侦查+进攻）：marchId → [type, owner, endServerSec]（del 分类依赖）。 */
    private final Map<String, long[]> tracked = new ConcurrentHashMap<>();
    /** 服务器侧 EXTREME 迷你锁存：owner → 锁存到期 ms（判定机 H3 的上游）。 */
    private final Map<Long, Long> extremeLock = new ConcurrentHashMap<>();
    /** 服务器侧压缩计数：owner → 次数（≥3 触发锁存）。 */
    private final Map<Long, Integer> compressCount = new ConcurrentHashMap<>();

    private ServerWarState(String fpid, long sid) {
        this.fpid = fpid;
        this.sid = sid;
    }

    // ---------------- 实例管理 ----------------

    static String key(String fpid, long sid) {
        return (fpid == null ? "" : fpid) + "|" + sid;
    }

    /** get-or-create（SessionKeeper 会话挂载时；测试可直接 register）。 */
    public static ServerWarState forRole(String fpid, long sid) {
        String k = key(fpid, sid);
        ServerWarState w = REGISTRY.get(k);
        if (w == null) {
            synchronized (REGISTRY) {
                w = REGISTRY.get(k);
                if (w == null) {
                    w = new ServerWarState(fpid, sid);
                    REGISTRY.put(k, w);
                }
            }
        }
        return w;
    }

    static ServerWarState register(String fpid, long sid) {
        return forRole(fpid, sid);
    }

    static void unregister(String fpid, long sid) {
        REGISTRY.remove(key(fpid, sid));
    }

    /** p:war.* 参数热载（App 同步的 roleParams JSON；缺省=全默认）。 */
    public static void configure(VaultStore.Account acc, long sid) {
        ServerWarState w = REGISTRY.get(key(acc == null ? "" : acc.fpid, sid));
        if (w == null) return;
        w.core.configure(configOf(acc, sid));
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

    /** SessionKeeper 资产库钩子调用：不为未注册角色创建状态；已注册才同步。 */
    public static void syncAssets(String fpid, long sid, CoreAssetLibrary lib, long ownUidNow) {
        ServerWarState w = REGISTRY.get(key(fpid, sid));
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
    public static String gateNote(String fpid, long sid, String taskId) {
        ServerWarState w = REGISTRY.get(key(fpid, sid));
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
    public static String roundYieldNote(String fpid, long sid, String fnOnly) {
        ServerWarState w = REGISTRY.get(key(fpid, sid));
        if (w == null || !w.core.wartime()) return null;
        if (fnOnly == null || fnOnly.isEmpty()) {
            return "战时整轮让位（" + reasonText(w.core) + "）";
        }
        // 单任务（补给补领/手动单功能）：allowlist 内放行
        return w.core.dailyTaskGated(fnOnly) ? "战时暂停（" + reasonText(w.core) + "）" : null;
    }

    /** RoundScheduler 让位：该账号任一角色处于战争态 → 自动整轮延后（退出后窗口补跑）。 */
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
     * warState 摘要（/status /accounts 暴露）：无实例返回 null。
     * sid 查不到时回退该 fpid 任一实例（多角色/旧数据兜底）。
     */
    public static JSONObject summaryJson(String fpid, long sid) {
        ServerWarState w = REGISTRY.get(key(fpid, sid));
        if (w == null) {
            for (Map.Entry<String, ServerWarState> e : REGISTRY.entrySet()) {
                if (e.getKey().startsWith((fpid == null ? "" : fpid) + "|")) {
                    w = e.getValue();
                    break;
                }
            }
        }
        if (w == null) return null;
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

    /** roleParams 的 war JSON → 判定机 Config（sub/strategy 键与 App FnDetail war 条目一致）。 */
    static WarState.Config configOf(VaultStore.Account acc, long sid) {
        WarState.Config c = new WarState.Config();
        try {
            if (acc == null) return c;
            Map<String, String> raw = acc.roleParams.get(String.valueOf(sid));
            String s = raw == null ? null : raw.get("war");
            if (s == null || s.isEmpty()) return c;
            JSONObject p = new JSONObject(s);
            JSONObject sub = p.optJSONObject("sub");
            if (sub != null) {
                c.auto = sub.optBoolean("auto", true);
                c.scoutHard = sub.optBoolean("scout_hard", true);
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
