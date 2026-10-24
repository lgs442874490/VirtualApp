# zcode agent 与 key 套餐使用核查（2026-10-24）

> 范围：核查 zcode 的 agent / key 套餐**是否已用于本仓库的开发与测试**、**桌面端是否提供 CLI**、
> **key 当前是否可用**（只记录 HTTP 状态，**不输出、不记录任何 key 明文**）。
>
> 方法：只读核查（`ls/stat/read`、只读 `grep`、一次最小 HTTP 探测、CLI `--help/version`）。
> 不修改任何代码/配置，不写 key，不调用业务接口。
>
> 快照时间：2026-10-24 21:2x–21:4x（Asia/Shanghai）。

---

## 0. 结论速览

| 问题 | 结论 | 关键证据 |
|---|---|---|
| **zcode agent + key 是否已用于本仓库开发/测试？** | **是**。两条路径都出现过：① `zcode` CLI（ZCode.app 内置 `zcode.cjs`）用于独立验证/执行（有本仓库操作记录）；② 2026-10-24 夜班用 `zcode key` 直连 BigModel coding API + 自建 `GLM Agent` 工具循环做开发（flash）/测试（glm-5.3） | 见 §2、§3 |
| **zcode 桌面端是否有 CLI？** | **有**。`/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs`（Electron-node 打包，`zcode 0.16.5`），支持 `--prompt/-p/--print/--cwd/--model` 等；实测 `version` 退出码 0 | 见 §5 |
| **key 当前是否可用？** | **可用**。`GET /api/coding/paas/v4/models` → **HTTP 200**；`POST /chat/completions`（`glm-5.3-flash`, `max_tokens=1`）→ **HTTP 200** | 见 §4 |
| **套餐模型是否覆盖开发/测试两档？** | **覆盖**。模型列表含 `glm-5.3` 与 `glm-5.3-flash`（另有 `glm-5.3-flashx`、`glm-5.x` 系列，共 11 个） | 见 §4 |
| **与文档记载是否有出入？** | **有 2 处**：① `夜班总结` 称「zcode 桌面端无 CLI」，与本地 CLI 事实不符；② `zcode-test` 包装脚本不会切到 `glm-5.3`，当前配置下测试通道仍可能是 flash | 见 §6 |

---

## 1. 核查项清单

| # | 核查项 | 结果 |
|---|---|---|
| 1 | `design/夜班总结-20261024.md` 的记载 | ✅ 已读，明确记录 zcode key 用途与模型分工 |
| 2 | `design/夜班技术债清理-20261024.md` 的记载 | ✅ 已读，记录 GLM Agent 执行方式与复核结论 |
| 3 | `~/.dsh-secrets/zcode-dev` 是否存在、用法 | ✅ 存在（可执行），dev 通道包装脚本 |
| 4 | `~/.dsh-secrets/zcode-test` 是否存在、用法 | ✅ 存在（可执行），test 通道包装脚本 |
| 5 | `~/.dsh-secrets/zhipu-coding.key` 是否存在 | ✅ 存在（权限 600，未读取/未输出内容） |
| 6 | `/tmp/glm_agent/agent.py` 是否存在、用法 | ✅ 存在（14.5 KB），直连 coding API 的工具循环 |
| 7 | zcode 桌面端是否有 CLI | ✅ 有（`zcode.cjs` 0.16.5） |
| 8 | key 当前可用性（最小探测） | ✅ 200 / 200 |

---

## 2. 文档记载（仓库内证据）

| 文档:行 | 记载要点 |
|---|---|
| `design/夜班总结-20261024.md:3` | 用户指示「恢复 zcode key（BigModel coding API：`glm-5.3-flash` 开发、`glm-5.3` 测试）」 |
| `design/夜班总结-20261024.md:4` | 「zcode 桌面端无 CLI，但 key 可直连 `https://open.bigmodel.cn/api/coding/paas/v4`（已验证 glm-5.3-flash / glm-5.3 可用）；Lead 自建 GLM Agent 工具循环（`/tmp/glm_agent/agent.py`）执行开发/测试」 |
| `design/夜班总结-20261024.md:41` | 「测试模型复核：`glm-5.3` 按固定命令跑全量并汇总：PASS（批次2）」 |
| `design/夜班总结-20261024.md:72-75` | 复现段：`GLM_API_KEY='<用户提供的 key>' GLM_MODEL='glm-5.3-flash' python3 /tmp/glm_agent/agent.py --cwd ... --task-file ... --max-steps 30`，并注明「开放式任务 flash 易探索超步；用精确编辑型 prompt 成功率高」 |
| `design/夜班技术债清理-20261024.md:3-4` | 「恢复使用 zcode key（BigModel coding API）开发；`glm-5.3-flash` 负责开发、`glm-5.3` 负责测试」；「Lead 自建 GLM Agent 工具循环…每个任务由 flash 开发、glm-5.3 测试复核」 |
| `design/夜班技术债清理-20261024.md:20,43-44` | 批次 2「glm-5.3 复核 PASS」；备注 flash 开放式任务易超步、改精确编辑型 prompt 后连续成功 |
| `design/变更方案-反应式开盾与守护盟友触发策略-20261024.md:4` | 本次改动「zcode key（BigModel coding API）用于独立复核」 |
| `design/七模块-1比1复刻-任务清单-20261023.md:4` | 「今晚 20:00 前不使用 zcode 套餐（本轮全部由 DSH 团队完成）」——说明套餐使用是按轮次人工决策的 |
| `design/自动化模块-实现落差与修复待办-20261018.md:7-8,36-37` | 更早一轮：「zcode 隔离验证」产出 `qa/evidence-20261018-verify/`；zcode 对运行态只读核验 |
| `qa/evidence-20261018-verify/provenance.md:1-…` | 以「zcode / qa 独立验证」为标题的 Gradle 跑测与会话记录（外部进程污染说明） |

> 归纳：**zcode（产品/CLI/key）不只是「计划」，在本仓库已有实际使用痕迹**——既有 2026-10-18 的
> 「zcode 独立验证」产物，也有 2026-10-24 夜班的「zcode key + 自建 agent」开发/测试记录。

---

## 3. 本地资产核查（存在性/权限/用途）

| 路径 | 存在 | 属性（只读观察） | 用途/内容摘要 | 证据 |
|---|---|---|---|---|
| `~/.dsh-secrets/zhipu-coding.key` | ✅ | `-rw-------`（600），49 B | 智谱 coding API key 文件；**本次未打印/未输出内容、未写入任何文档**（仅在一次探测命令中作为 `Authorization` 头由 shell 读入） | `ls -l` |
| `~/.dsh-secrets/zcode-dev` | ✅ | `-rwx------`（700），756 B | **dev 通道包装**：从 key 文件导出 `ZCODE_API_KEY` → `flock` 串行 → 备份 `~/.zcode/cli/config.json` → 临时把 `model.main` 改为 `zhipu-coding/glm-5.3-flash` → `exec node /Applications/ZCode.app/Contents/Resources/glm/zcode.cjs "$@"` → 退出 `trap` 恢复配置 | 本文件 §5.3 引用 |
| `~/.dsh-secrets/zcode-test` | ✅ | `-rwx------`（700），236 B | **test 通道包装**：仅导出 `ZCODE_API_KEY` 后 `exec node .../zcode.cjs "$@"`；**不修改模型配置**（见 §6 差异 2） | 同上 |
| `/tmp/glm_agent/agent.py` | ✅ | `-rw-------`，14 590 B（2026-10-24 04:04） | GLM 编码 Agent：OpenAI-compatible tool-calling loop，`GLM_BASE_URL` 默认 `https://open.bigmodel.cn/api/coding/paas/v4`，`GLM_MODEL` 默认 `glm-5.3-flash`；工具 bash/read/write/edit/glob/grep；`BLOCKED_BASH` 拦 `git commit/push/merge/reset/checkout/switch` 等 | `agent.py:25-36` |
| `/tmp/glm_agent/smoke.md` | ✅ | 260 B | 冒烟任务提示词 | `ls` |
| `/tmp/glm_agent/2026*`（10 个目录） | ✅ | 2026-10-24 04:04–05:04 | 每次 Agent 运行的产物目录（任务/日志） | `ls -lat` |
| `/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs` | ✅ | 12.6 MB（2026-09-04） | zcode CLI 入口（Electron-node bundle） | §5 |
| `/Applications/ZCode.app/Contents/Resources/glm/.node-bundle-meta.json` | ✅ | 143 B | `runtime=electron-node`、`entry=zcode.cjs`、`platform=darwin-x64`、`source=apps/zcode-cli/packages/cli/dist/zcode.cjs` | 文件内容 |
| `~/.zcode/cli/config.json` | ✅ | 600，1311 B | provider `zhipu-coding`（openai-compatible，baseURL=coding paas v4）；定义 `glm-5.3`、`glm-5.3-flash`；**当前 `model.main = zhipu-coding/glm-5.3-flash`** | §5.4 |
| `~/.zcode/cli/config.dev.json` | ✅ | 600，1311 B | dev 配置副本，`model.main` 同为 flash | §5.4 |
| `~/.zcode/cli/config.json.bak-1792508993` | ✅ | 157 B，仅 `plugins` 键 | 早期备份（无 model 段） | §5.4 |
| `~/.zcode/cli/exec/` | ✅ | 119 条目，目录 mtime 2026-10-22 23:49 | CLI 执行日志；**31 个文件内容含 `VAContainer`**（最后修改 2026-10-22 21:03） | `grep -rl VAContainer` |
| `~/.zcode/cli/artifacts/` | ✅ | 84 条目，目录 mtime 2026-10-23 00:06 | CLI 产物；**2 220 个文件内容含 `VAContainer`** | `grep -rl` 计数 |
| `which zcode` | ❌ | 未安装到 PATH | CLI 需用 `node .../zcode.cjs` 或上表两个包装脚本调用 | `which zcode` 退出码 1 |

> 结论：**key 与 agent 工具链是「本机已部署、可复现」状态**；CLI 存在但未加入 PATH，靠 `zcode.cjs` 绝对路径/包装脚本调用。

---

## 4. key 当前可用性（最小 API 探测，仅记录 HTTP 状态）

探测方式：从 `~/.dsh-secrets/zhipu-coding.key` 读取到 shell 变量后直接放进 `Authorization` 头，
**只输出 HTTP 状态码**；响应体仅解析「键名/模型 id」，随后删除临时文件。**全程未打印 key 明文。**

| # | 请求 | 端点 | HTTP 状态 | 结论 |
|---|---|---|---|---|
| P1 | `GET`（带 Bearer） | `https://open.bigmodel.cn/api/coding/paas/v4/models` | **200** | key 有效、coding 套餐端点可达 |
| P2 | `POST` `{"model":"glm-5.3-flash","messages":[{"role":"user","content":"hi"}],"max_tokens":1}` | `https://open.bigmodel.cn/api/coding/paas/v4/chat/completions` | **200** | key 具备 chat 调用能力；响应 `model` 回显 `glm-5.3-flash` |

P1 返回模型列表（共 11 个，按返回顺序）：
`glm-4.5`、`glm-4.5-air`、`glm-4.6`、`glm-4.7`、`glm-5`、`glm-5-turbo`、`glm-5.1`、`glm-5.2`、
**`glm-5.3`**、**`glm-5.3-flash`**、`glm-5.3-flashx`

→ 与夜班文档的「flash 开发 / glm-5.3 测试」分工**模型层面可支撑**。

> 安全声明：本文件与仓库内任何文件均**不含 key 明文**；探测命令使用 `$(cat …)` 读入，未 `echo`、
> 未写入日志；临时响应体已删除（`/tmp/.probe_*.json`）。

---

## 5. zcode 桌面端 CLI 核查

### 5.1 结论

**有 CLI。** 桌面端 App 包内自带一个 Electron-node 打包的 CLI：

```text
/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs   （12.6 MB，2026-09-04）
.node-bundle-meta.json: runtime=electron-node, entry=zcode.cjs,
                        platform=darwin-x64, source=apps/zcode-cli/packages/cli/dist/zcode.cjs
```

### 5.2 实测输出（不需要 key 的命令）

| 命令 | 退出码 | 输出摘要 |
|---|---|---|
| `node .../zcode.cjs version` | 0 | `0.16.5` |
| `node .../zcode.cjs --help` | 0 | 版本 `zcode 0.16.5`；子命令 `app-server / commands / doctor / login / logout / plugins / skills / tui / version`；选项含 `--prompt <text>`、`-p/--print`、`--cwd <path>`、`--model`、`--attach`、`--surface`、`--browser-use`、`--disallowedTools` 等；「无子命令时打开全屏 TUI」 |

### 5.3 本机封装的两个通道（`~/.dsh-secrets/zcode-dev` / `zcode-test`）

| 通道 | 行为 | 关键点 |
|---|---|---|
| `zcode-dev` | `ZCODE_API_KEY="$(cat ~/.dsh-secrets/zhipu-coding.key)"`；`flock /tmp/zcode-config-dev.lock` 串行；备份→把 `~/.zcode/cli/config.json` 的 `model.main` 改为 `zhipu-coding/glm-5.3-flash`→`exec node .../zcode.cjs "$@"`→`trap` 恢复配置 | 并发安全（flock + 备份恢复）；**固定 flash 模型** |
| `zcode-test` | `ZCODE_API_KEY="$(cat …/zhipu-coding.key)"`；`exec node .../zcode.cjs "$@"` | **不改配置文件** → 实际模型取 `config.json` 当前值（见 §6 差异 2） |

> 两个脚本都通过环境变量 `ZCODE_API_KEY` 传 key（bundle 中该变量名出现 1 次），
> 与 `~/.zcode/cli/config.json` 的 `provider.zhipu-coding`（openai-compatible，baseURL=coding paas v4）配合使用。

### 5.4 当前 CLI 配置（脱敏摘要）

| 配置项 | 值 |
|---|---|
| `provider.zhipu-coding.kind` | `openai-compatible` |
| `provider.zhipu-coding.options.baseURL` | `https://open.bigmodel.cn/api/coding/paas/v4` |
| 已定义模型 | `glm-5.3`（GLM-5.3）、`glm-5.3-flash`（GLM-5.3-Flash）；`contextWindow=1000000`；`supportsToolCall/supportsReasoning/supportsStructuredOutput=true` |
| `model.main` | `zhipu-coding/glm-5.3-flash` |

> 配置中未发现 key 字段（key 走环境变量），配置项里可能的敏感值本文件一律不记录。

---

## 6. 与文档记载的差异（重要，供 Lead 裁决，不做猜测）

| # | 文档记载 | 本地事实 | 影响 | 建议 |
|---|---|---|---|---|
| D1 | `夜班总结-20261024.md:4`：「**zcode 桌面端无 CLI**」 | `/Applications/ZCode.app/.../zcode.cjs` 存在且可运行（`0.16.5`，`--prompt/--cwd/--model` 齐备）；`~/.dsh-secrets/zcode-dev`/`zcode-test` 两个 CLI 包装脚本创建于 **2026-10-20/21**（早于夜班）；`~/.zcode/cli/exec`、`artifacts` 中存在 **31 / 2 220** 个含 `VAContainer` 的文件（最新 2026-10-22 21:03）→ CLI 曾被实际用于本仓库 | 夜班「无 CLI」的表述不准确，或只针对「桌面 GUI 没有独立命令行入口/当时未走通」；两种可能都**未实证** | 若需复现夜班口径：直接沿用 `agent.py` 直连 API（已验证）；若要用 CLI：用 §8 的 `zcode-dev`/`zcode-test` 包装。请 Lead 确认夜班当时的真实原因，避免文档继续漂移 |
| D2 | `zcode-test` 脚本注释：「测试通道：智谱 Coding Plan / `glm-5.3`」 | 脚本只导出 `ZCODE_API_KEY`，**不修改** `config.json`；当前 `config.json` 的 `model.main = zhipu-coding/glm-5.3-flash` | 直接跑 `zcode-test` 很可能仍是 **flash**，与「测试=glm-5.3」的分工不一致（**未实际运行验证**，仅静态推断） | 测试通道调用时显式加 `--model zhipu-coding/glm-5.3`，或改造脚本像 dev 那样临时改配置+flock+恢复 |
| D3 | 夜班执行路径 = key 直连 API + `/tmp/glm_agent/agent.py`（`夜班总结:4,72-75`） | 与 zcode CLI 并行存在（同一把 key） | 两条路径都可复现，但**并发时会争用同一套餐配额/限速**（配额细节未实证） | 同一时段只走一条路径；批量任务错峰 |

---

## 7. 使用建议

| 场景 | 推荐做法 | 理由 |
|---|---|---|
| **代码开发（低风险、精确编辑型任务）** | `GLM_MODEL=glm-5.3-flash` + `/tmp/glm_agent/agent.py`；prompt 里给「文件位置 + 目标代码块」 | 夜班实测：开放式 prompt 易超步，精确编辑型连续成功（`夜班技术债清理:44`） |
| **测试复核（全量门禁/汇总）** | `GLM_MODEL=glm-5.3`；或 zcode CLI `--model zhipu-coding/glm-5.3` | 与夜班「flash 开发 / glm-5.3 测试」分工一致 |
| **需要 zcode 产品能力（skills/plugins/app-server）** | `~/.dsh-secrets/zcode-dev --prompt ... --cwd "$PWD"` | 走 CLI 才能用到 skills/plugins/TUI；模型自动固定 flash |
| **并发跑多个 agent** | 必须沿用 dev 脚本的 `flock` + 配置备份/恢复；不要同时裸改 `~/.zcode/cli/config.json` | 否则模型配置互相覆盖、CLI 读到半写状态 |
| **key 管理** | 只用 `$(cat ~/.dsh-secrets/zhipu-coding.key)` 读入；禁止 `echo`、禁止写入仓库/文档/日志；文件保持 600 | 防泄漏；仓库内已有「hygiene-secrets」类扫描先例（`qa/evidence-20261018-verify/hygiene-secrets.txt`） |
| **禁止事项** | agent/CLI 均不得执行 `git commit/merge/push/checkout/switch` | 与 `AGENTS.md` 的强制流程一致；`agent.py` 已用 `BLOCKED_BASH` 拦截 |

---

## 8. 复现命令（均不输出 key）

```bash
# 1) CLI 存在性/版本（无需 key）
node /Applications/ZCode.app/Contents/Resources/glm/zcode.cjs version   # → 0.16.5

# 2) key 最小探测：只打印 HTTP 状态码
curl -s -o /dev/null -w '%{http_code}\n' --max-time 20 \
  -H "Authorization: Bearer $(cat "$HOME/.dsh-secrets/zhipu-coding.key")" \
  https://open.bigmodel.cn/api/coding/paas/v4/models                    # → 200

# 3) GLM Agent（开发 flash / 测试 glm-5.3）
GLM_API_KEY="$(cat "$HOME/.dsh-secrets/zhipu-coding.key")" GLM_MODEL='glm-5.3-flash' \
  python3 /tmp/glm_agent/agent.py --cwd "$PWD" --task-file /tmp/glm_tasks/<task>.md --max-steps 30

# 4) zcode CLI 两通道（dev 自动切 flash；test 需自行确认模型，见 §6-D2）
~/.dsh-secrets/zcode-dev  --prompt "只读检查：列出 design/ 下 2026-10-24 的文档" --cwd "$PWD"
~/.dsh-secrets/zcode-test --prompt "..." --model zhipu-coding/glm-5.3  --cwd "$PWD"
```

> 注意：`/tmp/glm_agent/agent.py` 位于 `/tmp`，**不随仓库持久化**；2026-10-24 用户批准后已归档到
> `tools/zcode/glm_agent.py`（配套 `tools/zcode/README.md`，不含 key），后续按 README 调用即可。

---

## 9. 未实证 / 风险清单

| # | 项 | 说明 |
|---|---|---|
| U1 | 套餐档位、额度、限速、计费规则 | 只验证了「能调用」，**未查询用量/限额**，也未做压测 |
| U2 | zcode CLI 用 `ZCODE_API_KEY` 直接鉴权的完整行为（是否还需 `zcode login` OAuth、token 过期策略） | 只确认 bundle 含该变量名与 help 文本；**未用 key 实跑一次 headless prompt**（避免额外消耗/不确定性） |
| U3 | `zcode-test` 通道实际命中的模型 | 静态推断为 flash（config 未改），**未运行验证** |
| U4 | 夜班「桌面端无 CLI」的真实背景 | 可能是「GUI 无命令行入口/当时未走通/仅用 API 更省事」，**不猜测**，待 Lead 澄清 |
| U5 | key 对应账号/套餐归属 | 未查询账户信息（无需且涉及隐私） |
| U6 | 多 agent 并发下的实际限速表现 | 无实测数据；dev 脚本的 flock 只解决**配置文件竞争**，不解决**配额竞争** |

---

## 10. 证据索引

| 主题 | 位置 |
|---|---|
| 用户指示与模型分工 | `design/夜班总结-20261024.md:3-5` |
| 「无 CLI」记载（待纠正/澄清） | `design/夜班总结-20261024.md:4` |
| glm-5.3 复核 PASS | `design/夜班总结-20261024.md:41` |
| GLM Agent 复现命令 | `design/夜班总结-20261024.md:72-75` |
| 夜班执行方式（flash 开发 / glm-5.3 测试复核） | `design/夜班技术债清理-20261024.md:3-4,20,43-44` |
| 本次变更的 zcode 用途 | `design/变更方案-反应式开盾与守护盟友触发策略-20261024.md:4` |
| 套餐使用按轮次决策 | `design/七模块-1比1复刻-任务清单-20261023.md:4` |
| 更早的「zcode 独立验证」产物 | `design/自动化模块-实现落差与修复待办-20261018.md:7-8,36-37`；`qa/evidence-20261018-verify/provenance.md:1-…` |
| key 文件（不输出内容） | `~/.dsh-secrets/zhipu-coding.key`（600） |
| dev/test 包装脚本 | `~/.dsh-secrets/zcode-dev`、`~/.dsh-secrets/zcode-test`（均 700） |
| GLM Agent 源码 | `/tmp/glm_agent/agent.py:25-27`（BASE/KEY/MODEL 默认值）、`:31-36`（`BLOCKED_BASH` 禁 git 写） |
| zcode CLI 入口与元数据 | `/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs`、`.node-bundle-meta.json` |
| zcode CLI 配置（脱敏） | `~/.zcode/cli/config.json`、`config.dev.json` |
| CLI 实际用于本仓库的痕迹 | `~/.zcode/cli/exec/`（31 个含 `VAContainer` 的日志）、`~/.zcode/cli/artifacts/`（2 220 个） |
