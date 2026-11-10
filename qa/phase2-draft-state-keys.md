# Phase 2 方案 A：状态键命名空间与迁移（设计草稿）

- 任务：Agent Teams `task-7`（只读分析；本文件是唯一产出）
- 基线：master `9d07208`（含 P0 互助修复 + Phase 1 双角色联盟互助/科技捐献隔离）
- 生产只读快照：2026-10-29 02:06（北京）；`state.json` 1,015,211 B / 1176 键；账号 `48460403` 有 5 个角色，其中 **sid=888 同服双角色**（uid 35371154 / 88280792）——正是 Phase 2 的目标场景
- 边界：未改源码、未 commit、未动生产；所有 file:line 均指 `git show 9d07208:...` 版本

## 0. 结论速览

1. 现状键型是「前缀 + fpid + sid + 尾段」的**扁平命名**，没有统一构造/解析入口：23 个 server 文件、11 个 App 文件各自拼 key；同账号同服双角色会在 `log:/st:/res:/todo:/srv:cad:/srv:gather:/srv:bear:/srv:shop:/srv:arena:/srv:behemoth:` 上互相覆盖。
2. 已有 4 类「角色隔离」先例可直接复用其模式（新键 + 旧键回退 + **仅 active 双写旧键**）：`mod:`（TaskContext）、`ledger:`（ClaimLedger）、`srv:formation:*`（SessionKeeper:2515-2538）、`srv:asset:*`（AssetStateProjector + SessionKeeper:841-847）；`srv:alliance_science:*`/`alliance_help` 由 Phase 1 完成。
3. 方案：新增统一 `StateKeys` 门面（键构造 + 角色读回退 + active 双写），键语法 `<family>:<fpid>:<sid>:<uid>:<rest>`；**按角色**族加 uid、**按账号**族保持 fpid/sid、**全局/日**族不变。旧键仅 active 角色双写「一版」后关闭。
4. 迁移不写一次性脚本：新键写入 + 读新 miss 回退旧 + active 双写自动补齐（惰性迁移）；回滚用 `VAULT_STATE_SCOPE=perrole|dual|legacy` 一档开关，旧键在双写期始终新鲜。
5. 关键连带改动必须同批：`HealthMonitor`/`DailyDigest` 的 `key.split(":",4)` 解析（role 键会把 uid 当成 fn 前缀，误判成“子项日志”而漏计）、角色删除时清理 per-uid 键、`st:/log:` 无 retention 导致的体积增长。总工作量约 **4-5 人日 / 约 1600-2300 行 server + 400-600 行 App**。

## 1. 现状盘点：键族清单

### 1.1 分类口径

- **R（按角色 uid）**：同一 fpid+sid 下不同 uid 必须互不覆盖（任务状态、节拍、采集快照、模块台账）。
- **A（按账号/区服 sid）**：账号级/区服级聚合或映射，多角色共享（整轮汇总、最近运行、角色映射、告警节流）。
- **G（全局/联盟/日）**：跨账号共享（派单统计、日报、告警去重键）。
- **C（配置）**：不在 StateStore（vault.json `roleConfig/roleParams` + App prefs `p:`），Phase 2-A 范围外但影响“按角色显示”。

### 1.2 键族总表（构造点 → 读写方 → 语义 → Phase 2 决策）

| # | 键模板 | 构造/写入点（file:line） | 主要读取方 | 语义 | Phase 2 |
|---|---|---|---|---|---|
| 1 | `mod:<fpid>:<sid>[:<uid>]:<fn>` | TaskContext.java:128-160（已角色化）；直写点：CaptureTasks:213,325,357,400、ServerShieldResponder:624-629、ServerTownTasks:197,314、ServerShopLeagueTasks:2012、ServerGuardianEngine:2118 | App FnParams:234/256、FnDetail、HomeActivity:1577,4971；Digest/Health | R | 直写点改走 `StateKeys.role()` + active 双写旧键；TaskContext 已就绪 |
| 2 | `log:<fpid>:<sid>:<fn>[:<sub>]` | RoundRunner:125,223（子项日志同键型） | App VaultSync:521、LogStore:152-167；HealthMonitor:322；DailyDigest:589 | R | 加 uid → `log:<fpid>:<sid>:<uid>:<fn>`；两处解析同步（风险 R1） |
| 3 | `st:<yyyy-MM-dd>:<fpid>:<sid>:<fn>` | RoundRunner:153,174；TaskCadenceScheduler:573 | App VaultSync:524、LogStore:167；Digest/Health（按前缀） | R | 加 uid；增加 retention（当前无清理，见 R2） |
| 4 | `ledger:<fpid>:<sid>[:<uid>]:<scope>` | ClaimLedger.java:31-32,41,68（**已角色化**） | 各任务、Digest | R | **无改动**（Phase 1 完成） |
| 5 | `summary:<fpid>` | RoundRunner:100、ModularRoundRunner:144 | App HomeActivity/DailyRunner:1307；VaultSync | A | 保持；如需按角色展示加 `summary:<fpid>:<sid>:<uid>`（可选） |
| 6 | `srv:lastrun:<fpid>` | RoundRunner:103、ModularRoundRunner:148 | RoundScheduler:148、VaultHttpApi:485、Digest:541 | A | 保持账号级（整轮语义）；角色级“最近运行”另加键（可选） |
| 7 | `srv:cad:<fpid>:<sid>:<fn>` | RoundRunner:215、TaskCadenceScheduler:556、ServerFieldTasks:449 | TaskCadenceScheduler:332/362/382、HealthMonitor:246 | R（每角色独立节拍） | 加 uid；**账号级派单预算/统计保持全局**（R3） |
| 8 | `srv:cadlast:<fpid>` | TaskCadenceScheduler:316,362,382 | 同左 | A | 保持（账号级最小派单间隔） |
| 9 | `srv:cad:stats:<day>` | TaskCadenceScheduler:108,445,470 | HealthMonitor:241-243、DailyDigest:534 | G | 保持不变；可加 per-uid 细分列（可选） |
| 10 | `srv:alert:<kind>:<fpid|global>:<day>` | HealthMonitor:62,704-707,756 | HealthMonitor:731-733 | A/G | 保持 |
| 11 | `srv:digest:<day>` | DailyDigest:60,868,902 | DailyDigest:238 | G | 保持（30 天 purge:902） |
| 12 | `questprog:/questdone:/questts:/act:/boxes:<fpid>:<sid>` | CaptureTasks:107-117（suffix=fpid:sid） | App QuestStore:36-72、VaultSync:527-530 | R | suffix 加 uid；App 读回退旧键 |
| 13 | `res:<fpid>:<sid>`、`todo:<fpid>:<sid>`、`rallies:<fpid>:<sid>` | CaptureTasks:223,236,278 | App HomeActivity:1622,1704,1841；VaultSync | R | 加 uid；App 读回退旧键 |
| 14 | `srv:bear:<fpid>:<sid>:{launched,joins,state}` | ServerBearTasks:126,2608-2616 | ServerBearTasks 内部、App | R | 加 uid |
| 15 | `srv:gather:*`、`srv:intel:*` | ServerFieldTasks:449,700,1444,1449,1454,1576,1715,2755,2786 | 各任务、Digest | R | 加 uid（队列/重试/上限均按角色） |
| 16 | `srv:shop:<…>:pending`、`srv:mobilization:<fpid>:<sid>[:refresh]` | ServerShopLeagueTasks:467,1369,1493 | 联盟商店/动员任务 | R | 加 uid |
| 17 | `srv:arena:day:*`、`srv:arena:diamond:*` | ServerTownTasks:1256,1521 | 竞技场任务 | R | 加 uid |
| 18 | `srv:behemoth:<fpid>:<sid>…` | ServerTasks:4072 | 巨兽任务 | R | 加 uid |
| 19 | `srv:asset:<fpid>:<sid>[:<uid>]` | AssetStateProjector:44,60-66；SessionKeeper:841-847（**新键+旧键仅 active，env 回滚**） | App AssetCenter/GatherConfig:42、VaultSync:545 | R（已双写） | **无改动**；作为迁移模板 |
| 20 | `srv:formation:{catalog,memory,bear,autosettings}:<fpid>:<sid>[:<uid>]` | FormationCatalog:321-333、FormationMemory:73-79；SessionKeeper:2515-2538（**新键+旧键仅 active**） | 编队任务、App FormationStore:64 | R（已双写） | **无改动**；作为迁移模板 |
| 21 | `srv:alliance_science:<fpid>:<sid>[:<uid>]` | ServerTasks:3674-3685（Phase 1） | 捐献任务 | R（已双写） | 无改动 |
| 22 | `srv:guardian:<fpid>:<sid>:ledger`、`mod:…:guardian` | ServerGuardianEngine:2114-2118 | Guardian、FnParams:455/460、Digest:480-491 | **待定** | 建议角色级 ledger + 账号级通知去重（决策 D3） |
| 23 | `srv:roleuid:<fpid>:<kid>[:<uid>]`、`srv:uid:/srv:identity:<fpid>:<sid>:<uid>` | SessionKeeper:195,229,1213-1222 | SessionKeeper 登录映射 | A | 保持（sid→uid 映射是 Phase 2 的输入数据） |
| 24 | `p:<fpid>:<sid>:<fn>`（App prefs）、`roleConfig/roleParams`（vault.json，sid 键） | FnParams:262,234-256；VaultStore:107-109；VaultHttpApi:229-236 | App 设置页、TaskContext.params | C | v1 保持 sid 级共享；per-role override 留 Phase 2-B（决策 D2） |

### 1.3 Phase 1 已角色化先例（直接复用模式）

- `TaskContext.modKey`: roleUid>0 → `mod:<fpid>:<sid>:<uid>:<fn>`，读角色键 miss 回退旧键（TaskContext.java:128-144）。
- `ClaimLedger`: roleUid>0 → `ledger:<fpid>:<sid>:<uid>:<scope>`（ClaimLedger.java:31-32）。
- 编队目录：新键带 uid，**旧键只由 active 写**（SessionKeeper.java:2521-2538，FormationCatalog:321/327）。
- 资产投影：`srv:asset:<fpid>:<sid>:<uid>` + 旧键仅 active + `VAULT_ASSET_LIBRARY=off` 回滚（AssetStateProjector:44-66、SessionKeeper.java:841-847）。
- 生产实证：`mod:48460403:888:88280792:alliance_help`、`srv:alliance_science:48460403:888:88280792`、`srv:asset:48460403:888:35371154`、旧键 `mod:…:alliance_help` 并存。

## 2. 命名空间方案

### 2.1 键语法 v2（唯一合法形状）

```
role    : <family>:<fpid>:<sid>:<uid>:<rest...>     # 按角色（uid>0）
account : <family>:<fpid>[:<sid>][:<rest...>]       # 按账号/区服（uid 禁止出现）
global  : <family>[:<yyyy-MM-dd>][:<rest...>]        # 全局/日
legacy  : 现状形状（仅作为回退读 + 过渡期双写目标，禁止新增写入方）
```

- `family` 白名单枚举（对应 §1.2 表），禁止 `strings.Split` 猜语义；解析统一 `int uid = StateKeys.uidOf(key, family)`。
- `uid<=0` 表示“无角色上下文（旧/兼容路径）”，与 Phase 1 `roleUid=0` 一致，不引入第三态。

### 2.2 三类归属决策表

| 归属 | 族 | 规则 |
|---|---|---|
| 按角色 R | mod / log / st / ledger / srv:cad / quest* / act / boxes / res / todo / rallies / srv:gather / srv:intel / srv:bear / srv:shop / srv:mobilization / srv:arena / srv:behemoth / srv:asset(已) / srv:formation(已) / srv:alliance_science(已) | 写角色键；读角色键→旧键回退；旧键仅 active 双写 |
| 按账号 A | summary / srv:lastrun / srv:cadlast / srv:supplyfire / srv:alert / srv:roleuid / srv:uid / srv:identity | 保持现状；不得加 uid（否则“整轮/告警/节流”语义被按角色放大） |
| 全局 G | srv:cad:stats / srv:digest / global 告警 | 保持现状 |
| 配置 C | p:（App prefs）/ roleConfig / roleParams | v1 保持 sid 级；per-role override 列入 Phase 2-B |

### 2.3 `StateKeys` 门面（新增 1 个类，键构造唯一入口）

```java
public final class StateKeys {                      // vault-server: com.carlos.vaultserver.state.StateKeys
    static String role(String family, String fpid, long sid, long uid, String... rest);
    static String legacy(String family, String fpid, long sid, String... rest);
    static String account(String family, String fpid, long... sidOr0);
    static String readRole(StateStore s, String roleKey, String legacyKey);   // role miss → legacy
    static void writeRole(StateStore s, String roleKey, String legacyKey,
                          String value, boolean active, boolean dualWrite);   // active 且开关 on 才双写
    static String uidOf(String key, String family);   // 解析；非法返回 0
}
```
配套：Phase 2 的所有写入点改为 `StateKeys`（grep 校验无裸 `"mod:" +` 等）；新增单测锁定“每个 family 的 role/legacy 键形状 + 回退 + 双写矩阵”。

## 3. 迁移 / 兼容 / 回滚

### 3.1 分阶段（按提交顺序）

| 阶段 | 内容 | 写 | 读 | 旧键 |
|---|---|---|---|---|
| M0 | 引入 `StateKeys` + 单测，不改行为 | 现状 | 现状 | 现状 |
| M1 | task/capture/模块写侧切 role 键（active 双写旧键） | role(+dual) | role→legacy | active 双写保持新鲜 |
| M2 | 观测/App 读侧适配（解析 + 按选中角色） | 同 M1 | 同上 + 新增角色维度展示 | 同上 |
| M3 | 观察 1-2 周后关闭双写（`VAULT_STATE_DUAL_WRITE=off`） | role only | role→legacy | 冻结、只读回退 |
| M4 | 清理：角色删除时清 per-uid 键；旧键保留供旧版 App 回退 | role | role→legacy | 保留一版 |

### 3.2 双写矩阵（M1-M2）

| 键族 | 角色键写入 | 旧键双写 | 读取顺序 |
|---|---|---|---|
| mod/log/st/ledger/cad/quest*/res/todo/rallies/gather/intel/bear/shop/mobilization/arena/behemoth | 所有角色 | **仅 active** | role → legacy |
| asset/formation/alliance_science/help（Phase 1 已双写） | 现状 | 现状 | 现状（勿重复改造） |
| account/global 族 | 不适用 | 不适用 | 现状 |

### 3.3 回滚

- 开关：`VAULT_STATE_SCOPE=perrole|dual|legacy`（默认 `dual`）+ `VAULT_STATE_DUAL_WRITE=on|off`。
- `legacy`：写侧全部退回旧键、读侧仍读 role→legacy（双写期旧键新鲜，可立即回到 P0/Phase1 行为）；`perrole`：旧键不写。
- 数据不回滚：新键保留但闲置；如需清理用 `stateStore.removePrefix("mod:<fpid>:<sid>:<uid>:")` 白名单脚本（人工执行，先备份 state.json）。
- 演练验收：切 flag 重启一次 → `/state` 中旧键 `_ts` 仍更新、App 今日页正常 → 再切回。

### 3.4 一次性迁移脚本（结论：v1 不做）

理由：已有“读旧回退 + active 双写”，M1 上线后 active 角色第一次 tick 即把旧状态投影到角色键（惰性迁移），无需停机。仅当需要把历史 `log:/st:` 精确回填到每个角色时才考虑离线脚本（读 state.json + vault.json 的 sid→uid 映射，逐角色复制，幂等，停机执行）。

## 4. 风险清单

| # | 风险 | 影响 | 证据 | 缓解 |
|---|---|---|---|---|
| R1 | 观测解析错位：`split(":",4)` 将 `log:fpid:sid:uid:fn` 的 `uid:fn` 当 fn，`fn.indexOf(':')` 判为“子项日志”而跳过 | HealthMonitor 的 claimed/error 计数归零→假告警；Digest 任务维度错乱 | HealthMonitor.java:322-336、:246；DailyDigest.java:589-593 | 同批改解析（split 5 / `StateKeys.uidOf`）+ 用 role 键样本加单测 |
| R2 | 状态体积/写放大：`st:` 666 个、`log:` 258 个，无 `st:/log:` 清理（仅 digest 有 30 天 purge） | 双角色后键数约 ×2，state.json 已 1.0 MB 且每次 flush 全量 JSON | RoundRunner.java:125-174（写）；DailyDigest.java:902（仅 digest 清理） | Phase 2 同批加 retention（st/log 保留 3-7 天，按前缀 purge）+ 体积监控 |
| R3 | 请求量×角色数：capture 10min/会话、cadence 按角色派单 | 游戏协议请求翻倍，触发限流/封号风险 | SessionKeeper.java:938-946（capture）；TaskCadenceScheduler（账号预算） | 账号级 QPS/派单预算不变 + 角色间 jitter 错峰 + capture 每账号轮转（同一时刻只采一个角色） |
| R4 | 切角色/App 缓存：`/state` 全量下发、VaultSync 原样写 prefs、UI 读旧键 | App 今日页显示上一角色/串角色状态 | VaultHttpApi.java:337-344、VaultSync.java:509-546、HomeActivity.java:1577-1841 | App 读 `role(selectedUid)→legacy`；prefs 缓存按 uid 分桶；切角色清旧展示 |
| R5 | 角色删除残留：removeRoleLocked 只删 vault 角色，不清 StateStore | orphan per-uid 键、重登后旧状态回显 | SessionKeeper.java:1960-1975；StateStore.removePrefix:108 | 删除角色时按白名单 family `removePrefix`；加单测 |
| R6 | Phase 1 已写键兼容：help/science/asset/formation 已 role+legacy | 重复改造会造成双写源冲突 | ServerHelpResponder:188-193、ServerTasks:3674-3685、SessionKeeper:841-847,2515-2538 | 统一白名单标注 “已完成”；M0 单测断言这些键形状不变 |
| R7 | 旧键双写退出过早 | 旧版 App 读不到状态 | VaultSync/HomeActivity 全量读旧键 | M3 前置条件：App 最低版本覆盖 + 观察 2 周；保留 legacy 读回退 |
| R8 | 账号级功能被误角色化 | “整轮已跑/最近运行/告警节流/日报”按角色放大，请求翻倍或告警重复 | summary/lastrun/cadlast/alert/digest 见 §1.2 #5/#6/#8/#10/#11 | §2.2 白名单评审；M0 单测锁定 account/global family 不允许 uid |
| R9 | guardian 语义未定（同账号双角色同联盟） | 重复驻防/重复企微通知 | ServerGuardianEngine:2114-2118；Digest:480-491 | 决策 D3：建议角色级 ledger + 账号/联盟级去重 |

## 5. 工作量估计与提交拆分建议

| # | 提交（PR）建议 | 主要文件 | 预估 diff | 测试 |
|---|---|---|---|---|
| C1 | `feat(state): StateKeys 门面（纯新增，无行为变更）` | StateKeys + StateKeysTest | +220 行 | +180 行 |
| C2 | `feat(task): cadence/round 透传 roleUid + log/st/cad 角色键（active 双写）` | TaskCadenceScheduler、RoundRunner、ModularRoundRunner、RoundScheduler、TaskContext(测试) | ~300 行 | +250 行 |
| C3 | `feat(capture): captureAll 按角色采集 + 账号内错峰` | CaptureTasks、SessionKeeper | ~250 行 | +150 行 |
| C4 | `feat(modules): 各模块 srv:* 台账按角色` | ServerFieldTasks、ServerBearTasks、ServerShopLeagueTasks、ServerTownTasks、ServerTasks、ServerGuardianEngine | ~450 行 | +300 行 |
| C5 | `feat(session): 兄弟角色全任务接线 + 角色删除清键 + 回滚开关` | SessionKeeper、VaultHttpApi(可选过滤)、StateStore(可加 purge) | ~300 行 | +200 行 |
| C6 | `feat(obs/app): HealthMonitor/DailyDigest 解析适配 + App 按选中角色显示` | HealthMonitor、DailyDigest + App 11 文件（VaultSync/FnParams/HomeActivity/FnDetail/DailyRunner/LogStore/QuestStore/RoleStore/FormationStore/AssetCenterActivity/GatherConfigActivity） | server ~120 / app ~400-600 行 | +250 行 |

合计：**server 约 1200-1700 行 / 15 个文件 + App 约 400-600 行 / 11 个文件（合计约 1600-2300 行 / 26 文件）**，预计 **4-5 人日**（不含 App UI 细节打磨）；测试约 +1300 行。建议顺序 C1→C2→C3→C4→C5→C6（C6 的观测解析可与 C2 合并提前，避免观测失真期）。

## 6. 验收 / 观测（只读，生产允许）

1. 键出现：`ssh vault-vps 'grep -o "mod:48460403:888:88280792:[a-z_]*" /opt/vault-server/data/state.json | sort -u'` 应覆盖兄弟角色任务；`log:…:88280792:…`、`st:…:88280792:…` 出现。
2. 旧键新鲜：`mod:48460403:888:alliance_help.help_all_ts` 持续更新（active 双写），兄弟角色不覆盖。
3. 观测口径：HealthMonitor 线上告警数与 Phase 2 前基线对比（R1 回归）；`srv:cad:stats:<day>` 派单量因角色化后的总预算不升。
4. App 切角色：A/B 角色互切后今日页/功能页状态行分别为各自 uid 内容；旧键回退路径可用（关闭新键读取的开关）。
5. 体积：`state.json` 周环比增长 < 角色数×(人均日增) 预期，且 retention 生效（st/log 天数）。

## 7. 需 Lead 决策项

- **D1 双写退出时机**：M3 前 App 最低版本要求（旧版依赖旧键）与观察窗口（建议 2 周）。
- **D2 配置是否按角色**：`roleConfig/roleParams` 现按 sid 键（VaultStore:107-109），双角色共享；Phase 2-B 是否做 per-uid override（影响 App 设置页 + API 契约）。
- **D3 guardian 归属**：角色级 ledger（推荐，各自会话独立）还是账号级去重（避免同联盟双角色重复通知）。
- **D4 采集错峰策略**：账号内轮转（同一时刻只采一个角色）vs 全角色并行（请求量×N）。
- **D5 retention 口径**：`st:`/`log:` 保留天数（建议 7 天 `st:`、`log:` 保持 per-fn 50 条但值按角色分键）。

## 8. 边界声明

本文件为唯一写入（`qa/phase2-draft-state-keys.md`）；未修改源码/配置、未 commit/merge/push、未重启生产、未对游戏账号发协议。生产数据仅 `ssh vault-vps` 只读（state.json/vault.json 计数与键名，不含任何凭证）。
