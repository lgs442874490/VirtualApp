package com.carlos.vault;

import android.content.Context;
import android.content.SharedPreferences;

import com.carlos.vault.license.LicenseManager;

import java.util.List;

/**
 * 批次7（task-25）：新用户初始化四步引导的状态机 + 首启跳转判定。
 *
 * <p>五步（与 design/全流程行为与分级门控-20261023.md §5.2 一致）：
 * <ol>
 *   <li>{@link #STEP_LICENSE} 绑定兑换码（授权激活，复用 LicenseUi/LicenseManager）；</li>
 *   <li>{@link #STEP_CLONE} 克隆/初始化容器（复用 AccountVaultActivity 初始化向导：克隆→首启→重置→存基线）；</li>
 *   <li>{@link #STEP_ACCOUNT} 添加分身账户（复用号库导入/克隆，可跳过）；</li>
 *   <li>{@link #STEP_LAUNCH} 打开游戏分身（复用 VaultEngine/EntryDetail）；</li>
 *   <li>{@link #STEP_AUTOMATION} 打开自动化任务（T2 会员权限；T1 用户止于第④步，本步显示升级说明、不强制阻塞）。</li>
 * </ol>
 *
 * <p>持久化（SharedPreferences {@value #PREF_NAME}）：
 * <ul>
 *   <li>{@code onboarding_done}：整页完成（用户点「进入主页」或自动完成时置 true）；</li>
 *   <li>{@code onboarding_steps}：四位状态码字符串，位 0..3 对应步骤 1..4，字符
 *       {@code 0}=未开始/{@code 1}=已完成/{@code 2}=已跳过；</li>
 *   <li>{@code onboarding_version}：引导版本（预留；当前不因升级重置，避免打扰存量用户）。</li>
 * </ul>
 *
 * <p>纯函数（编解码/推进/自动检测/完成判定/跳转判定）均为 static，JVM 单测不依赖 Android 运行时；
 * 只有实例方法读写 SharedPreferences 与进程内跳转标记。
 */
public final class OnboardingState {

    /** 步骤号（1 起；与 UI 卡片编号一致）。 */
    public static final int STEP_LICENSE = 1;
    /** ② 克隆/初始化容器（初始化向导四步：克隆→首启→重置→存基线）。 */
    public static final int STEP_CLONE = 2;
    /** ③ 添加分身账户（可跳过）。 */
    public static final int STEP_ACCOUNT = 3;
    /** ④ 打开游戏分身。 */
    public static final int STEP_LAUNCH = 4;
    /** ⑤ 打开自动化（T2+；T1 显示升级说明、不计入完成要求）。 */
    public static final int STEP_AUTOMATION = 5;
    /** 步骤总数。 */
    public static final int STEP_COUNT = 5;

    /** 单步状态。 */
    public static final int PENDING = 0;
    public static final int DONE = 1;
    public static final int SKIPPED = 2;

    /** 引导文案版本（预留；将来步骤变化可用于强制重看，当前仅记录）。 */
    public static final int CURRENT_VERSION = 1;

    static final String PREF_NAME = "onboarding";
    static final String KEY_DONE = "onboarding_done";
    static final String KEY_STEPS = "onboarding_steps";
    static final String KEY_VERSION = "onboarding_version";
    /** 用户点过「稍后再说」：后续冷启动不再自动弹引导（☰ 功能菜单入口保留）。批次8（P0-3）。 */
    static final String KEY_DEFERRED = "onboarding_deferred";

    /**
     * 本进程是否已自动拉起过引导：防止 AccountVaultActivity/HomeActivity 在授权校验
     * {@code recreate()}、屏幕旋转、冷启动多次 onCreate 时反复弹引导页。
     */
    private static volatile boolean sRedirectedThisProcess = false;

    private final SharedPreferences sp;

    public OnboardingState(Context ctx) {
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ==================== 实例读写 ====================

    /** 整页引导是否已完成（用户明确完成/自动完成）。 */
    public boolean isDone() {
        return sp.getBoolean(KEY_DONE, false);
    }

    /** 记录整页完成标记（不清各步状态，便于「我的→新手引导」再次查看）。 */
    public void setDone(boolean done) {
        SharedPreferences.Editor ed = sp.edit().putBoolean(KEY_DONE, done)
                .putInt(KEY_VERSION, CURRENT_VERSION);
        if (done) ed.putBoolean(KEY_DEFERRED, false);   // 完成引导后清「稍后再提」标记
        ed.apply();
    }

    /** 用户是否点过「稍后再说」（记录后不再自动弹，入口保留）。批次8（P0-3）。 */
    public boolean isDeferred() {
        return sp.getBoolean(KEY_DEFERRED, false);
    }

    /** 记录/清除「稍后再说」。完成引导或重置时自动清除。批次8（P0-3）。 */
    public void setDeferred(boolean deferred) {
        sp.edit().putBoolean(KEY_DEFERRED, deferred).apply();
    }

    /** 四步状态（永远返回长度 4 的数组，下标 = 步骤号-1）。 */
    public int[] steps() {
        return decode(sp.getString(KEY_STEPS, ""));
    }

    public void setSteps(int[] steps) {
        sp.edit().putString(KEY_STEPS, encode(steps)).apply();
    }

    public void markStep(int step, int status) {
        setSteps(mark(steps(), step, status));
    }

    public void markDone(int step) {
        markStep(step, DONE);
    }

    public void markSkipped(int step) {
        markStep(step, SKIPPED);
    }

    public boolean allSettled() {
        return allSettled(steps());
    }

    public int settledCount() {
        return settledCount(steps());
    }

    public int firstPendingStep() {
        return firstPendingStep(steps());
    }

    /** 重置（调试/客服排查用；正常入口不暴露给用户）。 */
    public void reset() {
        sp.edit().putBoolean(KEY_DONE, false)
                .putString(KEY_STEPS, "")
                .putInt(KEY_VERSION, 0)
                .putBoolean(KEY_DEFERRED, false)
                .apply();
        sRedirectedThisProcess = false;
    }

    // ==================== 首启跳转 ====================

    /**
     * 是否应该自动拉起引导页（AccountVaultActivity.onCreate 调用）。
     *
     * <p>必须同时满足：未完成引导 + 授权已可用（未激活时先走授权门禁，那本身是第①步）+ 本进程未拉过
     * + **不是老用户** + 未点过「稍后再说」。
     *
     * <p>批次8（P0-3）修复“老用户被引导反复打扰”：
     * <ul>
     *   <li><b>老用户</b>：AccountStore 已有账号，或持久化的步骤状态已完成 ①授权+②克隆+③账号
     *       （{@code autoDetect} 上轮已判定过 ≥3 步）→ 视为已完成初始化，冷启动直接进主页；</li>
     *   <li><b>稍后再说</b>：用户点过「稍后再说」写入 {@code onboarding_deferred} 后，
     *       后续冷启动不再自动弹（☰ 功能菜单「新手引导」入口保留，可随时补做）。</li>
     * </ul>
     */
    public static boolean shouldRedirect(Context ctx) {
        if (ctx == null) return false;
        if (sRedirectedThisProcess) return false;
        boolean licensed;
        try {
            LicenseManager lm = LicenseManager.get(ctx);
            lm.ensureLoaded();
            licensed = lm.localUsable();
        } catch (Throwable t) {
            licensed = false;
        }
        boolean done;
        boolean deferred = false;
        boolean existingUser = false;
        try {
            OnboardingState st = new OnboardingState(ctx);
            done = st.isDone();
            deferred = st.isDeferred();
            existingUser = isLikelyExistingUser(st.steps(), hasAccounts(ctx));
        } catch (Throwable t) {
            done = true;   // 读不到状态时宁可不打扰
        }
        boolean redirect = shouldRedirect(done, licensed, sRedirectedThisProcess,
                existingUser, deferred);
        if (redirect) sRedirectedThisProcess = true;
        return redirect;
    }

    /** 纯判定（单测用）：已完成 / 未授权 / 本进程已拉过 → 不跳。 */
    public static boolean shouldRedirect(boolean onboardingDone, boolean licenseUsable,
                                         boolean alreadyRedirected) {
        return shouldRedirect(onboardingDone, licenseUsable, alreadyRedirected, false, false);
    }

    /**
     * 纯判定（批次8，单测用）：在原有三条之上，老用户（已有账号或已自动检测完成≥3步）与
     * 「稍后再说」标记都不再自动弹。
     */
    public static boolean shouldRedirect(boolean onboardingDone, boolean licenseUsable,
                                         boolean alreadyRedirected, boolean existingUser,
                                         boolean deferred) {
        if (existingUser || deferred) return false;
        return !onboardingDone && licenseUsable && !alreadyRedirected;
    }

    /**
     * 老用户判定（纯函数，批次8 P0-3）：
     * <ul>
     *   <li>号库已有账号 → 老用户（用户已完成“添加分身”这一步）；</li>
     *   <li>或步骤状态里 ①授权 ②克隆 ③账号 都已完成（OnboardingActivity 的 autoDetect 已判定 ≥3 步）。</li>
     * </ul>
     */
    public static boolean isLikelyExistingUser(int[] steps, boolean hasAccount) {
        if (hasAccount) return true;
        return statusOf(steps, STEP_LICENSE) == DONE
                && statusOf(steps, STEP_CLONE) == DONE
                && statusOf(steps, STEP_ACCOUNT) == DONE;
    }

    /** 号库是否已有账号（读不到按无账号处理：新用户仍可被引导）。 */
    private static boolean hasAccounts(Context ctx) {
        try {
            List<AccountStore.Account> accounts = new AccountStore(ctx).all();
            return accounts != null && !accounts.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 调试用：允许再次自动拉起（不持久化）。 */
    static void resetProcessRedirectFlag() {
        sRedirectedThisProcess = false;
    }

    // ==================== 纯函数：编解码 / 推进 / 判定 ====================

    /** "10201"（或任意长度）→ 长度 5 的状态数组；未知字符按 PENDING。 */
    public static int[] decode(String raw) {
        int[] steps = new int[STEP_COUNT];
        if (raw == null) return steps;
        for (int i = 0; i < STEP_COUNT && i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '1') {
                steps[i] = DONE;
            } else if (c == '2') {
                steps[i] = SKIPPED;
            } else {
                steps[i] = PENDING;
            }
        }
        return steps;
    }

    /** 状态数组 → 定长五位状态码（越界/非法值按 PENDING 写 '0'）。 */
    public static String encode(int[] steps) {
        StringBuilder sb = new StringBuilder(STEP_COUNT);
        for (int i = 0; i < STEP_COUNT; i++) {
            int v = steps != null && i < steps.length ? steps[i] : PENDING;
            sb.append(v == DONE ? '1' : v == SKIPPED ? '2' : '0');
        }
        return sb.toString();
    }

    /** 推进一步（步骤号 1..5；非法步骤号/状态原样返回副本，不抛异常）。 */
    public static int[] mark(int[] steps, int step, int status) {
        int[] out = copyOf(steps);
        if (step < 1 || step > STEP_COUNT) return out;
        if (status != DONE && status != SKIPPED && status != PENDING) return out;
        out[step - 1] = status;
        return out;
    }

    /**
     * 自动勾选（不覆盖用户已完成的步骤；已跳过但条件满足会升级为完成）：
     * 授权可用→①完成；容器已克隆且有基线→②完成；已有账号→③完成；游戏分身存活→④完成。
     * ⑤自动化无可靠本地检测（由用户引导确认/跳过）。
     */
    public static int[] autoDetect(int[] steps, boolean licenseUsable, boolean containerInitialized,
                                   boolean hasAccount, boolean gameAlive) {
        int[] out = copyOf(steps);
        if (licenseUsable) out = upgrade(out, STEP_LICENSE);
        if (containerInitialized) out = upgrade(out, STEP_CLONE);
        if (hasAccount) out = upgrade(out, STEP_ACCOUNT);
        if (gameAlive) out = upgrade(out, STEP_LAUNCH);
        return out;
    }

    private static int[] upgrade(int[] steps, int step) {
        int[] out = copyOf(steps);
        if (step < 1 || step > STEP_COUNT) return out;
        if (out[step - 1] != DONE) out[step - 1] = DONE;
        return out;
    }

    public static int statusOf(int[] steps, int step) {
        if (steps == null || step < 1 || step > STEP_COUNT) return PENDING;
        return steps[step - 1];
    }

    /** 该步是否已有结论（完成或跳过）。 */
    public static boolean settled(int[] steps, int step) {
        return statusOf(steps, step) != PENDING;
    }

    /** 全部五步是否都有结论（完成或跳过）→ 可以「进入主页」。 */
    public static boolean allSettled(int[] steps) {
        return allSettled(steps, STEP_COUNT);
    }

    /**
     * 前 {@code requiredCount} 步是否都有结论。
     * T1 用户引导止于第④步（{@link #requiredStepCount(int)}=4）；T2+ 需完成第⑤步（=5）。
     */
    public static boolean allSettled(int[] steps, int requiredCount) {
        int need = normalizeRequiredCount(requiredCount);
        for (int step = 1; step <= need; step++) {
            if (!settled(steps, step)) return false;
        }
        return true;
    }

    /** 授权等级 → 完成本引导所需的步骤数（T2+ 含第⑤步自动化；其它按 T1 止于第④步）。 */
    public static int requiredStepCount(int tier) {
        return tier >= 2 ? STEP_COUNT : STEP_LAUNCH;
    }

    private static int normalizeRequiredCount(int requiredCount) {
        if (requiredCount <= 0) return STEP_COUNT;
        return Math.min(requiredCount, STEP_COUNT);
    }

    /** 有结论的步数（完成 + 跳过），用于 X/4 进度。 */
    public static int settledCount(int[] steps) {
        int n = 0;
        for (int step = 1; step <= STEP_COUNT; step++) {
            if (settled(steps, step)) n++;
        }
        return n;
    }

    /** 已完成步数（不含跳过）。 */
    public static int doneCount(int[] steps) {
        int n = 0;
        for (int step = 1; step <= STEP_COUNT; step++) {
            if (statusOf(steps, step) == DONE) n++;
        }
        return n;
    }

    /** 第一个未结论的步骤号（1..5）；全部有结论返回 0。 */
    public static int firstPendingStep(int[] steps) {
        for (int step = 1; step <= STEP_COUNT; step++) {
            if (!settled(steps, step)) return step;
        }
        return 0;
    }

    /** 状态文案（卡片角标/读屏）。 */
    public static String statusLabel(int status) {
        switch (status) {
            case DONE:
                return "已完成";
            case SKIPPED:
                return "已跳过";
            case PENDING:
            default:
                return "未开始";
        }
    }

    /** 角标类型（UiStyle.badge 的 type：ok/run/idle）。 */
    public static String statusBadgeType(int status) {
        switch (status) {
            case DONE:
                return "ok";
            case SKIPPED:
                return "idle";
            case PENDING:
            default:
                return "run";
        }
    }

    /** 数组合法长度归一（内部拷贝，永不返回 null）。 */
    private static int[] copyOf(int[] steps) {
        int[] out = new int[STEP_COUNT];
        if (steps != null) {
            System.arraycopy(steps, 0, out, 0, Math.min(steps.length, STEP_COUNT));
        }
        return out;
    }
}
