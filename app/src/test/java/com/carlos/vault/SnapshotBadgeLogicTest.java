package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「快照徽标口径」单测（2026-10-25 真机问题）。
 *
 * <p><b>被修复的缺陷</b>：账号详情页在「隆科多」上显示「未绑定 token」，同时挂着绿色徽标
 * 「已保存登录 · 备有 token」；实际 {@code files/snap_<id>.zip} 里 {@code account_session_cn}
 * 是空的（未登录快照）。根因是 UI/写侧都只看「快照文件存不存在」。
 *
 * <p>本测试覆盖三块纯逻辑（均不依赖 Android，可直接在 JVM 跑）：
 * <ol>
 *   <li>{@link SessionProbe#classifySnapshot} —— 徽标四态判定（含 token + 坏快照的优先级）；</li>
 *   <li>{@link SessionProbe#snapshotHasValidSession} —— 真实 zip 上的快照会话有效性（fpid 归属）；</li>
 *   <li>{@link SessionProbe#canSnapshotLive} —— 写侧门：无会话 / 会话属于别的号时不写快照。</li>
 * </ol>
 */
public class SnapshotBadgeLogicTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PREF_ENTRY = "shared_prefs/com.cg.sdk.xml";
    private static final String SESSION_KEY = "account_session_cn";

    // ==================== 1. 徽标四态 ====================

    /** 只有快照文件、没有有效会话 ⇒ 必须是「已存现场·无登录会话」，绝不是「已保存登录」。 */
    @Test
    public void classify_snapshotFileWithoutSession_isSnapNoSession() {
        assertEquals(SessionProbe.SnapshotState.SNAP_NO_SESSION,
                SessionProbe.classifySnapshot(false, true, false));
    }

    /** 有 token + 快照文件但快照无效 ⇒ token 登录（现场未保存），不能报「可免登录」。 */
    @Test
    public void classify_tokenWithInvalidSnapshot_isTokenOnly() {
        assertEquals(SessionProbe.SnapshotState.TOKEN_ONLY,
                SessionProbe.classifySnapshot(true, true, false));
    }

    /** 有 token 且没有快照 ⇒ token 登录。 */
    @Test
    public void classify_tokenOnly_isTokenOnly() {
        assertEquals(SessionProbe.SnapshotState.TOKEN_ONLY,
                SessionProbe.classifySnapshot(true, false, false));
    }

    /** 快照有效、无 token（原生登录后保存过现场）⇒ 可免登录。 */
    @Test
    public void classify_validSnapshotWithoutToken_isSnapValid() {
        assertEquals(SessionProbe.SnapshotState.SNAP_VALID,
                SessionProbe.classifySnapshot(false, true, true));
    }

    /** 快照有效时即使有 token 也报「可免登录」（与 launchEntry 先还原有效快照一致）。 */
    @Test
    public void classify_validSnapshotWithToken_isSnapValid() {
        assertEquals(SessionProbe.SnapshotState.SNAP_VALID,
                SessionProbe.classifySnapshot(true, true, true));
    }

    /** 既没 token 也没快照 ⇒ 原生登录、未绑定 token（保持原文案）。 */
    @Test
    public void classify_nothing_isNativeOnly() {
        assertEquals(SessionProbe.SnapshotState.NATIVE_ONLY,
                SessionProbe.classifySnapshot(false, false, false));
    }

    // ==================== 2. 快照会话有效性（真实 zip） ====================

    @Test
    public void snapshot_validSessionMatchingFpid() throws Exception {
        File zip = zipWithPrefs(validSessionXml("80049204", "tok-abc"));
        assertTrue(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, "80049204"));
    }

    @Test
    public void snapshot_emptyExpectFpid_onlyRequiresSession() throws Exception {
        File zip = zipWithPrefs(validSessionXml("80049204", "tok-abc"));
        // 原生登录型入口没配 fpid：无从比对，只判「会话存在且非空」（预期语义）
        assertTrue(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, ""));
        assertTrue(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, null));
    }

    @Test
    public void snapshot_sessionOfAnotherAccount_isInvalid() throws Exception {
        File zip = zipWithPrefs(validSessionXml("80971192", "tok-abc"));
        // 真机 D-4：A 入口的快照里存着别人的会话 ⇒ 必须判无效（否则会还原成别人的号）
        assertFalse(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, "80049204"));
    }

    /** 登出态：session_key 被清空但 fpid 残留 ⇒ 不是有效会话（真机 4 次 ANR 现场就是这个）。 */
    @Test
    public void snapshot_logoutState_isInvalid() throws Exception {
        File zip = zipWithPrefs(sessionXml("80049204", ""));
        assertFalse(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, "80049204"));
        assertFalse(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, ""));
    }

    /** 快照里压根没有会话键（干净登录界面基线）⇒ 无效。 */
    @Test
    public void snapshot_noSessionKey_isInvalid() throws Exception {
        File zip = zipWithPrefs("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map>\n    <string name=\"other_key\">x</string>\n</map>\n");
        assertFalse(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, ""));
    }

    /** 快照里没有该 prefs 条目 ⇒ 无效。 */
    @Test
    public void snapshot_missingPrefsEntry_isInvalid() throws Exception {
        File zip = zipWithPrefs(validSessionXml("80049204", "tok-abc"), "other/entry.xml");
        assertFalse(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, "80049204"));
    }

    /** 快照文件不存在 ⇒ 无效（fail-closed）。 */
    @Test
    public void snapshot_missingFile_isInvalid() throws Exception {
        File f = new File(tmp.getRoot(), "snap_nope.zip");
        assertFalse(SessionProbe.snapshotHasValidSession(f, PREF_ENTRY, SESSION_KEY, "80049204"));
    }

    /** 假 zip（不是合法压缩包）⇒ 无效且不抛异常。 */
    @Test
    public void snapshot_brokenZip_isInvalid() throws Exception {
        File f = tmp.newFile("snap_broken.zip");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("not a zip".getBytes(UTF8));
        }
        assertFalse(SessionProbe.snapshotHasValidSession(f, PREF_ENTRY, SESSION_KEY, "80049204"));
    }

    // ==================== 3. 写侧门：该不该把 live 存成快照 ====================

    @Test
    public void canSnapshot_liveWithoutSession_isFalse() {
        assertFalse(SessionProbe.canSnapshotLive("", ""));
        assertFalse(SessionProbe.canSnapshotLive("", "80049204"));
        assertFalse(SessionProbe.canSnapshotLive(null, "80049204"));
        assertFalse(SessionProbe.canSnapshotLive(null, null));
    }

    @Test
    public void canSnapshot_nativeEntryWithLiveSession_isTrue() {
        // 原生登录型入口（没配 fpid）：只要有会话就允许存
        assertTrue(SessionProbe.canSnapshotLive("80049204", ""));
        assertTrue(SessionProbe.canSnapshotLive("80049204", null));
    }

    @Test
    public void canSnapshot_matchingFpid_isTrue() {
        assertTrue(SessionProbe.canSnapshotLive("80049204", "80049204"));
    }

    @Test
    public void canSnapshot_mismatchedFpid_isFalse() {
        // live 里登录的是别人的号 ⇒ 不能存进本入口（否则就是错位快照）
        assertFalse(SessionProbe.canSnapshotLive("80971192", "80049204"));
    }

    // ==================== 4. live 会话 fpid 解析（写侧读盘口径） ====================

    @Test
    public void parseLiveFpid_loggedIn_returnsFpid() {
        assertEquals("80049204",
                SessionProbe.parseSessionFpid(validSessionXml("80049204", "tok-abc"), SESSION_KEY));
    }

    @Test
    public void parseLiveFpid_loggedOut_returnsEmpty() {
        assertEquals("", SessionProbe.parseSessionFpid(sessionXml("80049204", ""), SESSION_KEY));
    }

    @Test
    public void parseLiveFpid_garbage_returnsEmpty() {
        assertEquals("", SessionProbe.parseSessionFpid("", SESSION_KEY));
        assertEquals("", SessionProbe.parseSessionFpid(null, SESSION_KEY));
        assertEquals("", SessionProbe.parseSessionFpid("<map/>", SESSION_KEY));
    }

    // ==================== 工具 ====================

    /** 生成一份 Android SharedPreferences 风格的 XML（引号按 SP 的写法转义成 &quot;）。 */
    private static String sessionXml(String fpid, String token) {
        String json = "{\"fpid\":\"" + fpid + "\",\"session_key\":\"" + token + "\"}";
        String escaped = json.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map>\n"
                + "    <string name=\"" + SESSION_KEY + "\">" + escaped + "</string>\n"
                + "</map>\n";
    }

    private static String validSessionXml(String fpid, String token) {
        return sessionXml(fpid, token);
    }

    private File zipWithPrefs(String xmlContent) throws IOException {
        return zipWithPrefs(xmlContent, PREF_ENTRY);
    }

    private File zipWithPrefs(String xmlContent, String entryName) throws IOException {
        File f = tmp.newFile("snap_test_" + System.nanoTime() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(xmlContent.getBytes(UTF8));
            zos.closeEntry();
        }
        return f;
    }
}
