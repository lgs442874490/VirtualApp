package com.carlos.vault.daily;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 协议监听（诊断）：开启后常驻会话收到的每条服务端推送按「时间 fpid:sid 协议名 内容」
 * 追加落盘 files/proto_log.txt（超 2MB 轮转为 .old）。
 *
 * <p>用途：逆向新功能的协议族。原生游戏内操作后（顶号会踢掉本会话），本会话按探测节律
 * 重连，登录时收到的 notify_*_init 全量推送即包含该功能的服务端状态形状——例如
 * 「史诗与远方」「成长任务支线」这类尚未搬进任务表的功能，先抓推送定协议名和字段，
 * 再按命名约定推 req 动作。
 *
 * <p>开关在今日页「工具箱 → 协议监听」；关闭即时生效（append 每次落盘前复查开关）。
 */
public final class ProtoLogger {

    private static final String PREFS = "home";
    private static final String KEY_ON = "proto_log_on";
    private static final long ROTATE_BYTES = 2L * 1024 * 1024;
    private static final int CAP_DEFAULT = 1200;
    private static final int CAP_AUDIT = 16 * 1024;

    /** 审计关键协议放宽截断（默认 1200 会把大 notify 截成不可解析的 JSON——
     * 2026-10-14 对标审计中 notify_achievement_info 全量列表因此无法复核）。
     * 用 contains：快照路径传入形如 "SNAPSHOT-LOGIN:notify_..." 带 tag 前缀。 */
    private static int capOf(String proto) {
        if (proto == null) return CAP_DEFAULT;
        return (proto.contains("notify_achievement") || proto.contains("notify_mail")
                || proto.contains("notify_value_exchange") || proto.contains("notify_daily_bundle")
                || proto.contains("notify_vip") || proto.contains("notify_fourteen_sign"))
                ? CAP_AUDIT : CAP_DEFAULT;
    }

    private ProtoLogger() {
    }

    public static boolean on(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ON, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ON, on).apply();
    }

    /** 日志文件大小（字节；无文件 0），工具箱展示用。 */
    public static long logSize(Context ctx) {
        File f = new File(ctx.getFilesDir(), "proto_log.txt");
        return f.isFile() ? f.length() : 0;
    }

    /** 给一条（新建或已在线的）常驻会话挂监听钩子（幂等；未开启则空操作）。 */
    public static void attach(Context ctx, RoleSessionKeeper.LiveSession ls) {
        if (!on(ctx) || ls == null) return;
        if (!ls.protoLogAttached.compareAndSet(false, true)) return;
        dumpExisting(ctx, ls, "SNAPSHOT-LOGIN");
        ls.engine.addMessageHook((proto, content) -> append(ctx, ls, proto, content));
    }

    /**
     * 开启开关后给当前全部在线会话补挂（会话建立早于开关开启的情况）。
     * 已挂过的会话也会补一次快照转存——引擎的 notifies 表存着登录 init 突发的
     * 最新状态，是逆向新功能协议的主要数据源（登录后挂钩会错过实时突发）。
     */
    public static void reattachAll(Context ctx) {
        if (!on(ctx)) return;
        List<RoleSessionKeeper.LiveSession> ss = RoleSessionKeeper.get(ctx).sessionsSnapshot();
        if (ss == null) return;
        for (RoleSessionKeeper.LiveSession ls : ss) {
            if (ls == null) continue;
            if (ls.protoLogAttached.compareAndSet(false, true)) {
                ls.engine.addMessageHook((proto, content) -> append(ctx, ls, proto, content));
            }
            dumpExisting(ctx, ls, "SNAPSHOT");
        }
    }

    /** 把引擎已存的全部 notify（登录 init 突发等）转存进日志（tag 标注来源）。 */
    private static synchronized void dumpExisting(Context ctx, RoleSessionKeeper.LiveSession ls,
                                                 String tag) {
        if (!on(ctx) || ls == null) return;
        try {
            String protos = ls.engine.notifyProtos();
            if (protos == null || protos.isEmpty()) return;
            File f = new File(ctx.getFilesDir(), "proto_log.txt");
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                        .format(new Date()));
                w.write(' ');
                w.write(ls.fpid + ":" + ls.sid);
                w.write(" ---- " + tag + " begin (" + protos.split(",").length + " protos) ----\n");
            }
            for (String p : protos.split(",")) {
                if (p.isEmpty()) continue;
                append(ctx, ls, tag + ":" + p, ls.engine.notifyContent(p));
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void append(Context ctx, RoleSessionKeeper.LiveSession ls,
                                            String proto, Map<String, Object> content) {
        if (!on(ctx) || proto == null) return;
        try {
            File dir = ctx.getFilesDir();
            File f = new File(dir, "proto_log.txt");
            if (f.isFile() && f.length() > ROTATE_BYTES) {
                File old = new File(dir, "proto_log.txt.old");
                if (!old.delete() && old.isFile()) return;   // 删不掉则暂停写入，防无限膨胀
                if (!f.renameTo(old)) return;
            }
            String json;
            try {
                Object j = DailyRunner.toJavaJson(content);
                json = j == null ? "" : j.toString();
            } catch (Throwable t) {
                json = String.valueOf(content);
            }
            int cap = capOf(proto);
            if (json.length() > cap) json = json.substring(0, cap) + "…";
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                        .format(new Date()));
                w.write(' ');
                w.write(ls == null ? "?" : ls.fpid + ":" + ls.sid);
                w.write(' ');
                w.write(proto);
                w.write(' ');
                w.write(json);
                w.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }
}
