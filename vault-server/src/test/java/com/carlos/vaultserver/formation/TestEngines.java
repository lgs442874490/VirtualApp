package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编队单测公共引擎替身：只回放推送/记录请求，不连网（不触达 Android/网络）。
 *
 * <p>{@link FakeEngine#notifyData} 与生产 {@link WjdrEngine#notifyData} 同语义（剥 data/info/content
 * 包装）；{@link FakeEngine#request} 记录 wire payload 并按队列回放响应（默认 code=0）。
 */
final class TestEngines {

    private TestEngines() {
    }

    static class FakeEngine extends WjdrEngine {
        /** proto → 推送内容（原始 content，可带 data 包装）。 */
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        /** 出站请求协议名（按顺序）。 */
        final List<String> requestProtos = new ArrayList<>();
        /** 出站请求参数（按顺序；深拷贝自调用方）。 */
        final List<Map<String, Object>> requestArgs = new ArrayList<>();
        /** 预置响应队列（空 = 默认 code=0）。 */
        final Deque<Map<String, Object>> responses = new ArrayDeque<>();

        FakeEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requestProtos.add(proto);
            requestArgs.add(copyMap(args));
            GameMessage msg = new GameMessage();
            msg.setProto(proto + ".response");
            Map<String, Object> content = responses.isEmpty()
                    ? new LinkedHashMap<>() : responses.poll();
            msg.setContent(content);
            return msg;
        }

        @Override
        public String sessionState() {
            return STATE_ONLINE;
        }

        @Override
        public void heartbeatTick() {
        }

        @Override
        public long lastUid() {
            return 88280792L;
        }

        @Override
        public long lastServerId() {
            return 888L;
        }

        /** 预置一次响应（content；{@code code=0} 表示成功）。 */
        void respond(long code) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            responses.add(m);
        }

        static Map<String, Object> mapOf(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
            return m;
        }

        /** {k,v} 对（sproto pair_int_int）。 */
        static Map<String, Object> pair(long k, long v) {
            return mapOf("k", k, "v", v);
        }

        /** 深拷贝为 Map<String,Object>（请求参数记录用；null → 空 Map）。 */
        @SuppressWarnings("unchecked")
        static Map<String, Object> copyMap(Map<String, Object> args) {
            if (args == null) return new LinkedHashMap<>();
            return (Map<String, Object>) deepCopy(args);
        }

        @SuppressWarnings("unchecked")
        private static Object deepCopy(Object v) {
            if (v instanceof Map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    out.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
                }
                return out;
            }
            if (v instanceof List) {
                List<Object> out = new ArrayList<>();
                for (Object o : (List<?>) v) out.add(deepCopy(o));
                return out;
            }
            return v;
        }
    }
}
