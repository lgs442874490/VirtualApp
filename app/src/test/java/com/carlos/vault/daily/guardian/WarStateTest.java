package com.carlos.vault.daily.guardian;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

/**
 * P7 WarState 判定机单测（设计 §6.11 v1.4/v1.5）：
 * H0-H4 硬触发、软评分 ALERT 进出（τ=10min 衰减）、全敌静默/开盾退出（多敌语义）、
 * 人工强制/解除、E1 任务闸 allowlist、信号去重、war.auto/scout_hard 旋钮。
 * 时间全部显式注入（at/now），无真实等待。
 */
public class WarStateTest {

    private static final long M = 60_000L;

    /** 记录进/出/提醒事件的侦听。 */
    private static final class Rec extends WarState.ListenerAdapter {
        int enters, exits, notices;
        WarState.Trigger lastTrigger;
        String lastExitReason, lastExitDetail, lastNotice;

        @Override public void onWarEnter(WarState.Trigger t, String detail) {
            enters++;
            lastTrigger = t;
        }

        @Override public void onWarExit(String reason, String detail) {
            exits++;
            lastExitReason = reason;
            lastExitDetail = detail;
        }

        @Override public void onNotice(String text) {
            notices++;
            lastNotice = text;
        }
    }

    private static WarState.Signal scout(long marchId, long owner, long at) {
        return WarState.Signal.hard("H0:m" + marchId, WarState.Trigger.H0_SCOUT,
                owner, ThreatBoard.Kind.SCOUT, at);
    }

    // ---------------- H0-H4 硬触发 ----------------

    @Test
    public void h0_scout_entersWartime_immediately() {
        WarState ws = new WarState();
        Rec rec = new Rec();
        ws.setListener(rec);
        ws.onScout(101, 900, 1_000_000L);
        assertTrue(ws.wartime());
        assertEquals(WarState.Trigger.H0_SCOUT, ws.reason());
        assertEquals(1, rec.enters);
        assertEquals(1, ws.board().size());
    }

    @Test
    public void h0_sameMarchId_deduped_withinWindow() {
        WarState ws = new WarState();
        Rec rec = new Rec();
        ws.setListener(rec);
        ws.onScout(101, 900, 1_000_000L);
        ws.onScout(101, 900, 1_000_000L + M);   // 同 id 10min 内重复推送：整条信号去重（板也不重复入账）
        assertEquals(1, rec.enters);
        assertEquals(1, ws.board().enemy(900).signalCount);
    }

    @Test
    public void h1_landing_and_battleMail_enterWartime_softOnce() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.alertTh = 35;   // 抬高预警阈值：+30 单条进攻不进 ALERT，隔离本用例的软分噪声
        ws.configure(c);
        Rec rec = new Rec();
        ws.setListener(rec);
        long t0 = 1_000_000L;
        ws.onAttackMarch(201, 900, t0 / 1000 + 600, true, t0);
        assertFalse(ws.wartime());
        assertEquals(1, ws.board().enemy(900).signalCount);   // 板已入账
        ws.onAttackEnded(201, 900, 777, false, t0 + M);   // 落地（remain≤2s）→ H1
        assertTrue(ws.wartime());
        assertEquals(WarState.Trigger.H1_BATTLE, ws.reason());
        // 75s 后战报邮件到（同场战斗）：已在战时进入事件不重复，软 +40 不重复计
        ws.onBattleConfirmed(900, 777, "b1", t0 + M + 75_000L);
        assertEquals(1, rec.enters);
        assertEquals(0, rec.notices);
    }

    @Test
    public void h2_twoConcurrentAttacks_enterWartime() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        ws.onAttackMarch(301, 900, t0 / 1000 + 600, true, t0);
        assertFalse(ws.wartime());
        ws.onAttackMarch(302, 901, t0 / 1000 + 600, true, t0 + 1000);
        assertTrue(ws.wartime());
        assertEquals(WarState.Trigger.H2_MULTI_PRONG, ws.reason());
        assertEquals(2, ws.inFlightAttacks());
    }

    @Test
    public void h3_extremeMarch_enterWartime() {
        WarState ws = new WarState();
        ws.onExtremeMarch(401, 900, 1_000_000L);
        assertTrue(ws.wartime());
        assertEquals(WarState.Trigger.H3_EXTREME, ws.reason());
    }

    @Test
    public void h4_feintBurst_fiveWithin30min_enterWartime() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        // 每次佯攻 = 一条进攻行军 5s 内召回（在途即清，不触 H2 的"2 条并发"）
        for (int i = 0; i < 4; i++) {
            long at = t0 + i * M;
            ws.onAttackMarch(501 + i, 900, at / 1000 + 600, false, at);
            ws.onAttackEnded(501 + i, 900, 777, true, at + 5000);   // 佯攻召回
            assertFalse("前 4 次佯攻不触发", ws.wartime());
        }
        long at5 = t0 + 5 * M;
        ws.onAttackMarch(599, 900, at5 / 1000 + 600, false, at5);
        ws.onAttackEnded(599, 900, 777, true, at5 + 5000);   // 第 5 次 → H4
        assertTrue(ws.wartime());
        assertEquals(WarState.Trigger.H4_FEINT_BURST, ws.reason());
    }

    @Test
    public void h4_feintsBeyondWindow_doNotAccumulate() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        // 5 次佯攻散布在 40min 内（窗口 30min）——任意时刻窗口内不足 5 次，不触发
        for (int i = 0; i < 5; i++) {
            long at = t0 + i * 10 * M;
            ws.onAttackMarch(601 + i, 900, at / 1000 + 600, false, at);
            ws.onAttackEnded(601 + i, 900, 777, true, at + 5000);
        }
        assertFalse(ws.wartime());
    }

    // ---------------- 软评分与 ALERT ----------------

    @Test
    public void softSignals_reachAlert_thenDecayBackToNormal() {
        WarState ws = new WarState();
        Rec rec = new Rec();
        ws.setListener(rec);
        long t0 = 1_000_000L;
        // +30 进攻 + +15 联盟通知 = 45 ≥ 25 → ALERT（非战争态）
        ws.onSignal(WarState.Signal.soft("S30:m1", 30, 900, ThreatBoard.Kind.ATTACK, t0));
        ws.onSignal(WarState.Signal.soft("S15:n1", 15, 901, ThreatBoard.Kind.OTHER, t0 + 1000));
        assertEquals(WarState.State.ALERT, ws.state());
        assertFalse(ws.wartime());
        assertEquals(1, rec.notices);
        // τ=10min：25min 后 45·e^(-2.5)≈3.7 < 10，且静默满 10min → 回 NORMAL
        ws.tick(t0 + 25 * M);
        assertEquals(WarState.State.NORMAL, ws.state());
    }

    @Test
    public void alert_blocked_fromEnteringWartime_bySoft() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        ws.onSignal(WarState.Signal.soft("S30:m1", 30, 900, ThreatBoard.Kind.ATTACK, t0));
        assertEquals(WarState.State.ALERT, ws.state());
        // 新进攻软信号追加仍不越级到战争态
        ws.onSignal(WarState.Signal.soft("S30:m2", 5, 900, ThreatBoard.Kind.ATTACK, t0 + 1000));
        assertEquals(WarState.State.ALERT, ws.state());
    }

    // ---------------- 退出判据 ----------------

    @Test
    public void quietExit_singleEnemy_afterQuietWindow() {
        WarState ws = new WarState();
        Rec rec = new Rec();
        ws.setListener(rec);
        long t0 = 1_000_000L;
        ws.onScout(101, 900, t0);
        assertTrue(ws.wartime());
        ws.tick(t0 + 29 * M);
        assertTrue("29min 不退", ws.wartime());
        ws.tick(t0 + 30 * M);
        assertFalse("静默满 30min 退出", ws.wartime());
        assertEquals(1, rec.exits);
        assertTrue(rec.lastExitReason.contains("静默"));
        assertTrue("归档含敌情板", rec.lastExitDetail.contains("敌情板归档"));
        assertEquals("重入重建空板", 0, ws.board().size());
    }

    @Test
    public void quietExit_multiEnemy_secondEnemyRestartsClock() {
        // 多敌：两个敌人先后侦查，先者满窗、后者未满 → 不退（设计 §6.11.8 用户规则 3）
        WarState ws2 = new WarState();
        long t0 = 1_000_000L;
        ws2.onScout(101, 900, t0);
        ws2.onScout(102, 901, t0 + 29 * M);   // 第 29 分钟第二个敌人，静默从零起算
        ws2.tick(t0 + 30 * M);
        assertTrue("任一未静默则不退", ws2.wartime());
        ws2.tick(t0 + 58 * M);
        assertTrue("901 才静默 29min", ws2.wartime());
        ws2.tick(t0 + 59 * M);
        assertFalse("901 满 30min（29+30）→ 全敌静默退出", ws2.wartime());
    }

    @Test
    public void quietExit_anyNewSignal_resetsClock() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        ws.onScout(101, 900, t0);
        ws.onScout(102, 900, t0 + 25 * M);   // 第 25 分钟同一敌人再侦查 → 计时重置
        ws.tick(t0 + 30 * M);
        assertTrue(ws.wartime());
        ws.tick(t0 + 55 * M);
        assertFalse(ws.wartime());
    }

    @Test
    public void shieldExit_allShielded_andNoInFlight() {
        WarState ws = new WarState();
        Rec rec = new Rec();
        ws.setListener(rec);
        long t0 = 1_000_000L;
        ws.onAttackMarch(201, 900, t0 / 1000 + 600, true, t0);
        ws.onAttackMarch(202, 901, t0 / 1000 + 600, true, t0);
        ws.onExtremeMarch(999, 902, t0);   // 第三个敌人（H3 入战）
        assertTrue(ws.wartime());
        // 只有一个开盾 → 不退
        ws.onEnemyShield(900, true, t0 + M);
        ws.tick(t0 + 2 * M);
        assertTrue(ws.wartime());
        // 全部开盾，但 902 还有在途进攻（H3 行军未解除）→ 不退
        ws.onEnemyShield(901, true, t0 + 2 * M);
        ws.onEnemyShield(902, true, t0 + 2 * M);
        ws.tick(t0 + 3 * M);
        assertTrue("有在途进攻不退", ws.wartime());
        // 在途清零 → 全敌开盾即退
        ws.onAttackEnded(201, 900, 777, false, t0 + 3 * M);   // 201 落地（在途-1）
        ws.onAttackEnded(202, 901, 778, true, t0 + 3 * M);    // 202 召回（在途-1）
        ws.tick(t0 + 4 * M);
        assertFalse(ws.wartime());
        assertTrue(rec.lastExitReason.contains("开盾"));
    }

    @Test
    public void shieldExit_disabled_configNoExit() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.shieldExit = false;
        ws.configure(c);
        long t0 = 1_000_000L;
        ws.onScout(101, 900, t0);
        ws.onEnemyShield(900, true, t0 + M);
        ws.tick(t0 + 2 * M);
        assertTrue("shield_exit=false 不走开盾退出", ws.wartime());
    }

    @Test
    public void unshield_resetsQuietClock_asNewSignal() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        ws.onScout(101, 900, t0);
        ws.onEnemyShield(900, true, t0 + M);      // 开盾（静默）
        ws.onEnemyShield(900, false, t0 + 10 * M); // 撤盾 = 新信号，重置静默计时
        ws.tick(t0 + 30 * M);
        assertTrue("撤盾后静默仅 20min，不退", ws.wartime());
        ws.tick(t0 + 40 * M + 1000);
        assertFalse(ws.wartime());
    }

    // ---------------- 人工通道 ----------------

    @Test
    public void manualForce_lockAndUnlock_highestPriority() {
        WarState ws = new WarState();
        Rec rec = new Rec();
        ws.setListener(rec);
        long t0 = 1_000_000L;
        ws.forceWartime(true, "测试", t0);
        assertTrue(ws.wartime());
        assertTrue(ws.manualLocked());
        assertEquals(WarState.Trigger.MANUAL, ws.reason());
        // 人工锁定期间静默满窗不自动退
        ws.tick(t0 + 60 * M);
        assertTrue(ws.wartime());
        // 解除 → NORMAL；解除后不自动重入（板已归档，无新信号）
        ws.forceWartime(false, "测试", t0 + 61 * M);
        assertFalse(ws.wartime());
        ws.tick(t0 + 120 * M);
        assertFalse(ws.wartime());
        assertEquals(1, rec.enters);
        assertEquals(1, rec.exits);
        // 新 H 信号可重入
        ws.onScout(103, 905, t0 + 130 * M);
        assertTrue(ws.wartime());
        assertEquals(2, rec.enters);
    }

    // ---------------- E1 任务闸与旋钮 ----------------

    @Test
    public void e1_allowlist_gate() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        assertFalse("平时不闸", ws.dailyTaskGated("gather"));
        assertFalse(ws.dailyTaskGated("battle_shield"));
        ws.onScout(101, 900, t0);
        assertTrue("战时 allowlist 外拦截", ws.dailyTaskGated("gather"));
        assertTrue(ws.dailyTaskGated("hunt"));
        assertFalse("战斗支援放行", ws.dailyTaskGated("battle_shield"));
        assertFalse("军医放行", ws.dailyTaskGated("medic"));
    }

    @Test
    public void e1_allowlist_override() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.allowlist = new LinkedHashSet<>(Arrays.asList("gather"));
        ws.configure(c);
        ws.onScout(101, 900, 1_000_000L);
        assertFalse("自定义 allowlist 生效", ws.dailyTaskGated("gather"));
        assertTrue(ws.dailyTaskGated("battle_shield"));
    }

    @Test
    public void autoOff_hardTriggersIgnored_manualStillWorks() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.auto = false;
        ws.configure(c);
        ws.onScout(101, 900, 1_000_000L);
        assertFalse("auto=false 仅人工强制", ws.wartime());
        ws.forceWartime(true, "人工", 1_000_000L + M);
        assertTrue(ws.wartime());
    }

    @Test
    public void scoutHardOff_scoutDegradesToSoft() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.scoutHard = false;
        ws.configure(c);
        long t0 = 1_000_000L;
        ws.onScout(101, 900, t0);
        assertFalse(ws.wartime());
        assertEquals(WarState.State.NORMAL, ws.state());   // +15 < 25
        ws.onScout(102, 900, t0 + 1000);
        ws.onScout(103, 900, t0 + 2000);   // 累计 45 ≥ 25 → 预警
        assertEquals(WarState.State.ALERT, ws.state());
    }

    @Test
    public void friendlyUid_scoutIgnored() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.friendlyUids = new LinkedHashSet<>(Arrays.asList(42L));
        ws.configure(c);
        assertTrue(ws.isFriendlyUid(42L));
        assertFalse(ws.isFriendlyUid(43L));
    }

    @Test
    public void explain_and_boardSummary_shapes() {
        WarState ws = new WarState();
        long t0 = 1_000_000L;
        assertTrue(ws.explain(t0).contains("平时"));
        ws.onScout(101, 900, t0);
        ws.onScout(102, 901, t0 + 1000);
        String ex = ws.explain(t0 + 2000);
        assertTrue(ex.contains("战争态"));
        assertTrue(ex.contains("威胁源×2"));
        assertTrue(ex.contains("最活跃"));
        String sum = ws.board().summary(t0 + 2000, 30 * M);
        assertTrue(sum.contains("静默进度 0/2"));
        assertTrue(ws.board().mostActive().ownerUid == 900 || ws.board().mostActive().ownerUid == 901);
    }

    @Test
    public void durationAlert_firesOnce() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.durationAlertMs = 120 * M;
        ws.configure(c);
        Rec rec = new Rec();
        ws.setListener(rec);
        long t0 = 1_000_000L;
        ws.onScout(101, 900, t0);
        // 持续有信号（敌活跃）→ 静默退出不成立，持续超时提醒才可触达
        ws.onScout(102, 900, t0 + 121 * M);
        ws.tick(t0 + 121 * M);
        assertEquals(1, rec.notices);
        assertTrue(rec.lastNotice.contains("已持续"));
        // 之后不再重复提醒
        ws.tick(t0 + 150 * M);
        assertEquals("只提醒一次", 1, rec.notices);
    }

    @Test
    public void threatBoard_allQuiet_semantics() {
        ThreatBoard b = new ThreatBoard();
        assertTrue("空板=全静默", b.allQuiet(1000, 30 * M));
        assertFalse("空板不满足全开盾", b.allShielded());
        b.onSignal(900, ThreatBoard.Kind.SCOUT, 0);
        assertFalse(b.allQuiet(29 * M, 30 * M));
        assertTrue(b.allQuiet(30 * M, 30 * M));
        assertEquals(1, b.quietCount(30 * M, 30 * M));
        b.onShield(900, true, 0);
        assertTrue(b.enemy(900).shielded);
        assertTrue(b.allShielded());
        assertEquals("开盾不刷新静默计时", 1, b.enemy(900).signalCount);
        b.onShield(900, false, 0);
        assertFalse(b.enemy(900).shielded);
        assertTrue("撤盾=新信号", b.enemy(900).signalCount == 2);
        assertFalse(b.allShielded());
    }

    @Test
    public void configSetters_roundTrip() {
        WarState ws = new WarState();
        WarState.Config c = new WarState.Config();
        c.quietExitMs = 45 * M;
        c.alertTh = 15;
        c.feintBurstCount = 3;
        ws.configure(c);
        long t0 = 1_000_000L;
        // 45min 配置：29min 不退
        ws.onScout(101, 900, t0);
        ws.tick(t0 + 29 * M);
        assertTrue(ws.wartime());
        ws.tick(t0 + 45 * M);
        assertFalse(ws.wartime());
    }
}
