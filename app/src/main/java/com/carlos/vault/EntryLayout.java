package com.carlos.vault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口列表的<b>展示布局 + 排序 + 分组</b>纯逻辑。
 *
 * <p><b>为什么单独一个类</b>：这几件事（构建展示列表 / 应用拖拽落位 / 分组增删改名 / 折叠态）
 * 全是纯数据变换，却最容易出错（38 条老数据的分组缺省、跨组拖拽、重命名的一次性事务…）。
 * 把它们从 Activity 里抽出来，就能<b>刻意不 import 任何 {@code android.*}</b>（与
 * {@link AccountImport}、{@link SessionProbe} 同款做法），从而能直接用 {@code javac}
 * 在宿主机干跑验证。Activity 只负责渲染与手势编排。
 *
 * <h3>数据模型（与架构设计一致）</h3>
 * <ul>
 *   <li><b>排序 = 数组顺序（隐式）</b>：不加 {@code order} 字段 ⇒ 38 条老数据<b>零迁移</b>。
 *       "全局顺序"与"组内顺序"由同一个数组顺序同时表达：展示时按 {@code group} 做<b>稳定分组</b>
 *       （保持数组相对顺序）。</li>
 *   <li><b>分组 = 每条一个 {@code group} 字段</b>（存分组名，{@code ""} = 未分组）+ 独立键
 *       {@code group_meta} = {@code [{"name":"…","collapsed":false}]}（同时表达分组顺序与折叠态）。</li>
 * </ul>
 *
 * <h3>两条铁律（38 条老数据必须一条不丢）</h3>
 * <ol>
 *   <li>{@code group} 缺省 ⇒ {@code ""} ⇒ 归入"未分组"，且"未分组"<b>始终置顶</b>、
 *       <b>不可折叠</b>（否则用户一折叠就以为数据丢了）。</li>
 *   <li>成员 {@code group} 指向一个<b>没在 {@code group_meta} 里登记</b>的分组名时，
 *       也要在列表里显示出来（按首次出现顺序追加），<b>绝不能因为元数据缺失就让条目"消失"</b>。</li>
 * </ol>
 */
public final class EntryLayout {

    /** 未分组的原始分组键（哨兵值）。 */
    public static final String UNGROUPED = "";

    /** "未分组"的展示名。 */
    public static final String UNGROUPED_TITLE = "未分组";

    /** 行类型：分组表头。 */
    public static final int TYPE_HEADER = 0;
    /** 行类型：入口 tile。 */
    public static final int TYPE_ENTRY = 1;

    private EntryLayout() {
    }

    // ==================== 模型 ====================

    /**
     * 布局用的最小条目模型：只含"布局/排序"关心的字段（id + group）。
     * 刻意不复用 {@link AccountStore.Account}（那个类依赖 {@code android.content.Context}，
     * 一旦引用本类就没法在宿主机 javac 干跑）。
     */
    public static final class Item {
        /** 入口唯一标识（{@code AccountStore.Account.id}）。 */
        public String id;
        /** 所属分组名；{@code ""} = 未分组（null 视同 ""）。 */
        public String group;

        public Item() {
            this.id = "";
            this.group = UNGROUPED;
        }

        public Item(String id, String group) {
            this.id = id == null ? "" : id;
            this.group = group == null ? UNGROUPED : group;
        }

        @Override
        public String toString() {
            return id + ":" + group;
        }
    }

    /** 分组元数据：列表顺序 = 分组顺序；{@code collapsed} = 折叠态。 */
    public static final class Group {
        /** 分组名（人类可读，同时就是成员 {@link Item#group} 的取值）。 */
        public String name;
        /** 是否折叠（只影响展示，不影响数据）。 */
        public boolean collapsed;

        public Group() {
            this.name = "";
            this.collapsed = false;
        }

        public Group(String name, boolean collapsed) {
            this.name = name == null ? "" : name;
            this.collapsed = collapsed;
        }

        @Override
        public String toString() {
            return name + (collapsed ? "▸" : "▾");
        }
    }

    /** 一行展示项（表头 或 条目）。 */
    public static final class Row {
        /** {@link #TYPE_HEADER} 或 {@link #TYPE_ENTRY}。 */
        public final int type;
        /** 表头：显示名（"未分组" 或分组名）；条目：null。 */
        public final String title;
        /** 原始分组键（{@code ""} = 未分组）。表头与条目都有。 */
        public final String group;
        /** 表头：成员数；条目：0。 */
        public final int count;
        /** 表头：是否折叠；条目：false。 */
        public final boolean collapsed;
        /** 表头：是否"未分组"（未分组不可折叠）；条目：false。 */
        public final boolean ungrouped;
        /** 条目：在 {@code items} 里的下标（拖拽定位用）；表头：-1。 */
        public final int itemIndex;
        /** 条目：入口 id；表头：null。 */
        public final String itemId;

        private Row(int type, String title, String group, int count, boolean collapsed,
                    boolean ungrouped, int itemIndex, String itemId) {
            this.type = type;
            this.title = title;
            this.group = group;
            this.count = count;
            this.collapsed = collapsed;
            this.ungrouped = ungrouped;
            this.itemIndex = itemIndex;
            this.itemId = itemId;
        }

        static Row header(String title, String group, int count, boolean collapsed, boolean ungrouped) {
            return new Row(TYPE_HEADER, title, group, count, collapsed, ungrouped, -1, null);
        }

        static Row entry(Item it, int index, String group) {
            return new Row(TYPE_ENTRY, null, group, 0, false, false, index, it.id);
        }

        public boolean isHeader() {
            return type == TYPE_HEADER;
        }

        @Override
        public String toString() {
            return type == TYPE_HEADER
                    ? ("[H:" + title + "(" + count + ")" + (collapsed ? "折叠" : "展开")
                        + (ungrouped ? ",置顶" : "") + "]")
                    : ("[E:" + itemId + "@" + itemIndex + ":" + group + "]");
        }
    }

    // ==================== 构建展示列表 ====================

    /**
     * 由条目列表 + 分组元数据构建展示行。
     *
     * <p>分组出现顺序：<b>"未分组"永远第一</b> → 随后按 {@code meta} 顺序（<b>空组也显示</b>，
     * 否则用户没法删/改名）→ 最后是"有成员却没在 meta 登记"的组（按首次出现顺序追加，
     * 保证条目不会消失）。折叠组只显示表头、不显示成员。
     */
    public static List<Row> buildRows(List<Item> items, List<Group> meta) {
        List<Row> rows = new ArrayList<>();
        if (items == null) items = new ArrayList<Item>();
        if (meta == null) meta = new ArrayList<Group>();

        List<String> order = orderedGroupKeys(items, meta);
        for (String g : order) {
            boolean ungrouped = UNGROUPED.equals(g);
            Group mg = findGroup(meta, g);
            // 未分组不可折叠（折叠会让 38 条老数据"消失"，用户会以为丢了数据）
            boolean collapsed = !ungrouped && mg != null && mg.collapsed;
            List<Integer> memberIdx = indicesInGroup(items, g);
            rows.add(Row.header(ungrouped ? UNGROUPED_TITLE : g, g,
                    memberIdx.size(), collapsed, ungrouped));
            if (!collapsed) {
                for (int i = 0; i < memberIdx.size(); i++) {
                    int idx = memberIdx.get(i);
                    rows.add(Row.entry(items.get(idx), idx, g));
                }
            }
        }
        return rows;
    }

    /** 分组键的展示顺序："" → meta 顺序 → 未登记但有成员的组。 */
    public static List<String> orderedGroupKeys(List<Item> items, List<Group> meta) {
        List<String> out = new ArrayList<>();
        out.add(UNGROUPED);
        if (meta != null) {
            for (int i = 0; i < meta.size(); i++) {
                Group g = meta.get(i);
                if (g == null || g.name == null) continue;
                String n = g.name;
                if (n.isEmpty() || UNGROUPED_TITLE.equals(n) || out.contains(n)) continue;
                out.add(n);
            }
        }
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                String g = keyOf(items.get(i));
                if (!out.contains(g)) out.add(g);
            }
        }
        return out;
    }

    /**
     * 展示行的分组键顺序（含表头顺序）；用于拖拽时确定"组序"。
     *
     * 注：刻意<b>不重载</b> {@link #orderedGroupKeys(List, List)} —— 两个方法擦除后都是
     * {@code (List, List)}，同名重载会直接编译失败（name clash: same erasure）。
     */
    public static List<String> groupKeysFromRows(List<Row> rows, List<Item> items) {
        List<String> out = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.type != TYPE_HEADER) continue;
                String g = r.group == null ? UNGROUPED : r.group;
                if (!out.contains(g)) out.add(g);
            }
        }
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                String g = keyOf(items.get(i));
                if (!out.contains(g)) out.add(g);
            }
        }
        return out;
    }

    /** 某分组在 {@code items} 里的成员下标（保持数组相对顺序）。 */
    public static List<Integer> indicesInGroup(List<Item> items, String group) {
        List<Integer> out = new ArrayList<>();
        String g = group == null ? UNGROUPED : group;
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            if (g.equals(keyOf(items.get(i)))) out.add(i);
        }
        return out;
    }

    /** 某分组的成员数。 */
    public static int countInGroup(List<Item> items, String group) {
        return indicesInGroup(items, group).size();
    }

    public static Group findGroup(List<Group> meta, String name) {
        if (meta == null || name == null) return null;
        for (int i = 0; i < meta.size(); i++) {
            Group g = meta.get(i);
            if (g != null && name.equals(g.name)) return g;
        }
        return null;
    }

    static String keyOf(Item it) {
        if (it == null || it.group == null) return UNGROUPED;
        return it.group;
    }

    // ==================== 拖拽落位 ====================

    /**
     * 应用一次拖拽：把 {@code rows[fromRow]} 指向的条目移动到 {@code rows[toRow]} 的位置。
     *
     * <p><b>语义（明确约定，干跑即按此断言）</b>：
     * <ul>
     *   <li>目标是<b>条目</b> ⇒ 源条目落到该条目所在的槽位（插到它前面），若两者不同组则同时改
     *       源条目的 {@code group}（<b>跨分组拖拽允许</b>）。</li>
     *   <li>目标是<b>分组表头</b> ⇒ 源条目落到该组<b>开头</b>（空组即落到该组的位置）。</li>
     *   <li>目标表头<b>处于折叠态</b> ⇒ <b>拒绝</b>（返回 false；先展开再拖，减少歧义）。</li>
     * </ul>
     *
     * <p><b>实现</b>：先按当前展示顺序把条目分桶（组序 + 组内顺序 = 当前展示顺序），
     * 从源桶取出、按目标位置插入目标桶，再按组序展平回 {@code items}。
     * 这样做同时正确处理了"组内重排"与"跨组搬移"，而单纯 `Collections.swap(展示行)`
     * 在中间夹着分组表头时会把表头和条目换位 —— 那才是错的。
     *
     * @return true 表示顺序（及可能的 group）已改变；false 表示这次拖拽无效（调用方应忽略）
     */
    public static boolean applyDrag(List<Item> items, List<Row> rows, int fromRow, int toRow) {
        if (items == null || rows == null) return false;
        if (fromRow < 0 || fromRow >= rows.size()) return false;
        if (toRow < 0 || toRow >= rows.size()) return false;

        Row src = rows.get(fromRow);
        if (src.type != TYPE_ENTRY) return false;
        Row dst = rows.get(toRow);
        if (dst.type == TYPE_HEADER && dst.collapsed) return false;   // 禁止拖入折叠态分组
        if (dst.type == TYPE_ENTRY && dst.itemIndex == src.itemIndex) return false;
        if (src.itemIndex < 0 || src.itemIndex >= items.size()) return false;

        // 1) 当前展示顺序下的分组键序列
        List<String> order = groupKeysFromRows(rows, items);
        // 2) 分桶
        Map<String, List<Item>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) buckets.put(order.get(i), new ArrayList<Item>());
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String g = keyOf(it);
            List<Item> b = buckets.get(g);
            if (b == null) {
                b = new ArrayList<Item>();
                buckets.put(g, b);
                order.add(g);
            }
            b.add(it);
        }
        // 3) 取出源（按 items 下标精确定位，不依赖 equals）
        Item moved = items.get(src.itemIndex);
        List<Item> srcBucket = buckets.get(keyOf(moved));
        if (srcBucket != null) srcBucket.remove(moved);
        // 4) 目标组 + 组内下标
        String target;
        int inGroup;
        if (dst.type == TYPE_HEADER) {
            target = dst.group == null ? UNGROUPED : dst.group;
            inGroup = 0;
        } else {
            Item anchor = (dst.itemIndex >= 0 && dst.itemIndex < items.size())
                    ? items.get(dst.itemIndex) : null;
            target = anchor == null
                    ? (dst.group == null ? UNGROUPED : dst.group) : keyOf(anchor);
            List<Item> tb = buckets.get(target);
            inGroup = (anchor == null || tb == null) ? 0 : tb.indexOf(anchor);
            if (inGroup < 0) inGroup = (tb == null ? 0 : tb.size());
        }
        moved.group = target;
        List<Item> targetBucket = buckets.get(target);
        if (targetBucket == null) {
            targetBucket = new ArrayList<Item>();
            buckets.put(target, targetBucket);
            order.add(target);
        }
        if (inGroup < 0) inGroup = 0;
        if (inGroup > targetBucket.size()) inGroup = targetBucket.size();
        targetBucket.add(inGroup, moved);
        // 5) 按组序展平回 items
        items.clear();
        for (int i = 0; i < order.size(); i++) {
            List<Item> b = buckets.get(order.get(i));
            if (b != null) items.addAll(b);
        }
        return true;
    }

    /** 纯数组移动（把下标 from 的元素移到 to）。供外部做简单重排。 */
    public static boolean reorder(List<Item> items, int from, int to) {
        if (items == null) return false;
        if (from < 0 || from >= items.size()) return false;
        if (to < 0 || to >= items.size()) return false;
        if (from == to) return false;
        Item it = items.remove(from);
        items.add(to, it);
        return true;
    }

    /** 把下标 index 的条目改到指定分组（{@code ""} = 未分组）。 */
    public static boolean setGroupAt(List<Item> items, int index, String group) {
        if (items == null || index < 0 || index >= items.size()) return false;
        items.get(index).group = group == null ? UNGROUPED : group;
        return true;
    }

    // ==================== 分组增删改名 / 折叠 ====================

    /** 置反折叠态；返回新状态。分组不存在返回 false 且不改动。 */
    public static boolean toggleCollapsed(List<Group> meta, String name) {
        Group g = findGroup(meta, name);
        if (g == null) return false;
        g.collapsed = !g.collapsed;
        return g.collapsed;
    }

    /** 校验新建分组名：返回 null 表示通过，否则是中文原因。 */
    public static String validateGroupName(List<Group> meta, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "分组名不能为空";
        if (UNGROUPED_TITLE.equals(n)) return "「" + UNGROUPED_TITLE + "」是系统保留名";
        if (findGroup(meta, n) != null) return "分组「" + n + "」已存在";
        return null;
    }

    /** 校验重命名：返回 null 表示通过，否则是中文原因。 */
    public static String validateRename(List<Group> meta, String oldName, String newName) {
        if (oldName == null || oldName.isEmpty()) return "原分组不存在";
        if (findGroup(meta, oldName) == null) return "原分组不存在";
        String n = newName == null ? "" : newName.trim();
        if (n.isEmpty()) return "分组名不能为空";
        if (UNGROUPED_TITLE.equals(n)) return "「" + UNGROUPED_TITLE + "」是系统保留名";
        if (n.equals(oldName)) return "新名与原名相同";
        if (findGroup(meta, n) != null) return "分组「" + n + "」已存在";
        return null;
    }

    /**
     * 重命名：**同一个方法里**改掉 {@code meta} 的组名与所有成员条目的 {@code group}。
     *
     * <p>调用方（{@link AccountStore#renameGroup}）随后会把 {@code accounts} 与
     * {@code group_meta} 放在**同一个 {@code commit()}** 里落盘 ⇒ 一次事务，不会出现
     * "元数据改了、成员没改"的中间态。校验不通过返回 false 且<b>不做任何改动</b>。
     */
    public static boolean renameInPlace(List<Group> meta, List<Item> items,
                                        String oldName, String newName) {
        if (validateRename(meta, oldName, newName) != null) return false;
        String n = newName.trim();
        for (int i = 0; i < meta.size(); i++) {
            Group g = meta.get(i);
            if (g != null && oldName.equals(g.name)) g.name = n;
        }
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (oldName.equals(keyOf(items.get(i)))) items.get(i).group = n;
            }
        }
        return true;
    }

    /**
     * 删除分组：从 {@code meta} 移除该组，成员退回"未分组"。
     *
     * <p>⚠️ 注意与 {@link #buildRows} 的区别：**"最后一个成员被移走"不会自动删除分组**
     * （只是变成空组，仍显示，方便用户改名/删除）。只有显式调用本方法才真正删组。
     * 成员永远只是退回"未分组"，<b>绝不删条目</b>。
     */
    public static boolean deleteInPlace(List<Group> meta, List<Item> items, String name) {
        if (meta == null || name == null || name.isEmpty()) return false;
        boolean removed = false;
        for (int i = meta.size() - 1; i >= 0; i--) {
            Group g = meta.get(i);
            if (g != null && name.equals(g.name)) {
                meta.remove(i);
                removed = true;
            }
        }
        if (removed && items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (name.equals(keyOf(items.get(i)))) items.get(i).group = UNGROUPED;
            }
        }
        return removed;
    }

    // ==================== group_meta 的 JSON 读写（手写，零依赖） ====================

    /**
     * 解析 {@code group_meta}：{@code [{"name":"…","collapsed":false}, …]}。
     * 手写最小解析器（不用 {@code org.json}，那样就没法在宿主机 javac 干跑）。
     * 任何解析不了的部分都跳过，绝不抛异常。
     */
    public static List<Group> parseMeta(String json) {
        List<Group> out = new ArrayList<>();
        if (json == null) return out;
        int i = 0;
        int n = json.length();
        while (i < n && json.charAt(i) != '[') i++;
        if (i >= n) return out;
        i++;
        while (i < n) {
            while (i < n) {
                char c = json.charAt(i);
                if (Character.isWhitespace(c) || c == ',') i++;
                else break;
            }
            if (i >= n || json.charAt(i) == ']') break;
            if (json.charAt(i) != '{') break;
            int end = matchingBrace(json, i);
            if (end < 0) break;
            String obj = json.substring(i, end + 1);
            // ⚠️ 必须先推进游标再判断：任何 `continue` 都不能绕过它，
            //    否则 i 原地不动 ⇒ 死循环（这是干跑用例⑦专门盯着的点）。
            i = end + 1;
            String name = jsonStringField(obj, "name");
            boolean collapsed = "true".equals(jsonBareValue(obj, "collapsed"));
            if (name == null || name.isEmpty()) continue;
            // 保留名（"未分组"）与重复名一律丢弃，保证 meta 读出来就是规范的：
            // 否则一份脏数据会在每次读-写循环里被原样搬来搬去，永远清不掉。
            if (UNGROUPED_TITLE.equals(name)) continue;
            boolean dup = false;
            for (Group g : out) if (name.equals(g.name)) dup = true;
            if (dup) continue;
            out.add(new Group(name, collapsed));
        }
        return out;
    }

    /**
     * 序列化 {@code group_meta}。
     *
     * <p>空名、保留名（"未分组"）、重复名一律跳过 —— 与 {@link #parseMeta}、
     * {@link #orderedGroupKeys}、{@link #validateGroupName} 的口径保持一致，
     * 这样"读一次再写一次"就能把历史脏数据自然清掉，而不是原样搬来搬去。
     */
    public static String toMeta(List<Group> meta) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (meta != null) {
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < meta.size(); i++) {
                Group g = meta.get(i);
                if (g == null || g.name == null || g.name.isEmpty()) continue;
                if (UNGROUPED_TITLE.equals(g.name) || seen.contains(g.name)) continue;
                seen.add(g.name);
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(escapeJson(g.name))
                        .append("\",\"collapsed\":").append(g.collapsed).append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static int matchingBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String jsonStringField(String obj, String key) {
        String k = "\"" + key + "\"";
        int p = obj.indexOf(k);
        if (p < 0) return null;
        int c = obj.indexOf(':', p + k.length());
        if (c < 0) return null;
        int i = c + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        if (i >= obj.length() || obj.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < obj.length()) {
            char ch = obj.charAt(i);
            if (ch == '\\') {
                if (i + 1 < obj.length()) {
                    char nx = obj.charAt(i + 1);
                    switch (nx) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 5 < obj.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(
                                            obj.substring(i + 2, i + 6), 16));
                                    i += 6;
                                    continue;
                                } catch (Throwable ignored) {
                                }
                            }
                            sb.append(nx);
                            break;
                        default: sb.append(nx);
                    }
                }
                i += 2;
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    private static String jsonBareValue(String obj, String key) {
        String k = "\"" + key + "\"";
        int p = obj.indexOf(k);
        if (p < 0) return null;
        int c = obj.indexOf(':', p + k.length());
        if (c < 0) return null;
        int i = c + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        int s = i;
        while (i < obj.length()) {
            char ch = obj.charAt(i);
            if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) break;
            i++;
        }
        return obj.substring(s, i);
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
