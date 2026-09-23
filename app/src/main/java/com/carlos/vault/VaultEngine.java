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

    /** 向游戏 prefs 写入账号会话（XML 转义后合并/新建） */
    public boolean injectSession(String fpid, String token) throws IOException {
        File prefsDir = new File(gameDataDir(), "shared_prefs");
        if (!prefsDir.exists() && !prefsDir.mkdirs()) return false;
        File f = new File(prefsDir, PREF_NAME + ".xml");
        String sess = "{\"fpid\":\"" + fpid + "\",\"session_key\":\"" + token
                + "\",\"is_new\":false,\"expire_on\":9999999999999,\"account_type\":\"mobile\""
                + ",\"session_expire_in\":31536000000,\"created_ts\":" + System.currentTimeMillis()
                + ",\"is_verify_id_card\":true,\"offline_time\":0}";
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
        String fp = liveFingerprint();
        if (fp != null && fp.equals(store.getSnapshotFingerprint(entryId)) && hasEntrySnapshot(entryId)) {
            return false;
        }
        return saveEntrySnapshot(entryId);
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

            if (!keepLive) {
                // 走既有还原链（wipe → 有快照还原快照 → 无快照回退基线/注入 token）。
                // wipeGameInternal() 内部会把归属标记清空，这里不用再管。
                wipeGameInternal();
                boolean restored = false;
                if (hasEntrySnapshot(acc.id)) {
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
                    if (useToken) injectSession(acc.fpid, acc.token);
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

    /** 保存当前登录到入口：杀游戏 → 快照 */
    public boolean captureEntrySnapshot(String entryId) {
        try {
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
