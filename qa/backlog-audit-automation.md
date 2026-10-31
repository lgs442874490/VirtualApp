# 历史待办核对 · 自动化/奖励/联盟/商店/采集/情报/巨熊/竞技场（2026-10-30）

- 基线：master `c2e8987`（P0 + Phase1 + Phase2A/2B/2C 已合入并部署；生产 `VAULT_PER_ROLE=on`，App v4.2.18）。
- 只读核对：`git show master:…` + `ssh vault-vps`（state.json / vault-20261030.log / vault.json），未改源码、未重启/部署、未发游戏请求。
- 来源：①`design/七模块-1比1复刻-任务清单-20261023.md` §8/§9 未闭环+各批次候选；②`design/自动化模块-实现落差与修复待办-20261018.md` G1..G14；③`design/采集-队列-核心资产库-总体规划与开发清单-20261024.md` §5/§10.4；④`qa/audit-20261023/{reward,wilderness,alliance-shop}-gaps.md` P0/P1。
- 说明：文档中“未部署/未完成”已被 10-23~10-30 后续批次覆盖的，以当前代码为准判 ✅；🟡=主体已实现但有明确尾项；❌=仍未做。
- 生产证据缩写：`Plog` = 生产 `state.json` 的 `log:48460403:888:<fn>` 最新一条；`Pmod` = `mod:…` 键。

## 一、公共底座 / 调度 / 执行框架

| ID | 事项 | 来源 | 状态 | 证据（file:line / 生产） | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| C1 | 任务节奏调度（55 项/全天） | 七模块 §4 C1/§8 | ✅ | `task/TaskCadenceScheduler.java`、`TaskCadence.java`；Plog 各任务 10-30 全天运行 | — | — |
| C2 | notify 事件唤醒 | 七模块 §4 C2/§8 | ✅ | `task/TaskWakeRouter.java:28-39,275-317`（mail/endless/redpacket/activity 等映射） | — | — |
| C3 | 业务态 nextWakeAt 通用化 | 七模块 §8/§9 | ✅ | `task/TaskOutcome.java` nextWakeAtMillis；intel/gather/bear/supply 已接线（`ServerFieldTasks.java:2420`） | — | — |
| C4 | 登录初始化竞态（20s grace） | 七模块 §8 | ✅ | `TaskCadenceScheduler.java` INIT_GRACE_MILLIS；Phase2 按角色 onlineSince | — | — |
| C5 | 活动日历周期同步/门控 | 七模块 §8/§9 | ✅ | `CaptureTasks.captureCalendar`；`TaskWakeRouter.java:277` calendar→activity_rewards；`ServerTasks.activityCalendarBlock` | — | — |
| C6 | App 本地模式同修（采集/情报） | 七模块 §8 | ✅ | `app/.../daily/FieldTasks.java:56-80`（res_id 102-105） | — | — |
| C7 | 部署/灰度观察 | 七模块 §4 C7 | ✅ | master 部署（Phase2 验证报告：生产 1380 tests、双角色在线；`/status` byRole 已上线） | — | — |
| G1 | 模块默认值对齐任务目录 | 自动化落差 G1 | ✅ | `task/Modules.java:20`（默认唯一事实源=ServerTasks.enabledByDefault） | — | — |
| G2 | 断线截断整轮（SESSION_CLOSED） | 自动化落差 G2 | ✅ | `automation/ModuleRegistry.java:92-113`（hasSessionClosed→break） | — | — |
| G4 | FormationPlanner/ShopService 服务层接线 | 自动化落差 G4 | ❌ | `AutomationServices.java:27-30` 仍 `HeroDirectory.empty()`；全仓无 `.services()` 消费方；执行器为手写实现 | P1 | 文档降级“手写等价”，或真接线（HeroDirectory 工厂+FormationPlan 注入）；否则双算法漂移 |
| G5 | 配置分层（automation JSON） | 自动化落差 G5 | 🟡 | `AutomationConfig.java:24,68-70` defaults/legacyActions 仅测试用；唯一事实源仍是 roleConfig/roleParams | P2 | 文档明确未启用；或补 App automation JSON 同步链路 |
| G6 | 落账 dur 恒 0 | 自动化落差 G6 | ✅ | `automation/AutomationModule.java:61`、`ModuleRegistry.java:159` 已计时 | — | — |
| G7 | 商店刷新免费/钻石预算 | 自动化落差 G7 | 🟡 | `ServerShopLeagueTasks.java:224-226,394,661-681`（allow_diamond+max_diamond_spend 已有；免费次数计次仍未见） | P2 | 补 free_refresh_left 协议实证与计次 |
| G8 | 高级 JSON 参数被忽略 | 自动化落差 G8 | ✅ | `ServerTasks.java:5089-5098`（launch/prepare 参数）、`4907-4916`（type=102 payload）；编队参数接入 | — | — |
| G9 | sid=0 旧配置迁移残余 | 自动化落差 G9 | ✅ | `HomeActivity.java:133-165` configSid 统一；`app/src/test/.../PerRoleConfigStoreTest.java` 已覆盖 | — | — |
| G10 | 文档/QA 状态漂移 | 自动化落差 G10 | 🟡 | 七模块 §8/§9 仍写“未部署/未完成”，与现状不符；§5/§9 自相矛盾 | P2 | 由 Lead 统一维护状态基线，旧报告加“已被新报告覆盖”注记 |
| G11 | 测试门禁缺口 | 自动化落差 G11 | 🟡 | 仅有 `automation/ModulesDefaultParityTest.java`；无 `ModularRoundRunnerTest`/`AutomationServicesWiringTest` | P2 | 补整轮编排/断线截断/默认值一致性用例（G1/G2/G6 防回归） |
| G12 | App 57 项 vs 服务端 55 项 | 自动化落差 G12 | 🟡 | App `FnRegistry` 57（多 `guardian`/`war`，`FnRegistry.java:107-108`）；服务端 `ServerTasks` 55 TaskDef；Plog `war`=“待迁移到模块化目录” | P2 | `war` 只是 App 配置项，建议 runner 白名单跳过不再记“待迁移”；guardian 走反应式引擎 |
| G13 | 采集资源轮转游标 | 自动化落差 G13 | 🟡 | `ServerFieldTasks.java:1139-1145` 有队列 cursor；资源列表仍每轮从头（无跨轮资源游标） | P3 | 文档明示或补 state 游标 |
| G14 | 银行取款成功/续存<100 无记录 | 自动化落差 G14 | ❌ | `ServerActivityTasks.java:522-526`：`reDep>=100` 才续存，else 无 skip 记录 | P3 | 加 `else m.skip("余额<100不续存")` |
| G17 | 注释漂移 | 自动化落差 G17 | 🟡 | `HomeActivity`/`VaultStore` 旧注释（activeSid/默认值语义） | P3 | 随手修 |
| P2A-1 | 全局 20 派单/分双角色饱和 | Phase2 验证报告 §5 | 🟡 | 10-30 生产 `global_budget` 跳过 5787 次；`srv:cad:stats` byRole 已有 | P1 | 用户已定保持 20；建议评估 26–30 或按角色保底调参，观察限流 |
| P2B-1 | 开盾/战争态真实来袭未实战验证 | Phase2 验证报告 §5 | 🟡 | 生产仅 `mod:…:battle_shield` 旧键历史事件；无 10-29 后真实 WAR 触发记录 | P1 | 下一次真实来袭/被侦查时核对双角色 uid 键与旧键镜像 |
| P2B-2 | `war` App-only 任务占位日志 | 本次核对 | 🟡 | Plog `war`=“待迁移到模块化目录”（2 条），roleConfig 打开时每轮出现 | P3 | runner 跳过 App-only 任务（war/guardian）或文档说明 |

## 二、奖励 / 活动

| ID | 事项 | 来源 | 状态 | 证据（file:line / 生产） | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| R1 | 活动奖励 17 项取数修复 | 七模块 §3.1 P0 / reward P0-1 | ✅ | `ServerTasks.activityRewards`（1500-2900 区）+ `ServerNotifyHistory` 多实例；Plog `activity_rewards`=领取0/跳过15（未开放自动跳过） | — | — |
| R2 | 卡 BP/英雄殿堂/英勇见证真协议 | 七模块 §3.1 P0 / reward P1 | ✅ | `ServerTasks.java:1577-1583` value_exchange index 表；`req_card_bp_collect_reward`；Plog `card_bp` 任务存在 | — | — |
| R3 | 挂机奖励字段/节奏 | 七模块 §3.1 P0 / reward P0-2 | ✅ | `ServerTasks.java:1341-1357`（open_hang_up_reward_view_time/current_reward/hang_start_time） | — | — |
| R4 | 每日任务事件化+活跃度箱 | 七模块 §3.1 P0 / reward P0-3 | ✅ | `TaskWakeRouter` dailyquest→daily_mission；Plog `daily_mission`=今日任务奖励已全部领取（码4302） | — | — |
| R5 | 邮件 25 类型（当前 21） | 七模块 §3.1 P1 / reward P0-6 | 🟡 | `ServerTasks.java:564-568`：system1+alliance4+battle6+message9+collection1=21 型；Plog `mail`=没有可领取附件 | P1 | 对齐网页版 25 型（补 4 型）并真机抽查战报/消息板块 |
| R6 | 统帅礼包 lv 参数/4503 | 七模块 §3.1 P1 / reward P0-5 | ✅ | `ServerTasks.commanderGift`；Plog `commander_gift`=统帅特权未激活（SKIPPED 非 ERROR） | — | — |
| R7 | 登录好礼 21 天/七天状态化 | 七模块 §12 批次4/§11 候选 | 🟡 | `ServerTasks.java:436-487` 三轨 mod 已写；Plog `login_rewards`=DEGRADED“21天签到错误码11506” | P1 | 映射 11506（未同步/未开放）→ SKIP/等待 notify；真机校准 21 天 |
| R8 | 背包自动开箱产品决策 | 七模块 §3.1 P2 / reward P0 | ✅ | `ServerShopLeagueTasks.java:2282-2330`（资源包 100111-100115 + random_boxes 可选）；Plog `backpack`=无可使用道具（未配置类型） | — | 产品确认是否扩大自动使用白名单 |
| R9 | 活动 id 目录补齐 | 七模块 §11/§12 候选 | ❌ | `ServerTasks.java:2501`：未知活动 id 直接 skip；Plog `customize`=“自选箱未知活动 id 2507019（网页版常量表 15 个 id 之外）” | P1 | 补 2507019 等新活动 id/族映射 + 单测 |
| R10 | 领取码未映射（10151 已修，11506/6159 未修） | 本次核对（Phase2 修 10151） | 🟡 | `ServerTasks.java:2917` 已有 40031 等；11506（21 天签到）、6159（体力补给）全仓无匹配；Plog supply=“体力补给:错误码 6159” | P2 | 按网页版码表补 11506/6159（及 1543/11794，见 L1）分类 |
| R11 | 邮件/无尽/红包推送化 | reward P1 | ✅ | `TaskWakeRouter.java:285-312`（notify_new_mail_syn→mail；notify_general_bp_*→act_endless；notify_red_packet_user_info→red_packet） | — | — |
| R12 | 失落/米娅/无限/自选/钓鱼形态 | reward P1 | ✅ | `ServerTasks.java:2342-2460`（lost/wish/mia/infinite/dawn）；Plog 多个任务“活动未开放/通知未同步”属未开而非缺实现 | — | 活动开放期再真机复验 |
| R13 | 日报按任务/角色聚合计数 | 七模块 §12 批次7 候选 | ✅ | `DailyDigest.java:89-90,322-405`（消费 srv:cad:stats，byAccount/byRole）；生产 state 已有 byRole | — | — |

## 三、联盟 / 商店

| ID | 事项 | 来源 | 状态 | 证据（file:line / 生产） | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| L1 | 联盟宝箱批量 by_ids + is_end 对账 | 七模块 §3.6 P1 / alliance-shop-gaps | 🟡 | `ServerTasks.java:3040-3115` 逐箱 `req_alliance_receive_chest{id,type}`；无 `receive_chest_by_ids`/`is_end`；已处理 2/4303/7501/40611/1529/1540 | P1 | 补批量协议+is_end 对账，减少请求数 |
| L1b | 宝箱未映射码 1543/11794 | 本次核对（Plog） | ❌ | Plog `alliance_chest` 近几条=ERROR“宝箱失败×8（码[1543]）”；`pet`=“联盟宝箱无可领(码11794)”；源码无 1543/11794 处理 | P1 | 归入 1540 同款失效集合或 skip 码表（1543/11794），避免每轮 ERROR |
| L2 | 科技捐献（Map/lv+1/免费次数/循环） | 七模块 §3.6 P0 | ✅ | `ServerTasks.allianceDonate` + P0 修复；Plog `alliance_donate`=免费次数已用尽→恢复后自动捐献；Phase1 uid 隔离 | — | — |
| L3 | 总动员完整状态机 | 七模块 §3.6 P1 / §9 | ✅ | `ServerShopLeagueTasks.java:1547+`（init/奖励/接取/刷新/免费首购）；Plog `alliance_mobilization` 正常轮转 | — | — |
| L4 | 争霸赛窗口/编队 | 七模块 §3.6 P1 / §9/§12 | ✅ | `ServerShopLeagueTasks.java:1696-1714,1925,2070-2120`（signupDay/窗口/5:2:3 编队）；默认关 | — | 真机字段校准仍建议（P2） |
| L5 | 联盟互助常驻 | 七模块 §3.6 | ✅ | `ServerHelpResponder`（Phase1 按 uid）；Plog `alliance_help`=常驻响应器已接管 | — | — |
| S1 | 商店安全闸（不误花钻） | 七模块 §3.7 P0 | ✅ | `ServerShopLeagueTasks.java:163-300,394,661`；默认 allow_diamond=false | — | — |
| S2 | buy_names 名单/预算/pending 对账 | 七模块 §3.7 P1 / §9 | ✅ | `ServerShopLeagueTasks.java:168-226`（buy_names 优先）、`:435-492`（pending 防重）、币种预算 | — | — |
| S3 | 12 店铺 942 商品目录 | 七模块 §3.7 | ✅ | 目录对齐（批次1 审计 + 后续 S2）；prod `shop/alliance_shop` 默认关 | — | — |
| D4 | 周捐献排名 type 固定 2 | alliance-shop-gaps D4 | 🟡 | `ServerTasks.java:3443` 仍 `rank_type` 默认 1（网页版周捐献排名固定 type=2） | P1 | 默认改 2（或按周/日自动选），补单测 |
| D5 | 捐献发展/排名奖励状态前置+子开关 | alliance-shop-gaps D5 | 🟡 | `ServerTasks.java:3437-3460` 仅 ClaimLedger 去重；无 `subOn("alliance_donate",…)` 子开关 | P2 | 加子开关与状态前置，减少无谓请求 |
| D6 | 40031（每日激励已领）码映射 | alliance-shop-gaps D6 | ✅ | `ServerTasks.java:2917`（40031/40032/40034 已归已领） | — | — |
| L0 | 联盟宝箱多帧 add 合并 | 七模块 §11/§12 | ✅ | `ServerTasks.mergeChestContent:3117+`（init/add/update 合并，trophy 上限） | — | — |

## 四、采集 / 情报 / 巨熊 / 巨兽 / 竞技场

| ID | 事项 | 来源 | 状态 | 证据（file:line / 生产） | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| G1 | 采集 res_id 102-105 | 七模块 §3.2 P0 | ✅ | `ServerFieldTasks.gather` + App `FieldTasks.java:56-80`；Plog `gather`=队列已满(capacity=3/6 occupied) | — | — |
| G2 | 采集任务内续派 | 七模块 §3.2 P0 / §9 | ✅ | `ServerFieldTasks.java:440-449` 写 `srv:cad:…:gather`（min(180s, 最早行军 endtime-1s)） | — | — |
| G3 | 联盟矿 type=110/采集加速/重物搬运 | 七模块 §3.2 P1 | ✅ | `ServerFieldTasks.java:1066-1069,1574+`（maintainGatherSpeedup/maintainHeavyLifting，默认关/道具优先）；Pmod gather=“8小时采集加速生效中/heavy” | — | 默认开关与钻石购买保持显式；真机继续观察 |
| G4 | fullResourceOnly 默认对齐 | wilderness-gaps/七模块 §3.2 P2 | ✅ | `app/.../gather/GatherConfigV2.java:135` 默认 true；旧配置迁移保持旧行为 | — | — |
| I2 | 情报体力/队列/英雄预留门控 | 七模块 §3.3 P0 | ✅ | `ServerFieldTasks.intel` 门控（批次2）；Plog `intel`=配置未启用（用户未开） | — | 用户开启后真机复核 |
| I3 | 专家技能 effect_type:6 | 七模块 §3.3 P1 / §9 | ✅ | `ServerTownTasks.java:820-1066`（安全窗/40237/台账）；Pmod expert=“专家情报技能已使用” | — | — |
| I4 | 专家额外情报初始化竞态 | 七模块 §3.3 P0 | ✅ | 初始化宽限/`expert_extra` 重试（批次2）；与 I3 同一任务 | — | — |
| B1 | 巨熊周窗口/type=411/99/对账 | 七模块 §3.4 P0 | ✅ | `ServerBearTasks`（窗口/预热/编队/上车）；Plog `bear`=已预约 10月31日 21:30；Pmod bear 有 start/end/reserve | — | 10-31 21:30 窗口做编队+301 复验（原 10-23 只验证到 code=301） |
| B1b | 巨熊 301 逐队降级重试 | 七模块 §10/§12 候选 | ✅ | `ServerBearTasks.java:120-122,338-408`（blacklist301，MAX=2 轮） | — | 随 B1 窗口真机复验 |
| B2 | 巨熊窗口与采集/情报 Gate 协调 | 七模块 §3.4 P1 | ✅ | `ServerFieldTasks` gatherPauseReason（bear 窗口让位，批次3/10） | — | — |
| A1 | 竞技场结算窗/真实胜负/重试 | 七模块 §3.5 P0 | ✅ | `ServerTownTasks.java:1258-1290,1514-1720`（23:10 窗、report.win、重试、不写死）；Plog `arena`=配置未启用（用户关） | — | 开启后真机复核 23:10 结算 |
| A2a | 竞技场买次数（默认关+预算） | 七模块 §3.5 P1 / §9 | ✅ | `ServerTownTasks.java:1279,1683-1720`（req_arena_buy_challenge_times，余额未知不买） | — | — |
| A2b | 竞技场独占窗（其它任务让位） | 七模块 §3.5 P1 | ❌ | 全仓无 ArenaExclusiveGate/独占窗实现（仅 arena 自身结算窗判断） | P2 | 明确是否需要（竞技场窗口内让位其它队列任务） |
| P0-5 | 巨兽每日发起/99 上车 | 七模块 §3.4/wilderness P0-5 | ✅ | `ServerTasks.java:4091-4116,4184,4907`（type=102/99，launch 默认关）；Plog `behemoth`=自动上车已开启、发起默认关 | — | 用户开启 launch 后真机复核 |
| G3b | 采集 7/8/9 级资源命中验证 | 采集库 §10.4 | 🟡 | 等级无响应字段，按王国天数+逐级搜索实现；生产 log 未见 7/8/9 命中明细 | P2 | 真机只读观察一轮各等级搜索命中 |

## 五、资产库 / 采集队列（采集库文档）

| ID | 事项 | 来源 | 状态 | 证据（file:line / 生产） | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| C1-C3 | 资产库核心/队列公式/配置 v2 | 采集库 §5.1 | ✅ | `app/src/main/java/com/carlos/vault/wjdr/asset/**`、`QueueCapacity`、`GatherConfigV2`；server 引用同源 | — | — |
| A1-A2 | 10 域解析器 + accept 分发 | 采集库 §5.2 | ✅ | `wjdr/asset/parsers/**`、`AssetParsers`；单测齐全 | — | — |
| S1-S3 | 服务端 gather 队列化/容量替换/测试 | 采集库 §5.3 | ✅ | `ServerFieldTasks.gatherV2`、`GatherQueuePlanTest`；Plog `gather` queue: capacity/occupied 正常 | — | — |
| U1-U4 | 采集设置页/入口/编队/单测 | 采集库 §5.4 | ✅ | `GatherConfigActivity`、`FormationPicker`、`GatherConfigV2Test` | — | — |
| W1-W4 | 资产库接线/投影/App 读 | 采集库 §5.5 | ✅ | `SessionKeeper` attach/project（含 uid 键）、`srv:asset`；App 设置页读投影 | — | — |
| Q3 | 审核/合并/部署 | 采集库 §5.6 | ✅ | 代码已在 master；生产运行（asset 键存在） | — | — |
| §10.4-1 | 联盟矿/加速/搬运“本期不做” | 采集库 §10.4 | ✅ | 后续批次已实现（G3 行），文档已过时 | — | 更新文档 |
| §10.4-2 | 城市坐标 city_point 未进 snapshot | 采集库 §10.4 | ✅ | `AssetSnapshot.java:100`（city_point 字段已存在） | — | — |
| §10.4-3 | 战争/驻防域只事实表未迁移 | 采集库 §10.4 | ✅ | `SessionKeeper.java:728` syncAssets→WarState；Guardian 读资产库队列 | — | — |
| §10.5-4 | 资产页/读点 P1 迁移 | 采集库 §10.5 | 🟡 | `AssetCenterActivity` 已有；巨兽/巨熊等仍有旧字段兜底路径 | P2 | 逐步改读资产库，保留 fallback |

## 六、统计

| 状态 | 数量 | 说明 |
|---|---|---|
| ✅ 已完成 | 52 | 含后续批次已覆盖的旧“未完成” |
| 🟡 部分完成（有明确尾项） | 18 | 见上表 |
| ❌ 未做 | 5 | G4、R9、L1b、A2b、G14（G14 为 P3 边角，未计入 Top10） |
| **合计核对** | **75** | 来源文档候选/未闭环项去重后 |

> 注：G14（银行 <100 不续存无记录）、G10/G11/G13/G17 属低优先；R5 邮件 21/25、D4 周排名 type、D5 捐献子开关为“文档承诺 vs 代码”差距，用户可见度中等。

## 七、Top 10 未完成/建议优先处理（按用户影响排序）

1. **L1b 宝箱错误码 1543/11794 未分类**（Plog `alliance_chest` ERROR×7-8/轮；`pet` 同码文案）→ 归入 1540 失效集合/跳过码表，消除每日 ERROR 与无效请求。
2. **G4 FormationPlanner/ShopService 未接线**（服务层死代码 + 手写执行器双算法漂移）→ 文档降级或真接线。
3. **R9 活动 id 2507019 未识别**（Plog customize “15 个 id 之外”）→ 补目录+活动族映射。
4. **R7 21 天签到 11506 未映射**（Plog login_rewards DEGRADED）→ 码表分类+等待 notify。
5. **D4 周捐献排名 type 默认 1（应 2）**（`ServerTasks.java:3443`）→ 默认 2+单测。
6. **L1 联盟宝箱批量 by_ids/is_end 未做**（逐箱请求，双角色放大）→ 批量+对账。
7. **P2A-1 20/分预算双角色饱和**（10-30 global_budget skip 5787）→ 评估 26–30/分或每角色保底（用户已定 20，需再次确认）。
8. **R5 邮件类型 21/25**（`ServerTasks.java:564-568`）→ 补齐 4 型并真机抽查。
9. **B1 巨熊窗口未复验**（10-31 21:30 已预约；301 降级已实现但只在 10-23 验证到 code=301）→ 下个窗口做编队+301 复验。
10. **A2b 竞技场独占窗缺失 + R10 6159/11506 未映射**（P2）→ 明确产品口径后补。

> 另：G12 `war` 占位日志、G14 银行<100 记录、G10/G11 文档/测试门禁为 P2/P3 清理项，可并入下个维护批。
