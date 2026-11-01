package com.carlos.vault.protocapture;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 抓包摘要/可读 JSON/hex 纯函数。 */
public class ProtoCaptureLogTest {

    @Test
    public void toReadable_mapListEscapes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", 123L);
        m.put("name", "a\"b\nc");
        m.put("list", Arrays.asList(1, "x"));
        String s = ProtoCaptureLog.toReadable(m);
        assertTrue(s.contains("\"uid\":123"));
        assertTrue(s.contains("a\\\"b\\nc"));
        assertTrue(s.contains("[1,\"x\"]"));
    }

    @Test
    public void hex_truncatesAndFormats() {
        assertEquals("0102ff", ProtoCaptureLog.hex(new byte[]{1, 2, (byte) 0xFF}, 64));
        String h = ProtoCaptureLog.hex(new byte[]{1, 2, 3, 4, 5}, 3);
        assertEquals("010203…", h);
    }

    @Test
    public void record_summaryClipAndExport() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) longText.append('x');
        ProtoCaptureRecord r = new ProtoCaptureRecord(1_700_000_000_000L, "C2S", "loginauth",
                longText.toString(), "{\"a\":1}", 123, "");
        assertTrue(r.listLine().contains("C2S"));
        assertTrue(r.listLine().contains("loginauth"));
        assertTrue("摘要最多200", r.listLine().length() < 260);
        String e = r.exportText();
        assertTrue(e.contains("bytes=123"));
        assertTrue(e.contains("content: {\"a\":1}"));
        assertEquals("", ProtoCaptureRecord.clip(null, 10));
        assertEquals("abc…", ProtoCaptureRecord.clip("abcdef", 3));
    }

    @Test
    public void log_bufferCapsAt500() {
        ProtoCaptureLog.clear();
        for (int i = 0; i < 510; i++) {
            ProtoCaptureLog.add(new ProtoCaptureRecord(i, "S2C", "p" + i, "s", "", 1, ""));
        }
        assertEquals(500, ProtoCaptureLog.size());
        assertEquals("最新在前", "p509", ProtoCaptureLog.snapshot().get(0).proto);
        ProtoCaptureLog.clear();
    }
}
