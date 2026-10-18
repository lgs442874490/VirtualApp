package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.CaptureTasks;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.automation.AutomationConfig;
import com.carlos.vaultserver.automation.AutomationContext;
import com.carlos.vaultserver.automation.AutomationServices;
import com.carlos.vaultserver.automation.ModuleRegistry;
import com.carlos.vaultserver.automation.ModuleResult;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 模块化整轮执行器：与旧 RoundRunner 输出完全同键型（log:/st:/summary:），
 * 但执行顺序由 ModuleRegistry 拓扑排序决定，单任务模式只跑目标动作。
 *
 * <p>切换：SessionKeeper 调本类；设置环境变量 VAULT_LEGACY_ROUND=1 可回退旧 RoundRunner。
 */
public final class ModularRoundRunner {

    private static volatile ModuleRegistry REGISTRY;

    private ModularRoundRunner() {
    }

    private static ModuleRegistry registry() {
        ModuleRegistry r = REGISTRY;
        if (r == null) {
            synchronized (ModularRoundRunner.class) {
                r = REGISTRY;
                if (r == null) {
                    r = Modules.registry();
                    r.validate();
                    REGISTRY = r;
                }
            }
        }
        return r;
    }

    public static String runRound(WjdrEngine engine, VaultStore.Account acc, long sid,
                                  StateStore store, Map<String, JSONObject> params,
                                  String fnOnly, String trigger) {
        String fpid = acc.fpid;
        TaskContext tc = new TaskContext(engine, fpid, sid, store, params, trigger);
        ModuleRegistry reg = registry();

        Map<String, Boolean> roleConfig = acc.roleConfig.get(String.valueOf(sid));
        AutomationConfig config = AutomationConfig.fromLegacy(
                reg.modules().values(), roleConfig, params);
        AutomationContext ctx = new AutomationContext(tc, config.module("legacy"),
                AutomationServices.defaults());

        List<ModuleResult> results = new ArrayList<>();
        if (fnOnly != null && !fnOnly.isEmpty()) {
            results.add(reg.runAction(fnOnly, ctx, config));
        } else {
            results.addAll(reg.runAll(ctx, config));
        }
        // 会话已断：后续动作不可能成功（ModuleRegistry.runAll 已截断后续模块），
        // 跳过“待迁移”补记、采集收尾，并且不消耗当天整轮（不写 srv:lastrun）。
        boolean sessionClosed = hasSessionClosed(results);
        if (!sessionClosed && fnOnly == null && roleConfig != null) {
            // 配置开启但模块化目录未收录的动作：如实记待迁移，不静默丢失。
            for (Map.Entry<String, Boolean> e : roleConfig.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) continue;
                if (reg.moduleOfAction(e.getKey()) != null) continue;
                TaskOutcome r = new TaskOutcome(e.getKey(), e.getKey(),
                        TaskOutcome.Category.SKIPPED, "待迁移到模块化目录");
                RoundRunner.applyResult(store, fpid, sid, e.getKey(), r, trigger);
            }
        }

        int success = 0, claimed = 0, skipped = 0, abnormal = 0;
        StringBuilder summary = new StringBuilder();
        for (ModuleResult mr : results) {
            for (ModuleResult.Entry e : mr.entries) {
                TaskOutcome o = e.outcome;
                RoundRunner.applyResult(store, fpid, sid, e.actionId, o, trigger);
                if (o.category == TaskOutcome.Category.CLAIMED) claimed++;
                if (o.category == TaskOutcome.Category.SKIPPED) skipped++;
                if (o.category == TaskOutcome.Category.DEGRADED
                        || o.category == TaskOutcome.Category.ERROR) abnormal++;
                if (o.successLike()) success++;
                if (summary.length() > 0) summary.append('\n');
                summary.append(o.toString());
                if (o.category == TaskOutcome.Category.SESSION_CLOSED) break;
            }
        }
        if (!sessionClosed) {
            try {
                CaptureTasks.captureAll(engine, store, fpid, sid);
            } catch (Throwable ignored) {
            }
        }
        String head = "服务器整轮 · " + com.carlos.vaultserver.Beijing.fmt("MM-dd HH:mm")
                .format(new Date()) + " · 成功" + success + "(领取" + claimed
                + " 跳过" + skipped + ")" + (abnormal > 0 ? " 异常" + abnormal : "")
                + (sessionClosed ? " 会话中断" : "");
        String text = head + "\n" + summary;
        store.putString("summary:" + fpid, text);
        // 断线轮次不消耗当天整轮：允许调度器下一 tick 重试（st=1 可重试语义）。
        if ((fnOnly == null || fnOnly.isEmpty()) && !sessionClosed) {
            store.putLong("srv:lastrun:" + fpid, System.currentTimeMillis());
        }
        store.flush();
        FileLog.info("模块化整轮完成 fpid={} sid={}：{}", fpid, String.valueOf(sid), head);
        return text;
    }

    private static boolean hasSessionClosed(List<ModuleResult> results) {
        for (ModuleResult r : results) {
            for (ModuleResult.Entry e : r.entries) {
                if (e.outcome != null
                        && e.outcome.category == TaskOutcome.Category.SESSION_CLOSED) {
                    return true;
                }
            }
        }
        return false;
    }
}
