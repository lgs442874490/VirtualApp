# 历史待办核对（B）：守护盟友 / 编队 / 账号池 / 观察点 / 离线通知 / 门控 / 热更 / 鸿蒙

- 任务：Agent Teams `task-16`（只读核对；本文是唯一产出）
- 代码基线：**master `c2e8987`**（含 P0/Phase1/Phase2 全部交付，v4.2.18）；主工作区工作树在 `modify/ui-unification-…` 分支（未改动）
- 运行态基线：生产只读 `ssh vault-vps`（2026-10-30 13:4x 北京；`state.json` 2008 键/2.4MB，`vault-20261030.log`）
- 判定口径：✅=当前代码/生产已具备；🟡=部分实现/已实现但未放出/待验证/陈旧描述；❌=未实现或平台边界
- 说明：**不照抄旧文档**——2026-10-24~30 的多次交付（Guardian P1、编队 1:1、账号池 P1/M6、观察点 MVP、Phase2 角色隔离、v4.2.18）已关闭大量旧条目，下表以当前代码为准

## 0. 统计与结论

| 领域 | 条目数 | ✅ | 🟡 | ❌ |
|---|---|---|---|---|
| A 守护盟友（P0-P2 + 早期 v1.3 设计） | 14 | 5 | 4 | 5 |
| B 编队 1:1（服务端+App） | 10 | 7 | 3 | 0 |
| C 账号池 / 账号页 | 10 | 8 | 0 | 2 |
| D 观察点 MVP | 7 | 2 | 1 | 4 |
| E 离线通知 / 风险 | 7 | 4 | 1 | 2 |
| F 门控 / 分级 / 下架 | 7 | 4 | 2 | 1（另 16 项未开发但已正确隐藏） |
| G 热更自托管 | 5 | 2 | 2 | 1 |
| H 鸿蒙克隆 | 5 | 2 | 2 | 1 |
| X 跨领域（Phase2 遗留） | 3 | 2 | 0 | 1 |
| **合计** | **68** | **36** | **15** | **17** |

**一句话**：执行链路类历史待办绝大多数已落地（守护 P0/P1 加速、编队 1:1、账号页 P1、观察点 MVP、热更自托管、鸿蒙引导）；剩余缺口集中在**守护的“卡秒/战报/多目标”实战闭环**、**观察点的 UI/深度判定**、**状态保留策略**与**若干门控/文案陈旧**。

---

## A. 守护盟友（来源：`design/守护盟友-服务端化设计与分期-20261023.md` P0/P1/P2；`design/守护盟友-自动驻防系统设计-20261015.md` v1.3）

| ID | 事项 | 来源 | 状态 | 证据（当前代码/生产） | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| A1 | P0 最小闭环：服务端检测→type=5 发射→撤防→状态/账本回显 | 20261023 §4.1 | ✅ | `guardian/ServerGuardianEngine.java`、`ServerGuardianLauncher.java`（变体/endtime/off_work）；生产 `docs(qa): 追加守护盟友自动编队` 部署后日志 `守护盟友 P0 钩子已挂载 … dry_run=false` | - | 保持 |
| A2 | P1 对等加速（301900/301901、库存记账、先至余量、钻石路径默认关） | §4.2 | ✅ | `GuardianSpeedupPlanner.java`；Engine `:593,:804`；生产 30 日 05:30 配置 `speedup=true diamond_speedup=false` | - | 灰度观察 |
| A3 | P1 卡秒发射（300ms 重估敌 ETA→`waitUntil`→fireAt 窗口） | §4.2 | ❌ | 服务端为“决策即发 + 加速追赶”：`ServerGuardianLauncher` 无 `waitUntil/fireAt`；Engine `:131` 注释“ETA 过近不再追（P1 卡秒/加速才处理）” | **P1** | 落地 `GuardianTiming`+等待重估（设计 §3.5/§6.4），否则 G1“先于敌到达”只能靠加速，拦截窗口窄 |
| A4 | P1 战报判定 + 防守码表校准（`notify_new_mail_syn`） | §4.2/R8 | ❌ | 服务端无 mail 解析（`ServerGuardianEngine` 无 `new_mail/report` 分支）；仅 App `GuardReportCodes.java`（本地模式） | **P1** | 服务端接 first_type=30 战报→胜/负/未知（未知不撤），码表用真实样本校准 |
| A5 | P1 压缩/EXTREME 幂等响应（同一压缩态一次、单轮≤6 发） | §4.2 | ✅ | Engine `:35,:42,:354-355`（`speedupRounds` 幂等 + 单轮链上限） | - | 保持 |
| A6 | P1 多威胁排队 / 并发≤2（App 上限 2） | §4.2 | 🟡 | Engine `:144 MAX_CONCURRENT_MISSIONS=1`、`:340 activeTargets` 单目标 | P2 | 提并发/按 ETA−D−Δ 排序派发 |
| A7 | P2 `GuardianHold` 常驻戒备（EXTREME 不撤防窗口） | §4.3-2 | ❌ | 仅有 App `daily/guardian/GuardianHold.java`；服务端 Engine 无 hold 字段/分支 | P2 | 服务端移植 EXTREME 锁存+不撤防窗口 |
| A8 | P2 `GuardianPreGarrison` 预驻防（H2/H3 提前派驻/10min 冷却/默认关） | §4.3-2 | ❌ | 仅 App `GuardianPreGarrison.java`；服务端无 | P2 | 视产品价值再立项（默认关、双预算） |
| A9 | P2 盟友坐标表（`notify_alliance_member_pos_info/_update`） | §4.3-3 | ✅ | Engine `:117-118` 协议常量、`:575 allies.putAll`、`:331 allies` 表 | - | 保持；补退盟 evict 单测（若缺） |
| A10 | P2 产品化：T3 门控/FnDetail 配置页/实发二次确认 | §4.3-5 | ✅ | `app/.../FeatureGate.java:90 GUARDIAN(premium,true)`；`FnDetail.java:202` guardian 子项；生产 guardian 实盘已开 | - | 保持 |
| A11 | P2 观测：`srv:guardian` 账本接入 DailyDigest / HealthMonitor 告警 | §4.3-6 | 🟡 | Digest `:91-94,:358,:483` 已解析 guardian；**HealthMonitor 无 guardian 段**（grep 空） | P2 | 加“在线但 24h 无守护事件/连续失败”健康告警 |
| A12 | P2 安全预算：每行军/钻石上限、账号级日预算、协议预算 | §4.3-7 | 🟡 | 有 `max_speedups_per_march/diamond_max_per_march`（Engine Config）；**无按日/按账号预算** | P2 | 加日预算与 429/限流兜底 |
| A13 | 早期 v1.3 设计的未落地项：GuardRule 多规则数组、EndgameGuard、SnapshotExecutor 背靠背流水、QueueGuard/SlotPools 反佯攻、迁城释队 A-23、回程加速、260s 内多波次 | 20261015 §4-§6 | ❌ | master 无对应实现（仅 P0/P1 收敛版）；A-23 迁城仍为 debug 探针 | 视立项 | 作为 v2 设计池，不默认承诺 |
| A14 | 实盘实战验证：24h 内 launch/speedup 事件、目标/盟友白名单配置 | §4.3 验收 | 🟡 | 生产 ledger 50 条**全为 threat_landed**（无 launch/speedup）；配置 `ally_uid=0 target_uid=0`；近 24h 无实际驻防 | **P1** | 配置真实盟友/目标做 3 场真机对拍（不加速/加速/召回），补齐验收报告 |

## B. 编队 1:1（来源：`design/编队功能-*.md` 20261025 四篇 + 实施契约）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| B1 | 英雄名引号 62/62、缺 3 英雄、代际/职业目录 | App 缺口 P0-1/P2-1 | ✅ | `HeroCatalog.java` 名表为正常中文（`:95-102`）；`GENERATIONS` 表存在（`:26,:161+`） | - | 新英雄仍按版本补表 |
| B2 | 目录 v2：uid 键、capturedAt、marchCapacity{0/1/2/3 英雄档}、gameFormations、available | 服务端 P0-4/P1-3/P1-8 | ✅ | `FormationCatalog.java:60 VERSION=2,:163 gameSlots,:220+ 序列化`；生产 `srv:formation:catalog:48460403:888:{uid}` version=2、heroes=40、gameFormations=6、marchCapacity 全档 | - | 保持 |
| B3 | ROLE 合法性：≤3/去重/职业唯一/目录存在/301-403 黑名单/容量缩放/formation_ids 候选回退 | 服务端 P0-1/P0-2/P0-3/P1-2 | ✅ | `FormationResolver.java:282-294`（校验+黑名单）、`:318-351`（fitFormation 缩放）、`:815 formationIdsOf` | - | 补回归样本 |
| B4 | 编队记忆（DAILY/BATTLE 保存/恢复，`req_soldier_set_prefab` 逐槽） | 服务端 P1-4 | ✅ | `formation/FormationMemory.java` + `VaultHttpApi.java:718` `formation.memory-save/restore`；生产 state 存在 memory 块 | - | 真机多槽恢复冒烟 |
| B5 | 巨熊模板（模板表 + 上车/绑定） | 服务端 P1-5 | ✅ | `app/src/main/assets/formation/bear_templates.json` + `FormationStore.bearTemplates`；服务端 `formation.bear-binding`（`:718-760`） | - | 模板数据随版本更新 |
| B6 | 服务端一键编队 action（GATHER_MEAT/WOOD/…内置模板绑定各队列） | App P1-7 / 服务端 P1-6 | 🟡 | 仅 App `daily/formation/FormationQuickSetup.java` 本地写入绑定；服务端无 `formation.quick-setup` action（App 保存后经 params 生效，功能等效但无服务端编排） | P2 | 若需“服务端一键”再补 action；否则文档标注现状 |
| B7 | App 编队管理页三段式/头像卡/职业占位/比例弹窗 | App P0-2/P0-4/P1-1/P1-2/P1-6 | ✅ | `FormationManagerActivity.java:46-47`（3 tab+子 tab、游戏编队只读）、`ui/HeroTileView.java`、`ui/FormationRatioDialog.java` | - | 保持 |
| B8 | 游戏内编队只读区（`gameFormations` 展示） | App P1-3 | ✅ | `FormationManagerActivity.java:252,:328-357` 拉取/展示 gameFormations | - | 保持 |
| B9 | P2 UI 打磨：分页(10/页)、RecyclerView 性能、兵种图标、容量实时预览、删除引用提示 | App P2-1/2/3/5/6 | 🟡 | 删除已有“引用校验提示”（`FormationManagerActivity:1871`）；分页/RecyclerView/图标未逐项落地（`daily` 仍多 LinearLayout） | P2 | 按使用量排序做（先分页/预览） |
| B10 | 301/403 日志占位符缺参、目录日志缺 fpid/sid（可归因） | 服务端 P2-3 | 🟡 | master 已无旧占位串（`301 英雄拉黑` grep 空，疑重构）；目录日志含 fpid/sid/uid（`SessionKeeper:2515+`） | P3 | 抽查生产日志，若仍有缺参补 |

## C. 账号池 / 账号页（来源：`design/账号池M1…/M2M3…/P1…`、`网页版账号页复刻-20261021`、`角色管理-同服多角色修复-20261020`）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| C1 | M1 服务端角色池接口（roles/role-action/account-action/status） | 账号池M1 | ✅ | `VaultHttpApi` `/v1/vault/accounts,role-action,account-action`；`VaultStore.RoleEntry` | - | 保持 |
| C2 | M2/M3 账号页 + 分身创建/游戏版本升级 | 账号池M2M3 | ✅ | `AccountVaultActivity.java`（批量导入/网格/角色行）、`gameupdate/*`（verifier/clone/update） | - | 保持 |
| C3 | P1 防封保护 / 易盾风控检测 | 账号页 M6 可选 | ✅ | `WjdrEngine.java:212,238,280`（一次性易盾检测+防封上报）、`wjdr/HtProtectTokenSupport.java`、`SessionKeeper:1838 antiban` + App `PoolRoleActions` 防封确认 | - | 生产观察 40271/风控码 |
| C4 | 顶号恢复设置（账号级 initialDelay/retry/attempts/cooldown） | 账号页 P1 §三.3 | ✅ | 服务端 `VaultHttpApi:775 recovery-settings`；App `HomeActivity:3271-3738` 恢复设置卡 + `VaultSync:827` 上报 | - | 保持 |
| C5 | 批量导入/导出失败明细（SAF） | 账号页复刻 §5 | ✅ | `AccountVaultActivity.java:89-96`（ACTION_OPEN/CREATE_DOCUMENT + 冲突明细） | - | 保持 |
| C6 | 分组 / 移动账号到分组 | 账号页复刻 §4 | ✅ | `AccountVaultActivity.java:51-75` 分组表头/排序/折叠；`RoleManagerSheet` 角色级管理 | - | 保持 |
| C7 | 实时推送（网页版 WS realtime）替代 30s 轮询 | 账号页复刻 §5 差异 | ❌ | App 仅轮询 `VaultSync:951 fetchEvents` + `/v1/vault/events`；无 WebSocket | P2 | 需服务端 WS/SSE + App 长连接（收益=状态秒级） |
| C8 | 多用户 admin /「仅我的账号」 | 账号页复刻 §1 后续 | ❌ | 全链路单用户（无 user 维度）；FeatureGate 无多用户能力 | 暂不立项 | 产品决策后再做 |
| C9 | 资源统计 / 资产中心 | 账号页 M6 | ✅ | `AssetCenterActivity.java` + `srv:asset:*:uid` 投影（生产 3 键：旧+双角色） | - | 保持 |
| C10 | 旧限制「同服双角色共享开关/参数/指纹/统计」 | 角色管理 §4 已知限制#1 | ✅ 已修复 | Phase2C：`VaultStore.roleConfigByUid/roleParamsByUid`；生产 `roleConfigByUid` 已存 5 个角色（含同服 2 角色各自条目） | - | 保留旧 sid 键作默认/回滚 |

## D. 观察点 MVP（来源：`design/观察点-服务端MVP-20261027.md`）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| D1 | 服务端 MVP：监视敌方 80 级炉/保护友盟聚集地/迁城跟踪，只发告警 | §1-§3 | ✅ | `watchpoint/WatchPointEngine.java`（令牌桶 `:123`、轮询 `:313`）；生产 `watchpoint.json` 配置 4 目标 + wecom，`vault-20261030.log` 观察点日志 2091 行/日 | - | 保持；定期看“告警=0”是否因目标不活跃 |
| D2 | 误报过滤（自循环/未知联盟/只报打到友盟城） | 后续 commits `5cf1cd0/14dadc8` | ✅ | 生产日志 `[观察点] 来袭候选被过滤（目标城联盟未知…）`；`自循环忽略/未知目标忽略` 计数 | - | 保持 |
| D3 | App 端「观察点」配置页 | §9 未包含 | ❌ | App 无 watchpoint 页面/API（仅服务端 `data/watchpoint.json`） | P1 | 加 `/v1/vault/watchpoint` 配置 API + App 页（多目标增删/告警类型/企微） |
| D4 | 燃烧/城墙/护盾判定（逐城 `req_world_mapobj`） | §9 | ❌ | 未实现（引擎仅用地图对象 uid/lv/aid + 态势推送） | P2 | 先实测单城字段，再做护盾/燃烧告警 |
| D5 | 多账号 / 多区服（按 kid 分组） | §9 | ❌ | `WatchPointStore` 单 `kid` + 单会话；无分组 | P2 | 按 fpid/kid 多实例 + 配置 |
| D6 | 行军加速/召回二次告警、战报关联 | §9 | ❌ | 无二次告警/战报关联逻辑（只 add/update/del 首次判定） | P2 | 加“目标被加速/被召回/战报结果”事件 |
| D7 | 全量扫描/地图刷新频率可配 | §6/§9 | 🟡 | `fullScanIntervalMs` 代码支持（`:169,:308`），**生产配置=0（关闭）** | P3 | 需要全图刷新时再开，注意请求预算 |

## E. 离线通知与风险检测（来源：`design/离线通知与风险检测可行性-20261024.md`）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| E1 | 结论：离线=“游戏 TCP 连接不在”；常驻会话在线=准实时 | §0-§3 | ✅ | 仍成立；生产常驻会话 + 心跳/重连/顶号恢复（`SessionKeeper`） | - | 对客口径沿用 |
| E2 | `force_online` 风险强制回连（暂停/让位/退避期间的最后一公里） | §5 P3 | ✅ | `SessionKeeper.riskForceOnlineReason` + `ServerShieldResponder/ServerGuardianEngine.forceOnline`；守护仅实盘生效 | - | 保持 60s 节流/次数上限 |
| E3 | 企微机器人通知（WarNotify→WeComNotifier） | §5 P2 | ✅ | `WeComNotifier.java`；生产健康告警/观察点 `wecomEnabled=true` 已推送历史 | - | 保持 token/webhook 轮换 |
| E4 | App 轮询 `/v1/vault/events` 作为外部通知兜底 | §5 P2 | ✅ | `VaultSync.fetchEvents`（`:951`）+ `VaultHttpApi /events` | - | 保持 |
| E5 | 外部进程 watchdog（健康 `/healthz`+存活+token 过期告警/重启） | §5 P4 | ❌ | 生产仅 systemd 服务 + 系统定时器，无专用 watchdog；`/healthz` 仅被动 | **P1** | 加外部 cron/脚本：healthz、token 余量、日志停滞→企微告警 |
| E6 | U6：active 角色掉线时兄弟会话是否接管 shield/guardian | §7 U6 | 🟡 | Phase2 后 `perRole=on` 时全角色各自 shield/收割；**guardian 仍仅 active 角色挂载**（`SessionKeeper:967 if (active)`） | P2 | 产品决策：守护是否允许兄弟角色接管（当前明确不接管，需文档化） |
| E7 | 短信通道（App 轮询→短信/电话） | §5 P2 | ❌ | 无短信 SDK/权限；仅企微 | 暂不立项 | 企微已覆盖多数；短信需资质与成本 |

## F. 门控 / 分级 / 下架（来源：`design/全流程行为与分级门控-20261023.md` §4 20 项）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| F1 | tier 字段 + `implemented` 门控（未开发/无权限不显示） | §1-§3 | ✅ | `FeatureGate.java`（`visible()`：implemented=false 任何等级隐藏；`:90-117` 清单）；`ToolBoxActivity:121-129` 按 tier 渲染 | - | 保持；新增功能先进 FeatureGate |
| F2 | 历史“规划中 · 14”整段下架 | §4 #1-14 | ✅ | `ToolBoxActivity.java:29-30,:121-122` 注释 + 代码无该网格 | - | 保持 |
| F3 | P1 协议探针/协议监听仅 debug 包 | §4 #16/#17 | ✅ | `ToolBoxActivity.java:185 if (debugBuild)`；`FeatureGate PROTOCOL_LOGGER/PROBE debugOnly=true` | - | release 包抽查 |
| F4 | “清理缓存（占位）”移除/实现 | §4 #15 | ✅ | master `清理缓存` 字符串已无（grep 0） | - | 保持 |
| F5 | 未开发/未立项功能（16 项）仍隐藏：排行榜/进攻撞矿/起号/活动榜单/迁城/三消/爆兵/宠物洗练/巨兽批量/坐标查询/侦察战报/移民抢位/充值记录/背包自动开箱/战争状态任务行/编队管理独立 gate | §4 全表 | ❌ 功能未开发（门控已正确隐藏） | `FeatureGate.java:96-117`（implemented=false）；`ToolBoxActivity` 仅保留已实现项 | 按立项 | 逐个立项开发后再置 implemented=true |
| F6 | `FeatureGate.FORMATION_MANAGE=false` 陈旧枚举（编队管理 v4.2.11 起已实现） | §4 #14 | 🟡 | 功能可用：入口经 `FormationPicker.java:542/554`、`FormationEntryCard.java:96/115` → `FormationManagerActivity.launch/launchNew`（未经该 gate，用户可见不受影响）；`FeatureGate.java:117` 仅剩自身引用 | P2 | 置 true 或删除该枚举，避免后续误引用；注释同步更新 |
| F8 | **移民已实现（2026-11-06 更新）**：F5 清单中的「移民抢位」已于分支 `add/migrant-1to1-20261106-2058` 完成 1:1 复刻并放开 | §4 #12 | ✅ 已实现 | `FeatureGate.IMMIGRATION` implemented=true（T3 + 服务器模式）；服务端 `vaultserver/migrant`（14 action）、App `migrant` 工作区 + 3 弹窗；单测 56+963 全绿 | - | 真机按 `qa/验收用例-移民功能-20261106.md` 验收 |
| F7 | `BEHEMOTH_AUTOJOIN` 文案/状态陈旧（注释“每日发起未做”，实际服务端已实现） | §4 #20 | 🟡 | 服务端 `ServerTasks.behemoth` 已含每日发起/体力/上车（`:4144+`）；FeatureGate 注释仍写“仅原生自动上车” | P2 | 更新文案与 FeatureGate label；确认 launch_enabled 是否对用户开放（默认 false） |

## G. 热更新自托管（来源：`design/热更新自托管与参数选项化-20261025.md`）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| G1 | 热更自托管：`/v1/vault/hotconfig` + `/app-latest.apk`，参数选项化 | §2-§3 | ✅ | `HotConfigRoutes.java` + `VaultHttpApi:110` 路由；生产 `hotconfig.json` version=2026103005、APK 4.2.18（`{base}/v1/vault/app-latest.apk`） | - | 保持 |
| G2 | 移除「高级参数(JSON)」入口，参数全部选项化 | §3 | ✅ | `FunctionActivity`/`FnParams` 无 JSON 卡片（历史提交已删），子项/策略走 UI | - | 保持 |
| G3 | APK 路由 Range 断点续传 | §6 限制#3 | ❌ | `HotConfigRoutes` 流式整包，无 Range/断点；12MB 可接受 | P3 | 弱网体验优化时再加 |
| G4 | 老设备残留 `hot_url` 优先老失效地址 | §6 限制#2 | 🟡 | `HotConfig` 读 prefs `hot_url` 优先（兼容设计）；无自动清理/迁移 | P2 | 版本升级时一次性清理指向开发机的旧 URL |
| G5 | 热更 version 与 APK versionCode 双轨（运维易错） | §6 限制#1 | 🟡 | 发布需 `HOTUPDATE_VERSION` 单调；无自动校验脚本 | P3 | 发布脚本加校验（hotconfig.version 递增、apk.versionCode 对应） |

## H. 鸿蒙克隆（来源：`qa/analysis-20261029-harmonyos-clone-missing-apk.md` + commits `5be62f6/f131989`）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| H1 | 误导文案修复 + 本地安卓 APK 导入（下载目录自动识别/SAF 选包） | §1-§4 | ✅ | `gameupdate/LocalApkScanner.java`（MediaStore 下载目录→cache）、`GameCloneGuide/GameCloneFallback`；commit 已含 v4.2.18 发版 | - | 真机回归安卓机 |
| H2 | 安卓容器运行/克隆鸿蒙 HAP | §5 平台边界 | ❌ | 系统 PackageManager 不可见 HAP；产品边界，只能引导装安卓版 | 不修 | 文案/客服口径保持 |
| H3 | 鸿蒙 7（卓易通等兼容层）VA 引擎能否完整跑游戏 | §5 后续 | 🟡 | 未回填真机结论（报告 §6 runbook 待执行/回填机型与日志） | **P1** | 找 1 台 HarmonyOS 7 真机跑 runbook 并回填 |
| H4 | 大 APK（≈888MB）导入临时空间提示/清理 | §5 后续 | ✅ | `LocalApkScanner.java:83-120` 复制进 cache、安装成功由收尾逻辑删除；文档已提示存储紧张 | - | 低存储设备冒烟 |
| H5 | 鸿蒙 runbook 真机结果回填（步骤 1-9、异常分支原文） | §6 | 🟡 | 报告表格仍为“待回填” | P2 | 验证后更新报告（含 `GAMEUPD` 日志） |

## X. 跨领域（Phase2 相关遗留，影响上述模块）

| ID | 事项 | 来源 | 状态 | 证据 | 优先级 | 建议 |
|---|---|---|---|---|---|---|
| X1 | 全任务/节奏/采集/编队/守护按角色 uid 隔离 + 每角色配置 | Phase2 发版说明 | ✅ | 生产：双角色各自 `srv:cad:*:uid:fn`、`srv:formation:catalog:*:uid`、`srv:guardian:*:uid:ledger`、`roleConfigByUid`（5 角色）；两角色各自整轮日志 | - | 保持；旧键仅 active 镜像 |
| X2 | 状态键体积/保留策略（st:/log: 无 retention） | Phase2 分析 §风险 R2 | ❌ | 生产 state 2008 键/2.4MB，`st:` 1146、`log:` 450，服务端无 st/log 清理（仅 digest 30 天） | **P1** | 加 retention（st 7 天/log 按角色 cap）+ 体积监控 |
| X3 | 守护/开盾/辅助按角色双写旧键（App 展示不变） | Phase2B | ✅ | 生产 `srv:guardian:48460403:888:{uid}:ledger` + 旧键并存；`srv:asset:*:uid` | - | 观察 1~2 周后关闭旧键写（Lead 决策） |

---

## Top 10 未完成 / 待办（按优先级）

1. **A3 守护服务端卡秒发射缺失**（无 waitUntil/重估；先至只能靠加速追赶）——P1，直接影响 G1 验收。
2. **A4 服务端战报判定/防守码表缺失**——失败/未知无法判撤与告警，P1。
3. **A14 守护实盘实战验证缺口**（生产 24h 仅 `threat_landed`，无 launch/speedup；目标/盟友未配置）——P1，需 3 场真机对拍。
4. **A6/A7/A8 多目标并发 1→2、服务端 Hold/PreGarrison**——P2，影响“真正常驻戒备”。
5. **X2 st/log 状态无 retention**（2.4MB 持续增长，角色化后放大）——P1 运维隐患。
6. **D3 观察点 App 配置页缺失**（只能改服务器 watchpoint.json）——P1 可用性。
7. **D4/D6 观察点燃烧/城墙/护盾判定、加速/召回二次告警、战报关联**——P2 深度能力。
8. **E5 外部 watchdog 未实现**（进程挂/token 过期/日志停滞无主动告警）——P1 稳定性。
9. **F6/F7 门控/文案陈旧**：`FORMATION_MANAGE=false` 为未引用陈旧枚举（入口不走 gate，功能可见）；`BEHEMOTH_AUTOJOIN` 文案仍写“每日发起未做”（服务端已实现）——P2 清理，避免误读。
10. **H3/H5 鸿蒙卓易通兼容层真机验证与 runbook 回填**——P1 质量闭环。

## 证据与复核（只读）

- 代码：`git show master:<path>`（上表路径均为 master `c2e8987`）；关键：`guardian/ServerGuardianEngine.java`、`ServerGuardianLauncher.java`、`formation/FormationResolver.java`、`formation/FormationCatalog.java`、`FeatureGate.java`、`VaultHttpApi.java`、`watchpoint/WatchPointEngine.java`、`HotConfigRoutes.java`。
- 测试：`vault-server/src/test/java/com/carlos/vaultserver/{guardian,formation,task}/*`、`app/src/test/java/com/carlos/vault/**`（全量绿为交付前状态；本次只读未重跑）。
- 生产：`ssh vault-vps`（只读）——
  - `state.json`：`srv:guardian:*:{uid}:ledger`、`srv:formation:catalog:*:{uid}`（version=2/heroes=40/gameFormations=6）、`srv:cad:*:{uid}:fn`、`srv:asset:*:{uid}`；
  - `vault.json`：`roleConfigByUid` 5 角色、`roleParamsByUid` 2 角色；
  - `data/watchpoint.json`：4 个监视目标 + wecom；`vault-20261030.log` 观察点 2091 行/日；
  - `data/hotconfig.json`：version 2026103005 / APK 4.2.18。
- 边界：本任务未改任何源码/配置、未 commit、未重启/部署生产、未对游戏账号发协议；唯一写入 `qa/backlog-audit-runtime.md`。

## 需 Lead/用户决策

1. 守护卡秒/战报/多目标是否进 Phase 3（对应 A3/A4/A6/A7/A8，约 3-5 人日）还是先做“加速追赶 + 实盘对拍”。
2. 观察点 App 配置页与多账号/多区服是否立项（D3/D5）。
3. 守护是否允许兄弟角色接管（E6 产品口径）。
4. 状态 retention 天数与旧键退场窗口（X2/X3）。
5. 鸿蒙真机资源（H3/H5）。
