#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最严格对标审计：真机登录快照 vs 号库功能实现（2026-10-14）。
方法：最新快照按角色提取领取类协议状态 → 按号库代码同款判定条件评估"可领" →
与 daily_logs 任务结果对照 → 裁定 有效/无效/无法判定。"""
import json, re, sys
from datetime import datetime

BASE = "/Users/liangguansheng/ZCodeProject/VAContainer/tools/bundle_re/audit_1014"

# ---------- 1. 解析 proto_log 快照 ----------
def parse_snapshots(path):
    snaps = []  # {time, fpid, sid, protos:{name:json}}
    cur = None
    hdr = re.compile(r'^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (\S+) ---- SNAPSHOT-(LOGIN|) begin \((\d+) protos\)')
    ent = re.compile(r'^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (\S+):(\S+) SNAPSHOT[-A-Z]*:(\S+) (\{.*)$')
    for line in open(path, encoding='utf-8', errors='replace'):
        m = hdr.match(line)
        if m:
            cur = {'time': m.group(1), 'fpid': m.group(2).split(':')[0],
                   'sid': m.group(2).split(':')[1], 'protos': {}}
            snaps.append(cur); continue
        m = ent.match(line)
        if m and cur is not None:
            name, js = m.group(4), m.group(5)
            try:
                cur['protos'][name] = json.loads(js)
            except Exception:
                cur['protos'][name] = None
    return snaps

snaps = []
for f in ['proto_log_dev_old.txt', 'proto_log_dev.txt']:
    snaps += parse_snapshots(f'{BASE}/{f}')

def latest_by_sid():
    out = {}
    for s in snaps:
        out[s['sid']] = s          # 越晚越新（文件按序解析）
    return out

last = latest_by_sid()
print(f"快照总数 {len(snaps)}；各角色最新：")
for sid, s in last.items():
    print(f"  sid={sid}  {s['time']}  {len(s['protos'])} protos")

def ts2str(ts):
    try:
        return datetime.fromtimestamp(int(ts)).strftime('%m-%d %H:%M')
    except Exception:
        return str(ts)

DAY = 86400
now = 1792100000  # 10-14 深夜审计时刻锚点

# ---------- 2. 逐协议评估（判定条件 = 号库 Java 代码同款） ----------
def audit_sid(sid):
    s = last[sid]
    P = s['protos']
    R = []   # (协议, 号库功能, 快照判定, 证据)
    def add(proto, fn, verdict, ev): R.append((proto, fn, verdict, ev))

    # 14天登录好礼（老功能：无条件一键领，服务端跳过已领）
    j = P.get('notify_fourteen_sign_info')
    if j:
        rec = j.get('free_award_record') or []
        days = j.get('sign_days') or 0
        uncl = [d for d in range(1, days+1) if d not in rec]
        add('fourteen_sign', 'login_rewards',
            '无可领' if not uncl else f'❗可领{uncl}',
            f"sign_days={days} free_record={rec} last_sign={ts2str(j.get('last_sign_ts',0))}")

    # 7天签到（新功能 req_seven_sign_one_click_award，10-14 快照）
    j = P.get('notify_sevensign_info')
    if j:
        got = j.get('get_award_info') or []
        days = j.get('sign_days') or 0
        stale = int(j.get('lastsign_time', 0)) < now - 30*DAY
        uncl = [d for d in range(1, days+1) if d not in got]
        add('sevensign', 'login_rewards(新)',
            '无可领' if not uncl else f'❗可领{uncl}',
            f"sign_days={days} got={got} last_sign={ts2str(j.get('lastsign_time',0))}{';数据陈旧(30天前)' if stale else ''}")

    # 月卡（validity_period>0 才有每日奖励）
    j = P.get('notify_month_card_info')
    if j:
        act = [c for c in (j.get('cardList') or []) if int(c.get('validity_period', 0)) > 0]
        add('month_card', 'activity_rewards',
            '无在期卡' if not act else f'❗在期卡{len(act)}张待核',
            f"在期={[c['card_type'] for c in act] if act else '[]'}")

    # VIP 每日经验/礼包（get_*_ts 当天即已领）
    j = P.get('notify_vip_info')
    if j:
        exp_ts, gift_ts = int(j.get('get_daily_exp_ts', 0)), int(j.get('get_daily_gift_ts', 0))
        unc = j.get('uncollected_daily_gift') or {}
        today_exp = exp_ts > now - DAY
        today_gift = gift_ts > now - DAY
        v = ('✅今日已领' if today_exp and today_gift and not unc
             else f'❗exp_今日={today_exp} gift_今日={today_gift} uncollected={bool(unc)}')
        add('vip_daily', 'activity_rewards/commander_gift', v,
            f"exp_ts={ts2str(exp_ts)} gift_ts={ts2str(gift_ts)} uncollected_keys={list(unc.keys())[:3]}")

    # 每日特惠礼包 free_gift
    j = P.get('notify_daily_bundle_info')
    if j:
        fg = str(j.get('free_gift'))
        add('daily_bundle', 'activity_rewards',
            '语义存疑' if fg == 'true' else '无可领',
            f"free_gift={fg} reset_ts={ts2str((j.get('info') or {}).get('reset_ts', 0))}")

    # 成就（新功能：complete=true 且 received!=true）
    j = P.get('notify_achievement_info')
    if j:
        infos = j.get('infos') or []
        if isinstance(infos, dict): infos = list(infos.values())
        uncl = [a for a in infos
                if str(a.get('complete')) == 'true' and str(a.get('received')) != 'true']
        add('achievement', 'achievement_claim(新)',
            '✅无可领' if not uncl else f'❗可领{len(uncl)}个',
            f"总数{len(infos)} complete未领={[a.get('id') for a in uncl][:8]}")

    # 邮件附件（mail_type_info_syn 若带列表）
    j = P.get('notify_mail_type_info_syn')
    if j:
        att = 0; total = 0
        def walk(o):
            nonlocal att, total
            if isinstance(o, dict):
                if 'attach_statue' in o:
                    total += 1
                    if int(o.get('attach_statue', 0)) == 1: att += 1
                for v in o.values(): walk(v)
            elif isinstance(o, list):
                for v in o: walk(v)
        walk(j)
        add('mail', 'mail', '无可领' if att == 0 else f'❗附件未领{att}封',
            f'邮件总数{total}')

    # 兑换类（雪原贸易站已结束，验证 v 状态）
    j = P.get('notify_value_exchange_init')
    add('value_exchange', 'act_snowtrade(新)', '活动已结束' if not j else '开放中',
        've_info=' + (str((j or {}).get('ve_info', {}))[:80] if j else '无推送'))

    # 联盟宝箱
    j = P.get('notify_alliance_chest_init')
    if j:
        s_ = json.dumps(j)[:200]
        add('alliance_chest', 'alliance_chest', '形状待核', s_[:120])

    return R

# ---------- 3. daily_logs 解析 ----------
import html
xml = open(f'{BASE}/daily_logs.xml', encoding='utf-8', errors='replace').read()
logs = {}   # key fpid:sid:fn -> entries
for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', xml, re.S):
    key, val = m.group(1), html.unescape(m.group(2))
    try:
        arr = json.loads(val)
        logs[key] = arr if isinstance(arr, list) else []
    except Exception:
        logs[key] = []
print(f"\ndaily_logs 键数 {len(logs)}")

print("\n" + "="*100)
for sid in sorted(last):
    s = last[sid]
    print(f"\n### 角色 sid={sid}（快照 {s['time']}）")
    print(f"{'协议':<20}{'号库功能':<32}{'快照判定':<18}证据")
    for proto, fn, verdict, ev in audit_sid(sid):
        print(f"{proto:<20}{fn:<32}{verdict:<18}{ev}")
    # 该角色今天的任务日志
    print(f"--- 今日任务日志（{sid}） ---")
    today = datetime.now().strftime('%m-%d')
    for key, arr in sorted(logs.items()):
        parts = key.split(':')
        if len(parts) >= 3 and parts[1] == sid and arr:
            e = arr[-1]
            try:
                ts = int(float(e.get('ts', 0)))
            except Exception:
                continue
            if ts > 4102444800: ts //= 1000
            if ts < 1000000000: continue
            t = datetime.fromtimestamp(ts).strftime('%m-%d %H:%M')
            if ts > now - 2*DAY:
                print(f"  {parts[2]:<22} {t} {e.get('category','?'):<10} {str(e.get('reason',''))[:70]}")
