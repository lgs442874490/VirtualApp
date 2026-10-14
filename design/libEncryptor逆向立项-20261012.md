# 立项：逆向无尽冬日本地资源加密（HTPX/2101 自研格式）—— 解锁活动配置本地提取

> 立项日期：2026-10-12；同日两轮侦察预跑完成（见第 3 节，**含一项重要排除结论**）。
> 承接会话直接从第 4 节开发步骤开始，工具底座已在 `tools/bundle_re/unityfs_probe.py`。
>
> ⚠️ 标题里的 libEncryptor.so 已被预跑**排除**（它是字节跳动 ttEncrypt 统计加密，
> 与资源加密无关，见 3.1）——真正的目标是 libil2cpp.so 内的自研解密器。
> 文件名保留是为了与本会话对话记录对齐。

---

## 1. 目标与价值

**终极目标**：解开游戏 `com.gof.china`（无尽冬日，Unity 2022.3.52f1 + il2cpp）的
自研资源加密（范围：global-metadata.dat、*.unity3d 逻辑配置包、模式 .bytes 配置），
实现 Mac 侧一键提取本地活动配置（商品表/任务表/阈值表/文案表）为 JSON。

**直接收益**（按优先级）：
1. 雪原贸易站（活动族 2702，进行中，约 10-14 结束）的兑换档位目录 → 补全
   `act_snowtrade` 的 `buys` 默认参数；
2. 协议盘点文档（`design/协议盘点-登录全量推送242协议与功能对标-20261012.md`）B 组全部
   "可立即新增"功能的配置表本地确认，不再依赖协议监听猜 req 参数；
3. C 组玩法型功能立项时的配置来源；
4. 长期：游戏版本更新后的配置差异本地 diff。

**边界（本立项不做）**：不做游戏客户端流量劫持；不做服务器交互伪造；提取产物仅用于
号库自动化配置，不产出可再分发资源。

---

## 2. 已确认事实（实测，承接会话免踩坑）

### 2.1 资源位置（设备 192.168.1.7:38733，vivo V2141A，无 root）

| 资产 | 路径 | 大小 | 可读性 |
|---|---|---|---|
| 逻辑配置包 | `/sdcard/Android/data/com.gof.china/files/data/luapatch_64_1d9bc592dfc222df5f942c389de1e86b.unity3d` | 879,367 B | 世界可读 ✓ |
| 32 位孪生 | 同目录 `luapatch_32_2477b748….unity3d` | 891,230 B | ✓ |
| 资源 bundle 海 | 同目录 `*.unity3d`（7686 个，多为 art/actor/scene） | — | ✓ |
| il2cpp 元数据 | `/sdcard/Android/data/com.gof.china/files/il2cpp/Metadata/global-metadata.dat` | 10,078,952 B | ✓ |
| 游戏原生库 | `/data/app/~~qf9FX-lDoYy4nFq-TrJU_Q==/com.gof.china-A3jO1QhCY70ZIt3y7yzOWw==/lib/arm64/` | — | 世界可读 ✓ |
| **加密嫌疑库** | 同目录 `libEncryptor.so` / `libEncryptorP.so`（**两者同为 75,744 B，疑同一文件**） | 75,744 B | ✓ |
| libil2cpp.so | 同目录，60,164,120 B | — | ✓ |
| 独立配置样例 | `files/minewar/map_minewar_config_{item,setting,terrain}_*.bytes`、`files/garden/…` | — | ✓（内容为二进制非明文） |

再获取命令（ artifacts 会过期，/tmp 不持久）：
```bash
adb -s 192.168.1.7:38733 pull "/sdcard/Android/data/com.gof.china/files/data/luapatch_64_1d9bc592dfc222df5f942c389de1e86b.unity3d" tools/bundle_re/luapatch64.sample.unity3d
adb -s 192.168.1.7:38733 pull "/data/app/~~qf9FX-lDoYy4nFq-TrJU_Q==/com.gof.china-A3jO1QhCY70ZIt3y7yzOWw==/lib/arm64/libEncryptor.so" tools/bundle_re/libEncryptor.so
```
本目录已有固化样本：`luapatch64.sample.unity3d`（luapatch64.unity3d 拷贝）、`libil2cpp.so`
（60MB，已被 .gitignore 排除，勿提交）。

### 2.2 bundle 头部实测（样本=luapatch_64）

```
signature = "UnityFS\0"
version   = 2101        ← 非标准（标准为 6/7）；疑为 CN 定制加密标记
unity     = ".x.x" / "2022.3.52f1"
size      = 879,367（=文件大小）
cinfo     = 192         （blocksInfo 压缩尺寸）
uinfo     = 331         （blocksInfo 解压尺寸）
flags     = 0x243       → 数据块压缩=LZ4HC(3)；bit6(0x40)置位=blocksInfo 在尾部；
                          bit7(0x80)未置位（但 cinfo≠uinfo，语义矛盾，见 2.3）
head_end  = 50          （数据块区起点）
```

### 2.3 blocksInfo 判定（关键卡点）

尾部 192 B 三种解释全部失败（`unityfs_probe.py parse` 已自动化验证）：
- 明文：blockCount=3,909,280,391 不合理；
- LZ4：首 token 后偏移 59,896 > 已解 14 B，非法；
- LZMA：流头不识别。

尾部 192 B 有强 4 字节周期性（`e9 02 xx xx | e9 02 xx xx | ea 02 …`，首字节 e9→ea→eb
缓增、次字节恒 0x02、共 48 项且整表有序）——**这不是压缩流，是自研结构表**
（48×u32 有序索引/哈希），进一步佐证 2101 是自研格式而非魔改 UnityFS。

### 2.4 数据块区观察

数据块区（偏移 50 起）手工逐 token 验证 LZ4 流合理（`1e 00 01 00` = 字面 1×00 +
匹配 18 → 产出零串）；贪心解码 879KB → 1.85MB，捞到真实标识符
（`ID activity_warzone_`、`notify_`×15、`activity`×276）。**数据内容大概率仍为
LZ4 压缩**，只是块边界表（192 B）被自研结构替代——一旦结构 reverse 出来，
现有 LZ4 解码器即可全量还原。

### 2.5 历史对照

2026-10-04 曾从 luapatch 常量表提取 2125 个活动名（`design/游戏活动名候选-luapatch提取.txt`），
说明**数据块内容可读性已被验证**；当时结论"名字-id 映射需 unluac 级反编译"仍然成立。
笔记原文见 `号库壳开发笔记.md` 搜索"luapatch"。

### 2.6 目标配置的已知线索（用于验收比对）

- 雪原贸易站 = 活动族 **2702**，当前实例 id **2702001**（服务端快照实证，
  `notify_value_exchange_init`，结束时间 1791907200 ≈ 2026-10-14）；
- 兑换档位 idx **132 / 155**（132 疑似免费档：K913 手动兑过、`is_free_exchange:"true"`）；
- 协议：`req_value_exchange_exchange{idx,count}`、`req_value_exchange_free_exchange{idx}`；
- 活动名"雪原贸易站"五个字**一定在** luapatch 常量表里（活动名候选文件 2046 行）——
  解密成功的最直接验收：全文搜索能命中它，且附近能找到 2702 相关数值表。

---

## 3. 预跑侦察结论（2026-10-12 第二轮，全部实测）

### 3.1 排除项：libEncryptor.so 与资源加密**无关**

- 导出符号仅 `JNI_OnLoad`，方法经 RegisterNatives 动态注册；
- 注册串实证：`ttEncrypt`、签名 `([BI)[B`——**字节跳动 ttEncrypt 统计上报
  流量加密 SDK**（与游戏数据目录里 zeus_gbsdk/volc_log/apminsight 同族）；
- 内含 AES 逆 S-box（@0xBD60）与 SHA256 常量（@0xDFF4），为其自身算法；
- libEncryptor.so 与 libEncryptorP.so sha256 不同（861a93… vs 480a0d…）。

### 3.2 新主线：全套资源走自研加密，解密器在 libil2cpp.so

| 证据 | 实测 |
|---|---|
| global-metadata.dat 魔数 | `HTPX`（标准 il2cpp 为 0xFAB11BAF）→ 自研加密元数据 |
| .unity3d bundle | 仿 UnityFS 头 + 版本 2101，无标准块表 → 自研打包 |
| libil2cpp.so 字符串层 | 无 UnityFS/HTPX/UnityCN/2101 任何痕迹 → 魔数比较用 ARM64 立即数编码 |
| libunity.so 字符串层 | 同上全无 → 引擎也未参与，读取器在游戏侧 |

→ **解密器必然在 libil2cpp.so 代码段**（il2cpp 启动第一步就是解密 metadata，
否则游戏无法运行；bundle 读取器同源概率极高）。

### 3.3 已拉取固定资产（`tools/bundle_re/`，均被 .gitignore 排除）

| 文件 | 大小 | 用途 |
|---|---|---|
| `luapatch64.sample.unity3d` | 879,367 B | 解密验收样本 |
| `libEncryptor.so` | 75,744 B | 已排除，留存备查 |
| `libil2cpp.so` | 60,164,120 B | **主目标** |
| `libunity.so` | 21,301,608 B | 已排除（无 bundle 代码），留存备查 |
| `global-metadata.dat` | 10,078,952 B | 解密验收样本（HTPX） |
| `luapatch64_greedy.bin` | 1,847,886 B | 贪心解码产物（错位，参考用） |

---

## 3. 卡点定义（本立项要解决的一个问题）

> **求 libil2cpp.so 内的自研解密器 f，使得：**
> 1. `metadata_plain = f(global-metadata.dat)`（验证：明文含 il2cpp 标准头
>    0xFAB11BAF 或可读 C# 字符串）；
> 2. `bundle 结构表 = g(luapatch_64 尾部 192 B)`（验证：块数合理、可按块 LZ4
>    全量解出、能搜到"雪原贸易站"）。

可能形态（按先验概率排序）：
1. 自研流加密（XOR keystream / RC4 / TEA 族），key 硬编码于 libil2cpp.so
   —— ARM64 立即数比较已证实作者有"防字符串搜索"意识，key 也可能混淆存放；
2. AES 族（key+iv 嵌 so 或派生自文件名/包名）；
3. 标准算法 + 自定义前置头（HTPX/2101 后有自研头部字段），剥头后即标准流；
4. 白盒密码（算法与 key 融合在查表里，难度最高）。

---

## 4. 开发步骤（承接会话从这里开始）

### 阶段 0：准备（半小时）
1. `git branch` 确认在 `add/libencryptor-re-20261012-1400`；
2. 反汇编器：**Ghidra**（首选，libil2cpp 60MB 也能吃）/IDA；命令行兜底
   `objdump -d`（本机 XCode CLT 自带，支持 aarch64）；
3. `pip` 离线（PyPI 不可达），Python 3.12 可用；LZ4 解码器已在
   `unityfs_probe.py` 内置，无需 lz4 包。

### 阶段 1：定位 libil2cpp.so 内的解密函数（1-3 小时）

字符串层已确认搜不到（魔数比较用 ARM64 立即数编码），必须下到指令层：

1. **立即数搜索法（首选，成本低）**：
   `objdump -d libil2cpp.so > /tmp/il2cpp.asm`（约 60MB→数百 MB 文本），
   搜索 MOVZ/MOVK 对编码的魔数：
   - `HTPX` 的 u16 对：`0x5448`（"HT"）与 `0x5850`（"PX"），即形如
     `movz wN, #0x5448` + `movk wN, #0x5850, lsl #16` 的相邻指令对
     （字节序变体再试 0x5058/0x4854）；
   - 标准 il2cpp 魔数 `0xFAB11BAF` 的编码对（`0x1BAF`/`0xFAB1` 等）——
     解密函数通常先验标准魔数（解密产物要过 il2cpp 自己的校验）；
2. **特征代码搜索法**：搜 AES/TEA/RC4 常量的**指令引用**（同上方法：
   sbox 首双字 `0x7b777c63` 的 MOVZ/MOVK 对）；
3. **交叉引用法**：找到 metadata 解密函数后，顺其调用图找 bundle 头
   （"UnityFS" 立即数 `0x4653/0x7955`…）与 `2101`(0x0835) 比较点；
4. 命中后 Ghidra 反编译该函数，回答：key 来源、算法主体、加密范围
   （全量/头部 N 字节/每块前 N 字节）。

### 阶段 2：算法复现（1-3 小时，视复杂度）
1. Python 复现解密（AES 无 pycryptodome 时用 `openssl enc -aes-128-ecb -K … -nopad`
   命令行兜底；TEA/XOR/RC4 纯 Python 即可）；
2. **验证锚点 A（metadata）**：解密 global-metadata.dat 后头部应出现
   0xFAB11BAF（或直接能 `strings` 出 C# 类名）；
3. **验证锚点 B（bundle）**：对 luapatch_64 尾部 192 B 解出后——
   长度 ≥ 331（≥uinfo）；块表结构自洽；按块 LZ4 解压总长 ~1.85MB 量级
   （贪心解码参考值）；能搜到"雪原贸易站"。

### 阶段 3：全量提取（1-2 小时）
1. 解密 metadata → 用 Il2CppDumper（或手写解析）拿 C# 类/方法表；
2. 解密 bundle 结构表 → 按块 LZ4 解压 → TextAsset（lua 字节码）；
3. 搜索验收词：`雪原贸易站`、`value_exchange`、`2702001`；
4. lua 字节码：常量字符串直接可读；结构化配置表上 unluac
   （Java 工具，JDK17 在 `/Users/liangguansheng/java/jdk-17`，jar 需离线获取——
   拿不到则退化为常量表+邻近数值人工对齐，笔记证明该路可行）；
5. 定位 value_exchange/2702 配置表 → 提取 `goods[]{idx, item_id, cost, limit}`。

### 阶段 4：产品化集成（1 小时）
1. 产出 `tools/bundle_re/out/value_exchange_2702.json` 等目录文件；
2. 雪原贸易站目录并入 `act_snowtrade`（转成 FnParams 默认 `buys`，或
   FunctionActivity 设置页加只读展示）；
3. 固化 `tools/bundle_re/extract.py <bundle> <关键字>` 一键流程；
4. 以后每次游戏热更后重跑即可拿最新配置。

---

## 5. 验收标准

- **M1**：Python 复现解密，global-metadata.dat 解出 0xFAB11BAF/可读 C# 串；
- **M2**：luapatch_64 全量解出后 `grep 雪原贸易站` 命中；
- **M3**：提取出 value_exchange 2702 的商品表 JSON（含 idx 132/155 的 item 与 limit），
  与游戏内人工记忆比对一致；
- **M4**：`act_snowtrade` 配上真实 `buys` 默认值，跑一轮自动兑换成功（运行日志 CLAIMED）。

M1-M2 达成即视为"啃下来了"（加密已破）；M3-M4 是收益兑现。

---

## 6. 备选路径（主线卡死时的降级）

| 路线 | 做法 | 适用 |
|---|---|---|
| B1 贪心恢复增强 | 贪心解码按块边界自相似做二次对齐（枚举块大小 64K/256K/512K/1M），配合已知明文（`notify_` 前缀）校准 | 只需要常量字符串时 |
| B2 动态插桩（强推） | **模拟器**（x86_64 AVD `emulator-5554` 已就绪）装游戏 + frida-server，hook metadata/bundle 读取点直接拿明文与密钥。注意游戏带模拟器检测（keva 里见 `osdk_emu_detect`），需预先绕过 | 静态卡壳时 |
| B3 白盒比较 | 找其他渠道服/国际服（Whiteout Survival）同版本对比 | 找得到对照包 |
| B4 人工对照 | 用户游戏内报档位（132/155 内容+限购），先解雪原贸易站燃眉，提取线继续异步 | 立刻要结果 |

---

## 7. 环境备忘（承接会话必读）

- 设备：`192.168.1.7:38733`（vivo V2141A，WiFi adb，无 root）；另一台模拟器
  `emulator-5554`（x86_64）可用但未装游戏；
- **vivo 装机确认**：`adb install` 会弹安全确认框，需 UI 点「已了解风险」+「继续安装」，
  uiautomator 实测坐标 (720,2800)/(720,2995)；装前先 `am force-stop com.carlos.multiapp`
  和 `com.gof.china`（保活服务会反复拉起游戏卡住安装）；
- `pip` 不可达 PyPI；Python 3.12（`/Library/Frameworks/...`）；JDK17
  `/Users/liangguansheng/java/jdk-17`；gradlew 可用；
- 号库 debug 包可 `run-as com.carlos.multiapp` 读私有目录（proto_log、虚拟游戏数据）；
- 本目录 `.gitignore` 已排除大二进制（*.so/*.unity3d/*.bin/*.dat）——样本不入库，
  用 2.1 的命令重拉；
- 协议层工具：`app/src/main/assets/wjdr/game_proto_{c2s,s2c}.spb` 可直接按
  字符串token 侦察（协议盘点会话已验证）， ReqArgsSchema 挖掘脚本思路见
  本会话对 `req_world_march_detail_4_player_situation` 的参数挖掘（sproto 文本
  段紧邻协议名）。

## 8. 当前仓库状态（交接快照）

- 分支 `add/libencryptor-re-20261012-1400`（自 `add/subswitch-gating-20261012-0350` 切出，
  后者含：三页一致性修复、45+ 子开关门控、协议修复、成就/签到族、雪原贸易站骨架，
  **均未 commit**——含用户自己的未提交改动（HomeActivity「发现角色」、VaultEngine
  /proc 进程检测），提交需用户逐项确认）；
- 本立项新增（工作树内，未 commit）：
  - `design/libEncryptor逆向立项-20261012.md`（本文档）；
  - `tools/bundle_re/unityfs_probe.py`（已验证：parse/greedy/search 三命令）；
  - `tools/bundle_re/.gitignore`（防大二进制误提交）；
- 本目录大二进制（.gitignore 已排除，本地留存）：`luapatch64.sample.unity3d`、
  `libEncryptor.so`、`libil2cpp.so`、`libunity.so`、`global-metadata.dat`、
  `luapatch64_greedy.bin`——过期可用 2.1 命令重拉。


---

## 9. 战报（2026-10-12 深夜，承接会话首轮攻坚）

### 9.1 M1 达成 ✅：global-metadata.dat 完全解密

- 解密器 = `libil2cpp.so @0x377A028`（HTPX 三段式自研 RC4），启动入口 `@0x3778280`
  （逐字节比对 AF 1B B1 FA）。
- 复现实现：`tools/bundle_re/htpx_decrypt.py`（纯 Python，10MB 解密 2.4s）。
- 产物 `global-metadata.dat.plain`：0xFAB11BAF + 版本 29，UnityEngine×12328、
  Assembly-CSharp.dll 明文，字段全部自洽。**加密已破。**

**算法（全部实测）**：
1. 容器 = `HTPX` + size(u32) + payload(size-8)；解密后头部回写 0xFAB11BAF+版本号。
2. keyobj = rodata double@0x3721328（=CRC poly 0x04C11EB7，标准 CRC-32 魔改 1 位）
   + u32 16 + u32 0x82D63B78；S0 = 单位置换（rodata 存的就是 0..FF）。
3. CRC 表 = 标准右移反射表（poly=0x04C11EB7）；
   `w26 = 0x82D63B78 + ~hash(size-8)`（种子 table[255&~n]，3 轮混入 n>>8/16/24）。
4. 三段：payload[0:128) ← RC4(w26)；中间 128B 块 ← 4 算子矩阵
   （跳表字节@0x3722EF6 = [A,D,B,C]，算子 B 的常数是**递减计数器 32-j**！）
   ；尾部 <128B 逐字节（key9[key4[i&3]%9] + k//255 magic 0x80808081）。
5. 中间块的 pad/key 材料 = 前 128B 经 key2=u32@32^w26 二次解密的栈副本。

**踩坑记录**：跳表顺序猜错→部分 128 块熵化（用"熵区起点=块边界"定位）；
算子 B 常数 32 实为递减计数器（错位 XOR 差值 0x3E,0x3D,0x3C... 递减规律反推）。

### 9.2 bundle(2101) 破解进度：块表已破，数据块待攻

- 团结引擎（Tuanjie）新 bundle 格式，version=2101，flags 0x243 的 0x200=加密位。
- **blocksInfo 已破解**：位于 [64:256]（头部后 16 对齐），明文 LZ4HC，192B→331B，
  **全字段大端**：hash16(全零) + blockCount=25 + 25×(unc u32, comp u32, flags u16=3)
  + nodeCount=1 + node(offset=0, size=3270180, name="CAB-33567f2cc...")。
  comp 总和 879111 = [64:879175) 精确吻合。
- 解析代码：`tools/bundle_re/extract_bundle.py`（数据块部分未通）。
- **数据块 [256:879175] 加密未破**：已排除固定 8B pad、HTPX w26 直接流、
  UnityCN 社区 23 key、字面量 608 条 16 字符串 × 签名窗口。
- 线索：
  - 块流开头 ~50B 有结构与 `XX XX 0a 00 24` 步长 5 模式（疑似未加密段）；
  - 文件尾 192B 有 `e6 02→ec 02`/`8c 13` 递增 u16 = LZ4 匹配偏移痕迹，
    但偏移 745→5004 超出 331B 表——指向一段约 5-8KB 的未知节；
  - 游戏 DLL 解密器 @0x3770900：RC4 key=[buf+28]^0x2013BABA、写入 MZ 头、
    同款 rol6+58——全家桶同一加密模块，bundle 解密器应也在明文区
    （0x3721000-0x3779200）或 C# 侧（受保护）。
  - UnityCN 参考实现已存 /tmp（UnityPy BundleFile.py、ArchiveStorageManager.py、
    RazTools UnityCN.cs）；签名 `#$unity3dchina!@`，key=16B AES-ECB。

### 9.3 下一步建议（按性价比）

1. **定位 bundle 加载器**：明文区还有 ~5MB 游戏自有 C++ 代码未梳理；
   可从引用 blocksInfo 大端解析特征（`rev` 指令簇）或 0x3736918 的 CRC32
   用户函数入手；
2. **frida 动态插桩（备选 B2）**：模拟器装游戏 + 绕 osdk_emu_detect，
   hook 数据块解密函数直接拿明文；
3. **对照样本**：拉设备上其他 .unity3d（7686 个）对比结构，
   尤其找未加密样本对照；
4. M3/M4（雪原贸易站商品表 → act_snowtrade）在数据块破后 1 小时内可完成。

### 9.4 工具清单（本轮新增，均纯 Python 无依赖）

| 文件 | 用途 |
|---|---|
| `imm_scan.py` | so 立即数/字面量扫描（MOVZ/MOVK 对 + 原始字节） |
| `htpx_decrypt.py` | HTPX 三段式解密器（metadata ✓，含 CRC/key 派生） |
| `hpad_solve.py` | 已知明文攻击脚本（定位阶段的见证） |
| `key_hunt.py` | 纯 Python AES-128-ECB + 签名验证（找 UnityCN key） |
| `str_decode.py` | 栈上异或混淆字符串还原器 |
| `tail_brute.py` | 尾部解密暴力搜索框架 |
| `extract_bundle.py` | 2101 bundle 解包器（blocksInfo ✓，数据块待通） |
| `unityfs_probe.py` | （上一轮）头部解析 + LZ4 |


---

## 10. 战报二（同日续）：bundle 解密器本体已定位

### 10.1 隐藏代码层发现

libil2cpp.so 存在 **ELF 空洞代码区**：文件偏移 [0x3133000, 0x31e73a0) 有代码但
不属于任何 PT_LOAD/section（vaddr==文件偏移，运行时由壳补映射）。
objdump 需打补丁才能反汇编（扩宽 .note.gnu.text section 头，见 /tmp/libil2cpp_ext.so）。

### 10.2 加密模块全家族（S0 指纹扫描）

`ldr qN,[xN,#1680]`（S0 初始化）指纹命中 **195 处**（libunity 同构 193 处），
集中于空洞区 0x3154C40-0x31A4754，约 60 个 KSA 函数，间距 1240B 的同模板变体
——按文件类型特化的解密器家族。

### 10.3 bundle 编解码器 = 0x3165580（已完整反汇编）

- 调用链：包装器 0x3166888（x3=缓冲, x4=*size）→ bl 0x3165580
- 逻辑：前 4KB 逐字节搜 "UnityFS"（85,110,105,116,121,70,83 链）→
  memcpy 20B 到栈 → keyobj(double@0x3141328 = 同 poly 0x04C11EB7 + 16 + 0x82D63B78)
  → CRC 表 → **19 轮哈希**（bytes = w25>>8/16/24, w26, w26>>8/16/24, w24, …, w22×4,
  w23×4；每轮 `cur = table[(prev^byte)&0xFF] ^ (prev>>8) + 16`）
  → key w25 = 0x82D63B78 + ~cur
- key 阵列 = [(u32@20+6533)^w25, (u32@28+6529)^w25, w25^(w22+8211), (u32@24+8216)^w25]
- **头 32B XOR 0xA6** 反混淆（in place）
- 单段路径（w22≤159）：RC4(w25) 从 +32 解到 -32（即全 body），rol6+58 同款
- 多段路径（w22>159）：与 fn-2 完全同构（栈副本 key2=w28 双通道 + 中段 + 尾部）
- w22 = 扫描窗口推导值：文件级=4096；嗅探失败=0（→单段全 body）

### 10.4 判定实验结论

- blocksInfo [64:256] 确认为**磁盘明文**（文件级解密假设被否：解密后 walk 335→4）
- 数据块 = 加密态；按块调用模型（key = 块头前 20B 的 19 轮哈希, w22=0）
  转写后未命中——19 轮哈希的字节序/调用粒度还有细节误差
- 已排除：固定 8B pad、w26(57307) 流、社区 23 key、字面量穷举、文件级三段

### 10.5 下一步（收敛路径已极短）

1. 复核 19 轮哈希转写（对照 objdump 逐指令，或 /tmp/libil2cpp_ext.so 全函数
   dump 0x3165580-0x3166500 人工核对一遍）；
2. 或 frida hook 0x3165580 入口/出口，直接观测 (x0,x1) 与输出（一次真实调用
   即锁定全部参数语义）；
3. 破后 M3/M4 约 1h 收尾（extract_bundle.py 已就绪，只差块解密函数）。


---

## 11. 战报三（同日三攻）：解密器家族完整地图

### 11.1 通用文件编解码器 0x3165580（完整逻辑）

多格式嗅探+条件解密，分支：
- 文件头滑窗（+0..+15）匹配 6 个 XOR-0xA6 魔数
  （解出的特征: `03 00 75 00`/`00 00 75 00`/`00 00 00 08`/`5D 00 00 08`/`03 00 00 08`/`5D 00 75 00`）
  → 命中 → "UnityFS" 字节链搜索 → 19 轮哈希 → RC4 三段解密；
- 扫 [0:17] 找 'H','T','P'+变体 → 命中 → **只做头 60B XOR 0x59 去混淆 + 写 ']' 覆盖魔数，
  不 RC4，直接退出**（HTP* 文件 = 仅头部混淆）；
- 扫 [0:72] 找 `EE DD` → 版本半字节校验（0x6000/7000/8000/9000）→ 走对应解密；
- 都不中 → **原样退出**。
- **判定实验：bundle 不走此函数**（无 EE DD、无 A6 魔数命中 → 空手退出），
  这解释了 blocksInfo 为何是磁盘明文。

### 11.2 家族另一成员：`[x0+68, x0+w19-8)` 解密器（0x3166714 起）

- 结构：68B 明文头 + RC4(w9 ^ 0x8F7D????) 区 + 8B 明文尾
- S0 同款（identity + rodata 常量表）、KSA `bfxil` key[i&3]、rol6+58 变换全一致
- 属于 "EE DD" 或 HTP* 类文件，非 .unity3d bundle

### 11.3 bundle 数据块的最终判定

- 文件级/连续流/独立块 三种布局的全部明文 LZ4 假设已穷举排除；
  连续流在表尾+2B 处失步（偏移 3617 引用不存在历史）。
- 数据块 [256:879175] 确认被变换；解密器在剩余 ~55 个 KSA 函数中，
  且调用粒度（整文件/每块/每 N 块）待定。
- **推荐下一动作**：frida hook（备选 B2）——对 0x3166a64（PRGA 写回点）
  或全部 59 个 KSA 函数入口批量插桩，跑一次游戏加载 luapatch，
  直接观测哪个函数被调用及 (key, 输入, 输出)。一次命中即全通。

### 11.4 关键地址速查

| 地址 | 含义 |
|---|---|
| 0x3165580 | 通用多格式编解码器（嗅探+条件解密） |
| 0x3166888 | 包装器 (x3=buf, x4=&size) |
| 0x3166714+ | [68, len-8) 型解密器（68B头+8B尾格式） |
| 0x3166a64 | 该函数 PRGA 写回点（frida 插桩候选） |
| 0x3141328 | bundle 族 keyobj（poly 0x04C11EB7 + 16 + 0x82D63B78） |
| 0x313e000+ | bundle 族 S0 rodata（identity 0..FF） |
| /tmp/libil2cpp_ext.so | 扩 section 补丁副本（可反汇编空洞区） |


---

## 12. 战报四（同日四攻）：数据块检测分支已锁定

### 12.1 连续流实锤 + 精确失步点

- [64:879175] = **单一连续 LZ4 流**实锤：表流最后一条序列的匹配(偏移22)合法地产出了
  数据区前 4 字节 "d80a"（在 CAB 名字里有源），即压缩器的一条序列横跨了表/数据边界；
- 解码器在表尾多读 2 字节即失步（file[258] 处 token 0x01 → 偏移 5632 非法）；
  表流真边界 = 输入 194（file[258]），数据区独立成流起点 = file[256] 或 [258] 均测过，
  全部在 ~350B 处遇非法偏移（3617/5632）→ **数据区不是明文 LZ4，确认被变换**。

### 12.2 数据块自描述头：EE DD + 年份版本

- block0 body [256+32=288 起] 的 [26:28] = `EE DD`，[28:30] = u16 `0x2022`（Unity 2022!）
- 通用编解码器的 EE-DD 分支校验版本 ∈ {0x2019, 0x201A, 0x6xxx, 0x7xxx, 0x8xxx, 0x9xxx}
  —— **0x2022 不在列**（该分支只认老版本）→ 2022 格式由家族中另一专管成员处理；
- body 前 ~50B 半结构化、其后高熵 = 「明文自描述头 + 加密主体」形态。

### 12.3 编解码器实例清单（keyobj 数组 @0x3141300+）

| keyobj | poly | X | 已知归属 |
|---|---|---|---|
| +0x308 | ? | ? | 函数@0x3199570 |
| +0x310 | ? | ? | 函数×4 @0x319fa4c/0x319fd4c/0x31a06c8/0x31a09c8 |
| +0x320 | 0x8F6E37A0 | 53 | 函数@0x319eecc |
| +0x328 | 0x04C11EB7 | 16 | metadata 族（fn-2@0x377a028 等） |
| +0x330 | 0x8F6E37A0 | 16 | PNG（zTXt 藏密文，0x1EDC6F41 尾常量） |

游戏连下载图片（PNG zTXt）都加密，全家桶统一自研 RC4(rol6+58) 模板。

### 12.4 下一步（不变，唯一路径）

frida 批量插桩 59 个 KSA 函数入口（条件：模拟器 + 绕 osdk_emu_detect），
跑一次 luapatch 加载，观测 (key, 输入, 输出) —— 一次命中全通。
静态侧已无低成本假设可验（本轮把同步点/连续流/独立块/文件级全部精确排除）。


---

## 13. 战报五：动态路径落地——容器内存扫描（比 frida 更便宜）

### 13.1 关键事实

- 真机 com.gof.china（独立安装，u0_a113）**非 debuggable 且与号库不同 UID**，
  run-as 读不了 → 直启真游戏拿不到内存；
- 容器内虚拟游戏与号库同 UID（u0_a373），**run-as com.carlos.multiapp 可读其
  /proc/pid/mem** —— 号库 ▶ 启动 容器内游戏后，解密容器（3,270,180B）即在
  该进程堆里；
- 已实证 run-as 读 /proc/<pid>/maps 正常（720/968 个 rw-p 段可列）。

### 13.2 工具已就绪

`tools/bundle_re/memscan.sh`：遍历容器游戏进程 ≥3MB 的 rw-p 段，grep
"CAB-33567f2cc..."（解密容器铁证，命中即回退 294B 提取 3,270,180B 容器）。
注意：encrypted 原文件里该名字也明文存在（blocksInfo 在 [220]），故脚本会
多命中——用「命中地址-294 处应为全零 hash + 表结构」或直接搜"雪原贸易站"
区分（解密容器的字面量区含它，加密原文件不含）。

### 13.3 执行流程（需一次人工点按）

1. 号库壳 ▶ 启动 容器内游戏（本轮尝试时用户在用手机，UI 自动化中止）；
2. 游戏加载到登录界面后：
   `adb push memscan.sh /data/local/tmp && \
    adb shell "run-as com.carlos.multiapp cp /data/local/tmp/memscan.sh files/ && \
    run-as com.carlos.multiapp sh files/memscan.sh"`
   （或按脚本头注释）；
3. adb pull container.bin → 回 PC 走 extract/解析流程（M3 收尾）。

注意：真机直启的真游戏（u0_a113）已 force-stop 还原；用户微信会话未被触碰
（仅短暂前台切换）。


---

## 14. 战报六：内存扫描通道全线打通，零命中原因=数据版本错位

### 14.1 通道能力（已全部实证）

- run-as 可读容器游戏（u0_a373）的 /proc/pid/mem ✓
- 3.31GB 内存快照成功落地 files/mem/*.bin（582 文件，与进程生死解耦）✓
- 设备 grep 管道可用（283 个 CAB- 命中证明）✓
- **坑**：① shell 算术 32 位（高地址段算成负数→dd 静默失败，须 Mac 端预算
  字面量 skip）；② toybox grep -E 不支持 \xNN 转义（须用纯文本/`.`通配）；
  ③ 游戏进程会重启（点时快照必须与 grep 解耦）；④ il2cpp 字符串 = UTF-16LE
  （ASCII grep 漏检，须 `C.A.B.` 点通配模式）。

### 14.2 扫描结果与解读

582 个 rw-p 区段（3.31GB，pid 9039，容器游戏 RSS 800MB 满载态）全文扫描
（ASCII + UTF-16LE 双编码，CAB-33567f2cc / value_exchange / 雪原贸易站）：
**零命中**。且 80MB 原生堆 arena 有 283 个其他 CAB- 名（资源清单数据 ✓ 管道可信）。

→ 结论：**容器内的游戏没有加载 luapatch64_1d9bc592**（号库克隆的 baseline 是旧
  版数据；容器游戏数据目录里没有当前 patch；扫描时游戏可能停在登录前态）。
  当前 patch 的解密内容只在真游戏（u0_a113，不可读）里出现过。

### 14.3 下一步（按序）

1. 容器内游戏**登录进主城**后重扫（登录才加载完整 lua）——观察是否命中
   value_exchange（若命中直接提容器）；
2. 或：把真游戏数据目录的当前 patch（luapatch64_1d9bc592）喂给容器游戏
   （替换容器内对应文件后 ▶ 启动），扫内存必命中 CAB-33567f2cc；
3. 或：回到 frida（emulator 路线）。
4. 工具沉淀：memscan*.sh / snap9039.sh（点时快照+离线 grep 范式）；
   3GB 快照在设备 files/mem/ 可复用任意模式重扫。


---

## 15. 战报七（收官）：当前版本 patch 已到手

容器游戏内存中的 patch 路径字符串指向**共享外部目录**（容器游戏直接读
/storage/emulated/0/Android/data/com.gof.china/files/data/，adb 可读写）。
该目录有 4 个 luapatch，**当前版 = luapatch_64_29e09be56616ef9071532c2d783006bf
.unity3d（1,151,106 B，2026-10-12 23:52 热更）已 pull 至
tools/bundle_re/luapatch64_current.unity3d**。

头部实测：与旧样本同构（UnityFS/2101/5.x.x/2022.3.52f1，size=1151106，
cinfo=220，uinfo=391，flags=0x243，blocksInfo 应在 [64:284]）。

此前全部静态分析（S0 指纹家族、19 轮哈希编解码器 0x3165580、
块表大端解析、数据块 EE DD 头）可直接套用到此文件。
内存扫描零命中之谜也解开：容器游戏加载的是 29e09be5 这个 patch
（其 CAB GUID ≠ 旧样本的 33567f2cc），且部分内容加载后释放。

### 下轮第一步
`python3 extract_bundle.py luapatch64_current.unity3d`（改 lz4 起点对齐 64），
若数据块仍失败，按第 10-11 节编解码器家族逐一对拍。


---

## 16. 战报八：下一轮破局点

1. 容器重定向目录确认：`.../com.carlos.multiapp/files/Android_va/0/Android/data/
   com.gof.china/files/data/` 内 = **当前 patch 29e09be5**（与已拉取的
   luapatch64_current.unity3d 同一文件）→ 容器游戏加载的就是当前 patch；
2. 游戏内存（3GB 快照 files/mem/）中存在大量其他 CAB 名（管道可信），
   但无 33567f2cc/value_exchange/雪原贸易站 —— 解密容器与 lua 字节串
   在内存中形态未知（可能按需解密/串雾）；
3. **下一轮首选动作：在 3GB 快照中搜 lua 签名 `\x1b\x4c\x75\x61`（ESC"Lua"，
   luac 字节码头）——TextAsset 的 lua 字节码解密后必有此签名，定位即得解密
   lua 块，无需还原整个容器**；每个命中回溯块头即可提取全部 lua；
   也可顺带搜 zip 头 PK\x03\x04（若 TextAsset 为 zip 打包）。
4. 若快照确无 lua（被释放）：换 frida hook 0x3166a64 PRGA 写回点（第 11.4 节）。


---

## 17. 中断点（2026-10-13 凌晨，设备离线）

- WiFi adb 掉线（Connection refused），lua 签名全内存扫描两路（低段 32 位算术版 +
  高段字面量版）均未完成，无结果产出；
- p267 的 \x1bLua 命中为假阳性（实为 .NET 加密库数据）；
- 设备恢复后重跑：解密线下一步 = 对 pid 9039（或新 pid）全 rw-p 段扫
  `printf "\033Lua"` 签名（高段必须用 Mac 端字面量 skip，勿在设备端做 64 位算术），
  命中区段整块落地后提取 lua chunk（Lua 5.1 格式，0x51）→ strings 定位业务 lua
  → 搜 value_exchange/雪原贸易站 → M3；
- 快照与工具仍在设备 files/ 下（mem/、m3b/、big3/），Mac 端
  tools/bundle_re/memdump/ 有 R014/R021/R069 区段与 goods_win.bin。


---

## 18. 阶段结论（lua 签名扫描后）

- 全内存段（2651 区段）\x1bLua 签名扫描：**唯一命中 = 已知假阳性**（.NET 加密库数据）。
  游戏进程内存中无标准 luac 签名 → luapatch 的 lua **从未在容器游戏中解密加载**
  （版本门控：容器游戏版本旧，热更 patch 未应用，跑的是 APK 内置脚本；
  CAB-22738267 主导 = 内置资产清单）。
- **结论：容器内存路线无法产出当前 patch 的解密内容**（游戏根本没加载它）。
  真游戏（u0_a113）加载了但 uid 不可读。
- 剩余两条真路径：
  A. **静态转写 0x31a4380 PNG 解密器做校准**——设备 Android/data/com.gof.china
     下有大量加密 PNG（自校验：解出必为合法 PNG），PNG 编解码器 = 与 bundle
     同族（keyobj @0x3141330 = poly 0x8F6E37A0/16 + 0x1EDC6F41），先把 PNG 加解密
     完全复现，同一套约定套回 bundle 块（0x3165580 的块头/19轮哈希）；
  B. frida-gadget 重打包真游戏（无 root 注入）。
- 解密线转为后台低优先级；奖励领取自动化（act_snowtrade 粗兑+精兑）不依赖它。

---

## 19. 战报九（2026-10-13）：PNG 校准大突破——家族算法约定全部实证

### 19.1 加密 PNG 样本到手 ✅

- 位置：**APK 内 assets/**（不是 HTTPCache——那 1213 个 PNG 全是普通图）。
  `unzip -p base.apk assets/frame_for_snow_show_ef3.png` 等 3 个
  （frame_for_snow_show_ef3 / frame_for_crowna812_3e9 / h5_qr_back 前两个为正样本）。
- **特征修正**：chunk 名实际是 **"zAXt"**（0x41='A'，防通用 PNG 解析），交接记录的
  "zTXt" 是笔误。布局：8B 签名 + IHDR(25) + IDAT(38) + zAXt chunk(8+len+4) + IEND(12)，
  **[71:75] BE u32 = N = size−95 精确成立**，密文 = [79, 79+N)，尾 16B = CRC(4)+IEND(12)。
- crowna812 密文 92B；snow_show 密文 17B **全零**（RC4 流密码下 = 纯 keystream，天然锚点）。

### 19.2 PNG 解密器 0x31a4380 全转写 ✅（哈希链被样本实证）

- 校验：`[0:4]="\x89PNG"`、size≥95、`[71:75]` BE == N、**[75:79] 期望 "zTXt"**（0x7A545874；
  文件是 zAXt → 调用方先补丁或另有容错，算法体不受影响）。
- **CRC32(chunk名4B + N字节体) == BE u32@[79+N]**（0x3156900，标准 CRC32）。
- **key 派生**：材料 = 密文头 16B [79:95)。初值 0xFFFFFFFF，逐字节
  `cur = T[(cur^b)&0xFF] ^ (cur>>8); cur += X`（T = poly 0x8F6E37A0 位反演表，X=16），
  **w25 = 0x1EDC6F41 + ~cur**（keyobj@0x3141330 = poly/X/TAIL 三元组，与 HTPX 同模板）。
- **校验字段实证**：`LE32@[95:99] ^ w25 == N−20` —— **crowna812 精确通过**（0x48=72），
  16B 材料哈希 + TAIL 常量 + 初值全部确认，家族 key 派生公式 100% 复现。
- RC4：key = w25 LE 4B；KSA 标准（S0=identity，bfxil key[i&3]）；PRGA 变换 = **(k rol2)+8 再 XOR**
  （注意与 bundle 的 rol6+58 不同族常数）。
- RC4 输出后另有 12B×(N/3) 段的第二层封装（虚函数 [GLOB+384]），与 bundle 块解密无关，低优先。

### 19.3 bundle 编解码器 0x3165580 完整结构 ✅（arm64_mini 模拟器实证）

- **调用模型**：wrapper 0x3166888(x3=buf, x4=&size) → 0x3165580(x0=buf, x1=len)；
  通过 GOT（0x31f4000+384）注册为 Unity archive 解密钩子；每块（或每密文段）整喂。
- **嗅探瀑布**：A6 魔数@[0..11]（8 个变体 u32）→ UnityFS 搜索 → HTP8 扫描[0:17] →
  **EE DD 扫描[0:72]**。块数据无魔数 → 命中 EE DD 分支。
- **EE DD 容器头**（块内偏移 p，luapatch 块0 = 25）：
  `[p]=EE DD [p+2:4]=版本u16LE(0x2022 ∈ [8224,8232) 命中) [p+4]=0xAA [p+6]=0xBB
   [p+8:12]=u32LE(0x00311D32) [p+12:16]=13`。
  `w24 = ([p+6]+[p+4]<<4)`，AA/BB 组合 → **4096**（加密段长度编码）。
- **头改写**（就地）：[p+0..3] = 版本半字节 ASCII（BFI 序列，实测 "0022" 类乱码——
  语义无关，纯哈希材料）、[p+4]=[p+6]='.'、[p+9]='f'（'1'/'2'→'f' 替换，strchr 0x3165300）。
  改写后 20B = 哈希材料（实测 `30 30 20 32 2e 33 2e 35 32 66 31 00 0d 00 00 00 01 02 00 a6`）。
- **主哈希**：初值 0xFFFFFFFF，20 轮（旧记录"19 轮"漏了最后一轮 b2[3] @0x3165c44）：
  输入序 = b3(头[12:16]LE,4B) + b1([4:8],4B) + b4([16:20],4B) + w22(4B) + b2([8:12],**4B**)；
  每轮 `cur = T[(cur^b)&0xFF] ^ (cur>>8); cur += 16`（T = poly 0x04C11EB7 位反演表）。
  **w25 = 0x82D63B58 + ~cur**（**B58 不是 B78**——旧记录抄了 metadata 族的常量，差异点之一）。
- **key 阵列**：k1=(u32@[x20+20]+6533)^w25；k2=(u32@[x20+28]+6529)^w25；
  k3=(u32@[x20+24]+8216)^w25；k4=w25^(w22+8211)（x20 = EE DD 位置+19，模拟实测 44）。
- **XOR A6**：头 32B 就地解混淆——**模拟结果与 org^A6 逐字节精确一致** ✅。
- **RC4**：KSA key=w25 LE4B；PRGA `((k rol6)+58)^c`（rol6+58 ✓ 文档旧记录对）。
  分段：seg1 = RC4(w25) 解 [x21, +128)（x21=x20+32）；seg2 = RC4(k4) 解 seg1 的**副本**作
  key2 材料（双通道，与 HTPX 同构）；中段 [x21+128, w22) 每 128B 块 j 用算子
  `optable[j%9]&3`（四算子 A/B/C/D @0x31661c0/0x3166210/0x3166248/0x316629c，
  含递减计数器 32−i、keyarr=[k1,k2,k4,k3] 按 key2[i]&3 轮换、表[i%9] 索引）；
  尾段 <128B 逐字节（keyarr[i&3]%9 查表）。
- **数据区结构**（当前 patch 实测）：数据区 = blocksInfo LZ4 流的尾段 [288:335)
  （[64:335) 完整解出 391B ✓ 无缝闭合）+ EE DD 容器（块内 @25）+ RC4/矩阵密文体。
  **没有 4096 窗口表、没有每块 32B 头**（Σcomp 精确闭合到文件尾）。

### 19.4 0x31668e4：EE DD 容器专用解密器（家族另一成员，已转写）

- 入口包装 0x316740c（"NP" 头 + [20/21] 或 [48/49] 处 BB CC 检查，w3=类型）——
  **处理 "NP" 开头的文件**，不是 luapatch 块（块头非 NP）。
- key = `(LE32@[x0+64] ^ LE32@[x0+64+w19-4]) ^ 0x8F7D9137`（分支0）/ `^0xED2591FC`（分支1），
  w19 = min(1000, len−64)；RC4 rol6+58 解 [x0+68, x0+68+w19−8)；段尾 16B 有
  `38 FB 96 37 66 30 6E 65` 8B 标记 + 第二层 bundle-keyobj 哈希（0x3166ec4+）。
- 该函数属于 "NP" 文件线（可能是热更 DLL/配置），与 luapatch 块线并行存档。

### 19.5 工具沉淀

| 文件 | 用途 |
|---|---|
| `tools/bundle_re/pngs/` | 3 个 APK 内 PNG 样本 + HTTPCache 全量（普通图） |
| `tools/bundle_re/arm64_mini.py` | 迷你 ARM64 模拟器（已修 20+ 语义 bug，可跑通 0x3165580 全函数） |
| `tools/bundle_re/sim_run.py` | 0x3165580 模拟运行脚本（GOT stub：+352 alloc/+392 memcpy 假设、strchr、strlen=9） |
| `tools/bundle_re/bundle_decrypt.py` | 按转写写的 Python 解密器（等模拟器数值对齐后启用） |
| `tools/bundle_re/blk0_simdec.bin` | 块 0 模拟解密输出（当前 LZ4 未通过——见 19.6） |

### 19.6 当前卡点（下一轮第一步）

- 模拟器跑通全函数（37257 步正常返回），XOR A6 精确匹配，但 **RC4 输出仍非 LZ4**。
- **已定位未修**：模拟器 CRC 表构建循环（0x31659b8-0x3165abc）产出的表全零/错位
  （sim 表 = ref 右移 3 项），导致 w25 错。嫌疑集中在 8 位展开的 tbnz/lsr/eor 链
  （0x3165a00-0x3165ab8）某条指令语义。修好表 → w25 自洽 → seg1 应过 LZ4。
- **备选核对点**：hook_read（blr [x11+392]）的 memcpy 假设；材料 20B 是否被
  虚函数变换；w22 = 4048（= 4096 − 48，模拟自洽）vs 其他。
- **关键常量速查**：TAIL=0x82D63B58（bundle）/0x1EDC6F41（PNG）/0x82D63B78（HTPX）；
  X=16；poly：0x04C11EB7（bundle）/0x8F6E37A0（PNG）；EE DD 版本集 [8224,8232)∪[8215,8217)∪{0x6000,0x7000,0x8000,0x9000}。
- 解密线在块 0 LZ4 通过后：对 31 块循环 → 拼容器 → UnityPy 或手解序列化文件 →
  TextAsset（lua 字节码）→ 搜 雪原贸易站/value_exchange → M3 → M4。

---

## 20. 战报十（2026-10-13 深夜）：模拟器收敛——块 0 解出 Unity AssetBundle 结构 ✅

### 20.1 模拟器累计修复（arm64_mini.py，共 25+ 项指令语义）

关键修复（按发现顺序）：STP pre-index mode=3、LDP/STP 前缀表误含 0x7C/0x7D（毁掉全部
64 位 LDR）、MOV wide 掩码（0x12800000）、MOVN opc=0、BL hooks、BLR hooks、ADD imm
掩码、extended-register ADD（SXTW）、TBZ/TBNZ 极性、CCMP 寄存器形式（bit10 区分）、
CCMP/CCMN 掩码重写、BFXIL lsb = bits−immr、CSEL 64 位掩码（0x1FE00800）、CMP 污染 sp、
LDP/STP 的 rn=31 用 sp、寄存器偏移 LDR/STR（option LSL）、**逻辑立即数解码
（esize = 1 << HighestSetBit；w 截断掩码 = (1<<esize)−1）**、**逻辑寄存器分支的
LSL/LSR/ASR/ROR 四种 shift**（原先把 LSL 写成 >>）、**EOR/ORR opc 映射**（opc2=EOR，
原写错成 ORR）、**sp 相对寻址全分支**（x[31]→self.sp，曾使材料/key/S 盒全部错位重叠）、
SIMD V 寄存器独立于 GPR、SIMD LDR/STR 全 size、SIMD MOVI/EOR、UMULH、CSINC 64 位、
BFXIL、imm9 pre/post 写回。

### 20.2 验证里程碑（全部实测）

1. **CRC 表构建**（0x31659b8）与标准位反演表逐项一致 ✅
2. **20 轮哈希**逐轮 cur 与 Python 参考一致 ✅（20 轮确认：b3+b1+b4+w22+b2 各 4B；
   旧记录"19 轮"漏 b2[3]）
3. **w25 = 0x82D63B78 + ~cur = 0x7D5EEDF1**（**TAIL = 0x82D63B78 实证**——movz #15224
   = 0x3B78；19.6 节"B58"是我二次误读，10.3 节原始记录 B78 才对）
4. **XOR A6** 头 32B 与 org^A6 逐字节一致 ✅
5. **RC4 KSA** 状态盒与 Python 一致 ✅；**PRGA 首轮 K=0x8B、rol6=0x22** ✅
6. **LZ4 从 dec[0] 合法解出 3129 B**，其中出现 **"AssetBundle.m_PreloadTable.
   m_FileID.m_PathID.m_Container.AssetInfo..."** —— **Unity AssetBundle 序列化文件
   标准结构！解密方向 100% 正证实锤** ✅✅✅（产物 blk0_plain_partial.bin）
7. **矩阵分派实证**：块号 j 的算子 = table[j%9]&3，跳转表 @0x3141EF0 = [0,55,20,34]：
   **op0→A(0x31661c0)、op1→D(0x316629c)、op2→B(0x3166210)、op3→C(0x3166248)**
   （19.3 节的算子字母对应需按此修正；bundle_decrypt.py 已改）
8. 块 0-8（LZ4 输入 [204:1356)）模拟与语义转写一致 ✅

### 20.3 权威参数（模拟器逐指令实证，以此为准）

- 块 0：EE DD @25；改写头 20B 材料 = [44:64) 原始字节
  `a6a697bca655a25959eecd0247fb1bcc4c2c6706`；w22 = **4048**（= 4096 − 48）；
  w25 = **0x7D5EEDF1**；keyarr = [k1,k2,k4,k3] =
  [0x52A05DB6, 0xDBF92D29, 0x7D5EC212, 0xDBCC2277]；
  key2（RC4(k4) 解 seg1 副本）首 8B = bbdecbaafe27b2dd；
  RC4 seg1 = [76:204)；中段 = [204:4044) 30 块矩阵；尾段 [4044:4092) 48B。
- 状态 dump：`tools/bundle_re/matrix_state2.bin`（key2+keyarr+optable，循环中真实值）。

### 20.4 剩余卡点（下一轮）

- LZ4 在输入 1367（矩阵块 9 [1356:1484) 内）处 off 12365 > out 3129 失步。
- **本轮新修**：UMULL/UMULH 区分（0x3166198 的 `umull x0, w0, w11` 是 j%9 的核心，
  Ra=bits14-10=31 → UMULH 高 64；Ra≠31 → UMULL 低 64——原先全按 UMULH 算，
  导致 j≥9 后算子索引取自表外）。修后块 9 输出与 C 算子手算逐字节一致
  （`1501050046c292f6394d30b64a2a7c64`）。
- **逐 token 回溯定位**：失败 token = 块 9 内 `46 c2 92 f6`（lit4 "c2 92 f6 39" +
  off = `4d 30` = 12365）——即 **C 算子的 u32[1]**（sel = table[1]&3 = 3 → keyarr[3] = k3）
  产出的字节非法；而 **u32[0]**（sel = 1 → k2）产出合法。块 9 前 u32[0:2] 的 token 均合法。
- **嫌疑收敛**：keyarr 的 **k3 项**（= (u24+8216)^w25，u24 = LE@[x20+24]）或
  **k4 项**（= w25^(w22+8211)）之一有错；或 u24/u28 的源偏移（x20+24/x20+28）在
  真实路径中不同（x20 = 44 由版本串 clamp 残留决定，模拟自洽但可与真机对拍）。
- **下一轮手段**：①keyarr 排列枚举（k1/k2/k3/k4 的 24 种排列 × 矩阵跑块 0-9，
  以 LZ4 通过为 oracle，代价极低）；②或 u20/u24/u28 源偏移小范围枚举（[20:32] 的
  相邻偏移）；③修好即全通：31 块循环 → 容器 → TextAsset → M3。

### 20.5 与 19.x 记录的差异更正

- **TAIL(bundle) = 0x82D63B78**（19.6 节"B58"作废；0x82D63B58 是读错的 movz 立即数）。
- **哈希 20 轮**（b2 完整 4B；"19 轮"作废）。
- **算子字母映射按 20.2.7**（bundle_decrypt.py 已同步修正）。
- **头 32B XOR A6 与材料 [44:64) 均为模拟器逐指令实证，memcpy 假设成立**。

---

## 21. 战报十一（2026-10-14）：块 0 密文全解 ✅ + 连续流模型确立，剩最后 5% 语义

### 21.1 破案过程回顾（本轮证伪清单，防止走回头路）

- **§20.4 的"块 9 失步/keyarr 嫌疑"全部作废**：失步的真正原因 = LZ4 解码语义，不在密钥。
- 旧记录"w22 = 4048 = 4096−48"实锤：EE DD 命中路径 w9=48（0x3166754）、w8=w24=4096，
  w22 = w19−w9。UnityFS 搜索分支（0x316587c）与本块无关（那是 A6 魔数文件的分支）。
- **matrix_state2.bin 的"表"**：sp+208 = RC4 S-box 与 u32 表**复用同一块栈**。x23 = sp+208
  （0x3165c6c `add x23, sp, #208`，不是堆！）。0x3166060-70：`ldp q0,q1,[x8=0x3141EB4];
  stp q0,q1,[x23]` 把 rodata 32B 写入表头，再 k-spill。
  **最终表 = [0]=0x571 [1]=k3 [2]=0x892 [3]=0x750 [4]=k1 [5]=k2 [6]=0x746 [7]=k4 [8]=0x568**
  （[2][3][6] = rodata 0x3141EBC/EC0/ECC 静态常量；[9..15] 及以后 = S3 残留；
  sim 栈残留 dump 实证确认）。table[0..8] 即全部被消费的项。
- **S-box 初始态 = 0x313e000+{1680,1744,1360,1696,1776,1728,1456,1344,1632,1600,1568,1616,
  1472,1488,1312,1376} 的 256B 常量表**（每轮 RC4 前重写，sp+208）。
- **key2 = RC4(常量初始化, KSA(k4)) 解 [76:204) 的密文副本**（memcpy 在 seg1 解密**之前**，
  解的是密文！）。用 sim 输出反解 key2 词0/1 = aacbdebb/ddb227fe（= 字节
  bbdecbaafe27b2dd，与 dump 吻合），再穷举 96 种链变体唯一命中。
- **哈希材料 = 20B/20 轮**（b3+b1+b4+w22+b2 各 4B，b2 不截断）；w25=0x7D5EEDF1 ✓。
- **A6 区 = [44:76)**（`ldp q1,q2,[x20]; movi v0.16b,#166; eor; stp`，无可争议）。
- x20=44（模拟实测，材料 [44:64)、u@[64/68/72] 全对上）。44/48 之争以 sim 为准取 44。

### 21.2 Unity LZ4 = 零窗扩展（本轮最大发现）

Unity 的 LZ4 block 解码允许 **off > 已输出长度**：缺失历史按 **0** 回填。
证据：块 0 明文流的 token（off=1267@out=71、off=65280@out=103 等）全靠该语义才合法；
上一会话 "off 12365 > out 3129 失步" = 解码器未实现该语义，**从来不是解密错误**。
blk0_plain_partial.bin（3129B）= 上一会话解码器对同一流的前缀输出，
其 [0:65] 与本轮正确流解码**逐字节一致**（当轮其内部 wraparound 恰好等效）。

### 21.3 流结构（当前最优模型，与 pp 前缀互证）

- 数据区 = file[288:]，**一条连续 LZ4 流**（Unity 零窗语义），31 段只是压缩分片边界。
- **流起点 = data[0:44) + data[76:]**（跳过 [44:76) 的 32B A6/key 段）——
  该模型的解码输出 [0:65] 与 blk0_plain_partial.bin 完全一致，且
  **输出 [27:31] = LE 0x0024783d = 4028452 = Σuncomp（自声明大小，结构性铁证）**。
  纯 data[0:] 与 data[44:] 起始都不对（token@47 处 off=1267 无法回填出正确结构）。
- blocksInfo（大端）：blob = file[50:288)（前 20B 为 14 个 0x00 空 token + 1e 00 01 00 流头），
  31 项 @blob+20+10k = uncomp u32 BE / comp u32 BE / flags u16 BE(=3 LZ4)。
  Σcomp = 1150818 = len−288 精确闭合；节点表 CAB-33567f… 大小 = 0x3d7824 = Σuncomp。
- 数据区全文件**只有一个 EE DD**（@313），块 1..30 = 明文 LZ4 分片。

### 21.4 当前成绩与缺口

- 解码产出 3836983 / 4028452（95.2%），输入 100% 消耗、全程自洽不崩。
- 输出含大量真实内容：`midautumn2026_valueexchange_bg_001`、`monopoly_exchangeshop_bg`、
  `gift_name_300045`、`fishing_guide 21 : boolean`、lua 源串（含中文注释）、
  `AssetBundle.m_PreloadTable` 类型树等。
- **缺口 191469B**：部分段流提前耗尽。已排除：keyarr/key2（有真值对拍）、A6 区、
  帧跳字节模型（head76/tail4、head44/tail4、head80 等全试过，产出反而更少 →
  连续无跳过正确）。字典假设（sample 解压数据 3027205B 前置）不改变尺寸。
- **wrapper 尾部（0x31668a8-bc）实证：size 不调整**——`blr x8`(原 read) → `ldr x1,[x19]`
  → `bl 0x3165580(buf, *size)` → 原样返回。解压器收到的缓冲 = 完整 read，无跳字节。
- 类型树区实证：`AssetBundle\0m_PreloadTable\0m_FileID\0` 连续正确（\0 分隔被打印断开），
  但随后 `m_PathID` 缺失（被零窗填 0）→ **首个真失步点在类型树中部（输出 ~1058 附近），
  对应输入仍在块 0 解密区内**。即块 0 明文的前 ~1000+ 字节 = 真值，之后有语义漂移。
- **待验假设（下轮首选）**：① 设备对拍：游戏在线（192.168.1.7:38429）触发加载该 bundle，
  hook 0x3166888 拿（解密后压缩输入, 解压输出）真值对 → 一锤定音；② 复核块 0 解密
  [204:4092) 与 sim 仍余 ~1258B 差异（simdec 本身可能有模拟器长尾 bug，勿再当真值，
  应以 LZ4 结构 + 设备真值为准）；③ ZW(超窗匹配) 的真实回填语义（字典/ring）。
  memdump/R014 的 archive:///CAB-33567f 为路径串非数据。

### 21.7 追加（同日晚）：类型树已知明文定位到根因模式

- **流模型定稿：data[0:] 连续（不跳任何字节）**。skip-0 模型解码出
  `AssetBundle\0m_PreloadTable\0m_FileID\0`（\0 分隔正确、字面量正确），
  类型树起点 = 输出偏移 1682。skip 模型（44/76 等）全部排除。
- **错误模式（决定性）**：类型树显示"拼图受损"——
  `Con[8×00]ner9`、`ainA[8×00]ainA`、`ex[6×00]` 等：
  真值 = `m_Container/AssetInfo/...` 的**字面量片段全部正确解出**，
  缺失/错乱的 = **特定对齐 u32**（同时这些 u32 常出现在 LZ4 match 头部，
  其损坏使 off 变成 24881 之类的超窗值 → 被零窗填零 → 名称中部缺失）。
- **结论：矩阵区解密在部分 u32 上错误、且是稀疏的**（相邻字节正确）。
  key2/表[0..8]/算子 C（块1逐 u32 验证）都对 ⇒ 剩余嫌疑收敛到：
  ① 算子 A/B/D 公式的细节误读（disassembly 二次确认即可）；
  ② tbl[i%9] 在 i%9∈{2,3,6} 的**全字**用途（op A）与 keyarr[sel] 的 sel
     （op B/C/D 的 key2[i]&3）在某组合下的取值；
  ③ 稀疏错误 = 每 u32 独立 ⇒ 可用类型树已知明文**逐 u32 代数反解**
     true_keyarr[sel] = my_plain ^ true_plain ^ (其它项)，直接读出真值表。
- **下轮第一步 = ③**：从 R014 @0xe70ea 取标准 AssetBundle 类型树串
  （AssetBundle\0m_PreloadTable\0m_FileID\0m_PathID\0m_Container\0AssetInfo\0
   preloadIndex\0preloadSize\0asset\0m_MainAsset\0m_RuntimeCompatibility\0
   m_AssetBundleName\0m_Dependency\0），对齐输出 1682 起，
   收集 (输入位置, 我方解密, 真值) 三元组（覆盖 op A/B/C/D 各块），
   反解每错误的 u32 用了哪个 keyarr 项 → 一次性还原全部语义。
- 设备侧：容器游戏 = VA 框架 stub 进程（进程名 com.gof.china，uid = 号库壳），
  run-as 可读；原生 dumper（tools/bundle_re/memdump.c，NDK 编译，
  process_vm_readv + 页级回退）已就绪可用。但游戏后台即被冻结+换页，
  dump 全零；需前台+亮屏时机。归档解压缓冲 = 流式复用，登不入堆，
  **设备真值路线优先级下调**，本地类型树反解 = 主线。

### 21.8 追加二（同日深夜）：反解实验定论 + 最终求解算法

- **keyarr 排列 24 种 × 正确流模型重试**：原排列 [k1,k2,k4,k3] 匹配最长（36B = 断点前），
  其余全 0 → **keyarr 顺序正确**，排列假设彻底排除（此前排除结论在错误流模型下得出，作废重测）。
- **类型树字面量假设证伪**：真流断点后为字面量+匹配混合编码
  （如 "m_" 用 off≈24 的短匹配 + "PathID…" 字面量），"T 逐字节等于流中字面量" 不成立，
  滑动对齐 ±16 × 4 算子 × 3 种 key 项来源（528 组合）无一致解。
- **最终求解算法（已验证可行性，未实施）——"已知输出续段前向求解"**：
  1. 前提：解析至 out=1717 全部正确（"…m_FileID\0" 为最后一字节）；
  2. 已知续段：out[1718:] = "m_PathID\0m_Container\0AssetInfo\0preloadIndex\0…"
     （R014 @0xe70ea 的标准 AssetBundle 类型树，>200B 已知明文）；
  3. 每 u32 的 key 项 K_i = 唯一未知量；字面量 token 处 K_i 可直接由
     已知输出字节反解（K_i = true_plain ^ (其余已知项)）；
  4. 匹配 token 处：off 必 ≤ 已确认正确的前缀长度（强约束），候选 token
     按"解密后字节 = 已知输出字节"过滤；
  5. 前向逐 token 求解 + 歧义处回溯，全部 K_i 落定后即得真值表 → 反推算子语义修正
     → 全包解码。
  实现要点：块边界跨 i（i=0..31 循环用 tbl[i%9]/key2[i]），K_i 需按 (块,j,i) 索引；
  op B/D 的 K_i = keyarr[key2[i]&3]（4 选 1，强约束），op A 的 K_i = tbl[i%9]（全字），
  op C = keyarr[tbl[i%9]&3]——即只需对 A/B/C/D 判定哪一类 + 4 选 1，搜索空间极小。
- **设备资产（已沉淀）**：memdump.c/memdump_native（NDK process_vm_readv + 页级回退）
  + redump.sh 流程可复用；VA stub 进程（进程名 com.gof.china、uid=号库壳）run-as 可读；
  冻结/换页问题需前台亮屏时机，本轮未获解压真值。

### 21.9 追加三（同日深夜二）：根因锁定 = key2（RC4 流）中途漂移

- **块 4 已知明文逐字节验证（决定性）**：类型树字面量 [727:751) = 24B
  `AssetBundle\0m_PreloadTabl` 在我的解密流中**逐字节完全正确**（含 i=3..7 五个 u32）。
  in[751] 起为 LZ4 match token，真值 = `00 0e 00`（off=14 len=4，回拷 "e\0m_"，
  与类型树续段完全吻合）；我的解密 = `0f 31 61`。
- **反解真 key2[8]**：由 op B + 已知真 token 反解，真 key2[8] = `42cf153c`（sel=0 组合），
  我的 key2[8] = `4dcf153c`——**仅最高字节差（0x4d vs 0x42）**。
  key2 词 0..3（字节 #1..16）已验证正确 → **RC4 流在约第 33 字节后漂移**。
- **结论：key2 生成（RC4 常量初始化 + KSA(k4) + PRGA×128）存在段边界级细节错误**，
  嫌疑：① S-box 在 PRGA 前被部分重置（0x3165fa0 的 stp 序列覆盖范围需逐字节核对）；
  ② PRGA 的 i/j 起点；③ key2 缓冲与 S-box 同栈段的复用边界。
- **下轮解法（约束充分，纯本地）**：收集类型树全部字面量片段
  （"AssetBundle\0m_PreloadTabl"、"FileID\0"、"Fi"、以及 out 更深处的
  "RuntimeCompatibility\0m_" 等已验证正确片段），每片段反解出对应 key2 字节的
  真值约束（≥30 字节约束量），对 RC4 链变体（重置点 × PRGA 分段）穷举验证。
  约束满足的变体 = 真 key2 → 全量解码 → CAB/序列化/lua → M3。
- 关键数据：块 4 逐 u32 对比表已在本节上方终端输出（i=3..7 OK，i=8 起仅
  部分 字节 对）；`stream_final.bin`（当前流）、`stream_skip0.bin`（同物）。

### 21.10 追加四（同日深夜三）：解密彻底排除，问题收敛为纯 LZ4 语义

- **sim（= 真实代码）全帧栈 dump 成功**（帧基址 = 入口 sp−592；key2@+40、keyarr@+168、
  表@+208）。**key2 全 32 词已提取**（sim_key2_true.bin）：
  词0-11 = aacbdebb ddb227fe 14210c51 4c7f09db b2e36d2e d66b2c15 04029b61 f94b63a0
  4dcf153c bfc62626 7a75103e d4139d24。
  词2 = 14210c51 与本节类型树反解候选集吻合 ✓；词8 = 4dcf153c 与我的 rc4_const 一致 ✓。
  **⇒ 我的 Python 解密 = 代码行为 = 正确，解密线彻底排除。**
- **表（sp+208）sim 实证**：[0]=571 [1]=k3 [2]=892 [3]=750 [4]=k1 [5]=k2 [6]=746
  [7]=k4 [8]=568 [9..]=S3 残留 —— 与我方模型一致。
- **⇒ 剩余唯一问题 = LZ4 解码语义**：类型树中断 @out≈1058 处的 match（off=512/544/672/
  800/992，全部 ≤ out 合法）拷贝的是我解码器输出中 [512-992] 区的内容——该区在我的
  解码里非真值。**即：同样的密文流，Unity 真解码器解出的 [0:1058] 与我一致，
  [1058:] 开始分叉——分叉点前的一个 token 的语义与我不同。**
- **下轮第一步（纯本地，高确定性）**：对 out≈990-1120 的 token 序列（已知）逐一
  枚举 Unity LZ4 的真实语义变体：① 零窗填 0（当前）；② off 回绕 64KB 窗；
  ③ **off 指向"压缩输入缓冲"而非输出**；④ ext-length 边界处理。
  用已知类型树 + "m_Dependency\0" 后续结构做 oracle，一个 token 一个 token 对齐。
  分叉 token 定位后，语义修正 → 全包解码 → CAB/序列化/lua → M3。
- 设备资产不变（memdump_native + redump.sh 可复用）；本轮无 app/ 改动、未 commit。

### 21.11 追加五（终）：错误定位完成 —— 结构层混淆假设 + 前向求解器规范（下轮直接实施）

- **本轮决定性实验链**：
  1. keyarr 24 排列 × 正确流：原排列唯一最优（36B）→ keyarr 顺序正确；
  2. 块 4 已知明文逐 u32：i=3..7 五个 u32 **全部精确正确**
     （流字面量 = "sset Bund le\0m _Pre load" = 类型树原文）；
  3. 错误字节位置清单：in[726]（token 字节）、in[751]（match token）、
     in[762,763]（match off）—— **全部落在 LZ4 结构字节上**，
     且呈"部分 u32 错误"（如 [760:764) 前 2B 对后 2B 错）——
     u32 宽度的矩阵 XOR 算子**不可能**产生这种模式。
- **结论（假设 H*）：解密正确；LZ4 token 流存在额外结构层变换**
  （打包器对 LZ4 结构字节做了轻度混淆/替换，字面量不动），
  或等价地：真流在该处的 token 划分与我的解析不同。
- **下轮唯一任务：实现前向求解器提取真 token 流**（§21.8 规范 + 本节约束）：
  - 状态：(in_pos, out_pos)；已知 out[1718:1880] = 类型树续段（R014 提取）；
  - 每 步 候选：字面量 token（ll, ml=0）与 match token（off ≤ out），
    要求解出的输出 = 已知续段；
  - 字面量路径直接给出真流字节 → 与我方流对比 → 差异 = 结构混淆映射；
  - 已知窗口耗尽处（类型树后）用"LZ4 自洽 + 块尺寸精确闭合"继续；
  - 产出真流后：diff 我方流 → 修正语义 → 全包解码（其余全部已验证）。
- 备忘：设备路线（watcher.sh）保持可用但非必需；本轮全部发现基于纯本地证据链。

### 21.12 最终战报（M3 达成）：号库协议日志 = M3 数据源，无需破解归档

- **M3 商品表已从号库壳自带协议日志直接提取**（proto_log.txt / proto_log_old.txt）：
  `SNAPSHOT-LOGIN:notify_value_exchange_init`（协议 10826）完整 JSON。
- 活动 2702001（雪原贸易站）：exchange_data = [{k:132,v:0},{k:155,v:1}]，
  v = 兑换状态（1 可兑/0 已兑）；两份快照对比证实玩家已兑换物品 132。
  活动窗口 1791648000~1791907200（10-11 16:00 ~ 10-14 16:00 UTC+8）。
- **M4 act_snowtrade 实现 = 解析号库壳自身 proto_log 的该协议即可**，数据源稳定
  （每次登录快照自动记录），无需解密归档。
- 产物：design/m3_data/value_exchange_2702001.json。
- 解密归档线（§21.7-21.11）保留为长期研究：算法已验证，LZ4 池引用语义与
  解压历史重建方法已记录，未来离线解包按文档继续。

---

## 22. 会话交接（2026-10-14 深夜，session 3 收口）

### 22.1 任务主线与当前状态

| 线 | 状态 |
|---|---|
| **M3 雪原贸易站商品表** | ✅ **已达成**（号库壳协议日志直接提取，无需破解归档） |
| **M4 act_snowtrade 模块** | ⏳ 待做：解析号库自身 proto_log 的 notify_value_exchange_init（数据源现成） |
| **归档解密研究线** | 解密算法 100% 验证；剩 LZ4 解压池语义（长期研究，§21.7-21.11） |

### 22.2 M3 成果（已交付）

- 数据源 = 号库壳自带 `proto_log.txt` 的 `SNAPSHOT-LOGIN:notify_value_exchange_init`（协议 10826），
  登录快照自动记录完整 JSON，**M4 无需解密归档**。
- 活动 2702001（雪原贸易站）：exchange_data = [{k:132,v:0},{k:155,v:1}]，
  **v = 兑换状态（1 可兑 / 0 已兑）**；两份快照对比证实玩家已兑换物品 132。
  窗口 1791648000~1791907200（10-11 16:00 ~ 10-14 16:00 UTC+8），version=10，
  is_free_exchange=true。相关商店购买记录 id 470025~470030。
- 产物：`design/m3_data/value_exchange_2702001.json`；本地 `proto_log.txt/proto_log_old.txt`。

### 22.3 解密研究线收口状态

- **解密算法 100% 验证**：key2 全 32 词从 sim 真实执行提取（`sim_key2_true.bin`）与
  Python rc4_const 一致；表 sp+208 = rodata 0x3141EB4 头 32B + k-spill（sim 实证）；
  块 4 类型树字面量逐字节正确（i=3..7 五个 u32 精确）。
- **错误定性**：解密正确；错误全部位于 LZ4 结构字节（token/match 头），呈部分-u32 形态
  → 唯一自洽解释 = LZ4 token 流有结构层变换或真流 token 划分不同。
- **LZ4 机制**：Unity LZ4 零窗扩展（off>out 合法）+ **解压池引用**
  （off 越界匹配 = 引用游戏解压历史残留的 64KB 窗口，池内有同类归档的类型树）。
  池内容 = 游戏解压历史，静态不可知，需设备在解压时刻捕获。
- **修复路径**：§21.8 前向求解器（已知输出续段 + 逐 token 前向，字面量路径自解 K 项）
  或设备真值对拍。

### 22.4 设备与环境（重要坑位）

- **设备地址会变**：本session经历 192.168.1.7:38215（失效）→ 10.219.166.246:40503（当前）。
- **两个游戏实例**：独立版（uid u0_a113，系统隔离永不可读）与号库容器版
  （stub 进程名 com.gof.china、uid u0_a373 = 号库壳 uid，run-as 可读）。
  **必须扫描容器版**；用户常混淆两者。
- **toybox dd 32 位 seek 溢出**：>2GB 偏移静默回绕读出错误数据 → 必须用
  `iflag=skip_bytes,count_bytes`（已验证）。
- **shell 算术 32 位有符号**：区段地址 ≥2GB 在 ash `$((16#...))` 中溢出为负 →
  区段清单的解析必须在 C 层做（memdump2 已内置 maps 解析）。
- **VA 容器切后台即丢弃内存页**（读回全零）→ dump 必须在前台亮屏时机；
  `watcher.sh`（30 分钟窗口，自动发现 PID + 等待可读 + dump + 搜标记）可复用。
- 号库 = Lody VirtualApp（VirtualCore.getLaunchIntent(GAME_PKG, uid)）。

### 22.5 工具与产物清单（tools/bundle_re/）

- `memdump_native`/`memdump2` + `memdump.c/memdump2.c`：NDK 原生 dumper
  （process_vm_readv + 页级回退 + 内置 maps 解析，64 位安全）
- `heap_native_full.bin`（本地 3.57GB，号库容器游戏全堆真值，含全部解压内容）
- `watcher.sh`/`auto_dump.sh`/`dumpmem5.sh`（iflag 修复版）/`scan*.sh`
- `stream_final.bin`（解密后块0流）、`out_final.bin`、`sim_key2_true.bin`、`sim_table_true.bin`
- `bundle_full_decrypt.py`（块0 解密管线，已验证）
- `mined/`（类型树、lua 块、雪原贸易站区段提取物）、`vex_pull2/vB.bin`（value_exchange 协议 schema）

### 22.6 下轮任务

1. **M4 act_snowtrade**（主线）：号库工程（本仓库 app/）新增模块解析
   `notify_value_exchange_init`（JSON 在 proto_log），展示雪原贸易站兑换状态/提醒。
   注意：app/ 有用户未提交改动，先开新分支；commit/merge 等用户确认。
2. （可选）解密收尾：容器游戏进入游戏本体后重扫（watcher.sh），或 §21.8 前向求解器。
3. 设备：10.219.166.246:40503（会变）；watcher 自动发现 PID，无需硬编码。

---

## 23. Session 4（2026-10-14 深夜）：M4 收口——v 语义更正 + 兑换清单 UI 补齐

### 23.1 关键更正：exchange_data 的 v = 已兑次数（§22.2 结论有误）

- **更正**：`exchange_data` 的 `v` 不是「1 可兑 / 0 已兑」，而是 **该角色在该档位的已兑次数**
  （按角色隔离，活动期内不重置）。§22.2「对比两份快照证实玩家已兑换物品 132」
  实为**误比了不同角色的快照**（10-12 快照来自 sid 913，10-13 快照来自 sid 916）。
- **裁定证据（proto_log 原始记录，同一时刻双角色快照并存）**：
  - sid 913 全程 `132:v=1`（K913 手动兑过 132——与 ShopTasks 注释「K913 手动兑的档」互证）；
  - sid 916 全程 `132:v=0`（未兑），两角色 `155:v=1`（各兑过一次）；
  - 10-12→10-13 跨日 v 无重置。
- **推论**：`exchange_data` = **全量档位状态表**（未兑档位也在推送里，916 从未兑过仍列出 132/155）；
  `is_free_exchange` = 活动的免费兑功能开关（恒 "true"），免费档是否可兑实际看该档已兑次数。
- **影响**：号库 app 未提交改动中的 `ShopTasks.snowTrade` 把 v 当已兑次数（bought 表、
  `count = want - bought`）——**语义正确，无需修改**；号库功能「雪原贸易站」自动兑换
  （免费兑+按清单兑换）在更早的「协议盘点」会话已实现（FnRegistry/FnDetail/DailyTasks/ShopTasks，
  即当前工作区未提交改动的一部分），§22.6「M4 待做」的前提不成立。
- `design/m3_data/value_exchange_2702001.json` 的 notes 已同步更正（快照补 sid 字段）。

### 23.2 本轮 M4 增量（分支 add/snowtrade-pick-ui-20261014-2156，未提交）

- **`SubCardUi.snowtradePickRow`**：功能页 buy 子项新增档位勾选行（对齐烈焰獠牙
  `flamefangsPickRow` 模式）。清单 = 内置已知档（132 免费档/155 实证档）∪ 最近
  exchange_data 快照档（snowTrade 缓存于 FnParams `seen_idx`）→ 下期活动档位自适应。
- **`ShopTasks.snowTrade` 状态行补齐**：buy 子项新增实时状态（`档155 1/3→补2 · 档132 3/3 已足`），
  清单未配置时提示已知档位；活动未开放时 free/buy 状态行同步刷新（原来停留旧值）。
- **`FnDetail`**：buy 子项描述更新为勾选交互。
- 展示/提醒定位：子项状态行（26h 内为实时、超期为 ⏳ 快照）+ 每日任务轮自动兜底，
  不新增通知机制。

### 23.3 遗留与备注

- 本期活动（2702001）已于 2026-10-14 16:00 结束，兑换流程未做真机验证；下期活动开放后
  勾选清单会随 seen_idx 自适应，届时按任务轮日志核对。
- 归档解密研究线（§21.7-21.11）状态不变：解密 100% 验证，剩 LZ4 解压池语义，长期研究。
- 设备/坑位备注（§22.4）全部继续有效。

### 21.5 工具沉淀

- `tools/bundle_re/bundle_full_decrypt.py`：完整管线（EE DD 帧解密 + Unity LZ4），
  块 0 的解密已可复现（密文→明文帧），流组装部分待 21.4-② 落定后收口。
- 本轮关键中间产物：`blk0_final.bin`（解密后块0）、`archive_data.bin`（95.2% 解码）、
  `sample_data.bin`（sample 包 92.6% 解码，同缺口 → 缺口是系统性的，与热更无关）。
