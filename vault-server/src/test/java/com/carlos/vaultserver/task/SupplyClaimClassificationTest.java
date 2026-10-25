package com.carlos.vaultserver.task;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-25 P1 修复回归：仓库补给「假的领取成功」。
 *
 * <p>真机监控实证：12/12 次 CLAIMED 中 10 次两个子协议均返回 code=6158（本窗口已领取）、
 * rewards 为空；旧实现用 {@code msg.contains("已领取")} 判 CLAIMED，而 6158 的跳过文案本身就是
 * “本窗口已领取” → 没领到也报领取成功。
 *
 * <p>修复：只有子项真正返回奖励字段（online_reward / spec_reward）才计 CLAIMED；
 * 两子项均为 6158 → SKIPPED。
 */
public class SupplyClaimClassificationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }

    private TaskContext context(CaptureTasksTest.FakeEngine engine) {
        return new TaskContext(engine, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "节奏");
    }

    @Test
    public void bothAlreadyClaimed_isSkippedNotClaimed() throws Exception {
        CaptureTasksTest.FakeEngine engine = new CaptureTasksTest.FakeEngine();
        engine.responses.put("req_receive_online_reward", msg(map("code", 6158)));
        engine.responses.put("req_receive_spec_reward", msg(map("code", 6158)));

        TaskOutcome out = ServerTasks.supplyRewards(context(engine));

        assertEquals("两子项都已领（6158）不能报 CLAIMED",
                TaskOutcome.Category.SKIPPED, out.category);
        assertEquals("无到账证据时 rewards 必须为空", "", out.rewards);
        assertTrue("文案保留业务原因", out.message.contains("本窗口已领取"));
        assertTrue("子项级也应是 skip", out.subResults.get("online").startsWith("skip:"));
        assertTrue("子项级也应是 skip", out.subResults.get("spec").startsWith("skip:"));
        assertFalse(out.category.name().contains("CLAIMED"));
    }

    @Test
    public void onlineReallyClaimed_isClaimedWithRewards() throws Exception {
        CaptureTasksTest.FakeEngine engine = new CaptureTasksTest.FakeEngine();
        engine.responses.put("req_receive_online_reward",
                msg(map("code", 0, "online_reward",
                        java.util.Arrays.asList(map("id", 200401, "count", 5)))));
        engine.responses.put("req_receive_spec_reward", msg(map("code", 6158)));

        TaskOutcome out = ServerTasks.supplyRewards(context(engine));

        assertEquals(TaskOutcome.Category.CLAIMED, out.category);
        assertTrue("领取明细应从 online_reward 解析", out.rewards.contains("物品200401"));
        assertTrue(out.subResults.get("online").startsWith("ok:"));
        assertTrue(out.subResults.get("spec").startsWith("skip:"));
    }

    @Test
    public void bothRewardFieldsEmpty_isSkipped() throws Exception {
        CaptureTasksTest.FakeEngine engine = new CaptureTasksTest.FakeEngine();
        engine.responses.put("req_receive_online_reward", msg(map("code", 0)));
        engine.responses.put("req_receive_spec_reward", msg(map("code", 0)));

        TaskOutcome out = ServerTasks.supplyRewards(context(engine));

        assertEquals("code=0 但无奖励字段 → 暂不可领，不能报 CLAIMED",
                TaskOutcome.Category.SKIPPED, out.category);
        assertEquals("", out.rewards);
    }
}
