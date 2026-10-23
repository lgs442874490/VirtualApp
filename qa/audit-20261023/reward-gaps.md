# 奖励功能 1:1 对照审计（task-1 / 只读）

> 审计人：audit-reward（只读，不构建、不改源码、不用 zcode）
> 时间：2026-10-23 03:2x（北京时间）
> 分支：`add/auto-claim-parity-20261023-0302`（本次未做任何写操作，仅新增本文件）
> 权威基准：`/Users/liangguansheng/ZCodeProject/wjdr-review/src-tool/com/wjdr/tool/modules/daily/`（网页版反编译源码，下称“网页版”）
> 我方：`VAContainer/vault-server/.../task/*.java`（服务器模式，生产实际运行）+ `VAContainer/app/.../daily/*.java`（App 模式）
> 生产只读证据：`ssh vault-vps` 读取 `/opt/vault-server/data/state.json`、`data/vault.json`、`data/logs/vault-2026102*.log`（只读命令，未重启/未写）

---

## 0. 一句话结论（先给 Lead 排期用）

**“好多奖励没有自动领”不是 notify 没同步，而是三层叠加的缺陷：**

1. **协议层写错/漏写**（P0，收益最大）：`activity_rewards` 里 17 项至少 **10 项**因为 `notify_*_info` vs 实际 `*_init`、id 藏在 `bp_info/ve_info/ci_info/...` 包装层、用 `List` 解析器解析 `Map`、参数缺 id/lv 而恒跳过或报错；网页版另有 **6 张卡 BP 阶段奖励 + 英雄殿堂 + 英勇的见证 + 月卡按卡 id 领取**根本没实现。
2. **调度层只有“每天一轮”**（P0）：服务器端 `RoundScheduler` 只在账号窗口 `[windowStart,windowEnd)`（当前账号 05:00–09:00，见 `vault.json`）跑一轮整轮 + supply 单功能补领；网页版是 **TaskEngine 每任务 wakeAt + interestedProtocols 事件驱动**（领取类间隔 30s–3600s，且每日任务/邮件/活动是“推送到达即领”）。这导致 **每日任务活跃度奖励、邮件、活动开窗、挂机奖励（10 分钟一领）** 等日内产生的奖励全部要等次日窗口，跨天即永久丢失。
3. **notify 数据确实“有”，但代码查不到**（P0 根因澄清）：生产日志实证 48460403 在 `10-23 00:41:16` 登录后一次性收到 **167 个不同 notify**，包含我们需要的 `notify_adventure_pve_info`、`notify_daily_claim_info`、`notify_customize_item_init`、`notify_multiple_choice_pack_init`、`notify_battle_pass_info`、`notify_month_card_info`、`notify_card_bp_info`、`notify_value_exchange_init`、`notify_vip_info` 等；`WjdrEngine.notifyHook`（`app/.../wjdr/WjdrEngine.java:651-682`）在**长会话**里持续累积到 `notifies` Map，不会因为轮次晚 18 分钟而丢失。所以 `activity_rewards` 的“未开放/未同步”绝大多数是**我方取数代码的名字/包装层/形态错误**，不是推送缺失。

**预计 P0 修复后，单账号每日可直接多领：挂机奖励（≈2.67× 产量）+ 每日任务全部活跃度宝箱（0→325 档/7 箱）+ 远征宝藏 1 次 + 自选箱/匠人宝库/月卡每日各 1 次（开放期）+ 英雄殿堂/英勇见证（开放期）+ 卡 BP/英雄集结阶段奖励（开放期一次性数十档）+ 邮件若干封 + 科技捐献 10 次/日。**

---

## 1. 生产基线证据（只读复核，fpid=48460403 / sid=888）

### 1.1 调度基线

| 事实 | 证据 |
|---|---|
| 账号整轮时间窗 `windowStart=5, windowEnd=9`（北京时间） | `/opt/vault-server/data/vault.json` → `accounts[0]`（48460403） |
| 最近一次整轮 `srv:lastrun:48460403 = 10-23 00:59:37`（手动触发，不是窗口轮） | `state.json` → `srv:lastrun:48460403`；`data/logs/vault-20261023.log:2941`（16:59:37Z 整轮完成） |
| 10-22 的窗口定时整轮发生在 **05:45:12**（trg=定时） | `state.json` → `log:48460403:888:activity_rewards[2].ts=1792619112400` → 10-22 05:45:12 |
| 窗口整轮之外只有 **supply 单功能补领**（01:57:33、00:45/00:48/00:50/00:55…） | `state.json` → `summary:48460403`、`srv:supplyfire:48460403`；`vault-20261023.log` 补给补领启动行 |
| `RoundScheduler` 30s tick，只在窗口内随机一次、同日不重复 | `vault-server/src/main/java/com/carlos/vaultserver/task/RoundScheduler.java:24,107-188` |
| 网页版同账号是“每个任务独立节奏”：activity-rewards/mailbox/commander/endless **180s**、supply 60s、alliance-donations 30s、alliance-chests 120s、daily-mission 120s、adventure-rewards 3600s 基线但 CD 到点精确唤醒、login-rewards 300s | `wjdr-review/.../daily/model/AbstractDailyAutomationTaskDefinition.java:37-84` |

### 1.2 最近一轮（10-23 00:59:37）各奖励任务结果（`state.json` → `log:48460403:888:*[0]`）

| 任务 | cat | 结果文案（原样） | 判定 |
|---|---|---|---|
| login_rewards | CLAIMED | 登录好礼已领（5分钟加速…）；七天签到已全领或不可用；21天签到已全领或不可用 | 部分正确（盲发协议，无状态判定） |
| mail | CLAIMED | 领取邮件 8 封（候选 8） | 只扫 5 个板块，仍有漏 |
| supply | CLAIMED | 普通补给已领取物品200301×5；体力补给:本窗口已领取 | 基本对齐 |
| daily_mission | SKIPPED | 今日任务奖励已全部领取（活跃度 15） | **00:59 只领到 15 活跃度**；当天后续任务奖励全部丢失 |
| chapter_quest | SKIPPED | 无可领（服务端跳过×2） | 无网页版对应模块（App 额外功能），日内漏 |
| adventure | SKIPPED | **当前暂无可领取的挂机奖励** | 见 §5.1：实际是“已领但没识别 + 24h 才领一次” |
| activity_rewards | **ERROR** | **领取2/跳过13/失败1**；detail=每日特惠礼包：钻石×100 | 见 §3：17 项中 10+ 项取数错误 |
| commander_gift | **ERROR** | **服务端返回错误码 4503** | 见 §5.4：缺 `{lv}` 参数 + 未解析 VIP 状态 |
| achievement_claim | SKIPPED | 没有已完成未领取的成就 | 日内完成成就全部等到次日（网页版无此模块） |
| act_endless | SKIPPED | 暂无可领（档位未达成） | 1 次/日 vs 网页版 180s+BP 推送 |
| red_packet | SKIPPED | 领取0/跳过2 联盟· 世界刷新码2· | 1 次/日 vs 网页版 300s 节奏 |
| backpack | SKIPPED | 领取0/跳过1 **背包数据未同步** | 见 §5.3：形态解析错误 |
| alliance_chest | SKIPPED | 暂无可领取的宝箱（无宝箱列表） | 1 次/日 vs 网页版 120s + 4 类推送 |
| alliance_donate | SKIPPED | **科技捐献:科技数据未同步**；每日/每周/排名奖励今日已捐献 | 见 §5.2：Map 形态 + lv+1 + freeCount |
| social_gift / bank | SKIPPED | 配置未启用 | （本任务不评） |

### 1.3 “notify 已同步”的硬证据

* `vault-20261023.log:349`：`16:41:16 I/VaultSrv: 登录成功 48460403|888|88280792`
* `vault-20261023.log:160-350`（16:41:15–16:41:16Z = 北京 00:41:15）：该次登录推送流共 **167 个不同协议**，其中与奖励相关且被我方代码漏用的：

```
YES notify_adventure_pve_info        ← 我方忽略（adventure 只发 view/claim）
YES notify_daily_claim_info          ← 我方查 notify_daily_free_reward_info（不存在）
YES notify_customize_item_init       ← 我方查 notify_customize_item_info（不存在）
YES notify_multiple_choice_pack_init ← 我方查 notify_multiple_choice_pack_info（不存在）
YES notify_battle_pass_info          ← 我方 extractId 取顶层 id，实际在 {"bp_info":{...}}
YES notify_month_card_info           ← 我方无参领取，实际要 {id:cardList[].id}
YES notify_card_bp_info              ← 我方完全未实现（卡 BP 阶段奖励）
YES notify_value_exchange_init       ← 我方 activity_rewards 未实现（英雄殿堂/英勇见证）
YES notify_vip_info                  ← 我方未解析（统帅礼包 {lv}、VIP 每日经验）
YES notify_hero_story_info           ← 我方 extractId 取不到（形态 {"reward_stat":{...}}，无顶层 id）
YES notify_achievement_info / notify_dailyquests / notify_fourteen_sign_info / notify_reward_info / notify_backpack_init
no  notify_lost_treasure_info / notify_hero_turntable_init（10-23 已结束）/ notify_fortune_card_init /
    notify_infinite_box_init / notify_optional_gift_pack_init / notify_fishing_activity_info /
    notify_duck_market_act_info —— 这些活动当期为“确实未开放”，跳过合理
```

* `WjdrEngine.notifyHook` 把所有 inbound（含 `type=REQUEST` 的推送）写入 `notifies`：`app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:651-682`；`notifyData` 只解包 `data/info/content` 三层（`:704-712`），`extractId` 只看顶层 `id/activity_id/activityId/activities`（`:715-736`）——**这就是包装层协议全部失败的代码级原因**。
* `collectNotifies(millis)` 只是 `Thread.sleep`（`:685-691`），对长会话没有“拉取”作用；它不会让缺失的推送出现。生产里 `activity_rewards` 的 “未同步” 不是等得不够久。

---

## 2. 总表（任务 | 网页版协议序列/前置/节奏 | 我方实现 | 根因 | 等级 | 最小修复 | 预计每日多领）

> 等级口径：**P0** = 当前生产可复现漏领/误报，且修复只动 1–2 个文件；**P1** = 有漏但需要调度底座或收益中等；**P2** = 网页版无对应模块 / 仅状态文案问题。

| # | 任务 | 网页版协议序列 + 前置状态来源 + 触发/节奏 | 我方实现（file:line） | 漏领/误跳过根因 | 等级 | 最小修复（文件+函数级） | 预计每日多领 |
|---|---|---|---|---|---|---|---|
| 1 | **activity_rewards 合集 17 项** | 见 §3 逐项表。总节奏：`activity-rewards` 180s 轮询 + 16 类 `notify_*` 推送唤醒（`task/activityrewards/ActivityRewardsTaskModule.java:21`），**只在 state 判定 claimable 时才发领取** | `vault-server/.../task/ServerTasks.java:863-1021`（noArg:868-877，byId:878-900，战令:901-909，英勇见证:910-922，曙光:923-1000） | ①`_info`/`_init` 名字错 3 项；②id 藏在包装层（`bp_info/ve_info/ci_info/mcp_info/ht_info/ogp_info/ci_info`）→`extractId` 全 null；③缺 6 卡 BP、英雄殿堂、英勇见证真协议；④`notify_daily_claim_info/notify_month_card_info` 名字/参数错；⑤无 claimable 判定，盲发 | **P0** | 新增 `vault-server/.../task/RewardNotifies.java`（包装层/常量 id/时间戳判定），重写 `ServerTasks.activityRewards`；同步 `app/.../daily/DailyTasks.java:901-1000` | 远征宝藏 1 次；自选箱 1–3 次（按开放）；匠人宝库 1 次；许愿屋 1 次（开放期）；英雄殿堂 1 次/日；英勇见证 1 次/期；英雄集结阶段奖励（开放期数十档）；卡 BP 阶段奖励（开放期数十档）；月卡每日 1 次/卡 |
| 2 | **adventure 挂机奖励** | `AdventureRewardsTaskModule.java:21` 监听 `notify_adventure_pve_info`；state 用 `adventure_pve_info.hang_start_time`+`pass_checkpoint` 判可领（`DailyAdventureRewardState.java:89-97`）；`req_open_hang_up_reward_view` 解析 `open_hang_up_reward_view_time/current_reward`（`:99-103`）；`req_get_hang_up_reward` 解析 `hang_start_time/current_reward`（`:105-116`）；4004→设 15s 重试（`DailyAdventureRewardService.java:167-171`），到点精确唤醒（`:76-88`）；最小 600s/累积上限 32400s（state:18-19） | `ServerTasks.java:793-861`；App `DailyTasks.java:818-899` | ①预览字段取 `endtime/end_time`（实际 `open_hang_up_reward_view_time`）；②领取成功证据取 `rewards/reward`（实际 `current_reward`）→ **领到了也报“当前暂无可领取”**；③完全忽略 `notify_adventure_pve_info`；④**一轮/日 vs 10 分钟一领** → 9h 封顶后 15h 产量/日白丢 | **P0** | `ServerTasks.adventureReward`：改解析字段 + 用 hang_start_time 算可领；`RoundScheduler` 增 adventure 单功能到点补领（仿 `RoundRunner.supplyWakeAt:194-202` 的 `mod:...:adventure:hang_up` 唤醒）或走 §6 推送调度 | 日产量 9h→24h，约为当前 **2.67×** |
| 3 | **commander_gift 统帅礼包** | `DailyCommanderGiftService.java:272-292`：`req_vip_get_daily_free_gift{lv:vip_lv}`；claimable=online && vip_lv>0 && active && !claimedToday；状态=`notify_vip_info{vip_lv,vip_active_endtime,get_daily_gift_ts,uncollected_daily_gift}`；4502=已领→SKIP，4503=VIP 未激活→SKIP；`autoRenewEnabled=false` 默认不续费（`:10-12`） | `ServerTasks.commanderGift:1260-1268`（无参 `classifySimple`）；且 `activityRewards:871` 又发一次同协议 | ①缺 `{lv}` 参数 → 生产 4503/错误码；②未解析 `notify_vip_info` → 无法判 claimedToday/active，4503 落 ERROR；③activity_rewards 与 commander_gift 双发（App 侧用 `commander_gift.gift` 子开关收敛，`DailyTasks.java:914-916`） | **P0** | `ServerTasks.commanderGift` 重写：读 `notify_vip_info`，传 `{lv}`，4502/4503 归 SKIP；activity_rewards 的 noArg 里 commander 项改为同一 helper（或删除，只留独立任务） | 1 个/日 + 状态从 ERROR→正确；如启用 autoRenew 另有 30 天特权续期（默认不开） |
| 4 | **alliance_donate 科技捐献** | `DailyAllianceDonationService.java:500-556`：循环 `freeCount` 次，`req_alliance_science_donate{id,level:target.level()+1}`；目标=推荐科技优先（`orderedTargets:556+`）；freeCount/恢复来自 `notify_recover_resource_init.data["205.0"]{count,max_count,next_calc_ts,recover,interval}`（state `rememberDonationRecovery/rememberRecovery`）；science 来源=`notify_alliance_science_init.sciences`（**Map**，"id.0"→{id,lv,donate}）+ 事件 `notify_alliance_science_exp_update.science` 单条累积 | `ServerTasks.allianceDonate:1111-1213`（科学段 1118-1156） | ①`notify_alliance_science_init.sciences` 是 **Map**，我方 `Exec.mapsOf`（List-only，`Exec.java:65-73`）→ 空 → “科技数据未同步”；②level 传 `lv`，网页版传 `lv+1`；③忽略高频 `notify_alliance_science_exp_update`（10-23 单日 144 条）；④无 freeCount 门控（`notify_recover_resource_init` 未解析） | **P0** | `ServerTasks.allianceDonate` 科学段：从 `notify_alliance_science_init`/`notify_alliance_science_exp_update` 建 science 表（Map 容错）、`level=lv+1`、按 recovery 205 的 freeCount 循环 | 免费捐献 up to 10 次/日（联盟币/荣誉/科技推进）+ 联盟发展奖励进度 |
| 5 | **daily_mission 每日任务** | `DailyMissionTaskModule` 监听 `notify_dailyquests`；state 解析 `notify_dailyquests{quests,finishs,integral}`（`DailyMissionState.java:37-39`）；`claimAvailableNow`→`req_get_all_dailyquest_award{quest_ids}`，批量失败回退 `req_get_dailyquest_award{questid}`（`DailyMissionService.java:200-225`）；integral 达 40/80/120/160/215/270/325 领活跃度宝箱；节奏 120s + 推送 | `ServerTasks.dailyMission:509-616`（含单领兜底 685-711）；节奏=整轮 1 次/日 | **日内完成的任务奖励/活跃度宝箱要等次日整轮**，跨天重置 → 永久丢失。生产：00:59 整轮“活跃度 15”清零后，当天 325 档全部不领 | **P0** | 承接 §6 推送调度：`notify_dailyquests`/`notify_update_dailyquest`/`notify_update_dailyquest_integral` → 单功能 `daily_mission`；`ServerTasks.dailyMission` 已能正确领，无需大改（保留兑底） | 每日任务全量奖励 + 活跃度宝箱 0→7 箱（当前仅早间零星） |
| 6 | **mail 邮件奖励** | `MailboxRewardsTaskModule` 监听 `notify_new_mail_syn/notify_mail_type_info_syn/notify_unlock_mail_attach/notify_mail_important`（180s）；`synchronizeMailboxTypes`→`req_mail_type_info`；5 大板块 25 种邮件类型（`DailyMailboxRewardService.java:323-329`：system 10 / alliance 20,100,120,170 / battle 30,110,130,140,160,210 / message 40,70,80,90,150,180,190,200,220 / collection 50）；分页 50/页（`:187+`）；候选=unread||hasAttachment，排除 important（`:145-154`）；批量 `req_batch_mail_read{first_type,mid_list_new,mid_list}`，失败逐封 `req_mail_read`+`req_mail_receive`（`:240-300`）；1201/1202/1204/1215 归 SKIP | `ServerTasks.mailRewards:282-407`；类型默认 `{10,20,30,40,50}`（:300-305），`keep_unread` 默认 true | ①邮件类型只扫 5/25 种 → battle/message 板块附件永远不领；②只用 `mailid_new`，忽略 legacy `mid_list` 数字 id；③无逐封 `req_mail_receive` 兜底；④日内新邮件要等次日窗口（有 30 天过期风险） | **P1**（类型/兜底可立即修，节奏随 §6） | `ServerTasks.mailRewards`：类型表改成网页版 5 板块全量；`req_batch_mail_read` 支持 `mid_list`；批量失败走逐封 `req_mail_receive`；`notify_new_mail_syn` → 单功能 | 若干封/日（数量取决于收件节奏） |
| 7 | **login_rewards 登录好礼** | `LoginRewardsTaskModule` 监听 `notify_fourteen_sign_info/notify_fourteen_purchase`（300s）；state 用 `{server_index,last_sign_ts,sign_days,free_award_record,buy_award_record}` 判可领天；`req_fourteen_sign_get_all_reward` 一键（`DailyLoginRewardService.java:33,96-102`），失败按天 `req_fourteen_sign_get_reward{day}`（`:118-135`）；7 天/21 天在 bootstrap 页（`req_seven_sign_one_click_award`/`req_sign_21day_get_all_reward`） | `ServerTasks.loginRewards:147-233` | 无状态判定，盲发 3 协议；在生产可正常工作，但“无可领”是服务端错误码兜底，21 天 `rewards` 判据可能与实际响应不符；节奏偏慢（300s vs 1 次/日窗口） | P2 | 加 `notify_fourteen_sign_info` 解析（三个协议保持），“已全领”判断改状态而非错误码 | 0（当前已能领） |
| 8 | **act_endless 无尽征程** | `EndlessJourneyTaskModule` 监听 `notify_general_bp_normal_info/extra_info/integral_update/purchase`（180s）；claim=`req_general_bp_get_all_reward{id:2904006}`（`DailyEndlessJourneyService.java:66-84`），11723/11724/11727 归 SKIP | `ServerActivityTasks.endlessJourney:373-405` | 协议/错误码都对齐，**只差节奏**：1 次/日 → 里程碑到点后最多延迟 24h（活动结束前最后一段会丢） | P1 | §6 调度：`notify_general_bp_*` → 单功能 `act_endless`（App `PushWakeDriver.java:40-41` 已有映射，服务器缺） | 开放期 1–N 档里程碑（防期末丢） |
| 9 | **red_packet 游戏红包** | `DailyRedPacketService.java:99,247,336`：监听 `notify_red_packet_user_info`（300s）；`req_red_packet_refresh{channel_type,id_lst}`→`req_red_packet_get{channel_type,id,index}` | `ServerActivityTasks.redPacket:466-504` | 协议对齐；**节奏 1 次/日** → 抢不到日内红包（红包有配额/过期） | P1 | §6 调度：`notify_red_packet_user_info` → `red_packet` | 若干红包（按频道刷新） |
| 10 | **alliance_chest 联盟宝箱**（口径归联盟组，仅列关联） | `AllianceChestsTaskModule` 监听 `notify_alliance_chest_init/add/update/delete`（120s）；分类批量领取（`DailyAllianceChestService`） | `ServerTasks.allianceChest:1057-1109`；节奏 1 次/日 | 日内宝箱要等次日；宝箱可能过期 | P1（与联盟组协同） | §6 调度 + 分类口径由联盟组定 | 若干/日 |
| 11 | **achievement_claim 成就奖励** | 网页版**无此模块**（`TaskModuleRegistry.REQUIRED_MODULE_IDS` 47 项中无 achievement/chapter）；我方为 App 额外功能，协议自 `notify_achievement_info{infos[]{id,complete,received}}`→`req_achievement_get_reward{list_data}`（`ServerTasks.achievementClaim:241-280`，与抓包形态一致） | `ServerTasks.java:241-280` | 协议正确；日内完成的成就等次日；无网页版对照 | P2 | §6 调度：`notify_achievement_info` → `achievement_claim` | 若干（按成就节奏） |
| 12 | **chapter_quest 章节/成长任务** | 网页版无此模块；我方解析 `notify_chapterquests{quests[],cur_chapter}`/`notify_growthquests{quests[]}`（`ServerTasks.chapterQuest:715-791`） | 同左 | 协议基本正确；日内完成等次日（`notify_growthquests`/`notify_chapterquests` 只在登录期推送，App `PushWakeDriver.java:29-30` 已挂） | P2 | §6 调度 + 服务器补挂这两个协议 | 若干 |
| 13 | **backpack 背包道具** | 网页版**无自动开箱模块**（`DailyBackpackResourceService` 是手动 UI：`req_batch_use_item`/`req_use_item`）；我方为 App 额外功能 | `ServerShopLeagueTasks.backpack:760-808` | `notify_backpack_init.data` 是 **List<{id,count}>**，我方用 `Exec.kvPairs`（期望 `[{k,v}]`，`Exec.java:76-83`）→ 空 → “背包数据未同步”；`notify_backpack_update` 也未监听 | P2（若保留功能则升 P1 修解析） | 改 `mapsOf` 取 `{id,count}`；监听 `notify_backpack_init/update`；无网页版对照，是否自动开箱需产品确认 | 资源包若干（如开启） |
| 14 | **social_gift / bank / shop 等** | 非本任务核心 | — | 见联盟组/商店组报告 | — | — | — |

---

## 3. 活动奖励合集：网页版权威 17 项（+卡 BP 6 项）逐项对照

> 网页版实现位置：`service/DailyActivityRewardsService.java`（领取）+ `service/DailyActivityRewardsState.java`（状态）+ `service/DailyActivityRewardKey.java`（24 个 key）；触发：`task/activityrewards/ActivityRewardsTaskModule.java:21` 的 16 类通知 + 180s 轮询。
> 我方实现：`vault-server/.../task/ServerTasks.java:863-1021`（下文“我方”均指该函数内的行号）。

| 网页版 key（显示名） | 领取协议 + 参数（权威） | 状态来源 notify + 判据 | 我方现状 | 判定/缺口 |
|---|---|---|---|---|
| `welfare-card-daily` 月卡/周卡每日奖励 | `req_month_card_get_daily_reaward{"id":cardId}`（每张卡单独领；`DailyActivityRewardsService.java:325-362,337`） | `notify_month_card_info{cardList:[{id,validity_period,daily_reaward_time}]}`；判 `validity_period>now && !isSameDay(daily_reaward_time)`（state:932-1003） | `ServerTasks.java:869` noArg“月卡每日奖励”，**无 id** | **P0 错**：无 id 必失败（生产 fail 计数来源之一）；未解析 cardList |
| `commander-daily-exp` VIP每日经验 | `req_vip_get_daily_exp{}`（无参） | `notify_vip_info{get_daily_exp_ts,vip_lv}`（state:1005-1030） | `:870` ✓ 无参；但未判 claimedToday | P2：协议对，缺判定（重复请求被服务端跳过，无收益损失） |
| `cmd` 统帅每日礼包（独立模块 commander-gift） | `req_vip_get_daily_free_gift{"lv":vip_lv}` | 同 `notify_vip_info` | `:871` + `commanderGift:1260-1268` 均**无参** | **P0 错**：见 §5.4 |
| `daily-special` 每日特惠 | `req_daily_bundle_get_free_gift{}` | `notify_daily_bundle_info{free_gift,reset_ts}`（state:922-930） | `:872` ✓（生产已领“钻石×100”） | ✓ |
| `lost-treasure` 失落宝藏 | `req_lost_treasure_get_daily_reward{"id":2706001/2706002}`（`DailyActivityRewardKey.LOST_TREASURE_IDS:73`） | `notify_lost_treasure_info.info{id,daily_reward_get_ts}`（state:756-762,1082-1088） | `:879` 名字对、id 靠 `extractId`（info 子层）→ 取不到 | 当期活动未开（0 推送）暂不损失；**形态错，开窗即漏**（P1 latent） |
| `journey-treasure` 远征/征途宝藏 | `req_daily_claim_free_reward{"id":2435002}` | **`notify_daily_claim_info{id,info{free_reward_ts,...}}`**（state:897-910；活动 2435002 当前在开，见 `todo:48460403:888`） | `:880` 查 **`notify_daily_free_reward_info`（不存在）** | **P0 漏**：该活动现在就是开的，每日 1 次全丢 |
| `wish-house` 许愿屋 | `req_hero_turntable_get_free_gift{"id":2402001}` | `notify_hero_turntable_info` **或 `_init`**`{ht_info{free_gift_get_time}}`（state:144-149） | `:881` 只查 `notify_hero_turntable_info`（实际推送 `_init`，10-22 12 次） | **P0 错**：10-22 活动开放日整日未领（活动 10-23 00:00 结束） |
| `mia-wish-house` 米娅许愿屋 | `req_fortune_card_get_daily_free_gift{"id":2703001}` | `notify_fortune_card_info|_init{fc_info.daily_reward_ts}` | `:882` 只查 `_info` | P1 latent（当期 0 推送=未开） |
| `infinite-grocery` 无限宝箱 | `req_infinite_box_get_reward{"id":2510001}`；可领判 `reward_group/cur_order`（state:1032-1048,1178-1183） | `notify_infinite_box_info|_prepared{info{...}}` | `:883` 只查 `_info` | P1 latent |
| `dawn-wisdom` 曙光智慧 | `req_optional_gift_pack_get_free_box{"id":2508008}` | `notify_optional_gift_pack_info|_init{ogp_info.free_box_get_time}`（state:912-920） | `:884` “自选礼包”只查 `_info` | P1 latent |
| `dawn-market` **曙光市集** | **`req_optional_gift_pack_get_free_box{"id":2508001..2508007}`**（`DAWN_MARKET_IDS:78`） | 同上 `ogp_info` | `:923-1000` 用 **`notify_duck_market_act_info` + `req_duck_market_get_quest_award/cost_award`** | **P0 族系错**：网页版权威源码里**无 duck_market 协议**（全 src-tool grep 为 0）；我方的 duck 协议来源无实证，当前“未开启/未同步”是查了个不存在的通知。应按 optional_gift_pack 的 2508001-7 实现 |
| `rare-custom` 奇货自选 | `req_customize_item_get_free_box{"id":RARE_CUSTOM_IDS(2507001-6)}` | `notify_customize_item_info|_init{ci_info{id,free_box_get_time}}`（state:1050-1058） | `:885` “自选箱”只查 `_info`；且只发 1 次、id 取不到 | **P0 漏**：`notify_customize_item_init` 生产高频（10-23 20 次），当天可领却全跳过 |
| `craft-custom` 匠心自选 | 同上，`CRAFT_CUSTOM_IDS(2507007,8,15,16)` | 同上 | 同上 | **P0 漏**（与上同一 bug；需按 3 组 id 分别领） |
| `armament-custom` 武备自选 | 同上，`ARMAMENT_CUSTOM_IDS(2507009,10,12-14)` | 同上 | 同上 | **P0 漏**（同上） |
| `craftsman-treasury` 匠人宝库/工匠的宝库 | `req_multiple_choice_pack_get_free_box{"id":2509001..}"` | `notify_multiple_choice_pack_info|_init{mcp_info{id,free_box_get_time}}`（state:171-176） | `:886` 只查 `_info` | **P0 漏**：`_init` 生产高频（10-23 23 次） |
| `fishing-championship` 钓鱼锦标赛/免费礼 | `req_fishing_get_free_gift{"id":2418001/2418002}` | `notify_fishing_activity_info{activity_info{id,free_gift_get_time,...}}`（state:1060-1080） | `:887` 查 **`notify_fishing_info`（不存在）** | P1 latent（当期 0 推送=未开） |
| `hero-rally` 英雄集结/战令 | `req_battle_pass_collect_all_reward{"id":2401001..2401010}`；7307 失败回退逐档 `req_battle_pass_collect_reward{"id","reward_id"}`（`DailyActivityRewardsService.java:241-316`） | `notify_battle_pass_info|_prepared{bp_info{id,score,start_ts,end_ts,free_reward,buy_reward,version}}`（state:790-811） | `:901-909` 用 `extractId(notifyContent("notify_battle_pass_info"))` → 顶层无 id（实际 `{"bp_info":{...}}`）→ **恒 skip**；无逐档兜底 | **P0 漏**：活动 2401010 当前在开（todo 窗口 10-12→10-26），免费阶段奖励全未领 |
| `heroic-witness` **英勇的见证** | **`req_value_exchange_free_exchange{"id":2702001,"idx":WITNESS_INDEX[version]}`**（`DailyActivityRewardsService.java:225`；index 表 state:26-31） | `notify_value_exchange_info|_init{ve_info{id:2702001,version,is_free_exchange,exchange_data}}`；判 `is_free_exchange!=true`（state:764-778） | `:910-922` 用 **`req_hero_story_claim_reward{id}` + `notify_hero_story_info`** | **P0 错**：网页版权威源码无 `req_hero_story_claim_reward`（全库 0 命中）；`notify_hero_story_info` 形态为 `{"reward_stat":{"id":{k,v:"true"}}}`，无顶层 id → 我方恒 skip。真正的“英勇的见证”是价值兑换免费档 |
| （英雄殿堂 `hero-hall`，网页版同表第 19 key） | `req_value_exchange_exchange{"id":2702001,"idx":DAILY_INDEX[version],"count":1}` | 同上 `ve_info.exchange_data` 判 `[idx]==0`（state:764-778,302-311） | 我方 **无此领取**（仅 `ServerShopLeagueTasks.snowTrade:389-514` 借用 value_exchange 做雪原贸易站，且默认关闭） | **P0 漏**：`notify_value_exchange_init` 生产高频；每日免费档 1 次 |
| （卡 BP 6 项：`intelligence-month-card` / `-epic` / `-legend`、`special-week-card` / `-epic` / `-legend`） | `req_card_bp_collect_reward{"id":2505001-3 / 2506001-3, "stage_id":1..100}`；逐档领，8202/8201 停、8203 记已领（`DailyActivityRewardsService.java:368-396`；cardKey state:725-745） | `notify_card_bp_info{info{id,progress,free_reward,buy_reward,buy_gift_id}}`；claimable=`active && progress>0 && 未领阶段>0`（state:652-669,747-754） | **完全未实现**（我方 ServerTasks 无卡 BP 代码） | **P0 漏**：`notify_card_bp_info` 10-23 单日 40 次（4 张卡活动在开），`free_reward` 标记说明免费档可领 |
| （`daily_bundle` 已在 4 行；`COMMANDER_DAILY_EXP` 已在 2 行） | — | — | — | — |

**补充：网页版还有 8 个活动 key 不在我方 17 项里（了解即可）**：`dawn-wisdom`、`rare/craft/armament-custom` 三拆、`hero-hall`、三个卡 BP 系列。它们全部走同一套 state+claim，若实现 §3 的通用取数，可顺带全部覆盖。

---

## 4. notify 名称 / 形态差异总表（我方 vs 网页版/抓包实证）

> 抓包实证位置：`VAContainer/tools/bundle_re/proto_log.txt`（10-13/14 登录快照全量推送）与 `tools/bundle_re/audit_1014/proto_log_dev_old.txt`（10-12，含在线事件）。这些文件是**我方仓库内的登录推送抓包**，与网页版源码互证。

| 协议 | 实际形态（抓包证据） | 网页版读取方式 | 我方代码 | 结论 |
|---|---|---|---|---|
| `notify_adventure_pve_info` | `{"adventure_pve_info":{"pass_checkpoint":129,"hang_start_time":1791886167}}`（`proto_log.txt:110`） | `DailyAdventureRewardState.java:89-97` | **未使用** | 漏：应以此做可领判定 |
| `notify_daily_claim_info` | `{"id":2435002,"info":{"id":2435002,"free_reward_ts":...,"receive_slot_index":0,"has_pay":"false","main_index":17}}`（`proto_log.txt:98`） | state:897-902（取 `info.free_reward_ts`） | 查 `notify_daily_free_reward_info`（不存在） | **名字错** |
| `notify_value_exchange_init` / `_info` | `{"ve_info":{"id":2702001,"preview_ts":...,"start_ts":...,"end_ts":...,"exchange_data":{"132.0":{"k":132,"v":0},"155.0":{"k":155,"v":1}},"version":10,"is_free_exchange":"true"}}`（`proto_log.txt:76`） | state:764-778（`ve_info`，version→index 表） | 仅 `ServerShopLeagueTasks.snowTrade:402-447`；activity_rewards 未接 | 漏：英雄殿堂/英勇见证 |
| `notify_battle_pass_info` / `_prepared` | `{"bp_info":{"id":2401010,"score":40,"free_reward":{"1086.0":{"id":1086,"mark":"true"}},"buy_reward":{},"buy":"false","bp_version_idx":36,...}}`（`proto_log.txt:53`） | state:95-103,790-811 | `ServerTasks:913` `extractId` 顶层 → null | **包装层错**（应读 `bp_info.id`、`free_reward` 标记） |
| `notify_hero_turntable_init` / `_info` | 抓包窗口未开（0 条）；网页版 state 声明 `{"ht_info":{...free_gift_get_time}}`（state:148-153） | 同时接受 `_info` 与 `_init` | 只查 `notify_hero_turntable_info` | **名字错**（生产实际见 `_init` 12 次/10-22） |
| `notify_customize_item_init` / `_info` | `{"ci_info":{"id":2507019,"start_ts":...,"end_ts":...,"buy_info":{},"free_box_get_time":0,...}}`（`proto_log.txt:158`） | 同时接受 `_info`/`_init`；id 在 `ci_info.id`（state:1050-1058） | 只查 `notify_customize_item_info` | **名字+包装层错** |
| `notify_multiple_choice_pack_init` / `_info` | `{"mcp_info":{"id":2509004,"start_ts":...,"end_ts":...,"free_box_get_time":0}}`（`proto_log.txt:683`） | 同时接受 `_info`/`_init`；id 在 `mcp_info.id` | 只查 `notify_multiple_choice_pack_info` | **名字+包装层错** |
| `notify_optional_gift_pack_init` / `_info` | 抓包窗口未开（0 条）；state 声明 `{"ogp_info":{...free_box_get_time}}` | 同时接受 `_info`/`_init`；id 2508008 或 2508001-7 | 只查 `notify_optional_gift_pack_info` 且 id 取不到 | 名字+包装层错（latent） |
| `notify_month_card_info` | `{"cardList":[{"card_type":3,"validity_period":0,"daily_reaward_time":...,"id":5000205},...]}`（`proto_log.txt:347`）；也可能 `{"cardList":[]}`（:154） | state:932-1003，按 cardList[].id 领 | `ServerTasks:869` 无参 | **参数错** |
| `notify_vip_info` | `{"vip_lv":4,"vip_exp":...,"vip_active_endtime":...,"get_daily_exp_ts":...,"get_daily_gift_ts":...,"uncollected_daily_gift":{}}`（`proto_log.txt:97`） | 指挥官服务读 `vip_lv/active/claimed` | 未解析 | 缺状态 |
| `notify_card_bp_info` | `{"info":{"id":2506003,"progress":3300,"free_reward":{"1.0":{"id":1,"mark":"true"}...},"buy_reward":{},"buy_gift_id":0,...}}`（`proto_log.txt:176`） | state:51-77,652-669 | **无实现** | 漏 |
| `notify_hero_story_info` | `{"reward_stat":{"500031.0":{"k":500031,"v":"true"},...}}`（`proto_log.txt:93`） | **网页版不消费此协议**（全 src-tool grep 为 0）；我方设计文档推测 `req_hero_story_claim_reward` | `ServerTasks:910-922` | 协议未实证；`extractId` 无顶层 id → 恒 skip。真正的“英勇的见证”应走 value_exchange |
| `notify_duck_market_act_info` | 抓包无（0 条）；网页版无此协议族 | 网页版“曙光市集”=`req_optional_gift_pack_get_free_box{id:2508001-7}` | `ServerTasks:928` | **族系错**（非“未同步”，是查错协议） |
| `notify_alliance_science_init` | `{"sciences":{"6019.0":{"id":6019,"lv":5,"donate":0},...},"recommend":...}`（`proto_log.txt:83`）→ **Map** | state:394-406 `maps(data.get("sciences"))`；level 用 `lv+1` 领取（service:533） | `ServerTasks:1128-1136` 用 `Exec.mapsOf`（List-only）→ 空 | **形态错 + level 错** |
| `notify_alliance_science_exp_update`（在线事件） | `{"science":{"id":6023,"lv":0,"donate":717955170}}`（`audit_1014/proto_log_dev_old.txt:108`） | state:111-115,443-447 累积单条 science | 完全未监听（10-23 单日 144 条） | **漏** |
| `notify_recover_resource_init`（联盟捐献免费次数源） | `{"data":{"205.0":{"id":205,"count":N,"max_count":M,"next_calc_ts":...,"recover":1,"interval":...}}}`（网页版 state `rememberDonationRecovery` 取 id=205；抓包 `proto_log_dev_old.txt` 同族 `data` Map） | state `initializeRecovery/rememberDonationRecovery` | 未解析（CaptureTasks 只用资源 211/212 等做 `res:`） | 漏：freeCount 未知 |
| `notify_backpack_init` | `{"data":[{"id":510000,"count":14},...]}`（`proto_log.txt:36`）→ **List<{id,count}>** | 网页版无自动开箱；背包状态由 BehemothAccountState 解析 | `ServerShopLeagueTasks:765-769` 用 `kvPairs`（期望 `{k,v}`）→ 空 | 形态错（“未同步”实为解析失败） |
| `notify_intelligence_events`（Lead 问） | 未在 proto_log 命中（事件型）；网页版 `IntelligenceTaskModule.java:19` 的 TRIGGER_PROTOCOLS **确实包含** `notify_intelligence_events/events_del/battle_result` | 网页版接受 | 我方 `ServerFieldTasks.java:232`、App `FieldTasks.java:231` 也在用 | **与网页版一致，不是差异点**（属野外组 task-2 范围） |
| `notify_reward_info` | `{"online_reward":{"online_reward":[{"id":200401,"count":7}],"day_count":1,...},"spec_reward":{"receive_time":...},"bank":{...}}`（`proto_log.txt:150`） | SupplyState 判可领/下次时间 | `CaptureTasks.captureSupplyState` 部分解析（`online_ts`） | 基本对齐（补领机制已有） |
| `notify_fourteen_sign_info` | `{"server_index":5004001,"last_sign_ts":...,"sign_days":4,"free_award_record":[1,2,3,4],"buy_award_record":[]}`（`proto_log.txt:68`） | state 判可领天，14 天协议 | 未解析，盲发 | P2（当前能领） |
| `notify_sevensign_info` / `notify_sign_21day_info` | `proto_log.txt:146` / old log 有 `_info` | 网页版在 bootstrap（`req_seven_sign_one_click_award`/`req_sign_21day_get_all_reward`） | 我方直接发协议 | 一致性 OK |

---

## 5. 三个“未同步/暂无可领取”的确切根因

### 5.1 adventure“当前暂无可领取的挂机奖励”

**不是没同步，也不是没可领，而是三处代码错误叠加：**

1. 预览字段：`ServerTasks.java:798-799` 取 `endtime/end_time`；网页版/抓包实际是 `open_hang_up_reward_view_time`（`DailyAdventureRewardState.java:99-103`），所以“距到量阈值”策略永远不生效。
2. 到账证据：`ServerTasks.java:847-857` 只看 `rewards/reward`；网页版领取响应解析 `hang_start_time/current_reward`（state:105-116）。→ **即使服务端已发放，也被判“跳过”并写 SKIPPED**（生产文案由此而来）。
3. 节奏：`RoundScheduler.java:129-166` 一天只在窗口跑一轮 → 挂机奖励 10 分钟可领一次、累积上限 9h（state:18-19），一天只领 1 次，等于把 24h 产量压到 9h。**这是收益侧最大项（≈2.67×）。**
4. 附加：`notify_adventure_pve_info`（登录即到，`proto_log.txt:110`）完全没被使用，无法在不发协议的情况下判可领；4004 时应像网页版一样 15s 后重试（`DailyAdventureRewardService.java:167-171`），我方直接 SKIP 到下一轮（次日）。

**最小修复**：`ServerTasks.adventureReward` 改 3 个字段 + 用 `notify_adventure_pve_info.hang_start_time` 算 `now-hang_start_time>=600`；`RoundScheduler` 或 §6 调度器增加 `adventure` 单功能 10 分钟补领（仿 `RoundRunner.supplyWakeAt:194-202`）。

### 5.2 科技捐献“科技数据未同步”

1. **形态错**：`notify_alliance_science_init.sciences` 是 `Map<"id.0", {id,lv,donate}>`（抓包 `proto_log.txt:83`），我方 `Exec.mapsOf`（`Exec.java:65-73`）只接受 `List` → 永远空。网页版用 `DailyAllianceDonationState.maps(...)`（兼容 Map）。
2. **参数错**：网页版领取传 `level = target.level()+1`（`DailyAllianceDonationService.java:533`）；我方传 `lv`（`ServerTasks.java:1139`）→ 协议请求会被服务端拒绝（这正是历史 `code=2` 类错误）。
3. **数据源缺失**：即便 init 推送没到（48460403 在 10-23 00:41 首次登录推送里**没有** `notify_alliance_science_init`，它 16:44:02 才随第二角色登录到达，见 `vault-20261023.log:558`），网页版靠在线事件 `notify_alliance_science_exp_update.science` 持续累积（state:443-447），单日 144 条；我方完全未监听。
4. **没有免费次数**：免费捐献上限来自 `notify_recover_resource_init.data["205.0"]`（`count/max_count/next_calc_ts/recover/interval`），网页版 `rememberDonationRecovery`/`projectRecovery` 会投影恢复；我方未解析 → 即使修好 1/2，也只能盲捐一次。

**最小修复**：`ServerTasks.allianceDonate` 科学段：①`rewardSciences()` 合并 init（Map values）+ exp_update 单条；②`level=lv+1`；③解析 recovery 205 得 freeCount 并按次捐；④保留“推荐科技优先”。

### 5.3 背包“背包数据未同步”

- 抓包形态：`notify_backpack_init {"data":[{"id":510000,"count":14},...]}`（`proto_log.txt:36`）。
- 我方：`ServerShopLeagueTasks.backpack:765-769` 用 `Exec.kvPairs(notifyContent(...).get("data"))`；`kvPairs` 期望 `[{k:..,v:..}]`（`Exec.java:76-83`）→ 恒空 → 文案“背包数据未同步”。
- 且未监听 `notify_backpack_update`（生产 10-22/23 275 次）。
- 说明：网页版**没有**背包自动开箱模块（`DailyBackpackResourceService` 是手动 UI，`req_batch_use_item` 只在手动/宠物升级用），所以此功能属我方 App 额外功能；若保留，改 `mapsOf` 解析 `{id,count}` 并把文案改为真实原因。**是否自动开箱需产品确认（可能误花/误开限量箱）。**

### 5.4 统帅礼包“服务端返回错误码 4503”（Lead 未列但属 P0 奖励）

- 网页版：`req_vip_get_daily_free_gift{"lv":vip_lv}`（`DailyCommanderGiftService.java:272-292`）；4503=`VIP_INACTIVE`（`:41,188-192`）→ 归 SKIPPED“统帅特权未激活”，默认 `autoRenewEnabled=false` 不自动续费（`config/DailyCommanderGiftConfig.java:10-12`）。
- 我方：`ServerTasks.commanderGift:1260-1268` 无参 + `classifySimple` → 4503 不在 `classifyServerCode` 白名单（`:1350-1393`）→ ERROR；`activityRewards:871` 再发一次同协议（重复）。
- 修复：解析 `notify_vip_info`（`proto_log.txt:97`），`{lv}` 必传；4502/4503 归 SKIP；续费功能保持默认关闭（防 10000 钻石误花）。

---

## 6. 调度架构根因：一次/天 vs 事件驱动（Lead 要求单列）

### 6.1 网页版语义（权威）

- `TaskEngine.decideNext`（`engine/TaskEngine.java:450-459`）：每次收到 `TaskSignal`（`SNAPSHOT_READY / TIMER_FIRED / PROTOCOL_NOTIFICATION / COMMAND_COMPLETED / CONFIG_CHANGED / RECONCILE_REQUESTED`）→ 调模块 `decide()` → `Noop / SleepUntil(wakeAt) / Execute(TaskCommand)`。
- `TaskScheduler`（`engine/TaskScheduler.java:45-136`）：按 `wakeAt` 精确调度定时器。
- `TaskSignalBus.publishAccount` + `protocolTargets`（`engine/TaskSignalBus.java:36-65`）：**每条 inbound `notify_*` 都会成为 PROTOCOL_NOTIFICATION 信号，只投递给 `acceptsProtocol(proto)`（即 `interestedProtocols()` 命中）的模块** → 模块在 SLEEPING/COMPLETED 时被“唤醒”重新 decide（`TaskEngine.shouldReevaluate:461-483`）。
- 模块节奏：`AbstractStandardTaskModule.decideFromReady:92-107` 优先用 status 的 `nextClaimInSeconds/nextExecuteInSeconds/remainingSeconds` 走 `SleepUntil`；无则 `intervalSeconds`（`AbstractDailyAutomationTaskDefinition.forTask:37-84`：领取类 30s–3600s 不等）。
- 领取类模块的 `interestedProtocols` 举例（都是“推送到达即可能领取”）：
  - activity-rewards：`task/activityrewards/ActivityRewardsTaskModule.java:21`（16 类）
  - mailbox：`task/mailboxrewards/MailboxRewardsTaskModule.java`（new_mail_syn 等）
  - supply：`task/supply/SupplyTaskModule.java`（notify_reward_info/start_online_reward/online_reward_timeup）
  - adventure：`task/adventurerewards/AdventureRewardsTaskModule.java:21`（notify_adventure_pve_info）
  - commander：`task/commandergift/CommanderGiftTaskModule.java`（vip_info/update）
  - 还有 `engine/DailyTaskEngineBridge.forwardProtocolNotification:187-206` 把 inbound 全量转发（`notify_*` 前缀）。
- 活动日历：登录上线即 `req_activity_calendar_get_data`（`DailyActivityCalendarService.java:50-61,96-105`），并由 `DailyActivityCalendarSyncScheduler` 周期续拉（`DailyActivityCalendarSyncScheduler.java:60-120`），用于“活动开窗/结束/家族”门控——我方只在一轮结束调一次（`CaptureTasks.captureCalendar:123+`，`ModularRoundRunner:131`）。

### 6.2 我方服务器现状

- `RoundScheduler`（`vault-server/.../task/RoundScheduler.java:107-188`）：
  - 30s tick；只在账号 `[windowStart,windowEnd)` 内随机一次跑**整轮**（`:129-166`，同日不重复，`srv:lastrun` 记录）；
  - 仅 **supply** 有“到点单功能补领”（`:167-186`，`RoundRunner.supplyWakeAt:194-202` 读 `mod:...:supply.online_ts`）；
  - 其余全部奖励类任务共享同一轮次 → 日内事件无法触发。
- `SessionKeeper`（`vault-server/.../SessionKeeper.java`）已有可挂载点：
  - `LiveSession.engine`（`:245`）长会话；已有 `ServerHelpResponder`/`ServerShieldResponder` 两个**反应式**子任务在会话 tick 里创建（`:343-347`），`attachWarHook` 用 `engine.addMessageHook`（`:393-399`）；`WjdrEngine.addMessageHook`（`:971-973`）是公开 API。
  - `submitRound(fpid, fn, "trigger")`（`:1372-1421`）已支持**单功能**执行（`fn` 非空只跑该任务）；`executorBusy(fpid)` 可防堆积。
- App 侧已有先例：`app/.../daily/PushWakeDriver.java`（P3，`WAKE_MAP:25-46`）用 `engine.addMessageHook` 做“推送→单功能执行”，10 分钟冷却；但 **WAKE_MAP 只覆盖 17 个任务，且不含 activity_rewards/mail/commander/alliance_chest/alliance_donate/achievement/red_packet**（`PushWakeDriver.java:28-45`），所以即使 App 模式，活动奖励合集仍只靠每日整轮。

### 6.3 最小改造方案（建议直接排期）

**方案 A（推荐，1–2 天，与网页版 1:1 语义对齐）：服务器版 PushWakeDispatcher（移植 App `PushWakeDriver`）**

1. 新增 `vault-server/src/main/java/com/carlos/vaultserver/task/PushWakeDispatcher.java`：
   - `attach(LiveSession)`：`ls.engine.addMessageHook((proto, content) -> …)`（参考 `SessionKeeper.java:393-399` 的 war hook 写法）；
   - `WAKE_MAP`（按网页版 `interestedProtocols` 全量）：`notify_dailyquests|notify_update_dailyquest*`→`daily_mission`；`notify_new_mail_syn|notify_mail_type_info_syn|notify_unlock_mail_attach|notify_mail_important`→`mail`；`notify_adventure_pve_info`→`adventure`；`notify_reward_info|notify_start_online_reward|notify_online_reward_timeup`→`supply`；`notify_vip_info|notify_vip_update`→`commander_gift`；`notify_alliance_science_init|…exp_update|notify_recover_resource_init/update|notify_alliance_dev_*`→`alliance_donate`；`notify_alliance_chest_*`→`alliance_chest`；`notify_activity_calendar_data`→（可选）活动开窗唤醒；**activity_rewards 的 16 类**（网页版 `ActivityRewardsTaskModule.java:21` 原样照搬）；`notify_general_bp_*`→`act_endless`；`notify_red_packet_user_info`→`red_packet`；`notify_achievement_info`→`achievement_claim`；`notify_chapterquests|notify_growthquests`→`chapter_quest`；`notify_backpack_init|update`→`backpack`。
   - 冷却：领取类 30–60s（网页版 30s–180s；可用每 fn 独立 cooldown，避免推送风暴）；`adventure` 独立 10 分钟（对齐 MINIMUM_CLAIM_SECONDS=600）；检查 `keeper.sessionOnline` + `keeper.executorBusy==false`（防与整轮并发）。
2. `SessionKeeper.LiveSession`：在 `tick()`/登录成功分支 `PushWakeDispatcher.attach(this)`（紧邻 `ServerHelpResponder`/`ServerShieldResponder` 创建处 `:343-347`），重连后重新 attach。
3. `RoundScheduler` 保留“每日整轮”兜底（全量补齐/配置变更），但不再是唯一触发；`srv:lastrun` 语义不变。
4. `RoundScheduler` 增加 `adventure` 到点补领（若不想一次做 Dispatcher，可先只做这一条：`RoundRunner` 已有 `supplyWakeAt` 模式，照抄读 `mod:adventure.hang_up` + `notify_adventure_pve_info` 换算时间）。

**方案 B（更彻底，2–3 天）：** 在 vault-server 内实现网页版同构的 `TaskEngine/TaskScheduler/SignalBus`（3 个类 + `TaskModule` 适配 45 个 `TaskDef`）。收益是可复用网页版全部“CD_BASED/TIME_WINDOW/PERIODIC”语义与活动日历门控，但工作量大，建议在 A 稳定后评估。

---

## 7. P0 立即修清单（按收益排序 + 复现证据）

> 复现方式统一：`ssh -o BatchMode=yes vault-vps 'python3 -c "import json;d=json.load(open(\"/opt/vault-server/data/state.json\"));print(d[\"log:48460403:888:<task>\"])"'`；或看 `summary:48460403` 与 `data/logs/vault-20261023.log:2941` 那一轮。

| 序 | P0 项 | 收益 | 复现证据 | 最小改动文件/函数 | 验收方式 |
|---|---|---|---|---|---|
| **P0-1** | activity_rewards 取数修复（名字/包装层/常量 id/参数） | **最高**：远征宝藏、自选箱、匠人宝库、许愿屋、英雄殿堂、英勇见证、月卡、英雄集结、卡 BP | 生产 `activity_rewards` = “领取2/跳过13/失败1”；登录推送含 `notify_daily_claim_info/customize_item_init/multiple_choice_pack_init/battle_pass_info/month_card_info/card_bp_info/value_exchange_init`（`vault-20261023.log:160-350`），但都被取名/形态挡掉 | 新增 `vault-server/.../task/RewardNotifies.java`（包装层解包 + Map/List 容错 + activityId 常量表 + 时间戳判定）；重写 `ServerTasks.activityRewards:863-1021`；App 同步改 `DailyTasks.java:901-1000` | 下一轮 `领取数` 从 2 升到 ≥8；`adventure` 不再报“暂无可领取”；`log:*:activity_rewards` 无“失败1” |
| **P0-2** | adventure 字段 + 节奏 | 挂机产量 **×2.67** | 生产连续多轮“当前暂无可领取的挂机奖励”（`log:48460403:888:adventure[0..2]`）；`req_get_hang_up_reward` 响应字段实为 `hang_start_time/current_reward`（state:105-116） | `ServerTasks.adventureReward:793-861`（3 个字段）；`RoundScheduler.tick` 增 adventure 补领或 §6 Dispatcher | 同一天出现多次 `adventure` CLAIMED（每 10 分钟），`detail` 有物品 |
| **P0-3** | 每日任务事件化（含活跃度宝箱） | 全量每日奖励 + 7 箱 | 00:59 轮“今日任务奖励已全部领取（活跃度 15）”（`log:...daily_mission[0]`）；当天后续完成无任务再跑 | §6 Dispatcher：`notify_dailyquests|notify_update_dailyquest*`→`daily_mission`；`ServerTasks.dailyMission` 不必大改 | 当日整轮后仍可见 `daily_mission` CLAIMED 且活跃度≥40/80… |
| **P0-4** | 科技捐献（形态/level/freeCount/exp_update） | 免费捐献 up to 10 次/日 | “科技捐献:科技数据未同步”（`log:...alliance_donate[0]`）；`notify_alliance_science_init.sciences` 是 Map（`proto_log.txt:83`）；exp_update 单日 144 条（`audit_1014/proto_log_dev_old.txt:108`） | `ServerTasks.allianceDonate:1118-1156` | 出现“科技捐献✓”且次数=freeCount |
| **P0-5** | 统帅礼包 `{lv}` + VIP 状态 | 1/日 + 去掉 ERROR | “服务端返回错误码 4503”（`log:...commander_gift[0]`）；`notify_vip_info` 已在登录推送（`proto_log.txt:97`） | `ServerTasks.commanderGift:1260-1268` + `activityRewards:871` 去重 | commander_gift 变 CLAIMED/SKIPPED（不再 ERROR） |
| **P0-6** | 邮件板块全量化（battle/message/collection）+ legacy mid_list + 逐封兜底 | 若干封/日 | 生产只报“候选 8”且默认类型仅 `{10,20,30,40,50}`（`ServerTasks.java:300-305`）；网页版 25 种类型/5 板块（`DailyMailboxRewardService.java:323-329`） | `ServerTasks.mailRewards:282-407` | 候选数上升；battle/message 板块出现领取 |
| **P0-7** | 推送调度底座（可选但推荐，串起 P0-2/3/6 与 P1） | 放大以上所有收益 | `RoundScheduler` 只有整轮+supply（`:107-188`）；App 已有 `PushWakeDriver.java:25-46` 可移植；服务器无对应 | 新增 `vault-server/.../task/PushWakeDispatcher.java`；`SessionKeeper.LiveSession.tick`（`:343-347` 附近）attach | 整轮之外能看到 `_trg=推送` 的 CLAIMED 记录 |

**依赖顺序建议**：P0-1（纯协议函数，收益最大、风险最低）→ P0-5/P0-4（小函数）→ P0-7（调度底座）→ P0-2/P0-3/P0-6（挂在底座上）。P0-7 未上之前，P0-2 可先用 `RoundScheduler` 的 supply 模式单点补领。

---

## 8. P1/P2 待办（下一批）

| 等级 | 项 | 说明 |
|---|---|---|
| P1 | `mail` 日内推送化 | 类型/兜底修完后接 §6；关注 30 天过期邮件 |
| P1 | `act_endless` 推送化 | `notify_general_bp_normal_info/extra_info/integral_update/purchase`；App `PushWakeDriver.java:40-41` 已有映射 |
| P1 | `red_packet` 推送化 | `notify_red_packet_user_info`；网页版 300s 节奏 |
| P1 | `lost-treasure` / `mia-wish-house` / `infinite-grocery` / `optional-gift-pack` / `fishing` 形态修复 | 当期未开放，但取数路径同 §3，一次修完 |
| P1 | 英雄殿堂/英勇见证并入 activity-rewards | 用 `req_value_exchange_exchange{id:2702001,idx:DAILY_INDEX[version],count:1}` 与 `req_value_exchange_free_exchange{id:2702001,idx:WITNESS_INDEX[version]}`；index 表见 `DailyActivityRewardsState.java:26-31`；**注意我方 `ServerShopLeagueTasks.snowTrade:389-514` 的 free_idx 默认 132 是错误语义（132 是 HERO_HALL 的每日档，不是免费见证档），若启用雪原贸易站要重核对** |
| P1 | 活动日历周期同步 | 网页版 `DailyActivityCalendarSyncScheduler` 常驻；我方只在轮末 `CaptureTasks.captureCalendar`（`:123+`）。用于活动开窗/结束门控与“活动开了才唤醒” |
| P1 | 卡 BP 6 项阶段奖励 | `req_card_bp_collect_reward{id,stage_id}`×1..100，进度/已领来自 `notify_card_bp_info.free_reward`；开放期一次性收益大，建议紧随 P0-1 |
| P2 | `login_rewards` 状态化 | 解析 `notify_fourteen_sign_info`，`claimAll` 失败按天 `req_fourteen_sign_get_reward{day}` |
| P2 | `achievement_claim` / `chapter_quest` 推送化 | 网页版无对应模块（App 额外功能）；`notify_achievement_info`、`notify_chapterquests/growthquests` 接 §6 即可 |
| P2 | `backpack` 解析修复 | `mapsOf(data)` 取 `{id,count}`；无网页版对照，自动开箱需产品确认 |
| P2 | `social_gift`/`bank`/`shop` | 归各自组 |

---

## 9. 附录：查阅过的关键 file:line 索引

### 9.1 网页版权威源码（`/Users/liangguansheng/ZCodeProject/wjdr-review/src-tool/com/wjdr/tool/modules/daily/`）

```
engine/TaskEngine.java:446-483,550-559            decideNext/shouldReevaluate/updateWake
engine/TaskScheduler.java:45-136                 wakeAt 精确调度
engine/TaskSignalBus.java:36-65                  PROTOCOL_NOTIFICATION 按 interestedProtocols 过滤
engine/DailyTaskEngineBridge.java:187-217        inbound notify → 信号
task/AbstractStandardTaskModule.java:65-171      decide/SleepUntil/Execute/nextDelaySeconds
model/AbstractDailyAutomationTaskDefinition.java:37-84  各任务 interval（activity 180s / supply 60s / adventure 3600s+CD / daily-mission 120s / alliance-chests 120s / login 300s / red-packets 300s / endless 180s）
task/activityrewards/ActivityRewardsTaskModule.java:21   16 类触发协议
service/DailyActivityRewardsService.java:161-206,219-239,241-316,325-362,368-396,398-411   claimKeys/claimOne/cardBP/heroRally/welfare/idPayload
service/DailyActivityRewardsState.java:22-33(常量),51-225(accept 全表),302-336(index 表),394-447(science?),652-669,725-745(cardBP),756-762(lost),764-778(valueExchange),790-811(heroRally),897-910(dailyClaim),912-920(optionalGift),922-930(dailySpecial),932-1003(welfareCard),1032-1048(infinite),1050-1058(customize),1060-1080(fishing),1082-1137(timestamp/refresh)
service/DailyActivityRewardKey.java:11-34,72-79  24 key + id 集合
service/DailyAdventureRewardService.java:76-88,154-195  preview/claim/4004 重试/对账
service/DailyAdventureRewardState.java:18-19,37-137    字段解析
task/adventurerewards/AdventureRewardsTaskModule.java:21
service/DailyMailboxRewardService.java:67-120,131-165,187-300,323-329  状态/分页/批量/逐封/5 板块 25 类型
service/DailySupplyService.java:44,316-388       BEFORE_EXPIRY(30min)/nextAutomaticEvaluationAt
service/DailyCommanderGiftService.java:40-41,140-200,202-292  claim/{lv}/renew/4502/4503
config/DailyCommanderGiftConfig.java:9-12        autoRenewEnabled 默认 false
config/DailyActivityRewardsConfig.java:16-20     defaults 全关
service/DailyLoginRewardService.java:33-34,96-135  claim all/day
task/loginrewards/LoginRewardsTaskModule.java:22
service/DailyAllianceDonationService.java:500-556,533   freeCount 循环/level+1
service/DailyAllianceDonationState.java:52-145,177-218,394-420,443-447,463-500  science/recovery/help
task/alliancedonation/AllianceDonationTaskModule.java:触发协议 18 类
service/DailyActivityCalendarService.java:50-105  req_activity_calendar_get_data/态
service/DailyActivityCalendarState.java:37-140    日历 parse/family
service/DailyActivityCalendarSyncScheduler.java:60-120  周期同步
service/DailyEndlessJourneyService.java:66-84
service/DailyRedPacketService.java:99,247,336
engine/TaskModuleRegistry.java:28                网页版 47 个模块 id（无 achievement/chapter/backpack）
```

### 9.2 我方实现（`/Users/liangguansheng/ZCodeProject/VAContainer/`）

```
vault-server/src/main/java/com/carlos/vaultserver/task/ServerTasks.java
  :72-136 任务目录；:147-233 loginRewards；:241-280 achievementClaim；:282-407 mailRewards（:300-305 类型表）；
  :409-507 supplyRewards；:509-616 dailyMission（:624-711 辅助）；:715-791 chapterQuest；
  :793-861 adventureReward；:863-1021 activityRewards（:868-877 noArg / :878-900 byId / :901-909 战令 /
  :910-922 英勇见证 / :923-1000 曙光 duck_market / :1023-1055 tryClaim）；
  :1057-1109 allianceChest；:1111-1213 allianceDonate（:1118-1156 科学 / :1159-1181 dev / :1182-1199 rank）；
  :1216-1258 freeShield；:1260-1268 commanderGift；:1319-1336 classifySimple；:1350-1393 classifyServerCode
vault-server/.../task/ServerActivityTasks.java:342-371(fishing),373-405(endlessJourney),466-504(redPacket)
vault-server/.../task/ServerShopLeagueTasks.java:389-514(snowTrade/value_exchange),760-808(backpack)
vault-server/.../task/RoundScheduler.java:24,107-188        调度（整轮/补给）
vault-server/.../task/RoundRunner.java:29-202             旧整轮 + supplyWakeAt:194-202
vault-server/.../task/ModularRoundRunner.java:53-149      模块化整轮（captureAll:129-134，srv:lastrun:143-145）
vault-server/.../task/Modules.java:61-73                  reward 域模块
vault-server/.../task/TaskContext.java:44-88              subOn/strategy/modState/saveModText
vault-server/.../task/Exec.java:65-83                     mapsOf/kvPairs（形态解析根源）
vault-server/.../task/ClaimLedger.java                    按天台账
vault-server/.../CaptureTasks.java:29,35-62,66-119,123-215,322-360  采集（calendar/quest/supply）
vault-server/.../SessionKeeper.java:221-250(LiveSession),343-347(help/shield),393-399(addMessageHook),
  1372-1421(submitRound),executorBusy/sessionOnline/sessionSid
vault-server/.../ServerHelpResponder.java / ServerShieldResponder.java   反应式子任务先例
app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:651-682(hook),685-691(collectNotifies),
  694-712(notifyContent/notifyData),715-736(extractId),971-973(addMessageHook)
app/src/main/java/com/carlos/vault/daily/DailyTasks.java:818-899(adventureReward),901-1000(activityRewards)
app/src/main/java/com/carlos/vault/daily/PushWakeDriver.java:22-164（App 已有推送唤醒，WAKE_MAP:25-46 缺奖励类）
app/src/main/java/com/carlos/vault/daily/FnRegistry.java:41-50（奖励栏 10 项）
```

### 9.3 抓包/生产证据

```
tools/bundle_re/proto_log.txt:31(daily_bundle)、:36(backpack)、:53/247/811(battle_pass)、:68(fourteen_sign)、
  :76/269(value_exchange)、:83/839(science_init)、:93(hero_story)、:95(achievement)、:97(vip)、
  :98(daily_claim)、:110(adventure_pve)、:146(sevensign)、:150(reward_info)、:154/347/718(month_card)、
  :158(customize_item)、:176/740(card_bp)、:184(fishing_activity)、:683(multiple_choice_pack)
tools/bundle_re/audit_1014/proto_log_dev_old.txt:108/117/179(science_exp_update)
生产 state.json：summary:48460403、log:48460403:888:「login_rewards/mail/supply/daily_mission/chapter_quest/
  adventure/activity_rewards/commander_gift/achievement_claim/act_endless/red_packet/backpack/alliance_chest/
  alliance_donate」[0]、mod:48460403:888:activity_rewards:dawn_market、srv:lastrun:48460403、
  srv:supplyfire:48460403、todo:48460403:888（活动日历 28 条，2435002/2401010 在开）
生产 data/vault.json：accounts[0].windowStart=5/windowEnd=9/auto=true
生产 data/logs/vault-20261023.log:349(登录成功 48460403)、:160-350(登录 167 协议)、:558(16:44 science_init)、
  :2941(16:59:37Z 整轮完成)；vault-20261022.log 同类
```

---

### 附：本报告的边界与 TODO

- **未动**：任何源码/配置/文档；未 git 操作；未跑 gradle；未重启服务；未使用 zcode。
- **未能 100% 实证的两点**（高置信但需真机抓包复核）：①`req_get_hang_up_reward` 成功响应的确切字段（依据网页版 state 解析推断为 `hang_start_time/current_reward`）；②`req_hero_story_claim_reward` 是否为真实游戏协议（网页版源码 0 命中，疑为 App 猜测；建议直接在真机协议监听里验证或改用 value_exchange 方案）。其余结论均有源码 file:line 或抓包/生产日志直接证据。
- **留给下一批**：§3 通用取数实现后，把 `lost-treasure / mia-wish-house / infinite-grocery / optional-gift-pack / fishing / dawn-wisdom / 3x-custom` 一次性补齐；活动日历门控与 §6 方案 B 的完整 TaskEngine 同构评估。
