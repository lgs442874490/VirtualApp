package com.carlos.vaultserver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 联盟简称 → aid 取值优先级回归（2026-11-02 GLM-5.3 审查致命项）：
 * 地图昵称前缀存在纯数字简称（如 {@code [818]}），旧实现先按数字直通 →
 * 「快照回填 818 → 用户保存」会把 friendAids 写成 818，静默写坏友盟配置。
 */
public class AbbrAidPickTest {

    @Test
    public void numericAbbrIsNotTreatedAsAid() {
        // 818 是真实联盟简称，解析不到时不能当成 aid=818
        assertNull(VaultHttpApi.pickAbbrAid(null, "818"));
        assertNull(VaultHttpApi.pickAbbrAid(0L, "818"));
    }

    @Test
    public void resolvedAidWins() {
        assertEquals(Long.valueOf(89700731L), VaultHttpApi.pickAbbrAid(89700731L, "818"));
        assertEquals(Long.valueOf(89700652L), VaultHttpApi.pickAbbrAid(89700652L, "USY"));
    }

    @Test
    public void longNumericAidPassthrough() {
        // 老数据/脚本直接塞 aid（8 位）仍可用
        assertEquals(Long.valueOf(89700055L), VaultHttpApi.pickAbbrAid(null, "89700055"));
        assertEquals(Long.valueOf(1234567L), VaultHttpApi.pickAbbrAid(null, "1234567"));
    }

    @Test
    public void nonNumericUnresolvedStaysNull() {
        assertNull(VaultHttpApi.pickAbbrAid(null, "NOTEXIST"));
        assertNull(VaultHttpApi.pickAbbrAid(null, ""));
        assertNull(VaultHttpApi.pickAbbrAid(null, null));
    }
}
