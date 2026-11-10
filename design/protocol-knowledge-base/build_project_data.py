#!/usr/bin/env python3
"""从当前 App / Server 源码生成项目协议索引；实证状态只来自人工维护的证据表。"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).with_name("project-data.js")
SOURCE_ROOTS = [ROOT / "app/src/main/java", ROOT / "vault-server/src/main/java"]
PROTO = re.compile(r"[\"']((?:req|notify)_[a-z0-9_]+)[\"']")

# 证据必须来自项目真机、生产日志或专项 QA；代码存在和单测通过本身不会进入此表。
VERIFIED = {
    "req_role_list": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §2/§3", "真实账号登录发现 5 个角色，角色切换与身份校验链路通过。"),
    "req_loaddata": ("qa/服务器模式第一轮端到端验证-vault-server.md §2", "服务器登录成功并进入心跳与初始通知采集。"),
    "req_players_base_info": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §3/§5", "返回角色名、等级、战力、联盟，与游戏内 207,239,112 战力等数据一致。"),
    "req_heartbeat": ("qa/服务器模式第一轮端到端验证-vault-server.md §2", "服务器常驻日志 heartbeats=20/100，断线让位后可恢复在线。"),
    "notify_game_init": ("design/协议盘点-登录全量推送242协议与功能对标-20261012.md E", "登录快照实证 servertime/zero_time，项目用于服务器时钟。"),
    "notify_base_info": ("design/协议盘点-登录全量推送242协议与功能对标-20261012.md D5", "双角色登录快照互证，RoleStore.syncRoleMeta 已落地。"),
    "notify_alliance_init": ("qa/验证报告-20261024-真机与生产验收.md §15", "alliance.abbr 回填并在真机角色列表显示 888-cch-昵称。"),
    "notify_dailyquests": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §6", "quests 浮点字符串键与 finishs.isgetaward 已抓取；44 个动态任务 id 替代旧 21 个静态 id。"),
    "req_get_all_dailyquest_award": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §6", "实测响应仅 code/integral/geted_boxid/expert_effect_map；4302 表示全部已领，不以积分变化冒充到账。"),
    "notify_recover_resource_init": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "普通/体力补给状态与下一窗口在整轮及游戏内对照有效。"),
    "req_receive_online_reward": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "普通补给实测领取物品 200401×5，重复执行为本窗口已领取。"),
    "req_receive_spec_reward": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "体力补给窗口状态实测，重复执行为本窗口已领取。"),
    "req_hero_search_times": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "高级招募与史诗招募均获得真实执行反馈。"),
    "req_use_expert_skill": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "忠诚令牌与每日士兵在真机整轮中执行成功。"),
    "req_travel_info": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "专家游历状态读取与连续挂机流程已在真机整轮验证。"),
    "req_travel_recv_energy": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "行动力已领取，并继续完成挂机游历。"),
    "req_normal_travel_afk": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4/§5", "真机游戏弹出游历结束奖励，与服务器执行结果一致。"),
    "req_homeland_take_life_tree_coin": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "晨曦岛生命之树真实领取成功。"),
    "req_general_bp_get_all_reward": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4", "无尽征程里程碑实测领取物品 570202×5。"),
    "req_alliance_trap_info": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4/§5", "巨熊预约时间与游戏顶部通知 2026-10-19 21:00 一致。"),
    "req_city_item_free_use": ("qa/测试报告-20261018-统一自动化与48460403真机验证.md §4/§5", "8 小时免费盾开启后游戏内剩余 07:55:01。"),
    "req_world_map_search": ("qa/验证报告-20261024-真机与生产验收.md §7-§10", "生产按资源与等级搜索矿点，后续真实派出采集队。"),
    "req_world_march": ("qa/验证报告-20261024-真机与生产验收.md §7-§10", "生产 type=4 采集达到 3/3 在途；hero_id k=位次/v=英雄，soldier k=兵种/v=数量；301/401/403/406/415 已有处理证据。"),
    "notify_player_march_add": ("qa/验证报告-20261024-真机与生产验收.md §7-§10", "真实出征后资产库 queue occupied/idle 与 byType 同步，三路采集在途。"),
    "notify_player_march_del": ("qa/验证报告-20261024-真机与生产验收.md §7-§10", "行军结束释放队列占用，队列资产库与生产在途状态联动。"),
    "notify_hero_init": ("qa/验证报告-20261024-真机与生产验收.md §4/§9", "生产实测英雄等级字段为 lev；62 名职业目录补齐后按盾/矛/弓各选一名。"),
    "notify_soldier_init": ("qa/验证报告-20261024-真机与生产验收.md §7-§10", "士兵库存用于真实编队；prefab 细字段仍需单独复核，不能由本条概括为全部已验证。"),
    "notify_soldier_update": ("qa/验证报告-20261024-真机与生产验收.md §9-§10", "add/del 增量同步库存，修复旧快照导致 401；生产三队列满负荷。"),
    "notify_soldier_formation_update": ("design/守护盟友-协议实证-20261015.md §2.7-§2.8", "守城及驻防参战后真实推送到达，含 minor_wound。"),
    "notify_world_situation_info_add": ("design/守护盟友-协议实证-20261015.md §2.1-§2.5", "真机样本实证侦查 type=1、进攻 type=2、驻防 type=5，含 owner/endtime/target_type。"),
    "notify_world_situation_info_up": ("design/守护盟友-协议实证-20261015.md §1", "敌行军加速后 endtime 前移实时可见，事件间隔约 0.5-1 秒。"),
    "notify_world_alliance_situation_add": ("design/守护盟友-协议实证-20261015.md §2.2", "to_marchs 携带进攻与守方城信息，可提取目标 uid 与兵力。"),
    "notify_world_alliance_situation_update": ("design/守护盟友-协议实证-20261015.md §1", "联盟视野内敌加速推送实证，可直接更新 ETA。"),
    "notify_world_alliance_situation_del": ("design/守护盟友-协议实证-20261015.md §2.3", "endtime 距当前较远为召回、约等于当前为到达，已有成对样本。"),
    "notify_world_alliance_situation_init": ("design/守护盟友-协议实证-20261015.md §3", "断线重连后补发全量态势，用于恢复监视视野。"),
    "notify_embassy_world_march_add": ("design/守护盟友-协议实证-20261015.md §2.5", "驻防落地后城主接收使馆登记，含 soldier_map、troop_limit。"),
    "notify_embassy_world_march_del": ("design/守护盟友-协议实证-20261015.md §2.6", "高级迁城时驻防他城部队瞬时召回，使馆登记删除。"),
    "notify_alliance_member_pos_update": ("design/守护盟友-协议实证-20261015.md §2.6", "迁城后真实坐标更新，与使馆撤防级联同帧到达。"),
    "req_world_march_detail_4_player_situation": ("design/守护盟友-协议实证-20261015.md §3", "断线后全量重建态势可行，已用于守护引擎补采。"),
    "req_world_march_speedup": ("design/守护盟友-协议实证-20261015.md §1", "参数 {id:int,itemid:int,owner:string}；响应 code/endtime；301900=25%、301901=50%；owner 数字或 state=3 可返 2807。"),
    "notify_new_mail_syn": ("design/守护盟友-协议实证-20261015.md §2.7-§2.8", "守城与驻防参战均收到 first_type=30 战报邮件。"),
    "notify_hospital_wound_soldiers": ("design/守护盟友-协议实证-20261015.md §2.7", "守城结算后伤兵入库，含兵种与数量。"),
    "notify_player_power_update": ("design/守护盟友-协议实证-20261015.md §2.7", "守城结算级联中总战力下降推送已采集。"),
    "req_shop_quick_buy": ("qa/验证报告-20261024-真机与生产验收.md §11", "生产曾真实消耗 250 钻购买 8 小时加速；钻石购买默认关闭且有日预算，不能当作无副作用查询。"),
    "req_alliance_help_all": ("qa/服务器模式P2-数据采集与互助迁移验证.md", "服务器互助链路已有专项验证；批量帮助后仍以游戏状态为准。"),
    "notify_alliance_help_init": ("design/协议盘点-登录全量推送242协议与功能对标-20261012.md A", "登录快照提供互助初始列表，项目常驻响应器使用。"),
    "notify_alliance_help_update": ("design/协议盘点-登录全量推送242协议与功能对标-20261012.md A/D", "在线互助增量事件已采集，可驱动 req_alliance_help_all。"),
}

SPECIAL = {
    "req_world_march": {
        "parameters": "当前实证：type=4 采集；endpoint={x,y}；formation.hero_id 为 pair_int_int，k=上阵位次、v=英雄 id；soldier k=兵种 id、v=数量。",
        "limitations": "301 英雄不在城、401 士兵不足、403 英雄占用、406 兵力超限、415 上阵位超限。不可只凭 code=0，须核对 march id 与在途推送。",
        "dataScope": "当前角色向资源点、玩家或活动目标发起的一支行军及其 march id。"
    },
    "req_world_march_speedup": {
        "parameters": "id=行军号(int)，itemid=301900(25%)或301901(50%)，owner=行军所有者 uid 的字符串。",
        "limitations": "owner 用数字会返 2807；state=3 已到达/采集中不可加速；目标应为 state=2/4。钻石加速参数仍待实证。",
        "dataScope": "单个在途行军的新 endtime 与加速执行结果。"
    },
    "req_get_all_dailyquest_award": {
        "parameters": "quest id 列表应来自 notify_dailyquests.quests 动态 key，按 double 解析；不要只使用旧静态 21 项。",
        "limitations": "4302/4303 需结合 finishs 未领数判断；integral 单独变化不是到账证据；响应没有 awards/rewards。",
        "dataScope": "当前角色每日任务领奖状态、积分与积分宝箱领取信息。"
    },
    "req_shop_quick_buy": {
        "parameters": "项目用于加速道具快捷购买；8h=250 钻、24h=600 钻为当前实现口径。",
        "limitations": "真实消耗钻石。必须显式开启购买开关并执行日预算；默认关闭。",
        "dataScope": "指定商品的快捷购买结果与对应资源消耗。"
    },
    "req_pet_use_skill": {
        "limitations": "生产仅获得 11542（技能未拥有/未解锁/冷却中）反馈，尚未取得 550031 成功激活证据，故保留待实证。"
    },
    "req_soldier_set_prefab": {
        "limitations": "编队记忆恢复目前只有代码与单测；真实角色逐槽恢复反馈未完成，不能标记已实证。"
    },
    "notify_soldier_init": {
        "limitations": "士兵库存与编队读取已使用；prefab 的真实生产帧字段仍建议在账号恢复后开一帧日志复核。"
    },
    "req_use_item": {
        "limitations": "采集加速因生产背包无 302000/302001，道具使用成功链路未触发，保留待实证。"
    },
    "req_world_march_recall": {
        "limitations": "早期 {id} 样本 code=2 时行军已落地；须取明确在途行军提前召回并核对 player_march_del。"
    }
}

CANDIDATES = [
    ("req_get_daily_bundle", "每日免费礼包", "由 notify_daily_bundle_info 按 notify_X ↔ req_get_X 命名规约推断；需对比 req_daily_bundle_free 候选。"),
    ("req_daily_bundle_free", "每日免费礼包", "每日免费礼包备选请求名，与 req_get_daily_bundle 二选一实测。"),
    ("req_get_achievement_award", "成就奖励", "由 notify_achievement_info 的 complete/received 字段推断领奖动作。"),
    ("req_get_daily_claim", "史诗与远方候选", "由 notify_daily_claim_info 推断；需在游戏内手动领取并抓包确认。"),
    ("req_get_hero_story_award", "英雄故事奖励", "由 notify_hero_story_info.reward_stat 推断；与 daily_claim 候选需区分。"),
    ("req_seven_sign", "7 天签到", "由 notify_sevensign_info 推断签到动作。"),
    ("req_sign_21day", "21 天签到", "由 notify_sign_21day_info 推断签到动作。"),
    ("req_get_lost_treasure_award", "失落宝藏", "由 notify_lost_treasure_info 的每日奖励与累计大奖字段推断。"),
    ("req_get_online_reward", "在线时长奖励", "由 notify_online_reward_timeup 到点事件推断。"),
    ("req_get_personal_7days_award", "个人 7 日活动", "由 notify_personal_7days_init 的任务与积分奖励字段推断。"),
]

RULES = [
    ("alliance_help", "联盟", "联盟互助"), ("alliance_chest", "联盟", "联盟宝箱"),
    ("alliance_science", "联盟", "联盟捐献"), ("alliance_dev", "联盟", "联盟发展"),
    ("alliance_showdown", "联盟", "联盟争霸赛"), ("mobilization", "联盟", "联盟总动员"),
    ("alliance_trap", "活动", "巨熊行动"), ("mass_march", "活动", "集结与巨熊"),
    ("world_alliance_situation", "野外", "战争态势"), ("world_situation", "野外", "战争态势"),
    ("embassy", "联盟", "使馆与驻防"), ("guardian", "联盟", "守护盟友"),
    ("dailyquest", "奖励", "每日任务"), ("chapterquest", "奖励", "章节任务"),
    ("growthquest", "奖励", "成长任务"), ("achievement", "奖励", "成就奖励"),
    ("fourteen_sign", "奖励", "登录好礼"), ("sevensign", "奖励", "7 天签到"),
    ("sign_21day", "奖励", "21 天签到"), ("mail", "奖励", "邮件奖励"),
    ("recover_resource", "奖励", "仓库补给"), ("online_reward", "奖励", "在线奖励"),
    ("month_card", "奖励", "月卡奖励"), ("battle_pass", "奖励", "战令奖励"),
    ("general_bp", "奖励", "无尽征程"), ("card_bp", "奖励", "征程里程碑"),
    ("daily_bundle", "奖励", "每日免费礼包"), ("daily_claim", "奖励", "每日领取"),
    ("lost_treasure", "奖励", "失落宝藏"), ("reward", "奖励", "通用奖励"),
    ("hospital", "城镇", "军医所"), ("barrack", "城镇", "兵营训练"),
    ("research", "城镇", "科技研究"), ("science", "城镇", "科技研究"),
    ("hero_search", "城镇", "英雄招募"), ("recruitment", "城镇", "英雄招募"),
    ("expert", "城镇", "专家与游历"), ("travel", "城镇", "专家游历"),
    ("pet", "城镇", "宠物"), ("homeland", "城镇", "晨曦岛"),
    ("firecrystal", "城镇", "火晶提炼"), ("spar", "城镇", "火晶与淬炎"),
    ("climbtower", "城镇", "地心探索"), ("arena", "城镇", "竞技场"),
    ("shelter", "城镇", "避难所"), ("survivor", "城镇", "幸存者"),
    ("building", "城镇", "建筑升级"), ("furniture", "城镇", "家具升级"),
    ("soldier", "城镇", "编队与士兵"), ("hero", "城镇", "英雄与编队"),
    ("queue", "城镇", "队列管理"), ("balloon", "活动", "正中靶心"),
    ("pitch_pot", "活动", "壶纳千祥"), ("snow", "活动", "除雪活动"),
    ("frozen_soil", "活动", "冰封宝藏"), ("icebound", "活动", "冰封宝藏"),
    ("polar_expedition", "活动", "逐光之旅"), ("brawl_war", "活动", "欢乐乱斗"),
    ("fishing", "活动", "钓鱼活动"), ("monster_siege", "活动", "怪攻守城"),
    ("supremacy", "活动", "至尊盛宴"), ("mercenary", "活动", "佣兵荣耀"),
    ("truck_escort", "活动", "运宝车队"), ("icefield_hunter", "活动", "冰原猎人"),
    ("red_packet", "活动", "游戏红包"), ("bank", "活动", "钻石银行"),
    ("frostfire_war", "活动", "燃霜矿区"), ("activity_calendar", "活动", "活动日历"),
    ("personal_7days", "活动", "个人 7 日活动"), ("world_map", "野外", "世界地图"),
    ("world_march", "野外", "行军队列"), ("player_march", "野外", "行军队列"),
    ("world_resource", "野外", "野外采集"), ("own_city_field", "野外", "野外采集"),
    ("intelligence", "野外", "情报任务"), ("adventure_pve", "野外", "探险速刷"),
    ("auto_monster", "野外", "巨兽狩猎"), ("alliance_activity_auto_join", "野外", "自动集结"),
    ("town_interaction", "野外", "战斗护盾"), ("city_item", "野外", "战斗护盾"),
    ("shop", "商店", "商店购买"), ("value_exchange", "商店", "兑换所"),
    ("social_gift", "商店", "社交礼物"), ("backpack", "商店", "背包道具"),
    ("use_item", "商店", "背包道具"), ("batch_use_item", "商店", "背包道具"),
    ("vip", "奖励", "统帅礼包"), ("role_list", "系统", "角色管理"),
    ("players_base_info", "系统", "角色资料"), ("player_modify_name", "系统", "角色管理"),
    ("loaddata", "系统", "游戏登录"), ("heartbeat", "系统", "会话保活"),
    ("server_list", "系统", "区服列表"), ("settings", "系统", "会话设置"),
    ("dun163", "系统", "登录风控"), ("game_init", "系统", "游戏登录"),
    ("base_info", "系统", "角色资料"), ("alliance_init", "系统", "联盟资料"),
    ("resource", "系统", "资源资产"), ("buff_show", "系统", "增益状态"),
    ("schedule", "系统", "服务器时钟"), ("logout", "系统", "会话退出"),
    ("chat", "系统", "聊天社交"), ("friend", "系统", "好友社交"),
    ("recharge", "系统", "充值记录"), ("migrant", "独立工具", "跨服移民"),
    ("rank", "独立工具", "排行榜"), ("move_city", "独立工具", "迁城"),
]


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//.*", "", text)


def classify(name):
    body = name.removeprefix("req_").removeprefix("notify_")
    for key, category, feature in RULES:
        if key in body:
            return category, feature
    return "系统", "其他游戏能力"


def source_index():
    result = {}
    for source_root in SOURCE_ROOTS:
        for path in source_root.rglob("*.java"):
            text = strip_comments(path.read_text(errors="ignore"))
            for match in PROTO.finditer(text):
                name = match.group(1)
                if name.endswith("_") or name in {"req_x", "req_y", "notify_z"}:
                    continue
                result.setdefault(name, set()).add(str(path.relative_to(ROOT)))
    return result


def make_record(name, paths, number, status=None, candidate_note=None):
    category, feature = classify(name)
    direction = "推送" if name.startswith("notify_") else "请求"
    status = status or ("命名推测" if candidate_note else "待实证")
    verified = name in VERIFIED and not candidate_note
    if verified:
        status = "已实证"
    evidence, evidence_note = VERIFIED.get(name, (None, None))
    if candidate_note:
        source = "命名规约推断"
        evidence = "design/协议盘点-登录全量推送242协议与功能对标-20261012.md B"
        evidence_note = candidate_note
        paths = set()
    elif verified:
        source = "项目真机与生产实测"
    else:
        source = "当前项目代码索引"
        evidence = "当前 App / vault-server 源码调用点：" + "、".join(sorted(paths)[:4])
        evidence_note = "仅确认项目已引用或接入；未找到明确的成功反馈，需按验证步骤补测。"
    path_text = "、".join(sorted(paths)[:4]) if paths else "尚无项目调用点"
    summary = (f"项目{feature}使用的{direction}协议，{evidence_note}" if verified else
               candidate_note or f"当前项目{feature}链路已引用的{direction}协议，等待补充明确有效反馈。")
    if direction == "推送":
        steps = ["在登录或事件监听挂载后捕获该协议，保留角色 uid、时间与原始载荷",
                 "对照当前调用点核对字段形状，缺失字段不得按默认值假装成功",
                 "将状态按 fpid/sid/uid 隔离后更新业务仓库",
                 "用一次真实业务变化确认增量或全量同步结果"]
        method = "被动监听"
    else:
        steps = [f"从调用点复核参数构造：{path_text}",
                 "在有效角色会话与活动窗口内执行一次受控请求",
                 "记录完整 code、响应内容和相关错误码，不以 code=0 单独判成功",
                 "通过后续 notify、游戏内状态或资产变化确认实际效果"]
        method = "游戏 TCP sproto"
    record = {
        "id": f"P{number:04d}", "name": name, "summary": summary,
        "category": category, "direction": direction, "feature": feature,
        "status": status, "source": source,
        "acquisition": ("项目真机/生产 QA：" + evidence if verified else
                        "命名规约候选，必须打开对应游戏界面手动操作并用 ProtoLogger 抓包" if candidate_note else
                        "静态索引当前项目真实调用点；" + path_text),
        "implementationSteps": steps, "method": method,
        "parameters": "优先复用当前调用点参数构造；本索引未自动推断字段类型，修改前须核对 schema 或真实帧。",
        "auth": "需要有效游戏角色会话；按 fpid/sid/uid 隔离，活动协议还需处于开放窗口。",
        "dataScope": f"当前角色{feature}相关的请求响应或推送状态，具体字段以实测样本为准。",
        "limitations": evidence_note or "待补充成功响应、业务效果、频控与重复执行边界。",
        "evidence": (evidence + " — " + evidence_note) if evidence_note else evidence,
        "verifiedAt": "2026-10-25" if verified else "",
        "tags": ["当前项目", "已实证" if verified else "待实证", feature] if not candidate_note else ["待实证", "命名推测", feature],
        "related": []
    }
    record.update(SPECIAL.get(name, {}))
    return record


def main():
    seed_text = (ROOT / "design/protocol-knowledge-base/data.js").read_text()
    existing = set(re.findall(r'\bname:\s*"((?:req|notify)_[a-z0-9_]+)"', seed_text))
    index = source_index()
    records = []
    overrides = {}
    number = 1001
    for name, paths in sorted(index.items()):
        record = make_record(name, paths, number)
        number += 1
        if name in existing:
            if name in VERIFIED:
                overrides[name] = {k: record[k] for k in ["status", "source", "acquisition", "evidence", "verifiedAt", "limitations"]}
            continue
        records.append(record)
    for name, feature, note in CANDIDATES:
        if name not in index and name not in existing:
            records.append(make_record(name, set(), number, candidate_note=note))
            number += 1
    payload = ("/* 由 build_project_data.py 生成；实证状态只在脚本 VERIFIED 表中人工维护。 */\n"
               "window.PROJECT_PROTOCOL_SEED = " + json.dumps(records, ensure_ascii=False, indent=2) + ";\n"
               "window.PROJECT_PROTOCOL_OVERRIDES = " + json.dumps(overrides, ensure_ascii=False, indent=2) + ";\n")
    OUT.write_text(payload)
    counts = {}
    for item in records:
        counts[item["status"]] = counts.get(item["status"], 0) + 1
    print(json.dumps({"sourceProtocols": len(index), "newRecords": len(records), "overrides": len(overrides), "status": counts, "output": str(OUT)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
