package com.carlos.vaultserver.formation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 英雄实力计算（2026-11-09，逐项对齐网页版 com.wjdr.tool.modules.behemoth.service.HeroPowerCatalog）。
 *
 * <p>游戏协议不提供英雄战力（notify_hero_init 只有 等级/星级/专武/技能），
 * 网页版用内置 {@code config/hero-catalog.json} 的 powerModel 计算：
 * <pre>
 * 实力 = 等级战力 × levelPowerRatio
 *      + 星级战力 × starPowerRatio
 *      + 技能战力（quality*100 + position*10 + level）
 *      + 装备战力（powerRatio/10000 × 强化等级战力 + 天赋百分比 + 突破）
 *      + 专武战力（exclusivePowerRatio/10000 × 专武等级战力）
 * </pre>
 * 无装备的赫克托（50024，Lv80/5星/专武5/技能满）≈ 2,304,648；装备强化后接近游戏内实力。
 */
public final class HeroPowerCatalog {

    private static final JSONObject MODEL = loadModel();
    private static final JSONObject HEROES = MODEL.optJSONObject("heroes");
    private static final JSONArray HERO_LEVEL_POWER = MODEL.optJSONArray("heroLevelPower");
    private static final JSONArray HERO_STAR_POWER = MODEL.optJSONArray("heroStarPower");
    private static final JSONObject SKILL_POWER = MODEL.optJSONObject("skillPower");
    private static final JSONObject SKILL_NEED_STAR = MODEL.optJSONObject("skillNeedStar");
    private static final JSONObject EQUIPMENT_POWER_RATIO = MODEL.optJSONObject("equipmentPowerRatio");
    private static final JSONArray EQUIPMENT_LEVEL_POWER = MODEL.optJSONArray("equipmentLevelPower");
    private static final JSONObject EQUIPMENT_TALENT_PERCENT = MODEL.optJSONObject("equipmentTalentPowerPercentage");
    private static final JSONArray EQUIPMENT_BREAK_LEVELS = MODEL.optJSONArray("equipmentBreakLevels");
    private static final JSONObject EQUIPMENT_BREAK_POWER = MODEL.optJSONObject("equipmentBreakPower");
    private static final JSONObject EXCLUSIVE_POWER_RATIO = MODEL.optJSONObject("exclusivePowerRatio");
    private static final JSONArray EXCLUSIVE_LEVEL_POWER = MODEL.optJSONArray("exclusiveLevelPower");

    private HeroPowerCatalog() {
    }

    /**
     * 英雄实力（含装备/专武）；协议里已有 powerFight/power 时优先用协议值。
     *
     * @param hero      原始英雄推送对象（id/lev/training_lev/star/elv/skills）
     * @param equipment 该英雄身上的装备明细（eid/slv/rlv/rstage/sid；sid=5 跳过）
     */
    public static long power(Map<String, Object> hero, List<Map<String, Object>> equipment) {
        if (hero == null || hero.isEmpty()) return 0L;
        long explicit = Math.max(num(hero.get("powerFight")),
                Math.max(num(hero.get("power_fight")), num(hero.get("power"))));
        if (explicit > 0L) return explicit;
        long id = num(hero.get("id"));
        JSONObject def = HEROES == null ? null : HEROES.optJSONObject(String.valueOf(id));
        if (def == null) return 0L;   // 目录无该英雄（测试/新英雄）→ 保留协议值语义（0）
        double total = basePower(def, hero)
                + equipmentPower(equipment)
                + exclusivePower(id, hero);
        return total <= 0.0 ? 0L : Math.round(total);
    }

    private static double basePower(JSONObject def, Map<String, Object> hero) {
        double total = 0.0;
        long level = Math.max(1L, effectiveLevel(hero));
        if (level <= HERO_LEVEL_POWER.length()) {
            total += def.optDouble("levelPowerRatio", 0.0) / 100.0 * HERO_LEVEL_POWER.optLong((int) level - 1, 0);
        }
        long star = Math.max(0L, num(hero.get("star")));
        if (star < HERO_STAR_POWER.length()) {
            total += def.optDouble("starPowerRatio", 0.0) / 100.0 * HERO_STAR_POWER.optLong((int) star, 0);
        }
        total += skillPower(def, hero);
        return total;
    }

    private static long skillPower(JSONObject def, Map<String, Object> hero) {
        Map<Long, Long> unlocked = skillLevels(hero.get("skills"));
        long total = 0L;
        JSONArray skills = def.optJSONArray("skills");
        if (skills == null) return 0L;
        int quality = def.optInt("quality", 0);
        for (int i = 0; i < skills.length(); i++) {
            JSONObject skill = skills.optJSONObject(i);
            if (skill == null) continue;
            long skillId = skill.optLong("id", 0L);
            int position = skill.optInt("position", 0);
            long protocolLevel = unlocked.getOrDefault(skillId, 0L);
            long level = Math.max(1L, protocolLevel);
            String key = String.valueOf(quality * 100L + position * 10L + level);
            Long power = SKILL_POWER == null || !SKILL_POWER.has(key) ? null : SKILL_POWER.optLong(key, 0L);
            if (power == null) continue;
            boolean active = position == 0 || protocolLevel > 0L;
            if (!active) {
                // 网页版语义：未激活但 needStar=0 的技能仍计入战力
                Long needStar = SKILL_NEED_STAR == null ? null
                        : (SKILL_NEED_STAR.has(key) ? SKILL_NEED_STAR.optLong(key, 0L) : null);
                if (needStar == null || needStar != 0L) continue;
            }
            total += power;
        }
        return total;
    }

    /** 协议 skills：{订阅id:{id,lv}} 或 [{id,lv}]（取同 id 最大等级）。 */
    private static Map<Long, Long> skillLevels(Object raw) {
        Map<Long, Long> levels = new LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Object v : ((Map<?, ?>) raw).values()) {
                Map<String, Object> m = mapOf(v);
                if (m == null) continue;
                long id = num(m.get("id"));
                long level = Math.max(0L, num(m.get("lv")));
                if (id <= 0L || level <= 0L) continue;
                levels.merge(id, level, Math::max);
            }
        } else if (raw instanceof List) {
            for (Object v : (List<?>) raw) {
                Map<String, Object> m = mapOf(v);
                if (m == null) continue;
                long id = num(m.get("id"));
                long level = Math.max(0L, num(m.get("lv")));
                if (id <= 0L || level <= 0L) continue;
                levels.merge(id, level, Math::max);
            }
        }
        return levels;
    }

    private static double equipmentPower(List<Map<String, Object>> equipment) {
        if (equipment == null || equipment.isEmpty()) return 0.0;
        double total = 0.0;
        for (Map<String, Object> detail : equipment) {
            if (detail == null || detail.isEmpty()) continue;
            if (num(detail.get("sid")) == 5L) continue;   // 专武槽由 exclusive 计算
            long equipmentId = num(detail.get("eid"));
            if (EQUIPMENT_POWER_RATIO == null || !EQUIPMENT_POWER_RATIO.has(String.valueOf(equipmentId))) continue;
            double ratio = EQUIPMENT_POWER_RATIO.optDouble(String.valueOf(equipmentId), 0.0);
            long strengthLevel = Math.max(0L, num(detail.get("slv")));
            double levelFight = ratio / 10000.0 * levelPower(EQUIPMENT_LEVEL_POWER, strengthLevel);
            double talentFight = talentFight(detail, levelFight);
            long breakPower = breakPower(equipmentId, strengthLevel);
            total += Math.floor(levelFight + talentFight + breakPower);
        }
        return total;
    }

    private static double talentFight(Map<String, Object> detail, double levelFight) {
        long talentLevel = Math.max(0L, num(detail.get("rlv")));
        if (talentLevel == 0L || EQUIPMENT_TALENT_PERCENT == null) return 0.0;
        String key = talentLevel + "_" + Math.max(0L, num(detail.get("rstage")));
        if (!EQUIPMENT_TALENT_PERCENT.has(key)) return 0.0;
        return levelFight * EQUIPMENT_TALENT_PERCENT.optDouble(key, 0.0) / 100.0;
    }

    private static long breakPower(long equipmentId, long strengthLevel) {
        if (EQUIPMENT_BREAK_POWER == null || EQUIPMENT_BREAK_LEVELS == null) return 0L;
        JSONArray values = EQUIPMENT_BREAK_POWER.optJSONArray(String.valueOf(equipmentId));
        if (values == null || values.length() == 0) return 0L;
        long total = 0L;
        for (int i = 0; i < EQUIPMENT_BREAK_LEVELS.length() && i < values.length(); i++) {
            if (strengthLevel >= EQUIPMENT_BREAK_LEVELS.optLong(i, 0L)) {
                total += values.optLong(i, 0L);
            }
        }
        return total;
    }

    private static double exclusivePower(long heroId, Map<String, Object> hero) {
        long level = Math.max(0L, num(hero.get("elv")));
        if (level == 0L || EXCLUSIVE_POWER_RATIO == null) return 0.0;
        if (!EXCLUSIVE_POWER_RATIO.has(String.valueOf(heroId))) return 0.0;
        double ratio = EXCLUSIVE_POWER_RATIO.optDouble(String.valueOf(heroId), 0.0);
        return ratio / 10000.0 * levelPower(EXCLUSIVE_LEVEL_POWER, level);
    }

    /** 网页版 at(values, index)：越界返回 0。 */
    private static long levelPower(JSONArray values, long index) {
        if (values == null || index < 0L || index >= values.length()) return 0L;
        return values.optLong((int) index, 0L);
    }

    private static long fallbackPower(Map<String, Object> hero) {
        long level = Math.max(1L, effectiveLevel(hero));
        long star = Math.max(0L, num(hero.get("star")));
        long skillTotal = 0L;
        for (long skillLevel : skillLevels(hero.get("skills")).values()) skillTotal += skillLevel;
        return level * 1_000_000L + star * 1_000L + skillTotal;
    }

    private static long effectiveLevel(Map<String, Object> hero) {
        return Math.max(1L, Math.max(num(hero.get("lev")), num(hero.get("training_lev"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try {
                return (long) Double.parseDouble(((String) o).trim());
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static JSONObject loadModel() {
        try (InputStream in = HeroPowerCatalog.class.getResourceAsStream("/config/hero-catalog.json")) {
            if (in == null) return new JSONObject();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONObject root = new JSONObject(sb.toString());
            JSONObject model = root.optJSONObject("powerModel");
            return model == null ? new JSONObject() : model;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 供测试：无装备基础实力（不读协议显式 power）。 */
    static long basePowerForTest(Map<String, Object> hero) {
        long id = num(hero.get("id"));
        JSONObject def = HEROES == null ? null : HEROES.optJSONObject(String.valueOf(id));
        if (def == null) return fallbackPower(hero);
        return Math.round(basePower(def, hero));
    }
}
