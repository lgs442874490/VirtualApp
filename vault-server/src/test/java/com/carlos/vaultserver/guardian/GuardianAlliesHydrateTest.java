package com.carlos.vaultserver.guardian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次 L（2026-10-31）：从登录推送缓存补盟友坐标。
 *
 * <p>生产实证：{@code notify_alliance_member_pos_info} 只在登录握手随 notifies 一次性下发，
 * 守护引擎登录后才 attach → 钩子收不到 → allies 恒空、坐标反查全部 skip。这里验证缓存补链。
 */
public class GuardianAlliesHydrateTest {

    private static final String FPID = "111";
    private static final long SID = 913L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

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
        params.put(ServerGuardianEngine.FN_ID, "{\"sub\":{\"enabled\":true}}");
        a.roleParams.put(String.valueOf(SID), params);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    /** 只提供 notifyData 的最小引擎桩。 */
    private static final class CacheEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();

        CacheEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            return notifies.get(proto);
        }

        @Override
        public String sessionState() {
            return STATE_OFFLINE;
        }
    }

    private static Map<String, Object> posPush() {
        List<Object> members = new ArrayList<>();
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("uid", 1001L);
        m1.put("x", 500L);
        m1.put("y", 600L);
        members.add(m1);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("uid", 1002L);
        m2.put("x", 700L);
        m2.put("y", 800L);
        members.add(m2);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("member_list", members);
        return content;
    }

    @Test
    public void hydratesAlliesFromLoginNotifyCache() {
        CacheEngine engine = new CacheEngine();
        engine.notifies.put(ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, posPush());
        ServerGuardianEngine g = new ServerGuardianEngine(FPID, SID, state, vault);
        try {
            g.hydrateAlliesFromNotify(engine);
            assertEquals("缓存里的 2 个盟友坐标应被解析", 2, g.alliesCount());
            // 坐标反查命中
            assertEquals(1001L, g.allyUidByPoint(500, 600));
            assertEquals(1002L, g.allyUidByPoint(700, 800));
            assertEquals("未命中返回 0", 0L, g.allyUidByPoint(1, 1));
        } finally {
            g.shutdown();
        }
    }

    @Test
    public void emptyCacheKeepsAlliesEmpty_andSecondCallThrottled() {
        CacheEngine engine = new CacheEngine();
        ServerGuardianEngine g = new ServerGuardianEngine(FPID, SID, state, vault);
        try {
            g.hydrateAlliesFromNotify(engine);
            assertEquals(0, g.alliesCount());
            engine.notifies.put(ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, posPush());
            g.hydrateAlliesFromNotify(engine);   // 30s 节流内 → 不重复解析
            assertEquals("节流内不重复解析", 0, g.alliesCount());
        } finally {
            g.shutdown();
        }
    }

    @Test
    public void notifyHookPathStillWorks() {
        CacheEngine engine = new CacheEngine();
        ServerGuardianEngine g = new ServerGuardianEngine(FPID, SID, state, vault);
        try {
            g.onPush(engine, ServerGuardianEngine.PROTO_ALLIANCE_POS_UPDATE, posPush());
            assertEquals(2, g.alliesCount());
        } finally {
            g.shutdown();
        }
    }
}
