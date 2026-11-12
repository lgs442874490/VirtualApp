package com.carlos.vaultserver.bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BootstrapBatchStore} 单测（M4b 任务书 §2.6 Store 恢复）：append/update/list/active、
 * 重启恢复（RUNNING → FAILED 服务重启中断）、30 条截断、文件 + .bak 落盘。
 */
public class BootstrapBatchStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dataDir() {
        return tmp.getRoot().toPath();
    }

    private static JSONObject batch(String id, String state) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("state", state);
        o.put("serverId", 1L);
        o.put("targetFurnaceLevel", 9);
        o.put("accounts", new JSONArray());
        return o;
    }

    @Test
    public void appendUpdateListActive() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        store.append(batch("bb1", "RUNNING"));
        store.append(batch("bb2", "RUNNING"));

        List<JSONObject> list = store.list(10);
        assertEquals(2, list.size());
        assertEquals("bb2", list.get(0).optString("id"));
        assertEquals("bb1", list.get(1).optString("id"));

        assertEquals("RUNNING", store.find("bb1").optString("state"));
        assertEquals("bb2", store.active().optString("id"));

        store.update(batch("bb2", "COMPLETED"));
        assertEquals("COMPLETED", store.find("bb2").optString("state"));
        assertEquals("bb1", store.active().optString("id"));

        store.update(batch("bb1", "FAILED"));
        assertNull(store.active());
        assertEquals(1, store.list(1).size());
        assertNull(store.find("nope"));
    }

    /** 任务书 §2.5：重启 RUNNING → FAILED("服务重启中断")。 */
    @Test
    public void restartMarksRunningAsFailed() {
        BootstrapBatchStore first = new BootstrapBatchStore(dataDir());
        first.append(batch("bb-run", "RUNNING"));
        first.append(batch("bb-done", "COMPLETED"));

        // 模拟重启：新实例重新 load
        BootstrapBatchStore second = new BootstrapBatchStore(dataDir());
        second.load();
        assertEquals("FAILED", second.find("bb-run").optString("state"));
        assertEquals(BootstrapBatchStore.RESTART_INTERRUPTED,
                second.find("bb-run").optString("error"));
        assertEquals("COMPLETED", second.find("bb-done").optString("state"));
        assertNull("恢复后不应再有 active", second.active());
    }

    @Test
    public void truncatedToThirtyBatches() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        for (int i = 1; i <= BootstrapBatchStore.MAX_BATCHES + 2; i++) {
            store.append(batch(String.format("bb%03d", i), "COMPLETED"));
        }
        assertEquals(BootstrapBatchStore.MAX_BATCHES, store.size());
        assertEquals("bb032", store.list(100).get(0).optString("id"));
        assertNull("最旧 bb001 应被淘汰", store.find("bb001"));
        assertNotNull("bb003 应保留", store.find("bb003"));
    }

    @Test
    public void fileAndBackupWritten() throws Exception {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        store.append(batch("bb1", "RUNNING"));
        store.update(batch("bb1", "COMPLETED"));
        Path file = dataDir().resolve("bootstrap_batches.json");
        Path backup = dataDir().resolve("bootstrap_batches.json.bak");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.isRegularFile(backup));
        JSONObject root = new JSONObject(Files.readString(file));
        assertEquals("bb1", root.optJSONArray("batches").getJSONObject(0).optString("id"));
    }

    @Test
    public void corruptedFileFallsBackToBackup() throws Exception {
        BootstrapBatchStore first = new BootstrapBatchStore(dataDir());
        first.append(batch("bb1", "RUNNING"));
        first.update(batch("bb1", "COMPLETED"));   // .bak = 上一版（RUNNING）
        Files.writeString(dataDir().resolve("bootstrap_batches.json"), "{broken");
        BootstrapBatchStore second = new BootstrapBatchStore(dataDir());
        second.load();
        assertNotNull("主文件损坏时应回退 .bak 恢复", second.find("bb1"));
        assertEquals("FAILED", second.find("bb1").optString("state"));
        assertEquals(BootstrapBatchStore.RESTART_INTERRUPTED,
                second.find("bb1").optString("error"));
    }

    @Test
    public void updateUnknownIdIgnored() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        store.update(batch("ghost", "COMPLETED"));
        assertEquals(0, store.size());
    }
}
