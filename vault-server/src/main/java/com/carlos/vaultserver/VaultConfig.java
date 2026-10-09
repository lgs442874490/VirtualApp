package com.carlos.vaultserver;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 运行配置（环境变量 &gt; 默认值；风格对齐 license-server 的 config.go）。 */
public final class VaultConfig {

    /** API 监听地址。VAULT_LISTEN，默认 127.0.0.1:8790（由 Caddy 反代对外）。 */
    public final String listen;
    /** 数据目录（vault.json / logs）。VAULT_DATA_DIR，默认 ./data。 */
    public final Path dataDir;
    /** API 签名密钥（与 App 端 VaultSync 内置值一致）。VAULT_SECRET。 */
    public final String secret;
    /** 全局 SOCKS5 出口（静态住宅 IP）。VAULT_SOCKS=host:port，空 = 直连。 */
    public final InetSocketAddress socksProxy;
    /** 默认顶号策略：human_first / grab_back / yield_only。 */
    public final String takeoverPolicy;
    /** 签名时间窗（±毫秒）。 */
    public final long signWindowMillis = 5 * 60_000L;

    VaultConfig(String listen, Path dataDir, String secret,
                InetSocketAddress socksProxy, String takeoverPolicy) {
        this.listen = listen;
        this.dataDir = dataDir;
        this.secret = secret;
        this.socksProxy = socksProxy;
        this.takeoverPolicy = takeoverPolicy;
    }

    public static VaultConfig fromEnv() {
        String listen = env("VAULT_LISTEN", "127.0.0.1:8790");
        Path dataDir = Paths.get(env("VAULT_DATA_DIR", "data"));
        String secret = env("VAULT_SECRET", "");
        if (secret.isEmpty()) {
            secret = "vault-dev-secret-only-for-local";
            System.err.println("警告：未设置 VAULT_SECRET，使用开发默认密钥（生产必须显式配置）");
        }
        InetSocketAddress proxy = null;
        String socks = env("VAULT_SOCKS", "");
        if (!socks.isEmpty()) {
            String[] hp = socks.split(":");
            proxy = new InetSocketAddress(hp[0], Integer.parseInt(hp[1]));
        }
        String policy = env("VAULT_TAKEOVER_POLICY", "human_first");
        return new VaultConfig(listen, dataDir, secret, proxy, policy);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? def : v;
    }
}
