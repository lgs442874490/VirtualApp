package com.carlos.vaultserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * vault-server 入口：环境变量配置（VAULT_LISTEN / VAULT_DATA_DIR / VAULT_SECRET /
 * VAULT_SOCKS / VAULT_TAKEOVER_POLICY），启动 HTTP API + 会话管理器。
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        VaultConfig config = VaultConfig.fromEnv();
        Files.createDirectories(config.dataDir);
        FileLog.setup(config.dataDir.resolve("logs"));
        FileLog.info("vault-server 启动：listen={} dataDir={} 出口代理={} 顶号策略={}",
                config.listen, config.dataDir,
                config.socksProxy == null ? "直连" : String.valueOf(config.socksProxy),
                config.takeoverPolicy);

        // 真机监控（2026-10-25）：任务级协议/会话事件环形缓冲 + JSONL 落盘（默认 off，VAULT_MONITOR=on 开启）。
        // 只做观测，不改任务判定；App「工具箱 → 真机监控」经 POST /v1/vault/monitor 拉取。
        com.carlos.vaultserver.monitor.MonitorStore monitor =
                com.carlos.vaultserver.monitor.MonitorStore.install(config.dataDir);
        com.carlos.vaultserver.monitor.MonitorStore.pruneOldFiles(monitor.dir(), 7);
        com.carlos.vaultserver.monitor.TaskTrace.install();
        FileLog.info("真机监控：{}（dir={}）", monitor.isEnabled() ? "已启用" : "未启用",
                monitor.dir());

        VaultStore store = new VaultStore(config.dataDir);
        boolean fresh = !Files.isRegularFile(config.dataDir.resolve("vault.json"));
        store.load();
        if (fresh) store.setPolicy(config.takeoverPolicy); // 环境变量仅作首启默认，之后以库内值为准
        FileLog.info("身份库载入：{} 个账号", String.valueOf(store.snapshot().size()));

        StateStore stateStore = new StateStore(config.dataDir);
        stateStore.load();

        // 战争通知（敌人监视 + 企微机器人）：事件环形缓冲 + 推送中枢，接线会话与 API
        WatchEventStore watchEvents = new WatchEventStore(config.dataDir);
        watchEvents.load();
        WarNotify war = new WarNotify(store, watchEvents, new WeComNotifier());

        SessionKeeper keeper = new SessionKeeper(store, config, new ClasspathAssets(), stateStore, war);
        keeper.reconcile();
        // v4.1.8：昵称解析接线（WarNotify 只在自己的 name/wecom 线程回调，绝不占推送线程）
        war.setNameResolver(keeper::resolvePlayerName);

        com.carlos.vaultserver.task.RoundScheduler scheduler =
                new com.carlos.vaultserver.task.RoundScheduler(store, stateStore, keeper);
        scheduler.start();

        // 任务节奏调度：网页版 1:1 语义（每任务独立间隔持续执行），修复“一天只跑一轮、
        // 大量奖励全天漏领”的长期缺陷。回滚：VAULT_TASK_CADENCE=off。
        com.carlos.vaultserver.task.TaskCadenceScheduler cadence =
                new com.carlos.vaultserver.task.TaskCadenceScheduler(store, stateStore, keeper);
        cadence.start();

        VaultHttpApi api = new VaultHttpApi(config, store, keeper, stateStore, scheduler, war);
        api.start();

        // 每日自动化日报（批次5）：北京时间定点（默认 09:00，VAULT_DIGEST_TIME=HH:mm 覆盖）汇总近 24h
        // 自动化结果推企微（复用 vault.json watch.webhookUrl；无 webhook 只落日志 + state srv:digest:{date}）。
        // 只读 StateStore，不发游戏协议；回滚 VAULT_DIGEST=off。
        com.carlos.vaultserver.task.DailyDigest digest =
                new com.carlos.vaultserver.task.DailyDigest(store, stateStore, new WeComNotifier(),
                        () -> {
                            int online = 0;
                            for (SessionControl.SessionStatus s : keeper.status()) {
                                if (com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(s.state)) online++;
                            }
                            return online;
                        });
        digest.start();

        // 自动化健康告警（批次6）：每 5 分钟只读 StateStore 评估 会话离线/节奏停滞/零领取/错误率/
        // 节奏键缺失，命中推企微（同类 6 小时节流）；无 webhook 只落日志；回滚 VAULT_ALERTS=off。
        // 0 游戏协议，只读 log:/srv:cad: 与账号 keep/auto、会话在线态。
        com.carlos.vaultserver.task.HealthMonitor health =
                new com.carlos.vaultserver.task.HealthMonitor(store, stateStore, new WeComNotifier(),
                        keeper::status);
        health.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            FileLog.info("vault-server 停止");
            health.stop();
            digest.stop();
            api.stop();
            cadence.stop();
            scheduler.stop();
            keeper.shutdownAll();
            stateStore.flush();
            com.carlos.vaultserver.monitor.MonitorStore.shutdown();
        }));

        // 主线程驻留（daemon 线程不自持）
        Thread.currentThread().join();
    }

    private Main() {
    }
}
