package com.carlos.vault;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TC（P2-1）：崩溃环形缓冲的截断语义。
 * 崩溃处理路径不能因为日志无限增长拖垮存储；只保留最近 N 条。
 */
public class CrashGuardTest {

    @Test
    public void trim_keepsLastN() {
        String content = "e1\n---CRASH---\ne2\n---CRASH---\ne3";
        assertEquals("e2\n---CRASH---\ne3", CrashGuard.trimRing(content, 2));
        assertEquals(content, CrashGuard.trimRing(content, 3));
        assertEquals(content, CrashGuard.trimRing(content, 10));
    }

    @Test
    public void trim_degenerateInputs() {
        assertEquals("", CrashGuard.trimRing("", 3));
        assertEquals("", CrashGuard.trimRing(null, 3));
        assertEquals("", CrashGuard.trimRing("e1", 0));
        assertEquals("e1", CrashGuard.trimRing("e1", 1));
    }
}
