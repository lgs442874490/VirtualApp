package com.carlos.vault.daily;

import org.json.JSONObject;

/**
 * 狩猎参数（fnId=hunt）纯逻辑层：解析 / 夹取 / 写回。
 *
 * <p>存储契约（App 与服务器共用，键名/格式不可改）：{@code params["hunt"]} 内
 * {@code level_min}（默认 4）/ {@code level_max}（默认 6）/ {@code max_times}（默认 5）。
 * {@link FieldTasks#hunt} 与服务器狩猎引擎按同一语义夹取：
 * {@code level_min ∈ [1,10]}、{@code level_max = max(level_min, clamp(level_max,1,10))}、
 * {@code max_times ∈ [1,20]}。本类保持与执行侧完全一致，避免"界面显示 Lv8-6、
 * 实际只打 Lv8"这类显示/执行不一致。
 *
 * <p>本类不碰 SharedPreferences / Activity / 网络，全是纯函数，便于 JVM 单测；
 * {@link #apply(JSONObject, Cfg)} 只覆盖三个参数键，params 内其它键（编队/子开关/未来
 * 新增字段）原样保留，不改变存储格式，旧数据/旧服务端可直接兼容。
 */
public final class HuntConfig {

    /** 功能 id（FnRegistry/FnParams 键）。 */
    public static final String FN_ID = "hunt";

    public static final String KEY_LEVEL_MIN = "level_min";
    public static final String KEY_LEVEL_MAX = "level_max";
    public static final String KEY_MAX_TIMES = "max_times";

    /** 等级可选范围 1-10。 */
    public static final int LEVEL_FLOOR = 1;
    public static final int LEVEL_CEIL = 10;
    /** 出击次数可选范围 1-20。 */
    public static final int TIMES_FLOOR = 1;
    public static final int TIMES_CEIL = 20;

    /** 缺省值（与 FieldTasks.hunt / 服务器狩猎引擎默认一致）。 */
    public static final int DEFAULT_LEVEL_MIN = 4;
    public static final int DEFAULT_LEVEL_MAX = 6;
    public static final int DEFAULT_MAX_TIMES = 5;

    /** 已夹取的狩猎配置：各值均在可选范围内且满足 {@code levelMin <= levelMax}。 */
    public static final class Cfg {
        public int levelMin;
        public int levelMax;
        public int maxTimes;

        public Cfg() {
            this(DEFAULT_LEVEL_MIN, DEFAULT_LEVEL_MAX, DEFAULT_MAX_TIMES);
        }

        public Cfg(int levelMin, int levelMax, int maxTimes) {
            this.levelMin = clampLevel(levelMin);
            this.levelMax = Math.max(this.levelMin, clampLevel(levelMax));
            this.maxTimes = clampTimes(maxTimes);
        }

        /** 设等级下限：越过上限时把上限抬到下限（保证 min<=max）。 */
        public void setLevelMin(int v) {
            levelMin = clampLevel(v);
            if (levelMax < levelMin) levelMax = levelMin;
        }

        /** 设等级上限：低于下限时把下限降到上限（以用户最新选的档为准，保证 min<=max）。 */
        public void setLevelMax(int v) {
            levelMax = clampLevel(v);
            if (levelMin > levelMax) levelMin = levelMax;
        }

        public void setMaxTimes(int v) {
            maxTimes = clampTimes(v);
        }

        /** 卡片摘要文案，如 {@code "Lv4-6 · 出击5次"}。 */
        public String summary() {
            return "Lv" + levelMin + "-" + levelMax + " · 出击" + maxTimes + "次";
        }

        public Cfg copy() {
            return new Cfg(levelMin, levelMax, maxTimes);
        }
    }

    /** 等级夹取到 [1,10]。 */
    public static int clampLevel(int v) {
        return Math.max(LEVEL_FLOOR, Math.min(LEVEL_CEIL, v));
    }

    /** 出击次数夹取到 [1,20]。 */
    public static int clampTimes(int v) {
        return Math.max(TIMES_FLOOR, Math.min(TIMES_CEIL, v));
    }

    /**
     * 纯函数：params JSON → 已夹取配置。null/缺键/类型异常一律回落默认值；
     * 兼容历史 JSON 里把数值写成字符串（如 {@code "level_min":"8"}）的旧数据。
     */
    public static Cfg parse(JSONObject p) {
        JSONObject o = p == null ? new JSONObject() : p;
        int min = clampLevel(numOf(o, KEY_LEVEL_MIN, DEFAULT_LEVEL_MIN));
        int max = clampLevel(numOf(o, KEY_LEVEL_MAX, DEFAULT_LEVEL_MAX));
        int times = clampTimes(numOf(o, KEY_MAX_TIMES, DEFAULT_MAX_TIMES));
        // 与 FieldTasks.hunt 一致：下限优先，上限至少等于下限
        return new Cfg(min, Math.max(min, max), times);
    }

    /**
     * 纯函数：把配置写回 params JSON 并返回同一对象（便于链式 save）。
     * 只改 level_min / level_max / max_times 三个键，其它键（编队等）保持不变。
     */
    public static JSONObject apply(JSONObject target, Cfg cfg) {
        JSONObject out = target == null ? new JSONObject() : target;
        Cfg c = cfg == null ? new Cfg() : cfg;
        try {
            out.put(KEY_LEVEL_MIN, c.levelMin);
            out.put(KEY_LEVEL_MAX, c.levelMax);
            out.put(KEY_MAX_TIMES, c.maxTimes);
        } catch (Throwable ignored) {
            // JSONObject.put 在本仓库用到的实现里可能声明受检 JSONException；失败的极端情形
            // 只影响本次写回（调用方仍会保存原对象），不抛给 UI 线程。
        }
        return out;
    }

    /** 等级下拉可选项 1..10（升序）。 */
    public static int[] levelOptions() {
        int[] a = new int[LEVEL_CEIL - LEVEL_FLOOR + 1];
        for (int i = 0; i < a.length; i++) a[i] = LEVEL_FLOOR + i;
        return a;
    }

    /** 出击次数下拉可选项 1..20（升序）。 */
    public static int[] timesOptions() {
        int[] a = new int[TIMES_CEIL - TIMES_FLOOR + 1];
        for (int i = 0; i < a.length; i++) a[i] = TIMES_FLOOR + i;
        return a;
    }

    /** 宽松取整数：Number 直接取，字符串尝试解析，其余/异常回落 def。 */
    private static int numOf(JSONObject o, String key, int def) {
        Object v = o == null ? null : o.opt(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return (int) Math.round(Double.parseDouble(((String) v).trim()));
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private HuntConfig() {
    }
}
