package com.carlos.vault.daily;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 行军域资源锁（对标原版 TaskEngine 六种资源锁的端侧最小集，见 design/行军类常驻化与资源锁-立项设计.md）。
 *
 * <p>为什么需要：常驻会话上多个触发源并发（推送唤醒/每日整轮/互助/手动执行），引擎的
 * requestLock 只串行化单条协议请求，挡不住"两个任务先后各派一队兵打同一目标"这类
 * <b>逻辑层竞态</b>——原版用 6 种资源锁 + 竞技场独占窗防的正是这个。
 *
 * <p>端侧最小集 4 种资源：{@link Resource#MARCH 出征通道}、{@link Resource#SOLDIERS 兵力}、
 * {@link Resource#DIAMONDS 钻石}、{@link Resource#ARENA 竞技场独占窗}。
 * 锁按 fpid:sid（角色）全局注册——同一角色不管走在线会话还是临时登录，都进同一张锁表；
 * 不同账号/角色的游戏资源天然隔离，不需要跨角色锁。
 *
 * <p>防死锁：多锁按 {@link Resource} 枚举序固定顺序获取；拿不齐（超时）回滚已持有的锁并让出。
 */
public final class MarchCoordinator {

    /** 资源（原版 6 种 ACCOUNT_ACTION/WORLD_MARCH/FORMATION_HEROES/SOLDIERS/DIAMONDS/CLAIM_IDEMPOTENCY 的端侧合并）。 */
    public enum Resource { MARCH, SOLDIERS, DIAMONDS, ARENA }

    /** 任务 → 资源需求（Boolean=写锁/独占；EnumMap 迭代即枚举序=加锁顺序）。 */
    private static final Map<String, Map<Resource, Boolean>> NEEDS = new ConcurrentHashMap<>();

    static {
        need("gather", Resource.MARCH, false, Resource.SOLDIERS, false);
        need("hunt", Resource.MARCH, false, Resource.SOLDIERS, false);
        need("intel", Resource.MARCH, false, Resource.SOLDIERS, false);
        need("act_icehunter", Resource.MARCH, false, Resource.SOLDIERS, false);
        need("act_mercenary", Resource.MARCH, false, Resource.SOLDIERS, false);
        need("act_truck", Resource.MARCH, false);
        need("act_feast", Resource.MARCH, false);
        need("behemoth", Resource.MARCH, true);       // 发车/上车独占出征通道（原版 behemoth 预约语义）
        need("medic", Resource.SOLDIERS, true);       // 批量治疗独占兵力
        need("barracks", Resource.SOLDIERS, true);    // 收兵/续训独占兵力
        // M3 竞技场独占窗：执行期间其它出征全部让道（原版 ArenaExclusiveGate 的行军域近似）
        need("arena", Resource.ARENA, true, Resource.MARCH, true);
        need("bank", Resource.DIAMONDS, true);        // 存取独占钻石
        need("shop", Resource.DIAMONDS, true);
        need("alliance_shop", Resource.DIAMONDS, true);
        need("act_flamefangs", Resource.DIAMONDS, true);
    }

    private static void need(String fn, Object... pairs) {
        Map<Resource, Boolean> m = new EnumMap<>(Resource.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((Resource) pairs[i], (Boolean) pairs[i + 1]);
        }
        NEEDS.put(fn, m);
    }

    /** 该任务是否受行军域锁约束（不在表内=纯领取类，无需锁）。 */
    public static boolean isGuarded(String fnId) {
        return fnId != null && NEEDS.containsKey(fnId);
    }

    // ---------- 角色（fpid:sid）级单例锁表 ----------

    private static final ConcurrentHashMap<String, MarchCoordinator> REGISTRY = new ConcurrentHashMap<>();

    public static MarchCoordinator forKey(String fpid, long sid) {
        return REGISTRY.computeIfAbsent(fpid + ":" + sid, k -> new MarchCoordinator());
    }

    private final EnumMap<Resource, ReentrantReadWriteLock> locks = new EnumMap<>(Resource.class);

    private MarchCoordinator() {
        for (Resource r : Resource.values()) {
            locks.put(r, new ReentrantReadWriteLock(true));   // 公平锁：先到先得，防推送风暴插队
        }
    }

    /**
     * 按任务需求集拿锁（枚举序加锁防死锁）。
     *
     * @return 持锁句柄（close 即按获取逆序释放）；{@code null}=超时拿不齐已回滚让出，调用方应记
     * SKIPPED（"资源占用中"），下轮触发再试——让出是正常调度语义，不是失败
     */
    public AutoCloseable tryAcquire(String fnId, long timeoutMillis) {
        Map<Resource, Boolean> need = fnId == null ? null : NEEDS.get(fnId);
        if (need == null || need.isEmpty()) {
            return () -> {
            };   // 无需求任务直接放行
        }
        java.util.List<ReentrantReadWriteLock> held = new java.util.ArrayList<>();
        java.util.List<Boolean> heldWrite = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMillis);
        try {
            for (Map.Entry<Resource, Boolean> e : need.entrySet()) {   // EnumMap 迭代=枚举序
                ReentrantReadWriteLock l = locks.get(e.getKey());
                boolean write = Boolean.TRUE.equals(e.getValue());
                long wait = Math.max(1, deadline - System.currentTimeMillis());
                boolean ok = write
                        ? l.writeLock().tryLock(wait, TimeUnit.MILLISECONDS)
                        : l.readLock().tryLock(wait, TimeUnit.MILLISECONDS);
                if (!ok) {
                    for (int i = held.size() - 1; i >= 0; i--) {
                        if (heldWrite.get(i)) held.get(i).writeLock().unlock();
                        else held.get(i).readLock().unlock();
                    }
                    return null;
                }
                held.add(l);
                heldWrite.add(write);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            for (int i = held.size() - 1; i >= 0; i--) {
                if (heldWrite.get(i)) held.get(i).writeLock().unlock();
                else held.get(i).readLock().unlock();
            }
            return null;
        }
        final java.util.List<ReentrantReadWriteLock> fHeld = held;
        final java.util.List<Boolean> fHeldWrite = heldWrite;
        return () -> {
            for (int i = fHeld.size() - 1; i >= 0; i--) {
                if (fHeldWrite.get(i)) fHeld.get(i).writeLock().unlock();
                else fHeld.get(i).readLock().unlock();
            }
        };
    }
}
