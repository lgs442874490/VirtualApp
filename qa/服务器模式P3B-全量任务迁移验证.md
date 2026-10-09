# vault-server Phase 3B · 全量任务执行器迁移验证（50/50）

> 日期：2026-10-08 深夜 · 环境：Mac 本地 vault-server（/tmp/vault-qa4，P3B 构建）+ 本地
> license-server + 模拟器 emulator-5554 + 开发账号 80971192（active 913）。
> 范围：剩余 38 项任务执行器（城镇 11 / 活动 15 / 野外 3 / 联盟 4 / 商店与其他 5）全量迁移，
> ServerTasks 目录达成与 App DailyTasks.all() 完全对齐（50 项）。

## 一、本轮代码（全部在 vault-server `task/` 包）

| 文件 | 内容 |
|---|---|
| `Exec.java` | App 端 ExecUtil 逐行移植：响应判定/通知解析/出征编队（clientSquads/formation/soldiers）/Multi 聚合 |
| `TownDevCatalog.java` | 科技/建筑目录 + 休闲/战狂路线（towndev/towndev.json 随 jar classpath 打包，与 App assets 同源） |
| `ServerTownTasks.java` | 城镇管理（招募+专家技能）/军医所/兵营训练（建筑等级+科技解锁档位推导）/宠物/专家游历/晨曦岛/火晶提炼/地心爬塔/竞技场 + tryStep/squadHeroes 公共件 |
| `ServerActivityTasks.java` | 正中靶心（兑换+免费局）/壶纳千祥（满分结算）/除雪/冰封宝藏/逐光之旅/欢乐乱斗/钓鱼/无尽征程/怪攻守城/至尊盛宴（赴宴出征）/红包/钻石银行/佣兵荣耀（出征）/运宝车队（发车）/冰原猎人（集结BOSS） |
| `ServerShopLeagueTasks.java` | 商店购买/联盟商店（shops.json 目录+限购）/烈焰獠牙/社交礼物/燃霜矿区（报名+里程碑）/联盟总动员/争霸赛/联盟互助/背包道具（flamefangs/socialgifts.json 入 jar） |
| `ServerFieldTasks.java` | 野外采集（搜矿派队）/情报任务（派遣+战斗+领奖）/自动狩猎（搜怪出征×N）+ 科技研究（ensure 递归前置）/建筑升级（前置最低链） |
| ServerTasks.all() | 扩至 50 项（与 App DailyTasks 目录逐项对齐：id/名称/默认开关） |
| build.gradle | processResources 增打包 `towndev/*.json` |

## 二、验证记录（通过）

1. **单测**：31/31；fat jar 595KB（含 schema + 任务目录）。
2. **全量整轮（手动触发）**：约 40 秒完成 50 项——**成功 47（领取 10）异常 3**；
   64 个日志键（任务级+子项级）+ summary + 当日状态机全落库。
3. **真实游戏动作生效（采样）**：
   - 邮件奖励＝**领取邮件 9 封**（批量读信协议真实执行）
   - 仓库补给＝普通补给已领取（奖励明细入日志）
   - 战斗护盾＝**8 小时免费护盾已开启**
   - 城镇管理＝史诗招募✓ 忠诚令牌✓ 每日士兵（专家技能协议）
   - 专家游历＝行动力已领 + **挂机游历×10**
   - 巨兽狩猎＝原生自动上车已开启；巨熊＝已预约 10月9日 20:30
   - 晨曦岛＝回礼+生命之树✓；宠物＝技能✓；正中靶心＝签到✓
4. **异常如实分级**（与 App 端同任务同错误一致，属账号/活动状态而非移植差异）：
   登录好礼码7506、每日任务码4302、活动合集 12 跳过/2 失败（未开放活动自动跳过）。
5. **App 回显**：fetchState「采集状态回写 150 键」→ daily_logs 64 键 + summary 落本地；
   日常页常驻卡「913区·在线 · 服务器」、功能行「领取邮件 9 封（候选 9）」（截图
   vaultsrv-P3B-日常页-全量整轮.png）——运行日志/当日完成统计/子项状态零改动渲染。

## 三、现场还原

模拟器：服务器模式/票据/端点覆盖已清、App force-stop；Mac 两个本地服务已停；
/tmp/vault-qa4、/tmp/vault_license.db。

## 四、遗留

1. 真机 + 静态住宅 IP 环境下的全量整轮与风控观察（模拟器直连验证只覆盖协议正确性）。
2. 出征类任务（采集/狩猎/佣兵/冰原猎人/盛宴赴宴）在服务器的编队口径与 App 一致
   （notify 推送驱动），但服务器会话无 UI 操作路径，若编队数据缺失会如实 SKIPPED。
3. 竞技场/兵营训练/建造研究等默认关闭项已在服务器支持，行为开关沿用 App 同步的配置。
