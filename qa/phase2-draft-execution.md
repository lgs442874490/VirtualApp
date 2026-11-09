# Phase 2 方案B · 执行调度与配置按角色 —— 设计草稿

- 基线：master `9d07208`（含 P0 `db7d1a6` + Phase1 `ba8feef`/`1812a76`）；只读分析，未改源码/未动生产。
- 生产只读核对：`ssh -o BatchMode=yes vault-vps`（2026-10-29 02:0x 读取 vault.json / state.json / cad 统计）。
- 现有 Phase1 先例（本设计沿用同一“roleUid>0 才用 uid 键、roleUid=0 旧路径不变”口径）：
  `TaskContext.roleUid`(TaskContext.java:36,55,63)、`modKey`(:130)、`ClaimLedger` 角色键(ClaimLedger.java:23-32)、
  `ServerTasks.allianceScienceKey(fpid,sid,roleUid)`(ServerTasks.java:3681-3685)/`runAllianceDonate`(:3779)、
  `ServerHelpResponder` 角色构造(:25-27,159-176)。

## 0. 目标与结论摘要

1. **调度单元从 fpid 提升为 `RoleRef(fpid,sid,uid)`**：候选/间隔/退避/预算/派单/采集/唤醒/战时/台账/日志全部按角色键；
   账号只剩“跨角色聚合预算”与“App 兼容镜像”两个职责。
2. **不保留“active 写入方”作为执行门控**：Phase2 下每个在线角色都是自身状态的写入方；`activeUid` 降级为
   “App 当前展示角色”（用于旧键镜像与 /state 默认角色），不再决定谁跑任务。
3. **必须解决的现网事实**：888 服两个角色同时在跑（均 desiredOnline=true RUNNING），但 cad/st/log/summary/lastrun
   仍按 fpid 或 fpid:sid 单键（生产 state：`srv:cad:48460403:888:*` 40 个、`st:*:48460403:888:*` 441 个、0 个带 uid）。
4. **配置当前按 sid 键，同服双角色无法区分**：App 侧 `acc:<fpid>:<sid>`、`p:<fpid>:<sid>:<fn>`；同步协议
   `roleConfig`/`roleParams` 也按 sid 存（VaultHttpApi.java:229-244），后一个角色会覆盖前一个。
5. **预算需要重估**：单角色实测量 ≈ 9.64 派单/分钟（见 §3.3）；双角色 ≈19.3/分钟，逼近现默认全局 20/分钟上限。

## 1. 现状盘点（file:line，master 9d07208）

| 层 | 现状 | 证据 | Phase2 缺口 |
|---|---|---|---|
| 会话容器 | 已是 `key=fpid\|sid\|uid` 的多角色容器，`LiveSession` 每角色独立引擎/单线程执行器/inFlight/onlineSince | SessionKeeper.java:31-57,103-121,283-402 | API 仍是“按 fpid 找 active 会话” |
| 会话寻址 | `sessionForAccount(fpid)`：active 角色优先，否则任一会话；`submitRound/executorBusy/sessionSid/sessionOnline/sessionOnlineSince` 全部 fpid 入参 | SessionKeeper.java:2164-2178,2180-2258 | 无 `(fpid,sid,uid)` 寻址入口；fallback 会把任务派给错误角色 |
| 写入方门控 | `isActiveRoleWriter()` 只让 active 角色跑整轮/采集/开盾/守护；兄弟角色仅“占位在线” + Phase1 互助/捐献 | SessionKeeper.java:1123-1137,895-908,974-1019 | 全量任务需放开到所有角色（Phase1 已开小口） |
| 整轮调度 | 按 fpid 30s 节拍：`nextRunAt/manualQueue/supplyFiredAt`、`srv:lastrun:<fpid>`、`srv:supplyfire:<fpid>` | RoundScheduler.java:24,44-46,114-119,121-202 | 整轮/补给/手动需 per-role |
| 整轮执行 | `RoundRunner/ModularRoundRunner.runRound(engine,acc,sid,...)` 建 `TaskContext`（roleUid=0），用 `roleConfig.get(sid)`，收尾 captureAll(fpid,sid) | RoundRunner.java:30-40,93；ModularRoundRunner.java:53-64,90-91,135,157-163 | 无 uid 入参；`summary:<fpid>`、`srv:lastrun:<fpid>` 账号级 |
| 节奏调度 | 候选=账号；`candidateOf(acc)` 用 fpid 级闸门；`pickNextAccount` 按 fpid round-robin；`srv:cad:<fpid>:<sid>:<fn>` / `st:*:<fpid>:<sid>:<fn>` / `srv:cadlast:<fpid>`；全局 20/分、账号级最小 6s | TaskCadenceScheduler.java:241-273,279-343,351-392,406-414,556-558,571-583 | 同 sid 双角色共用 cad/st/last，必然互相覆盖；公平性只在 fpid 维 |
| 派单寻址 | `KeeperFacade.submitRound(fpid,fn,trigger)`，`submitRound` 内 `sessionForAccount` 选会话、`roleParams.get(sid)` | RoundScheduler.java:50-81；SessionKeeper.java:2180-2213 | 需 `submitRound(RoleRef,fn,trigger)` 直接命中角色会话 |
| 采集 | 只在 active 会话每 10 分钟 `captureAll(engine,store,fpid,sid)`；写 `todo:/res:/mod:/rallies:/questprog:` 等 fpid:sid 键 | SessionKeeper.java:937-947；CaptureTasks.java:36-47,213-236,325-357 | 采集需每角色各跑；状态键加 uid + 旧键由 active 镜像 |
| 资产/编队（已 per-uid） | `srv:asset:<fpid>:<sid>:<uid>` 新键 + 仅 active 写旧键；编队 `stateKey(fpid,sid,uid)` + `legacyStateKey` 双写 | SessionKeeper.java:831-851,2497-2536；AssetStateProjector.java:60-65；FormationCatalog.java:321-327 | 作为 Phase2 状态键模板 |
| 推送唤醒 | 每会话 hook → `wakeRouter.onNotify(fpid,sid,proto)` 写 `srv:cad:<fpid>:<sid>:<fn>=0`；节流键 `fpid\|fn`（不含 sid/uid） | SessionKeeper.java:445-460；TaskWakeRouter.java:28-39,80-113,158-165 | 同 sid 双角色唤醒同一 cad 键；需 uid；节流需按角色+账号双层 |
| 战争/让位 | `ServerWarState.forRole(fpid,sid)`/`key(fpid,sid)`；`wartimeYield(fpid)` 账号级；整轮 `roundYieldNote(fpid,sid)` | ServerWarState.java:69,74,456-460,119；ModularRoundRunner.java:70-76 | 战争态键需 uid；让位粒度需决策（单角色/全账号） |
| 配置模型 | `Account.roleConfig/roleParams: sid → …`；sync 解析 `a.roleConfig.put(rsid,cfg)`（同 sid 后写覆盖先写） | VaultStore.java:106-109,283-287,413-414；VaultHttpApi.java:212-244 | 需 uid 维度 + 继承/迁移 |
| App 侧配置 | `DailyConfigStore: acc:<fpid>:<serverid>`；`FnParams: p:<fpid>:<sid>:<fn>`；sync 按 `RoleStore.Role(sid,uid)` 逐角色上传 | DailyConfigStore.java:116-131；FnParams.java:261-262；VaultSync.java:1072-1120；RoleStore.java:57-76 | App 本地存储也按 sid 键，同服双角色配置共用一个槽 |
| App 状态消费 | `/state` 回写：`log:/st:` → daily_logs，其余（mod/res/todo/summary…）→ daily_state，均按 fpid:sid 键 | VaultSync.java:508-545 | Phase2 若改 uid 键，App 需按当前角色读/写或服务端保留旧键镜像 |

## 2. 目标模型：RoleRef 与键规范

```java
// com.carlos.vaultserver.task.RoleRef（新，值对象）
public final class RoleRef {
    public final String fpid; public final long sid; public final long uid;
    public RoleRef(String fpid, long sid, long uid) {...}
    public String key() { return fpid + "|" + sid + "|" + uid; }          // 调度内存键（roleUid=0 → active 占位）
    public String suffix() { return fpid + ":" + sid + ":" + uid; }      // 状态键后缀
    public boolean roleScoped() { return uid > 0L; }                      // false=旧/主控兼容路径
}
```

| 用途 | 现状键 | Phase2 键（roleScoped） | 兼容策略 |
|---|---|---|---|
| 节奏下次到期 | `srv:cad:<fpid>:<sid>:<fn>`(TaskCadenceScheduler:556-558) | `srv:cad:<fpid>:<sid>:<uid>:<fn>` | 双读旧键一轮；新写只写新键 |
| 当日结果状态机 | `st:<date>:<fpid>:<sid>:<fn>`(:571-575) | `st:<date>:<fpid>:<sid>:<uid>:<fn>` | 迁移读旧键一次 |
| 账号/角色最小间隔 | `srv:cadlast:<fpid>`(:316-321,382) | `srv:cadlast:<fpid>:<sid>:<uid>` | 新增；账号级预算独立聚合 |
| 整轮最近执行 | `srv:lastrun:<fpid>`(RoundScheduler:148) | `srv:lastrun:<fpid>:<sid>:<uid>` | App 仍读旧键=active 镜像 |
| 补给补领去重 | `srv:supplyfire:<fpid>`(RoundScheduler:187) | `srv:supplyfire:<fpid>:<sid>:<uid>` | 旧键 active 镜像 |
| 任务日志 | `log:<fpid>:<sid>:<fn>`(RoundRunner:125,223) | `log:<fpid>:<sid>:<uid>:<fn>` | 旧键由 active 写（App 零改动） |
| 整轮摘要 | `summary:<fpid>`(ModularRoundRunner:157) | `summary:<fpid>:<sid>:<uid>` | active 旧键镜像 |
| 采集状态 | `todo:/res:/rallies:/questprog:/questts:/mod:` fpid:sid(CaptureTasks:213-236) | 加 `:<uid>:` 段 | active 旧键镜像（同 asset 模式） |
| 资产/编队 | 已 uid 新键 + legacy 双写 | 不变，作为模板 | 已兼容 |
| 战争态 | `war:<fpid>:<sid>`(ServerWarState:69-74) | `war:<fpid>:<sid>:<uid>` | 旧键=账号聚合视图 |
| 唤醒节流 | `fpid\|fn`(TaskWakeRouter:58-60,158-165) | `fpid\|sid\|uid\|fn` + 账号级总节流 | 新增 |

## 3. 调度改造

### 3.1 KeeperFacade v2（寻址接口，RoundScheduler/TaskCadenceScheduler 共用）

```java
public interface KeeperFacade {
    List<RoleRef> onlineRoles(String fpid);              // 新增：该账号所有在线角色（含 sid/uid）
    boolean submitRound(RoleRef role, String fn, String trigger);   // 替换 fpid 版；直接 sessions.get(sessionKey(role))
    boolean sessionOnline(RoleRef role);                 // = LiveSession.state ONLINE
    long    sessionOnlineSince(RoleRef role);             // = LiveSession.onlineSince（INIT_GRACE 按角色）
    boolean executorBusy(RoleRef role);                  // = LiveSession.inFlight>0
    boolean wartimeYield(RoleRef role);                  // = ServerWarState.war(role)
    boolean ensureSession(RoleRef role);                 // 手动触发/上线
    // 兼容期只读方法（旧 App/旧调用）：fpid 版 default 方法 → 映射 activeRole(acc)
}
```

`SessionKeeper.submitRound(RoleRef)` 内部：`sessions.get(sessionKey(fpid,sid,uid))`，不存在/未 RUNNING/非 ONLINE 直接 false
（**不再 fallback 兄弟角色**，避免任务派错人）；`roleParams` 取 `acc.roleParams(uid)`（§7）。`sessionForAccount` 仅保留给
`/status`、`yield`、`ensureSession` 等账号级兼容 API。

### 3.2 节奏调度候选与公平

```java
static final class RoleCandidate { VaultStore.Account acc; long sid; long uid; String fn; }

List<RoleCandidate> candidates;
for (Account acc : store.snapshot())
  for (long sid : acc.roleSidsOfInterest())                 // 来自 roles/desiredOnline/RoleEntry
    for (long uid : acc.roleUids(sid))
      c = candidateOf(acc, sid, uid, now); if (c!=null) candidates.add(c);

String picked = pickNextRole(keys, cursor);                 // key=fpid|sid|uid，字典序+round-robin（同 :406-414）
dispatch(c);                                                // keeper.submitRound(new RoleRef(...), fn, "节奏")
```

闸门逐角色执行（顺序不变）：`no_token/auto_off/offline(role)/init_grace(role)/no_sid/wartime(role)/window/busy(role)/min_gap(role)/no_task(role)/no_due(role)`。
`enabledTaskIds(acc,sid,uid)` 用 `roleConfig(uid)`（§7）。

### 3.3 预算与最小间隔（现状量化 + 方案）

- 现网实测：2026-10-29 `srv:cad:stats:2026-10-29`：dispatch=1198，统计区间 00:00:01→02:04:16（124.3 min）
  ⇒ **≈9.64 派单/分钟/单角色**；双角色同时跑全任务 ⇒ **≈19.3/分钟**，而 `DEFAULT_GLOBAL_MAX_PER_MINUTE=20`
  （TaskCadenceScheduler:86,244-247,419-423）⇒ 几乎 100% 触顶，会出现“某角色长期排不上”（现有 `min_gap`/`global_budget`
  skip 计数会飙升）。
- 建议默认：`VAULT_CADENCE_GLOBAL_MAX` 提到 **30**（≈15/角色，留 30% 余量）；若保守可维持 20 但增加“**每角色保底**”：
  `floor = max(4, globalMax / onlineRoleCount)`；60s 窗口内某角色派单 < floor 且其在候选中时优先选它（加权 round-robin）。
- `MIN_DISPATCH_GAP_MILLIS=6s`（:68）现为**账号级**（`srv:cadlast:<fpid>`）⇒ 双角色共享 10 次/分；Phase2 改为
  **每角色 6s + 账号聚合上限**（建议 `VAULT_CADENCE_ACCOUNT_MAX` 默认 24/分），即两角色各 12/分、总 24/分；
  保留“账号连续同秒派单”抑制（同角色两单之间再加 2s 抖动）。
- `INIT_GRACE_MILLIS=20s`（:76）按角色使用 `LiveSession.onlineSince`；两个角色登录时间不同，各自独立计时。
- 抖动/退避：`nextIntervalForResult` 的 st 键加 uid；`SKIP_BACKOFF×3/≥180s`、`ERROR_BACKOFF×2/≥120s`（:77-82）不变。
- 预算耗尽时仍**不写 cad 键**（:244-247 语义保持），避免“记账式跳过”造成永久漂移。

### 3.4 整轮 / 补给 / 手动

- 整轮：`srv:lastrun:<fpid>:<sid>:<uid>`；每角色每天各自一轮（窗内随机，互不占位）；`auto`/`windowStart/End` 先维持账号级
  （Phase2.5 再考虑按角色窗口；若需要，`RoleEntry` 加 `windowStart/End`）。
- 补给：`RoundRunner.supplyWakeAt(store,fpid,sid)`(RoundRunner.java:251) → `(store, RoleRef)`；`srv:supplyfire` 按角色。
- 手动触发：`/automation action=run` 增加可选 `sid/uid`：缺省=active 角色（兼容）；`VAULT_PER_ROLE=on` 且未指定时=**所有在线角色各跑一轮**
  （或由 App 传目标角色）；`requestRun(RoleRef, fn)`；`manualQueue` 键改 `RoleRef.key()`。
- `summary:<fpid>` 旧键由 active 角色写（App 今日页不变），同时新键每角色一份供后续 App 版本/看板读取。
- `VAULT_LEGACY_ROUND=1` 分支（SessionKeeper:2207-2213）同样需要 role 参数。

## 4. 采集与状态（per-role）

- 主控会话（active）保持现有时序：开盾/守护 → 采集每 10 分钟 → 互助（SessionKeeper:909-952）。
- 兄弟会话 Phase2 放开为**同样时序**（各自 `nextCaptureAt`、`inFlight`、`onlineSince`）：`captureAll(engine,store,fpid,sid,uid)`。
- 键：`todo:/res:/rallies:/questprog:/questts:/mod:` 加 uid 段；**旧 fpid:sid 键只由 active 角色写**（镜像），
  与 asset（SessionKeeper:839-848）/formation legacy（:2497-2536）完全同款，App v4.2.x 零改动。
- 并发：`captureAll` 会发 `req_*` 请求，两个角色同时采集会造成账号内请求尖峰；建议 `SessionKeeper` 提供
  **账号级采集串行器**（同一 fpid 的 capture 间隔 ≥5s），或 per-role `nextCaptureAt` 加 `uid hash` 偏移（0/5min 错开）。
- 状态 flush：现 `stateStore.flush()` 在采集后（:946）；双角色会双倍 flush，建议改为时间窗合并（500ms）。

## 5. 推送唤醒（TaskWakeRouter）

```java
// SessionKeeper.attachWakeHook: hook 里带 roleUid
long sd = resolveSid(); long uid = roleUid > 0 ? roleUid : wantUid;
wakeRouter.onNotify(fp, sd, uid, proto);

// TaskWakeRouter
int onNotifyAt(String fpid, long sid, long uid, String proto, long now) {
  cadenceKey(fpid,sid,uid,fn) = "srv:cad:" + fpid + ":" + sid + ":" + uid + ":" + fn;   // 0=立即到期
  if (!claimWakeSlot(fpid + "|" + sid + "|" + uid, fn, now)) continue;   // 每角色 10s 节流
  if (!claimAccountWake(fpid, now)) break;                                // 账号级总节流（防双角色唤醒风暴）
}
```

唤醒只是“提前到点”，不增加派单权：最终仍受 §3.3 预算/间隔约束；账号级节流默认沿用 10s（TaskWakeRouter:47-60），
双角色各 10s ⇒ 账号最多 12 次/分唤醒写键，可接受。

## 6. 战争 / 让位 / 顶号

- 战争态：`ServerWarState.warStateKey(fpid,sid,uid)`、`forRole(RoleRef)`；`roundYieldNote/gateNote` 按角色；
  账号级 `wartimeYield` 保留为“聚合视图”（任一角色战时 → 账号日报标注），**不再拦截所有角色**。
- 让位/顶号 API：`SessionKeeper.yield(fpid,probeMillis)` 现寻址 active 会话（SessionKeeper:2261-2269）；Phase2 改为
  `yield(fpid, sid, uid, probeMillis)`，`uid` 缺省时**让位该账号全部在线角色**（安全默认：用户进游戏时服务端整体退让），
  `VAULT_YIELD_SCOPE=active|all` 可切回旧语义。
- human_first 顶号检测是 per-session 的（LiveSession 每会话独立 takeover 探测）；多角色下同一账号可能各自让位/回探，
  互不影响；但“同一 token 登录单角色是否影响兄弟会话”需 Phase2 灰度期用 monitor `session` 事件验证（生产 Phase1 已长期双在线，
  暂未观察到互踢；仍列为风险 R4）。

## 7. 配置与参数按 uid

### 7.1 服务端模型

```java
// VaultStore.Account：新增 uid 维度；旧 map 保留为 sid 默认（不删，旧 App/回滚可用）
Map<String, Map<String, Boolean>> roleConfigByUid;   // "sid:uid" → {fn:bool}
Map<String, Map<String, String>>  roleParamsByUid;  // "sid:uid" → {fn:json}
// 读取优先级：roleConfigByUid[sid:uid] → roleConfig[sid]（旧/默认） → 任务目录默认
```

`VaultHttpApi` 同步解析（:212-245）改为：`r.uid` 非空 → 写 `sid:uid` 键；`r.uid` 空（旧 App）→ 仍写 `sid` 键。
`automationSnapshot`（:472-500）增加 `uid` 入参/字段，缺省 activeUid。

### 7.2 App 侧

- `DailyConfigStore` 键 `acc:<fpid>:<sid>`（:116-131）→ `acc:<fpid>:<sid>:<uid>`；`FnParams` 键 `p:<fpid>:<sid>:<fn>`
  （:261-262）→ `p:<fpid>:<sid>:<uid>:<fn>`；`mod:` 读取缓存同步改 uid 段。
- sync payload 已是逐角色 `roles[]`（VaultSync.java:1102-1120：每个 Role 带 sid/uid），**协议形状无需变**，只是服务端按 uid 落库。
- 迁移：首次升级读 `acc:<fpid>:<sid>` 作为该 sid 下 activeUid 的初始配置（一次性 copy）；旧键保留只读一个版本；
  删除账号/换角色时清理 `sid:uid` 键（`RoleStore.updateUid` 已有 from→to 迁移逻辑可复用）。
- 兼容矩阵：

| App | Server | 行为 |
|---|---|---|
| 旧（无 uid） | 新 | 服务端写 sid 默认；该 sid 下所有角色继承（等价现状） |
| 新（带 uid） | 旧 | 服务端忽略未知 uid 字段，按 sid 覆盖（现状）；App 需容忍 |
| 新 | 新 | 每角色独立；无 uid 配置回退 sid 默认 |

### 7.3 灰度

`VAULT_PER_ROLE=off` 时**完全走旧读路径**（roleConfig/roleParams 按 sid），新键只写不读；`VAULT_PER_ROLE=on` 后按 uid 优先；
`VAULT_PER_ROLE_FPIDS=<fpid,...>` allowlist 便于单账号灰度；`roleConfigByUid` 缺失时逐字段回退 sid 默认，保证“升级不重置配置”。

## 8. 并发 / 风控 / 观测

- 账号内并发：已有 `config.maxConcurrentSessions` 按角色会话计数（SessionKeeper:55-57）；建议增加
  `VAULT_PER_ACCOUNT_MAX_ROLES`（默认 2，超限拒绝 role login）防止误开 4 角色打满请求。
- 请求速率：账号级 token bucket（复用 §3.3 预算窗口），目标 ≤24 派单/分 + 唤醒 ≤12 写键/分；每角色内部仍是单线程串行。
- 顶号/限流风险信号：`code=5` 顶号事件（monitor session event，已按 fpid/roleUid 落）、`1528`、请求超时；
  灰度验收指标：双角色在线时 `session takeover` 次数不高于单角色基线 ×1.5、`code=5` 不显著上升。
- 观测：`srv:cad:stats` 增加 `byRole:{"fpid|sid|uid":n}` 与 `skipByRole`（现 `byAccount` 见 :96-103,463-469）；
  monitor TaskTrace/标题加 uid；HealthMonitor 告警键 `srv:alert:{kind}:{fpid}:{sid}:{uid}:{date}`（现 fpid 级 HealthMonitor:51）；
  `/status` 已按角色返回 `roleState/roles`（VaultHttpApi:369,544-553），App 无需新增协议。
- 回滚开关与失败隔离：每角色 `runState` 已独立（RoleEntry），可单角色 PAUSED；`VAULT_PER_ROLE=off` 立即回到 active 单角色执行；
  新键全部“写冗余、读可回退”，回滚不丢状态。

## 9. 工作量与实施顺序（估算，1 人日 ≈ 6h）

| 阶段 | 内容 | 主要文件 | 估算 | 灰度/回滚 |
|---|---|---|---|---|
| P2-1 | RoleRef + KeeperFacade v2 + SessionKeeper 按角色寻址（不改任务行为） | SessionKeeper, RoundScheduler, TaskCadenceScheduler, 测试 | 0.5–1d | 纯接口，`off` 等价现状 |
| P2-2 | 节奏 per-role 键（cad/st/last）+ 公平/预算/间隔离 | TaskCadenceScheduler, TaskWakeRouter, CadenceFairnessTest/TaskCadenceSchedulerTest | 1–1.5d | `VAULT_PER_ROLE` + allowlist |
| P2-3 | 整轮/补给/手动 per-role + summary 镜像 | RoundScheduler, RoundRunner, ModularRoundRunner | 0.5–1d | 旧键 active 镜像 |
| P2-4 | 采集 per-role + 状态键 uid + active 旧键镜像 | SessionKeeper, CaptureTasks, 测试 | 1–1.5d | 双写，App 零改动 |
| P2-5 | 配置/参数按 uid（服务端+App+sync+迁移） | VaultStore, VaultHttpApi, DailyConfigStore, FnParams, VaultSync | 1.5–2d | 按 uid 优先、sid 回退 |
| P2-6 | 唤醒/战争/让位 per-role | TaskWakeRouter, ServerWarState, SessionControl | 0.5–1d | `VAULT_YIELD_SCOPE` |
| P2-7 | 预算/风控/观测/长稳+发版 | Main, HealthMonitor, docs | 1d | 指标门槛 |
| **合计** | | | **6–8.5 人日** | 分 3 个 PR：调度寻址(P2-1/2/3)→状态采集(P2-4/6)→配置(P2-5)+P2-7 |

建议顺序理由：P2-1/2/3 不依赖配置改造即可让“两个角色各跑同一份 sid 配置”（先解决调度公平与状态隔离）；
P2-4 让采集/日志按角色可观测；P2-5 才允许“两个角色不同任务配置”，对用户可见变化最晚，便于灰度。

## 10. 需 Lead/用户确认的决策点

1. **全局预算**：默认 20/分是否提升到 30/分（双角色实测需求 ≈19.3/分）；或保持 20 并接受各角色 ~50% 节奏。
2. **App 兼容策略**：旧键镜像由 active 角色写（推荐，App 零改动）还是升级 App 按 uid 读（可省镜像写但需发版）。
3. **让位/顶号粒度**：用户进游戏时是让位“全部角色”（推荐安全）还是只让位当前角色（`VAULT_YIELD_SCOPE`）。
4. **配置继承**：uid 无配置时是否回退该 sid 旧配置（推荐是，避免升级后任务全关）。
5. **整轮窗口**：Phase2 先保持账号级 `windowStart/End`（推荐），还是同批做角色级窗口（需 `RoleEntry` 扩展 + App 配置页）。

## 11. 风险与未决

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 双角色请求翻倍触发游戏端限流/风控 | 任务失败率上升、`code=5` | §3.3 账号预算 + 错峰采集 + 灰度观察 24h |
| R2 | 同 sid 旧键互相覆盖（若无 uid 键/镜像策略误写） | App 显示错角色/状态丢 | 旧键只由 active 写；新键全量；单测锁键型 |
| R3 | 配置迁移覆盖用户已调好的开关 | 用户投诉 | 逐字段回退 sid 默认 + copy 迁移 + 发版说明 |
| R4 | 同 token 双会话登录是否互踢（线上 Phase1 未观察到，但未做专项） | 会话抖动 | 灰度期抓 monitor session 事件；可切 `VAULT_PER_ROLE=off` |
| R5 | 节奏预算/公平实现引入新饿死 | 某角色任务延迟 | 保底 floor + `byRole` 观测 + CadenceFairnessTest 覆盖 |
| R6 | 状态键数量翻倍（state.json 体积/写频） | flush/IO 压力 | uid 键只对需要隔离的模块启用；flush 合并；监测文件体积 |
