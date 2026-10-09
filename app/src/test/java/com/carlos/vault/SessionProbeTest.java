package com.carlos.vault;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * TC-P14：会话快照探测（防「A 入口快照存着 B 号会话」、防登出态误判）。
 * 任何异常必须 fail-closed 判无效。
 */
public class SessionProbeTest {

    private static final String PREF_ENTRY = "shared_prefs/com.cg.sdk.xml";
    private static final String SESSION_KEY = "account_session_cn";

    private static String xml(String fpid, String sessionKey) {
        String json = "{\"fpid\":\"" + fpid + "\",\"session_key\":\"" + sessionKey + "\"}";
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map>\n"
                + "    <string name=\"account_session_cn\">" + json + "</string>\n"
                + "    <string name=\"other\">x</string>\n"
                + "</map>";
    }

    private static File zip(String entryName, String content) throws Exception {
        File f = File.createTempFile("snap", ".zip");
        f.deleteOnExit();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return f;
    }

    @Test
    public void probe_validSession_matchingFpid() throws Exception {
        File zip = zip(PREF_ENTRY, xml("12345678", "live-session-token"));
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, "12345678");
        assertTrue(r.valid);
        assertEquals("12345678", r.fpid);
        assertTrue(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, "12345678"));
    }

    @Test
    public void probe_logoutState_emptySessionKey_invalid() throws Exception {
        // 登出态只清 session_key，fpid 可能残留 ⇒ 必须判无效（fail-closed）
        File zip = zip(PREF_ENTRY, xml("12345678", ""));
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, "12345678");
        assertFalse(r.valid);
    }

    @Test
    public void probe_sessionKeyMissing_invalid() throws Exception {
        File zip = zip(PREF_ENTRY, "<map><string name=\"other\">x</string></map>");
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, null);
        assertFalse(r.valid);
    }

    @Test
    public void probe_fpidMismatch_invalid() throws Exception {
        // 防串号核心：A 入口的快照存着 B 号会话 ⇒ 还原前必须拦下
        File zip = zip(PREF_ENTRY, xml("99999999", "live-session-token"));
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, "12345678");
        assertFalse(r.valid);
        // 期望为空时只看 session_key
        assertTrue(SessionProbe.snapshotHasValidSession(zip, PREF_ENTRY, SESSION_KEY, ""));
    }

    @Test
    public void probe_zipMissingEntry_invalid() throws Exception {
        File zip = zip("other/entry.txt", "hello");
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, "12345678");
        assertFalse(r.valid);
    }

    @Test
    public void probe_missingFile_invalid() throws Exception {
        SessionProbe.Result r = SessionProbe.probe(
                new File("/nonexistent/snap.zip"), PREF_ENTRY, SESSION_KEY, "12345678");
        assertFalse(r.valid);
    }

    @Test
    public void probe_entityEscapesAndFullWidthQuotes() throws Exception {
        // XML 实体（&quot; 等）必须反转义；fpid 必须纯数字
        String json = "{&quot;fpid&quot;:&quot;12345678&quot;,&quot;session_key&quot;:&quot;tok&quot;}";
        String content = "<map><string name=\"account_session_cn\">" + json + "</string></map>";
        File zip = zip(PREF_ENTRY, content);
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, "12345678");
        assertTrue(r.valid);
        assertEquals("12345678", r.fpid);
    }

    @Test
    public void parseSessionFpid_nonDigitFpid_returnsEmpty() {
        String xml = xml("12a45", "tok");
        assertEquals("", SessionProbe.parseSessionFpid(xml, SESSION_KEY));
    }

    @Test
    public void entryNameSuffixMatching() throws Exception {
        // zip 条目名做后缀兜底匹配（真实快照的条目可能带前缀目录）
        File zip = zip("data/user/0/pkg/" + PREF_ENTRY, xml("12345678", "tok"));
        SessionProbe.Result r = SessionProbe.probe(zip, PREF_ENTRY, SESSION_KEY, "12345678");
        assertTrue(r.valid);
    }
}
