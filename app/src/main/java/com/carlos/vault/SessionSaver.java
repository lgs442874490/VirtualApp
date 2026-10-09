package com.carlos.vault;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话自动存档器（需求项 R3：尽最大努力补一次快照）。
 *
 * ⚠️ 先读这段再读代码 —— 不要把本类当成"登录态持久化"的主手段：
 *
 * Android **不保证**任何生命周期回调会在进程被杀前执行。用户在任务栏划掉、{@code adb shell
 * am force-stop}、系统 LMK 回收，都可能直接杀掉进程，onPause / onStop / onDestroy 一个都不回调。
 * 因此 R3 在"强杀"这一最要命的场景下**一次都跑不到**。
 *
 * 真正的兜底是 R1（冷启动不再无条件 wipe live）+ R2（按真实归属自动存档）：只要 live 目录还在，
 * 游戏自己写进去的会话就还在，完全不依赖任何回调。本类只是"有机会时多存一份备份"，覆盖的是
 * "用户正常退到后台 / 回到号库"这类能拿到回调的情形。
 *
 * 另：本类**不杀游戏进程**（{@link VaultEngine#captureEntrySnapshot} 会 {@code killGameAndWait}，
 * 在生命周期里调用会把用户正在玩的游戏直接关掉）。代价是快照可能与游戏正在写文件有轻微竞态，
 * 因此这里只在**容器游戏进程已经不存活**时才真正写，保证快照自洽；否则只是跳过，等下一次机会。
 */
public final class SessionSaver implements Application.ActivityLifecycleCallbacks {

    /**
     * 两次自动存档的最小间隔，避免频繁写约 9MB 的 zip。
     *
     * <p><b>只在"不急"的时候生效（#72 F3）</b>：一旦 live 里已经存在有效会话（用户刚登录成功），
     * {@link #schedule()} 会跳过这个间隔立刻尝试写 —— 否则用户还没等到 45s 就被闪退/强杀，
     * 下次进去又要验证码。真正的开销由后面两道门兜住：游戏活着会跳过、指纹没变会跳过，
     * 所以"跳节流"并不会变成"每次都写 9MB"。
     */
    private static final long MIN_INTERVAL_MS = 45_000L;

    private static volatile SessionSaver instance = null;

    private final Application app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 上次真正"排队"存档的时间戳（CAS 用，保证并发触发只放行一次） */
    private final AtomicLong lastQueuedAt = new AtomicLong(0L);
    /**
     * 【#72 F3】"急需保存"标记：由 {@link #requestSaveNow} 置位，下一次回调跳过节流直接尝试写，
     * 真正写成功后自动清除（写之前若游戏还活着会被跳过，标记保留到下次机会 —— 这正是
     * "注入后先记待存、游戏一结束立刻落盘"想要的行为）。
     */
    private final AtomicBoolean immediate = new AtomicBoolean(false);
    private volatile VaultEngine engine = null;
    private volatile AccountStore store = null;

    private SessionSaver(Application app) {
        this.app = app;
    }

    /**
     * 向 Application 注册全局生命周期监听。可重复安全调用（内部一次性保护）：
     * 重复注册会让同一事件被回调多次，从而反复触发存档。
     *
     * 之所以从 Activity 侧调用、而不是改 manifest 的 android:name：工程里的 Application 类
     * （com.carlos.common.App）来自预编译依赖、源码不在本仓库，不能就地注册回调。
     */
    public static synchronized void install(Application app) {
        if (app == null) return;
        if (instance != null) return;
        instance = new SessionSaver(app);
        app.registerActivityLifecycleCallbacks(instance);
    }

    /**
     * 对外触发入口：例如在 Service.onTaskRemoved() 里请求一次存档。
     * 未安装（instance == null）时静默返回，不抛异常。
     */
    public static void requestSave(Context ctx) {
        SessionSaver s = instance;
        if (s == null) return;
        s.schedule();
    }

    /**
     * 【#72 F3】请求**立刻**保存：跳过 {@link #MIN_INTERVAL_MS} 节流。
     * 典型调用点：{@code VaultEngine.launchEntry} 注入 token 之后 —— 用户马上要开始玩，
     * 不能让他们先熬过 45s 才有快照；否则一闪退就又要验证码。
     *
     * <p>本方法的语义是"标记待存 + 立刻尝试一次"：此刻游戏还活着的话 {@link #saveOnce} 会跳过，
     * 标记保留，等游戏退出后的下一个生命周期回调再落盘。未安装时静默返回，不抛异常。
     */
    public static void requestSaveNow(Context ctx) {
        SessionSaver s = instance;
        if (s == null) return;
        s.immediate.set(true);
        s.schedule();
    }

    private VaultEngine engine() {
        VaultEngine e = engine;
        if (e == null) {
            e = new VaultEngine(app);
            engine = e;
        }
        return e;
    }

    private AccountStore store() {
        AccountStore s = store;
        if (s == null) {
            s = new AccountStore(app);
            store = s;
        }
        return s;
    }

    /**
     * 节流排队：距上次排队不足 {@link #MIN_INTERVAL_MS} 时**默认直接返回**，并用 CAS 抢占
     * "这次由我触发"，避免并发回调各排一个任务。
     *
     * <p>节流期内仍会放行的两种情形见方法内的注释（急需保存标记 / live 已有有效会话）。
     */
    private void schedule() {
        long now = System.currentTimeMillis();
        long last = lastQueuedAt.get();
        if (now - last < MIN_INTERVAL_MS) {
            // 【#72 F3】节流期内也放行两种情形：① 明确标记了"急需保存"；② live 里已有有效会话
            // （用户刚登录成功）。否则第一次登录后的 45 秒内被杀 ⇒ 又要验证码。
            if (!immediate.get() && !liveHasValidSession()) return;
        }
        if (!lastQueuedAt.compareAndSet(last, now)) return;
        try {
            executor.execute(this::saveOnce);
        } catch (Throwable ignored) {
        }
    }

    /**
     * live 里是否存在"属于当前归属入口的有效会话" —— 判定口径与 {@link SessionProbe} 一致：
     * {@code session_key} 非空（{@code readLiveSessionFpid} 在登出态返回空串）+ fpid 与入口记录的号匹配。
     * 入口没配 fpid（原生登录型）时只要求会话存在，与读侧的宽松口径对齐。
     * 任何异常一律当作"没有"，回到普通节流。
     */
    private boolean liveHasValidSession() {
        try {
            String owner = store().getLiveEntryId();
            if (owner == null || owner.isEmpty()) return false;
            String live = engine().readLiveSessionFpid();
            if (live.isEmpty()) return false;
            AccountStore.Account oa = store().byId(owner);
            String expect = (oa == null || oa.fpid == null) ? "" : oa.fpid;
            return expect.isEmpty() || expect.equals(live);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 后台线程执行：仅在"live 有归属 + 游戏已死 + 内容有变化"时才写快照。 */
    private void saveOnce() {
        try {
            String owner = store().getLiveEntryId();
            if (owner == null || owner.isEmpty()) return;
            VaultEngine eng = engine();
            if (!eng.hasLiveContent()) return;
            // 游戏还活着 ⇒ 它的 SP/db 可能正在 rename/写，快照会不自洽。跳过，等下次机会。
            if (eng.isContainerGameAlive()) return;
            // ⚠️ 这里**不要**另外加"会话↔入口归属同步"：autoSaveEntrySnapshot() 内部第一件事
            // 就是 syncSessionOwner(owner)（且在"指纹没变 ⇒ 提前 return"之前），本路径天然覆盖。
            // 在此重复同步不但多余，还会多读一次 prefs；绕过本方法直接调 saveEntrySnapshot()
            // 才会丢掉同步（那条路是"提取会话"的显式操作，不是自动存档）。
            eng.autoSaveEntrySnapshot(owner);
            immediate.set(false);   // 真的写成了（或判定无变化）才清标记，其余情况留给下次机会
        } catch (Throwable ignored) {
        }
    }

    // ---------- Application.ActivityLifecycleCallbacks ----------

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // 回到号库界面：常发生在"玩完游戏把它划掉、再回到号库"之后 —— 此刻游戏已死、数据自洽，
        // 是最有意义的存档时机。
        schedule();
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // 【#72 F3】退到前台 Activity 之外的第一个信号，也争取一次（原来这里是空的）。
        schedule();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        // 退到后台：也补一次（若届时游戏已死）。
        schedule();
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
