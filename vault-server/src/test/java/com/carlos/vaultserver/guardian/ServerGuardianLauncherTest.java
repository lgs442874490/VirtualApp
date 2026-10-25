package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 守护盟友 P0 发射器单测（按 {@code design/守护盟友-服务端化设计与分期-20261023.md} §3.4/§4.1）：
 * A-2 三变体参数、变体锁定、响应 endtime/marchId 解析、撤防协议选择、失败原码透传且不重试风暴。
 *
 * <p>不连真协议、不建会话：引擎替身只为 seq 响应/请求留痕。
 */
public class ServerGuardianLauncherTest {

    /** 最小引擎替身：按调用顺序返回预置响应，记录请求。 */
    private static class TestEngine extends WjdrEngine {
        final Deque<GameMessage> marchResponses = new ArrayDeque<>();
        final List<String> requested = new ArrayList<>();
        final List<Map<String, Object>> argLog = new ArrayList<>();
        /** P1：加速响应（{code,endtime}）。 */
        GameMessage speedupResponse;
        /** P1：钻石购买响应（{code}）。 */
        GameMessage quickBuyResponse;

        TestEngine() {
            super(null);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            argLog.add(args);
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH.equals(proto)) {
                GameMessage m = marchResponses.pollFirst();
                return m;
            }
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            requested.add(proto);
            argLog.add(args);
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP.equals(proto)) {
                return speedupResponse;
            }
            if (ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY.equals(proto)) {
                return quickBuyResponse;
            }
            return null;
        }
    }

    private TestEngine engine;

    @Before
    public void setUp() {
        engine = new TestEngine();
    }

    // ---------------- 夹具 ----------------

    private static Map<String, Object> formation() {
        Map<String, Object> f = new LinkedHashMap<>();
        List<Map<String, Object>> heroes = new ArrayList<>();
        heroes.add(new LinkedHashMap<>(Map.of("id", 1L, "count", 1L)));
        f.put("hero_id", heroes);
        f.put("soldier", new ArrayList<>());
        return f;
    }

    private static GameMessage msg(int code) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", code);
        GameMessage m = new GameMessage();
        m.setContent(content);
        return m;
    }

    private static GameMessage okMsg(long marchId, long endtime) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", marchId);
        info.put("endtime", endtime);
        info.put("state", 2L);
        info.put("type", 5L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", 0);
        content.put("march_info", info);
        GameMessage m = new GameMessage();
        m.setContent(content);
        return m;
    }

    // ---------------- A-2 三变体参数 ----------------

    @Test
    public void buildLaunchArgs_emptyVariant() {
        Map<String, Object> f = formation();
        Map<String, Object> args = ServerGuardianLauncher.buildLaunchArgs(674, 684, f, 1001L,
                ServerGuardianLauncher.VARIANT_EMPTY);
        assertEquals("驻防行军 type 必须=5（A-1 实证）", 5L,
                ((Number) args.get("type")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> ep = (Map<String, Object>) args.get("endpoint");
        assertEquals(674L, ((Number) ep.get("x")).longValue());
        assertEquals(684L, ((Number) ep.get("y")).longValue());
        assertTrue("formation 原样透传", args.get("formation") == f);
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) args.get("extra");
        assertTrue("variant0 extra 必须为空", extra.isEmpty());
    }

    @Test
    public void buildLaunchArgs_targetUidAndParam1Variants() {
        Map<String, Object> f = formation();
        Map<String, Object> a1 = ServerGuardianLauncher.buildLaunchArgs(1, 2, f, 1001L,
                ServerGuardianLauncher.VARIANT_TARGET_UID);
        @SuppressWarnings("unchecked")
        Map<String, Object> e1 = (Map<String, Object>) a1.get("extra");
        assertEquals(1001L, ((Number) e1.get("target_uid")).longValue());

        Map<String, Object> a2 = ServerGuardianLauncher.buildLaunchArgs(1, 2, f, 1001L,
                ServerGuardianLauncher.VARIANT_PARAM1);
        @SuppressWarnings("unchecked")
        Map<String, Object> e2 = (Map<String, Object>) a2.get("extra");
        assertEquals(1001L, ((Number) e2.get("param1")).longValue());

        // targetUid 未知（0）：变体 1/2 的 extra 也应为空（不猜参数）
        Map<String, Object> a3 = ServerGuardianLauncher.buildLaunchArgs(1, 2, f, 0L,
                ServerGuardianLauncher.VARIANT_TARGET_UID);
        @SuppressWarnings("unchecked")
        Map<String, Object> e3 = (Map<String, Object>) a3.get("extra");
        assertTrue(e3.isEmpty());
    }

    // ---------------- 发射 / 变体锁定 / 失败不重试 ----------------

    @Test
    public void launch_successLocksVariantAndParsesMarchInfo() {
        long end = 1_792_760_400L;
        engine.marchResponses.add(msg(ServerGuardianLauncher.CODE_QUEUE_FULL));
        engine.marchResponses.add(okMsg(555L, end));
        ServerGuardianLauncher launcher = new ServerGuardianLauncher();

        ServerGuardianLauncher.LaunchResult r = launcher.launch(engine, 674, 684, formation(), 1001L);

        assertTrue("code=0 + marchId>0 才算成功", r.ok);
        assertEquals(555L, r.marchId);
        assertEquals("endtime 必须取响应（权威）", end, r.endtimeSec);
        assertEquals("变体1（target_uid）成功", ServerGuardianLauncher.VARIANT_TARGET_UID, r.variant);
        assertEquals(1, launcher.lockedVariant());
        assertEquals(2, engine.requested.size());

        // 已锁定变体：下一条威胁只试 1 个变体（防变体轮试风暴）
        engine.marchResponses.add(okMsg(556L, end + 30));
        ServerGuardianLauncher.LaunchResult r2 = launcher.launch(engine, 675, 685, formation(), 1001L);
        assertTrue(r2.ok);
        assertEquals(3, engine.requested.size());
        assertEquals(ServerGuardianLauncher.VARIANT_TARGET_UID, r2.variant);
    }

    @Test
    public void launch_allVariantsFail_recordsRawCodeWithoutExtraRetry() {
        engine.marchResponses.add(msg(ServerGuardianLauncher.CODE_QUEUE_FULL));
        engine.marchResponses.add(msg(ServerGuardianLauncher.CODE_NOT_SAME_ALLIANCE_2815));
        engine.marchResponses.add(msg(ServerGuardianLauncher.CODE_HERO_AWAY));
        ServerGuardianLauncher launcher = new ServerGuardianLauncher();

        ServerGuardianLauncher.LaunchResult r = launcher.launch(engine, 674, 684, formation(), 1001L);

        assertFalse(r.ok);
        assertEquals("失败保留最后一个原始业务码", ServerGuardianLauncher.CODE_HERO_AWAY, r.code);
        assertEquals("三变体各试一次（同一次 A-2 自发现），不是重试风暴", 3, engine.requested.size());
        assertEquals("未成功不锁定变体", -1, launcher.lockedVariant());
        assertFalse("classify 只做展示", r.text.isEmpty());
        assertTrue(r.text.contains("英雄不在城"));
    }

    @Test
    public void launch_invalidArgs_noRequest() {
        ServerGuardianLauncher launcher = new ServerGuardianLauncher();
        assertFalse(launcher.launch(engine, 0, 0, formation(), 1L).ok);
        assertFalse(launcher.launch(engine, 674, 684, null, 1L).ok);
        assertTrue("参数不完整时不发任何请求", engine.requested.isEmpty());
    }

    @Test
    public void launch_requestException_noCrash() {
        TestEngine bad = new TestEngine() {
            @Override
            public GameMessage request(String proto, Map<String, Object> args) {
                throw new IllegalStateException("连接已关闭");
            }
        };
        ServerGuardianLauncher.LaunchResult r = new ServerGuardianLauncher()
                .launch(bad, 674, 684, formation(), 1L);
        assertFalse(r.ok);
        assertEquals(ServerGuardianLauncher.CODE_LOCAL_ERROR, r.code);
    }

    // ---------------- 响应解析（防御式） ----------------

    @Test
    public void endtimeAndMarchId_parsingShapes() {
        assertEquals(1792760400L, ServerGuardianLauncher.endtimeOf(okMsg(9L, 1792760400L)));
        assertEquals(9L, ServerGuardianLauncher.marchIdOf(okMsg(9L, 1792760400L)));

        // end_time / 嵌套 march / data 包装
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("code", 0);
        m1.put("end_time", 123L);
        m1.put("march", Map.of("id", 7L));
        assertEquals(123L, ServerGuardianLauncher.endtimeOf(content(m1)));
        assertEquals(0L, ServerGuardianLauncher.marchIdOf(content(m1)));

        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("code", 0);
        m2.put("data", Map.of("endtime", 456L));
        assertEquals(456L, ServerGuardianLauncher.endtimeOf(content(m2)));

        assertEquals("缺字段返回 0（不猜）", 0L, ServerGuardianLauncher.endtimeOf(null));
        assertEquals(0L, ServerGuardianLauncher.marchIdOf(null));
    }

    private static GameMessage content(Map<String, Object> c) {
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    // ---------------- 撤防协议选择 ----------------

    @Test
    public void recallProtoSelection_usesEndtimeNotStateField() {
        assertEquals("在途(state=2)→召回", ServerGuardianLauncher.PROTO_MARCH_RECALL,
                ServerGuardianLauncher.recallProtoFor(true));
        assertEquals("已驻扎(state=3)→off_work", ServerGuardianLauncher.PROTO_MARCH_OFF_WORK,
                ServerGuardianLauncher.recallProtoFor(false));

        assertTrue("now<endtime = 在途", ServerGuardianLauncher.inFlightAt(1000L, 999L));
        assertFalse("now>=endtime = 已驻扎/已到达", ServerGuardianLauncher.inFlightAt(1000L, 1000L));
        assertFalse("endtime 未知按已驻扎处理（保守撤回）", ServerGuardianLauncher.inFlightAt(0L, 1L));

        Map<String, Object> args = ServerGuardianLauncher.buildMarchIdArgs(555L);
        assertEquals(555L, ((Number) args.get("id")).longValue());
        assertTrue("marchId 非法不带 id（不猜）", ServerGuardianLauncher.buildMarchIdArgs(0L).isEmpty());
    }

    @Test
    public void classifyCodeText_mapsKnownGameCodes() {
        assertEquals("成功", ServerGuardianLauncher.classifyCodeText(0));
        assertEquals("队列已满", ServerGuardianLauncher.classifyCodeText(2878));
        assertEquals("英雄不在城", ServerGuardianLauncher.classifyCodeText(301));
        assertEquals("目标不同盟", ServerGuardianLauncher.classifyCodeText(2815));
        assertEquals("目标不同盟", ServerGuardianLauncher.classifyCodeText(1510));
        assertEquals("同城已有我方援助", ServerGuardianLauncher.classifyCodeText(2814));
        assertEquals("行军不存在", ServerGuardianLauncher.classifyCodeText(2806));
        assertEquals("加速道具不足", ServerGuardianLauncher.classifyCodeText(2827));
        assertNotNull(ServerGuardianLauncher.classifyCodeText(9999));
        assertTrue(ServerGuardianLauncher.classifyCodeText(9999).contains("9999"));
    }

    // ---------------- P1：对等加速（req_world_march_speedup） ----------------

    @Test
    public void speedupArgs_shapeMatchesAppEvidence() {
        Map<String, Object> args = ServerGuardianLauncher.buildSpeedupArgs(555L, 301900L, 1001L);
        assertEquals("id = 我方行军号", 555L, ((Number) args.get("id")).longValue());
        assertEquals("itemid = 301900/301901", 301900L, ((Number) args.get("itemid")).longValue());
        Object owner = args.get("owner");
        assertTrue("owner 必须是字符串 uid（A-15 实证）", owner instanceof String);
        assertEquals("1001", owner);
        assertTrue("marchId 非法不猜", !ServerGuardianLauncher.buildSpeedupArgs(0L, 0L, 1L).containsKey("id"));
    }

    @Test
    public void speedup_successParsesEndtime() {
        long end = 1_792_760_400L;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        c.put("endtime", end);
        engine.speedupResponse = content(c);

        ServerGuardianLauncher.SpeedupResult r = new ServerGuardianLauncher()
                .speedup(engine, 555L, 301900L);

        assertTrue("code=0 即受理", r.ok);
        assertEquals(0, r.code);
        assertEquals("响应 endtime 是加速后权威到达时刻", end, r.endtimeSec);
        assertEquals("只发一次（不重试）", 1, engine.requested.size());
        assertEquals(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, engine.requested.get(0));
        Map<String, Object> args = engine.argLog.get(0);
        assertEquals(555L, ((Number) args.get("id")).longValue());
        assertEquals(301900L, ((Number) args.get("itemid")).longValue());
        assertTrue(args.get("owner") instanceof String);
    }

    @Test
    public void speedup_itemShortageKeepsRawCode() {
        engine.speedupResponse = msg(ServerGuardianLauncher.CODE_ITEM_SHORTAGE);
        ServerGuardianLauncher.SpeedupResult r = new ServerGuardianLauncher()
                .speedup(engine, 555L, 301901L);
        assertFalse("2827=道具不足，不算成功", r.ok);
        assertEquals(ServerGuardianLauncher.CODE_ITEM_SHORTAGE, r.code);
        assertTrue("分类文案可读", r.text.contains("加速道具不足"));
    }

    @Test
    public void speedup_invalidArgs_noRequest() {
        ServerGuardianLauncher launcher = new ServerGuardianLauncher();
        assertFalse(launcher.speedup(engine, 0L, 301900L).ok);
        assertFalse(launcher.speedup(engine, 555L, 0L).ok);
        assertFalse(launcher.speedup(null, 555L, 301900L).ok);
        assertTrue("参数不完整时不发任何请求", engine.requested.isEmpty());
    }

    @Test
    public void speedup_requestException_noCrash() {
        TestEngine bad = new TestEngine() {
            @Override
            public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
                throw new IllegalStateException("连接已关闭");
            }
        };
        ServerGuardianLauncher.SpeedupResult r = new ServerGuardianLauncher()
                .speedup(bad, 555L, 301900L);
        assertFalse(r.ok);
        assertEquals(ServerGuardianLauncher.CODE_LOCAL_ERROR, r.code);
    }

    // ---------------- P1：钻石快捷购买（req_shop_quick_buy） ----------------

    @Test
    public void quickBuyArgs_shapeMatchesAppEvidence() {
        Map<String, Object> args = ServerGuardianLauncher.buildQuickBuyArgs(301901L, 1L);
        assertEquals("App 实证 use=false（只买不用）", Boolean.FALSE, args.get("use"));
        assertEquals(301901L, ((Number) args.get("id")).longValue());
        assertEquals(1L, ((Number) args.get("count")).longValue());
        assertEquals("App 实证 8s 超时", 8, ServerGuardianLauncher.QUICK_BUY_TIMEOUT_SEC);
        assertEquals(301901L, ServerGuardianLauncher.DIAMOND_BUY_ITEM_ID);
    }

    @Test
    public void quickBuy_successAndFailureKeepRawCode() {
        engine.quickBuyResponse = msg(0);
        ServerGuardianLauncher.QuickBuyResult ok = new ServerGuardianLauncher()
                .quickBuy(engine, 301901L, 1L);
        assertTrue("code=0 视为购买成功（301901 入背包）", ok.ok);
        assertEquals(0, ok.code);
        assertEquals(301901L, ok.itemId);
        assertEquals("单发不重试", 1, engine.requested.size());
        assertEquals(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, engine.requested.get(0));
        Map<String, Object> args = engine.argLog.get(0);
        assertEquals(Boolean.FALSE, args.get("use"));
        assertEquals(301901L, ((Number) args.get("id")).longValue());
        assertEquals(1L, ((Number) args.get("count")).longValue());

        // 失败码原样透传（不发第二次）
        engine.requested.clear();
        engine.argLog.clear();
        engine.quickBuyResponse = msg(1002);
        ServerGuardianLauncher.QuickBuyResult bad = new ServerGuardianLauncher()
                .quickBuy(engine, 301901L, 1L);
        assertFalse(bad.ok);
        assertEquals(1002, bad.code);
        assertTrue("日志文案不含成功字样", bad.text.contains("失败"));
        assertEquals(1, engine.requested.size());
    }

    @Test
    public void quickBuy_invalidArgsOrException_noCrash() {
        ServerGuardianLauncher launcher = new ServerGuardianLauncher();
        assertFalse(launcher.quickBuy(engine, 0L, 1L).ok);
        assertFalse(launcher.quickBuy(engine, 301901L, 0L).ok);
        assertFalse(launcher.quickBuy(null, 301901L, 1L).ok);
        assertTrue("参数不完整时不发任何请求", engine.requested.isEmpty());

        TestEngine bad = new TestEngine() {
            @Override
            public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
                throw new IllegalStateException("连接已关闭");
            }
        };
        ServerGuardianLauncher.QuickBuyResult r = launcher.quickBuy(bad, 301901L, 1L);
        assertFalse(r.ok);
        assertEquals(ServerGuardianLauncher.CODE_LOCAL_ERROR, r.code);
    }
}
