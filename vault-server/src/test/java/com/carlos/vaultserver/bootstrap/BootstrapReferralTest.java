package com.carlos.vaultserver.bootstrap;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link BootstrapReferral} 单测（M4b 任务书 §2.6）：append/listPending/markPulled/
 * 重启恢复/文件落盘 + 结构字段完整断言。sdkToken 允许明文落盘（回流数据本体），
 * 但断言结构字段齐全（fpid/sdkToken/encrypted/label/serverId/uid/furnaceLevel/
 * batchId/bootstrappedAt/pulled）。
 */
public class BootstrapReferralTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dataDir() {
        return tmp.getRoot().toPath();
    }

    private static JSONObject entry(String fpid, long serverId) {
        JSONObject o = new JSONObject();
        o.put("fpid", fpid);
        o.put("sdkToken", "tok-" + fpid + "-abcdef0123456789");
        o.put("encrypted", JSONObject.NULL);
        o.put("label", "号-" + fpid);
        o.put("serverId", serverId);
        o.put("uid", 9001L);
        o.put("furnaceLevel", 9L);
        o.put("batchId", "bb2026110704000001");
        o.put("bootstrappedAt", System.currentTimeMillis());
        o.put("pulled", false);
        return o;
    }

    @Test
    public void appendListPendingMarkPulled() {
        BootstrapReferral r = new BootstrapReferral(dataDir());
        r.append(entry("fp1", 1L));
        r.append(entry("fp2", 2L));

        List<JSONObject> pending = r.listPending();
        assertEquals(2, pending.size());
        assertEquals("fp1", pending.get(0).optString("fpid"));

        // 结构字段完整
        JSONObject first = pending.get(0);
        assertEquals("tok-fp1-abcdef0123456789", first.optString("sdkToken"));
        assertTrue(first.has("encrypted"));
        assertTrue(first.has("label"));
        assertTrue(first.has("serverId"));
        assertTrue(first.has("uid"));
        assertTrue(first.has("furnaceLevel"));
        assertTrue(first.has("batchId"));
        assertTrue(first.has("bootstrappedAt"));
        assertFalse(first.optBoolean("pulled", true));

        // markPulled：只标记存在的 fpid；重复标记不重复计数
        assertEquals(1, r.markPulled(List.of("fp1", "ghost")));
        assertEquals(1, r.listPending().size());
        assertEquals("fp2", r.listPending().get(0).optString("fpid"));
        assertEquals(0, r.markPulled(List.of("fp1")));
        assertEquals(1, r.markPulled(List.of("fp2")));
        assertEquals(0, r.listPending().size());
        assertEquals(2, r.listAll().size());
    }

    @Test
    public void appendSameFpidReplacesAndResetsPulled() {
        BootstrapReferral r = new BootstrapReferral(dataDir());
        r.append(entry("fp1", 1L));
        r.markPulled(List.of("fp1"));
        assertEquals(0, r.listPending().size());

        // 重新起号：同 fpid 覆盖并重置 pulled=false
        JSONObject rework = entry("fp1", 3L);
        rework.put("uid", 9500L);
        r.append(rework);
        List<JSONObject> pending = r.listPending();
        assertEquals(1, pending.size());
        assertEquals(3L, pending.get(0).optLong("serverId"));
        assertFalse(pending.get(0).optBoolean("pulled", true));
        assertEquals(1, r.size());
    }

    @Test
    public void restartRestoresFromFile() throws Exception {
        BootstrapReferral first = new BootstrapReferral(dataDir());
        first.append(entry("fp1", 1L));
        first.append(entry("fp2", 2L));
        first.markPulled(List.of("fp1"));

        // 模拟重启：新实例重新 load
        BootstrapReferral second = new BootstrapReferral(dataDir());
        second.load();
        assertEquals(2, second.size());
        assertEquals(1, second.listPending().size());
        JSONObject pending = second.listPending().get(0);
        assertEquals("fp2", pending.optString("fpid"));
        assertEquals("tok-fp2-abcdef0123456789", pending.optString("sdkToken"));

        // 主文件落盘且为合法 JSON
        Path file = dataDir().resolve("bootstrap_referral.json");
        assertTrue(Files.isRegularFile(file));
        JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(2, root.optJSONArray("entries").length());
    }

    @Test
    public void backupWrittenOnSecondFlush() throws Exception {
        BootstrapReferral r = new BootstrapReferral(dataDir());
        r.append(entry("fp1", 1L));   // 第一次写：只有主文件
        r.append(entry("fp2", 2L));   // 第二次写：产生 .bak
        assertTrue(Files.isRegularFile(dataDir().resolve("bootstrap_referral.json")));
        assertTrue(Files.isRegularFile(dataDir().resolve("bootstrap_referral.json.bak")));

        // 主文件写坏 → .bak 兜底恢复
        Files.writeString(dataDir().resolve("bootstrap_referral.json"), "{broken",
                StandardCharsets.UTF_8);
        BootstrapReferral second = new BootstrapReferral(dataDir());
        second.load();
        assertEquals("fp1", second.listAll().get(0).optString("fpid"));
    }

    @Test
    public void emptyAndInvalidEntriesIgnored() {
        BootstrapReferral r = new BootstrapReferral(dataDir());
        r.append(null);
        r.append(new JSONObject().put("label", "无 fpid"));
        assertEquals(0, r.size());
    }

    @Test
    public void maskShortAndLongValues() {
        // mask 语义对齐 PassportIds.mask（M1 权威实现）：≤6 位全掩为 ***，否则前 3 后 3
        assertEquals("", BootstrapReferral.mask(null));
        assertEquals("***", BootstrapReferral.mask("abcd"));
        assertEquals("***", BootstrapReferral.mask("tok-"));
        assertEquals("tok***789", BootstrapReferral.mask("tok-abcd-abcdef0123456789"));
    }
}
