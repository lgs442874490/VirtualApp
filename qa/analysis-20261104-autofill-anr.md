# 分身闪退 + 游戏内输入框迟滞：日志分析与修复（2026-11-04 04:24–04:31 真机日志）

> 设备：vivo V2141A（PD2141）/ Android 14 / 无线调试 `192.168.1.7:38587`
> 宿主：`com.carlos.multiapp` 4.2.48-HTTPS加密（versionCode **23559**，lastUpdateTime 2026-11-04 04:12:19）
> 容器内游戏：`com.gof.china` 1.16.x（原生同版本已安装，UID 10113；克隆属主 UID 10379）
> 取证材料：`/tmp/va_diag/`（`logcat_main.txt` 15766 行、`dropbox_anr.txt` 81542 行、`window.txt` 04:24–04:32）
> 分析对象：设备 `logcat` / `dumpsys dropbox data_app_anr` / `/data/anr` 文件列表 / 已装 APK 的 dex 与 so

## 1. 结论（一句话）

**两个症状是同一个根因**：容器内的 autofill 会话发起被 system_server 以 `SecurityException: Invalid component` 拒绝，而 `IAutoFillManager.startSession` 是 **oneway** 调用、异常不回传，客户端主线程在 `SyncResultReceiver.getIntResult()` 里死等满 **5000ms**，于是：

- 在启动分身的首屏布局阶段 ⇒ **输入派发超时 ANR ⇒ 系统移除容器任务 = 用户看到的「闪退」**；
- 在点游戏内输入框（`View` 获焦 / 布局）阶段 ⇒ **主线程被卡 5 秒，键盘要等 5 秒才弹 = 「要点好几次 / 等大几秒才出输入框」**。

**这不是新 bug，也不是回归性代码缺陷**：修复在 2026-10-25 就已合入 master（引擎 AAR 两处字节码补丁，见 `qa/evidence-20261025-clone-autofill-anr/README.md` §8–§9）。**这次是构建流程绕过发版门禁，把没打补丁的引擎打进了 APK**。

## 2. 现场时间线（2026-11-04）

| 时间 | 事件 | 证据来源 |
|---|---|---|
| 04:24:15 | 宿主进程 17511 启动 | logcat |
| 04:24:46.348 | `EntryDetail: [IME预热] 开始` → 04:24:46.655 完成，继续启动分身 | logcat |
| 04:24:49.186 | `VActivityManagerService: start new process : com.gof.china pid: 20784`（第 1 次） | logcat |
| 04:24:51.336 | 克隆内 12 条 `SettingsProviderHook … _prefix=autofill/`（AutofillManager 初始化读 DeviceConfig） | logcat |
| 04:25:52.159 | 克隆内再次 `SettingsProviderHook … _prefix=autofill/`（用户点输入框 / 首屏布局获焦） | logcat |
| 04:25:52.162 | **系统侧**：`AutofillManagerServiceImpl: App (package=com.carlos.multiapp, UID=10379) passed component (ComponentInfo{com.gof.china/com.unity3d.player.DDUnityLaunchActivity}) owned by UID 10113` | dropbox |
| 04:25:52.163 | **系统侧**：`Binder: java.lang.SecurityException: Invalid component` @ `assertCallerLocked:606` → `createSessionByTokenLocked:560` → `startSessionLocked:404` | dropbox |
| 04:25:57.161 | **客户端**：`AutofillManager: Exception getting result from SyncResultReceiver: TimeoutException: Not called in 5000ms`（死等满 5s） | logcat |
| 04:25:57.187 | `Choreographer: Skipped 302 frames!`（主线程被卡住） | logcat |
| 04:25:57.451 | `reacting to signal 3` → 04:25:59.940 `Wrote stack traces to tombstoned`（ANR 抓栈） | logcat |
| 04:26:01 | `ActivityManager: ANR in com.carlos.multiapp:p0 (…ShadowActivity$P0)` | dropbox |
| 04:26:06.204 | 第 2 次拉起 `com.gof.china pid: 22072` | logcat |
| 04:26:46.632 | 点输入框 → 5s 空档 → 04:26:51.635 超时 → **04:26:51.815 才 `ImeTracker onRequestShow`** → 04:26:52.164 `onShown` | logcat |
| 04:26:51.650 | `[CrashSightReport-Native]: get signal 3` | logcat |
| 04:27:10 / 04:27:34 | 第 3、4 次拉起（23384 / 23681） | logcat |
| 04:28:32.717 → 04:28:37.719 | 又一次 5s 死等超时；04:28:37.789 `onRequestShow` → 04:28:38.116 `onShown` | logcat |
| 04:28:41.498 → 04:28:46.500 | 连续第二次 5s 超时 → 04:28:46.539 `get signal 3` | logcat |
| 04:30:38.335 | 第 5 次拉起（25034，当前存活） | logcat |

`/data/anr` 里对应三次 ANR 文件：`anr_2026-11-04-04-25-57-277`、`anr_2026-11-04-04-26-51-686`、`anr_2026-11-04-04-28-46-585`。
`dumpsys dropbox data_app_anr` 里对应三条：04:26:03、04:27:05、04:29:00。

## 3. 证据链（为什么是 autofill）

### 3.1 ANR 主体

```
Subject: Input dispatching timed out (8a1440a com.gof.china/com.unity3d.player.DDUnityLaunchActivity
         (server) is not responding. Waited 5000ms for FocusEvent(hasFocus=false))
```

三次 ANR 主体完全一致（另两次为 `7376303 … Waited 5001ms`、`cf7a267 … Waited 5000ms`）。

### 3.2 客户端主线程栈（`Cmd line: com.gof.china`，pid 20784，04:25:57）

```
"main" prio=5 tid=1 TimedWaiting
  at jdk.internal.misc.Unsafe.park(Native method)
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:252)
  at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:276)
  at com.android.internal.util.SyncResultReceiver.waitResult(SyncResultReceiver.java:55)
  at com.android.internal.util.SyncResultReceiver.getIntResult(SyncResultReceiver.java:68)
  at android.view.autofill.AutofillManager.startSessionLocked(AutofillManager.java:2419)
  at android.view.autofill.AutofillManager.notifyViewEnteredLocked(AutofillManager.java:1772)
  at android.view.autofill.AutofillManager.notifyViewEntered(AutofillManager.java:1594)
  at android.view.autofill.AutofillManager.notifyViewEntered(AutofillManager.java:1406)
  at android.view.View.notifyEnterOrExitForAutoFillIfNeeded(View.java:8520)
  at android.view.View.layout(View.java:24992)
  at android.widget.RelativeLayout.onLayout(RelativeLayout.java:1109)
  …
```

即：**布局/获焦路径同步进入 autofill，主线程阻塞在 5s 超时等待上**，与 InputDispatcher 的 5s 派发超时同额 ⇒ 先 ANR、后被杀。

### 3.3 为什么系统会拒绝（决定性）

VA 克隆把「虚拟包名 + 原 Activity 类名」原样发给系统：

```
App (package=com.carlos.multiapp, UID=10379) passed component
    (ComponentInfo{com.gof.china/com.unity3d.player.DDUnityLaunchActivity}) owned by UID 10113
→ AutofillManagerServiceImpl.assertCallerLocked:606 → SecurityException: Invalid component
```

调用方是宿主（UID 10379），组件属主是原生游戏（UID 10113），校验必然失败。该 binder 调用为 oneway，异常只落在系统侧日志，客户端永远收不到回包 ⇒ `SyncResultReceiver` 等满 5000ms。

### 3.4 「输入框要等大几秒」的量化

| 时刻 | 动作 | 到键盘 `onShown` 的延迟 |
|---|---|---|
| 04:26:46.632 点输入框 | 5.003s 死等（04:26:51.635 超时） | onRequestShow 04:26:51.815 → onShown 04:26:52.164 = **5.53s** |
| 04:28:32.717 点输入框 | 5.002s 死等（04:28:37.719 超时） | onRequestShow 04:28:37.789 → onShown 04:28:38.116 = **5.40s** |

对照：宿主自己页面内 `onRequestShow → onShown` = 04:24:36.314 → 04:24:36.632 = **318ms**（正常量级）。
⇒ 用户感受到的「等大几秒」就是这 5 秒；「要点好几次」是因为第一次点击的焦点/布局事件被 ANR 打断、焦点丢失，需要重试。

## 4. 为什么这次又出现了（构建回归，不是代码回归）

2026-10-25 的修复是两处**引擎 AAR 字节码补丁** + 换入补丁 `libvbox.so`，由 `tools/release.sh` 门禁 2 在发版时换入、构建后还原仓库原版 AAR。**手工 `./gradlew assembleRelease` 会绕过门禁 2**，直接用仓库里的原版 AAR。

已装 APK（sha256 `c6b725bb…`，versionCode 23559）实测：

| 检查项 | 已装 APK | 补丁引擎（应为） |
|---|---|---|
| `lib/arm64-v8a/libvbox.so` | `a0f06e48…`（原版） | `0dd16cb7…` |
| `InvocationStubManager.injectInternal` 中 autofill 注册路径 | `invoke-static BuildCompat.isTiramisu()` + `if-nez` ⇒ Android 13+ **跳过** `AutoFillManagerStub` 注册 | `const/4 v0, #0` ⇒ 恒注册 |
| 运行期日志 `AutoFillManagerStub inject` | **0 条** | 应有 |

进一步定位构建来源：该 APK 与
`/Users/liangguansheng/ZCodeProject/VAContainer/.agents/worktrees/modify/guardian-review-fixes-20261103-2332/app/build/outputs/apk/release/app-release.apk`
（2026-11-04 03:16 产出）**sha256 完全一致**，而该 worktree 的 `app/sdk/lib-release.aar` 是原版 `06531a73…`。
⇒ 结论：**这次是在 guardian worktree 里手工构建的，没有走 `tools/release.sh`，补丁引擎没进包。**

对照：master 走 `release.sh` 出的 `无尽号库-v4.2.48-HTTPS加密版.apk`（sha256 `69cab6a7…`）里 `libvbox.so` = `0dd16cb7…`（补丁版），是正常的。

> 顺带：`libvbox.so = a0f06e48…` 还意味着 Android 14+ `renameat2` 丢 flags 的老缺陷（MEMORY.md §1）也在这版里复发，容器内游戏下载/更新可能再次 `EINVAL`。同一个漏打补丁动作，两个后果。

## 5. 修复方案

**根治点在构建流程，不在业务代码**：

1. `app/sdk/lib-release.aar` 直接换为补丁版（sha256 `ccc44319…`），让**任何**构建（手工 / worktree / debug / CI）都天然带补丁，消灭「忘了走 release.sh」这一类回归；
2. `app/build.gradle` 增加 `preBuild` 哈希校验任务：仓库 AAR 不是补丁版就**构建失败并打印修复指引**，把「静默发错包」变成「构建期红灯」；
3. `tools/verify_apk.sh` 增加第 6 项门禁：**反编译 APK dex 校验 autofill 补丁确实在包里**（不只看 so 哈希），这样 autofill 补丁单独被回退也能拦住；
4. `tools/release.sh` 门禁 2 改为幂等（已是补丁版则只校验哈希）；
5. `tools/engine/patch_engine.py` 支持「基座已是补丁版」的幂等重建，避免脚本在补丁基座上误报。

> 不做的事：不改业务代码；不靠「关掉系统自动填充服务」这种设备侧规避（2026-10-25 README §6 已列为临时规避，本次要的是修复）；不动 `engine-upgrade/`。

## 6. 真机验证结果（PASS）

完整证据：`qa/evidence-20261104-autofill-anr/README.md`。

| 判据 | 修复前 | 修复后 |
|---|---|---|
| hook 已挂载（游戏进程内） | 无 | `MethodInvocationStub: The Hook(isServiceEnabled, …AutoFillManagerStub$2) … added`（pid 28411） |
| `SyncResultReceiver$TimeoutException` | 4 次 | **0 次** |
| `AutofillManagerServiceImpl` / `Invalid component` | 5 次 | **0 次**（改为正常会话：`sysui_multi_action 906/907/919 + 908,com.vivo.cipherchain`） |
| `dumpsys activity lastanr` | 04:28:46（3 次 ANR） | **仍 04:28:46，距装机 17 分钟未推进** |
| 游戏进程重启 | 6 分钟 5 次 | **仅 1 次**，pid 28411 稳定 |
| **autofill 请求 → 键盘 `onRequestShow`** | **5068 / 5072 / 5183 ms** | **56 / 61 / 69 / 70 / 73 ms（6/6，≈70~90 倍）** |
| `onRequestShow` → `onShown` | 327~349 ms | 314~336 ms（持平，未被本次改动影响） |

号库数据（4 账号 / 4 角色 / 4 快照 / 16 prefs）一条未丢；系统 autofill 服务未被关闭或改动
（仍 `com.vivo.cipherchain/...CipChainService`）—— 修复来自容器引擎 hook，不是规避系统能力。

**遗留（另一个 bug，本次未修）**：容器内 Unity IME 覆盖窗口（`com.unity3d.player.Q`）**首次创建**约 **4.375 s**
（一次性冷启动，同会话后续 0.26~0.52 s）。证据：tap 后游戏进程 4.2 s 零日志，
`input_focus … reason=Waiting for window because NO_WINDOW` → `Window became focusable`；
该段发生在 autofill 请求**之前**，与本次修复无关，修复前同样存在。建议单独立项。

## 7. 附带观察（本次不修，记录备查）

- `VaultSync: /v1/vault/state|accounts|sync|token|policy 网络失败: timeout` 在 04:24–04:25 高频出现（后台线程），属服务端可达性问题，与本次 ANR 无因果关系。
- 宿主 `EntryDetailActivity` 的「IME 预热」探针会先 show 再 hide 键盘（04:24:46.330 show → 04:24:46.649 hide），并伴随 `RemoteInputConnectionImpl: getTextBeforeCursor on inactive InputConnection` 告警，属预期副作用、不影响功能。
- `WXMIRROR: bind getTransactionItems/getActivityToken/USE_CACHE/getLaunchingActivity/performNewIntents fail: InvocationTargetException` 为微信镜像反射降级日志，与本次无关。
