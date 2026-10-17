package com.carlos.vault;

import android.content.Context;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * 资源更新状态同步（P1-4，实验开关，默认关闭）。
 *
 * <p><b>问题</b>：UI 资源本体在共享外部层（Android_va/…/files/data，约 1.9GB，
 * 全库一份），但「已更新到哪」的状态是每账号私有——
 * <ul>
 *   <li>{@code files/htpUpdate.config}（32B：{"a":引擎版本,"l":0,"s":0,"u":""}）</li>
 *   <li>PlayerPrefs {@code __KEY_MD5_PATCH__} / {@code __KEY_MD5_SPLIT__}（清单指纹）</li>
 *   <li>PlayerPrefs {@code __KEY_RES_VERSION_<channelId>__}（各渠道资源版本号）</li>
 * </ul>
 * 切到另一个账号时，该账号快照里的状态是旧值 ⇒ 游戏判定「资源版本不一致」⇒
 * 重新走 CheckHotFix → DownloadHotFixVersion → DownloadSplitAsset 流程（真机
 * 日志实证：patch 11→12 只改了 luapatch_32/64 两个文件，其余账号仍各跑一遍校验）。
 *
 * <p><b>方案</b>：只同步<b>状态白名单</b>，绝不动资源本体与账号凭据——
 * <ul>
 *   <li>capture：切号时（wipe 前）从即将让位的 live 读取白名单键，写入全库共享的
 *       {@code files/resource_state.json}（canonical，不进任何账号快照）；</li>
 *   <li>apply：启动前（快照还原/注入之后、launchGame 之前）把 canonical 应用到
 *       live 的 prefs/htp —— 必须在还原之后，否则会被旧快照盖回。</li>
 * </ul>
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>白名单外一个键都不碰（token/session/lastUserId/账号数据绝不复制）；</li>
 *   <li>写入前备份 prefs 与 htp 到 {@code *.res_sync_bak}，异常即中止不改盘；</li>
 *   <li>仅在游戏进程已死时执行（调用点都在 killGameAndWait 之后）；</li>
 *   <li>游戏若仍触发下载（状态校验不止这些键），日志会记录 before/after 供追加排查。</li>
 * </ul>
 *
 * <p><b>开关</b>（默认关）：SharedPreferences {@code resource_sync} 的 {@code enabled}
 * 或标记文件 {@code files/resource_state_sync.enabled} 存在。调试期开启方式：
 * <pre>adb shell run-as com.carlos.multiapp touch files/resource_state_sync.enabled</pre>
 */
public final class ResourceStateSync {

    private static final String CANONICAL = "resource_state.json";
    private static final String MARKER = "resource_state_sync.enabled";
    private static final String LOG = "resource_state_sync.log";

    /** 游戏 PlayerPrefs 文件（真机实测路径，游戏启动时读取）。 */
    static final String PREFS_NAME = "com.gof.china.v2.playerprefs.xml";
    static final String HTP_NAME = "htpUpdate.config";

    /** 精确白名单：清单指纹。 */
    static final String[] EXACT_KEYS = {"__KEY_MD5_PATCH__", "__KEY_MD5_SPLIT__"};
    /** 前缀白名单：各渠道资源版本号（__KEY_RES_VERSION_1784206943__=28 等）。 */
    static final String PREFIX = "__KEY_RES_VERSION_";
    private static final String PREFIX_TAIL = "__";

    private ResourceStateSync() {
    }

    // ---------- 开关 ----------

    public static boolean enabled(Context ctx) {
        try {
            if (ctx.getSharedPreferences("resource_sync", Context.MODE_PRIVATE)
                    .getBoolean("enabled", false)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return new File(ctx.getFilesDir(), MARKER).exists();
    }

    public static void setEnabled(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences("resource_sync", Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled", on).apply();
            File m = new File(ctx.getFilesDir(), MARKER);
            if (on && !m.exists()) {
                m.createNewFile();
            } else if (!on && m.exists()) {
                m.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 白名单判定（纯函数，供 JVM 单测锁定口径）。 */
    static boolean isWhitelistKey(String key) {
        if (key == null) return false;
        for (String k : EXACT_KEYS) {
            if (k.equals(key)) return true;
        }
        return key.startsWith(PREFIX) && key.endsWith(PREFIX_TAIL) && key.length() > PREFIX.length() + PREFIX_TAIL.length();
    }

    // ---------- capture：live → canonical ----------

    /**
     * 从即将让位的 live 目录采集资源状态写入 canonical。
     * 只在两个清单指纹都非空时才写（指纹缺失说明该会话没走完更新链，采了也没意义）。
     * 返回 true 表示 canonical 被更新。
     */
    public static boolean captureFromLive(VaultEngine eng, Context ctx) {
        try {
            File prefs = new File(new File(eng.gameDataDir(), "shared_prefs"), PREFS_NAME);
            if (!prefs.isFile() || prefs.length() <= 0 || prefs.length() > 4 * 1024 * 1024) {
                return false;
            }
            Map<String, String[]> found = readWhitelist(prefs);
            String md5Patch = get(found, "__KEY_MD5_PATCH__");
            String md5Split = get(found, "__KEY_MD5_SPLIT__");
            if (md5Patch == null || md5Patch.isEmpty() || md5Split == null || md5Split.isEmpty()) {
                return false;   // 更新链没走完，不采
            }
            JSONObject out = new JSONObject();
            out.put("v", 1);
            out.put("md5Patch", md5Patch);
            out.put("md5Split", md5Split);
            JSONObject rv = new JSONObject();
            for (Map.Entry<String, String[]> e : found.entrySet()) {
                if (e.getKey().startsWith(PREFIX)) {
                    rv.put(e.getKey(), e.getValue()[1]);
                }
            }
            out.put("resVersions", rv);
            String htp = readText(new File(new File(eng.gameDataDir(), "files"), HTP_NAME), 256);
            if (htp != null) out.put("htp", htp);
            out.put("capturedAt", System.currentTimeMillis());
            out.put("source", "live");
            File c = new File(ctx.getFilesDir(), CANONICAL);
            atomicWrite(c, out.toString().getBytes(StandardCharsets.UTF_8));
            appendLog(ctx, "capture md5Patch=" + md5Patch + " md5Split=" + md5Split
                    + " resVersions=" + rv.length() + (htp == null ? "" : " htp=yes"));
            return true;
        } catch (Throwable t) {
            appendLog(ctx, "capture fail: " + t);
            return false;
        }
    }

    // ---------- apply：canonical → live ----------

    /**
     * 把 canonical 的资源状态应用到 live（游戏进程必须已死）。
     * 返回 true 表示实际写入（有差异且成功）；false = 无差异/无 canonical/失败。
     */
    public static boolean applyToLive(VaultEngine eng, Context ctx) {
        try {
            File c = new File(ctx.getFilesDir(), CANONICAL);
            byte[] cbytes = readBytes(c, 64 * 1024);
            if (cbytes == null) return false;
            JSONObject canon = new JSONObject(new String(cbytes, StandardCharsets.UTF_8));
            String md5Patch = canon.optString("md5Patch", "");
            String md5Split = canon.optString("md5Split", "");
            if (md5Patch.isEmpty() || md5Split.isEmpty()) return false;

            File prefs = new File(new File(eng.gameDataDir(), "shared_prefs"), PREFS_NAME);
            if (!prefs.isFile()) return false;   // live 尚未初始化，交给注入/首次启动
            Map<String, String[]> cur = readWhitelist(prefs);

            // 无差异快速返回（避免无意义重写 prefs）
            boolean diff = !md5Patch.equals(get(cur, "__KEY_MD5_PATCH__"))
                    || !md5Split.equals(get(cur, "__KEY_MD5_SPLIT__"));
            JSONObject rv = canon.optJSONObject("resVersions");
            if (!diff && rv != null) {
                Iterator<String> it = rv.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!rv.optString(k, "").equals(get(cur, k))) {
                        diff = true;
                        break;
                    }
                }
            }
            if (!diff) return false;

            // 备份（保留最近一份）
            copyFile(prefs, new File(prefs.getParentFile(), PREFS_NAME + ".res_sync_bak"));

            // 写 prefs：DOM 只动白名单键
            writeWhitelist(prefs, md5Patch, md5Split, rv);

            // htpUpdate.config 原样覆盖（32B 纯状态，无账号字段）
            String htp = canon.optString("htp", "");
            if (!htp.isEmpty()) {
                File hf = new File(new File(eng.gameDataDir(), "files"), HTP_NAME);
                if (hf.isFile()) {
                    copyFile(hf, new File(hf.getParentFile(), HTP_NAME + ".res_sync_bak"));
                    atomicWrite(hf, htp.getBytes(StandardCharsets.UTF_8));
                }
            }
            appendLog(ctx, "apply before{md5Patch=" + get(cur, "__KEY_MD5_PATCH__")
                    + ",md5Split=" + get(cur, "__KEY_MD5_SPLIT__") + "}"
                    + " after{md5Patch=" + md5Patch + ",md5Split=" + md5Split + "}"
                    + (rv == null ? "" : " resVersions=" + rv.length()));
            return true;
        } catch (Throwable t) {
            appendLog(ctx, "apply fail: " + t);
            return false;
        }
    }

    // ---------- prefs XML（DOM，白名单内增改，白名单外不动） ----------

    /** 读白名单键 → {key → [type, value]}，type ∈ {"string","int"}。 */
    private static Map<String, String[]> readWhitelist(File prefs) throws Exception {
        Map<String, String[]> out = new LinkedHashMap<>();
        Document doc = parse(prefs);
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String name = e.getAttribute("name");
            if (!isWhitelistKey(name)) continue;
            if ("string".equals(e.getTagName())) {
                out.put(name, new String[]{"string", e.getTextContent()});
            } else if ("int".equals(e.getTagName())) {
                out.put(name, new String[]{"int", e.getAttribute("value")});
            }
        }
        return out;
    }

    private static void writeWhitelist(File prefs, String md5Patch, String md5Split,
                                       JSONObject resVersions) throws Exception {
        Document doc = parse(prefs);
        Element root = doc.getDocumentElement();
        removeChild(root, "__KEY_MD5_PATCH__");
        removeChild(root, "__KEY_MD5_SPLIT__");
        root.appendChild(stringNode(doc, "__KEY_MD5_PATCH__", md5Patch));
        root.appendChild(stringNode(doc, "__KEY_MD5_SPLIT__", md5Split));
        if (resVersions != null) {
            Iterator<String> it = resVersions.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!isWhitelistKey(k)) continue;
                removeChild(root, k);
                root.appendChild(intNode(doc, k, resVersions.optString(k, "0")));
            }
        }
        save(doc, prefs);
    }

    private static Document parse(File f) throws Exception {
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        try (InputStream in = new FileInputStream(f)) {
            return db.parse(in);
        }
    }

    private static void save(Document doc, File out) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            t.transform(new DOMSource(doc), new StreamResult(fos));
        }
        if (out.exists() && !out.delete()) throw new IllegalStateException("无法替换 " + out);
        if (!tmp.renameTo(out)) throw new IllegalStateException("原子替换失败 " + out);
    }

    private static void removeChild(Element root, String name) {
        NodeList children = root.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && name.equals(((Element) n).getAttribute("name"))) {
                root.removeChild(n);
            }
        }
    }

    private static Element stringNode(Document doc, String name, String value) {
        Element e = doc.createElement("string");
        e.setAttribute("name", name);
        e.setTextContent(value);
        return e;
    }

    private static Element intNode(Document doc, String name, String value) {
        Element e = doc.createElement("int");
        e.setAttribute("name", name);
        e.setAttribute("value", value);
        return e;
    }

    // ---------- 小工具 ----------

    private static String get(Map<String, String[]> m, String k) {
        String[] v = m.get(k);
        return v == null ? null : v[1];
    }

    private static String readText(File f, int max) {
        byte[] b = readBytes(f, max);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f, int max) {
        try {
            if (!f.isFile() || f.length() <= 0 || f.length() > max) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void atomicWrite(File f, byte[] data) throws Exception {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.getFD().sync();
        }
        if (f.exists() && !f.delete()) throw new IllegalStateException("无法替换 " + f);
        if (!tmp.renameTo(f)) throw new IllegalStateException("原子替换失败 " + f);
    }

    private static void copyFile(File src, File dst) {
        try {
            byte[] b = readBytes(src, 8 * 1024 * 1024);
            if (b == null) return;
            atomicWrite(dst, b);
        } catch (Throwable ignored) {
        }
    }

    /** 诊断日志（files/resource_state_sync.log，保留末尾 120 行）：before/after 必记。 */
    private static void appendLog(Context ctx, String msg) {
        try {
            android.util.Log.i("ResStateSync", msg);
            File f = new File(ctx.getFilesDir(), LOG);
            StringBuilder sb = new StringBuilder();
            byte[] old = readBytes(f, 64 * 1024);
            if (old != null) sb.append(new String(old, StandardCharsets.UTF_8));
            sb.append(System.currentTimeMillis()).append(' ').append(msg).append('\n');
            String all = sb.toString();
            int idx = 0, lines = 0;
            for (int i = all.length() - 1; i >= 0; i--) {
                if (all.charAt(i) == '\n' && ++lines > 120) {
                    idx = i + 1;
                    break;
                }
            }
            atomicWrite(f, all.substring(idx).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }
}
