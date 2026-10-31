package com.carlos.vaultserver.automation.services;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 军医所服务：伤兵统计 + 分批循环治疗 + 联盟求助。
 *
 * <p>修复的 BUG：
 * <ol>
 *   <li>旧实现只读 notify_hospital_init.wound_soldiers；实战大量伤兵来自
 *       notify_hospital_wound_soldiers 推送，init 可能是空快照。本服务合并多个通知并按兵种取 max，
 *       避免漏计/重复计。</li>
 *   <li><b>2026-10-28 P0 数据丢失</b>：{@link #snapshot} 把伤兵合并进局部 map 后调用
 *       {@code new Snapshot(sources)}，只把 sources 传进去、wounded/healing 被丢弃，
 *       导致 {@link Snapshot#woundedTotal()} 恒为 0（生产实证"无伤兵（hospital_init=376; ）"）。
 *       现在构造函数显式接收 wounded/healing。</li>
 *   <li>治疗改为按批循环（1:1 网页版 DailyHospitalState.planHealingBatch:332-346 +
 *       DailyTownService.processHospitalHealing:965-1035）：每批 min(剩余, heal_count) 名、
 *       高等级优先；成功后本地扣减并继续，直到清零/资源不足(51)/上批治疗中(2301)/
 *       数据变化(2302)/连续失败，单轮最多 {@link #MAX_BATCHES_PER_ROUND} 批。</li>
 *   <li>联盟求助从错误的 {@code req_alliance_help_all} 改为网页版同款
 *       {@code req_queue_ask_for_help{qid}}（RecoveryAutomationExecutor.askHospitalHelp:20-22），
 *       qid 取 notify_queue_info / notify_queue_update 的 queue_map 中 base.qtype==6 的医院队列
 *       （DailyHospitalState.rememberQueue:161-190）；解析不到时不盲发，状态行写明原因。</li>
 * </ol>
 *
 * <p>参数契约（App 功能页同步，服务端只读 medic 参数顶层键，勿改键名）：
 * {@code heal_count}（int，缺省 {@link #DEFAULT_HEAL_COUNT}=400；显式 0=全部；>0 夹 1..{@link #MAX_HEAL_COUNT}）、
 * {@code immediate}（bool）、{@code request_help}（bool，默认 true）。
 */
public final class MedicService {

    /**
     * 每批治疗量缺省值（App 医院功能页数字输入框默认 400，范围 1-10000）。
     * 2026-10-28 Lead 契约：服务端缺省 400 而不是"0=全部"——App 不再"打开即写默认 400"，
     * 用户没动过设置时若把缺省 0 当全部，会一次性请求全部伤兵，与"每批 400 循环治疗"不符。
     * 显式传 0 仍是"全部"（兼容旧客户端语义）。
     */
    public static final int DEFAULT_HEAL_COUNT = 400;

    /** 单轮最多治疗批次数（防请求风暴；正常 immediate=false 时第 2 批即被 2301 挡住）。 */
    public static final int MAX_BATCHES_PER_ROUND = 20;

    /** 单批治疗量上限（App 输入框 1-10000）。 */
    public static final int MAX_HEAL_COUNT = 10000;

    /** 医院队列类型（网页版 DailyHospitalState.HOSPITAL_QUEUE_TYPE=6）。 */
    public static final long HOSPITAL_QUEUE_TYPE = 6L;

    /** 治疗相关协议/错误码（网页版 DailyTownService.handleHospitalHealingError:1020-1044）。 */
    static final int CODE_RESOURCE_SHORT = 51;
    static final int CODE_HEALING = 2301;
    static final int CODE_WOUNDED_CHANGED = 2302;
    /** 联盟求助不可用（网页版 askHospitalHelp 判 75）。 */
    static final int CODE_HELP_UNAVAILABLE = 75;

    private static final String HELP_UNAVAILABLE_NOTE = "求助未发（医院队列未同步）";

    /** 伤兵/治疗中快照（合并多个通知后的结果）。 */
    public static final class Snapshot {
        public final Map<Long, Long> wounded;
        public final Map<Long, Long> healing;
        public final String sources;

        Snapshot(Map<Long, Long> wounded, Map<Long, Long> healing, String sources) {
            this.wounded = wounded == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(wounded));
            this.healing = healing == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(healing));
            this.sources = sources == null ? "" : sources;
        }

        public long woundedTotal() {
            long n = 0;
            for (long v : wounded.values()) n += v;
            return n;
        }

        public long healingTotal() {
            long n = 0;
            for (long v : healing.values()) n += v;
            return n;
        }

        /**
         * 规划一批治疗：累计不超过 {@code limit}（&lt;=0=全部），高等级优先。
         * 排序口径 1:1 网页版 DailyHospitalState.planHealingBatch:332-346：
         * 等级 =(id/100)%100 降序 → 兵种顺序 盾(1)/弓(3)/矛(2) → id 升序（保证确定性）。
         */
        public Map<Long, Long> planHealingBatch(int limit) {
            return MedicService.planHealingBatch(wounded, limit);
        }
    }

    public Snapshot snapshot(TaskContext tc) {
        tc.engine.collectNotifies(1500);
        Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        try {
            FileLog.info("medic notifies fpid={} protos={}", tc.fpid,
                    tc.engine.notifyProtos());
        } catch (Throwable ignored) {
        }
        for (String proto : new String[]{
                "notify_hospital_init",
                "notify_hospital_wound_soldiers",
                // 跨服/乱斗军医所：按配置可选择是否纳入；默认纳入，避免"主城无伤兵但活动有伤兵"。
                "notify_brawl_war_hospital_init",
                "notify_arsenal_war_hospital_init",
                "notify_arsenal_league_hospital_init"}) {
            try {
                notifies.put(proto, tc.engine.notifyContent(proto));
            } catch (Throwable ignored) {
            }
        }
        return buildSnapshot(notifies);
    }

    /**
     * 纯函数：把各通知内容（proto → 原始 content）合并成快照。
     * notifyContent 返回原始 content，这里按 {@link WjdrEngine#notifyData} 同款规则解包
     * data/info/content 内层 Map；init 与 wound push 按兵种取 max 防重复计数。
     */
    public static Snapshot buildSnapshot(Map<String, Map<String, Object>> notifies) {
        Map<String, Map<String, Object>> safe = notifies == null
                ? Collections.emptyMap() : notifies;
        Map<Long, Long> wounded = new LinkedHashMap<>();
        Map<Long, Long> healing = new LinkedHashMap<>();
        StringBuilder sources = new StringBuilder();

        readAndMerge(wounded, unwrap(safe.get("notify_hospital_init")),
                "wound_soldiers", "hospital_init", sources);
        readAndMerge(wounded, unwrap(safe.get("notify_hospital_wound_soldiers")),
                "wound_soldiers", "hospital_wound", sources);
        readAndMerge(wounded, unwrap(safe.get("notify_brawl_war_hospital_init")),
                "wound_soldiers", "brawl_hospital_init", sources);
        readAndMerge(wounded, unwrap(safe.get("notify_arsenal_war_hospital_init")),
                "wound_soldiers", "arsenal_war_hospital_init", sources);
        readAndMerge(wounded, unwrap(safe.get("notify_arsenal_league_hospital_init")),
                "wound_soldiers", "arsenal_league_hospital_init", sources);

        // 正在治疗的伤兵：heal_data.soldiers
        readHealing(healing, unwrap(safe.get("notify_hospital_init")),
                "heal_data", sources);
        return new Snapshot(wounded, healing,
                sources.length() == 0 ? "无军医所通知" : sources.toString());
    }

    /**
     * 解析 wound_soldiers（支持 [{k,v}] 与 {soldiers:[{k,v}]} 两种形状）。
     */
    public static Map<Long, Long> parseWounded(Object value) {
        Map<Long, Long> m = Exec.kvPairs(value);
        if (m.isEmpty() && value instanceof Map) {
            Object inner = ((Map<?, ?>) value).get("soldiers");
            m = Exec.kvPairs(inner);
        }
        return m;
    }

    /** 多来源合并取 max，避免 init + push 重复计数。 */
    public static void mergeMax(Map<Long, Long> out, Map<Long, Long> in) {
        if (out == null || in == null) return;
        for (Map.Entry<Long, Long> e : in.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    /**
     * 纯函数：规划一批治疗。{@code limit<=0} 表示全部剩余伤兵。
     * 只取可识别兵种（id/10000 ∈ 1..3，网页版 supportedSoldier 同口径）。
     */
    public static Map<Long, Long> planHealingBatch(Map<Long, Long> wounded, int limit) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (wounded == null || wounded.isEmpty()) return out;
        long remaining = limit <= 0 ? Long.MAX_VALUE : Math.min(limit, MAX_HEAL_COUNT);
        List<Map.Entry<Long, Long>> candidates = new ArrayList<>();
        for (Map.Entry<Long, Long> e : wounded.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (e.getValue() <= 0L || !supportedSoldier(e.getKey())) continue;
            candidates.add(e);
        }
        candidates.sort(Comparator
                .comparingInt((Map.Entry<Long, Long> e) -> soldierLevel(e.getKey())).reversed()
                .thenComparingInt(e -> troopOrder(e.getKey()))
                .thenComparingLong(Map.Entry::getKey));
        for (Map.Entry<Long, Long> e : candidates) {
            if (remaining <= 0L) break;
            long take = Math.min(remaining, e.getValue());
            if (take <= 0L) continue;
            out.put(e.getKey(), take);
            remaining -= take;
        }
        return out;
    }

    /** 纯函数：从伤兵表扣减已治疗批次（不改入参，返回新 map；扣到 0 的条目移除）。 */
    public static Map<Long, Long> subtractBatch(Map<Long, Long> wounded, Map<Long, Long> batch) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (wounded != null) out.putAll(wounded);
        if (batch == null) return out;
        for (Map.Entry<Long, Long> e : batch.entrySet()) {
            Long cur = out.get(e.getKey());
            if (cur == null) continue;
            long left = cur - e.getValue();
            if (left > 0L) out.put(e.getKey(), left);
            else out.remove(e.getKey());
        }
        return out;
    }

    // ---------- 医院队列 qid（联盟求助目标） ----------

    /**
     * 纯函数：从 notify_queue_info / notify_queue_update 的 content 中解析医院队列 qid。
     * 形状兼容：{data:{queue_map:{qid:{base:{qtype:6}}}}} / 已解包内层 / {queue:{...}} / 单条队列本体。
     *
     * @return 医院队列 qid；0=未同步（此时不盲发求助）
     */
    public static long hospitalQidOfNotify(Map<String, Object> content) {
        if (content == null) return 0L;
        List<Map<String, Object>> layers = new ArrayList<>();
        layers.add(content);
        Map<String, Object> inner = unwrap(content);
        if (inner != null && inner != content) layers.add(inner);
        for (Map<String, Object> layer : layers) {
            long q = hospitalQidOfQueueMap(layer.get("queue_map"));
            if (q > 0L) return q;
            q = hospitalQidOfQueueMap(layer.get("queues"));
            if (q > 0L) return q;
            q = hospitalQidOfSingleQueue(layer.get("queue"));
            if (q > 0L) return q;
            q = hospitalQidOfSingleQueue(layer);
            if (q > 0L) return q;
        }
        return 0L;
    }

    /** 纯函数：直接解析 queue_map（{qid:{...}} Map 或 [{qid:...}] List 形状兼容）。 */
    public static long hospitalQidOfQueueMap(Object queueMap) {
        if (queueMap == null) return 0L;
        List<Map<String, Object>> queues = new ArrayList<>();
        if (queueMap instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) queueMap).entrySet()) {
                Map<String, Object> q = Exec.mapOf(e.getValue());
                if (q == null) continue;
                Map<String, Object> copy = new LinkedHashMap<>(q);
                if (numOf(copy.get("qid")) <= 0L) {
                    long keyQid = numOf(e.getKey());
                    if (keyQid > 0L) copy.put("qid", keyQid);
                }
                queues.add(copy);
            }
        } else {
            queues.addAll(Exec.mapsOf(queueMap));
        }
        for (Map<String, Object> q : queues) {
            Map<String, Object> base = Exec.mapOf(q.get("base"));
            long qtype = base != null && base.get("qtype") != null
                    ? numOf(base.get("qtype")) : numOf(q.get("qtype"));
            if (qtype != HOSPITAL_QUEUE_TYPE) continue;
            long qid = numOf(q.get("qid"));
            if (qid <= 0L) qid = numOf(q.get("id"));
            if (qid > 0L) return qid;
        }
        return 0L;
    }

    /** 单条队列形状（notify_queue_be_help.queue / 已解包本体）：base.qtype==6 时取 qid。 */
    private static long hospitalQidOfSingleQueue(Object raw) {
        Map<String, Object> q = Exec.mapOf(raw);
        if (q == null) return 0L;
        Map<String, Object> base = Exec.mapOf(q.get("base"));
        long qtype = base != null && base.get("qtype") != null
                ? numOf(base.get("qtype")) : numOf(q.get("qtype"));
        if (qtype != HOSPITAL_QUEUE_TYPE) return 0L;
        long qid = numOf(q.get("qid"));
        return qid > 0L ? qid : numOf(q.get("id"));
    }

    /** 当前会话最近一次同步到的医院队列 qid（0=未同步）。 */
    static long hospitalQid(WjdrEngine engine) {
        if (engine == null) return 0L;
        for (String proto : new String[]{"notify_queue_info", "notify_queue_update"}) {
            try {
                long q = hospitalQidOfNotify(engine.notifyContent(proto));
                if (q > 0L) return q;
                q = hospitalQidOfNotify(engine.notifyData(proto));
                if (q > 0L) return q;
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    // ---------- 治疗主流程 ----------

    /**
     * App 参数入口（{@code Modules.medic} 与 {@code ServerTownTasks.medic} 共用唯一事实源）：
     * 读 medic 顶层键 {@code heal_count}（缺省 {@link #DEFAULT_HEAL_COUNT}=400，显式 0=全部）、
     * {@code immediate}（缺省 false）、{@code request_help}（缺省 true）。
     *
     * <p>两处入口不允许各写一份缺省：App 不再"打开医院卡即写默认 400"，若服务端缺省仍按
     * "0=全部"会在用户未改设置时一次性请求全部伤兵，与产品要求"每批 400 循环治疗"不一致。
     */
    public static TaskOutcome healFromParams(TaskContext tc) {
        return new MedicService().heal(tc, healCountOf(tc), immediateOf(tc), requestHelpOf(tc));
    }

    /** 每批治疗量解析：缺省 400；显式 0 保留"全部"语义（实际夹取见 {@link #normalizeHealCount}）。 */
    public static int healCountOf(TaskContext tc) {
        try {
            return tc.params("medic").optInt("heal_count", DEFAULT_HEAL_COUNT);
        } catch (Throwable t) {
            return DEFAULT_HEAL_COUNT;
        }
    }

    /** immediate 解析：缺省 false（保留排队治疗语义，与网页版一致）。 */
    static boolean immediateOf(TaskContext tc) {
        try {
            return tc.params("medic").optBoolean("immediate", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** request_help 解析：缺省 true（产品要求治疗成功后自动联盟求助）。 */
    static boolean requestHelpOf(TaskContext tc) {
        try {
            return tc.params("medic").optBoolean("request_help", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 分批循环治疗 + 每批成功后联盟求助。
     *
     * @param maxCount   每批治疗量：0=全部；&gt;0 最多该数量（按高等级优先截取；上限 {@link #MAX_HEAL_COUNT}）
     * @param immediate  是否立即完成（true 时单轮可连续清空伤兵）
     * @param requestHelp 是否在治疗成功后发起联盟求助（req_queue_ask_for_help{qid}）
     */
    public TaskOutcome heal(TaskContext tc, int maxCount, boolean immediate,
                            boolean requestHelp) {
        // App 执行参数卡「自动治疗」子开关（medic.sub.heal，默认 true）：
        // 关掉后服务端必须同样不治疗，保持与 App 显示一致（2026-10-28 app-ui 对齐）。
        if (!tc.subOn("medic", "heal", true)) {
            TaskOutcome skipped = new TaskOutcome("medic", "军医所",
                    TaskOutcome.Category.SKIPPED, "自动治疗已关闭（medic.sub.heal=false）");
            skipped.subResults.put("heal", "skip:自动治疗子开关已关闭");
            tc.saveModText("medic", "heal", "skip:自动治疗子开关已关闭");
            return skipped;
        }
        try {
            Snapshot s = snapshot(tc);
            long total = s.woundedTotal();
            if (total <= 0) {
                String why = "无伤兵（" + s.sources + "）";
                TaskOutcome outcome = new TaskOutcome("medic", "军医所",
                        TaskOutcome.Category.SKIPPED, why);
                outcome.subResults.put("heal", "skip:" + why);
                tc.saveModText("medic", "heal", "skip:" + why);
                return outcome;
            }
            int healCount = normalizeHealCount(maxCount);
            long qid = requestHelp ? hospitalQid(tc.engine) : 0L;
            Map<Long, Long> remaining = new LinkedHashMap<>(s.wounded);
            long healed = 0L;
            int batches = 0;
            int consecutiveFail = 0;
            String stop = "";
            String helpNote = requestHelp ? null : "求助已关闭（request_help=false）";
            boolean helpAsked = false;

            for (int i = 0; i < MAX_BATCHES_PER_ROUND; i++) {
                Map<Long, Long> batch = planHealingBatch(remaining, healCount);
                if (batch.isEmpty()) {
                    stop = stop.isEmpty() ? "无剩余可治疗伤兵" : stop;
                    break;
                }
                long want = sum(batch);
                GameMessage rsp = tc.engine.request("req_hospital_heal_soldier",
                        Exec.args("soldiers", kvPairs(batch), "immediate", immediate));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    batches++;
                    healed += want;
                    consecutiveFail = 0;
                    remaining = subtractBatch(remaining, batch);   // 成功批从本地伤兵表扣减
                    if (requestHelp && !helpAsked) {
                        helpNote = askHelp(tc, qid);
                        helpAsked = true;
                    }
                    if (remaining.isEmpty()) {
                        stop = "伤兵已清零";
                        break;
                    }
                    if (!immediate) Exec.sleep(200);   // 连批之间轻停顿，避免同 tick 打爆
                    continue;
                }
                // 失败分类（1:1 网页版 handleHospitalHealingError:1020-1044）
                if (code == CODE_HEALING) {
                    stop = "上批仍在治疗中";
                    // 治疗进行中也能求助：网页版 healing() 分支只做 askHospitalHelp。
                    if (requestHelp && !helpAsked) {
                        helpNote = askHelp(tc, qid);
                        helpAsked = true;
                    }
                    break;
                } else if (code == CODE_RESOURCE_SHORT) {
                    stop = "资源不足，等待资源恢复";
                    break;
                } else if (code == CODE_WOUNDED_CHANGED) {
                    stop = "伤兵数量已变化，等待军医所同步";
                    break;
                } else if (code == 2 || code == -1) {
                    stop = "伤兵数据无效（码" + code + "）";
                    break;
                } else {
                    consecutiveFail++;
                    stop = "治疗失败（码" + code + "）";
                    if (consecutiveFail >= 2) break;
                }
            }

            long leftTotal = sum(remaining);
            TaskOutcome.Category cat;
            if (batches > 0) {
                cat = TaskOutcome.Category.CLAIMED;
            } else if (isNormalSkipStop(stop)) {
                cat = TaskOutcome.Category.SKIPPED;
            } else {
                cat = TaskOutcome.Category.ERROR;
            }
            String healText = (batches > 0
                    ? "治疗" + healed + "伤兵/" + batches + "批（剩余" + leftTotal + "，伤兵总数" + total + "）"
                    : "未治疗") + (stop.isEmpty() ? "" : " · " + stop);
            TaskOutcome outcome = new TaskOutcome("medic", "军医所", cat, healText);
            outcome.subResults.put("heal", (batches > 0 ? "ok:" : "skip:") + healText);
            tc.saveModText("medic", "heal", (batches > 0 ? "" : "skip:") + healText);
            if (helpNote != null) {
                String helpText = helpNote + (batches > 0 ? "（" + batches + "批后）" : "");
                outcome.subResults.put("help", helpSubPrefix(helpNote) + helpText);
                tc.saveModText("medic", "help", helpText);
            }
            return outcome;
        } catch (Exception e) {
            if (com.carlos.vaultserver.task.ServerTownTasks.sessionLost(e)) {
                return com.carlos.vaultserver.task.ServerTasks.classifyException(e, "军医所");
            }
            return new TaskOutcome("medic", "军医所", TaskOutcome.Category.ERROR,
                    "治疗异常：" + e.getClass().getSimpleName());
        }
    }

    /** 每批治疗量：0=全部；&gt;0 夹到 [1, {@link #MAX_HEAL_COUNT}]。 */
    static int normalizeHealCount(int healCount) {
        if (healCount <= 0) return 0;
        return Math.min(healCount, MAX_HEAL_COUNT);
    }

    /**
     * 求助子结果的子项前缀（App 执行参数卡按 ok/skip/fail 着色）：
     * 只有真正发出并成功才算 ok；未同步/已关闭/服务端暂不可用归 skip；失败/异常归 fail。
     * 修复 review 指出的"求助失败也被标 ok:"导致 UI 误显示绿色"。
     */
    static String helpSubPrefix(String helpNote) {
        if (helpNote == null) return "skip:";
        if (helpNote.startsWith("已求助")) return "ok:";
        if (helpNote.startsWith("求助失败") || helpNote.startsWith("求助异常")) return "fail:";
        return "skip:";
    }

    /** 该停止原因是否属于"正常跳过"（资源/队列/数据同步类），而不是真异常。 */
    private static boolean isNormalSkipStop(String stop) {
        return stop.isEmpty()
                || stop.contains("上批仍在治疗中")
                || stop.contains("资源不足")
                // 2026-11-01 生产实证（新角色）：军医所数据未就绪返回码 2 → 跳过而非 ERROR
                || stop.contains("伤兵数据无效")
                || stop.contains("伤兵数量已变化")
                || stop.contains("无剩余可治疗伤兵")
                || stop.contains("伤兵已清零");
    }

    /**
     * 联盟求助（网页版 RecoveryAutomationExecutor.askHospitalHelp:20-22）：
     * {@code req_queue_ask_for_help{qid}}；旧实现误用 {@code req_alliance_help_all}。
     * qid 未同步时不盲发，返回可读原因。
     */
    private String askHelp(TaskContext tc, long qid) {
        if (qid <= 0L) return HELP_UNAVAILABLE_NOTE;
        try {
            GameMessage help = tc.engine.request("req_queue_ask_for_help",
                    Exec.args("qid", qid));
            if (Exec.ok(help)) return "已求助联盟（qid=" + qid + "）";
            int code = Exec.codeOf(help);
            if (code == CODE_HELP_UNAVAILABLE) return "求助暂不可用（码" + code + "）";
            if (code == CODE_HEALING || code == CODE_RESOURCE_SHORT) {
                return "求助未生效（码" + code + "）";
            }
            return "求助失败（码" + code + "）";
        } catch (Throwable t) {
            return "求助异常：" + t.getClass().getSimpleName();
        }
    }

    // ---------- 内部工具 ----------

    /** notifyData 同款解包：data/info/content 中第一个 Map 内层。 */
    private static Map<String, Object> unwrap(Map<String, Object> content) {
        if (content == null) return null;
        for (String k : new String[]{"data", "info", "content"}) {
            Map<String, Object> inner = Exec.mapOf(content.get(k));
            if (inner != null) return inner;
        }
        return content;
    }

    private static void readAndMerge(Map<Long, Long> out, Map<String, Object> notify,
                                     String key, String source, StringBuilder sources) {
        if (notify == null) return;
        Map<Long, Long> m = parseWounded(notify.get(key));
        if (m.isEmpty()) return;
        sources.append(source).append('=').append(sum(m)).append("; ");
        mergeMax(out, m);
    }

    private static void readHealing(Map<Long, Long> out, Map<String, Object> notify,
                                    String key, StringBuilder sources) {
        if (notify == null) return;
        Map<String, Object> heal = Exec.mapOf(notify.get(key));
        if (heal == null) return;
        Map<Long, Long> m = Exec.kvPairs(heal.get("soldiers"));
        for (Map.Entry<Long, Long> e : m.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Math::max);
        }
        if (!m.isEmpty()) sources.append("healing=").append(sum(m)).append("; ");
    }

    /** 兵种 id 是否可治疗（id/10000 ∈ 1..3，网页版 supportedSoldier 同口径）。 */
    static boolean supportedSoldier(long soldierId) {
        long type = soldierId / 10000L;
        return type >= 1L && type <= 3L;
    }

    /** 士兵等级：(id/100)%100（网页版 soldierLevel 同口径）。 */
    static int soldierLevel(long soldierId) {
        return (int) Math.floorMod(soldierId / 100L, 100L);
    }

    /** 兵种优先级：盾(1)=0 → 弓(3)=1 → 矛(2)=2，与网页版 troopOrder 一致。 */
    static int troopOrder(long soldierId) {
        long type = soldierId / 10000L;
        if (type == 1L) return 0;
        if (type == 3L) return 1;
        if (type == 2L) return 2;
        return 3;
    }

    private static long sum(Map<Long, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }

    /** 伤兵批次转协议对列表 soldiers:[{k,v}]。 */
    private static List<Map<String, Object>> kvPairs(Map<Long, Long> batch) {
        List<Map<String, Object>> out = new ArrayList<>(batch.size());
        for (Map.Entry<Long, Long> e : batch.entrySet()) {
            out.add(Exec.kv(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 数值解析：兼容 Number / "123" / "123.0"（sproto 的 map 键常带 .0）。 */
    private static long numOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
