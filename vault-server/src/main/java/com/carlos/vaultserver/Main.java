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

        VaultHttpApi api = new VaultHttpApi(config, store, keeper, stateStore, scheduler, war);
        api.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            FileLog.info("vault-server 停止");
            api.stop();
            scheduler.stop();
            keeper.shutdownAll();
            stateStore.flush();
        }));

        // 主线程驻留（daemon 线程不自持）
        Thread.currentThread().join();
    }

    private Main() {
    }
}
