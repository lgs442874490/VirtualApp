#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""D 段（真实迁移）观察器——窗口期验收 TC-M21/22/23 用。

用途：活动窗口一到，你不用盯着页面，本脚本每 5s 轮询服务端并打印**状态迁移时间线**，
到点自动做 V2 时序断言、attempts 统计与结果判定，最后输出一份可直接贴进验收单的报告。

前置
    export VAULT_SECRET='<与服务端一致>'
    export MIGRANT_FPID='<账号 fpid>'
    export MIGRANT_JOB='<任务 job_id>'      # 创建任务后从大盘/接口拿
    # 可选：MIGRANT_ROLE_UID / VAULT_URL

用法
    python3 qa/tools/migrant_d_watch.py                # 观察到任务进入终态
    python3 qa/tools/migrant_d_watch.py --once         # 只抓一次当前快照
    python3 qa/tools/migrant_d_watch.py --interval 3   # 轮询间隔（默认 5s）

只读：只调 jobs / attempts，绝不触发任何危险动作。
"""

import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request

OP = "vaultMigrant"
PATH = "/v1/vault/migrant"
TERMINAL = {"SUCCEEDED", "REJECTED", "UNKNOWN", "CANCELLED"}

_ESC = {'"': '\\"', '\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t',
        '\b': '\\b', '\f': '\\f', '\u2028': '\\u2028', '\u2029': '\\u2029'}


def enc_str(s):
    out = ['"']
    for ch in s:
        out.append(_ESC.get(ch, ('\\u%04x' % ord(ch)) if ord(ch) < 0x20 else ch))
    out.append('"')
    return ''.join(out)


def enc_value(v):
    if v is None:
        return 'null'
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, int):
        return str(v)
    return enc_str(str(v))


def canonical(fields):
    return '{' + ','.join(enc_str(k) + ':' + enc_value(fields[k])
                          for k in sorted(fields.keys())) + '}'


def post(url, secret, fields, timeout=20):
    body = dict(fields)
    ts = int(time.time() * 1000)
    nonce = os.urandom(16).hex()
    body['ts'] = ts
    body['nonce'] = nonce
    payload = "v1\nPOST\n%s\n%d\n%s\n%s" % (
        OP, ts, nonce, hashlib.sha256(canonical(body).encode('utf-8')).hexdigest())
    body['sign'] = hmac.new(secret.encode('utf-8'), payload.encode('utf-8'),
                            hashlib.sha256).hexdigest()
    req = urllib.request.Request(url + PATH, data=canonical(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json; charset=utf-8'},
                                 method='POST')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode('utf-8'))
    except (urllib.error.HTTPError, urllib.error.URLError) as e:
        return {'code': -1, 'message': str(e), 'data': {}}


def fmt_ms(ms):
    if not ms:
        return '-'
    return time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(ms / 1000.0))


def fmt_delta(ms):
    if ms is None:
        return '-'
    sign = '+' if ms >= 0 else '-'
    a = abs(ms) / 1000.0
    if a >= 3600:
        return '%s%d时%d分' % (sign, int(a // 3600), int((a % 3600) // 60))
    if a >= 60:
        return '%s%d分%d秒' % (sign, int(a // 60), int(a % 60))
    return '%s%.0f秒' % (sign, a)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', default=os.environ.get('VAULT_URL', 'http://127.0.0.1:8790'))
    ap.add_argument('--interval', type=float, default=5.0)
    ap.add_argument('--once', action='store_true')
    ap.add_argument('--max-minutes', type=float, default=180.0)
    args = ap.parse_args()

    secret = os.environ.get('VAULT_SECRET', '')
    fpid = os.environ.get('MIGRANT_FPID', '')
    job_id = os.environ.get('MIGRANT_JOB', '')
    if not secret or not fpid or not job_id:
        print('需要 VAULT_SECRET / MIGRANT_FPID / MIGRANT_JOB', file=sys.stderr)
        return 2

    print('观察任务 %s（账号 %s）\n轮询间隔 %.1fs；Ctrl-C 结束\n' % (job_id, fpid, args.interval))
    last_status = None
    events = []
    deadline = time.time() + args.max_minutes * 60
    final = None

    while True:
        r = post(args.url, secret, {'action': 'jobs', 'fpid': fpid, 'sid': '0', 'uid': '0'})
        if r.get('code') != 0:
            print('[%s] 拉取失败：%s' % (time.strftime('%H:%M:%S'), r.get('message')))
        else:
            d = r.get('data') or {}
            jobs = (d.get('active') or []) + (d.get('finished') or [])
            job = next((j for j in jobs if j.get('job_id') == job_id), None)
            if job is None:
                print('[%s] 任务不在列表中（可能已清理或 job_id 有误）' % time.strftime('%H:%M:%S'))
            else:
                st = job.get('status')
                if st != last_status:
                    line = '[%s] %-9s attempts=%s/%s  %s' % (
                        time.strftime('%H:%M:%S'), st, job.get('attempt_count'),
                        job.get('max_attempts'), (job.get('error') or '')[:60])
                    print(line)
                    events.append((time.time(), st, job))
                    last_status = st
                if st in TERMINAL:
                    final = job
                    break
        if args.once:
            break
        if time.time() > deadline:
            print('已达观察上限 %s 分钟，停止' % args.max_minutes)
            break
        time.sleep(args.interval)

    if final is None:
        print('\n（未进入终态，未做时序断言）')
        return 0

    # ---------- attempts 与时序断言（V2 / TC-M21） ----------
    print('\n===== 终态报告 =====')
    print('状态        : %s（%s）' % (final.get('status'), final.get('status_label')))
    print('响应码      : %s' % final.get('response_code'))
    print('重登王国    : %s' % (final.get('reconciled_kid') or '-'))
    print('时钟源      : %s' % (final.get('clock_source') or '-'))
    print('错误        : %s' % (final.get('error') or '-'))

    fs = final.get('freedom_start_millis') or 0
    adv = final.get('advance_millis') or 0
    print('\n开放时刻    : %s' % fmt_ms(fs))
    print('提前量      : %d ms' % adv)
    print('时间轴：')
    for k, label in (('created_at_millis', '创建'), ('armed_at_millis', '就绪'),
                     ('sent_at_millis', '首次发送'), ('finished_at_millis', '结束')):
        v = final.get(k) or 0
        print('  %-8s %s%s' % (label, fmt_ms(v),
                               ('（距开放 %s）' % fmt_delta(v - fs)) if (v and fs) else ''))

    a = post(args.url, secret, {'action': 'attempts', 'fpid': fpid, 'sid': '0',
                                'uid': '0', 'job_id': job_id})
    attempts = []
    if a.get('code') == 0:
        attempts = (a.get('data') or {}).get('attempts') or []
    print('\n请求次数    : %d' % len(attempts))

    ok = True
    if attempts and fs:
        first = attempts[0]
        first_sent = first.get('sent_at_millis') or 0
        delta = first_sent - fs
        lo, hi = -adv, 50
        passed = lo <= delta <= hi
        ok = ok and passed
        print('首发偏差    : %d ms（要求 %d ~ %d ms）→ %s'
              % (delta, lo, hi, 'PASS' if passed else 'FAIL'))
        print('首发服务器钟: %s' % ('是' if first.get('server_now_millis') == first_sent
                                    else '否（异常：sent_at 与 server_now 不同域）'))
        if first.get('server_now_millis') != first_sent:
            ok = False
    elif fs:
        print('首发偏差    : 无请求记录（未发射）')

    if attempts:
        print('\n阶段分布：')
        for x in attempts[:12]:
            print('  #%s %-10s %-8s %s' % (x.get('attempt_no'), x.get('phase_label') or x.get('phase'),
                                          x.get('outcome'), fmt_ms(x.get('sent_at_millis'))))
        if len(attempts) > 12:
            print('  …共 %d 条（完整列表见 App 请求审计弹窗）' % len(attempts))

    # TC-M22
    if final.get('status') == 'SUCCEEDED':
        kid = final.get('target_kid')
        rec = final.get('reconciled_kid')
        good = (rec == kid) or (rec in (0, None))
        print('\nTC-M22 迁移结果: %s（target=%s reconciled=%s）'
              % ('PASS' if good else 'FAIL', kid, rec))
        ok = ok and good
    # TC-M23
    if final.get('status') == 'UNKNOWN':
        nxt = final.get('next_reverify_at_millis') or 0
        print('\nTC-M23 延迟复核: %s（%s）'
              % ('已排期' if nxt else '未排期', fmt_ms(nxt) if nxt else '-'))

    print('\n===== %s =====' % ('全部断言通过' if ok else '存在失败项，请贴出本报告'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
