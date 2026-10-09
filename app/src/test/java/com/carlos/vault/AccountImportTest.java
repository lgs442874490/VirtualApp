package com.carlos.vault;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * TC-P09 / TC-P10 / TC-P11 / TC-P12（JVM 部分）：批量导入解析。
 * 对标原版 Web 版「账号池加密串导入」的端侧等价物；解析为纯函数，可直接在宿主机断言。
 */
public class AccountImportTest {

    private static final String TOKEN = "abcdef0123456789ABCDEF"; // ≥16、无空白、不含 ----

    @Test
    public void legacyFormat_labelPipeFpid() {
        List<AccountImport.Row> rows = AccountImport.parse("大王|12345678----" + TOKEN);
        assertEquals(1, rows.size());
        AccountImport.Row r = rows.get(0);
        assertNull(r.error);
        assertEquals("12345678", r.fpid);
        assertEquals(TOKEN, r.token);
        assertEquals("大王", r.label);
        assertTrue(r.isOk());
    }

    @Test
    public void legacyFormat_fullWidthPipe() {
        List<AccountImport.Row> rows = AccountImport.parse("大王｜12345678----" + TOKEN);
        assertEquals(1, rows.size());
        assertEquals("12345678", rows.get(0).fpid);
        assertEquals("大王", rows.get(0).label);
    }

    @Test
    public void legacyFormat_noLabel_defaultsToFpid() {
        List<AccountImport.Row> rows = AccountImport.parse("12345678----" + TOKEN);
        assertEquals(1, rows.size());
        assertEquals("12345678", rows.get(0).fpid);
        // 备注为空 ⇒ 默认用号ID（沿用 AccountStore.importLine 既有约定）
        assertEquals("12345678", rows.get(0).label);
    }

    @Test
    public void tablePaste_twoLineFormat() {
        List<AccountImport.Row> rows = AccountImport.parse(
                "备注大王\taccount=12345678\nsdkToken=" + TOKEN);
        assertEquals(1, rows.size());
        AccountImport.Row r = rows.get(0);
        assertNull(r.error);
        assertEquals("12345678", r.fpid);
        assertEquals(TOKEN, r.token);
        assertEquals("备注大王", r.label);
    }

    @Test
    public void tablePaste_singleLineWithBothKeys() {
        List<AccountImport.Row> rows = AccountImport.parse(
                "account=12345678 sdkToken=" + TOKEN);
        assertEquals(1, rows.size());
        assertEquals("12345678", rows.get(0).fpid);
        assertEquals(TOKEN, rows.get(0).token);
    }

    @Test
    public void tablePaste_orphanTokenLine() {
        List<AccountImport.Row> rows = AccountImport.parse("sdkToken=" + TOKEN);
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).error);
        assertTrue(rows.get(0).error.contains("token 行没有对应的账号行"));
    }

    @Test
    public void cleanup_bomCrlfCommentHeader() {
        String text = "\uFEFF备注/账号/token 表头\r\n"
                + "# 注释行\r\n"
                + "\r\n"
                + "大王|12345678----" + TOKEN + "\r\n";
        List<AccountImport.Row> rows = AccountImport.parse(text);
        assertEquals(1, rows.size());
        assertEquals("12345678", rows.get(0).fpid);
    }

    @Test
    public void validation_fpidNotDigits() {
        List<AccountImport.Row> rows = AccountImport.parse("大王|abc123----" + TOKEN);
        // extractFpid 取末尾连续数字：abc123 → 123，仍是纯数字 ⇒ 需构造真正无数字的行
        List<AccountImport.Row> rows2 = AccountImport.parse("大王|abcdef----" + TOKEN);
        assertEquals(1, rows2.size());
        assertNotNull(rows2.get(0).error);
        assertTrue(rows2.get(0).error.contains("号ID"));
    }

    @Test
    public void validation_tokenTooShort() {
        List<AccountImport.Row> rows = AccountImport.parse("大王|12345678----shorttoken");
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).error);
        assertTrue(rows.get(0).error.contains("token"));
    }

    @Test
    public void validation_tokenContainsSeparator() {
        List<AccountImport.Row> rows = AccountImport.parse("12345678----ab" + "----" + "cd0123456789");
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).error);
    }

    @Test
    public void validation_tokenWithWhitespace() {
        List<AccountImport.Row> rows = AccountImport.parse("12345678----abcdef 0123456789");
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).error);
    }

    @Test
    public void validation_unparsableLine() {
        List<AccountImport.Row> rows = AccountImport.parse("就是一行普通文字");
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).error);
        assertTrue(rows.get(0).error.contains("无法解析"));
    }

    @Test
    public void dedupe_sameFpidKeepsFirst() {
        List<AccountImport.Row> rows = AccountImport.parse(
                "甲|12345678----" + TOKEN + "\n乙|12345678----" + TOKEN + "ZZ");
        assertEquals(2, rows.size());
        assertNull(rows.get(0).error);
        assertNotNull(rows.get(1).error);
        assertTrue(rows.get(1).error.contains("与第 1 行重复"));
    }

    @Test
    public void accountLineWithoutFollowingToken_failsValidation() {
        List<AccountImport.Row> rows = AccountImport.parse("备注\taccount=12345678");
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).error); // token 缺失 ⇒ 判失败，绝不写入
    }

    @Test
    public void headerOnlyOnce_andSecondCommentStillSkipped() {
        // 表头判定只作用于首个内容行；后续行不再按表头跳过
        List<AccountImport.Row> rows = AccountImport.parse(
                "备注\t账号\ttoken\n大王|12345678----" + TOKEN);
        assertEquals(1, rows.size());
        assertEquals("12345678", rows.get(0).fpid);
    }
}
