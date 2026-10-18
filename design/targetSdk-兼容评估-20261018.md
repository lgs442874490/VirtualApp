# targetSdk 兼容评估（2026-10-18）

> 现状：`compileSdk 33`、`targetSdk 26`、`minSdk 21`（`AppConfig.gradle`）。
> 本文只做评估与分阶段方案，不在本批次直接改 targetSdk——VA 引擎、外部存储重定向、
> FGS、16KB page size 任何一项都可能导致克隆/启动/保活回归，必须独立分支验证。

## 1. 为什么现在要评估

- Android 14/15/16 对前台服务、通知、精确闹钟、后台启动、存储的约束逐年收紧；
  targetSdk 26 目前靠“旧目标兼容”绕开一部分限制，但 OEM（HyperOS/OriginOS）
  会按自己的策略限制，且 Google Play 对 targetSdk 有最低要求（本工具侧载，不直接受影响）。
- 已发现的现实问题：`POST_NOTIFICATIONS` 未声明（已修）、`GameKeepAliveService`
  无 FGS 类型（已补 `dataSync`）、`dataSync` 在 Android 15 有 6h/24h 限额。
- VA 引擎（预编译 AAR）对高 targetSdk 的包可见性、后台启动、外部存储路径敏感，
  不能只改 Gradle 数字。

## 2. 逐项风险

| 项 | targetSdk 26 现状 | 升级后风险 | 处理 |
|---|---|---|---|
| 前台服务类型 | 26 不强制；`GameKeepAliveService` 已补 dataSync | 34+ 必须声明类型；`dataSync` 在 15 有 6h/24h 限额 | 逐服务选类型；长期保活改服务器模式 |
| 通知权限 | 已声明 `POST_NOTIFICATIONS`，运行时请求 | 13+ 拒绝后 FGS 通知不可见 | 已修；补引导文案 |
| 精确闹钟 | `SCHEDULE_EXACT_ALARM` 已声明，代码有降级 | 12+ 需用户授权；14 默认拒绝部分场景 | 保留 `canScheduleExactAlarms` 降级；引导授权 |
| 存储 | `requestLegacyExternalStorage=true` 在 26 有效 | 29+ 分区存储；VA 外部路径/克隆资源可能不可见 | 升级前验证 `Android_va` 路径、`/sdcard/Android/data` 访问 |
| 包可见性 | 26 无限制 | 30+ `queryIntentActivities`/包查询受限 | 加 `<queries>` 或 `QUERY_ALL_PACKAGES`（需评估商店政策） |
| 后台 Activity 启动 | 26 较宽松 | 29+ 限制；通知/服务里 startActivity 可能被拒 | 启动游戏时确保有前台 Activity；不要从后台静默拉起 |
| 16KB page size | 26 无要求 | Android 15 对 targetSdk 35 要求 native lib 16KB 对齐 | **先检查 `libvbox.so`/`libshadowhook.so` 的 LOAD 对齐**；不满足则不能升 35 |
| 边到边/预测返回 | 26 旧行为 | 35 强制 edge-to-edge | 升级前做 UI 适配 |
| VA 引擎兼容 | 26 已验证 | 34/35 的 Activity/Service/Provider 回调、包可见性、存储可能变 | 每次升级跑完整克隆/启动/切号/保活回归 |

## 3. 分阶段方案

### Phase 0（当前，保持 26）
- 已完成：`POST_NOTIFICATIONS` 声明、`GameKeepAliveService` 补 `dataSync` 类型。
- 继续：在 Android 14/15/16 真机/模拟器记录克隆、首启、切号、保活基线；
  收集 `logcat`/`DropBox`/`exit-info`，为升级对比。

### Phase 1（targetSdk 33/34，独立分支）
- 加 `<queries>`（或 `QUERY_ALL_PACKAGES`）解决包可见性；
- 为所有 FGS 声明类型；补 `FOREGROUND_SERVICE_*` 权限；
- 验证 VA `installPackage(package:)`、`getLaunchIntent`、`startActivity`、
  `Android_va` 外部路径、快照/注入；
- 重点回归：克隆、首启、切号、24h 常驻、通知唤醒、精确闹钟。

### Phase 2（targetSdk 35，独立分支）
- 检查 native lib 16KB 对齐（`readelf -l` 的 LOAD align ≥ 0x4000）；
- 处理 edge-to-edge、预测返回、`dataSync` 6h/24h；
- 评估是否把自动化默认切到 `vault-server`，手机端只保留“玩游戏”。

### Phase 3（targetSdk 36 / Android 16）
- 与 `engine-upgrade-android16` 分支合并验证；
- 重点：VA 引擎在 Android 16 的进程/窗口/焦点行为、FGS 限制、后台冻结。

## 4. 验收矩阵

| 场景 | 26（基线） | 34 | 35 | 36 |
|---|---|---|---|---|
| 克隆系统 APK | ✅ 已验证 | 待测 | 待测 | 待测 |
| 首启下载资源 | ✅ 已验证（补丁引擎） | 待测 | 待测 | 待测 |
| 切号/快照/注入 | ✅ 已验证 | 待测 | 待测 | 待测 |
| 双开/文件锁 | 待测 | 待测 | 待测 | 待测 |
| 24h 常驻/心跳 | 待测（服务器模式优先） | 待测 | 待测 | 待测 |
| 通知/精确闹钟 | 待测 | 待测 | 待测 | 待测 |

## 5. 结论

- **本批次不直接升级 targetSdk**；先完成 Phase 0 基线与 FGS/通知修复。
- 升级的第一道硬门槛是 **VA 引擎 + 16KB page size + 包可见性/存储**，
  不是 Gradle 里改一个数字。
- 每次升级独立分支、独立装机回归；不通过就回滚 targetSdk，不影响现有 26 版本。
