package com.carlos.vault.wjdr.pet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宠物技能目录（App 日常页 / vault-server 共用，协议知识来自游戏本地化与实测推送）。
 *
 * <p>技能 id 形如 {@code 5500x1}（宠物 id × 10 + 技能槽 1），例如宠物 55003（麝牛）
 * 的技能是 550031（重物搬运）。目录按「发育类 / 战斗类」分组：
 * <ul>
 *   <li><b>发育类</b>：城建助手、心灵伴侣、重物搬运、敏锐感官、大地馈赠——每天冷却好自动使用；</li>
 *   <li><b>战斗类</b>：锋锐铁喙、迅猛急袭、野性咆哮、势如雪崩、兽群战术、猛虎出山、
 *       硬化皮肤、亘古不灭、迷雾吐息、寒渊坚甲——默认不自动使用，由用户逐项勾选。</li>
 * </ul>
 *
 * <p>冷却小时数用于本地预判（少发冷却中的请求）；即使数值与服务器有偏差，
 * 请求失败也只会被跳过，不会阻塞其它技能。
 */
public final class PetSkillCatalog {

    public static final int CAT_DEVELOPMENT = 1;
    public static final int CAT_COMBAT = 2;

    /** 单条宠物技能。 */
    public static final class Skill {
        public final long skillId;
        public final long petId;
        public final String petName;
        public final String skillName;
        public final int category;
        public final int cooldownHours;
        public final String effect;

        Skill(long skillId, long petId, String petName, String skillName,
              int category, int cooldownHours, String effect) {
            this.skillId = skillId;
            this.petId = petId;
            this.petName = petName;
            this.skillName = skillName;
            this.category = category;
            this.cooldownHours = cooldownHours;
            this.effect = effect;
        }

        public boolean development() {
            return category == CAT_DEVELOPMENT;
        }

        public String categoryName() {
            return development() ? "发育" : "战斗";
        }
    }

    private static final List<Skill> ALL;
    private static final Map<Long, Skill> BY_ID;
    private static final Map<Long, Skill> BY_PET;

    static {
        List<Skill> all = new ArrayList<>();
        // 发育类（每天冷却好自动使用）
        add(all, 550011L, 55001L, "洞斑鬣狗", "城建助手", CAT_DEVELOPMENT, 23,
                "建造速度提升，持续 5 分钟");
        add(all, 550021L, 55002L, "北极狼", "心灵伴侣", CAT_DEVELOPMENT, 23,
                "恢复领主最多 60 点体力");
        add(all, 550031L, 55003L, "麝牛", "重物搬运", CAT_DEVELOPMENT, 15,
                "下一次野外采集瞬间完成");
        add(all, 550041L, 55004L, "巨貘", "敏锐感官", CAT_DEVELOPMENT, 23,
                "立刻发现最多 500 份宠物口粮");
        add(all, 550061L, 55006L, "大角鹿", "大地馈赠", CAT_DEVELOPMENT, 23,
                "随机发现一份雪原珍宝");
        // 战斗类（默认关闭，用户可逐项开启）
        add(all, 550051L, 55005L, "泰坦巨鸟", "锋锐铁喙", CAT_COMBAT, 20,
                "提升部队攻击");
        add(all, 550071L, 55007L, "雪豹", "迅猛急袭", CAT_COMBAT, 20,
                "提升行军速度并降低敌方杀伤，持续 2 小时");
        add(all, 550081L, 55008L, "穴狮", "野性咆哮", CAT_COMBAT, 20,
                "提升我军攻击力，持续 2 小时");
        add(all, 550091L, 55009L, "雪猿", "势如雪崩", CAT_COMBAT, 20,
                "增加出征部队数量，持续 2 小时");
        add(all, 550101L, 55010L, "板齿犀", "兽群战术", CAT_COMBAT, 20,
                "提升部队作战属性");
        add(all, 550111L, 55011L, "剑齿虎", "猛虎出山", CAT_COMBAT, 20,
                "提升部队攻击力");
        add(all, 550121L, 55012L, "猛犸象", "硬化皮肤", CAT_COMBAT, 20,
                "提升部队防御力");
        add(all, 550131L, 55013L, "冰霜古猿", "亘古不灭", CAT_COMBAT, 20,
                "提升部队生存能力");
        add(all, 550141L, 55014L, "霜鳞避役", "迷雾吐息", CAT_COMBAT, 20,
                "降低敌方部队防御力，持续 2 小时");
        add(all, 550151L, 55015L, "新宠物", "寒渊坚甲", CAT_COMBAT, 20,
                "提升部队防御力");
        ALL = Collections.unmodifiableList(all);
        Map<Long, Skill> byId = new LinkedHashMap<>();
        Map<Long, Skill> byPet = new LinkedHashMap<>();
        for (Skill s : ALL) {
            byId.put(s.skillId, s);
            byPet.put(s.petId, s);
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_PET = Collections.unmodifiableMap(byPet);
    }

    private PetSkillCatalog() {
    }

    private static void add(List<Skill> out, long skillId, long petId, String petName,
                            String skillName, int category, int cooldownHours, String effect) {
        out.add(new Skill(skillId, petId, petName, skillName, category, cooldownHours, effect));
    }

    /** 全部技能（发育类在前，战斗类在后）。 */
    public static List<Skill> all() {
        return ALL;
    }

    public static Skill byId(long skillId) {
        return BY_ID.get(skillId);
    }

    public static Skill byPetId(long petId) {
        return BY_PET.get(petId);
    }

    /** 宠物 id → 默认技能 id（未收录返回 0）。 */
    public static long skillIdOfPet(long petId) {
        Skill s = BY_PET.get(petId);
        return s == null ? 0L : s.skillId;
    }

    /** 发育类技能 id（默认自动使用集合）。 */
    public static long[] developmentIds() {
        List<Long> ids = new ArrayList<>();
        for (Skill s : ALL) {
            if (s.development()) ids.add(s.skillId);
        }
        return toArray(ids);
    }

    /** 战斗类技能 id。 */
    public static long[] combatIds() {
        List<Long> ids = new ArrayList<>();
        for (Skill s : ALL) {
            if (!s.development()) ids.add(s.skillId);
        }
        return toArray(ids);
    }

    public static boolean isDevelopment(long skillId) {
        Skill s = BY_ID.get(skillId);
        return s != null && s.development();
    }

    /** 技能可读名；目录未收录时退回「技能 <id>」。 */
    public static String nameOf(long skillId) {
        Skill s = BY_ID.get(skillId);
        return s == null ? "技能" + skillId : s.skillName;
    }

    private static long[] toArray(List<Long> ids) {
        long[] out = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        return out;
    }
}
