package com.carlos.vault;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 游戏进程保活前台服务。目的：切到微信等后台时，游戏进程不被系统 LMK 回收。
 *
 * 本服务运行在**宿主主进程**（manifest 中不指定 android:process），不能放在 VA 的
 * 游戏 stub 进程 :p0 上：:p0 与游戏同进程，829M Unity 首启会把该进程主线程打满，
 * startForegroundService() 之后 onStartCommand 排不上队，5 秒内没调到 startForeground，
 * 系统会抛 ForegroundServiceDidNotStartInTimeException 并杀掉调用方（也就是游戏进程）
 * ——与"保活"的初衷完全相反。
 *
 * 放在宿主主进程后：宿主主线程在游戏启动时是空闲的，能按时完成 startForeground()；
 * 而前台服务提升的是**同 UID** 的进程优先级，com.gof.china 与宿主同为 u0_a373，
 * 游戏进程一样受保护，保活目的仍然达成。
 *
 * 约束（务必保持）：Android 8+ 要求 startForegroundService 后 5 秒内必须调用
 * startForeground，否则抛 ForegroundServiceDidNotStartInTimeException。因此
 * onStartCommand 里第一件事就同步、无条件地 startForeground，不依赖任何可能
 * 失败或耗时的构造；通知通道已在 start() 里提前建好，这里不做额外 IPC。
 */
public class GameKeepAliveService extends Service {

    private static final String CH_ID = "game_keepalive";
    private static final int NOTIF_ID = 2790;

    public static void start(Context ctx) {
        try {
            // 提前把通知通道建好（含一次 IPC），这样 onStartCommand 里的 startForeground
            // 变成纯调用，不再夹带通道创建开销，进一步压低 5 秒死限被击穿的概率。
            ensureChannel(ctx);
            Intent it = new Intent(ctx, GameKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(it);
            } else {
                ctx.startService(it);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, GameKeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 第一步立即进入前台，杜绝超时崩溃。
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {
            // 极端情况：若 startForeground 抛异常，立即 stopSelf，避免被系统判为超时后连带崩溃。
            stopSelf();
            return START_NOT_STICKY;
        }
        // 被杀后由系统重建，继续保活。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // R3 尽最大努力：任务被移除时请求一次后台存档（不保证被调用，见 SessionSaver 注释）。
        SessionSaver.requestSave(this);
    }

    private Notification buildNotification() {
        ensureChannel(this);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        // 用系统内置图标，避免资源/向量兼容问题；不做 PendingIntent，避免拖慢启动时序。
        b.setContentTitle("无尽号库")
                .setContentText("正在运行游戏，可安心切到其他应用")
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setOngoing(true);
        return b.build();
    }

    /** 静态形式，便于在 start() 里、startForegroundService() 之前提前调用 */
    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = nm.getNotificationChannel(CH_ID);
                if (ch == null) {
                    ch = new NotificationChannel(CH_ID, "无尽号库保活", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    ch.enableVibration(false);
                    nm.createNotificationChannel(ch);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
