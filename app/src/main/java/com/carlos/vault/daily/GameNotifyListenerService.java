package com.carlos.vault.daily;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.File;
import java.io.FileWriter;

/**
 * 游戏原生通知监听（侦查/战争/部队返回/资源/灯塔/寻宝/联盟捐献…）：
 * - 事件驱动唤醒调度器（通知会拉活进程，兜底 Handler 被冻结的情况）
 * - 战争/侦查类事件 → 立即给所有自动账号反应式开盾
 * - 全部事件落盘 notify_events.txt（验证与排查用）
 */
public class GameNotifyListenerService extends NotificationListenerService {

    private static final String[] HOST_PKGS = {"com.carlos.multiapp", "com.gof.china"};

    // 事件分类关键词（按优先级匹配标题+文案）
    private static final String[][] RULES = {
            {"WAR", "被袭击", "敌军来袭", "烽火", "城市遭到攻击", "战争警报"},
            {"RECON", "侦查报告", "侦察报告", "敌情"},
            {"MARCH_RETURN", "部队返回", "行军结束", "出征返回", "部队抵达", "集结结束"},
            {"HEAL_DONE", "治疗完成", "伤兵治愈", "治疗结束"},
            {"RESOURCE", "采集完成", "资源生产", "生产完成", "资源已满"},
            {"WAREHOUSE_FULL", "仓库已满", "存储已达上限"},
            {"LIGHTHOUSE", "灯塔", "情报"},
            {"PET", "寻宝", "宠物"},
            {"ALLIANCE", "联盟捐献", "联盟求助", "联盟宝箱"},
            {"BUILD_DONE", "建造完成", "升级完成", "研究完成"},
            {"MAIL", "新邮件", "邮件到达"},
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            boolean host = false;
            for (String p : HOST_PKGS) {
                if (p.equals(pkg)) {
                    host = true;
                    break;
                }
            }
            if (!host) return;
            Bundle extras = sbn.getNotification().extras;
            String title = str(extras.getCharSequence(android.app.Notification.EXTRA_TITLE));
            String text = str(extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
            String hit = classify(title + " " + text);
            if (hit == null) return;
            logEvent(hit + " | " + title + " | " + brief(text));
            DailySchedulerService.onGameEvent(this, hit, title + " " + text);
        } catch (Throwable ignored) {
        }
    }

    private static String classify(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) {
                if (s.contains(rule[i])) return rule[0];
            }
        }
        return null;
    }

    private static String str(CharSequence c) {
        return c == null ? "" : String.valueOf(c);
    }

    private static String brief(String s) {
        return s == null ? "" : (s.length() > 60 ? s.substring(0, 60) : s);
    }

    static void logEvent(String msg) {
        try {
            File dir = DailySchedulerService.appFiles();
            if (dir == null) return;
            FileWriter w = new FileWriter(new File(dir, "notify_events.txt"), true);
            w.write(System.currentTimeMillis() + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 通知使用权是否已授予（引导页判断用）。 */
    public static boolean enabled(Context ctx) {
        String flat = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(ctx.getPackageName());
    }

    /** 打开系统的通知使用权设置页。 */
    public static void openSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

}
