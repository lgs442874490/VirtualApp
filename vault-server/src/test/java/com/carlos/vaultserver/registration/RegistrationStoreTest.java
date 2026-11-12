package com.carlos.vaultserver.registration;

import org.json.JSONArray;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RegistrationStore} 单测（任务书 §2.3）：append/update/list/active、重启恢复
 * （RUNNING → FAILED 服务重启中断）、30 条截断、原子写 + .bak。
 */
public class RegistrationStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dataDir() {
        return tmp.getRoot().toPath();
    }

    private static JSONObject batch(String id, String state) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("state", state);
        o.put("goalCount", 1);
        o.put("phones", new JSONArray());
        return o;
    }

    @Test
    public void appendUpdateListActive() {
        RegistrationStore store = new RegistrationStore(dataDir());
        store.append(batch("b1", "RUNNING"));
        store.append(batch("b2", "RUNNING"));

        // list：最近在前
        List<JSONObject> list = store.list(10);
        assertEquals(2, list.size());
        assertEquals("b2", list.get(0).optString("id"));
        assertEquals("b1", list.get(1).optString("id"));

        // find / active
        assertEquals("RUNNING", store.find("b1").optString("state"));
        assertEquals("b2", store.active().optString("id"));

        // update：终态后 active 落到 b1
        store.update(batch("b2", "COMPLETED"));
        assertEquals("COMPLETED", store.find("b2").optString("state"));
        assertEquals("b1", store.active().optString("id"));

        // 全部终态 → active 为 null
        store.update(batch("b1", "FAILED"));
        assertNull(store.active());

        // limit 生效
        assertEquals(1, store.list(1).size());
        assertNull(store.find("nope"));
    }

    @Test
    public void restartMarksRunningAndWaitingAsFailed() {
        RegistrationStore first = new RegistrationStore(dataDir());
        first.append(batch("b-run", "RUNNING"));
        first.append(batch("b-wait", "WAITING"));
        first.append(batch("b-done", "COMPLETED"));

        // 模拟重启：新实例重新 load
        RegistrationStore second = new RegistrationStore(dataDir());
        second.load();
        assertEquals("FAILED", second.find("b-run").optString("state"));
        assertEquals(RegistrationStore.RESTART_INTERRUPTED,
                second.find("b-run").optString("error"));
        assertEquals("FAILED", second.find("b-wait").optString("state"));
        assertEquals(RegistrationStore.RESTART_INTERRUPTED,
                second.find("b-wait").optString("error"));
        assertEquals("COMPLETED", second.find("b-done").optString("state"));
        assertNull("恢复后不应再有 active", second.active());
    }

    @Test
    public void truncatedToThirtyBatches() {
        RegistrationStore store = new RegistrationStore(dataDir());
        for (int i = 1; i <= RegistrationStore.MAX_BATCHES + 2; i++) {
            store.append(batch(String.format("b%03d", i), "COMPLETED"));
        }
        assertEquals(RegistrationStore.MAX_BATCHES, store.size());
        List<JSONObject> list = store.list(100);
        assertEquals(RegistrationStore.MAX_BATCHES, list.size());
        assertEquals("b032", list.get(0).optString("id"));   // 最新保留
        assertNull("最旧 b001 应被淘汰", store.find("b001"));
        assertNotNull("b003 应保留", store.find("b003"));
    }

    @Test
    public void fileAndBackupWritten() throws Exception {
        RegistrationStore store = new RegistrationStore(dataDir());
        store.append(batch("b1", "RUNNING"));   // 第一次写：只有主文件
        store.update(batch("b1", "COMPLETED")); // 第二次写：产生 .bak
        Path file = dataDir().resolve("registration_batches.json");
        Path backup = dataDir().resolve("registration_batches.json.bak");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.isRegularFile(backup));
        // 主文件内容合法 JSON 且含批次
        JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("b1", root.optJSONArray("batches").getJSONObject(0).optString("id"));
        // .bak 是上一次内容（RUNNING）
        JSONObject bak = new JSONObject(Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals("RUNNING",
                bak.optJSONArray("batches").getJSONObject(0).optString("state"));
    }

    @Test
    public void corruptedFileFallsBackToBackup() throws Exception {
        RegistrationStore first = new RegistrationStore(dataDir());
        first.append(batch("b1", "RUNNING"));
        first.update(batch("b1", "COMPLETED"));   // .bak = 上一版（RUNNING）
        // 写坏主文件
        Files.writeString(dataDir().resolve("registration_batches.json"), "{broken",
                StandardCharsets.UTF_8);
        RegistrationStore second = new RegistrationStore(dataDir());
        second.load();
        // .bak 里 b1 是 RUNNING → 重启恢复规则把它标记为 FAILED(服务重启中断)
        assertNotNull("主文件损坏时应回退 .bak 恢复", second.find("b1"));
        assertEquals("FAILED", second.find("b1").optString("state"));
        assertEquals(RegistrationStore.RESTART_INTERRUPTED,
                second.find("b1").optString("error"));
    }

    @Test
    public void updateUnknownIdIgnored() {
        RegistrationStore store = new RegistrationStore(dataDir());
        store.update(batch("ghost", "COMPLETED"));   // 未 append 过：静默忽略
        assertEquals(0, store.size());
    }
}
