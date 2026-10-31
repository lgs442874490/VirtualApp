package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.pet.PetSkillCatalog;
import com.carlos.vault.wjdr.pet.PetSupport;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 宠物任务回归：寻宝 area_info 解析 / 每轮最多 3 只 / 技能默认发育类。
 *
 * <p>生产实证：notify_pet_adventure_info 的形状是 {@code {info:{area_info:{...},
 * daily_adventure_nums:N}}}；旧代码读 {@code adv.get("areas")} 恒为 null，导致寻宝
 * 从未真正执行过。本测试锁定新解析和行为。
 */
public class PetTaskTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private FakeEngine engine;
    private final Map<String, JSONObject> params = new LinkedHashMap<>();
    private TaskContext tc;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        engine = new FakeEngine();
        params.clear();
        tc = new TaskContext(engine, FPID, SID, store, params, "单测");
    }

    // ======================= 目录 / 解析 =======================

    @Test
    public void catalog_developmentIds_are_the_five_growth_skills() {
        Set<Long> dev = new LinkedHashSet<>();
        for (long id : PetSkillCatalog.developmentIds()) dev.add(id);
        assertEquals("发育类=城建助手/心灵伴侣/重物搬运/敏锐感官/大地馈赠",
                5, dev.size());
        assertTrue(dev.contains(550011L));
        assertTrue(dev.contains(550021L));
        assertTrue(dev.contains(550031L));
        assertTrue(dev.contains(550041L));
        assertTrue(dev.contains(550061L));
        assertFalse("战斗类不能被默认勾选", dev.contains(550051L));
        assertFalse(dev.contains(550091L));
        assertEquals("重物搬运", PetSkillCatalog.nameOf(550031L));
        assertEquals("城建助手", PetSkillCatalog.nameOf(550011L));
    }

    @Test
    public void adventureAreas_reads_real_notify_shape() {
        Map<String, Object> adv = adv(4L,
                area(1, 55001, now() - 60),   // 已完成
                area(2, 55002, now() + 3600), // 进行中
                area(3, 0, 0));               // 空闲
        List<PetSupport.Area> areas = PetSupport.adventureAreas(adv);
        assertEquals(3, areas.size());
        assertTrue(areas.get(0).completed(now()));
        assertTrue(areas.get(1).inProgress(now()));
        assertTrue(areas.get(2).idle());
        assertEquals(4, PetSupport.dailyAdventureUsed(adv));
    }

    @Test
    public void ownedSkills_merge_pet_info_and_use_skill_info() {
        Map<String, Object> petInfo = mapOf("info", listOf(
                pet(55001, true), pet(55003, true), pet(55004, false)));
        Map<String, Object> exInfo = mapOf("use_skill_info", mapOf(
                "550071.0", mapOf("skill_id", 550071L,
                        "skill_info", mapOf("use_time", 100L))));
        Set<Long> owned = PetSupport.ownedSkillIds(petInfo, exInfo);
        assertTrue(owned.contains(550011L));
        assertTrue(owned.contains(550031L));
        assertFalse("未解锁宠物不算拥有", owned.contains(550041L));
        assertTrue("历史用过的技能也算拥有", owned.contains(550071L));
    }

    @Test
    public void dispatchAndClaimWriteBackToCachedNotify() {
        Map<String, Object> adv = adv(0L, area(1, 0, 0), area(2, 0, 0), area(3, 0, 0));
        PetSupport.markAreaDispatched(adv, 1L, 55001L, 100L, 200L, false);
        PetSupport.Area a1 = PetSupport.adventureAreas(adv).get(0);
        assertEquals(55001L, a1.petId);
        assertEquals(200L, a1.finishTime);
        assertTrue(a1.inProgress(150L));
        PetSupport.markAreaClaimed(adv, 1L, 4L, 300L);
        PetSupport.Area a1b = PetSupport.adventureAreas(adv).get(0);
        assertTrue("领奖后该点变为空闲，避免下一轮重复领奖/无法派遣", a1b.idle());
        assertEquals(4L, a1b.index);
        assertEquals(300L, a1b.refreshTime);
    }

    // ======================= 任务行为 =======================

    @Test
    public void selectedSkills_default_to_development_and_ignore_combat() {
        List<Long> selected = ServerTownTasks.petSelectedSkills(new JSONObject());
        assertEquals(5, selected.size());
        assertTrue(selected.contains(550031L));
        assertFalse(selected.contains(550051L));
    }

    @Test
    public void explicitEmptySelection_means_all_off() {
        JSONObject p = new JSONObject();
        p.put("selected_skills", new JSONArray());
        assertEquals(0, ServerTownTasks.petSelectedSkills(p).size());
    }

    @Test
    public void applySkills_uses_each_selected_skill_once_and_writes_state() throws Exception {
        JSONObject p = new JSONObject();
        JSONArray arr = new JSONArray();
        arr.put(550031L);
        p.put("selected_skills", arr);
        params.put("pet", p);
        engine.respond(0L);
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.petApplySkills(tc, ServerTownTasks.petSelectedSkills(tc.params("pet")),
                new LinkedHashSet<Long>(), new LinkedHashMap<String, Object>(), now(), m);
        assertEquals("单技能请求=逐条发送，不因其它冷却技能整批失败",
                1, engine.requestProtos.size());
        assertEquals("req_pet_use_skill", engine.requestProtos.get(0));
        assertTrue(String.valueOf(engine.requestArgs.get(0).get("skill_id_lst")).contains("550031"));
        String skills = tc.modState("pet").optString("skills", "");
        assertTrue("状态行需写技能名", skills.contains("重物搬运"));
        String used = tc.modState("pet").optString("skill_used", "");
        assertTrue("成功使用需落本地时间，通知延迟时不重复刷", used.contains("550031"));
    }

    @Test
    public void adventure_claims_only_completed_then_dispatches_idle_max_three() throws Exception {
        // 1 已完成；2/3 空闲；拥有 55001..55005（55001 领奖后释放可再次派遣）
        Map<String, Object> adv = adv(0L,
                area(1, 55001, now() - 60),
                area(2, 0, 0),
                area(3, 0, 0));
        Set<Long> owned = new LinkedHashSet<>();
        for (long id = 55001; id <= 55005; id++) owned.add(id);
        engine.respond(0L); // get_reward
        engine.respond(0L); // dispatch area 1（刚领完可再派）
        engine.respond(0L); // dispatch area 2
        engine.respond(0L); // dispatch area 3
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.petRunAdventure(tc, adv, owned, now(), m);
        assertEquals("1 次领奖 + 3 次派遣（每轮上限 3）", 4, engine.requestProtos.size());
        assertEquals("req_pet_adventure_get_reward", engine.requestProtos.get(0));
        for (int i = 1; i < 4; i++) {
            assertEquals("req_pet_adventure_dispatch", engine.requestProtos.get(i));
            assertEquals("按藏宝点顺序派遣", (long) i, ((Number) engine.requestArgs.get(i).get("id")).longValue());
            assertEquals("释放的宠物优先再派", 55000L + i, ((Number) engine.requestArgs.get(i).get("pet_id")).longValue());
        }
        String state = tc.modState("pet").optString("adventure", "");
        assertTrue("状态行需包含领奖与派遣", state.contains("领奖×1"));
        assertTrue(state.contains("派遣×3"));
    }

    @Test
    public void adventure_stale_pet_id_is_released_on_11787() throws Exception {
        // 快照 pet_id 还在，但服务器说该点没有宠物（11787）：释放该宠物，允许继续派遣，避免永久堵住。
        Map<String, Object> adv = adv(1L, area(1, 55001, now() - 60), area(2, 0, 0), area(3, 0, 0));
        Set<Long> owned = new LinkedHashSet<>();
        owned.add(55001L);
        engine.respond(11787L); // get_reward：该点实际无宠物
        engine.respond(0L);     // 释放后把 55001 派到空闲点
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.petRunAdventure(tc, adv, owned, now(), m);
        assertEquals(2, engine.requestProtos.size());
        assertEquals("req_pet_adventure_get_reward", engine.requestProtos.get(0));
        assertEquals("req_pet_adventure_dispatch", engine.requestProtos.get(1));
    }

    @Test
    public void adventure_stops_after_daily_limit_like_error() throws Exception {
        Map<String, Object> adv = adv(4L,
                area(1, 0, 0),
                area(2, 0, 0),
                area(3, 0, 0));
        Set<Long> owned = new LinkedHashSet<>();
        owned.add(55001L);
        owned.add(55002L);
        engine.respond(11790L); // 第一次派遣即被服务器拒绝（次数不足/体力等）
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.petRunAdventure(tc, adv, owned, now(), m);
        assertEquals("失败后不再继续派遣，避免刷失败请求", 1, engine.requestProtos.size());
        String state = tc.modState("pet").optString("adventure", "");
        assertTrue("状态行要显示失败码", state.contains("11790"));
    }

    // ======================= 测试替身 =======================

    static final class FakeEngine extends WjdrEngine {
        final List<String> requestProtos = new ArrayList<>();
        final List<Map<String, Object>> requestArgs = new ArrayList<>();
        final Deque<Long> responses = new ArrayDeque<>();
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();

        FakeEngine() {
            super(null);
        }

        void respond(long code) {
            responses.add(code);
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
            requestArgs.add(args == null ? new LinkedHashMap<String, Object>() : args);
            GameMessage msg = new GameMessage();
            msg.setProto(proto);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", responses.isEmpty() ? 0L : responses.poll());
            msg.setContent(content);
            return msg;
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    static Map<String, Object> area(long id, long petId, long finishTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (petId > 0) m.put("pet_id", petId);
        if (finishTime > 0) m.put("finish_time", finishTime);
        return m;
    }

    static Map<String, Object> adv(long used, Map<String, Object>... areas) {
        Map<String, Object> areaInfo = new LinkedHashMap<>();
        for (Map<String, Object> a : areas) areaInfo.put(String.valueOf(a.get("id")) + ".0", a);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("area_info", areaInfo);
        info.put("daily_adventure_nums", used);
        return mapOf("info", info);
    }

    static Map<String, Object> pet(long id, boolean unlock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("is_unlock", unlock ? "true" : "false");
        return m;
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static <T> List<T> listOf(T... items) {
        List<T> out = new ArrayList<>();
        for (T t : items) out.add(t);
        return out;
    }
}
