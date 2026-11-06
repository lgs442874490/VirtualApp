# license-server —— VAContainer 兑换码授权服务端

> 实现设计文档 §2.2 / §3.3 / §4 / §5 / §10（T01 + T02）。
> Go 标准库 `net/http` + 纯 Go SQLite 驱动 `modernc.org/sqlite`，**单静态二进制、无 CGO、无外部服务**。

---

## 1. 文件清单

| 文件 | 职责 |
|---|---|
| `go.mod` / `go.sum` | module `license-server`，仅依赖 `modernc.org/sqlite` |
| `main.go` | 入口：子命令分发（`initdb`/`serve`/`issue`/`revoke`/`list`/`rebind`）+ `serve` 启动与优雅关闭 |
| `config.go` | flag/env 配置加载（flag > env > 默认） |
| `model.go` | 结构体、错误码、枚举、常量 |
| `db.go` | 打开库、`//go:embed schema.sql` 幂等迁移、查询/写入封装 |
| `schema.sql` | 建表 DDL（照抄设计 §4.1，字段名未改动） |
| `codegen.go` | 兑换码生成（15 位随机 + 1 位加权校验位，剔除易混字符）、归一化与校验、**tier 参数解析** |
| `sign.go` | 规范化 JSON（canonical JSON）、HMAC 校验、时间戳窗口、nonce 防重放 |
| `server.go` | HTTP 路由与 `activate`/`verify`/`rebind` 处理器、nonce TTL 清理循环 |
| `cli.go` | `initdb`/`issue`/`revoke`/`list`/`rebind` 命令实现、换绑核心逻辑 |
| `deploy/Caddyfile` | 反向代理 + 自动 HTTPS 生产配置（域名 `cs.888syd.xyz`，由容器化 Caddy 加载） |
| `deploy/license-server.service` | systemd 单元（监听 `172.17.0.1:8787`，`EnvironmentFile=/etc/license-server.env`） |
| `deploy/license-ctl` | 运维 CLI 包装脚本（**必须经它跑 CLI**，否则会落到 cwd 下的空库，见 §4.1） |
| `sign_test.go` | 规范化 JSON / 校验位 / **签名测试向量** 的回归测试 |
| `tier_test.go` | **三级权限（tier）**：旧库迁移/默认 3/activate·verify 返回 tier/CLI `--tier` 的回归测试 |

---

## 2. 构建（单静态二进制）

```bash
cd license-server
go mod tidy                      # 首次拉取依赖（国内可加 GOPROXY=https://goproxy.cn,direct）
CGO_ENABLED=0 go build -o license-cli .
```

- `CGO_ENABLED=0` 也能编译，产出**可整包拷走的单文件**（Linux 交叉编译：`GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o license-cli .`）。
- 本机（darwin/amd64）实测产物约 **16 MB**。

---

## 3. 快速上手（本地）

```bash
# 1) 建库（幂等，可反复执行）
LICENSE_DB=./license.db ./license-cli initdb

# 2) 设置环境变量（必须设置 LICENSE_SECRET，否则 serve 拒绝启动）
export LICENSE_DB=./license.db
export LICENSE_SECRET='<至少32字节的随机串>'
export LICENSE_ADMIN_TOKEN='<运营方令牌>'
export LICENSE_ADDR='127.0.0.1:8787'

# 3) 启动服务
./license-cli serve
```

### 配置项（环境变量 / 同名 flag 覆盖）

| 环境变量 | flag | 默认 | 说明 |
|---|---|---|---|
| `LICENSE_DB` | `--db` | `./license.db` | SQLite 路径 |
| `LICENSE_SECRET` | `--secret` | 无 | **请求签名密钥；serve 必填，未设置直接拒绝启动** |
| `LICENSE_ADMIN_TOKEN` | `--admin-token` | 无 | 管理令牌（rebind 鉴权，常量时间比对） |
| `LICENSE_ADDR` | `--addr` | `127.0.0.1:8787` | 监听地址（仅监听本机，由 Caddy 终结 TLS） |
| `LICENSE_REBIND_MAX` | `--rebind-max` | `1` | 换绑次数上限，**默认 `1`（每码限 1 次）**；`0` = 不限 |
| `LICENSE_NONCE_TTL` | `--nonce-ttl-ms` | `900`（秒） | nonce 防重放记录存活时长（须 > 签名窗口 5 分钟，默认 15 分钟） |

```bash
# 试探活
curl -s http://127.0.0.1:8787/healthz
# {"code":0,"message":"ok","data":{"status":"ok"},"serverTime":...}
```

---

## 4. 部署（cs.888syd.xyz：systemd 后端 + 独立 Caddy 容器）

> **域名定版（v1.2 决策）**：`https://cs.888syd.xyz`（弃用原 `lic.888syd.xyz` 方案）。
>
> **关联拓扑（2026-11-06 核实）**：vault-server（业务/热更）生产在**广州 `43.139.170.103`**（ssh 别名 `gz`，ubuntu + `~/.ssh/clouddroid_deploy`），监听 `8790`；新加坡机经反向隧道（广州发起 `ssh -R 172.17.0.1:8791:127.0.0.1:8790`，密钥 `~/.ssh/vault_tunnel`）把 `8791` 暴露给 Caddy 反代。**旧地址 `103.236.96.82:64868`（ssh 别名 vault-vps）已废弃**。新加坡机登录：本机密钥均无权限，需从广州跳板 `ssh -i ~/.ssh/vault_tunnel ubuntu@43.163.7.111`（sudo 免密）。
> A 记录已存在（→ 43.163.7.111，新加坡测试机），证书由 Caddy 自动签发，**无需新增 DNS**。
>
> **当前拓扑（已上线）**：
>
> | 层 | 形态 | 监听 |
> |---|---|---|
> | 授权后端 | 宿主机 systemd `license-server` | `172.17.0.1:8787`（docker0 桥，**不对公网暴露**） |
> | TLS 终结 / 反代 | **独立 Caddy 容器** | `0.0.0.0:80/tcp`、`0.0.0.0:443/tcp` |
> | 原 CloudDroid 业务 | **已下线**（`docker compose down`，数据卷保留） | — |
>
> **为什么 Caddy 跑在容器里**：宿主机 UDP 443 被 `hysteria` 占用，Caddy 2.6.2 启动时会
> `listen udp :443: bind: address already in use`（该版本 Caddyfile 无关闭 HTTP/3 的开关）。
> 放进容器可获得独立 netns，UDP 443 空闲，且不干扰宿主机 hysteria。
>
> **为什么后端监听 `172.17.0.1` 而不是 `127.0.0.1`**：容器经 docker0 网桥访问宿主机，
> `host.docker.internal` 解析为 `172.17.0.1`；监听 `127.0.0.1` 会导致 Caddy 返回 502
> `dial tcp 172.17.0.1:8787: connection refused`。

步骤：

1. 上传 `deploy/license-server-linux`（或本机 `GOOS=linux GOARCH=amd64 go build` 产物）到 `/opt/license-server/`。
2. 建 `/etc/license-server.env`（600 权限）：`LICENSE_SECRET=<≥32字节随机串>`、`LICENSE_ADMIN_TOKEN=<随机串>`。**不要写进仓库或对话。**
3. 安装 systemd 单元 `deploy/license-server.service`（监听 `172.17.0.1:8787`）并 `systemctl enable --now license-server`。
4. 以容器方式起 Caddy（配置文件 `deploy/Caddyfile`）：

   ```bash
   docker run -d --name caddy-license --restart unless-stopped \
     -p 80:80/tcp -p 443:443/tcp \
     --add-host host.docker.internal:host-gateway \
     -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" \
     -v caddy_data:/data -v caddy_config:/config \
     caddy:2.6.2
   ```

5. 客户端 `LicenseConfig.BASE_URL` = `https://cs.888syd.xyz`（**不含 `/v1`**；`/v1/...` 由客户端按
   `LicenseConfig.PATH_*` 拼接，见 `LicenseApi.post`；若 BASE_URL 误带 /v1 会请求到 /v1/v1/activate 而 404）。

验证（三层）：

```bash
curl -s http://172.17.0.1:8787/healthz                            # 服务器本机探活（后端直连）
curl -s https://cs.888syd.xyz/healthz                             # 经 Caddy 的公网探活
# 密钥一致性：用 LICENSE_SECRET 对 /v1/activate 发 phase=hello 签名请求，
# 返回 data.salt 必须 == HMAC_SHA256(SECRET, "vp-fp-salt:v1")[:32]，否则说明进程用的不是该密钥。
```

> 排障提示：`/etc/license-server.env` 若为空值，服务仍能启动但签出的盐会不匹配（客户端必失败）。
> 修改 env 后必须 `systemctl restart license-server`，且**先验证长度再清理临时文件**（先验证后清理）。

### 4.1 运维 CLI 必须走 `license-ctl` 包装脚本

🔴 **踩过的坑**：`license-server` 的 `--db` 默认值是 `./license.db`（cwd 相对路径），
而 systemd 服务用的是 `/opt/license-server/data/license.db`。直接裸跑
`sudo /opt/license-server/license-server list` 会在 cwd 下**新建一个空库**，表现为
"共 0 条记录"；此时 `issue` 发出来的码写进了空库，**用户拿码激活必报 1001 码不存在**。
（`sudo` 还会清掉环境变量，所以光配 `LICENSE_DB` 也不够。）

修法两步，已在线上实施：

1. `/etc/license-server.env` 增加 `LICENSE_DB=/opt/license-server/data/license.db`
2. 部署包装脚本 `/usr/local/bin/license-ctl`（0755）：

   ```sh
   #!/bin/sh
   # license-ctl —— 让 CLI 与 systemd 服务共用同一份配置
   set -a
   . /etc/license-server.env
   set +a
   exec /opt/license-server/license-server "$@"
   ```

之后一律用 `sudo license-ctl list|issue|revoke|rebind ...`，**不要再裸跑二进制**。
验证方式：`sudo license-ctl list` 与 `curl -s http://172.17.0.1:8787/healthz` 指向同一份数据——
前者不带 `--db` 也必须能列出已签发的码。

---

## 5. 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/activate` | `phase=hello`（换盐）/ `phase=bind`（绑定出票） |
| POST | `/v1/verify` | 校验授权状态（服务端权威） |
| POST | `/v1/rebind` | 换绑（需 `adminToken`） |
| GET | `/healthz` | 探活 |

- 统一响应信封：`{ "code":0, "message":"ok", "data":{...}, "serverTime":<epoch ms> }`。
- **业务错误一律以 HTTP 200 + 信封 `code` 表达**（便于客户端统一解析；不产生 5xx，避免客户端误判为"离线"）。
- 错误码：`ok=0`；`1001` 码不存在、`1002` 格式错误、`1003` 已绑其他设备、`1004` 已停用、`1005` 已过期、`1006` 设备不匹配、`1007` 未激活、`1008` 换绑超限、`1009` 盐/挑战失效；`2001` 签名失败、`2002` 时间戳越界、`2003` nonce 重放、`2004` 参数非法；`3001` 服务端错误。
- **`verify` 返回码口径（冻结 §5.1）**：只要码**存在且格式合法**，`verify` 一律返回 **`code:0`**，业务结果放 `data.status ∈ {active, expired, revoked, device_mismatch}`（**封闭四值集合**）。`1004`（revoked）/`1005`（expired）/`1006`（device_mismatch）**绝不作为 `verify` 的返回码**（仅作为 `data.status` 语义常量）；`verify` 的非 0 业务码只有 `1007`（未激活），该分支**不返回 `data.status`**（保持四值集合封闭，客户端凭 `code=1007` 判定）。其余非 0 码仅来自协议层（`1001/1002`、`2001~2004`、`3001`）。
- 校验顺序（`sign.go`）：参数完整性 `2004` → 时间戳窗口 `2002` → 签名 `2001` → nonce 占用 `2003`（**先验签再占用 nonce**，避免伪造请求污染 nonce 表）。

### 5.1 授权等级（tier，批次7 三级权限，2026-10-23 新增）

兑换码在原有 `type`（`month`/`permanent`）之外新增**正交**的 `tier` 等级（1/2/3，与时长/金额无关）：

| tier | 语义 | App 功能范围（客户端门控） |
|---|---|---|
| `1` | 普通 | 只有分身 |
| `2` | 会员 | 日常脚本 |
| `3` | 高级会员 | 高阶功能 |

**响应字段**（只增字段、不改既有字段/口径，向后兼容）：

- `POST /v1/activate` → `data.ticket.tier`（int，1/2/3）
- `POST /v1/verify` → `data.tier`（int，1/2/3；未激活 `1007` 分支也会带该码的 tier）

```json
{"code":0,"message":"ok","data":{
  "ticket":{"ticketId":"t_...","code":"XXXX-...","type":"month","tier":2,
            "activatedAt":1792...,"expireAt":1793...,"serverStatus":"active"}},
 "serverTime":1792...}
```

**存量兼容（用户拍板：不伤害老用户）**：

- DB：`license_code.tier INTEGER NOT NULL DEFAULT 3`；`db.go ensureColumn` 对旧库幂等执行
  `ALTER TABLE license_code ADD COLUMN tier INTEGER NOT NULL DEFAULT 3` → **存量码一律 = 3（高级会员）**。
  旧库升级只需正常重启/`initdb`（自动迁移），无需手工 SQL、不会丢数据。
- 旧版服务端/旧客户端：响应缺 tier 时 App 按 `TIER_DEFAULT=3` 处理；旧票据（无 tier 字段）的
  HMAC 规范化串保持不变 → **旧票据签名继续通过、不失效**（详见 App 侧 `LicenseTicket#signableFields` 注释）。
- 新发码：`issue --tier 1|2|3` 指定；未指定默认 `3`。

---

## 6. 签名规范（**客户端必须字节级复现**）

### 6.1 待签名串

```
sign = HMAC_SHA256( APP_SECRET,
        "v1" + "\n" + METHOD + "\n" + OP + "\n" + ts + "\n" + nonce + "\n" + SHA256_HEX(body_without_sign) )
```

- `APP_SECRET`：服务端 = `LICENSE_SECRET`；客户端 = 编译期常量。
- `METHOD`：大写，如 `POST`。
- `OP`：**逻辑操作名，不含 `/v1` 前缀**，取值 `activate` / `verify` / `rebind`
  （架构师冻结修订，取代设计 §5.0 的"完整路径"写法；详见 §10-6）。用 OP 可避免版本升级 /
  Caddy 反代 `strip_prefix` 导致签名全线 2001。
- `ts`：epoch 毫秒（整数，十进制原文）。
- `nonce`：16 字节随机数的 32 位小写 hex。
- `SHA256_HEX(...)`：哈希输出的**小写 hex**。
- `sign`：输出**小写 hex**。
- **`body_without_sign` = 请求体去掉 `sign` 字段后的「规范化 JSON」**（见 6.2）。

### 6.2 规范化 JSON（canonical JSON）规则

对请求体对象执行如下处理，得到 `body_without_sign` 的字节串：

1. **对象键排序**：按 key 的 **UTF-8 字节序升序**排列（等价于普通字典序；数字/大写字母排在小写字母前）。
2. **无空白**：键值分隔固定 `:`，元素分隔固定 `,`，**不允许任何空格/换行/缩进**。
3. **嵌套对象**：递归，每一层的键都各自排序（不改变层级结构）。
4. **数组**：**保持原顺序**，元素递归规范化（数组元素若是对象，对象内键仍排序）。
5. **数字**：**按 JSON 原文原样输出**，不做重格式化——整数 `1712345678901` 仍是 `1712345678901`，**绝不变成 `1.712345678901e+12`**。（实现上用 `json.Number` 保留原文。）
6. **字符串转义**：
   - `"` → `\"`，`\` → `\\`，换行 → `\n`，回车 → `\r`，制表 → `\t`，退格 → `\b`，换页 → `\f`；
   - 其余 `U+0000`~`U+001F` 控制字符 → `\u00xx`（小写 hex）；另 **`U+2028`/`U+2029` 恒转义为 `\u2028`/`\u2029`**（JS 行分隔符，与 Go/Java 一致）；
   - **`/`、`<`、`>`、`&` 不转义**（保持原字符）；
   - **非 ASCII（含中文）不转义**，直接输出 UTF-8 字节（**不**写成 `\uXXXX`）；
7. **null**：输出字面量 `null`；**布尔**：`true` / `false`。

> 服务端在验签时**总是**对收到的请求体重新做上述规范化，因此客户端可以按任意顺序发送字段，**但计算 `sign` 时必须使用规范化后的字节串**。

### 6.3 测试向量（架构师冻结黄金向量，客户端单测对齐用）

以下向量取自 `.workbuddy/artifacts/license-signature-testvectors.md`（**架构师冻结修订版**，其 §0–§3 的 `/v1/...` 旧值作废），由独立 Python 与工程内真实 Java 实现双算并逐字节比对，**权威锚点**。

公共参数：`APP_SECRET = "vc2026-hj-license-9f3a7c1e5b8d2460"`、`ts = 1757844000000`、`nonce = 0123456789abcdef0123456789abcdef`、`METHOD = POST`；签名第 3 槽为 **OP**（不含 `/v1`）。

---

**向量 1 —— activate phase=hello（OP=`activate`）**

`canonical`（即 body_without_sign）：

```json
{"appVersion":"2.3.2","appVersionCode":23202,"nonce":"0123456789abcdef0123456789abcdef","phase":"hello","pkg":"com.carlos.multiapp","ts":1757844000000}
```

- `SHA256_HEX(canonical)` = `0271bbc23d680d85fca6fffc7422125904fe39a7374cde737e5c076e10d60d11`
- `sign` = `c245e9280564bfe4e67079ae2d9eec1f5eba147257cab691c363c6ae561ae319`

---

**向量 2 —— activate phase=bind（OP=`activate`）**

`canonical`（`deviceHash` = 64 个 `a`，`salt` = 32 个 `b`）：

```json
{"appVersion":"2.3.2","appVersionCode":23202,"challengeId":"9f3a7c1e5b8d2460","code":"K7M9-PQRS-TUVW-XYZL","deviceHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","deviceLabel":"vivo V2141A","nonce":"0123456789abcdef0123456789abcdef","phase":"bind","pkg":"com.carlos.multiapp","salt":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","ts":1757844000000}
```

- `SHA256_HEX(canonical)` = `6109e54289a78209e5aab99a0ff1a1228ae460d9acbd1a57d0b62bb760da0c7e`
- `sign` = `9debd9c6c260baf16f63674f7b6880691994a0e62055b12cc6ff6c625c166d85`

---

**向量 3 —— verify（OP=`verify`）**

`canonical`：

```json
{"appVersion":"2.3.2","code":"K7M9-PQRS-TUVW-XYZL","deviceHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","nonce":"0123456789abcdef0123456789abcdef","pkg":"com.carlos.multiapp","ticketId":"t_1_deadbeef","ts":1757844000000}
```

> 勘误（2026-10-06，测试期发现）：本向量 canonical 此前误印了 `"appVersionCode":23202,` 字段（照抄向量 1 格式所致）。权威定义以 `sign_test.go` `TestFrozenGoldenVectors` 为准——verify 向量**不含** appVersionCode；经 Python 独立复算与其 SHA 一致（6964…b3f57）。

- `SHA256_HEX(canonical)` = `696469710df51e763b9e968d5502ecd285cc0e89e1cde6346ef7f53b8c1b3f57`
- `sign` = `9665e4144a3ad9b3baf6305d3d8d4cab2af324f2f55bbe1466854838f333fd67`

---

**向量 4 —— 转义边界（回归 `<>&` / 中文 / 控制字符 / U+2028）**

`deviceLabel` 取 `a"b\c` + 换行 + `d<e&f>中` + `U+0001` + `U+2028` 时，`canonical` 必须为：

```json
{"deviceLabel":"a\"b\\c\nd<e&f>中\u0001\u2028","nonce":"0123456789abcdef0123456789abcdef","pkg":"com.carlos.multiapp","ts":1757844000000}
```

- `SHA256_HEX(canonical)` = `3ab81a5f66a9c8a00f69638b165af1d078e761342fdfe87055493bdce145ac2b`（UTF-8 字节长度 **138**，架构师独立复算）
- 本向量**刻意不配 `sign`**：它是"转义器"回归固件、与端点无关。

> 若服务端误用 Go 默认 `json.Marshal`（HTML 转义开启），`<>&` 会写成 `\u003c\u003e\u0026`，本向量立刻失败。
> 追加边界：输入 `\b\f\u0001` → 输出 `{"d":"\b\f\u0001"}`（`\b` `\f` 用短转义）。

> 复跑方式：`go test -v -run 'TestFrozenGoldenVectors|TestEscapeBoundary' ./...`（上述向量已内置为服务端回归测试，逐字节断言 canonical / SHA256 / sign / 字节长度）。

---

## 7. 运维命令

```bash
# 发码：默认 month（30 天）；--tier 指定授权等级（1=普通/2=会员/3=高级会员，默认 3）；--out 导出 CSV
./license-cli issue --type month     --tier 3 --count 10 --remark "首批高级会员" --out codes.csv
./license-cli issue --type month     --tier 2 --count 5  --remark "会员码"
./license-cli issue --type permanent --tier 3 --count 1  --remark "终身高级会员"
# 不传 --tier = 默认 3（与存量码口径一致，兼容旧脚本）

# 查列表：--status active|revoked|all（默认 all）；输出含 TIER 列
./license-cli list --status active
./license-cli list --status all

# 吊销：--code 或 --code-id，--reason 记录原因
./license-cli revoke --code XXXX-XXXX-XXXX-XXXX --reason "退款"
./license-cli revoke --code-id 12 --reason "误发"

# 换绑（需 admin-token；常量时间比对）
LICENSE_ADMIN_TOKEN=... ./license-cli rebind --code XXXX-XXXX-XXXX-XXXX \
    --new-device-hash <新机加盐指纹> --reason "换机"
```

> 旧库升级：升级二进制后正常 `initdb`/重启即可，`db.go ensureColumn` 会自动补
> `license_code.tier INTEGER NOT NULL DEFAULT 3`（存量码一律 = 3 高级会员），无需手工 SQL。

`list` 输出示例（本地时区可读，库里存 epoch 毫秒 UTC；TIER 列形如 `高级会员(3)`）：

```
CODE                 TYPE       TIER        STATUS   DEVICE       ACTIVATED            EXPIRE               REMAIN
5WX4-QF6L-XKAF-ETTY  permanent  高级会员(3) active   63WC...     2026-09-14 14:54:56  -                     永久
63WC-S3HA-JWYC-UHMW  month      会员(2)     active   Pixel 8 Pro 2026-09-14 14:55:35  2026-10-14 14:55:35  30 天
2MAV-A5AN-PCUP-8HB8  month      普通(1)     revoked  vivo V2141A 2026-09-14 14:54:53  2026-10-14 14:54:53  已停用
```

---

## 8. 数据库备份（§12-D）

SQLite 用在线备份命令 `.backup`（WAL 模式下安全，不锁库）：

```bash
sqlite3 license.db ".backup 'backup-$(date +\%Y\%m\%d-\%H\%M).db'"
```

`crontab -e` 样例（每天 03:30 备份、保留 14 天）：

```cron
30 3 * * * cd /opt/license-server && sqlite3 license.db ".backup '/opt/license-server/backup-$(date +\%Y\%m\%d).db'" >> /var/log/license-backup.log 2>&1
35 3 * * * find /opt/license-server -name 'backup-*.db' -mtime +14 -delete
```

---

## 9. 日志与安全

- **绝不落明文 `code`**；日志只输出 `code_id` 与**截断的** `device_hash`（前 8 位），见 §11.8 与 `server.go:logAction`。
- `adminToken` 与 `LICENSE_SECRET` 比对均使用 `crypto/subtle.ConstantTimeCompare`。
- `LICENSE_SECRET` 未设置时 **serve 拒绝启动** 并打印明确指引（不会用空密钥静默运行）。
- nonce 表 `used_nonce` 由后台 goroutine 每 5 分钟清理一次（TTL 默认 15 分钟）。

---

## 10. 与设计文档的偏差说明

1. **`verify` 的 `data.status` 遵循冻结契约（封闭四值集合，非偏差）**：`status ∈ {active, expired, revoked, device_mismatch}`。未激活（`1007`）是 `verify` 唯一保留的非 0 业务码，该分支**不返回 `status` 字段**（客户端凭 `code=1007` 判定为未激活）。
2. **CLI `rebind` 直接对本地库执行**（而非回调 HTTP 接口）：运营方持有本地库访问权即等同运营权限，直连库更简单、更稳、无需运营方持有 APP_SECRET 去签 HTTP 请求。仍按要求校验 `--admin-token`（常量时间比对，与 `LICENSE_ADMIN_TOKEN` 一致）。远程换绑则由 `/v1/rebind` 提供（同样强制 `adminToken`）。
3. **`/v1/rebind` 的 adminToken 校验失败返回 `2001`**：设计 §5.1 未定义"管理令牌错误"专用码，就近复用 `2001 BAD_SIGNATURE`（同为鉴权失败），并在 `message` 中明确"管理员令牌校验失败"。
4. **响应字段 `code_expr`** 按 §5.4 原文保留（名称虽不自然，但保持契约一致）。
5. **`LICENSE_REBIND_MAX` 默认值取 `1`（每码限换绑 1 次）**：设计 §12-C 原文为"默认不限（`REBIND_MAX=0`）"，此处按**用户拍板**改为默认 `1`（每码限 1 次）；需要放开时设 `LICENSE_REBIND_MAX=0`。`LICENSE_NONCE_TTL` 为新增可选配置项（默认 `900` 秒，不影响设计契约）。
6. **签名待签名串第 3 槽用 `OP`（`activate`/`verify`/`rebind`）而非完整路径**：设计 §5.0 原文为 `PATH`（含 `/v1` 前缀）。架构师已于 2026-09-14 **冻结修订为 `OP`**（见 `.workbuddy/artifacts/license-signature-testvectors.md` 顶部「架构师冻结修订」小节，其 §0–§3 的 `/v1/...` 旧值作废），理由是与版本升级 / Caddy 反代 `strip_prefix` 解耦、防跨端重放能力等价。服务端已按冻结版实现，并由 §6.3 黄金向量 + `sign_test.go`（`TestFrozenGoldenVectors`）逐字节锁定。
7. **`license_code.tier`（批次7 三级权限，2026-10-23 新增）**：设计文档 §4.1 原表无等级字段，本批按用户需求新增 `tier INTEGER NOT NULL DEFAULT 3 CHECK (tier IN (1,2,3))`；旧库由 `db.go ensureColumn` 幂等补列，存量码 = 3（高级会员）**不降权**。`type`（month/permanent，时长）与 `tier`（功能档位）正交；`activate` 响应 `data.ticket.tier`、`verify` 响应 `data.tier` 为**新增字段**，既有字段与错误码口径不变（旧客户端忽略即可）。

---

## 13. 管理后台（2026-11-06）

- **入口**：`https://cs.888syd.xyz/admin`（现有 Caddy 为整域 `reverse_proxy`，`/admin` 自动反代到同一 upstream，**无需改 Caddyfile**）。
- **口令**：在 `/etc/license-server.env` 增加 `LICENSE_ADMIN_PASSWORD=<≥16 字符随机串>`，然后 `systemctl restart license-server`。
  - 未配置口令时 `/admin` 与 `/admin/api/*` 返回 503；授权接口 `/v1/*` 不受影响。
- **功能**：概览、兑换码（批量生成 1–100 / 筛选 / 启停 / 使用状态 / 使用记录 / CSV 导出）、
  功能权限矩阵（T1/T2/T3/GM × 39 项功能）、功能目录、操作审计。
- **数据库迁移**：启动时幂等建 `feature_catalog`、`role_feature`、`admin_audit` 表，并给
  `license_code` 补 `is_gm` 列；旧库无需手工 SQL（迁移前建议 `.backup`）。
- **GM 码**：后台等级选 GM，或 CLI `issue --tier gm`；`activate/verify` 下发 `role=4`
  （`tier` 仍为 3，旧 App 按高级会员处理）。GM 仅开放"已实现且非调试包"功能。
- **矩阵生效**：后台保存后服务端立即生效；App 下次启动/进主界面 verify 时拉取并落盘
  （离线用票据缓存；旧 App 忽略新字段、回退硬编码）。
- **接口**：`POST /admin/api/login|logout`、`GET /admin/api/overview|codes|features|matrix|audit`、
  `POST /admin/api/codes/generate`、`POST /admin/api/codes/{id}/revoke|restore`、
  `POST /admin/api/codes/batch`、`GET /admin/api/codes/{id}/logs`、`PUT /admin/api/matrix`、
  `POST /admin/api/matrix/reset`、`GET /admin/api/codes/export`、`GET /admin/api/codes/{id}/logs/export`。
- **安全**：单管理员口令（常量时间比较）、会话 cookie（HttpOnly/SameSite=Strict，8h）、
  同 IP 连续失败 5 次锁 10 分钟、写操作 Origin 校验、审计只增不删。
- **验收自检**：
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' https://cs.888syd.xyz/admin/       # 200（未配口令则 503）
  curl -s https://cs.888syd.xyz/healthz
  ```
