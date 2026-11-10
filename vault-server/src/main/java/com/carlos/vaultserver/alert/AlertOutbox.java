package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.WatchEventStore;

import org.json.JSONObject;

/**
 * 告警出口（设计 §11）：事件库是唯一源，先入库再决定企微。
 *
 * <p>P3 先落地“观察点告警入库”这一半（App 时间线/短信因此可见）。
 * 企微发送仍由各引擎原有的门控（开关/静默/限速）负责，避免一次性改两条出口。
 */
public final class AlertOutbox {

    private final WatchEventStore events;

    public AlertOutbox(WatchEventStore events) {
        this.events = events;
    }

    /**
     * 写一条告警事件（幂等）。
     *
     * @param route     A1/A2/A3/A4
     * @param alertKey  全局唯一键（同一条告警只入库一次）
     * @param text      已渲染文案（企微/时间线共用）
     * @param detectedAt 毫秒
     * @param source    PUSH/SCAN
     */
    public boolean emit(String route, String alertKey, String text, long detectedAt, String source) {
        JSONObject e = new JSONObject();
        e.put("phase", "alert");
        e.put("route", route == null ? "" : route);
        e.put("alertKey", alertKey == null ? "" : alertKey);
        e.put("text", text == null ? "" : text);
        e.put("detectedAt", detectedAt);
        e.put("source", source == null ? "" : source);
        e.put("aggregated", false);
        e.put("count", 1);
        return events.appendOnce(e);
    }

    /** 按文案前缀推断路由（观察点旧出口只有文案，P4 页面合并前用这条兜底）。 */
    public static String routeOfText(String text) {
        if (text == null) return "A4";
        if (text.startsWith("🚨 敌袭")) return "A2";
        if (text.startsWith("🚨 敌情")) return "A1";
        if (text.contains("资源")) return "A3";
        return "A4";
    }
}
