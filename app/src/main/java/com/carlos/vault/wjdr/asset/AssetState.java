package com.carlos.vault.wjdr.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产库可变内部状态（单写者：inbound 钩子线程；构建快照时一次性读取）。
 *
 * <p>解析器同包（{@code com.carlos.vault.wjdr.asset}）直接读写字段；外部只能通过
 * {@link CoreAssetLibrary#snapshot()} 读不可变快照。Java 8 兼容，纯 java.util。
 */
final class AssetState {

    final AssetKey key;

    // ---------- META ----------
    long uid;
    long kid;
    /** 城市坐标（notify_world_player_init 的 city_point{x,y}）；未知/未到帧为 0。 */
    long cityX;
    long cityY;
    long fid;
    String nickName = "";
    long level;
    long stoveLv;
    long power;
    long registerTime;
    long allianceId;
    String allianceName = "";
    String allianceAbbr = "";
    long metaAtMs;

    // ---------- CLOCK ----------
    long serverTimeSec;
    long serverTimeRecvAtMs;
    long projectStartTs;
    long openTime;
    long utcOffsetSec;
    long zeroTime;
    long clockAtMs;

    // ---------- CALENDAR ----------
    final Map<Long, List<AssetSnapshot.Schedule>> schedules = new LinkedHashMap<Long, List<AssetSnapshot.Schedule>>();
    long calendarAtMs;

    // ---------- RESOURCE ----------
    final Map<Long, Long> resources = new LinkedHashMap<Long, Long>();
    long resourceAtMs;

    // ---------- RECOVER / STAMINA ----------
    final Map<Long, AssetSnapshot.RecoverItem> recover = new LinkedHashMap<Long, AssetSnapshot.RecoverItem>();
    long recoverAtMs;

    // ---------- BACKPACK ----------
    final Map<Long, Long> backpack = new LinkedHashMap<Long, Long>();
    long backpackAtMs;

    // ---------- QUEUE ----------
    final Map<Long, Long> research = new LinkedHashMap<Long, Long>();     // tid -> lev
    long vipLevel;
    long vipActiveEndtime;
    /** showId → 临时 buff 队列数（buff_map[10505]）。 */
    final Map<Long, Long> buffQueues = new LinkedHashMap<Long, Long>();
    /** showId → 过期时间（秒）；0=永不过期。 */
    final Map<Long, Long> buffExpire = new LinkedHashMap<Long, Long>();
    long extraMarch;
    int pendingDispatch;
    boolean marchLimitReached;
    final Map<Long, AssetSnapshot.QueueMarch> marches = new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
    final Map<Long, AssetSnapshot.QueueMarch> massMasters = new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
    long queueAtMs;
    String queueSource = "none";

    // ---------- INTEL ----------
    long radarLevel;
    long radarExp;
    long powerLevel;
    long nextEventTime;
    final Map<Long, AssetSnapshot.IntelEvent> intelEvents = new LinkedHashMap<Long, AssetSnapshot.IntelEvent>();
    long intelAtMs;

    // ---------- BEHEMOTH ----------
    long monsterMaxLv;
    long lastDailyTime;
    long expertRewardDailyCount;
    final Map<Long, Long> joinDailyCounts = new LinkedHashMap<Long, Long>();
    final Map<Long, Long> masterDailyCounts = new LinkedHashMap<Long, Long>();
    long behemothAtMs;

    // ---------- AUTO JOIN ----------
    long autoJoinEndTime;
    long autoJoinLimit;
    boolean autoJoinJustSoldier;
    boolean autoJoinOffline;
    final Set<Long> autoJoinSelected = new LinkedHashSet<Long>();
    long autoJoinAtMs;

    // ---------- BEAR ----------
    long bearReserveTime;
    long bearMassStart;
    long bearMassEnd;
    long bearCoolEnd;
    boolean bearAutoReserve;
    long bearDonate;
    long bearTotalScore;
    long bearTrapTime;
    long bearTrapExtraAid;
    long bearTrapMonsterId;
    long bearTrapX;
    long bearTrapY;
    boolean bearReserveKnown;
    long bearAtMs;

    // ---------- WAR ----------
    final Map<Long, AssetSnapshot.QueueMarch> allianceMarches = new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
    final Map<Long, AssetSnapshot.QueueMarch> worldMarches = new LinkedHashMap<Long, AssetSnapshot.QueueMarch>();
    final Map<Long, Long> warPowers = new LinkedHashMap<Long, Long>();
    long lastMailFirstType;
    long lastMailSecondType;
    long lastMailAtMs;
    long warAtMs;

    // ---------- GUARDIAN ----------
    final Map<Long, AssetSnapshot.Ally> allies = new LinkedHashMap<Long, AssetSnapshot.Ally>();
    final Map<Long, AssetSnapshot.Garrison> garrisons = new LinkedHashMap<Long, AssetSnapshot.Garrison>();
    final Set<Long> holds = new LinkedHashSet<Long>();
    long guardianAtMs;

    // ---------- 诊断 ----------
    final Map<String, Long> protocolSeenAtMs = new LinkedHashMap<String, Long>();
    final Set<AssetDomain> staleDomains = new LinkedHashSet<AssetDomain>();
    final List<String> parseErrors = new ArrayList<String>();
    long readyAtMs;

    AssetState(AssetKey key) {
        this.key = key;
    }

    long now() {
        return System.currentTimeMillis();
    }

    void noteError(String proto, Throwable t) {
        String msg = proto + ": " + (t == null ? "null" : t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : " " + t.getMessage()));
        parseErrors.add(msg.length() > 180 ? msg.substring(0, 180) : msg);
        while (parseErrors.size() > 16) parseErrors.remove(0);
    }

    /** 服务器当前秒（时钟未到时用本地时间）。 */
    long serverNowSec() {
        if (serverTimeSec <= 0) return System.currentTimeMillis() / 1000L;
        long recv = serverTimeRecvAtMs > 0 ? serverTimeRecvAtMs : System.currentTimeMillis();
        return serverTimeSec + Math.max(0L, System.currentTimeMillis() - recv) / 1000L;
    }

    int techQueueCount() {
        int n = 0;
        for (long tid : QueueCapacity.TECH_IDS) {
            Long lev = research.get(tid);
            if (lev != null && lev.longValue() > 0) n++;
        }
        return n;
    }

    int activeBuffQueues(long serverNowSec) {
        int sum = 0;
        for (Map.Entry<Long, Long> e : buffQueues.entrySet()) {
            long expire = valueOf(buffExpire.get(e.getKey()));
            if (expire > 0 && expire <= serverNowSec) continue;
            sum += (int) Math.max(0L, e.getValue().longValue());
        }
        return sum;
    }

    boolean vip6Active(long serverNowSec) {
        if (vipLevel < QueueCapacity.VIP_QUEUE_LEVEL) return false;
        return vipActiveEndtime <= 0 || vipActiveEndtime > serverNowSec;
    }

    int rawCapacity() {
        long now = serverNowSec();
        return QueueCapacity.rawCapacity(techQueueCount(), vip6Active(now),
                activeBuffQueues(now), extraMarch);
    }

    /** 普通占用：marches 去重 id ∪ massMasters 中 type=99 的 id。 */
    int occupied() {
        Set<Long> ids = new HashSet<Long>();
        for (AssetSnapshot.QueueMarch m : marches.values()) {
            if (m != null && m.id > 0 && QueueCapacity.occupiesNormalQueue(m.type)) ids.add(m.id);
        }
        for (AssetSnapshot.QueueMarch m : massMasters.values()) {
            if (m != null && m.id > 0 && m.type == 99L) ids.add(m.id);
        }
        return ids.size();
    }

    int kingdomDay() {
        if (projectStartTs <= 0 || openTime <= 0 || serverTimeSec <= 0) return 0;
        long alignedOpen = projectStartTs + Math.floorDiv(openTime - projectStartTs, 1209600L) * 1209600L;
        long elapsedDays = Math.floorDiv(serverNowSec() - alignedOpen, 86400L);
        if (elapsedDays < 0 || elapsedDays >= Integer.MAX_VALUE) return 0;
        return (int) elapsedDays + 1;
    }

    int maxResourceLevel() {
        return kingdomDay() >= 701 ? 9 : 8;
    }

    // ================= 快照构建 =================

    AssetSnapshot build(long version, boolean ready, java.util.Set<String> missingProtocols) {
        long nowMs = System.currentTimeMillis();
        AssetSnapshot.Meta meta = new AssetSnapshot.Meta(uid, kid, cityX, cityY, fid, nickName,
                level, stoveLv, power, registerTime, allianceId, allianceName, allianceAbbr,
                metaAtMs);
        int day = kingdomDay();
        AssetSnapshot.Clock clock = new AssetSnapshot.Clock(serverTimeSec, serverTimeRecvAtMs,
                projectStartTs, openTime, utcOffsetSec, zeroTime, day,
                day >= 701 ? 9 : 8);

        Map<Long, List<AssetSnapshot.Schedule>> cal = new LinkedHashMap<Long, List<AssetSnapshot.Schedule>>();
        for (Map.Entry<Long, List<AssetSnapshot.Schedule>> e : schedules.entrySet()) {
            cal.put(e.getKey(), new ArrayList<AssetSnapshot.Schedule>(e.getValue()));
        }
        AssetSnapshot.Calendar calendar = new AssetSnapshot.Calendar(cal);

        AssetSnapshot.Resources res = new AssetSnapshot.Resources(resources, resourceAtMs);
        AssetSnapshot.Recover rec = new AssetSnapshot.Recover(recover, recoverAtMs);
        AssetSnapshot.RecoverItem st = recover.get(201L);
        AssetSnapshot.Stamina stamina = st == null ? AssetSnapshot.Stamina.empty()
                : new AssetSnapshot.Stamina(st.count, st.maxCount, st.recoverPer,
                st.nextCalcTs, st.intervalSec);
        AssetSnapshot.Backpack bp = new AssetSnapshot.Backpack(backpack, backpackAtMs);

        int raw = rawCapacity();
        int cap = QueueCapacity.clampCapacity(raw);
        int occ = occupied();
        int idle = QueueCapacity.idle(cap, occ, pendingDispatch, marchLimitReached);
        List<AssetSnapshot.QueueMarch> marchList = new ArrayList<AssetSnapshot.QueueMarch>(marches.values());
        AssetSnapshot.Queue queue = new AssetSnapshot.Queue(raw, cap, occ, idle, pendingDispatch,
                marchLimitReached, QueueCapacity.countSpecialByType(marchList),
                QueueCapacity.countNormalByType(marchList), marchList, queueAtMs, queueSource);

        AssetSnapshot.Intel intel = new AssetSnapshot.Intel(radarLevel, radarExp, powerLevel,
                nextEventTime, intelEvents, intelAtMs);
        AssetSnapshot.Behemoth behemoth = new AssetSnapshot.Behemoth(monsterMaxLv, lastDailyTime,
                joinDailyCounts, masterDailyCounts, expertRewardDailyCount, behemothAtMs);
        AssetSnapshot.AutoJoin autoJoin = new AssetSnapshot.AutoJoin(autoJoinEndTime, autoJoinLimit,
                autoJoinJustSoldier, autoJoinOffline, autoJoinSelected, autoJoinAtMs);
        AssetSnapshot.Bear bear = new AssetSnapshot.Bear(bearReserveTime, bearMassStart, bearMassEnd,
                bearCoolEnd, bearAutoReserve, bearDonate, bearTotalScore, bearTrapTime,
                bearTrapExtraAid, bearTrapMonsterId, bearTrapX, bearTrapY, bearReserveKnown,
                bearAtMs);
        AssetSnapshot.War war = new AssetSnapshot.War(allianceMarches, worldMarches, warPowers,
                lastMailFirstType, lastMailSecondType, lastMailAtMs, warAtMs);
        AssetSnapshot.Guardian guardian = new AssetSnapshot.Guardian(allies, garrisons, holds,
                guardianAtMs);

        return new AssetSnapshot(version, nowMs, readyAtMs, ready, key, meta, clock, calendar, res,
                rec, stamina, bp, queue, intel, behemoth, autoJoin, bear, war, guardian,
                missingProtocols == null ? Collections.<String>emptySet()
                        : Collections.unmodifiableSet(new LinkedHashSet<String>(missingProtocols)),
                Collections.unmodifiableSet(new LinkedHashSet<AssetDomain>(staleDomains)),
                Collections.unmodifiableMap(new LinkedHashMap<String, Long>(protocolSeenAtMs)));
    }

    // ================= 形状兼容工具（供解析器使用） =================

    static long valueOf(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1L : 0L;
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return 0L;
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    static boolean boolOf(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v != null) {
            String s = String.valueOf(v).trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return false;
    }

    static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** List/Map(值)/Collection → Map 列表（map 形 key "201.0" 自动转值列表）。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapsOf(Object v) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) out.add(mapOf(o));
            }
        } else if (v instanceof Map) {
            for (Object o : ((Map<?, ?>) v).values()) {
                if (o instanceof Map) out.add(mapOf(o));
            }
        } else if (v instanceof java.util.Collection) {
            for (Object o : (java.util.Collection<?>) v) {
                if (o instanceof Map) out.add(mapOf(o));
            }
        }
        return out;
    }

    /** 宽松列表：List/Collection/Map(值) → Object 列表。 */
    static List<Object> listOf(Object v) {
        if (v instanceof List) return new ArrayList<Object>((List<?>) v);
        if (v instanceof Map) return new ArrayList<Object>(((Map<?, ?>) v).values());
        if (v instanceof java.util.Collection) return new ArrayList<Object>((java.util.Collection<?>) v);
        return new ArrayList<Object>();
    }

    /** 去包装：content 直接是字段容器；若存在 data/info/content 且为 Map 则取内层（兼容 notifyData 口径）。 */
    static Map<String, Object> unwrap(Map<String, Object> content) {
        if (content == null) return new LinkedHashMap<String, Object>();
        for (String k : new String[]{"data", "info", "content"}) {
            Object v = content.get(k);
            if (v instanceof Map && ((Map<?, ?>) v).size() > 0
                    && content.size() <= 2) {
                return mapOf(v);
            }
        }
        return content;
    }

    /** 按关键字取第一个非空数字字段。 */
    static long firstNum(Map<String, Object> m, long def, String... keys) {
        if (m == null) return def;
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String && !((String) v).trim().isEmpty()) {
                long n = valueOf(v);
                if (n != 0L) return n;
            }
        }
        return def;
    }

    /** 按关键字取字符串字段。 */
    static String firstStr(Map<String, Object> m, String... keys) {
        if (m == null) return "";
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v);
        }
        return "";
    }

    /** 嵌套 Map 取字段（如 data.player.trap_time）。 */
    static Map<String, Object> child(Map<String, Object> m, String key) {
        if (m == null) return new LinkedHashMap<String, Object>();
        return mapOf(m.get(key));
    }

    /** 数字对总表：支持 Map（"k.0" → 值）与 List（元素自身 id/count）。 */
    static Map<Long, Long> longMap(Object raw) {
        Map<Long, Long> out = new LinkedHashMap<Long, Long>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                long k = valueOf(e.getKey());
                long v = valueOf(e.getValue());
                if (k > 0) out.put(k, v);
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = mapOf(o);
                long id = firstNum(m, 0, "id", "k");
                long count = firstNum(m, 0, "count", "v");
                if (id > 0) out.put(id, count);
            }
        }
        return out;
    }

    /** 只保留诊断用：清空对某域的 stale 标记。 */
    void markFresh(AssetDomain domain) {
        staleDomains.remove(domain);
    }

    void markStale(AssetDomain domain) {
        staleDomains.add(domain);
    }

    /** 集合是否发生变化（供解析器 change detect 使用）。 */
    static boolean changedLong(Map<Long, Long> before, Map<Long, Long> after) {
        if (before.size() != after.size()) return true;
        for (Map.Entry<Long, Long> e : after.entrySet()) {
            Long old = before.get(e.getKey());
            if (old == null || old.longValue() != e.getValue().longValue()) return true;
        }
        return false;
    }

    static void copyInto(Map<Long, Long> dst, Map<Long, Long> src) {
        dst.clear();
        dst.putAll(src);
    }

    static void addParseError(AssetState st, String proto, Throwable t) {
        st.noteError(proto, t);
    }

    Iterator<Map.Entry<Long, AssetSnapshot.QueueMarch>> marchesIterator() {
        return marches.entrySet().iterator();
    }

    static <T> List<T> copyList(List<T> src) {
        return src == null ? new ArrayList<T>() : new ArrayList<T>(src);
    }

    static Map<Long, Integer> emptyCountMap() {
        return Collections.emptyMap();
    }

    static Map<Long, Long> emptyLongMap() {
        return Collections.emptyMap();
    }

    static Map<Long, Object> newMap() {
        return new HashMap<Long, Object>();
    }
}
