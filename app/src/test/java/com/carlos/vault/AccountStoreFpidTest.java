package com.carlos.vault;

import org.junit.Test;

import static org.junit.Assert.*;

/** TC-P15：fpid 提取容错（老数据 fpid 混入备注的自修复基础）。 */
public class AccountStoreFpidTest {

    @Test
    public void extractFpid_tailDigits() {
        assertEquals("81444913", AccountStore.extractFpid("kk大王｜⇥81444913"));
        assertEquals("12345678", AccountStore.extractFpid("12345678"));
        assertEquals("12345678", AccountStore.extractFpid("abc 12345678"));
    }

    @Test
    public void extractFpid_noDigits() {
        assertEquals("", AccountStore.extractFpid("没有数字"));
        assertEquals("", AccountStore.extractFpid(""));
        assertEquals("", AccountStore.extractFpid(null));
    }

    @Test
    public void extractFpid_mixedTakesLastRun() {
        // 取「末尾连续数字」：前面出现的数字段不干扰
        assertEquals("777", AccountStore.extractFpid("12abc34...777"));
    }
}
