# 联盟科技「24 次 / 25 次满」问题定位报告

- 任务：共享看板 task-2（只读分析；不改源码、不 commit/merge、不动生产、不对生产账号发协议请求）
- 分析时间：2026-10-28 23:08–23:35（北京时间）
- 仓库/分支：`/Users/liangguansheng/ZCodeProject/VAContainer`，工作区分支 `modify/ui-unification-20261026-0305`，HEAD `4e0a9f7`
- 生产只读核对：`ssh -o BatchMode=yes vault-vps`（host `liuliangJto3BJyxA5Pu`），数据 `/opt/vault-server/data/state.json`、`data/logs/vault-20261028.log`、`data/monitor/monitor-20261028.jsonl`、`data/vault.json`
- 重要前提：**功能修复提交 `92ae8de` 不是当前工作区分支的祖先**（`git merge-base --is-ancestor 92ae8de HEAD` 为否）。工作区 `vault-server/.../ServerTasks.java` 是**修复前**版本；生产 jar 已包含修复（见 §7.4）。本报告的「当前逻辑」以 **master / 生产** 为准，行号取自 master 版本（工作区同名文件行号有偏移，且缺持久化逻辑）。

---

## 0. 结论摘要（先看这里）

1. **用户看到的「24 次，25 次满」极大概率是游戏内「联盟 → 科技」页的「免费捐献次数」**：协议 `notify_recover_resource_init/update` 的 `data["205.0"] = {id:205, recover:1, interval:600, max_count:25}`，即每 600s 恢复 1 次、上限 25。24 是「差 1 次满」的显示值，与用户措辞完全吻合。**不是 号库 App 的文案**（App 该功能卡只有「免费科技捐献」开关文字，不显示数字）。
2. **不存在「设计成 25 次满才请求/捐献」的设计**。代码与网页版 1:1 基准都是「只要 `freeCount>0` 就捐，单轮最多 `min(50, freeCount)` 次」。所以「24 次一直不降到 0」**不是设计，是缺陷**。
3. **最可能的直接根因（P0，代码级已证实）**：master 修复版把「免费次数」做成「引擎推送 + 持久化快照」双路，并用 `effectiveFreeCount = min(两路)`、且只在 `engineFree <= persistedFree` 时采纳新快照（`ServerTasks.java:3208-3222, 3772-3777`）。这导致**持久化侧一旦是偏小的旧值（例如 0），服务端真值 24 永远无法把它抬上去**；本地每 600s 只投影出 1 次 → 每 10 分钟只捐 1 次 → 真实次数被"恢复 1 / 捐 1"钉在 24（偶尔 25）。生产数据里 `res:` 快照记录 `205:24`，而 `srv:alliance_science` 里 `recovery.count=0`，两者**直接矛盾**，是本次判断的硬证据。
4. **结构性帮凶（P1）**：同一账号 48460403 在 888 服有 **两个角色**（35371154 隆科多 lv70；88280792 小鹿矿工5号 lv13），但服务端所有任务状态键是 `fpid:sid`，**不含 uid**；「205 免费次数」是**按角色**的。只有「active 角色会话」跑任务，另一个角色的免费次数只涨不花 → 必然堆积到 25。加上 `TaskContext` 没有 uid、`isActiveRoleWriter()` 在身份未核验（roleUid=0）时会用 wantUid 兜底，存在"按 A 角色旧状态判断 B 角色"的机制。（本次已确认结构性风险与生产现象吻合；因为键里没有 uid，从线上数据**无法 100% 区分 24 属于哪个角色**——这本身就是要修的缺陷。）
5. 次要但真实：**22:53:50 生产会话因「账号在其他设备上线」让位（human_first），到 23:23 探测回连前服务器不跑任何任务**；用户在这段时间看游戏，必然看到次数停在 24/25。这是「当下看起来完全不动」的叠加因素，但不能解释"自动化日志说已用尽、游戏里却有 24"的历史矛盾。
6. 用户猜的「是不是设计 25 次满才请求」→ **否**；「是不是功能失效」→ **是（部分失效）**：任务确实在跑、也确实每 10 分钟捐 1 次，但**无法把积压的免费次数一次性花掉**，且次数模型与游戏真实值脱节。

---

## 1. 报障数字判读（A：24/25 最可能指什么）

### 1.1 首选解释：游戏内「联盟科技·免费捐献次数」= 24/25

证据链：

| 证据 | 内容 |
|---|---|
| 抓包 `tools/bundle_re/audit_1014/proto_log_dev_old.txt:32,4031,4223…` | `notify_recover_resource_init.data["205.0"] = {"id":205,"count":N,"next_calc_ts":...,"recover":1,"interval":600,"max_count":25}`；`count` 随时间增长，抓包样本里出现过 `count:25`（:5364, :6992）和 `count:2`（:6816） |
| 1:1 网页版对照（`qa/audit-20261023/alliance-shop-gaps.md:101,124`） | 「免费捐献次数：`notify_recover_resource_init/update` 的 `data[]` 中 `id==205` 项 → `count/max_count/next_calc_ts/recover/interval`，按服务器时钟投影恢复」 |
| 代码 | `ServerTasks.recoveryOf()` 明确取 id==205（master:3561-3575）；`projectedRecoveryCount()` 按其投影（master:3582-3594） |
| 生产 state | `res:48460403:888 = {"…","203":0,"205":24,…}` —— 即最近一次登录 init 里 205 的 `count=24`（`CaptureTasks.saveResources` 写入） |

结论：`recover:1 / interval:600 / max_count:25` 与「每 10 分钟 +1，25 次满」完全一致；**24 是"差 1 次满"的免费捐献次数**。这也解释了用户为什么说「又有 24 次了」——它是会随时间恢复的资源条。

### 1.2 排除的其它候选

- **App 功能卡/今日页文案**：`FnRegistry.java:104`、`FnDetail.java:230-231` 只写「联盟捐献 / 免费科技捐献 / 每日免费科技捐献」，没有数字；全仓 App 侧没有读取/展示 `res:` 次数的 UI（`res:` 只有 `DailyRunner.saveResources` 写入本地 SharedPreferences，且生产 state 的 `res:` 来自服务端 `CaptureTasks`）。
- **任务结果里的「科技捐献✓×N」**：那是本轮的捐献成功次数，正常是 1（见 §2.4）；若它变成 24 才说明"一轮捐了 24 次"，与用户描述不符。
- **某科技的捐献进度（donate 值）**：如 6023 的 `donate=30021400`，是联盟科技捐献累计值，单位是大数，不可能是 "/25" 的分子。
- **联盟科技等级/研究进度**：lv 上限 5（生产 sciences 多为 lv5），不会是 25。

### 1.3 若仍不确定，需要用户提供（截图项）

1. **游戏内**「联盟 → 科技」页（不是 号库 App）截图：重点看**捐献按钮旁边/顶部的「免费捐献次数」**（通常是 `24/25` 或红色角标 `24`，旁边有 `+1` 倒计时，约 10 分钟）。请同时说明：这个 24 是显示在"捐献"按钮附近，还是显示在某条科技的研究进度条上。
2. **是哪个角色**：昵称/等级 —— 「隆科多 lv70」还是「小鹿矿工5号 lv13」？是否就是 号库 App 里当前勾选/主控的角色？
3. **观察时间**（北京时间，精确到分），便于对齐生产日志。
4. 方便的话手动点一次捐献，确认数字是否 `24 → 23`，10 分钟后再看是否回到 `24`（可确认它就是可消耗的免费次数）。
5. 同一时间 号库 App 里该任务的文案（是否显示「科技捐献:免费次数已用尽（下次恢复 HH:MM），恢复后自动捐献」）。

---

## 2. 当前捐献逻辑逐行分析（B）

> 以下行号 = master `vault-server/src/main/java/com/carlos/vaultserver/task/ServerTasks.java`（生产 jar 同源逻辑）。节奏：`TaskCadence.java:93 alliance_donate=30s`；模块入口 `Modules.java:71`。

### 2.1 主流程（master:3183-3415）

```
collectNotifies(1500)                                  // 3188 收推送
st = loadAllianceScience(tc)                           // 3205 srv:alliance_science:<fpid>:<sid>
persistedFree = st.recoveryKnown()? project(st.recovery, now) : -1     // 3208-3209
freshSciences = collectSciences(engine); merge 进 st                   // 3210-3211
freshRecovery = recoveryOf(engine); engineFree = project(fresh, now)   // 3214-3215
freeCount = effectiveFreeCount(engineFree, persistedFree)              // 3217, 3772-3777 = 双路取小(-1 未知)
if (freshRecovery != null && (persistedFree<0 || engineFree<=persistedFree)) st.recovery = fresh  // 3220-3222 ← 关键
legacy no_free_until 兼容 + 落盘 + noFree 冷却                            // 3223-3230
分支：sciences 空 / freeCount<0（不盲捐）/ 冷却中 / freeCount<=0（等恢复）/ else 捐献   // 3232-3244
捐献循环：targets = orderedScienceTargets(st.sciences, st.recommend)    // 3250
          budget = min(50, freeCount)                                  // 3251
          for i in [0,budget): for target in targets: req_alliance_science_donate{id, level=lv+1}
             code==0 → donated++; break 选下一个目标                       // 3264-3271
             可跳过目标码 → continue                                    // 3273-3277
             1528 → no_free_until + normalize(count=0) + 停轮            // 3278-3288
             1545 → 资源不足冷却 60s + 停轮                              // 3289-3296
             1510/1511 → 联盟状态冷却 300s + 停轮                        // 3297-3303
             未知码 → WARN + 冷却 300s + 停轮                            // 3304-3311
          所有目标都跳过 → targetCooldown 300s + “均不可捐”               // 3313-3321
成功合计>0 → 本地 normalize(count=freeCount-donated) + 落盘 + 写 mod      // 3324-3334
```

### 2.2 `projectedRecoveryCount` 投影算法：公式本身与文档基准一致，但「输入快照」有问题

master:3582-3594：

```java
count = max(0, recovery.count); max = max(0, recovery.max_count);
next = recovery.next_calc_ts; recover = max(1, recovery.recover); interval = max(0, recovery.interval);
if (max<=0 || count>=max || next<=0 || interval<=0 || nowSec < next) return count;
return min(max, count + (1 + (nowSec-next)/interval) * recover);
```

- 公式与 `qa/audit-20261023/alliance-shop-gaps.md:134` / 网页版 `rememberDonationRecovery/projectRecovery` 口径一致；现有单测 `RewardClaimFixTest:496-510`、master `AllianceDonationPersistenceTest.projectedRecoveryCount_matchesWeb` 覆盖。
- 问题**不在公式**，而在：①它被作用在**可能是别的角色、或本地归一化后**的 `st.recovery` 上；②`st.recovery` 是「本地扣减后的值」，与服务端真值不是同一语义；③`min()` 合并让偏小的持久化值永远压住偏大的服务端值（见 2.3）。

### 2.3 免费次数双路合并：这是「24 堆积不花」的机制核心

`effectiveFreeCount`（master:3772-3777）：

```java
if (engineFree<0 && persistedFree<0) return -1;   // 未知：不盲捐（ok）
if (engineFree<0) return persistedFree;
if (persistedFree<0) return engineFree;
return Math.min(engineFree, persistedFree);       // ← 都已知取小
```

采纳规则（master:3220-3222）：**只有 `engineFree <= persistedFree` 才把 `st.recovery` 换成引擎新快照**。

由此得到一个**不可自愈的锁死状态**（纯代码推演）：

```
持久化 st.recovery = {count:0, next_calc_ts:T, interval:600}
服务端 init/update  = {count:24, max_count:25, ...}     // 引擎侧 24
每轮：persistedFree = 0~1（看是否过 T），engineFree = 24
     freeCount = min(24, 0~1) = 0~1 → 这一轮最多只发 1 次请求
     采纳条件 24 <= 0~1 为假 → st.recovery 永远保持旧的 count=0
     → 下一 600s 又只投影出 1 次 → 只捐 1 次 → 真实次数被"恢复 1/捐 1"钉在 24
```

即：**只要持久化快照曾经是偏小的旧值（换角色、换会话、旧版本归一化残留、跨日等），服务端后来真实恢复出来的 24 次永远进不了模型，也永远捐不掉**。这就是用户看到的"24 次一直不降"。

设计意图（master:3220-3221 注释）是防「用陈旧 init 快照覆盖本地扣减 → 重复超捐」，但实现用 min + 单向采纳，**防住了超捐，也把"补花积压"彻底堵死**。

### 2.4 正常应然行为 vs 本次生产实测

- **应然**：只要 `freeCount>0`（哪怕 24）就 `budget=min(50,24)=24` 连捐，一轮把免费次数打到 0（网页版 1:1 口径）。
- **生产实测（关键）**：`/opt/vault-server/data/monitor/monitor-20261028.jsonl`（kind=task.end，fn=alliance_donate）：
  - 12:27–14:27：每 ~10 分钟 `✓×1`；
  - **任务空档后的补花是有效的**：`15:09:59 ✓×4`、`16:39:31 ✓×7`、`18:35:35 ✓×11`（间隔分别≈42m/72m/116m，与恢复周期数 4/7/11 吻合——说明旧/首启版本**能把积压花掉**）；
  - **19:00:53 重启部署后**：19:07:56～22:47:21 共 **23 轮全部是 `✓×1`**，再没有一次 >1。同一时间 `res:…"205":24`（登录 init 真值 24）与 `srv:…recovery.count=0` 并存 → 说明**真值 24 没进入模型，模型只按每周期 1 次在挤**。
  - 22:53:50 后会话让位，任务停跑（见 §6），此后次数只会继续恢复到 25。

**结论：免费次数一恢复（>0）"正常情况下"会被立刻捐，但当前生产并没有按 `freeCount=24` 去捐，而是按被污染的 `freeCount=0~1` 在捐。** 存在"长期堆积到 24-25"的现实路径，且线上正在发生。

### 2.5 其它逐项核对（结论：不是本次根因，但值得顺手修）

1. **免费次数未知（`engineFree<0 && persistedFree<0`）→ 不盲捐**：正确，符合"宁漏不花"（master:3234-3235 文案）。
2. **`budget=min(50, freeCount)`**：与网页版 `while freeCount>0 && attempts<50` 等价（master:3251）。**不存在"满 25 才捐"门槛**。
3. **成功码 0 / 各失败码**（master:3263-3311，`isDonationTargetSkipCode` master:3625-3635）：
   - `1528`=免费次数用尽：记录 `no_free_until=next_calc/interval 兜底`、停轮 → 合理；
   - `1545`=资源不足：60s 冷却停轮 → 合理；
   - `1510/1511`=联盟状态异常：300s 冷却 → 合理；
   - `1507`（今日已捐）/`1584`（目标不可捐）/`2,1570,1571,1574,1576,1577,1579,1580,1581`（研究中等）：目标级跳过、换下一个 → 这正是 92ae8de 修的点（生产 `17:51:55 WARN 联盟科技捐献失败 id=6023 level=1 code=1584` 是修复前版本残留，18:34 后无同类 WARN）；
   - 未知码：WARN + 300s 冷却，不静默 → 合理。
4. **`no_free_until` 冷却**：生效（master:3230；同时兼容写旧 `mod:` 键 :3223-3225, :3284）。小瑕疵：过期后不清零（无功能影响）、`noFreeUntilSec` 与 `recovery` 两处状态可能不同步（建议统一）。
5. **sciences 快照来源**：`notify_alliance_science_init.sciences` 是 `Map<"id.0",{id,lv,donate}>`，`Exec.mapsOf` 已兼容（master:3505-3529）；`notify_alliance_science_exp_update.science` 单条累积（同级 lv 更新 donate、更高 lv 覆盖，master:3531-3541）；持久化 round-trip 正常（master:3677-3751）。**生产快照 61 项、57 项 lv5、6023 为 `lv0/donate=30021400`**，与 `notify_recover_resource` 无关，不是本次数字来源。
6. **`orderedScienceTargets` 与 6023（lv=0 但 donate 巨大）**：推荐科技优先，然后 id 升序（master:3597-3610）；6023 会以 `level=lv+1=1` 请求。生产中 6023 时好时坏：master 修复前 1584 会卡整轮（已修）；修复后 1584 跳过。**6023 本身不是"24 不花"的原因**（22:47 那轮就是 `id=6023 level=1` 成功 `code=0`，每轮只发 1 次是因为 budget=1）。
7. **持久化落盘**：解析成功/成功捐献/失败码都会 `saveAllianceScience`；键 `srv:alliance_science:<fpid>:<sid>`（master:3672-3674）——**注意没有 uid，见 §3**。

---

## 3. 多角色同 sid 共用 state 键（C：机制与影响）

### 3.1 事实

- 账号 `48460403`（生产 `vault.json`）：`activeSid=888`，`activeUid=88280792`；同一 888 服有两个角色：
  - `888:35371154` 隆科多（lv70，power 211742942，`desiredOnline=false`，STOPPED）
  - `888:88280792` 小鹿矿工5号（lv13，power 1129487，`desiredOnline=true`，RUNNING）
  - `roleConfig[888].alliance_donate=true`（任务已启用）
- 所有任务状态键按 `fpid:sid`：
  - `srv:alliance_science:48460403:888`（sciences/recommend/recovery/no_free_until/target_cooldown）
  - `res:48460403:888`（205 等 recover 资源快照）
  - `log:/mod:/ledger:48460403:888:alliance_donate`
  - `TaskContext`（`vault-server/.../task/TaskContext.java:12-45`）只有 `fpid/sid`，**没有 uid**
- 只有「active 角色会话」跑任务与采集：`SessionKeeper.tick()` 在 `:877 if (!isActiveRoleWriter()) break;` 之后才跑采集/任务，`:914 CaptureTasks.captureAll(...)`；`isActiveRoleWriter()` 在 `SessionKeeper.java:1051-1060`。
- 「205 免费捐献次数」是**按角色**的资源（每个角色的 recover 计时独立）；「联盟科技 sciences」是**联盟级**（本次两角色同联盟 `cch`，科技表本来就该一样）。

### 3.2 影响机制

1. **非写入方角色的免费次数只涨不花**：只有 active 角色会话执行 `alliance_donate`，另一个角色的 205 每 600s 恢复、无人消费 → 必然涨到 `max_count=25`（24 就是"差一次满"的可见形态）。用户若正看的是**非主控角色**，就会看到 24/25，而自动化日志里全是另一个角色的"已用尽/✓×1"。
2. **同一 `srv:alliance_science` 键被两个角色轮流写**：
   - `recovery`（205，按角色）被 A 角色归一化成 `count=0` 后，B 角色登录带着真值 24 进来，被 §2.3 的 `min + 单向采纳` **压住** → B 角色也被钉死在 24；
   - `res:`（205 快照）同一键，只反映"最后写者"的角色，App/人工看到的数字和真实归属可能不是同一个角色。
3. **`isActiveRoleWriter()` 的身份空窗**（`SessionKeeper.java:1051-1060`）：
   ```java
   long mine = roleUid > 0 ? roleUid : wantUid;
   return mine == 0 || mine == aUid;
   ```
   登录后、`verifyIdentity()` 落地前 `roleUid=0`，此时用 `wantUid` 兜底，**默认角色发现会话（如 19:00:57 先以 35371154 登录再切 88280792）会被误判成 active writer**，在短暂窗口里跑采集/任务，把"默认角色"的 205/科技数据写进共享键。本报告无法从线上证明这次窗口一定发生过采集写，但这是明确的结构性缺陷。
4. **无法归因**：由于键里没有 uid，我们**无法从 `srv:alliance_science`/`res:` 反推 24 属于哪个角色**。这既是本次分析的盲区，也是必须修的原因（只读排查被卡住）。

### 3.3 与用户描述的对应

「游戏里看到 24/25，自动化却按另一角色的旧数据判断」——**机制上成立且高度可疑**：
- 19:00:57 两个角色同一秒登录（monitor session 事件：`login 隆科多 uid=35371154` + `login 小鹿矿工5号 uid=88280792`）；
- `alliance_donate` 成功捐献的 `rank_data.value` 在 18:57:40 有一次**跳变**（18:47:25 为 `29280/rank44` → 18:57:40 为 `23760/rank50`），说明 18:47–18:57 之间**捐献的角色换了**（与 18:47:36 登录 35371154 吻合）；
- 22:53:50「账号在其他设备上线」→ 22:53:54 服务器会话让位关闭，22:56 前后 App 侧 `activeUid` 已为 `88280792`（用户 22:57 在手机切到该角色）。
- 也就是说：过去 3.5 小时在捐的很可能是 **35371154（隆科多）**，而用户 22:57 起在游戏里看到的是 **88280792（小鹿矿工5号）**（或反之）。**需要用户确认看到 24 的是哪个角色**（见 §8）。

---

## 4. 根因排序（D）

| 排序 | 根因 | 证据 | 置信度 |
|---|---|---|---|
| **R1（P0，直接根因）** | 免费次数双路合并 `min()` + 只在 `engineFree<=persistedFree` 时采纳服务端快照（master:3208-3222, 3772-3777）：偏小的持久化值**永久锁死**真实积压次数，每周期只捐 1 次，次数被钉在 24/25 | ① `res:48460403:888` 记 `205:24` ↔ `srv:alliance_science:48460403:888.recovery={count:0,next_calc_ts:22:58}` 同时存在（22:51:45 写入）；② 19:00 部署后 23 轮全是 `✓×1`，而此前任务空档后能 `✓×4/×7/×11`；③ 22:47 单发 `id=6023 level=1` 就返回 0，之后不再发——是本模型自限（budget=1），不是服务端拒绝 | 高（代码可证 + 线上数据吻合） |
| **R2（P1，结构性帮凶）** | 同服双角色共用 `fpid:sid` 状态键、205 按角色：非写入方角色只涨不花；换角色时旧 `recovery` 污染新角色；`isActiveRoleWriter` 身份空窗可用默认角色跑任务/采集 | 双角色同 888；19:00:57 双登录；18:57 捐献角色切换（rank_value 跳变）；`TaskContext` 无 uid；`SessionKeeper:1051-1060` 兜底逻辑 | 中高（机制确定；具体到本次 24 属谁无法从线上区分） |
| **R3（P1，运维叠加）** | 22:53:50「账号在其他设备上线」→ 服务器按 human_first 让位到约 23:23 才探测回连；期间 0 次捐献，次数只会继续恢复到 25 | monitor session 事件 22:53:50–22:53:54；`yieldProbeAt=1793201031235`（=23:23:51） | 高（解释"当下不动"，不解释历史矛盾） |
| **R4（排除）** | "设计成满 25 才捐" | 代码 `budget=min(50,freeCount)`、`while freeCount>0`（master:3251）；App 无阈值参数（`FnDetail.java:230-231`）；网页版 1:1 同口径 | 高（无此设计） |

> 一句话：**R1 是"为什么有 24 也不花"的机械原因，R2 是"24 为什么会长期待在非自动化角色身上 / 旧快照为什么会是 0"的来源，R3 是"现在为什么一动不动"的叠加因素。三者都要修/告知。**

---

## 5. 补丁建议（D：只给方案，不改源码）

> 基线：**在 master（含 92ae8de）或 `modify/functional-bugs-20261028-0152` 上改**，不要基于当前 ui-unification 分支（该分支无持久化逻辑，且不属于本问题）。按 AGENTS.md：先开分支 → 人工审核 → commit/merge。

### 5.1 P0：免费次数改为「服务端原始快照 + 本地已花费」两字段（替换 min 逻辑）

文件：`vault-server/src/main/java/com/carlos/vaultserver/task/ServerTasks.java`

1. `AllianceScienceState`（master:3651-3669）增加：
   ```java
   String recoverySig;     // 最近一次服务端快照指纹: count|max_count|next_calc_ts|recover|interval
   long   spentSinceSig;   // 自该快照以来本地成功捐献次数
   ```
2. 编码/解码（master:3677-3732）同步持久化 `recovery_sig` / `spent_since_sig`。
3. `allianceDonate` 里替换 3208-3222 与 3230 附近的取值逻辑：
   ```java
   Map<String,Object> fresh = recoveryOf(tc.engine);
   if (fresh != null) {
       String sig = recoverySigOf(fresh);                 // 纯函数
       if (!sig.equals(st.recoverySig)) {                 // 服务端给了新快照 → 以服务端为准
           st.recovery = new LinkedHashMap<>(fresh);
           st.recoverySig = sig;
           st.spentSinceSig = 0L;
       }
   }
   long projected = st.recoveryKnown() ? projectedRecoveryCount(st.recovery, nowSec) : -1L;
   long freeCount = projected < 0L ? -1L : Math.max(0L, projected - st.spentSinceSig);
   ```
   - 删除 `effectiveFreeCount()` 的双路取小（或保留函数但只做“未知判断”）；
   - 成功捐献后：`st.spentSinceSig += donated;`（同时保留输出态 `st.recovery` 原样，不再写回“投影后 count”）；
   - 收到 `1528` 时：`st.spentSinceSig += Math.max(0, projected);` 并 `st.noFreeUntilSec=...`；
   - 这样：**新快照（含 24 的 init、含换角色后的 init、含空档后 update）一定被采纳**；同时本地已花次数不会被陈旧快照重复计算（保留 master 单测 `localDeduction_notReAddedByProjection` 的意图）。
4. 状态文案/日志加可观测性：`FileLog.info("联盟科技免费次数 engine=%d persisted=%d spent=%d source=%s")`，状态行在 `freeCount<=0` 时带 `(引擎=%d,缓存=%d)`，便于下次一眼定位。

### 5.2 P1：per-role 隔离（205 / recommend 必须按 uid）

1. `TaskContext`（`:12-45`）增加 `final long roleUid;`（0=未知）；构造点 `RoundRunner.java:36`、`ModularRoundRunner.java:59` 从 LiveSession/engine 传入**已核验的 roleUid**。
2. 键改为：`srv:alliance_science:<fpid>:<sid>:<uid>`；`sciences` 是联盟级可共享，但 `recovery`、`recommend` 必须按角色。过渡期：先读新键，miss 再读旧键并迁移；双写一个版本。
3. `isActiveRoleWriter()`（`SessionKeeper.java:1051-1060`）：
   ```java
   if (roleUid <= 0) return false;              // 身份未核验前不写任何 fpid:sid 状态、不跑任务
   return roleUid == parseUid(acc.activeUid);
   ```
   去掉 `mine == 0 ||` 与 `wantUid` 兜底（或至少 captureAll/任务路径禁止 roleUid=0 的会话）。
4. `res:` 等 per-character 采集键同样建议按 uid 增加新键（App 资产中心已有 `srv:asset:<fpid>:<sid>:<uid>` 的先例，见 `SessionKeeper.java:817-826`），旧键仅由 active writer 写以兼容。

### 5.3 P1：换角色/重启后的"补花"路径显式化

- 在 `allianceDonate` 的 `freeCount>0` 分支，若 `budget>1`，**必须真的连续发到 0/失败为止**（现有 for 循环已支持；不要让 `st.recovery` 的归一化把 budget 又压回 1）；
- 增加一条兜底：当 `engineFree - persistedFree >= 2`（差异明显）时，记 WARN + 采用 engine（即使 sig 相同），并写一条 `log:` 便于复盘。

### 5.4 单测（建议加在 `vault-server/src/test/java/com/carlos/vaultserver/task/`）

参考已有：master `AllianceDonationPersistenceTest`（本分支工作区没有该文件，仅在 master）、`RewardClaimFixTest` L2（`RewardClaimFixTest.java:472-535`，本分支存在）。

| 用例 | 期望 |
|---|---|
| `freshBacklogOverridesStalePersisted` | persisted=`{count:0,next:now+600}`，engine=`{count:24,max:25}`，sig 变化 → `freeCount==24`，且 `st.recovery==engine`（**当前 master 会返回 0 → 修复前必失败**） |
| `backlogBurnedInOneRound` | freeCount=24，模拟 24 次 `code=0` → 一轮 `donated==24`，状态 `✓×24` |
| `staleInitNotDoubleSpent` | 引擎快照 sig 不变跨两轮，第一轮捐 3 后第二轮 `freeCount` 减少 3（保留现有 `localDeduction_notReAddedByProjection`） |
| `recoveryPerRoleIsolation` | 角色 A 的 `count=24` 不影响角色 B（键含 uid） |
| `activeRoleWriterRejectsUnverifiedSession` | `roleUid=0` → `isActiveRoleWriter()==false` |
| `recoverySigRoundTrip` | 编码/解码保留 `recovery_sig/spent_since_sig` |
| 纯函数可测的 `recoverySigOf()` / `resolveFreeCount()` | 建议抽成 static 纯函数，便于在本分支（无持久化）也能加回归测试 |

运行：`cd vault-server && ../gradlew test --tests "*AllianceDonation*" --tests "*RewardClaim*"`（参考仓库既有 gradle 结构）。

---

## 6. 生产证据附录（只读采集，未发任何游戏协议请求）

### 6.1 关键 state 键（2026-10-28 23:08 读取）

```
srv:alliance_science:48460403:888 =
  {"sciences":{ …61 项，57 项 lv5，6005/6011 lv3，6017 lv4，4008/4014 lv1，6023 lv0 donate=30021400 … },
   "recommend":6023,
   "recovery":{"id":205,"count":0,"next_calc_ts":1793199480,"recover":1,"interval":600,"max_count":25},
   "no_free_until":0,"target_cooldown_until":0,"updated_at":1793199105388}
   // next_calc_ts=1793199480 → 北京时间 22:58；updated_at=22:51:45

res:48460403:888 = {"211":10,"212":99,"201":9390,"213":10,"214":10,"203":0,"215":10,"204":10,"205":24,"216":20}
   // 由 notify_recover_resource_init 采集（CaptureTasks.saveResources），即游戏登录快照 205=24

mod:48460403:888:alliance_donate = {"no_free_until":"0","no_free_until_trg":"节奏","no_free_until_ts":1793198841331}
log:48460403:888:alliance_donate（最近 50 条，22:15:08–22:51:45）：
  22:17:52 / 22:27:10 / 22:38:03 / 22:47:21 = “科技捐献✓×1; …”，其余均为
  “科技捐献:免费次数已用尽（下次恢复 HH:MM），恢复后自动捐献; 每日发展奖励已领（台账）…”
```

### 6.2 monitor 统计（`data/monitor/monitor-20261028.jsonl`）

- `fn=alliance_donate` 的 `task.end` 全天 1022 条；成功轮次 108 轮、累计 128 次。
- 多捐只出现在任务空档后：`15:09:59 ✓×4`、`16:39:31 ✓×7`、`18:35:35 ✓×11`；
  **19:00:53 部署重启后 19:07:56–22:47:21 的 23 轮全部 `✓×1`**（约每 10 分钟 1 次，与 600s 恢复节奏一致）。
- 成功响应样本（19:07 起每轮）：`req_alliance_science_donate {"id":6023,"level":1}` → `code=0 {multiple:1~20, rank_data.value 逐步 +120*multiple}`；
  即"服务端允许捐"，停下的原因是客户端 `budget=1`（模型自限）。
- `rank_data.value`：18:47:25 = `29280/rank44` → 18:57:40 = `23760/rank50`（**角色切换**）；之后连续到 22:47:21 = `30960/rank45`。

### 6.3 会话/角色时间线（monitor kind=session + vault.json）

```
04:22:41  login 小鹿矿工5号 uid=88280792（离线恢复后）
14:15:04  closed → 14:15:08 login 小鹿矿工5号            （14:15 部署 functional-bugs3）
18:34:44  closed → 18:34:48 login 小鹿矿工5号            （18:34/18:35 部署 guardian）
18:47:35  state→logging；18:47:36 login 隆科多 uid=35371154（notifyEntries=35）  ← 角色切换点
19:00:53  两个会话同时 closed（部署重启）
19:00:57  两个 login：隆科多(35371154) + 小鹿矿工5号(88280792)
22:53:50  takeover：账号在其他设备上线（code=5）；22:53:54 会话关闭/offline
          yieldProbeAt=1793201031235（= 23:23:51 探测回连），策略 human_first
vault.json（23:06 写入）：activeSid=888，activeUid=88280792；
  roles: 888:35371154 隆科多 lv70 desiredOnline=false STOPPED；
         888:88280792 小鹿矿工5号 lv13 desiredOnline=true RUNNING；
  roleConfig[888].alliance_donate=true
```

### 6.4 生产 jar 已含修复（不再有 17:51 那类 WARN）

- 生产 `vault-server-all.jar`（sha256 `bc362c69…`）与三个当日 backup jar 的 `ServerTasks.class` 中均检出 `effectiveFreeCount`、`alliance_science`、`normalizeRecovery`、`target_cooldown_until`、"恢复后自动捐献" 等修复版特征串 → 生产跑的是 master 修复逻辑（本报告"当前逻辑"成立）。
- 生产日志 `17:51:55 WARN 联盟科技捐献失败 id=6023 level=1 code=1584` 仅出现 1 次，且 18:34 新包上线后没有再出现 → 与 92ae8de 的 1507/1584 目标级跳过修复一致。
- 注意：工作区 `HEAD 4e0a9f7` **不含** 92ae8de，本地同名 `ServerTasks.java` 是旧逻辑（无持久化、1507/1584 不跳过、`no_free_until` 只写 mod 键）。**补丁不要打在当前分支。**

---

## 7. 验证方法（不 deploy、不改生产即可做的）

1. **立即（只读，不重启）**：用 §6.1 的对比判断"次数模型 vs 真实快照"是否脱节：
   - 读 `res:<fpid>:<sid>` 的 `"205"` 与 `srv:alliance_science:…recovery.count`（+按 `next_calc_ts` 投影）；
   - 若两者差 ≥1 且持续存在，即 R1/R2 复现（本次：24 vs 0）。
2. **修复后（需人工审核/发布）**：
   - `log:…:alliance_donate` 应出现 `✓×N` 且 N>1（换角色/空档/重启后第一次补花）；
   - `srv:…recovery.count` 应收敛到 0 附近，且与 `res:205` 不再打架；
   - 游戏内「联盟科技」页手动对照：自动任务跑完一轮后，免费次数应从 24 掉到 0/1，再按 10 分钟 +1、恢复即被捐。
3. **本地单测**：见 §5.4；先跑 master 现有 `AllianceDonationPersistenceTest`，再加失败用例（当前 master 的 `freshBacklogOverridesStalePersisted` 预期失败，证明缺陷）。

---

## 8. 需要用户/Lead 确认的信息（决定"是 R1 还是 R2 为主"）

1. **看到 24/25 的是哪个角色**：隆科多（35371154，lv70）还是 小鹿矿工5号（88280792，lv13）？是否就是 号库 App 当前主控角色？
2. **截图**：游戏内「联盟 → 科技」页、「免费捐献次数」位置（捐献按钮旁的 `24/25` 或角标 + 倒计时）；以及该数字是"次数"还是"科技研究进度"。
3. **观察时间**（北京时间，精确到分），以及当时 号库 App 的功能卡/日志文案（是否"免费次数已用尽（下次恢复 …）"）。
4. 是否希望"只要恢复就立刻捐到 0"（当前设计意图/网页版 1:1 口径），还是用户期望"攒到 25 再捐"？—— 若用户其实希望"攒满再捐"，那是需求变更（加阈值参数），不是本次 bug。

---

## 9. 只读合规说明

- 全程只通过 `ssh -o BatchMode=yes vault-vps` 读取 `state.json` / 日志 / monitor / vault.json / jar 类常量；**未重启、未部署、未改任何生产文件**。
- **未对生产账号发送任何游戏协议请求**（未触发 donate/登录）。
- 本工作区仅新建本文件 `qa/analysis-20261028-alliance-science.md`；未改动任何源码/配置，未 commit、未 merge。
