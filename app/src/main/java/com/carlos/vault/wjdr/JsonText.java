package com.carlos.vault.wjdr;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 平台中立安全 JSON 序列化（2026-10-25 真机监控）：App（Android）与 vault-server（JVM）共用，
 * 不依赖 {@code org.json} 或任何 Android/第三方 API，纯 {@code java.util} + 反射数组。
 *
 * <p>用途：把游戏协议参数/响应（{@code Map<String,Object>} 嵌套树）序列化成监控事件里的
 * {@code args}/{@code data} 文本字段。游戏返回是外部数据，形状不可控，因此这里做防御：
 * <ul>
 *   <li>支持 {@link Map}（键允许数字/字符串/枚举）、{@link Collection}、数组（含基本类型数组）、
 *       {@link CharSequence}/{@link Character}、{@link Number}、{@link Boolean}、{@link Enum}、{@code null}；</li>
 *   <li><b>循环引用防炸</b>：容器在当前递归栈上重复出现输出 {@value #CYCLE_MARK}，不无限递归；</li>
 *   <li><b>深度上限</b>：默认 {@value #DEFAULT_MAX_DEPTH} 层，超出后容器输出 {@value #DEPTH_MARK}，标量照常输出；</li>
 *   <li><b>长度上限</b>：默认 {@value #DEFAULT_MAX_LENGTH} 字符，超出截断并在末尾追加
 *       {@value #TRUNCATED_MARK}，同时经 {@link Result#truncated} 暴露是否截断；</li>
 *   <li><b>异常兜底</b>：序列化过程任何异常返回 {@value #SAFE_PLACEHOLDER}（合法 JSON 对象字面量），
 *       单个值 toString 抛异常时该值输出 {@value #UNSERIALIZABLE_MARK}。</li>
 * </ul>
 *
 * <p>说明：截断发生在字符级，截断后的文本可能不是合法 JSON；调用方应把它当“文本字段”落库
 * （监控事件里 {@code args}/{@code data} 都是字符串），不要二次解析。非 ASCII 字符原样输出（UTF-8）。
 */
public final class JsonText {

    /** 默认深度上限（顶层为 0，容器最多展开 6 层）。 */
    public static final int DEFAULT_MAX_DEPTH = 6;
    /** 默认输出长度上限（字符数）。 */
    public static final int DEFAULT_MAX_LENGTH = 4000;
    /** 输出被截断时追加在末尾的标记。 */
    public static final String TRUNCATED_MARK = "...[truncated]";
    /** 循环引用占位。 */
    public static final String CYCLE_MARK = "[cycle]";
    /** 超深占位。 */
    public static final String DEPTH_MARK = "[depth]";
    /** 单个值无法序列化（toString 抛异常等）占位。 */
    public static final String UNSERIALIZABLE_MARK = "[unserializable]";
    /** 整体序列化失败的安全占位（合法 JSON 对象字面量）。 */
    public static final String SAFE_PLACEHOLDER = "{\"_json_error\":true}";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonText() {
    }

    /** 序列化（默认长度/深度上限）。 */
    public static String toJson(Object value) {
        return toJson(value, DEFAULT_MAX_LENGTH);
    }

    /** 序列化并限制输出长度（{@code maxLength <= 0} 表示不限）。 */
    public static String toJson(Object value, int maxLength) {
        return toJsonChecked(value, maxLength).text;
    }

    /** 序列化并返回“文本 + 是否截断”。 */
    public static Result toJsonChecked(Object value, int maxLength) {
        return toJsonChecked(value, maxLength, DEFAULT_MAX_DEPTH);
    }

    /** 序列化并返回“文本 + 是否截断”，同时指定长度/深度上限。 */
    public static Result toJsonChecked(Object value, int maxLength, int maxDepth) {
        int limit = maxLength > 0 ? maxLength : Integer.MAX_VALUE;
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        Builder b = new Builder(limit, depth);
        try {
            b.value(value, 0);
        } catch (Throwable t) {
            return new Result(SAFE_PLACEHOLDER, false);
        }
        return b.finish();
    }

    /** 序列化但绝不抛异常：失败返回 {@link #SAFE_PLACEHOLDER}（等价于 {@link #toJson(Object)}）。 */
    public static String safe(Object value) {
        return toJson(value);
    }

    /**
     * 限制一段已序列化文本的长度（超长截断 + 末尾标记）。
     * 供只有现成文本（如 {@code JSONObject.toString()}）的调用方复用同一截断口径。
     */
    public static Result limit(String text, int maxLength) {
        if (text == null) return new Result("", false);
        if (maxLength <= 0 || text.length() <= maxLength) return new Result(text, false);
        String cut = text.substring(0, maxLength);
        int n = cut.length();
        if (n > 0 && Character.isHighSurrogate(cut.charAt(n - 1))) cut = cut.substring(0, n - 1);
        return new Result(cut + TRUNCATED_MARK, true);
    }

    /** 序列化结果：文本 + 是否被长度截断。 */
    public static final class Result {
        public final String text;
        public final boolean truncated;

        Result(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }

        public boolean isTruncated() {
            return truncated;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    // ---------- 序列化实现 ----------

    private static final class Builder {
        private final StringBuilder sb;
        private final int maxLength;
        private final int maxDepth;
        private final IdentityHashMap<Object, Boolean> onStack = new IdentityHashMap<>();
        private boolean truncated;

        Builder(int maxLength, int maxDepth) {
            this.sb = new StringBuilder(Math.min(256, maxLength));
            this.maxLength = maxLength;
            this.maxDepth = maxDepth;
        }

        Result finish() {
            if (!truncated) return new Result(sb.toString(), false);
            String cut = sb.toString();
            if (cut.length() > maxLength) cut = cut.substring(0, maxLength);
            int n = cut.length();
            // 不把代理对（如 emoji）截成半个
            if (n > 0 && Character.isHighSurrogate(cut.charAt(n - 1))) cut = cut.substring(0, n - 1);
            return new Result(cut + TRUNCATED_MARK, true);
        }

        void value(Object v, int depth) {
            if (truncated) return;
            if (v == null) {
                append("null");
                return;
            }
            if (v instanceof CharSequence || v instanceof Character || v instanceof Enum) {
                string(String.valueOf(v));
                return;
            }
            if (v instanceof Boolean) {
                append(((Boolean) v) ? "true" : "false");
                return;
            }
            if (v instanceof Number) {
                number((Number) v);
                return;
            }
            if (depth >= maxDepth) {
                string(DEPTH_MARK);
                return;
            }
            if (v instanceof Map) {
                map((Map<?, ?>) v, depth);
                return;
            }
            if (v instanceof Collection) {
                collection((Collection<?>) v, depth);
                return;
            }
            if (v.getClass().isArray()) {
                array(v, depth);
                return;
            }
            // 未知类型：toString 兜底（宿主对象可能抛异常）
            try {
                string(String.valueOf(v));
            } catch (Throwable t) {
                string(UNSERIALIZABLE_MARK);
            }
        }

        private void map(Map<?, ?> m, int depth) {
            if (enter(m)) return;
            try {
                append("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (truncated) return;
                    if (!first) append(",");
                    first = false;
                    Object k = e == null ? null : e.getKey();
                    string(k == null ? "null" : String.valueOf(k));
                    append(":");
                    value(e == null ? null : e.getValue(), depth + 1);
                }
                append("}");
            } finally {
                leave(m);
            }
        }

        private void collection(Collection<?> c, int depth) {
            if (enter(c)) return;
            try {
                append("[");
                boolean first = true;
                for (Object o : c) {
                    if (truncated) return;
                    if (!first) append(",");
                    first = false;
                    value(o, depth + 1);
                }
                append("]");
            } finally {
                leave(c);
            }
        }

        private void array(Object arr, int depth) {
            if (enter(arr)) return;
            try {
                append("[");
                int len = Array.getLength(arr);
                for (int i = 0; i < len; i++) {
                    if (truncated) return;
                    if (i > 0) append(",");
                    value(Array.get(arr, i), depth + 1);
                }
                append("]");
            } finally {
                leave(arr);
            }
        }

        /** 入栈判环：已在当前递归栈上 → 输出 cycle 占位并返回 true（调用方直接 return）。 */
        private boolean enter(Object container) {
            if (onStack.containsKey(container)) {
                string(CYCLE_MARK);
                return true;
            }
            onStack.put(container, Boolean.TRUE);
            return false;
        }

        private void leave(Object container) {
            onStack.remove(container);
        }

        private void number(Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    append("null");   // JSON 无 NaN/Infinity 字面量
                    return;
                }
            }
            String s = n.toString();
            if (!s.isEmpty() && looksLikeJsonNumber(s)) {
                append(s);
            } else {
                string(s);
            }
        }

        private void string(String s) {
            append("\"");
            for (int i = 0; i < s.length(); i++) {
                if (truncated) return;
                char c = s.charAt(i);
                switch (c) {
                    case '"': append("\\\""); break;
                    case '\\': append("\\\\"); break;
                    case '\b': append("\\b"); break;
                    case '\f': append("\\f"); break;
                    case '\n': append("\\n"); break;
                    case '\r': append("\\r"); break;
                    case '\t': append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            append("\\u00");
                            append(String.valueOf(HEX[(c >> 4) & 0xF]));
                            append(String.valueOf(HEX[c & 0xF]));
                        } else {
                            append(String.valueOf(c));
                        }
                }
            }
            append("\"");
        }

        /** 追加文本：达到长度上限即停止并标记截断（后续追加全部忽略）。 */
        private void append(String s) {
            if (truncated || s == null || s.isEmpty()) return;
            int remain = maxLength - sb.length();
            if (s.length() <= remain) {
                sb.append(s);
                return;
            }
            if (remain > 0) sb.append(s, 0, remain);
            truncated = true;
        }
    }

    /** 保守的数字字面量判定（避免把 "+1"/"0x1A"/"NaN" 之类非法 JSON 数字直接写入）。 */
    private static boolean looksLikeJsonNumber(String s) {
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        if (!(first == '-' || (first >= '0' && first <= '9'))) return false;
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c != '-' && c != '+' && c != '.' && c != 'e' && c != 'E') {
                return false;
            }
        }
        return hasDigit;
    }
}
