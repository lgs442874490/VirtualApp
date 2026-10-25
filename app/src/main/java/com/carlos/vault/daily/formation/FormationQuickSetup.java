package com.carlos.vault.daily.formation;

import android.content.Context;

import com.carlos.vault.daily.FnParams;
import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.daily.gather.GatherConfigV2;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一键编队（网页规格 §1.12「quick-formation」的 App 本地实现，契约 §0：模板与一键编队均为 App 本地）。
 *
 * <p>5 步（与旧版服务端 {@code DailyQuickFormationService} 同口径）：
 * <ol>
 *   <li>{@code gather} 采集：前 {@code min(4, 队伍数)} 个启用队列改 ROLE + 对应资源内置模板；</li>
 *   <li>{@code behemoth} 巨兽：仅当队列数=1 时改 ROLE + 巨兽内置（否则跳过）；</li>
 *   <li>{@code intel} 情报：改 ROLE + 打野内置；</li>
 *   <li>{@code hunt} 狩猎：仅当队列数=1 时改 ROLE + 打野内置（否则跳过）；</li>
 *   <li>{@code hero_mission} 英雄的使命：改 ROLE + 巨兽内置（App 当前无该模块，仅写 params 前向兼容）。</li>
 * </ol>
 *
 * <p>结果状态用网页小写字符串：{@code applied|skipped|failed}；每步异常被捕获为 failed
 * （不中断后续步骤）。T4 直接用于「一键编队结果」弹窗逐行渲染。
 */
public final class FormationQuickSetup {

    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";

    public static final String MODULE_GATHER = "gather";
    public static final String MODULE_BEHEMOTH = "behemoth";
    public static final String MODULE_INTEL = "intel";
    public static final String MODULE_HUNT = "hunt";
    public static final String MODULE_HERO_MISSION = "hero_mission";

    /** 网页模块中文映射（未知用 fnId/未知模块）。 */
    public static final String MSG_NOT_SYNCED = "当前角色编队尚未同步，请等待容量同步完成后重试";
    public static final String MSG_NO_GATHER_QUEUE = "没有启用的采集队列，已跳过";
    public static final String MSG_MULTI_QUEUE_SKIP_FMT = "当前队列数为 %d，仅单队列时自动绑定";

    /** 一步结果。 */
    public static final class Step {
        public final String module;
        public final String status;
        public final String message;

        public Step(String module, String status, String message) {
            this.module = module == null ? "" : module;
            this.status = status == null ? STATUS_FAILED : status;
            this.message = message == null ? "" : message;
        }

        public String label() {
            return moduleLabel(module);
        }

        /** 弹窗行文案：{@code 已设置 · 采集：msg} / {@code 已跳过 · ...} / {@code 失败 · ...}（网页 §1.12）。 */
        public String line() {
            String prefix;
            if (STATUS_APPLIED.equals(status)) prefix = "已设置";
            else if (STATUS_SKIPPED.equals(status)) prefix = "已跳过";
            else prefix = "失败";
            return prefix + " · " + label() + "：" + message;
        }
    }

    private FormationQuickSetup() {
    }

    /** 模块中文（网页 §1.12 映射）。 */
    public static String moduleLabel(String module) {
        if (module == null) return "未知模块";
        if (MODULE_GATHER.equals(module)) return "采集";
        if (MODULE_BEHEMOTH.equals(module)) return "每日巨兽";
        if (MODULE_INTEL.equals(module)) return "情报";
        if (MODULE_HUNT.equals(module)) return "自动狩猎";
        if (MODULE_HERO_MISSION.equals(module)) return "英雄的使命";
        return "未知模块";
    }

    /**
     * 纯规划（可 JVM 单测）：不落库，只产出每步状态/文案。
     *
     * @param queueLimit           队伍数（1..5）
     * @param gatherQueueResources 启用的采集队列资源类型（MEAT/WOOD/COAL/IRON，按队列顺序）
     * @param builtInsReady        6 个懒创建内置是否已就绪（容量已同步 + 有英雄目录）
     */
    public static List<Step> plan(int queueLimit, List<String> gatherQueueResources,
                                  boolean builtInsReady) {
        List<Step> steps = new ArrayList<Step>();
        int limit = clampQueueLimit(queueLimit);
        int gatherCount = gatherQueueResources == null ? 0 : gatherQueueResources.size();
        int gatherTarget = Math.min(4, Math.min(limit, gatherCount));

        if (!builtInsReady) {
            steps.add(new Step(MODULE_GATHER, STATUS_FAILED, MSG_NOT_SYNCED));
            steps.add(new Step(MODULE_BEHEMOTH, STATUS_FAILED, MSG_NOT_SYNCED));
            steps.add(new Step(MODULE_INTEL, STATUS_FAILED, MSG_NOT_SYNCED));
            steps.add(new Step(MODULE_HUNT, STATUS_FAILED, MSG_NOT_SYNCED));
            steps.add(new Step(MODULE_HERO_MISSION, STATUS_FAILED, MSG_NOT_SYNCED));
            return steps;
        }

        if (gatherTarget <= 0) {
            steps.add(new Step(MODULE_GATHER, STATUS_SKIPPED, MSG_NO_GATHER_QUEUE));
        } else {
            steps.add(new Step(MODULE_GATHER, STATUS_APPLIED,
                    String.format(Locale.ROOT, "已设置 %d 个采集队列", gatherTarget)));
        }
        steps.add(oneQueueStep(MODULE_BEHEMOTH, limit));
        steps.add(new Step(MODULE_INTEL, STATUS_APPLIED, "已绑定打野内置编队"));
        steps.add(oneQueueStep(MODULE_HUNT, limit));
        steps.add(new Step(MODULE_HERO_MISSION, STATUS_APPLIED, "已绑定巨兽内置编队"));
        return steps;
    }

    private static Step oneQueueStep(String module, int queueLimit) {
        if (queueLimit != 1) {
            return new Step(module, STATUS_SKIPPED,
                    String.format(Locale.ROOT, MSG_MULTI_QUEUE_SKIP_FMT, queueLimit));
        }
        return new Step(module, STATUS_APPLIED, "已绑定内置编队");
    }

    /** 队伍数（取采集配置 {@code max_queues}，默认 3；clamp 1..5）。 */
    public static int queueLimit(Context ctx, String fpid, long sid) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, "gather");
            return clampQueueLimit(p.optInt("max_queues", p.optInt("maxQueues", 3)));
        } catch (Throwable t) {
            return 3;
        }
    }

    /** 启用的采集队列资源类型（按队列 id 顺序，最多 6 个）。 */
    public static List<String> gatherQueueResources(Context ctx, String fpid, long sid) {
        List<String> out = new ArrayList<String>();
        try {
            GatherConfigV2 cfg = GatherConfigV2.parse(
                    FnParams.params(ctx, fpid, sid, "gather"));
            for (GatherConfigV2.QueueCfg q : cfg.queues) {
                if (q.enabled) out.add(q.resourceType);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 执行一键编队（写 FnParams：gather 队列级 v2 + 各功能 formation_mode/formation_id）。
     *
     * @return 5 步结果（applied/skipped/failed），永不抛异常
     */
    public static List<Step> run(Context ctx, String fpid, long sid) {
        return run(ctx, fpid, sid, "");
    }

    /**
     * 同上，但目录/内置编队按<b>角色 uid</b> 精确读取（多角色同区服不串目录；
     * uid 空/0 时等价旧重载，读旧键）。
     */
    public static List<Step> run(Context ctx, String fpid, long sid, String uid) {
        if (ctx == null) return plan(queueLimitOr3(null, fpid, sid), null, false);
        int limit = clampQueueLimit(queueLimit(ctx, fpid, sid));
        List<String> resources = gatherQueueResources(ctx, fpid, sid);
        boolean ready;
        List<FormationModel> lib;
        try {
            FormationStore.ensureBuiltIns(ctx, fpid, sid, uid);
            lib = FormationStore.formations(ctx, fpid, sid);
            ready = hasAllLazyBuiltIns(lib)
                    && FormationStore.catalogOf(ctx, fpid, sid, uid).configured();
        } catch (Throwable t) {
            ready = false;
            lib = new ArrayList<FormationModel>();
        }
        List<Step> steps = plan(limit, resources, ready);
        List<Step> out = new ArrayList<Step>();
        for (Step step : steps) {
            if (!STATUS_APPLIED.equals(step.status)) {
                out.add(step);
                continue;
            }
            try {
                out.add(apply(ctx, fpid, sid, step, limit, resources));
            } catch (Throwable t) {
                out.add(new Step(step.module, STATUS_FAILED,
                        t.getMessage() == null ? "设置失败" : t.getMessage()));
            }
        }
        return out;
    }

    private static Step apply(Context ctx, String fpid, long sid, Step step, int limit,
                              List<String> resources) {
        if (MODULE_GATHER.equals(step.module)) {
            return applyGather(ctx, fpid, sid, limit, resources);
        }
        long formationId;
        String fnId;
        if (MODULE_BEHEMOTH.equals(step.module)) {
            formationId = FormationBuiltIns.ID_BEHEMOTH;
            fnId = "behemoth";
        } else if (MODULE_INTEL.equals(step.module)) {
            formationId = FormationBuiltIns.ID_WILD;
            fnId = "intel";
        } else if (MODULE_HUNT.equals(step.module)) {
            formationId = FormationBuiltIns.ID_WILD;
            fnId = "hunt";
        } else {
            formationId = FormationBuiltIns.ID_BEHEMOTH;
            fnId = "hero_mission";
        }
        JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
        FormationBinding.applyIds(p, new long[]{formationId});
        FnParams.saveParams(ctx, fpid, sid, fnId, p);
        return new Step(step.module, STATUS_APPLIED,
                "已绑定「" + builtInName(formationId) + "」");
    }

    private static Step applyGather(Context ctx, String fpid, long sid, int limit,
                                    List<String> resources) {
        JSONObject p = FnParams.params(ctx, fpid, sid, "gather");
        GatherConfigV2 cfg = GatherConfigV2.parse(p);
        int target = Math.min(4, Math.min(limit, resources.size()));
        int bound = 0;
        long firstFormationId = 0;
        for (GatherConfigV2.QueueCfg q : cfg.queues) {
            if (!q.enabled) continue;
            if (bound >= target) break;
            long builtInId = FormationBuiltIns.idForGatherResource(q.resourceType);
            if (builtInId <= 0) continue;
            q.formationMode = FormationStore.MODE_ROLE;
            q.formationId = builtInId;
            if (firstFormationId == 0) firstFormationId = builtInId;
            bound++;
        }
        try {
            p.put(GatherConfigV2.KEY, cfg.toV2Json());
            if (firstFormationId > 0) {
                // 兼容旧服务端/旧读取方：功能级 formation_mode/formation_id 指向首个队列模板
                p.put("formation_mode", FormationStore.MODE_ROLE);
                p.put("formation_id", firstFormationId);
            }
        } catch (Throwable ignored) {
        }
        FnParams.saveParams(ctx, fpid, sid, "gather", p);
        if (bound <= 0) return new Step(MODULE_GATHER, STATUS_SKIPPED, MSG_NO_GATHER_QUEUE);
        return new Step(MODULE_GATHER, STATUS_APPLIED,
                String.format(Locale.ROOT, "已设置 %d 个采集队列", bound));
    }

    private static boolean hasAllLazyBuiltIns(List<FormationModel> lib) {
        long[] ids = {FormationBuiltIns.ID_BEHEMOTH, FormationBuiltIns.ID_COAL,
                FormationBuiltIns.ID_IRON, FormationBuiltIns.ID_MEAT,
                FormationBuiltIns.ID_WOOD, FormationBuiltIns.ID_WILD};
        for (long id : ids) {
            if (FormationRules.findById(lib, id) == null) return false;
        }
        return true;
    }

    private static String builtInName(long id) {
        FormationModel f = FormationBuiltIns.byId(id);
        return f == null ? String.valueOf(id) : f.name;
    }

    private static int queueLimitOr3(Context ctx, String fpid, long sid) {
        return ctx == null ? 3 : queueLimit(ctx, fpid, sid);
    }

    private static int clampQueueLimit(int v) {
        return v < 1 ? 1 : (v > 5 ? 5 : v);
    }
}
