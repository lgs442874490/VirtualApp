# 鸿蒙「找不到安装包、无法克隆无尽冬日」根因分析与修复（2026-10-29）

> 反馈原文：多开分身有个 bug，鸿蒙系统提示找不到安装包无法克隆，实际上是**有**安装好无尽冬日这个游戏的。
> 反馈补充：设备为 **HarmonyOS 7**；游戏从**华为应用市场**安装（应用市场下发的可能是鸿蒙版）。

## 1. 结论（一句话）

不是安装包丢了，也不是号库的 bug 把包弄坏了：**鸿蒙应用市场给 HarmonyOS 7 下发的是
「鸿蒙版」无尽冬日（HAP 原生应用）**，它不属于 Android 应用，安卓 PackageManager
（号库/VA 容器所在的环境）查不到、也无法克隆。号库旧文案「手机上未找到已安装的无尽冬日」
把「鸿蒙原生版不可克隆」说成了「没装」，才让用户以为安装包不见了。

**能修的部分**：把误导性文案改成说清原因 + 给可执行出路，并让「安卓版 APK」一键导入容器：
- 「下载」目录里有 `wjdr*.apk` → 点「开始克隆」自动识别、校验后装入容器（无需先装原生安卓版）；
- 没有本地包 → 弹窗说明「鸿蒙版 HAP 不可克隆」，提供「选择本地 APK」（SAF 选包，免存储权限）
  与「下载安卓版」两个入口。

**不能修的部分（产品边界）**：安卓容器无法运行/克隆鸿蒙原生版（HAP）。要使用号库，手机里必须有
**安卓版**无尽冬日 APK（自己下载导入，或在卓易通等安卓兼容环境里装安卓版）。

## 2. 现场与证据

| 项 | 内容 |
|---|---|
| 设备 | HarmonyOS 7（华为） |
| 游戏来源 | 华为应用市场 → 2025-07 起应用市场已上架鸿蒙版《无尽冬日》，HarmonyOS 5/6/7 默认下发 HAP |
| 现象 | 点「克隆软件 / 开始克隆 / 重新克隆分身」→ 提示找不到安装包、无法克隆 |
| 实际 | 游戏在桌面/设置里明明已安装（鸿蒙应用列表里可见） |

代码链路（旧行为）：

1. `VaultEngine.installGameFromSystem()` → `cloneFromSystem(GAME_PKG, "无尽冬日")`
   - `ctx.getPackageManager().getApplicationInfo("com.gof.china", 0)` → `NameNotFoundException`
   - catch 后返回 **「手机上未找到已安装的无尽冬日」**
2. `VaultEngine.recloneGameFromSystem()` → `GameRecloneFlow.blockText(NATIVE_MISSING)`
   → **「无法重新克隆：手机上未找到已安装的原生游戏「无尽冬日」，请先安装或更新原生游戏」**
3. 若系统 PM 能查到包、但引擎解析不到（package: URI），返回 `安装失败: status=7/8`
   （`STATUS_FAILURE_INCOMPATIBLE / NO_BASE_APK`）。

鸿蒙原生版（HAP）不在 Android PackageManager 的查询范围内，因此第 1/2 步必然失败；
普通安卓机（小米/vivo 等）装的是 APK，所以一直没暴露。

## 3. 修复内容

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/carlos/vault/gameupdate/GameCloneFallback.java`（新增） | 纯函数：识别「找不到安卓版」类失败（`未找到已安装`/`无法读取原生游戏版本`/`status=7/8`）、华为/鸿蒙机型判定、引导文案（固定前缀 `系统里没有可克隆的「安卓版」无尽冬日`）与原文拼接 |
| `app/src/main/java/com/carlos/vault/gameupdate/GameCloneGuide.java`（新增） | 引导弹窗（说明 HAP 原因 + 两条出路）、SAF「选择本地 APK」（`ACTION_OPEN_DOCUMENT`，免存储权限）、选包后复制到私有 cache → 校验 → 装入容器 |
| `app/src/main/java/com/carlos/vault/VaultEngine.java` | `installGameFromSystem()` / `recloneGameFromSystem()`：系统包不可见/引擎 status=7/8 时自动退到「下载」目录最新 `wjdr*.apk`（`LocalApkScanner` + `GameApkVerifier` + `installGameFromLocalApk`）；没有本地包时返回引导文案；新增 `installGameFromLocalApk(File)`（首次=全量安装，已有=保数据覆盖；包名/版本/签名校验） |
| `app/src/main/java/com/carlos/vault/AccountVaultActivity.java` | 克隆向导弹窗化：失败且属「找不到安卓版」→ `GameCloneGuide` 引导弹窗；`onActivityResult` 接入选包结果 |
| `app/src/main/java/com/carlos/vault/EntryDetailActivity.java` | 「去克隆」「重新克隆分身」失败同样走引导弹窗；新增 `onActivityResult` 接入选包结果 |
| `app/src/main/AndroidManifest.xml` | `<queries>` 声明 `com.gof.china`/`com.gof.global`/`com.tencent.mm`：覆盖 HarmonyOS 2/3/4 对第三方 App 也做「已安装应用列表」可见性过滤的情况（targetSdk 26 在 AOSP 不受限，但鸿蒙有实测受限案例） |
| `app/src/test/java/com/carlos/vault/gameupdate/GameCloneFallbackTest.java`（新增） | 11 条 JVM 单测：归因边界、机型判定、鸿蒙文案要素（鸿蒙版/HAP/选择本地 APK/卓易通/wjdr）、原文不重复拼接、引导文案归 CLONE 诊断类 |
| `无尽号库使用说明.md` | 常见问题新增鸿蒙条目 |

用户侧可执行路径（修复后）：

1. 克隆页点「开始克隆」→ 若「下载」目录有 `wjdr*.apk` 自动导入；否则弹窗说明原因；
2. 弹窗点「选择本地 APK」→ 文件选择器挑安卓版无尽冬日 APK（推荐）；
3. 或点「下载安卓版」→ 浏览器打开官方下载页（`https://wjdr-app.campfiregames.cn/download`）；
4. 或在卓易通/安卓环境装好安卓版后再点「开始克隆」自动识别原生包克隆（不占额外空间）。

## 4. 验证

- JVM：`./gradlew :app:testDebugUnitTest --tests "com.carlos.vault.gameupdate.GameCloneFallbackTest"` 通过；
- 编译/manifest 合并：`:app:processDebugMainManifest`、`:app:compileDebugJavaWithJavac` 通过（`<queries>` 合并正常，AAR 自带 `QUERY_ALL_PACKAGES` 不冲突）；
- 真机待验（需鸿蒙设备）：
  1. 鸿蒙机、应用市场装鸿蒙版无尽冬日 → 点「开始克隆」应弹「未找到安卓版「无尽冬日」」引导弹窗（不再是一句「找不到安装包」）；
  2. 弹窗「选择本地 APK」选安卓版 APK → 校验通过后容器内出现游戏，可继续②首启；
  3. 把 `wjdr*.apk` 放「下载」目录（并允许存储权限）→ 点「开始克隆」直接完成克隆；
  4. 安卓机回归：原生已装安卓版时行为不变（仍走 package: URI，秒克隆、不复制大文件）；
  5. 重新克隆分身（保数据覆盖）在「本地 APK」路径下数据仍在。

## 5. 剩余限制 / 后续

- 安卓容器无法运行鸿蒙版（HAP）——这是平台边界，只能引导用户装安卓版；
- 鸿蒙 7 上号库需跑在「卓易通」等安卓兼容层里，VA 引擎能否完整跑游戏仍需真机验证；
- 本地 APK 导入走「复制到私有 cache」再安装，大包（≈888MB）会额外占用一份临时空间，
  安装成功后自动删除；存储紧张时提示用户清理。

## 6. 真机验证 runbook（鸿蒙，2026-10-29 补齐）

> 前置：一台 HarmonyOS 手机；应用市场已装「鸿蒙版」无尽冬日；号库（debug 或待发版）已装。
> 全程可用 `adb logcat -s GAMEUPD` 观察；日期/机型号请回填本报告。

| # | 步骤 | 期望 |
|---|---|---|
| 准备 | 只装鸿蒙版游戏（确保安卓环境里没有 com.gof.china 的 APK） | 系统设置里能确认是鸿蒙应用 |
| 1 | 号库 → 我的 → 克隆软件（或初始化向导①克隆） | 不再出现「克隆失败：手机上未找到已安装的无尽冬日」；弹出**「未找到安卓版「无尽冬日」」**引导弹窗 |
| 2 | 阅读弹窗文案 | 说清「鸿蒙版 HAP 安卓容器看不到」；有「选择本地 APK / 下载安卓版 / 取消」 |
| 3 | 点「选择本地 APK」，选一个安卓版无尽冬日 APK | 进度显示「正在读取本地安装包…」→ 成功 toast「克隆完成 ✓ 继续点②首启」；容器里出现游戏 |
| 4 | 继续②首启 | 游戏能拉起并下载资源（与普通安卓机一致） |
| 5 | 换机测兜底：把 `wjdr*.apk` 放「下载」目录，允许存储权限，点「开始克隆」 | 不弹窗，直接识别并装入（日志 `GAMEUPD: 系统包不可见，已用「下载」目录的本地 APK 完成克隆`） |
| 6 | 无本地包且不点选包，重复点「开始克隆」 | 每次都稳定弹引导弹窗（不崩溃、不误报「已克隆」） |
| 7 | 重新克隆路径：入口详情页 →「重新克隆分身」 | 同为引导弹窗；选本地 APK 后容器数据仍在（`hasLiveContent` 为真） |
| 8 | 安卓机回归（小米/vivo/荣耀） | 原生已装安卓版时，点「开始克隆」仍秒克隆（package: URI，无额外弹窗、无本地导入日志） |
| 9 | 微信支付路径回归 | 容器微信版本检测/装入不受本次改动影响 |

异常分支：

- **SAF 选包后失败**：弹「本地安装包克隆失败」+ 具体原因（包名不符/版本未提升/签名不一致/存储不足），原文需回填到本报告；
- **文件选择器打不开**：toast 提示把 APK 放进「下载」目录重试；
- **卓易通兼容层限制**：若②首启起不来，记录 `logcat`（VA 引擎进程/`libvbox` 报错）——属于「安卓兼容层能否跑 VA」的问题，与本修复分开跟踪。
