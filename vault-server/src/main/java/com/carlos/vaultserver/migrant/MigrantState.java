package com.carlos.vaultserver.migrant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色级移民状态快照（参考原版 {@code MigrantStateTracker} 623 行移植）。
 *
 * <p>职责：把登录初始化推送与请求响应解析成 {@link Snapshot}，供 {@link MigrantRules#evaluate}
 * 与 14 个 action 使用。原版覆盖背包/医院/招募/世界行军等大量与移民无关的字段，本类只保留
 * 移民判定真正用到的部分：基础信息、联盟、背包（许可证/召回/加速道具）、移民活动信息、
 * 王国列表/详情、赛会、邀请、行军（召回前准备）。
 *
 * <p>线程模型：所有写方法 synchronized；引擎 inbound 钩子、角色会话线程、HTTP 线程都只会
 * 短暂进锁，不做 IO。
 */
public final class MigrantState {

    // ---------- 角色/活动基础 ----------
    private long uid;
    private long currentKid;
    private long registerTimeSeconds;
    private int stoveLevel;
    private int office;
    private long allianceId;
    private boolean supremacyWarActive;
    private boolean migrantFirstLoginNotified;
    private boolean baseInitialized;
    private boolean migrantInitialized;
    private boolean backpackInitialized;
    private boolean allianceInitialized;
    private boolean serverTimeInitialized;
    private int utcOffsetSeconds;

    /** 移民活动 info（notify_migrant_init.info 原样保留）。 */
    private Map<String, Object> migrantInfo = new LinkedHashMap<>();

    private final Map<Integer, Long> itemCounts = new LinkedHashMap<>();
    private final Map<Long, March> marches = new LinkedHashMap<>();
    private final Map<Long, KingdomInfo> kingdoms = new LinkedHashMap<>();
    private final Map<Long, Integer> roleCountByKingdom = new LinkedHashMap<>();
    /** 最近一次收到的服务器时间（毫秒），用于无角色引擎时的兜底展示。 */
    private volatile long lastServerEpochMillis;

    public MigrantState() {
    }

    // =====================================================================
    // 推送入口
    // =====================================================================

    /**
     * 处理一条入站消息（引擎 addMessageHook 回调，RESPONSE 与 notify 都会到）。
     * 只认识移民相关协议；其余静默忽略。
     */
    public synchronized void handle(String proto, Map<String, Object> content) {
        if (proto == null || content == null) return;
        switch (proto) {
            case "loginauth":
                recordLogin(content);
                break;
            case "notify_game_init": {
                Object offset = content.get("utc_offset");
                if (offset != null) utcOffsetSeconds = intOf(offset);
                updateServerTime(content.get("servertime"));
                break;
            }
            case "notify_server_time":
                updateServerTime(content.get("time"));
                break;
            case "notify_base_info":
                handleBaseInfo(content);
                break;
            case "notify_migrant_init":
                handleMigrantInit(content);
                break;
            case "notify_migrant_first_login":
                migrantFirstLoginNotified = true;
                break;
            case "notify_backpack_init":
                handleBackpackInit(content);
                break;
            case "notify_backpack_update":
                handleBackpackUpdate(content);
                break;
            case "notify_alliance_init":
                handleAllianceInit(content);
                break;
            case "notify_alliance_player_update":
                handleAlliancePlayerUpdate(content);
                break;
            case "notify_alliance_update":
                handleAllianceUpdate(content);
                break;
            case "notify_supremacy_war_player_init":
                supremacyWarActive = intOf(content.get("state")) != 0;
                break;
            case "notify_world_player_init":
                handleWorldPlayerInit(content);
                break;
            case "notify_world_march_add":
                rememberOwnedWorldMarch(content.get("march"));
                break;
            case "notify_world_march_update":
                handleWorldMarchUpdate(content);
                break;
            case "notify_world_march_del":
            case "notify_player_march_del":
                removeMarch(content.get("id"));
                break;
            case "notify_player_march_add":
                rememberMarch(content.get("march"));
                break;
            case "notify_player_march_update":
                handleMarchUpdate(content);
                break;
            case MigrantRules.PROTO_KINGDOM_LIST:
                handleKingdomList(content);
                break;
            case MigrantRules.PROTO_KINGDOM_INFO:
                rememberKingdom(stringMap(content.get("info")));
                break;
            default:
                break;
        }
    }

    /** 登录响应：uid / serverid / role_list（同账号角色按 kid 计数）。 */
    public synchronized void recordLogin(Map<String, Object> content) {
        if (content == null) return;
        Long loginUid = longObject(content.get("uid"));
        if (loginUid != null) uid = loginUid;
        Long loginKid = longObject(content.get("serverid"));
        if (loginKid != null) currentKid = loginKid;
        roleCountByKingdom.clear();
        for (Map<String, Object> role : objectMaps(content.get("role_list"))) {
            long kid = longOf(role.get("kid"), 0L);
            if (kid <= 0L) continue;
            Integer prev = roleCountByKingdom.get(kid);
            roleCountByKingdom.put(kid, prev == null ? 1 : prev + 1);
        }
    }

    /** 用账号库角色表补齐「同账号在目标王国的角色数」（登录响应没带 role_list 时兜底）。 */
    public synchronized void seedRoleCounts(Map<Long, Integer> counts) {
        if (counts == null || counts.isEmpty()) return;
        if (!roleCountByKingdom.isEmpty() && counts.keySet().equals(roleCountByKingdom.keySet())) return;
        roleCountByKingdom.clear();
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getKey() != null && e.getKey() > 0L && e.getValue() != null && e.getValue() > 0) {
                roleCountByKingdom.put(e.getKey(), e.getValue());
            }
        }
    }

    /** 引擎侧请求成功后主动记录响应（原版 recordSuccessfulRequest 移植）。 */
    public synchronized void recordResponse(String proto, Map<String, Object> content) {
        if (proto == null || content == null) return;
        if (MigrantRules.PROTO_KINGDOM_LIST.equals(proto)) {
            handleKingdomList(content);
        } else if (MigrantRules.PROTO_KINGDOM_INFO.equals(proto)) {
            rememberKingdom(stringMap(content.get("info")));
        }
    }

    private void handleKingdomList(Map<String, Object> content) {
        kingdoms.clear();
        for (Map<String, Object> info : objectMaps(content.get("list"))) {
            rememberKingdom(info);
        }
    }

    private void handleBaseInfo(Map<String, Object> content) {
        Long nextUid = longObject(content.get("uid"));
        if (nextUid != null) uid = nextUid;
        Long nextKid = longObject(content.get("kid"));
        if (nextKid != null) currentKid = nextKid;
        Long nextRegisterTime = longObject(content.get("register_time"));
        if (nextRegisterTime != null) registerTimeSeconds = nextRegisterTime;
        Object nextStove = content.get("stove_lv");
        if (nextStove != null) stoveLevel = intOf(nextStove);
        Object nextOffice = content.get("office");
        if (nextOffice != null) office = intOf(nextOffice);
        baseInitialized = true;
    }

    private void handleMigrantInit(Map<String, Object> content) {
        Map<String, Object> info = stringMap(content.get("info"));
        if (info.isEmpty() && content.containsKey("act_info")) info = content;
        migrantInfo = deepCopyMap(info);
        migrantInitialized = !migrantInfo.isEmpty();
    }

    private void handleBackpackInit(Map<String, Object> content) {
        itemCounts.clear();
        for (Map<String, Object> item : objectMaps(content.get("data"))) {
            int id = intOf(item.get("id"));
            if (id <= 0) continue;
            itemCounts.put(id, longOf(item.get("count"), 0L));
        }
        backpackInitialized = true;
    }

    private void handleBackpackUpdate(Map<String, Object> content) {
        boolean delete = "del".equalsIgnoreCase(String.valueOf(content.get("type")));
        for (Map<String, Object> item : objectMaps(content.get("data"))) {
            int id = intOf(item.get("id"));
            if (id <= 0) continue;
            long count = longOf(item.get("count"), 0L);
            Long current = itemCounts.get(id);
            long next = (current == null ? 0L : current) + (delete ? -count : count);
            itemCounts.put(id, Math.max(0L, next));
        }
    }

    private void handleAllianceInit(Map<String, Object> content) {
        allianceId = longOf(stringMap(content.get("player")).get("aid"), 0L);
        allianceInitialized = true;
    }

    private void handleAlliancePlayerUpdate(Map<String, Object> content) {
        Map<String, Object> player = stringMap(content.get("player"));
        Long nextAid = longObject(player.get("aid"));
        if (nextAid != null) allianceId = nextAid;
        allianceInitialized = true;
    }

    private void handleAllianceUpdate(Map<String, Object> content) {
        Map<?, ?> members = asMap(content.get("members"));
        for (Map.Entry<?, ?> entry : members.entrySet()) {
            Map<String, Object> member = stringMap(entry.getValue());
            long memberUid = longOf(firstNonNull(member.get("uid"), entry.getKey()), 0L);
            if (memberUid == uid && booleanOf(member.get("del"))) allianceId = 0L;
        }
    }

    private void updateServerTime(Object value) {
        long epochMillis = normalizeEpochMillis(longOf(value, 0L));
        if (epochMillis > 0L) {
            lastServerEpochMillis = epochMillis;
            serverTimeInitialized = true;
        }
    }

    // ---------- 行军（召回准备兜底；优先用 CoreAssetLibrary 队列） ----------

    private void handleWorldPlayerInit(Map<String, Object> content) {
        marches.clear();
        for (Map<String, Object> march : objectMaps(content.get("march_info"))) {
            rememberMarch(march);
        }
    }

    private void handleWorldMarchUpdate(Map<String, Object> content) {
        long id = longOf(content.get("id"), 0L);
        long owner = longOf(content.get("owner"), 0L);
        Map<String, Object> change = stringMap(content.get("change"));
        if (id <= 0L) id = longOf(change.get("id"), 0L);
        if (id <= 0L) return;
        if (owner > 0L && owner != uid && !marches.containsKey(id)) return;
        if (owner <= 0L && !marches.containsKey(id)) return;
        March march = marches.get(id);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (march != null) merged.putAll(march.raw);
        merged.putAll(change);
        merged.put("id", id);
        if (owner > 0L) merged.put("owner", owner);
        rememberOwnedWorldMarch(merged);
    }

    private void handleMarchUpdate(Map<String, Object> content) {
        long id = longOf(content.get("id"), 0L);
        if (id <= 0L) return;
        March march = marches.get(id);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (march != null) merged.putAll(march.raw);
        merged.putAll(stringMap(content.get("change")));
        merged.put("id", id);
        rememberMarch(merged);
    }

    private void rememberMarch(Object value) {
        Map<String, Object> raw = stringMap(value);
        long id = longOf(raw.get("id"), 0L);
        if (id > 0L) marches.put(id, March.from(raw));
    }

    private void rememberOwnedWorldMarch(Object value) {
        Map<String, Object> raw = stringMap(value);
        if (longOf(raw.get("owner"), 0L) == uid) rememberMarch(raw);
    }

    private void removeMarch(Object value) {
        Map<String, Object> raw = stringMap(value);
        long id = longOf(firstNonNull(raw.get("id"), value), 0L);
        if (id > 0L) marches.remove(id);
    }

    private void rememberKingdom(Map<String, Object> info) {
        long kid = longOf(info.get("kid"), 0L);
        if (kid > 0L) kingdoms.put(kid, KingdomInfo.from(info));
    }

    // =====================================================================
    // 读取
    // =====================================================================

    /** 指定道具库存（未同步返回 0）。 */
    public synchronized long itemCount(int itemId) {
        Long v = itemCounts.get(itemId);
        return v == null ? 0L : v;
    }

    /** 退盟成功后乐观置零（服务端 notify 可能晚于请求返回；原版 tracker 也只信推送）。 */
    public synchronized void markAllianceLeft() {
        allianceId = 0L;
        allianceInitialized = true;
    }

    /** 背包是否已同步（道具不足判断用）。 */
    public synchronized boolean backpackSynced() {
        return backpackInitialized;
    }

    /** 本地扣减道具计数（发给服务端后先记账，等 notify_backpack_update 校准）。 */
    public synchronized void consumeItem(int itemId, long count) {
        if (count <= 0L) return;
        Long current = itemCounts.get(itemId);
        itemCounts.put(itemId, Math.max(0L, (current == null ? 0L : current) - count));
    }

    public synchronized long currentKid() {
        return currentKid;
    }

    public synchronized long uid() {
        return uid;
    }

    public synchronized boolean firstLoginNotified() {
        return migrantFirstLoginNotified;
    }

    public synchronized void clearFirstLoginFlag() {
        migrantFirstLoginNotified = false;
    }

    public synchronized void resetForLogin() {
        uid = 0L;
        currentKid = 0L;
        registerTimeSeconds = 0L;
        stoveLevel = 0;
        office = 0;
        allianceId = 0L;
        supremacyWarActive = false;
        migrantFirstLoginNotified = false;
        baseInitialized = false;
        migrantInitialized = false;
        backpackInitialized = false;
        allianceInitialized = false;
        serverTimeInitialized = false;
        migrantInfo = new LinkedHashMap<>();
        itemCounts.clear();
        marches.clear();
        kingdoms.clear();
        roleCountByKingdom.clear();
    }

    /** 行军快照（兜底数据源；引擎优先读 CoreAssetLibrary）。 */
    public synchronized List<March> marches() {
        return Collections.unmodifiableList(new ArrayList<>(marches.values()));
    }

    /** 是否已收到移民活动配置。 */
    public synchronized boolean migrantInitialized() {
        return migrantInitialized;
    }

    // =====================================================================
    // Snapshot
    // =====================================================================

    /**
     * 构建评估快照。
     *
     * @param serverNowMillis 引擎时钟（角色游戏服时钟或本机墙钟兜底）
     * @param clockReady      角色游戏服时钟是否已校准（仅用于 clock_source 展示）
     */
    public synchronized Snapshot snapshot(long serverNowMillis, boolean clockReady) {
        Map<String, Object> activity = deepCopyMap(migrantInfo);
        Map<Long, Long> kidOpenTimes = new LinkedHashMap<>();
        forEachPair(activity.get("kid_set"), (key, value) -> {
            long kid = longOf(key, 0L);
            long openTime = longOf(value, 0L);
            if (kid > 0L && openTime > 0L) kidOpenTimes.put(kid, openTime);
        });
        Map<String, Object> actInfo = stringMap(activity.get("act_info"));
        long nowMillis = serverNowMillis > 0L ? serverNowMillis
                : (lastServerEpochMillis > 0L ? lastServerEpochMillis : System.currentTimeMillis());
        List<Long> invitationKids = invitationKids(activity);
        return new Snapshot(
                baseInitialized, migrantInitialized, backpackInitialized, allianceInitialized,
                true, uid, currentKid, registerTimeSeconds, stoveLevel, office, allianceId,
                supremacyWarActive, migrantFirstLoginNotified, nowMillis, clockReady, utcOffsetSeconds,
                longOf(actInfo.get("id"), 0L), longOf(actInfo.get("start_ts"), 0L),
                longOf(actInfo.get("end_ts"), 0L), longOf(actInfo.get("serial_no"), 0L),
                longOf(activity.get("gid"), 0L), longOf(activity.get("standard_points"), 0L),
                longOf(activity.get("personal_points"), 0L), longOf(activity.get("last_migrant_ts"), 0L),
                longOf(activity.get("president"), 0L),
                itemCounts.containsKey(MigrantRules.MIGRANT_PERMIT_ITEM_ID)
                        ? itemCounts.get(MigrantRules.MIGRANT_PERMIT_ITEM_ID) : 0L,
                Collections.unmodifiableMap(kidOpenTimes),
                Collections.unmodifiableMap(new LinkedHashMap<>(kingdoms)),
                Collections.unmodifiableMap(new LinkedHashMap<>(roleCountByKingdom)),
                Collections.unmodifiableList(invitationKids));
    }

    private static List<Long> invitationKids(Map<String, Object> activity) {
        Map<String, Object> civilian = stringMap(activity.get("civilian_data"));
        List<Long> result = new ArrayList<>();
        for (Map<String, Object> invitation : objectMaps(civilian.get("invitation_list"))) {
            long kid = longOf(invitation.get("kid"), 0L);
            if (kid <= 0L || intOf(invitation.get("status")) != 0) continue;
            result.add(kid);
        }
        return result;
    }

    /** 移民状态快照（不可变；字段均 public final，Java 8 无 record）。 */
    public static final class Snapshot {
        public final boolean baseInitialized;
        public final boolean migrantInitialized;
        public final boolean backpackInitialized;
        public final boolean allianceInitialized;
        public final boolean serverTimeInitialized;
        public final long uid;
        public final long currentKid;
        public final long registerTimeSeconds;
        public final int stoveLevel;
        public final int office;
        public final long allianceId;
        public final boolean supremacyWarActive;
        public final boolean migrantFirstLoginNotified;
        public final long serverNowMillis;
        public final boolean roleClockReady;
        public final int utcOffsetSeconds;
        public final long activityId;
        public final long activityStartSeconds;
        public final long activityEndSeconds;
        public final long activitySerialNo;
        public final long competitionAreaId;
        public final long standardPoints;
        public final long personalPoints;
        public final long lastMigrantSeconds;
        public final long presidentUid;
        public final long permitCount;
        public final Map<Long, Long> kidOpenTimes;
        public final Map<Long, KingdomInfo> kingdoms;
        public final Map<Long, Integer> roleCountByKingdom;
        public final List<Long> invitationKids;

        public Snapshot(boolean baseInitialized, boolean migrantInitialized, boolean backpackInitialized,
                        boolean allianceInitialized, boolean serverTimeInitialized, long uid,
                        long currentKid, long registerTimeSeconds, int stoveLevel, int office,
                        long allianceId, boolean supremacyWarActive, boolean migrantFirstLoginNotified,
                        long serverNowMillis, boolean roleClockReady, int utcOffsetSeconds,
                        long activityId, long activityStartSeconds, long activityEndSeconds,
                        long activitySerialNo, long competitionAreaId, long standardPoints,
                        long personalPoints, long lastMigrantSeconds, long presidentUid,
                        long permitCount, Map<Long, Long> kidOpenTimes,
                        Map<Long, KingdomInfo> kingdoms, Map<Long, Integer> roleCountByKingdom,
                        List<Long> invitationKids) {
            this.baseInitialized = baseInitialized;
            this.migrantInitialized = migrantInitialized;
            this.backpackInitialized = backpackInitialized;
            this.allianceInitialized = allianceInitialized;
            this.serverTimeInitialized = serverTimeInitialized;
            this.uid = uid;
            this.currentKid = currentKid;
            this.registerTimeSeconds = registerTimeSeconds;
            this.stoveLevel = stoveLevel;
            this.office = office;
            this.allianceId = allianceId;
            this.supremacyWarActive = supremacyWarActive;
            this.migrantFirstLoginNotified = migrantFirstLoginNotified;
            this.serverNowMillis = serverNowMillis;
            this.roleClockReady = roleClockReady;
            this.utcOffsetSeconds = utcOffsetSeconds;
            this.activityId = activityId;
            this.activityStartSeconds = activityStartSeconds;
            this.activityEndSeconds = activityEndSeconds;
            this.activitySerialNo = activitySerialNo;
            this.competitionAreaId = competitionAreaId;
            this.standardPoints = standardPoints;
            this.personalPoints = personalPoints;
            this.lastMigrantSeconds = lastMigrantSeconds;
            this.presidentUid = presidentUid;
            this.permitCount = permitCount;
            this.kidOpenTimes = kidOpenTimes == null
                    ? Collections.<Long, Long>emptyMap() : kidOpenTimes;
            this.kingdoms = kingdoms == null
                    ? Collections.<Long, KingdomInfo>emptyMap() : kingdoms;
            this.roleCountByKingdom = roleCountByKingdom == null
                    ? Collections.<Long, Integer>emptyMap() : roleCountByKingdom;
            this.invitationKids = invitationKids == null
                    ? Collections.<Long>emptyList() : invitationKids;
        }

        /** 登录初始化数据是否完整（原版 complete() 口径）。 */
        public boolean complete() {
            return baseInitialized && migrantInitialized && backpackInitialized
                    && allianceInitialized && serverTimeInitialized;
        }

        public long serverNowSeconds() {
            return serverNowMillis / 1000L;
        }
    }

    /** 目标王国信息（原版 KingdomInfo 移植）。 */
    public static final class KingdomInfo {
        public final long kid;
        public final int state;
        public final long migrantNum;
        public final long normalMigrantNum;
        public final long specialInviteCount;
        public final int powerLimit;
        public final int language;
        public final int flag;
        public final String name;
        public final long president;
        public final long allianceId;
        public final int superCount;
        public final int continuousSuperCount;

        public KingdomInfo(long kid, int state, long migrantNum, long normalMigrantNum,
                           long specialInviteCount, int powerLimit, int language, int flag,
                           String name, long president, long allianceId, int superCount,
                           int continuousSuperCount) {
            this.kid = kid;
            this.state = state;
            this.migrantNum = migrantNum;
            this.normalMigrantNum = normalMigrantNum;
            this.specialInviteCount = specialInviteCount;
            this.powerLimit = powerLimit;
            this.language = language;
            this.flag = flag;
            this.name = name == null ? "" : name;
            this.president = president;
            this.allianceId = allianceId;
            this.superCount = superCount;
            this.continuousSuperCount = continuousSuperCount;
        }

        static KingdomInfo from(Map<String, Object> info) {
            return new KingdomInfo(
                    longOf(info.get("kid"), 0L), intOf(info.get("state")),
                    longOf(info.get("migrant_num"), 0L), longOf(info.get("normal_migrant_num"), 0L),
                    longOf(info.get("special_invite_count"), 0L), intOf(info.get("power_limit")),
                    intOf(info.get("language")), intOf(info.get("flag")), text(info.get("name")),
                    longOf(info.get("president"), 0L), longOf(info.get("aid"), 0L),
                    intOf(info.get("super_count")), intOf(info.get("conti_super_count")));
        }
    }

    /** 行军（兜底解析；字段与资产库 QueueMarch 对齐，够召回/展示用）。 */
    public static final class March {
        public final long id;
        public final long owner;
        public final long type;
        public final long state;
        public final long startTime;
        public final long endTime;
        public final long startX;
        public final long startY;
        public final long endX;
        public final long endY;
        public final long masterMarchId;
        final Map<String, Object> raw;

        March(long id, long owner, long type, long state, long startTime, long endTime,
              long startX, long startY, long endX, long endY, long masterMarchId,
              Map<String, Object> raw) {
            this.id = id;
            this.owner = owner;
            this.type = type;
            this.state = state;
            this.startTime = startTime;
            this.endTime = endTime;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.masterMarchId = masterMarchId;
            this.raw = raw == null ? new LinkedHashMap<String, Object>() : raw;
        }

        static March from(Map<String, Object> raw) {
            return new March(longOf(raw.get("id"), 0L), longOf(raw.get("owner"), 0L),
                    longOf(raw.get("type"), 0L), longOf(raw.get("state"), 0L),
                    longOf(raw.get("starttime"), 0L), longOf(raw.get("endtime"), 0L),
                    longOf(raw.get("start_x"), 0L), longOf(raw.get("start_y"), 0L),
                    longOf(raw.get("end_x"), 0L), longOf(raw.get("end_y"), 0L),
                    longOf(firstNonNull(raw.get("master_march_id"), raw.get("master_id")), 0L), raw);
        }
    }

    // =====================================================================
    // 解析工具（原版 WeakValueUtils/forEachPair 的等价实现）
    // =====================================================================

    static long longOf(Object v, long fallback) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            try {
                return (long) Double.parseDouble(String.valueOf(v).trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    static Long longObject(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int intOf(Object v) {
        return (int) longOf(v, 0L);
    }

    static boolean booleanOf(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return false;
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stringMap(Object v) {
        if (!(v instanceof Map)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<?, ?> asMap(Object v) {
        return v instanceof Map ? (Map<?, ?>) v : Collections.emptyMap();
    }

    /** 列表/Map 两种形状都转成对象列表（原版 objectMaps 等价）。 */
    static List<Map<String, Object>> objectMaps(Object v) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (v instanceof Map) {
            for (Object item : ((Map<?, ?>) v).values()) {
                Map<String, Object> mapped = stringMap(item);
                if (!mapped.isEmpty()) result.add(mapped);
            }
        } else if (v instanceof Iterable) {
            for (Object item : (Iterable<?>) v) {
                Map<String, Object> mapped = stringMap(item);
                if (!mapped.isEmpty()) result.add(mapped);
            }
        }
        return result;
    }

    /** 键值对形状 {k,v} / [[k,v]] / Map 的遍历（kid_set 等）。 */
    static void forEachPair(Object value, PairConsumer consumer) {
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) acceptPair(item, consumer);
            return;
        }
        if (!(value instanceof Map)) return;
        Map<?, ?> map = (Map<?, ?>) value;
        if (isPairMap(map)) {
            acceptPair(map, consumer);
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Map && isPairMap((Map<?, ?>) v)) {
                acceptPair(v, consumer);
            } else {
                consumer.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void acceptPair(Object value, PairConsumer consumer) {
        if (!(value instanceof Map)) return;
        Map<?, ?> map = (Map<?, ?>) value;
        Object key = firstNonNull(map.get("k"), map.get("key"));
        Object itemValue = firstNonNull(map.get("v"), map.get("value"));
        if (key != null && itemValue != null) consumer.accept(key, itemValue);
    }

    private static boolean isPairMap(Map<?, ?> map) {
        boolean hasKey = map.containsKey("k") || map.containsKey("key");
        boolean hasValue = map.containsKey("v") || map.containsKey("value");
        return hasKey && hasValue;
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) return out;
        for (Map.Entry<String, Object> e : source.entrySet()) {
            out.put(e.getKey(), deepCopy(e.getValue()));
        }
        return out;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map) return deepCopyMap(stringMap(value));
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) out.add(deepCopy(item));
            return out;
        }
        return value;
    }

    private static long normalizeEpochMillis(long value) {
        if (value <= 0L) return 0L;
        return value < 10000000000L ? value * 1000L : value;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    @FunctionalInterface
    interface PairConsumer {
        void accept(Object key, Object value);
    }

    /** 测试辅助：绕过推送直接注入王国详情。 */
    synchronized void putKingdomForTest(KingdomInfo info) {
        if (info != null && info.kid > 0L) kingdoms.put(info.kid, info);
    }
}
