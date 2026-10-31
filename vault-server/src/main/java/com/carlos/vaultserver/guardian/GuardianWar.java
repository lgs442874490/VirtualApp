package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.VaultStore;

import org.json.JSONObject;

import java.util.Map;
import java.util.Set;

/**
 * 批次 J（2026-10-31 用户长期规则第 3 条，{@code design/守护盟友-长期生效约束-20261031.md}）：
 * <b>守护战争态</b>配置与常量。
 *
 * <p>规则：守护开关开启（enabled && !dry_run）后，一旦有人来侦查（march type=1）即进入战争态：
 * <ul>
 *   <li>立即召回全部采集队列（type=4，逐队 {@code req_world_march_recall}）；</li>
 *   <li>暂停占世界行军队列的日常任务（{@link #QUEUE_TASKS} 清单，gather 直接停派含联盟矿）；</li>
 *   <li>6 个行军队列全部留给对盟友驻防（{@code reserve_slots} 等价全部可用）；</li>
 *   <li>无任何在途敌方行军指向我方/盟友持续 {@code war_exit_idle_sec} → 退出战争态恢复日常。</li>
 * </ul>
 *
 * <p>配置来源：App 同步的 {@code roleParams.guardian}（{@code sub}/{@code strategy}/顶层三处同键，
 * 与 {@link ServerGuardianEngine#parseConfig} 同款解析口径）：
 * <pre>
 * war_on_scout(默认 true)      侦查(type=1)即进战争态；false=侦查只降级为预警，进攻类仍可入战时
 * war_exit_idle_sec(默认 60)   无在途敌方行军持续 N 秒退出战争态（clamp 5..3600）
 * war_recall_gather(默认 true) 战争态立即召回全部 type=4 采集队列
 * war_pause_queue_tasks(默认 true) 战争态暂停占队日常任务（清单见 QUEUE_TASKS）
 * </pre>
 *
 * <p>回滚：{@code VAULT_GUARDIAN_WAR=off|0|false|no}（System 属性优先，便于单测注入）→ 本批新增行为
 * 全部关闭，回到批次 I 之前的既有战争态口径（p:war 判定 + 既有时战 allowlist 总闸）。
 */
public final class GuardianWar {

    /** 回滚环境变量/系统属性：off/0/false/no → 关闭批次 J 新增行为。 */
    public static final String ENV_ROLLBACK = "VAULT_GUARDIAN_WAR";

    /**
     * 战争态暂停的「占世界行军队列」任务清单（用户口径：巨兽/情报/狩猎/联盟矿等）。
     *
     * <p>gather 含联盟矿（gatherV2 内联盟矿/普通采集共用世界队列，见 ServerFieldTasks）；
     * behemoth 含发起/自动上车（req_world_march type=411/99）；bear 为巨熊发起/上车。
     */
    /** 是否「占世界行军队列」的日常任务（战争态只暂停这一类；G7 用户口径）。 */
    public static boolean isQueueTask(String fnId) {
        return fnId != null && QUEUE_TASKS.contains(fnId);
    }

    public static final Set<String> QUEUE_TASKS = Set.of(
            "gather", "hunt", "intel", "behemoth", "bear", "adventure_sweep",
            "act_mercenary", "act_truck", "act_feast", "act_icehunter", "act_joy");

    /** 默认无威胁迟滞：60s（用户裁决，见设计文档规则 3）。 */
    public static final long DEFAULT_EXIT_IDLE_SEC = 60L;
    static final long MIN_EXIT_IDLE_SEC = 5L;
    static final long MAX_EXIT_IDLE_SEC = 3600L;

    private GuardianWar() {
    }

    /** 守护战争态生效配置快照（不可变；每次热载重建）。 */
    public static final class Settings {
        /** 是否启用批次 J 守护战争态（守护实盘 enabled&&!dry_run，且未被 env 回滚）。 */
        public final boolean enabled;
        /** 侦查即入战争态（false=侦查降级预警，进攻类仍按既有 H 触发入战时）。 */
        public final boolean onScout;
        /** 无在途敌方行军持续多少秒退出战争态。 */
        public final long exitIdleSec;
        /** 战争态立即召回全部 type=4 采集队列。 */
        public final boolean recallGather;
        /** 战争态暂停占队任务（{@link #QUEUE_TASKS}）。 */
        public final boolean pauseQueueTasks;

        Settings(boolean enabled, boolean onScout, long exitIdleSec,
                 boolean recallGather, boolean pauseQueueTasks) {
            this.enabled = enabled;
            this.onScout = onScout;
            this.exitIdleSec = exitIdleSec;
            this.recallGather = recallGather;
            this.pauseQueueTasks = pauseQueueTasks;
        }

        public String describe() {
            return "war_enabled=" + enabled + " on_scout=" + onScout
                    + " exit_idle=" + exitIdleSec + "s recall_gather=" + recallGather
                    + " pause_queue_tasks=" + pauseQueueTasks;
        }
    }

    /**
     * 解析某角色的守护战争态配置：守护参数（{@code roleParams.guardian}，按 uid → sid 回退）+
     * 实盘门控（enabled && !dry_run，允许 env 灰度覆盖）+ 回滚开关。
     *
     * @param acc 账号（null → 全部默认值；enabled=false）
     */
    public static Settings resolve(VaultStore.Account acc, long sid, long uid) {
        String json = guardianJson(acc, sid, uid);
        JSONObject p = null;
        if (json != null && !json.isEmpty()) {
            try {
                p = new JSONObject(json);
            } catch (Throwable ignored) {
            }
        }
        boolean live = ServerGuardianEngine.queueDefenseActive(acc, sid, uid);
        boolean onScout = bool(p, "war_on_scout", true);
        long idle = num(p, "war_exit_idle_sec", DEFAULT_EXIT_IDLE_SEC);
        idle = Math.max(MIN_EXIT_IDLE_SEC, Math.min(MAX_EXIT_IDLE_SEC, idle));
        boolean recallGather = bool(p, "war_recall_gather", true);
        boolean pauseQueue = bool(p, "war_pause_queue_tasks", true);
        return new Settings(live && !envRollback(), onScout, idle, recallGather, pauseQueue);
    }

    /** 回滚开关：{@code VAULT_GUARDIAN_WAR=off/0/false/no} → true（其它值/未设置=false）。 */
    public static boolean envRollback() {
        String v = setting(ENV_ROLLBACK);
        if (v == null) return false;
        String s = v.trim();
        return "off".equalsIgnoreCase(s) || "0".equals(s)
                || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s);
    }

    /** roleParams.guardian 原始 JSON（uid 优先，回退该 sid 旧参数；无则 null）。 */
    static String guardianJson(VaultStore.Account acc, long sid, long uid) {
        try {
            Map<String, String> raw = VaultStore.roleParamsOf(acc, sid, uid);
            return raw == null ? null : raw.get(ServerGuardianEngine.FN_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 三处（sub/strategy/顶层）同键布尔解析：缺键/非法 → 默认值。 */
    static boolean bool(JSONObject p, String key, boolean def) {
        Object v = first(p, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)) return false;
        }
        return def;
    }

    /** 三处同键数值解析：缺键/非法 → 默认值。 */
    static long num(JSONObject p, String key, long def) {
        Object v = first(p, key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private static Object first(JSONObject p, String key) {
        if (p == null) return null;
        JSONObject sub = p.optJSONObject("sub");
        if (sub != null && sub.has(key)) return sub.opt(key);
        JSONObject st = p.optJSONObject("strategy");
        if (st != null && st.has(key)) return st.opt(key);
        return p.has(key) ? p.opt(key) : null;
    }

    /** 取值：System 属性优先（单测注入），其次环境变量。 */
    static String setting(String key) {
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
}
