#!/usr/bin/env python3
"""观察点城缓存查询小工具（2026-11-02）。

按 uid / 昵称子串 / 联盟简称 / 联盟 aid / 最低炉子等级，查询服务端整图扫描缓存（约 4500 座城）。
地图城堡昵称形如 "[818]小淑女i"，简称就是方括号前缀，因此 --abbr 818 可直接筛出该联盟成员。

用法示例：
  python3 tools/watchpoint_lookup.py --fid 123861751          # 用户视角：账号ID → 角色/坐标
  python3 tools/watchpoint_lookup.py --uid 31374667
  python3 tools/watchpoint_lookup.py --abbr 818 --min-lv 80
  python3 tools/watchpoint_lookup.py --nick 小淑女
  python3 tools/watchpoint_lookup.py --aid 89700652 --min-lv 80
  python3 tools/watchpoint_lookup.py --player 123861751            # 借在线会话查联盟简称/aid
  python3 tools/watchpoint_lookup.py --resolve --abbr 818 --min-lv 80  # 全图批量解析简称，筛出该联盟 80 级成员
  python3 tools/watchpoint_lookup.py --alliance 818                 # 联盟简称 → aid（官方搜索协议）

只读：走 /v1/vault/watchpoint action=lookup，签名与 App 同源（HMAC-SHA256）。
默认端点取环境变量 VAULT_BASE（缺省 https://cs.888syd.xyz）；
签名密钥**必须**由环境变量 VAULT_SECRET 或 --secret 提供（不在仓库里内置任何密钥）。
"""

import argparse
import hashlib
import hmac
import json
import os
import secrets
import ssl
import sys
import time
import urllib.request

# 端点不内置生产域名：必须由 VAULT_BASE 或 --base 显式给出（避免把线上地址写进仓库）
DEFAULT_BASE = os.environ.get("VAULT_BASE", "")
DEFAULT_SECRET = os.environ.get("VAULT_SECRET", "")
OP = "vaultWatchpoint"


def sign_body(secret, op, fields):
    """按 ApiSign 规范签名：HMAC(secret, "v1\nPOST\n<op>\n<ts>\n<nonce>\n<sha256(canonical)>")。"""
    f = dict(fields)
    f["ts"] = int(time.time() * 1000)
    f["nonce"] = secrets.token_hex(16)
    canonical = json.dumps(f, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    msg = "v1\nPOST\n%s\n%d\n%s\n%s" % (op, f["ts"], f["nonce"], digest)
    f["sign"] = hmac.new(secret.encode("utf-8"), msg.encode("utf-8"), hashlib.sha256).hexdigest()
    return json.dumps(f, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def post(base, secret, fields, timeout=20, insecure=False):
    body = sign_body(secret, OP, fields).encode("utf-8")
    req = urllib.request.Request(base.rstrip("/") + "/v1/vault/watchpoint", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    ctx = ssl._create_unverified_context() if insecure else None
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description="查询观察点城缓存（uid/昵称/联盟简称/等级）")
    ap.add_argument("--fid", help="账号ID（游戏领主档案里的数字，服务端玩家目录反查角色）")
    ap.add_argument("--uid", help="角色 uid（精确）")
    ap.add_argument("--nick", help="昵称子串（模糊）")
    ap.add_argument("--abbr", help="联盟简称（地图昵称方括号前缀，如 818）")
    ap.add_argument("--aid", type=int, help="联盟 aid（精确）")
    ap.add_argument("--min-lv", type=int, default=0, help="最低炉子等级（如 80）")
    ap.add_argument("--limit", type=int, default=200, help="最多返回条数，默认 200")
    ap.add_argument("--base", default=DEFAULT_BASE, help="服务端基址，默认 %s" % DEFAULT_BASE)
    ap.add_argument("--secret", default=DEFAULT_SECRET,
                    help="签名密钥（也可用环境变量 VAULT_SECRET；不内置默认值）")
    ap.add_argument("--insecure", action="store_true", help="跳过 TLS 校验（自签证书场景）")
    ap.add_argument("--player", help="查玩家条目（uid，逗号分隔；返回 alliance.abbr/aid/name/stove_lv）")
    ap.add_argument("--alliance", help="联盟简称 → aid（走官方联盟搜索协议，1 次请求）")
    ap.add_argument("--resolve", action="store_true",
                    help="批量解析：对缓存候选逐个查 alliance.abbr（配合 --abbr/--min-lv 使用）")
    ap.add_argument("--fpid", default="", help="玩家查询所用账号 fpid（player/resolve/alliance 必填）")
    ap.add_argument("--sid", type=int, default=0, help="区服 sid；0=同账号任意在线角色（推荐）")
    ap.add_argument("--json", action="store_true", help="只输出原始 JSON")
    args = ap.parse_args()

    if not args.base:
        print("缺少服务端地址：请设置 VAULT_BASE 或用 --base 指定（如 https://你的域名）", file=sys.stderr)
        return 2
    if not args.secret:
        print("缺少签名密钥：请设置 VAULT_SECRET 或用 --secret 指定", file=sys.stderr)
        return 2
    if args.insecure:
        print("⚠️ 已跳过 TLS 校验（--insecure）：仅限自签证书/内网环境", file=sys.stderr)
    if (args.player or args.resolve or args.alliance) and not args.fpid:
        print("这些查询需要 --fpid（游戏领主档案里的账号ID）", file=sys.stderr)
        return 2

    if args.alliance:
        fields = {"action": "alliance", "abbr": str(args.alliance).strip(),
                  "fpid": args.fpid, "sid": str(args.sid)}
        try:
            resp = post(args.base, args.secret, fields, timeout=30, insecure=args.insecure)
        except Exception as e:  # noqa: BLE001
            print("请求失败：%s" % e, file=sys.stderr)
            return 1
        if resp.get("code") != 0:
            print("服务端返回错误：%s %s" % (resp.get("code"), resp.get("message")), file=sys.stderr)
            return 1
        d = resp.get("data") or {}
        print("联盟简称 %s → aid %s（%s）" % (d.get("abbr"), d.get("aid"),
                                          "已找到" if d.get("found") else "未找到"))
        return 0

    if args.resolve:
        fields = {"action": "resolve", "fpid": args.fpid, "sid": str(args.sid),
                  "minLv": str(args.min_lv), "limit": str(args.limit)}
        if args.abbr:
            fields["abbr"] = str(args.abbr)
        if args.aid:
            fields["aid"] = str(args.aid)
        try:
            resp = post(args.base, args.secret, fields, timeout=90, insecure=args.insecure)
        except Exception as e:  # noqa: BLE001
            print("请求失败：%s" % e, file=sys.stderr)
            return 1
        if args.json:
            print(json.dumps(resp, ensure_ascii=False, indent=2))
            return 0
        if resp.get("code") != 0:
            print("服务端返回错误：%s %s" % (resp.get("code"), resp.get("message")), file=sys.stderr)
            return 1
        rows = (resp.get("data") or {}).get("players") or []
        print("命中 %d 人：" % len(rows))
        print("%-12s %-12s %-4s %-5s %-12s %-4s %s" % ("uid", "账号(fid)", "lv", "aid", "abbr", "坐标", "昵称"))
        for r in rows:
            print("%-12s %-12s %-4s %-5s %-12s (%s,%-4s) %s" % (
                r.get("uid"), r.get("fid"), r.get("stoveLv"), r.get("aid"), r.get("abbr"),
                r.get("x"), r.get("y"), r.get("nick")))
        return 0

    if args.player:
        fields = {"action": "player", "uids": args.player,
                  "fpid": args.fpid, "sid": str(args.sid)}
        try:
            resp = post(args.base, args.secret, fields, insecure=args.insecure)
        except Exception as e:  # noqa: BLE001
            print("请求失败：%s" % e, file=sys.stderr)
            return 1
        if args.json:
            print(json.dumps(resp, ensure_ascii=False, indent=2))
            return 0
        if resp.get("code") != 0:
            print("服务端返回错误：%s %s" % (resp.get("code"), resp.get("message")), file=sys.stderr)
            return 1
        rows = (resp.get("data") or {}).get("players") or []
        print("命中 %d 个玩家：" % len(rows))
        print("%-12s %-12s %-4s %-6s %-12s %s" % ("uid", "账号(fid)", "lv", "aid", "abbr", "昵称"))
        for r in rows:
            print("%-12s %-12s %-4s %-6s %-12s %s" % (
                r.get("uid"), r.get("fid"), r.get("stoveLv"), r.get("aid"), r.get("abbr"),
                r.get("nick") if r.get("found") else "<未找到>"))
        return 0

    if not args.secret:
        print("缺少签名密钥：请设置环境变量 VAULT_SECRET 或传 --secret", file=sys.stderr)
        return 2

    fields = {"action": "lookup", "limit": str(args.limit)}
    if args.fid:
        fields["fid"] = str(args.fid)
    if args.uid:
        fields["uid"] = str(args.uid)
    if args.nick:
        fields["nick"] = args.nick
    if args.abbr:
        fields["abbr"] = str(args.abbr)
    if args.aid:
        fields["aid"] = str(args.aid)
    if args.min_lv:
        fields["minLv"] = str(args.min_lv)

    try:
        resp = post(args.base, args.secret, fields, insecure=args.insecure)
    except Exception as e:  # noqa: BLE001
        print("请求失败：%s" % e, file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(resp, ensure_ascii=False, indent=2))
        return 0
    if resp.get("code") != 0:
        print("服务端返回错误：%s %s" % (resp.get("code"), resp.get("message")), file=sys.stderr)
        return 1
    data = resp.get("data") or {}
    cities = data.get("cities") or []
    print("命中 %d 座城：" % len(cities))
    print("%-12s %-12s %-5s %-6s %s" % ("uid", "aid", "lv", "坐标", "昵称"))
    for c in cities:
        print("%-12s %-12s %-5s (%s,%-4s) %s" % (
            c.get("uid"), c.get("aid"), c.get("lv"), c.get("x"), c.get("y"), c.get("nick")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
