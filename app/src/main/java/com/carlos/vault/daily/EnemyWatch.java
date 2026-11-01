package com.carlos.vault.daily;

import android.content.Context;
import android.telephony.SmsManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 敌情监控（EnemyWatch）：挂在所有在线会话上监听世界行军推送，命中"被标记敌人"名单时
 * 经企业微信群机器人 webhook + 短信双通道分级推送。
 *
 * 数据源（P1 实证 2026-10-15，design/守护盟友-协议实证-20261015.md）：
 *   notify_world_situation_info_add/_up     marches:{槽位:{march:{id,owner,type,endpoint,starttime,endtime},power}}
 *   notify_world_alliance_situation_add/_up to_marchs/marchs:[{id,owner,type,starttime,endtime,power,atk_city,...}]
 *   notify_world_alliance_situation_del     to_marchs/marchs:[{id,type,...}]（endtime>now=召回，≈now=到达）
 * 判定口径：endtime 前移>2s=加速（升级事件）；del 时 endtime-now>2s=召回，否则=到达。
 * type 语义：1=侦查 2=进攻 5=驻防(增援)。
 *
 * 注意：world_situation 系列推送存在地图范围（观察哨周边/联盟相关），远处的标记敌人行军
 * 可能不进推送——由 req_world_march_detail_4_player_situation 定向轮询兜底（每 uid 60s±抖动，
 * 服务器拒绝则自动停用轮询并留痕）。
 *
 * <p><b>侦查类推送限流（用户 2026-11-01 硬规则）</b>：侦查（type=1）滑动 5 分钟窗口内每个敌人
 * 只推送前 3 次（{@link ScoutPushQuota}，与 vault-server WarNotify 同源共用）。超限侦查仍入跟踪表
 * （防轮询重报）但静默：其加速/解除推送也不再发，避免无上下文的孤立通知。
 */
public final class EnemyWatch {

    private static final String TAG = "EnemyWatch";
    /** endtime 前移超过该秒数判为"加速中"（升级事件）。 */
    private static final long SPEEDUP_MIN_S = 2L;
    /** del 时 endtime 与当前差值超过该秒数判为召回，否则为到达。 */
    private static final long RECALL_MIN_S = 2L;
    /** 同一行军升级通知的最小间隔。 */
    private static final long ESCALATE_COOLDOWN_MS = 60_000L;
    /** 企业微信机器人官方限速 20 条/分钟，队列发送按 3s 间距节流。 */
    private static final long WECOM_MIN_INTERVAL_MS = 3000L;
    /** 跟踪表 TTL：endtime 后 15 分钟未 del（推送丢失）则静默清除。 */
    private static final long TRACK_TTL_MS = 15 * 60_000L;
    /** 定向轮询间隔（毫秒，每标记 uid）。 */
    private static final long POLL_MS = 60_000L;

    /** 每会话只挂一个 hook（key = fpid:sid）。 */
    private static final ConcurrentHashMap<com.carlos.vault.wjdr.WjdrEngine, Boolean> ATTACHED = new ConcurrentHashMap<>();
    /** 行军跟踪表：marchId → track（含 owner/kid）。 */
    private static final ConcurrentHashMap<Long, Track> TRACKED = new ConcurrentHashMap<>();
    /** 已解除（到达/召回）的行军：marchId → 解除时刻，防轮询旧数据重报。 */
    private static final ConcurrentHashMap<Long, Long> RESOLVED = new ConcurrentHashMap<>();
    /** 侦查推送配额（滑动 5 分钟窗口内每个敌人只推前 3 次；与 vault-server WarNotify 同源口径）。 */
    private static final ScoutPushQuota SCOUT_QUOTA = new ScoutPushQuota();

    private static final LinkedBlockingQueue<String> WECOM_QUEUE = new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<String> SMS_QUEUE = new LinkedBlockingQueue<>();
    private static volatile boolean workersStarted = false;
    private static volatile long pollUnsupportedUntil = 0L;

    private EnemyWatch() {
    }

    // ------------------------------------------------------------------ 挂载

    /** 会话登录后调用（RoleSessionKeeper，模式同 ProtoLogger.attach）。幂等；开关关闭时不挂。 */
    public static void attach(Context ctx, RoleSessionKeeper.LiveSession fls) {
        try {
            if (!EnemyWatchStore.enabled(ctx)) return;
            if (fls.engine == null) return;
            if (ATTACHED.putIfAbsent(fls.engine, Boolean.TRUE) != null) return;
            final Context app = ctx.getApplicationContext();
            final long kid = fls.sid;
            fls.engine.addMessageHook((proto, content) -> {
                try {
                    handle(app, kid, proto, content);
                } catch (Throwable t) {
                    EnemyWatchStore.log(app, TAG + " 处理异常 " + proto + ": " + t);
                }
            });
            EnemyWatchStore.log(app, "已挂载 " + fls.fpid + ":" + fls.sid);
            startWorkers(app);
            startPoller(app);
        } catch (Throwable t) {
            EnemyWatchStore.log(ctx, TAG + " 挂载失败: " + t);
        }
    }

    /** 配置保存后调用：对全部已在线会话补挂（attach 幂等，已挂的跳过）。 */
    public static void attachAll(Context ctx) {
        if (!EnemyWatchStore.enabled(ctx)) return;
        try {
            for (RoleSessionKeeper.LiveSession fls : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                attach(ctx, fls);
            }
        } catch (Throwable t) {
            EnemyWatchStore.log(ctx, TAG + " 补挂失败: " + t);
        }
    }

    private static synchronized void startWorkers(final Context app) {
        if (workersStarted) return;
        workersStarted = true;
        Thread wecom = new Thread(() -> {
            while (true) {
                try {
                    String text = WECOM_QUEUE.poll(30, TimeUnit.SECONDS);
                    if (text == null) continue;
                    postWecom(app, text);
                    Thread.sleep(WECOM_MIN_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    EnemyWatchStore.log(app, TAG + " 微信发送异常: " + t);
                }
            }
        }, "enemy-watch-wecom");
        wecom.setDaemon(true);
        wecom.start();
        Thread sms = new Thread(() -> {
            while (true) {
                try {
                    String text = SMS_QUEUE.poll(30, TimeUnit.SECONDS);
                    if (text == null) continue;
                    sendSms(app, text);
                    Thread.sleep(30_000L);   // 短信通道 30s 冷却，防轰炸
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    EnemyWatchStore.log(app, TAG + " 短信发送异常: " + t);
                }
            }
        }, "enemy-watch-sms");
        sms.setDaemon(true);
        sms.start();
    }

    // ------------------------------------------------------------------ 推送解析

    private static void handle(Context ctx, long kid, String proto, Map<?, ?> content) {
        if (!EnemyWatchStore.enabled(ctx)) return;
        Map<Long, Map<Long, String>> byKid = EnemyWatchStore.byKid(ctx);
        Map<Long, String> list = byKid.get(kid);
        if (list == null || list.isEmpty()) return;
        if (content == null) return;

        switch (proto) {
            case "notify_world_situation_info_add":
            case "notify_world_situation_info_up": {
                Object marches = content.get("marches");
                if (marches instanceof Map) {
                    for (Object v : ((Map<?, ?>) marches).values()) {
                        if (!(v instanceof Map)) continue;
                        Map<?, ?> entry = (Map<?, ?>) v;
                        Object m = entry.get("march");
                        if (m instanceof Map) {
                            handleMarch(ctx, kid, list, asMap(m), asLong(entry.get("power")));
                        }
                    }
                } else if (marches instanceof List) {
                    // 兜底形状：列表直挂 march 对象
                    for (Object v : (List<?>) marches) {
                        if (v instanceof Map) handleMarch(ctx, kid, list, asMap(v), asLong(((Map<?, ?>) v).get("power")));
                    }
                }
                // 轮询响应（req_world_march_detail_4_player_situation → detail.marchs）复用同一处理器
                Object marchs = content.get("marchs");
                if (marchs instanceof List) {
                    for (Object v : (List<?>) marchs) {
                        if (v instanceof Map) handleMarch(ctx, kid, list, asMap(v), asLong(((Map<?, ?>) v).get("power")));
                    }
                }
                break;
            }
            case "notify_world_alliance_situation_add":
            case "notify_world_alliance_situation_update": {
                for (String key : new String[]{"to_marchs", "marchs"}) {
                    Object arr = content.get(key);
                    if (arr instanceof List) {
                        for (Object v : (List<?>) arr) {
                            if (v instanceof Map) handleMarch(ctx, kid, list, asMap(v), asLong(((Map<?, ?>) v).get("power")));
                        }
                    }
                }
                break;
            }
            case "notify_world_alliance_situation_del": {
                for (String key : new String[]{"to_marchs", "marchs"}) {
                    Object arr = content.get(key);
                    if (arr instanceof List) {
                        for (Object v : (List<?>) arr) {
                            if (v instanceof Map) handleDel(ctx, asLong(((Map<?, ?>) v).get("id")));
                        }
                    }
                }
                break;
            }
            default:
                break;
        }
        sweep();
    }

    private static void handleMarch(Context ctx, long kid, Map<Long, String> list, Map<?, ?> m, long power) {
        long id = asLong(m.get("id"));
        long owner = asLong(m.get("owner"));
        if (id <= 0 || owner <= 0) return;
        String note = list.get(owner);
        if (note == null) return;   // 未标记

        long type = asLong(m.get("type"));
        long start = asLong(m.get("starttime"));
        long end = asLong(m.get("endtime"));
        long nowS = System.currentTimeMillis() / 1000L;
        if (end <= 0) return;

        long targetUid = 0;
        try {
            Object atk = m.get("atk_city");
            if (atk instanceof Map) targetUid = asLong(((Map<?, ?>) atk).get("targetid"));
            if (targetUid <= 0) {
                Object target = m.get("target");
                if (target instanceof Map) {
                    Object city = ((Map<?, ?>) target).get("map_city");
                    if (city instanceof Map) targetUid = asLong(((Map<?, ?>) city).get("uid"));
                }
            }
        } catch (Throwable ignored) {
        }
        int[] xy = endpoint(m);

        Track t = TRACKED.get(id);
        if (t == null) {
            if (RESOLVED.containsKey(id)) return;   // 已解除（到达/召回）的行军，防止轮询旧数据重报
            if (end + 60 < nowS) return;   // 陈旧样本
            t = new Track(id, owner, kid, type, note, end, targetUid, xy == null ? "" : "(" + xy[0] + "," + xy[1] + ")");
            if (type == 1 && !SCOUT_QUOTA.allow(owner, System.currentTimeMillis())) {
                // 用户 2026-11-01：侦查类推送 5 分钟内每个敌人只推前 3 次；超限只跟踪不推送
                t.muted = true;
                TRACKED.put(id, t);
                EnemyWatchStore.log(ctx, "侦查推送超限 uid" + owner + " 行军" + id
                        + "（5 分钟内已推 " + ScoutPushQuota.MAX_PUSHES + " 次，静默）");
                return;
            }
            TRACKED.put(id, t);
            String kind = type == 1 ? "侦查" : type == 2 ? "进攻" : type == 5 ? "驻防" : "行军#" + type;
            StringBuilder sb = new StringBuilder("🎯敌情·").append(kind).append('\n')
                    .append(note.isEmpty() ? "" : "备注[" + note + "] ").append("uid ").append(owner).append('\n')
                    .append("目标：").append(targetUid > 0 ? "uid " + targetUid + " " : "").append(t.xy).append('\n')
                    .append("预计到达：").append(Math.max(0, end - nowS)).append("s 后（").append(hhmmss(end)).append("）");
            if (power > 0) sb.append("\n兵力：power≈").append(power);
            sb.append("\n行军 ").append(id).append('@').append(kid);
            send(ctx, sb.toString(), false);
            EnemyWatchStore.log(ctx, "命中 " + kind + " uid" + owner + " 行军" + id);
        } else if (t.muted) {
            if (end > 0) t.endServerSec = end;   // 超限静默的侦查：只维护跟踪，不推加速/重估
        } else if (end < t.endServerSec - SPEEDUP_MIN_S) {
            long prevEnd = t.endServerSec;
            t.endServerSec = end;
            long nowMs = System.currentTimeMillis();
            if (!t.escalated || nowMs - t.lastEscalateAt > ESCALATE_COOLDOWN_MS) {
                t.escalated = true;
                t.lastEscalateAt = nowMs;
                String msg = "⚡敌情升级：" + (t.note.isEmpty() ? "" : "备注[" + t.note + "] ") + "uid " + t.owner
                        + " 行军 " + id + " 加速中！剩余 " + Math.max(0, prevEnd - nowS) + "s → " + Math.max(0, end - nowS)
                        + "s（预计 " + hhmmss(end) + " 落地）";
                send(ctx, msg, true);
                EnemyWatchStore.log(ctx, "升级 行军" + id + " 加速 " + (prevEnd - nowS) + "s→" + (end - nowS) + "s");
            }
        } else if (end > t.endServerSec) {
            t.endServerSec = end;   // 变慢/重估，静默更新
        }
    }

    private static void handleDel(Context ctx, long marchId) {
        if (marchId <= 0) return;
        Track t = TRACKED.remove(marchId);
        RESOLVED.put(marchId, System.currentTimeMillis());
        if (t == null || t.resolved) return;
        t.resolved = true;
        if (t.muted) {
            EnemyWatchStore.log(ctx, "解除静默 行军" + marchId + "（该侦查超限未推送）");
            return;
        }
        long nowS = System.currentTimeMillis() / 1000L;
        long remain = t.endServerSec - nowS;
        String how = remain > RECALL_MIN_S ? "召回（距落地还剩 " + remain + "s）" : "已落地";
        send(ctx, "✅敌情解除：" + (t.note.isEmpty() ? "" : "备注[" + t.note + "] ") + "uid " + t.owner
                + " 行军 " + marchId + " " + how, false);
        EnemyWatchStore.log(ctx, "解除 行军" + marchId + " " + how);
    }

    private static void sweep() {
        long nowMs = System.currentTimeMillis();
        for (Map.Entry<Long, Track> e : TRACKED.entrySet()) {
            if (e.getValue().endServerSec * 1000L + TRACK_TTL_MS < nowMs) TRACKED.remove(e.getKey());
        }
        for (Map.Entry<Long, Long> e : RESOLVED.entrySet()) {
            if (nowMs - e.getValue() > TRACK_TTL_MS) RESOLVED.remove(e.getKey());
        }
        SCOUT_QUOTA.sweep(nowMs);   // 侦查配额表回收窗口外记录，防长期运行无界增长
    }

    private static int[] endpoint(Map<?, ?> m) {
        try {
            Object p = m.get("endpoint");
            if (p instanceof Map) {
                return new int[]{(int) asLong(((Map<?, ?>) p).get("x")), (int) asLong(((Map<?, ?>) p).get("y"))};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ 定向轮询兜底

    private static synchronized void startPoller(final Context app) {
        final Thread poller = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(POLL_MS + (long) (Math.random() * 10_000L));   // ±抖动拟人化
                    if (!EnemyWatchStore.enabled(app)) continue;
                    if (System.currentTimeMillis() < pollUnsupportedUntil) continue;
                    pollOnce(app);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    EnemyWatchStore.log(app, TAG + " 轮询异常: " + t);
                }
            }
        }, "enemy-watch-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private static void pollOnce(Context app) {
        Map<Long, Map<Long, String>> byKid = EnemyWatchStore.byKid(app);
        for (Map.Entry<Long, Map<Long, String>> e : byKid.entrySet()) {
            long kid = e.getKey();
            RoleSessionKeeper.LiveSession fls = findOnline(app, kid);
            if (fls == null) continue;
            for (Long uid : e.getValue().keySet()) {
                try {
                    java.util.Map<String, Object> args = new java.util.HashMap<>();
                    args.put("owner", uid);
                    args.put("kid", kid);
                    com.carlos.vault.wjdr.protocol.GameMessage rsp = fls.engine.request(
                            "req_world_march_detail_4_player_situation", args);
                    Map<String, Object> rc = rsp == null ? null : rsp.getContent();
                    if (rc == null || asLong(rc.get("code")) != 0) {
                        // 服务器不接受他人 owner 查询：停用轮询 30 分钟并留痕（推送通道仍有效）
                        pollUnsupportedUntil = System.currentTimeMillis() + 30 * 60_000L;
                        EnemyWatchStore.log(app, "轮询查询被拒(code≠0)，停用30分钟，仅用推送通道");
                        return;
                    }
                    Object detail = rc.get("detail");
                    if (!(detail instanceof Map)) continue;
                    handle(app, kid, "notify_world_situation_info_add", asMap(detail));
                } catch (Throwable t) {
                    EnemyWatchStore.log(app, TAG + " 轮询 uid" + uid + " 失败: " + t);
                }
            }
        }
    }

    private static RoleSessionKeeper.LiveSession findOnline(Context ctx, long kid) {
        try {
            RoleSessionKeeper keeper = RoleSessionKeeper.get(ctx);
            for (RoleSessionKeeper.LiveSession fls : keeper.sessionsSnapshot()) {
                if (fls.sid == kid && fls.engine != null
                        && com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(fls.engine.sessionState())) return fls;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 守护盟友等模块的公共告警入口：微信+短信双通道（绕过名单逻辑直接推送）。 */
    public static void alert(Context ctx, String text, boolean sms) {
        try {
            startWorkers(ctx);
            boolean wecom = EnemyWatchStore.wecomOn(ctx) && !EnemyWatchStore.webhook(ctx).isEmpty();
            boolean smsOk = EnemyWatchStore.smsOn(ctx) && !EnemyWatchStore.smsPhone(ctx).isEmpty() && sms;
            if (wecom) WECOM_QUEUE.offer(text);
            if (smsOk) SMS_QUEUE.offer("[守护] " + text.split("\n")[0]);
            EnemyWatchStore.log(ctx, "告警推送 微信=" + wecom + " 短信=" + smsOk + ": " + text.split("\n")[0]);
        } catch (Throwable ignored) {
        }
    }

    private static void glog2(Context c, String line) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "guardian_log.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(new java.util.Date()) + " " + line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 双通道发送

    private static void send(Context ctx, String text, boolean escalate) {
        boolean wecom = EnemyWatchStore.wecomOn(ctx) && !EnemyWatchStore.webhook(ctx).isEmpty();
        boolean sms = EnemyWatchStore.smsOn(ctx) && !EnemyWatchStore.smsPhone(ctx).isEmpty()
                && (!EnemyWatchStore.smsEscalateOnly(ctx) || escalate);
        if (wecom) WECOM_QUEUE.offer(text);
        if (sms) SMS_QUEUE.offer("[敌情] " + text.replace("\n", " | "));
        if (!wecom && !sms) EnemyWatchStore.log(ctx, "命中但通道未配置，丢弃: " + text.split("\n")[0]);
    }

    private static void postWecom(Context app, String text) {
        HttpURLConnection conn = null;
        try {
            String webhook = EnemyWatchStore.webhook(app);
            if (webhook.isEmpty()) return;
            JSONObjectWrap body = new JSONObjectWrap();
            String payload = body.text(text);
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(webhook).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            EnemyWatchStore.log(app, "微信推送 " + code + " " + abbreviate(resp));
        } catch (Throwable t) {
            EnemyWatchStore.log(app, "微信推送失败: " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void sendSms(Context app, String text) {
        try {
            String phone = EnemyWatchStore.smsPhone(app);
            if (phone.isEmpty()) return;
            SmsManager sm = SmsManager.getDefault();
            List<String> parts = sm.divideMessage(text);
            if (parts.size() <= 1) {
                sm.sendTextMessage(phone, null, text, null, null);
            } else {
                ArrayList<String> list = new ArrayList<>(parts);
                sm.sendMultipartTextMessage(phone, null, list, null, null);
            }
            EnemyWatchStore.log(app, "短信已发 → " + phone);
        } catch (SecurityException se) {
            EnemyWatchStore.log(app, "短信失败：缺 SEND_SMS 运行时权限，请在系统设置中授予");
        } catch (Throwable t) {
            EnemyWatchStore.log(app, "短信失败: " + t);
        }
    }

    /** 配置界面"测试推送"：微信 + 短信各发一条。 */
    public static void sendTest(Context ctx) {
        String msg = "🧪敌情监控测试推送：通道打通即部署成功。";
        if (EnemyWatchStore.wecomOn(ctx) && !EnemyWatchStore.webhook(ctx).isEmpty()) WECOM_QUEUE.offer(msg);
        if (EnemyWatchStore.smsOn(ctx) && !EnemyWatchStore.smsPhone(ctx).isEmpty()) SMS_QUEUE.offer(msg);
        startWorkers(ctx.getApplicationContext());
        EnemyWatchStore.log(ctx, "手动触发测试推送");
    }

    // ------------------------------------------------------------------ 工具

    private static long asLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (Throwable t) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object o) {
        return (Map<?, ?>) o;
    }

    private static String hhmmss(long serverSec) {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(serverSec * 1000L));
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 一条被标记敌军的行军跟踪记录。 */
    private static final class Track {
        final long marchId;
        final long owner;
        final long kid;
        final long type;
        final String note;
        volatile long endServerSec;      // 服务器秒
        final long targetUid;
        final String xy;
        boolean escalated = false;
        long lastEscalateAt = 0L;
        boolean resolved = false;
        /** 侦查推送超限被静默：首次推送未发，后续加速/解除也不再推送。 */
        boolean muted = false;

        Track(long marchId, long owner, long kid, long type, String note, long endServerSec, long targetUid, String xy) {
            this.marchId = marchId;
            this.owner = owner;
            this.kid = kid;
            this.type = type;
            this.note = note;
            this.endServerSec = endServerSec;
            this.targetUid = targetUid;
            this.xy = xy;
        }
    }

    /** 极简 JSON 文本消息构造（避免为一条 push 引 JSON 依赖路径分叉）。 */
    private static final class JSONObjectWrap {
        String text(String content) {
            String esc = content.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "");
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + esc + "\"}}";
        }
    }
}
