#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""移民端点冒烟脚本（验收清单 0 段 / P-1 用）。

用途：部署新版 vault-server 之后，**不依赖 App** 直接验证 /v1/vault/migrant 端点
已上线、验签通道可用、核心 action 的返回结构与契约一致。

设计原则
- **只读**：默认只调 accounts / overview / jobs / 非法 action / 越权 job 五类，
  绝不触发 test-early / create / bulk / recall / cancel 等真实动作。
- **不落密钥**：VAULT_SECRET 只从环境变量读，不写文件、不回显。

用法
    export VAULT_SECRET='<与服务端一致的 secret>'
    python3 qa/tools/migrant_smoke.py
    # 可选：
    python3 qa/tools/migrant_smoke.py --url http://127.0.0.1:8790 --verbose

退出码：0=全部通过；1=有失败；2=用法/环境问题。
"""

import argparse
import hashlib
import hmac
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request

OP = "vaultMigrant"
PATH = "/v1/vault/migrant"

# 与服务端 ApiSign.writeString 逐字对齐：仅转义这 8 类，非 ASCII 原样（UTF-8）
_ESC = {
    '"': '\\"',
    '\\': '\\\\',
    '\n': '\\n',
    '\r': '\\r',
    '\t': '\\t',
    '\b': '\\b',
    '\f': '\\f',
    '\u2028': '\\u2028',
    '\u2029': '\\u2029',
}


def enc_str(s):
    out = ['"']
    for ch in s:
        if ch in _ESC:
            out.append(_ESC[ch])
        elif ord(ch) < 0x20:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return ''.join(out)


def enc_value(v):
    if v is None:
        return 'null'
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        # 服务端只处理 Long/Integer/Boolean/String，浮点按字符串走
        return enc_str(str(v))
    return enc_str(str(v))


def canonical(fields):
    """键字典序、无空白（对齐 ApiSign.writeCanonical）。"""
    parts = []
    for k in sorted(fields.keys()):
        parts.append(enc_str(k) + ':' + enc_value(fields[k]))
    return '{' + ','.join(parts) + '}'


def build_signed_body(secret, fields):
    body = dict(fields)
    ts = int(time.time() * 1000)
    nonce = os.urandom(16).hex()
    body['ts'] = ts
    body['nonce'] = nonce
    can = canonical(body)
    payload = "v1\nPOST\n%s\n%d\n%s\n%s" % (OP, ts, nonce,
                                            hashlib.sha256(can.encode('utf-8')).hexdigest())
    sign = hmac.new(secret.encode('utf-8'), payload.encode('utf-8'),
                    hashlib.sha256).hexdigest()
    body['sign'] = sign
    return canonical(body)


def post(url, secret, fields, timeout=20):
    data = build_signed_body(secret, fields).encode('utf-8')
    req = urllib.request.Request(url + PATH, data=data,
                                 headers={'Content-Type': 'application/json; charset=utf-8'},
                                 method='POST')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return {'code': -1, 'message': 'HTTP %s' % e.code,
                'data': {}}
    except (urllib.error.URLError, socket.timeout) as e:
        return {'code': -2, 'message': '连接失败：%s' % e, 'data': {}}


class Report(object):
    def __init__(self, verbose=False):
        self.rows = []
        self.verbose = verbose

    def check(self, name, ok, detail=''):
        self.rows.append((name, ok, detail))
        flag = 'PASS' if ok else 'FAIL'
        print('  [%s] %s%s' % (flag, name, ('  — ' + detail) if detail else ''))
        return ok

    def summary(self):
        bad = [r for r in self.rows if not r[1]]
        print('\n合计 %d 项：通过 %d，失败 %d' % (len(self.rows),
                                             len(self.rows) - len(bad), len(bad)))
        if bad:
            print('失败项：')
            for name, _ok, detail in bad:
                print('  - %s%s' % (name, ('（%s）' % detail) if detail else ''))
        return 0 if not bad else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', default=os.environ.get('VAULT_URL', 'http://127.0.0.1:8790'))
    ap.add_argument('--verbose', action='store_true')
    ap.add_argument('--fpid', default=os.environ.get('MIGRANT_FPID', ''),
                    help='真实测试账号 fpid；给出后会额外验证 accounts/overview 的完整结构')
    ap.add_argument('--role', default=os.environ.get('MIGRANT_ROLE_UID', ''),
                    help='该账号下的角色 uid（配合 --fpid 验证 overview 离线语义）')
    args = ap.parse_args()

    secret = os.environ.get('VAULT_SECRET', '')
    if not secret:
        print('缺少 VAULT_SECRET 环境变量（与服务端 VAULT_SECRET 一致）', file=sys.stderr)
        return 2

    rep = Report(args.verbose)
    print('目标：%s%s\n' % (args.url, PATH))

    # --- 1. 端点存在性 + 验签 ---
    r = post(args.url, secret, {'action': 'jobs', 'fpid': '__probe__', 'sid': '0', 'uid': '0'})
    if r.get('code') == -2:
        rep.check('P-1 服务端可达', False, r.get('message', ''))
        return rep.summary()
    rep.check('P-1 服务端可达', True)
    # 验签失败时服务端返回 2001
    rep.check('P-1 验签通过（非 2001）', r.get('code') != 2001,
              'code=%s %s' % (r.get('code'), r.get('message', '')))
    # 未注入模块会是 3002「移民模块未启用」；未知 fpid 是 1004
    code = r.get('code')
    rep.check('P-1 移民模块已启用（非 3002 未启用）',
              not (code == 3002 and '未启用' in str(r.get('message', ''))),
              'code=%s %s' % (code, r.get('message', '')))

    # --- 2. accounts：结构 ---
    r = post(args.url, secret, {'action': 'accounts', 'fpid': '__probe__', 'sid': '0', 'uid': '0'})
    if r.get('code') == 1004:
        rep.check('accounts 对未知 fpid 返回 1004（不存在口径正确）', True,
                  r.get('message', ''))
    else:
        rep.check('accounts 对未知 fpid 应返回 1004', False,
                  'code=%s %s' % (r.get('code'), r.get('message')))

    # --- 2b. 真实 fpid：accounts + overview 结构（可选） ---
    if args.fpid:
        r = post(args.url, secret, {'action': 'accounts', 'fpid': args.fpid,
                                    'sid': '0', 'uid': '0'})
        ok = r.get('code') == 0
        accts = (r.get('data') or {}).get('accounts') if ok else None
        rep.check('accounts 返回 accounts 数组', isinstance(accts, list),
                  'len=%s' % (len(accts) if isinstance(accts, list) else 'n/a'))
        if isinstance(accts, list) and accts:
            a0 = accts[0]
            need = ('accountRoleId', 'game_name', 'server_id', 'online')
            miss = [k for k in need if k not in a0]
            rep.check('accounts 元素含约定字段 %s' % '/'.join(need), not miss,
                      '缺 %s；实得键 %s' % (miss, sorted(a0.keys())))
            rep.check('accounts 只含本 fpid（不泄露其它账号）',
                      all(str(x.get('fpid', args.fpid)) == args.fpid for x in accts), '')

        if args.role:
            r = post(args.url, secret, {'action': 'overview', 'fpid': args.fpid,
                                        'sid': '0', 'uid': '0',
                                        'account_role_id': int(args.role)})
            d = r.get('data') or {}
            rep.check('overview 离线返回 code=0（非报错）', r.get('code') == 0,
                      'code=%s %s' % (r.get('code'), r.get('message')))
            rep.check('overview 离线显式 online=false（契约裁决）',
                      d.get('online') is False,
                      'data.online=%r' % (d.get('online'),))
            rep.check('overview 离线带可读原因', bool(d.get('message')),
                      str(d.get('message', ''))[:40])

            # 越权：用另一个 fpid 访问该角色必须 1004（角色归属校验 P0-1 语义）
            r2 = post(args.url, secret, {'action': 'overview', 'fpid': '__other__',
                                         'sid': '0', 'uid': '0',
                                         'account_role_id': int(args.role)})
            rep.check('越权 fpid 访问他人角色被拒（1004）', r2.get('code') == 1004,
                      'code=%s %s' % (r2.get('code'), r2.get('message')))

    # --- 3. 非法 action → 3002（用真实 fpid：服务端先校验账号、后校验 action） ---
    probe_fpid = args.fpid or '__probe__'
    r = post(args.url, secret, {'action': '__nope__', 'fpid': probe_fpid,
                                'sid': '0', 'uid': '0'})
    rep.check('非法 action 返回 3002', r.get('code') == 3002,
              'code=%s %s' % (r.get('code'), r.get('message')))

    # --- 4. 不存在的 job_id → 1004 ---
    r = post(args.url, secret, {'action': 'cancel', 'fpid': probe_fpid, 'sid': '0',
                                'uid': '0', 'job_id': '__no_such_job__'})
    rep.check('不存在的 job_id 返回 1004', r.get('code') == 1004,
              'code=%s %s' % (r.get('code'), r.get('message')))

    # --- 5. jobs：空任务仓结构 ---
    r = post(args.url, secret, {'action': 'jobs', 'fpid': probe_fpid, 'sid': '0', 'uid': '0'})
    if r.get('code') == 0:
        d = r.get('data') or {}
        rep.check('jobs 返回 active/finished 两个数组',
                  isinstance(d.get('active'), list) and isinstance(d.get('finished'), list),
                  'active=%s finished=%s' % (type(d.get('active')).__name__,
                                             type(d.get('finished')).__name__))
    else:
        rep.check('jobs 调用', False, 'code=%s %s' % (r.get('code'), r.get('message')))

    print('\n提示：以上为只读冒烟。真实角色链路请在 App 内按验收清单 A/B/C 段执行。')
    return rep.summary()


if __name__ == '__main__':
    sys.exit(main())
