# 野外/情报/巨熊/巨兽/狩猎/竞技场 1:1 差距审计（task-2）

- 审计人：audit-wilderness（read-only）
- 时间：2026-10-23 03:00–03:35（Asia/Shanghai）；分支：`add/auto-claim-parity-20261023-0302`
- 硬约束执行情况：未改任何源码/配置/文档（仅写本文件）；未 git 写操作；未跑 gradle；未用 zcode；生产仅只读 `ssh -o BatchMode=yes vault-vps`（读 `/opt/vault-server/data/state.json`、`data/logs/`）；未重启任何服务。
- 对照基准（网页版反编译源码，下称“网页版”）：`/Users/liangguansheng/ZCodeProject/wjdr-review/src-tool/com/wjdr/tool/modules/daily/`（行号均以该目录为根）
- 我方服务器实现：`vault-server/src/main/java/com/carlos/vaultserver/`；App 实现：`app/src/main/java/com/carlos/vault/`
- 参考文档：`../wjdr-review/野外-采集-六维深调报告.md`（全 78 行，尤其 §二/§六）、`../网页版功能全览与移植对照.md` L58-62、`../子功能运行状态六维分析.md` L139-143、`design/行军队列战术手册-20261015.md` §P3/P4、`design/自动化模块-实现落差与修复待办-20261018.md` G1/G8/G10
- 生产基线：**部署 jar 为 v4.1.8（2026-10-22 16:41 UTC 启动，`vault-20261023.log` 16:41:15.142 行），只有“整轮调度器已启动（30s 节拍）”（同 log 16:41:15.320），无本分支未提交的 `TaskCadenceScheduler`**（`ssh` 验证：日志 0 次“任务节奏调度”，`state.json` 0 个 `srv:cad:*` 键）。因此下文“现网行为”= 每天窗口内一轮整轮（`srv:lastrun:48460403=1792688377862`，即 2026-10-22 16:59:37 UTC / 10-23 00:59 北京；上一轮“定时”为 10-21 21:45 UTC / 10-22 05:45 北京）。

---

## 0. 结论摘要（先给 P0）

1. **竞技场：每日奖励基本必失败 + 从不买次数。** 我方一次性打满 5 场后立刻 `req_arena_get_daily_reward`，失败即永久放弃（`ServerTownTasks.java:777-780`）；网页版在**结算窗 23:10–24:00** 里按“当日完成次数≥5 且未领”重试到成功、并在 00:00–20:00 预购次数、打完免费次数后按预算再买（`DailyArenaService.java:74,421-467,538-562,774-809`）。生产连续 4 天 `arena:fight=挑战5胜0`、`arena:daily=次数未达标(2925)`（`state.json:log:48460403:888:arena*`）。
2. **采集：`res_id` 换算错误，导致搜索永远为空、采集从未真正派出。** 我方把 102/103/104/105 减 101 成 1/2/3/4 传给 `req_world_map_search.res_id`（`ServerFieldTasks.java:47,57`；App 同 bug `FieldTasks.java:54-63`），网页版直传 102-105（`DailyGatherService.java:1413-1415` + `DailyGatherConfig.java:213-216`）。生产 48460403/888 roleConfig `gather=true`，最近 21 条记录全是“附近未搜到资源点”，且日志中“采集搜索失败”=0（说明每次搜索都返回 2872 空结果，而不是坐标解析问题）。
3. **情报：读错通知协议 → 事件永远为空。** 我方只读 `notify_intelligence_events`（`ServerFieldTasks.java:232`）；网页版以 `notify_intelligence_info` 为初始化权威（`DailyIntelligenceState.java:47,112-120`），`events` 增量更新才用 `notify_intelligence_events`（`:48`）。生产登录批次里只有 `notify_intelligence_info`、没有 `notify_intelligence_events`（`vault-20261023.log` 16:59:02.930 medic notifies 列表 / 16:59:16 才出现 events_del），故我方 `intel` 连续“暂无情报事件”。
4. **巨熊：只做“看预约”没有“周窗口发车/99 上车/对账”。** 我方 `ServerTasks.bear` 仅调用 `CaptureTasks.bear` 读一次 `req_alliance_trap_info` 写文案（`ServerTasks.java:1297-1309`；`CaptureTasks.java:296-320`）。网页版完整实现：登录同步预约 → 活动前 10 分钟清场 → 整点 `req_world_march type=411` 发起集结 → `type=99` 上车 ≤6 队 → `req_world_mass_master_detail`/通知对账（`DailyBearActionService.java:143-181,305-366,507-570,679-702`；`DailyBearMarchExecutor.java:77-235,236-415,430-505,677-689`）。生产 48460403/888 `mod bear="未预约 · 建议提前预约"`；57259770/724 有 `已预约 10月23日 21:00`（预约时间已读到，但现网就没有发车代码）。
5. **巨兽狩猎：只开关“原生自动上车”，不发起每日巨兽**，且开关被拒。我方 `ServerTasks.behemoth` 只发 `req_alliance_activity_auto_join_info/open`（固定 1/4/3 类目、march_limit=3）（`ServerTasks.java:1270-1295`）；网页版每日发起上限 10 次：搜索 `req_world_map_search`→`req_world_mapobj`→`req_world_march type=102`，并加入联盟集结 `type=99`、体力不足用 610002 道具（`DailyBehemothService.java:407-444,579-651,652-690`；`BehemothAutomationService.java:984-1005`）。生产 `behemoth=开启码1507/1600`（被拒）。
6. **采集/情报/狩猎没有任务内部的“续派/唤醒”状态机**：现网只靠每天一轮整轮（`RoundScheduler.java:134-171`），任务本身是“一次性填坑”（`ServerFieldTasks.java:26-104`、`228-327`、`331-410`）。本分支未提交的 `TaskCadenceScheduler` 只补了“每任务固定间隔”（`TaskCadence.java:77-81`；`TaskCadenceScheduler.java:149-209`），**没有**网页版的业务态 wakeAt（march endAt 秒级、nextAttemptAt=0 的 18 协议 replan、180s 看门狗）与通知唤醒，且未部署。
7. **专家情报收益全丢**：①“情报专家技能”网页版每日自动 `req_use_expert_skill{expert_id,skill_id,effect_type:6}`（`DailyExpertIntelligenceService.java:40,117,134-143,200-214`）我方未实现（只有 town_affairs 里写死的 expert 7004/7001 effect_type 14）；②我方独有的 `intel.expert_extra` 依赖 `notify_expert_quests` 推送（`ServerFieldTasks.java:277-321`），生产该推送在整轮开始后才到达（16:58:45.601 建会话+开整轮，16:59:05 记录“专家情报未同步”，16:59:16 才收到 `notify_expert_quests`）→ 长期显示“未同步/已领61”交替。
8. **狩猎默认关（与网页版一致）但内部能力缺**：无 `req_world_mapobj` 二次校验、无体力罐头 610002、无与采集/巨兽/情报/巨熊窗口的暂停协调（`DailyHuntService.java:317,339,383-390`；`DailyHuntPauseSupport`）。

---

## 1. 触发/节奏对照表（网页版 interestedProtocols vs 我方是否有 notify 唤醒）

### 1.1 网页版底座（所有任务共用）
- 任务模块声明 `interestedProtocols()` → 推送到达 `DailyTaskEngineBridge.forwardProtocolNotification`（`engine/DailyTaskEngineBridge.java:188-202`）→ `onProtocolNotification`（`:223-229`）→ `TaskSignalBus.publishAccount`（`engine/TaskSignalBus.java:37-58`；`:48-57` 按 protocol 过滤模块）→ `TaskEngine.accept`（`engine/TaskEngine.java:112-118`）→ `AbstractStandardTaskModule.decide` 在 SLEEPING/COMPLETED 收到 `PROTOCOL_NOTIFICATION/TIMER_FIRED` 即重评（`task/AbstractStandardTaskModule.java:65-90,208-210`）。
- 每任务独立间隔 + 命令完成后按业务 `nextRunAt` 睡眠：`model/AbstractDailyAutomationTaskDefinition.java:32-73`；`AbstractStandardTaskModule.java:92-137,160-181`。
- 空闲任务也接受 `CONFIG_CHANGED/RECONCILE_REQUESTED/SNAPSHOT_READY`（`engine/TaskModule.java:14-25`）。

### 1.2 逐功能对照

| 功能 | 网页版唤醒协议（file:line） | 网页版间隔/节奏 | 我方 server 是否有 notify 唤醒 | 我方生产节奏（现网） | 我方本分支未提交 cadence |
|---|---|---|---|---|---|
| 采集 gather | `task/gather/GatherTaskModule.java:23`：`notify_world_player_init / player_march_add/update/del / world_resource_info / buff_show* / city_item_init/update / intelligence_info / intelligence_events(_del/battle_result) / pet_info/update/ex_*`（19 个） | 模块 180s；`runCycle` 内业务 `nextAttemptAt`：march 最早 endAt+250ms、无 march 3s、封顶 180s（`DailyGatherService.java:1060-1079`）；18 协议入站即 `nextAttemptAt=0`（`:110,167-185`） | **无**（服务器侧只在 `SessionKeeper` 挂战争钩子 `SessionKeeper.java:391-399`；采集无消息钩子/无任务引擎） | 每天 1 轮，单次 `collectNotifies(2500)`（`ServerFieldTasks.java:29`），派满即结束 | `gather=15s`（`TaskCadence.java:77`），仅定时、无业务态唤醒 |
| 情报 intel | `task/intelligence/IntelligenceTaskModule.java:19`：`notify_intelligence_info / events / events_del / battle_result / power_info / spar_leak_info` | 180s（同文件 :22）；状态源 `DailyIntelligenceState.java:47-52` | **无**；只读 `notify_intelligence_events`（`ServerFieldTasks.java:232`） | 每天 1 轮；因协议错→恒“暂无情报事件” | `intel=15s`（`TaskCadence.java:78`） |
| 情报专家（技能） | `task/expertintelligence/ExpertIntelligenceTaskModule.java:21`：`notify_expert_info / update_expert_info / update_expert_skill_info` | 60s；01:30 服务器安全窗后才用；用后今天不再（`DailyExpertIntelligenceService.java:39,136-143,200-214`） | **未实现** | 无 | `expert=60s`（`TaskCadence.java:55`）但任务体是“专家游历”，不含技能使用 |
| 巨熊 bear | `task/bearaction/BearActionTaskModule.java:24`：31 个（`notify_alliance_trap_update / mass_march_* / world_alliance_situation_* / player_march_* / world_mapobj* / pet_* / game_init/server_time/alliance_*`） | 任务间隔 5s（`BEAR_ACTIVITY`，`AbstractDailyAutomationTaskDefinition.java:20`）；窗口 TIME_WINDOW；登录同步预约（`DailyBearActionService.java:103-181`）；活动开始前 10 分钟准备（`DailyBearSchedule.java:16,67-72`） | **无**（`CaptureTasks.bear` 不是常驻，无钩子） | 每天 1 轮，仅读预约写文案 | `bear=15s`（`TaskCadence.java:80`），但执行体仍是“读预约”，不会发车 |
| 巨兽 behemoth | `task/behemoth/BehemothTaskModule.java:29`：`notify_world_npc_init / notify_world_npc_update` | 15s；`nextExecuteInSeconds` 到服务端次日 0 点（同文件 :47-56；`DailyBehemothService.java:752-793`）；发起上限默认 10（`config/DailyBehemothConfig.java:14`） | **无**（只 `auto_join` 开关请求） | 每天 1 轮 `auto_join_open` 被拒（码 1507/1600） | `behemoth=15s`（`TaskCadence.java:79`） |
| 狩猎 hunt | `task/hunt/HuntTaskModule.java:24`：`notify_world_player_init / player_march_* / world_mapobj* / world_mapobjs / backpack_update / world_npc_update` | 15s；`claimable && idle>0` 时 1s 连续补（同文件 :40-47） | **无** | 默认 false，生产“配置未启用” | `hunt=15s`（`TaskCadence.java:81`） |
| 竞技场 arena | `task/arena/ArenaTaskModule.java:24`：`notify_game_init / server_time / arena_init / hero_init/update / resource_init/update` | 30s；`nextWakeDelayMillis`（按服务器时间）→`nextFightInSeconds`→1s（同文件 :38-49；`DailyArenaService.java:150-162`）；另有独占窗协调 5s 巡检（`DailyArenaWindowCoordinator.java:69-72`） | **无** | 每天 1 轮，一次性 5 场 | `arena=30s`（`TaskCadence.java:59`） |

> 结论：**网页版 6 个模块全部声明了 interestedProtocols 并由推送重评；我方服务器侧 0 个 notify 唤醒。** App 侧有半套 `PushWakeDriver`（`app/src/main/java/com/carlos/vault/daily/PushWakeDriver.java:52-59` JSON：gather→`notify_world_march_update`、hunt→`notify_mapobj_update`、intel→`notify_intelligence_info`、behemoth→`notify_alliance_activity_update`；:61-64 行军冷却 15 分钟；:95-102 兜底轮询只 gather/hunt/intel；:104-163 执行 `DailyRunner.runSingle`），但：①只在 App 本地会话生效，服务器模式不参与；②巨熊/竞技场完全没有唤醒；③协议名与网页版不完全一致（网页版采集听 `notify_player_march_*`，我方 App 听 `notify_world_march_update`，生产日志中 `notify_world_march_update` 出现 **0 次**、`notify_player_march_update` 6 次 → App 采集唤醒实际也不会触发）。

---

## 2. 逐功能对照与最小修复

### 2.1 野外采集 gather —— P0（收益最大：全天队列空转）

**网页版逻辑（file:line）**
1. 触发/续派：19 协议（`task/gather/GatherTaskModule.java:23`）→ `DailyGatherService.replanFromInbound` 置 `nextAttemptAt=0`（`:110,167-185`）；`claim()` 到期即跑 `executeCycle/runCycle`（`:187-200,204-255,556-694`）。
2. 节奏：`nextMarchTransitionAt`=最早 `march.endAt*1000+250ms`（`:1060-1074`）；无 march=+3s；`queueWaitUntil` 封顶 180s（`:1076-1079`）；任务模块 180s 看门狗（`GatherTaskModule.java:26`）。
3. 派队：6 条固定队列配置（资源 102 肉/103 木/104 煤/105 铁；等级 1-9；编队 AUTOMATIC/ROLE；兵配比 4w/3w/3w）（`config/DailyGatherConfig.java:58-60,213-216`；`nextInactiveQueue` `DailyGatherService.java:1384-1400`）。
4. 搜索：`req_world_map_search{mapobj_type:2, level_min, level_max, res_id, is_full}`，**res_id 直传 102-105**（`:1413-1415`）；响应取单键 `content.point`（`:1355-1373`）。
5. 出征：`req_world_march type=4`（普通采集合成 `:1648-1650`）；联盟矿 `type=110`（`:1652-1654`，目标来自 `req_alliance_building_list` type=39 → `req_world_mapobj` 二次校验 `:1181-1354`）。
6. 暂停协调：情报优先（`gatherPriorityEventCount>0` → WAITING_INTELLIGENCE）、狩猎运行中暂停、巨熊/乱斗/三角会战窗口暂停（`pauseBeforeDispatch` `:694-712`）；战锁 8619 退避 300s / 8637 退避 60s（`:221-255`）。
7. 附加收益：采集加速 8h/24h buff 自动续 + 钻石购买限额 2000/日（`:1422-1535`）；重物搬运宠物队列五态状态机（`:714-1011`）；王国 701 天开 9 级矿门（`:304-337`）。

**我方实现（file:line）**
- `vault-server/.../task/ServerFieldTasks.java:26-104`：`collectNotifies(2500)`（:29）→ 读参数 `min_level`(1-6)/`max_queues`(1-5)/`resources`（:31-32）→ 空闲数 `slots` → 逐资源逐级搜索（:69-92）→ `req_world_march type=4`（:79-81）→ 2878/401 停止本轮（:86-89）→ 无派队写 skip“附近未搜到资源点”（:97）。
- 资源 id：`raw - 101`（:47）与 `optInt(i) - 101`（:57）→ 传 1-4；搜索 payload `:167-188`（`res_id` 为换算后的值）；响应已修成读 `point`（:178-181），并有 points/data/list 兜底。
- 计数/容量：`countMarches` 额外发 `req_world_march_detail_4_player_situation`（:195-216），容量取 `notify_world_player_init.world.capacity`（:218-224，失败回退 3）。
- App 同构：`app/.../daily/FieldTasks.java:31-127`（`:54-63` 同 res 换算；`:78-79` fullOnly；`:95-97` type=4；`GatherCapacity.java` 仅产容量估算文案，无续派）。
- 节奏：现网 `RoundScheduler.java:134-171`（窗口内随机一轮，错过即补跑）；本分支未提交 `TaskCadence.java:77`=15s、`TaskCadenceScheduler.java:149-209`（只按 interval）。

**缺口**
- P0-A：`res_id` 传输口径错（1-4 vs 102-105）→ 生产每次搜索 2872 → **采集从未派出一队**。证据：`state.json` `log:48460403:888:gather` 最近 21 条全“领取0/跳过1 附近未搜到资源点”；`vault-20261023.log` 中“采集搜索失败”0 条（若搜索返回别的错误码会打 WARN，见 `ServerFieldTasks.java:175`）→ 只能是每次 2872。
- P0-B：无任务内续派：一轮结束后不再检查，队列空闲一整天；无 `nextAttemptAt`/march endAt 唤醒；`pauseBeforeDispatch` 的情报/狩猎/巨熊窗口协调完全没有（`ServerFieldTasks.java:26-104` 无任何 gate 调用）。
- P1-C：无联盟矿（type=110）、无采集加速、无重物搬运；这三项是网页版并列收益项（`野外-采集-六维深调报告.md:23-25`）。
- P1-D：`countMarches` 用附加查询而非 `notify_player_march_init` 状态机（网页版 `BehemothAccountState` 常驻维护 march 队列），多角色/断线重连后会高估空位（宁可被拒也不漏发，低危）。
- P2-E：默认 `fullResourceOnly=false` vs 网页版默认 true（`DailyGatherService.java` 的默认配置构造 `fullResourceOnly=true`；`ServerFieldTasks.java:63` only “full”策略才 true）。

**最小修复方案（文件+函数级）**
1. `ServerFieldTasks.gather`：删除 `-101` 换算，直接传 102-105（:47,:57；同步 `app/.../FieldTasks.java:54-63`）；改后先单账号手动跑一轮验证是否出现“派出采集×N”（这是 P0 的验收点）。
2. `ServerFieldTasks.gather` 末尾：若 `slots>0 && sent==0` 但存在 2872，不要立即放弃；按 `exec.wait` 外的节奏重试（见 3）。
3. 新增服务器侧“野外常驻”最小实现（建议放 `ServerFieldTasks` 或新 `ServerFieldLoop`，复用 `TaskCadenceScheduler` 的 15s 间隔）：每轮只做“算空闲队列→搜索→派 1 队”，返回 `nextWakeAt = min(march endAt+250ms, now+180s)` 写入 `srv:cad:*`（`TaskCadenceScheduler` 增加“业务 nextWakeAt 覆盖 interval”能力）；并在 `SessionKeeper` 的会话引擎挂 `addMessageHook` 监听 `notify_player_march_* / notify_world_resource_info / notify_intelligence_info`，命中则把该账号 gather 的 `srv:cad` 置为 now（对齐 `DailyGatherService.java:167-185`）。
4. P1：联盟矿按 `ServerFieldTasks` 增加 `req_alliance_building_list(33/39)`+`req_world_mapobj` 校验+`type=110`（可照抄网页版 `DailyGatherService.java:1181-1354` 的协议顺序，只保留必需字段）；采集加速/重物搬运建议单独立项（引用 `野外-采集-六维深调报告.md:69-70`）。

---

### 2.2 情报 intel（含专家额外情报/专家技能）—— P0（事件全漏）

**网页版逻辑（file:line）**
1. 触发：6 协议（`task/intelligence/IntelligenceTaskModule.java:19`），180s（:22）。
2. 状态源：`notify_intelligence_info` → `initialize()` 用 `events/radar_level/radar_exp/power_level/next_event_time` 全量替换（`service/DailyIntelligenceState.java:47,112-120`）；`notify_intelligence_events` 增量更新、`events_del` 删除、`battle_result` 改战斗结果、`power_info` 更新雷达（:48-52）。
3. 事件类型：`eventConfigId` → 1 怪物/2 营救/3 英雄之旅(PVE)/11 活动巨兽/100 冒险巨兽（`service/DailyIntelligenceEventCatalog.java:7-12,17-33`）；世界行军类 = 1/2/11/100（:40-42）。
4. 行为：`status==1` 可派（世界行军 type=2→301、其余→302，`service/DailyIntelligenceService.java:919-928`；type=3 PVE 走 `req_intelligence_start_battle`→`req_intelligence_end_battle` `:712-787`；type=11 需烈焰獠牙开关 `:574-597`）；`status==2` 已可领 → `req_intelligence_receive`（`:634-668`）；一次评估最多 30 个 action（`:83,452-530`）。
5. 资源协调：体力 10/12（`:598-606`）、情报保留 50 体力（`:256-262`）、普通队列数 = 配置数（默认 1，`config/DailyIntelligenceConfig.java:13,35-41`）、英雄/士兵预留（`:618-633`）；采集让位情报（`DailyGatherService.java:700-706`）。
6. 专家技能（expert-intelligence）：`notify_expert_info` 等三协议（`task/expertintelligence/ExpertIntelligenceTaskModule.java:21`）→ 服务器 00:01:30 安全窗后 `req_use_expert_skill{expert_id,skill_id,effect_type:6}`，成功/40237 日限当天不再（`service/DailyExpertIntelligenceService.java:39-41,113-143,200-214`）。

**我方实现（file:line）**
- `ServerFieldTasks.java:228-327`：`collectNotifies(2500)`；只读 `notify_intelligence_events`（:232）；只处理 `status==1`（:241）；`type==2||3` → `req_world_march type=301/302`（:243-249），其余 → `req_intelligence_start_battle` + sleep 3s + `end_battle`（:251-262）；派队成功即立刻 `req_intelligence_receive`（:264-270）；未按 status=2 领取、未去重、未处理 2501 语义之外的失败；固定 1 队编队（`Exec.formation(engine,1)`，:244）。
- 专家额外情报：`if tc.subOn("intel","expert_extra",true)` → `notifyData("notify_expert_quests")` → `finishs` 中 `isgetaward=false` 逐条 `req_get_expert_quest_award{quest_id}` + 按天台账（`ServerFieldTasks.java:277-321`；`ServerTasks.java` 的 `unclaimedFinishIds/finishCount/tryClaim`）。
- App 同构：`app/.../FieldTasks.java:199-284`（同样只读 events、status==1、type2/3→301/302、派队后立即 receive）。

**缺口**
- P0-F：数据源协议错。生产登录批里 `notify_intelligence_info` 有、`notify_intelligence_events` 无（`vault-20261023.log` 16:59:02.930 的 `medic notifies ...` 列表只含 `notify_intelligence_info`；16:44:02.801 才见过一次 `notify_intelligence_events_del`）→ 我方 `intel` 每天“暂无情报事件”。证据：`state.json:log:48460403:888:intel` 共 20 条，最近全部“领取0/跳过1 暂无情报事件”。
- P0-G：类型映射与网页版相反：我方把 `type==3` 当世界行军（302），网页版 `type==3` 是 PVE 英雄之旅（`DailyIntelligenceService.java:712-787`）；我方把 `type==1` 当 PVE，网页版 type1 是普通世界行军（301/302 规则 `:919-928` + `DailyIntelligenceEventCatalog.java:40-42`）。
- P0-H：只处理 status==1，`status==2`（已可领取）事件永远不领；而“派队成功后立刻 receive”时序也不对（网页版等战斗结果/推送后 status 变 2 再领）。
- P1-I：无体力预算/保留 50、无情报优先于采集、无英雄/士兵占位与多队列（`DailyIntelligenceService.java:256-262,452-530,618-633`）。
- P1-J：专家技能未实现（`req_use_expert_skill{effect_type:6}`）；`expert_extra` 依赖迟到推送（生产 16:58:45.601 建会话即开整轮，16:59:05 判定“未同步”，16:59:16 推送才到）→ 需要“等待初始化数据完成再判/或后续轮次重试”。

**最小修复方案**
1. `ServerFieldTasks.intel`：改为 `eq = notifyData("notify_intelligence_info")`，优先读 `eq.events`；若存在 `notify_intelligence_events` 则以其 `events` 覆盖/合并（保留 `id→event` 映射，`events_del` 删除）。文件级改动仅 3-5 行。
2. 重写事件处理循环（同函数）：
   - `status==2` → `req_intelligence_receive{id}`（+2501 视为已处理）；
   - `status==1 && type==3`（英雄之旅）→ `req_intelligence_start_battle`→`end_battle`（保留现 PVE 分支，但触发条件改为 `type==3`；type==11 单独开关）；
   - `status==1 && type in {1,2,11,100}` → `req_world_march type=(type==2?301:302)` + `extra.event_id`；派队后不要立即 receive，等下次轮次 status 变 2 再领（对齐 `DailyIntelligenceService.java:452-530,634-668,919-928`）。
3. 在排队前加体力门（`stamina>=10/12` 且保留 50 给巨兽，`DailyIntelligenceService.java:256-262,598-606`）与“普通队列数 = `min(capacity, config.queueCount)`”，至少保证不因队列满而空转。
4. P1：新增 `expert-intelligence` 任务（可并入 `expert` 或 `intel` 子项）：读 `notify_expert_info/update_expert_*` 找 `expert_id/skill_id`（网页版 `DailyExpertIntelligenceState` 扫描 `experts[].skills` 里 effect_type/6 的技能），服务器安全窗后发 `req_use_expert_skill`，按服务端日去重（`DailyExpertIntelligenceService.java:40,117,134-143,200-214`）。
5. `expert_extra`：整轮不要用“首次读取为空即判未同步”；改为等待登录后 N 秒/或每轮重试，并把 `mod` 文案区分“未收到推送（待重试）”。验收：生产账号 `intel:expert_extra` 不再长期“未同步”。

---

### 2.3 巨熊行动 bear —— P0（每周固定收益 0；当前只是“看预约”）

**网页版逻辑（file:line）**
1. 触发：31 协议（`task/bearaction/BearActionTaskModule.java:24`），任务间隔 5s（`BEAR_ACTIVITY`，`AbstractDailyAutomationTaskDefinition.java:20`）。
2. 预约同步：登录/被唤醒时 `watch_alliance_situation_activity` + `req_alliance_trap_info`（`service/DailyBearActionService.java:143-181`：`synchronizeAllianceTrap`）；状态解析 `reserve_time/start/end/cooldown`（`service/DailyBearActionState.java:142-160`，`reserve_time` 权威覆盖 `:147`）。**网页版没有“预约请求”协议**——预约是游戏端行为，网页版只读取 `reserve_time` 并据此排程（`DailyBearSchedule.windowAt(now,reserveTime)` `service/DailyBearSchedule.java:58-65`）。
3. 目标发现：`req_alliance_building_list`（type=33 熊坑）→ `req_alliance_building_list_exist{mapobj_type:33}` 兜底 → `req_world_mapobj{kid,point}` 解析目标（`DailyBearActionService.java:243-300`）。
4. 窗口：配置 `startTime/endTime`（必须 30 分钟跨度）→ 活动开始前 10 分钟准备、活动 30 分钟（`DailyBearSchedule.java:15-16,25-33,43-72`）；若读到 `reserve_time` 则以其为活动开始（`DailyBearSchedule.java:58-65`；`DailyBearActionConfig.java:19,37-39` 默认 `enabled=false,launchEnabled=true,joinEnabled=true,useReservedTime=false`）。
5. 执行：准备阶段清场（撤回普通行军 `req_world_march_off_work`）+ 宠物技能；活动开始后：
   - 发起：`req_world_march type=411` + `extra{monsterid, prepare_time_index}`（`service/DailyBearMarchExecutor.java:677-680`；准备时间档 320/620/1820/3620s `:690-698`）；
   - 上车：≤6 队（默认发起 1 + 上车编队 1-6，`DailyBearActionConfig.java:23`）`req_world_march type=99` + `extra{master_marchid}`，坐标=车头城市（`:681-685`）；
   - 对账：同步响应/`notify_player_march_*`/`notify_mass_march_*` 通知 + `req_world_mass_master_detail{id,owner,kid}` 成员详情确认（`:430-505`）；`mass` 候选来自世界状态（`BehemothAccountState.bearRallyCandidates`）。
6. 取消/结束：剩余时间不足准备档位时停止发起（`:154-158`）；活动结束后释放 activity gate；其他占队列任务在窗口内暂停（`service/DailyBearActivityGate.java:108-123`；采集 `DailyGatherService.java:706-711`、情报 `DailyIntelligenceService.java:326,538-547`、狩猎 `DailyHuntService.java:159,186-188`、巨兽 `DailyBehemothService.java:420,488-500`）。

**我方实现（file:line）**
- `ServerTasks.java:1297-1309`：`bear()` → `CaptureTasks.bear(engine,store,fpid,sid)` → 读 `mod:...:bear` 文案，返回 CLAIMED/SKIPPED。
- `CaptureTasks.java:296-320`：只发 `req_alliance_trap_info`，取 `reserve_time/mass_start_time/mass_end_time/cool_end_time` 写 `{"trap":"已预约 X月X日 HH:mm"/"未预约 · 建议提前预约"}`；**无** 预约请求、无排程、无发车、无 99 上车、无对账、无 ActivityGate。
- App 同构：`app/.../FieldTasks.java:323-...`（`bear` 也仅同步陷阱文案）。
- `ServerTasks.java:117` 默认 true（roleConfig 未配时每天跑），但执行体只是看预约；`FnRegistry.java:87` 文案也自认“上车由自动上车承接”。

**生产证据**
- `state.json:mod:48460403:888:bear={"trap":"未预约 · 建议提前预约"}`（10-23 00:59 最近一轮）；`log:48460403:888:bear` 20 条，10-21 21:45 那轮为“已预约 10月22日 21:10”，之后无任何发车记录（本就不存在）。
- 57259770/724 `bear="已预约 10月23日 21:00"`（`state.json:log:57259770:724:bear`，10-22 17:00:52 UTC；该账号 `auto=false`，只手动跑过一轮）——说明预约时间可被读到，缺的是“到点发车”。

**最小修复方案（按 1:1 顺序）**
1. 新建服务器侧 `ServerBearWindow`（或扩 `ServerTasks.bear`，但建议独立类）：
   - `syncReservation()`：`req_alliance_trap_info` → 解析 `reserve_time`（权威）→ 计算 `prepAt=reserve-600s, startAt=reserve, endAt=reserve+1800s`；同时 `req_alliance_building_list` 过滤 type=33 → `req_world_mapobj` 拿 `monsterid`/坐标（照 `DailyBearActionService.java:143-181,243-300`）。
   - 排程：把 `nextWakeAt=prepAt` 写入 cadence（`TaskCadenceScheduler` 需支持“业务 nextWakeAt”覆盖固定间隔，见 §2.6 公共修复）。
2. 到点执行（新函数 `executeWindow`）：
   - 清场：`req_world_march_off_work{id}` 撤普通采集/狩猎行军（网页版 `DailyBearActionService.java:517-570` + `continueClearingForHeavyLifting` 类似逻辑）；
   - 发起：`req_world_march{type:411, endpoint:{x,y}, formation:{hero_id,soldier}, extra:{monsterid, prepare_time_index}}`（`DailyBearMarchExecutor.java:677-680`；编队先复用 `Exec.formation`，P1 再接配置）；
   - 上车：联盟内候选（从 `notify_world_alliance_situation_*`/`notify_mass_march_*` 或 `req_alliance_building_list` 附近车头）逐个 `req_world_march{type:99, endpoint:车头城市, formation, extra:{master_marchid}}`，最多 6 队（`DailyBearMarchExecutor.java:236-415,681-685`）；
   - 对账：对未确认的上车请求，查询 `req_world_mass_master_detail{id,owner,kid}`（`DailyBearMarchExecutor.java:473-505`）。
3. ActivityGate：在窗口内阻止 gather/intel/hunt/behemoth 派新世界行军（可先做最简：`ServerFieldTasks`/`ServerTasks.behemoth` 入口读该账号 bear 窗口状态，窗口内 SKIP；对齐 `DailyBearActivityGate.java:108-123`）。
4. 验收：用 57259770/724（已有 10-23 21:00 预约）在 20:50 前部署只读?——不行，部署受限；修复验收交给开发/运维，审计侧给出“到点日志应出现 req_world_march type=411/99”的判定标准。

---

### 2.4 巨兽狩猎 behemoth —— P0（每日 10 次发起 0 次；auto_join 开关也失败）

**网页版逻辑（file:line）**
1. 触发：`notify_world_npc_init/update`（`task/behemoth/BehemothTaskModule.java:29`），15s；无任务时按 `nextExecuteInSeconds` 睡到服务端次日（:47-56）。
2. 发起上限：默认 10/日（`config/DailyBehemothConfig.java:14`），`launchCount` 以服务端 master_count 与本地台账取 max（`DailyBehemothService.java:815-846`）。
3. 协议：`req_world_map_search` 按等级 → `req_world_mapobj{kid,point}` → 校验 `monsterid/level` 后 `req_world_march{type:102,...extra{monsterid,prepare_time_index}}`（`modules/behemoth/service/BehemothAutomationService.java:991-1005`；`BehemothLaunchFacade.java:34-107`）。
4. 上车：`type=99 + extra{master_marchid}`（`BehemothAutomationService.java:984-989`）；原生自动上车三个类目 id 1=巨兽/4=死神/3=巨熊，`req_alliance_activity_auto_join_info/open/close`（`DailyBehemothService.java:503-563`；open payload `:536`，info `:549`）。
5. 资源/暂停：情报优先让出体力与队列（`DailyBehemothService.java:606-608`）；体力保留 50（情报）+25/次，不足用背包 610002 体力道具（`:652-690`）；巨熊窗口暂停（`:484-500`）；解散退避 60s（`:139-207` 的 `dismissalBackoffSeconds`）。
6. 关闭收尾：disable 命令 `req_alliance_activity_auto_join_close`（`BehemothTaskModule.java:59-71`）。

**我方实现（file:line）**
- `ServerTasks.java:1270-1295`：只发 `req_alliance_activity_auto_join_info{auto_mass_id_list:[1,4,3]}` → `req_alliance_activity_auto_join_open{...,march_limit:3}`，成功只写一句文案；**不搜索、不发起、不上车对账、不体力、不计数、不暂停协调**。
- `Modules.java:110-118` 把 behemoth 归 wilderness 模块；默认 `ServerTasks.java:116` true → 每天跑但只能开原生上车。
- App 同构：`app/.../FieldTasks.java:287-321`。

**生产证据**
- `state.json:log:48460403:888:behemoth` 最近 20 条：“开启码1507”（10-22 16:59）、“开启码1600”（10-21 21:45、10-20 16:18）→ 开关请求被服务端拒绝，连原生自动上车都没开起来。
- 无任何巨兽发起/上车记录（`req_world_march type=102/99` 在 server 代码中不存在：`grep -n "type.*102\|auto_join" ServerTasks.java` 仅见上述两请求）。

**最小修复方案**
1. `ServerTasks.behemoth` 重写为“每日发起循环”：按 cadence 15s 跑，每轮：
   - 检查今日已发起次数（`ClaimLedger` 或新 `srv:behemoth:<day>:count`），≥ `launchLimit`（默认 10）→ 次日 0 点再唤醒；
   - `req_world_map_search{mapobj_type? 按网页版 `BehemothAutomationService.searchPayload(level)`}`→ `req_world_mapobj` 校验 → `req_world_march type=102 extra{monsterid,prepare_time_index:1}`（可用 `Exec.formation` 编队起步）；
   - 体力 `<50+25` 时先尝试 610002 道具（`req_use_item`/背包协议，参照 `BehemothStaminaItemSupport`）。
2. 保留 auto_join 但修正入参：先 `info` 读取服务端当前类目/上限，再按服务端返回值 `open`；“1507/1600”需按网页版 `DailyBehemothService.java:548-563` 的状态同步逻辑解释错误码（TODO：1507/1600 语义需真机/抓包复验；网页版错误码表未直接覆盖，建议先记录响应体）。
3. 情报优先：若 `intel` 有可派事件则本轮回 SKIP（对齐 `DailyBehemothService.java:606-608`），待 §2.2 修好 intel 后联调。
4. 巨熊窗口暂停：复用 §2.3 的 gate 判定。

---

### 2.5 自动狩猎 hunt —— P1（默认关，但一开就有多个 1:1 缺口）

**网页版逻辑（file:line）**
- 触发 9 协议（`task/hunt/HuntTaskModule.java:24`），15s；`claimable && idle>0` 时 1s 连补（:40-47）。
- 协议：`req_world_map_search`（等级区间）→ `req_world_mapobj` 二次校验 → `req_world_march{type:3, endpoint, formation, extra{monsterid}}`（`service/DailyHuntService.java:317,339,383-390`）。
- 配置：队列数 1-6、等级 1-30、距离 80-150、体力 10/次、体力道具 610002（`config/DailyHuntConfig.java:17-23,47-54`）。
- 暂停协调：`DailyHuntPauseSupport` 让 gather/intel/behemoth/巨熊集结避让；巨熊窗口暂停（`DailyHuntService.java:159,186-188`）。

**我方实现（file:line）**
- `ServerFieldTasks.java:331-410`：单轮最多 `max_times`（默认 5）× 逐级搜索（`searchMonster` 在 :507 附近）→ `req_world_march type=3`（:368-372）；无 `req_world_mapobj` 校验；无体力道具；无暂停协调；英雄不足时轮换候选（:354-360）。
- 默认关（`ServerTasks.java:118``false`），生产 `hunt=配置未启用` 与网页版默认一致。

**缺口/修复**
- P1：开启后应补 mapobj 校验（对齐 :339-378 的 2817/2818/2831 错误码处理）、体力不足 610002 补充、与采集/巨兽/巨熊窗口互斥；否则会重复搜到已不可打目标/空耗体力。
- 修复点同上：`ServerFieldTasks.hunt` + `Exec` 增加 use_item 封装。

---

### 2.6 竞技场 arena —— P0（每日奖励必失败 + 不买次数）

**网页版逻辑（file:line）**
1. 触发：`notify_game_init/server_time/arena_init/hero_*/resource_*`（`task/arena/ArenaTaskModule.java:24`），30s；`nextWakeDelayMillis`→`nextFightInSeconds`→1s（:38-49）。
2. 独占窗协调：`service/DailyArenaWindowCoordinator.java:69-72` 每 5s 巡检；定时场次前 1 分钟 `enterPreparing`、可执行时 `enterActive`（:142-158）；`ArenaExclusiveGate` 对其他模块 `blocks()=true`（`engine/ArenaExclusiveGate.java:31-37`；`TaskEngine.java:316-321` 独占期间其他任务 SleepUntil 5s）；定时顶号：场次前 2 分钟 `loginAutomaticallyForScheduledTask`（`service/DailyArenaPreLoginService.java:40,108-144`）。
3. 次数/购买：免费 5 次（`DailyArenaState.java:160-176` 由 `free_times_use` 算）；付费次数 `paid_remaining=left_times`、`buy_times` 累计（:169-176）；`challengesCompletedToday = free_used + max(0,buy_times-left_times)`（:179-186）。00:00–20:00 可预购（`DailyArenaService.java:78-79,421-467`），`req_arena_buy_challenge_times{buy_times:remaining}`（:439）；非定时模式次数用完再买 1（:538-562）。
4. 挑战：`req_arena_get_opponent`（:628）→ 需要资料时 `req_players_base_info{uids}`（:640）→ 选最低战力且过滤联盟/王国/黑名单/已挑战（:669-730）→ `req_arena_fight{opponent_type,opponent_id,hero_array,version}`（:598,649-656）；间隔默认 2s（:607-610，构造参数 `minimumRefreshWaitSeconds`），批次预算 9.5s/19s（:68-69）。
5. 刷新对手：免费 N 次（默认 5），超出按 `allowDiamondOpponentRefresh` + `diamondOpponentRefreshLimit`（默认 2000 钻，`config/DailyArenaConfig.java:16`）只花 100 钻/次（`DailyArenaService.java:692-715`）。
6. 每日奖励：结算窗 23:10–24:00（`DailyArenaService.java:74,761-768`）；条件 `dailyRewardClaimEnabled && !dailyRewardClaimed && challengesCompletedToday>=5`（:806-808）→ `req_arena_get_daily_reward`（:780）；2926=已领、2925=次数未达标均记 settled（:790-806）；其他错误 5 分钟重试（:800-807）；`nextArenaWake` 在 23:10 再唤醒（:817-836）。

**我方实现（file:line）**
- `ServerTownTasks.java:731-786`：`req_arena_get_opponent`（:735）→ 按战力升序取对手（:742）→ `req_arena_fight` 最多 `maxFights`（默认 5，:745-752）→ sleep 800ms（:767）→ 胜场用 `Exec.ok(f)` 计（:765-766）→ 最后立刻 `req_arena_get_daily_reward` 一次（:777-780），2926/2925 只记跳过。
- 无买次数、无刷新对手、无定时场/独占窗、无顶号、无结算窗重试、无对手类型/胜负真实解析、无黑名单/王国/联盟过滤。

**生产证据**
- `state.json:log:48460403:888:arena:fight` 16 条，连续“挑战5胜0”；`arena:daily` 16 条连续“次数未达标”；`arena` 汇总“领取1/跳过1 挑战5场胜0✓ 次数未达标·”（10-22 16:59 UTC 最近一轮）。“胜0”本身很可能是统计口径：网页版从 `report.win` 取胜负（`DailyArenaService.java:599-601`），我方用 `Exec.ok`（响应 code==0）当胜场，服务端战斗响应不是“胜=ok”语义。
- 由此每日奖励失败的直接原因：`challengesCompletedToday>=5` 不成立（真实完成次数可能因部分请求失败 <5）或结算窗未到就请求；我方没有 23:10 重试。

**最小修复方案（P0）**
1. `ServerTownTasks.arena` 从“一次性”改“有状态循环”：维护当日 `free_used/buy_times/left_times` 从响应 `base`（`req_arena_rank_range`/`req_arena_get_opponent`/fight 响应）解析（对齐 `DailyArenaState.java:160-186`），不再用 `Exec.ok` 当胜场；胜负读 `report.win`。
2. 增加买次数：定时模式 00:00–20:00 预购 `req_arena_buy_challenge_times{buy_times:目标-已购}`（`DailyArenaService.java:421-467`）；非定时模式次数耗尽再买 1（:538-562）；加 `allowDiamondPurchase` 默认关/预算校验。
3. 每日奖励改为“23:10 后再领、失败 5 分钟重试到 24:00”（新增 `srv:arena:nextWake`，由 cadence 支持业务 nextWakeAt）；2925 不要记 settled 为成功（网页版也记 settled，但会等次日；我方需保留到次日，避免当天反复请求）。
4. 独占窗/顶号：第一阶段只做“定时场次前 1 分钟不跑其他野外任务 + 场次时间点唤醒”；P2 再做预登录（`DailyArenaPreLoginService.java:108-144`）。
5. 挑战循环：每场后 sleep `max(2s, 服务端下一场时间)`，每轮 ≤20 action（`DailyArenaService.java:475-537,607-610`）。

---

## 3. P0 清单（按“每日漏做/漏领”收益排序）+ 可复现证据

| 排序 | 功能 | 缺口 | 每日损失（估算口径） | 复现证据（只读） |
|---|---|---|---|---|
| P0-1 | 竞技场 | 结算窗重试缺失 + 购买次数缺失 + 真实完成次数/胜负未解析 → 每日挑战奖励基本领不到 | 每日挑战奖励（钻石/道具）+ 免费5次之外的付费次数（配置开启时） | `state.json:log:48460403:888:arena:daily` 16 条全“次数未达标”；`arena:fight` 16 条全“挑战5胜0”；`arena` 汇总最近“领取1/跳过1 …次数未达标” |
| P0-2 | 采集 | res_id 1-4 应为 102-105 → 搜索恒 2872、从未派队 | 全天 3-5 队采集队列收益（最大项） | `state.json:log:48460403:888:gather` 21 条全“附近未搜到资源点”；`vault-20261023.log` “采集搜索失败”0 条（否则 WARN）|
| P0-3 | 情报 | 只读 `notify_intelligence_events`，登录批只有 `notify_intelligence_info` → 事件永远空；且 status=2 不领、type 映射反 | 每日全部情报事件（含 301/302 行军与 PVE 英雄之旅奖励） | `state.json:log:48460403:888:intel` 20 条全“暂无情报事件”；`vault-20261023.log` 16:59:02.930 notifies 列表有 `notify_intelligence_info`、无 `notify_intelligence_events` |
| P0-4 | 巨熊 | 只有预约读取，无周窗口发车/99 上车/对账 | 每周 1-2 次巨熊活动发起+上车奖励（联盟积分/个人奖励） | `state.json:mod:48460403:888:bear`；“已预约 10月23日 21:00”账号存在（57259770/724）但无发车代码可查 |
| P0-5 | 巨兽 | 只开原生自动上车且被拒，无每日 10 次发起 | 每日 10×巨兽发起奖励（体力 25×10 可用性） | `state.json:log:48460403:888:behemoth` “开启码1507/1600” |
| P0-6 | 全部野外 | 无 notify 唤醒 + 任务内无续派；现网每天仅窗口一轮 | 采集/情报/狩猎全天队列空转；活动窗口错过 | `srv:lastrun:48460403` 10-22 16:59 UTC；上一条“定时”10-21 21:45 UTC；生产日志无“任务节奏调度”/`state.json` 无 `srv:cad:*` |
| P1-7 | 专家情报 | `req_use_expert_skill{effect_type:6}` 未实现；`expert_extra` 依赖迟到推送 | 每日专家情报技能收益 + 专家额外情报任务奖励 | `state.json:mod:48460403:888:intel={"expert_extra":"未同步"}`；日志 16:58:45.601 建会话+整轮 vs 16:59:16 才收到 `notify_expert_quests` |
| P2-8 | 狩猎 | 默认关（与网页版一致）；开启后缺 mapobj 校验/体力道具/暂停协调 | 开启后的野怪收益与体力浪费 | `state.json:log:48460403:888:hunt=配置未启用` |

---

## 4. 公共修复：节奏/唤醒层还缺什么（Lead 已补“每任务间隔”层，本任务只列任务内部缺口）

- Lead 已补（未提交、未部署）：`TaskCadence.java:77-81`（gather/intel/behemoth/bear/hunt 均 15s）、`TaskCadenceScheduler.java:149-209`（到点挑一个任务 `submitRound`，写 `srv:cad:<fpid>:<sid>:<fn>`；`enabledTaskIds` 与整轮同口径 `:215-228`）。生产未部署（日志 0 次“任务节奏调度”；`state.json` 0 个 `srv:cad:*`；jar 启动行为只有“整轮调度器已启动”）。
- 仍缺（任务内部，需各功能修）：
  1. **业务态 wakeAt**：网页版会按业务结果睡到精确时刻（采集=march endAt+250ms `DailyGatherService.java:1060-1079`；巨熊=活动前10min/活动开始 `DailyBearActionService.java:489-500,305-366`；竞技场=结算窗/场次/下一场 `DailyArenaService.java:739-836`；巨兽=次日 0 点 `DailyBehemothService.java:752-793`）。`TaskCadenceScheduler` 目前只有固定 interval，需支持任务返回 `nextWakeAt` 覆盖（建议：`RoundScheduler.KeeperFacade` 增加 `businessNextWakeAt(fpid,sid,fn)` 查询，或任务把 `nextWakeAt` 写入 `srv:cad:*`，调度器取 `min(interval,nextWakeAt)`）。
  2. **notify 唤醒**：服务器侧需在 `SessionKeeper.attachWarHook` 同类位置新增 `addMessageHook`，按网页版 `interestedProtocols()` 映射（本报告 §1.2 表）把命中协议写给对应任务（置 `srv:cad:<fn>=now`），无需完整 TaskEngine；先覆盖高频止损协议：`notify_intelligence_info`（intel）、`notify_player_march_*`（gather/hunt）、`notify_alliance_trap_update`/`notify_mass_march_*`（bear）、`notify_arena_init`（arena）。
  3. **登录初始化竞态**：现网“建会话与开整轮同毫秒”（`vault-20261023.log` 16:58:45.601 建会话 + 开整轮），任务只 `collectNotifies(2500)` 后就读状态；网页版有 `DailyInitialDataSyncService` 等 init 推送（`service/DailyInitialDataSyncService.java:38-40`）。最小修复：整轮/单任务开始前等待“已登录 + 角色数据 ready”（`engine.sessionState()==ONLINE` 且 uid/serverId 就绪）+ `collectNotifies` 从 2500ms 提到 5-8s（或等到 `notify_world_player_init`/`notify_expert_info` 等关键 init 到达，带超时）。
  4. **独占/窗口互斥**：网页版有 ArenaExclusiveGate（`engine/ArenaExclusiveGate.java:31-37`）与 BearActivityGate（`service/DailyBearActivityGate.java:108-123`）；我方两个都没有服务器侧实现，导致竞技场开打/巨熊窗口内 gather/hunt/behemoth 仍可能占队列。建议最小实现：`stateStore` 写入 `srv:exclusive:<fpid>`（owner/窗口起止），`ServerFieldTasks.gather/hunt`、`ServerTasks.behemoth` 入口检查并 SKIP。

---

## 5. 附录：file:line 索引

### 5.1 网页版（根：`wjdr-review/src-tool/com/wjdr/tool/modules/daily/`）
- 引擎/唤醒：`engine/TaskEngine.java:112-118,316-321,446-476`；`engine/TaskSignalBus.java:37-58`；`engine/DailyTaskEngineBridge.java:188-229`；`task/AbstractStandardTaskModule.java:65-137,160-181,208-210`；`model/AbstractDailyAutomationTaskDefinition.java:13-23,32-73`；`engine/ArenaExclusiveGate.java:31-49`。
- 采集：`task/gather/GatherTaskModule.java:23,26,35-41`；`service/DailyGatherService.java:110,167-200,204-255,304-337,556-712,714-1011,1060-1079,1081-1180,1181-1354,1355-1420,1422-1535,1648-1665`；`config/DailyGatherConfig.java:21-30,54-60,213-216`。
- 情报：`task/intelligence/IntelligenceTaskModule.java:19,22`；`service/DailyIntelligenceService.java:83,113-130,156-207,256-262,264-345,346-384,452-535,531-568,598-633,634-668,671-710,712-787,919-928`；`service/DailyIntelligenceState.java:41-60,112-120,179-186,210`；`service/DailyIntelligenceEventCatalog.java:7-12,17-33,40-42`；`task/expertintelligence/ExpertIntelligenceTaskModule.java:21`；`service/DailyExpertIntelligenceService.java:38-41,113-143,200-214`。
- 巨熊：`task/bearaction/BearActionTaskModule.java:24,26-28,37-44`；`service/DailyBearActionService.java:103-181,183-211,216-241,243-300,305-374,435-453,468-501,507-570,679-702,703-724`；`service/DailyBearSchedule.java:15-16,25-33,43-72`；`service/DailyBearMarchExecutor.java:77-94,143-235,236-415,416-430,430-505,613-673,677-698`；`config/DailyBearActionConfig.java:19-23,37-39`；`service/DailyBearActivityGate.java:108-123`；`service/DailyBearActionState.java:142-160`；`service/DailyBearMarchTracker.java:43-104`。
- 巨兽：`task/behemoth/BehemothTaskModule.java:29,47-56,59-71`；`service/DailyBehemothService.java:139-207,313-351,407-444,484-563,579-651,652-690,752-846`；`config/DailyBehemothConfig.java:13-16,44-52`；`modules/behemoth/service/BehemothAutomationService.java:984-1005`；`modules/behemoth/service/BehemothLaunchFacade.java:34-107`。
- 狩猎：`task/hunt/HuntTaskModule.java:24,40-47`；`service/DailyHuntService.java:60-71,159-188,317,339,383-390`；`config/DailyHuntConfig.java:17-23,47-54`。
- 竞技场：`task/arena/ArenaTaskModule.java:24,38-49`；`service/DailyArenaService.java:68-81,133-162,204-295,357-467,475-562,564-605,607-656,669-730,739-836`；`service/DailyArenaState.java:160-186`；`service/DailyArenaWindowCoordinator.java:42,69-72,114-172`；`service/DailyArenaPreLoginService.java:40,66-68,108-144`；`config/DailyArenaConfig.java:16,39-49`。

### 5.2 我方（根：`VAContainer/`）
- 服务器：`vault-server/src/main/java/com/carlos/vaultserver/task/ServerFieldTasks.java:26-104,167-188,195-216,228-327,331-410`；`task/ServerTasks.java:114-120,1270-1295,1297-1309`；`task/ServerTownTasks.java:731-786`；`CaptureTasks.java:296-320`；`task/RoundScheduler.java:100-193`；`task/ModularRoundRunner.java:53-148`；`task/Modules.java:110-118`；`task/TaskCadence.java:77-81`；`task/TaskCadenceScheduler.java:149-209,215-228`；`Main.java:41-49`；`SessionKeeper.java:391-399,1372-1410`。
- App：`app/src/main/java/com/carlos/vault/daily/FieldTasks.java:31-127,199-284,287-321,323-...`；`daily/PushWakeDriver.java:25-71,95-163`；`daily/FnRegistry.java:83-89`；`daily/GatherCapacity.java`（容量估算文案，无续派逻辑）。
- 默认开关：`ServerTasks.java:114-120`（gather/intel/hunt false；behemoth/bear true；arena false）。

### 5.3 生产只读证据（`ssh -o BatchMode=yes vault-vps`）
- 版本/调度：`/opt/vault-server/data/logs/vault-20261023.log` 16:41:15.142（启动）、16:41:15.320（整轮调度器启动，**无任务节奏调度**）；`/opt/vault-server/vault-server-all.jar` 时间 2026-10-22 16:41。
- 账号配置：`/opt/vault-server/data/vault.json` accounts：48460403 auto=true window[5,9] roleConfig[888]={barracks,adventure_sweep,arena,gather,guardian,intel}=true；57259770 auto=false roleConfig 空。
- 整轮节奏：`state.json:srv:lastrun:48460403=1792688377862`（2026-10-22 16:59:37 UTC=10-23 00:59 北京）；`log:...:gather` 上一“定时”=10-21 21:45 UTC。
- 采集：`state.json:log:48460403:888:gather` 最近 21 条“附近未搜到资源点”；`vault-20261023.log` “采集搜索失败”0 次。
- 情报：`state.json:log:48460403:888:intel` “暂无情报事件”；`vault-20261023.log` 16:59:02.930 `medic notifies fpid=48460403` 列表含 `notify_intelligence_info`、不含 `notify_intelligence_events`；16:59:16.041 才出现 `notify_expert_quests`（整轮 16:58:45.601 已开始）。
- 巨熊：`state.json:mod:48460403:888:bear={"trap":"未预约 · 建议提前预约"}`；`state.json:log:57259770:724:bear` “已预约 10月23日 21:00”。
- 巨兽：`state.json:log:48460403:888:behemoth` “开启码1507/1600”。
- 竞技场：`state.json:log:48460403:888:arena*` “挑战5胜0”“次数未达标”（16/16 条）。

### 5.4 TODO（未闭环，需真机/部署后复验）
- [ ] res_id 102-105 直传后是否出现“派出采集×N”（P0-2 验收）。
- [ ] `behemoth auto_join_open` 错误码 1507/1600 的服务端语义（网页版未覆盖，需要响应体/抓包）。
- [ ] `notify_intelligence_info.events[].type` 实际取值与 `DailyIntelligenceEventCatalog.resolveType` 的 eventConfigId 映射一致性（我方当前直接读 `type` 字段，网页版是从 `eventConfigId` 解析；若推送里没有 `type` 字段需补映射）。
- [ ] 竞技场 `req_arena_fight` 响应 `report.win` 字段实测形状（我方“胜0”判定需据此修正）。
- [ ] 巨熊“预约”本身是否需要客户端请求（网页版无预约请求，只有读取；若本体由玩家手动预约，则我方只需按 reserve_time 发车）。
- [ ] 本分支 `TaskCadence/TaskCadenceScheduler` 部署后，各任务 15s 间隔是否造成请求风暴（网页版间隔 5-30s，但都是常驻会话 + 引擎节流；建议先灰度单账号）。
