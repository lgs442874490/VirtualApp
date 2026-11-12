package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Action;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.ActionKind;
import com.carlos.vaultserver.bootstrap.BootstrapService.BootstrapConfig;
import com.carlos.vaultserver.bootstrap.BootstrapService.LoginOutcome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link BootstrapService} 单测（M4a 任务书 §2.5）：fake {@link BootstrapService.ProtocolPort}
 * 脚本回放 + 可注入 Sleeper——零真实网络。覆盖 runNext 全链（改名→升级 payload 断言→COMPLETED）、
 * 队列 WAITING/MANUAL 文案、升级拒绝分支（119/51 重试/806/120/其他）、runUntil 连续执行、
 * levelBuilding 三协议、joinAlliance 三分支、rename 205/51 语义、status runState 文案、
 * createRole 直通与落区校验。
 *
 * <p>文案断言与原版逐字一致（全角标点）。
 */
public class BootstrapServiceTest {

    // ================= fake 装配 =================

    /** 脚本回放端口：按 proto 排队响应；未脚本化的 proto 默认 code=0。 */
    private static class ScriptedPort implements BootstrapService.ProtocolPort {
        final List<Map.Entry<String, Map<String, Object>>> calls = new ArrayList<>();
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();

        @Override
        public Map<String, Object> notify(String proto) {
            return notifies.get(proto);
        }
        private final Map<String, ArrayDeque<Map<String, Object>>> scripted = new LinkedHashMap<>();

        @SafeVarargs
        final void when(String proto, Map<String, Object>... contents) {
            ArrayDeque<Map<String, Object>> q = scripted.computeIfAbsent(proto,
                    ignored -> new ArrayDeque<>());
            q.addAll(Arrays.asList(contents));
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(new AbstractMap.SimpleImmutableEntry<>(proto,
                    args == null ? Map.of() : args));
            GameMessage msg = new GameMessage();
            msg.setProto(proto);
            ArrayDeque<Map<String, Object>> q = scripted.get(proto);
            if (q != null && !q.isEmpty()) {
                msg.setContent(q.poll());
            } else if (BootstrapService.MAIL_COVER_LIST.equals(proto)
                    || BootstrapService.MAIL_BATCH_READ.equals(proto)
                    || BootstrapService.ACHIEVEMENT_INFO.equals(proto)
                    || BootstrapService.ACHIEVEMENT_REWARD.equals(proto)) {
                msg.setContent(new LinkedHashMap<>(Map.of("code", 9003)));
            } else if (BootstrapService.GUIDE_SET_STEP.equals(proto)) {
                msg.setContent(new LinkedHashMap<>(Map.of("code", 9002)));
            } else if (BootstrapService.HERO_SEARCH.equals(proto)
                    || BootstrapService.HERO_UPGRADE.equals(proto)
                    || BootstrapService.HERO_PROMOTE_STAR.equals(proto)
                    || BootstrapService.ADVENTURE_BEGIN.equals(proto)
                    || BootstrapService.ADVENTURE_END.equals(proto)) {
                // 默认让英雄/探险协议失败：既有用例不关心，避免新增环节改变其流程
                msg.setContent(new LinkedHashMap<>(Map.of("code", 9001)));
            } else if (BootstrapService.EXCHANGE_CDKEY.equals(proto)) {
                // 默认把兑换码当「永久失败」：既有用例不关心兑换码，避免新增环节改变其流程
                msg.setContent(new LinkedHashMap<>(Map.of("code", 7201)));
            } else {
                msg.setContent(new LinkedHashMap<>(Map.of("code", 0)));
            }
            return msg;
        }

        int count(String proto) {
            return (int) calls.stream().filter(c -> c.getKey().equals(proto)).count();
        }

        Map<String, Object> argsOf(String proto, int index) {
            int seen = 0;
            for (Map.Entry<String, Map<String, Object>> c : calls) {
                if (c.getKey().equals(proto)) {
                    if (seen++ == index) return c.getValue();
                }
            }
            return Map.of();
        }
    }

    private static final BootstrapService.Sleeper NO_SLEEP = millis -> {
    };

    /** 可控时钟：验证「退避到期后恢复尝试」而不真实睡眠（卡点 A 必测 3）。 */
    private static final class MutableClock implements java.util.function.LongSupplier {
        private long now;

        MutableClock(long start) {
            this.now = start;
        }

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private static Map<String, Object> content(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static long numberOf(Object raw) {
        return com.carlos.vault.wjdr.protocol.ProtocolValueSupport.number(raw);
    }

    /** 熔炉 4 级（318 英雄大厅 1、310 3 级满足 301→5 前置）：验证熔炉 <5 也尝试钻石。 */
    private static Map<String, Object> readyLoadFurnace4() {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 4L, "status", 0L));
        buildings.put("318", content("bid", 318L, "lv", 1L, "status", 0L));
        buildings.put("310", content("bid", 310L, "lv", 3L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        return content("uid", 8001L, "name", "旧名字", "buildings", buildings,
                "queues", new ArrayList<>(), "res", content("103", 100000, "101", 500));
    }

    /** 熔炉 9 级 + 301→10 前置（323 9 级、329 1 级）：验证耗尽语义与 ENDED。 */
    private static Map<String, Object> readyLoadFurnace9(long diamonds) {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 9L, "status", 0L));
        buildings.put("323", content("bid", 323L, "lv", 9L, "status", 0L));
        buildings.put("329", content("bid", 329L, "lv", 1L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        return content("uid", 8001L, "name", "旧名字", "buildings", buildings,
                "furnitures", List.of(
                        content("bid", 302L, "conf_fid", 401L, "lv", 4L),
                        content("bid", 302L, "conf_fid", 402L, "lv", 4L)),
                "queues", new ArrayList<>(), "res", content("103", 100000, "101", diamonds));
    }

    /** 就绪样本：熔炉 1 + 伐木场 1（熔炉 2 级前置已满足）+ 空闲队列 + 资源。 */
    private static Map<String, Object> readyLoad(long furnace, long sawmill) {
        Map<String, Object> b301 = content("bid", 301L, "lv", furnace, "status", 0L);
        Map<String, Object> b302 = content("bid", 302L, "lv", sawmill, "status", 0L);
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", b301);
        buildings.put("302", b302);
        Map<String, Object> load = content("uid", 8001L, "name", "旧名字",
                "buildings", buildings, "queues", new ArrayList<>(),
                "res", content("103", 100000, "101", 500));
        return load;
    }

    private static BootstrapService service(ScriptedPort port, Map<String, Object> load,
                                            BootstrapConfig cfg) {
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP);
        svc.applyConfig(cfg);
        svc.syncLoadData(load);
        return svc;
    }

    /** 注入退避表/作用域（模拟生产按 fpid 共享，单测可换可控时钟）。 */
    private static BootstrapService service(ScriptedPort port, Map<String, Object> load,
                                            BootstrapConfig cfg, BootstrapHoldStore holds, String scope) {
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, holds, scope);
        svc.applyConfig(cfg);
        svc.syncLoadData(load);
        return svc;
    }

    private static BootstrapConfig enabledConfig() {
        return new BootstrapConfig(true, 0L, 2, true, false, false, "", false, "");
    }

    // ================= runNext 全链 =================

    @Test
    public void runNext_fullChain_renameThenUpgradeThenCompleted() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 2, true, false, false,
                "", false, "远征者");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);

        // 第 1 步：改名优先
        Map<String, Object> r1 = svc.runNext();
        assertEquals("STARTED", r1.get("status"));
        assertEquals("已修改角色名称为 远征者", r1.get("message"));
        assertEquals(Map.of("from", "旧名字", "to", "远征者"), r1.get("rename"));
        assertEquals(1, port.count("req_player_modify_name"));
        assertEquals(Map.of("name", "远征者", "use_diamond", false),
                port.argsOf("req_player_modify_name", 0));

        // 第 2 步：升级熔炉（payload 断言：bid/use_diamond/qid）
        Map<String, Object> r2 = svc.runNext();
        assertEquals("STARTED", r2.get("status"));
        assertEquals("已请求升级 大熔炉 到 2 级", r2.get("message"));
        assertEquals(1, port.count("req_upgrade_building"));
        assertEquals(Map.of("bid", 301L, "use_diamond", true, "qid", 1L),
                port.argsOf("req_upgrade_building", 0));
        assertEquals(0L, r2.get("responseCode"));
        assertEquals(2L, svc.state().level(301L));

        // 第 3 步：目标达成
        Map<String, Object> r3 = svc.runNext();
        assertEquals("COMPLETED", r3.get("status"));
        assertEquals("大熔炉已到达目标等级 2", r3.get("message"));
        assertEquals(true, r3.get("levelingCompleted"));
    }

    @Test
    public void runNext_stateNotReady_waiting() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
        assertEquals("等待建筑与队列全量状态同步", r.get("message"));
    }

    @Test
    public void runNext_disabled_throws() {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(1L, 1L),
                BootstrapConfig.defaults());
        try {
            svc.runNext();
            fail("主开关未开启应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("起号模块主开关未开启", e.getMessage());
        } catch (Exception e) {
            fail("应抛 IllegalStateException");
        }
    }

    // ================= 队列 / MANUAL =================

    @Test
    public void runNext_noIdleQueue_waiting() throws Exception {
        // 队列全忙但**没有建筑在施工**（qtype 非建筑队列）→ 秒不了队列 → 等空闲建筑队列。
        // 注意：若队列上有建筑在施工，新增的 finishBusyQueue 会先秒完并返回 STARTED（新版语义）。
        ScriptedPort port = new ScriptedPort();
        List<Object> busyQueues = List.of(
                content("qid", 1L, "status", 0L, "qtype", 2L),
                content("qid", 2L, "status", 0L, "qtype", 2L));
        Map<String, Object> load = content("buildings", content("301", content("bid", 301L, "lv", 1L)),
                "queues", busyQueues);
        BootstrapService svc = service(port, load, enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("实际消息=" + r.get("message") + " calls=" + port.calls, "WAITING", r.get("status"));
        assertEquals("当前没有空闲建筑队列", r.get("message"));
        assertEquals(0, port.count("req_upgrade_building"));
    }

    @Test
    public void runNext_instantDiamondOff_manual() throws Exception {
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 2, false, false, false, "", false, "");
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        Map<String, Object> r = svc.runNext();
        assertEquals("MANUAL", r.get("status"));
        assertEquals("下一步 大熔炉 2 级需要等待完成，请开启钻石即时升级", r.get("message"));
        assertEquals(0, port.count("req_upgrade_building"));
    }

    // ================= 升级被拒分支 =================

    @Test
    public void upgradeRejected_119_expBlocked() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 119L));
        BootstrapService svc = service(port, readyLoad(1L, 1L), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("EXP_BLOCKED", r.get("status"));
        assertEquals("大熔炉 的家具经验不足，已改为先补齐家具再升级建筑", r.get("message"));
        assertEquals(119, r.get("errorCode"));
        assertEquals(301L, r.get("blockedBuildingId"));
    }

    @Test
    public void upgradeRejected_51_retryThenWaiting() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 51L));
        BootstrapService svc = service(port, readyLoad(1L, 1L), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
        assertEquals("资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级", r.get("message"));
        assertEquals(51, r.get("errorCode"));
        // 51 → 补资源后重试一次 + 熔炉 <5 也尝试一次钻石（R-07）= 3 次
        assertEquals(3, port.count("req_upgrade_building"));
        Map<String, Object> preparation = (Map<String, Object>) r.get("preparation");
        Map<String, Object> backpack = (Map<String, Object>) preparation.get("backpack");
        assertEquals(false, backpack.get("ready"));
        assertEquals("背包尚未同步，暂不使用资源包", backpack.get("message"));
    }

    @Test
    public void upgradeRejected_806_resyncRequired() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 806L));
        BootstrapService svc = service(port, readyLoad(1L, 1L), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("RESYNC_REQUIRED", r.get("status"));
        assertEquals("服务端判定前置建筑等级不足，本地建筑等级可能尚未同步，请等待全量同步后重试",
                r.get("message"));
        assertEquals(806, r.get("errorCode"));
        assertEquals(Map.of("buildings", true, "queues", true), r.get("sync"));
    }

    @Test
    public void upgradeRejected_120_missingFurniture() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_create_furniture", content("code", 120L));
        // levelBuilding(302)：首动作 CREATE_FURNITURE 401
        BootstrapService svc = service(port, readyLoad(2L, 1L), enabledConfig());
        Map<String, Object> r = svc.levelBuilding(302L);
        assertEquals("RESYNC_REQUIRED", r.get("status"));
        assertEquals("服务端判定建筑缺少家具，等待家具数据同步后重试", r.get("message"));
        assertEquals(120, r.get("errorCode"));
    }

    @Test
    public void upgradeRejected_otherCode_failed() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 999L));
        BootstrapService svc = service(port, readyLoad(1L, 1L), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("FAILED", r.get("status"));
        assertEquals("升级被服务端拒绝：req_upgrade_building 返回错误码 999", r.get("message"));
        assertEquals(999, r.get("errorCode"));
    }

    // ================= 卡点 A：51 hold 联动 planner（审查 P1-1/P1-3） =================

    /** 熔炉 7 级 + 盾兵营 7 级（8 级前置已满足）：用于验证 51→钻石兜底。 */
    private static Map<String, Object> readyLoadFurnace7() {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 7L, "status", 0L));
        buildings.put("321", content("bid", 321L, "lv", 7L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        return content("uid", 8001L, "name", "旧名字", "buildings", buildings,
                "queues", new ArrayList<>(), "res", content("103", 100000, "101", 500));
    }

    /**
     * 网页版对齐 P0：熔炉 ≥5 时建筑 51 应改用钻石直升
     * （`req_upgrade_building{bid,use_diamond:true,qid:0}`），而不是 30 分钟 hold。
     */
    @Test
    public void upgradeRejected_51_buildingAtFurnace7_usesDiamondFallback() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 首发 51 + 补资源后重试 51 → 钻石兜底成功
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 8, true, false, false,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace7(), cfg);

        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals("资源不足，已改用钻石完成 升级 大熔炉 到 8 级", r.get("message"));
        assertEquals(true, r.get("diamondFallback"));
        assertEquals(3, port.count("req_upgrade_building"));
        assertEquals(Map.of("bid", 301L, "use_diamond", true, "qid", 0L),
                port.argsOf("req_upgrade_building", 2));
        assertEquals(8L, svc.state().level(301L));
    }

    /** 钻石兜底也失败且熔炉 <9：WAITING，不进入 ENDED。 */
    @Test
    public void upgradeRejected_51_buildingDiamondFails_below9_waits() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 51L), content("code", 51L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 8, true, false, false,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace7(), cfg);

        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).startsWith("资源或道具不足"));
        // 首发 + 补偿重试 + 钻石两次（熔炉 ≥5，R-07）
        assertEquals(4, port.count("req_upgrade_building"));
    }

    /**
     * P1-3 回归（需求 R-03）：建筑 51 且钻石兜底失败后，必须写 30s 动作退避 + 30s 购买冷却；
     * 冷却内同一动作不得再按全量成本强制购买资源（否则每轮 5s 烧一次钻石）。
     */
    @Test
    public void upgradeRejected_51_buildingMarksHoldAndBuyCooldown() throws Exception {
        ScriptedPort port = new ScriptedPort();
        for (int i = 0; i < 12; i++) {
            port.when("req_upgrade_building", content("code", 51L));
        }
        port.when("req_shop_info", content("info", content("id", 701L,
                "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        port.when("req_shop_buy", content("code", 0L));
        MutableClock clock = new MutableClock(5_000_000L);
        BootstrapHoldStore holds = new BootstrapHoldStore(clock);
        String scope = "fp-51-cool";
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 8, true, false, true,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace7(), cfg, holds, scope);

        Map<String, Object> first = svc.runNext();
        assertEquals("WAITING", first.get("status"));
        Action held = new Action(ActionKind.UPGRADE_BUILDING, 301L, "大熔炉",
                0L, 0L, 8L, List.of());
        String actionKey = BootstrapService.holdKey(held);
        assertEquals("建筑 51 必须写 30s 动作退避",
                5_000_000L + BootstrapService.ACTION_RETRY_MILLIS,
                holds.deadlineMillis(scope, actionKey));
        assertEquals("建筑 51 必须写 30s 购买冷却",
                5_000_000L + BootstrapService.ACTION_RETRY_MILLIS,
                holds.deadlineMillis(scope, "buy:" + actionKey));
        assertEquals("首轮 51 应补一次资源", 1, port.count("req_shop_buy"));

        // 显式点名升级绕过 planner 的 hold 过滤（HoldPolicy.NONE），专测购买冷却本身
        svc.levelBuilding(301L);
        assertEquals("购买冷却内不得再次购买", 1, port.count("req_shop_buy"));
        assertEquals("购买冷却内不得再次查询商店", 1, port.count("req_shop_info"));
    }

    /** 熔炉 2 级 + 民居1 1 级：目标熔炉 3 的首个动作是「民居1 家具 101 建造」。 */
    private static Map<String, Object> readyLoadFurnace3() {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 2L, "status", 0L));
        buildings.put("308", content("bid", 308L, "lv", 1L, "status", 0L));
        return content("uid", 8001L, "name", "旧名字", "buildings", buildings,
                "queues", new ArrayList<>(), "res", content("103", 100000, "101", 500));
    }

    private static BootstrapConfig furnace3Config() {
        return new BootstrapConfig(true, 0L, 3, true, false, false, "", false, "");
    }

    /** 必测 1：首个动作被 51 hold 后，下一轮必须跳过它、执行 plan 里独立可执行的后续动作。 */
    @Test
    public void runNext_heldAction_skipsToNextAction() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 家具 101 建造连续两次 51 → 30 分钟 hold；其升级依赖它，必须跳过整条 101 链；
        // 家具 103 与 101 相互独立 → 下一轮应执行 103 建造。
        port.when("req_create_furniture", content("code", 51L), content("code", 51L),
                content("code", 0L));
        BootstrapService svc = service(port, readyLoadFurnace3(), furnace3Config());

        Map<String, Object> first = svc.runNext();
        assertEquals("WAITING", first.get("status"));
        assertTrue(String.valueOf(first.get("message")).contains("资源或道具不足"));
        assertEquals(2, port.count("req_create_furniture"));

        Map<String, Object> second = svc.runNext();
        assertEquals("STARTED", second.get("status"));
        assertEquals("已请求民居1 家具 103 建造", second.get("message"));
        int last = port.count("req_create_furniture") - 1;
        assertEquals("跳过被 hold 的家具 101，执行独立的家具 103",
                Map.of("bid", 308L, "conf_fid", 103L),
                port.argsOf("req_create_furniture", last));
    }

    /** 必测 2：plan 内所有可行动作都在 hold → 必须 WAITING（含剩余秒数），绝不能误报 COMPLETED。 */
    @Test
    public void runNext_allActionsHeld_waitingNotCompleted() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 51L));
        MutableClock clock = new MutableClock(1_000_000L);
        BootstrapHoldStore holds = new BootstrapHoldStore(clock);
        BootstrapService svc = service(port, readyLoad(1L, 1L), enabledConfig(),
                holds, "fp-all-hold");

        Map<String, Object> first = svc.runNext();   // 唯一动作「熔炉 1→2」被 51 hold
        assertEquals("WAITING", first.get("status"));

        Map<String, Object> second = svc.runNext();
        // plan.complete() 的历史语义是「无阻塞」，不能拿它判完成
        assertEquals("WAITING", second.get("status"));
        assertFalse("全 hold 不得误报 COMPLETED", "COMPLETED".equals(second.get("status")));
        assertEquals("动作退避中，剩余 30 秒", second.get("message"));
        assertEquals(30L, second.get("holdRemainingSeconds"));
        // 被跳过后不再发升级请求（仍为 51 首发 + 补偿重试 + 一次钻石 = 3 次）
        assertEquals(3, port.count("req_upgrade_building"));
    }

    /** 必测 3：被跳过的动作不得刷新 hold；到期后必须恢复尝试。 */
    @Test
    public void runNext_heldAction_notRenewedAndRecoversAfterExpiry() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_create_furniture", content("code", 51L), content("code", 51L),
                content("code", 0L), content("code", 0L));
        MutableClock clock = new MutableClock(2_000_000L);
        BootstrapHoldStore holds = new BootstrapHoldStore(clock);
        String scope = "fp-renew";
        BootstrapService svc = service(port, readyLoadFurnace3(), furnace3Config(), holds, scope);

        svc.runNext();   // 家具 101 建造被 51 → 写入 30 分钟 hold
        Action held = new Action(ActionKind.CREATE_FURNITURE, 308L, "民居1",
                101L, 0L, 1L, List.of());
        String key = BootstrapService.holdKey(held);
        long deadline = holds.deadlineMillis(scope, key);
        assertEquals(2_000_000L + BootstrapService.ACTION_RETRY_MILLIS, deadline);

        clock.advance(10_000L);
        Map<String, Object> skipped = svc.runNext();
        assertEquals("已请求民居1 家具 103 建造", skipped.get("message"));
        assertEquals("被跳过的动作不得续期 hold", deadline, holds.deadlineMillis(scope, key));

        clock.advance(1_790_001L);   // 越过到期点
        Map<String, Object> resumed = svc.runNext();
        assertEquals("到期后恢复尝试被 hold 的动作", "已请求民居1 家具 101 建造",
                resumed.get("message"));
    }

    // ================= runUntil =================

    @Test
    public void runUntil_twoStartedThenWaitingOn51() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 第 1、2 次升级成功，第 3 次（熔炉 1→2）51：首发+重试+一次钻石
        port.when("req_upgrade_building", content("code", 0L), content("code", 0L),
                content("code", 51L), content("code", 51L), content("code", 51L));
        BootstrapService svc = service(port, readyLoad(0L, 0L), enabledConfig());
        Map<String, Object> summary = svc.runUntil(5);
        assertEquals(2, summary.get("started"));
        // 前 2 步各 1 次 + 第 3 步 51 首发/重试/钻石各 1 次 = 5 次
        assertEquals(5, port.count("req_upgrade_building"));
        assertEquals("已连续执行 2 步；资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级",
                summary.get("message"));
        List<?> results = (List<?>) summary.get("results");
        assertEquals(3, results.size());
    }

    @Test
    public void runUntil_stepLimitReached() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(0L, 0L), enabledConfig());
        Map<String, Object> summary = svc.runUntil(2);
        assertEquals(2, summary.get("started"));
        assertEquals("已连续执行 2 步；本轮已达到单次执行上限 2 步，可再次点击继续",
                summary.get("message"));
    }

    @Test
    public void runUntil_completedAtTarget() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(2L, 1L), enabledConfig());
        Map<String, Object> summary = svc.runUntil(5);
        assertEquals(0, summary.get("started"));
        assertEquals("大熔炉已到达目标等级 2", summary.get("message"));
    }

    @Test
    public void runUntil_firstStepManual_zeroStarted() throws Exception {
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 1, false, false, false, "", false, "");
        BootstrapService svc = service(new ScriptedPort(), readyLoad(0L, 0L), cfg);
        Map<String, Object> summary = svc.runUntil(5);
        assertEquals(0, summary.get("started"));
        assertEquals("下一步 大熔炉 1 级需要等待完成，请开启钻石即时升级", summary.get("message"));
    }

    // ================= levelBuilding =================

    @Test
    public void levelBuilding_sawmill_createFurnitureFirst() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = service(port, readyLoad(2L, 1L), enabledConfig());
        Map<String, Object> r = svc.levelBuilding(302L);
        assertEquals("STARTED", r.get("status"));
        assertEquals("已请求伐木场 家具 401 建造", r.get("message"));
        assertEquals(1, port.count("req_create_furniture"));
        assertEquals(Map.of("bid", 302L, "conf_fid", 401L),
                port.argsOf("req_create_furniture", 0));
        assertEquals(Long.valueOf(1L), svc.state().furnitureLevels().get(302L).get(401L));
    }

    @Test
    public void levelBuilding_sawmill_thenUpgradeFurniture() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = service(port, readyLoad(2L, 1L), enabledConfig());
        svc.levelBuilding(302L);   // CREATE 401 → 1 级
        Map<String, Object> r = svc.levelBuilding(302L);   // UPGRADE 401 1→2
        assertEquals("STARTED", r.get("status"));
        assertEquals("已请求伐木场 家具 401 升级到 2 级", r.get("message"));
        assertEquals(1, port.count("req_upgrade_furniture"));
        assertEquals(Map.of("bid", 302L, "conf_fid", 401L, "add_lv", 1L),
                port.argsOf("req_upgrade_furniture", 0));
    }

    @Test
    public void levelBuilding_unknownBuilding_throws() {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(1L, 1L), enabledConfig());
        try {
            svc.levelBuilding(999L);
            fail("不支持的建筑应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("不支持的建筑编号: 999", e.getMessage());
        } catch (Exception e) {
            fail("应抛 IllegalArgumentException");
        }
    }

    @Test
    public void levelBuilding_maxedOut_completed() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(80L, 1L), enabledConfig());
        Map<String, Object> r = svc.levelBuilding(301L);
        assertEquals("COMPLETED", r.get("status"));
        assertEquals("该建筑已满级或不可升级", r.get("message"));
    }

    @Test
    public void levelBuilding_notReady_waiting() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), enabledConfig());
        Map<String, Object> r = svc.levelBuilding(301L);
        assertEquals("WAITING", r.get("status"));
        assertEquals("等待建筑与队列全量状态同步", r.get("message"));
    }

    // ================= joinAlliance / resolveAlliance =================

    private static final Map<String, Object> ALLIANCE_PUBLIC = content(
            "aid", 7L, "name", "战盟", "abbr", "ZM", "public", 1L,
            "count", 50L, "member_max", 100L, "lv", 5L,
            "condition", content("level", 10L, "power", 1000L));

    @Test
    public void joinAlliance_publicUnique() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_alliance_search_by_key",
                content("code", 0L, "alliances", List.of(ALLIANCE_PUBLIC)),
                content("code", 0L, "alliances", List.of(ALLIANCE_PUBLIC)));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 9, true, false, false,
                "战盟", false, "");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        Map<String, Object> r = svc.joinAlliance();
        assertEquals("JOINED", r.get("status"));
        assertEquals("已提交加入联盟请求", r.get("message"));
        assertEquals(7L, r.get("allianceId"));
        assertEquals("req_alliance_join", r.get("protocol"));
        assertEquals(false, r.get("demandApply"));
        assertEquals(Map.of("aid", 7L), port.argsOf("req_alliance_join", 0));
        // 搜索结果视图
        Map<String, Object> resolved = svc.resolveAlliance("战盟");
        assertEquals(true, resolved.get("unique"));
        assertEquals(1, resolved.get("count"));
        assertEquals(7L, resolved.get("allianceId"));
    }

    @Test
    public void joinAlliance_privateWithAutoApply() throws Exception {
        ScriptedPort port = new ScriptedPort();
        Map<String, Object> alliance = content("aid", 8L, "name", "夜幕", "public", 0L);
        port.when("req_alliance_search_by_key", content("code", 0L, "alliances", List.of(alliance)));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 9, true, false, false,
                "夜幕", true, "");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        Map<String, Object> r = svc.joinAlliance();
        assertEquals("JOINED", r.get("status"));
        assertEquals("已提交联盟申请", r.get("message"));
        assertEquals("req_alliance_apply_join", r.get("protocol"));
        assertEquals(true, r.get("demandApply"));
    }

    @Test
    public void joinAlliance_privateWithoutAutoApply_throws() throws Exception {
        ScriptedPort port = new ScriptedPort();
        Map<String, Object> alliance = content("aid", 8L, "name", "夜幕", "public", 0L);
        port.when("req_alliance_search_by_key", content("code", 0L, "alliances", List.of(alliance)));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 9, true, false, false,
                "夜幕", false, "");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        try {
            svc.joinAlliance();
            fail("非公开招募且未开自动申请应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("目标联盟非公开招募，请开启自动申请后再执行", e.getMessage());
        }
    }

    @Test
    public void joinAlliance_notUnique_throws() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // name=战盟 与 abbr=战盟 两条同时命中 → 非唯一
        port.when("req_alliance_search_by_key", content("code", 0L, "alliances",
                List.of(content("aid", 7L, "name", "战盟"),
                        content("aid", 9L, "abbr", "战盟"))));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 9, true, false, false,
                "战盟", true, "");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        try {
            svc.joinAlliance();
            fail("非唯一匹配应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("联盟名称或简称未匹配到唯一联盟，请检查配置", e.getMessage());
        }
    }

    @Test
    public void resolveAlliance_keywordValidation() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(1L, 1L), enabledConfig());
        try {
            svc.resolveAlliance("  ");
            fail("空关键字应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("请填写联盟名称或简称", e.getMessage());
        }
        try {
            svc.resolveAlliance("一二三四五六七八九十一二三四五六七");   // 17 字
            fail("超长关键字应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("联盟名称或简称最长 16 个字符", e.getMessage());
        }
    }

    @Test
    public void joinAlliance_noConfiguredName_throws() {
        BootstrapService svc = service(new ScriptedPort(), readyLoad(1L, 1L), enabledConfig());
        try {
            svc.joinAlliance();
            fail("未配置联盟应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("尚未配置联盟名称或简称", e.getMessage());
        } catch (Exception e) {
            fail("应抛 IllegalStateException");
        }
    }

    // ================= rename 205 / 51 =================

    @Test
    public void rename_205_treatedAsSuccess_noRepeat() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_player_modify_name", content("code", 205L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 2, true, false, false,
                "", false, "新甲");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        // 205 → 视为成功（缓存），继续执行升级主线
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals("已请求升级 大熔炉 到 2 级", r.get("message"));
        assertEquals(1, port.count("req_player_modify_name"));
        // 第二步：改名缓存命中，不再请求
        svc.runNext();
        assertEquals(1, port.count("req_player_modify_name"));
    }

    @Test
    public void rename_51_retryWithDiamond() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_player_modify_name", content("code", 51L), content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 2, true, false, false,
                "", false, "新乙");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        // 51 → 钻石重试成功 → 与原版一致：落到错误记录分支返回 empty，主线继续升级
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals("已请求升级 大熔炉 到 2 级", r.get("message"));
        assertEquals(2, port.count("req_player_modify_name"));
        assertEquals(Map.of("name", "新乙", "use_diamond", true),
                port.argsOf("req_player_modify_name", 1));
    }

    @Test
    public void rename_51_retryFails_recordsErrorAndContinues() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_player_modify_name", content("code", 51L), content("code", 51L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 2, true, false, false,
                "", false, "新丙");
        BootstrapService svc = service(port, readyLoad(1L, 1L), cfg);
        Map<String, Object> r = svc.runNext();
        // 改名失败不阻塞主线：继续升级
        assertEquals("STARTED", r.get("status"));
        assertEquals(2, port.count("req_player_modify_name"));
    }

    // ================= status =================

    @Test
    public void status_runStates_andViews() {
        // DISABLED
        BootstrapService disabled = service(new ScriptedPort(), readyLoad(1L, 1L),
                BootstrapConfig.defaults());
        Map<String, Object> s0 = disabled.status();
        assertEquals("DISABLED", s0.get("runState"));
        assertEquals("主开关未开启，起号流程不会执行", s0.get("message"));

        // WAITING_SYNC
        BootstrapService waiting = service(new ScriptedPort(), Map.of(), enabledConfig());
        Map<String, Object> s1 = waiting.status();
        assertEquals("WAITING_SYNC", s1.get("runState"));
        assertEquals("等待建筑与队列全量状态同步", s1.get("message"));

        // READY
        BootstrapService ready = service(new ScriptedPort(),
                readyLoad(2L, 1L),
                new BootstrapConfig(true, 0L, 9, true, false, false, "", false, ""));
        Map<String, Object> s2 = ready.status();
        assertEquals("READY", s2.get("runState"));
        assertEquals("可以执行下一步", s2.get("message"));
        assertEquals(8001L, s2.get("roleUid"));
        assertEquals("旧名字", s2.get("roleName"));
        assertEquals(2L, s2.get("furnaceLevel"));
        assertEquals(9, s2.get("targetFurnaceLevel"));
        assertEquals(false, s2.get("completed"));
        assertEquals(Map.of("wood", 100000L, "coal", 0L, "iron", 0L, "diamond", 500L),
                s2.get("resources"));
        List<?> buildings = (List<?>) s2.get("buildings");
        // 301（熔炉族恒显）+ 302（已建）+ 318（英雄大厅特殊追加）
        assertEquals(3, buildings.size());
        assertEquals(Map.of("id", 301L, "name", "大熔炉", "level", 2L, "busy", false),
                buildings.get(0));
        assertEquals(Map.of("id", 302L, "name", "伐木场", "level", 1L, "busy", false),
                buildings.get(1));
        assertTrue(((List<?>) s2.get("remainingSteps")).size() > 0);

        // LEVEL_COMPLETED
        BootstrapService done = service(new ScriptedPort(), readyLoad(9L, 1L), enabledConfig());
        Map<String, Object> s3 = done.status();
        assertEquals("LEVEL_COMPLETED", s3.get("runState"));
        assertEquals("大熔炉已到达目标等级", s3.get("message"));
        assertEquals(true, s3.get("completed"));
    }

    /**
     * R-05 语义：同一份增量经引擎累积通道只能被消费一次（消费即清空），跨轮不会重复扣减。
     * 原「字符串签名去重」由累积通道的消费语义替代（审查 P2-2：同内容两次真实增量不再被吞）。
     */
    @Test
    public void syncLoadData_resourceUpdateConsumedOnce() {
        WjdrEngine engine = new WjdrEngine(null);
        ScriptedPort port = new ScriptedPort() {
            @Override
            public Map<String, Object> consumeResourceUpdate() {
                return engine.consumeResourceUpdate();
            }
        };
        BootstrapService svc = service(port, content("res", content("103", 1000L)),
                enabledConfig());
        engine.accumulateResourceUpdate(content("data",
                List.of(content("id", 103L, "count", -900L))));
        svc.syncLoadData(Map.of());
        assertEquals(100L, svc.state().resourceCount(103L));
        svc.syncLoadData(Map.of());
        assertEquals("消费即清空：第二轮不再重复扣减", 100L, svc.state().resourceCount(103L));
    }

    /**
     * P1-1 回归（需求 R-01）：本轮没有 res 快照时，res_update 必须叠加在旧资源上，
     * 而不是从 0 起算（当前实现会得到 -900）。
     */
    @Test
    public void syncLoadData_deltaWithoutResSnapshot_mergesLegacy() {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), enabledConfig());
        svc.syncLoadData(content("res", content("103", 1000L)));
        assertEquals(1000L, svc.state().resourceCount(103L));
        svc.syncLoadData(content("res_update", List.of(content("id", 103L, "count", -900L))));
        assertEquals("旧值 1000 + 增量 -900", 100L, svc.state().resourceCount(103L));
    }

    // ================= C2：R-05 资源增量累积通道 =================

    /** R-05 验收：引擎累积两次相同增量，一次 syncLoadData 后资源减少 1800（而非 900）。 */
    @Test
    public void syncLoadData_consumesAccumulatedEngineChannel() {
        WjdrEngine engine = new WjdrEngine(null);
        ScriptedPort port = new ScriptedPort() {
            @Override
            public Map<String, Object> consumeResourceUpdate() {
                return engine.consumeResourceUpdate();
            }
        };
        BootstrapService svc = service(port, content("res", content("103", 2000L)),
                enabledConfig());
        // 构造（含首次 syncLoadData）之后再模拟两次推送
        engine.accumulateResourceUpdate(content("data",
                List.of(content("id", 103L, "count", -900L))));
        engine.accumulateResourceUpdate(content("data",
                List.of(content("id", 103L, "count", -900L))));
        svc.syncLoadData(Map.of());
        assertEquals("2000 + (-900) + (-900)", 200L, svc.state().resourceCount(103L));
        // 消费即清空：再次同步不得重复扣减
        svc.syncLoadData(Map.of());
        assertEquals(200L, svc.state().resourceCount(103L));
    }

    /** R-05 边界：无推送（引擎通道返回 null）时回退 loadContent.res_update。 */
    @Test
    public void syncLoadData_fallsBackToContentWhenChannelMissing() {
        BootstrapService svc = service(new ScriptedPort(), content("res", content("103", 1000L)),
                enabledConfig());
        svc.syncLoadData(content("res_update", List.of(content("id", 103L, "count", -300L))));
        assertEquals(700L, svc.state().resourceCount(103L));
    }

    // ================= C2：R-07 资源耗尽语义 =================

    /** R-07 验收 4：coverable/unsupplied 口径——煤/铁缺口不可覆盖。 */
    @Test
    public void buyMissingResources_reportsCoverableAndUnsupplied() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9001L, "res", content("101", 500L)));
        port.when("req_shop_info", content("info", content("id", 701L,
                "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        port.when("req_shop_buy", content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());

        Map<String, Object> woodOnly = svc.buyMissingResources(
                List.of(new BuildingGateCatalog.Cost(103L, 25000L)));
        assertEquals("只有木材且钻石够买：可覆盖", Boolean.TRUE, woodOnly.get("coverable"));
        assertEquals(0, ((List<?>) woodOnly.get("unsupplied")).size());

        Map<String, Object> withCoal = svc.buyMissingResources(List.of(
                new BuildingGateCatalog.Cost(103L, 25000L),
                new BuildingGateCatalog.Cost(104L, 100L)));
        assertEquals("煤不在商店：不可覆盖", Boolean.FALSE, withCoal.get("coverable"));
        List<?> unsupplied = (List<?>) withCoal.get("unsupplied");
        assertTrue("unsupplied 应含煤矿缺口", unsupplied.stream().anyMatch(u ->
                u instanceof Map && numberOf(((Map<?, ?>) u).get("resourceId")) == 104L));
    }

    /** R-07 验收 3：熔炉 <5 的建筑 51 也先尝试一次钻石升级（不再要求 >=5）。 */
    @Test
    public void upgradeRejected_51_furnace4_triesDiamondOnce() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 首发 51 → 补资源后重试 51 → 钻石兜底成功
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 5, true, false, false,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace4(), cfg);

        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals(true, r.get("diamondFallback"));
        assertEquals(3, port.count("req_upgrade_building"));
        assertEquals("钻石升级载荷", Map.of("bid", 301L, "use_diamond", true, "qid", 0L),
                port.argsOf("req_upgrade_building", 2));
    }

    /** R-07 验收 2：coverable=true 时耗尽计数不增（三次 51 仍 WAITING，不 ENDED）。 */
    @Test
    public void upgradeRejected_51_coverable_trueDoesNotAccumulate() throws Exception {
        ScriptedPort port = new ScriptedPort();
        for (int i = 0; i < 12; i++) {
            port.when("req_upgrade_building", content("code", 51L));
        }
        port.when("req_shop_info", content("info", content("id", 701L,
                "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        port.when("req_shop_buy", content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 9, true, false, true,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace9(500L), cfg);

        for (int i = 0; i < 3; i++) {
            Map<String, Object> r = svc.levelBuilding(302L);
            assertEquals("第 " + (i + 1) + " 次 51 仍应 WAITING", "WAITING", r.get("status"));
        }
        assertNotEquals("可覆盖时不得 ENDED", "ENDED",
                svc.status().get("runState"));
    }

    /** R-07/R-06 验收 1：coverable=false（煤/铁缺口）连续 3 次 → ENDED 终态。 */
    @Test
    public void upgradeRejected_51_coverableFalse_threeTimes_ended() throws Exception {
        ScriptedPort port = new ScriptedPort();
        for (int i = 0; i < 12; i++) {
            port.when("req_upgrade_building", content("code", 51L));
        }
        port.when("req_shop_info", content("info", content("id", 701L,
                "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        port.when("req_shop_buy", content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 10, true, false, true,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace9(0L), cfg);

        Map<String, Object> first = svc.runNext();
        assertEquals("WAITING", first.get("status"));
        Map<String, Object> second = svc.runNext();
        assertEquals("WAITING", second.get("status"));
        Map<String, Object> third = svc.runNext();
        assertEquals("ENDED", third.get("status"));
        assertEquals(true, third.get("resourceEnded"));

        Map<String, Object> status = svc.status();
        assertEquals("ENDED", status.get("runState"));
        assertEquals(true, status.get("resourceEnded"));
        assertTrue("ENDED 文案应说明资源耗尽：" + status.get("message"),
                String.valueOf(status.get("message")).contains("资源"));

        Map<String, Object> endedAgain = svc.runNext();
        assertEquals("ENDED 为终态，不可再推进", "ENDED", endedAgain.get("status"));
    }

    /** R-07 验收 5：resourceExhaustedAttempts 只在钻石直升成功时清零，普通动作成功不清零。 */
    @Test
    public void successfulAction_doesNotResetExhaustedAttempts() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, new BootstrapHoldStore(), "fp-attempts", store);
        svc.applyConfig(enabledConfig());
        svc.syncLoadData(readyLoad(1L, 1L));
        store.holder("fp-attempts").incrementResourceExhaustedAttempts();
        store.holder("fp-attempts").incrementResourceExhaustedAttempts();

        Map<String, Object> ok = svc.runNext();
        assertEquals("STARTED", ok.get("status"));
        assertEquals("普通动作成功不得清零耗尽计数", 2,
                store.holder("fp-attempts").resourceExhaustedAttempts());
    }

    @Test
    public void config_fromMap_clampAndNormalize() {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), BootstrapConfig.defaults());
        svc.applyConfig(Map.of("enabled", "true", "targetFurnaceLevel", 15, "roleName",
                "一二三四五六七八九十一二三四五六七", "instantDiamond", "0"));
        BootstrapConfig cfg = svc.config();
        assertTrue(cfg.enabled());
        assertEquals(9, cfg.targetFurnaceLevel());   // 15 → clamp 9
        assertEquals(16, cfg.roleName().length());   // 17 字 → 截 16
        assertFalse(cfg.instantDiamond());           // "0" → false
        svc.applyConfig(Map.of("targetFurnaceLevel", 0));
        assertEquals(1, svc.config().targetFurnaceLevel());   // 0 → clamp 1
        assertEquals(BootstrapConfig.defaults().toMap().keySet(),
                svc.config().toMap().keySet());
    }

    // ================= createRole =================

    /** login 直返脚本引擎（不触网络）。 */
    private static WjdrEngine scriptedEngine(long uid, long serverId) {
        return new WjdrEngine(null) {
            @Override
            public LoginResult login(String fpid, String sdkToken, Long serverid,
                                     Map<String, Object> extraParams) {
                LoginResult r = new LoginResult();
                r.uid = uid;
                r.serverId = serverId;
                r.authContent = new LinkedHashMap<>(extraParams);
                return r;
            }
        };
    }

    @Test
    public void createRole_requiresTargetServer() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), enabledConfig());
        try {
            svc.createRole(null, "fpid", "token", 0L, "名字");
            fail("未选区服应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("请选择目标区服", e.getMessage());
        }
    }

    @Test
    public void createRole_success_passesIsCreateAndName() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), enabledConfig());
        WjdrEngine engine = new WjdrEngine(null) {
            @Override
            public LoginResult login(String fpid, String sdkToken, Long serverid,
                                     Map<String, Object> extraParams) {
                LoginResult r = new LoginResult();
                r.uid = 777L;
                r.serverId = 9L;
                r.authContent = new LinkedHashMap<>(extraParams);
                return r;
            }
        };
        LoginOutcome outcome = svc.createRole(engine, "fpid", "token", 9L, "远征甲");
        assertEquals(777L, outcome.uid());
        assertEquals(9L, outcome.serverId());
        assertEquals(false, outcome.serverMismatch());
        assertEquals("已在该区服创建新角色并登录", outcome.message());
        // 建号载荷：is_create=true + name（extraParams 并入 loginauth）
        assertEquals(true, outcome.authContent().get("is_create"));
        assertEquals("远征甲", outcome.authContent().get("name"));
    }

    @Test
    public void createRole_serverMismatch_throwsWithOriginalMessage() throws Exception {
        BootstrapService svc = service(new ScriptedPort(), Map.of(), enabledConfig());
        WjdrEngine engine = scriptedEngine(200L, 3L);
        try {
            svc.createRole(engine, "fpid", "token", 9L, 100L, "");
            fail("落地角色与期望不一致应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("游戏账号登录到了其他角色：期望 uid=100（区服 9），实际 uid=200（区服 3）"
                    + "，已中止登录。请检查该角色的区服资料后重试", e.getMessage());
        }
    }

    // ================= M4b：情报（熔炉 ≥7 挂点） =================

    /** 情报推送端口：notify 键可编程（collectNotifies 跳时，notify 返回脚本表）。 */
    private static final class IntelPort extends ScriptedPort {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        /** collectNotifies 调用次数（R-04：world 完成后必须为 0 增量）。 */
        int collectCalls;

        IntelPort() {
            this(true);
        }

        /**
         * @param seedWorld 是否预置 world 成功响应（kingdom info 带 kid + fog）。
         *                  R-08 失败路径用 {@code false} 自行脚本化失败响应。
         */
        IntelPort(boolean seedWorld) {
            if (seedWorld) {
                when("req_world_kingdom_info", content("code", 0L, "kid", 77L));
                when("req_world_map_fog", content("code", 0L));
            }
        }

        @Override
        public Map<String, Object> notify(String proto) {
            return notifies.get(proto);
        }

        @Override
        public void collectNotifies(long millis) {
            // 跳时：测试不等待推送窗口（只计数）
            collectCalls++;
        }
    }

    /** 情报事件条目（event_id=3001 英雄之旅区间，形状对齐 notify_intelligence_info）。 */
    private static Map<String, Object> intelEvent(long id, long status) {
        return intelEvent(id, status, 0L);
    }

    /** 情报事件（type 字段决定是否支持快速战斗：新版只支持 type==3）。 */
    private static Map<String, Object> intelEvent(long id, long status, long type) {
        return content("id", id, "status", status, "type", type, "event_id", 3001L, "expire_ts", 0L);
    }

    /** 熔炉 7（情报门槛）+ 318 英雄大厅 + 空闲队列；目标等级 7（情报直通优先于完成态）。 */
    private static Map<String, Object> intelReadyLoad(long hallLevel) {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 7L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        buildings.put("318", content("bid", 318L, "lv", hallLevel, "status", 0L));
        return content("uid", 8001L, "name", "旧名字", "buildings", buildings,
                "queues", new ArrayList<>(), "res", content("103", 100000, "101", 500));
    }

    private static BootstrapConfig intelConfig() {
        return new BootstrapConfig(true, 0L, 7, true, false, false, "", false, "");
    }

    /** 熔炉 ≥7 时世界地图先于情报：测试先推进 world 两件套（kingdom info + fog）。 */
    private static void advanceWorldFirst(BootstrapService svc) throws Exception {
        Map<String, Object> info = svc.runNext();
        assertEquals("STARTED", info.get("status"));
        assertTrue("应先推进世界地图：" + info.get("message"),
                String.valueOf(info.get("message")).contains("世界地图"));
        Map<String, Object> fog = svc.runNext();
        assertEquals("STARTED", fog.get("status"));
        assertTrue("第二步应发送 fog：" + fog.get("message"),
                String.valueOf(fog.get("message")).contains("世界地图"));
    }

    /** 熔炉 ≥7 的 finishPass：背包钻石收尾一次，之后不再重复。 */
    @Test
    public void finishPass_atFurnace7_runsOnce() throws Exception {
        ScriptedPort port = new ScriptedPort();
        Map<String, Object> load = new LinkedHashMap<>(readyLoad(7L, 1L));
        load.put("bag", content("100001", 2L));   // 钻石道具
        BootstrapService svc = service(port, load,
                new BootstrapConfig(true, 0L, 7, true, false, false, "", false, ""));

        Map<String, Object> first = svc.runNext();
        assertEquals("STARTED", first.get("status"));
        assertTrue("应执行 ≥7 收尾：" + first.get("message"),
                String.valueOf(first.get("message")).contains("收尾完成"));
        assertEquals(1, first.get("backpackDiamonds"));

        Map<String, Object> second = svc.runNext();
        assertFalse("收尾只做一次，不重复：" + second,
                String.valueOf(second.get("message")).contains("收尾完成"));
    }

    @Test
    public void intel_claimRewards_atFurnace7() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("11", intelEvent(11L, 2L), "12", intelEvent(12L, 2L))));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        advanceWorldFirst(svc);
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals("已领取 2 个情报任务奖励", r.get("message"));
        assertEquals(List.of(11L, 12L), r.get("intelligenceClaimed"));
        assertEquals(2, port.count("req_intelligence_receive"));
        assertEquals(Map.of("id", 11L), port.argsOf("req_intelligence_receive", 0));
        // 情报先行：本轮不发生建筑动作
        assertEquals(0, port.count("req_upgrade_building"));
    }

    @Test
    public void intel_claim2501Skipped_fallsThrough() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("12", intelEvent(12L, 2L))));
        port.when("req_intelligence_receive", content("code", 2501L));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        advanceWorldFirst(svc);
        Map<String, Object> r = svc.runNext();
        // 2501 = 已领过：跳过不计数 → claimed 空 → 无 status=1 → 情报模块放行 → 主线完成态
        assertEquals(1, port.count("req_intelligence_receive"));
        assertEquals("COMPLETED", r.get("status"));
        assertEquals("大熔炉已到达目标等级 7", r.get("message"));
    }

    @Test
    public void intel_claimRejected_breaksLoop() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("21", intelEvent(21L, 2L), "22", intelEvent(22L, 2L))));
        port.when("req_intelligence_receive", content("code", 500L));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        advanceWorldFirst(svc);
        Map<String, Object> r = svc.runNext();
        // 第一个领取被拒（非 2501）→ 终止本轮领取 → 无 status=1 → 放行主线
        assertEquals(1, port.count("req_intelligence_receive"));
        assertEquals("COMPLETED", r.get("status"));
    }

    @Test
    public void intel_fightIdle_hallReady() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("13", intelEvent(13L, 1L, 3L))));
        // 新版：只有 type==3 支持快速战斗，且需要真实队伍（topFiveHeroes）
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>(intelReadyLoad(1L));
        load.put("hero_list", List.of(content("id", 50006L, "lev", 10L)));
        BootstrapService svc = service(port, load, intelConfig());
        advanceWorldFirst(svc);
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals("已快速完成 1 个英雄之旅情报（客户端默认小队）", r.get("message"));
        assertEquals(13L, r.get("intelligenceFought"));
        assertEquals(Map.of("id", 13L, "fight_heros_data",
                        Map.of("source", 1L, "fight_heros", List.of(50006L, 0L, 0L, 0L, 0L))),
                port.argsOf("req_intelligence_start_battle", 0));
        assertEquals(Map.of("id", 13L), port.argsOf("req_intelligence_end_battle", 0));
        assertEquals(0, port.count("req_upgrade_building"));
    }

    @Test
    public void intel_fightIdle_hallNotBuilt_skipped() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("13", intelEvent(13L, 1L))));
        // 英雄大厅 318 未建（lv=0）→ 小队未就绪 → 不开战，放行主线
        BootstrapService svc = service(port, intelReadyLoad(0L), intelConfig());
        advanceWorldFirst(svc);
        Map<String, Object> r = svc.runNext();
        assertEquals(0, port.count("req_intelligence_start_battle"));
        assertEquals("COMPLETED", r.get("status"));
    }

    @Test
    public void intel_noPushNotify_skippedEntirely() throws Exception {
        // 无推送（notify 全 null）→ 快照未初始化 → 情报模块整体降级跳过（不阻塞主线）
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals(0, port.count("req_intelligence_receive"));
        assertEquals(0, port.count("req_intelligence_start_battle"));
        assertEquals("COMPLETED", r.get("status"));
    }

    @Test
    public void intel_belowFurnace7_notTriggered() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("11", intelEvent(11L, 2L))));
        // 熔炉 2 < 7：情报挂点不启用（尽管推送有可领事件，领取/开战均不发生）；
        // 主线照常规划执行——301 升 3 级前置 308:2 → 首个动作「建造 民居1 到 1 级」（STARTED），
        // 对齐原版 runNextStep：有动作即执行首个动作，非 COMPLETED/WAITING。
        BootstrapService svc = service(port, readyLoad(2L, 1L), intelConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals(0, port.count("req_intelligence_receive"));
        assertEquals(0, port.count("req_intelligence_start_battle"));
        assertEquals("STARTED", r.get("status"));
        assertEquals("已请求建造 民居1 到 1 级", r.get("message"));
    }

    @Test
    public void intel_eventsDelRemovesEntry() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("11", intelEvent(11L, 2L))));
        port.notifies.put(BootstrapService.NOTIFY_INTEL_DEL,
                content("ids", List.of(11L)));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        advanceWorldFirst(svc);
        Map<String, Object> r = svc.runNext();
        assertEquals(0, port.count("req_intelligence_receive"));
        assertEquals("COMPLETED", r.get("status"));
    }

    // ================= M4b：背包资源包 usePacks（51 重试链路三态） =================

    /** 熔炉 1 + 伐木场 1 + 空闲队列 + 指定背包物品。 */
    private static Map<String, Object> packLoad(Map<String, Object> bag) {
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 1L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        return content("uid", 8001L, "name", "旧名字", "buildings", buildings,
                "queues", new ArrayList<>(),
                "res", content("103", 0, "101", 500), "bag", bag);
    }

    @Test
    public void usePacks_enough_opensPacksThenUpgrades() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 0L));
        // 熔炉 2 级需木材 180；背包 2 个「100 木材」包（100021×2）→ 全开（200）后重试成功
        BootstrapService svc = service(port, packLoad(content("100021", 2L)), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals(1, port.count("req_use_item"));
        assertEquals(Map.of("id", 100021L, "count", 2L), port.argsOf("req_use_item", 0));
        assertEquals("已请求升级 大熔炉 到 2 级（已开启背包资源包 1 项）", r.get("message"));
        // 本地乐观扣物品/加资源
        assertEquals(0L, svc.state().itemCount(100021L));
        assertEquals(200L, svc.state().resourceCount(103L));
    }

    @Test
    public void usePacks_notEnough_usedAllStillWaiting() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 51L));
        // 1 个 100 包开完仍缺 80 → 重试仍 51 → WAITING（带已开包摘要）
        BootstrapService svc = service(port, packLoad(content("100021", 1L)), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
        assertTrue("主提示应保留：" + r.get("message"),
                String.valueOf(r.get("message")).startsWith(
                        "资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级"));
        assertTrue("应带开包摘要：" + r.get("message"),
                String.valueOf(r.get("message")).contains("（已开启背包资源包 1 项）"));
        Map<String, Object> preparation = (Map<String, Object>) r.get("preparation");
        Map<String, Object> backpack = (Map<String, Object>) preparation.get("backpack");
        assertEquals(true, backpack.get("ready"));
        assertEquals(1, ((List<?>) backpack.get("used")).size());
    }

    @Test
    public void usePacks_backpackSyncedButNoPacks_zeroUsed() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L),
                content("code", 51L));
        // 背包已同步但无任何资源包 → used 空 → WAITING 文案无开包后缀（可能带缺口摘要）
        BootstrapService svc = service(port, packLoad(content()), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals(0, port.count("req_use_item"));
        assertEquals("WAITING", r.get("status"));
        assertTrue("主提示应保留（P2-2 后可能带缺口摘要）：" + r.get("message"),
                String.valueOf(r.get("message")).startsWith(
                        "资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级"));
    }

    @Test
    public void usePacks_safeResourceAndSelectBoxPayload() throws Exception {
        // 白名单语义抽测：安全资源计数（103+193）足够 → 不开包；自选箱 payload 带 extra
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 0L));
        // 普通木材 50 + 安全木材（193）200 → 总 250 ≥ 180 → 不发 req_use_item
        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", content("bid", 301L, "lv", 1L, "status", 0L));
        buildings.put("302", content("bid", 302L, "lv", 1L, "status", 0L));
        Map<String, Object> load = content("uid", 8001L, "name", "旧名字",
                "buildings", buildings, "queues", new ArrayList<>(),
                "res", content("103", 50L, "193", 200L, "101", 500),
                // 2026-11-11 起 topUpResources 会无条件开「固定资源箱」（useAllFixedPacks），
                // 本用例只验证「按缺口开包」：改用自选箱 100101（不属固定箱段），
                // 安全资源足够时仍应一次都不开。
                "bag", content("100101", 3L));
        BootstrapService svc = service(port, load, enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals(0, port.count("req_use_item"));
        assertEquals("已请求升级 大熔炉 到 2 级", r.get("message"));
    }

    // ================= 任务奖励领取（真实轮 2026-11-10：新号资源为 0 只能靠领奖） =================

    @Test
    public void claimTaskRewards_claimsFromPushLists() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 推送：成长任务 1 个、章节任务 1 个、未领宝箱 1 个（已领的 get_flag=true 应排除）
        port.notifies.put("notify_growthquests", content("quests",
                List.of(content("id", 11L), content("id", 12L))));
        port.notifies.put("notify_chapterquests", content(
                "quests", List.of(content("id", 21L)),
                "chapter_award", List.of(content("id", 31L, "get_flag", false),
                        content("id", 32L, "get_flag", true))));
        // 协议成功
        port.when("req_get_growthquest_award", content("code", 0L), content("code", 0L));
        port.when("req_get_chapterquest_award", content("code", 0L));
        port.when("req_get_chapter_award", content("code", 0L));
        port.when("req_seven_sign_one_click_award", content("code", 0L));
        port.when("req_sign_21day_get_all_reward", content("code", 0L));

        BootstrapService svc = new BootstrapService(port);
        Map<String, Object> r = svc.claimTaskRewards();

        @SuppressWarnings("unchecked") List<String> claimed = (List<String>) r.get("claimed");
        assertEquals(7, claimed.size());   // 2 成长 + 1 章节 + 1 宝箱 + 2 签到 + 1 探险挂机
        assertTrue("应含成长任务 11", claimed.contains("成长任务 11"));
        assertTrue("应含章节宝箱 31", claimed.contains("章节宝箱 31"));
        assertFalse("已领宝箱 32 不应再领", claimed.contains("章节宝箱 32"));
        assertEquals(0, r.get("failed"));
    }

    @Test
    public void claimTaskRewards_upstreamRejectionCountsAsFailed() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.notifies.put("notify_growthquests", content("quests", List.of(content("id", 11L))));
        port.when("req_get_growthquest_award", content("code", 51L));   // 被拒
        port.when("req_seven_sign_one_click_award", content("code", 0L));
        port.when("req_sign_21day_get_all_reward", content("code", 0L));

        BootstrapService svc = new BootstrapService(port);
        Map<String, Object> r = svc.claimTaskRewards();

        @SuppressWarnings("unchecked") List<String> claimed = (List<String>) r.get("claimed");
        assertEquals(1, r.get("failed"));
        assertFalse("被拒的不计入已领", claimed.contains("成长任务 11"));
    }

    @Test
    public void claimTaskRewards_noPush_noQuestClaimed() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = new BootstrapService(port);
        Map<String, Object> r = svc.claimTaskRewards();
        @SuppressWarnings("unchecked") List<String> claimed = (List<String>) r.get("claimed");
        // 原版语义：两个签到协议无条件尝试（不依赖推送），任务奖必须依赖推送列表
        for (String item : claimed) {
            assertFalse("无推送时不应领任务奖: " + item,
                    item.startsWith("成长任务") || item.startsWith("章节"));
        }
    }


    // ================= 商店补资源（真实轮 2026-11-10：新号资源为 0，需钻石买） =================

    @Test
    public void buyMissingResources_purchasesByGapWithinDiamondBudget() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 钻石 100，木材缺口 25000（需 3 包，2 钻/包）
        port.when("req_loaddata", content("uid", 9001L, "res", content("101", 100L)));
        // 新版结构：{info:{goods:{"id":{id, conf_id, grid_id}}}}，conf_id 经目录 configId 映射 itemId
        // 木材包 configId=70110102 → itemId=100123
        port.when("req_shop_info", content("info", content("id", 701L,
                "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        port.when("req_shop_buy", content("code", 0L));

        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());
        Map<String, Object> r = svc.buyMissingResources(
                List.of(new BuildingGateCatalog.Cost(103L, 25000L)));

        @SuppressWarnings("unchecked") List<Map<String, Object>> purchases =
                (List<Map<String, Object>>) r.get("purchases");
        assertEquals(1, purchases.size());
        assertEquals(3L, purchases.get(0).get("packs"));       // 25000/10000 向上取整
        assertEquals(6L, purchases.get(0).get("diamondCost")); // 3 包 × 2 钻
        assertEquals(6L, r.get("diamondSpent"));
    }

    @Test
    public void buyMissingResources_noDiamonds_buysNothing() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9002L, "res", content("101", 0L)));
        port.when("req_shop_info", content("goods", List.of(content("itemId", 100123L, "goodsId", 555L))));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());

        Map<String, Object> r = svc.buyMissingResources(List.of(new BuildingGateCatalog.Cost(103L, 25000L)));
        @SuppressWarnings("unchecked") List<Map<String, Object>> purchases =
                (List<Map<String, Object>>) r.get("purchases");
        assertTrue("没钻石不应购买", purchases.isEmpty());
    }

    @Test
    public void shopCatalog_hasCommanderPackPrices() {
        assertEquals(2L, BootstrapShopCatalog.priceOf(100123L));   // 1 万木材
        assertEquals(10L, BootstrapShopCatalog.priceOf(100133L));  // 1 万煤矿
        assertEquals(40L, BootstrapShopCatalog.priceOf(100143L));  // 1 万铁矿
        assertEquals(0L, BootstrapShopCatalog.priceOf(1L));        // 未知商品
    }


    // ================= 探险挂机奖励（2026-11-11：网页版 0 基础起号补资源手段） =================

    @Test
    public void claimAdventureHangUp_success() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_open_hang_up_reward_view", content("code", 0L));
        port.when("req_get_hang_up_reward", content("code", 0L, "rewards", "wood:100"));
        BootstrapService svc = new BootstrapService(port);
        Map<String, Object> r = svc.claimAdventureHangUp();
        assertEquals(Boolean.TRUE, r.get("claimed"));
        assertEquals(0L, r.get("code"));
    }

    @Test
    public void claimAdventureHangUp_notReadySkipsWithoutError() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_open_hang_up_reward_view", content("code", 0L));
        port.when("req_get_hang_up_reward", content("code", 4004L));   // 挂机时长未到
        BootstrapService svc = new BootstrapService(port);
        Map<String, Object> r = svc.claimAdventureHangUp();
        assertEquals(Boolean.FALSE, r.get("claimed"));
        assertTrue("应提示挂机时长未达条件: " + r.get("reason"),
                String.valueOf(r.get("reason")).contains("挂机时长"));
    }

    @Test
    public void claimAdventureHangUp_previewFailureDegrades() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_open_hang_up_reward_view", content("code", 5000L));  // 未解锁等
        BootstrapService svc = new BootstrapService(port);
        Map<String, Object> r = svc.claimAdventureHangUp();
        assertFalse(Boolean.TRUE.equals(r.get("claimed")));
    }


    // ================= 幸存者委任（真实轮 2026-11-11：不委任则资源恒 0） =================

    @Test
    public void assignIdleSurvivors_startsAllIdleOnes() {
        ScriptedPort port = new ScriptedPort();
        // 精确协议成功（有工位数据时走 send_or_fired）
        port.when("req_survivor_send_or_fired", content("code", 0L), content("code", 0L), content("code", 0L));
        port.when("req_loaddata", content("uid", 9101L,
                "survivor_list", List.of(
                        content("sid", 2001L, "fid_work", 0L, "is_sick", false),
                        content("sid", 2002L, "fid_work", 0L, "is_sick", false),
                        content("sid", 2003L, "fid_work", 7001L, "is_sick", false))));
        port.when("req_survivor_work_start", content("code", 0L), content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());

        Map<String, Object> r = svc.assignIdleSurvivors();
        assertEquals(2, r.get("idleBefore"));
        assertEquals(2, r.get("assignedCount"));
        assertEquals(0, r.get("failed"));
    }

    @Test
    public void runNext_assignsSurvivorsBeforeBuilding() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9102L,
                "buildings", content("301", content("bid", 301L, "lv", 0L, "status", 0L)),
                "queues", new ArrayList<>(),
                "res", content("103", 0L),
                "survivor_list", List.of(content("sid", 3001L, "fid_work", 0L, "is_sick", false))));
        port.when("req_survivor_work_start", content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());
        svc.applyConfig(new BootstrapService.BootstrapConfig(true, 724L, 3, true, false, false, "", false, ""));

        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertTrue("应先委任幸存者: " + r.get("message"),
                String.valueOf(r.get("message")).contains("幸存者"));
    }


    /** 最小引擎替身：createRole 只用到 login 返回的 uid/serverId/authContent。 */
    private static final class FakeEngine extends com.carlos.vault.wjdr.WjdrEngine {
        private final long uid;
        private final long serverId;

        FakeEngine(long uid, long serverId) {
            super(null);
            this.uid = uid;
            this.serverId = serverId;
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid,
                                 java.util.Map<String, Object> extraParams) {
            LoginResult r = new LoginResult();
            r.uid = uid;
            r.serverId = serverId > 0 ? serverId : (serverid == null ? 0L : serverid);
            return r;
        }
    }

    // ================= 建号前置校验（2026-11-11 用户提示「建号前置是账号在线」） =================

    @Test
    public void countRolesInServer_countsByServerId() {
        Map<String, Object> roles = content("list", List.of(
                content("serverid", 724L, "uid", 1L),
                content("serverid", 724L, "uid", 2L),
                content("serverid", 888L, "uid", 3L),
                content("kid", 724L, "uid", 4L)));
        assertEquals(3, BootstrapService.countRolesInServer(roles, 724L));
        assertEquals(1, BootstrapService.countRolesInServer(roles, 888L));
        assertEquals(0, BootstrapService.countRolesInServer(roles, 1L));
    }

    /** 新版口径：建号 uid<=0 视为服务端未创建角色，并带 block_create_role_endtime 提示。 */
    @Test
    public void createRole_uidZero_throwsWithBlockHint() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = new BootstrapService(port);
        com.carlos.vault.wjdr.WjdrEngine engine = new ZeroUidEngine();
        try {
            svc.createRole(engine, "f1", "tk", 724L, "");
            fail("uid<=0 应判建号失败");
        } catch (IllegalStateException e) {
            assertTrue("应提示服务端没有创建角色: " + e.getMessage(),
                    e.getMessage().contains("没有创建角色"));
            assertTrue("应带建号限制时间戳提示: " + e.getMessage(),
                    e.getMessage().contains("block_create_role_endtime") || e.getMessage().contains("禁止该账号建号"));
        }
    }

    /** 建号成功：uid>0 且无限制。 */
    @Test
    public void createRole_successWithPositiveUid() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = new BootstrapService(port);
        FakeEngine engine = new FakeEngine(299459378L, 724L);
        BootstrapService.LoginOutcome out = svc.createRole(engine, "f1", "tk", 724L, "");
        assertEquals(299459378L, out.uid());
    }

    /** uid=0 且带 block 时间戳的引擎替身。 */
    private static final class ZeroUidEngine extends com.carlos.vault.wjdr.WjdrEngine {
        ZeroUidEngine() {
            super(null);
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid,
                                 java.util.Map<String, Object> extraParams) {
            LoginResult r = new LoginResult();
            r.uid = 0L;
            r.serverId = serverid == null ? 0L : serverid;
            r.authContent = new java.util.LinkedHashMap<>();
            r.authContent.put("block_create_role_endtime", System.currentTimeMillis() / 1000L + 3600L);
            return r;
        }
    }


    // ================= 兑换码（新版 BootstrapCdkeyCatalog：熔炉≥3 才兑换） =================

    @Test
    public void claimCdkeys_belowFurnace3_doesNothing() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9201L,
                "buildings", content("301", content("bid", 301L, "lv", 2L, "status", 0L))));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());
        assertEquals("熔炉 <3 不兑换", 0, svc.claimCdkeys());
    }

    @Test
    public void claimCdkeys_furnace3_exchangesAllAndSkipsPermanentFailures() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9202L,
                "buildings", content("301", content("bid", 301L, "lv", 3L, "status", 0L))));
        // 显式给 13 个码都脚本成功响应（ScriptedPort 对兑换码默认是永久失败）
        for (int i = 0; i < BootstrapService.CDKEYS.size(); i++) {
            port.when("req_exchange_cdkey", content("code", 0L));
        }
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());

        int claimed = svc.claimCdkeys();
        assertEquals("13 个固定兑换码", 13, claimed);
        // 第二次调用：已尝试过的不再请求
        assertEquals(0, svc.claimCdkeys());
        // 校验协议与参数
        boolean sawExchange = port.calls.stream()
                .anyMatch(c -> "req_exchange_cdkey".equals(c.getKey())
                        && "WJDR666".equals(c.getValue().get("cdkey"))
                        && Long.valueOf(2L).equals(((Number) c.getValue().get("platform")).longValue()));
        assertTrue("应以 {cdkey, platform:2} 调用 req_exchange_cdkey", sawExchange);
    }


    // ================= 英雄与探险（新版 BootstrapHeroService / BootstrapAdventureService） =================

    @Test
    public void heroAdvance_recruitsAndUpgradesHighestHero() {
        ScriptedPort port = new ScriptedPort();
        // 招募协议成功（默认替身会让英雄协议失败）
        port.when("req_hero_search_times", content("code", 0L), content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>();
        load.put("hero_list", List.of(content("id", 5001L, "lev", 10L),
                content("id", 5002L, "lev", 30L)));
        svc.syncLoadData(load);

        Map<String, Object> r = svc.heroAdvance();
        assertNotNull("有英雄数据应产出动作", r);
        assertTrue("应先做首次史诗招募: " + r.get("message"),
                String.valueOf(r.get("message")).contains("史诗招募"));
        // 第二次：招募阶梯（首次史诗已标记）
        Map<String, Object> r2 = svc.heroAdvance();
        assertNotNull(r2);
        assertTrue(String.valueOf(r2.get("message")).contains("招募"));
    }

    @Test
    public void heroAdvance_noHeroData_returnsNull() {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = new BootstrapService(port);
        assertNull("无英雄数据不应动作", svc.heroAdvance());
    }

    @Test
    public void adventureBattle_noHeroes_skips() {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = new BootstrapService(port);
        assertNull("没有英雄打不了探险", svc.adventureBattle(0L));
    }

    @Test
    public void adventureBattle_withHeroes_beginsAndEnds() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_begin_adventure_pve", content("is_fast", false));
        port.when("req_end_adventure_pve", content("is_win", true));
        BootstrapService svc = new BootstrapService(port);
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>();
        load.put("hero_list", List.of(content("id", 5002L, "lev", 30L)));
        load.put("adventure_pve_info", content("pass_checkpoint", 0L));
        svc.syncLoadData(load);

        Map<String, Object> r = svc.adventureBattle(0L);
        assertNotNull(r);
        assertEquals("STARTED", r.get("status"));
        assertTrue("应通关第 1 关: " + r.get("message"), String.valueOf(r.get("message")).contains("第 1 关"));
        // 校验开局参数：checkpoint=1、五人队伍（不足补 0）
        Map<String, Object> args = port.argsOf("req_begin_adventure_pve", 0);
        assertEquals(1L, ((Number) args.get("checkpoint")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> squadData = (Map<String, Object>) args.get("fight_heros_data");
        @SuppressWarnings("unchecked")
        List<Long> heroes = (List<Long>) squadData.get("fight_heros");
        assertEquals("队伍固定 5 个槽位", 5, heroes.size());
        assertEquals(5002L, heroes.get(0).longValue());
        assertEquals(0L, heroes.get(4).longValue());
        assertEquals(1L, svc.stateForTest().adventurePassed());
    }


    // ================= 精确工位委任（新版 assignIdleSurvivorToProduction） =================

    @Test
    public void assignIdleSurvivors_preciseStationUsesSendOrFired() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_survivor_send_or_fired", content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>();
        // 302 伐木场（Lv1），工位 confFid=401（类型 24），实例 fid=4010001，无人占用
        java.util.Map<String, Object> b302 = content("bid", 302L, "lv", 1L, "status", 0L);
        load.put("buildings", content("302", b302));
        load.put("furnitures", List.of(content("bid", 302L, "conf_fid", 401L, "fid", 4010001L,
                "lv", 1L, "type", 24L)));
        load.put("survivor_list", List.of(content("sid", 5001L, "fid_work", 0L, "is_sick", false)));
        svc.syncLoadData(load);

        Map<String, Object> r = svc.assignIdleSurvivors();
        assertEquals(1, r.get("assignedCount"));
        Map<String, Object> args = port.argsOf("req_survivor_send_or_fired", 0);
        assertEquals(5001L, ((Number) args.get("sid")).longValue());
        assertEquals(4010001L, ((Number) args.get("fid_work")).longValue());
        assertEquals(1L, ((Number) args.get("type")).longValue());
    }


    // ================= 新手引导（新版 BootstrapTutorialService） =================

    @Test
    public void tutorialAdvance_reportsNextStep() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_guide_set_step", content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>();
        load.put("guide_list", List.of(content("id", 100101L, "step", 0L)));
        svc.syncLoadData(load);

        Map<String, Object> r = svc.tutorialAdvance(java.util.Map.of(100101L, 9L));
        assertNotNull("有引导数据应推进", r);
        assertEquals("STARTED", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("第 1/9 步"));
        // 校验上报参数
        Map<String, Object> args = port.argsOf("req_guide_set_step", 0);
        assertEquals(100101L, ((Number) args.get("id")).longValue());
        assertEquals(1L, ((Number) args.get("step")).longValue());
        assertEquals(1L, svc.stateForTest().guideSteps().get(100101L).longValue());
    }

    @Test
    public void tutorialAdvance_alreadyAtMax_returnsNull() {
        ScriptedPort port = new ScriptedPort();
        BootstrapService svc = new BootstrapService(port);
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>();
        load.put("guide_list", List.of(content("id", 100101L, "step", 9L)));
        svc.syncLoadData(load);
        assertNull("已达 maxStep 不再上报", svc.tutorialAdvance(java.util.Map.of(100101L, 9L)));
    }


    // ================= 邮件与成就（新版 BootstrapMailService / BootstrapMilestoneService） =================

    @Test
    public void claimMails_readsActionableMailIds() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_mail_cover_list", content("code", 0L, "coverlist", List.of(
                content("first_type", 1L, "mailid_new", "m1", "isread", 0L, "attach_statue", 0L),
                content("first_type", 1L, "mailid_new", "m2", "isread", 1L, "attach_statue", 1L),
                content("first_type", 1L, "mailid_new", "m3", "isread", 1L, "attach_statue", 0L))));
        port.when("req_batch_mail_read", content("code", 0L));
        BootstrapService svc = new BootstrapService(port);

        int claimed = svc.claimMails();
        assertEquals("未读 m1 + 有附件 m2 应领，已读无附件 m3 跳过；calls=" + port.calls, 2, claimed);
        Map<String, Object> args = port.argsOf("req_batch_mail_read", 0);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) args.get("mid_list_new");
        assertEquals(2, ids.size());
        assertTrue(ids.contains("m1") && ids.contains("m2"));
        assertEquals(1L, ((Number) args.get("first_type")).longValue());
    }

    @Test
    public void claimMails_noActionable_returnsZero() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_mail_cover_list", content("code", 0L, "coverlist", List.of(
                content("first_type", 1L, "mailid_new", "m3", "isread", 1L, "attach_statue", 0L))));
        BootstrapService svc = new BootstrapService(port);
        assertEquals(0, svc.claimMails());
    }

    @Test
    public void claimAchievements_claimsClaimableOnly() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_achievement_info", content("code", 0L, "list", List.of(
                content("id", 11L, "state", 2L),
                content("id", 12L, "state", 1L),
                content("id", 13L, "can_get", 1L))));
        port.when("req_achievement_get_reward", content("code", 0L), content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        assertEquals("11(state=2) 与 13(can_get=1) 可领", 2, svc.claimAchievements());
    }

    // ================= C3：R-04 world 完成后不再探测情报 =================

    /** world 两件套（kingdom info + fog）完成后，runNext 不得再 collectNotifies（P2-1 去阻塞）。 */
    @Test
    public void worldCompleted_stopsIntelProbeAndCollect() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO, content("events", content()));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        advanceWorldFirst(svc);                  // kingdom info（world 未完成 → 探测）
        svc.runNext();                           // fog
        int probesAfterWorld = port.collectCalls;
        svc.runNext();                           // world 已完成 → 不得再探测
        svc.runNext();
        assertEquals("world 完成后不得再 collectNotifies：" + port.calls,
                probesAfterWorld, port.collectCalls);
    }

    // ================= C3：R-08 world 推进失败重试 =================

    /** world kingdom info 连续失败 3 次后放弃（不再每轮刷），成功即清零。 */
    @Test
    public void worldInfoFailures_retryThenGiveUpAfterThree() throws Exception {
        IntelPort port = new IntelPort(false);
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO, content("events", content()));
        port.when("req_world_kingdom_info", content("code", 500L), content("code", 500L),
                content("code", 500L));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());

        svc.runNext();   // 第 1 次失败：下轮仍重试
        assertEquals(1, port.count("req_world_kingdom_info"));
        svc.runNext();
        assertEquals(2, port.count("req_world_kingdom_info"));
        svc.runNext();   // 第 3 次失败 → 放弃
        assertEquals(3, port.count("req_world_kingdom_info"));
        svc.runNext();   // 放弃后不再请求
        assertEquals("3 次失败后放弃 world，不再每轮刷", 3, port.count("req_world_kingdom_info"));
    }

    /** kid 三种字段都取不到：不发送 fog，按失败计数；3 次后放弃。 */
    @Test
    public void worldKidMissing_doesNotSendFog_countsFailure() throws Exception {
        IntelPort port = new IntelPort(false);
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO, content("events", content()));
        port.when("req_world_kingdom_info", content("code", 0L), content("code", 0L),
                content("code", 0L), content("code", 0L));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());

        svc.runNext();
        svc.runNext();
        svc.runNext();
        assertEquals("kid 取不到不得发送 fog", 0, port.count("req_world_map_fog"));
        assertEquals(3, port.count("req_world_kingdom_info"));
        svc.runNext();
        assertEquals("kid 持续缺失 3 次后放弃", 3, port.count("req_world_kingdom_info"));
    }

    // ================= C3：R-09 finishPass 当日语义 =================

    /** 三项全空时不占「今天已收尾」；当天后续新增邮件仍会收尾。 */
    @Test
    public void finishPass_emptyRewards_doesNotMarkDay() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 12 个空响应：finishPass 6 个大类 + 主线条带（489 行）6 个大类，
        // 避免主线取到默认 9003 而把 mail 大类打入 5 分钟退避
        for (int i = 0; i < 12; i++) {
            port.when(BootstrapService.MAIL_COVER_LIST,
                    content("code", 0L, "coverlist", List.of()));
        }
        BootstrapService svc = service(port, readyLoad(7L, 1L),
                new BootstrapConfig(true, 0L, 7, true, false, false, "", false, ""));

        Map<String, Object> first = svc.runNext();
        assertFalse("三项全空不算收尾：" + first,
                String.valueOf(first.get("message")).contains("收尾完成"));

        port.when(BootstrapService.MAIL_COVER_LIST, content("code", 0L, "coverlist", List.of(
                content("mailid_new", "m1", "isread", 0L, "attach_statue", 1L))));
        for (int i = 0; i < 5; i++) {
            port.when(BootstrapService.MAIL_COVER_LIST,
                    content("code", 0L, "coverlist", List.of()));
        }
        port.when(BootstrapService.MAIL_BATCH_READ, content("code", 0L));
        Map<String, Object> second = svc.runNext();
        assertTrue("全空未置位 → 新增邮件仍应触发收尾：" + second,
                String.valueOf(second.get("message")).contains("收尾完成"));
    }

    /** 收尾日期取 ProtocolPort.serverNowMs()，固定 Asia/Shanghai 时区。 */
    @Test
    public void finishPass_usesServerClockShanghaiDate() throws Exception {
        long utcMs = java.time.ZonedDateTime.of(2026, 11, 12, 16, 30, 0, 0,
                java.time.ZoneOffset.UTC).toInstant().toEpochMilli();   // 上海 2026-11-13 00:30
        ScriptedPort port = new ScriptedPort() {
            @Override
            public long serverNowMs() {
                return utcMs;
            }
        };
        port.when(BootstrapService.MAIL_COVER_LIST, content("code", 0L, "coverlist", List.of(
                content("mailid_new", "m1", "isread", 0L, "attach_statue", 1L))));
        for (int i = 0; i < 5; i++) {
            port.when(BootstrapService.MAIL_COVER_LIST,
                    content("code", 0L, "coverlist", List.of()));
        }
        port.when(BootstrapService.MAIL_BATCH_READ, content("code", 0L));
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, new BootstrapHoldStore(), "fp-date", store);
        svc.applyConfig(new BootstrapConfig(true, 0L, 7, true, false, false, "", false, ""));
        svc.syncLoadData(readyLoad(7L, 1L));

        Map<String, Object> result = svc.runNext();
        assertTrue("应执行收尾：" + result,
                String.valueOf(result.get("message")).contains("收尾完成"));
        assertEquals("服务端毫秒按 Asia/Shanghai 折算日期", "2026-11-13",
                store.holder("fp-date").finishPassDay());
    }

    // ================= C3：R-10 里程碑形状回退 =================

    /** infos 为空数组时必须回退 list/achievements/data 的 state/can_get 形状。 */
    @Test
    public void claimAchievements_emptyInfos_fallsBackToListShape() {
        ScriptedPort port = new ScriptedPort();
        port.when(BootstrapService.ACHIEVEMENT_INFO, content("code", 0L,
                "infos", List.of(),
                "list", List.of(content("id", 7L, "state", 2L))));
        port.when(BootstrapService.ACHIEVEMENT_REWARD, content("code", 0L));
        BootstrapService svc = new BootstrapService(port);

        assertEquals(1, svc.claimAchievements());
        assertEquals(Map.of("id", 7L), port.argsOf(BootstrapService.ACHIEVEMENT_REWARD, 0));
    }

    /** infos 非空数组时只按里程碑形状（complete && !received）领，不误领 list 项。 */
    @Test
    public void claimAchievements_nonEmptyInfos_usesMilestoneShapeOnly() {
        ScriptedPort port = new ScriptedPort();
        port.when(BootstrapService.ACHIEVEMENT_INFO, content("code", 0L,
                "infos", List.of(content("id", 9L, "complete", true, "received", false)),
                "list", List.of(content("id", 7L, "state", 2L))));
        port.when(BootstrapService.ACHIEVEMENT_REWARD, content("code", 0L));
        BootstrapService svc = new BootstrapService(port);

        assertEquals(1, svc.claimAchievements());
        assertEquals(Map.of("id", 9L), port.argsOf(BootstrapService.ACHIEVEMENT_REWARD, 0));
        assertEquals(1, port.count(BootstrapService.ACHIEVEMENT_REWARD));
    }

    // ================= C3：R-11 失败钻石道具区分错误类型 =================

    /** 51（道具不存在/不可用）→ 本会话永久跳过，第二次不再请求。 */
    @Test
    public void claimBackpackDiamonds_51_permanentSkip() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_use_item", content("code", 51L));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(content("bag", content("100001", 1L)));

        assertEquals(0, svc.claimBackpackDiamonds());
        assertEquals(1, port.count("req_use_item"));
        svc.claimBackpackDiamonds();
        assertEquals("51 后本会话不再重试", 1, port.count("req_use_item"));
    }

    /** 非 51（限流/其它码）→ 不永久跳过，下一轮可重试。 */
    @Test
    public void claimBackpackDiamonds_otherCode_retryable() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_use_item", content("code", 500L), content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(content("bag", content("100001", 1L)));

        assertEquals(0, svc.claimBackpackDiamonds());
        assertEquals("非 51 不永久跳过，第二次仍请求并成功", 1, svc.claimBackpackDiamonds());
        assertEquals(2, port.count("req_use_item"));
    }

    // ================= C4：R-12 默认值统一 =================

    /** 需求 R-12：defaults()/fromMap 的 buyShopResources 默认 true；显式 false 仍可关闭。 */
    @Test
    public void config_buyShopResourcesDefaultsTrue() {
        assertEquals(true, BootstrapConfig.defaults().buyShopResources());
        assertEquals("fromMap 空 map 同样默认 true",
                true, BootstrapConfig.fromMap(Map.of()).buyShopResources());
        assertEquals("未传该键时默认 true",
                true, BootstrapConfig.fromMap(Map.of("enabled", true)).buyShopResources());
        assertEquals("显式 false 必须尊重",
                false, BootstrapConfig.fromMap(Map.of("buyShopResources", "false"))
                        .buyShopResources());
    }

    // ================= 二次审查 P1-2：商店不买煤/铁 =================

    /** P1-2 回归：统帅商店即使返回煤/铁商品也不购买（R-07：只买生肉/木材）。 */
    @Test
    public void buyMissingResources_neverBuysCoalOrIron() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9004L, "res", content("101", 1000L)));
        port.when("req_shop_info", content("info", content("id", 701L, "goods", content(
                "1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L),
                "2", content("id", 556L, "conf_id", 70110103L, "grid_id", 2L),
                "3", content("id", 557L, "conf_id", 70110104L, "grid_id", 3L)))));
        port.when("req_shop_buy", content("code", 0L));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());

        Map<String, Object> r = svc.buyMissingResources(List.of(
                new BuildingGateCatalog.Cost(103L, 25000L),
                new BuildingGateCatalog.Cost(104L, 100L),
                new BuildingGateCatalog.Cost(105L, 100L)));

        assertEquals(Boolean.FALSE, r.get("coverable"));
        for (Map.Entry<String, Map<String, Object>> call : port.calls) {
            if ("req_shop_buy".equals(call.getKey())) {
                assertEquals("只允许购买木材包 goodsId=555：" + call.getValue(),
                        555L, numberOf(call.getValue().get("goods_id")));
            }
        }
        assertTrue("煤/铁缺口计入 unsupplied", ((List<?>) r.get("unsupplied")).size() >= 2);
    }

    // ================= 二次审查 P2 回归 =================

    /** P2-1：ENDED 文案必须带不可覆盖缺口摘要（unsupplied 不再恒空）。 */
    @Test
    public void upgradeRejected_51_endedMessageContainsGap() throws Exception {
        ScriptedPort port = new ScriptedPort();
        for (int i = 0; i < 12; i++) {
            port.when("req_upgrade_building", content("code", 51L));
        }
        port.when("req_shop_info", content("info", content("id", 701L,
                "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        port.when("req_shop_buy", content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 10, true, false, true,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace9(0L), cfg);

        svc.runNext();
        svc.runNext();
        Map<String, Object> third = svc.runNext();
        assertEquals("ENDED", third.get("status"));
        String message = String.valueOf(third.get("message"));
        assertTrue("ENDED 文案应带缺口摘要：" + message,
                message.contains("缺口") && message.contains("煤矿"));
    }

    /**
     * P2-2 + R-07：purchase 缺失（buyShopResources=false）时按本地缺口判定；
     * 熔炉 7 连续 10 次不可覆盖 → ENDED（<9 的 stall>=10 分支）。
     */
    @Test
    public void upgradeRejected_51_noPurchase_furnace7_stallTen_ended() throws Exception {
        ScriptedPort port = new ScriptedPort();
        for (int i = 0; i < 80; i++) {
            port.when("req_upgrade_building", content("code", 51L));
        }
        MutableClock clock = new MutableClock(9_000_000L);
        BootstrapHoldStore holds = new BootstrapHoldStore(clock);
        String scope = "fp-stall10";
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 8, true, false, false,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace7(), cfg, holds, scope);

        for (int i = 0; i < 9; i++) {
            Map<String, Object> r = svc.runNext();
            assertEquals("第 " + (i + 1) + " 次 51 仍应 WAITING：" + r,
                    "WAITING", r.get("status"));
            clock.advance(BootstrapService.ACTION_RETRY_MILLIS + 1_000L);
        }
        Map<String, Object> ended = svc.runNext();   // stall = 10
        assertEquals("stall>=10 → ENDED：" + ended, "ENDED", ended.get("status"));
        assertTrue("ENDED 文案带缺口：" + ended.get("message"),
                String.valueOf(ended.get("message")).contains("缺口"));
    }

    /** P2-3：熔炉 9 的 51 分支同样写购买冷却，第二次不再全量购买。 */
    @Test
    public void upgradeRejected_51_furnace9_buyCooldown() throws Exception {
        ScriptedPort port = new ScriptedPort();
        for (int i = 0; i < 12; i++) {
            port.when("req_upgrade_building", content("code", 51L));
        }
        for (int i = 0; i < 6; i++) {
            port.when("req_shop_info", content("info", content("id", 701L,
                    "goods", content("1", content("id", 555L, "conf_id", 70110102L, "grid_id", 1L)))));
        }
        port.when("req_shop_buy", content("code", 0L));
        BootstrapConfig cfg = new BootstrapConfig(true, 0L, 10, true, false, true,
                "", false, "");
        BootstrapService svc = service(port, readyLoadFurnace9(500L), cfg);

        svc.runNext();
        int buys = port.count("req_shop_buy");
        int shopCalls = port.count("req_shop_info");
        assertTrue("首轮应尝试购买：" + buys, buys >= 1);
        svc.runNext();
        assertEquals("≥9 分支购买冷却生效：第二次不再查商店", shopCalls, port.count("req_shop_info"));
        assertEquals("≥9 分支购买冷却生效：第二次不再购买", buys, port.count("req_shop_buy"));
        Map<String, Object> third = svc.runNext();
        assertEquals("三次不可覆盖 → ENDED", "ENDED", third.get("status"));
    }

    /** P2-5：ENDED 后 levelBuilding 必须被终态拦截。 */
    @Test
    public void levelBuilding_afterEnded_returnsEnded() throws Exception {
        ScriptedPort port = new ScriptedPort();
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, new BootstrapHoldStore(),
                "fp-lb-ended", store);
        svc.applyConfig(enabledConfig());
        svc.syncLoadData(readyLoad(1L, 1L));
        store.holder("fp-lb-ended").markResourceEnded();

        Map<String, Object> r = svc.levelBuilding(301L);
        assertEquals("ENDED", r.get("status"));
        assertEquals("终态不可再推进：不得发升级请求", 0, port.count("req_upgrade_building"));
    }

    /** P2-6：目标已达成但 ENDED 已置位时，status 必须 ENDED 优先（与批次层一致）。 */
    @Test
    public void resourceEnded_statusEndedEvenWhenTargetReached() throws Exception {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapService svc = new BootstrapService(new ScriptedPort(), new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, new BootstrapHoldStore(),
                "fp-ended-priority", store);
        svc.applyConfig(new BootstrapConfig(true, 0L, 9, true, false, false, "", false, ""));
        svc.syncLoadData(readyLoad(9L, 1L));
        store.holder("fp-ended-priority").markResourceEnded();

        Map<String, Object> status = svc.status();
        assertEquals("ENDED 优先于 LEVEL_COMPLETED", "ENDED", status.get("runState"));
        assertEquals("目标达成仍记录 completed", true, status.get("completed"));
        assertEquals("ENDED 文案", "大熔炉资源耗尽，起号结束", status.get("message"));
    }

    /** R-09 边界：serverNowMs 返回 0/负值时必须回退本机日期，不写 1970。 */
    @Test
    public void finishPass_serverClockInvalid_fallsBackLocalDate() throws Exception {
        ScriptedPort port = new ScriptedPort() {
            @Override
            public long serverNowMs() {
                return 0L;
            }
        };
        port.when(BootstrapService.MAIL_COVER_LIST, content("code", 0L, "coverlist", List.of(
                content("mailid_new", "m1", "isread", 0L, "attach_statue", 1L))));
        for (int i = 0; i < 5; i++) {
            port.when(BootstrapService.MAIL_COVER_LIST,
                    content("code", 0L, "coverlist", List.of()));
        }
        port.when(BootstrapService.MAIL_BATCH_READ, content("code", 0L));
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, new BootstrapHoldStore(),
                "fp-clock0", store);
        svc.applyConfig(new BootstrapConfig(true, 0L, 7, true, false, false, "", false, ""));
        svc.syncLoadData(readyLoad(7L, 1L));

        svc.runNext();
        assertEquals("非法服务器时钟回退本机（按 Asia/Shanghai 折算，跨时区稳定）",
                java.time.Instant.ofEpochMilli(System.currentTimeMillis())
                        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString(),
                store.holder("fp-clock0").finishPassDay());
    }

    /** R-10：infos 为 Map（非数组）时按需求回退 list 形状，不误判里程碑。 */
    @Test
    public void claimAchievements_infosMapShape_fallsBackToList() {
        ScriptedPort port = new ScriptedPort();
        port.when(BootstrapService.ACHIEVEMENT_INFO, content("code", 0L,
                "infos", content("1", content("id", 9L, "complete", true, "received", false)),
                "list", List.of(content("id", 7L, "state", 2L))));
        port.when(BootstrapService.ACHIEVEMENT_REWARD, content("code", 0L));
        BootstrapService svc = new BootstrapService(port);

        assertEquals(1, svc.claimAchievements());
        assertEquals(Map.of("id", 7L), port.argsOf(BootstrapService.ACHIEVEMENT_REWARD, 0));
    }

    // ================= 二次审查 P1-3 / P2-8 =================

    /** P1-3：unsupplied 按「库存后缺口」，库存足够时 coverable=true（动作含煤/铁也一样）。 */
    @Test
    public void buyMissingResources_stockCoversCost_coverableTrue() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_loaddata", content("uid", 9005L, "res",
                content("103", 500000L, "104", 100000L, "105", 30000L)));
        BootstrapService svc = new BootstrapService(port);
        svc.syncLoadData(port.request("req_loaddata", Map.of()).getContent());

        Map<String, Object> r = svc.buyMissingResources(List.of(
                new BuildingGateCatalog.Cost(103L, 460000L),
                new BuildingGateCatalog.Cost(104L, 92000L),
                new BuildingGateCatalog.Cost(105L, 23000L)), true);

        assertEquals("库存足够 → 可覆盖（不误终态）", Boolean.TRUE, r.get("coverable"));
        assertEquals(0, ((List<?>) r.get("unsupplied")).size());
    }

    /** P2-8：他人正在发 world info（已认领但 kid 未回填）时，fog 分支不得计失败。 */
    @Test
    public void worldAdvance_inFlightClaim_doesNotCountFailure() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO, content("events", content()));
        BootstrapSessionState store = new BootstrapSessionState();
        String scope = "fp-inflight";
        BootstrapService svc = new BootstrapService(port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), NO_SLEEP, new BootstrapHoldStore(), scope, store);
        svc.applyConfig(intelConfig());
        svc.syncLoadData(intelReadyLoad(1L));
        // 模拟另一个请求已认领 info，但 kid 尚未写回
        store.holder(scope).claimWorldKingdomInfo();

        svc.runNext();
        assertEquals("in-flight 不得计 world 失败", 0,
                store.holder(scope).worldAttempts());
        assertEquals("in-flight 不得发 fog", 0, port.count("req_world_map_fog"));
    }
}
