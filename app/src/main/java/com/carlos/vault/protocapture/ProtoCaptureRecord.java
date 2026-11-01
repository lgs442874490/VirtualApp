package com.carlos.vault.protocapture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 一条真机抓包记录（T3）。 */
public final class ProtoCaptureRecord {
    public final long timestampMs;
    /** C2S / S2C */
    public final String direction;
    public final String proto;
    public final String summary;
    public final String full;
    public final int bytes;
    public final String rawHex;

    public ProtoCaptureRecord(long timestampMs, String direction, String proto, String summary,
                              String full, int bytes, String rawHex) {
        this.timestampMs = timestampMs;
        this.direction = direction == null ? "" : direction;
        this.proto = proto == null ? "" : proto;
        this.summary = summary == null ? "" : summary;
        this.full = full == null ? "" : full;
        this.bytes = bytes;
        this.rawHex = rawHex == null ? "" : rawHex;
    }

    public String time() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
                .format(new Date(timestampMs));
    }

    /** 列表行文案：时间 方向 协议 摘要（摘要最多 200 字）。 */
    public String listLine() {
        return time() + "  " + direction + "  " + proto + "  " + clip(summary, 200);
    }

    /** 导出文本（含完整 content）。 */
    public String exportText() {
        StringBuilder sb = new StringBuilder();
        sb.append(time()).append(' ').append(direction).append(' ').append(proto)
                .append(" bytes=").append(bytes).append('\n');
        sb.append("summary: ").append(clip(summary, 4000)).append('\n');
        if (!full.isEmpty()) sb.append("content: ").append(full).append('\n');
        if (!rawHex.isEmpty()) sb.append("raw: ").append(rawHex).append('\n');
        return sb.toString();
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
