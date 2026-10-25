package com.carlos.vaultserver.monitor;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-25 修复回归：监控事件 seq 必须跨进程重启连续（App 增量游标依赖）。
 *
 * <p>生产实证：重启后 seq 从 1 重新计数，App 端 since=旧 seq 的增量拉取会永久漏事件。
 * 修复：安装时扫描 monitor-*.jsonl 的最大 seq 续号。
 */
public class MonitorStoreSeqTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<String, String> env() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("VAULT_MONITOR", "on");
        e.put("VAULT_MONITOR_CONTENT", "500");
        return e;
    }

    private static JSONObject event(String kind) {
        JSONObject e = new JSONObject();
        e.put("kind", kind);
        return e;
    }

    @Test
    public void seqContinuesAcrossReinstall() {
        Path data = tmp.getRoot().toPath();
        MonitorStore first = MonitorStore.install(data, env());
        long a = first.record(event("mark"));
        long b = first.record(event("mark"));
        assertEquals(1L, a);
        assertEquals(2L, b);
        first.close();

        // 模拟进程重启：重新 install（同一目录）
        MonitorStore second = MonitorStore.install(data, env());
        long c = second.record(event("mark"));
        assertTrue("重启后 seq 必须大于重启前最大值，实际=" + c + "（修复前=1）", c > b);
        assertEquals(3L, c);
        second.close();
        MonitorStore.shutdown();
    }

    @Test
    public void scanMaxSeqReadsRotatedFileToo() throws Exception {
        Path data = tmp.getRoot().toPath();
        Path dir = data.resolve("monitor");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("monitor-20261024.jsonl.1"),
                "{\"seq\":41,\"kind\":\"mark\"}\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("monitor-20261025.jsonl"),
                "{\"seq\":7,\"kind\":\"mark\"}\n{\"seq\":88,\"kind\":\"session\"}\n",
                StandardCharsets.UTF_8);

        assertEquals(88L, MonitorStore.scanMaxSeq(dir));

        MonitorStore store = MonitorStore.install(data, env());
        long next = store.record(event("mark"));
        assertEquals("应从历史最大 88 续号", 89L, next);
        store.close();
        MonitorStore.shutdown();
    }

    @Test
    public void disabledStoreRecordsNothing() {
        Map<String, String> off = new LinkedHashMap<>();
        off.put("VAULT_MONITOR", "off");
        MonitorStore store = MonitorStore.install(tmp.getRoot().toPath(), off);
        assertEquals(0L, store.record(event("mark")));
        assertEquals(0L, store.status().optLong("lastSeq", -1L));
        store.close();
        MonitorStore.shutdown();
    }
}
