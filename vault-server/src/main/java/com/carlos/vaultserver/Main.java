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
        // 2026-10-30 批次 A：状态保留（st:<day> 默认保留 7 天；可用 VAULT_STATE_RETENTION=off 关闭）
        StateRetention.start(stateStore);

        // 战争通知（敌人监视 + 企微机器人）：事件环形缓冲 + 推送中枢，接线会话与 API
        WatchEventStore watchEvents = new WatchEventStore(config.dataDir);
        watchEvents.load();
        WarNotify war = new WarNotify(store, watchEvents, new WeComNotifier());

        SessionKeeper keeper = new SessionKeeper(store, config, new ClasspathAssets(), stateStore, war);
        keeper.reconcile();
        // v4.1.8：昵称解析接线（WarNotify 只在自己的 name/wecom 线程回调，绝不占推送线程）
        war.setNameResolver(keeper::resolvePlayerName);

        // 观察点（2026-10-27 MVP）：地图范围查询轮询——监视敌方 80 级炉子 / 保护友盟聚集地 / 迁城定位。
        // 只发告警（企业微信），不发日报/汇总；默认关闭，需 VAULT_WATCHPOINT=on 且 data/watchpoint.json enabled=true。
        com.carlos.vaultserver.watchpoint.WatchPointStore watchPointStore =
                new com.carlos.vaultserver.watchpoint.WatchPointStore(config.dataDir);
        com.carlos.vaultserver.watchpoint.WatchPointEngine watchPoint =
                new com.carlos.vaultserver.watchpoint.WatchPointEngine(
                        keeper, watchPointStore, store, new WeComNotifier());
        watchPoint.start();
        // 2026-10-31：把观察点实例共享给守护盟友（按坐标反查城 uid/联盟、保护矩形判定）。
        com.carlos.vaultserver.watchpoint.PlayerDirectory.install(config.dataDir);
        com.carlos.vaultserver.watchpoint.WatchPointEngine.install(watchPoint);

        com.carlos.vaultserver.task.RoundScheduler scheduler =
                new com.carlos.vaultserver.task.RoundScheduler(store, stateStore, keeper);
        scheduler.start();

        // P7 战争态全局节拍（2026-10-25 修复）：对全部角色实例的 WARTIME 做退出判定，
        // 不再只依赖 active 写入方角色的会话 tick（否则副角色卡死的战时态永不解除，
        // 账号级战时让位会无限期拦截节奏派单）。战时上限：VAULT_WARTIME_MAX_MIN（默认 90，0=不限）。
        ServerWarState.startTicker();

        // 任务节奏调度：网页版 1:1 语义（每任务独立间隔持续执行），修复“一天只跑一轮、
        // 大量奖励全天漏领”的长期缺陷。回滚：VAULT_TASK_CADENCE=off。
        com.carlos.vaultserver.task.TaskCadenceScheduler cadence =
                new com.carlos.vaultserver.task.TaskCadenceScheduler(store, stateStore, keeper);
        cadence.start();

        // 批次 B：观察点配置端点（get/set/enable）——写 data/watchpoint.json，引擎按 mtime 5s 热载
        // 移民（藏宝阁-自由移民，2026-11-06）：任务持久化 + 角色级执行引擎 + 1s 调度。
        // 到点抢位在角色会话线程内跑；serverClock 复用 WjdrEngine.serverClock()（既有实现，勿重写）。
        com.carlos.vaultserver.migrant.MigrantJobStore migrantJobs =
                new com.carlos.vaultserver.migrant.MigrantJobStore(config.dataDir);
        migrantJobs.load();
        com.carlos.vaultserver.migrant.MigrantEngine migrantEngine =
                new com.carlos.vaultserver.migrant.MigrantEngine(keeper, store, migrantJobs, stateStore);
        com.carlos.vaultserver.migrant.MigrantScheduler migrantScheduler =
                new com.carlos.vaultserver.migrant.MigrantScheduler(keeper, store, migrantJobs, migrantEngine);
        migrantScheduler.start();
        FileLog.info("移民模块：已启动（任务 {} 个）", String.valueOf(migrantJobs.count()));

        VaultHttpApi api = new VaultHttpApi(config, store, keeper, stateStore, scheduler, war,
                watchPointStore);
        api.attachMigrant(new com.carlos.vaultserver.migrant.MigrantApi(
                keeper, store, stateStore, migrantJobs, migrantEngine));
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
            watchPoint.stop();
            digest.stop();
            api.stop();
            cadence.stop();
            // 移民：先停调度（不再发起新动作），再落盘任务，避免关停时丢 attempts
            migrantScheduler.stop();
            migrantJobs.flush();
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
