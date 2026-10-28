package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetKey;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;

import org.junit.After;
import org.junit.Test;

/**
 * R2 Phase 2：同账号同服多角色资产库共存回归。
 *
 * <p>背景（生产 2026-10-29 03:xx 实测）：{@code AssetKey.roleKey()} 之前是 {@code fpid:sid}，
 * 888 服两角色（uid 35371154 / 88280792）先后 attach 时后一个会把前一个从 byEngine 移除，
 * 导致先登录角色的 {@code CoreAssetRegistry.get(engine)} 为 null → 采集任务长期
 * “资产库未就绪(missing: registry)” 而跳过。修复：角色主键带 uid。
 */
public class CoreAssetRegistryRoleTest {

    private final CoreAssetRegistry registry = CoreAssetRegistry.global();

    @After
    public void cleanup() {
        registry.clearAll();
    }

    @Test
    public void twoRolesSameServer_coexistByEngine() {
        registry.clearAll();
        WjdrEngine engineA = new WjdrEngine(null);
        WjdrEngine engineB = new WjdrEngine(null);
        CoreAssetLibrary libA = registry.attach(engineA, "48460403", 888L, 35371154L, 1L);
        CoreAssetLibrary libB = registry.attach(engineB, "48460403", 888L, 88280792L, 1L);
        assertNotNull(libA);
        assertNotNull(libB);
        assertNotSame("同 fpid:sid 不同 uid 必须是两个独立资产库", libA, libB);
        assertEquals("角色A 库主键带 uid", "48460403:888:35371154", libA.key().roleKey());
        assertEquals("角色B 库主键带 uid", "48460403:888:88280792", libB.key().roleKey());
        assertNotNull("角色A 的库不能被角色B 顶掉", registry.get(engineA));
        assertNotNull("角色B 的库必须在", registry.get(engineB));
    }

    @Test
    public void detachOneRoleKeepsSibling() {
        registry.clearAll();
        WjdrEngine engineA = new WjdrEngine(null);
        WjdrEngine engineB = new WjdrEngine(null);
        registry.attach(engineA, "48460403", 888L, 35371154L, 1L);
        registry.attach(engineB, "48460403", 888L, 88280792L, 1L);
        registry.detach(engineA);
        assertNull("detach 后 A 不应再有库", registry.get(engineA));
        assertNotNull("兄弟角色 B 的库必须保留", registry.get(engineB));
    }

    @Test
    public void reconnectSameRoleReplacesLibrary() {
        registry.clearAll();
        WjdrEngine engineA = new WjdrEngine(null);
        CoreAssetLibrary first = registry.attach(engineA, "48460403", 888L, 35371154L, 1L);
        CoreAssetLibrary second = registry.attach(engineA, "48460403", 888L, 35371154L, 2L);
        assertNotSame("同角色重连（新 sessionEpoch）应重建库", first, second);
        assertEquals(second, registry.get(engineA));
        assertEquals("角色键含 uid", "48460403:888:35371154", second.key().roleKey());
    }

    @Test
    public void legacyKeyWithoutUidKeepsOldShape() {
        assertEquals("111:888", new AssetKey("111", 888L, 0L, 1L).roleKey());
        assertEquals("111:888", new AssetKey("111", 888L, 0L, 1L).legacyRoleKey());
        assertEquals("111:888:1001", new AssetKey("111", 888L, 1001L, 1L).roleKey());
    }
}
