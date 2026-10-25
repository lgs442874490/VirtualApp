package com.carlos.vault.wjdr;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机监控 JsonText 单测（2026-10-25）：平台中立安全 JSON 序列化的形状/防御性契约。
 *
 * <p>覆盖：Map/List/Collection/数组/数字键/String 转义/Number/Boolean/null、循环引用、
 * 深度上限、长度截断 + trunc 标记、toString 抛异常兜底、整体异常安全占位。
 */
public class JsonTextTest {

    @Test
    public void scalars_basicLiterals() {
        assertEquals("null", JsonText.toJson(null));
        assertEquals("true", JsonText.toJson(Boolean.TRUE));
        assertEquals("false", JsonText.toJson(Boolean.FALSE));
        assertEquals("42", JsonText.toJson(42));
        assertEquals("-7", JsonText.toJson(-7L));
        assertEquals("3.5", JsonText.toJson(3.5d));
        assertEquals("\"文本\"", JsonText.toJson("文本"));
        assertEquals("\"abc\"", JsonText.toJson(new StringBuilder("abc")));
        assertEquals("\"RED\"", JsonText.toJson(Color.RED));
    }

    private enum Color { RED }

    @Test
    public void string_escapesSpecials() {
        assertEquals("\"a\\\"b\"", JsonText.toJson("a\"b"));
        assertEquals("\"a\\\\b\"", JsonText.toJson("a\\b"));
        assertEquals("\"a\\nb\\tc\\rd\"", JsonText.toJson("a\nb\tc\rd"));
        // 控制字符转 \\u00xx，中文原样保留
        assertEquals("\"\\u0001中\"", JsonText.toJson("\u0001中"));
    }

    @Test
    public void numbers_nonFiniteBecomeNull() {
        assertEquals("null", JsonText.toJson(Double.NaN));
        assertEquals("null", JsonText.toJson(Double.POSITIVE_INFINITY));
        assertEquals("null", JsonText.toJson(Float.NEGATIVE_INFINITY));
        // 非法 JSON 数字形态（toString 返回非数字）退化为字符串
        assertEquals("\"odd\"", JsonText.toJson(new Number() {
            @Override public int intValue() { return 0; }
            @Override public long longValue() { return 0; }
            @Override public float floatValue() { return 0; }
            @Override public double doubleValue() { return 0; }
            @Override public String toString() { return "odd"; }
        }));
    }

    @Test
    public void map_supportsNumericAndStringKeys() {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1, "a");
        m.put("2", 2);
        m.put(null, null);
        String json = JsonText.toJson(m);
        assertEquals("{\"1\":\"a\",\"2\":2,\"null\":null}", json);
    }

    @Test
    public void containers_listSetArrayNested() {
        List<Object> list = new ArrayList<>();
        list.add(1);
        list.add("x");
        list.add(Arrays.asList(true, null));
        assertEquals("[1,\"x\",[true,null]]", JsonText.toJson(list));

        Set<Object> set = new LinkedHashSet<>();
        set.add("b");
        set.add("a");
        assertEquals("[\"b\",\"a\"]", JsonText.toJson(set));

        assertEquals("[1,2,3]", JsonText.toJson(new int[]{1, 2, 3}));
        assertEquals("[\"a\",\"b\"]", JsonText.toJson(new String[]{"a", "b"}));
    }

    @Test
    public void cycle_selfReferencesAreMarkedNotStackOverflow() {
        List<Object> self = new ArrayList<>();
        self.add(self);
        assertEquals("[\"" + JsonText.CYCLE_MARK + "\"]", JsonText.toJson(self));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("self", map);
        assertEquals("{\"self\":\"" + JsonText.CYCLE_MARK + "\"}", JsonText.toJson(map));

        // 兄弟节点不是环：同一对象出现两次（非递归栈）应完整输出
        List<Object> shared = Arrays.asList("v");
        List<Object> root = Arrays.asList(shared, shared);
        assertEquals("[[\"v\"],[\"v\"]]", JsonText.toJson(root));
    }

    @Test
    public void depth_deepNestingIsCapped() {
        List<Object> deep = new ArrayList<>();
        deep.add("leaf");
        for (int i = 0; i < 20; i++) {
            List<Object> parent = new ArrayList<>();
            parent.add(deep);
            deep = parent;
        }
        String json = JsonText.toJson(deep);
        assertTrue(json.contains(JsonText.DEPTH_MARK));
        assertFalse(json.contains("leaf"));
        // 浅层（默认上限 6 内）不受影响
        String shallow = JsonText.toJson(Arrays.asList(Arrays.asList(Arrays.asList("leaf"))));
        assertTrue(shallow.contains("leaf"));
        assertFalse(shallow.contains(JsonText.DEPTH_MARK));
    }

    @Test
    public void truncation_marksAndReportsFlag() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) big.append('汉');
        JsonText.Result r = JsonText.toJsonChecked(big.toString(), 50);
        assertTrue(r.truncated);
        assertTrue(r.isTruncated());
        assertTrue(r.text.endsWith(JsonText.TRUNCATED_MARK));
        // 截断正文不超过上限 + 标记长度
        assertEquals(50 + JsonText.TRUNCATED_MARK.length(), r.text.length());

        JsonText.Result small = JsonText.toJsonChecked("ok", 50);
        assertFalse(small.truncated);
        assertEquals("\"ok\"", small.text);
    }

    @Test
    public void truncation_neverSplitsSurrogatePair() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20; i++) big.append("\uD83D\uDE00"); // 😀
        // 上限 4：字符级截断会切在 emoji 的高位代理上（`"`+高代理+低代理+高代理），必须回退一位
        JsonText.Result r = JsonText.toJsonChecked(big.toString(), 4);
        assertTrue(r.truncated);
        String body = r.text.substring(0, r.text.length() - JsonText.TRUNCATED_MARK.length());
        assertEquals(3, body.length());
    }

    @Test
    public void limit_reusesTruncationForExistingText() {
        assertEquals("abc", JsonText.limit("abc", 10).text);
        JsonText.Result r = JsonText.limit("abcdef", 3);
        assertTrue(r.truncated);
        assertEquals("abc" + JsonText.TRUNCATED_MARK, r.text);
        assertEquals("", JsonText.limit(null, 10).text);
        // maxLength<=0 = 不限
        assertEquals("abcdef", JsonText.limit("abcdef", 0).text);
    }

    @Test
    public void unknownObjects_areStringifiedWithExceptionFallback() {
        Object bomb = new Object() {
            @Override public String toString() {
                throw new IllegalStateException("boom");
            }
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bad", bomb);
        assertEquals("{\"bad\":\"" + JsonText.UNSERIALIZABLE_MARK + "\"}", JsonText.toJson(m));
    }

    @Test
    public void safe_neverThrowsAndReturnsPlaceholderOnHardFailure() {
        // 顶层 Collection.iterator() 抛异常 → 整体安全占位
        java.util.AbstractCollection<Object> bad = new java.util.AbstractCollection<Object>() {
            @Override public java.util.Iterator<Object> iterator() {
                throw new IllegalStateException("boom");
            }
            @Override public int size() { return 0; }
        };
        assertEquals(JsonText.SAFE_PLACEHOLDER, JsonText.safe(bad));
        assertNotNull(JsonText.safe("ok"));
        assertEquals("\"ok\"", JsonText.safe("ok"));
    }
}
