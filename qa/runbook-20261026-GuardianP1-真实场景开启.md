# Runbook：Guardian P1「驻防对等加速」真实场景开启清单（2026-10-26，更新）

> 适用：生产 103.236.96.82（`vault-server`）。P1 已上线但**默认全关**；真实受攻时按本清单开启。
> 结论先说：**盟友/目标/坐标不需要配置**——由游戏推送自动解析（见 §1），开启只需服务端 env。

## 1. 目标信息是自动的（无需配置）

| 信息 | 来源（自动） |
|---|---|
| 被攻击的盟友/目标 uid | 联盟态势 push（`notify_world_alliance_situation_*`）与世界观测 push 中敌方行军的 `targetUid` |
| 目标坐标 | 同一 push 的 endpoint（`t.x/t.y`）→ 缺省回退**盟友坐标表**（`notify_alliance_member_pos_info/update`）→ 都没有才 `skip_no_point` |
| 敌方兵力 | push 内兵力字段（`troopsKnown/troops`）；未知时若配置了 `troops_min` 则保守不发射 |
| 对等加速（P1） | 只用"我方在途驻防行军 + 敌方同一行军的 endtime 前移"，**也不需要目标配置** |

代码口径：`ServerGuardianEngine.decide` 中 `ally_uid/target_uid/target_point` 均为 **可选过滤器**（0=不过滤，任意盟友被攻击都守）；`resolveTargetPoint` 优先用 push endpoint，其次盟友坐标表。

## 2. 真实开启（只加 env + 重启，1 分钟）

编辑 `/etc/vault-server.env`（systemd EnvironmentFile）：
```bash
# 实盘开启守护（无需 App 参数；目标自动解析）
VAULT_GUARDIAN_ENABLED=1          # 总开关
VAULT_GUARDIAN_DRY_RUN=0          # 0=真实出征（1=只记录不发射）
VAULT_GUARDIAN_TROOPS_MIN=0       # 敌方兵力下限，0=不限；想只守大军来袭可设如 50000
VAULT_GUARDIAN_SPEEDUP=1          # P1 对等加速（敌加速→同步加速保持先到）
VAULT_GUARDIAN_DIAMOND=0          # 道具不足是否花钻石买（默认 0；开了才真花钻石）
# 可选：diamond_max_per_march 默认 3、speedup_margin_s 默认 2（roleParams 里配，见 §4）
systemctl restart vault-server
```
启动日志确认：
```
守护盟友 P0 钩子已挂载 <fpid>:<sid>（enabled=true dry_run=false … troops_min=0 speedup=true diamond_speedup=false diamond_max=3 speedup_margin=2s）
```
> env 每 10s 热载（`CONFIG_TTL_MILLIS`），但 env 变量本身需重启进程才生效；`enabled/dry_run` 也可由 roleParams 控制（App 有入口时优先 App）。

### 编队
- 默认使用 App 同步的编队库（`formation_library`）按 `formation` 解析；
- 未配编队时走 `squad_index=1` 无英雄的 A-2 变体（1 兵）——能驻防但战力弱，**建议在 App 编队管理里配好主编队**，服务端自动读取。

## 3. 观察 / 回滚

- 观察：`guardian_log`/ledger 事件
  - 守护：`launch/skip_*/recall/…`
  - 加速：`speedup`（每次加速：itemid/前后剩余/库存/latencyMs）、`speedup_skip`（已先到/不可为）、`speedup_no_item`（2827）、`speedup_diamond_buy*`（钻石路径）
  - 指标：我方先至是否持续 ≥ `speedup_margin_s`（默认 2s）；`latencyMs`（推送→发请求）G2 目标 p95 ≤800ms；我方是否持续早于敌到达。
- 回滚：`VAULT_GUARDIAN_ENABLED=0`（或 `VAULT_GUARDIAN_SPEEDUP=0`）后重启即恢复原状；jar 回滚用 `backup/vault-server-all.jar.bak-20261026-0125`。

## 4. 可选精细参数（roleParams `guardian`，App 有入口时优先）

| 键 | 默认 | 说明 |
|---|---|---|
| `sub.enabled` / `sub.dry_run` | false / true | 与 env 等价，roleParams 优先于默认；env 覆盖优先于 roleParams |
| `strategy.troops_min` | 0 | 敌方兵力下限（0=不限）；未知兵力时保守不发射 |
| `strategy.ally_uid` / `target_uid` / `target_point` | 0 | **可选过滤器**；0=不过滤（推荐留 0，自动守所有盟友） |
| `sub.speedup_enabled` | false | 对等加速（env 可覆盖） |
| `sub.diamond_speedup_enabled` / `strategy.diamond_max_per_march` | false / 3 | 钻石购买开关与上限（真花钻石） |
| `strategy.speedup_margin_s` / `strategy.max_speedups_per_march` | 2 / 0 | 先至余量秒 / 单行军加速发数上限（0=不限） |
| `strategy.formation{squad_index,hero_ids}` | — | 缺省用 App 编队库 |

## 5. 当前生产状态（2026-10-26 03:15，已实机验证 env 开启路径）

- 生产 jar sha256 `9860f3792f1a99770ff7a02a5a4006562a68d8816661fb60cbb8d9f3d851a1d1`（master `5ebd767`：v4.2.11 + 战时修复 + 空同步保护 + token 回取 + P1/对等加速 + env 实盘开关；全部默认关）。
- **env 实盘路径已实机验证**：临时加 `VAULT_GUARDIAN_ENABLED=1 + VAULT_GUARDIAN_DRY_RUN=1` → 钩子日志 `enabled=true dry_run=true`；撤销后恢复 `enabled=false dry_run=true`（当前默认关）。
- 账号：1 个测试号 `48460403`（已在线）；真实账号快照在 `backup/vault.json.before-restore-20261025-064413`、`data/vault.json.stopbad-20261024-174527`。
- master 已合并（`5ebd767`），**未 push**；仓库 merge 钩子的自动发版流程仍因临时 worktree 无 gradlew 中止（v4.2.11 由另一会话正式发版，版本文件已是 4.2.11）。
