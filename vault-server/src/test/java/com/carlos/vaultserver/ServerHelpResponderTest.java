package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 联盟互助常驻响应器单测（P0 2026-10-28 二次加固）。
 *
 * <p>真实协议样本（tools/bundle_re/audit_1014/proto_log_dev_old.txt / proto_log.txt）：
 * <pre>
 * notify_alliance_help_init {"alliance_help":{"help_list":[{"uid":20778222,"qid":20001,
 *   "helpers":[23943109,...],"qtype":2,"extra":{"id":1039,"lv":2,"help_id":72200593},
 *   "endtime":1792218859,"max":51}],"day_glory":1700,"help_num":0}}
 * notify_alliance_help_update {"type":3,"data":[{"uid":...,"qid":...,"extra":{"help_id":...}}]}  // 3=删除，1/2=增改
 * req_alliance_help_all {"code":0,"count":1,"rewards":[{"id":110,"count":20}],"day_glory":...}
 * </pre>
 *
 * <p>锁死本次纠偏后的语义（生产 monitor-20261028.jsonl 实证）：
 * <ul>
 *   <li>1536 = “服务端此刻没有可响应的互助请求”（网页版 NO_AVAILABLE_HELP_CODE），
 *       不得写 settled、不得跨天封停；只做“标记当前可帮已处理 + 5s 短退避”；</li>
 *   <li>tick 只 seed-once：不再每 30s 用登录快照重放（否则抹掉推送增量与乐观已帮标记）；</li>
 *   <li>成功只认服务端 count/help_num，code=0 且 count=0 不得写“已响应 N 位”；</li>
 *   <li>单条唯一键用 extra.help_id（同一 owner+qid 可并发多条），type=3 按 help_id 精确删除。</li>
 * </ul>
 */
public class ServerHelpResponderTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;
    /** 固定时钟：tick(engine, now) 可注入，避免真的 sleep 30s。 */
    private static final long BASE_NOW = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
    }

    /** 最小引擎替身：在线态/uid/通知/响应可注入（不连网）。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<BiConsumer<String, Map<String, Object>>> hooks = new ArrayList<>();
        boolean online = true;
        long uid = OWN_UID;

        TestEngine() {
            super(null);
        }

        @Override
        public void addMessageHook(BiConsumer<String, Map<String, Object>> hook) {
            hooks.add(hook);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
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
        public String sessionState() {
            return online ? WjdrEngine.STATE_ONLINE : WjdrEngine.STATE_TAKEOVER;
        }

        @Override
        public long lastUid() {
            return uid;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            return responses.get(proto);
        }

        /** 模拟推送到达（钩子线程）：驱动 dirty 标记。 */
        void push(String proto, Map<String, Object> content) {
            notifies.put(proto, content);
            for (BiConsumer<String, Map<String, Object>> h : hooks) h.accept(proto, content);
        }
    }

    private static GameMessage msg(long code, Map<String, Object> extra) {
        GameMessage m = new GameMessage();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", code);
        if (extra != null) content.putAll(extra);
        m.setContent(content);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 一条真实形状的互助请求（唯一标识在 extra.help_id）。 */
    private static Map<String, Object> helpReq(long uid, long qid, long helpId, long endtime, long max) {
        return map("uid", uid, "qid", qid, "helpers", List.of(), "qtype", 6L,
                "extra", map("help_id", helpId), "endtime", endtime, "max", max);
    }

    /** 真实 init 形状：进度字段/列表都在 alliance_help 内层。 */
    private static Map<String, Object> initPush(List<Map<String, Object>> reqs) {
        return map("alliance_help", map("help_list", reqs, "day_glory", 1700L, "help_num", 0L));
    }

    private static Map<String, Object> updatePush(long type, List<Map<String, Object>> data) {
        return map("type", type, "data", data);
    }

    private String modText() {
        String raw = state.getString("mod:" + FPID + ":" + SID + ":alliance_help");
        return raw == null ? "" : new JSONObject(raw).optString("help_all", "");
    }

    private JSONObject modJson() {
        String raw = state.getString("mod:" + FPID + ":" + SID + ":alliance_help");
        return raw == null ? new JSONObject() : new JSONObject(raw);
    }

    private String legacyKey() {
        return ServerHelpResponder.LEGACY_SETTLED_PREFIX + FPID + ":" + SID;
    }

    // ==================== 推送解析（真实样本形状） ====================

    @Test
    public void progressFields_parsedFromNestedAllianceHelp() {
        Map<String, Object> content = initPush(List.of());
        assertEquals("init 的 help_num 在 alliance_help 内层", 0L,
                ServerHelpResponder.helpNumOf(content));
        assertEquals("init 的 day_glory 在 alliance_help 内层", 1700L,
                ServerHelpResponder.dayGloryOf(content));
        // 裸 help_list 形状（update_all）也要兼容
        Map<String, Object> bare = map("help_list", List.of(), "help_num", 7L, "day_glory", 200L);
        assertEquals(7L, ServerHelpResponder.helpNumOf(bare));
        assertEquals(200L, ServerHelpResponder.dayGloryOf(bare));
        // data 包装（部分协议）
        assertEquals(9L, ServerHelpResponder.helpNumOf(map("data", map("help_num", 9L))));
    }

    @Test
    public void parseHelpRequests_realShape() {
        Object list = map("alliance_help", map("help_list", List.of(
                map("uid", 20778222L, "qid", 20001L,
                        "helpers", List.of(23943109L, 23940407L), "endtime", 1792218859L, "max", 51L,
                        "extra", map("id", 1039L, "lv", 2L, "help_id", 72200593L)))));
        // 真实推送里 help_list 在 alliance_help 内层；服务端 applyPush 用 deepGet 取到后再解析
        List<ServerHelpResponder.HelpReq> parsed = ServerHelpResponder.parseHelpRequests(
                ((Map<?, ?>) ((Map<?, ?>) list).get("alliance_help")).get("help_list"));
        assertEquals(1, parsed.size());
        ServerHelpResponder.HelpReq r = parsed.get(0);
        assertEquals(20778222L, r.owner);
        assertEquals(20001L, r.qid);
        assertEquals("单条唯一键来自 extra.help_id", 72200593L, r.helpId);
        assertEquals(51L, r.max);
        assertFalse("自己没帮过但已过期 → 不可帮", r.availableFor(OWN_UID, 1792218860L));
        assertTrue("未过期/未满员/没帮过 → 可帮", r.availableFor(OWN_UID, 1792218858L));
        assertFalse("发起人自己不能帮自己", r.availableFor(20778222L, 1792218858L));
        assertFalse("未登录 uid=0 不发", r.availableFor(0L, 1792218858L));
    }

    @Test
    public void parseHelpRequests_ownerFieldFallbacks() {
        List<ServerHelpResponder.HelpReq> byUserId = ServerHelpResponder.parseHelpRequests(List.of(
                map("user_id", 300L, "qid", 1L, "max", 2L)));
        assertEquals(300L, byUserId.get(0).owner);
        assertEquals(300L, ServerHelpResponder.helpOwnerOf(map("userId", "300")));
        assertEquals(300L, ServerHelpResponder.helpOwnerOf(map("id", 300L)));
        assertEquals(300L, ServerHelpResponder.helpOwnerOf(map("uid", 300L)));
        assertEquals(0L, ServerHelpResponder.helpOwnerOf(map("nick", "x")));
        // qid 兼容 queue_id；不再把 help_id 当 qid 的回退
        assertEquals(88L, ServerHelpResponder.helpQidOf(map("queue_id", 88L)));
        assertEquals(0L, ServerHelpResponder.helpQidOf(map("help_id", 88L)));
    }

    @Test
    public void parseHelpRequests_helpersListAndMap() {
        List<ServerHelpResponder.HelpReq> listShape = ServerHelpResponder.parseHelpRequests(List.of(
                map("uid", 1L, "qid", 2L, "max", 5L, "helpers", List.of(10L, 11L))));
        assertEquals(2, listShape.get(0).helpers.size());

        List<ServerHelpResponder.HelpReq> mapShape = ServerHelpResponder.parseHelpRequests(List.of(
                map("uid", 1L, "qid", 2L, "max", 2L,
                        "helpers", map("1.0", 10L, "2.0", 11L))));
        assertTrue("helpers 为 Map 时同样能识别已帮成员", mapShape.get(0).helpers.contains(10L));
        assertEquals(2, mapShape.get(0).helpers.size());
        assertFalse("人数已满（max=2）→ 不可帮", mapShape.get(0).availableFor(OWN_UID, 0L));
    }

    @Test
    public void parseHelpRequests_singleAndEmptyShapes() {
        assertEquals(1, ServerHelpResponder.parseHelpRequests(
                map("uid", 5L, "qid", 6L, "max", 1L)).size());
        assertTrue(ServerHelpResponder.parseHelpRequests(null).isEmpty());
        assertTrue(ServerHelpResponder.parseHelpRequests(List.of()).isEmpty());
        assertTrue("缺 qid 的脏数据忽略",
                ServerHelpResponder.parseHelpRequests(List.of(map("uid", 5L))).isEmpty());
        assertTrue("缺 owner 的脏数据忽略",
                ServerHelpResponder.parseHelpRequests(List.of(map("qid", 5L))).isEmpty());
    }

    @Test
    public void helpIdOf_keyFallsBackToOwnerQidWhenMissing() {
        ServerHelpResponder.HelpReq withId = ServerHelpResponder.parseHelpRequests(List.of(
                helpReq(2002L, 60001L, 12345L, 0L, 51L))).get(0);
        assertEquals(12345L, withId.helpId);
        assertEquals("2002:60001:12345", withId.key());

        ServerHelpResponder.HelpReq withoutId = ServerHelpResponder.parseHelpRequests(List.of(
                map("uid", 2002L, "qid", 60001L, "helpers", List.of(), "max", 51L))).get(0);
        assertEquals(0L, withoutId.helpId);
        assertEquals("缺失 help_id 时回退 owner:qid", "2002:60001", withoutId.key());
    }

    // ==================== 响应码语义（1536 不再是“每日上限”） ====================

    @Test
    public void responseKind_mapping() {
        assertEquals(ServerHelpResponder.HELP_RSP_OK, ServerHelpResponder.helpResponseKind(0));
        assertEquals("1536=服务端此刻没有可响应请求（瞬时），不是每日上限",
                ServerHelpResponder.HELP_RSP_NO_AVAILABLE,
                ServerHelpResponder.helpResponseKind(1536));
        assertEquals(ServerHelpResponder.HELP_RSP_NO_REQUEST,
                ServerHelpResponder.helpResponseKind(75));
        assertEquals(ServerHelpResponder.HELP_RSP_NO_REQUEST,
                ServerHelpResponder.helpResponseKind(2));
        assertEquals(ServerHelpResponder.HELP_RSP_ALLIANCE,
                ServerHelpResponder.helpResponseKind(1510));
        assertEquals(ServerHelpResponder.HELP_RSP_ALLIANCE,
                ServerHelpResponder.helpResponseKind(1511));
        assertEquals(ServerHelpResponder.HELP_RSP_ALLIANCE,
                ServerHelpResponder.helpResponseKind(16873));
        assertEquals(ServerHelpResponder.HELP_RSP_UNKNOWN,
                ServerHelpResponder.helpResponseKind(999));
    }

    @Test
    public void blockMillis_byKind() {
        assertEquals("1536 只短退避 5s，等下一次推送/轮询再试",
                ServerHelpResponder.HELP_NO_AVAILABLE_BLOCK_MILLIS,
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_NO_AVAILABLE));
        assertEquals(ServerHelpResponder.HELP_UNKNOWN_BLOCK_MILLIS,
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_NO_REQUEST));
        assertTrue("未知码要退避，避免 30s 节奏反复打",
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_UNKNOWN) > 0);
        assertTrue("联盟异常等待更久",
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_ALLIANCE)
                        >= ServerHelpResponder.HELP_BLOCK_MILLIS);
        assertEquals(0L, ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_OK));
    }

    @Test
    public void responseHelpers_codeAndHelpNum() {
        assertEquals(0, ServerHelpResponder.responseCode(msg(0, null)));
        assertEquals(1536, ServerHelpResponder.responseCode(msg(1536, null)));
        assertEquals(-1, ServerHelpResponder.responseCode(null));
        assertEquals(5L, ServerHelpResponder.responseHelpNum(
                msg(0, map("help_num", 5L))));
        assertEquals(4L, ServerHelpResponder.responseHelpNum(
                msg(0, map("alliance_help", map("help_num", 4L)))));
        assertEquals(3L, ServerHelpResponder.responseHelpNum(msg(0, map("count", 3L))));
        assertEquals(0L, ServerHelpResponder.responseHelpNum(null));
    }

    // ==================== 会话线程 tick 集成（引擎替身） ====================

    @Test
    public void tick_successRespondsAndWritesState() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all",
                msg(0, map("count", 2L)));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        assertTrue(responder.tick(engine, BASE_NOW));

        assertEquals(1, engine.requested.size());
        assertEquals("req_alliance_help_all", engine.requested.get(0));
        assertTrue("成功文案（只认服务端 count）", modText().contains("已响应 2 位"));
        JSONObject mod = modJson();
        assertEquals("进度字段来自 alliance_help 内层", 1700L, mod.optLong("day_glory", 0L));
        assertEquals(2, mod.optInt("help_num", 0));
    }

    @Test
    public void tick_noAvailableRequests_writesReadableState() {
        TestEngine engine = new TestEngine();
        // 只有自己发起的请求 → 没有可帮对象
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(OWN_UID, 10001L, 777L, 0L, 37L))));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);

        assertTrue("不发无意义请求", engine.requested.isEmpty());
        assertTrue(modText().contains("当前没有盟友需要互助"));
    }

    /** 核心回归：1536 不得落盘/跨天，短暂退避后新请求仍要帮。 */
    @Test
    public void tick_1536_noSettle_keepsWaitingThenHelpsNewRequest() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 111L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(1536, null));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);

        assertEquals("第一次请求照常发出", 1, engine.requested.size());
        assertNull("1536 绝不能写 srv:alliance_help_settled", state.getString(legacyKey()));
        assertTrue("状态文案要说明 1536 语义", modText().contains("1536"));
        assertTrue("状态文案要说明继续等待", modText().contains("继续等待"));
        assertFalse("绝不能出现“明天自动继续”的当日封停文案", modText().contains("明天自动继续"));

        // 新请求推送到达（新 help_id）→ 越过 5s 短退避后必须继续帮（证明没有当天封停）
        engine.push("notify_alliance_help_update",
                updatePush(1L, List.of(helpReq(3003L, 60001L, 222L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));
        responder.tick(engine, BASE_NOW + 6_000L);

        assertEquals("1536 之后的新请求仍要发出请求", 2, engine.requested.size());
        assertTrue(modText().contains("已响应 1 位"));
        assertNull("成功也不写 settled", state.getString(legacyKey()));
    }

    /** 历史版本残留的 settled 键：必须被忽略并清理（否则改完代码当天仍不帮）。 */
    @Test
    public void tick_legacySettledKeyIgnoredAndCleared() {
        state.putString(legacyKey(), "2026-10-28");
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);

        assertEquals("旧 settled 键不能阻止本轮请求", 1, engine.requested.size());
        assertNull("旧 settled 键应被幂等清理", state.getString(legacyKey()));
        assertTrue(modText().contains("已响应 1 位"));
    }

    /** seed-once：30s 兜底轮询不得再用登录快照重放，抹掉“已帮”标记导致重复请求。 */
    @Test
    public void tick_seedOnce_doesNotWipeHandledMarks() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);
        assertEquals(1, engine.requested.size());

        // 30s 后兜底轮询（无新推送）：旧实现会重放 init → 标记丢失 → 再发一次；新实现不得重复。
        responder.tick(engine, BASE_NOW + 31_000L);
        assertEquals("重放不得抹掉乐观已帮标记（否则 30s 重复请求）", 1, engine.requested.size());
    }

    /** 登录快照之后新到的请求必须能被帮到（增量不能被 seed/重放抹掉）。 */
    @Test
    public void tick_newRequestAfterSeed_isHelped() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(OWN_UID, 10001L, 777L, 0L, 37L))));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);
        assertTrue("只有自己的请求：不发", engine.requested.isEmpty());

        engine.push("notify_alliance_help_update",
                updatePush(1L, List.of(helpReq(2002L, 60001L, 888L, 0L, 51L))));
        responder.tick(engine, BASE_NOW + 1_000L);
        assertEquals("登录后新推送的请求要能帮到", 1, engine.requested.size());
        assertTrue(modText().contains("已响应 1 位"));
    }

    /** code=0 但 count=0：不得假成功。 */
    @Test
    public void tick_countZero_notClaimedAsSuccess() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(0, map()));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);

        assertEquals(1, engine.requested.size());
        assertFalse("count=0 不能写“已响应”", modText().contains("已响应"));
        assertTrue("要有可读状态", modText().contains("无实际帮助"));
        assertNull(state.getString(legacyKey()));
    }

    /** 两次发送最小间隔 5s：推送洪峰下不得每条推送打一次协议。 */
    @Test
    public void sendMinInterval_preventsRequestBurst() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);
        assertEquals(1, engine.requested.size());

        engine.push("notify_alliance_help_update",
                updatePush(1L, List.of(helpReq(3003L, 60001L, 222L, 0L, 51L))));
        responder.tick(engine, BASE_NOW + 1_000L);
        assertEquals("5s 内不重复发送", 1, engine.requested.size());

        responder.tick(engine, BASE_NOW + 6_000L);
        assertEquals("越过最小间隔后继续发送", 2, engine.requested.size());
    }

    /** 同一 owner+qid 不同 help_id 的并发请求必须并存；type=3 按 help_id 精确删除。 */
    @Test
    public void helpIdKey_concurrentSameOwnerQid_andType3PreciseRemove() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init", initPush(List.of(
                helpReq(2002L, 60001L, 111L, 0L, 51L),
                helpReq(2002L, 60001L, 222L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(1536, null));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);
        assertEquals("同 owner+qid 的两条并发请求都要保留", 2, responder.pendingHelpCount());

        // type=3 只删 help_id=111 的那条
        engine.push("notify_alliance_help_update",
                updatePush(3L, List.of(helpReq(2002L, 60001L, 111L, 0L, 51L))));
        assertEquals(1, responder.pendingHelpCount());

        // 旧形状（无 help_id）type=3：按 owner:qid 兼容删除
        engine.push("notify_alliance_help_update",
                updatePush(3L, List.of(map("uid", 2002L, "qid", 60001L))));
        assertEquals(0, responder.pendingHelpCount());
    }

    /** auto_help 推送只更新进度，不动请求表。 */
    @Test
    public void autoHelpPush_updatesProgressOnly() {
        TestEngine engine = new TestEngine();
        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        // auto_help 只有进度：走钩子路径更新 help_num/day_glory，不得动请求表。
        engine.push("notify_alliance_help_auto_help", map("help_num", 7L, "day_glory", 900L));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));

        responder.tick(engine, BASE_NOW);

        assertEquals("auto_help 不得清空请求表", 1, responder.pendingHelpCount());
        assertEquals(7, modJson().optInt("help_num", 0));
        assertEquals(900L, modJson().optLong("day_glory", 0L));
    }

    @Test
    public void tick_unknownCodeDoesNotClaimSuccess() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(999, null));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine, BASE_NOW);

        assertEquals(1, engine.requested.size());
        assertFalse("未知码不能写成功", modText().contains("已响应"));
        assertTrue("未知码要有可读状态", modText().contains("999"));
        assertNull(state.getString(legacyKey()));
    }

    @Test
    public void tick_offlineDoesNothing() {
        TestEngine engine = new TestEngine();
        engine.online = false;
        engine.push("notify_alliance_help_init",
                initPush(List.of(helpReq(2002L, 60001L, 12345L, 0L, 51L))));
        engine.responses.put("req_alliance_help_all", msg(0, map("count", 1L)));
        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        assertFalse(responder.tick(engine, BASE_NOW));
        assertTrue(engine.requested.isEmpty());
    }
}
