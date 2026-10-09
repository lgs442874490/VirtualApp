package com.carlos.vault;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * TC-P13：账号分组管理纯逻辑（未分组置顶不可折叠 / 删组不删条目 / 拖拽规则 / 组名校验）。
 */
public class EntryLayoutTest {

    private static List<EntryLayout.Item> items(String... idsWithGroup) {
        List<EntryLayout.Item> l = new ArrayList<>();
        for (int i = 0; i < idsWithGroup.length; i += 2) {
            l.add(new EntryLayout.Item(idsWithGroup[i], idsWithGroup[i + 1]));
        }
        return l;
    }

    @Test
    public void buildRows_ungroupedFirstAndCannotCollapse() {
        List<EntryLayout.Item> items = items("a", "", "b", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);

        assertEquals(4, rows.size());
        assertEquals(EntryLayout.TYPE_HEADER, rows.get(0).type);
        assertTrue(rows.get(0).ungrouped);           // 未分组置顶
        assertTrue(rows.get(0).collapsed == false);  // 未分组不可折叠（恒展开）
        assertEquals("a", rows.get(1).itemId);
        assertEquals(EntryLayout.TYPE_HEADER, rows.get(2).type);
        assertFalse(rows.get(2).ungrouped);
        assertEquals("b", rows.get(3).itemId);
    }

    @Test
    public void buildRows_emptyGroupStillShown_orphanGroupAppended() {
        List<EntryLayout.Item> items = items("a", "G9"); // G9 未登记 ⇒ 孤儿组按首现追加
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);
        // 表头：未分组(恒置顶，空也显示) → G1(空也显示) → G9(孤儿组)
        assertEquals(4, rows.size());
        assertTrue(rows.get(0).ungrouped);
        assertEquals("G1", rows.get(1).title);
        assertEquals("G9", rows.get(2).title);
        assertEquals("a", rows.get(3).itemId);
        // 条目绝不消失
        long entryCount = 0;
        for (EntryLayout.Row r : rows) if (r.type == EntryLayout.TYPE_ENTRY) entryCount++;
        assertEquals(1, entryCount);
    }

    @Test
    public void buildRows_collapsedGroupHidesMembers() {
        List<EntryLayout.Item> items = items("a", "G1", "b", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", true));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);
        // 未分组表头恒显示（空）+ 折叠的 G1 表头；成员隐藏
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).ungrouped);
        assertEquals("G1", rows.get(1).title);
        assertTrue(rows.get(1).collapsed);
    }

    @Test
    public void drag_entryOntoEntry_insertsBefore_sameGroup() {
        List<EntryLayout.Item> items = items("a", "", "b", "G1", "c", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);
        // rows: 0=H(未分组) 1=a 2=H(G1) 3=b 4=c ；把 c(4) 拖到 b(3) 上
        boolean changed = EntryLayout.applyDrag(items, rows, 4, 3);
        assertTrue(changed);
        // c 插到 b 前，组不变
        assertEquals("c", items.get(1).id);
        assertEquals("b", items.get(2).id);
        assertEquals("G1", items.get(1).group);
        assertEquals("G1", items.get(2).group);
    }

    @Test
    public void drag_ontoExpandedHeader_movesToGroupHead() {
        List<EntryLayout.Item> items = items("a", "", "b", "G1", "c", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);
        // 把 a(1) 拖到 H(G1)(2) 上 ⇒ a 移入 G1 组首
        boolean changed = EntryLayout.applyDrag(items, rows, 1, 2);
        assertTrue(changed);
        assertEquals("G1", items.get(0).group);
        assertEquals("a", items.get(0).id);
    }

    @Test
    public void drag_ontoCollapsedHeader_rejected() {
        List<EntryLayout.Item> items = items("a", "", "b", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", true));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);
        boolean changed = EntryLayout.applyDrag(items, rows, 1, 2);
        assertFalse("折叠表头作落点必须拒绝", changed);
        assertEquals("", items.get(0).group); // 无副作用
    }

    @Test
    public void drag_headerAsSource_rejected() {
        List<EntryLayout.Item> items = items("a", "", "b", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        List<EntryLayout.Row> rows = EntryLayout.buildRows(items, meta);
        assertFalse(EntryLayout.applyDrag(items, rows, 0, 1));
    }

    @Test
    public void validateGroupName_rules() {
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        assertNotNull(EntryLayout.validateGroupName(meta, ""));
        assertNotNull(EntryLayout.validateGroupName(meta, null));
        assertNotNull(EntryLayout.validateGroupName(meta, "未分组")); // 保留名
        assertNotNull(EntryLayout.validateGroupName(meta, "G1"));     // 重名
        assertNull(EntryLayout.validateGroupName(meta, "G2"));
    }

    @Test
    public void validateRename_rules() {
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        assertNotNull(EntryLayout.validateRename(meta, "G9", "G8")); // 原组不存在
        assertNotNull(EntryLayout.validateRename(meta, "G1", "G1")); // 同名
        assertNotNull(EntryLayout.validateRename(meta, "G1", "未分组"));
        assertNull(EntryLayout.validateRename(meta, "G1", "G1new"));
    }

    @Test
    public void renameInPlace_updatesMetaAndMembersAtomically() {
        List<EntryLayout.Item> items = items("a", "G1", "b", "G1", "c", "");
        List<EntryLayout.Group> meta = new ArrayList<>(Arrays.asList(new EntryLayout.Group("G1", false)));
        assertTrue(EntryLayout.renameInPlace(meta, items, "G1", "G2"));
        assertEquals("G2", meta.get(0).name);
        assertEquals("G2", items.get(0).group);
        assertEquals("G2", items.get(1).group);
        assertEquals("", items.get(2).group); // 非成员不动

        // 校验不通过 ⇒ 不做任何改动
        assertFalse(EntryLayout.renameInPlace(meta, items, "G2", "未分组"));
        assertEquals("G2", meta.get(0).name);
    }

    @Test
    public void deleteInPlace_membersFallBackToUngrouped_neverDeleted() {
        List<EntryLayout.Item> items = items("a", "G1", "b", "");
        List<EntryLayout.Group> meta = new ArrayList<>(Arrays.asList(new EntryLayout.Group("G1", false)));
        assertTrue(EntryLayout.deleteInPlace(meta, items, "G1"));
        assertTrue(meta.isEmpty());
        assertEquals("", items.get(0).group); // 退回未分组
        assertEquals(2, items.size());       // 条目绝不删除
        assertFalse(EntryLayout.deleteInPlace(meta, items, "G1")); // 已不存在
    }

    @Test
    public void toggleCollapsed_flipsState() {
        List<EntryLayout.Group> meta = Arrays.asList(new EntryLayout.Group("G1", false));
        assertTrue(EntryLayout.toggleCollapsed(meta, "G1"));
        assertFalse(EntryLayout.toggleCollapsed(meta, "G1"));
        assertFalse(EntryLayout.toggleCollapsed(meta, "G9")); // 不存在的组
    }

    @Test
    public void orderedGroupKeys_metaOrderPlusOrphans() {
        List<EntryLayout.Item> items = items("a", "G9", "b", "G1");
        List<EntryLayout.Group> meta = Arrays.asList(
                new EntryLayout.Group("G1", false), new EntryLayout.Group("G2", false));
        List<String> keys = EntryLayout.orderedGroupKeys(items, meta);
        // 未分组总是第一，其余按 meta 顺序，孤儿组按首现追加
        assertEquals(Arrays.asList("", "G1", "G2", "G9"), keys);
    }
}
