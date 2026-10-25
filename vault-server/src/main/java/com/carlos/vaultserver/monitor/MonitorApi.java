package com.carlos.vaultserver.monitor;

import com.carlos.vaultserver.FileLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 真机调试监控 HTTP 处理（{@code POST /v1/vault/monitor}，op={@code vaultMonitor}）。
 *
 * <p>App「真机监控」页经此端点拉取 {@link MonitorStore} 的事件流，或写入一条用户打点。
 * 契约见 {@code design/真机监控-服务端任务级协议监控-20261025.md} §3.3：
 *
 * <pre>
 * action=query（默认）  since / limit / kinds / fpid / includeData → MonitorStore.query(...)
 * action=mark           msg → 记一条 kind=mark 事件 → {enabled:true, seq, serverTime}
 * action=status         → MonitorStore.status()（未安装 {enabled:false}）
 * </pre>
 *
 * <p>未启用（{@code VAULT_MONITOR}≠on / 未安装）时一律返回 {@code {enabled:false}} 形态，
 * 不抛异常——旧服务端 App 侧按「不支持」提示。本类是纯函数式入口：所有异常都兜成
 * 结构化错误对象（{@code error/message} 字段），绝不外溢到 HTTP 层或会话线程。
 */
public final class MonitorApi {

    /** query 默认返回条数（契约 §3.3）。 */
    static final int DEFAULT_LIMIT = 200;

    private MonitorApi() {
    }

    /**
     * 处理一次监控请求。
     *
     * @param fields 已验签的扁平请求字段（可为 null，等价空请求 → action=query）
     * @return 响应信封的 {@code data} 对象；任何异常都转成结构化错误，不抛出
     */
    public static JSONObject handle(Map<String, Object> fields) {
        Map<String, Object> f = fields == null ? Map.of() : fields;
        String action = str(f, "action");
        if (action == null || action.trim().isEmpty()) action = "query";
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        try {
            switch (normalized) {
                case "query":
                    return query(f);
                case "mark":
                    return mark(f);
                case "status":
                    return status();
                default:
                    return error("unknown_action", "未知 action: " + action);
            }
        } catch (Throwable t) {
            FileLog.warn("监控接口异常 action={}：{}", action, String.valueOf(t));
            return error("monitor_error",
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }

    // ---------- action 实现 ----------

    /** 事件游标查询：未启用时返回契约要求的空结果（enabled=false），App 据此显示“服务端监控未启用”。 */
    private static JSONObject query(Map<String, Object> f) {
        long since = optLong(f, "since", 0L);
        if (since < 0L) since = 0L;
        int limit = optInt(f, "limit", DEFAULT_LIMIT);
        Set<String> kinds = splitKinds(str(f, "kinds"));
        String fpid = str(f, "fpid");
        boolean includeData = !isFalse(str(f, "includeData"));
        MonitorStore store = MonitorStore.get();
        if (store == null) {
            JSONObject out = new JSONObject();
            out.put("enabled", false);
            out.put("events", new JSONArray());
            out.put("count", 0);
            out.put("lastSeq", 0L);
            out.put("minSeq", 0L);
            out.put("mem", 0);
            out.put("dropped", 0L);
            out.put("serverTime", System.currentTimeMillis());
            return out;
        }
        // 未启用时 MonitorStore.query 自身即返回 enabled=false + 空 events（record 早退，内存为空）。
        return store.query(since, limit, kinds, fpid, includeData);
    }

    /** 用户打点：写一条 kind=mark 事件，返回新 seq 供 App 立刻定位。 */
    private static JSONObject mark(Map<String, Object> f) {
        MonitorStore store = MonitorStore.get();
        JSONObject out = new JSONObject();
        if (store == null || !store.isEnabled()) {
            out.put("enabled", false);
            out.put("seq", 0L);
            out.put("serverTime", System.currentTimeMillis());
            out.put("message", "服务端监控未启用（需在环境变量加 VAULT_MONITOR=on 后重启）");
            return out;
        }
        String msg = str(f, "msg");
        JSONObject event = new JSONObject();
        event.put("kind", "mark");
        event.put("msg", msg == null ? "" : msg);
        // 带上可选账号/区服，App 过滤时与其它事件口径一致
        String fpid = str(f, "fpid");
        if (fpid != null && !fpid.isEmpty()) event.put("fpid", fpid);
        String sid = str(f, "sid");
        if (sid != null && !sid.isEmpty()) event.put("sid", sid);
        long seq = store.record(event);
        out.put("enabled", true);
        out.put("seq", seq);
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }

    /** 监控状态：未安装时只回 enabled=false（不泄露路径）。 */
    private static JSONObject status() {
        MonitorStore store = MonitorStore.get();
        if (store == null) {
            JSONObject out = new JSONObject();
            out.put("enabled", false);
            out.put("serverTime", System.currentTimeMillis());
            return out;
        }
        return store.status();
    }

    // ---------- 工具 ----------

    private static JSONObject error(String type, String message) {
        JSONObject out = new JSONObject();
        out.put("enabled", MonitorStore.enabled());
        out.put("error", type == null ? "error" : type);
        out.put("message", message == null ? "" : message);
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }

    /** 逗号分隔 kind 白名单；空串 = 全部（null 表示不传过滤）。 */
    private static Set<String> splitKinds(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String k = part.trim();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    private static boolean isFalse(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return "0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s);
    }

    private static String str(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return "null".equals(s) ? null : s;
    }

    private static long optLong(Map<String, Object> fields, String key, long def) {
        Object v = fields.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) {
            try {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static int optInt(Map<String, Object> fields, String key, int def) {
        long v = optLong(fields, key, Long.MIN_VALUE);
        if (v == Long.MIN_VALUE) return def;
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
