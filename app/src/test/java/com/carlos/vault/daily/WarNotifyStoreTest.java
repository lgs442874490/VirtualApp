package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

/**
 * 战争通知 App 侧存储单测（mockito 桩 SharedPreferences，模式同 AccountPoolCacheTest）：
 * 已发 eventId 环形（cap 200 + 去重条目）、节流时刻落盘、手机号 trim。
 */
public class WarNotifyStoreTest {

    private Context ctx;
    private SharedPreferences sp;
    private SharedPreferences.Editor ed;

    @Before
    public void setUp() {
        ctx = mock(Context.class);
        sp = mock(SharedPreferences.class);
        ed = mock(SharedPreferences.Editor.class);
        when(ctx.getApplicationContext()).thenReturn(ctx);
        when(ctx.getSharedPreferences("war_notify", Context.MODE_PRIVATE)).thenReturn(sp);
        when(sp.edit()).thenReturn(ed);
        when(ed.putString(anyString(), anyString())).thenReturn(ed);
        when(ed.putLong(anyString(), anyLong())).thenReturn(ed);
    }

    private static String idsJson(String... values) {
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        return arr.toString();
    }

    @Test
    public void markSent_appendsAndStampsThrottle() throws Exception {
        when(sp.getString("sent_ids", "[]")).thenReturn(idsJson("w1"));
        WarNotifyStore.markSent(ctx, "w2", 555L);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(ed).putString(eq("sent_ids"), cap.capture());
        JSONArray arr = new JSONArray(cap.getValue());
        assertEquals(2, arr.length());
        assertEquals("w1", arr.optString(0));
        assertEquals("w2", arr.optString(1));
        verify(ed).putLong("last_sms_at", 555L);
        verify(ed).apply();
    }

    @Test
    public void markSent_dedupesSameEventId() throws Exception {
        when(sp.getString("sent_ids", "[]")).thenReturn(idsJson("w1", "w2"));
        WarNotifyStore.markSent(ctx, "w1", 1L);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(ed).putString(eq("sent_ids"), cap.capture());
        JSONArray arr = new JSONArray(cap.getValue());
        assertEquals(2, arr.length());
        assertEquals("w2", arr.optString(0));   // w1 被移到末尾，无重复
        assertEquals("w1", arr.optString(1));
    }

    @Test
    public void markSent_trimsToCap200() throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < WarNotifyGate.SENT_CAP + 3; i++) arr.put("old" + i);
        when(sp.getString("sent_ids", "[]")).thenReturn(arr.toString());
        WarNotifyStore.markSent(ctx, "newest", 2L);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(ed).putString(eq("sent_ids"), cap.capture());
        JSONArray out = new JSONArray(cap.getValue());
        assertEquals(WarNotifyGate.SENT_CAP, out.length());
        assertEquals("old4", out.optString(0));      // 203+1=204 条裁到 200，最旧 4 条被裁
        assertEquals("newest", out.optString(WarNotifyGate.SENT_CAP - 1));
    }

    @Test
    public void markSent_emptyEventIdOnlyStampsThrottle() {
        when(sp.getString("sent_ids", "[]")).thenReturn("[]");
        WarNotifyStore.markSent(ctx, "", 7L);
        verify(ed).putLong("last_sms_at", 7L);
        verify(ed).putString(anyString(), anyString());   // 空表也回写（无害）
    }

    @Test
    public void sentIds_parsesPersisted() {
        when(sp.getString("sent_ids", "[]")).thenReturn(idsJson("a", "b"));
        Set<String> s = WarNotifyStore.sentIds(ctx);
        assertEquals(2, s.size());
        assertTrue(s.contains("a"));
    }

    @Test
    public void smsPhone_trims() {
        when(sp.getString("sms_phone", "")).thenReturn(" 13812345678 ");
        assertEquals("13812345678", WarNotifyStore.smsPhone(ctx));
        when(sp.getString("sms_phone", "")).thenReturn(null);
        assertEquals("", WarNotifyStore.smsPhone(ctx));
    }
}
