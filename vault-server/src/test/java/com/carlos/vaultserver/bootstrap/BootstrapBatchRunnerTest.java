package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.AccountEntry;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.AccountState;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.EngineFactory;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.ImportResult;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.Session;
import com.carlos.vaultserver.bootstrap.BootstrapService.BootstrapConfig;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link BootstrapBatchRunner} 单测（M4b 任务书 §2.6）：EngineFactory 替身
 * （fake {@link WjdrEngine}：login/close 全替身）+ 脚本协议端口（req_loaddata 直接回
 * 熔炉达标 loaddata，跳时 Sleeper）——零真实网络。覆盖：2 号串行（1 成功 1 建号失败）、
 * cancel 语义（前置/执行中）、超时上限、import 三形态（加密串/fpid|token/非法行）、
 * SUCCESS 写回流清单 + 会话表清理。
 */
public class BootstrapBatchRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 共享 hold 表与会话状态是进程级：每个用例前清空，避免 fpid 复用时串用例（卡点 A / P1-2 新增）。 */
    @org.junit.Before
    public void resetSharedHolds() {
        BootstrapHoldStore.shared().clearAll();
        BootstrapSessionState.shared().clearAll();
    }

    private static final BootstrapService.Sleeper NO_SLEEP = millis -> {
    };

    // ================= fake 装配 =================

    /** fake 引擎：login 固定结果（或抛错），close 记录关闭。 */
    private static final class FakeEngine extends WjdrEngine {
        final long uid;
        final long serverId;
        RuntimeException loginError;
        int loginCalls;
        /** 前 N 次 login 抛「角色已存在」（建号失败 → 回退登录链路用）。 */
        int failNextLogins;
        int initializeCalls;
        boolean closed;

        FakeEngine(long uid, long serverId) {
            super(null);
            this.uid = uid;
            this.serverId = serverId;
        }

        @Override
        public void initialize() {
            initializeCalls++;
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid,
                                 Map<String, Object> extraParams) throws Exception {
            loginCalls++;
            if (loginError != null) throw loginError;
            if (failNextLogins > 0) {
                failNextLogins--;
                throw new IllegalStateException("loginauth code=9 角色已存在");
            }
            LoginResult r = new LoginResult();
            r.uid = uid;
            r.serverId = serverid != null ? serverid : serverId;
            r.authContent = Map.of();
            return r;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 脚本回放端口：按 proto 排队响应；未脚本化 proto 默认 code=0（同 BootstrapServiceTest）。 */
    private static final class ScriptedPort implements BootstrapService.ProtocolPort {
        final List<Map.Entry<String, Map<String, Object>>> calls = new ArrayList<>();
        private final Map<String, java.util.ArrayDeque<Map<String, Object>>> scripted =
                new LinkedHashMap<>();

        void when(String proto, Map<String, Object>... contents) {
            java.util.ArrayDeque<Map<String, Object>> q =
                    scripted.computeIfAbsent(proto, ignored -> new java.util.ArrayDeque<>());
            q.addAll(Arrays.asList(contents));
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(new AbstractMap.SimpleImmutableEntry<>(proto, args));
            GameMessage msg = new GameMessage();
            msg.setProto(proto);
            java.util.ArrayDeque<Map<String, Object>> q = scripted.get(proto);
            msg.setContent(q != null && !q.isEmpty() ? q.poll()
                    : new LinkedHashMap<>(Map.of("code", 0)));
            return msg;
        }
    }

    private static Map<String, Object> content(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 熔炉已达标的 loaddata（buildings + 空队列 + 资源）→ runUntil 直接 COMPLETED。 */
    private static Map<String, Object> loadCompleted(long furnaceLevel) {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", furnaceLevel, "status", 0L));
        return content("uid", 8001L, "name", "起号号",
                "buildings", buildings, "queues", new java.util.ArrayList<>(),
                "res", content("103", 100000, "101", 500));
    }

    /** 熔炉 9 + 301→10 前置满足、资源为 0：51 三次后应 ENDED（R-06/R-07）。 */
    private static Map<String, Object> loadFurnace9Ended() {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 9L, "status", 0L));
        buildings.put("323", content("bid", 323L, "lv", 9L, "status", 0L));
        buildings.put("329", content("bid", 329L, "lv", 1L, "status", 0L));
        return content("uid", 8001L, "name", "起号号",
                "buildings", buildings, "queues", new java.util.ArrayList<>(),
                "res", content("101", 0));
    }

    /**
     * 601 复现用 loaddata：熔炉 5 + 铁矿厂 304 1 级 + 家具 601 1 级
     * （熔炉 6 需 304 到 5 级 → 601 需先到 2 级）。另带一条忙碌建造队列（302），
     * 使每轮 runUntil 的第一个动作是「秒完施工队列」→ 失败轮也一定是 started&gt;0。
     */
    private static Map<String, Object> loadFurnitureStall() {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 5L, "status", 0L));
        buildings.put("304", content("bid", 304L, "lv", 1L, "status", 0L));
        buildings.put("318", content("bid", 318L, "lv", 1L, "status", 0L));
        buildings.put("310", content("bid", 310L, "lv", 3L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        Map<String, Object> busyQueue = content("qid", 1L, "bid", 302L, "status", 0L);
        return content("uid", 8001L, "name", "起号号",
                "buildings", buildings,
                "furnitures", List.of(content("bid", 304L, "conf_fid", 601L, "lv", 1L)),
                "queues", List.of(busyQueue),
                "res", content("103", 100000, "101", 500));
    }

    /** 合法加密串（RegistrationService.parseEncrypted 可解析：fpid\ntoken\nbase64(device)）。 */
    private static String encryptedOf(String fpid, String token) {
        String device = Base64.getEncoder().encodeToString(
                "{\"device_id\":\"d1\"}".getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().encodeToString(
                (fpid + "\n" + token + "\n" + device).getBytes(StandardCharsets.UTF_8));
    }

    /** 引擎捕获表（close 断言用）。 */
    private final List<FakeEngine> engines = new ArrayList<>();

    /** 单号成功的会话工厂：每号新 fake engine + 熔炉达标端口。 */
    private EngineFactory successFactory(long furnaceLevel) {
        return (fpid, sdkToken, serverId) -> {
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            port.when("req_loaddata", loadCompleted(furnaceLevel));
            return new Session(engine, port);
        };
    }

    private BootstrapBatchRunner runner(BootstrapConfig cfg, List<AccountEntry> accounts,
                                        BootstrapBatchStore store, BootstrapReferral referral,
                                        EngineFactory factory, ConcurrentMap<String, Session> active) {
        return new BootstrapBatchRunner(cfg, accounts, store, referral, factory,
                NO_SLEEP, BootstrapBatchRunner.DEFAULT_TIMEOUT_MINUTES, active);
    }

    private static BootstrapConfig batchConfig(long serverId, int target) {
        return new BootstrapConfig(true, serverId, target, true, false, false, "", false, "");
    }

    private List<AccountEntry> accounts(String... fpids) {
        ArrayList<String> lines = new ArrayList<>();
        for (String fpid : fpids) lines.add(fpid + "|tk-" + fpid);
        return new ArrayList<>(BootstrapBatchRunner.importAccounts(lines).accounts());
    }

    // ================= import 三形态 =================

    @Test
    public void importAccounts_threeFormsAndDedup() {
        String encrypted = encryptedOf("fp-enc", "tk-enc");
        ImportResult r = BootstrapBatchRunner.importAccounts(List.of(
                encrypted, "fp2|tk2", "not-a-valid-encrypted!!", "fp2|tk-dup", "   "));
        assertEquals(2, r.accounts().size());

        AccountEntry first = r.accounts().get(0);
        assertEquals("fp-enc", first.fpid);
        assertEquals("tk-enc", first.sdkToken);
        assertEquals(encrypted, first.encrypted);
        AccountEntry second = r.accounts().get(1);
        assertEquals("fp2", second.fpid);
        assertNull("两段式导入 encrypted 为 null", second.encrypted);

        assertEquals(3, r.errors().size());
        assertTrue(r.errors().get(0).contains("第 3 行"));
        assertTrue(r.errors().get(1).contains("重复"));
        assertTrue(r.errors().get(2).contains("第 5 行"));
    }

    @Test
    public void importAccounts_emptyInput() {
        ImportResult r = BootstrapBatchRunner.importAccounts(List.of());
        assertEquals(0, r.accounts().size());
        assertEquals(1, r.errors().size());
    }

    // ================= 批次执行 =================

    @Test
    public void run_twoAccounts_firstSuccessSecondCreateFailed() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        AtomicInteger created = new AtomicInteger();
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            if (created.incrementAndGet() == 2) {
                throw new RuntimeException("建号失败：区服排队中");
            }
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            port.when("req_loaddata", loadCompleted(2L));
            return new Session(engine, port);
        };
        BootstrapBatchRunner runner = runner(batchConfig(1L, 2), accounts("fp1", "fp2"),
                store, referral, factory, new ConcurrentHashMap<>());
        runner.run();

        assertEquals("COMPLETED", runner.state());
        assertEquals(1, runner.successCount());
        assertEquals(1, runner.failCount());
        assertEquals(AccountState.SUCCESS, runner.entry(0).state);
        assertEquals(8001L, runner.entry(0).uid);
        assertEquals(2L, runner.entry(0).furnaceLevel);
        assertEquals(AccountState.FAILED, runner.entry(1).state);
        assertTrue(runner.entry(1).error.contains("建号失败"));
        // 视图汇总文案对齐原版前端日志
        assertTrue(runner.view().optString("message").contains("成功 1 个，失败 1 个"));
        // 失败号不写回流
        assertEquals(1, referral.listPending().size());
        assertEquals("fp1", referral.listPending().get(0).optString("fpid"));
        // 会话已关闭
        assertEquals(1, engines.size());
        assertTrue(engines.get(0).closed);
    }

    /**
     * R-06 回归：runNext 返回 ENDED（资源耗尽）时，批次必须立即结束该号并置 ENDED 终态，
     * 不再按「停滞」空转 10 分钟或误判 FAILED。
     */
    @Test
    public void run_endedAccount_marksEndedImmediately() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            // 每轮 runLeveling 都会重新拉 loaddata：队列里备足多轮
            for (int i = 0; i < 400; i++) {
                port.when("req_loaddata", loadFurnace9Ended());
            }
            for (int i = 0; i < 600; i++) {
                port.when("req_upgrade_building", content("code", 51L));
            }
            port.when("req_shop_info", content("info", content("id", 701L,
                    "goods", content("1", content("id", 555L, "conf_id", 70110102L,
                            "grid_id", 1L)))));
            return new Session(engine, port);
        };
        // buyShopResources=true：51 时按商店购买结果判定 coverable（钻石 0 → 买不到 → 不可覆盖）
        BootstrapConfig endedConfig = new BootstrapConfig(true, 1L, 10, true, false, true,
                "", false, "");
        BootstrapBatchRunner runner = runner(endedConfig, accounts("fp-ended"),
                store, referral, factory, new ConcurrentHashMap<>());

        runner.run();

        assertEquals("ENDED 必须是该号终态（error=" + runner.entry(0).error + "）",
                AccountState.ENDED, runner.entry(0).state);
        assertEquals("ENDED 不计入 FAILED 统计", 0, runner.failCount());
        assertTrue("ENDED 计入终态集合", BootstrapBatchRunner.isTerminal("ENDED"));
        assertTrue("ENDED 文案应说明资源耗尽：" + runner.entry(0).error,
                runner.entry(0).error.contains("资源"));
        assertEquals("ENDED 也要回填熔炉等级（UI 展示）", 9L, runner.entry(0).furnaceLevel);
        assertTrue("批次汇总应体现已结束：" + runner.view().optString("message"),
                runner.view().optString("message").contains("已结束"));
    }

    /** SUCCESS → 写回流清单（含 encrypted/batchId/uid/furnaceLevel/pulled=false 完整结构）。 */
    @Test
    public void run_successWritesReferralAndClearsActive() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        ConcurrentMap<String, Session> active = new ConcurrentHashMap<>();
        BootstrapBatchRunner runner = runner(batchConfig(5L, 2), accounts("fp1"),
                store, referral, successFactory(2L), active);
        runner.entry(0).label = "盟主号";

        runner.run();

        assertEquals("COMPLETED", runner.state());
        assertEquals(1, referral.listPending().size());
        org.json.JSONObject item = referral.listPending().get(0);
        assertEquals("fp1", item.optString("fpid"));
        assertEquals("盟主号", item.optString("label"));
        assertEquals("tk-fp1", item.optString("sdkToken"));
        assertTrue(item.isNull("encrypted"));
        assertEquals(5L, item.optLong("serverId"));
        assertEquals(8001L, item.optLong("uid"));
        assertEquals(2L, item.optLong("furnaceLevel"));
        assertEquals(runner.id, item.optString("batchId"));
        assertFalse(item.optBoolean("pulled", true));
        assertTrue(item.has("bootstrappedAt"));
        // 批次结束后在线会话表清空
        assertFalse(active.containsKey("fp1"));
        assertEquals(1, engines.size());
        assertTrue(engines.get(0).closed);
        // 步骤摘要
        assertTrue(runner.entry(0).steps.get(0).contains("建号成功"));
    }

    /** 起号停滞（started==0 且未达标）→ 该号 FAILED 带原因，批次继续/部分成功。 */
    @Test
    public void run_stalledAccount_failsWithReason() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        // instantDiamond=false → runNext 返回 MANUAL（等待钻石即时升级）→ started==0 停滞
        BootstrapConfig cfg = new BootstrapConfig(true, 1L, 2, false, false, false, "", false, "");
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            // 熔炉 1 未达标 + 队列空闲 + 资源充足：规划可执行但 MANUAL 挡住
            Map<String, Object> buildings = new LinkedHashMap<>();
            buildings.put("301", content("bid", 301L, "lv", 1L, "status", 0L));
            port.when("req_loaddata", content("uid", 8001L, "name", "起号号",
                    "buildings", buildings, "queues", new java.util.ArrayList<>(),
                    "res", content("103", 100000)));
            return new Session(engine, port);
        };
        BootstrapBatchRunner runner = runner(cfg, accounts("fp1"),
                store, new BootstrapReferral(dataDir()), factory, new ConcurrentHashMap<>());
        runner.run();

        // 全部号失败 → 批次 FAILED（对齐注册批次 runner 终态语义：无成功即 FAILED）
        assertEquals("FAILED", runner.state());
        assertEquals(0, runner.successCount());
        assertEquals(1, runner.failCount());
        assertEquals(AccountState.FAILED, runner.entry(0).state);
        assertTrue(runner.entry(0).error.contains("起号停滞"));
        assertTrue(runner.entry(0).error.contains("钻石即时升级"));
    }

    /**
     * 真实轮回归（2026-11-10）：等待类阻塞原因不得判失败（新号资源不足、建筑正在建造、
     * 等待同步都是正常中间态）；而「需要开启钻石即时升级」属于需人工改配置的状态，必须暴露失败。
     */
    @Test
    public void isWaitableBlock_classifiesReasons() {
        assertTrue(BootstrapBatchRunner.isWaitableBlock("资源或道具不足，等待补充后重试：建造 伐木场 到 1 级"));
        assertTrue(BootstrapBatchRunner.isWaitableBlock("大熔炉 正在建造或升级，等待完成后继续"));
        assertTrue(BootstrapBatchRunner.isWaitableBlock("等待建筑与队列全量状态同步"));
        assertTrue(BootstrapBatchRunner.isWaitableBlock("当前没有空闲建筑队列"));
        // 卡点 A：51 长退避的等待文案必须算等待类，不能判成停滞立即 FAILED
        assertTrue(BootstrapBatchRunner.isWaitableBlock("动作退避中，剩余 1800 秒"));
        // 需人工介入（未开钻石即时升级）→ 非等待类，应失败暴露
        assertFalse(BootstrapBatchRunner.isWaitableBlock("下一步 伐木场 2 级需要等待完成，请开启钻石即时升级"));
        assertFalse(BootstrapBatchRunner.isWaitableBlock(""));
        assertFalse(BootstrapBatchRunner.isWaitableBlock(null));
        assertFalse(BootstrapBatchRunner.isWaitableBlock("前置建筑 999 不在门槛目录中，无法自动建造"));
    }

    /** 卡点 A：51 长退避不能按普通停滞计数（10 分钟上限会在 30 分钟 hold 到期前误判）。 */
    @Test
    public void isHoldWait_onlyHoldReason() {
        assertTrue(BootstrapBatchRunner.isHoldWait("动作退避中，剩余 1800 秒"));
        assertFalse(BootstrapBatchRunner.isHoldWait("资源或道具不足，等待补充后重试"));
        assertFalse(BootstrapBatchRunner.isHoldWait(""));
        assertFalse(BootstrapBatchRunner.isHoldWait(null));
    }

    /**
     * 实跑 2026-11-12 回归：hold 等待轮必须重置「只有辅助动作」计数，
     * 否则 30 分钟 hold 期间累计 >60 轮，任一次辅助动作成功都会误判停滞。
     */
    @Test
    public void nextLastBuildingRound_holdWaitResetsAuxiliaryOnlyCounter() {
        assertEquals("hold 等待轮：计数跟随当前轮",
                80, BootstrapBatchRunner.nextLastBuildingRound(80, 0, false, true));
        assertEquals("真实建筑动作：计数跟随当前轮",
                61, BootstrapBatchRunner.nextLastBuildingRound(61, 0, true, false));
        assertEquals("普通辅助动作轮：保持上次建筑动作轮次",
                0, BootstrapBatchRunner.nextLastBuildingRound(61, 0, false, false));
    }

    /** 单号超时上限（包级构造注入 0 毫秒立即触发）→ FAILED 带超时文案。 */
    @Test
    public void run_timeoutLimitMarksAccountFailed() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapConfig cfg = new BootstrapConfig(true, 1L, 9, true, false, false, "", false, "");
        BootstrapBatchRunner runner = new BootstrapBatchRunner(cfg, accounts("fp1"),
                store, new BootstrapReferral(dataDir()), successFactory(1L),
                NO_SLEEP, 0L, new ConcurrentHashMap<>());
        runner.run();

        // 唯一号超时失败 → 批次 FAILED（对齐注册批次 runner 终态语义）
        assertEquals("FAILED", runner.state());
        assertEquals(AccountState.FAILED, runner.entry(0).state);
        assertTrue(runner.entry(0).error.contains("起号超时上限"));
    }

    // ================= 601 终态传导（P1-1）/ 回退登录降级（P2-6） =================

    /**
     * P1-1 回归（2026-11-14 二次审查）：家具 51 达上限的 {@code FAILED} 必须是显式终态。
     * 旧实现的失败只留在 {@code summary.results} 里，而 {@code runLeveling} 在
     * {@code started>0} 时按「有进展」继续 → 计数被清 → 循环到单号超时（120 分钟）。
     *
     * <p>本用例把上限配成 1（第一轮即达上限），并用一条忙碌建造队列保证失败轮
     * {@code started>0}（首个动作是「秒完施工队列」），从而精确复现被吞路径。
     */
    @Test
    public void run_furnitureStallFailed_notSwallowedWhenRoundStarted() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        BootstrapConfig cfg = new BootstrapConfig(true, 1L, 9, true, false, false,
                "", false, "", 1);
        ScriptedPort[] portBox = new ScriptedPort[1];
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            portBox[0] = port;
            for (int i = 0; i < 60; i++) {
                port.when("req_loaddata", loadFurnitureStall());
                port.when("req_upgrade_furniture", content("code", 51L));
            }
            return new Session(engine, port);
        };
        BootstrapBatchRunner runner = new BootstrapBatchRunner(cfg, accounts("fp-stall"),
                store, referral, factory, NO_SLEEP, 5_000L, new ConcurrentHashMap<>());

        runner.run();

        // 证明失败轮确实 started>0：首轮先「秒完施工队列」（STARTED），同一轮内才发生 51 失败。
        List<String> protos = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> call : portBox[0].calls) {
            protos.add(call.getKey());
        }
        int load = protos.indexOf("req_loaddata");
        int speed = protos.indexOf(BootstrapService.QUEUE_SPEED);
        int upgrade = protos.indexOf("req_upgrade_furniture");
        assertTrue("首轮应先秒队列（STARTED）：" + protos, speed > load);
        assertTrue("51 失败应发生在同一轮的秒队列之后：" + protos, upgrade > speed);
        assertFalse("秒队列与 51 失败之间不得重新拉 loaddata（同一轮）：" + protos,
                protos.subList(speed, upgrade).contains("req_loaddata"));

        assertEquals("601 是显式终态，必须是 FAILED",
                AccountState.FAILED, runner.entry(0).state);
        assertTrue("失败原因必须是「重新发起起号」而不是超时/空转：" + runner.entry(0).error,
                runner.entry(0).error.contains("重新发起起号"));
        assertFalse("不得被「起号停滞：」前缀改写（原始 message 直出）：" + runner.entry(0).error,
                runner.entry(0).error.startsWith("起号停滞"));
        assertEquals("终态失败计入 failCount", 1, runner.failCount());
    }

    /**
     * 二次审查 P2-3：清单显式 {@code #uid} 是操作者意图，钉扎失败必须 fail-fast，
     * 不得降级到服务端默认角色（否则可能在老号上继续起号、误耗资源）。
     */
    @Test
    public void run_explicitUidPinnedMismatch_failsFast() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        List<AccountEntry> accounts = new ArrayList<>();
        AccountEntry entry = new AccountEntry("fp-uid", "tk-uid", null, "");
        entry.targetUid = 9999L;   // 显式指定角色 uid，服务端实际返回 8001
        accounts.add(entry);
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engine.failNextLogins = 1;   // 建号失败（角色已存在）→ 进入回退登录
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            port.when("req_loaddata", loadCompleted(9L));
            return new Session(engine, port);
        };
        BootstrapBatchRunner runner = runner(batchConfig(1L, 9), accounts, store, referral,
                factory, new ConcurrentHashMap<>());

        runner.run();

        assertEquals("显式 #uid 失配必须 fail-fast，不得降级登错角色："
                        + runner.entry(0).error, AccountState.FAILED, runner.entry(0).state);
        assertTrue("失败原因应说明指定角色未命中：" + runner.entry(0).error,
                runner.entry(0).error.contains("未能登录指定角色"));
        assertFalse("不得静默降级：", String.join("；", runner.entry(0).steps).contains("降级"));
    }

    /**
     * P2-6 回归（二次审查 P2-3 调整后）：只有**存档启发式 uid**（非显式 #uid）钉扎失败时，
     * 才降级为「服务端默认角色」再登录一次，避免回退链路直接失败。
     */
    @Test
    public void run_archivedUidPinnedMismatch_degradesToDefaultLogin() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        // 存档记录 uid=9999（已失效），服务端实际返回 8001
        Path archiveDir;
        try {
            archiveDir = tmp.newFolder("archive-degrade").toPath();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        com.carlos.vaultserver.registration.AccountArchive archive =
                com.carlos.vaultserver.registration.AccountArchive.install(archiveDir);
        archive.recordRole("fp-arch", 1L, 9999L);
        try {
            EngineFactory factory = (fpid, sdkToken, serverId) -> {
                FakeEngine engine = new FakeEngine(8001L, serverId);
                engine.failNextLogins = 1;   // 建号失败（角色已存在）→ 进入回退登录
                engines.add(engine);
                ScriptedPort port = new ScriptedPort();
                port.when("req_loaddata", loadCompleted(9L));
                return new Session(engine, port);
            };
            BootstrapBatchRunner runner = runner(batchConfig(1L, 9), accounts("fp-arch"),
                    store, referral, factory, new ConcurrentHashMap<>());

            runner.run();

            assertEquals("存档 uid 未命中应降级默认登录并继续：" + runner.entry(0).error,
                    AccountState.SUCCESS, runner.entry(0).state);
            assertEquals(8001L, runner.entry(0).uid);
            assertTrue("步骤摘要应记录降级：" + String.join("；", runner.entry(0).steps),
                    String.join("；", runner.entry(0).steps).contains("降级"));
        } finally {
            resetArchiveSingleton();
        }
    }

    /** 清理 AccountArchive 进程级单例，避免污染其它用例。 */
    private static void resetArchiveSingleton() {
        try {
            java.lang.reflect.Field field =
                    com.carlos.vaultserver.registration.AccountArchive.class
                            .getDeclaredField("INSTANCE");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("重置 AccountArchive 单例失败", e);
        }
    }

    // ================= cancel 语义 =================

    @Test
    public void cancelBeforeRun_finishesCancelled() {
        BootstrapBatchRunner runner = runner(batchConfig(1L, 2), accounts("fp1"),
                new BootstrapBatchStore(dataDir()), new BootstrapReferral(dataDir()),
                successFactory(2L), new ConcurrentHashMap<>());
        runner.cancel();
        runner.run();
        assertEquals("CANCELLED", runner.state());
        assertEquals(AccountState.PENDING, runner.entry(0).state);
    }

    /** 执行中取消：当前号自然结束（标 FAILED 批次已取消）后停，不再开下一号。 */
    @Test
    public void cancelDuringBatch_stopsAfterCurrentAccount() {
        BootstrapBatchStore store = new BootstrapBatchStore(dataDir());
        BootstrapReferral referral = new BootstrapReferral(dataDir());
        AtomicReference<BootstrapBatchRunner> holder = new AtomicReference<>();
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            BootstrapBatchRunner running = holder.get();
            if (running != null) running.cancel(); // 第 1 号会话建立时请求取消
            return successFactory(2L).create(fpid, sdkToken, serverId);
        };
        BootstrapBatchRunner runner = runner(batchConfig(1L, 2), accounts("fp1", "fp2"),
                store, referral, factory, new ConcurrentHashMap<>());
        holder.set(runner);
        runner.run();

        assertEquals("CANCELLED", runner.state());
        assertEquals("当前号自然结束后置 FAILED（批次已取消）",
                AccountState.FAILED, runner.entry(0).state);
        assertTrue(runner.entry(0).error.contains("批次已取消"));
        assertEquals("下一号不再开", AccountState.PENDING, runner.entry(1).state);
    }

    // ================= 重复 run 防重入 =================

    @Test
    public void runTwice_secondRunIgnored() {
        BootstrapBatchRunner runner = runner(batchConfig(1L, 2), accounts("fp1"),
                new BootstrapBatchStore(dataDir()), new BootstrapReferral(dataDir()),
                successFactory(2L), new ConcurrentHashMap<>());
        runner.run();
        assertEquals("COMPLETED", runner.state());
        runner.run(); // 幂等：不再执行
        assertEquals(1, runner.successCount());
    }

    // ================= 并发视图安全（P1-1 回归） =================

    /** 批次线程 steps.add 与视图线程 toView（String.join 迭代）并发：迭代期 add 不抛 CME。 */
    @Test
    public void toView_safeUnderConcurrentStepsAdd() throws Exception {
        AccountEntry entry = new AccountEntry("fp-cow", "tk-cow", null, "");
        AtomicBoolean done = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                entry.steps.add("第 " + i + " 步");
            }
            done.set(true);
        });
        writer.start();
        int reads = 0;
        while (!done.get()) {
            // 写入窗口内反复迭代建视图：不抛 CME 即通过（初始窗口 steps 可为空，不校验内容）
            entry.toView();
            reads++;
        }
        writer.join();
        assertEquals(20_000, entry.steps.size());
        assertTrue("写入窗口内完成多次视图迭代", reads > 0);
        assertFalse("全部写完后视图含完整步骤", entry.toView().optString("steps").isEmpty());
    }

    // ================= 轮间中断（P2-f 回归） =================

    /**
     * 轮间 sleep 被中断：恢复中断标志 + 置 cancelled → 批次 CANCELLED，当前号「批次已取消」，
     * 剩余号保持 PENDING（不再伪 FAILED）。
     */
    @Test
    public void roundGapInterrupt_cancelsBatchAndKeepsRestPending() throws Exception {
        BootstrapService.Sleeper stopAtRoundGap = millis -> {
            if (millis == BootstrapBatchRunner.ROUND_INTERVAL_MILLIS) {
                throw new InterruptedException("轮间被中断");
            }
        };
        EngineFactory factory = (fpid, sdkToken, serverId) -> {
            FakeEngine engine = new FakeEngine(8001L, serverId);
            engines.add(engine);
            ScriptedPort port = new ScriptedPort();
            // 熔炉未达标（301 lv=0）+ 升级请求 2 次成功后 51 阻塞 → 本轮 started=2、熔炉未达标
            // → 进入轮间 sleep（5000ms）→ 被 stopAtRoundGap 中断
            Map<String, Object> buildings = new LinkedHashMap<>();
            buildings.put("301", content("bid", 301L, "lv", 0L, "status", 0L));
            buildings.put("302", content("bid", 302L, "lv", 0L, "status", 0L));
            port.when("req_loaddata", content("uid", 8001L, "name", "起号号",
                    "buildings", buildings, "queues", new java.util.ArrayList<>(),
                    "res", content("103", 100000, "101", 500)));
            port.when("req_upgrade_building",
                    content("code", 0L), content("code", 0L),
                    content("code", 51L), content("code", 51L));
            return new Session(engine, port);
        };
        BootstrapBatchRunner runner = new BootstrapBatchRunner(batchConfig(1L, 9),
                accounts("fp1", "fp2"), new BootstrapBatchStore(dataDir()),
                new BootstrapReferral(dataDir()), factory, stopAtRoundGap,
                BootstrapBatchRunner.DEFAULT_TIMEOUT_MINUTES, new ConcurrentHashMap<>());
        runner.run();

        assertEquals("轮间中断 → 批次 CANCELLED", "CANCELLED", runner.state());
        assertEquals(AccountState.FAILED, runner.entry(0).state);
        assertTrue(runner.entry(0).error.contains("批次已取消"));
        assertTrue(runner.entry(0).steps.get(runner.entry(0).steps.size() - 1).contains("取消"));
        assertEquals("剩余号保持 PENDING，不伪 FAILED", AccountState.PENDING, runner.entry(1).state);
        assertTrue("中断标志已恢复", Thread.interrupted()); // 顺带清除，免污染后续测试
    }

    @Test
    public void importAccounts_invalidUtf8LineReportsLineNumber() {
        try {
            ImportResult r = BootstrapBatchRunner.importAccounts(Arrays.asList(
                    "fp1|tk1", "///???"));
            assertEquals(1, r.accounts().size());
            assertEquals(1, r.errors().size());
            assertTrue(r.errors().get(0).contains("第 2 行"));
        } catch (Exception e) {
            fail("不应抛出: " + e);
        }
    }

    private Path dataDir() {
        return tmp.getRoot().toPath();
    }
}
