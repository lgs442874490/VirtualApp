package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

/**
 * 全量同步骤减保护（2026-11-08）：多设备/多 App 实例共用身份库时，本地账号较少的
 * 一次全量 sync 会把其它设备账号整库删掉（生产 20:02:40：5 账号 → 1 账号）。
 * 规则：缺失「已有且有 token」账号数 ≥2 且超过半数 → 拒绝；显式删除走 deletedFpids。
 */
public class VaultHttpApiShrinkGuardTest {

    @Test
    public void reject_whenMissingMajorityAndAtLeastTwo() {
        // 生产事故形态：服务端 5 个账号，incoming 只带 1 个 → 缺失 4 个
        assertTrue(VaultHttpApi.shouldRejectShrink(5, 4));
        assertTrue(VaultHttpApi.shouldRejectShrink(3, 2));
        assertTrue(VaultHttpApi.shouldRejectShrink(7, 4));
    }

    @Test
    public void allow_whenMissingMinorityOrSingle() {
        // 缺失 1 个不拦（对齐另一实例单号/正常恢复）
        assertFalse(VaultHttpApi.shouldRejectShrink(5, 1));
        assertFalse(VaultHttpApi.shouldRejectShrink(1, 1));
        // 恰好一半不拦（5 个缺 2 个：2*2=4 不大于 5）
        assertFalse(VaultHttpApi.shouldRejectShrink(5, 2));
        // 6 缺 3（恰好一半）不拦
        assertFalse(VaultHttpApi.shouldRejectShrink(6, 3));
    }

    @Test
    public void deletedFpids_parsedFromCommaAndWhitespace() {
        Set<String> s = VaultHttpApi.parseFpidSet("48460403, 53008052  86885582");
        assertEquals(3, s.size());
        assertTrue(s.contains("48460403"));
        assertTrue(s.contains("53008052"));
        assertTrue(s.contains("86885582"));
        assertTrue(VaultHttpApi.parseFpidSet("").isEmpty());
        assertTrue(VaultHttpApi.parseFpidSet(null).isEmpty());
    }
}
