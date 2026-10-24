package com.carlos.vault.daily;

import org.junit.Test;

import org.json.JSONObject;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 狩猎参数纯逻辑单测（批次13：移除「高级参数（JSON）」入口，狩猎参数改为下拉选项）。
 *
 * <p>契约依据：{@code params["hunt"]} 内 level_min/level_max/max_times（默认 4/6/5），
 * 与 FieldTasks.hunt 及服务器狩猎引擎同一夹取语义；本测试只跑 JVM 可测静态逻辑，
 * 不触碰 SharedPreferences / Activity / 网络。
 */
public class HuntConfigTest {

    // ==================== 默认值 ====================

    @Test
    public void parse_null_usesDefaults() {
        HuntConfig.Cfg cfg = HuntConfig.parse(null);
        assertEquals("默认下限 4", 4, cfg.levelMin);
        assertEquals("默认上限 6", 6, cfg.levelMax);
        assertEquals("默认出击 5 次", 5, cfg.maxTimes);
    }

    @Test
    public void parse_emptyObject_usesDefaults() {
        HuntConfig.Cfg cfg = HuntConfig.parse(new JSONObject());
        assertEquals(4, cfg.levelMin);
        assertEquals(6, cfg.levelMax);
        assertEquals(5, cfg.maxTimes);
    }

    @Test
    public void parse_partialKeys_keepsOtherDefaults() throws Exception {
        JSONObject p = new JSONObject();
        p.put("level_min", 7);
        HuntConfig.Cfg cfg = HuntConfig.parse(p);
        assertEquals(7, cfg.levelMin);
        assertEquals("缺 level_max → 默认 6 但被下限抬到 7", 7, cfg.levelMax);
        assertEquals("缺 max_times → 默认 5", 5, cfg.maxTimes);
    }

    // ==================== min > max 夹取 ====================

    @Test
    public void parse_minGreaterThanMax_maxIsRaisedToMin() throws Exception {
        // 与 FieldTasks.hunt 一致：lvMin 优先，lvMax = max(lvMin, clamp(lvMax))
        JSONObject p = new JSONObject();
        p.put("level_min", 8);
        p.put("level_max", 3);
        HuntConfig.Cfg cfg = HuntConfig.parse(p);
        assertEquals(8, cfg.levelMin);
        assertEquals(8, cfg.levelMax);
        assertTrue("夹取后必须满足 min<=max", cfg.levelMin <= cfg.levelMax);
    }

    @Test
    public void setters_keepMinNotGreaterThanMax() {
        HuntConfig.Cfg cfg = new HuntConfig.Cfg(2, 4, 5);
        cfg.setLevelMin(9);
        assertEquals(9, cfg.levelMin);
        assertEquals("下限抬高 → 上限跟上", 9, cfg.levelMax);

        cfg = new HuntConfig.Cfg(7, 9, 5);
        cfg.setLevelMax(3);
        assertEquals("上限降低 → 下限跟到上限（以最新选择为准）", 3, cfg.levelMin);
        assertEquals(3, cfg.levelMax);
    }

    // ==================== 越界夹取 ====================

    @Test
    public void parse_outOfRange_isClamped() throws Exception {
        JSONObject p = new JSONObject();
        p.put("level_min", 0);
        p.put("level_max", 99);
        p.put("max_times", 0);
        HuntConfig.Cfg cfg = HuntConfig.parse(p);
        assertEquals("level_min 下限 1", 1, cfg.levelMin);
        assertEquals("level_max 上限 10", 10, cfg.levelMax);
        assertEquals("max_times 下限 1", 1, cfg.maxTimes);

        p = new JSONObject();
        p.put("level_min", -5);
        p.put("level_max", 1000);
        p.put("max_times", 99);
        cfg = HuntConfig.parse(p);
        assertEquals(1, cfg.levelMin);
        assertEquals(10, cfg.levelMax);
        assertEquals("max_times 上限 20", 20, cfg.maxTimes);
    }

    @Test
    public void clamp_helpersCoverWholeRange() {
        assertEquals(HuntConfig.LEVEL_FLOOR, HuntConfig.clampLevel(-1));
        assertEquals(HuntConfig.LEVEL_CEIL, HuntConfig.clampLevel(11));
        assertEquals(5, HuntConfig.clampLevel(5));
        assertEquals(HuntConfig.TIMES_FLOOR, HuntConfig.clampTimes(0));
        assertEquals(HuntConfig.TIMES_CEIL, HuntConfig.clampTimes(21));
        assertEquals(20, HuntConfig.clampTimes(20));
    }

    @Test
    public void parse_legacyStringValues_tolerated() throws Exception {
        // 历史「高级参数（JSON）」里用户可能把数值写成字符串，不能因此丢配置
        JSONObject p = new JSONObject();
        p.put("level_min", "8");
        p.put("level_max", "9");
        p.put("max_times", "12");
        HuntConfig.Cfg cfg = HuntConfig.parse(p);
        assertEquals(8, cfg.levelMin);
        assertEquals(9, cfg.levelMax);
        assertEquals(12, cfg.maxTimes);
    }

    // ==================== apply：只写三个键，保留无关键 ====================

    @Test
    public void apply_keepsUnrelatedKeys() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "role");
        p.put("formation_id", 3);
        p.put("soldiers", "50000");
        p.put("ratio", "40:30:30");
        p.put("total", 120000);
        p.put("future_key", "keep-me");

        HuntConfig.Cfg cfg = new HuntConfig.Cfg(7, 9, 12);
        JSONObject out = HuntConfig.apply(p, cfg);

        assertSame("原地写回，便于直接 saveParams", p, out);
        assertEquals(7, out.optInt("level_min"));
        assertEquals(9, out.optInt("level_max"));
        assertEquals(12, out.optInt("max_times"));
        assertEquals("编队模式保留", "role", out.optString("formation_mode"));
        assertEquals("编队 id 保留", 3, out.optInt("formation_id"));
        assertEquals("兵量保留", "50000", out.optString("soldiers"));
        assertEquals("比例保留", "40:30:30", out.optString("ratio"));
        assertEquals("总量保留", 120000, out.optInt("total"));
        assertEquals("未知键也保留", "keep-me", out.optString("future_key"));
    }

    @Test
    public void apply_nullTarget_returnsObjectWithDefaults() {
        JSONObject out = HuntConfig.apply(null, null);
        assertNotNull(out);
        assertEquals(HuntConfig.DEFAULT_LEVEL_MIN, out.optInt("level_min", -1));
        assertEquals(HuntConfig.DEFAULT_LEVEL_MAX, out.optInt("level_max", -1));
        assertEquals(HuntConfig.DEFAULT_MAX_TIMES, out.optInt("max_times", -1));
    }

    @Test
    public void apply_thenParse_roundTrips() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "auto");
        HuntConfig.Cfg cfg = new HuntConfig.Cfg(2, 5, 8);
        JSONObject saved = HuntConfig.apply(p, cfg);
        // 模拟 FnParams：JSON 序列化再解析（存储格式不变）
        HuntConfig.Cfg back = HuntConfig.parse(new JSONObject(saved.toString()));
        assertEquals(2, back.levelMin);
        assertEquals(5, back.levelMax);
        assertEquals(8, back.maxTimes);
        assertEquals("auto", saved.optString("formation_mode"));
    }

    // ==================== UI 辅助（下拉选项/摘要） ====================

    @Test
    public void options_coverWholeSelectableRange() {
        int[] levels = HuntConfig.levelOptions();
        assertEquals(10, levels.length);
        assertEquals(1, levels[0]);
        assertEquals(10, levels[levels.length - 1]);
        int[] times = HuntConfig.timesOptions();
        assertEquals(20, times.length);
        assertEquals(1, times[0]);
        assertEquals(20, times[times.length - 1]);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, levels);
    }

    @Test
    public void summary_showsCurrentValues() {
        assertEquals("Lv4-6 · 出击5次", new HuntConfig.Cfg().summary());
        assertEquals("Lv8-8 · 出击20次", new HuntConfig.Cfg(8, 3, 20).summary());
    }

    @Test
    public void copy_isIndependent() {
        HuntConfig.Cfg a = new HuntConfig.Cfg(4, 6, 5);
        HuntConfig.Cfg b = a.copy();
        b.setLevelMin(9);
        assertEquals("拷贝互不影响", 4, a.levelMin);
        assertEquals(9, b.levelMin);
        assertFalse(a.levelMin == b.levelMin);
    }
}
