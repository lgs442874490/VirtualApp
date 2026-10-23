package com.carlos.vaultserver.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知历史环形缓存（R2，批次3 2026-10-23）：按账号（fpid）保存 allowlist 协议最近若干条推送，
 * 供任务线程提取“同一协议多实例活动”（曙光市集 7 档、自选箱多组、卡 BP 多卡、多封邮件/宝箱 add）。
 *
 * <p><b>为什么需要</b>：引擎 {@code WjdrEngine.hookNotifies()} 对每个 proto 只做
 * {@code notifies.put(proto, content)}（app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:686-688），
 * 同名协议后到覆盖先到——而网页版 {@code TaskEngine} 把<b>每一条</b> inbound notify 都变成一次信号
 * （wjdr-review/src-tool/com/wjdr/tool/modules/daily/engine/TaskEngine.java:446-483），
 * 活动奖励状态机也是按“活动 id”逐个维护、逐个 claimOne
 * （service/DailyActivityRewardsState.java:95-225、service/DailyActivityRewardsService.java:398-411）。
 * 我方只有最后一帧时，多档活动每轮只能覆盖最后到达项（qg/audit-20261023/reward-gaps.md R2）。
 *
 * <p><b>内存红线</b>：生产 JVM {@code -Xmx256m}（2026-10-23 只读复核
 * {@code ps -eo args | grep vault-server} = java -Xmx256m -jar vault-server-all.jar），因此本类：
 * <ul>
 *   <li>只收 allowlist 协议（活动/宝箱/情报/邮件/行军/资源恢复/活动日历，见 {@link #ALLOWLIST}），
 *       其它高频协议（心跳/战斗帧/聊天等）一律不记；</li>
 *   <li>每账号每 proto 最多 {@link #MAX_PER_PROTO}=8 条（超限淘汰最旧），总条数硬上限
 *       {@link #MAX_TOTAL_ENTRIES}=2000，总字符预算 {@link #MAX_TOTAL_CHARS}=4MB，超限全局淘汰最旧；</li>
 *   <li>账号数上限 {@link #MAX_FPIDS}=16（生产 3 个账号，只读复核 state.json，仅作兜底）；</li>
 *   <li>只存<b>不可变深拷贝</b>（Map/List 全量复制 + 不可变包装，单条超
 *       {@link #MAX_ENTRY_CHARS} 字符直接拒绝），引擎/网络层后续改原 Map 不会污染历史；</li>
 *   <li>纯内存、不落盘、不接触引擎状态机；会话下线不清理（任务线程仍需最近一次推送），
 *       由容量淘汰自然收敛——「不清空任务需要的旧数据」。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：inbound 线程（每会话一条）写，任务线程读；全部静态方法在同一把锁内完成，
 * 读路径返回不可变快照。单次 record 是 O(每 proto 8 条) 的内存比较，开销可忽略。
 *
 * <p><b>与单条路径的关系</b>：调用方（{@link ServerTasks#activityRewards} 等）必须保留
 * {@code engine.notifyContent(proto)} 单条兜底：历史因容量淘汰/钩子晚挂/内容超限未收录时，
 * 旧路径仍能拿到最新一帧。
 */
public final class ServerNotifyHistory {

    /** 每账号每 proto 保留条数（需求口径：默认 8；同名协议多次推送时旧帧淘汰）。 */
    public static final int MAX_PER_PROTO = 8;

    /** 全局总条数硬上限（-Xmx256m 内存红线；生产 3 账号理论峰值 3×28×8=672，2000 仅作兜底）。 */
    public static final int MAX_TOTAL_ENTRIES = 2000;

    /** 全局估算字符预算（键名+字符串值长度之和；4MB 约为 -Xmx256m 的 1.6%，足够 2000 条活动帧）。 */
    public static final long MAX_TOTAL_CHARS = 4L * 1024L * 1024L;

    /** 账号数上限（防止账号池无限增长；超限淘汰最久未写入账号）。 */
    public static final int MAX_FPIDS = 16;

    /** 单条内容复制预算：超过即拒绝记录（异常大帧不占历史内存）。 */
    static final int MAX_ENTRY_CHARS = 32_000;

    /** 深拷贝最大层数。 */
    static final int MAX_COPY_DEPTH = 6;

    /** 深拷贝最大节点数。 */
    static final int MAX_COPY_ITEMS = 2048;

    /**
     * allowlist（任务说明原样，顺序即匹配顺序）：
     * 活动类 notify_* + 宝箱/情报/行军/资源恢复/邮件/联盟活动。未列出的协议零副作用。
     */
    static final String[] ALLOWLIST = {
            "notify_card_bp_",
            "notify_lost_treasure",
            "notify_value_exchange",
            "notify_battle_pass",
            "notify_daily_claim",
            "notify_optional_gift_pack",
            "notify_daily_bundle",
            "notify_infinite_box",
            "notify_hero_turntable",
            "notify_fortune_card",
            "notify_customize_item",
            "notify_multiple_choice_pack",
            "notify_fishing_activity",
            "notify_month_card",
            "notify_vip",
            "notify_alliance_chest_",
            "notify_intelligence_",
            "notify_player_march_",
            "notify_mass_march_",
            "notify_recover_resource_",
            "notify_activity_calendar_data",
            "notify_mail_",
            "notify_new_mail_syn",
            "notify_expert_quests",
            "notify_mobilization_",
            "notify_alliance_showdown_",
            "notify_alliance_science_",
            "notify_alliance_dev_",
    };

    /** 全局锁：写（inbound 线程）/读（任务线程）共用；临界区只做内存拷贝，无 IO。 */
    private static final Object LOCK = new Object();

    /** fpid → (proto → 最近 N 条，按到达顺序从旧到新)。 */
    private static final Map<String, LinkedHashMap<String, ArrayDeque<Entry>>> ACCOUNTS =
            new LinkedHashMap<>();

    /** fpid → 最后写入时刻（毫秒；账号数超限时淘汰 LRU）。 */
    private static final Map<String, Long> LAST_WRITE_AT = new LinkedHashMap<>();

    private static long totalEntries;
    private static long totalChars;
    private static long seqCounter;

    private ServerNotifyHistory() {
    }

    /**
     * 一条不可变的历史快照。
     *
     * <p>{@code content} 是深拷贝 + 不可变包装；调用方只能读，改原 Map 不会影响历史。
     */
    public static final class Entry {
        public final String proto;
        public final Map<String, Object> content;
        /** 记录时刻（毫秒，用于活动日历新鲜度判定）。 */
        public final long recordedAtMillis;
        /** 全局到达序号（跨 proto 排序；同账号内单调递增）。 */
        public final long seq;
        /** 估算字符数（键名+字符串值；用于全局预算记账）。 */
        final int chars;

        Entry(String proto, Map<String, Object> content, long recordedAtMillis, long seq, int chars) {
            this.proto = proto;
            this.content = content;
            this.recordedAtMillis = recordedAtMillis;
            this.seq = seq;
            this.chars = chars;
        }
    }

    // =======================================================================
    // 写路径（inbound 线程；SessionKeeper 会话钩子调用）
    // =======================================================================

    /**
     * 记录一条 inbound 推送（SessionKeeper 历史钩子调用；非 allowlist / 空内容 / 超预算返回 false）。
     *
     * @param fpid    账号指纹（空/null 忽略；<b>不含 sid</b>，同账号多角色共用一份历史，符合需求口径）
     * @param proto   协议名（必须命中 {@link #ALLOWLIST}）
     * @param content 引擎 inbound 原始内容（本方法内部深拷贝，调用后可安全修改原 Map）
     */
    public static boolean record(String fpid, String proto, Map<String, Object> content) {
        return recordAt(fpid, proto, content, System.currentTimeMillis());
    }

    /** 可注入记录时刻的实现（包级可见：单测确定性地构造“过期日历”）。 */
    static boolean recordAt(String fpid, String proto, Map<String, Object> content, long nowMillis) {
        if (fpid == null || fpid.trim().isEmpty()) return false;
        if (!isAllowlisted(proto)) return false;                 // 非 allowlist：零副作用
        Copy copy = immutableCopy(content);
        if (copy == null || copy.map.isEmpty()) return false;    // 空帧/超预算不记
        String key = fpid.trim();
        synchronized (LOCK) {
            LinkedHashMap<String, ArrayDeque<Entry>> protos =
                    ACCOUNTS.computeIfAbsent(key, k -> new LinkedHashMap<>());
            LAST_WRITE_AT.put(key, nowMillis);
            ArrayDeque<Entry> deque = protos.computeIfAbsent(proto, p -> new ArrayDeque<>());
            // 同内容去重并移到最新：避免同一帧重复推送占满 8 条名额、把其它活动实例挤掉。
            for (Iterator<Entry> it = deque.iterator(); it.hasNext(); ) {
                Entry old = it.next();
                if (old.content.equals(copy.map)) {
                    it.remove();
                    totalEntries--;
                    totalChars -= old.chars;
                    break;
                }
            }
            deque.addLast(new Entry(proto, copy.map, nowMillis, ++seqCounter, copy.chars));
            totalEntries++;
            totalChars += copy.chars;
            while (deque.size() > MAX_PER_PROTO) {                // 每 proto ≤8：淘汰最旧
                dropEntry(deque.removeFirst());
            }
            trim();
        }
        return true;
    }

    // =======================================================================
    // 读路径（任务线程）
    // =======================================================================

    /** 该账号全部历史（旧→新；无记录返回空列表，绝不返回 null）。 */
    public static List<Entry> history(String fpid) {
        return history(fpid, new String[0]);
    }

    /**
     * 按协议名/前缀取历史（旧→新）。
     *
     * <p>匹配语义：{@code proto.startsWith(key)}——传完整协议名即“精确+其变体”，
     * 传 {@code notify_lost_treasure} 即整族。空 keys = 全部协议。
     */
    public static List<Entry> history(String fpid, String... protoOrPrefixes) {
        if (fpid == null || fpid.trim().isEmpty()) return Collections.emptyList();
        synchronized (LOCK) {
            LinkedHashMap<String, ArrayDeque<Entry>> protos = ACCOUNTS.get(fpid.trim());
            if (protos == null || protos.isEmpty()) return Collections.emptyList();
            List<Entry> out = new ArrayList<>();
            for (Map.Entry<String, ArrayDeque<Entry>> e : protos.entrySet()) {
                if (!matchesAny(e.getKey(), protoOrPrefixes)) continue;
                out.addAll(e.getValue());
            }
            out.sort(Comparator.comparingLong(entry -> entry.seq));
            return Collections.unmodifiableList(out);
        }
    }

    /** 匹配协议族的最新一帧内容（无记录返回 null）。 */
    public static Map<String, Object> latestContent(String fpid, String... protoOrPrefixes) {
        List<Entry> entries = history(fpid, protoOrPrefixes);
        if (entries.isEmpty()) return null;
        return entries.get(entries.size() - 1).content;
    }

    /** 匹配协议族最近一次记录时刻（毫秒；无记录返回 0；活动日历新鲜度判定用）。 */
    public static long latestRecordedAt(String fpid, String... protoOrPrefixes) {
        List<Entry> entries = history(fpid, protoOrPrefixes);
        if (entries.isEmpty()) return 0L;
        long max = 0L;
        for (Entry e : entries) max = Math.max(max, e.recordedAtMillis);
        return max;
    }

    // =======================================================================
    // 容量统计 / 清理（单测与运行态自检）
    // =======================================================================

    /** 该账号当前历史条数。 */
    public static int entryCount(String fpid) {
        if (fpid == null) return 0;
        synchronized (LOCK) {
            LinkedHashMap<String, ArrayDeque<Entry>> protos = ACCOUNTS.get(fpid.trim());
            if (protos == null) return 0;
            int n = 0;
            for (ArrayDeque<Entry> deque : protos.values()) n += deque.size();
            return n;
        }
    }

    /** 全局历史条数（≤ {@link #MAX_TOTAL_ENTRIES}）。 */
    public static int totalEntries() {
        synchronized (LOCK) {
            return (int) Math.min(Integer.MAX_VALUE, totalEntries);
        }
    }

    /** 全局估算字符数（≤ {@link #MAX_TOTAL_CHARS}）。 */
    public static long totalChars() {
        synchronized (LOCK) {
            return totalChars;
        }
    }

    /** 当前持有历史的账号数（≤ {@link #MAX_FPIDS}）。 */
    public static int accountCount() {
        synchronized (LOCK) {
            return ACCOUNTS.size();
        }
    }

    /** 清空某账号历史（提供 API 而非任务自动清理：任务需要历史做多实例领取）。 */
    public static void clear(String fpid) {
        if (fpid == null) return;
        synchronized (LOCK) {
            removeAccount(fpid.trim());
        }
    }

    /** 清空全部历史（单测/运维止损用）。 */
    public static void clearAll() {
        synchronized (LOCK) {
            ACCOUNTS.clear();
            LAST_WRITE_AT.clear();
            totalEntries = 0L;
            totalChars = 0L;
            seqCounter = 0L;
        }
    }

    /** 协议是否入历史 allowlist（纯函数；SessionKeeper 钩子可先用它短路，减少拷贝开销）。 */
    public static boolean isAllowlisted(String proto) {
        if (proto == null || proto.isEmpty()) return false;
        for (String prefix : ALLOWLIST) {
            if (proto.startsWith(prefix)) return true;
        }
        return false;
    }

    // =======================================================================
    // 内部：容量淘汰
    // =======================================================================

    /** 账号数 / 总条数 / 总字符任一超限 → 全局淘汰最旧，直到回到红线内。 */
    private static void trim() {
        while (ACCOUNTS.size() > MAX_FPIDS) {
            String lru = null;
            long lruAt = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : LAST_WRITE_AT.entrySet()) {
                long at = e.getValue() == null ? 0L : e.getValue();
                if (at < lruAt) {
                    lruAt = at;
                    lru = e.getKey();
                }
            }
            if (lru == null) break;
            removeAccount(lru);
        }
        while (totalEntries > MAX_TOTAL_ENTRIES || totalChars > MAX_TOTAL_CHARS) {
            if (!removeOldestEntry()) break;
        }
    }

    /** 删除全局 seq 最小的条目（跨账号/跨 proto 扫描；仅在超红线时触发）。 */
    private static boolean removeOldestEntry() {
        String oldestAccount = null;
        String oldestProto = null;
        Entry oldest = null;
        for (Map.Entry<String, LinkedHashMap<String, ArrayDeque<Entry>>> a : ACCOUNTS.entrySet()) {
            for (Map.Entry<String, ArrayDeque<Entry>> p : a.getValue().entrySet()) {
                ArrayDeque<Entry> deque = p.getValue();
                if (deque.isEmpty()) continue;
                Entry head = deque.peekFirst();
                if (oldest == null || head.seq < oldest.seq) {
                    oldest = head;
                    oldestAccount = a.getKey();
                    oldestProto = p.getKey();
                }
            }
        }
        if (oldest == null) return false;
        LinkedHashMap<String, ArrayDeque<Entry>> protos = ACCOUNTS.get(oldestAccount);
        if (protos == null) return false;
        ArrayDeque<Entry> deque = protos.get(oldestProto);
        if (deque == null || deque.isEmpty()) return false;
        dropEntry(deque.removeFirst());
        if (deque.isEmpty()) protos.remove(oldestProto);
        if (protos.isEmpty()) removeAccount(oldestAccount);
        return true;
    }

    /** 彻底移除账号（含计数回退）。 */
    private static void removeAccount(String fpid) {
        LinkedHashMap<String, ArrayDeque<Entry>> protos = ACCOUNTS.remove(fpid);
        LAST_WRITE_AT.remove(fpid);
        if (protos == null) return;
        for (ArrayDeque<Entry> deque : protos.values()) {
            while (!deque.isEmpty()) dropEntry(deque.removeFirst());
        }
    }

    private static void dropEntry(Entry entry) {
        if (entry == null) return;
        totalEntries = Math.max(0L, totalEntries - 1L);
        totalChars = Math.max(0L, totalChars - entry.chars);
    }

    private static boolean matchesAny(String proto, String[] protoOrPrefixes) {
        if (protoOrPrefixes == null || protoOrPrefixes.length == 0) return true;
        boolean hasKey = false;
        for (String key : protoOrPrefixes) {
            if (key == null || key.isEmpty()) continue;
            hasKey = true;
            if (proto.startsWith(key)) return true;
        }
        return !hasKey;   // 传了全空 key 视同“全部协议”
    }

    // =======================================================================
    // 内部：不可变深拷贝（内存红线）
    // =======================================================================

    /** 深拷贝结果：不可变 Map + 估算字符数。 */
    private static final class Copy {
        final Map<String, Object> map;
        final int chars;

        Copy(Map<String, Object> map, int chars) {
            this.map = map;
            this.chars = chars;
        }
    }

    /** 深拷贝为不可变结构；超过单帧预算/层数/节点数返回 null（调用方拒绝记录）。 */
    @SuppressWarnings("unchecked")
    private static Copy immutableCopy(Map<String, Object> content) {
        if (content == null) return null;
        int[] budget = {MAX_ENTRY_CHARS};
        int[] items = {0};
        Object copied = copyValue(content, 0, budget, items);
        if (!(copied instanceof Map)) return null;
        return new Copy((Map<String, Object>) copied, MAX_ENTRY_CHARS - budget[0]);
    }

    private static Object copyValue(Object v, int depth, int[] budget, int[] items) {
        if (budget[0] <= 0 || depth > MAX_COPY_DEPTH || ++items[0] > MAX_COPY_ITEMS) return null;
        if (v == null) return null;
        if (v instanceof String) {
            String s = (String) v;
            budget[0] -= s.length();
            return budget[0] < 0 ? null : s;
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) return v;
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                String k = String.valueOf(e.getKey());
                budget[0] -= k.length();
                Object cv = copyValue(e.getValue(), depth + 1, budget, items);
                if (budget[0] < 0 || (cv == null && e.getValue() != null)) return null;
                out.put(k, cv);
            }
            return Collections.unmodifiableMap(out);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                Object cv = copyValue(o, depth + 1, budget, items);
                if (budget[0] < 0 || (cv == null && o != null)) return null;
                out.add(cv);
            }
            return Collections.unmodifiableList(out);
        }
        // 其它类型（JSONArray/枚举/自定义对象）统一转字符串快照，绝不保留外部可变引用。
        String s = String.valueOf(v);
        budget[0] -= s.length();
        return budget[0] < 0 ? null : s;
    }
}
