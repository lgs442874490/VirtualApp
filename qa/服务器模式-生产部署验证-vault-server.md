# 服务器模式 · 生产部署验证（vault-server → 103.236.96.82）

日期：2026-10-08 ｜ 版本：App v4.0.0-服务器模式（build 23400）｜ 环境：Mac 模拟器 ↔ 生产 VPS

## 结论

**生产端到端全通。** App 直连生产端点 `http://103.236.96.82:64868`，开发号 80971192
在 VPS 上登录在线（uid=295534397），手动触发整轮 50 项任务跑完，状态 141 键回写 App
今日页正常渲染。生产服务保持运行，该号 keep=1 留作风控观察。

## 生产环境落位

| 项 | 值 |
|---|---|
| VPS | 103.236.96.82（陕西电信 NAT VPS，SSH/HTTP 共用 64868） |
| 服务 | systemd `vault-server.service`，User=vault，jar=/opt/vault-server/vault-server-all.jar |
| 监听 | 127.0.0.1:8790（仅本机） |
| 入口 | 上游网关 64868 协议复用 → 机内 nginx 8081 → `/v1/vault/*`、`/vault-healthz` 反代 8790 |
| 数据 | /opt/vault-server/data/{vault.json,state.json,logs/} |
| 出口 | 直连（VAULT_SOCKS 未配，游戏服 43.144.130.216:30101 可达已验证） |

## 入口链路勘察过程（这台 VPS 的特殊性）

1. 域名 888syd.xyz 未备案：80/8081 返回运营商拦截页（根本没到 nginx），8443 TLS 被 reset。
2. 随机高端口 39870/39871 映射不通 → NAT VPS 端口白名单，仅面板声明端口可用。
3. **发现**：上游网关在 64868 上做 SSH/HTTP 协议复用——HTTP 请求 64868 会落到机内 nginx 8081。
   验证：`curl -H 'Host: cs.888syd.xyz' http://103.236.96.82:64868/vault-healthz` 经 nginx access
   log 确认命中。→ IP 直连 + 64868 即为可用 HTTP 入口，无需域名。
4. 用户选择方案 B（IP 明文端点）而非提供新加坡合规机器。测试用 39870/39871 监听已撤除。

## 端到端验证记录（模拟器，仅覆盖 license 端点为本地，vault 端点用生产默认）

1. 改 `VaultSync.DEFAULT_BASE_URL = "http://103.236.96.82:64868"`，出 debug 包 12,338,966 B。
2. App 仅写 license_endpoint.txt（本地授权）→ 安装 → 我的页开启「服务器模式」。
3. 服务器日志：`建会话 fpid=80971192` → `身份库同步 keep=1` → `登录成功 fpid=80971192
   sid=913 uid=295534397`（陕西电信 IP 出口，游戏服接受）。
4. FunctionActivity 触发 `policy action=run` → 服务器日志 `整轮完成 fpid=80971192 sid=913：
   成功46(领取7) 异常4`（约 18 秒），state.json 141 键。
5. App fetchState：logcat `采集状态回写 141 键`；prefs 实测 `summary:80971192` =
   `服务器整轮 · 10-08 16:30 · 成功46(领取7) 异常4`，`act:80971192:913` = 125（活跃度），
   vault_server.xml 状态 online。
6. 截图：qa/vaultsrv-生产-今日页.png（2026-10-08T16-31-26Z）。

## 安全取舍（方案 B 已知并接受）

- 明文 HTTP：token/账号对链路可见；HMAC 签名防篡改/防重放，不防窃听。
- 端点与 SSH 共用 64868：依赖上游网关复用行为，属非常规但实测稳定。
- 后续迁正规 HTTPS 入口时：改 DEFAULT_BASE_URL + 前置 TLS，协议层零改动。
- **VPS root 密码曾在聊天中明文出现，建议面板「重置密码」。**

## 现场还原

- 本地 license-server 后台任务已停；模拟器已删 license_endpoint.txt / vault_endpoint.txt /
  license_state.xml / vault_server.xml / daily_state.xml / daily_logs.xml 并 force-stop。
- 生产 vault-server 保持运行（dev 号 80971192 keep=1 常驻在线，兼作风控观察哨）。

## 交付物

- `无尽号库-v4.0.0-服务器模式版.apk`（release，10,055,855 B，默认连生产端点）
- vault-server/README.md 部署段落已改为真实链路（网关复用/IP 端点/安全取舍）。
