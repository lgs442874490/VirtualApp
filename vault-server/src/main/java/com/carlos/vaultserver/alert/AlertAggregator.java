package com.carlos.vaultserver.alert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 同「攻击者 + 目标」的 30s 波次聚合（设计 §10.3，按用户 2026-11-09 口径修正）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>聚合键必须含攻击者（owner）：a 打 b 的 N 波合并；a/c/d 同时打 b 是三个窗口，各自独立推送；</li>
 *   <li>首条立即返回 true（即时推送），窗口内后续波次返回 false（静默累计）；</li>
 *   <li>窗口到期由调用方 flush：仅当 count&gt;1 时返回一条汇总（“共 N 波”）。</li>
 * </ul>
 */
public final class AlertAggregator {

    /** 一个聚合窗口。 */
    public static final class Window {
        public final String key;
        public final long firstAt;
        public int count;
        public String baseText;

        Window(String key, long firstAt, int count, String baseText) {
            this.key = key;
            this.firstAt = firstAt;
            this.count = count;
            this.baseText = baseText;
        }
    }

    private final long windowMs;
    private final Map<String, Window> windows = new HashMap<>();

    public AlertAggregator(long windowMs) {
        this.windowMs = windowMs;   // <=0 = disabled（单测/未装配中枢时直通）
    }

    /**
     * 喂入一波。返回 true=首条（调用方立即推送）；false=窗口内后续（静默，等 flush 汇总）。
     * 同 key 距离上次首条超过窗口 → 视为新窗口，重新返回 true。
     */
    public synchronized boolean feed(String key, String text, long now) {
        if (windowMs <= 0L) return true;   // disabled：永不聚合
        Window w = windows.get(key);
        if (w == null || now - w.firstAt >= windowMs) {
            windows.put(key, new Window(key, now, 1, text));
            return true;
        }
        w.count++;
        return false;
    }

    /** 到期窗口：仅 count&gt;1 的返回（ammount=1 的已即时推过，不重复）。 */
    public synchronized List<Window> flushExpired(long now) {
        List<Window> out = new ArrayList<>();
        if (windowMs <= 0L) return out;
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Window w = it.next().getValue();
            if (now - w.firstAt < windowMs) continue;
            it.remove();
            if (w.count > 1) out.add(w);
        }
        return out;
    }

    /** 诊断/测试：当前窗口数。 */
    public synchronized int size() {
        return windows.size();
    }
}
