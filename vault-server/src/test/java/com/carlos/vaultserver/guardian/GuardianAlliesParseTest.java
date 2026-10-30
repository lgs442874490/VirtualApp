package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 批次 H：盟友坐标推送解析（修生产 allies=0）。
 *
 * <p>覆盖：member_list/members/list/data/info/content/member_pos 包装；List/Map 容器；
 * uid/id/player_id；x/y、pos/city_pos/point、[x,y]、"x,y" 字符串；add/update/info upsert；
 * del 删除；空 payload 诊断不崩 + describe alliesLastProto。
 */
public class GuardianAlliesParseTest {

    private static final String FPID = "111";
    private static final long SID = 913L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;
    private final List<ServerGuardianEngine> engines = new ArrayList<>();

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
        vault.load();
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        Map<String, String> params = new LinkedHashMap<>();
        params.put(ServerGuardianEngine.FN_ID,
                "{\"sub\":{\"enabled\":true,\"dry_run\":true}}");
        a.roleParams.put(String.valueOf(SID), params);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    @After
    public void clean() {
        for (ServerGuardianEngine e : engines) {
            try {
                e.shutdown();
            } catch (Throwable ignored) {
            }
        }
        engines.clear();
    }

    private ServerGuardianEngine engine() {
        ServerGuardianEngine e = new ServerGuardianEngine(FPID, SID, state, vault);
        engines.add(e);
        return e;
    }

    private static Map<String, Object> entry(Object uid, Object x, Object y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", uid);
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    private static Map<Long, long[]> parse(Map<String, Object> content) {
        return ServerGuardianEngine.parseMemberPos(content);
    }

    private static void assertPos(Map<Long, long[]> out, long uid, int x, int y) {
        long[] p = out.get(uid);
        assertEquals("缺 uid=" + uid + "；parsed=" + out.keySet(), true, p != null);
        assertEquals(x, p[0]);
        assertEquals(y, p[1]);
    }

    // ---------------- 1. 各顶层包装 + List/Map ----------------

    @Test
    public void wrappers_memberList_members_list_flat() {
        Map<String, Object> memberListMap = new LinkedHashMap<>();
        memberListMap.put("member_list", Map.of("1.0", entry(2001L, 524, 420)));
        assertPos(parse(memberListMap), 2001L, 524, 420);

        Map<String, Object> memberListArr = new LinkedHashMap<>();
        memberListArr.put("member_list", List.of(entry(2002L, 525, 421)));
        assertPos(parse(memberListArr), 2002L, 525, 421);

        Map<String, Object> members = new LinkedHashMap<>();
        members.put("members", Map.of("2.0", entry("2003", "526", "422")));
        assertPos(parse(members), 2003L, 526, 422);

        Map<String, Object> list = new LinkedHashMap<>();
        list.put("list", List.of(entry(2004L, 527, 423)));
        assertPos(parse(list), 2004L, 527, 423);

        // 直接 Map key→元素 / 单条顶层
        assertPos(parse(Map.of("2005", entry(2005L, 528, 424))), 2005L, 528, 424);
        assertPos(parse(entry(2006L, 529, 425)), 2006L, 529, 425);

        // Map key=uid、value=纯坐标（无 uid）{"2007":{"x":..,"y":..}}
        Map<String, Object> keyed = new LinkedHashMap<>();
        keyed.put("member_list", Map.of("2007", Map.of("x", 541, "y", 441)));
        assertPos(parse(keyed), 2007L, 541, 441);
    }

    @Test
    public void wrappers_dataInfoContent_nested() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("data", Map.of("member_list", List.of(entry(2010L, 530, 430))));
        assertPos(parse(data), 2010L, 530, 430);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("info", List.of(entry(2011L, 531, 431)));
        assertPos(parse(info), 2011L, 531, 431);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("content", Map.of("members", Map.of("x", entry(2012L, 532, 432))));
        assertPos(parse(content), 2012L, 532, 432);

        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("member_pos", entry(2013L, 533, 433));
        assertPos(parse(pos), 2013L, 533, 433);
    }

    // ---------------- 2. uid/坐标字段变体 ----------------

    @Test
    public void uidAndCoordinateVariants() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("member_list", List.of(Map.of("id", 2020L, "x", 534L, "y", 434L)));
        assertPos(parse(id), 2020L, 534, 434);

        Map<String, Object> pid = new LinkedHashMap<>();
        pid.put("member_list", List.of(Map.of("player_id", "2021", "x", "535", "y", "435")));
        assertPos(parse(pid), 2021L, 535, 435);

        Map<String, Object> posMap = new LinkedHashMap<>();
        posMap.put("member_list", List.of(
                Map.of("uid", 2022L, "pos", Map.of("x", 536, "y", 436))));
        assertPos(parse(posMap), 2022L, 536, 436);

        Map<String, Object> cityPos = new LinkedHashMap<>();
        cityPos.put("member_list", List.of(
                Map.of("uid", 2023L, "city_pos", Map.of("x", 537, "y", 437))));
        assertPos(parse(cityPos), 2023L, 537, 437);

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("member_list", List.of(
                Map.of("uid", 2024L, "point", List.of(538, 438))));
        assertPos(parse(point), 2024L, 538, 438);

        Map<String, Object> str = new LinkedHashMap<>();
        str.put("member_list", List.of(Map.of("uid", 2025L, "pos", "539,439")));
        assertPos(parse(str), 2025L, 539, 439);

        Map<String, Object> strSpace = new LinkedHashMap<>();
        strSpace.put("member_list", List.of(Map.of("uid", 2026L, "pos", "540 440")));
        assertPos(parse(strSpace), 2026L, 540, 440);
    }

    @Test
    public void multipleEntries_mixedContainersAndWrappers() {
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> memberList = new LinkedHashMap<>();
        memberList.put("1.0", entry(2300L, 542, 442));
        memberList.put("2.0", entry(2301L, 543, 443));
        content.put("data", Map.of("member_list", memberList));
        content.put("info", List.of(entry(2302L, 544, 444)));
        Map<Long, long[]> out = parse(content);
        assertEquals(3, out.size());
        assertPos(out, 2300L, 542, 442);
        assertPos(out, 2301L, 543, 443);
        assertPos(out, 2302L, 544, 444);
    }

    @Test
    public void emptyAndMalformed_noThrow() {
        assertTrue(parse(null).isEmpty());
        assertTrue(parse(Map.of()).isEmpty());
        assertTrue(parse(Map.of("member_list", "bad")).isEmpty());
        assertTrue(parse(Map.of("member_list", List.of(Map.of("x", 1, "y", 2)))).isEmpty());
        assertTrue(parse(Map.of("member_list", List.of(Map.of("uid", 1, "x", 0, "y", 2)))).isEmpty());
    }

    // ---------------- 3. engine：upsert / del / describe / 诊断 ----------------

    @Test
    public void onPush_addUpdateInfo_upsert_andDescribe() {
        ServerGuardianEngine engine = engine();
        engine.attach(new WjdrEngineStub());
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("member_list", Map.of("1.0", entry(2100L, 600, 500)));
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_ADD, add);
        assertEquals(1, engine.allyCount());
        assertTrue(engine.alliesUpdatedAt() > 0L);
        assertTrue(engine.describe().contains(
                "alliesLastProto=" + ServerGuardianEngine.PROTO_ALLIANCE_POS_ADD));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("members", List.of(entry(2100L, 601, 501)));
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_UPDATE, update);
        assertEquals(1, engine.allyCount());
        assertEquals(2100L, engine.allyUidByPoint(601, 501));
        assertTrue(engine.describe().contains(
                "alliesLastProto=" + ServerGuardianEngine.PROTO_ALLIANCE_POS_UPDATE));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("data", Map.of("member_list", List.of(entry(2101L, 602, 502))));
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, info);
        assertEquals(2, engine.allyCount());
    }

    @Test
    public void onPush_del_removesUids() {
        ServerGuardianEngine engine = engine();
        engine.attach(new WjdrEngineStub());
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("member_list", List.of(entry(2200L, 610, 510), entry(2201L, 611, 511)));
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_ADD, add);
        assertEquals(2, engine.allyCount());

        Map<String, Object> del = new LinkedHashMap<>();
        del.put("member_list", Map.of("1.0", Map.of("uid", 2200L)));
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_DEL, del);
        assertEquals(1, engine.allyCount());
        assertEquals(0L, engine.allyUidByPoint(610, 510));
        assertEquals(2201L, engine.allyUidByPoint(611, 511));
        assertTrue(engine.describe().contains(
                "alliesLastProto=" + ServerGuardianEngine.PROTO_ALLIANCE_POS_DEL));
    }

    @Test
    public void onPush_emptyPayload_diagnosticThrottledAndNoBreak() {
        ServerGuardianEngine engine = engine();
        engine.attach(new WjdrEngineStub());
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, Map.of());
        engine.onPush(new WjdrEngineStub(), ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO,
                Map.of("member_list", List.of()));
        assertEquals(0, engine.allyCount());
        assertTrue("空 payload 不更新 allies 但记录最后协议",
                engine.describe().contains("alliesLastProto="
                        + ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO));
        assertTrue("alliesUpdatedAgoMs=-1", engine.describe().contains("alliesUpdatedAgoMs=-1"));
    }

    /** 最小引擎替身（onPush 只需要非 null；坐标推送不请求）。 */
    private static final class WjdrEngineStub extends com.carlos.vault.wjdr.WjdrEngine {
        WjdrEngineStub() {
            super(null);
        }
    }
}
