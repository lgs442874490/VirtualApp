package com.carlos.vault.wjdr.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 核心资产库不可变快照（读者无锁 O(1) 读取）。
 *
 * <p>设计口径（见 design/research-20261024/核心资产库协议与架构盘点.md）：
 * <ul>
 *   <li>App（Java 8 源级）与 vault-server（Java 17）同源编译，只用 java.util，禁 Android/Spring；</li>
 *   <li>所有集合在构造时冻结（不可变包装），快照整体替换（volatile），域对象只重建变化域；</li>
 *   <li>字段为空/未知用 0、""、空集合表示，不抛异常；诊断信息见 {@link #missingProtocols}、
 *       {@link #staleDomains}、{@link #protocolSeenAtMs}。</li>
 * </ul>
 *
 * <p>注意：Java 8 兼容（App sourceCompatibility=1.8），因此不用 record / List.of / Map.of。
 */
public final class AssetSnapshot {

    public final long version;
    public final long updatedAtMs;
    public final long readyAtMs;
    public final boolean ready;
    public final AssetKey key;

    public final Meta meta;
    public final Clock clock;
    public final Calendar calendar;
    public final Resources resources;
    public final Recover recover;
    public final Stamina stamina;
    public final Backpack backpack;
    public final Queue queue;
    public final Intel intel;
    public final Behemoth behemoth;
    public final AutoJoin autoJoin;
    public final Bear bear;
    public final War war;
    public final Guardian guardian;

    public final Set<String> missingProtocols;
    public final Set<AssetDomain> staleDomains;
    public final Map<String, Long> protocolSeenAtMs;

    public AssetSnapshot(long version, long updatedAtMs, long readyAtMs, boolean ready, AssetKey key,
                         Meta meta, Clock clock, Calendar calendar, Resources resources,
                         Recover recover, Stamina stamina, Backpack backpack, Queue queue,
                         Intel intel, Behemoth behemoth, AutoJoin autoJoin, Bear bear,
                         War war, Guardian guardian, Set<String> missingProtocols,
                         Set<AssetDomain> staleDomains, Map<String, Long> protocolSeenAtMs) {
        this.version = version;
        this.updatedAtMs = updatedAtMs;
        this.readyAtMs = readyAtMs;
        this.ready = ready;
        this.key = key;
        this.meta = meta;
        this.clock = clock;
        this.calendar = calendar;
        this.resources = resources;
        this.recover = recover;
        this.stamina = stamina;
        this.backpack = backpack;
        this.queue = queue;
        this.intel = intel;
        this.behemoth = behemoth;
        this.autoJoin = autoJoin;
        this.bear = bear;
        this.war = war;
        this.guardian = guardian;
        this.missingProtocols = missingProtocols;
        this.staleDomains = staleDomains;
        this.protocolSeenAtMs = protocolSeenAtMs;
    }

    /** 空快照（attach 后、首帧前）。 */
    public static AssetSnapshot empty(AssetKey key) {
        return new AssetSnapshot(0L, 0L, 0L, false, key, Meta.empty(), Clock.empty(),
                Calendar.empty(), Resources.empty(), Recover.empty(), Stamina.empty(),
                Backpack.empty(), Queue.empty(), Intel.empty(), Behemoth.empty(),
                AutoJoin.empty(), Bear.empty(), War.empty(), Guardian.empty(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());
    }

    public boolean hasDomain(AssetDomain d) {
        return !staleDomains.contains(d);
    }

    // ======================= 域模型 =======================

    /** 角色元数据（notify_base_info / notify_alliance_init / notify_player_power_update 等）。 */
    public static final class Meta {
        public final long uid;
        public final long kid;
        /** 承载城市坐标（notify_world_player_init 的 city_point{x,y}）；未知/未到帧为 0。 */
        public final long cityX;
        public final long cityY;
        public final long fid;
        public final String nickName;
        public final long level;
        public final long stoveLv;
        public final long power;
        public final long registerTime;
        public final long allianceId;
        public final String allianceName;
        public final String allianceAbbr;
        public final long updatedAtMs;

        public Meta(long uid, long kid, long cityX, long cityY, long fid, String nickName,
                    long level, long stoveLv, long power, long registerTime, long allianceId,
                    String allianceName, String allianceAbbr, long updatedAtMs) {
            this.uid = uid;
            this.kid = kid;
            this.cityX = cityX;
            this.cityY = cityY;
            this.fid = fid;
            this.nickName = nickName == null ? "" : nickName;
            this.level = level;
            this.stoveLv = stoveLv;
            this.power = power;
            this.registerTime = registerTime;
            this.allianceId = allianceId;
            this.allianceName = allianceName == null ? "" : allianceName;
            this.allianceAbbr = allianceAbbr == null ? "" : allianceAbbr;
            this.updatedAtMs = updatedAtMs;
        }

        public static Meta empty() {
            return new Meta(0, 0, 0, 0, 0, "", 0, 0, 0, 0, 0, "", "", 0);
        }
    }

    /** 服务器时钟/王国进度（notify_game_init / notify_server_time / notify_schedule_update）。 */
    public static final class Clock {
        public final long serverTimeSec;
        public final long serverTimeRecvAtMs;
        public final long projectStartTs;
        public final long openTime;
        public final long utcOffsetSec;
        public final long zeroTime;
        public final int kingdomDay;          // 开服第 N 天（1 起；0=未知）
        public final int maxResourceLevel;    // 当前王国可采资源矿最高等级（8 或 9；0=未知）

        public Clock(long serverTimeSec, long serverTimeRecvAtMs, long projectStartTs, long openTime,
                     long utcOffsetSec, long zeroTime, int kingdomDay, int maxResourceLevel) {
            this.serverTimeSec = serverTimeSec;
            this.serverTimeRecvAtMs = serverTimeRecvAtMs;
            this.projectStartTs = projectStartTs;
            this.openTime = openTime;
            this.utcOffsetSec = utcOffsetSec;
            this.zeroTime = zeroTime;
            this.kingdomDay = kingdomDay;
            this.maxResourceLevel = maxResourceLevel;
        }

        public static Clock empty() {
            return new Clock(0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** 服务器当前秒（本地单调外推）。 */
        public long serverNowSec() {
            long now = System.currentTimeMillis();
            if (serverTimeSec <= 0) return now / 1000L;
            long recv = serverTimeRecvAtMs > 0 ? serverTimeRecvAtMs : now;
            long delta = Math.max(0L, now - recv) / 1000L;
            return serverTimeSec + delta;
        }

        public boolean known() {
            return serverTimeSec > 0;
        }
    }

    /** 活动日历（notify_activity_calendar_data）。 */
    public static final class Calendar {
        public final Map<Long, List<Schedule>> schedules;   // activityId → 时间段

        public Calendar(Map<Long, List<Schedule>> schedules) {
            this.schedules = freeze(schedules);
        }

        public static Calendar empty() {
            return new Calendar(Collections.<Long, List<Schedule>>emptyMap());
        }

        public boolean activeAt(long activityId, long serverNowSec) {
            List<Schedule> list = schedules.get(activityId);
            if (list == null) return false;
            for (Schedule s : list) {
                if (s.beginTs <= serverNowSec && (s.endTs <= 0 || serverNowSec < s.endTs)) return true;
            }
            return false;
        }
    }

    /** 活动时间段。 */
    public static final class Schedule {
        public final long beginTs;
        public final long endTs;
        public final long groupId;

        public Schedule(long beginTs, long endTs, long groupId) {
            this.beginTs = beginTs;
            this.endTs = endTs;
            this.groupId = groupId;
        }
    }

    /** 资源（notify_resource_init/update；id：101 钻石/102 肉/103 木/104 煤/105 铁/106 钢/110 联盟币）。 */
    public static final class Resources {
        public final Map<Long, Long> items;
        public final long updatedAtMs;

        public Resources(Map<Long, Long> items, long updatedAtMs) {
            this.items = freeze(items);
            this.updatedAtMs = updatedAtMs;
        }

        public static Resources empty() {
            return new Resources(Collections.<Long, Long>emptyMap(), 0);
        }

        public long get(long id) {
            Long v = items.get(id);
            return v == null ? 0L : v.longValue();
        }

        public long diamond() { return get(101L); }
        public long meat() { return get(102L); }
        public long wood() { return get(103L); }
        public long coal() { return get(104L); }
        public long iron() { return get(105L); }
        public long steel() { return get(106L); }
        public long allianceCoin() { return get(110L); }
    }

    /** 可恢复资源项（notify_recover_resource_init/update）：201 体力/203 免费盾/205 捐献次数…。 */
    public static final class RecoverItem {
        public final long id;
        public final long count;
        public final long maxCount;
        public final long recoverPer;
        public final long nextCalcTs;
        public final long intervalSec;

        public RecoverItem(long id, long count, long maxCount, long recoverPer, long nextCalcTs,
                           long intervalSec) {
            this.id = id;
            this.count = count;
            this.maxCount = maxCount;
            this.recoverPer = recoverPer;
            this.nextCalcTs = nextCalcTs;
            this.intervalSec = intervalSec;
        }
    }

    /** 可恢复资源集合（含体力 id=201）。 */
    public static final class Recover {
        public final Map<Long, RecoverItem> items;
        public final long updatedAtMs;

        public Recover(Map<Long, RecoverItem> items, long updatedAtMs) {
            this.items = freeze(items);
            this.updatedAtMs = updatedAtMs;
        }

        public static Recover empty() {
            return new Recover(Collections.<Long, RecoverItem>emptyMap(), 0);
        }

        public RecoverItem get(long id) {
            return items.get(id);
        }
    }

    /** 体力派生视图（唯一权威：recover id=201；count 可超 max，按网页版不 clamp）。 */
    public static final class Stamina {
        public final long current;
        public final long max;
        public final long recoverPer;
        public final long nextCalcTs;
        public final long intervalSec;

        public Stamina(long current, long max, long recoverPer, long nextCalcTs, long intervalSec) {
            this.current = current;
            this.max = max;
            this.recoverPer = recoverPer;
            this.nextCalcTs = nextCalcTs;
            this.intervalSec = intervalSec;
        }

        public static Stamina empty() {
            return new Stamina(0, 0, 0, 0, 0);
        }

        public boolean known() {
            return max > 0 || current > 0;
        }

        /** 按服务器秒重算当前体力：从 next_calc_ts 起点、interval 步进、recover 步长。 */
        public long staminaAt(long serverNowSec) {
            if (nextCalcTs <= 0 || intervalSec <= 0 || recoverPer <= 0) return current;
            if (serverNowSec < nextCalcTs) return current;
            long steps = 1 + (serverNowSec - nextCalcTs) / intervalSec;
            return current + steps * recoverPer;
        }
    }

    /** 背包道具（notify_backpack_init/update）：itemId → count。 */
    public static final class Backpack {
        public final Map<Long, Long> items;
        public final long updatedAtMs;

        public Backpack(Map<Long, Long> items, long updatedAtMs) {
            this.items = freeze(items);
            this.updatedAtMs = updatedAtMs;
        }

        public static Backpack empty() {
            return new Backpack(Collections.<Long, Long>emptyMap(), 0);
        }

        public long get(long itemId) {
            Long v = items.get(itemId);
            return v == null ? 0L : v.longValue();
        }
    }

    /** 一条行军队列（own / alliance / world 共用结构）。 */
    public static final class QueueMarch {
        public final long id;
        public final long owner;
        public final long type;
        public final long state;
        public final long startTime;
        public final long endTime;
        public final long targetType;
        public final long targetId;
        public final long aid;
        public final long startX;
        public final long startY;
        public final long endX;
        public final long endY;
        public final long masterMarchId;
        public final long refreshId;     // collect_resource.refresh_id（采集资源点）
        /** 编队英雄 id（squad_list.hero_id 的 v 值；供自动编队排除在途英雄，防 403）。 */
        public final List<Long> heroIds;

        public QueueMarch(long id, long owner, long type, long state, long startTime, long endTime,
                          long targetType, long targetId, long aid, long startX, long startY,
                          long endX, long endY, long masterMarchId, long refreshId) {
            this(id, owner, type, state, startTime, endTime, targetType, targetId, aid,
                    startX, startY, endX, endY, masterMarchId, refreshId,
                    java.util.Collections.<Long>emptyList());
        }

        public QueueMarch(long id, long owner, long type, long state, long startTime, long endTime,
                          long targetType, long targetId, long aid, long startX, long startY,
                          long endX, long endY, long masterMarchId, long refreshId,
                          List<Long> heroIds) {
            this.id = id;
            this.owner = owner;
            this.type = type;
            this.state = state;
            this.startTime = startTime;
            this.endTime = endTime;
            this.targetType = targetType;
            this.targetId = targetId;
            this.aid = aid;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.masterMarchId = masterMarchId;
            this.refreshId = refreshId;
            this.heroIds = heroIds == null ? java.util.Collections.<Long>emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<Long>(heroIds));
        }

        public boolean specialQueue() {
            return QueueCapacity.isSpecialQueue(type);
        }
    }

    /** 行军队列聚合（容量/占用/空闲/特殊队列/在途行军）。 */
    public static final class Queue {
        public final int rawCapacity;      // 公式原值（未 clamp）
        public final int capacity;         // 只读层 clamp(1..6)
        public final int occupied;         // 普通占用（排除特殊队列；含 type=99 上车）
        public final int idle;
        public final int pending;
        public final boolean limitReached;
        public final Map<Long, Integer> specialByType;   // 特殊队列 type→条数（不占普通队列）
        public final Map<Long, Integer> byType;          // 普通队列 type→条数
        public final List<QueueMarch> marches;           // 自己全部在途行军（含特殊）
        public final long updatedAtMs;
        public final String source;                      // 诊断：init/push/response

        public Queue(int rawCapacity, int capacity, int occupied, int idle, int pending,
                     boolean limitReached, Map<Long, Integer> specialByType,
                     Map<Long, Integer> byType, List<QueueMarch> marches, long updatedAtMs,
                     String source) {
            this.rawCapacity = rawCapacity;
            this.capacity = capacity;
            this.occupied = occupied;
            this.idle = idle;
            this.pending = pending;
            this.limitReached = limitReached;
            this.specialByType = freeze(specialByType);
            this.byType = freeze(byType);
            this.marches = Collections.unmodifiableList(new ArrayList<QueueMarch>(marches));
            this.updatedAtMs = updatedAtMs;
            this.source = source == null ? "" : source;
        }

        public static Queue empty() {
            return new Queue(0, 0, 0, 0, 0, false,
                    Collections.<Long, Integer>emptyMap(), Collections.<Long, Integer>emptyMap(),
                    new ArrayList<QueueMarch>(), 0, "none");
        }

        public boolean known() {
            return rawCapacity > 0;
        }
    }

    /** 情报事件。 */
    public static final class IntelEvent {
        public final long id;
        public final long eventId;       // event_config_id（用于类型映射）
        public final long status;        // 1 可派 / 2 可领 / 3 战斗中
        public final long x;
        public final long y;
        public final long expireTs;
        public final boolean played;

        public IntelEvent(long id, long eventId, long status, long x, long y, long expireTs,
                          boolean played) {
            this.id = id;
            this.eventId = eventId;
            this.status = status;
            this.x = x;
            this.y = y;
            this.expireTs = expireTs;
            this.played = played;
        }
    }

    /** 情报（notify_intelligence_info 全量 + events 增量/del + battle_result/power_info）。 */
    public static final class Intel {
        public final long radarLevel;
        public final long radarExp;
        public final long powerLevel;
        public final long nextEventTime;
        public final Map<Long, IntelEvent> events;
        public final long updatedAtMs;

        public Intel(long radarLevel, long radarExp, long powerLevel, long nextEventTime,
                     Map<Long, IntelEvent> events, long updatedAtMs) {
            this.radarLevel = radarLevel;
            this.radarExp = radarExp;
            this.powerLevel = powerLevel;
            this.nextEventTime = nextEventTime;
            this.events = freeze(events);
            this.updatedAtMs = updatedAtMs;
        }

        public static Intel empty() {
            return new Intel(0, 0, 0, 0, Collections.<Long, IntelEvent>emptyMap(), 0);
        }

        /** 可派事件（status=1 且未过期；业务仍自己判编队/体力）。 */
        public List<IntelEvent> dispatchable(long serverNowSec) {
            List<IntelEvent> out = new ArrayList<IntelEvent>();
            for (IntelEvent e : events.values()) {
                if (e.status == 1L && (e.expireTs <= 0 || e.expireTs > serverNowSec)) out.add(e);
            }
            return out;
        }
    }

    /** 巨兽每日次数/怪物等级（notify_world_npc_init/update）。 */
    public static final class Behemoth {
        public final long monsterMaxLv;
        public final long lastDailyTime;
        public final Map<Long, Long> joinDailyCounts;
        public final Map<Long, Long> masterDailyCounts;
        public final long expertRewardDailyCount;
        public final long updatedAtMs;

        public Behemoth(long monsterMaxLv, long lastDailyTime, Map<Long, Long> joinDailyCounts,
                        Map<Long, Long> masterDailyCounts, long expertRewardDailyCount,
                        long updatedAtMs) {
            this.monsterMaxLv = monsterMaxLv;
            this.lastDailyTime = lastDailyTime;
            this.joinDailyCounts = freeze(joinDailyCounts);
            this.masterDailyCounts = freeze(masterDailyCounts);
            this.expertRewardDailyCount = expertRewardDailyCount;
            this.updatedAtMs = updatedAtMs;
        }

        public static Behemoth empty() {
            return new Behemoth(0, 0, Collections.<Long, Long>emptyMap(),
                    Collections.<Long, Long>emptyMap(), 0, 0);
        }
    }

    /** 原生自动上车状态（req_alliance_activity_auto_join_info 响应）。 */
    public static final class AutoJoin {
        public final long endTime;
        public final long limit;
        public final boolean justSoldier;
        public final boolean offline;
        public final Set<Long> selectedIds;
        public final long updatedAtMs;

        public AutoJoin(long endTime, long limit, boolean justSoldier, boolean offline,
                        Set<Long> selectedIds, long updatedAtMs) {
            this.endTime = endTime;
            this.limit = limit;
            this.justSoldier = justSoldier;
            this.offline = offline;
            this.selectedIds = selectedIds == null ? Collections.<Long>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<Long>(selectedIds));
            this.updatedAtMs = updatedAtMs;
        }

        public static AutoJoin empty() {
            return new AutoJoin(0, 0, false, false, Collections.<Long>emptySet(), 0);
        }
    }

    /** 巨熊/联盟陷阱状态（notify_alliance_trap_update + req_alliance_trap_info 权威）。 */
    public static final class Bear {
        public final long reserveTime;
        public final long massStartTime;
        public final long massEndTime;
        public final long coolEndTime;
        public final boolean autoReserve;
        public final long donate;
        public final long totalScore;
        public final long trapTime;        // 上次参与时间（notify_alliance_init.player.trap_time）
        public final long trapExtraAid;
        public final long trapMonsterId;
        public final long trapX;
        public final long trapY;
        public final boolean reserveKnown;
        public final long updatedAtMs;

        public Bear(long reserveTime, long massStartTime, long massEndTime, long coolEndTime,
                    boolean autoReserve, long donate, long totalScore, long trapTime,
                    long trapExtraAid, long trapMonsterId, long trapX, long trapY,
                    boolean reserveKnown, long updatedAtMs) {
            this.reserveTime = reserveTime;
            this.massStartTime = massStartTime;
            this.massEndTime = massEndTime;
            this.coolEndTime = coolEndTime;
            this.autoReserve = autoReserve;
            this.donate = donate;
            this.totalScore = totalScore;
            this.trapTime = trapTime;
            this.trapExtraAid = trapExtraAid;
            this.trapMonsterId = trapMonsterId;
            this.trapX = trapX;
            this.trapY = trapY;
            this.reserveKnown = reserveKnown;
            this.updatedAtMs = updatedAtMs;
        }

        public static Bear empty() {
            return new Bear(0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, false, 0);
        }

        public boolean hasTrapTarget() {
            return trapMonsterId > 0 && trapX > 0 && trapY > 0;
        }
    }

    /** 战争事实（只做事实表，判定仍归 WarState/服务器 Guardian 引擎）。 */
    public static final class War {
        /** 联盟域态势行军（盟友/敌方，含 power）。 */
        public final Map<Long, QueueMarch> allianceMarches;
        /** 世界域态势行军（视野内）。 */
        public final Map<Long, QueueMarch> worldMarches;
        /** 每名行军发起者的战力（key=owner uid）。 */
        public final Map<Long, Long> powers;
        public final long lastMailFirstType;
        public final long lastMailSecondType;
        public final long lastMailAtMs;
        public final long updatedAtMs;

        public War(Map<Long, QueueMarch> allianceMarches, Map<Long, QueueMarch> worldMarches,
                   Map<Long, Long> powers, long lastMailFirstType, long lastMailSecondType,
                   long lastMailAtMs, long updatedAtMs) {
            this.allianceMarches = freeze(allianceMarches);
            this.worldMarches = freeze(worldMarches);
            this.powers = freeze(powers);
            this.lastMailFirstType = lastMailFirstType;
            this.lastMailSecondType = lastMailSecondType;
            this.lastMailAtMs = lastMailAtMs;
            this.updatedAtMs = updatedAtMs;
        }

        public static War empty() {
            return new War(Collections.<Long, QueueMarch>emptyMap(),
                    Collections.<Long, QueueMarch>emptyMap(), Collections.<Long, Long>emptyMap(),
                    0, 0, 0, 0);
        }
    }

    /** 盟友坐标（notify_alliance_member_pos_*）。 */
    public static final class Ally {
        public final long uid;
        public final long x;
        public final long y;
        public final long kid;
        public final boolean leader;
        public final long updatedAtMs;

        public Ally(long uid, long x, long y, long kid, boolean leader, long updatedAtMs) {
            this.uid = uid;
            this.x = x;
            this.y = y;
            this.kid = kid;
            this.leader = leader;
            this.updatedAtMs = updatedAtMs;
        }
    }

    /** 驻防队伍（使馆驻防 notify_embassy_world_march_add/del）。 */
    public static final class Garrison {
        public final long marchId;
        public final long owner;
        public final long targetUid;
        public final long state;
        public final long endTime;
        public final long troopLimit;

        public Garrison(long marchId, long owner, long targetUid, long state, long endTime,
                        long troopLimit) {
            this.marchId = marchId;
            this.owner = owner;
            this.targetUid = targetUid;
            this.state = state;
            this.endTime = endTime;
            this.troopLimit = troopLimit;
        }
    }

    /** 驻防队友事实（盟友坐标 + 我方驻防队伍）。 */
    public static final class Guardian {
        public final Map<Long, Ally> allies;
        public final Map<Long, Garrison> garrisons;
        public final Set<Long> holds;      // 由判定机写入的常驻戒备目标（可选）
        public final long updatedAtMs;

        public Guardian(Map<Long, Ally> allies, Map<Long, Garrison> garrisons, Set<Long> holds,
                        long updatedAtMs) {
            this.allies = freeze(allies);
            this.garrisons = freeze(garrisons);
            this.holds = holds == null ? Collections.<Long>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<Long>(holds));
            this.updatedAtMs = updatedAtMs;
        }

        public static Guardian empty() {
            return new Guardian(Collections.<Long, Ally>emptyMap(),
                    Collections.<Long, Garrison>emptyMap(), Collections.<Long>emptySet(), 0);
        }
    }

    // ======================= 工具 =======================

    static <K, V> Map<K, V> freeze(Map<K, V> in) {
        if (in == null || in.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(in));
    }
}
