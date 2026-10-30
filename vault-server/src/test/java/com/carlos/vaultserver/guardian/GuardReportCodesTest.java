package com.carlos.vaultserver.guardian;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 C A4：服务端战报码表（1:1 对标 App GuardReportCodes）与 notify_new_mail_syn 解析。
 *
 * <p>铁律：未收录只记 unknown（不判成败），任何分支都不应改变撤防/任务状态（由 Engine 用例覆盖）。
 */
public class GuardReportCodesTest {

    // ==================== 默认码表 ====================

    @Test
    public void defaultMapping_battleFirstAndVerdicts() {
        GuardReportCodes.Mapping m = new GuardReportCodes.Mapping();
        assertTrue("first=30 是战斗/防守战报", m.isBattleFirst(30L));
        assertFalse("first=31 未收录为战报类型", m.isBattleFirst(31L));
        assertEquals("战斗/防守战报", m.roleOf(30L));
        assertEquals(1, m.roleCount());
        assertEquals(5, m.secondSize());

        assertEquals(GuardReportCodes.WIN, m.verdictOf(30L, 4L));
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30L, 5L));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30L, 6L));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30L, 7L));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30L, 8L));
        assertNull("未收录 second=1 → 不判成败", m.verdictOf(30L, 1L));
        assertNull("未收录 second=99", m.verdictOf(30L, 99L));
    }

    // ==================== params 覆盖 ====================

    @Test
    public void override_rolesSecondAndByFirst() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(
                "{\"roles\":{\"31\":\"集结战报\"},"
                        + "\"second\":{\"1\":\"loss\",\"9\":\"garbage\"},"
                        + "\"by_first\":{\"30\":{\"5\":\"unknown\",\"4\":\"loss\"}}}");
        assertTrue("覆盖新增战报 first=31", m.isBattleFirst(31L));
        assertEquals("集结战报", m.roleOf(31L));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30L, 1L));
        assertEquals("覆盖 by_first 最高优先级", GuardReportCodes.UNKNOWN, m.verdictOf(30L, 5L));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30L, 4L));
        assertEquals("默认值仍在（未覆盖项不被清空）", GuardReportCodes.LOSS, m.verdictOf(30L, 6L));
        assertEquals("非法值归一为 unknown", GuardReportCodes.UNKNOWN, m.verdictOf(31L, 9L));
    }

    @Test
    public void override_invalidJsonFallsBackToDefaults() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse("{not-json");
        assertEquals(1, m.roleCount());
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30L, 5L));
        assertNull(m.verdictOf(30L, 1L));
        // 空/ null → 默认
        assertEquals(5, GuardReportCodes.Mapping.parse("").secondSize());
        assertEquals(5, GuardReportCodes.Mapping.parse(null).secondSize());
    }

    // ==================== 推送形状解析 ====================

    private static Map<String, Object> cover(long first, long second, long id, String title) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("first_type", first);
        c.put("second_type", second);
        c.put("mailid_new", id);
        c.put("title", title);
        return c;
    }

    @Test
    public void parseMails_supportedShapes() {
        // 1) 顶层 mail_cover_info
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("mail_cover_info", cover(30L, 5L, 1001L, "防守胜利"));
        List<GuardReportCodes.ReportMail> m1 = GuardReportCodes.parseMails(c1);
        assertEquals(1, m1.size());
        assertEquals(30L, m1.get(0).firstType);
        assertEquals(5L, m1.get(0).secondType);
        assertEquals(1001L, m1.get(0).mailId);
        assertEquals("防守胜利", m1.get(0).title);

        // 2) mail_cover_info 为 List
        Map<String, Object> c2 = new LinkedHashMap<>();
        List<Object> coverList = new ArrayList<>();
        coverList.add(cover(30L, 6L, 1002L, "防守失败"));
        c2.put("mail_cover_info", coverList);
        assertEquals(1, GuardReportCodes.parseMails(c2).size());

        // 3) infolist 列表，每项含 mail_cover_info
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("mail_cover_info", cover(30L, 4L, 1003L, ""));
        Map<String, Object> c3 = new LinkedHashMap<>();
        c3.put("infolist", List.of(mail));
        List<GuardReportCodes.ReportMail> m3 = GuardReportCodes.parseMails(c3);
        assertEquals(1, m3.size());
        assertEquals(4L, m3.get(0).secondType);

        // 4) 直接 first_type/second_type（无 cover 包装）
        Map<String, Object> c4 = new LinkedHashMap<>();
        c4.put("first_type", 30L);
        c4.put("second_type", 7L);
        c4.put("id", 1004L);
        assertEquals(1, GuardReportCodes.parseMails(c4).size());

        // 5) 空/无字段 → 空列表
        assertTrue(GuardReportCodes.parseMails(new LinkedHashMap<>()).isEmpty());
        assertTrue(GuardReportCodes.parseMails(null).isEmpty());
    }

    @Test
    public void parseMails_ignoresInvalidAndClips() {
        Map<String, Object> coverBad = new LinkedHashMap<>();
        coverBad.put("first_type", 0L);
        coverBad.put("second_type", 0L);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("mail_cover_info", coverBad);
        assertTrue("first/second 都为 0 → 不是有效邮件", GuardReportCodes.parseMails(c).isEmpty());

        Map<String, Object> longTitle = cover(30L, 5L, 9L, "x".repeat(500));
        Map<String, Object> c2 = new LinkedHashMap<>();
        c2.put("mail_cover_info", longTitle);
        String title = GuardReportCodes.parseMails(c2).get(0).title;
        assertTrue("标题截断 ≤120", title.length() <= 120);
    }
}
