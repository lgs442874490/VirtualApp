package com.carlos.vault;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Xml;

import android.net.Uri;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.VAppInstallerParams;
import com.lody.virtual.remote.VAppInstallerResult;

import com.carlos.vault.license.LicenseManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 号库引擎：负责游戏分身的安装/基线/注入/启动。
 * 核心约定：游戏外部资源(Android_va)永不触碰；切号只动内部数据(约12MB)。
 */
public class VaultEngine {

    public static final String GAME_PKG = "com.gof.china";
    public static final String PREF_NAME = "com.cg.sdk";
    public static final String SESSION_KEY = "account_session_cn";

    private final Context ctx;
    /**
     * 宿主账号库：用于读写 "live 目录归属" 与快照指纹。
     * 与 UI 层的实例是同一份 SharedPreferences（account_vault），数据天然共享，无需传递实例。
     */
    private final AccountStore store;

    public VaultEngine(Context ctx) {
        this.ctx = ctx;
        this.store = new AccountStore(ctx);
        probeHiddenApi();
    }

    /**
     * 诊断：Android16 上引擎镜像（MAX-TARGET-O 隐藏字段）疑似被反射过滤绑不上，
     * 导致容器微信 8.0.6x 在 HCallbackStub 崩溃。探明：直接反射是否被拦、
     * setHiddenApiExemptions 解除是否可用、解除后能否反射。
     */
    private void probeHiddenApi() {
        final String CT = "android.app.servertransaction.ClientTransaction";
        try {
            try {
                java.lang.reflect.Field f = Class.forName(CT).getDeclaredField("mLifecycleStateRequest");
                f.setAccessible(true);
                android.util.Log.e("WXPROBE", "direct reflect OK: " + f);
            } catch (Throwable t) {
                android.util.Log.e("WXPROBE", "direct reflect BLOCKED: " + t);
            }
            try {
                Class<?> vmr = Class.forName("dalvik.system.VMRuntime");
                java.lang.reflect.Method get = vmr.getDeclaredMethod("getRuntime");
                get.setAccessible(true);
                Object rt = get.invoke(null);
                java.lang.reflect.Method exh = vmr.getDeclaredMethod("setHiddenApiExemptions", String[].class);
                exh.setAccessible(true);
                Object r = exh.invoke(rt, new Object[]{new String[]{"L"}});
                android.util.Log.e("WXPROBE", "exempt result=" + r);
            } catch (Throwable t) {
                android.util.Log.e("WXPROBE", "exempt failed: " + t);
            }
            try {
                java.lang.reflect.Field f2 = Class.forName(CT).getDeclaredField("mLifecycleStateRequest");
                f2.setAccessible(true);
                android.util.Log.e("WXPROBE", "after-exempt reflect OK: " + f2);
            } catch (Throwable t) {
                android.util.Log.e("WXPROBE", "after-exempt still blocked: " + t);
            }
        } catch (Throwable t) {
            android.util.Log.e("WXPROBE", "probe crash: " + t);
        }
    }

    // ---------- 路径 ----------

    private File hostData() {
        return new File(ctx.getApplicationInfo().dataDir);
    }

    private File usersDir() {
        return new File(hostData(), "virtual/data/user");
    }

    /** 虚拟用户数据目录（按 VA 安装记录优先，扫不到再退回目录探测） */
    public File gameDataDir() {
        int uid = launchUserId();
        if (uid >= 0) {
            File d = new File(usersDir(), uid + "/" + GAME_PKG);
            if (d.exists()) return d;
        }
        File users = usersDir();
        File[] list = users.listFiles();
        if (list != null) {
            for (File u : list) {
                File g = new File(u, GAME_PKG);
                if (g.exists()) return g;
            }
        }
        return new File(users, (uid >= 0 ? uid : 0) + "/" + GAME_PKG);
    }

    public File baselineZip() {
        return new File(ctx.getFilesDir(), "baseline_" + GAME_PKG + ".zip");
    }

    // ---------- 游戏安装/启动 ----------

    public boolean isGameInstalled() {
        try {
            return VirtualCore.get().getInstalledAppInfo(GAME_PKG, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public int launchUserId() {
        try {
            InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(GAME_PKG, 0);
            if (info != null) {
                int[] users = info.getInstalledUsers();
                if (users != null && users.length > 0) return users[0];
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 从系统已装的原版游戏克隆进容器（引用源APK，不复制大文件） */
    public String installGameFromSystem() {
        return cloneFromSystem(GAME_PKG, "无尽冬日");
    }

    /**
     * 克隆系统已装的 App 进容器。
     *
     * 关键约定：必须用 package: URI，不能用 ApplicationInfo.sourceDir 的 file: URI。
     * sourceDir 只指向 base.apk，而现代大型游戏/微信都是 App Bundle 分拆安装的
     * （base.apk + split_config.arm64_v8a.apk + 资源分包）。只装 base.apk 会丢掉
     * so 库与分包资源，表现为"安装返回成功、启动必失败且次次失败"。
     * 交给引擎用 package: URI 自行从系统包管理器解析，才能拿到完整包信息。
     */
    private String cloneFromSystem(String pkg, String label) {
        try {
            PackageManager pm = ctx.getPackageManager();
            pm.getApplicationInfo(pkg, 0);   // 未安装会抛 NameNotFoundException
            String err = ensureEngineReady();
            if (err != null) return err;
            VAppInstallerParams params = new VAppInstallerParams();
            VAppInstallerResult r = VirtualCore.get().installPackage(Uri.parse("package:" + pkg), params);
            if (r == null || r.status != VAppInstallerResult.STATUS_SUCCESS) {
                return r == null ? "安装失败" : ("安装失败: status=" + r.status);
            }
            return null;
        } catch (PackageManager.NameNotFoundException e) {
            return "手机上未找到已安装的" + label;
        } catch (Throwable t) {
            return "安装异常:" + t.getMessage();
        }
    }

    /**
     * 确保 VA 引擎已就绪。引擎启动是异步的，冷启动后立刻调用 VA 接口会失败
     * （旧入口 splashActivity 在跳主界面前会 waitForEngine，新入口没有这一步）。
     */
    public String ensureEngineReady() {
        try {
            VirtualCore vc = VirtualCore.get();
            if (!vc.isEngineLaunched()) {
                vc.waitForEngine();
            }
            return vc.isEngineLaunched() ? null : "引擎未就绪，请重启App后重试";
        } catch (Throwable t) {
            return "引擎启动异常:" + t.getMessage();
        }
    }

    public void killGame() {
        GameKeepAliveService.stop(ctx);   // 明确清理前先停保活，避免阻碍切号/保存
        try {
            VirtualCore.get().killAllApps();
        } catch (Throwable ignored) {
        }
    }

    /** 容器游戏进程是否还活着（它跑在本App的UID下，可直接枚举） */
    public boolean isContainerGameAlive() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (android.app.ActivityManager.RunningAppProcessInfo pi : ps) {
                if (pi.processName != null && pi.processName.startsWith(GAME_PKG)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 杀游戏并等待其进程真正退出（最多 timeoutMs 毫秒） */
    public boolean killGameAndWait(long timeoutMs) {
        killGame();
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (!isContainerGameAlive()) return true;
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !isContainerGameAlive();
    }

    private volatile String lastLaunchError = null;

    /** 最近一次启动失败的原因（供 UI 提示与排查，避免只看到一个"启动失败"） */
    public String getLastLaunchError() {
        return lastLaunchError;
    }

    /**
     * 启动容器内的游戏。
     *
     * 不走 VActivityManager.launchApp()：该 API 在解析启动 Intent 之前有两道服务端渠道
     * 配置闸门（channelLimit / channelStatus），配置只能由 VNetworkManagerService 联网
     * 下发写入，未落地时恒为 0，导致 `0 > 0` 不成立而在字节码偏移 58 直接 return false，
     * 与 userId、安装状态全都无关（详见 .workbuddy/artifacts/launch-false-rootcause.md）。
     *
     * 改用 VirtualCore.getLaunchIntent() + VActivityManager.startActivity()：两者都是
     * AAR public API，解析逻辑与 launchApp 后半段等价（同一批 StringFog 常量、同一套
     * category 回退、同样带 FLAG_ACTIVITY_NEW_TASK + setClassName），但不经过那两道闸门。
     */
    public boolean launchGame() {
        lastLaunchError = null;
        // —— 授权硬闸门（纯本地、无网络、不阻塞）——
        // gateForLaunch 只读内存状态 + 本地时钟判定，绝不联网：本方法会在 UI 线程
        // （AccountVaultActivity.onWizardStep / doSwitch 的 onPostExecute）被调用。
        // 失败原因复用既有 lastLaunchError 通道传给 UI，不新增错误传递路径。
        String licReason = LicenseManager.gateForLaunch(ctx);
        if (licReason != null) {
            lastLaunchError = licReason;
            return false;
        }
        String err = ensureEngineReady();
        if (err != null) {
            lastLaunchError = err;
            return false;
        }
        int[] uids = candidateUserIds();
        Throwable last = null;
        boolean resolved = false;   // 区分"解析不到入口"与"解析到了但拉起失败"
        for (int uid : uids) {
            try {
                Intent launch = VirtualCore.get().getLaunchIntent(GAME_PKG, uid);
                if (launch == null) continue;   // 该 userId 下解析不到入口，换下一个试
                resolved = true;
                VActivityManager.get().startActivity(launch, uid);
                // 延迟拉起保活前台服务：服务已移回宿主主进程（见 GameKeepAliveService
                // 与 manifest 注释），不再受游戏主线程繁忙影响，这里的延迟只是为了
                // 不在游戏启动瞬间抢资源。
                scheduleKeepAlive();
                return true;
            } catch (Throwable t) {
                last = t;
            }
        }
        InstalledAppInfo info = null;
        try {
            info = VirtualCore.get().getInstalledAppInfo(GAME_PKG, 0);
        } catch (Throwable ignored) {
        }
        if (info == null) {
            lastLaunchError = "容器里没有该游戏，请先完成①克隆";
        } else {
            String tried = "已试 userId=" + formatUserIds(uids);
            if (!resolved) {
                lastLaunchError = "解析不到启动入口（" + tried + "），请确认已完成①克隆";
            } else {
                lastLaunchError = "已解析到入口但拉起失败（" + tried + "）"
                        + (last != null ? "，异常:" + last.getMessage() : "");
            }
        }
        return false;
    }

    /** 把候选 userId 数组格式化成 "0,1,2"，供失败文案使用 */
    private static String formatUserIds(int[] uids) {
        StringBuilder sb = new StringBuilder();
        for (int u : uids) {
            if (sb.length() > 0) sb.append(",");
            sb.append(u);
        }
        return sb.toString();
    }

    /**
     * 延迟 3 秒后启动游戏进程保活服务。
     * 历史原因：服务早先注册在与游戏同进程的 :p0 上，必须等游戏主线程空闲才敢
     * startForegroundService，否则 5 秒内 onStartCommand 排不上队会被系统超时杀掉。
     * 现在服务已移回宿主主进程（宿主主线程此时是空闲的），该前提不再成立；
     * 保留延迟只是为了不在游戏启动瞬间抢资源，延迟本身无害。
     */
    private void scheduleKeepAlive() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    GameKeepAliveService.start(ctx);
                } catch (Throwable ignored) {
                }
            }, 3000);
        } catch (Throwable ignored) {
        }
    }

    // ---------- 容器微信（旧版支付试验） ----------

    public static final String WECHAT_PKG = "com.tencent.mm";
    /** 兼容保留：旧版微信包位置 */
    public static final String WECHAT_APK_PATH = "/sdcard/Download/wechat_8053.apk";
    /** 新版微信包：微信服务器会拒绝过老版本登录（提示"版本太低"），放这里用于自动升级 */
    public static final String WECHAT_APK_NEW = "/sdcard/Download/wechat_8079.apk";

    /** 微信是否已装入容器 */
    public boolean isWechatInstalled() {
        try {
            return VirtualCore.get().getInstalledAppInfo(WECHAT_PKG, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 容器内微信的 versionCode；未安装返回 -1 */
    public long installedWechatVersionCode() {
        try {
            InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(WECHAT_PKG, 0);
            if (info == null) return -1;
            android.content.pm.PackageInfo pi = info.getPackageInfo(0);
            return pi == null ? -1 : pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 确保容器微信用上手机下载目录里能找到的最新安装包：
     * wechat_8079.apk（新）优先，其次 wechat_8053.apk（旧，兼容）。
     * 包比容器内版本新（或未安装）时自动 卸旧→装新。
     * 返回 null 表示无需变更或已就绪；否则为给用户看的错误说明。
     */
    public String ensureWechatFromApk() {
        try {
            File apk = bestWechatApk();
            if (apk == null || !apk.exists()) {
                return isWechatInstalled() ? null
                        : "未找到微信APK：请把 wechat_8079.apk 放到手机 下载/ 目录";
            }
            android.content.pm.PackageInfo fpi =
                    ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (fpi == null) return "微信APK读取失败：" + apk.getName();
            long installed = installedWechatVersionCode();
            if (installed == fpi.versionCode) return null;
            if (installed >= 0) {
                // 旧版容器微信没有可保留的登录态，直接卸掉换新
                try { VirtualCore.get().uninstallPackage(WECHAT_PKG); } catch (Throwable ignored) { }
            }
            VAppInstallerParams params = new VAppInstallerParams();
            VAppInstallerResult r = VirtualCore.get().installPackage(Uri.fromFile(apk), params);
            if (r == null || r.status != VAppInstallerResult.STATUS_SUCCESS) {
                return "微信安装失败 status=" + (r == null ? "?" : r.status);
            }
            android.util.Log.i("WXUP", "容器微信已换装 v" + fpi.versionName
                    + " (code=" + fpi.versionCode + ") <- " + apk.getName());
            return null;
        } catch (Throwable t) {
            return "微信换装异常:" + t.getMessage();
        }
    }

    /**
     * 保数据降级：不卸载直接覆盖安装 8.0.53。
     * 背景：8.0.67 的支付界面是 LiteApp 组件，其基础库在容器内无法自举；
     * 8.0.53 的收银台是原生的，不依赖 LiteApp。登录态（数据库/会话）跨版本保留。
     */
    public String downgradeWechatKeepData() {
        try {
            File apk = new File(WECHAT_APK_PATH); // wechat_8053.apk
            if (!apk.exists()) return "未找到 wechat_8053.apk";
            android.content.pm.PackageInfo fpi =
                    ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (fpi == null) return "8053 APK 读取失败";
            long installed = installedWechatVersionCode();
            if (installed == fpi.versionCode) return null;
            VAppInstallerParams params = new VAppInstallerParams();
            params.setMode(VAppInstallerParams.MODE_INHERIT_EXISTING);
            params.addInstallFlags(VAppInstallerParams.FLAG_INSTALL_OVERRIDE_NO_CHECK
                    | VAppInstallerParams.FLAG_INSTALL_OVERRIDE_SKIP_ODEX);
            VAppInstallerResult r = VirtualCore.get().installPackage(Uri.fromFile(apk), params);
            if (r == null || r.status != VAppInstallerResult.STATUS_SUCCESS) {
                return "保数据降级失败 status=" + (r == null ? "?" : r.status);
            }
            android.util.Log.i("WXUP", "保数据降级到 v" + fpi.versionName
                    + " (code=" + fpi.versionCode + ")，登录数据保留");
            return null;
        } catch (Throwable t) {
            return "保数据降级异常:" + t.getMessage();
        }
    }

    /** 在两个已知位置里挑 versionCode 最大的可读微信包 */
    private File bestWechatApk() {
        File newer = new File(WECHAT_APK_NEW);
        File older = new File(WECHAT_APK_PATH);
        long vcNewer = apkVersionCode(newer);
        long vcOlder = apkVersionCode(older);
        if (vcNewer < 0 && vcOlder < 0) return null;
        return vcNewer >= vcOlder ? newer : older;
    }

    private long apkVersionCode(File f) {
        try {
            if (f == null || !f.exists()) return -1;
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            return pi == null ? -1 : pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 从本地 APK 把微信装入容器。
     * 试验背景：引擎（2024.10 预编译）跑不动 2026 新版微信（登录页崩溃），
     * 改用引擎同期的 8.0.53（2024.10 发布，签名已校验与官方一致）。
     */
    public String installWechatFromApk(String apkPath) {
        try {
            File apk = new File(apkPath);
            if (!apk.exists()) return "未找到 APK，请先把微信包放到手机 下载/wechat_8053.apk";
            VAppInstallerParams params = new VAppInstallerParams();
            VAppInstallerResult r = VirtualCore.get().installPackage(Uri.fromFile(apk), params);
            if (r == null || r.status != VAppInstallerResult.STATUS_SUCCESS) {
                return r == null ? "安装失败" : ("安装失败: status=" + r.status);
            }
            return null;
        } catch (Throwable t) {
            return "安装异常:" + t.getMessage();
        }
    }

    /** 启动容器内微信 */
    public boolean launchWechat() {
        diagnoseWechatRegistry();
        int[] uids = candidateUserIds();
        for (int uid : uids) {
            try {
                Intent launch = VirtualCore.get().getLaunchIntent(WECHAT_PKG, uid);
                if (launch == null) continue;
                VActivityManager.get().startActivity(launch, uid);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 一次性诊断：确认 VA 虚拟注册表里微信 WelcomeActivity 的可见性。
     * 背景：容器微信跳登录页时 VA resolve 不到该组件，转而透传真系统被拒（SecurityException）。
     * 此日志用于区分：注册表缺失 vs userId 错位。
     */
    private void diagnoseWechatRegistry() {
        try {
        } catch (Throwable ignored) {
        }
        try {
            com.lody.virtual.remote.InstalledAppInfo info =
                    VirtualCore.get().getInstalledAppInfo(WECHAT_PKG, 0);
            StringBuilder sb = new StringBuilder("WXDIAG installed=" + (info != null));
            if (info != null) {
                int[] users = info.getInstalledUsers();
                sb.append(" users=");
                if (users == null) sb.append("null");
                else for (int u : users) sb.append(u).append(',');
                for (int u : (users == null ? new int[]{0} : users)) {
                    android.content.Intent probe = new android.content.Intent();
                    probe.setClassName(WECHAT_PKG, WECHAT_PKG + ".plugin.account.ui.WelcomeActivity");
                    android.content.pm.ActivityInfo ai = VirtualCore.get().resolveActivityInfo(probe, u);
                    sb.append(" [u").append(u).append(" welcome=").append(ai != null).append(']');
                }
            }
            android.util.Log.e("WXDIAG", sb.toString());
        } catch (Throwable t) {
            android.util.Log.e("WXDIAG", "diag-fail", t);
        }
    }

    /** 候选虚拟用户号：VA登记的 + 常见 0/1/2 兜底，去掉重复 */
    private int[] candidateUserIds() {
        int[] registered = new int[0];
        try {
            InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(GAME_PKG, 0);
            if (info != null && info.getInstalledUsers() != null) registered = info.getInstalledUsers();
        } catch (Throwable ignored) {
        }
        int[] fallback = {0, 1, 2};
        int[] merged = new int[registered.length + fallback.length];
        int n = 0;
        for (int u : registered) merged[n++] = u;
        outer:
        for (int u : fallback) {
            for (int r : registered) if (r == u) continue outer;
            merged[n++] = u;
        }
        int[] out = new int[n];
        System.arraycopy(merged, 0, out, 0, n);
        return out;
    }

    // ---------- 数据目录操作 ----------

    /** 清空游戏内部数据（保留外部资源，不触发重新下载） */
    public void wipeGameInternal() {
        File users = usersDir();
        File[] list = users.listFiles();
        if (list != null) {
            for (File u : list) {
                deleteRecursively(new File(u, GAME_PKG));
            }
        }
        // live 已被清空 ⇒ 它不再属于任何入口。
        // 必须写在**这个方法内部**：restoreBaseline() 会先调本方法再解压，launchEntry 的
        // wipe 分支、向导的③重置都走这里。漏掉任何一条路径，归属标记就会继续指向一个已经被
        // 删空的目录，下次冷启动将误判为"live 就是本入口的"从而跳过还原 ⇒ 游戏起不来。
        store.setLiveEntryId(null);
    }

    /** live 目录当前是否存在且有内容（判断"能不能原样保留"） */
    public boolean hasLiveContent() {
        File dir = gameDataDir();
        if (!dir.exists() || !dir.isDirectory()) return false;
        File[] l = dir.listFiles();
        return l != null && l.length > 0;
    }

    /** 把当前游戏数据保存为基线（应在"登录界面、初始化完成"状态下执行） */
    public boolean saveBaseline() throws IOException {
        File dir = gameDataDir();
        if (!dir.exists() || dir.listFiles() == null || dir.listFiles().length == 0) return false;
        File out = baselineZip();
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        zipDir(dir, tmp);
        if (out.exists()) out.delete();
        return tmp.renameTo(out);
    }

    public boolean hasBaseline() {
        return baselineZip().exists() && baselineZip().length() > 1024;
    }

    /** 恢复基线：清空现有数据后解压基线 */
    public void restoreBaseline() throws IOException {
        wipeGameInternal();
        File dir = gameDataDir();
        unzip(baselineZip(), dir);
    }

    // ---------- token 提取 ----------

    /** 当前游戏会话中提取到的账号凭据。token 等同登录凭证，只用于用户主动复制/迁移。 */
    public static class SessionToken {
        public final String fpid;
        public final String token;

        public SessionToken(String fpid, String token) {
            this.fpid = fpid;
            this.token = token;
        }

        public String asLine() {
            return fpid + "----" + token;
        }
    }

    /**
     * 从当前容器游戏会话文件读取 fpid/session_key。
     * 只读，不修改游戏目录；调用方应在游戏退出后调用，避免读到尚未落盘的数据。
     */
    public SessionToken readCurrentSession() throws Exception {
        File sessionFile = new File(new File(gameDataDir(), "shared_prefs"), PREF_NAME + ".xml");
        if (!sessionFile.exists()) throw new IOException("当前游戏没有会话文件");
        String xml;
        try (InputStream in = new FileInputStream(sessionFile)) {
            byte[] bytes = new byte[(int) sessionFile.length()];
            int n = in.read(bytes);
            if (n <= 0) throw new IOException("会话文件为空");
            xml = new String(bytes, 0, n, Charset.forName("UTF-8"));
        }

        String jsonText = null;
        org.xmlpull.v1.XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new java.io.StringReader(xml));
        for (int event = parser.getEventType(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
             event = parser.next()) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG
                    && "string".equals(parser.getName())
                    && SESSION_KEY.equals(parser.getAttributeValue(null, "name"))) {
                jsonText = parser.nextText();
                break;
            }
        }
        if (jsonText == null || jsonText.trim().isEmpty()) {
            throw new IOException("当前没有已登录账号");
        }
        org.json.JSONObject session;
        try {
            session = new org.json.JSONObject(jsonText);
        } catch (Throwable e) {
            throw new IOException("登录会话格式无法识别");
        }
        String fpid = session.optString("fpid", "").trim();
        String token = session.optString("session_key", "").trim();
        if (!fpid.matches("[0-9]+")) throw new IOException("会话中的号ID无效");
        if (token.isEmpty()) throw new IOException("会话中没有 token");
        return new SessionToken(fpid, token);
    }

    /**
     * 先等待游戏完全退出，再读取当前会话。
     * 读取过程只读，不会覆盖账号库和快照。
     */
    public SessionToken extractCurrentSession() throws Exception {
        if (!killGameAndWait(10000)) throw new IOException("游戏仍在运行，无法安全读取");
        return readCurrentSession();
    }

    // ---------- token 注入 ----------

    /** 向游戏 prefs 写入账号会话（XML 转义后合并/新建）；手机号/身份证按脱敏空串处理。 */
    public boolean injectSession(String fpid, String token) throws IOException {
        return injectSession(fpid, token, "", "");
    }

    /**
     * 向游戏 prefs 写入账号会话（XML 转义后合并/新建）。
     *
     * <p><b>#70 F2：字段必须与游戏 SDK 的真实会话完全一致</b>（此前只写 9 个字段，且
     * {@code account_type} 写成小写 {@code "mobile"}）。真机可用样本（可登录账号）
     * {@code .workbuddy/artifacts/qa-loginfail/xml_snap_80049204.xml} 的
     * {@code account_session_cn} 共 <b>11 个字段</b>，顺序为：
     * <pre>
     *   fpid, session_key, is_new, expire_on, account_type, session_expire_in,
     *   created_ts, is_verify_id_card, mobile, id_card, offline_time
     * </pre>
     * 差异修正：{@code account_type} ⇒ {@code "Mobile"}（<b>大写 M</b>）；
     * 补齐 {@code mobile}、{@code id_card}（真实样本里这两项是<b>脱敏值</b>
     * {@code "133****7371"} / {@code "13023019961128****"}，我们没有真实值时按空串写，
     * 先保证字段集与字段名对齐 —— 字段缺失比值为空更容易被 SDK 判为"会话不完整"而清除）。
     *
     * <p>旁证：09-26 18:0x 实测注入后 90s 内 {@code account_session_cn} 被清空 ⇒ 合成会话
     * 没被 SDK 接受。字段对齐是第一个要排除的变量。
     *
     * @param fpid   号ID
     * @param token  会话 token（{@code session_key}）
     * @param mobile 手机号（脱敏值亦可）；无真实值传 {@code ""}
     * @param idCard 实名身份证（脱敏值亦可）；无真实值传 {@code ""}
     */
    public boolean injectSession(String fpid, String token, String mobile, String idCard)
            throws IOException {
        File prefsDir = new File(gameDataDir(), "shared_prefs");
        if (!prefsDir.exists() && !prefsDir.mkdirs()) return false;
        File f = new File(prefsDir, PREF_NAME + ".xml");
        String m = mobile == null ? "" : mobile;
        String ic = idCard == null ? "" : idCard;
        String sess = "{\"fpid\":\"" + fpid + "\",\"session_key\":\"" + token
                + "\",\"is_new\":false,\"expire_on\":9999999999999,\"account_type\":\"Mobile\""
                + ",\"session_expire_in\":31536000000,\"created_ts\":" + System.currentTimeMillis()
                + ",\"is_verify_id_card\":true,\"mobile\":\"" + m + "\",\"id_card\":\"" + ic
                + "\",\"offline_time\":0}";
        String sessXml = sess.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String entry = "    <string name=\"" + SESSION_KEY + "\">" + sessXml + "</string>\n";
        String content;
        if (f.exists()) {
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream fi = new FileInputStream(f)) {
                int n = fi.read(buf);
                content = new String(buf, 0, n, Charset.forName("UTF-8"));
            }
            if (content.contains("\"" + SESSION_KEY + "\"")) {
                content = content.replaceAll(
                        "<string name=\"" + SESSION_KEY + "\">[\\s\\S]*?</string>",
                        "<string name=\"" + SESSION_KEY + "\">" + sessXml + "</string>");
            } else {
                content = content.replace("</map>", entry + "</map>");
            }
        } else {
            content = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + entry + "</map>\n";
        }
        // 原子写：先落到同目录的临时文件并 fsync，再 rename 覆盖目标。
        // 旧的直接 FileOutputStream 覆盖，写到一半被杀会留下截断的 XML ⇒ 游戏读不到会话。
        // 目标在宿主自己的 dataDir 下、rename 由宿主进程直接发起，不经 VA 的 guest IO hook，
        // 因此不受 libvbox 上 rename 相关问题的影响。
        File tmp = new File(prefsDir, PREF_NAME + ".xml.vtmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(content.getBytes(Charset.forName("UTF-8")));
            os.flush();
            syncDescriptor(os);
        }
        if (f.exists() && !f.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return true;
    }

    // ---------- 完整切号流程 ----------

    /** 切号：杀游戏 → 恢复基线 → 注入token → 启动（token 为空 = 原生登录模式） */
    public boolean switchAccount(AccountStore.Account acc) {
        try {
            killGame();
            Thread.sleep(900);
            if (hasBaseline()) restoreBaseline();
            else wipeGameInternal();
            if (acc != null && acc.fpid != null && acc.token != null
                    && !acc.fpid.isEmpty() && !acc.token.isEmpty()) {
                injectSession(acc.fpid, acc.token);
            }
            // live 现在的内容属于该入口；acc == null（原生登录）用的是干净基线，不归属任何入口。
            store.setLiveEntryId(acc == null ? null : acc.id);
            Thread.sleep(200);
            return launchGame();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 原生登录模式：恢复干净基线后直接启动（游戏自带手机号/抖音等登录方式） */
    public boolean launchNativeLogin() {
        return switchAccount(null);
    }

    /** 入口快照文件（原生登录后保存该入口的登录状态） */
    public File entrySnapshot(String entryId) {
        return new File(ctx.getFilesDir(), "snap_" + entryId + ".zip");
    }

    public boolean hasEntrySnapshot(String entryId) {
        File f = entrySnapshot(entryId);
        return f.exists() && f.length() > 1024;
    }

    /** 把当前游戏数据保存为某入口的快照（调用前应已 killGame） */
    public boolean saveEntrySnapshot(String entryId) throws IOException {
        File dir = gameDataDir();
        if (!dir.exists() || dir.listFiles() == null || dir.listFiles().length == 0) return false;
        // 先取指纹（压缩过程中内容可能还在变，以压缩前的为准；宁可多存一次也不要漏存）
        String fp = liveFingerprint();
        File out = entrySnapshot(entryId);
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        zipDir(dir, tmp);
        if (out.exists()) out.delete();
        boolean ok = tmp.renameTo(out);
        if (ok) store.setSnapshotFingerprint(entryId, fp);
        return ok;
    }

    /**
     * 自动存档：只在 live 内容相对上次快照**确实有变化**时才真正写 zip。
     *
     * 直接的动机：入口可能会频繁被打开/退出，每次无脑重写一遍约 9MB 的 zip 既慢又伤存储。
     * 用轻量指纹判断有无变化即可，指纹只有"文件数 / 总字节数 / 最新 mtime"，不读文件内容。
     *
     * @return true 表示写了新快照；false 表示判定为无变化（**没有**写）
     */
    public boolean autoSaveEntrySnapshot(String entryId) throws IOException {
        if (!hasLiveContent()) return false;
        // 【新增】写 zip 之前先把「入口 ↔ 会话」归属校准（用户语义：同一入口内切换登录，以最新登录为准）。
        // 位置刻意在**指纹判定之前**：否则"内容没变"时会提前 return，归属同步会被一起跳过。
        // 同步失败/读不到会话都静默跳过 —— 绝不因为它中断存档（返回值不参与下面的流程判定）。
        syncSessionOwner(entryId);
        String fp = liveFingerprint();
        if (fp != null && fp.equals(store.getSnapshotFingerprint(entryId)) && hasEntrySnapshot(entryId)) {
            return false;
        }
        return saveEntrySnapshot(entryId);
    }

    /**
     * 读 live 目录里「**实际登录**的号ID（fpid）」。只读：不写文件、**不杀游戏进程**、不动归属标记。
     *
     * <p>读的就是 {@link #injectSession} 写的同一个文件（{@code <gameDataDir>/shared_prefs/<PREF_NAME>.xml}），
     * 解析交给纯 Java 的 {@link SessionProbe#parseSessionFpid}（与快照读侧同一套判定口径，
     * 可在宿主机 javac 干跑验证）。
     *
     * <p>任何失败（文件不存在 / 空 / 超大 / 未登录 / 解析异常）一律返回 {@code ""} ⇒ 调用方静默跳过同步。
     */
    public String readLiveSessionFpid() {
        // 进程存活前置检查：session 文件是持久登录态，游戏退出后仍留在盘上——只看文件
        // 会把"上次在容器里玩过的号"永久误判成"游戏在线"，拦掉它的同步/补跑/角色补全
        // （真机：94450730 在容器登录一次后，后续所有 syncOnly 恒被"游戏在线·跳过"拦截，
        // 角色表永远空）。容器游戏跑在宿主 uid 下，ActivityManager 只返回自身 uid 的进程，
        // 匹配到 com.gof.china 即为存活。
        if (!isContainerGameRunning()) return "";
        try {
            File f = new File(new File(gameDataDir(), "shared_prefs"), PREF_NAME + ".xml");
            if (!f.isFile()) return "";
            long len = f.length();
            if (len <= 0 || len > 1024L * 1024L) return "";
            byte[] buf = new byte[(int) len];
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                int n = in.read(buf);
                if (n <= 0) return "";
                if (n < buf.length) buf = java.util.Arrays.copyOf(buf, n);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return SessionProbe.parseSessionFpid(new String(buf, Charset.forName("UTF-8")), SESSION_KEY);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 容器游戏进程是否存活（顶号保护的"游戏正在跑"判定）。
     *
     * <p>容器（VA）里的游戏进程跑在宿主 uid 下、进程名即游戏包名。Android 11+ 的
     * 进程检测用 /proc 扫描（5s 缓存）：ActivityManager#getRunningAppProcesses 在
     * Android 11+ 只返回调用方包名自己的进程，VA 容器里的游戏进程（同 uid 但包名为
     * GAME_PKG）看不到 → 护栏恒失效 → keeper 被顶后立即重赛，把正在玩游戏的人反复顶下线
     * （真机 2026-10-12 02:23-02:37 实证：80971192 每几分钟一双角色竞速登录）。
     * /proc/<pid>/cmdline 对任意 uid 可读（ps 同源），同时覆盖容器与原生两种玩法。
     */
    public boolean isContainerGameRunning() {
        long now = System.currentTimeMillis();
        synchronized (PROC_SCAN_LOCK) {
            if (now - procScanAt < 5000) return procScanCached;
        }
        boolean running = false;
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    String name = d.getName();
                    boolean numeric = !name.isEmpty();
                    for (int i = 0; i < name.length() && numeric; i++) {
                        if (!Character.isDigit(name.charAt(i))) numeric = false;
                    }
                    if (!numeric) continue;
                    String cmd = readCmdline(name);
                    if (GAME_PKG.equals(cmd)) {
                        running = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        synchronized (PROC_SCAN_LOCK) {
            procScanAt = now;
            procScanCached = running;
        }
        return running;
    }

    private static final Object PROC_SCAN_LOCK = new Object();
    private static volatile long procScanAt;
    private static volatile boolean procScanCached;

    /** 读 /proc/<pid>/cmdline（去尾部 \0；不可读返回 ""）。 */
    private static String readCmdline(String pid) {
        try {
            byte[] buf = new byte[128];
            int n;
            java.io.FileInputStream in = new java.io.FileInputStream(
                    "/proc/" + pid + "/cmdline");
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            if (n <= 0) return "";
            int end = n;
            while (end > 0 && buf[end - 1] == 0) end--;
            return new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 会话 ↔ 入口 归属同步：live 里实际登录的号 与 入口记录的 {@code fpid} 不一致时，
     * **以最新登录为准**校准入口（同时作废旧 token，见
     * {@link AccountStore#updateFpidAndClearToken}）。修复真机上「A 入口快照里存着 B 账号会话」
     * （鲍哥888 / D-4，{@code login-failure-diagnosis.md} §13）。
     *
     * <p><b>只在"确实登录了"时才同步</b>：未登录 / 读不到会话 ⇒ 直接跳过，绝不把入口改成登出态残留值。
     *
     * <p><b>不碰 id / label / lastLogin</b>：理由见 {@link AccountStore#updateFpidAndClearToken} 的注释。
     *
     * @return true 表示确实校准了入口；false = 无需同步 / 读不到 / 未登录（调用方无需区分）
     */
    public boolean syncSessionOwner(String entryId) {
        if (entryId == null || entryId.isEmpty()) return false;
        String live = readLiveSessionFpid();
        if (live.isEmpty()) return false;              // 未登录 / 读不到 ⇒ 静默跳过
        AccountStore.Account target = null;
        for (AccountStore.Account a : store.all()) {
            if (a != null && entryId.equals(a.id)) {
                target = a;
                break;
            }
        }
        if (target == null) return false;
        String cur = target.fpid == null ? "" : target.fpid;
        if (live.equals(cur)) return false;            // 已一致 ⇒ 不写盘
        return store.updateFpidAndClearToken(entryId, live);
    }

    /**
     * live 目录的轻量指纹：文件总数 + 总字节数 + 最新修改时间。
     * 用于判断"和上次快照比有没有变化"，不是内容哈希，碰撞概率足够低且代价极低。
     */
    public String liveFingerprint() {
        File dir = gameDataDir();
        File[] top = dir.listFiles();
        if (top == null) return null;
        long count = 0L;
        long size = 0L;
        long newest = 0L;
        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
        for (File f : top) queue.add(f);
        while (!queue.isEmpty()) {
            File f = queue.poll();
            if (f == null) continue;
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) queue.add(k);
            } else {
                count++;
                size += f.length();
                long m = f.lastModified();
                if (m > newest) newest = m;
            }
        }
        return count + ":" + size + ":" + newest;
    }

    /**
     * 启动一个入口（v0.5 会话保持模型）。
     *
     * 【为什么要改】v0.4 在这里**无条件** wipeGameInternal()，且只在"切到别的入口"时才自动
     * 存档（条件是 autoSaveToId != acc.id）。二者叠加的结果：**重开同一个入口必然丢登录态**
     * —— 既不存档（本次会话的新登录从没进过任何快照），又把 live 连同刚写进去的会话一起删掉，
     * 最后因为该入口没有快照而回退到干净基线，游戏只能要求重新验证码登录。
     * 详见 .workbuddy/artifacts/login-state-persistence-analysis.md §0 / §2 Q2。
     *
     * 【v0.5 的三条规则】
     *  1. (R1) live 目录有归属记录、且就属于本次要启动的入口 ⇒ **原样保留**：不 wipe、不还原。
     *     游戏自己写进 in-game 数据里的会话因而天然保持 —— 这是修复的主力，它不依赖任何
     *     生命周期回调，划掉、force-stop、掉电都打不破（因为根本没删）。
     *  2. (R2) 离开会话前把 live 按**真实归属**存回快照，不再以"是否切号"为条件。
     *  3. (R4) 本方法**不判断 token/session 是否过期**。会话有效性由游戏向服务端校验，
     *     过期时游戏自然要求重新验证码登录。这里没有任何"跳过 / 伪造 / 延长会话"的逻辑。
     */
    public boolean launchEntry(String autoSaveToId, AccountStore.Account acc) {
        try {
            // 【#72 F2】纵深防御：**一律按 id 重新读最新账户**，不要用调用方传进来的对象。
            // 管理页的 acc 只在 onCreate 取过一次（改完 token 就陈旧）⇒ useToken 判空 ⇒ 不注入。
            // 有了这一步，任何调用方（主页 / 管理页 / 将来的入口）传陈旧对象都不会再漏注入。
            // 读不到（例如新入口尚未落盘）时退回调用方传入的对象，不改变既有行为。
            AccountStore.Account fresh = null;
            try {
                fresh = store.byId(acc.id);
            } catch (Throwable ignored) {
            }
            if (fresh != null) acc = fresh;

            // 服务器模式：进游戏前通知 vault-server 让位（30 分钟探测回连，期间不抢号）
            if (acc.fpid != null && !acc.fpid.isEmpty()) {
                try {
                    VaultSync.yieldForGame(ctx, acc.fpid, 30);
                } catch (Throwable ignored) {
                }
            }

            if (!killGameAndWait(10000)) return false;

            String liveOwner = store.getLiveEntryId();

            // —— R2：结算上一个会话。先看真实归属，归属未知才退回旧的 autoSaveToId ——
            // 关键约束：**绝不能把当前 live 存进一个"待会儿要从它自己的快照还原出来的入口"**，
            // 否则就是用别人的数据覆盖掉人家的好快照。v0.4 的 !equals(acc.id) 就是在防这个，
            // 但它顺手把"重开同一入口"也一刀切废了。改成按归属判断后两种情形都能正确处理。
            String saveTarget = null;
            if (liveOwner != null && !liveOwner.isEmpty()) {
                saveTarget = liveOwner;              // 归属明确：永远是安全的
            } else if (autoSaveToId != null && !autoSaveToId.isEmpty()
                    && !autoSaveToId.equals(acc.id)) {
                saveTarget = autoSaveToId;           // 归属未知：沿用旧的"切号才存"语义
            }
            if (saveTarget != null) {
                try {
                    autoSaveEntrySnapshot(saveTarget);
                } catch (Throwable ignored) {
                    // 存档失败不能阻断本次启动：R1 的保留路径不依赖快照，照样能保持登录。
                }
            }

            // —— R1：live 归属本入口且内容还在 ⇒ 保留，不 wipe、不还原 ——
            boolean keepLive = acc.id != null && !acc.id.isEmpty()
                    && acc.id.equals(liveOwner) && hasLiveContent();

            // 【新增】keepLive 正是「在某入口内换号登录后、重开同一入口」会走的分支：
            // 此时 live 里的会话就是**最新登录**，必须把入口 fpid 同步过来，否则下次
            // SessionProbe 判快照无效、又回退成旧号（QA 称之为"放大器"）。
            // 时序按要求：keepLive 成立 → 同步 → （下面）setLiveEntryId(acc.id)。
            if (keepLive) {
                syncSessionOwner(acc.id);
            }

            if (!keepLive) {
                // 走既有还原链（wipe → 有快照还原快照 → 无快照回退基线/注入 token）。
                // wipeGameInternal() 内部会把归属标记清空，这里不用再管。
                wipeGameInternal();
                boolean restored = false;
                // 【修复】快照必须先过"会话有效性"判定，再决定要不要解压。
                //
                // 原逻辑只看"快照文件存不存在"（hasEntrySnapshot = 存在且 >1024 字节），于是变成
                // 【有快照 ⇒ 只还原快照、永不注入 token】：一旦首次注入没成功，那份"未登录"
                // 快照就成了死循环 —— 点一次还原一次坏态、再存一份坏态，用户改 token / 重贴 /
                // 重启 App 全都无效（本仓库 .workbuddy/artifacts/token-path-audit.md C1、
                // login-failure-diagnosis.md D-1，两条独立路径收敛到同一处）。
                //
                // 判定同时比对 fpid（见 SessionProbe 类注释）：真机上发现过"A 入口的快照里存着
                // B 账号的会话"，若只判"有没有会话"会被误判为有效、照旧还原成别人的号。
                //
                // 失败即判无效（fail-closed）：后果只是多走一次"还原基线 + 注入"，方向安全；
                // 反过来 fail-open 会让坏快照继续固化。
                // 注意：不删除、不覆盖那份无效快照文件，留给下次 autoSaveEntrySnapshot() 正常覆盖。
                if (hasEntrySnapshot(acc.id)
                        && SessionProbe.snapshotHasValidSession(entrySnapshot(acc.id),
                                "shared_prefs/" + PREF_NAME + ".xml", SESSION_KEY, acc.fpid)) {
                    try {
                        unzip(entrySnapshot(acc.id), gameDataDir());
                        restored = true;
                    } catch (Throwable ignored) {
                    }
                }
                if (!restored) {
                    boolean useToken = acc.fpid != null && !acc.fpid.isEmpty()
                            && acc.token != null && !acc.token.isEmpty();
                    if (hasBaseline()) restoreBaseline();
                    if (useToken) {
                        injectSession(acc.fpid, acc.token);
                        // 【#72 F3】注入后立刻标记"待存"：SessionSaver 的下一次回调会跳过 45s 节流
                        // 直接尝试落盘（游戏还活着时它会自动跳过，等游戏一退出就补上）⇒
                        // 保证闪退 / 强杀之后再次进入仍免验证码。失败也不影响本次启动。
                        SessionSaver.requestSaveNow(ctx);
                    }
                }
            }

            // live 现在属于本入口（上面的 wipe/restore 路径已在 wipe 时清掉旧归属）
            store.setLiveEntryId(acc.id);

            Thread.sleep(200);
            return launchGame();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【#70 F1】作废某入口在 live 里的会话归属，使**下次打开该入口必然重新注入 token**。
     *
     * <p><b>为什么需要它</b>：{@link #launchEntry} 的 {@code keepLive} 分支（live 仍归属本入口）
     * 会<b>原样保留</b> live，既不 wipe 也不注入 —— 而 {@code injectSession} 只挂在
     * {@code !keepLive} 分支里。于是用户在管理页改完 token 后<b>紧接着打开同一入口</b>时，
     * live 仍归属它 ⇒ {@code keepLive=true} ⇒ 不注入 ⇒ 游戏沿用旧的 live 会话 ⇒
     * "token 改了却没生效"（{@code .workbuddy/artifacts/token-inject-auth-failure.md} R1）。
     *
     * <p><b>只摘归属标记，不删快照、不杀进程</b>：
     * <ul>
     *   <li>快照留着 —— {@code SessionProbe} 会比对 fpid，旧会话的快照会被判无效并走
     *       "还原基线 + 注入"，不会还原成旧号；覆盖交给 {@link #autoSaveEntrySnapshot} 正常进行；</li>
     *   <li>不在这里杀游戏 —— {@code launchEntry} 开头就会 {@code killGameAndWait}，
     *       提前杀反而会打断用户当前的操作。</li>
     * </ul>
     *
     * <p><b>幂等 + 静默</b>：live 没有归属、或归属的不是该入口 ⇒ 直接返回 false（本来就会走还原链）；
     * 任何异常一律吞掉并返回 false —— 这是"改设置"路径，绝不能因为作废失败而弹错或中断。
     *
     * @param entryId 要作废的目标入口 id
     * @return true = 确实摘掉了归属（下次打开必然重新注入）；false = 无需/无法作废
     */
    public boolean invalidateEntrySession(String entryId) {
        if (entryId == null || entryId.isEmpty()) return false;
        try {
            String owner = store.getLiveEntryId();
            if (owner == null || owner.isEmpty()) return false;   // live 无归属 ⇒ 下次本来就会 wipe+注入
            if (!entryId.equals(owner)) return false;             // live 不归它 ⇒ 与本次改动无关
            store.setLiveEntryId(null);                           // 只摘标记（null ⇒ 移除该键）
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void deleteEntryData(String entryId) {
        File f = entrySnapshot(entryId);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** 清理不属于任何现存入口、且 id 明显是脏数据（混入分隔符/空白）的遗留快照 */
    public void pruneOrphanSnapshots(java.util.Collection<String> validIds) {
        File[] files = ctx.getFilesDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (!(n.startsWith("snap_") && n.endsWith(".zip"))) continue;
            String id = n.substring(5, n.length() - 4);
            if (validIds.contains(id)) continue;
            if (id.contains("｜") || id.contains("|") || id.contains("\t") || id.contains(" ")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * 【#72 F4】「保存登录现场」的前置校验：不让"别人的 live"或"未登录态"被写进本入口快照。
     *
     * <p>判定口径与 {@link SessionProbe} 完全一致（{@code session_key} 非空 + fpid 匹配），
     * 只是对象从 zip 换成 live 目录 —— 这就是 {@link #readLiveSessionFpid}。
     * 入口没配 fpid（原生登录型）时只要求"会话存在且不为空"，与读侧 probe 的宽松口径对齐。
     *
     * @param entryId 目标入口 id
     * @return {@code null} = 允许保存；非 null = **拒绝原因**（可直接展示给用户）
     */
    public String captureGuardReason(String entryId) {
        if (entryId == null || entryId.isEmpty()) return "入口不存在，无法保存";
        try {
            if (!hasLiveContent()) return "当前没有可保存的游戏数据（游戏还没运行过），无法保存";
            String owner = store.getLiveEntryId();
            if (owner == null || owner.isEmpty()) {
                return "无法判断当前游戏归属哪个入口，为避免存错已取消保存";
            }
            if (!entryId.equals(owner)) {
                AccountStore.Account oa = store.byId(owner);
                String nm = (oa == null || oa.label == null || oa.label.isEmpty()) ? owner : oa.label;
                return "当前游戏里登录的是入口「" + nm + "」，不是本入口。请从本入口启动并登录后再保存";
            }
            String live = readLiveSessionFpid();
            if (live.isEmpty()) {
                return "当前游戏处于未登录状态，保存会把「未登录」写进本入口。请先登录游戏再保存";
            }
            AccountStore.Account target = store.byId(entryId);
            String expect = (target == null || target.fpid == null) ? "" : target.fpid;
            if (!expect.isEmpty() && !expect.equals(live)) {
                return "当前游戏登录的号（" + live + "）与本入口记录的号（" + expect + "）不一致，已取消保存";
            }
            return null;
        } catch (Throwable t) {
            return "读取当前登录状态失败（" + t + "），已取消保存以免写错";
        }
    }

    /** 保存当前登录到入口：先过 {@link #captureGuardReason}，再 杀游戏 → 快照 */
    public boolean captureEntrySnapshot(String entryId) {
        try {
            // 【#72 F4】纵深防御：即使调用方忘了先问校验，这里也不放行（保住了过去那个
            // 无条件打包 live 的写法会造成的错位）。
            if (captureGuardReason(entryId) != null) return false;
            if (!killGameAndWait(10000)) return false;
            return saveEntrySnapshot(entryId);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- zip 工具 ----------

    private static void zipDir(File dir, File output) throws IOException {
        FileOutputStream fos = new FileOutputStream(output);
        try {
            ZipOutputStream zos = new ZipOutputStream(fos);
            List<String> paths = new ArrayList<>();
            collect(dir, "", paths);
            byte[] buf = new byte[8192];
            for (String p : paths) {
                zos.putNextEntry(new ZipEntry(p));
                try (FileInputStream fi = new FileInputStream(new File(dir, p))) {
                    int n;
                    while ((n = fi.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            zos.finish();          // 先把中央目录写完，否则 sync 出来的不是完整 zip
            syncDescriptor(fos);   // 再刷盘（必须早于 close）
            zos.close();
        } finally {
            fos.close();
        }
    }

    /**
     * 尽力把已写入内容刷到物理磁盘（fsync）。
     * 只 close() 的话数据可能仅停在内核页缓存，掉电/硬重启会丢；快照是登录态的最后一道备份，
     * 值得多这一次 sync。此处同步的是宿主进程自己的 fd，不经 VA 的 guest IO hook。
     */
    private static void syncDescriptor(FileOutputStream fos) {
        try {
            fos.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    private static void collect(File base, String prefix, List<String> out) {
        File[] list = base.listFiles();
        if (list == null) return;
        for (File f : list) {
            String p = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) collect(f, p, out);
            else if (f.isFile()) out.add(p);
        }
    }

    private static void unzip(File zip, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) throw new IOException("cannot mkdir " + destDir);
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("cannot mkdir " + parent);
                if (e.isDirectory()) {
                    if (!out.exists()) out.mkdirs();
                    continue;
                }
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) fo.write(buf, 0, n);
                }
                zis.closeEntry();
            }
        }
    }

    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] list = f.listFiles();
        if (list != null) for (File c : list) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
