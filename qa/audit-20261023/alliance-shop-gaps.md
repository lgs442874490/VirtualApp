# 联盟类 / 商店类 1:1 差距审计（task-3）

- 审计人：audit-alliance-shop（read-only）
- 时间：2026-10-23 03:2x（Asia/Shanghai）；分支：`add/auto-claim-parity-20261023-0302`
- 硬约束执行情况：未改任何源码/配置/文档（仅写本文件）；未 git 写操作；未跑 gradle；未用 zcode；生产仅只读 `ssh -o BatchMode=yes vault-vps`（读 state.json / logs）。
- 对照基准（网页版反编译源码）：`/Users/liangguansheng/ZCodeProject/wjdr-review/src-tool/com/wjdr/tool/modules/daily/`
- 我方实现：`vault-server/src/main/java/com/carlos/vaultserver/task/{ServerShopLeagueTasks,ServerTasks,TaskCadence,TaskCadenceScheduler}.java`、`app/src/main/java/com/carlos/vault/daily/{ShopTasks,LeagueTasks,PushWakeDriver}.java`、`app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java`
- 参考文档：`../网页版功能全览与移植对照.md` L65-77（联盟类/商店购买）、`design/自动化模块-实现落差与修复待办-20261018.md` §3.4 L103-108、§3.7 L124-129
- 本报告 file:line 均可直接点开核对；生产复现命令见 §7。

---

## 0. 结论摘要（先给结论）

1. **“配置未启用”不是 bug，是默认开关与网页版一致的安全默认**：`shop`/`alliance_shop`/`alliance_showdown` 目录默认 false（`ServerTasks.java:129-130,125`）↔ 网页版 `DailyShopConfig.defaults()` 全 false（`config/DailyShopConfig.java:45-47`）+ `anyEnabled()` 要求开关与清单同时非空（`:58-60`）、`DailyAllianceShowdownConfig.defaults().autoSignupEnabled=false`（`config/DailyAllianceShowdownConfig.java:51-53`）、`DailyAllianceDonationConfig.defaults()` 全 false（`config/DailyAllianceDonationConfig.java:9-12`）。生产 roleConfig 稀疏（`vault.json` 账号 48460403 roleConfig[888] 仅 `barracks/adventure_sweep/arena/gather/guardian/intel`），所以整轮显示“配置未启用”与网页版口径一致 → **不建议为了“看起来有在跑”把默认改 true**；应提供“推荐勾选/安全清单 + 完整门控”。
2. **数据其实已经到达，问题在消费侧**：生产日志证明登录批次已推送 `notify_alliance_chest_init` / `notify_alliance_science_init` / `notify_recover_resource_init`，且登录后持续推送 `notify_alliance_chest_add`（vault-20261023.log:569/2544/2031/2609/3212/3237/3278…）。我方“科技数据未同步 / 无宝箱列表 / 背包数据未同步”的根因是**解析口径**（只认 List 不认 Map、只读 init 不读 add）与**状态机缺失**，不是服务器没推。
3. **联盟宝箱 P0**：只读 `notify_alliance_chest_init/update`，不读 `notify_alliance_chest_add/delete/brilliant_update` → 登录后新增宝箱全部漏领；`type` 硬编码 `cid==90101?2:1` 与网页版 3 分类口径（ALLY 90100-90200→1 / TROPHY 90200-90300→2 / BRILLIANT 90300-90400→3）不一致；无奖杯(500/日)上限、无批量 `req_alliance_receive_chest_by_ids`。
4. **联盟捐献 P0**：`Exec.mapsOf` 只接受 `List`（`Exec.java:65-73`），网页版 `values()/maps()` 同时兼容 Map+Collection（`DailyAllianceDonationState.java:571-597`）；科技列表 `sciences`、免费次数来源 `notify_recover_resource_init/update.data[id=205]`（`:517-535,551-558`）都是 Map/列表混形 → 我方 `donateId=0` → “科技数据未同步”。且我方只捐 1 次/轮、不读免费次数、不按“推荐科技优先”循环（网页版 `DailyAllianceDonationService.java:496-573`）。
5. **联盟总动员存在“误花钻石”隐患**：活动开放时我方无条件 `req_mobilization_buy_count{count:1}`（`ServerShopLeagueTasks.java:611`），而网页版购买价目 `[0,50,50,50,200,200,200,1000,1000,1000]`（`catalog/alliance-mobilization.json`），只允许“第 0 次免费次数 + 显式付费开关 + 三重预算”（`DailyAllianceMobilizationService.java:652-673`）。当前生产 7401 未开启未触发，属潜伏 P0/P1。
6. **商店：目录 1:1 已成立（942/942 完全一致），但执行侧与网页版差距大且开启后有误花钻石/超额购买风险**：我方 `buyFromShops` 只认 `buys`（configId→数量）、忽略 App 新 UI 写入的 `buy_names`，而 App UI 会把同一名字的**全部 variant confId 都写进 buys**（`SubCardUi.java:601-614`）→ 服务端按 configId 逐个买，重复购买/买贵档；且无币种余额校验、无 801 钻石购买预算、无“前 3 次免费刷新”计次与钻石刷新上限、对 12 个店铺都发 `req_shop_refresh`（网页版只有 801 流动/601 神秘可刷新）。
7. **节奏/唤醒**：网页版是 TaskEngine per-task interval + `interestedProtocols()` 推送唤醒（`AbstractDailyAutomationTaskDefinition.java:32-73`、`AbstractStandardTaskModule.java:65-90,208-210`、`DailyTaskEngineBridge.java:193-225`）；我方生产仍是 05:00-09:00 单轮（生产日志无“任务节奏调度”启动行；`TaskCadence.java`/`TaskCadenceScheduler.java` 未部署且**只做定时、不做推送唤醒**）。联盟宝箱(120s)/捐献(30s)/商店(60s) 的日间节奏在现网未生效。
8. 生产“联盟宝箱×187”能成功（`state.json:log:48460403:888:alliance_chest` 10-22 05:45）说明 init 形状可被现实现解析；10-23 00:59 “无宝箱列表”发生在登录后新箱尚未以 init 形式出现、且 add 推送被忽略 → 与 §3 一致。

---

## 1. 引擎/唤醒/节奏对照（所有功能的公共底座）

### 1.1 网页版
- 每任务独立间隔：`alliance-chests=120s`、`alliance-donation=30s`、`alliance-mobilization=5s`、`alliance-showdown=30s(TIME_WINDOW)`、`shops=60s(BACKGROUND)`（`model/AbstractDailyAutomationTaskDefinition.java:32-73`；`:37-41,50`）。
- 推送唤醒：任务声明 `interestedProtocols()`（如宝箱 4 个、捐献 18 个、总动员 10 个、争霸赛 2 个、商店 `notify_game_init/notify_server_time`；各 `task/*/*TaskModule.java:21`）；推送到达 → `DailyTaskEngineBridge.onProtocolNotification`（`:193-225`）→ `TaskSignalBus.publish(PROTOCOL_NOTIFICATION)`（`:50`）→ `AbstractStandardTaskModule.decide` 在 SLEEPING/COMPLETED 收到唤醒即重新 evaluate（`:65-90,208-210`）。
- 领取态判定：`status.claimable/executable` 为真才 Execute，否则 `SleepUntil(nextClaimInSeconds/nextExecuteInSeconds/remainingSeconds)`（`:92-107,160-181`）；命令完成按业务 `nextRunAt` 再睡（`:127-137`）。
- 登录期数据同步：登录序列固定 `loginauth → req_loaddata → req_players_base_info`（`core/account/GameAccountSessionService.java:1602-1660`，`core/common/constant/GameConstants.java:18`）；随后等待服务端 init 推送（`DailyInitialDataSyncService.java:38-40` 列出 `notify_alliance_chest_init/notify_alliance_science_init/notify_mobilization_init/notify_recover_resource_init` 等）。**没有额外请求协议去“拉”这些 init**。

### 1.2 我方
- 登录序列同为 loginauth/loaddata/players_base_info（`app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:389-458`）。
- 推送缓存：`notifies` 为 proto→最近一次 content（`WjdrEngine.java:154,651-682`）；取用时 `notifyContent/notifyData`（`:684-712`）。**不保留历史列表、不做 init 与 add/update 合并** → 任务必须自己实现状态机。
- 节奏：旧 `RoundScheduler` 每天 05:00-09:00 一轮（生产实证：`vault-20261023.log` 只有 16:41 起的手动/窗口轮与零星单任务轮）；本分支新增 `TaskCadence`（`TaskCadence.java:85-93` 含 alliance_chest=120/alliance_donate=30/alliance_mobilization=15/alliance_showdown=30/shop=60/alliance_shop=60）与 `TaskCadenceScheduler`（`:22-49` 只按 interval 调度，`grep notify|wake` 零命中 → **无推送唤醒**），生产 jar 未见“任务节奏调度已启动”日志 → **未部署**。
- App 侧有推送唤醒表 `PushWakeDriver.WAKE_MAP`（`:33-62`：mobilization/showdown 等），但只在 App 本地会话生效；服务器模式下不参与。服务器侧无对应实现 → P2。

---

## 2. 联盟宝箱（alliance-chest）

### 2.1 网页版协议序列 / 前置状态 / 节奏
1. 数据来源与维护（`service/DailyAllianceChestState.java`）：
   - `notify_alliance_chest_init` → `initialize`：遍历 `data.chests`（`values()` 兼容 Map/Collection），读 `brilliant`、`box_id`、`trophy_received_count`（`:196-209`；工具 `:571-597`）。前置：`notify_alliance_init/player_update` 写入 allianceId+joinTime，`allianceId>0` 才算 inAlliance（`:171-194`）；`notify_game_init` 重置（`:69-83`）。
   - `notify_alliance_chest_add` → merge 新箱（`:221-224`）、`update` → 标记 receive 并更新 trophy 计数、`is_end` 触发批次完成（`:226-235`）、`delete`（`:237-246`）、`notify_alliance_chest_brilliant_update`（`:248-254`）。
   - 可领判定：`!receive && endTime >= now`（`model/DailyAllianceChestStateSnapshot.java:33-39`）；trophy 类还受 `500 - trophyReceivedCount` 上限（`:41-43`）。
2. 分类口径（`service/DailyAllianceChestCategory.java:11-13`）：
   | key | 名称 | protocolType | cid 区间 |
   |---|---|---|---|
   | ally | 盟友赠礼 | 1 | 90100–90199 |
   | trophy | 战利品宝箱 | 2 | 90200–90299 |
   | brilliant | 辉煌宝箱 | 3 | 90300–90399 |
3. 领取（`service/DailyAllianceChestService.java:106-170`）：
   - 每类先过滤可领、trophy 按剩余额度截断（`:123-134`）；
   - 批量协议 `req_alliance_receive_chest_by_ids{ids}`（`:141`）；随后 `markPending(30s)`、等待 `notify...update.is_end` 批完成（最多 5s，`:144-146`）；
   - `1529`=TROPHY 上限 → `markTrophyLimitReached`（`:154-157`）并停；`1540/1541/1542/1543` 视为已收敛 → 降级逐条 `req_alliance_receive_chest{id,type}`（`:158-160,172-197,214-216`）；
   - 失败后用 revision 对账（500ms）判断是否服务端已处理（`:163-169,199-203`）。
4. 触发：`notify_alliance_chest_init/add/update/delete` 唤醒（`task/alliancechests/AllianceChestsTaskModule.java:21`），节奏 120s。自动可领条件：在线+ready+inAlliance+所选分类有可领（trophy 还需剩余>0）（`:82-93`）。

### 2.2 我方实现
`ServerTasks.allianceChest`（`ServerTasks.java:1057-1109`）：
- 只读 `notifyData("notify_alliance_chest_init")`，缺失时回落 `notify_alliance_chest_update`（`:1060-1061`）；再用 `Exec.mapOf(chests)` 强转 Map 后 `.values()`（`:1062`）——**若 init 的 chests 是 List 形状即判空**。
- 遍历未领项逐条 `req_alliance_receive_chest{id,type}`（`:1082-1083`）；`type = cid==90101L ? 2 : 1`（`:1079-1081`）；错误码 2/4303/7501/40611 归 SKIPPED（`:1088`）。
- 无 trophy 500 上限、无 brilliant 分类、不读 trophy_received_count、不处理 add/delete/brilliant_update、无批量协议、无 endTime 过滤。
- 默认开启（`ServerTasks.java:122`），节奏 120s（仅本分支 TaskCadence，未部署）。

### 2.3 缺口
| # | 缺口 | 证据 | 级别 |
|---|---|---|---|
| C1 | 登录后新增宝箱全部漏领：只读 init/update，忽略 `notify_alliance_chest_add` | 我方 `ServerTasks.java:1060-1061`；网页版 `DailyAllianceChestState.java:59,221-224`；生产 `vault-20261023.log:2031,2609,3212,3237,3278,3332,3343` 均为 `notify_alliance_chest_add` | **P0** |
| C2 | 分类/type 口径错误：网页版按 cid 区间 → type 1/2/3；我方只把 90101 映射为 2 | 我方 `:1079-1081`；网页版 `DailyAllianceChestCategory.java:11-13` | **P0** |
| C3 | 无 trophy 500/日上限，可能 1529 反复报错且漏判“已达上限” | 网页版 `DailyAllianceChestService.java:43,126-128,154-157`；`Snapshot.java:41-43` | P1 |
| C4 | 未使用批量 `req_alliance_receive_chest_by_ids`，逐条请求慢且无 `is_end` 批完成对账 | 网页版 `:41,136-170` | P1 |
| C5 | 文案误导：“无宝箱列表” 实际可能是“空列表/无未领/未入盟” | 我方 `:1063-1065`；网页版有 `inAlliance/ready/availableCount` 区分（`Snapshot.java:25-39`） | P1 |
| C6 | 不处理 delete/brilliant_update → 已删箱残留、辉煌箱状态不更新 | 网页版 `DailyAllianceChestState.java:62,237-254` | P1 |
| C7 | 不按 endTime 过滤过期箱 | 网页版 `Snapshot.java:34` | P2 |

### 2.4 最小修复方案（文件+函数级）
1. `ServerTasks.allianceChest` 前增加“每账号宝箱状态合并”小工具（可放 `ServerTasks` 私有静态方法或新增 `trail` 小类）：
   - 读 `notify_alliance_chest_init` 初始化 `Map<id, chest>`（`init.chests` 分别按 List/Map 兼容解析，见固定工具 `Exec.mapsOf` 修复）；
   - 再按到达顺序应用 `notify_alliance_chest_add`（merge）、`notify_alliance_chest_update`（receive=true、trophy_received_count、is_end）、`notify_alliance_chest_delete`（remove）、`brilliant_update`（brilliant/box_id）；
   - 取 `!receive && endtime>=now` 为候选。
2. `type` 改为按 `categoryOf(cid)`：`cid in [90100,90200)→1`、`[90200,90300)→2`、`[90300,90400)→3`，其余→1。
3. trophy 候选截断 `min(size, 500 - trophy_received_count)`。
4. 优先 `req_alliance_receive_chest_by_ids{ids}`；若返回 `2/4303/7501/40611` 再按条降级 `req_alliance_receive_chest{id,type}`；`1529` 记“trophy 上限已达”。
5. 失败文案细分：未入盟/初始化未就绪/分类无可领。
6. 与 `TaskCadence` 配合后可依赖 120s 节拍；若要 1:1 唤醒，需在服务器会话消息钩子上按 `notify_alliance_chest_*` 触发单任务（见 §1.2/§8）。

---

## 3. 联盟捐献（alliance-donation，含科技捐献/发展奖励/排名）

### 3.1 网页版协议序列 / 前置状态 / 节奏
- 任务触发协议 18 个（`task/alliancedonation/AllianceDonationTaskModule.java:21`），节奏 30s（`AbstractDailyAutomationTaskDefinition.java:38`）。
- 状态来源（`service/DailyAllianceDonationState.java:53-142`；`:571-597` 兼容 Map/Collection）：
  - `notify_alliance_science_init` → `initializeSciences`：`sciences`(id→{lv,donate}) + `recommend` + `reach_week`（`:394-406`）；
  - `notify_alliance_dev_init/update` → 个人活跃积分 `point`、每日已领 `daily_manual`、周宝箱可领 `week_reward`（`:407-424`）；
  - `notify_alliance_science_exp_update/research_finish` → 单科技更新；`recommend_update` → 推荐科技（`:437-450`）；
  - **免费捐献次数**：`notify_recover_resource_init/update` 的 `data[]` 中 `id==205` 项 → `count/max_count/next_calc_ts/recover/interval`（`:517-535,551-558`），并按服务器时钟投影恢复（`:560-570`）；
  - `notify_alliance_help_*` → 互助请求列表；`notify_alliance_settle_rank_success` → 上周排名资格（`:427-434`）。
- 领取流程（`service/DailyAllianceDonationService.java`）：
  1. 互助：`autoHelpEnabled` 且有可帮 → `RecoveryAutomationExecutor.helpAll`（即 `req_alliance_help_all`），1536/1510/1511 分类（`:422-444,513-525`）；
  2. 奖励：每日 `req_alliance_dev_get_daily`、每周 `req_alliance_dev_get_week`、周捐献排名 `req_alliance_rank_get_rewards{type=2}`，各自开关与状态判定，错误码 40031/40032/40034/40035 处理（`:446-494`）；
  3. 免费科技捐献：`while freeCount>0` 最多 50 次；目标顺序=推荐科技优先+其余随机；`req_alliance_science_donate{id, level+1}`；1528 停/1545 资源不足延迟/可跳过错误码集合（`:496-591`）；
  4. 每次成功 `pauseBetweenAutomaticActions`；失败有退避（`:598-600,717-955`）。
- 默认开关：`DailyAllianceDonationConfig.defaults()` 全 false（`config/DailyAllianceDonationConfig.java:9-12`），即网页版默认不捐不领。

### 3.2 我方实现
`ServerTasks.allianceDonate`（`ServerTasks.java:1111-1213`，默认 true `:123`）：
- 免费科技捐献（`:1118-1156`）：
  - 取 `tc.params("alliance_donate").science_id/science_level`，否则 `notifyData("notify_alliance_science_init").sciences` 用 `Exec.mapsOf` 取第一个（`:1122-1136`）；
  - 有 id 则发一次 `req_alliance_science_donate{id,level}`（`:1137-1148`），无 id 记 **“科技捐献:科技数据未同步”**（`:1149-1151`）；
  - 不读免费次数、不循环、不优先推荐科技。
- 无参领取：每日/每周发展奖励（`:1159-1181`）；排名 `req_alliance_rank_get_rewards{type=rank_type 默认 1}`（`:1182-1199`）。
- 不读 `notify_recover_resource_*`、不读 `notify_alliance_dev_init/update` 状态、无子开关、无 40031/40034 特殊处理（仅 `describeSupplyCode` 映射 1507/40032/40034，`ServerTasks.java:1338-1348`）。

### 3.3 缺口
| # | 缺口 | 证据 | 级别 |
|---|---|---|---|
| D1 | “科技数据未同步”根因：`Exec.mapsOf` 只接受 List；网页版 `maps()/values()` 兼容 Map+Collection | 我方 `Exec.java:65-73`、`ServerTasks.java:1128-1135,1150`；网页版 `DailyAllianceDonationState.java:394-406,571-597` | **P0** |
| D2 | 免费捐献次数来源缺失：不读 `notify_recover_resource_init/update.data[id=205]`，不知道剩几次、何时恢复 | 网页版 `:517-535,551-570`；我方全文无 `notify_recover_resource` | **P0** |
| D3 | 每轮只尝试 1 次捐献（网页版 while freeCount>0，最多 50 次；推荐优先） | 我方 `:1137-1148`；网页版 `DailyAllianceDonationService.java:496-511,527-573` | **P0** |
| D4 | 排名奖励默认 `type=1`，网页版周捐献排名固定 `type=2` | 我方 `:1183-1185`；网页版 `DailyAllianceDonationService.java:42-43,460` | **P1** |
| D5 | 每日/每周/排名奖励无状态前置判断、无子开关，已领仍每轮请求（生产 00:59 “每日发展奖励:今日已捐献” 即反复请求的噪声） | 我方 `:1158-1199`；网页版 `:446-464` | P1 |
| D6 | 错误码语义：`describeSupplyCode` 把 1507 映射“今日已捐献”、40032→“今日已领取”、40034→“本周已领取”，但 40031（每日激励已领）未映射 | 我方 `:1338-1348`；网页版 `DailyAllianceDonationService.java:56-59` | P2 |
| D7 | 互助：我方由常驻 `ServerHelpResponder`（30s 轮询 + `req_alliance_help_all`，`ServerHelpResponder.java:29,181,206`）负责，整轮 `alliance_help` 在 3 分钟内被接管则跳过（`ServerShopLeagueTasks.java:655-687`）→ 与网页版 `autoHelpEnabled` 语义**等价且更强**（网页版默认 false） | 生产 `log:...:alliance_help = 常驻互助接管中，跳过本轮`（预期） | ✅ |

### 3.4 最小修复方案
1. 修 `Exec.mapsOf`/新增 `Exec.mapsAny(Object)`：`Map→values()` 后逐项补 `id=key`（对齐网页版 `DailyAllianceDonationState.maps`）；`kvPairs` 同样兼容 Map。
2. `ServerTasks.allianceDonate` 重写科技段：
   - 取 sciences（Map/List 兼容）、`recommend`；
   - 取 `notify_recover_resource_*` 中 `id=205` 的 `count/max_count/next_calc_ts/recover/interval`，按服务器时间投影 freeCount（可简化：直接用 count，若 `next_calc_ts<=now` 用 `min(max, count+...)`）；
   - `while freeCount>0 && attempts<50`：推荐科技优先，发 `req_alliance_science_donate{id, lv+1}`；1528 → break；1545 → 延迟；可跳过码（`SKIPPABLE_TARGET_CODES` 对齐网页版 `DailyAllianceDonationService.java:68`）换下一个目标。
3. `rank_type` 默认改为 2（或读 App 配置 `alliance_donate.rank_type` 并补 UI 入口，见 G16）。
4. 每日/每周/排名领取前检查 `notify_alliance_dev_init/update` 与 `notify_alliance_settle_rank_success` 状态；已领不再发请求。
5. 子开关（与网页版 enabled/autoHelpEnabled/dailyIncentive/weeklyIncentive/weeklyDonationRank 一一对应）可选；最小可先内部默认“科技捐献开、发展/排名开”，后续加 `tc.subOn`。

---

## 4. 联盟总动员（alliance-mobilization）

### 4.1 网页版
- 触发：10 个 `notify_mobilization_*`（`task/alliancemobilization/AllianceMobilizationTaskModule.java:21`），间隔 5s（`AbstractDailyAutomationTaskDefinition.java:40`）。
- 状态机（`DailyAllianceMobilizationState.java:59-74`）：`notify_mobilization_init` 提供 `activity(stage_ts)`、公共/专属槽位、当前任务、`count`（剩余接取次数）、每日购买计数；`notify_mobilization_activity_update/position_update/quest_update/personal_position_update/score_update/del_receive_info/alliance_init` 增量维护。`active`=stage 3、`boardAvailable`=stage 3/4（`model/DailyAllianceMobilizationSnapshot.java:29-40`）。
- 主流程（`DailyAllianceMobilizationService.java:332-399`）：同步→`claimCompletedTask`（`:514-548`，过期未完成先 `give_up_quest`）→`claimMilestones`（`:576-604`，8413 空奖 300s 记忆）→任务进行中只刷新→空闲时：领免费次数 `req_mobilization_buy_count{count:1}`（仅当 `dailyPurchaseCount==0 && purchaseCost(0)==0`，`:652-658`）→自动接取 `req_mobilization_get_quest`/`req_mobilization_get_personal_quest`（`:690-732`）→需要时付费买次数（显式开关+`maxPaidPurchasesPerDay/maxDiamondSpendPerDay/maxSinglePurchaseCost` 三重预算，`:660-673`）→个人/公共刷新（`req_mobilization_refresh_personal_quest`/`req_mobilization_refresh_quest`，带每小时/每日限频与延迟，`:733-886`）。协议清单 `:59-67`。
- 错误码表（`:1232-1311`）：8401 活动未开放、8402/8404/8406 未参加、8405 条件不足、8408 次数不足、8409 无进行中任务、8410 未完成、8411/8407 槽位状态变化→重同步、8413 无里程碑、8414 今日购买上限。
- 默认配置：`DailyAllianceMobilizationConfig.defaults()` 全关（`config/DailyAllianceMobilizationConfig.java:34-36`）；付费购买必须有预算才能开启（`:1313-1326` 校验）。
- 依赖目录：`catalog/alliance-mobilization.json`（793KB，`purchaseCosts` 见 §5 误花证据）。

### 4.2 我方
`ServerShopLeagueTasks.mobilization`（`ServerShopLeagueTasks.java:581-625`，默认 true `ServerTasks.java:124`）：
- `req_mobilization_open_info` → 失败即 “总动员未开启(码7401)” 跳过整轮（`:586-589`）；
- 成功则：从 `notify_mobilization_init` 的 `quests/quest_list/data` 用 `Exec.mapsOf`（List-only！）找 `progress>=target` 发 `req_mobilization_get_quest_reward{pos}`（`:591-610`）；
- **无条件** `req_mobilization_buy_count{count:1}`（`:611`）；
- `req_mobilization_batch_get_milestone`，8413 视为空（`:613-618`）。

### 4.3 缺口
| # | 缺口 | 证据 | 级别 |
|---|---|---|---|
| M1 | 无条件购买接取次数 → 活动开放时第 2/3/4 次将花 50 钻、第 5-7 次 200 钻…（价目 `[0,50,50,50,200,200,200,1000,1000,1000]`） | 我方 `:611`；网页版 `DailyAllianceMobilizationService.java:652-673`；`catalog/alliance-mobilization.json` | **P0（误花钻石，现 7401 未触发）** |
| M2 | 无自动接取/刷新的完整主流程，只有“领已完成任务奖励+里程碑” | 我方 `:591-618`；网页版 `:332-399,690-886` | P1（活动开放时漏领/漏做任务） |
| M3 | 无窗口/阶段判定（stage_ts），7401 后当天不再复查；生产窗口轮只在 05:00-09:00 → 活动白天开放会整天空转 | 我方 `:586-589`；网页版 `DailyAllianceMobilizationSnapshot.java:29-40`；`TaskCadence` 未部署 | P1 |
| M4 | `notify_mobilization_init` 的 quests 兼容性（`Exec.mapsOf` List-only 同 D1）；`pos` 字段来源与 8409 清理未做 | 我方 `:594-607`；网页版 `:514-574` | P1 |
| M5 | 缺 catalog/alliance-mobilization.json（任务选择器/里程碑/价目表）→ 无法做 1:1 选择与预算 | 我方 assets 无该文件；网页版 jar `catalog/alliance-mobilization.json` | P1 |

### 4.4 最小修复方案
1. **立即安全化**：删除/门控 `:611` 的 `buy_count`——仅当能读到“今日购买计数==0 且首购免费”时调用（需要状态机才能判断）；无法判断时**不要买**（宁漏不花）。
2. 引入最小 `notify_mobilization_init` 状态缓存（activity id/stage_ts/count/dailyPurchaseCount），`open_info` 仅在静态数据缺失时调用；7401 归“未开放，等推送唤醒”，不要每轮打。
3. 接入 `catalog/alliance-mobilization.json`（从网页版 jar 复制资源，只读复制即可）后逐步补 claimMilestones 前置判断/自动接取/刷新（可用 `TaskCadence` 的 15s 节拍）。
4. 子开关与网页版对齐：reward/count/milestone + 付费购买显式开关与预算字段。

---

## 5. 联盟争霸赛（alliance-showdown）

### 5.1 网页版
- 触发：`notify_alliance_showdown_activity` / `notify_alliance_showdown_global_activity`（`task/allianceshowdown/AllianceShowdownTaskModule.java:21`）；节奏 30s + TIME_WINDOW 策略（`AbstractDailyAutomationTaskDefinition.java:41`）。
- 状态刷新（`DailyAllianceShowdownService.java:181-192`）：`req_alliance_showdown_activity_info` → 如赛季已报名且有 aid 再 `req_alliance_showdown_group_info{season,aid}` + `req_alliance_showdown_battle_report{reportid}` → `req_alliance_showdown_alliance_info`。
- 报名窗口（`:290-298`）：`signupDay∈{周一,周二}`、`signupTime HH:mm`、周二截止 23:00；`scheduledOccurrence` 计算本周窗口；默认 `MONDAY 00:00`、path=2、编队 AUTOMATIC、5:2:3（`config/DailyAllianceShowdownConfig.java:51-53`；校验 `:22-49`）。
- 执行（`:118-179`）：开关关闭→SKIP；离线→SKIP；状态未就绪→SKIP；未到窗口→SKIP；本季已报名→SKIP；否则 refresh 后再次确认已报名 → `signupWithin`：path(1-3)+formation（AUTOMATIC 按 5:2:3 或 ROLE 指定编队）+ 可选宠物战斗技 → `req_alliance_showdown_apply{type,formation{hero_id,soldier}}`（`:207-224`）；7705 超时/401 兵不足 分别 SKIP。
- 默认开关 false（`DailyAllianceShowdownConfig.java:51-53`）；自动可报名需在线+已初始化+未报名+过窗口（`:300-313`）。

### 5.2 我方
`ServerShopLeagueTasks.showdown`（`ServerShopLeagueTasks.java:627-653`，默认 false `ServerTasks.java:125`）：
- `req_alliance_showdown_activity_info` 失败 → “争霸赛未开启(码x)”（`:631-633`）；
- `apply==1` 或 `is_apply=="true"` → “已报名”（`:636-639`）；
- 否则 `Exec.formation(tc.engine, 1)`（固定 1 号编队）发 `req_alliance_showdown_apply{type:1, formation}`（`:641-645`）。

### 5.3 缺口
| # | 缺口 | 证据 | 级别 |
|---|---|---|---|
| S1 | 无报名窗口（周几/几点/赛季冷却），开启后每轮都尝试，可能错过周一窗口或非窗口期反复报错 | 我方 `:627-652`；网页版 `:290-313`；`TaskCadence=30s`（未部署） | **P1** |
| S2 | 无状态前置：不读 state（season/stage/signedUp/group/alliance），只读一次性响应 `apply` 字段；无 7705/401 处理 | 我方 `:635-646`；网页版 `:136-151,161-168` | P1 |
| S3 | 编队固定 1 号、路径固定 1（网页版默认 path=2、AUTOMATIC 5:2:3、可选宠物技） | 我方 `:641-643`；网页版 `:152-158,207-224` | P1 |
| S4 | 不刷新 group/battle_report/alliance_info，无战报数据 | 网页版 `:181-205` | P2 |
| S5 | 默认开关 false 与网页版一致 ✅（生产“配置未启用”属预期） | `ServerTasks.java:125` vs `DailyAllianceShowdownConfig.java:51-53` | ✅ |

### 5.4 最小修复方案
1. 增加账号级配置（或先用常量对齐网页版默认）：`signupDay/signupTime/path/formationMode`；`performClaim` 开头判定“本周已过窗口 / 本季已报名 / 未到窗口” → SKIP。
2. 状态读取对齐 `DailyAllianceShowdownState`（可只保留 season/stage/signedUp 三字段），用于赛季去重；处理 7705（超时重试）401（兵不足）。
3. 编队默认 path=2 + AUTOMATIC 5:2:3（复用 `Exec.formation` 的自动编队能力，若已有）或至少读取角色编队配置。
4. 若短期无法完整实现，保持默认 false 并在 UI 注明“服务器端未完成，请勿开启”。

---

## 6. 商店（shop / alliance_shop，12 店铺）

### 6.1 网页版结构
- 10 个 `ShopStage`（`service/DailyShopService.java:1902-1938`）：801 流动商人、401 联盟每日、501 联盟每周、701 统帅、1001 兵工厂、901 争霸赛、1201 最强王国、301 探险、2001 地心、601 神秘；另 1101 火晶微粒兑换为手动/提炼内兑换（`:200-250`，`DailyFireCrystalRefineService.java:195`），2401 乱斗商店在乱斗活动领取内（`DailyBrawlWarService.java:377` 调 `autoBuyBrawlWarShopWithinClaim`）→ **12 店铺 = 10 自动购买 stage + 1101 手动兑换 + 2401 活动内购买**。
- 商品目录两份资源：
  - `config/daily-shop-catalog.json`：429 个 offer / 542 个 configId（vendor 324、alliance-daily 57、alliance-weekly 40、brawl-war 8），`DailyShopCatalog.java:24-30,44-55`；
  - `config/daily-town-shop-catalog.json`：400 项（commander 51、arsenal 55、hegemony 45、cross-server 18、adventure 79、climbtower 21、mysterious 124、crystal-translation 7），`DailyTownShopCatalog.java:28-42,50-60`；8 个 town 店铺定义 `DailyTownShopDefinition.java:12-19`。
- 货架拉取：`req_shop_info_array{arr_id}`（周期店）或 `req_shop_info{shop_id}`（固定店）；商品条目字段 `id/conf_id/buy_count/grid_id`（`model/DailyShopStateGoods.java:11-14`）；目录匹配顺序 `configId → id → gridId`，且 scope/shopId 必须一致（`DailyShopCatalog.java:44-50`、`DailyTownShopCatalog.java:50-60`）。
- 购买（`:595-629,1172-1202`）：一次 `req_shop_buy{shop_id,goods_id,count:1}` 只买 1 件；前置校验：
  - 限购 `remaining = offer.limit - goods.buyCount`（`:615-618,1181`）；
  - 币种余额 `budgets[currencyId] >= price`（`:620-622,1183-1185`；预算来源 `currentBudgets` = 世界资源 101-105/109-113/272/600020，`:1221-1227`）；
  - 801 钻石购买预算 `vendorDiamondPurchaseLimit`（默认 0，`:856-857,872-874`）；
  - 601 神秘徽章预算 `mysteriousBadgePurchaseLimit`（默认 0，`:951-952,967`）；
  - 条件不满足错误码 800-830/10521/40240 → 当日该商品标记 blocked 不再重试（`:1037-1051,864-871`）；
  - pending 检查点（买/刷新前落盘，崩溃/重启后先对账再继续，`:1247-1341`）。
- 刷新（`:849-890,943-983,1442-1455`）：
  - **只有 801 和 601 可刷新**；801 次数→费用表 `VENDOR_REFRESH_COSTS=[0,0,0,50,100,100,200,200,500,1000]`（前 3 次免费，第 4 次起 50 钻…；`:84`）；601 费用表 `MYSTERIOUS_REFRESH_COSTS=[0,100,200,...,2000]`（首次免费；`:85`）；
  - 付费刷新需 `vendorDiamondRefreshEnabled` + `paidRefreshes(shop) < vendorDiamondRefreshLimit(0-7)` + 钻石余额 ≥ 费用（`:879,1449-1451`）；601 同理 `mysteriousDiamondRefreshEnabled/limit(0-20)`（`:972,1453-1455`）；
  - 其余店铺不刷新，靠服务器每日/每周重置（`secondsUntilNextDay`，`DailyShopState.java:66-80`）。
- 阶段化与幂等：每阶段完成后写 fingerprint，当天不重复采购；周期店只“检查一次/天”；`:1062-1143,1902-1938`。
- 默认与推荐：`DailyShopConfig.defaults()` 全 false（`config/DailyShopConfig.java:45-47`）；`daily-shop-recommended-offers.json` 提供安全推荐清单：vendor 285、mysterious 22（**恰为全部 price==0 的神秘商品**）、alliance-daily 33、alliance-weekly 0（`DailyShopRecommendationService.java:29-48`；本地比对见 §6.3）。

### 6.2 我方实现
`ServerShopLeagueTasks.buyFromShops`（`ServerShopLeagueTasks.java:159-256`，`shop`/`alliance_shop` 默认 false `ServerTasks.java:129-130`）：
- 店铺列表：`SHOP_IDS_TOWN={801,701,1001,901,1201,301,2001,601,1101,2401}`、`SHOP_IDS_ALLIANCE={401,501}`（`:148-149`）→ 12 店铺全覆盖；
- 参数：只读 `p.buys`（configId→数量），**不读 App 新 UI 的 `buy_names`**（`:163-173` vs App `ShopTasks.java:243,266-320`；UI 写 `SubCardUi.java:601-614`）；
- 拉货架 `req_shop_info_array`（一次拉 10/2 个店，`:189-190`）；按 `conf_id` 匹配本地目录、`buy_count/limit` 算 remaining（`:205-215`），一次 `req_shop_buy{count}` 批量买（`:216-230`）；
- 刷新：`refresh.rounds≤5`、`target_purchases`、`allow_diamond`（`:180-186`）；每轮刷新**对 shopIds 全量**发 `req_shop_refresh`（`:235-248`），只有 `code==51 && !allow_diamond` 才停；
- 无币种余额校验、无 801/601 预算、无免费 3 次计次、无 pending 对账、无条件 blocked 持久化。
- App 端本地实现在 name 模式上更完整：`buy_names` + 实时价防御 + 前 3 次免费/钻石门控（`ShopTasks.java:175-320`），但服务端未移植。

### 6.3 12 店铺逐项对照表
| # | shopId | 网页 scope/名称 | 币种 | 网页刷新策略 | 我方（server）行为 | 差异 |
|---|---|---|---|---|---|---|
| 1 | 801 | vendor 流动商人 | 101 钻石 / 102-105 资源 | 前 3 次免费；之后 50/100/100/200/200/500/1000，需开关+次数上限+余额 | 在 `shopIds` 内；每轮都 refresh，最多 rounds≤5；`allow_diamond` 控制是否继续 | **P0**：无免费 3 次计次/无钻石预算，可能花钻石；不区分费用表 |
| 2 | 401 | alliance-daily 联盟每日 | 110 | 不刷新（每日重置） | 在 `alliance_shop` 列表；也会被 refresh | P1：对不可刷新店发刷新协议 |
| 3 | 501 | alliance-weekly 联盟每周 | 110 | 不刷新（每周重置） | 同上 | P1 |
| 4 | 701 | commander 统帅商店 | 101 钻石 | 不刷新 | 在 `shop` 列表；按目录价购买，无钻石预算 | P1：钻石店无预算门控 |
| 5 | 1001 | arsenal 兵工厂 | 112 军工币 | 不刷新 | 在 `shop` 列表 | ✅ 目录/限购一致；缺余额校验 |
| 6 | 901 | hegemony 争霸赛商店 | 111 徽章 | 不刷新 | 在 `shop` 列表 | ✅ 同上 |
| 7 | 1201 | cross-server 最强王国 | 113 太阳币 | 不刷新 | 在 `shop` 列表 | ✅ 同上 |
| 8 | 301 | adventure 探险商店 | 109 竞技币 | 不刷新 | 在 `shop` 列表 | ✅ 同上 |
| 9 | 2001 | climbtower 地心商店 | 272 萤焰石 | 不刷新 | 在 `shop` 列表 | ✅ 同上 |
| 10 | 601 | mysterious 神秘商店 | 600020 神秘徽章 | 首次免费，之后 100…2000；需开关+上限+余额；另有徽章购买预算 | 在 `shop` 列表；每轮 refresh 无门控 | **P1**：可能误用钻石刷新；无徽章预算 |
| 11 | 1101 | crystal-translation 火晶微粒兑换 | 100081 火晶 / 106 钢 | 不自动购买（手动/提炼内兑换） | 在 `shop` 列表，若 buys 配置了 1101 的 confId 会被自动买 | P1：属手动兑换，自动买语义未对齐 |
| 12 | 2401 | brawl-war 乱斗商店 | 620288 乱斗币 | 不刷新；属乱斗活动 claim 内自动购买 | 在 `shop` 列表，按 buys 买（不校验活动状态/预算） | P1：应在乱斗活动内（`DailyBrawlWarService.java:377`） |

**目录匹配（942/942 完全一致，✅ 已实证）**：
- 我方 `app/src/main/assets/towndev/shops.json`：12 店 942 商品。
- 网页版 `daily-shop-catalog.json`(542 configId) + `daily-town-shop-catalog.json`(400) = 942。
- 逐 configId 对比 `itemId/price/limit/count/currencyId/name`：**0 处不一致、0 缺失/多余**。
- 复现：见 §7.3（只读 unzip jar + python 比对）。

**币种/限购对照**：网页版每店币种单一（见上表）；我方目录币种字段与网页一致，但**购买前不做余额判断**，只靠服务端报错 → 不会超花（服务端拒绝）但会刷错误日志；真正的超花在“钻石店 + 多 variant 重复购买”（6.4）。
**刷新免费次数**：网页版 801 前三免 / 601 首免，并有每日付费上限；我方只按 `rounds` 循环，`allowDiamond=false` 时遇 code=51 停止（`:242-246`）——**免费次数用完后 code 是否为 51 未经协议实证**（设计文档 §3.4 L105-108 亦指出该点），所以“默认不花钻石”只成立到“假设服务端拒绝即停”，缺少主动预算保护。
**默认开关安全性**：`shop=false/alliance_shop=false` 与网页版默认 false **一致** ✅；区别在于网页版有 285/22/33 条“推荐安全清单”可一键勾选，我方没有该清单，且 App UI 写入的 buys 是“全 variant”，服务端又忽略 `buy_names` → **一旦用户勾选，服务端行为与 App 期望不一致**（见 6.4）。

### 6.4 商店缺口与最小修复
| # | 缺口 | 证据 | 级别 |
|---|---|---|---|
| P1 | 服务端不实现 `buy_names` 名称模式：App 按“名字”只买最便宜一档×1，`buys` 只是兼容字段，但 UI 把该名字**所有 variant confId 都写进 buys**（各 1）；服务端只按 buys 逐 configId 买 → 同一物品买多份/买贵档/跨店重复 | 我方 `ServerShopLeagueTasks.java:163-173,205-230`；App `ShopTasks.java:240-320`；UI `SubCardUi.java:601-614` | **P0（开启即错买）** |
| P2 | 无币种余额/钻石预算校验：801/701 钻石店与 601 徽章店没有 `vendorDiamondPurchaseLimit` 等保护 | 网页版 `DailyShopService.java:615-622,856-857,951-952,1221-1227`；我方 `:205-231` | **P0（误花钻石）** |
| P3 | 刷新循环对 12 店铺全量发 `req_shop_refresh`，且无“前 3 次免费/601 首免/付费上限”计次 | 我方 `:235-248`；网页版 `:84-85,849-890,943-983,1442-1455` | **P0（误花钻石/无效请求）** |
| P4 | 无 pending 对账（买/刷新前落盘），断线重连后可能重复购买 | 网页版 `:1247-1341` | P1 |
| P5 | 无条件 blocked 未持久化：800-830/10521/40240 每次重试 | 网页版 `:1012-1051`；我方 `:223-229` 仅 skip | P1 |
| P6 | 缺少推荐安全清单资源（vendor 285/mysterious 22(全 0 价)/alliance-daily 33） | 网页版 `DailyShopRecommendationService.java:29-48`；我方 assets 无 | P1 |
| P7 | 1101/2401 语义错位（手动兑换/活动内购买） | 网页版 `:200-250`、`DailyBrawlWarService.java:377`；我方 `:148,159-256` | P1 |
| P8 | 实时价未读取（App 有 `livePriceOf` 防御，服务端按目录价算 remaining/计数） | App `ShopTasks.java:161-168`；网页版 `buildCatalogGoods` 用目录 price（`DailyShopService.java:645-651`） | P2（服务端会按真实价扣，风险低） |
| P9 | `shop` 用户勾选清单无法在服务端安全执行时，仍会“假成功”（只看返回码） | 我方 `:216-230` | P2 |

**最小修复方案（文件+函数级）**
1. **先加安全闸（P0，1 天级）**：`ServerShopLeagueTasks.buyFromShops` 入口检测 `p.optJSONObject("buy_names") != null` → 若服务端尚未实现名称模式，**直接 SKIP 并提示“服务器商店执行器暂不支持按名称采购，请先改用 App 本地模式或等待升级”**，避免按全 variant 重复购买。这是最小、零风险止血。
2. 移植 App 名称模式到服务端（`ServerShopLeagueTasks` 新增 `buyWantedPass`，参照 `app/.../ShopTasks.java:175-216`）：
   - 用 `catalog name` 分组，选实时货架中同名最低价条目，`count=1`；
   - 每名字每轮只买 1 次（bought set），限购按 `buy_count+1<=limit`；
   - 仅对 801 做刷新循环：前 3 次无条件免费；之后需 `refresh_diamond` 开关 + 当日已用次数 `< max`，且刷新前校验钻石余额 ≥ 下一档费用（内置 `VENDOR_REFRESH_COSTS`）。
3. 币种/预算：内置币种表 + 从 `notify_resource_init`/`capture` 世界资源读余额；801 增加 `vendorDiamondPurchaseLimit`（默认 0），601 增加 `mysteriousBadgePurchaseLimit`（默认 0），钻石店（701）默认不买除非显式预算；不足即 skip。
4. 刷新只对 801（+601 若实现神秘店）发；其余店铺不刷新。
5. 增加 pending 落盘与 blocked（按天）持久化（对齐网页版检查点）。
6. 资源清单：把 `daily-shop-recommended-offers.json` 转成我方 assets（或从网页版 jar 复制），UI 增加“一键勾选推荐”，默认只勾：
   - mysterious 22 个 0 价商品（纯免费，安全）；
   - vendor 285 条（不含 39 条钻石/高价条目）；
   - alliance-daily 33 条（联盟币，无钻石）。
   默认开关仍保持 false，避免自动开销。
7. 1101 从 `shop` 自动购买范围移除（保留手动兑换），2401 移到乱斗活动 claim（或在 shop 中加活动状态校验）。

---

## 7. P0 清单（按“每日漏领/可安全自动化收益”排序）

| 排序 | P0 | 功能 | 收益/风险 | 复现证据 | 修复入口 |
|---|---|---|---|---|---|
| 1 | C1+C2 宝箱只读 init、忽略 add、type 口径错 | 联盟宝箱 | 登录后新箱全天漏领；跨分类漏领（trophy/brilliant）；187 级收益已验证存在 | 生产 state：`log:48460403:888:alliance_chest` 10-22 05:45 `CLAIMED×187`；10-23 00:59 `SKIPPED 无宝箱列表`；`vault-20261023.log` 有多条 `notify_alliance_chest_add`（2031/2609/3212/3237/3278/3332/3343） | `ServerTasks.allianceChest`（1057-1109）重写状态合并+分类+批量 |
| 2 | D1+D2+D3 科技捐献解析/免费次数/循环 | 联盟捐献 | 免费捐献次数每日恢复（网页版可连续捐到 0），我方整轮 0 次 + “科技数据未同步”；发展/排名奖励已领噪声 | 生产 state：`log:...:alliance_donate` 多轮 `科技捐献:科技数据未同步`；`vault.json` 无 alliance_donate 参数；日志中 `notify_alliance_science_init` 已到达（`:569`、`:3253` 列表） | `Exec.mapsOf` + `ServerTasks.allianceDonate`（1111-1213） |
| 3 | P1+P2+P3 商店开启即错买/误花钻石 | 商店 | `shop/alliance_shop` 默认 false 安全；一旦开启：全 variant 重复买、无币种/钻石预算、对 12 店发刷新 → 真实经济风险 | 我方 `ServerShopLeagueTasks.java:163-173,205-248`；App `ShopTasks.java:240-320`；UI `SubCardUi.java:601-614`；网页版 `DailyShopService.java:84-85,615-622,849-983,1221-1227,1442-1455` | 先加 `buy_names` 安全闸（SKIP），再移植名称模式+预算 |
| 4 | M1 总动员无条件 buy_count | 联盟总动员 | 活动开放后每轮买次数：第 2/3/4 次各 50 钻、第 5-7 次 200 钻、8-10 次 1000 钻 | 我方 `ServerShopLeagueTasks.java:611`；价目 `catalog/alliance-mobilization.json`；网页版 `DailyAllianceMobilizationService.java:652-673`；当前生产 7401 未触发 | 先删/加免费判定（只买第 0 次免费） |
| 5 | M2/M3 总动员缺自动接取/刷新/窗口 | 联盟总动员 | 活动开放当天完全漏做（现 7401 属未开放，无法从日志判断开放期表现） | 我方 `:586-618`；网页版 `:332-399,690-886`；生产每轮 `总动员未开启(码7401)` | 状态机+catalog（见 §4.4） |
| 6 | S1/S2/S3 争霸赛无窗口/无状态 | 联盟争霸赛 | 开启后可能非窗口期反复请求/错过报名；已报名判断粗略 | 我方 `:627-652`；网页版 `:290-313`；生产 `配置未启用`（默认 false） | 配置+窗口+状态（见 §5.4） |
| 7 | 节奏/唤醒未部署 | 全部联盟/商店 | 现网仅 05:00-09:00 跑一轮；联盟宝箱(120s)/捐献(30s)/商店(60s) 日间节奏失效；网页版靠推送唤醒 | 生产日志无“任务节奏调度”启动行；`TaskCadence.java` 有表但 `TaskCadenceScheduler` 无 notify 唤醒；`PushWakeDriver` 仅 App 本地 | 部署 TaskCadence + 增加会话消息钩子按任务兴趣协议唤醒（对齐网页版 `interestedProtocols`） |

> 交叉组备注：`backpack`（“背包数据未同步”）与我方 `notify_backpack_init.data` 的 Map/List 解析同源（`ServerShopLeagueTasks.backpack:760-807`，`Exec.kvPairs:76-83`），归奖励组，本组只标注不重复实现；`homeland_visit` 与网页版协议/阈值一致（`LeagueTasks.java:260-335` vs `DailyHomelandVisitService.java`，15026/15028/MAX_CANDIDATES=20/300s），生产 1507 属“排行未开放”合理 SKIP，暂无缺口。

---

## 8. 附录 A：file:line 索引（网页版）

**联盟宝箱**
- `modules/daily/task/alliancechests/AllianceChestsTaskModule.java:21`（4 个触发协议）
- `modules/daily/service/DailyAllianceChestService.java:41-49`（协议/错误码）、`:82-93`（automaticClaimable）、`:106-134`（分类与 trophy 截断）、`:136-170`（by_ids 批量+5s is_end 等待）、`:154-157`（1529）、`:158-160,172-197`（收敛错误→逐条）、`:214-216`（1540/1541/1542/1543）
- `modules/daily/service/DailyAllianceChestState.java:48-65`（协议 switch）、`:69-83`（game_init 重置）、`:104-118`（pending 30s）、`:132-150`（trophy 上限/日切）、`:171-194`（入盟）、`:196-254`（init/add/update/delete/brilliant）
- `modules/daily/service/DailyAllianceChestCategory.java:11-13`（分类+cid 区间+type）
- `modules/daily/model/DailyAllianceChestStateSnapshot.java:25-43`（ready/inAlliance/claimable/trophyRemaining）
- `modules/daily/model/DailyAllianceChestStateChest.java:30-40`（merge）
- `modules/daily/config/DailyAllianceChestConfig.java:25-33`（默认 false + 全分类）

**联盟捐献**
- `task/alliancedonation/AllianceDonationTaskModule.java:21`（18 个触发协议）
- `service/DailyAllianceDonationService.java:39-68`（协议/错误码/可跳过码）、`:308-348`（performClaim）、`:350-377`（automaticClaimable）、`:422-444`（互助）、`:446-494`（发展/排名奖励）、`:496-511`（捐献循环 50 次）、`:527-573`（目标排序：推荐优先）、`:575-591`（1528/1545/1510）、`:652-695`（状态文案）
- `service/DailyAllianceDonationState.java:53-142`（协议 switch）、`:394-406`（sciences/recommend/reach_week）、`:407-424`（dev 激励）、`:425-434`（周排名资格）、`:517-535`（recover_resource → freeCount）、`:551-570`（恢复字段/投影）、`:571-597`（Map/Collection 兼容 `maps()`）
- `config/DailyAllianceDonationConfig.java:9-12`（全 false）

**联盟总动员**
- `task/alliancemobilization/AllianceMobilizationTaskModule.java:21`（10 个触发协议）
- `service/DailyAllianceMobilizationService.java:59-67`（协议清单）、`:332-399`（主流程）、`:514-548`（领已完成/放弃过期）、`:576-604`（里程碑）、`:652-658`（免费次数）、`:660-673`（付费购买三预算）、`:690-886`（接取/刷新）、`:1232-1311`（错误码表 8401…）
- `service/DailyAllianceMobilizationState.java:59-74`（协议 switch）
- `model/DailyAllianceMobilizationSnapshot.java:29-40`（active/boardAvailable/stage 由 stage_ts 推导）
- `config/DailyAllianceMobilizationConfig.java:34-36`（默认全关）、`:1313-1326`（付费购买校验）
- jar 资源：`catalog/alliance-mobilization.json`（`purchaseCosts:[0,50,50,50,200,200,200,1000,1000,1000]`）

**联盟争霸赛**
- `task/allianceshowdown/AllianceShowdownTaskModule.java:21`
- `service/DailyAllianceShowdownService.java:49-56`（5 协议）、`:118-179`（performClaim：窗口/已报名/7705/401）、`:181-205`（刷新 group/战报）、`:207-224`（报名提交）、`:290-298`（窗口计算）、`:300-313`（automaticClaimable）
- `config/DailyAllianceShowdownConfig.java:22-49`（校验）、`:51-53`（默认 MONDAY 00:00/path=2/AUTOMATIC/5:2:3）

**商店**
- `task/shops/ShopsTaskModule.java:21`（触发 notify_game_init/notify_server_time）
- `service/DailyShopService.java:72-116`（常量：店铺/币种/刷新费用表/预算/检查点键）、`:141-185`（status）、`:595-629`（手动买+限购+余额）、`:661-687`（performClaim/automaticClaimable）、`:721-786`（runShopCycle/evaluate）、`:788-847`（shouldSkip）、`:849-890`（801 购买+刷新）、`:892-915`（联盟固定店）、`:917-941`（周期店）、`:943-983`（601 购买+刷新）、`:985-1006`（周期候选）、`:1012-1051`（条件不满足当日拦截）、`:1053-1060`（town 单买）、`:1145-1170`（阶段完成判定）、`:1172-1202`（候选/单买）、`:1221-1227`（币种预算来源）、`:1247-1361`（pending/对账）、`:1442-1455`（刷新费用/付费次数）、`:1902-1938`（ShopStage 12 店顺序）
- `service/DailyShopCatalog.java:24-30`（资源路径）、`:44-55`（目录匹配+scope 校验）
- `service/DailyTownShopCatalog.java:28-60`（8 个 town 店铺目录/匹配）、`:118-124`（offerKey/selectionKey/selected）
- `service/DailyTownShopDefinition.java:12-19`（701/1001/901/1201/301/2001/601/1101 + 币种 + refreshable）
- `service/DailyShopState.java:27-50`（info/info_array 解析）、`:66-80`（次日刷新秒数）、`:134-147`（货架快照）
- `service/DailyShopRecommendationService.java:29-48`（推荐清单 vendor/mysterious/alliance-daily/weekly）
- `config/DailyShopConfig.java:11`（25 字段）、`:45-47`（默认全 false）、`:58-60`（anyEnabled）
- `service/DailyBrawlWarService.java:377`（2401 乱斗商店在活动内购买）
- `service/DailyFireCrystalRefineService.java:195`（1101 火晶钢粒子兑换在提炼内）
- 资源：`config/daily-shop-catalog.json`（429 offer/542 id）、`config/daily-town-shop-catalog.json`（400 项）、`config/daily-shop-recommended-offers.json`（285/22/33/0）

**引擎/节奏/唤醒**
- `model/AbstractDailyAutomationTaskDefinition.java:32-73`（任务间隔表）、`:37-41,50`（联盟/商店）
- `task/AbstractStandardTaskModule.java:65-90`（状态机 decide）、`:92-107`（ready→execute/sleep）、`:127-137`（命令完成睡眠）、`:160-171`（claimable/executable）、`:208-215`（awakened/reconcile）
- `engine/TaskEngine.java:464-476`（信号状态迁移）
- `engine/DailyTaskEngineBridge.java:193-225`（推送→PROTOCOL_NOTIFICATION）
- `engine/TaskSignalBus.java:50`（取消/去重）
- `core/account/GameAccountSessionService.java:1602-1660`（登录序列）
- `modules/daily/service/DailyInitialDataSyncService.java:38-40`（登录期等待的 init 协议清单）

## 附录 B：file:line 索引（我方）

**联盟**
- `vault-server/.../task/ServerTasks.java:122-123`（默认开）、`:1057-1109`（allianceChest）、`:1060-1061`（只读 init/update）、`:1062`（mapOf/values）、`:1079-1083`（type 硬编码+逐条领取）、`:1088`（错误码）、`:1111-1213`（allianceDonate）、`:1122-1136`（读 sciences）、`:1137-1148`（单次捐献）、`:1150`（科技数据未同步）、`:1159-1199`（发展/排名奖励+rank_type）、`:1338-1348`（错误码文案）
- `vault-server/.../task/Exec.java:65-73`（mapsOf 仅 List）、`:76-83`（kvPairs 仅 List{ k,v }）
- `vault-server/.../task/Modules.java:67-73`（联盟模块）、`:90-96`（商店模块）、`:138-143`（默认取 ServerTasks）
- `vault-server/.../task/TaskCadence.java:85-93`（联盟/商店间隔）
- `vault-server/.../task/TaskCadenceScheduler.java:22-49`（仅定时、无 notify 唤醒）、`:94-114`（启停/日志）
- `app/.../daily/LeagueTasks.java:20-98`（App 总动员）、`:102-142`（App 争霸赛）、`:146-191`（App 互助）、`:195-256`（App 背包）、`:260-335`（App 晨曦岛拜访）
- `app/.../daily/PushWakeDriver.java:33-62`（App 推送唤醒表，含 mobilization/showdown）
- `vault-server/.../ServerHelpResponder.java:29`（30s 轮询）、`:181`（help_all）、`:206`（help_all_ts 落状态）

**商店**
- `vault-server/.../task/ServerShopLeagueTasks.java:148-149`（12 店铺 id）、`:151-157`（shop/alliance_shop 入口）、`:159-256`（buyFromShops）、`:180-186`（refresh 参数）、`:189-190`（info_array）、`:205-215`（conf_id 匹配/限购）、`:216-230`（buy）、`:235-248`（全店刷新 + code 51 停止）、`:250`（无勾选文案）
- `app/.../daily/ShopTasks.java:150-151`（店铺 id）、`:175-216`（name 模式单次采购：最便宜一档×1）、`:240-320`（buy_names + 前 3 免费 + 钻石门控 + 实时刷新）、`:321-357`（旧 configId buys 模式）
- `app/.../daily/SubCardUi.java:520-614`（选品 UI/写 buys 全 variant + buy_names）
- `vault-server/.../automation/services/ShopService.java:11`（仅注释/策略未接）

**引擎/节奏**
- `app/.../wjdr/WjdrEngine.java:154`（notifies 缓存）、`:651-682`（登录前挂钩收推送）、`:684-712`（collectNotifies/notifyContent/notifyData）、`:389-458`（登录序列）
- `vault-server/.../task/RoundRunner.java:38-46`（默认开关/配置未启用）、`:57`（r.durationMs 由 executor 计）
- `vault-server/.../task/ModularRoundRunner.java:60-98`（模块化整轮/单任务）

---

## 9. 待办 / TODO（未完成取证，后续 1:1 复刻时优先验证）

1. **宝箱 init 的真实形状**（Map vs List）：生产只存最新 content 无 dump；建议下轮在只读 sidecar 打印一次 `notify_alliance_chest_init.content` 类型（不改源码，可用现有 ProtoLogger 的 notify dump 能力）。当前按“两者都兼容”实现即可。
2. **商店刷新 code=51 语义**：设计文档 §3.4 L105-108 指出未实证“第 4 次刷新返回码”；实现预算保护时不依赖该码。
3. **801 实时价字段**（price/cur_price/now_price/discount_price）：App 已做防御提取（`ShopTasks.java:161-168`），服务端未读；复刻时保留防御。
4. **联盟宝箱奖励文本口径**（RewardText）：服务端目前不解析领取奖励明细（`rewards` 字段），UI“领取内容”为空；建议按 `chest.rewards` 合并后写 TaskOutcome.rewards。
5. **角色/session 口径**：生产 10-23 00:59 轮在 48460403 sid 888 上记录“无宝箱列表”，与当天该号另一角色(炉13/主角色70)相关；复刻前先确认自动化跑的是哪个 role uid（`state.srv:roleuid:*` 与 SessionKeeper 绑定），避免把“角色差异”误判成协议差异。
6. **TaskCadence 部署**：本分支代码有节奏表但生产 jar 无“任务节奏调度已启动”日志；上线前需 Lead 确认部署窗口，并补相应用例（`TaskCadence` 已含 size/interval 断言可扩展）。

---

## 10. 复现命令（只读）

```bash
# 1) 生产 state 中相关任务日志
ssh -o BatchMode=yes vault-vps 'python3 - <<PY
import json,re
s=json.load(open("/opt/vault-server/data/state.json"))
for fn in ["alliance_chest","alliance_donate","alliance_mobilization","alliance_showdown","alliance_help","shop","alliance_shop","backpack","homeland_visit"]:
    for k in s:
        if re.match(r"log:[^:]+:[^:]+:"+fn+"$",k):
            print(k, json.loads(s[k])[0])
PY'

# 2) 生产 roleConfig / 默认开关
ssh -o BatchMode=yes vault-vps 'python3 -c "import json;v=json.load(open(\"/opt/vault-server/data/vault.json\"));[print(a[\"fpid\"],a[\"roleConfig\"]) for a in v[\"accounts\"]]"'

# 3) 推送实据（登录批次/新箱/科技）
ssh -o BatchMode=yes vault-vps 'grep -nE "notify_alliance_chest_(init|add)|notify_alliance_science_init|notify_recover_resource_init" /opt/vault-server/data/logs/vault-20261023.log | head -20'

# 4) 942 商品目录与网页版逐字段比对（只读，jar 只解压不执行）
cd /tmp && mkdir -p wjdr_shop_audit && cd wjdr_shop_audit
unzip -p /Users/liangguansheng/ZCodeProject/wjdr-review/server/wjdr-tool.jar config/daily-shop-catalog.json > web-catalog.json
unzip -p /Users/liangguansheng/ZCodeProject/wjdr-review/server/wjdr-tool.jar config/daily-town-shop-catalog.json > web-town-catalog.json
unzip -p /Users/liangguansheng/ZCodeProject/wjdr-review/server/wjdr-tool.jar config/daily-shop-recommended-offers.json > web-recommended.json
python3 - <<'PY'
import json
t=json.load(open('web-town-catalog.json')); d=json.load(open('web-catalog.json'))
ours=json.load(open('/Users/liangguansheng/ZCodeProject/VAContainer/app/src/main/assets/towndev/shops.json'))
web={}
for x in t: web[x['configId']]=(x['shopId'],x)
for x in d:
    sid={'vendor':801,'alliance-daily':401,'alliance-weekly':501,'brawl-war':2401}[x['scope']]
    for c in x['configIds']: web[c]=(sid,x)
our={g['configId']:(int(s),g) for s,arr in ours.items() for g in arr}
print('web',len(web),'ours',len(our),'missing',len(set(web)-set(our)),'extra',len(set(our)-set(web)))
bad=0
for c,(sid,w) in web.items():
    g=our[c][1]
    for k in ('price','limit','count','currencyId','itemId'):
        if int(w.get(k,0))!=int(g.get(k,0)): bad+=1; print('DIFF',c,k,w.get(k),g.get(k))
    if (w.get('name') or '')!=(g.get('name') or ''): bad+=1; print('DIFF name',c)
print('field mismatches',bad)
PY
```

- 生成本报告的环境：工作目录 `/Users/liangguansheng/ZCodeProject/VAContainer`；网页版源码 `/Users/liangguansheng/ZCodeProject/wjdr-review`；网页版 jar 只读解压目录 `/tmp/wjdr_shop_audit`。
- 本报告未对生产/仓库做任何写操作（除本文件）。
