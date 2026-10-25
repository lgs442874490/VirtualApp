# 部署记录：vault-server 战时卡死修复（2026-10-25）

- 分支/提交：`modify/wartime-gate-stall-20261025-0238` / 见 `git log`（本记录随后提交）
- 目标主机：**103.236.96.82**（`ssh vault-vps`，root；服务 `vault-server.service`，`/opt/vault-server`）
- 窗口：2026-10-25 03:27（北京）；生产会话已由用户手动停止（keep=false），重启不影响在线任务
- 部署方式：本地 `./gradlew fatJar` → 上传 `/tmp` → 校验 sha256 → 覆盖 `/opt/vault-server/vault-server-all.jar` → `systemctl restart vault-server`

## 产物与备份

| 项 | 值 |
|---|---|
| 新 jar | `vault-server-0.1.0-all.jar`，1,270,378 B，sha256 `3f04e1e55d82102b595a1d5ab359977aeac40d46b66a61a1f38fd2553ecc4d24` |
| 旧 jar | sha256 `45f04f5a6ef0d36e932bd1ffaeb4a7041d17240202520e31d86f9b0e6357bef2` |
| 备份 | `/opt/vault-server/backup/vault-server-all.jar.bak-20261024-1926`（服务器 UTC 时间戳） |

## 验证

| 项 | 结果 |
|---|---|
| `sha256sum /opt/vault-server/vault-server-all.jar` | `3f04e1e5…4d24`（与本地一致） |
| `systemctl is-active vault-server` | `active` |
| `curl 127.0.0.1:8790/healthz` | `{"code":0,"message":"ok"}` |
| 启动日志（journalctl） | `vault-server 启动` / `整轮调度器已启动` / **`战争态全局节拍已启动：30000ms（对全部已注册角色实例判退出/上限，不再只依赖 active 角色会话），战时上限=不限`** / `任务节奏调度已启动：55 项` / `HTTP API 已启动` / `健康告警已启用` |
| 环境变量 | `VAULT_WARTIME_MAX_MIN` 未配置 → 上限关闭（按原有「无侦查无进攻 30min」静默退出） |

## 回滚

```bash
cp /opt/vault-server/backup/vault-server-all.jar.bak-20261024-1926 /opt/vault-server/vault-server-all.jar
systemctl restart vault-server
curl -s http://127.0.0.1:8790/healthz
```

## 部署前后发现（与本部署无关，需用户关注）

- **03:15:17（北京）发生 `身份库同步：0 条（keep=0）`**：App 全量同步空账号列表 → 服务端 `store.replaceAll([])` 清空账号（当前 `vault.json` accounts=0）。
- 该时间早于本次部署（03:27）；旧 jar 时期即已发生，不是本次升级导致。
- 可恢复快照：`/opt/vault-server/data/vault.json.stopbad-20261024-174527`（2 个账号，含 token，savedAt=1792863775750 ≈ 01:42 北京）。
- 风险：若 App 端确实无账号（如卸载重装后首次同步），下次同步会再次清空 → 建议加「空列表保护/需显式确认」再恢复。

---

## 2026-10-25 14:44 合并部署：token 回取 + 战时卡死修复 + 空同步保护

| 项 | 值 |
|---|---|
| 新 jar | `vault-server-0.1.0-all.jar`，1,271,295 B，sha256 `65d41d979ded383a7d676ee081da943826e1d8fb02f301f5de0d6c46528ff366` |
| 覆盖掉的 jar | sha256 `d6df75cb04e1baf9323514bfa429b1b78a0a2a202133e8cb13e3952dec1c0e01`（并发会话 14:07 部署，仅含 token/get，不含战时修复/空保护）→ 已备份 `backup/vault-server-all.jar.bak-20261025-1442` |
| 合并提交 | `6eda58b merge: 合并并发会话的 token 回取接口 + 本分支（战时卡死修复/空同步保护）`；token/get 三文件取自并发会话 worktree `modify/clone-autofill-anr-20261025-0335` 的未提交改动（经用户同意，未改动其 worktree），三方合并无冲突 |
| 门禁 | vault-server 全量单测 **915/915 通过（76 类）**，含 token/get 8 条新用例与空同步保护用例 |
| 启动验证 | `systemctl is-active` active、`/healthz` ok；日志：`身份库载入：2 个账号`、`战争态全局节拍已启动：30000ms…战时上限=不限` |
| 线上功能验证 | ① 空 `accountsJson` 同步 → `emptyRejected=true, serverAccounts=2`，账号未被清空；② `POST /v1/vault/token/get {"fpid":"48460403"}` → `hasToken=true`（token 已脱敏核对）；③ `policy action=war-reset` → `cleared=0` 正常 |
| 账号恢复 | 从 `data/vault.json.stopbad-20261024-174527`（2 账号含 token，01:42 快照）合并 accounts 到当前 vault.json（保留 watch/顶号策略），重启后加载 2 个账号（keep=false，不自动起会话）；恢复前备份 `backup/vault.json.before-restore-20261025-064413` |

### 运维注意
1. **空同步保护已上线**：服务端非空时，App 发送空账号列表会被拒绝（`emptyRejected=true`，不影响 App 现状）；
   真需清空必须显式 `allowEmpty=true`（后续 App「删除全部账号」流程可带上）。
2. **部署协调**：同一时间只允许一个会话部署生产 jar；本次因并发会话各自部署导致一次覆盖（已回滚+合并修复），
   后续部署前先确认。
3. 清理账号/恢复快照前先备份 `vault.json`；`keep=false` 账号不会自动起会话。

---

## 2026-10-26 01:24 部署：Guardian P1 对等加速（默认全关）

| 项 | 值 |
|---|---|
| 合并提交 | `4835538 merge: Guardian P1 驻防对等加速（默认全关）并入生产分支` |
| 新 jar | 1,289,525 B，sha256 `0cd9187714f045c82291f44acbdf94a6cf3a3e2f8b388a27eb1323b8eeddb921` |
| 被替换 jar | sha256 `65d41d97…`（战时修复+空同步保护+token回取）→ 备份 `backup/vault-server-all.jar.bak-20261026-0125` |
| 门禁 | vault-server **957/957 全绿**（合并后：master 896 + 战时 10 + 空保护 1 + token 8 + P1 42） |
| 启动验证 | `active`、`/healthz` ok；`身份库载入：1 个账号`、`战争态全局节拍已启动`、会话 `48460403|888|35371154` 登录成功 |
| P1 默认状态 | 日志回显 `enabled=false dry_run=true … speedup=false diamond_speedup=false diamond_max=3 speedup_margin=2s max_speedups=unlimited` → **零行为变化、零请求** |
| 灰度入口 | env `VAULT_GUARDIAN_SPEEDUP=1` / `VAULT_GUARDIAN_DIAMOND=1`（未设置=以 roleParams 为准）；另需 roleParams `guardian.enabled=true` + 目标配置 |

### 备注（运维现状）
- 生产当前仅 1 个账号：`48460403`（label=测试账号，keep/auto=true，active 888/35371154，已自动上线）。
- 10-25 17:18 一次 App 非空同步（1 个测试账号）覆盖了此前恢复的 2 个真实账号；真实账号快照仍在：
  `backup/vault.json.before-restore-20261025-064413`、`data/vault.json.stopbad-20261024-174527`。
- 灰度未开启：`guardian` roleParams 当前不存在（roleParams 仅 formation/gather/intel），需目标配置（盟友 uid/目标城/坐标/兵力档位）才能实发；P1 对等加速依赖"在途驻防"，因此 dry_run 无法验证加速链，灰度需真发驻防。


---

## 2026-10-26 03:14 部署：Guardian env 实盘开关修复（master 5ebd767）

| 项 | 值 |
|---|---|
| 新 jar | 1,374,025 B，sha256 `9860f3792f1a99770ff7a02a5a4006562a68d8816661fb60cbb8d9f3d851a1d1`（基于 v4.2.11 + 本轮全部修复/P1） |
| 被替换 jar | `ce8f42d8…`（v4.2.11 + P1，不含 env 实盘缺省路径修复）→ 备份 `backup/vault-server-all.jar.bak-envfix-20261026-0315` |
| 修复 | `reloadConfig` 在无 guardian roleParams 时改走 `parseConfig(null)`，使 `VAULT_GUARDIAN_ENABLED/DRY_RUN/TROOPS_MIN` 生效（此前被 `Config.disabled()` 绕过） |
| 门禁 | 合并后 master 全量单测 **1086/1086 绿**（91 类）；App 无改动 |
| 实机验证 | ① 默认（无 env）：钩子 `enabled=false dry_run=true`；② 临时 env `ENABLED=1 DRY_RUN=1`：`enabled=true dry_run=true`；③ 撤销 env 重启：恢复 `enabled=false`；`/healthz` ok |
| 回滚 | `cp backup/vault-server-all.jar.bak-envfix-20261026-0315 … && systemctl restart vault-server` |
