package com.carlos.vault.wjdr.asset;

import com.carlos.vault.wjdr.WjdrEngine;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资产库注册表（进程级）：按「角色 fpid:sid」与「引擎实例」双索引。
 *
 * <p>接线点：
 * <ul>
 *   <li>vault-server：{@code SessionKeeper.LiveSession} 建会话后 {@link #attach}；断开时 {@link #detach}；</li>
 *   <li>App 原生模式：{@code RoleSessionKeeper} 建会话后 {@link #attach}。</li>
 * </ul>
 *
 * <p>重连/换会话代次：{@link #attach} 发现同角色已有旧库时关闭旧库并新建（init 会重放，
 * 旧快照不可作为决策依据）；任务每轮通过 {@link #get(WjdrEngine)} 取当前库，不长期持有旧引用。
 */
public final class CoreAssetRegistry {

    private static final CoreAssetRegistry GLOBAL = new CoreAssetRegistry();

    private final Map<String, CoreAssetLibrary> byRole = new LinkedHashMap<String, CoreAssetLibrary>();
    private final Map<WjdrEngine, CoreAssetLibrary> byEngine =
            new IdentityHashMap<WjdrEngine, CoreAssetLibrary>();

    public static CoreAssetRegistry global() {
        return GLOBAL;
    }

    /** 幂等 attach：同角色重建时关闭旧库。 */
    public synchronized CoreAssetLibrary attach(WjdrEngine engine, String fpid, long sid, long uid,
                                                long sessionEpoch) {
        if (fpid == null || fpid.isEmpty()) return null;
        AssetKey key = new AssetKey(fpid, sid, uid, sessionEpoch);
        CoreAssetLibrary old = byRole.get(key.roleKey());
        if (old != null) {
            byEngine.values().remove(old);
            old.close();
        }
        CoreAssetLibrary lib = new CoreAssetLibrary(key);
        byRole.put(key.roleKey(), lib);
        if (engine != null) byEngine.put(engine, lib);
        return lib;
    }

    public synchronized CoreAssetLibrary get(WjdrEngine engine) {
        if (engine == null) return null;
        return byEngine.get(engine);
    }

    public synchronized CoreAssetLibrary get(String fpid, long sid) {
        if (fpid == null) return null;
        String fp = fpid.trim();
        CoreAssetLibrary lib = byRole.get(fp + ":" + sid);
        if (lib != null) return lib;
        // R2 Phase 2：同服多角色主键已带 uid，旧的无 uid 查询按前缀回退（仅兼容调用，返回任一角色库）。
        String prefix = fp + ":" + sid + ":";
        for (Map.Entry<String, CoreAssetLibrary> e : byRole.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith(prefix)) return e.getValue();
        }
        return null;
    }

    public synchronized void detach(WjdrEngine engine) {
        CoreAssetLibrary lib = byEngine.remove(engine);
        if (lib == null) return;
        byRole.remove(lib.key().roleKey(), lib);
        lib.close();
    }

    public synchronized void detach(CoreAssetLibrary lib) {
        if (lib == null) return;
        byRole.remove(lib.key().roleKey(), lib);
        for (Map.Entry<WjdrEngine, CoreAssetLibrary> e : byEngine.entrySet()) {
            if (e.getValue() == lib) byEngine.remove(e.getKey());
        }
        lib.close();
    }

    public synchronized void clearAll() {
        for (CoreAssetLibrary lib : byRole.values()) lib.close();
        byRole.clear();
        byEngine.clear();
    }

    public synchronized int size() {
        return byRole.size();
    }
}
