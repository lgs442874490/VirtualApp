# 联盟互助自动响应失效（11 个可帮请求未被响应）根因分析与补丁方案

- 任务：Agent Teams `task-1`（只读分析；本文件是唯一产出）
- 分析人：help-protocol
- 时间：2026-10-28 23:20（Asia/Shanghai）；生产服务器时钟 = UTC，北京 = UTC+8（见 §1）
- 基线：当前分支 `modify/ui-unification-20261026-0305`（不含修复）；修复版在 master `92ae8de`（下文行号均指 `git show 92ae8de:...` 版本，已落 `/tmp/ServerHelpResponder.master.java`）
- 边界：**未修改任何源码、未 commit / merge、未重启或重部署生产、未对生产账号发任何游戏协议请求**。下文所有生产数据均来自 `ssh vault-vps` 只读命令 + 生产已有的 monitor/state/log 文件。

---

## 0. 一句话结论

1. **1536 不是“今日互助次数已用完”**，而是“**服务端此刻没有可响应的互助请求**”（网页版参考实现给它起的常量名就是 `NO_AVAILABLE_HELP_CODE`），属**瞬时可自愈**的“无事可做”信号。
2. 92ae8de 把 1536 误判为“每日额度用完”并**持久化 settled 一整天**，导致 **2026-10-28 04:23（北京）之后当天彻底停摆 18.5 小时**（到 22:53 为止 0 次互助请求）。这就是用户 22:54 手动进游戏看到 11 个可帮请求、自动化“没有帮”的**直接原因**，且该 bug **每天都会复发**（当天首个 1536 即封停到次日）。
3. 此外还有两个会长期损害互助效果的结构性缺陷，需一并修复：**tick 每轮用缓存的登录 init 快照重放（clear 重建），把推送增量和“已帮”标记全部抹掉**（→ 反复发请求、反复 1536、日志假成功）；**HelpReq 以 `owner:qid` 为键，而真实单条唯一键是 `extra.help_id`**（同一角色同一 qid 可并发多条 → 覆盖/误删/漏帮）。

---

## 1. 时钟与部署校准（先纠一个关键偏差）

### 1.1 生产日志时间戳是 UTC，不是北京时间

Lead 给的时序（“19:43–20:09 每 30s 一条已响应 2 位 / 20:22 已响应 1 位 / 20:23 首次 1536”）来自 `/opt/vault-server/data/logs/vault-20261028.log` 的行首时间，这些是 **UTC**：

- 证据 A：`state.json` 里 `mod:48460403:888:alliance_help.help_all_ts = 1793199223093` = **北京 2026-10-28 22:53:43** = UTC 14:53:43；同一时刻日志行 `14:53:43 ... notify_produced_resource_update`（`vault-20261028.log` 第 48275–48276 行）对得上。
- 证据 B：日志文件名按**北京日**滚动：`vault-20261028.log` 首行 `16:00:00 ... 服务器整轮 · 10-28 00:00`（即 UTC 10-27 16:00 = 北京 10-28 00:00）；`date -u`/`date` 在服务器上同为 UTC。
- 证据 C：monitor 的 epoch 与日志行一一对应：monitor 最后一次成功 `ts=1793132563019` = 北京 04:22:43，日志同一事件写作 `20:22:43`（UTC 10-27 20:22:43 = 北京 10-28 04:22:43）。

| Lead 引用的日志时间 | 实际 UTC | 实际北京时间（Asia/Shanghai） | 事件 |
|---|---|---|---|
| 19:43–20:09（每 ~30s “已响应 2 位”） | 10-27 19:43–20:09 | **10-28 03:43–04:09** | 旧版响应器 365 次请求，**其中仅 18 次真实成功**（见 §2.3），日志 95% 是假成功 |
| 20:22 “已响应 1 位” | 10-27 20:22:43 | **10-28 04:22:43** | 当天最后一次真实成功（`code:0,count:1`） |
| 20:23 首次“次数已用完（1536）” | 10-27 20:23:13 | **10-28 04:23:13** | 新代码（functional-bugs2）首次 1536 → 写 settled=`2026-10-28`，当天停摆起点 |
| 22:54 用户手动顶号 | 10-28 14:53:50 | **10-28 22:53:50** | `notify_logout` → “让位指令 … 1800000ms 后回连”（human_first），用户看到 11 个请求 |

> 结论：事故不是发生在“10-28 傍晚 19:43”，而是**凌晨 04:23 就被封停**，然后白天三次重部署（含 19:00 的当前 jar）因 settled 已落盘而**继续封停**，直到用户 22:53 手动上线检查。

### 1.2 部署时间线（`/opt/vault-server/backup` + 进程启动时间，均为 UTC）

| UTC 时间 | 北京 | 动作 | 与本 bug 关系 |
|---|---|---|---|
| 10-27 20:09 | 10-28 04:09 | 部署 `...bak-functional-bugs-20261027-2011` | 第一版 92ae8de 代码；旧版 365 条假成功日志止于 UTC 20:09:22 |
| 10-27 20:22 | 10-28 04:22 | 部署 `...bak-functional-bugs2-20261027-202328` | 04:22:43 最后一次成功，04:23:13 首个 1536 → settled |
| 10-28 06:15 | 10-28 14:15 | 部署 `...bak-functional-bugs3-20261028-061552` | 仍带 settled 逻辑，state 已落盘 → 继续封停 |
| 10-28 10:34 | 10-28 18:34 | 部署 `...bak-guardian415-20261028-103534` | 同上 |
| 10-28 11:00:53 | 10-28 19:00:53 | 当前 jar（Lead 说的“19:00 部署”），进程 3725403 | 已核验：jar 内 `ServerHelpResponder.class` 含 `HELP_CODE_DAILY_LIMIT`/`alliance_help_settled` 字符串 → 仍带“1536=当日结算”错误逻辑 |

> 关键纠正：**不是“19:00 那个 jar 引入了 1536 settled”**；引入者是 04:22 的 functional-bugs2（`92ae8de` 的开发中间版本），19:00 的 jar 只是**继续沿用**并保留了落盘的 settled 状态。

---

## 2. A. 1536 的真实语义：实证结论

### 2.1 权威参考实现（网页版源码，本机 `../wjdr-review`，只读引用）

| 文件:行 | 内容 | 含义 |
|---|---|---|
| `src-tool/com/wjdr/tool/modules/daily/service/DailyAllianceDonationService.java:51` | `private static final int NO_AVAILABLE_HELP_CODE = 1536;` | 反向工程方的命名：**没有可用的互助请求** |
| 同文件 `:513-518` `handleHelpError` | `if (exception.code()==1536) { state.markHelpAllHandled(ownUid, Map.of(), 0L); runtime.helpWaiting(); return; }` | 1536 → 只把当前本地可帮条目标记“已处理”并等待下一轮，**没有 settled、没有跨天、没有每日上限** |
| 同文件 `:851-858` `helpWaiting` | 文案 `"当前没有盟友需要互助"`，`helpBlockedUntilMillis = 0`（清退避） | 等下一次推送/轮询再判定 |
| `src-tool/.../daily/service/DailyAllianceDonationState.java:225-232` `markHelpAllHandled` | 仅 `availableFor` 的条目 `addHelper(ownUid)`，并用响应里的 `day_glory`/`count` 更新进度 | 本地乐观标记，等价于“这轮没有了” |
| `src-tool/.../attack/service/AttackAutomationService.java:73,437-441` | `NO_AVAILABLE_HELP_CODE = 1536`；catch 到 1536 仅 `markAllianceHelpHandled(ownUid, Map.of(), 0)` | 另一条业务链路（出征前补治疗互助）对 1536 的处理完全一致 |
| `src-tool/.../daily/model/DailyAllianceDonationStateHelpRequest.java:15-24` | `key()=ownerUid:queueId`；`availableFor` 与本仓库实现逐字一致 | 我们的判定本身是 1:1 移植，问题不在判定 |

> 也就是说：网页版作者（已经拿到真实游戏行为验证过的一方）**从未把 1536 当作每日上限**，只当作“当前无可用请求”。

### 2.2 抓包实证（`tools/bundle_re/audit_1014/proto_log_dev_old.txt`，离线、7147 行、4 个角色会话）

统计：**590 次 `req_alliance_help_all` 响应：9 次 `code:0`，581 次 `code:1536`**。

| 证据 | 行号 | 内容 |
|---|---|---|
| 1536 与成功交替出现（同账号同会话，间隔 2.5 分钟内又成功） | 124–127 → 612 | 23:34:22/23/24 连续 1536；23:36:49 请求成功（`{"code":0,"count":1,"rewards":[]}`） |
| 1536 与成功最密只隔 8–9 秒 | 02:11:10、02:11:18（生产） | 见 §2.3；抓包里 23:36:49.508 与 23:36:51.581 两次成功相隔 2.07s |
| **成功只发生在“刚收到 type=1/2（新增/进度）推送”之后** | 612（type=1 后 8ms 成功）、632、706、824、880、6222、6435 | 9/9 次成功：请求前最后一个事件是 `notify_alliance_help_update type=1/2`，且本地“未完成集合”非空（1–7 条） |
| **1536 绝大多数发生在“刚收到 type=3（该请求已完成）”之后** | 616、625、641、649…（共 576 次）；例：6439（type=3 后 34ms → 1536） | 576/581 次 1536：请求前最后事件是 `type=3`；其中 84 次本地未完成集合已空 |
| 请求在 10–30ms 内就被别人帮完（极快的互助池） | 96–97（type=1 23:34:17.134 → type=3 23:34:17.164，同名 help_id 57179860） | 说明 30s 轮询时“请求早已完成”，服务端自然回“没有可响应的请求” |
| 同一 `owner:qid` 可并发多条（**key 冲突的铁证**） | 125/140/144/147/150/154 | `uid=35356563, qid=60001` 在 1 秒内出现 help_id `69019774 / 90972315 / 98677154 / 32992707 / 88482271 / 35541277` 共 6 条不同请求 |
| init 快照里 helpers 可达 max（满员） | 3916（51/51）、4112（37/37×2）、5498（51/51） | `availableFor` 的 `max > helpers.size()` 判定正确；**本次数据只见到 `helpers==max`，未见到严格 `helpers>max`**（长行被日志截断的部分无法判定），故不应以“helpers 可能超过 max”为主要论据 |
| “有可帮列表却连续 1536” | 5304（:916 登录 init，可见 ≥5 条可帮：helpers 12/51、22/51、22/51、7/51…）→ 5635/5678/5757/5943（30s 间隔 4 连 1536） | 30s 轮询与真实可帮窗口错位的直接样本（与 §2.3 生产现象同构） |
| 成功奖励语义 | 6222、6435 | `{"code":0,"count":1,"rewards":[{"id":110,"count":20}],"day_glory":1580/1600}` → 每次成功 +20 联盟币、`day_glory` +20 |

### 2.3 生产监控实证（决定性证据，`/opt/vault-server/data/monitor/monitor-20261028.jsonl`）

该目录是生产“任务级协议监控”，逐条记录 `io.req`/`io.rsp`（含 `seq`/`ts`/`durMs`），本次只读提取 2734 行 `alliance_help` 相关记录（367 对请求/响应）。

| 北京时间 | monitor seq / ts | 事件 |
|---|---|---|
| 10-28 01:50:46 | 335610/335611 | 当天第一次请求：`code:0,count:2,rewards:[{id:110,count:40}],day_glory:260` |
| 10-28 01:50:46–04:08:51 | 共 365 对 | **365 次请求：18 次 `code:0`，347 次 1536**（30s 轮询为主 + 推送触发的 1–9s 补充） |
| 10-28 04:22:43 | 353373/353374 | 当天最后一次成功：`code:0,count:1,day_glory:620`（`day_glory` 260→620，**每次成功 +20**，无封顶迹象） |
| 10-28 04:23:13 | 353399/353400 | 新代码部署后的第一个 1536 → 立即写 `srv:alliance_help_settled=2026-10-28` |
| 10-28 04:23:13–22:52 | — | **`req_alliance_help_all` 出现 0 次**（18.5 小时彻底停摆；监督到的 1000 条 `task.start/end` 全是“常驻响应器已接管…今日联盟互助次数已用完…跳过”） |
| 10-28 22:53:43 | `state.json` `help_all_ts=1793199223093` | 停摆期间每轮只重写状态文案（`help_all="今日联盟互助次数已用完（码1536），明天自动继续"`） |
| 10-28 22:53:50 | 日志 `14:53:50 ... notify_logout` + `让位指令 48460403\|888\|35371154，1800000ms 后回连` | 用户手动登录顶号 → human_first 让位 30 分钟（见 §3 B5） |

对照“旧版假成功”：日志中 `已响应 2 位` 共 **365 条**，时间跨度 UTC 17:50:46–20:09:22（北京 01:50:46–04:09:22），而同一窗口 monitor 真实 `code:0` **仅 18 次**：

```
365 次旧版请求 = 18 次真实成功 + 347 次 1536        # 旧代码完全不看响应码，一律写“已响应 N 位”
```

### 2.4 反证“每日上限”的 5 条独立证据

1. 参考实现（§2.1）根本没有 settled/每日计数；常量名是 `NO_AVAILABLE_HELP_CODE`。
2. 抓包中 1536 与成功**交替**出现：最早一次 1536（23:34:22）之后 2.5 分钟（23:36:49）就成功；当天共 9 次成功，全部夹在 581 次 1536 之间。
3. 生产数据：04:22 成功 `day_glory` 已到 620（约 19 次成功）后，04:23 才第一次 1536；而同一天的 1536 也早于这 19 次成功出现过 300+ 次 → 与“次数用完”的单调性矛盾。
4. 同批抓包里其他角色的 init：`day_glory` 达 1700/2000（若每次成功 +20 ≈ 85–100 次），无任何“低次数上限”的迹象（此为推断性佐证）。
5. **代码路径反证**：`ServerHelpResponder.maybeHelp`（master:347-350）只有“本地可帮数 > 0”才发请求。因此每一次 1536 都是**客户端认为有可帮、服务端认为没有**——这是视图/时序差异（请求已完成/已满/已过期/被别人帮完），不是额度耗尽。

### 2.5 相关错误码对照

| 码 | 本仓库/参考实现中的语义 | 证据 |
|---|---|---|
| 0 | 成功；`count` = 本次帮助条数；`rewards[id=110]` = 联盟币；`day_glory` = 当日荣耀（每次 +20） | 抓包 612/6222；monitor 335611 |
| **1536** | **当前没有可响应的互助请求（瞬时）**；处理=标记本地已处理 + 等新推送 | 参考实现 `DailyAllianceDonationService:51,513-518`；抓包 §2.2 |
| 75 | 队列不可求助（`QUEUE_CANNOT_ASK_HELP_CODE`） | `AttackAutomationService.java:72` |
| 2 | 参数非法 | 参考实现 `PARAM_ILLEGAL_CODE` |
| 1510/1511 | 联盟成员身份变化 / 未找到联盟 | 参考实现 `:52-53,519-522` |
| 16873 | 未加入联盟（本项目任务层分类） | `ServerShopLeagueTasks` 旧实现；无独立参考源，保守保留 |
| 1528 | **联盟捐献免费次数用尽**（与互助无关，别混用） | 参考实现 `:54,581` |

> **结论（可据此落地）**：1536 是“**本轮没有可帮目标**”的瞬时信号。正确响应是：标记本地可帮集为已处理 → 写可读状态 → 等下一次推送/轮询再试；**不得持久化、不得当日封停、不得在任务层据此跳过一天**。

---

## 3. B. “为什么 11 个可帮请求没被帮”——根因排序

### B1（**确定性主因**，100% 解释“完全没帮”）：1536 被当成每日上限并落盘 settled

- 代码：`ServerHelpResponder`（master）
  - `:53-54` `HELP_CODE_DAILY_LIMIT = 1536`，注释“今日互助次数已用完”；
  - `:337-340` tick 里 `if (helpSettledToday(...)) { saveState(... "明天自动继续"); return; }` —— **在任何请求发出之前**直接返回；
  - `:365-370` 收到 1536 → `storeSettledDay(now)`（写 `srv:alliance_help_settled:<fpid>:<sid>="2026-10-28"`）；
  - 任务层兜底同样被封：`ServerShopLeagueTasks.allianceHelp` 在 92ae8de 版本新增 `helpSettled(...)` 提前返回 + 1536 时 `markHelpSettled`。
- 生产证据：§2.3 —— 04:23:13 落 settled 后 **18.5 小时 0 次请求**；`state.json` 现存 `srv:alliance_help_settled:48460403:888 = 2026-10-28`；`mod:...:alliance_help.help_all` 与 `log:...:alliance_help` 全是“今日次数已用完（码1536），明天自动继续”。
- 用户现象对齐：22:53:50 让位前，响应器每轮都在 settled 分支里空转；22:54 用户看到的 11 个请求**一条都没被尝试过**。
- 复发预测：次日 00:00（北京）settled 失效，但当天**第一个 1536 又会立即再次 settled** → 每天都会在若干次成功后停摆。

### B2（**高**，第二主因；修好 B1 后仍会造成“帮得很少/日志骗人”）：tick 用 stale `init` 快照重放，抹掉推送增量与“已帮”标记

- 代码（master）：
  - `:137-144` tick 每轮（dirty 或 ≥30s）固定执行 `applyPush(缓存内容)`，优先 `notify_alliance_help_update_all`，否则 `notify_alliance_help_init`；
  - `:173-178` `applyPush` 遇到含 `help_list` 的内容时 `helpRequests.clear()` 后整表重建；
  - `:359-360` 成功后就地 `helpers.add(ownUid)` 做乐观标记 —— 下一轮重放时被 `clear()` 清掉。
- 后果：
  1. **登录后新到的 `type=1` 请求被每轮重放抹掉**，`maybeHelp` 永远只看登录那一刻的 init 快照 → 之后新增的可帮请求（例如 04:23 之后累积到 11 个的那批）**永远进不了可帮集**；
  2. 每轮重新发现同一批旧条目可帮 → 反复发 `req_alliance_help_all`（30s 一次 + 每次推送）→ 反复 1536；
  3. 旧版代码逻辑不看响应码 → 每轮都写“已响应 N 位” → **95% 假成功**（§2.3 的 365 vs 18 就是这套机制的直接产物）。
- 抓包/生产证据：生产 365 次请求里 347 次 1536（30s 定拍，间隔 30.0–30.5s），且旧版日志 365 条成功文案与之 1:1 对应；抓包 5304→5635/5678/5757/5943 展示“init 有可帮列表但 30s 轮询持续 1536”。
- 备注：`WjdrEngine.notifies` 只是 `proto→最后一次内容` 的缓存（`app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:660,694-696`），init 内容自登录后**不会因为后续 update 而更新**，所以重放天然是旧快照。

### B3（**中**，会造成漏帮/误删/计数偏差）：`HelpReq.key()=owner:qid` 与真实协议唯一键 `extra.help_id` 不一致

- 代码（master）：`:90-92` `key() = owner + ":" + qid`；`:165-169` `type=3` 按 `owner:qid` 删除；`:176` 全量列表按 `owner:qid` 覆盖写入。
- 抓包铁证：`uid=35356563, qid=60001` 在 1 秒内 6 条不同 `help_id`（行 125/140/144/147/150/154）→ 本地 map 只会保留最后一条；`type=3` 删除时可能删掉“另一条新请求”，或删不掉已完成的旧条目 → 可帮数虚高/偏低、漏帮。
- 证据等级：抓包确定存在键冲突；但它对“最终 0 次请求”不是决定性的（决定性的是 B1）。

### B4（**低**，判定本身没错，属加固项）：`availableFor` / 字段形状

- `availableFor`（master:94-97）= 非自己 + 未满员 + 自己没帮过 + 未过期，与参考实现 `DailyAllianceDonationStateHelpRequest:22-24` **逐字一致**；抓包实测 `helpers==max` 的满员条目（行 3916/4112/5498）已被正确排除。**“helpers 可能超过 max”在本次可完整解析的数据中未出现**（超长行被日志 `…` 截断，不能作为主论据）。
- 隐患（非本次主因）：
  - `helpQidOf`（master:237-239）把 `help_id` 作为 `qid` 的回退 —— `help_id` 不是队列 id，会造成键错位；
  - `responseCode`（master:414-418）在“有 content 但无 code”时返回 0，可能把非常规失败形状当成功；
  - `firstOf` 只取“同层 Number/可转数字字段”，`extra` 嵌套字段（`help_id`）完全没解析。

### B5（**低**，不是 bug，但会误导用户）：顶号让位 / 会话写入方限制

- 代码：`SessionKeeper` 仅对 `isActiveRoleWriter()` 的角色挂载并 tick 互助响应器（`SessionKeeper.java:880-922`）；`STATE_TAKEOVER`/`RUN_PAUSED` 不 tick。
- 生产证据：22:53:50（北京）用户手动登录 → `notify_logout` → “让位指令 … 1800000ms 后回连”（human_first 策略）→ 22:53:54 资产库卸载。
- 影响：即使用户把 B1 修掉，**用户手动在线期间（及让位 30 分钟内）自动化本来就不会/不应该帮**；恢复自动需等 23:23:50 让位到期，而当天 settled 又要到次日 00:00 才失效 —— 两者叠加会继续让用户以为“永远不帮”。

### 3.1 根因汇总表

| 排序 | 根因 | 可能性 | 是否解释“11 个未帮” | 关键证据 | 修法 |
|---|---|---|---|---|---|
| 1 | 1536 → 当日 settled | **确定** | ✅ 完全解释（0 次请求） | state settled 键；monitor 04:23 后 0 请求；日志“明天自动继续” | 删除 settled 机制（P0-1） |
| 2 | tick 重放 stale init 抹状态 | **高** | ✅ 解释“新请求永远帮不到 + 反复 1536 + 假成功” | master:137-144/173-178；365 假成功 vs 18 真成功；init 内容只在登录更新 | 只 seed 一次（P0-2） |
| 3 | owner:qid 键冲突 | 中 | 部分（漏/误删/计数失真） | 抓包行 125/140/144/147/150/154 | help_id 唯一键（P1-1） |
| 4 | availableFor/字段 | 低 | 否（与参考一致） | 行 3916/4112/5498 满员判定正确 | 仅清 `help_id→qid` 回退等隐患（P1） |
| 5 | 顶号让位/写入方 | 低（设计如此） | 部分时段（用户在线时） | 日志 22:53:50 notify_logout+让位 | 不改代码，向用户解释 |

---

## 4. C. 补丁方案（基于 master 92ae8de）

> 所有行号均对应 `git show 92ae8de:vault-server/src/main/java/com/carlos/vaultserver/ServerHelpResponder.java`（504 行）与同 commit 的 `ServerShopLeagueTasks.java` / 测试文件。**本次不改源码**，以下为实施蓝图。

### 4.0 设计原则

1. **1536 = “本轮没有可帮目标”**：当作正常空转，标记本地已处理 + 短退避，**绝不落盘跨天**。
2. **推送是增量、init 是登录快照**：增量只合并、不回滚；init 只在“首次该会话”或真正的 init 推送到达时做全量替换。
3. **唯一键以 `extra.help_id` 为准**（缺失时退化为 `owner:qid`），保证并发请求不互相覆盖/误删。
4. **成功只看响应 `count`/`help_num`**，取不到就不冒称成功（避免再次出现“假成功 95%”）。

### 4.1 最小修复（P0，必须，4 项）

#### P0-1 去掉“1536=今日上限 + settled”

`vault-server/src/main/java/com/carlos/vaultserver/ServerHelpResponder.java`

- `:53-54` 改为：
  ```java
  /** req_alliance_help_all 响应码 1536：服务端当前没有可响应的互助请求（参考实现 NO_AVAILABLE_HELP_CODE，瞬时）。 */
  static final int HELP_CODE_NO_AVAILABLE = 1536;
  ```
- `:64-73` 删除 `HELP_RSP_DAILY_LIMIT`，1536 归入 `HELP_RSP_NO_REQUEST`（或新增 `HELP_RSP_NO_AVAILABLE=2`，语义更清楚）。
- `:272-279` `helpResponseKind`：
  ```java
  if (code == HELP_CODE_NO_AVAILABLE
          || code == HELP_CODE_NO_REQUEST || code == HELP_CODE_PARAM_ILLEGAL)
      return HELP_RSP_NO_REQUEST;
  ```
- `:281-294` 删除 `HELP_RSP_DAILY_LIMIT` 的 switch 分支（NO_REQUEST 保持 15s 退避）。
- `:333-394` `maybeHelp`：
  - 删除 `:337-340` 的 settled 提前返回；
  - 删除 `:365-370` 的 1536 分支，改为与“无可帮”同一处理：
  ```java
  } else if (kind == HELP_RSP_NO_REQUEST) {
      // 1536/75/2：本轮没有可响应目标（瞬时）。本地标记为已处理，避免同一批反复打；
      // 不落盘、不跨天。参考实现 handleHelpError: 1536 -> markHelpAllHandled + helpWaiting。
      for (HelpReq r : available) r.helpers.add(ownUid);
      saveState(false, 0, "推送", "当前没有可帮的互助请求（码" + code + "），等待新请求");
  }
  ```
  - `:382-385`：给 NO_REQUEST 也加短退避（当前代码刻意 `kind != HELP_RSP_NO_REQUEST` 跳过退避，会让每次推送（~1s 一条）都打一次）。建议：
  ```java
  long block = helpBlockMillis(kind);           // NO_REQUEST -> 15s
  if (block > 0) blockedUntil.set(System.currentTimeMillis() + block);
  ```
  若担心漏掉“瞬时请求”，可把 NO_REQUEST 的退避下调到 5s（见 §4.5 权衡）。
- `:296-329` 删除 `helpSettledKey/beijingDay/helpSettledToday/markHelpSettled/helpSettled`；
  新增**历史脏键清理**（避免线上旧键继续误导任务层/App 展示）：
  ```java
  /** 清理历史版本误写的“1536 当日结算”键（一次性；失败忽略）。 */
  static void clearLegacySettled(StateStore store, String fpid, long sid) {
      if (store == null) return;
      try { store.remove("srv:alliance_help_settled:" + fpid + ":" + sid); } catch (Throwable ignored) {}
  }
  ```
  在 `attach(...)` 或首个 tick 调用一次。**上线后第一次 tick 即会清掉生产上 `48460403:888` 的旧 settled，无需等次日。**
- `:396-411` 删除 `readSettledDay/storeSettledDay`。

`vault-server/src/main/java/com/carlos/vaultserver/task/ServerShopLeagueTasks.java`（92ae8de 版本 `allianceHelp`，`:2155-2205`）

- 删除 `:2170-2176` 的 `helpSettled(...)` 提前返回；
- `:2185-2191` 1536 分支改为不落盘：
  ```java
  if (code == 1536) {
      return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED,
              "当前没有可互助请求（码1536），等待新请求");
  }
  ```
  删除 `ServerHelpResponder.markHelpSettled(...)` 调用；
- 保留现有“常驻响应器 3 分钟内接管则跳过”的逻辑（它本身是对的，只是文案此前被 settled 文案污染）。

`app/src/main/java/com/carlos/vault/daily/LeagueTasks.java:180-182`（App 本地模式兜底）

- 1536 文案从“今日帮助次数已用完或无请求”改为“当前没有可互助请求（码1536）”。

`app/src/main/java/com/carlos/vault/daily/HelpResponder.java`

- 与服务器同款：`maybeHelp`（`:170-210`）成功判定改用响应 `count`（见 P0-3）；`poll` 的 stale 重放改 seed-once（见 P0-2）；1536 不写成功/不落盘。

#### P0-2 tick 的 stale init 重放改为“只 seed 一次”

当前 `:129-150`：
```java
// 兜底：用最近一次 help 推送内容重算（推送丢失时的保险）
for (String p : new String[]{"notify_alliance_help_update_all", "notify_alliance_help_init"}) {
    Map<String, Object> c = engine.notifyContent(p);
    if (c != null) { applyPush(p, c); break; }
}
```
问题：`notifies` 缓存是登录快照（`WjdrEngine.java:660,694`），每轮 `applyPush` 会 `helpRequests.clear()`（`:173-178`）。

改法（保持“钩子晚挂/漏发”兜底能力，但不再回滚增量）：
```java
private volatile boolean seeded;   // 本会话是否已用缓存快照播种

private void seedFromCacheOnce(WjdrEngine engine) {
    if (seeded) return;
    for (String p : new String[]{"notify_alliance_help_update_all", "notify_alliance_help_init"}) {
        Map<String, Object> c = engine.notifyContent(p);
        if (c != null) {
            applyPush(p, c, /*replace=*/true);
            seeded = true;
            return;
        }
    }
}
```
- `applyPush` 增加 `boolean replace`：`init`/`update_all` 且 `replace=true` 时 `clear()+重建`；否则**只 putIfAbsent 合并**，不 clear、不删已有“已帮”标记。
- 钩子路径（`:117-127`）调 `applyPush(proto, content, /*replace=*/true)` —— 真实 init/update_all 推送仍是全量替换（正确）；`type=1/2/3` 永远是增量。
- `seeded` 在**重新登录（新 init 推送）**时由钩子路径复位/置真；`STATE_TAKEOVER`/恢复后可重新 seed（可在 `attach` 时 `seeded=false`）。
- 测试 seam：把 `tick(WjdrEngine)` 拆为 `tick(WjdrEngine, long now)`（包内可见），1 参版本委托 `System.currentTimeMillis()`；便于单测跨过 30s 轮询、验证“重放不再抹标记”。

#### P0-3 成功判定只认响应里的真实条数

`:357-364` 当前 `long done = Math.max(available.size(), responseHelpNum(rsp));` —— 本地可用数会把 `code:0,count:0` 也写成“已响应 N 位”。

改为（对齐参考实现 `helpIfAvailable`）：
```java
if (kind == HELP_RSP_OK) {
    long done = responseHelpNum(rsp);              // count / help_num，只认服务端
    for (HelpReq r : available) r.helpers.add(ownUid);  // 与参考实现 markHelpAllHandled 对齐
    if (done > 0) {
        if (helpNumToday <= 0) helpNumToday = (int) Math.min(done, Integer.MAX_VALUE);
        saveState(true, done, "推送", "已响应 " + done + " 位盟友互助");
        FileLog.info("联盟互助 fpid={} sid={}：已响应 {} 位", fpid, String.valueOf(sid), String.valueOf(done));
    } else {
        saveState(false, 0, "推送", "当前没有可帮的互助请求（响应 count=0）");
    }
}
```

#### P0-4 清除存量 settled 键

见 P0-1 的 `clearLegacySettled`：上线后首个 tick 清掉 `srv:alliance_help_settled:<fpid>:<sid>`，避免“改了代码但当天仍然不帮”。也可在部署脚本/运维一次性 `state.json` 清理（需用户/Lead 批准，本分析未做）。

### 4.2 加固（P1，建议同批）

| # | 改动 | 说明 |
|---|---|---|
| P1-1 | `HelpReq` 增 `final long helpId`；`key() = helpId>0 ? owner+":"+qid+":"+helpId : owner+":"+qid` | master `:76-98`；`parseHelpRequests` `:195-208` 从条目 `extra.help_id` 解析（`extra` 兼容 Map）；`type=3`（`:165-166`）优先按 help_id 删，删不到再退 `owner:qid`。抓包行 125-156 的并发样本可做单测。 |
| P1-2 | `helpQidOf` 去掉 `"help_id"` 回退（`:237-239`） | `help_id` 不是 qid，回退会造成键错位。 |
| P1-3 | `busy` 抢不到时保住 dirty：`:351` 改 `if (!busy.compareAndSet(false,true)) { dirty.set(true); return; }` | 现在会丢掉一次唤醒，可能要等 30s 兜底。 |
| P1-4 | 发送最小间隔（如 5s）与推送洪峰保护 | 抓包中 `notify_alliance_help_update type=1` 可达 ~1s/条；若 1536 退避又取消，会变成 ~1 req/s。建议 `nextEarliestSendAt = now + 5000` 只在非 OK 后设置（比 15s 更利于抓“瞬时请求”，比 0 退避安全）。 |
| P1-5 | 状态/日志可观测性 | 文案区分“推送巡检（码1536）”；`saveState` 里记录 `help_num/day_glory/last_code`，便于 App 与 monitor 对账；不要再出现“明天自动继续”文案。 |
| P1-6 | App `HelpResponder.poll`（`:81-95`）同样 seed-once；`maybeHelp`（`:170-210`）成功判定改用响应 count | 若用户切回 App 本地自动化模式，行为一致。 |
| P1-7 | `TaskWakeRouter`（`notify_alliance_help_` → `alliance_help`，约 `:213`）保持不动 | 唤醒本身正确；只是任务层 1536 分类文案要改。 |

### 4.3 单测（`vault-server/src/test/java/com/carlos/vaultserver/ServerHelpResponderTest.java`）

现有 15 例中**必须改写/删除**（它们锁死的是错误语义）：
- `:214-232 responseKind_mapping`：1536 期望 `HELP_RSP_DAILY_LIMIT` → 改为 `HELP_RSP_NO_REQUEST`；
- `:235-244 blockMillis_byKind`：删 DAILY_LIMIT 断言；
- `:260-280 settledDay_crossesBeijingMidnight` / `settled_persistsInStateStore`：**删除**；
- `:320-346 tick_1536SettlesAndStopsRequestingAcrossRestart`：**删除**，替换为新语义用例。

新增用例（建议 ≥10 例）：
1. `code1536_isNoAvailable_notDailyLimit`：init 1 条可帮 → 响应 1536 → tick 后：`state` **无** `srv:alliance_help_settled` 键；文案含“没有可帮/1536”，不含“明天自动继续”。
2. `code1536_thenNewHelpPushStillRequests`：1536 后推进时间越过退避（用 `tick(engine, now)`），推一条新 `type=1`（新 help_id）→ 再次发出 `req_alliance_help_all`（证明未被当日封停）。
3. `tickReplayDoesNotWipeHandledMarks`：init 1 条可帮 + 响应 `code0,count1` → tick（首次 seed）→ 隔 31s 再 tick → **总请求数仍为 1**（标记未被重放清掉）。
4. `newRequestArrivesAfterLogin_isHelped`：先 init（无可帮）→ 再推 `type=1` 新请求 → tick → 发请求且 `available` 含该 help_id（证明增量不再被抹）。
5. `helpIdKeyKeepsConcurrentRequests`：同 `uid+qid`、不同 `help_id` 两条并发 → 内部 `helpRequests.size()==2`，两条都可帮（可加包内可见 `requestCountForTest()` 或用 tick 行为断言）。
6. `updateType3_removesOnlyMatchingHelpId`：`type=3` 推 help_id A → A 消失、B 仍可帮。
7. `helpersEqualsMax_notAvailable`（沿用）：满员排除；再补一条 `helpers` 含 ownUid 排除。
8. `responseCountZero_notClaimedAsSuccess`：`code0,count0` → 文案不出现“已响应”且 `help_num` 不被写大。
9. `legacySettledKeyIgnoredAndCleared`：预写 `srv:alliance_help_settled=today` → tick 仍发请求且该键被清。
10. `autoHelpPush_updatesProgressOnly`：`notify_alliance_help_auto_help{help_num,day_glory}` → 只更新进度，不动请求表。
11. 保留：init 内层字段解析、owner/helpers 形状兼容、offline 不动作、unknown code 不写成功。

### 4.4 验证步骤（不在本次执行；给 Lead/实施方）

本地（安全）：
```bash
cd vault-server
./gradlew test --tests 'com.carlos.vaultserver.ServerHelpResponderTest'
./gradlew test            # 期望全绿（92ae8de 基线 1211 例）
# 静态确认错误机制已移除：
git grep -n "markHelpSettled\|alliance_help_settled\|DAILY_LIMIT" -- vault-server/src app/src || echo OK
```
构建产物核对（部署前）：
```bash
unzip -p build/libs/vault-server-*.jar BOOT-INF/classes/com/carlos/vaultserver/ServerHelpResponder.class > /tmp/h.class
strings /tmp/h.class | grep -E "alliance_help_settled|DAILY_LIMIT" || echo "已移除"
```
生产只读观察（**需 Lead 批准部署后**，不主动发协议）：
```bash
# 1) 存量 settled 已清
ssh vault-vps 'grep -o "srv:alliance_help_settled:[^,]*" /opt/vault-server/data/state.json'  # 期望无输出
# 2) 出现 1536 之后仍持续有请求（不是一天只 1 次）
ssh vault-vps 'grep -c "req_alliance_help_all" /opt/vault-server/data/monitor/monitor-$(date +%Y%m%d).jsonl'
# 3) 状态文案不再出现“明天自动继续”
ssh vault-vps 'grep -o "help_all[^,]*" /opt/vault-server/data/state.json | head'
# 4) 日志能看到真实成功条数与 1536 分类
ssh vault-vps 'grep -E "联盟互助.*(已响应|1536)" /opt/vault-server/data/logs/vault-$(date +%Y%m%d).log | tail -20'
```
真机验收（用户侧）：
- 进入联盟互助页记录当前可帮请求数 N；等自动化接管后（避开用户手动在线/让位窗口）观察 `已响应` 条数增加、`day_glory` 每次 +20；
- 次日连续运行一整天：**同一天出现 1536 后仍应继续帮**（不再 04:23 式停摆）；
- 若观察到“有可帮请求但一直 1536”，收集该时段的 monitor 行 + 游戏内截图（说明仍存在服务端视图差异，需要抓已帮数据，见 §6）。

### 4.5 风险 / 权衡 / 回滚

- 去掉 settled 后请求频率上升：靠“1536 标记本地已处理 + 15s 退避（或 5s）+ 30s 轮询”控制，估算 ≤4 req/min（当前 2 req/min），可接受；日志量可控（仅状态行）。
- 不做 help_id 键改造也能修 B1/B2，但并发请求仍会漏/误删 → 建议同批做 P1-1。
- 回滚：本次方案只改 `ServerHelpResponder`/`ServerShopLeagueTasks` 文案与分类 + App 文案；回滚即恢复旧 jar/旧分支，state 里不会再写 settled（旧键已被清理，无残留影响）。
- 工作量估计：P0 ≈ 0.5–1 人日（含单测），P1 ≈ 1 人日（含键改造与 App 端）。

---

## 5. 证据索引（可复核命令）

| 证据 | 位置/命令（全部只读） |
|---|---|
| 修复版源码 | `git show 92ae8de:vault-server/src/main/java/com/carlos/vaultserver/ServerHelpResponder.java`（504 行）；测试 `git show 92ae8de:vault-server/src/test/java/com/carlos/vaultserver/ServerHelpResponderTest.java`（375 行） |
| 网页版参考实现 | `/Users/liangguansheng/ZCodeProject/wjdr-review/src-tool/com/wjdr/tool/modules/daily/service/DailyAllianceDonationService.java:51,513-518,851-858`；`DailyAllianceDonationState.java:225-232,455-490`；`.../attack/service/AttackAutomationService.java:73,437-441` |
| 抓包 | `tools/bundle_re/audit_1014/proto_log_dev_old.txt`：96-97、111-112、125-156、612、616、6222、6435、6439、3916、4112、5498、5304、5635-5943 |
| 生产 monitor | `ssh vault-vps 'grep "alliance_help" /opt/vault-server/data/monitor/monitor-20261028.jsonl'`：seq 335610/335611（首次成功）、353373/353374（最后成功）、353399/353400（致命 1536）；统计 367 响应=19×0+348×1536 |
| 生产 state | `ssh vault-vps 'grep -o "alliance_help_settled:[^\\"]*\\"[^\\"]*\\"" /opt/vault-server/data/state.json'`；`ssh vault-vps 'grep -o "mod:48460403:888:alliance_help[^}]*}" /opt/vault-server/data/state.json'`：`srv:alliance_help_settled:48460403:888="2026-10-28"`；`mod:...:alliance_help={"help_all":"今日联盟互助次数已用完（码1536），明天自动继续","help_all_ts":1793199223093,...}` |
| 生产日志 | `ssh vault-vps 'grep -n "联盟互助" /opt/vault-server/data/logs/vault-20261028.log'`：第 3150 行起 365 条“已响应 2 位”（UTC 17:50:46–20:09:22）；`20:22:43 已响应 1 位`；`20:23:13 今日次数已用完（1536）` |
| 顶号让位 | `ssh vault-vps 'grep -nE "14:5[0-9]:" ...vault-20261028.log'`：第 48279 行 `notify_logout`、第 48280 行 `让位指令 48460403\|888\|35371154，1800000ms 后回连` |
| 部署证据 | `ls -la /opt/vault-server/backup /opt/vault-server/vault-server-all.jar`；`ps -eo pid,lstart,args \| grep vault-server-all.jar`（11:00:53 UTC 启动）；`python3 -c 'import zipfile;...'` 核验 class 内 `alliance_help_settled`/`DAILY_LIMIT` |

---

## 6. 需要 Lead / 用户决策或补充的信息

1. **部署窗口与运维动作**：本方案要求部署新 jar 并允许“首个 tick 清 `srv:alliance_help_settled:48460403:888`”（代码内自动清，无需手工改 state）。请 Lead 决定灰度窗口；本人未重启/重部署生产。
2. **确定是否也修 App 本地模式**：若用户仍可能切到“App 本地自动化”（非服务器模式），则 App `HelpResponder`/`LeagueTasks` 必须同批修；若现在 100% 服务器模式，可作为 P1。
3. **退避策略选择**：1536 后 5s 还是 15s 退避（影响“抢瞬时请求”与请求频率的权衡）。建议 5s；如联盟里有大量自动互助账号（抓包显示请求常在 10–30ms 内被帮完），可再讨论“推送到达立即尝试（0–1s）”。
4. **是否补充 22:54 现场材料**：需要用户提供当时游戏内联盟互助页截图或 help_id 列表（可选，用于真机对照“11 条”是否都被后续修好版本覆盖）。
5. **1528/16873 等码的正式参考**：1528 已确认属“捐献免费次数”，16873 无参考实现，建议保留现有分类但不与互助混写文案。
6. **是否允许把 `../wjdr-review`（网页版参考实现）纳入证据仓库**：本次仅只读引用其行号，未复制代码进本仓库。

## 7. 边界声明（合规）

- 本文件是唯一写入的仓库文件：`qa/analysis-20261028-alliance-help.md`；未修改任何源码/配置，未 `git commit`/`merge`/`push`，未触碰工作区其他未提交改动。
- 生产侧仅执行只读命令（`ls`/`cat`/`grep`/`python3` 读取 state 与日志、`unzip -p` 读 jar 内 class、`ps`/`systemctl status` 查看状态）；**未重启/重部署/改配置**，**未对生产账号发送任何游戏协议**。
- 本地临时文件：`/tmp/ServerHelpResponder.master.java`、`/tmp/ServerHelpResponderTest.master.java`、`/tmp/mon-help-20261028.jsonl`（从生产 monitor grep 出的只读副本），均在仓库外。
