package com.carlos.vaultserver.movecity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁城 API action 分发（1:1 复刻原版 {@code /api/movecity/move-city/*} REST 面，
 * 2026-11-06；按本项目惯例收敛为单端点 POST + action）：
 *
 * <table>
 *   <tr><th>action</th><th>原版端点</th><th>语义</th></tr>
 *   <tr><td>protocols</td><td>GET /move-city/protocols</td><td>12 协议表</td></tr>
 *   <tr><td>execute</td><td>POST /move-city/execute</td><td>立即迁城（多账号批量，异步）</td></tr>
 *   <tr><td>schedule</td><td>POST /move-city/schedule</td><td>创建定时迁城（单账号）</td></tr>
 *   <tr><td>jobs</td><td>GET /move-city/jobs</td><td>任务列表（scheduled+finished）</td></tr>
 *   <tr><td>job</td><td>GET /move-city/jobs/{id}</td><td>任务详情（含 attempt_details）</td></tr>
 *   <tr><td>cancel</td><td>POST /move-city/jobs/{id}/cancel</td><td>取消未开始任务</td></tr>
 *   <tr><td>remove-account</td><td>POST /move-city/jobs/{id}/accounts/{roleId}/remove</td>
 *       <td>从任务移除账号</td></tr>
 * </table>
 *
 * <p>业务校验失败抛 {@link IllegalArgumentException}（中文文案逐字对齐原版），
 * 由 VaultHttpApi 转统一信封错误。
 */
public final class MoveCityApi {

    private MoveCityApi() {
    }

    public static JSONObject handle(MoveCityEngine engine, Map<String, Object> fields) {
        Map<String, Object> normalized = normalize(fields);
        String action = text(normalized.get("action"));
        if (action.isEmpty()) action = "protocols";
        switch (action) {
            case "protocols":
                return protocols(engine);
            case "execute":
                return mapToJsonObject(engine.executeNow(normalized));
            case "schedule":
                return mapToJsonObject(engine.schedule(normalized));
            case "jobs":
                return mapToJsonObject(engine.jobs());
            case "job":
                return mapToJsonObject(engine.job(text(normalized.get("jobId"))));
            case "cancel":
                return mapToJsonObject(engine.cancel(text(normalized.get("jobId"))));
            case "remove-account":
                return mapToJsonObject(engine.removeAccount(text(normalized.get("jobId")),
                        text(normalized.get("fpid"))));
            default:
                throw new IllegalArgumentException("未知 action: " + action);
        }
    }

    /**
     * 字段归一化：App 侧按「签名层只出现扁平标量」约定把账号列表序列化成
     * {@code accountsJson} 字符串（与 syncFields/watchpointSet 同款），
     * 引擎侧按原版形状消费 {@code accounts} 列表 → 这里解析回列表。
     * 两种形状都接受（App 旧版直传列表/curl 调试直传数组亦可用）。
     */
    private static Map<String, Object> normalize(Map<String, Object> fields) {
        if (fields == null) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>(fields);
        if (out.get("accounts") instanceof List) return out;
        Object raw = out.get("accountsJson");
        if (raw == null || text(raw).isEmpty()) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(text(raw));
        } catch (org.json.JSONException e) {
            // 审阅修复（P1）：非法 JSON 原会冒泡成 500 server error，这里转业务错误
            // （文案对齐原版 Controller requirePayloadAccess「账号参数格式错误」）。
            throw new IllegalArgumentException("账号参数格式错误");
        }
        List<Object> accounts = new java.util.ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject obj) {
                accounts.add(obj.toMap());
            } else if (item instanceof Map) {
                accounts.add(item);
            }
        }
        out.put("accounts", accounts);
        return out;
    }

    private static JSONObject protocols(MoveCityEngine engine) {
        List<Map<String, Object>> protocols = engine.protocols();
        JSONObject data = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Map<String, Object> p : protocols) {
            arr.put(new JSONObject(p));
        }
        data.put("protocols", arr);
        return data;
    }

    private static JSONObject mapToJsonObject(Map<String, Object> map) {
        JSONObject out = new JSONObject();
        if (map == null) return out;
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(map).entrySet()) {
            out.put(e.getKey(), wrap(e.getValue()));
        }
        return out;
    }

    private static Object wrap(Object v) {
        if (v instanceof Map<?, ?> m) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                o.put(String.valueOf(e.getKey()), wrap(e.getValue()));
            }
            return o;
        }
        if (v instanceof List<?> l) {
            JSONArray arr = new JSONArray();
            for (Object item : l) arr.put(wrap(item));
            return arr;
        }
        return v;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
