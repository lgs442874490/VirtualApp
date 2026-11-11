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
import android.os.IBinder;

/**
 * 前台保活服务（原「日常自动执行引擎」，2026-11-12 随旧本地模式清理）。
 *
 * <p>自动化任务全部在 vault-server 执行，本服务只负责：
 * <ul>
 *   <li>常驻前台通知 + AlarmManager 30 分钟兜底唤醒（防进程被冻结）；</li>
 *   <li>启动 WarNotifyPoller（战争通知事件轮询，短信开关关闭时空转）；</li>
 *   <li>清理过期 st:&lt;date&gt;: 当日状态键（防按日分键无限堆积）。</li>
 * </ul>
 */
public class DailySchedulerService extends Service {

    private static final String CHANNEL_ID = "daily_scheduler";
    private static final int NOTIF_ID = 4210;
    private static final long ALARM_INTERVAL_MS = 30 * 60 * 1000L;  // 闹钟兜底 30 分钟

    private static DailySchedulerService instance;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIF_ID, buildNotification("后台保活已开启"));
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
        scheduleWakeupAlarm();   // onCreate 已排；被系统重启（START_STICKY）走不到 onCreate 的极端路径兜底重排
        return START_STICKY;
    }

    /** 供通知监听等组件拿外部文件目录（事件日志用）。 */
    static java.io.File appFiles() {
        return instance != null ? instance.getExternalFilesDir(null) : null;
    }

    /**
     * 游戏原生通知事件入口（GameNotifyListenerService 调用）：客户端不再本地跑任务，
     * 仅记录事件供排障；联盟互助/开盾等实时逻辑由服务器常驻会话承担。
     */
    public static void onGameEvent(final android.content.Context ctx, final String type, String detail) {
        GameNotifyListenerService.logEvent("server-only ignore event " + type
                + (detail == null || detail.isEmpty() ? "" : " · " + detail));
    }

    // ---------- 通知 ----------

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台保活",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("无尽号库 · 保活")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true);
        return b.build();
    }

    // ---------- Alarm ----------

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
        instance = null;
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
