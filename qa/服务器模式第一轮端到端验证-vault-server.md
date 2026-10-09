# vault-server 服务器模式 · 第一轮端到端验证（最小闭环）

> 日期：2026-10-08 晚 · 环境：Mac 本地 vault-server（127.0.0.1:8790，fat jar）+
> 本地 license-server（127.0.0.1:8787，测试码 RB5N-CUPU-NTJG-QV4R 激活）+
> 模拟器 emulator-5554（diudiu2，API34）+ 开发账号 80971192（active 角色 913）。
> 背景：架构改造第一轮——自动化大脑迁服务器（静态住宅 IP 出口可配置，本次直连验证），
> App 做身份库 + token 补给站 + 分身游玩。

## 一、改造内容（本轮代码）

| 层 | 内容 |
|---|---|
| wjdr 包（共用） | 平台中立化：WjdrLog 可插拔 sink、WjdrEngine 去 Context（AssetSource 注入 + 顶号策略 supplier + 不透明 appContext）、GameProtocolClient 支持 SOCKS 出口、新增 beginYield 主动让位 |
| vault-server（新） | 纯 JVM 工程（Java 17 单 fat jar，约 434KB 含 .spb）；VaultStore 身份库（vault.json 原子写+bak，保留服务器学到指纹）；SessionKeeper 每账号单会话（activeSid，1s 三态扫描：offline→登录/online→心跳/recovering→退避重连/takeover→探测）；HTTP API（sync/token/status/policy + healthz，HMAC-SHA256 签名 + nonce 防重放，独立 VAULT_SECRET）；SOCKS5 全局+单账号出口配置；按天滚动文件日志（token 不落日志） |
| App | VaultSync 客户端（签名/信封照抄 LicenseApi 模式，vault_endpoint.txt 端点覆盖）；「我的 → ☁️ 服务器模式」开关；RoleSessionKeeper.syncOnline 服务器模式分支（停本地会话+去抖全量同步+拉状态）；AccountStore 写入钩子（token 更新/导入推送）；VaultEngine.launchEntry 进游戏前 yield 让位 30min；kickFullSync/autoSyncAccount 服务器分支；hero 徽标/keepStatusText/互助卡渲染服务器回传态 |

## 二、验证记录（全部通过）

1. **单元测试**：19/19 通过（签名规范化含中文/控制字符/长整型、验签错 secret/错 OP/重放/时间窗、
   身份库合并保留服务器指纹、API 全链路含假 keeper）。
2. **激活链路**：本地 license-server + 端点覆盖 → 激活成功（bind rowid=4）。
   ⚠️ 踩坑：端点覆盖文件须在 App 进程启动**前**写入（LicenseApi 构造时缓存基址）；
   另 BSD strings 不抽多字节字符，验证 APK 内容要用字节级扫描（classes11.dex）。
3. **身份库上传**：开服务器模式 → sync 上传 1 条（keep=0 时正确不建会话）；
   发现并修复缺口：keep 开关变化原先不同步 → syncOnline 服务器分支补去抖全量同步。
4. **服务器常驻**：日常页开「24h 常驻在线」→ 去抖同步 → 服务器建会话 →
   `登录成功 fpid=80971192 sid=913 uid=20778222`（19:56:33）→ 心跳
   `session alive ... heartbeats=20/100`。
5. **指纹**：vault.json 三角色指纹（888/913/902）与 App 端 AccountStore 完全一致（无漂移）；
   activeSid=913 正确生效。
6. **让位/回连**：policy yield（1 分钟版）→ state=takeover（lastFailure="App 端进游戏，服务器让位…"）
   → 60s 后探测回连 → state=online。
7. **App 状态回显**：重启 App（kickFullSync 服务器分支 syncAll+refreshStatus）→
   vault_server.xml status_cache state=online → 今日页 hero 徽标 **● 日常在线**、
   互助卡 **服务器常驻在线 · 互助自动响应中**（截图 vaultsrv-今日页-服务器在线.png）。
8. **本地回归（关服务器模式）**：本地 SessionKeeper 正常接管（竞速登录 913/888、
   顶号策略、退避重连全部工作）——M1 平台中立化改造无行为回归。
9. **互踢语义确认**：本地与服务器的会话竞争按预期各自退避（human_first 30min 探测），
   证明"服务器模式下必须停本地常驻"的分支是必要的（两模式切换由开关自动处理）。

## 三、现场还原

- 模拟器：keep=false（本地会话已全部 offline）、服务器模式与测试票据/端点覆盖已清除
  （license_state.xml / vault_server.xml / license_endpoint.txt / vault_endpoint.txt 删除）、App force-stop。
- Mac：vault-server 与 license-server 进程已停；测试库 /tmp/vault-qa、/tmp/vault_license.db。

## 四、遗留与下一步

1. **今日页数据采集**（syncOnly 链路移植到服务器、daily_state 回传 App）→ Phase 2。
2. **互助秒级响应 / 50 项任务执行器**迁移 → Phase 2/3（本轮互助卡只显示会话态）。
3. **多角色竞速在线**：服务器端 RTT 更稳，适合后续在 SessionKeeper 上实现亚秒轮换呈现。
4. **部署**：deploy/ 已备 systemd 单元 + Caddy 路径片段（/v1/vault/* → 127.0.0.1:8790）+ env 模板；
   VPS 需装 openjdk-17-jre-headless；静态住宅 IP 买入后填 VAULT_SOCKS 即生效（无需改代码）。
5. **真机验证**：本轮仅模拟器；建议真机（iQOO Z9）走一遍开关切换 + 进游戏让位实测。
6. 已知小坑：App 端状态缓存刷新依赖 syncOnline/kickFullSync 触发点，服务器登录后
   App 侧最长可能滞后一次触发周期才显示在线（可后续加定时轮询）。
