# 立项设计：游戏服务器时钟（serverClock）

> 状态：立项设计（未开工）· 2026-10-06 · 前置：迁城/首占/移民任一功能立项时必须先做
> 对标原版：core/time/GameServerClock（§4.3）

## 1. 背景与目标

原版的毫秒级到点执行（首占 T-0 50ms 重试、迁城同纳秒释放、移民 50ms 节拍）全部依赖
**游戏服务器时钟**而非本机时钟——本机时间不准/时区跳变会让"到点"差之毫秒。

号库目前所有时间都用 `System.currentTimeMillis()`（补给 wake/代办/调度），对分钟级任务够用；
但「进阶工具 14 项」里的迁城/首占/移民一旦开工，serverClock 是硬前提。

## 2. 原版实现要点（取证）

- 三源校准：`notify_game_init` / `notify_server_time`（或响应内 server_time 字段）/ 心跳响应
- 本地钟外推：校准点之间用 monotonic 时钟外推（不受用户改系统时间影响）
- 每会话一份（GameServerClock per AccountSession）

## 3. 号库设计

```
GameServerClock（每 WjdrEngine 一份，挂在 LiveSession）
  ├─ offsetMillis：服务器时间 - 本机 monotonic 时间
  ├─ 校准源：登录响应/loginauth+loaddata 的 server_time 字段、notify_game_init、
  │          每 20 次心跳附带校准（心跳已有，顺手带）
  ├─ nowServer()：SystemClock.elapsedRealtime() + offset
  └─ waitUntil(epochMillis)：对齐等待（>100ms sleep，≤100ms parkNanos——原版移民同款边界）
```

**关键点：用 `SystemClock.elapsedRealtime()`（单调钟）而非 currentTimeMillis**——用户改时间/
NTP 跳变不影响；offset 由校准源维护。

## 4. 接入点

| 现有代码 | 改造 |
|---|---|
| DailyTasks.captureSupplyState 的 online_ts/spec_end | 展示不变；wake 触发判断改用 serverNow |
| RoleSessionKeeper 心跳 | 每 20 次顺手校准（零成本） |
| 将来迁城/首占/移民 | waitUntil + 50ms 节拍循环（照抄原版） |

## 5. 风险

1. 服务器时间字段名需实测确认（loaddata/notify 里形状防御式深找，同角色元数据的做法）。
2. 单机多会话各自校准（原版同款），无需全局统一。

## 6. 里程碑与估计

M1 时钟本体+心跳校准+supply wake 接入：半天；M2 waitUntil+闹钟对齐（供迁城等用）：半天。
合计约 1 人日。建议与「进阶工具」首个行军类功能同批排期，不单独提前。
