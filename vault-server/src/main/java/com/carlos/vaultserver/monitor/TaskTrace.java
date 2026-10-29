package com.carlos.vaultserver.monitor;

import com.carlos.vault.wjdr.JsonText;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 服务端任务级协议监控（2026-10-25）：线程本地任务上下文 + 引擎 IO 事件 → {@link MonitorStore}。
 *
 * <p>解决的问题：真机上功能“有在跑但游戏里没效果”。本类把每个任务执行的链路落成
 * {@code task.start → io.req → io.rsp → task.end} 事件流，另含推送（notify）、会话（session）、
 * 用户打点（mark），经 {@code POST /v1/vault/monitor} 给 App「真机监控」页实时查看。
 *
 * <p><b>强约束（本类所有 public 方法）</b>：
 * <ul>
 *   <li>只做观测：绝不改变任务返回值/分类/控制流，绝不发游戏协议；</li>
 *   <li>内部全部 try/catch：MonitorStore 写入/JSON 序列化/引擎钩子异常一律吞掉，不抛给会话/任务线程；</li>
 *   <li>未启用（{@link MonitorStore#enabled()} == false）时 {@link #begin} 返回零开销空上下文，
 *       其余 API 立即返回。</li>
 * </ul>
 *
 * <p>接线（由宿主负责）：{@code MonitorStore.install(dataDir)} 后调用 {@link #install()} 一次；
 * 会话层在 {@code LiveSession} 构造时调用 {@link #attachNotify}；任务层在动作/任务执行前后
 * {@link #begin}/{@code Context#end}。
 */
public final class TaskTrace {

    private static final System.Logger LOG = System.getLogger("TaskTrace");

    /** 心跳协议名：高频噪声，任务级监控一律跳过（契约 §3.2）。 */
    static final String PROTO_HEARTBEAT = "req_heartbeat";

    /** 当前线程的任务上下文（嵌套时指向最内层；无任务=会话通道）。 */
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    /** install 幂等闸门。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** 注册到 WjdrEngine 的监听实例（uninstall 用，必须保持同一引用）。 */
    private static final WjdrEngine.IoListener IO_LISTENER = TaskTrace::onIo;

    /** {@code VAULT_MONITOR_NOTIFY} 解析结果：all / off / whitelist（null=尚未读取）。 */
    private static volatile String notifyMode;

    private TaskTrace() {
    }

    // ================= 任务上下文 =================

    /**
     * 开启一次任务观测（动作/任务执行前调用）。未启用时返回共享空上下文（零开销）。
     *
     * <p>嵌套调用（整轮 → 动作）时以最内层为准：内层结束会恢复外层上下文。
     * 返回的 {@link Context} 必须 {@link Context#end(TaskOutcome)} 或 {@link Context#close()}，
     * 否则 ThreadLocal 会在线程上泄漏（异常路径务必 finally/close）。
     *
     * @param fpid    账号
     * @param sid     区服（0/负数 = 未知，落空串）
     * @param fn      任务 id（动作 id）
     * @param name    任务中文名
     * @param trigger 触发来源（节奏|手动|服务器|整轮）
     */
    public static Context begin(String fpid, long sid, String fn, String name, String trigger) {
        return begin(fpid, sid, 0L, fn, name, trigger);
    }

    /**
     * R2 Phase 2B：带角色 uid 的任务观测（同服多角色监控可按 uid 区分角色）。
     *
     * <p>只做观测（契约第 1 条）：额外落 {@code uid} 字段，事件形状/判定/控制流全部不变。
     * task.start/task.end 均带 {@code uid}（{@code uid<=0} 落空串，与 {@code sid} 未知时同款）。
     *
     * @param uid 角色 uid（0=旧/未知）
     */
    public static Context begin(String fpid, long sid, long uid, String fn, String name,
                                String trigger) {
        MonitorStore store = MonitorStore.get();
        if (store == null || !store.isEnabled()) return Context.NOOP;
        Context ctx = null;
        try {
            ctx = new Context(store, fpid, sid, uid, fn, name, trigger, System.nanoTime(),
                    CURRENT.get());
            CURRENT.set(ctx);
            ctx.writeStart();
            return ctx;
        } catch (Throwable t) {
            // 监控失败不得影响任务：退回空上下文并清理可能残留的绑定
            if (ctx != null && CURRENT.get() == ctx) CURRENT.remove();
            return Context.NOOP;
        }
    }

    /**
     * 任务观测上下文：记录 task.start/task.end，并作为 IO 事件归属依据。
     * {@link #end} 幂等（重复调用只落一条 task.end）；{@link #close} 等价于 {@code end(null)}。
     */
    public static final class Context implements AutoCloseable {

        /** 未启用时共享的空上下文（不入 ThreadLocal）。 */
        static final Context NOOP = new Context(null, null, 0L, 0L, null, null, null, 0L, null);

        private final MonitorStore store;
        private final String fpid;
        private final String sidText;
        /** 角色 uid 文本（Phase 2B；uid&lt;=0 落空串，与 sid 未知时同款）。 */
        private final String uidText;
        private final String fn;
        private final String name;
        private final String trigger;
        private final long startedNanos;
        private final Context parent;
        private boolean ended;

        Context(MonitorStore store, String fpid, long sid, long uid, String fn, String name,
                String trigger, long startedNanos, Context parent) {
            this.store = store;
            this.fpid = nullToEmpty(fpid);
            this.sidText = sid > 0L ? String.valueOf(sid) : "";
            this.uidText = uid > 0L ? String.valueOf(uid) : "";
            this.fn = nullToEmpty(fn);
            this.name = nullToEmpty(name);
            this.trigger = nullToEmpty(trigger);
            this.startedNanos = startedNanos;
            this.parent = parent;
        }

        /** 结束观测并落 task.end（cat/message/rewards/durMs）；重复调用幂等。 */
        public void end(TaskOutcome outcome) {
            if (ended) return;
            ended = true;
            if (store == null) return;
            try {
                unbind();
                JSONObject e = new JSONObject();
                e.put("kind", "task.end");
                e.put("fpid", fpid);
                e.put("sid", sidText);
                e.put("uid", uidText);
                e.put("fn", fn);
                e.put("name", name);
                e.put("trigger", trigger);
                e.put("cat", outcome == null || outcome.category == null
                        ? "" : outcome.category.name());
                e.put("msg", outcome == null ? "" : nullToEmpty(outcome.message));
                e.put("rewards", outcome == null ? "" : nullToEmpty(outcome.rewards));
                long dur = outcome != null && outcome.durationMs > 0L
                        ? outcome.durationMs : elapsedMillis();
                e.put("durMs", Math.max(0L, dur));
                store.record(e);
            } catch (Throwable ignored) {
                // 观测失败绝不影响任务
            }
        }

        /** 异常/提前返回兜底：落 task.end（cat="", msg=""）。 */
        @Override
        public void close() {
            end(null);
        }

        /** 是否已结束（重复 end 幂等）。 */
        public boolean isEnded() {
            return ended;
        }

        private void writeStart() {
            if (store == null) return;
            try {
                JSONObject e = new JSONObject();
                e.put("kind", "task.start");
                e.put("fpid", fpid);
                e.put("sid", sidText);
                e.put("uid", uidText);
                e.put("fn", fn);
                e.put("name", name);
                e.put("trigger", trigger);
                store.record(e);
            } catch (Throwable ignored) {
            }
        }

        /** 解绑 ThreadLocal：恢复最近一个未结束的外层上下文，没有则清空。 */
        private void unbind() {
            if (CURRENT.get() != this) return;   // 乱序 end：不动别人的绑定
            Context p = parent;
            while (p != null && p.ended) p = p.parent;
            if (p != null) {
                CURRENT.set(p);
            } else {
                CURRENT.remove();
            }
        }

        private long elapsedMillis() {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        }
    }

    // ================= 引擎 IO 事件 =================

    /** 幂等注册 IO 监听（MonitorStore 已启用时；重复调用安全）。 */
    public static void install() {
        MonitorStore store = MonitorStore.get();
        if (store == null || !store.isEnabled()) return;
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            ensureNotifyMode();   // 监控启用时一次性读 VAULT_MONITOR_NOTIFY
            WjdrEngine.addIoListener(IO_LISTENER);
        } catch (Throwable t) {
            INSTALLED.set(false);
            LOG.log(System.Logger.Level.DEBUG, "TaskTrace 安装失败：" + t.getMessage());
        }
    }

    /** 反向注册（进程退出/单测防串测；幂等）。 */
    static void uninstall() {
        CURRENT.remove();
        if (INSTALLED.compareAndSet(true, false)) {
            try {
                WjdrEngine.removeIoListener(IO_LISTENER);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单测重置：恢复 install 前状态（监听器、ThreadLocal、notify 模式）。 */
    static void resetForTest() {
        uninstall();
        notifyMode = null;
    }

    /**
     * IO 监听实现（WjdrEngine → MonitorStore）。
     *
     * <p>direction：{@code req} → {@code io.req}；{@code rsp}/{@code err} → {@code io.rsp}
     * （err 额外带 {@code err=true}/{@code msg=异常简述}，契约 kind 不新增）。跳过心跳。
     * 有任务上下文时带 fpid/sid/fn/name/trigger；无上下文记 {@code fn=""}（会话通道）。
     */
    static void onIo(WjdrEngine engine, String direction, String proto,
                     Map<String, Object> body, long durMs, Throwable error) {
        try {
            MonitorStore store = MonitorStore.get();
            if (store == null || !store.isEnabled()) return;
            if (proto == null || proto.isEmpty()) return;
            if (PROTO_HEARTBEAT.equals(proto)) return;   // 心跳高频噪声不录

            boolean req = "req".equals(direction);
            Context ctx = CURRENT.get();
            String eFpid;
            String eSid;
            String eFn;
            String eName;
            String eTrigger;
            if (ctx != null && !ctx.ended) {
                eFpid = ctx.fpid;
                eSid = ctx.sidText;
                eFn = ctx.fn;
                eName = ctx.name;
                eTrigger = ctx.trigger;
            } else {
                // 会话通道：登录/补充/身份类请求（便于对照“用户手动登录原生游戏”前后的行为）
                eFpid = engine == null ? "" : nullToEmpty(engine.lastFpid());
                long sid = engine == null ? 0L : engine.lastServerId();
                eSid = sid > 0L ? String.valueOf(sid) : "";
                eFn = "";
                eName = "";
                eTrigger = "";
            }

            JSONObject e = new JSONObject();
            e.put("kind", req ? "io.req" : "io.rsp");
            e.put("fpid", eFpid);
            e.put("sid", eSid);
            e.put("fn", eFn);
            e.put("name", eName);
            e.put("trigger", eTrigger);
            e.put("proto", proto);
            if (req) {
                JsonText.Result args = JsonText.toJsonChecked(body, store.contentLimit());
                e.put("args", args.text);
                if (args.truncated) e.put("trunc", true);
            } else {
                e.put("code", codeOf(body));
                e.put("durMs", Math.max(0L, durMs));
                JsonText.Result data = JsonText.toJsonChecked(body, store.contentLimit());
                e.put("data", data.text);
                if (data.truncated) e.put("trunc", true);
                if (error != null) {
                    e.put("err", true);
                    e.put("msg", briefError(error));
                }
            }
            store.record(e);
        } catch (Throwable ignored) {
            // 监控异常绝不影响协议请求
        }
    }

    /** 游戏返回码：空响应=-1；无 code 字段=0；数值/字符串取整（契约 §2）。 */
    static int codeOf(Map<String, Object> content) {
        if (content == null) return -1;
        Object v = content.get("code");
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ================= 推送（notify）监控 =================

    /**
     * 挂接引擎的推送监控（LiveSession 构造时调一次）：白名单见契约 §3.2
     * （领取/任务域 notify_*），{@code VAULT_MONITOR_NOTIFY=all|off} 可覆盖。
     */
    public static void attachNotify(WjdrEngine engine, String fpid, Supplier<Long> sidSupplier) {
        if (engine == null) return;
        ensureNotifyMode();
        try {
            engine.addMessageHook((proto, content) -> {
                try {
                    recordNotify(fpid, sidSupplier, proto, content);
                } catch (Throwable ignored) {
                    // 推送监控异常绝不影响消息分发
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 推送事件落库（package-private 供 attachNotify 钩子与单测直接调用）。 */
    static void recordNotify(String fpid, Supplier<Long> sidSupplier, String proto,
                             Map<String, Object> content) {
        try {
            MonitorStore store = MonitorStore.get();
            if (store == null || !store.isEnabled()) return;
            if (proto == null || proto.isEmpty() || !notifyAllowed(proto)) return;
            long sid = safeSid(sidSupplier);
            JSONObject e = new JSONObject();
            e.put("kind", "notify");
            e.put("fpid", nullToEmpty(fpid));
            e.put("sid", sid > 0L ? String.valueOf(sid) : "");
            e.put("proto", proto);
            JsonText.Result data = JsonText.toJsonChecked(content, store.contentLimit());
            e.put("data", data.text);
            if (data.truncated) e.put("trunc", true);
            store.record(e);
        } catch (Throwable ignored) {
        }
    }

    /** 是否记录该推送协议（读 {@code VAULT_MONITOR_NOTIFY}，默认任务/领取白名单）。 */
    static boolean notifyAllowed(String proto) {
        if (proto == null || proto.isEmpty()) return false;
        ensureNotifyMode();
        String mode = notifyMode;
        if ("off".equals(mode)) return false;
        if ("all".equals(mode)) return true;
        return notifyWhitelisted(proto);
    }

    /**
     * 领取/任务域 notify 白名单（契约 §3.2，前缀匹配 + 名称含 reward）。
     * 覆盖：每日领取/每日任务/邮件/活动/任务/成就/补给/兑换/充值/十四天签到/七天/登录/
     * 关卡/章节/背包 等“做了但游戏里没效果”最需要对照的域。
     */
    static boolean notifyWhitelisted(String proto) {
        if (proto == null) return false;
        String[] prefixes = {
                "notify_daily_claim", "notify_dailyquests", "notify_mail",
                "notify_activity", "notify_quest", "notify_achievement",
                "notify_supply", "notify_value_exchange", "notify_vip",
                "notify_fourteen_sign", "notify_seven", "notify_login",
                "notify_stage", "notify_chapter", "notify_backpack"
        };
        for (String p : prefixes) {
            if (proto.startsWith(p)) return true;
        }
        return proto.contains("reward");
    }

    /** 单测注入 notify 模式（生产走 {@code VAULT_MONITOR_NOTIFY} 环境变量）。 */
    static void setNotifyModeForTest(String mode) {
        notifyMode = mode == null ? null : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static void ensureNotifyMode() {
        if (notifyMode != null) return;
        synchronized (TaskTrace.class) {
            if (notifyMode != null) return;
            String mode = "whitelist";
            try {
                String v = System.getenv("VAULT_MONITOR_NOTIFY");
                if (v != null && !v.trim().isEmpty()) {
                    mode = v.trim().toLowerCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {
            }
            notifyMode = mode;
        }
    }

    // ================= 会话 / 打点事件 =================

    /**
     * 会话事件（kind=session）：登录成功/失败、状态变化、顶号让位、暂停恢复等，供会话层调用。
     * 全部异常吞掉，绝不外溢到会话线程。
     *
     * @param data 附加快照（可空；按 contentLimit 截断）
     */
    public static void session(String fpid, String sid, String event, String msg, JSONObject data) {
        try {
            MonitorStore store = MonitorStore.get();
            if (store == null || !store.isEnabled()) return;
            JSONObject e = new JSONObject();
            e.put("kind", "session");
            e.put("fpid", nullToEmpty(fpid));
            e.put("sid", nullToEmpty(sid));
            e.put("event", nullToEmpty(event));
            e.put("msg", nullToEmpty(msg));
            if (data != null) {
                JsonText.Result d = JsonText.limit(data.toString(), store.contentLimit());
                e.put("data", d.text);
                if (d.truncated) e.put("trunc", true);
            }
            store.record(e);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 用户打点（kind=mark）：用户在原生游戏手动领取/做任务前后各点一次，便于框定对照窗口。
     */
    public static void mark(String source, String msg) {
        try {
            MonitorStore store = MonitorStore.get();
            if (store == null || !store.isEnabled()) return;
            JSONObject e = new JSONObject();
            e.put("kind", "mark");
            e.put("src", nullToEmpty(source));
            e.put("msg", nullToEmpty(msg));
            store.record(e);
        } catch (Throwable ignored) {
        }
    }

    // ================= 工具 =================

    private static long safeSid(Supplier<Long> supplier) {
        if (supplier == null) return 0L;
        try {
            Long sid = supplier.get();
            return sid == null ? 0L : sid;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String briefError(Throwable error) {
        String m = error.getMessage();
        String s = error.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
