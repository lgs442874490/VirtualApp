package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 *   <li>门控：账号自动化开启（auto/keep 统一开关）+ 子开关 battle_shield.reactive != false（默认 true，
 *     App FnDetail 同款语义）；</li>
 *   <li>幂等：开盾成功（8 小时护盾）后 8 小时内不重复开；其余结果（今日已用/协议异常/错误码）
 *     5 分钟冷却，防推送风暴；事件超过 10 分钟才被处理则丢弃——human_first 让位/掉线期积压的
 *     事件自然过期，回连后不为旧攻击补盾；</li>
 *   <li>让位保护：tick 只在会话 online 时执行（LiveSession 亦只在 online 分支调用），人玩期间
 *     服务器不发任何请求，不互踢；</li>
 *   <li>留痕：FileLog + mod:fpid:sid:battle_shield（reactive 子项，App FnDetail/今日页同键展示）。</li>
 * </ul>
 */
public final class ServerShieldResponder {

    // ---------------- 协议常量（实证口径，勿凭猜测改） ----------------

    /** 行军类型：1=侦查 2=进攻（5=驻防/增援不触发护盾）。 */
    private static final long MARCH_RECON = 1L;
    private static final long MARCH_WAR = 2L;
    /** 目标类型：1=玩家城（打野/情报类 target_type 非 1，不触发）。 */
    private static final long TARGET_CITY = 1L;

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
    /** 事件保鲜期：推送到达超过该时长才处理则丢弃。 */
    static final long EVENT_TTL_MILLIS = 10 * 60_000L;

    // ---------------- 状态 ----------------

    private final String fpid;
    private final long sid;
    private final StateStore store;
    private final VaultStore vault;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** 冷却截止时刻（包可见：单测断言幂等窗/重试窗）。 */
    final AtomicLong nextAttemptAt = new AtomicLong(0);
    /** 最近一次 WAR/RECON 推送时刻（包可见：单测模拟陈旧事件）。 */
    volatile long lastEventAt;
    /** 最近一次事件类型 WAR/RECON（包可见：单测断言）。 */
    volatile String lastEventKind = "";

    ServerShieldResponder(String fpid, long sid, StateStore store, VaultStore vault) {
        this.fpid = fpid;
        this.sid = sid;
        this.store = store;
        this.vault = vault;
    }

    // ---------------- 挂载与 tick ----------------

    /** 挂到引擎消息钩子（推送线程调用：只解析+置脏，不发请求）。 */
    void attach(WjdrEngine engine) {
        engine.addMessageHook((proto, content) -> {
            if (!SITUATION_PROTOS.contains(proto)) return;
            try {
                onPush(content, engine.lastUid());
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 推送入口（钩子线程）：命中"自己城被侦查/进攻"才置脏。
     * 包可见供单测直接驱动（无需引擎钩子分发）。
     */
    void onPush(Map<String, Object> content, long ownUid) {
        String kind = classify(content, ownUid);
        if (kind == null) return;
        lastEventKind = kind;
        lastEventAt = System.currentTimeMillis();
        dirty.set(true);
    }

    /** 会话线程 tick 驱动：有新鲜事件且过门控时开一次盾。返回 true 表示本轮有动作。 */
    boolean tick(WjdrEngine engine) {
        // 让位/恢复/登录中：不开盾（人玩优先，防互踢）；事件留脏，回连如仍在保鲜期才补
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return false;
        if (!dirty.compareAndSet(true, false)) return false;
        long now = System.currentTimeMillis();
        if (now - lastEventAt > EVENT_TTL_MILLIS) return false;   // 陈旧事件（让位期积压）
        if (now < nextAttemptAt.get()) return false;              // 幂等/防风暴冷却
        if (!busy.compareAndSet(false, true)) return false;
        try {
            react(engine, now);
        } finally {
            busy.set(false);
        }
        return true;
    }

    // ---------------- 开盾动作（会话线程内执行） ----------------

    private void react(WjdrEngine engine, long now) {
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null || !(acc.auto || acc.keep)) {
            FileLog.info("反应式开盾跳过 fpid={}：账号自动化未开启", fpid);
            saveState(null, "自动化未开启，未反应式开盾");
            return;
        }
        TaskContext tc = new TaskContext(engine, fpid, sid, store, paramsOf(acc, sid), "反应式");
        if (!tc.subOn("battle_shield", "reactive", true)) {
            FileLog.info("反应式开盾跳过 fpid={}：子开关 battle_shield.reactive 已关闭", fpid);
            saveState(tc, "反应式开盾已关闭（App 设置页可开）");
            return;
        }
        // 复用整轮同款执行器：free_id 从 notify_city_item_init 动态取，码表归 SKIPPED
        // （执行器内部已捕获 Exception 并分类，此处再兜一层 Throwable，防止 tick 链被打断）
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

    /** 写 mod:fpid:sid:battle_shield 的 reactive 子项（App FnDetail/今日页同键展示）。 */
    private void saveState(TaskContext tc, String text) {
        try {
            if (tc != null) {
                tc.saveModText("battle_shield", "reactive", text);
            } else {
                JSONObject mod = new JSONObject();
                String raw = store.getString("mod:" + fpid + ":" + sid + ":battle_shield");
                if (raw != null) mod = new JSONObject(raw);
                mod.put("reactive", text);
                mod.put("reactive_ts", System.currentTimeMillis());
                mod.put("reactive_trg", "反应式");
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
        if (content == null || ownUid <= 0) return null;
        boolean war = false, recon = false;
        for (Map<String, Object> m : marchesOf(content)) {
            long type = Exec.longOf(m.get("type"));
            if (type != MARCH_RECON && type != MARCH_WAR) continue;
            Object targetType = m.get("target_type");
            if (targetType != null && Exec.longOf(targetType) != TARGET_CITY) continue;
            if (targetUidOf(m) != ownUid) continue;
            if (type == MARCH_WAR) war = true;
            else recon = true;
        }
        if (war) return "WAR";
        return recon ? "RECON" : null;
    }

    /**
     * 收集推送中的行军对象，兼容三种实证形状：
     * 观察哨 {"marches":{"9.0":{"march":{...},"power":0}}} / 列表直挂；
     * 目标系列 {"to_marchs":[{...}]} / {"marchs":[{...}]}；以及 {data:{...}} 包装。
     */
    private static List<Map<String, Object>> marchesOf(Map<String, Object> content) {
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

    /** 守方 uid：atk_city.targetid 优先，退化 target.map_city.uid；取不到返回 0（不触发）。 */
    private static long targetUidOf(Map<String, Object> m) {
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
