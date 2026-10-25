# Runbook：Guardian P1「驻防对等加速」真实场景开启清单（2026-10-26）

> 适用：生产 103.236.96.82（`vault-server`），P1 已随 jar `0cd91877…` 上线但目前**默认全关**。
> 原则：不造测试场景；真实受攻时按本清单开启，先道具（25% 优先）、钻石最后。
> 相关设计/验证：`design/守护盟友-服务端化设计与分期-20261023.md`（P1 G2）、`qa/验证报告-20261025-GuardianP1-对等加速.md`。

## 0. 前置认知

- P1 只在「敌方同一进攻行军 **endtime 前移（加速/压缩）** 且我方对该目标**已有在途驻防**」时触发；因此 **dry_run 验不到加速链**（dry_run 不发驻防 → 无在途驻防）。
- 默认值（关）：`speedup_enabled=false`、`diamond_speedup_enabled=false`；`diamond_max_per_march=3`、`speedup_margin_s=2`、`max_speedups_per_march=0(不限)`。
- 关闭状态下：零请求、零出征、零钻石，对现有功能无影响。

## 1. 开启前必须准备（用户提供）

| 项 | roleParams 键 | 说明/示例 |
|---|---|---|
| 盟友 uid | `guardian.strategy.ally_uid` | 要保护的盟友 uid（威胁板按该盟友过滤） |
| 目标城 | `guardian.strategy.target_uid` 或 `strategy.target_point{x,y}` | 驻防目的地 |
| 兵力档位 | `guardian.strategy.troops_min` | 敌方来袭兵力 ≥ 该值才出征（0=不限） |
| 编队 | `guardian.formation{squad_index,hero_ids[]}` 或 App 编队库 | 缺省=槽位 1、无英雄（A-2 变体），建议配好 |
| 总开关/演练 | `guardian.sub.enabled=true`、`guardian.sub.dry_run=false` | 真实出征必须 dry_run=false |
| 其它 | `guardian.sub.recall`（默认 true）、`d_sec`（出发提前量，默认 38s） | 按需 |

## 2. 开启顺序（建议）

1. **道具加速灰度**（钻石关）：
   ```bash
   # /etc/vault-server.env 追加（灰度开关，App 参数缺失时可用；不影响默认）
   VAULT_GUARDIAN_SPEEDUP=1
   VAULT_GUARDIAN_DIAMOND=0
   systemctl restart vault-server
   ```
   同时确保 roleParams `guardian.enabled=true, dry_run=false` + 第 1 节目标配置已生效。
2. 观察 1–2 场真实受攻：
   - `guardian_log`/ledger：`speedup`（每次加速，含 itemid/前后剩余/库存/latencyMs）、`speedup_skip`（已先到/不可为）、`speedup_no_item`（2827）；
   - 先至是否持续 ≥ `speedup_margin_s`（默认 2s）；`latencyMs`（推送→请求发出）是否满足 G2 p95 ≤800ms；
   - 我方行军是否持续先于敌到达。
3. **钻石路径**（确需时再开）：`VAULT_GUARDIAN_DIAMOND=1`，先在单一目标小范围观察 `speedup_diamond_buy`/`speedup_diamond_buy_failed` 与钻石实际扣减；`diamond_max_per_march` 默认 3，可按需调（0=不限，慎用）。
4. 关闭：去掉 env 或设 `VAULT_GUARDIAN_SPEEDUP=0` + 重启；或把 roleParams `speedup_enabled=false`。

## 3. 当前限制（开启前注意）

1. **roleParams 由 App 全量同步下发**：App 端目前没有 `speedup_enabled`/`diamond_speedup_enabled` 等新键入口，且没有 guardian 目标配置入口时，服务端手工注入的 roleParams **会在下一次 App 同步时被覆盖**。
   - 现状可用 env 灰度开关覆盖 `speedup_enabled`/`diamond_speedup_enabled`（本清单第 2 节）；
   - 但 `ally_uid/target_uid/target_point/troops_min` **尚无 env 覆盖**——真实开启前建议补这组 env（约 0.2 人日，待确认），否则需保证开启期间 App 不做全量同步（不现实）。
2. App 的 guardian UI 受 FeatureGate 隐藏（`FeatureGate.GUARDIAN implemented=false`）；若已有内测入口可直接在 App 配置，优先用 App 下发（最稳）。
3. 加速链在 guardian 单线程串行：单发加速 4s、购买 8s 超时 × 最多 6 轮，极端场景会短暂占用同角色发射/撤防排队；观察 latency，必要时拆第二执行器。

## 4. 验证/回滚

- 验证：启动日志 `守护盟友 P0 钩子已挂载 … enabled=true dry_run=false … speedup=true diamond_speedup=false diamond_max=3 speedup_margin=2s`；ledger/`guardian_log` 事件流；`/v1/vault/status` 的 warState/角色运行态。
- 回滚：`VAULT_GUARDIAN_SPEEDUP=0`（或去掉）+ 重启即回到"只驻防不加速"；如需更彻底，roleParams `speedup_enabled=false`。
- 无数据库结构变更；jar 回滚用 `backup/vault-server-all.jar.bak-20261026-0125`。

## 5. 当前生产状态（2026-10-26 01:40）

- 仅 1 个账号 `48460403`（label=测试账号，已在线）；真实账号快照：`backup/vault.json.before-restore-20261025-064413`、`data/vault.json.stopbad-20261024-174527`。
- `guardian` roleParams 当前不存在 → 守护/加速均关；P1 已上线但零行为。
