# vault-server Phase 3A · 服务器整轮（任务执行器首批）验证

> 日期：2026-10-08 深夜 · 环境：Mac 本地 vault-server（/tmp/vault-qa3，P3A 构建）+ 本地
> license-server + 模拟器 emulator-5554 + 开发账号 80971192（active 913）。
> 范围：任务执行迁移第一批——奖励/联盟领取类 12 项 + 服务器整轮调度 + 配置同步 + 结果回传。

## 一、本轮代码

| 层 | 内容 |
|---|---|
| vault-server `task/` | `TaskContext`（配置/状态访问面，替代 Android Context 依赖）；`TaskOutcome`（DailyResult 同构五分类）；`ServerTasks`（12 执行器逐行移植：登录好礼/邮件/仓库补给/每日任务/挂机奖励/活动奖励17种/统帅礼包/联盟宝箱/联盟捐献/战斗护盾/巨兽狩猎/巨熊行动 + classify 码表全家）；`RoundRunner`（整轮：配置门控→执行→log:/st:/summary 同键型落 StateStore，会话中断截断，未迁移任务记"待迁移"；收尾重采集）；`RoundScheduler`（30s 节拍：时间窗内随机时刻整轮、错过窗口补跑、补给到点单任务补领、手动队列） |
| vault-server 模型/API | VaultStore 增 auto/windowStart/windowEnd/roleConfig（任务开关）/roleParams（任务参数）；policy 新增 `action=run`（fpid + 可选 fn）；SessionKeeper 增 submitRound/sessionSid/sessionOnline（整轮派发到会话线程，与心跳/采集串行） |
| App | 同步 payload 增 auto/窗口/每角色任务开关与参数（DailyConfigStore+FnParams 全量）；fetchState 回写路由新增 `log:`→daily_logs（去前缀）与 `st:`→daily_logs（当日状态机）——**运行日志/功能行摘要/当日完成统计零改动渲染**；DailySchedulerService 服务器模式停跑（整轮/补给补领全由服务器）；FunctionActivity「立即执行」服务器模式提交服务器；**RoleSessionKeeper.scan() 服务器模式自愈**（见下） |

## 二、验证记录（通过）

1. **单测**：31/31（新增 RoundRunner 6 用例：整轮落键/配置门控/未迁移标记/单任务/参数策略/TaskContext）。
2. **配置同步**：sync 后服务器拿到 auto/window（5-9）；角色 config/params 随 payload 到达
   （本轮实测因开发号无自定义配置为空 map，参数链路由单测与 supply 策略用例覆盖）。
3. **服务器整轮（手动触发 policy run）**：4 秒完成 12 任务——**成功9（领取5）异常3**；
   **邮件实领 12 封**（响应证据确认）；DEGRADED/ERROR 如实分级落日志（不假成功）。
4. **补给到点补领**：RoundScheduler 从采集的 online_ts 到点自动触发单任务轮，成功领取。
5. **结果回传渲染**：App 切页 fetchState「采集状态回写 47 键」→ daily_logs 出现
   `mail=领取邮件 12 封(CLAIMED, 387ms, 手动)`、`login_rewards=码7506(ERROR)`（如实）、
   当日状态机 `st:…:mail=2 已完成`；日常页常驻卡「**913区·在线 · 服务器**」、
   邮件行摘要「**领取邮件 12 封（候选 12）**」（截图 vaultsrv-P3A-日常页-服务器整轮.png）。
6. **本地/服务器隔离**：修复后（scan 服务器模式自愈）本地 SessionKeeper 事件为 0——
   服务器模式下本地零会话、零互踢。

## 三、踩坑与修复（本轮真实发现）

1. **模式转换窗口的本地残留竞速**：服务器模式开启前，启动 sweep 已按本地模式建立 desired
   并起跑竞速；模式开启后 scan() 仍按残留 desired 整号重赛，与服务器会话互踢数分钟。
   修复：scan() 顶部服务器模式自愈（1 秒内收敛关闭全部本地期望/会话）。复测本地事件归零。
2. classify* 生成的 taskId 可能是协议名/任务名（App 端由 runner 落键所以无碍）——
   RoundRunner 落键一律用目录 id（单测抓出，已修）。
3. QA 操作坑：keep 开关进场即开（上一轮"关闭"点击实际切到了开）导致首轮时间线混乱；
   以服务器端 vault.json 的 keep 值为准做现场核对，勿凭 UI 记忆。

## 四、现场还原

模拟器：服务器模式/票据/端点覆盖已清、App force-stop（daily_logs 里保留的服务器整轮
记录属测试数据，不影响后续）。Mac：两个本地服务已停；/tmp/vault-qa3、/tmp/vault_license.db。

## 五、遗留（P3B）

- 城镇/野外/联盟战斗/商店/活动操作类 33 项执行器迁移（本轮未迁移项在整轮中记
  SKIPPED"待迁移到服务器（P3B 批次）"，App 运行日志可见，不静默丢失）。
- 时间窗整轮的"窗内随机时刻"仅封顶 60 分钟随机，未做跨窗补跑失败重试策略。
- 真机验证待做；30 项任务的真实风控观察（建议真机+静态住宅 IP 后跑一周）。
