# 部署记录：vault-server 战时卡死修复（2026-10-25）

- 分支/提交：`modify/wartime-gate-stall-20261025-0238` / 见 `git log`（本记录随后提交）
- 目标主机：**103.236.96.82**（`ssh vault-vps`，root；服务 `vault-server.service`，`/opt/vault-server`）
- 窗口：2026-10-25 03:27（北京）；生产会话已由用户手动停止（keep=false），重启不影响在线任务
- 部署方式：本地 `./gradlew fatJar` → 上传 `/tmp` → 校验 sha256 → 覆盖 `/opt/vault-server/vault-server-all.jar` → `systemctl restart vault-server`

## 产物与备份

| 项 | 值 |
|---|---|
| 新 jar | `vault-server-0.1.0-all.jar`，1,270,378 B，sha256 `3f04e1e55d82102b595a1d5ab359977aeac40d46b66a61a1f38fd2553ecc4d24` |
| 旧 jar | sha256 `45f04f5a6ef0d36e932bd1ffaeb4a7041d17240202520e31d86f9b0e6357bef2` |
| 备份 | `/opt/vault-server/backup/vault-server-all.jar.bak-20261024-1926`（服务器 UTC 时间戳） |

## 验证

| 项 | 结果 |
|---|---|
| `sha256sum /opt/vault-server/vault-server-all.jar` | `3f04e1e5…4d24`（与本地一致） |
| `systemctl is-active vault-server` | `active` |
| `curl 127.0.0.1:8790/healthz` | `{"code":0,"message":"ok"}` |
| 启动日志（journalctl） | `vault-server 启动` / `整轮调度器已启动` / **`战争态全局节拍已启动：30000ms（对全部已注册角色实例判退出/上限，不再只依赖 active 角色会话），战时上限=不限`** / `任务节奏调度已启动：55 项` / `HTTP API 已启动` / `健康告警已启用` |
| 环境变量 | `VAULT_WARTIME_MAX_MIN` 未配置 → 上限关闭（按原有「无侦查无进攻 30min」静默退出） |

## 回滚

```bash
cp /opt/vault-server/backup/vault-server-all.jar.bak-20261024-1926 /opt/vault-server/vault-server-all.jar
systemctl restart vault-server
curl -s http://127.0.0.1:8790/healthz
```

## 部署前后发现（与本部署无关，需用户关注）

- **03:15:17（北京）发生 `身份库同步：0 条（keep=0）`**：App 全量同步空账号列表 → 服务端 `store.replaceAll([])` 清空账号（当前 `vault.json` accounts=0）。
- 该时间早于本次部署（03:27）；旧 jar 时期即已发生，不是本次升级导致。
- 可恢复快照：`/opt/vault-server/data/vault.json.stopbad-20261024-174527`（2 个账号，含 token，savedAt=1792863775750 ≈ 01:42 北京）。
- 风险：若 App 端确实无账号（如卸载重装后首次同步），下次同步会再次清空 → 建议加「空列表保护/需显式确认」再恢复。
