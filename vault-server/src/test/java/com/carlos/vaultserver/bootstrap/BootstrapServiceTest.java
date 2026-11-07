package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
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
            msg.setContent(q != null && !q.isEmpty() ? q.poll()
                    : new LinkedHashMap<>(Map.of("code", 0)));
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

    private static Map<String, Object> content(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
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
        // 队列 1、2 全忙 → 无空闲建筑队列（原版顺序：MANUAL 判定在前，instantDiamond=true 才走到这里）
        ScriptedPort port = new ScriptedPort();
        List<Object> busyQueues = List.of(
                content("qid", 1L, "status", 0L), content("qid", 2L, "status", 0L));
        Map<String, Object> load = content("buildings", content("301", content("bid", 301L, "lv", 1L)),
                "queues", busyQueues);
        BootstrapService svc = service(port, load, enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
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
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L));
        BootstrapService svc = service(port, readyLoad(1L, 1L), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
        assertEquals("资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级", r.get("message"));
        assertEquals(51, r.get("errorCode"));
        // 51 → 补资源后重试一次（M4a 补给占位，背包未同步）
        assertEquals(2, port.count("req_upgrade_building"));
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

    // ================= runUntil =================

    @Test
    public void runUntil_twoStartedThenWaitingOn51() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // 第 1、2 次升级成功，第 3 次（熔炉 1→2）双 51
        port.when("req_upgrade_building", content("code", 0L), content("code", 0L),
                content("code", 51L), content("code", 51L));
        BootstrapService svc = service(port, readyLoad(0L, 0L), enabledConfig());
        Map<String, Object> summary = svc.runUntil(5);
        assertEquals(2, summary.get("started"));
        // 前 2 步各 1 次 + 第 3 步 51 首发与重试各 1 次 = 4 次
        assertEquals(4, port.count("req_upgrade_building"));
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

        @Override
        public Map<String, Object> notify(String proto) {
            return notifies.get(proto);
        }

        @Override
        public void collectNotifies(long millis) {
            // 跳时：测试不等待推送窗口
        }
    }

    /** 情报事件条目（event_id=3001 英雄之旅区间，形状对齐 notify_intelligence_info）。 */
    private static Map<String, Object> intelEvent(long id, long status) {
        return content("id", id, "status", status, "event_id", 3001L, "expire_ts", 0L);
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

    @Test
    public void intel_claimRewards_atFurnace7() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("11", intelEvent(11L, 2L), "12", intelEvent(12L, 2L))));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
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
        Map<String, Object> r = svc.runNext();
        // 第一个领取被拒（非 2501）→ 终止本轮领取 → 无 status=1 → 放行主线
        assertEquals(1, port.count("req_intelligence_receive"));
        assertEquals("COMPLETED", r.get("status"));
    }

    @Test
    public void intel_fightIdle_hallReady() throws Exception {
        IntelPort port = new IntelPort();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO,
                content("events", content("13", intelEvent(13L, 1L))));
        BootstrapService svc = service(port, intelReadyLoad(1L), intelConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals("已快速完成 1 个英雄之旅情报（客户端默认小队）", r.get("message"));
        assertEquals(13L, r.get("intelligenceFought"));
        assertEquals(Map.of("id", 13L, "fight_heros_data",
                        Map.of("source", 1L, "fight_heros", List.of(0L, 0L, 0L, 0L, 0L))),
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
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L));
        // 1 个 100 包开完仍缺 80 → 重试仍 51 → WAITING（带已开包摘要）
        BootstrapService svc = service(port, packLoad(content("100021", 1L)), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("WAITING", r.get("status"));
        assertEquals("资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级（已开启背包资源包 1 项）",
                r.get("message"));
        Map<String, Object> preparation = (Map<String, Object>) r.get("preparation");
        Map<String, Object> backpack = (Map<String, Object>) preparation.get("backpack");
        assertEquals(true, backpack.get("ready"));
        assertEquals(1, ((List<?>) backpack.get("used")).size());
    }

    @Test
    public void usePacks_backpackSyncedButNoPacks_zeroUsed() throws Exception {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", content("code", 51L), content("code", 51L));
        // 背包已同步但无任何资源包 → used 空 → WAITING 文案无开包后缀
        BootstrapService svc = service(port, packLoad(content()), enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals(0, port.count("req_use_item"));
        assertEquals("WAITING", r.get("status"));
        assertEquals("资源或道具不足，等待补充后重试：升级 大熔炉 到 2 级", r.get("message"));
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
                "bag", content("100021", 3L));
        BootstrapService svc = service(port, load, enabledConfig());
        Map<String, Object> r = svc.runNext();
        assertEquals("STARTED", r.get("status"));
        assertEquals(0, port.count("req_use_item"));
        assertEquals("已请求升级 大熔炉 到 2 级", r.get("message"));
    }
}
