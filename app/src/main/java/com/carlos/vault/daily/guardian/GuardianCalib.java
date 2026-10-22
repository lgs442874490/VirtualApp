package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.FnParams;

import org.json.JSONObject;

/**
 * 距离分档标定 D（开发计划遗留 #5，设计 §6.2）。
 *
 * 实测（交接 20261017 §二.9）：标定 D 随距离变——近距 16-17s、中距 38s；旧模型按 kid 单值存，
 * 精卡等待与加速介入都按单值算，短途误等、长途误发。本类按 kid 存 near/mid/far 三档
 * EWMA 均值 D（秒）+ 采样数，另设 single 桶承接"距离未知"的样本与旧单值标定迁移。
 *
 * 档位阈值（发射点→目标欧氏格距）：near ≤ dist_near(默认20格) < mid ≤ dist_mid(默认60格) < far。
 * 取值顺序（estimate）：对应档 → single（缺档回退单值）→ 默认 38s（再缺回退默认）。
 *
 * 校准入口：每次驻防发射成功（响应 endtime − 发出时刻）自动记一个实测 D 样本，
 * EWMA(α=0.3) 更新对应档均值并落 FnParams（p:fpid:sid:guardian 顶层 "calib"）。
 * 旧 guardian_calib.txt（kid 单值）首次读取时迁移进 single 桶，此后以 FnParams 为准。
 */
public final class GuardianCalib {

    public static final int BAND_UNKNOWN = -1;
    public static final int BAND_NEAR = 0;
    public static final int BAND_MID = 1;
    public static final int BAND_FAR = 2;

    /** 全无标定时的兜底 D（交接实测中距样本，R3 介入线等场景的保守取值）。 */
    public static final long DEFAULT_D_SEC = 38L;
    /** EWMA 平滑系数：新样本权重 0.3（约 6 发收敛到新距离段的稳态）。 */
    public static final double EWMA_ALPHA = 0.3;
    /** 样本有效性界：D<1s 必是解析错；>1h 必是等待时长混进来了（都拒收）。 */
    public static final long MIN_SAMPLE_SEC = 1L;
    public static final long MAX_SAMPLE_SEC = 3600L;

    static final double DEF_NEAR_GRIDS = 20d;
    static final double DEF_MID_GRIDS = 60d;

    /** 单档统计：EWMA 均值 D（秒）+ 样本数。 */
    public static final class Bucket {
        public long dSec;
        public long samples;

        public Bucket() {
        }

        public Bucket(long d, long n) {
            this.dSec = d;
            this.samples = n;
        }
    }

    /** 一份标定表：三档 + single（未知距离/旧单值）。 */
    public static final class Table {
        public final Bucket near = new Bucket();
        public final Bucket mid = new Bucket();
        public final Bucket far = new Bucket();
        public final Bucket single = new Bucket();

        public boolean anySample() {
            return near.samples > 0 || mid.samples > 0 || far.samples > 0 || single.samples > 0;
        }

        public Bucket bucketOf(int band) {
            switch (band) {
                case BAND_NEAR: return near;
                case BAND_MID: return mid;
                case BAND_FAR: return far;
                default: return single;
            }
        }
    }

    private GuardianCalib() {
    }

    // ------------------------------------------------------------------ 纯逻辑（单测面）

    /** 欧氏格距。 */
    public static double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 距离 → 档位：≤nearTh 近、≤midTh 中、其余远（nearTh/midTh 非法时回默认 20/60）。 */
    public static int bandOf(double dist, double nearTh, double midTh) {
        if (!(nearTh > 0)) nearTh = DEF_NEAR_GRIDS;
        if (!(midTh > nearTh)) midTh = Math.max(nearTh + 1, DEF_MID_GRIDS);
        if (dist <= nearTh) return BAND_NEAR;
        return dist <= midTh ? BAND_MID : BAND_FAR;
    }

    /** 档位 D 取值：对应档 → single（缺档回退单值）→ 默认 38s（再缺回退默认）。 */
    public static long estimate(Table t, int band) {
        if (t != null) {
            Bucket b = t.bucketOf(band);
            if (b.samples > 0 && b.dSec > 0) return b.dSec;
            if (band != BAND_UNKNOWN && t.single.samples > 0 && t.single.dSec > 0) return t.single.dSec;
        }
        return DEFAULT_D_SEC;
    }

    /** 记入一个实测 D 样本（EWMA 更新；dist<0 = 距离未知 → single 桶）。返回 false=样本被拒收。 */
    public static boolean updated(Table t, double dist, long dSec, double nearTh, double midTh) {
        if (t == null || dSec < MIN_SAMPLE_SEC || dSec > MAX_SAMPLE_SEC) return false;
        Bucket b = t.bucketOf(dist >= 0 ? bandOf(dist, nearTh, midTh) : BAND_UNKNOWN);
        if (b.samples <= 0 || b.dSec <= 0) {
            b.dSec = dSec;
        } else {
            b.dSec = Math.round(b.dSec * (1 - EWMA_ALPHA) + dSec * EWMA_ALPHA);
        }
        b.samples++;
        return true;
    }

    public static String bandName(int band) {
        switch (band) {
            case BAND_NEAR: return "near";
            case BAND_MID: return "mid";
            case BAND_FAR: return "far";
            default: return "unknown";
        }
    }

    // ------------------------------------------------------------------ JSON（FnParams 顶层 "calib"）

    public static JSONObject toJson(Table t) {
        JSONObject o = new JSONObject();
        if (t == null) return o;
        try {
            JSONObject root = new JSONObject();
            for (int b : new int[]{BAND_NEAR, BAND_MID, BAND_FAR, BAND_UNKNOWN}) {
                Bucket bk = t.bucketOf(b);
                if (bk == null || bk.samples <= 0) continue;
                root.put(bandName(b), new JSONObject()
                        .put("d", bk.dSec).put("n", bk.samples));
            }
            o.put("calib", root);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Table fromJson(String raw) {
        Table t = new Table();
        if (raw == null || raw.isEmpty()) return t;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.has("calib")) root = root.optJSONObject("calib");
            if (root == null) return t;
            for (int b : new int[]{BAND_NEAR, BAND_MID, BAND_FAR, BAND_UNKNOWN}) {
                JSONObject o = root.optJSONObject(bandName(b));
                if (o == null) continue;
                Bucket bk = t.bucketOf(b);
                bk.dSec = o.optLong("d", 0L);
                bk.samples = o.optLong("n", 0L);
            }
        } catch (Exception ignored) {
        }
        return t;
    }

    // ------------------------------------------------------------------ Android 胶水（FnParams / 旧文件迁移）

    /** 读取标定表：FnParams 优先；无则尝试旧 guardian_calib.txt（kid 单值 → single 桶）。 */
    public static Table load(Context ctx, String fpid, long sid) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, GuardianEngine.FN_ID);
            JSONObject c = p.optJSONObject("calib");
            if (c != null) return fromJson(c.toString());
        } catch (Throwable ignored) {
        }
        Table t = new Table();
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "guardian_calib.txt");
            if (f.exists()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    String[] p2 = line.trim().split(",");
                    if (p2.length == 2 && String.valueOf(sid).equals(p2[0])) {
                        t.single.dSec = Long.parseLong(p2[1]);
                        t.single.samples = 1L;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    public static void save(Context ctx, String fpid, long sid, Table t) {
        try {
            FnParams.putTopLevel(ctx, fpid, sid, GuardianEngine.FN_ID, "calib",
                    toJson(t).optJSONObject("calib"));
        } catch (Throwable ignored) {
        }
    }

    /** 近距档阈值（格）——FnParams 策略项 dist_near，功能页可选。 */
    public static double nearGrids(Context ctx, String fpid, long sid) {
        return parseGrids(FnParams.strategy(ctx, fpid, sid, GuardianEngine.FN_ID, "dist_near", "20"),
                DEF_NEAR_GRIDS);
    }

    /** 中距档阈值（格）——FnParams 策略项 dist_mid。 */
    public static double midGrids(Context ctx, String fpid, long sid) {
        return parseGrids(FnParams.strategy(ctx, fpid, sid, GuardianEngine.FN_ID, "dist_mid", "60"),
                DEF_MID_GRIDS);
    }

    private static double parseGrids(String v, double def) {
        try {
            double d = Double.parseDouble(v);
            return d > 0 ? d : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 档位 D 一步取值：dist<0（缺坐标）→ single → 默认 38s。 */
    public static long estimateD(Context ctx, String fpid, long sid, double dist) {
        return estimateD(ctx, fpid, sid, load(ctx, fpid, sid), dist);
    }

    public static long estimateD(Context ctx, String fpid, long sid, Table t, double dist) {
        double nearTh = nearGrids(ctx, fpid, sid);
        double midTh = midGrids(ctx, fpid, sid);
        int band = dist >= 0 ? bandOf(dist, nearTh, midTh) : BAND_UNKNOWN;
        return estimate(t, band);
    }

    /** 校准入口：驻防发射成功后记一个实测 D 样本（EWMA 更新 + 落 FnParams）。 */
    public static void recordSample(Context ctx, String fpid, long sid, double dist, long dSec) {
        synchronized (LOCK) {
            try {
                Table t = load(ctx, fpid, sid);
                double nearTh = nearGrids(ctx, fpid, sid);
                double midTh = midGrids(ctx, fpid, sid);
                if (!updated(t, dist, dSec, nearTh, midTh)) {
                    glog(ctx, "标定样本拒收：D=" + dSec + "s 距离=" + dist + "（出界）");
                    return;
                }
                save(ctx, fpid, sid, t);
                Bucket b = t.bucketOf(dist >= 0 ? bandOf(dist, nearTh, midTh) : BAND_UNKNOWN);
                glog(ctx, "📐标定样本入账：距离 " + (dist >= 0 ? String.format(java.util.Locale.CHINA, "%.1f", dist) : "未知")
                        + " 格 → 档 " + bandName(dist >= 0 ? bandOf(dist, nearTh, midTh) : BAND_UNKNOWN)
                        + " 实测D=" + dSec + "s，档均值 " + b.dSec + "s（" + b.samples + " 样）");
            } catch (Throwable ignored) {
            }
        }
    }

    /** 标定表现状描述（日志/状态行用）。 */
    public static String describe(Table t, double dist, double nearTh, double midTh) {
        int band = dist >= 0 ? bandOf(dist, nearTh, midTh) : BAND_UNKNOWN;
        Bucket b = t == null ? null : t.bucketOf(band);
        StringBuilder sb = new StringBuilder("档位 ").append(bandName(band))
                .append(b != null && b.samples > 0
                        ? " D=" + b.dSec + "s(" + b.samples + "样)"
                        : " 无档样本")
                .append("·single ")
                .append(t != null && t.single.samples > 0
                        ? t.single.dSec + "s(" + t.single.samples + "样)"
                        : "无")
                .append("·兜底 ").append(DEFAULT_D_SEC).append("s");
        return sb.toString();
    }

    private static final Object LOCK = new Object();

    private static void glog(Context c, String line) {
        try {
            GuardianLaunch.glog(c, line);
        } catch (Throwable ignored) {
        }
    }
}
