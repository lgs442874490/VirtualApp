# license-server 分级（tier）部署 Runbook（2026-10-23）

- 变更：兑换码增加 `tier`（1 普通 / 2 会员 / 3 高级会员）；旧库自动迁移（存量码一律 3，不降权）
- 产物：`/tmp/license-server-linux-4.1.9`（linux/amd64 静态，10,526,880 B，sha256 `6d082066835cd738cb05258b9e48f92d24224072ce3a996b88bf4bbe736292be`）
- 目标主机：**43.163.7.111**（新加坡测试机，`cs.888syd.xyz` A 记录；license-server 127.0.0.1:8787，Caddy 容器终结 TLS）
- ⚠️ **当前这台 Mac 无该主机 SSH 登录权限**（id_rsa/id_ed25519/vault_vps 均 Permission denied），部署需用户提供访问方式或由用户/运维执行本 Runbook。

## 一、部署前检查（服务器上）
```bash
systemctl is-active license-server
ls -la /opt/license-server/
sha256sum /opt/license-server/license-server
# 找到 SQLite 数据库路径（通常 /opt/license-server/data/*.db 或 env 中 LICENSE_DB）
systemctl cat license-server | grep -E 'ExecStart|EnvironmentFile|WorkingDirectory'
grep -E 'LICENSE_DB|LICENSE_.*PATH' /etc/license-server.env   # 不打印密钥
curl -s http://127.0.0.1:8787/healthz
```

## 二、备份（必须）
```bash
TS=$(date +%Y%m%d-%H%M)
mkdir -p /opt/license-server/backup
cp -p /opt/license-server/license-server /opt/license-server/backup/license-server.bak-$TS
cp -p <DB路径> /opt/license-server/backup/license-db.bak-$TS
sha256sum /opt/license-server/backup/license-server.bak-$TS
```

## 三、上传与替换
```bash
# 从本机（有访问权时）
scp /tmp/license-server-linux-4.1.9 root@43.163.7.111:/tmp/license-server-new

# 服务器上
install -m755 /tmp/license-server-new /opt/license-server/license-server
sha256sum /opt/license-server/license-server   # 期望 6d082066…
systemctl restart license-server
sleep 2
systemctl is-active license-server
curl -s http://127.0.0.1:8787/healthz
```

## 四、迁移与兼容验证
```bash
# 1) tier 列已自动补齐（ensureColumn），存量码=3
sqlite3 <DB路径> "PRAGMA table_info(license_code);" | grep tier
sqlite3 <DB路径> "SELECT COUNT(*), MIN(tier), MAX(tier) FROM license_code;"

# 2) 发码/列表 CLI（示例，具体参数见 license-server/README.md §7）
/usr/local/bin/license-ctl list --limit 5            # 应出现 TIER 列
/usr/local/bin/license-ctl issue --type month --tier 1 --count 1   # 仅测试环境/确认后执行

# 3) 端到端：新 App（v4.1.9）用 tier=1 码激活 → 我的页授权卡显示「普通版」，
#    只能用分身（今日/日常不可见）；tier=3 码显示「高级版」。
```

## 五、回滚
```bash
cp -p /opt/license-server/backup/license-server.bak-<TS> /opt/license-server/license-server
cp -p /opt/license-server/backup/license-db.bak-<TS> <DB路径>
systemctl restart license-server
curl -s http://127.0.0.1:8787/healthz
```
注意：回滚 DB 会丢失回滚后的新激活/绑定数据（生产低峰执行）。

## 六、兼容性说明
- 旧库升级重启即自动迁移，无需手工 SQL。
- 旧 APK（≤v4.1.8）+ 新发的 T1/T2 码：旧客户端无 tier 逻辑，激活时会把 tier 纳入 HMAC 校验场景处理不完整（README §5.1/§10-7 已注明“极端组合可能需重新激活”）；**存量 T3 码不受影响**。
- 新 APK v4.1.9 + 旧服务端（无 tier 字段）：App 保持 T3（不降权），可正常使用；升级服务端后新发码才生效。

## 七、实际部署记录（2026-10-23 20:40，已执行）

| 项 | 内容 |
|---|---|
| 主机 | `ubuntu@43.163.7.111`（sshpass + servers.env；sudo NOPASSWD） |
| 备份 | `/opt/license-server/backup/license-server.bak-20261023-2022`、`license.db.bak-20261023-2022`（192,512 B） |
| 旧二进制 | sha256 `3d643026f415fe711040d41f9d61cd0c95b926f8545ae6ba8f979e6e754e0462` |
| 新二进制 | sha256 `6d082066835cd738cb05258b9e48f92d24224072ce3a996b88bf4bbe736292be`（10,526,880 B；gz 4.5MB 上传受跨境带宽限制约 12KB/s，需 gzip+断点续传） |
| 服务 | `systemctl restart license-server` → active；`172.17.0.1:8787/healthz` ok；公网 `https://cs.888syd.xyz/healthz` ok |
| DB 迁移 | 自动补列 `tier INTEGER NOT NULL DEFAULT 3`；存量 5 个 active 码全部 `高级会员(3)`（不降权） |
| CLI 验证 | `license-ctl list` 显示 TIER 列；`issue -tier 1|2|3`（默认3）可用 |
| 回滚 | `cp backup/license-server.bak-20261023-2022 /opt/license-server/license-server && systemctl restart license-server`；DB 回滚同理（会丢回滚后新数据） |
| 备注 | 客户端 v4.1.8 及以下无 tier 逻辑；新发 T1/T2 码建议配合 v4.1.9 APK（≥23509）使用；存量 T3 码对旧客户端兼容 |
