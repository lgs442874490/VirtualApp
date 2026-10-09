package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器调度器（30 秒节拍，对齐 App DailySchedulerService 语义）：
 * - 每日整轮：auto 开启且进入时间窗的账号，在窗内随机时刻跑一轮（同日不重复；错过窗口立即补跑）；
 * - 补给到点补领：supply online_ts 到点只跑 supply 单功能；
 * - 手动触发：policy action=run 提交的请求。
 * 执行都经由 SessionKeeper 派发到该账号的会话线程（会话不在线则跳过本轮）。
 */
public final class RoundScheduler {

    private static final long TICK_MILLIS = 30_000L;
    /** 窗内随机化上限：与 App 语义一致（时间窗内随机时刻），封顶 60 分钟分散负载。 */
    private static final long RANDOMIZE_CAP_MILLIS = 60 * 60_000L;

    private final VaultStore store;
    private final StateStore stateStore;
    private final KeeperFacade keeper;
    private final Map<String, Long> nextRunAt = new ConcurrentHashMap<>();
    private final Map<String, String> manualQueue = new ConcurrentHashMap<>();
    private final Map<String, Long> supplyFiredAt = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    /** SessionKeeper 的调度面（单测可注入假实现）。 */
    public interface KeeperFacade {
        /** 提交整轮（fn=null）或单任务到会话线程；返回是否受理（会话存在）。 */
        boolean submitRound(String fpid, String fn, String trigger);

        /** 账号当前会话的 sid（无会话返回 0）。 */
        long sessionSid(String fpid);

        /** 会话是否在线。 */
        boolean sessionOnline(String fpid);
    }

    public RoundScheduler(VaultStore store, StateStore stateStore, KeeperFacade keeper) {
        this.store = store;
        this.stateStore = stateStore;
        this.keeper = keeper;
    }

    public void start() {
        Thread t = new Thread(() -> {
            while (!stopped) {
                try {
                    tick();
                } catch (Throwable e) {
                    FileLog.warn("调度节拍异常：{}", String.valueOf(e));
                }
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "round-scheduler");
        t.setDaemon(true);
        t.start();
        FileLog.info("整轮调度器已启动（30s 节拍）");
    }

    public void stop() {
        stopped = true;
    }

    /** 手动触发入口（policy action=run）。 */
    public boolean requestRun(String fpid, String fn) {
        if (store.find(fpid) == null) return false;
        manualQueue.put(fpid, fn == null ? "" : fn);
        return true;
    }

    void tick() {
        long now = System.currentTimeMillis();
        String today = com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd").format(new Date());
        // 1) 手动触发（最高优先级）
        for (Map.Entry<String, String> e : manualQueue.entrySet()) {
            manualQueue.remove(e.getKey());
            String fn = e.getValue().isEmpty() ? null : e.getValue();
            if (keeper.submitRound(e.getKey(), fn, "手动")) {
                FileLog.info("手动整轮 fpid={} fn={}", e.getKey(), String.valueOf(fn));
            } else {
                FileLog.warn("手动整轮未受理 fpid={}（无会话/未常驻）", e.getKey());
            }
        }
        for (VaultStore.Account acc : store.snapshot()) {
            if (acc.token == null || acc.token.isEmpty()) continue;
            // 2) 每日整轮（auto + 时间窗）
            if (acc.auto) {
                long lastRun = longOf("srv:lastrun:" + acc.fpid);
                boolean ranToday = lastRun > 0 && com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd")
                        .format(new Date(lastRun)).equals(today);
                if (!ranToday) {
                    long windowStart = todayAt(acc.windowStart);
                    long windowEnd = todayAt(acc.windowEnd);
                    Long planned = nextRunAt.get(acc.fpid);
                    if (planned == null) {
                        // 首次入窗：在窗内随机取一时刻（错过窗口则立即补跑）
                        long plannedAt;
                        if (now >= windowEnd) {
                            plannedAt = now;                       // 错过今日窗口 → 立即补跑
                        } else if (now < windowStart) {
                            plannedAt = -1L;                        // 未到窗口，先不排
                        } else {
                            long span = Math.min(windowEnd - now, RANDOMIZE_CAP_MILLIS);
                            plannedAt = now + (long) (Math.random() * Math.max(span, 1));
                        }
                        if (plannedAt >= 0) {
                            nextRunAt.put(acc.fpid, plannedAt);
                            FileLog.info("整轮已排 fpid={} 予定={}", acc.fpid,
                                    com.carlos.vaultserver.Beijing.fmt("HH:mm:ss").format(new Date(plannedAt)));
                        }
                    } else if (now >= planned && keeper.sessionOnline(acc.fpid)) {
                        nextRunAt.remove(acc.fpid);
                        if (keeper.submitRound(acc.fpid, null, "定时")) {
                            FileLog.info("定时整轮启动 fpid={}", acc.fpid);
                        }
                    }
                } else {
                    nextRunAt.remove(acc.fpid);
                }
            }
            // 3) 补给到点补领（只跑 supply 单功能；成功触发后按新一轮 online_ts 重新武装）
            long sid = keeper.sessionSid(acc.fpid);
            if (sid > 0 && keeper.sessionOnline(acc.fpid)) {
                long wake = RoundRunner.supplyWakeAt(stateStore, acc.fpid, sid);
                if (wake > 0 && now >= wake && now - wake < 30 * 60_000L) {
                    Long fired = supplyFiredAt.get(acc.fpid);
                    long cur = longOf("srv:supplyfire:" + acc.fpid);
                    if (fired == null || fired != wake) {
                        supplyFiredAt.put(acc.fpid, wake);
                        stateStore.putLong("srv:supplyfire:" + acc.fpid, wake);
                        if (keeper.submitRound(acc.fpid, "supply", "定时")) {
                            FileLog.info("补给补领启动 fpid={} sid={}", acc.fpid, String.valueOf(sid));
                        }
                    } else if (cur == wake) {
                        // 30 分钟仍未变（补领失败/不可领）：放弃本窗口等待下一轮采集刷新
                        supplyFiredAt.remove(acc.fpid);
                        stateStore.putLong("srv:supplyfire:" + acc.fpid, 0);
                    }
                }
            }
        }
    }

    private long longOf(String key) {
        Object v = stateStore.snapshot().get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private long todayAt(int hour) {
        java.util.Calendar c = java.util.Calendar.getInstance(com.carlos.vaultserver.Beijing.TZ);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
