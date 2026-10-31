#!/usr/bin/env python3
"""vault-server 外部看护（watchdog，2026-10-30 批次 B / E5）。

为什么需要外部看护：进程内告警（HealthMonitor/企微日报）依赖 vault-server 自身存活；
进程挂死、token 过期、日志停滞时它们不会发出任何消息。本脚本由 systemd timer 在
**进程之外**运行（默认每 3 分钟），任一检查失败即通过企微机器人告警，带冷却防刷屏。

检查项（全部只读，不修改业务数据）：
  1. systemd：`systemctl is-active vault-server`（--no-systemd 跳过，用于非 systemd 主机）
  2. HTTP：GET /healthz 返回 200 且信封 code=0
  3. 日志新鲜度：data/logs/vault-YYYYMMDD.log 最近 mtime 距今 ≤ --log-max-age 秒
  4. 状态文件：data/state.json mtime 距今 ≤ --state-max-age 秒（调度在写）
  5. 会话在线：/v1/vault/status 中 keep=true 的账号至少 1 个 online（无 keep 账号则跳过）
  6. token 失效：status 账号 lastFailure 含 need_token/token 失效等关键字 → 告警

告警：读取 data/vault.json 的 watch.webhookUrl（企微群机器人）；--dry-run 只打印。
冷却：同一检查键在 --cooldown 秒内只报一次（默认 1800），状态存 --state-file。

退出码：0=全部正常；1=有告警（已发送或 dry-run 打印）；2=参数/环境错误。

用法示例：
  python3 vault_watchdog.py --dry-run            # 只看结果不发消息
  python3 vault_watchdog.py --no-systemd         # 非 systemd 主机
  python3 vault_watchdog.py --data-dir /opt/vault-server/data
"""
import argparse
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_ENDPOINT = "http://127.0.0.1:8790"
DEFAULT_DATA_DIR = "/opt/vault-server/data"
DEFAULT_ENV_FILE = "/etc/vault-server.env"


def log(msg: str) -> None:
    print("[watchdog %s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


# ---------------- 配置/Vault 读取 ----------------

def read_secret(env_file: str) -> str:
    try:
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("VAULT_SECRET="):
                    v = line.split("=", 1)[1].strip().strip('"').strip("'")
                    if v:
                        return v
    except OSError:
        pass
    return ""


def read_webhook(data_dir: str) -> str:
    try:
        with open(os.path.join(data_dir, "vault.json"), "r", encoding="utf-8") as f:
            v = json.load(f)
        return ((v.get("watch") or {}).get("webhookUrl") or "").strip()
    except (OSError, ValueError):
        return ""


# ---------------- 签名请求（与 ApiSign 一致） ----------------

def _esc(s: str) -> str:
    out = ['"']
    for ch in s:
        if ch == '"':
            out.append('\\"')
        elif ch == '\\':
            out.append('\\\\')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\r':
            out.append('\\r')
        elif ch == '\t':
            out.append('\\t')
        elif ord(ch) < 0x20:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return ''.join(out)


def _canonical(fields: dict) -> str:
    parts = []
    for k in sorted(fields):
        v = fields[k]
        if isinstance(v, bool):
            vs = 'true' if v else 'false'
        elif isinstance(v, int):
            vs = str(v)
        else:
            vs = _esc(str(v))
        parts.append(_esc(k) + ':' + vs)
    return '{' + ','.join(parts) + '}'


def signed_post(endpoint: str, op: str, fields: dict, secret: str, timeout: float = 8.0) -> dict:
    ts = int(time.time() * 1000)
    nonce = secrets.token_hex(16)
    body = dict(fields)
    body["ts"] = ts
    body["nonce"] = nonce
    canonical = _canonical(body)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    payload = "v1\nPOST\n%s\n%d\n%s\n%s" % (op, ts, nonce, digest)
    sign = hmac.new(secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).hexdigest()
    body["sign"] = sign
    raw = _canonical(body).encode("utf-8")
    req = urllib.request.Request(endpoint, data=raw,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as rsp:
        return json.loads(rsp.read().decode("utf-8"))


# ---------------- 检查项 ----------------

def check_systemd() -> str:
    try:
        p = subprocess.run(["systemctl", "is-active", "vault-server"],
                           capture_output=True, text=True, timeout=10)
        out = (p.stdout or "").strip()
        if p.returncode == 0 and out == "active":
            return ""
        return "systemd 服务未运行：is-active=%r rc=%d" % (out, p.returncode)
    except Exception as e:  # noqa: BLE001
        return "systemctl 调用失败：%s" % e


def check_health(base: str) -> str:
    try:
        with urllib.request.urlopen(base + "/healthz", timeout=8) as rsp:
            if rsp.status != 200:
                return "健康检查 HTTP %d" % rsp.status
            data = json.loads(rsp.read().decode("utf-8"))
            if data.get("code") != 0:
                return "健康检查信封 code=%r message=%r" % (data.get("code"), data.get("message"))
        return ""
    except Exception as e:  # noqa: BLE001
        return "健康检查请求失败：%s" % e


def _mtime_age(path: str) -> float:
    try:
        return time.time() - os.path.getmtime(path)
    except OSError:
        return -1.0


def check_files(data_dir: str, log_max_age: int, state_max_age: int) -> str:
    logs = os.path.join(data_dir, "logs")
    newest = -1.0
    try:
        for name in os.listdir(logs):
            if not name.startswith("vault-") or not name.endswith(".log"):
                continue
            age = _mtime_age(os.path.join(logs, name))
            if age >= 0 and (newest < 0 or age < newest):
                newest = age
    except OSError:
        return "日志目录不可读：%s" % logs
    if newest < 0:
        return "未找到 vault-*.log"
    if newest > log_max_age:
        return "日志已停滞 %.0f 秒（阈值 %d）" % (newest, log_max_age)
    state_age = _mtime_age(os.path.join(data_dir, "state.json"))
    if state_age < 0:
        return "state.json 不存在"
    if state_age > state_max_age:
        return "state.json 已 %.0f 秒未更新（阈值 %d）" % (state_age, state_max_age)
    return ""


def check_sessions(base: str, secret: str) -> str:
    if not secret:
        return ""
    try:
        rsp = signed_post(base + "/v1/vault/status", "vaultStatus", {}, secret)
    except Exception as e:  # noqa: BLE001
        return "status 接口请求失败：%s" % e
    if rsp.get("code") != 0:
        return "status 接口 code=%r message=%r" % (rsp.get("code"), rsp.get("message"))
    accounts = ((rsp.get("data") or {}).get("accounts") or [])
    if not accounts:
        return ""
    keep = [a for a in accounts if a.get("keep")]
    online = [a for a in keep if str(a.get("state")) == "online"]
    if keep and not online:
        return "常驻账号全部离线（keep=%d, online=0）" % len(keep)
    bad = []
    for a in accounts:
        lf = str(a.get("lastFailure") or "")
        if any(k in lf.lower() for k in ("need_token", "token 失效", "token失效", "登录失败")):
            bad.append("%s:%s" % (a.get("fpid"), lf[:60]))
    if bad:
        return "账号异常：%s" % "; ".join(bad[:3])
    return ""


# ---------------- 告警 ----------------

def load_state(path: str) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(path: str, state: dict) -> None:
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path + ".tmp", "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False)
        os.replace(path + ".tmp", path)
    except OSError as e:
        log("状态写入失败：%s" % e)


def wecom_send(webhook: str, text: str) -> bool:
    if not webhook:
        return False
    body = json.dumps({"msgtype": "text", "text": {"content": text}}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(webhook, data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=8) as rsp:
            return rsp.status == 200
    except Exception as e:  # noqa: BLE001
        log("企微发送失败：%s" % e)
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description="vault-server 外部看护")
    ap.add_argument("--base", default=os.environ.get("VAULT_WATCHDOG_BASE", DEFAULT_ENDPOINT),
                    help="vault-server 本机地址（默认 %s）" % DEFAULT_ENDPOINT)
    ap.add_argument("--data-dir", default=os.environ.get("VAULT_DATA_DIR", DEFAULT_DATA_DIR))
    ap.add_argument("--env-file", default=os.environ.get("VAULT_ENV_FILE", DEFAULT_ENV_FILE))
    ap.add_argument("--log-max-age", type=int, default=600, help="日志 mtime 阈值秒（默认 600）")
    ap.add_argument("--state-max-age", type=int, default=300, help="state.json mtime 阈值秒（默认 300）")
    ap.add_argument("--cooldown", type=int, default=1800, help="同类告警冷却秒（默认 1800）")
    ap.add_argument("--state-file", default=os.environ.get("VAULT_WATCHDOG_STATE",
                    "/var/lib/vault-watchdog/state.json"))
    ap.add_argument("--no-systemd", action="store_true", help="跳过 systemctl 检查")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不发企微")
    ap.add_argument("--force-alert", action="store_true", help="忽略冷却（测试用）")
    args = ap.parse_args()

    secret = read_secret(args.env_file)
    webhook = read_webhook(args.data_dir)
    state = load_state(args.state_file)
    now = time.time()

    checks = []
    if not args.no_systemd:
        checks.append(("systemd", check_systemd()))
    checks.append(("health", check_health(args.base)))
    checks.append(("files", check_files(args.data_dir, args.log_max_age, args.state_max_age)))
    checks.append(("sessions", check_sessions(args.base, secret)))

    failures = [(k, v) for k, v in checks if v]
    for key, msg in checks:
        log("%-9s %s" % (key, "OK" if not msg else "FAIL: " + msg))

    if not failures:
        state["last_ok_at"] = int(now)
        save_state(args.state_file, state)
        return 0

    recent = state.get("last_alert") or {}
    lines = ["【vault-server 看护】异常 %d 项" % len(failures)]
    to_send = []
    for key, msg in failures:
        last = float(recent.get(key) or 0)
        cooled = (now - last) < args.cooldown and not args.force_alert
        if cooled:
            log("冷却中，跳过告警：%s" % key)
            continue
        to_send.append((key, msg))
        recent[key] = int(now)
        lines.append("- %s：%s" % (key, msg))
    state["last_alert"] = recent
    save_state(args.state_file, state)

    if not to_send:
        return 1
    text = "\n".join(lines)
    if args.dry_run:
        log("dry-run 告警内容：\n" + text)
        return 1
    ok = wecom_send(webhook, text)
    log("企微告警已发送" if ok else "企微告警未发送（webhook 缺失或失败）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
