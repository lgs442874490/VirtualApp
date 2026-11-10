# 无尽号库 · UI 规范（v2 设计系统）

> 定版：2026-11-05 ｜ 适用：`app/src/main/java/com/carlos/vault/**` 下全部用户可见界面
> 唯一事实源：`UiStyle.java`（颜色 / 组件 / 圆角）· `UiComponents.java`（字号阶梯 / 页面骨架）· `UiLayoutRules.java`（边距 / 限宽）
> 核查清单：`qa/UI规范一致性核查清单-20261105.md`（第一～七节为整改前快照，第八节起为整改后结论）

---

## 1. 规范基线 S1–S11

| 编号 | 规范项 | 要求 | 权威定义 |
|---|---|---|---|
| S1 | 取色唯一性 | **任何页面/组件不得另起色值**，改色只发生在 `UiStyle`；例外见第 3 节 | `UiStyle.java` 类注释、颜色常量区 |
| S2 | 字号阶梯 | 只用 `UiComponents.TEXT_*`：`DISPLAY 30 / TAB_TITLE 24 / PAGE_TITLE 19 / HEADLINE 16 / BODY 14 / LABEL 13 / CAPTION 11.5 / MICRO 10.5`。**下限 10.5，不得低于** | `UiComponents.java:26-34` |
| S3 | 卡片分层 | 白底 `CARD` + 1px `LINE` 描边 + `RADIUS_CARD 16dp`，**不靠重阴影 / elevation 分层** | `UiStyle.card()` |
| S4 | 圆角体系 | 只用 `RADIUS_CARD 16 / RADIUS_BTN 12 / RADIUS_DIALOG 20 / RADIUS_ROW 10 / RADIUS_PILL 999` | `UiStyle.java` 常量区 |
| S5 | 边距与限宽 | 左右 18dp；宽屏内容 ≤ 640dp 居中，走 `UiLayoutRules.contentWidthPx()` | `UiLayoutRules.java` |
| S6 | 触感反馈 | 所有可点元素挂 `UiStyle.press()`（120ms / 0.985），实心按钮叠 `pressRound()` | `UiStyle.java` |
| S7 | 状态编码 | 色 + 圆点 + 文字三重编码；只用 `OK / RUN / WAIT / WARN / ERR / IDLE` 六组 | `UiStyle.badge()` |
| S8 | 轻提示 | 统一 `UiStyle.toast()`（墨底反色），不直接 new 系统 Toast | `UiStyle.java` |
| S9 | 详情页顶栏 | 一律 `UiComponents.topBar()` / `scrollPage()`：`TEXT_PAGE_TITLE 19` + 44dp 返回热区 + 18dp 边距。主 Tab 大标题用 `TEXT_TAB_TITLE 24` | `UiComponents.java` |
| S10 | 弹窗 | `AlertDialog.Builder` 一律带 `AlertDialog.THEME_DEVICE_DEFAULT_LIGHT`，或走 `UiStyle.showConfirmDialog/showInputDialog`；阻塞式加载只经 `UiStyle.progressDialog()` 构造 | `UiStyle.java` 弹窗段 |
| S11 | 资源一致性 | `res/values/{colors,styles,dimens}.xml` 的 token 与 `UiStyle` / `UiLayoutRules` 一致；例外见第 4 节 | `res/values/` |

### 1.1 字号基线裁决（2026-11-05）

**以 `UiComponents.TEXT_*` 为唯一代码基线。** Ardot「账号页 · App 多账号分组版」标注稿里的 9 / 10 / 11 / 12 / 14 / 20 是视觉标注的近似值，不是可写入代码的阶梯；落地时说明类文字已统一提到 `TEXT_MICRO 10.5`。新增页面一律先查本表，不得再从标注稿直接抄数字。主 Tab 的 24sp 属有意层级区分，已补 `TEXT_TAB_TITLE` 纳入阶梯，不强行压到 19。

### 1.2 阻塞式加载弹窗的收敛策略

`ProgressDialog` 自 API 26 起废弃，但项目仍有 21 处阻塞式等待，直接改成「行内处理中」会改动异步时序、需逐页真机验证。当前策略：**21 处全部收敛到 `UiStyle.progressDialog(ctx)` 工厂**，废弃 API 只出现在这一个位置；后续替换为统一 loading 组件时只改这一处。工厂刻意不额外设置主题 / `cancelable` / `indeterminate`，保持与各调用点原有行为一致。

---

## 2. 口径：登录方式与 UID（2026-11-05 用户确认）

| 项 | 口径 | 落地 |
|---|---|---|
| 不显示登录方式（`token` / `原生` / UID 徽标） | **仅账号页**（`HomeActivity` 账号 Tab） | 账号页已移除 `loginBadge()` / `uidTail()`；号库九宫格、账号入口详情页的就绪态文案**保留** |
| 不显示 `UID` 术语 | **全站** | 所有面向用户的 `UID` / `uid` 标签统一改为 **`fid`**（游戏内角色 ID 的产品术语）；取值不变 |

**`fid` 替换边界（务必遵守）**：只改字符串字面量与描述界面文案的注释。以下一律不动——

- 协议 / JSON 字段：`"uid"`、`"target_uid"`、`"ally_uid"`、`"activeUid"`、`"friendly_uids"`、`EXTRA_UID`、`formation_uid`
- 数据键：`getLongExtra("uid", …)`、`STATE_KEY_PREFIX + fpid:sid:uid`、`ACTIVE_KEY = "active2:"` 及其值格式（`RoleStore` 注释里的 `"sid:fid"` 只是术语同步，**存储内容与解析逻辑未变**）
- Java 标识符：`uid`、`statusUid`、`targetUid`、`ownerUid`、`COLOR_*`、`UUID`
- Android 平台概念：`Process.FIRST_APPLICATION_UID`、`/proc/<pid>/status` 的 `"Uid:"`、设备 `device_uuid` / `UUID.randomUUID()`
- 账号标识 `fpid` 与 `fid` 是**不同层级**：`fpid` 是账号 ID，`fid` 是角色 ID。不要把 `fpid` 显示成 `fid`，也不要反过来。

---

## 3. 例外适用范围 A：编队子系统（1:1 像素复刻）

**适用模块**：`daily/formation/ui/*`、`daily/formation/FormationIcons`、`daily/FormationManagerActivity`

**依据**：`design/ui-ref-编队-20261025/README.md` —— 线上网页版编队界面（Vue3 + Element Plus）**Android 1:1 复刻用**，逐像素给出 463dp 定宽弹窗、`#4f46e5` 主色、`#cfd5e0` 描边、`#f3f5f9` 页底、13/15/16px 字号、8/10/12/16 圆角、`#64748b` 职业角标。

**豁免项**：色板、`DESIGN_WIDTH_DP=463`、`designWidth()` 的 `marginDp=12`、圆角档位、字号档位、职业色。**不受 S1 / S2 / S4 / S5 全局 token 约束**，禁止"统一"到 `UiStyle.BRAND`、18dp 或 `TEXT_*`——会破坏复刻一致性。

**例外内仍须遵守**：

1. 颜色一律登记为 `ManagerUi` 具名常量，不得散落字面量（已落地：21 处色值全部收敛）；
2. 可点元素挂 `UiStyle.press()`；
3. 轻提示用 `UiStyle.toast()`；
4. `AlertDialog.Builder` 带统一主题；
5. 不使用 `ProgressDialog`。

> 这条例外是 2026-11-05 复核后改判的：此前把自成色板误报为「不符合」。后续核查不要重复报偏差。

---

## 4. 例外适用范围 B：VA 引擎遗留界面

`com.carlos.home.*` / `com.carlos.splash.*` 共 6 个 Activity + `res/layout/` 下的 XML，使用 `@style/UITheme` 与旧式 XML，属 VirtualApp 引擎自带，改动风险高，**不纳入 v2 UI 规范适用范围**。

`res/values/colors.xml` 中 `vault_*_solid` 四项是这些遗留 XML 里 `LabelView` 的**角标底色**，与 `UiStyle.*_SOLID`（状态圆点 / 底块，配白字）**语义不同**：

| token | 消费者 | 文字色 | 说明 |
|---|---|---|---|
| `vault_ok_solid` | `item_launcher_app.xml:71` | 白 | 进程数角标 |
| `vault_warn_solid` | `item_clone_app.xml:67` | **黑** | 必须亮底；换成深棕 `WARN_SOLID #B7791F` 会让黑字对比度不达标 |
| `vault_err_solid` | 无引用 | — | — |
| `vault_idle_solid` | 唯一引用已注释 | — | — |

⇒ 不按 S1 强行对齐；如要改，必须先核对对应 XML 的 `lv_text_color`。

**`dialog_tips.xml` 不是缺失文件**：由 `app/sdk/lib-release.aar` 提供 `res/layout/dialog_tips.xml`，`ListAppFragment:303` 的 `R.layout.dialog_tips` 在编译期正常解析（编译通过 + `R.jar` 含该符号）。不要删除引用或新建同名文件覆盖 AAR 资源。

---

## 5. 新增页面检查清单

1. 取色只从 `UiStyle.*` / 例外内 `ManagerUi.*`；
2. 字号只从 `UiComponents.TEXT_*`（例外内按对标稿）；
3. 圆角只从 `UiStyle.RADIUS_*`；
4. 容器走 `UiLayoutRules.contentWidthPx()`；
5. 详情页顶栏用 `UiComponents.topBar()`，主 Tab 用 `TEXT_TAB_TITLE`；
6. 所有可点元素 `UiStyle.press()`；
7. 弹窗带 `THEME_DEVICE_DEFAULT_LIGHT`，阻塞加载走 `UiStyle.progressDialog()`；
8. 面向用户的标识写 `fid`，不写 `UID`；
9. 账号页不显示登录方式；其他页面不受此约束；
10. 不使用 `new Button` / `new ListView` / `R.layout` 构建 v2 界面。

---

## 6. 已知待办（需真机或交互时序验证，暂缓）

| 项 | 原因 |
|---|---|
| 21 处阻塞加载 → 行内处理中态 | 改动异步时序，需逐页真机验证；当前已收敛到单一工厂 |
| 4 类自建顶栏整体替换为 `topBar()` | 需逐页重做返回逻辑与布局测量；本轮只做了标题字号 token 化 |
| `AccountVaultActivity` `PopupWindow` → 统一弹层 | 涉及长按菜单定位 |
| `daily/FunctionActivity` 原生 `RadioGroup/CheckBox/NumberPicker` → UiStyle 控件 | 涉及表单取值逻辑 |
| `UiStyle` 历史圆角档位 14 / 11 | 需对应页面真机比对后再收敛到 `RADIUS_*` |
| 签到格 10.5sp 排版 | `FunctionActivity` 4 列等宽，需 V2141A 确认不撑破 |
| `item_launcher_app.xml` / `item_clone_app.xml` 两个角标配色 | 资源色改动需真机确认 |

---

## 7. 分支基线提示

`qa/UI规范一致性核查清单-20261105.md` 第八节记录了 2026-11-05 的实测：`modify/account-page-ui-20261105-0230` 分支 **0 提交**，HEAD 是 master 的严格祖先、落后 119 提交，账号页改造与本规范整改**全部为工作区未提交状态**。合并 master 前须按清单的 before→after 表语义重放，不可整包 apply patch。
