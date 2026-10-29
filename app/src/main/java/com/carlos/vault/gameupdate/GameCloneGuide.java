package com.carlos.vault.gameupdate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.Toast;

import com.carlos.vault.VaultEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 「找不到安卓版无尽冬日」的引导弹窗与本地 APK 选择器。
 *
 * <p>场景（2026-10-29 鸿蒙反馈）：鸿蒙手机应用市场装的是<b>鸿蒙版（HAP）</b>，
 * 安卓容器看不到、也无法克隆。此时：
 * <ol>
 *   <li>先说明原因（{@link GameCloneFallback#missingGuideText}），不让用户以为安装包丢了；</li>
 *   <li>给两条可执行出路：SAF 选择本地安卓 APK（免存储权限，最稳）／打开官方下载页；</li>
 *   <li>选中 APK 后走 {@link VaultEngine#installGameFromLocalApk}（校验包名/版本/签名再装入容器）。</li>
 * </ol>
 *
 * <p>Activity 侧只需两行接线：
 * <pre>
 *   GameCloneGuide.showMissingDialog(this, err, engine, this::refresh);
 *   // onActivityResult 里：
 *   if (GameCloneGuide.handlePickResult(this, requestCode, resultCode, data, engine, this::refresh)) return;
 * </pre>
 */
public final class GameCloneGuide {

    /** ACTION_OPEN_DOCUMENT 选择本地 APK 的请求码（与 AccountVaultActivity 4711/4712 不冲突）。 */
    public static final int REQ_PICK_APK = 4211;

    private GameCloneGuide() {
    }

    /** {@code onActivityResult} 是否为本组件的选包请求。 */
    public static boolean isPickRequest(int requestCode) {
        return requestCode == REQ_PICK_APK;
    }

    /**
     * 展示「未找到安卓版游戏」引导弹窗。
     *
     * @param rawError    引擎/流程的原始失败文案（展示在引导之后，便于客服定位）
     * @param onInstalled 本地 APK 装入成功后回调（一般是刷新页面/继续引导）
     */
    public static void showMissingDialog(Activity activity, String rawError,
                                         VaultEngine engine, Runnable onInstalled) {
        if (activity == null || activity.isFinishing()) return;
        boolean huawei = GameCloneFallback.isHuaweiLike(Build.MANUFACTURER, Build.BRAND);
        AlertDialog dlg = new AlertDialog.Builder(activity,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("未找到安卓版「无尽冬日」")
                .setMessage(GameCloneFallback.guideWithDetail(huawei, rawError))
                .setPositiveButton("选择本地 APK", (d, w) -> startPick(activity))
                .setNeutralButton("下载安卓版", (d, w) -> openAndroidDownload(activity))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    /** SAF 选 APK：不需要存储权限，鸿蒙/各 ROM 的文件选择器都能用。 */
    private static void startPick(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/vnd.android.package-archive");
            activity.startActivityForResult(intent, REQ_PICK_APK);
        } catch (Throwable t) {
            Toast.makeText(activity,
                    "当前系统没有可用的文件选择器，请把官方 APK 放进「下载」目录后点「开始克隆」重试",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 官方下载短链（Android 版 APK，浏览器下载；配置里可换成自建源）。 */
    private static void openAndroidDownload(Activity activity) {
        try {
            String url = GameClientUpdateConfig.defaults().shortLink;
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(activity, "无法打开浏览器，请手动搜索「无尽冬日 安卓版 下载」",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 处理选包结果：复制到私有 cache（引擎只认文件路径）→ 校验并装入容器。
     *
     * @return true = 已处理（调用方 return）；false = 不是本组件的请求
     */
    public static boolean handlePickResult(final Activity activity, final int requestCode,
                                           final int resultCode, final Intent data,
                                           final VaultEngine engine, final Runnable onInstalled) {
        if (!isPickRequest(requestCode)) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (activity == null || activity.isFinishing()) return true;
        final Uri uri = data.getData();
        final ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在读取本地安装包…（大包需要一点时间）");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, String>() {
            private File apk;

            @Override
            protected String doInBackground(Void... p) {
                apk = copyToCache(activity, uri);
                if (apk == null) return "读取安装包失败（文件已移动/无权限或不是 APK）";
                return engine.installGameFromLocalApk(apk);
            }

            @Override
            protected void onPostExecute(String err) {
                pd.dismiss();
                if (apk != null) {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                    } catch (Throwable ignored) {
                    }
                }
                if (activity.isFinishing()) return;
                if (err == null) {
                    Toast.makeText(activity, "克隆完成 ✓ 继续点②首启", Toast.LENGTH_LONG).show();
                    if (onInstalled != null) onInstalled.run();
                } else {
                    new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                            .setTitle("本地安装包克隆失败")
                            .setMessage(err)
                            .setPositiveButton("好的", null)
                            .show();
                }
            }
        }.execute();
        return true;
    }

    /** 复制 content URI 指向的 APK 到私有 cache；失败返回 null。 */
    private static File copyToCache(Activity activity, Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = activity.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            File dir = new File(activity.getCacheDir(), "clone_apk");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File target = new File(dir, pickFileName(uri));
            out = new FileOutputStream(target);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return target;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 选包 URI 末段转安全文件名（兜底 clone_input.apk）。 */
    static String pickFileName(Uri uri) {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) return "clone_input.apk";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) name = name + ".apk";
        return name;
    }
}
