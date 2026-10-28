package com.carlos.vaultserver.watchpoint;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 观察点告警过滤单测（2026-10-28 生产误报回归）。
 *
 * <p>生产误报（12:18:53）：{@code 🚨 观察点·来袭 攻击者：酒当歌 行为：集结随从 目标：酒当歌 (493,580)}
 * —— 集结随从自循环行军（起点=终点=自己的城/集结点）且目标城联盟字段缺失（aid=0），
 * 旧过滤「目标联盟已知且非友盟」「攻击者联盟是友盟」都拦不住 → 误报。
 *
 * <p>本测试只覆盖纯函数/配置行为，不连真协议、不启动引擎线程：
 * <ul>
 *   <li>自循环：起点=终点、target.uid==owner → 不告警；</li>
 *   <li>PROTECT：target 缺失 / aid=0 / aid 非友盟 → 不告警；aid∈friendAids 且非自循环 → 告警；</li>
 *   <li>MONITOR：原有同联盟过滤保持，自循环被过滤；</li>
 *   <li>{@code incomingRallyFollowerAlerts=false}：99/100 静默，102/2/1 仍告警；</li>
 *   <li>文案「联盟未知」标注（来袭攻击者行 / 敌情攻击者行 / 敌情目标行）。</li>
 * </ul>
 */
public class WatchPointAlertFilterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long FRIEND_AID = 89700352L;   // yYz（种子 aidLabels）
    private static final long OTHER_AID = 89700704L;    // Hey（非友盟）

    private static Set<Long> friends() {
        Set<Long> s = new LinkedHashSet<>();
        s.add(FRIEND_AID);
        return s;
    }

    private static Map<Long, String> labels() {
        Map<Long, String> m = new LinkedHashMap<>();
        m.put(FRIEND_AID, "yYz");
        m.put(OTHER_AID, "Hey");
        return m;
    }

    // ==================== ① 自循环判定 ====================

    @Test
    public void isSelfMarch_sameStartAndEnd() {
        // 生产 12:18 形态：集结随从自循环（起点=终点=自己的城/集结点）
        assertTrue(WatchPointEngine.isSelfMarch(493, 580, 493, 580, 0L, 12345L));
        assertFalse("起点≠终点不是自循环", WatchPointEngine.isSelfMarch(493, 580, 500, 580, 0L, 12345L));
        // 坐标未知(-1) 不参与起点=终点判定，避免脏数据误杀
        assertFalse(WatchPointEngine.isSelfMarch(-1, -1, -1, -1, 0L, 12345L));
    }

    @Test
    public void isSelfMarch_targetIsOwnCity() {
        assertTrue("目标是发起者自己的城 → 自循环",
                WatchPointEngine.isSelfMarch(100, 200, 300, 400, 12345L, 12345L));
        assertFalse("目标是别人的城", WatchPointEngine.isSelfMarch(100, 200, 300, 400, 999L, 12345L));
        assertFalse("目标城未知(uid=0)且起点≠终点 → 非自循环",
                WatchPointEngine.isSelfMarch(100, 200, 300, 400, 0L, 12345L));
    }

    // ==================== ② PROTECT：已知友盟城才告警 ====================

    @Test
    public void shouldAlertIncoming_onlyKnownFriendCity() {
        assertFalse("target 缺失(aid=0) → 不告警（本次误报场景）",
                WatchPointEngine.shouldAlertIncoming(false, 0L, 0L, friends(), 99L, true));
        assertFalse("目标联盟未知 → 不告警",
                WatchPointEngine.shouldAlertIncoming(false, 0L, 0L, friends(), 100L, true));
        assertFalse("目标是非友盟 → 不告警",
                WatchPointEngine.shouldAlertIncoming(false, 0L, OTHER_AID, friends(), 99L, true));
        assertTrue("目标∈friendAids 且非自循环 → 告警（历史有效样例 yYz/SYD）",
                WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 99L, true));
        assertFalse("自循环即使目标友盟 → 也不告警",
                WatchPointEngine.shouldAlertIncoming(true, 0L, FRIEND_AID, friends(), 99L, true));
        assertFalse("攻击者本身就是友盟（增援/自己人）→ 不告警",
                WatchPointEngine.shouldAlertIncoming(false, FRIEND_AID, FRIEND_AID, friends(), 99L, true));
    }

    // ==================== ③ MONITOR：原有同联盟过滤保持 ====================

    @Test
    public void shouldAlertMonitor_keepsSameAllianceFilter() {
        assertTrue("打不同联盟/未知目标 → 告警", WatchPointEngine.shouldAlertMonitor(OTHER_AID, FRIEND_AID));
        assertTrue("目标联盟未知 → 仍告警（敌情侧不受收紧影响）",
                WatchPointEngine.shouldAlertMonitor(OTHER_AID, 0L));
        assertTrue("攻击者联盟未知 → 仍告警", WatchPointEngine.shouldAlertMonitor(0L, FRIEND_AID));
        assertFalse("同联盟/自家城之间的行军 → 不告警（原有口径）",
                WatchPointEngine.shouldAlertMonitor(FRIEND_AID, FRIEND_AID));
    }

    // ==================== ④ 随从告警开关 ====================

    @Test
    public void rallyFollowerAlertsSwitchFilters99And100Only() {
        // 默认 true：99/100 保留（历史有效预警）
        assertTrue(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 99L, true));
        assertTrue(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 100L, true));
        // false：只静默 99/100，102 主体 / 2 进攻 / 1 侦查仍告警
        assertFalse(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 99L, false));
        assertFalse(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 100L, false));
        assertTrue(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 102L, false));
        assertTrue(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 2L, false));
        assertTrue(WatchPointEngine.shouldAlertIncoming(false, 0L, FRIEND_AID, friends(), 1L, false));
    }

    // ==================== ⑤ 文案「联盟未知」标注 ====================

    @Test
    public void allySuffix_knownAndUnknown() {
        assertEquals(" [yYz]", WatchPointEngine.allySuffix(FRIEND_AID, labels()));
        assertEquals(" [盟999]", WatchPointEngine.allySuffix(999L, labels()));
        assertEquals("（联盟未知）", WatchPointEngine.allySuffix(0L, labels()));
        assertEquals("（联盟未知）", WatchPointEngine.allySuffix(-1L, labels()));
    }

    @Test
    public void incomingAlertText_marksUnknownAlliance() {
        String text = WatchPointEngine.incomingAlertText(
                "酒当歌", 0L, labels(), 99L,
                "莲花emira", FRIEND_AID, 493, 580, 66L, 85442L, "12:18:53");
        assertTrue("攻击者联盟未知必须标注，实际=" + text, text.contains("攻击者：酒当歌（联盟未知）"));
        assertTrue("友盟目标带 [abbr]", text.contains("莲花emira [yYz]"));
        assertTrue(text.contains("集结随从"));
        assertTrue(text.contains("(493,580)"));
        assertTrue(text.contains("ETA：1分6秒"));
        assertTrue(text.contains("兵力约 85442"));

        String known = WatchPointEngine.incomingAlertText(
                "技术分析", OTHER_AID, labels(), 99L,
                "莲花emira", FRIEND_AID, 528, 460, 60L, 0L, "21:34:00");
        assertTrue("已知联盟显示 [标签]，不出现联盟未知", known.contains("攻击者：技术分析 [Hey]"));
        assertFalse(known.contains("（联盟未知）"));
    }

    @Test
    public void outgoingAlertText_marksUnknownAllianceOnAttackerAndTarget() {
        String text = WatchPointEngine.outgoingAlertText(
                "酒当歌", 0L, labels(), 2L, null, 0L,
                500, 600, 120L, 1000L, "12:20:00");
        assertTrue("敌情攻击者联盟未知标注，实际=" + text,
                text.contains("监视目标出兵：酒当歌（联盟未知）"));
        assertTrue("目标城未知时不带联盟标注（仅坐标）", text.contains("目标：(500,600)"));

        String targetUnknown = WatchPointEngine.outgoingAlertText(
                "大陆元帥車宝山", 0L, labels(), 2L, "某城", 0L,
                500, 600, 120L, 0L, "12:20:00");
        assertTrue("敌情目标联盟未知标注，实际=" + targetUnknown,
                targetUnknown.contains("某城（联盟未知）"));
    }

    // ==================== ⑥ 配置开关默认值（watchpoint.json） ====================

    @Test
    public void storeConfig_incomingRallyFollowerAlertsDefaultsTrue() throws Exception {
        WatchPointStore store = new WatchPointStore(tmp.getRoot().toPath());
        store.load();
        assertTrue("默认保留 99/100 集结随从告警（历史有效预警）", store.incomingRallyFollowerAlerts);
        // 落盘后可读回 false
        store.incomingRallyFollowerAlerts = false;
        store.save();
        WatchPointStore reloaded = new WatchPointStore(tmp.getRoot().toPath());
        reloaded.load();
        assertFalse("开关可持久化关闭", reloaded.incomingRallyFollowerAlerts);
        // 既有默认 alertTypes 不允许改动（仍为 1/2/99/100/102）
        assertEquals(new LinkedHashSet<>(java.util.List.of(1L, 2L, 99L, 100L, 102L)),
                reloaded.alertTypes);
    }
}
