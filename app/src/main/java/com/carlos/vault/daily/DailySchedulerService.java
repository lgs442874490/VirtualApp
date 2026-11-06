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
        WarNotifyPoller.start(this);           // 战争通知事件轮询（幂等；短信开关关闭时空转）
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
/**
     * 游戏原生通知事件入口（GameNotifyListenerService 调用）：
     * - 任意事件 → 立即唤醒巡检（事件驱动兜底：通知能拉活被冻结的调度）
     * - WAR/RECON → 逐个自动账号反应式开盾（幂等，已开过返回6151跳过）
     */
    /** 供通知监听等组件拿外部文件目录（事件日志用）。 */
    static java.io.File appFiles() {
        return instance != null ? instance.getExternalFilesDir(null) : null;
    }

    public static void onGameEvent(final android.content.Context ctx, final String type, String detail) {
        // 统一自动化：任务只在 vault-server 执行，客户端收到游戏通知时不再本地跑任务。
        // 联盟互助/被打开盾等实时逻辑由服务器常驻会话（ServerHelpResponder）承担；
        // 服务器尚未迁移的反应式开盾属于已知缺口，见自动化验证报告。
        GameNotifyListenerService.logEvent("server-only ignore event " + type
                + (detail == null || detail.isEmpty() ? "" : " · " + detail));
    }
/** 通知文案含账号名或该凭证下任一角色名 → 定位账号。 */
/**
     * 巡检：2026-10 起客户端不再本地执行任何自动化任务。
     * 整轮/补给补领由 vault-server 的 RoundScheduler 负责；本机只同步“服务器模式”会话状态
     * （服务器模式下 syncOnline 会关闭本地常驻会话，防止与服务器互踢）。
     */
    private void sweepDueAccounts() {
        com.carlos.vault.VaultSync.ensureServerMode(this);
        RoleSessionKeeper.get(this).syncOnline();
    }
/** 今日时间窗截止时刻（w[1]:00；w[1]=24 时取当日末尾）——补跑重试不得越过窗口。 */
/** 时间窗内的随机时刻（每天只跑一次：今天没跑过且在窗内=2-15分钟后；否则明天窗口内随机）。 */
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

/** 执行结果通知（逐角色一行的汇总文本）。 */
// ---------- WakeLock / Alarm ----------

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vault:daily-run");
            wakeLock.acquire(10 * 60 * 1000L);
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
