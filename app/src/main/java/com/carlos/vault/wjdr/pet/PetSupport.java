package com.carlos.vault.wjdr.pet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宠物协议状态的纯解析/预判工具（App daily/TownTasks 与 vault-server ServerTownTasks 共用）。
 *
 * <p>只处理 {@code java.util} 结构，不依赖 Android / 网络，便于单测锁定推送形状：
 * <ul>
 *   <li>{@code notify_pet_info{info:[{id,lv,break_lv,is_unlock}]}}：拥有的宠物；</li>
 *   <li>{@code notify_pet_ex_info{info:{use_skill_info:{"550031.0":{...}}}}}：技能上次使用时间；</li>
 *   <li>{@code notify_pet_adventure_info{info:{area_info:{...},daily_adventure_nums:N}}}：
 *       三个藏宝点（area_info 在实测里是 map，App/服务端旧代码误读成 areas 列表）。</li>
 * </ul>
 */
public final class PetSupport {

    /** 每一轮最多派遣 3 只宠物（野外同时只有 3 个藏宝点）。 */
    public static final int MAX_DISPATCH_PER_ROUND = 3;
    /** 每日基础寻宝次数（专家技能可增加；仅用于展示/兜底，不硬性拦截）。 */
    public static final int DEFAULT_DAILY_TIMES = 4;
    /** 本地冷却预判留 5 分钟余量，避免服务器时钟/推送延迟导致提前请求。 */
    private static final long COOLDOWN_SLACK_SEC = 300L;
    /** 每日基础寻宝次数上限（专家加成未知，真正上限以服务器返回为准）。 */
    private static final int DAILY_TIME_HINT = DEFAULT_DAILY_TIMES;

    private PetSupport() {
    }

    // ======================= 推送结构兼容 =======================

    /** 取推送内层：{@code {info:{...}}} → 内层；{@code {info:[...]}} 保持原样。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> infoMap(Map<String, Object> content) {
        if (content == null) return null;
        Object info = content.get("info");
        if (info instanceof Map) return (Map<String, Object>) info;
        return content;
    }

    /** 兼容 List / Map 两种集合形状（Map 的 key 回填 id，如 area_info:{"1.0":{...}}）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapsOf(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                Map<String, Object> inner = mapOf(e.getValue());
                if (inner == null || inner.isEmpty()) continue;
                Map<String, Object> item = new LinkedHashMap<>(inner);
                if (!item.containsKey("id")) item.put("id", e.getKey());
                out.add(item);
            }
        } else if (v instanceof Collection) {
            for (Object o : (Collection<Object>) v) {
                Map<String, Object> item = mapOf(o);
                if (item != null && !item.isEmpty()) out.add(item);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapOf(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        return null;
    }

    public static long longOf(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 解析 id（兼容 map 键 "1.0"/"550031.0" 这类带小数点的形状）：先按数字，失败再取小数点前。
     */
    public static long idOf(Object v) {
        long n = longOf(v);
        if (n > 0) return n;
        if (v == null) return 0L;
        String s = String.valueOf(v).trim();
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try {
            return Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0L;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    // ======================= 宠物 / 技能状态 =======================

    /** 拥有的宠物 id（{@code is_unlock=false} 的跳过）。 */
    public static Set<Long> ownedPetIds(Map<String, Object> petInfo) {
        Set<Long> out = new LinkedHashSet<>();
        if (petInfo == null) return out;
        Object info = petInfo.get("info");
        List<Map<String, Object>> list = new ArrayList<>();
        if (info != null) {
            list = mapsOf(info);
            if (list.isEmpty()) {
                Map<String, Object> inner = mapOf(info);
                if (inner != null) {
                    Object nested = inner.get("info");
                    if (nested == null) nested = inner.get("pet_info");
                    list = mapsOf(nested != null ? nested : inner);
                }
            }
        } else {
            list = mapsOf(petInfo);
        }
        for (Map<String, Object> p : list) {
            long id = idOf(p.get("id"));
            if (id <= 0) continue;
            Object unlock = p.get("is_unlock");
            if (unlock != null && !truthy(unlock)) continue;
            out.add(id);
        }
        return out;
    }

    /** 拥有/已解锁的宠物技能 id（宠物目录映射 ∪ use_skill_info 里出现过的技能）。 */
    public static Set<Long> ownedSkillIds(Map<String, Object> petInfo, Map<String, Object> exInfo) {
        Set<Long> out = new LinkedHashSet<>();
        for (Long petId : ownedPetIds(petInfo)) {
            long sid = PetSkillCatalog.skillIdOfPet(petId);
            if (sid > 0) out.add(sid);
        }
        for (Long sid : skillUseTimes(exInfo).keySet()) {
            if (sid > 0) out.add(sid);
        }
        return out;
    }

    /** 技能上次使用时间（秒）：{@code use_skill_info{"550031.0":{use_time}}} → 550031→use_time。 */
    public static Map<Long, Long> skillUseTimes(Map<String, Object> exInfo) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (exInfo == null) return out;
        Map<String, Object> inner = infoMap(exInfo);
        Object usi = inner == null ? null : inner.get("use_skill_info");
        if (usi == null && exInfo != null) usi = exInfo.get("use_skill_info");
        for (Map<String, Object> item : mapsOf(usi)) {
            long sid = longOf(item.get("skill_id"));
            if (sid <= 0 && item.get("id") != null) sid = longOf(item.get("id"));
            Map<String, Object> info = mapOf(item.get("skill_info"));
            long useTime = info == null ? 0L : longOf(info.get("use_time"));
            if (sid <= 0) {
                sid = parseSkillKey(item.get("id"));
            }
            if (sid > 0) out.put(sid, useTime);
        }
        return out;
    }

    private static long parseSkillKey(Object key) {
        if (key == null) return 0L;
        String s = String.valueOf(key).trim();
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try {
            return Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 技能是否已过本地预判冷却（lastUse<=0 视为从未使用，直接可用）。 */
    public static boolean skillReady(long lastUseSec, int cooldownHours, long nowSec) {
        if (lastUseSec <= 0) return true;
        if (cooldownHours <= 0) return true;
        return nowSec >= lastUseSec + cooldownHours * 3600L - COOLDOWN_SLACK_SEC;
    }

    /** 技能可读列表：技能名（按目录顺序）；未知 id 退回「技能<id>」。 */
    public static String formatSkillNames(Collection<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (Long id : skillIds) {
            if (id == null || id <= 0) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(PetSkillCatalog.nameOf(id));
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    public static String csv(Collection<Long> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Long v : values) {
            if (v == null || v <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    public static Set<Long> parseCsv(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String part : raw.split("[,，\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                long v = Long.parseLong(part.trim());
                if (v > 0) out.add(v);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    // ======================= 宠物寻宝 =======================

    /** 一个藏宝点。 */
    public static final class Area {
        public final long id;
        public final long petId;
        public final long finishTime;
        public final long refreshTime;
        public final long index;
        public final Map<String, Object> raw;

        Area(Map<String, Object> raw) {
            this.raw = raw;
            this.id = idOf(raw.get("id"));
            this.petId = longOf(raw.get("pet_id"));
            this.finishTime = longOf(raw.get("finish_time"));
            this.refreshTime = longOf(raw.get("refresh_time"));
            this.index = longOf(raw.get("index"));
        }

        /** 有宠物在寻宝（进行中或已完成待领取）。 */
        public boolean occupied() {
            return petId > 0;
        }

        /** 已完成、可领取奖励。 */
        public boolean completed(long nowSec) {
            return petId > 0 && finishTime > 0 && finishTime <= nowSec;
        }

        /** 进行中（未到 finish_time）。 */
        public boolean inProgress(long nowSec) {
            return petId > 0 && (finishTime <= 0 || finishTime > nowSec);
        }

        /** 空闲、可派遣。 */
        public boolean idle() {
            if (petId > 0) return false;
            Object st = raw.get("status");
            return st == null || longOf(st) == 0L;
        }
    }

    /** 解析 notify_pet_adventure_info 的 area_info（兼容 actions 旧字段名与 List/Map）。 */
    public static List<Area> adventureAreas(Map<String, Object> adventureInfo) {
        if (adventureInfo == null) return new ArrayList<>();
        Map<String, Object> inner = infoMap(adventureInfo);
        Object areaInfo = inner == null ? null : inner.get("area_info");
        if (areaInfo == null && inner != null) areaInfo = inner.get("areas");
        if (areaInfo == null) areaInfo = adventureInfo.get("area_info");
        if (areaInfo == null) areaInfo = adventureInfo.get("areas");
        List<Area> out = new ArrayList<>();
        for (Map<String, Object> m : mapsOf(areaInfo)) {
            Area a = new Area(m);
            if (a.id > 0) out.add(a);
        }
        return out;
    }

    /**
     * 在缓存推送里找回原始 area map（可写）。{@link #adventureAreas} 为防外部修改做了拷贝，
     * 请求成功后需要用本方法把新状态写回缓存，避免游戏不推送 notify 时下一轮重复派遣。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> findAreaMap(Map<String, Object> adventureInfo, long areaId) {
        if (adventureInfo == null || areaId <= 0) return null;
        Map<String, Object> inner = infoMap(adventureInfo);
        Object areaInfo = inner == null ? null : inner.get("area_info");
        if (areaInfo == null && inner != null) areaInfo = inner.get("areas");
        if (areaInfo == null) areaInfo = adventureInfo.get("area_info");
        if (areaInfo == null) areaInfo = adventureInfo.get("areas");
        if (areaInfo instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) areaInfo).entrySet()) {
                Map<String, Object> m = mapOf(e.getValue());
                if (m == null) continue;
                long id = idOf(m.get("id"));
                if (id <= 0) id = idOf(e.getKey());
                if (id == areaId) return m;
            }
        } else if (areaInfo instanceof Collection) {
            for (Object o : (Collection<Object>) areaInfo) {
                Map<String, Object> m = mapOf(o);
                if (m != null && idOf(m.get("id")) == areaId) return m;
            }
        }
        return null;
    }

    /** 派遣成功：把宠物/时间写回缓存 area（游戏不推 notify 时下一轮也不会重复派遣）。 */
    public static void markAreaDispatched(Map<String, Object> adventureInfo, long areaId,
                                          long petId, long startTime, long finishTime,
                                          boolean rewardMultiple) {
        Map<String, Object> area = findAreaMap(adventureInfo, areaId);
        if (area == null) return;
        area.put("pet_id", petId);
        if (startTime > 0) area.put("start_time", startTime);
        if (finishTime > 0) area.put("finish_time", finishTime);
        area.put("is_reward_multiple", rewardMultiple ? "true" : "false");
    }

    /** 领奖成功：该点已刷新为空闲点（写回 index/refresh_time，清理旧宠物/时间）。 */
    public static void markAreaClaimed(Map<String, Object> adventureInfo, long areaId,
                                       long refreshIndex, long refreshTime) {
        Map<String, Object> area = findAreaMap(adventureInfo, areaId);
        if (area == null) return;
        area.remove("pet_id");
        area.remove("start_time");
        area.remove("finish_time");
        area.put("is_reward_multiple", "false");
        if (refreshIndex > 0) area.put("index", refreshIndex);
        if (refreshTime > 0) area.put("refresh_time", refreshTime);
    }

    /** 今日已消耗的寻宝次数（{@code daily_adventure_nums}；用户口径为“已用”，基础上限 4）。 */
    public static int dailyAdventureUsed(Map<String, Object> adventureInfo) {
        if (adventureInfo == null) return 0;
        Map<String, Object> inner = infoMap(adventureInfo);
        Object v = inner == null ? null : inner.get("daily_adventure_nums");
        if (v == null) v = adventureInfo.get("daily_adventure_nums");
        long n = longOf(v);
        return n <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** 今日基础次数是否已用完（专家加成后可能 >4，故只作为提示，不硬拦截）。 */
    public static boolean dailyHintExhausted(int used) {
        return used >= DAILY_TIME_HINT;
    }

    /** 当前不在寻宝中的拥有宠物（按目录顺序/拥有顺序）。 */
    public static List<Long> idlePets(Collection<Long> ownedPetIds, Collection<Long> busyPetIds) {
        Set<Long> busy = new LinkedHashSet<>();
        if (busyPetIds != null) busy.addAll(busyPetIds);
        List<Long> out = new ArrayList<>();
        if (ownedPetIds == null) return out;
        for (Long id : ownedPetIds) {
            if (id != null && id > 0 && !busy.contains(id)) out.add(id);
        }
        return out;
    }
}
