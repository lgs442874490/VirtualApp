package com.carlos.vault.daily;

import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.carlos.vault.VaultSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 战争通知事件轮询 + 短信分发（App 侧，server 模式）：
 * 服务器检测到监视敌人的动作后事件入队（/v1/vault/events 游标同步），本服务每 30s
 * 拉一次增量；命中门控（{@link WarNotifyGate}：开关/权限/eventId 去重/5 分钟节流）
 * 即用 {@link SmsManager} 发到配置手机号。
 *
 * <ul>
 *   <li>幂等单线程（daemon），由 DailySchedulerService 与通知设置页启动，重复 start 合并；</li>
 *   <li>权限被拒 = 只记日志（降级），UI 引导授权后自动恢复，不反复弹系统弹窗；</li>
 *   <li>游标推进不依赖发送成败：事件拉到即 ack，漏发由 eventId 去重 + 服务器 200 条
 *       环形缓冲兜底（下轮 cursor 之前的事件不再重拉）。</li>
 * </ul>
 */
public final class WarNotifyPoller {

    private static final String TAG = "WarNotifyPoller";
    /** 轮询间隔（独立 30s，不复用账号页 5s 池——事件频度低，省电省流量）。 */
    static final long POLL_MS = 30_000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    /** 上一轮请求未返回前不重叠发起。 */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private WarNotifyPoller() {
    }

    /** 启动轮询（幂等）。 */
    public static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    pollOnce(app);
                } catch (Throwable ignored) {
                }
            }
        }, "war-notify-poll");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "事件轮询已启动（" + POLL_MS / 1000 + "s）");
    }

    /** 单轮：短信开启才拉取；回调内推进游标并分发。 */
    static void pollOnce(final Context app) {
        // 开关检查必须在占位之前：CAS 后的裸 return 不会复位 IN_FLIGHT，
        // 短信默认关闭时会让轮询永久停摆（代码审查 P1-1）。
        if (!WarNotifyStore.smsEnabled(app)) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            final long cursor = WarNotifyStore.cursor(app);
            VaultSync.fetchEvents(app, cursor, (code, message, data) -> {
                try {
                    if (code != 0 || data == null) {
                        Log.w(TAG, "拉取事件失败 code=" + code + " " + message);
                        return;
                    }
                    long newCursor = data.optLong("ackCursor", data.optLong("cursor", cursor));
                    JSONArray events = data.optJSONArray("events");
                    if (events != null && events.length() > 0) {
                        dispatch(app, events);
                    }
                    WarNotifyStore.setCursor(app, newCursor);
                } finally {
                    IN_FLIGHT.set(false);
                }
            });
        } catch (Throwable t) {
            IN_FLIGHT.set(false);
            Log.w(TAG, "轮询异常: " + t);
        }
    }

    /** 分发新事件（最旧在前；回调线程执行，SmsManager 发送本身是异步入队）。 */
    static void dispatch(Context app, JSONArray events) {
        String phone = WarNotifyStore.smsPhone(app);
        boolean permission = ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            String eventId = e.optString("eventId", "");
            String why = WarNotifyGate.gate(WarNotifyStore.smsEnabled(app), phone, permission,
                    WarNotifyStore.lastSmsAt(app), System.currentTimeMillis(), eventId,
                    WarNotifyStore.sentIds(app));
            if (why != null) {
                Log.i(TAG, "短信跳过 " + eventId + "：" + why);
                continue;
            }
            String text = WarNotifyGate.formatSms(e, System.currentTimeMillis() / 1000L);
            if (sendSms(phone, text)) {
                WarNotifyStore.markSent(app, eventId, System.currentTimeMillis());
                Log.i(TAG, "短信已发 " + eventId + " → " + maskPhone(phone));
            }
        }
    }


    /** 日志脱敏：手机号仅保留前 3 后 2 位（隐私最小化）。 */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 6) return "**";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
    }

    private static boolean sendSms(String phone, String text) {
        try {
            SmsManager sm = SmsManager.getDefault();
            List<String> parts = sm.divideMessage(text);
            if (parts.size() <= 1) {
                sm.sendTextMessage(phone, null, text, null, null);
            } else {
                sm.sendMultipartTextMessage(phone, null, new ArrayList<>(parts), null, null);
            }
            return true;
        } catch (SecurityException se) {
            Log.w(TAG, "短信失败：缺 SEND_SMS 运行时权限");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "短信失败: " + t);
            return false;
        }
    }
}
