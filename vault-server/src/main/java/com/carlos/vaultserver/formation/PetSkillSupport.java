package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.Exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宠物技能状态（重物搬运等）的本地判定：避免对未解锁/未拥有技能反复触发 11542。
 *
 * <p>数据源（与网页版 DailyPetProtocolStateCore 同口径）：
 * <ul>
 *   <li>{@code notify_pet_info{info:[{id, lv, break_lv, is_unlock}]}} / {@code notify_pet_update}：
 *       宠物是否拥有 + 突破等级（技能等级由突破等级决定）；</li>
 *   <li>重物搬运技能 550031 属宠物 55003（麝牛），宠物目录 `skills` 按突破等级解锁（本类保守取
 *       break_lv &gt;= 2 视为已解锁，与网页版 skillLevel&gt;0 的常见门槛一致；具体等级映射后续可扩展）。</li>
 * </ul>
 *
 * <p>纯逻辑；未同步时返回“未知”（不阻塞，调用方保留原重试策略）。
 */
public final class PetSkillSupport {

    /** 重物搬运技能 id（网页版 DailyPetSkillConfig.HEAVY_LIFTING_SKILL_ID）。 */
    public static final long HEAVY_LIFTING_SKILL_ID = 550031L;
    /** 重物搬运所属宠物（麝牛）。 */
    public static final long HEAVY_LIFTING_PET_ID = 55003L;
    /** 解锁重物搬运所需突破等级（宠物目录 skill 定义中的常见门槛，保守值）。 */
    public static final int HEAVY_LIFTING_BREAK_LEVEL = 2;

    /** 宠物状态。 */
    public static final class PetState {
        public final long petId;
        public final long level;
        public final long breakLevel;
        public final boolean unlocked;

        PetState(long petId, long level, long breakLevel, boolean unlocked) {
            this.petId = petId;
            this.level = level;
            this.breakLevel = breakLevel;
            this.unlocked = unlocked;
        }
    }

    private PetSkillSupport() {
    }

    /** 读取宠物状态（notify_pet_info 全量 + notify_pet_update 增量；取不到返回 null）。 */
    @SuppressWarnings("unchecked")
    public static PetState pet(WjdrEngine engine, long petId) {
        if (engine == null) return null;
        Map<Long, PetState> all = pets(engine);
        return all.get(petId);
    }

    public static Map<Long, PetState> pets(WjdrEngine engine) {
        Map<Long, PetState> out = new LinkedHashMap<>();
        if (engine == null) return out;
        try {
            Map<String, Object> init = engine.notifyData("notify_pet_info");
            merge(out, init);
            Map<String, Object> update = engine.notifyData("notify_pet_update");
            merge(out, update);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void merge(Map<Long, PetState> out, Map<String, Object> content) {
        if (content == null) return;
        Object raw = content.get("info");
        if (raw == null) raw = content.get("pet_info");
        for (Map<String, Object> m : Exec.mapsOf(raw)) {
            long id = Exec.longOf(m.get("id"));
            if (id <= 0L) continue;
            long breakLevel = Exec.longOf(m.get("break_lv"));
            long level = Exec.longOf(m.get("lv"));
            boolean unlockFlag = !"false".equalsIgnoreCase(String.valueOf(m.get("is_unlock")));
            out.put(id, new PetState(id, level, breakLevel, unlockFlag));
        }
    }

    /** 重物搬运是否已解锁：拥有宠物 55003 且突破等级 ≥ 门槛。 */
    public static boolean heavyLiftingUnlocked(WjdrEngine engine) {
        PetState pet = pet(engine, HEAVY_LIFTING_PET_ID);
        if (pet == null || !pet.unlocked) return false;
        return pet.breakLevel >= HEAVY_LIFTING_BREAK_LEVEL;
    }

    /** 未解锁原因文案（已解锁返回空串）。 */
    public static String heavyLiftingLockReason(WjdrEngine engine) {
        PetState pet = pet(engine, HEAVY_LIFTING_PET_ID);
        if (pet == null) return "宠物状态未同步";
        if (!pet.unlocked) return "未拥有麝牛";
        if (pet.breakLevel < HEAVY_LIFTING_BREAK_LEVEL) {
            return "麝牛突破" + pet.breakLevel + "级（需" + HEAVY_LIFTING_BREAK_LEVEL + "级解锁重物搬运）";
        }
        return "";
    }
}
