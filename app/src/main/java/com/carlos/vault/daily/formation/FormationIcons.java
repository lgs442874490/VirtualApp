package com.carlos.vault.daily.formation;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.LruCache;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import com.carlos.vault.daily.formation.ui.ManagerUi;

/**
 * 编队英雄头像加载器（T6 app-assets）。
 *
 * <p>素材：{@code assets/hero_icons/{heroId}.webp}（96×96 WebP，由 {@code tools/formation_icons.sh}
 * 从线上网页版静态资源 {@code /hero/{id}.png} 下载缩放生成，构建时打进 APK，运行期<b>零网络</b>）。
 *
 * <p>职责（契约 §4.6）：
 * <ul>
 *   <li>{@link #avatar(Context, long)}：按英雄 id 解码头像，缺失/失败返回 {@code null}；</li>
 *   <li>{@link #placeholder(Context, String)}：头像缺失时的职业色块 + 职业首字占位；</li>
 *   <li>{@link #warmupAsync(Context, Collection)}：后台线程批量预解码，主线程不做 IO。</li>
 * </ul>
 *
 * <p>本类是纯 Android 工具类，不依赖编队模型/UI 任何类型（T1/T4/T5 均可直接调用）。
 * 内存：{@link LruCache} 按 bitmap 字节数计，上限约 {@code min(8MB, maxMemory/16)}；
 * 解码失败与缺失 id 会被记住，避免反复开 asset 流。
 */
public final class FormationIcons {

    /** assets 内头像目录。 */
    private static final String ASSET_DIR = "hero_icons";
    /** 素材边长（与 tools/formation_icons.sh 输出一致）。 */
    private static final int AVATAR_PX = 96;

    /** 职业占位色：盾 #64748B / 矛 #0EA5E9 / 弓 #F59E0B / 未知灰 #94A3B8（与 UI 职业色一致）。 */
    private static final int COLOR_INFANTRY = ManagerUi.PROF_COLOR_INFANTRY;
    private static final int COLOR_LANCER = ManagerUi.PROF_COLOR_LANCER;
    private static final int COLOR_MARKSMAN = ManagerUi.PROF_COLOR_MARKSMAN;
    private static final int COLOR_UNKNOWN = ManagerUi.PROF_COLOR_UNKNOWN;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(cacheSizeBytes()) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    /** 已确认缺失（404/解码失败）的英雄 id，避免重复 IO。 */
    private static final Set<Long> MISSING = Collections.synchronizedSet(new HashSet<Long>());
    /** 已完成预热的英雄 id（重复 warmup 调用去重）。 */
    private static final Set<Long> WARMED = Collections.synchronizedSet(new HashSet<Long>());

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "formation-icons");
            t.setDaemon(true);
            return t;
        }
    });

    private FormationIcons() {
    }

    /**
     * 英雄头像（96×96 WebP）。缺失 / 解码失败 / 非法 id 返回 {@code null}，调用方用
     * {@link #placeholder(Context, String)} 兜底。
     *
     * <p>注意：读 assets 属于 IO，首帧渲染建议先 {@link #warmupAsync(Context, Collection)}。
     */
    public static Drawable avatar(Context ctx, long heroId) {
        if (ctx == null || heroId <= 0L || MISSING.contains(heroId)) {
            return null;
        }
        final String key = "hero:" + heroId;
        Bitmap bmp = CACHE.get(key);
        if (bmp == null) {
            bmp = decodeAvatar(ctx, heroId);
            if (bmp == null) {
                MISSING.add(heroId);
                return null;
            }
            CACHE.put(key, bmp);
        }
        return toDrawable(ctx, bmp);
    }

    /**
     * 职业占位头像：色块 + 首字。用于头像缺失、或英雄数据未同步时。
     *
     * @param professionLabel 职业文案（盾兵/矛兵/弓兵/英雄 等，取首字）；null/空按“英雄”处理
     */
    public static Drawable placeholder(Context ctx, String professionLabel) {
        if (ctx == null) {
            return null;
        }
        final String label = (professionLabel == null || professionLabel.length() == 0)
                ? "英雄" : professionLabel;
        final String key = "ph:" + label;
        Bitmap bmp = CACHE.get(key);
        if (bmp == null) {
            bmp = createPlaceholder(label);
            CACHE.put(key, bmp);
        }
        return toDrawable(ctx, bmp);
    }

    /**
     * 后台预解码（主线程调用安全，立即返回）。已缓存/已缺失的 id 自动跳过。
     *
     * @param ids 英雄 id 集合，null/空安全
     */
    public static void warmupAsync(Context ctx, Collection<Long> ids) {
        if (ctx == null || ids == null || ids.isEmpty()) {
            return;
        }
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final List<Long> todo = new ArrayList<Long>(ids.size());
        for (Long id : ids) {
            if (id == null || id.longValue() <= 0L) {
                continue;
            }
            long v = id.longValue();
            if (MISSING.contains(v) || WARMED.contains(v) || CACHE.get("hero:" + v) != null) {
                continue;
            }
            todo.add(id);
        }
        if (todo.isEmpty()) {
            return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                for (Long id : todo) {
                    long v = id.longValue();
                    if (WARMED.contains(v) || CACHE.get("hero:" + v) != null) {
                        continue;
                    }
                    Bitmap bmp = decodeAvatar(app, v);
                    if (bmp == null) {
                        MISSING.add(v);
                    } else {
                        CACHE.put("hero:" + v, bmp);
                    }
                    WARMED.add(v);
                }
            }
        });
    }

    /** 清空内存缓存（低内存回调 / 主题重建 / 单测用）。 */
    public static void clearCache() {
        CACHE.evictAll();
        WARMED.clear();
    }

    // ---------------------------------------------------------------- internal

    private static Bitmap decodeAvatar(Context ctx, long heroId) {
        String path = ASSET_DIR + "/" + heroId + ".webp";
        InputStream in = null;
        try {
            AssetManager am = ctx.getAssets();
            in = am.open(path);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opt.inScaled = false;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opt);
            if (bmp == null) {
                return null;
            }
            // 让 Drawable 的固有尺寸按 96dp 解释（素材本身 96px），由 View 的尺寸做最终缩放。
            bmp.setDensity(DisplayMetrics.DENSITY_DEFAULT);
            return bmp;
        } catch (IOException e) {
            return null;
        } catch (OutOfMemoryError e) {
            CACHE.evictAll();
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }

    private static Bitmap createPlaceholder(String label) {
        Bitmap bmp = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(colorFor(label));
        float r = AVATAR_PX / 2f;
        canvas.drawCircle(r, r, r, fill);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(AVATAR_PX * 0.44f);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = r - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label.substring(0, 1), r, baseline, text);
        bmp.setDensity(DisplayMetrics.DENSITY_DEFAULT);
        return bmp;
    }

    private static int colorFor(String label) {
        if (label == null || label.length() == 0) {
            return COLOR_UNKNOWN;
        }
        char c = label.charAt(0);
        if (c == '盾') {
            return COLOR_INFANTRY;
        }
        if (c == '矛') {
            return COLOR_LANCER;
        }
        if (c == '弓' || c == '射') {
            return COLOR_MARKSMAN;
        }
        return COLOR_UNKNOWN;
    }

    private static Drawable toDrawable(Context ctx, Bitmap bmp) {
        if (bmp == null) {
            return null;
        }
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static int cacheSizeBytes() {
        long max = Runtime.getRuntime().maxMemory();
        long size = max / 16L;
        if (size < 1L * 1024 * 1024) {
            size = 1L * 1024 * 1024;
        }
        if (size > 8L * 1024 * 1024) {
            size = 8L * 1024 * 1024;
        }
        return (int) size;
    }
}
