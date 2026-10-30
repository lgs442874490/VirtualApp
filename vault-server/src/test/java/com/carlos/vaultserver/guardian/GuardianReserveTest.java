package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 批次 E：守护预留行军队列（{@link GuardianReserve}）。
 *
 * <p>覆盖：enabled+live→1、disabled→0、dry_run→0、reserve_slots=0/2/clamp、sub/strategy/顶层形状、
 * env VAULT_GUARDIAN_ENABLED/DRY_RUN 覆盖、以及 effectiveMaxQueues 下限保留 1 队采集。
 */
public class GuardianReserveTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void clean() {
        System.clearProperty("VAULT_GUARDIAN_ENABLED");
        System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
    }

    private TaskContext ctx(String guardianJson) {
        Map<String, JSONObject> params = new HashMap<>();
        if (guardianJson != null) params.put("guardian", new JSONObject(guardianJson));
        return new TaskContext(null, FPID, SID,
                new StateStore(tmp.getRoot().toPath()), params, "测试");
    }

    private static final String LIVE = "{\"sub\":{\"enabled\":true,\"dry_run\":false}}";

    @Test
    public void enabledLive_defaultReserveOne() {
        assertEquals(1, GuardianReserve.reserveSlots(ctx(LIVE)));
        assertTrue(ServerGuardianEngine.parseConfig(new JSONObject(LIVE))
                .describe().contains("reserve_slots=1"));
    }

    @Test
    public void disabledOrDryRun_zeroReserve() {
        assertEquals(0, GuardianReserve.reserveSlots(ctx(null)));
        assertEquals(0, GuardianReserve.reserveSlots(ctx("{\"sub\":{\"enabled\":false,\"dry_run\":false}}")));
        assertEquals("dry_run 不预留", 0, GuardianReserve.reserveSlots(
                ctx("{\"sub\":{\"enabled\":true,\"dry_run\":true}}")));
        assertEquals("无 TC", 0, GuardianReserve.reserveSlots(null));
    }

    @Test
    public void reserveSlots_paramAndClamp() {
        assertEquals(2, GuardianReserve.reserveSlots(ctx(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":2}}")));
        assertEquals("0=不预留", 0, GuardianReserve.reserveSlots(ctx(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":0}}")));
        assertEquals("上限 clamp 2", 2, GuardianReserve.reserveSlots(ctx(
                "{\"strategy\":{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":9}}")));
        assertEquals("负数→0", 0, GuardianReserve.reserveSlots(ctx(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":-1}}")));
        assertEquals("非法→默认 1", 1, GuardianReserve.reserveSlots(ctx(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":\"abc\"}}")));

        // 顶层扁平形状
        assertEquals(2, GuardianReserve.reserveSlots(ctx(
                "{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":2}")));
        // sub 优先于 strategy/top（与 parseConfig 的 first(sub,st,top) 同口径）
        assertEquals(1, GuardianReserve.reserveSlots(ctx(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"reserve_slots\":1},"
                        + "\"strategy\":{\"reserve_slots\":2},\"reserve_slots\":2}")));
    }

    @Test
    public void envOverrides_enableDryRun() {
        // 无 App params，仅 env 实盘开启 → 默认预留 1
        System.setProperty("VAULT_GUARDIAN_ENABLED", "1");
        System.setProperty("VAULT_GUARDIAN_DRY_RUN", "0");
        assertEquals(1, GuardianReserve.reserveSlots(ctx(null)));

        System.setProperty("VAULT_GUARDIAN_DRY_RUN", "1");
        assertEquals("env 演练 → 0", 0, GuardianReserve.reserveSlots(ctx(null)));

        System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
        System.setProperty("VAULT_GUARDIAN_ENABLED", "0");
        assertEquals("env 关闭 → 0", 0, GuardianReserve.reserveSlots(ctx(LIVE)));
    }

    @Test
    public void effectiveMaxQueues_keepsAtLeastOneGatherQueue() {
        assertEquals("capacity3/reserve1 → 2", 2, GuardianReserve.effectiveMaxQueues(3, 1));
        assertEquals("reserve0 → 3", 3, GuardianReserve.effectiveMaxQueues(3, 0));
        assertEquals("reserve 超过 capacity → 仍 1", 1, GuardianReserve.effectiveMaxQueues(3, 3));
        assertEquals("reserve2/capacity2 → 1", 1, GuardianReserve.effectiveMaxQueues(2, 2));
        assertEquals("capacity1 保留 1", 1, GuardianReserve.effectiveMaxQueues(1, 2));
        assertEquals("无队列 → 0", 0, GuardianReserve.effectiveMaxQueues(0, 1));
    }
}
