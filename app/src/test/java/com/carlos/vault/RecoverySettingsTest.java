package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 顶号恢复策略单测（P1 契约 §一/§三.3，纯 JVM）：
 * 默认值、范围校验（错误文案逐字对齐服务端 3002）、冷却下拉映射、
 * {@code account.recovery} 解析与旧服务端缺字段容错。
 */
public class RecoverySettingsTest {

    // ---------- 默认值（契约 §三.3 下拉/输入框默认） ----------

    @Test
    public void defaults_matchContract() {
        RecoverySettings d = RecoverySettings.defaults();
        assertTrue(d.enabled);
        assertEquals(30, d.initialDelayMinutes);
        assertEquals(5, d.retryIntervalSeconds);
        assertEquals(3, d.maxAttempts);
        assertEquals(15, d.cooldownMinutes);
        assertTrue(d.valid());
    }

    // ---------- validate：合法与逐字段错误（文案逐字） ----------

    @Test
    public void validate_valid_returnsNull() {
        assertNull(RecoverySettings.validate(1, 1, 0, 15));
        assertNull(RecoverySettings.validate(1440, 300, 10, 1440));
        assertNull(RecoverySettings.validate(30, 5, 3, 60));
    }

    @Test
    public void validate_delayRange_exactServerText() {
        assertEquals(RecoverySettings.ERR_DELAY,
                RecoverySettings.validate(0, 5, 3, 15));
        assertEquals(RecoverySettings.ERR_DELAY,
                RecoverySettings.validate(1441, 5, 3, 15));
        assertEquals("顶号后等待必须在 1 到 1440 分钟之间", RecoverySettings.ERR_DELAY);
    }

    @Test
    public void validate_retryRange_exactServerText() {
        assertEquals(RecoverySettings.ERR_RETRY,
                RecoverySettings.validate(30, 0, 3, 15));
        assertEquals(RecoverySettings.ERR_RETRY,
                RecoverySettings.validate(30, 301, 3, 15));
        assertEquals("重试间隔秒数必须在 1 到 300 之间", RecoverySettings.ERR_RETRY);
    }

    @Test
    public void validate_attemptsRange_exactServerText() {
        assertEquals(RecoverySettings.ERR_ATTEMPTS,
                RecoverySettings.validate(30, 5, -1, 15));
        assertEquals(RecoverySettings.ERR_ATTEMPTS,
                RecoverySettings.validate(30, 5, 11, 15));
        assertEquals("连续尝试次数必须在 0 到 10 之间", RecoverySettings.ERR_ATTEMPTS);
    }

    @Test
    public void validate_cooldownWhitelist_exactServerText() {
        // 只允许 15/30/60/120/1440；30 分钟不在白名单 → 服务端同文案
        assertEquals(RecoverySettings.ERR_COOLDOWN,
                RecoverySettings.validate(30, 5, 3, 20));
        assertEquals("冷却时长只支持 15 分钟、30 分钟、1 小时、2 小时或 24 小时",
                RecoverySettings.ERR_COOLDOWN);
    }

    @Test
    public void validate_firstErrorWins_inFieldOrder() {
        // 等待与间隔同时非法 → 先报等待（delay → retry → attempts → cooldown）
        assertEquals(RecoverySettings.ERR_DELAY,
                RecoverySettings.validate(0, 0, 99, 20));
        assertEquals(RecoverySettings.ERR_RETRY,
                RecoverySettings.validate(30, 0, 99, 20));
        assertEquals(RecoverySettings.ERR_ATTEMPTS,
                RecoverySettings.validate(30, 5, 99, 20));
    }

    // ---------- 冷却下拉映射（§三.3：15 分钟/30 分钟/1 小时/2 小时/24 小时） ----------

    @Test
    public void cooldownLabels_matchContractDropdown() {
        assertEquals("15 分钟", RecoverySettings.cooldownLabel(15));
        assertEquals("30 分钟", RecoverySettings.cooldownLabel(30));
        assertEquals("1 小时", RecoverySettings.cooldownLabel(60));
        assertEquals("2 小时", RecoverySettings.cooldownLabel(120));
        assertEquals("24 小时", RecoverySettings.cooldownLabel(1440));
    }

    @Test
    public void cooldownValue_roundtripAndUnknown() {
        assertEquals(15, RecoverySettings.cooldownValue("15 分钟"));
        assertEquals(60, RecoverySettings.cooldownValue("1 小时"));
        assertEquals(1440, RecoverySettings.cooldownValue("24 小时"));
        assertEquals(-1, RecoverySettings.cooldownValue("20 分钟"));
        assertEquals(-1, RecoverySettings.cooldownValue(null));
        // 全部下拉项往返一致
        for (int c : RecoverySettings.COOLDOWNS) {
            assertEquals(c, RecoverySettings.cooldownValue(RecoverySettings.cooldownLabel(c)));
        }
    }

    @Test
    public void isAllowedCooldown_whitelistOnly() {
        assertTrue(RecoverySettings.isAllowedCooldown(15));
        assertTrue(RecoverySettings.isAllowedCooldown(1440));
        assertFalse(RecoverySettings.isAllowedCooldown(0));
        assertFalse(RecoverySettings.isAllowedCooldown(20));
        assertFalse(RecoverySettings.isAllowedCooldown(2880));
    }

    // ---------- account.recovery 解析与旧服务端容错 ----------

    @Test
    public void fromAccount_fullRecoveryObject() throws Exception {
        JSONObject acc = new JSONObject("{\"fpid\":\"1\",\"recovery\":{"
                + "\"enabled\":false,\"initialDelayMinutes\":60,"
                + "\"retryIntervalSeconds\":10,\"maxAttempts\":5,\"cooldownMinutes\":120}}");
        RecoverySettings s = RecoverySettings.fromAccount(acc);
        assertFalse(s.enabled);
        assertEquals(60, s.initialDelayMinutes);
        assertEquals(10, s.retryIntervalSeconds);
        assertEquals(5, s.maxAttempts);
        assertEquals(120, s.cooldownMinutes);
    }

    @Test
    public void fromAccount_missingRecovery_fallsBackDefaults() throws Exception {
        // 旧服务端没有 recovery 字段 → 全默认（不崩、不填非法初值）
        RecoverySettings s = RecoverySettings.fromAccount(new JSONObject("{\"fpid\":\"1\"}"));
        RecoverySettings d = RecoverySettings.defaults();
        assertEquals(d.enabled, s.enabled);
        assertEquals(d.initialDelayMinutes, s.initialDelayMinutes);
        assertEquals(d.retryIntervalSeconds, s.retryIntervalSeconds);
        assertEquals(d.maxAttempts, s.maxAttempts);
        assertEquals(d.cooldownMinutes, s.cooldownMinutes);
    }

    @Test
    public void fromAccount_nullAccount_fallsBackDefaults() {
        assertEquals(RecoverySettings.defaults().initialDelayMinutes,
                RecoverySettings.fromAccount(null).initialDelayMinutes);
    }

    @Test
    public void fromAccount_partialFields_mixDefaults() throws Exception {
        // 只带部分字段：缺项逐项回落默认
        JSONObject acc = new JSONObject("{\"recovery\":{\"enabled\":false,"
                + "\"initialDelayMinutes\":120}}");
        RecoverySettings s = RecoverySettings.fromAccount(acc);
        assertFalse(s.enabled);
        assertEquals(120, s.initialDelayMinutes);
        assertEquals(RecoverySettings.DEFAULT_RETRY_SECONDS, s.retryIntervalSeconds);
        assertEquals(RecoverySettings.DEFAULT_MAX_ATTEMPTS, s.maxAttempts);
        assertEquals(RecoverySettings.DEFAULT_COOLDOWN_MINUTES, s.cooldownMinutes);
    }

    @Test
    public void fromAccount_outOfRangeValues_fallBackDefaults() throws Exception {
        // 脏数据（范围外）不给输入框填非法初值
        JSONObject acc = new JSONObject("{\"recovery\":{\"initialDelayMinutes\":99999,"
                + "\"retryIntervalSeconds\":0,\"maxAttempts\":99,\"cooldownMinutes\":20}}");
        RecoverySettings s = RecoverySettings.fromAccount(acc);
        assertEquals(RecoverySettings.DEFAULT_DELAY_MINUTES, s.initialDelayMinutes);
        assertEquals(RecoverySettings.DEFAULT_RETRY_SECONDS, s.retryIntervalSeconds);
        assertEquals(RecoverySettings.DEFAULT_MAX_ATTEMPTS, s.maxAttempts);
        assertEquals(RecoverySettings.DEFAULT_COOLDOWN_MINUTES, s.cooldownMinutes);
        assertTrue(s.valid());
    }

    // ---------- 保存成功 toast（契约 §三.3 逐字） ----------

    @Test
    public void savedToast_exactText() {
        assertEquals("顶号恢复设置已保存并立即生效", RecoverySettings.MSG_SAVED);
    }
}
