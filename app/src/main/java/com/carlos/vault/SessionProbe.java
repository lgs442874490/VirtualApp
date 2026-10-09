package com.carlos.vault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 入口快照「会话有效性」探测器。
 *
 * <p><b>为什么需要它</b>：`VaultEngine.launchEntry()` 原先只看「快照文件存不存在」
 * （{@code hasEntrySnapshot} = 文件存在且 >1024 字节）。于是【有快照 ⇒ 只还原快照、
 * 永不注入 token】——一旦首次注入没成功，那份「未登录」快照就成了死循环：
 * 点一次还原一次坏态、再存一份坏态，用户改 token / 重贴 / 重启 App 全都无效
 * （详见 {@code .workbuddy/artifacts/token-path-audit.md} 的 C1 与
 * {@code .workbuddy/artifacts/login-failure-diagnosis.md} 的 D-1）。
 *
 * <p><b>它回答的问题</b>：这份快照里**有没有属于本入口的、可用的登录会话**。
 * 只有回答「有」，才允许走「还原快照」分支；否则一律回退到「还原基线 + 注入 token」。
 *
 * <h3>两条判定（缺一不可）</h3>
 * <ol>
 *   <li><b>会话存在且非空</b> —— 对应 D-1：快照里压根没有 {@code account_session_cn}，
 *       或 {@code session_key} 为空，都说明这份快照是「未登录/已登出」态，还原它毫无意义。</li>
 *   <li><b>fpid 必须匹配</b> —— 对应 D-4：真机上发现过「A 入口的快照里存着 B 账号的会话」
 *       （鲍哥888 快照内是别人的号）。若只判「有没有会话」，这种快照会被误判为有效，
 *       照旧还原成别人的号。<b>所以必须比对 fpid，而不是只看有没有会话。</b></li>
 * </ol>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li><b>刻意不 import 任何 {@code android.*}</b>：与 {@link AccountImport} 同款做法，
 *       目的是能直接用 {@code javac} 在宿主机上干跑验证（无 Android SDK 依赖）。
 *       因此 JSON 与 XML 都用手写的最小解析器，不用 {@code org.json} / {@code XmlPullParser}
 *       （前者不在 JDK 里，后者不在 JDK 里）。</li>
 *   <li><b>只读一个 entry</b>：用 {@link ZipFile} 只取 {@code shared_prefs/<PREF>.xml}
 *       这一个条目解析，不解压整个（约 2.9MB 的）快照 —— 判定失败时那次解压就纯属浪费。</li>
 *   <li><b>fail-closed（失败即判无效）</b>：任何读不到 / 解析不了 / 不匹配的情况都返回
 *       {@code false}。后果只是「多走一次基线还原 + 注入」，方向是安全的；反过来
 *       fail-open 会让坏快照继续固化。</li>
 *   <li><b>不修改任何文件</b>：本类只读。无效快照文件不会被删除或覆盖，留给下次
 *       {@code autoSaveEntrySnapshot()} 正常覆盖（修法刻意保留用户数据）。</li>
 * </ul>
 */
public final class SessionProbe {

    /**
     * 单个 prefs 文件的读取上限。正常只有十几 KB；设上限是为了防止异常 zip
     * （条目声明巨大 / 构造畸形）把内存打爆，且反正超出部分对判定没有意义。
     */
    private static final int MAX_PREF_BYTES = 1024 * 1024;

    /** 探测结果：既给结论也给原因，便于排障与核对「有没有误伤正常入口」。 */
    public static final class Result {
        /** 这份快照是否含有本入口可用的会话。 */
        public final boolean valid;
        /** 无效原因（{@code valid == true} 时为 null）。用于日志与人工核对。 */
        public final String reason;
        /** 快照会话里的 fpid（可能为空/null，仅作参考）。 */
        public final String fpid;

        Result(boolean valid, String reason, String fpid) {
            this.valid = valid;
            this.reason = reason;
            this.fpid = fpid;
        }

        @Override
        public String toString() {
            return valid ? ("valid, fpid=" + fpid) : ("invalid: " + reason);
        }
    }

    private SessionProbe() {
    }

    /**
     * 判断入口快照里是否有「本入口可用的登录会话」。
     *
     * @param zipFile       入口快照 zip（{@code files/snap_<entryId>.zip}）
     * @param prefEntryName 快照内 prefs 条目的路径，如 {@code shared_prefs/com.cg.sdk.xml}
     * @param sessionKey    会话键名（{@link VaultEngine#SESSION_KEY}）
     * @param expectFpid    期望的号ID（入口的 {@code acc.fpid}）；为空/非空见下方说明
     * @return true ⇒ 可以安全还原这份快照；false ⇒ 应回退到「还原基线 + 注入 token」
     */
    public static boolean snapshotHasValidSession(File zipFile, String prefEntryName,
                                                  String sessionKey, String expectFpid) {
        return probe(zipFile, prefEntryName, sessionKey, expectFpid).valid;
    }

    /**
     * 从一份 **live（游戏目录里那份）** 的 SharedPreferences XML 文本里取"实际登录的号ID"。
     *
     * <p><b>为什么写侧也需要它</b>：{@link VaultEngine#launchEntry} 的存档路径此前只判断
     * "live 内容相对上次快照有没有变化"（指纹），**从不看会话属于谁**，于是真机上出现过
     * "把 B 号的会话存进 A 入口的快照"（{@code login-failure-diagnosis.md} §13 的 D-2）。
     * 要做"以最新登录为准"的归属同步，写侧就必须先能回答"live 里的会话是谁的"。
     * （读侧 {@link #probe} 早就有 fpid 校验，写侧此前完全没有 —— 同一道门只装了一半。）
     *
     * <p><b>与 {@link #probe} 的关系</b>：复用同一套"XML 取 string 值 → 反转义 → 抽 JSON 字段"
     * 逻辑，区别只在输入：这里直接吃 XML 文本（live 目录中的文件），不需要解开 zip。
     *
     * <p><b>fail-safe（失败即空串）</b>：读不到 / 解析不了 / 缺 fpid 一律返回 {@code ""}。
     * 调用方（{@link VaultEngine#syncSessionOwner}）把空串解释为"读不到会话 ⇒ 跳过同步、照旧存档"，
     * 因此**绝不能让解析失败抛出**影响存档主流程。取向与 {@link #probe} 的 fail-closed 一致：
     * 宁可不同步（维持现状），也不要按错误归属改写用户数据。
     *
     * <p><b>签名说明</b>：多了一个 {@code sessionKey} 参数（而非只吃 xml）—— 本类刻意不
     * import 任何非 JDK 类型、也不引用 {@link VaultEngine}，会话键名一直由调用方传入
     * （见 {@link #probe}）。这样本类才能继续在宿主机上直接 javac 干跑。
     *
     * <p><b>「登出态」必须返回空串</b>：判断依据与读侧 {@link #probe} 的判定①口径一致 ——
     * 光有 {@code fpid} 不够，还要 {@code session_key} 非空。"已登出"时游戏只清 session_key、
     * {@code fpid} 往往还留在 JSON 里；若把它当成"登录了某个号"，归属同步就会把一个**没在登录**
     * 的号写进入口（既污染归属，又与"以最新登录为准"的语义相反）。所以这里要求两者都非空。
     *
     * @param xml        prefs 文件完整文本（{@code shared_prefs/<PREF_NAME>.xml}）
     * @param sessionKey 会话键名（{@link VaultEngine#SESSION_KEY}）
     * @return 会话 JSON 里的 {@code fpid}；读不到 / 解析不了 / 未登录 ⇒ {@code ""}（**保证不返回 null**）
     */
    public static String parseSessionFpid(String xml, String sessionKey) {
        if (xml == null || xml.isEmpty()) return "";
        if (sessionKey == null || sessionKey.isEmpty()) return "";
        try {
            String raw = extractStringValue(xml, sessionKey);
            if (raw == null) return "";
            // SP 的 XML 会把 JSON 引号写成 &quot;，不反转义后面的字段抽取会全部落空。
            String json = unescapeXml(raw).trim();
            if (json.isEmpty()) return "";
            // 与读侧判定①同口径：session_key 非空才算"真的登录着"（登出态只清它，fpid 可能仍在）。
            String token = jsonStringField(json, "session_key");
            if (token == null || token.isEmpty()) return "";
            String fpid = jsonStringField(json, "fpid");
            if (fpid == null) return "";
            fpid = fpid.trim();
            // 与 VaultEngine.readCurrentSession() 的口径一致：号ID必须是纯数字才可信。
            // 否则宁可不同步（返回 ""）—— 垃圾 fpid 一旦写进 account.fpid，读侧比对会永远失配。
            return fpid.matches("[0-9]+") ? fpid : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 同 {@link #snapshotHasValidSession}，但额外返回原因与快照内的 fpid，供日志/排障使用。
     *
     * <p><b>expectFpid 为空时怎么办</b>：入口没配号ID（原生登录模式的入口）时无从比对，
     * 此时只判「会话存在且非空」——这类入口本来也不会注入 token，回退分支里
     * {@code useToken} 为假，行为与修复前一致（还原基线）。
     */
    public static Result probe(File zipFile, String prefEntryName,
                               String sessionKey, String expectFpid) {
        if (zipFile == null || !zipFile.isFile()) {
            return invalid("快照文件不存在: " + zipFile, null);
        }
        if (prefEntryName == null || prefEntryName.isEmpty()) {
            return invalid("prefs 条目名为空", null);
        }
        if (sessionKey == null || sessionKey.isEmpty()) {
            return invalid("会话键名为空", null);
        }

        ZipFile zf = null;
        try {
            zf = new ZipFile(zipFile);
            ZipEntry entry = zf.getEntry(prefEntryName);
            // 兜底：调用方可能只给了文件名（"com.cg.sdk.xml"）而实际条目带目录前缀。
            // 真实快照由 VaultEngine.zipDir() 生成，条目名是相对路径（shared_prefs/…），
            // 这里做一次后缀匹配，避免"路径写错 ⇒ 永远判无效 ⇒ 每次都白还原基线"的隐蔽故障。
            if (entry == null) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (n.equals(prefEntryName) || n.endsWith("/" + prefEntryName)) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) {
                return invalid("快照里没有 " + prefEntryName, null);
            }

            String xml = readEntry(zf, entry);
            if (xml == null || xml.isEmpty()) {
                return invalid("快照里的 " + prefEntryName + " 为空", null);
            }

            // —— 判定①：会话键必须存在 ——
            String raw = extractStringValue(xml, sessionKey);
            if (raw == null) {
                return invalid("快照会话里没有 " + sessionKey + " 键", null);
            }
            // Android 的 SharedPreferences 会把 JSON 里的引号写成 &quot;，必须先反转义，
            // 否则后面的 JSON 字段抽取会全部落空。
            String json = unescapeXml(raw).trim();
            if (json.isEmpty()) {
                return invalid("快照会话 " + sessionKey + " 为空", null);
            }

            String token = jsonStringField(json, "session_key");
            if (token == null || token.isEmpty()) {
                return invalid("快照会话 session_key 为空", null);
            }

            // —— 判定②：fpid 必须匹配（否则就是"存了别人的号"）——
            String fpid = jsonStringField(json, "fpid");
            if (expectFpid != null && !expectFpid.isEmpty()) {
                if (fpid == null || fpid.isEmpty()) {
                    return invalid("快照会话缺少 fpid", fpid);
                }
                if (!expectFpid.equals(fpid)) {
                    return invalid("快照会话属于别的号（快照=" + fpid + "，本入口=" + expectFpid + "）", fpid);
                }
            }
            return new Result(true, null, fpid);
        } catch (Throwable t) {
            return invalid("读取快照异常: " + t, null);
        } finally {
            closeQuietly(zf);
        }
    }

    // ---------------- 内部工具（全部手写，避免任何非 JDK 依赖） ----------------

    private static Result invalid(String reason, String fpid) {
        return new Result(false, reason, fpid);
    }

    /** 读一个 zip 条目的 UTF-8 文本，受 {@link #MAX_PREF_BYTES} 限制。 */
    private static String readEntry(ZipFile zf, ZipEntry entry) throws Exception {
        InputStream in = zf.getInputStream(entry);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PREF_BYTES) break;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 从 Android SharedPreferences 的 XML 里取 {@code <string name="key">…</string>} 的值。
     * 手写扫描而非用解析器：既能在宿主机 javac 干跑，也顺带兼容属性用单引号写成的文件。
     */
    private static String extractStringValue(String xml, String key) {
        int from = 0;
        while (true) {
            int lt = xml.indexOf("<string", from);
            if (lt < 0) return null;
            int gt = xml.indexOf('>', lt);
            if (gt < 0) return null;
            String tag = xml.substring(lt, gt);
            if (key.equals(attributeValue(tag, "name"))) {
                int end = xml.indexOf("</string>", gt + 1);
                if (end < 0) return null;
                return xml.substring(gt + 1, end);
            }
            from = gt + 1;
        }
    }

    /** 取 {@code <string …>} 开标签里某个属性的值，兼容双引号与单引号。 */
    private static String attributeValue(String tag, String attr) {
        int i = 0;
        while (true) {
            int p = tag.indexOf(attr, i);
            if (p < 0) return null;
            int q = p + attr.length();
            while (q < tag.length() && Character.isWhitespace(tag.charAt(q))) q++;
            if (q < tag.length() && tag.charAt(q) == '=') {
                q++;
                while (q < tag.length() && Character.isWhitespace(tag.charAt(q))) q++;
                if (q < tag.length()) {
                    char c = tag.charAt(q);
                    if (c == '"' || c == '\'') {
                        int e = tag.indexOf(c, q + 1);
                        if (e > 0) return tag.substring(q + 1, e);
                    }
                }
            }
            i = p + 1;
        }
    }

    /** 最小 JSON 字符串字段抽取：找 {@code "key":"…"} 并还原转义。 */
    private static String jsonStringField(String json, String key) {
        String k = "\"" + key + "\"";
        int p = json.indexOf(k);
        if (p < 0) return null;
        int c = json.indexOf(':', p + k.length());
        if (c < 0) return null;
        int i = c + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '\\') {
                if (i + 1 < json.length()) sb.append(json.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    /** XML 实体反转义（SP 的 XML 会把 JSON 的引号写成 {@code &quot;}，不还原就取不到字段）。 */
    private static String unescapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                continue;
            }
            String r = xmlEntity(s.substring(i + 1, semi));
            if (r == null) {
                sb.append(c);
                continue;
            }
            sb.append(r);
            i = semi;
        }
        return sb.toString();
    }

    private static String xmlEntity(String e) {
        if ("quot".equals(e)) return "\"";
        if ("apos".equals(e)) return "'";
        if ("amp".equals(e)) return "&";
        if ("lt".equals(e)) return "<";
        if ("gt".equals(e)) return ">";
        if (e.length() > 2 && (e.charAt(0) == '#')
                && (e.charAt(1) == 'x' || e.charAt(1) == 'X')) {
            try {
                return String.valueOf((char) Integer.parseInt(e.substring(2), 16));
            } catch (Throwable t) {
                return null;
            }
        }
        if (e.length() > 1 && e.charAt(0) == '#') {
            try {
                return String.valueOf((char) Integer.parseInt(e.substring(1)));
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void closeQuietly(ZipFile zf) {
        if (zf == null) return;
        try {
            zf.close();
        } catch (Throwable ignored) {
        }
    }
}
