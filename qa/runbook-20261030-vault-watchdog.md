# Runbook · vault-server 外部看护（watchdog，2026-10-30）

- 目的：进程内告警（HealthMonitor/日报）依赖 vault-server 自身存活；进程挂死、token 过期、
  日志停滞时无法自报。本看护由 **systemd timer 在进程之外**每 3 分钟运行，异常即发企微。
- 主机：生产 VPS（`ssh vault-vps`，systemd）
- 组件：
  - 脚本：`/opt/vault-watchdog/vault_watchdog.py`（仓库源 `tools/vault_watchdog.py`）
  - 单元：`/etc/systemd/system/vault-watchdog.service`（Type=oneshot）
  - 定时：`/etc/systemd/system/vault-watchdog.timer`（OnBootSec=90s，OnUnitActiveSec=180s）
  - 冷却状态：`/var/lib/vault-watchdog/state.json`

## 检查项

| 键 | 判据 |
|---|---|
| systemd | `systemctl is-active vault-server` == active |
| health | `GET http://127.0.0.1:8790/healthz` → HTTP 200 + 信封 code=0 |
| files | 最新 `data/logs/vault-*.log` mtime ≤ 600s；`data/state.json` mtime ≤ 300s |
| sessions | `/v1/vault/status`：keep=true 账号至少 1 个 online；lastFailure 含 need_token/登录失败 → 告警 |

告警走 `data/vault.json` 的 `watch.webhookUrl`（企微机器人）；同类告警 30 分钟冷却；
`--force-alert` 可忽略冷却（测试用），`--dry-run` 只打印。

## 常用命令

```bash
systemctl status vault-watchdog.timer --no-pager
systemctl list-timers vault-watchdog.timer --no-pager
journalctl -u vault-watchdog.service -n 50 --no-pager
python3 /opt/vault-watchdog/vault_watchdog.py --dry-run            # 手动体检（不发消息）
python3 /opt/vault-watchdog/vault_watchdog.py --dry-run --base http://127.0.0.1:1  # 模拟故障自测
```

## 回滚/关闭

```bash
systemctl disable --now vault-watchdog.timer
```

## 阈值调整

编辑 `/etc/systemd/system/vault-watchdog.service` 的 ExecStart 追加参数（如
`--log-max-age 900 --cooldown 3600`），然后 `systemctl daemon-reload && systemctl restart vault-watchdog.timer`。
也可用环境变量：`VAULT_WATCHDOG_BASE` / `VAULT_DATA_DIR` / `VAULT_ENV_FILE` / `VAULT_WATCHDOG_STATE`。

## 验证记录（2026-10-30 23:47 北京）

- 部署后手动 + timer 首跑：`systemd OK / health OK / files OK / sessions OK`；
- 故障注入（`--base http://127.0.0.1:1 --dry-run`）：正确输出 health/files 两项异常与告警文本；
- 未向生产群发送测试告警（避免干扰）。
