package com.carlos.vault.daily;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.carlos.vault.AccountStore;
import com.carlos.vault.wjdr.WjdrEngine;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 日常自动执行引擎：配置一次，每天在时间窗内自动逐账号执行。
 * - 前台服务（常驻+通知），Handler 每 10 分钟巡检到期账号
 * - AlarmManager 谁唤醒兜底（窗口期内每 30 分钟一次闹钟防打盹）
 * - 执行时持有 Partial WakeLock，防止跑一半被休眠
 * - 结果写入各账号"上次结果"，完成发通知汇总
 * - 任务级 wake：补给"下一次可领/限时截止"到点仅补领 supply 单功能（不重跑整轮日常）
 * - 启动时清理过期 st:<date>: 当日状态键（防按日分键无限堆积）
 */
public class DailySchedulerService extends Service {

    private static final String CHANNEL_ID = "daily_scheduler";
    private static final int NOTIF_ID = 4210;
    private static final long CHECK_INTERVAL_MS = 10 * 60 * 1000L;   // 巡检 10 分钟
    private static final long ALARM_INTERVAL_MS = 30 * 60 * 1000L;  // 闹钟兜底 30 分钟
    private static final long CATCHUP_MS = 45 * 60 * 1000L;         // 部分角色被顶号后的补跑间隔

    private static DailySchedulerService instance;
    private Handler handler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private PowerManager.WakeLock wakeLock;
    private boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        startForeground(NOTIF_ID, buildNotification("日常自动执行已开启"));
        RoleSessionKeeper.get(this).start();   // 常驻在线会话扫描（心跳/重连/互助响应）
        try {
            int pruned = new LogStore(this).pruneStaleDayKeys();   // 顺带清理过期 st:<date>: 键
            if (pruned > 0) android.util.Log.i("DailySched", "pruned " + pruned + " stale day keys");
        } catch (Throwable ignored) {
        }
        scheduleWakeupAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        scheduleWakeupAlarm();   // onCreate 已排；被系统重启（START_STICKY）走不到 onCreate 的极端路径兜底重排
        requestTick(true);
        return START_STICKY;
    }

    /**
     * tick 链单飞标志（P1-2）。
     * 历史 bug：onStartCommand 每次被 start 都直接 tick()——而 ensureStarted 会被
     * 每条游戏通知反复调用（真机 lastStartId=5）⇒ 每次都新排一条 10 分钟 postDelayed
     * 链，N 条通知后 N 条链并存，重复 sweep/重复排闹钟/耗电。
     * 所有调用都在主线程（handler=主 looper），AtomicBoolean 去重即可，无需加锁。
     */
    private final java.util.concurrent.atomic.AtomicBoolean tickQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 请求巡检（幂等）：链上已有一个待执行 tick 时不再叠加新链。
     *  immediate=true 立即跑一轮（服务被 start/闹钟到点），false 走 10 分钟节奏。 */
    private void requestTick(boolean immediate) {
        if (!tickQueued.compareAndSet(false, true)) return;   // 已有在链上
        if (immediate) {
            handler.post(this::tick);
        } else {
            handler.postDelayed(this::tick, CHECK_INTERVAL_MS);
        }
    }

    private void tick() {
        // 先释放"在链"标记：本轮 sweep 期间若有通知 requestTick(true)，会排一个
        // 立即轮接棒，末尾的 10 分钟重排因标记已被占而自动让位——链始终只有一条
        tickQueued.set(false);
        if (!running) return;
        try {
            sweepDueAccounts();
        } catch (Throwable t) {
            android.util.Log.e("DailySched", "sweep fail", t);
        }
        if (running) {
            requestTick(false);
        }
    }

    /** 供通知监听等组件拿外部文件目录（事件日志用）。 */
    static java.io.File appFiles() {
        return instance != null ? instance.getExternalFilesDir(null) : null;
    }

    /**
     * 游戏原生通知事件入口（GameNotifyListenerService 调用）：
     * - 任意事件 → 立即唤醒巡检（事件驱动兜底：通知能拉活被冻结的调度）
     * - WAR/RECON → 逐个自动账号反应式开盾（幂等，已开过返回6151跳过）
     */
    public static void onGameEvent(final android.content.Context ctx, final String type, String detail) {
        GameNotifyListenerService.logEvent("dispatch " + type);
        try {
            ensureStarted(ctx);
        } catch (Throwable t) {
            GameNotifyListenerService.logEvent("service start fail: " + t);
        }
        DailySchedulerService svc = instance;
        if (svc != null) {
            svc.handler.post(svc::sweepDueAccounts);
        }
        if ("WAR".equals(type) || "RECON".equals(type)) {
            new Thread(() -> {
                try {
                    android.os.PowerManager.WakeLock wl = acquireWake(ctx, "game-event-shield", 10 * 60_000L);
                    try {
                        for (com.carlos.vault.AccountStore.Account a : new com.carlos.vault.AccountStore(ctx).all()) {
                            if (a == null || a.token == null || a.token.isEmpty()
                                    || a.fpid == null || a.fpid.isEmpty()) continue;
                            RoleStore.Schedule s2 = RoleStore.schedule(ctx, a.fpid);
                            if (!s2.autoEnabled) continue;
                            // 子开关门控：reactive=反应式开盾（关掉后战争/侦查通知不再自动开盾，
                            // 每日定时免费盾由 battle_shield.free 单独控制）
                            if (!FnParams.subOn(ctx, a.fpid, s2.serverid, "battle_shield", "reactive", true)) {
                                continue;
                            }
                            DailyResult r = DailyRunner.runSingle(ctx, a.fpid, a.token, s2.serverid, "battle_shield");
                            GameNotifyListenerService.logEvent("reactive shield " + a.label + ": " + r.message);
                        }
                    } finally {
                        if (wl != null) try { wl.release(); } catch (Throwable ignored) { }
                    }
                } catch (Throwable t) {
                    GameNotifyListenerService.logEvent("shield fail: " + t);
                }
            }, "reactive-shield").start();
        }
        // T3定向触发：通知文案含账号/角色名 → 只对那个号定向执行（部队返回=补采集、治疗完成=续批、建造研究完成=刷新状态）
        if ("MARCH_RETURN".equals(type) || "HEAL_DONE".equals(type) || "BUILD_DONE".equals(type)) {
            final String hint = detail == null ? "" : detail;
            new Thread(() -> targetedRun(ctx, type, hint), "game-event-target").start();
        }
    }

    /** T3定向：匹配账号后执行对应功能；匹配不到只记日志（不打扰全部账号）。 */
    private static void targetedRun(android.content.Context ctx, String type, String text) {
        try {
            com.carlos.vault.AccountStore store = new com.carlos.vault.AccountStore(ctx);
            com.carlos.vault.AccountStore.Account a = matchAccount(ctx, store, text);
            if (a == null) {
                GameNotifyListenerService.logEvent("target no-match: " + text);
                return;
            }
            if (a.token == null || a.token.isEmpty()) return;
            RoleStore.Schedule s2 = RoleStore.schedule(ctx, a.fpid);
            if (!s2.autoEnabled) {
                GameNotifyListenerService.logEvent("target " + a.label + " 自动日常未开，跳过");
                return;
            }
            android.os.PowerManager.WakeLock wl = acquireWake(ctx, "game-event-target", 10 * 60_000L);
            try {
                if ("MARCH_RETURN".equals(type)) {
                    DailyResult r = DailyRunner.runSingle(ctx, a.fpid, a.token, s2.serverid, "gather");
                    GameNotifyListenerService.logEvent("target gather " + a.label + ": " + r.message);
                } else if ("HEAL_DONE".equals(type)) {
                    DailyResult r = DailyRunner.runSingle(ctx, a.fpid, a.token, s2.serverid, "medic");
                    GameNotifyListenerService.logEvent("target medic " + a.label + ": " + r.message);
                } else {
                    String err = DailyRunner.syncOnly(ctx, a.fpid, a.token, s2.serverid);
                    GameNotifyListenerService.logEvent("target sync " + a.label + ": "
                            + (err == null ? "ok" : err));
                }
            } finally {
                if (wl != null) try { wl.release(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            GameNotifyListenerService.logEvent("target fail: " + t);
        }
    }

    /** 通知文案含账号名或该凭证下任一角色名 → 定位账号。 */
    private static com.carlos.vault.AccountStore.Account matchAccount(
            android.content.Context ctx, com.carlos.vault.AccountStore store, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        for (com.carlos.vault.AccountStore.Account a : store.all()) {
            if (a != null && a.label != null && !a.label.isEmpty() && text.contains(a.label)) return a;
        }
        try {
            for (com.carlos.vault.AccountStore.Account a : store.all()) {
                if (a == null || a.fpid == null) continue;
                for (RoleStore.Role r : RoleStore.roles(ctx, a.fpid)) {
                    if (r.name != null && !r.name.isEmpty() && text.contains(r.name)) return a;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static RoleStore.Role roleOf(long sid, String name) {
        RoleStore.Role r = new RoleStore.Role();
        r.serverid = sid;
        r.name = name;
        r.enabled = true;
        return r;
    }

    private static android.os.PowerManager.WakeLock acquireWake(android.content.Context ctx, String tag, long ms) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(POWER_SERVICE);
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, tag);
            wl.acquire(ms);
            return wl;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 巡检：到期（nextRunAt<=now）且开自动的账号逐个执行。 */
    private void sweepDueAccounts() {
        // 服务器模式：整轮/补给补领由 vault-server 的 RoundScheduler 执行，
        // 本机不再 fresh 登录跑任务（防互踢）；只保留状态拉取。
        if (com.carlos.vault.VaultSync.isServerMode(this)) {
            RoleSessionKeeper.get(this).syncOnline();
            return;
        }
        AccountStore store = new AccountStore(this);
        for (AccountStore.Account acc : store.all()) {
            if (acc == null || acc.token == null || acc.token.isEmpty()
                    || acc.fpid == null || acc.fpid.isEmpty()) continue;
            RoleStore.Schedule s = RoleStore.schedule(this, acc.fpid);
            if (!s.autoEnabled) continue;
            if (s.nextRunAt <= 0) {
                s.nextRunAt = nextRunInWindow(s.lastRunAt);
                RoleStore.saveSchedule(this, acc.fpid, s);
                scheduleAlarmAt(s.nextRunAt + 1500, 1002, acc.fpid);
                continue;
            }
            scheduleAlarmAt(s.nextRunAt + 1500, 1002, acc.fpid);   // 到点精确唤醒（Handler 可能被冻结）
            if (System.currentTimeMillis() >= s.nextRunAt) {
                runAccountNow(acc, s);
            }
            // 任务级 wake：补给"到点可领"早于整轮 nextRunAt 时单独补领（只跑 supply，不重跑整轮）
            long wake = RoleStore.wakeAt(this, acc.fpid);
            if (wake > 0) {
                if (System.currentTimeMillis() >= wake) {
                    RoleStore.saveWakeAt(this, acc.fpid, 0);   // 先清防重复触发，补领后再顺延
                    runSupplyTopup(acc, s);
                } else {
                    scheduleAlarmAt(wake + 1500, 1003, acc.fpid);
                }
            }
        }
        // 常驻在线同步：自动日常开启的账号，工作区角色各保持一条在线会话（24h 心跳+自动互助）
        RoleSessionKeeper.get(this).syncOnline();
    }

    /**
     * 任务级到点补领：只跑 supply 单功能（登录→补领→重抓补给状态）。
     * 与整轮 runAccountRoles 的差异：不开全部任务，避免重复出征/购买类动作；
     * 同样带防顶号（游戏客户端正挂该号时不协议登录）。
     */
    public void runSupplyTopup(final AccountStore.Account acc, final RoleStore.Schedule s) {
        DailyRunner.SOURCE = "定时";
        final Context ctx = this;
        worker.execute(() -> {
            acquireWake();
            try {
                String live = new com.carlos.vault.VaultEngine(ctx).readLiveSessionFpid();
                if (live != null && !live.isEmpty() && live.equals(acc.fpid)) return;   // 防顶号
                long sid = s.serverid > 0 ? s.serverid : RoleStore.activeRole(ctx, acc.fpid);
                DailyResult r = DailyRunner.runSingle(ctx, acc.fpid, acc.token, sid, "supply");
                android.util.Log.i("DailySched", "supply topup " + acc.label + ": " + r.message);
                if (r.category == DailyResult.Category.CLAIMED) {
                    notifyMulti(acc, "补给补领：" + r.message);
                }
            } catch (Throwable t) {
                android.util.Log.e("DailySched", "topup fail " + acc.fpid, t);
            } finally {
                DailyRunner.scheduleNextSupplyWake(ctx, acc.fpid);   // 依据补领后的状态顺延下一轮
                releaseWake();
            }
        });
    }

    /** 执行单账号日常（后台线程 + WakeLock）——遍历该账号全部工作区角色逐个执行。 */
    public void runAccountNow(final AccountStore.Account acc, final RoleStore.Schedule s) {
        DailyRunner.SOURCE = "定时";
        final Context ctx = this;
        worker.execute(() -> {
            acquireWake();
            updateNotification("正在执行：" + acc.label + " 的日常…");
            boolean ran = false;
            try {
                // skipDoneToday=true：补跑轮对今日已跑角色短路（runAccountRoles 保证不重复动作）
                String result = DailyRunner.runAccountRoles(ctx, acc, true);
                if (result != null) {
                    ran = true;
                    notifyMulti(acc, result);
                }
            } catch (Throwable t) {
                android.util.Log.e("DailySched", "run fail " + acc.fpid, t);
            } finally {
                int pending = DailyRunner.lastPendingRoles;   // 本轮没跑成且今日从未跑过的角色数
                if (ran) {
                    // 轮次真实跑过才记"今日已跑"（runAccountRoles 返回 null = 顶号跳过/登录失败）
                    s.lastRunAt = System.currentTimeMillis();
                    if (pending > 0) {
                        // 部分角色被顶号/登录失败：短间隔补跑而非等明天（角色级短路保证
                        // 已跑角色只花一次配置读取；真机 2026-10-14：916 整轮被跳过后
                        // 14天签到/VIP/礼包全天无人补，次日快照仍可领）
                        long retry = System.currentTimeMillis() + CATCHUP_MS;
                        s.nextRunAt = retry <= todayWindowEnd() ? retry : nextRunInWindow(s.lastRunAt);
                    } else {
                        s.nextRunAt = nextRunInWindow(s.lastRunAt);
                    }
                } else {
                    // 失败/跳过不烧当天：30 分钟后重试（游戏还在玩就继续让位）
                    s.nextRunAt = System.currentTimeMillis() + 30 * 60_000L;
                }
                RoleStore.saveSchedule(ctx, acc.fpid, s);
                updateNotification("日常自动执行已开启 · 下次窗口 " + describeWindow());
                releaseWake();
            }
        });
    }

    /** 今日时间窗截止时刻（w[1]:00；w[1]=24 时取当日末尾）——补跑重试不得越过窗口。 */
    private long todayWindowEnd() {
        int[] w = RoleStore.window(this);
        Calendar c = Calendar.getInstance();
        if (w[1] >= 24) {
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
        } else {
            c.set(Calendar.HOUR_OF_DAY, w[1]);
        }
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        return c.getTimeInMillis();
    }

    /** 时间窗内的随机时刻（每天只跑一次：今天没跑过且在窗内=2-15分钟后；否则明天窗口内随机）。 */
    private long nextRunInWindow(long lastRunAt) {
        int[] w = RoleStore.window(this);
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        boolean ranToday = false;
        if (lastRunAt > 0) {
            Calendar lc = Calendar.getInstance();
            lc.setTimeInMillis(lastRunAt);
            ranToday = lc.get(Calendar.YEAR) == c.get(Calendar.YEAR)
                    && lc.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR);
        }
        if (hour >= w[0] && hour < w[1] && !ranToday) {
            // 当前就在窗口内且今天没跑过：2-15 分钟内随机执行（刚开启不用等到明天）
            return System.currentTimeMillis() + (2 + random.nextInt(13)) * 60_000L;
        }
        c.set(Calendar.HOUR_OF_DAY, w[0] + random.nextInt(Math.max(1, w[1] - w[0])));
        c.set(Calendar.MINUTE, random.nextInt(60));
        c.set(Calendar.SECOND, 0);
        if (ranToday || c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private String describeWindow() {
        int[] w = RoleStore.window(this);
        return w[0] + ":00-" + w[1] + ":00";
    }

    // ---------- 通知 ----------

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "日常自动执行",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("无尽号库 · 日常")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true);
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    /** 执行结果通知（逐角色一行的汇总文本）。 */
    private void notifyMulti(AccountStore.Account acc, String text) {
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "daily_result")
                    : new Notification.Builder(this);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel("daily_result", "日常执行结果",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            b.setContentTitle("日常完成 · " + acc.label + "（多角色）")
                    .setContentText("逐角色结果见展开")
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setAutoCancel(true);
            NotificationManager nm2 = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm2.notify(4300 + (int) (acc.fpid.hashCode() % 100), b.build());
        } catch (Throwable ignored) {
        }
    }

    private void notifyResult(AccountStore.Account acc, DailyRunner.Summary summary) {
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "daily_result")
                    : new Notification.Builder(this);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel("daily_result", "日常执行结果",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            String head = summary.loginError != null
                    ? "登录失败：" + summary.loginError
                    : "成功 " + summary.count(DailyResult.Category.CLAIMED)
                    + " · 跳过 " + summary.count(DailyResult.Category.SKIPPED)
                    + " · 异常 " + (summary.count(DailyResult.Category.DEGRADED)
                    + summary.count(DailyResult.Category.ERROR));
            b.setContentTitle("日常完成 · " + acc.label)
                    .setContentText(head)
                    .setStyle(new Notification.BigTextStyle().bigText(summary.toText()))
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setAutoCancel(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(4200 + (int) (acc.fpid.hashCode() % 100), b.build());
        } catch (Throwable ignored) {
        }
    }

    // ---------- WakeLock / Alarm ----------

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vault:daily-run");
            wakeLock.acquire(10 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void scheduleWakeupAlarm() {
        scheduleAlarmAt(System.currentTimeMillis() + ALARM_INTERVAL_MS, 1001, null);
    }

    /** 精确闹钟（可精确则 setExactAndAllowWhileIdle；否则退化为 AllowWhileIdle）。
     *  PendingIntent 身份 = Intent(filterEquals，不含 extras) + requestCode。
     *  P1-2 双保险：requestCode 按 fpid 哈希派生（%900 有碰撞概率）之上，
     *  再给 Intent 设置 data URI——每账号每闹钟类型拿到独一无二的 PendingIntent，
     *  彻底消除"后排账号顶掉前排账号闹钟"。 */
    private void scheduleAlarmAt(long atMillis, int requestCodeBase, String tag) {
        try {
            int rc = requestCodeBase + (tag == null ? 0 : Math.floorMod(tag.hashCode(), 900));
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, DailySchedulerService.class);
            if (tag != null) {
                i.setData(android.net.Uri.parse("vault://alarm/" + requestCodeBase + "/" + tag));
            }
            PendingIntent pi = PendingIntent.getService(this, rc, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
                } catch (SecurityException e) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- 启动入口 ----------

    public static void ensureStarted(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, DailySchedulerService.class));
        } catch (Throwable t) {
            try {
                ctx.startService(new Intent(ctx, DailySchedulerService.class));
            } catch (Throwable ignored) {
            }
        }
    }
}
