package com.carlos.vault;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** 两次自动存档的最小间隔，避免频繁写约 9MB 的 zip */
    private static final long MIN_INTERVAL_MS = 45_000L;

    private static volatile SessionSaver instance = null;

    private final Application app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 上次真正"排队"存档的时间戳（CAS 用，保证并发触发只放行一次） */
    private final AtomicLong lastQueuedAt = new AtomicLong(0L);
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
     * 节流排队：距上次排队不足 {@link #MIN_INTERVAL_MS} 直接返回；并用 CAS 抢占"这次由我触发"，
     * 避免并发回调各排一个任务。
     */
    private void schedule() {
        long now = System.currentTimeMillis();
        long last = lastQueuedAt.get();
        if (now - last < MIN_INTERVAL_MS) return;
        if (!lastQueuedAt.compareAndSet(last, now)) return;
        try {
            executor.execute(this::saveOnce);
        } catch (Throwable ignored) {
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
            eng.autoSaveEntrySnapshot(owner);
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
